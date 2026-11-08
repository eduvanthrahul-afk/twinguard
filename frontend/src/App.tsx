import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { fetchCarlaStatus, fetchCatalog, fetchFeatureReport, fetchFeatures, fetchGateReport, fetchLatestResults, restartCampaign, startFeatureRun, startRun } from "./api";
import type { CampaignStatus, CarlaStatus, Catalog, FeatureProgress, FeatureReport, FeatureRunRequest, FeatureView, GateReport, HistoryEntry, Progress, RunRequest } from "./api";
import ConfidencePage from "./components/ConfidencePage";
import FaultLabPage from "./components/FaultLabPage";
import FeaturePage from "./components/FeaturePage";
import FeatureRunPage from "./components/FeatureRunPage";
import FmuPage from "./components/FmuPage";
import HistoryPage from "./components/HistoryPage";
import ImportPage from "./components/ImportPage";
import PipelinePage from "./components/PipelinePage";
import ReleasesPage from "./components/ReleasesPage";
import RunPage from "./components/RunPage";
import Sidebar from "./components/Sidebar";
import Workflow from "./components/Workflow";

export type Theme = "light" | "dark";
export type Page = "releases" | "run" | "confidence" | "lab" | "pipeline" | "feature" | "featureRun" | "fmu" | "import" | "history";

export interface FeatureRunState {
  key: string;
  featureId: string;
  title: string;
  summary: string;
  request: FeatureRunRequest;
  runId: string | null;
  running: boolean;
  progress: FeatureProgress | null;
  report: FeatureReport | null;
  error: string | null;
}

export interface RunState {
  targetId: string;
  title: string;
  summary: string;
  request: RunRequest;
  runId: string | null;
  running: boolean;
  progress: Progress | null;
  report: GateReport | null;
  error: string | null;
}

const CARLA_POLL_MS = 5000;

function parseRequest<T>(text: string, fallback: T): T {
  try {
    return JSON.parse(text) as T;
  } catch {
    return fallback;
  }
}

function gateState(entry: HistoryEntry, report: GateReport | null): RunState {
  return {
    targetId: entry.targetId, title: entry.title, summary: entry.summary ?? "",
    request: parseRequest<RunRequest>(entry.request, { kind: "release", id: entry.targetId }),
    runId: entry.id, running: false, progress: null, report, error: report ? null : entry.error ?? "This run has no saved result.",
  };
}

function featureState(entry: HistoryEntry, report: FeatureReport | null): FeatureRunState {
  return {
    key: `${entry.feature}:${entry.targetId}`, featureId: entry.feature ?? "", title: entry.title, summary: entry.summary ?? "",
    request: parseRequest<FeatureRunRequest>(entry.request, { kind: "release", id: entry.targetId }),
    runId: entry.id, running: false, progress: null, report, error: report ? null : entry.error ?? "This run has no saved result.",
  };
}

function storedTheme(): Theme {
  try {
    return localStorage.getItem("twinguard-theme") === "dark" ? "dark" : "light";
  } catch {
    return "light";
  }
}

export default function App() {
  const [mode, setMode] = useState<"guided" | "console">("guided");
  const [page, setPage] = useState<Page>("releases");
  const [theme, setTheme] = useState<Theme>(storedTheme);
  const [catalog, setCatalog] = useState<Catalog | null>(null);
  const [catalogError, setCatalogError] = useState<string | null>(null);
  const [campaign, setCampaign] = useState<CampaignStatus | null>(null);
  const [run, setRun] = useState<RunState | null>(null);
  const [results, setResults] = useState<Record<string, RunState>>({});
  const [carla, setCarla] = useState<CarlaStatus | null>(null);
  const [features, setFeatures] = useState<FeatureView[]>([]);
  const [featureId, setFeatureId] = useState<string | null>(null);
  const [featureRun, setFeatureRun] = useState<FeatureRunState | null>(null);
  const [featureResults, setFeatureResults] = useState<Record<string, FeatureRunState>>({});
  const [fmuError, setFmuError] = useState<string | null>(null);
  const [historyKey, setHistoryKey] = useState(0);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const streamRef = useRef<EventSource | null>(null);
  const featureStreamRef = useRef<EventSource | null>(null);

  useLayoutEffect(() => {
    document.documentElement.dataset.theme = theme;
    try {
      localStorage.setItem("twinguard-theme", theme);
    } catch {
      // The choice simply won't be remembered.
    }
  }, [theme]);

  const loadCatalog = useCallback(() => {
    fetchCatalog().then(setCatalog).catch(() => setCatalogError("Can't reach the TwinGuard server. Check that it's running on port 8787, then reload."));
    fetchFeatures().then(setFeatures).catch(() => setCatalogError("Can't reach the TwinGuard server. Check that it's running on port 8787, then reload."));
  }, []);

  useEffect(loadCatalog, [loadCatalog]);

  // Saved results from earlier sessions fill the release tables, without replacing anything run since the page opened.
  useEffect(() => {
    fetchLatestResults().then((latest) => {
      const gate: Record<string, RunState> = {};
      const feature: Record<string, FeatureRunState> = {};
      for (const { run: entry, report } of latest) {
        if (entry.scope === "gate") gate[entry.targetId] ??= gateState(entry, report as GateReport | null);
        else feature[`${entry.feature}:${entry.targetId}`] ??= featureState(entry, report as FeatureReport | null);
      }
      setResults((now) => ({ ...gate, ...now }));
      setFeatureResults((now) => ({ ...feature, ...now }));
    }).catch(() => {
      // The tables still work. They just start empty.
    });
  }, []);

  useEffect(() => {
    const source = new EventSource("/api/campaign/stream");
    source.addEventListener("status", (e) => setCampaign(JSON.parse((e as MessageEvent).data)));
    source.addEventListener("progress", (e) => {
      const update = JSON.parse((e as MessageEvent).data);
      setCampaign((c) => ({ report: null, codeMutation: null, features: {}, ...c, running: true, error: null, ...update }));
    });
    return () => source.close();
  }, []);

  useEffect(() => () => {
    streamRef.current?.close();
    featureStreamRef.current?.close();
  }, []);

  useEffect(() => {
    if ((run && !run.running) || (featureRun && !featureRun.running)) setHistoryKey((k) => k + 1);
  }, [run?.running, featureRun?.running]);

  useEffect(() => {
    if (featureRun && !featureRun.running) {
      setFeatureResults((all) => (all[featureRun.key] === featureRun ? all : { ...all, [featureRun.key]: featureRun }));
    }
  }, [featureRun]);

  const executeFeature = useCallback(async (feature: FeatureView, targetId: string, title: string, summary: string, request: FeatureRunRequest, fromFmuPage = false) => {
    featureStreamRef.current?.close();
    const key = `${feature.id}:${targetId}`;
    const base: FeatureRunState = { key, featureId: feature.id, title, summary, request, runId: null, running: true, progress: null, report: null, error: null };
    setFmuError(null);
    setFeatureId(feature.id);
    setFeatureRun(base);
    if (!fromFmuPage) setPage("featureRun");
    const settle = (patch: Partial<FeatureRunState>) => setFeatureRun((r) => ({ ...(r ?? base), ...patch }));
    try {
      const runId = await startFeatureRun(feature.id, request);
      setFeatureRun((r) => (r ? { ...r, runId } : r));
      setPage("featureRun");
      const source = new EventSource(`/api/feature-runs/${runId}/stream`);
      featureStreamRef.current = source;
      let finished = false;
      source.addEventListener("progress", (e) => setFeatureRun((r) => (r ? { ...r, progress: JSON.parse((e as MessageEvent).data) } : r)));
      source.addEventListener("complete", (e) => {
        finished = true;
        settle({ runId, running: false, report: JSON.parse((e as MessageEvent).data) });
        source.close();
      });
      source.addEventListener("failed", (e) => {
        finished = true;
        settle({ runId, running: false, error: JSON.parse((e as MessageEvent).data).message });
        source.close();
      });
      source.onerror = () => {
        if (!finished) {
          settle({ runId, running: false, error: "Lost the connection to the server. Check that it's still running, then try again." });
          source.close();
        }
      };
    } catch (e) {
      const message = e instanceof Error ? e.message : "The run could not be started.";
      settle({ running: false, error: message });
      if (fromFmuPage) setFmuError(message);
    }
  }, []);

  useEffect(() => {
    let active = true;
    const check = () => {
      fetchCarlaStatus()
        .then((s) => active && setCarla(s))
        .catch(() => active && setCarla(null));
    };
    check();
    const timer = setInterval(check, CARLA_POLL_MS);
    return () => {
      active = false;
      clearInterval(timer);
    };
  }, []);

  // Finished runs are kept, newest first, for the releases table and the sidebar's recent runs.
  useEffect(() => {
    if (run && !run.running) {
      setResults((all) => (all[run.targetId] === run
        ? all
        : Object.fromEntries([[run.targetId, run], ...Object.entries(all).filter(([id]) => id !== run.targetId)])));
    }
  }, [run]);

  const execute = useCallback(async (targetId: string, title: string, summary: string, request: RunRequest, stayOnError = false) => {
    streamRef.current?.close();
    const base: RunState = { targetId, title, summary, request, runId: null, running: true, progress: null, report: null, error: null };
    setFmuError(null);
    setRun(base);
    if (!stayOnError) setPage("run");
    const settle = (patch: Partial<RunState>) => {
      setRun((r) => ({ ...(r ?? base), ...patch }));
    };
    try {
      const runId = await startRun(request);
      setRun((r) => (r ? { ...r, runId } : r));
      setPage("run");
      const source = new EventSource(`/api/runs/${runId}/stream`);
      streamRef.current = source;
      let finished = false;
      source.addEventListener("progress", (e) => setRun((r) => (r ? { ...r, progress: JSON.parse((e as MessageEvent).data) } : r)));
      source.addEventListener("complete", (e) => {
        finished = true;
        settle({ runId, running: false, report: JSON.parse((e as MessageEvent).data) });
        source.close();
      });
      source.addEventListener("failed", (e) => {
        finished = true;
        settle({ runId, running: false, error: JSON.parse((e as MessageEvent).data).message });
        source.close();
      });
      source.onerror = () => {
        if (!finished) {
          settle({ runId, running: false, error: "Lost the connection to the server. Check that it's still running, then try again." });
          source.close();
        }
      };
    } catch (e) {
      const message = e instanceof Error ? e.message : "The run could not be started.";
      settle({ running: false, error: message });
      if (stayOnError) setFmuError(message);
    }
  }, []);

  const busy = (run?.running ?? false) || (featureRun?.running ?? false);
  const selectedFeature = features.find((f) => f.id === featureId) ?? null;

  const rerunCampaign = useCallback(() => {
    setCampaign((c) => (c ? { ...c, running: true, error: null, phase: "Starting", progress: 0 } : c));
    restartCampaign().catch((e) => setCampaign((c) => (c ? { ...c, running: false, error: e.message } : c)));
  }, []);

  const openHistory = useCallback(async (entry: HistoryEntry) => {
    setHistoryError(null);
    try {
      if (entry.scope === "gate") {
        setRun(gateState(entry, await fetchGateReport(entry.id)));
        setPage("run");
      } else {
        setFeatureRun(featureState(entry, await fetchFeatureReport(entry.id)));
        setFeatureId(entry.feature);
        setPage("featureRun");
      }
    } catch (e) {
      setHistoryError(e instanceof Error ? e.message : "Couldn't open that run.");
    }
  }, []);

  const openFeatureOrBraking = (id: string) => {
    if (id === "braking") setPage("releases");
    else { setFeatureId(id); setPage("feature"); }
  };

  if (mode === "guided") {
    if (catalogError) return <div role="alert" className="m-8 rounded-md bg-danger-bg p-4 text-sm text-danger">{catalogError}</div>;
    if (!catalog) return <p className="p-8 text-sm text-fg-3">Loading…</p>;
    return (
      <Workflow
        releases={catalog.releases}
        features={features}
        run={run}
        results={results}
        featureRun={featureRun}
        featureResults={featureResults}
        onRunFeature={(f, c) => executeFeature(f, c.id, c.title, c.summary, { kind: "release", id: c.id })}
        campaign={campaign}
        theme={theme}
        onToggleTheme={() => setTheme((t) => (t === "light" ? "dark" : "light"))}
        onRun={(r) => execute(r.id, r.title, r.summary, { kind: "release", id: r.id })}
        onOpenConsole={(p) => { setPage(p); setMode("console"); }}
      />
    );
  }

  return (
    <div className="min-h-screen md:grid md:grid-cols-[240px_minmax(0,1fr)]">
      <Sidebar
        page={page}
        featureId={featureId}
        features={features}
        theme={theme}
        campaign={campaign}
        carla={carla}
        run={run}
        results={results}
        busy={busy}
        onNavigate={setPage}
        onOpenFeature={(id) => { setFeatureId(id); setPage("feature"); }}
        onToggleTheme={() => setTheme((t) => (t === "light" ? "dark" : "light"))}
        onGuided={() => setMode("guided")}
        onOpenRun={(state) => { setRun(state); setPage("run"); }}
      />

      <main className="min-w-0">
        {catalogError && <div role="alert" className="m-8 rounded-md bg-danger-bg p-4 text-sm text-danger">{catalogError}</div>}
        {!catalog && !catalogError && <p className="p-8 text-sm text-fg-3">Loading…</p>}
        {catalog && page === "releases" && (
          <ReleasesPage
            releases={catalog.releases}
            results={results}
            activeRun={run}
            onRun={(r) => execute(r.id, r.title, r.summary, { kind: "release", id: r.id })}
            onOpen={(state) => { setRun(state); setPage("run"); }}
            onOpenActive={() => setPage("run")}
            onImport={() => setPage("import")}
          />
        )}
        {catalog && page === "run" && run && (
          <RunPage run={run} theme={theme} onBack={() => setPage("releases")} onRerun={() => execute(run.targetId, run.title, run.summary, run.request)} />
        )}
        {catalog && page === "feature" && selectedFeature && (
          <FeaturePage
            feature={selectedFeature}
            campaign={campaign?.features?.[selectedFeature.id]}
            results={featureResults}
            active={featureRun}
            onRun={(f, targetId, title, summary, request) => executeFeature(f, targetId, title, summary, request)}
            onOpen={(state) => { setFeatureRun(state); setPage("featureRun"); }}
          />
        )}
        {catalog && page === "featureRun" && selectedFeature && featureRun && (
          <FeatureRunPage
            feature={selectedFeature}
            run={featureRun}
            onBack={() => setPage("feature")}
            onRerun={() => executeFeature(selectedFeature, featureRun.report?.targetId ?? featureRun.key.split(":")[1], featureRun.title, featureRun.summary, featureRun.request)}
          />
        )}
        {catalog && page === "fmu" && (
          <FmuPage
            features={features}
            running={busy}
            error={fmuError}
            onRunBraking={(baseline, candidate) => execute(candidate, `${candidate} vs ${baseline}`, "Third-party braking FMU, run in the same virtual car.", { kind: "fmu", baseline, candidate }, true)}
            onRunFeature={(feature, baseline, candidate) => executeFeature(feature, candidate, `${candidate} vs ${baseline}`, "Third-party FMU, run against this feature's safety rules.", { kind: "fmu", baseline, candidate }, true)}
          />
        )}
        {catalog && page === "confidence" && <ConfidencePage status={campaign} features={features} onRerun={rerunCampaign} />}
        {catalog && page === "lab" && (
          <FaultLabPage
            settings={catalog.settings}
            faults={catalog.faults}
            mutants={catalog.mutants}
            running={run?.running ?? false}
            onRun={(id, title, summary, request) => execute(id, title, summary, request)}
          />
        )}
        {catalog && page === "pipeline" && <PipelinePage />}
        {catalog && page === "import" && <ImportPage busy={busy} onChanged={loadCatalog} onOpenFeature={openFeatureOrBraking} />}
        {catalog && page === "history" && (
          <>
            {historyError && <div role="alert" className="m-5 rounded-md bg-danger-bg p-4 text-sm text-danger sm:mx-8">{historyError}</div>}
            <HistoryPage features={features} busy={busy} refreshKey={historyKey} onOpen={openHistory} />
          </>
        )}
      </main>
    </div>
  );
}
