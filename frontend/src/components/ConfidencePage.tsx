import { RotateCw, Search } from "lucide-react";
import { useMemo, useState } from "react";
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { FINDING_LABELS } from "../api";
import type { CampaignStatus, CurvePoint, FaultResult, FeatureView, Score } from "../api";
import CodeMutationSection from "./CodeMutationSection";
import { Button, PageHeader, Section, Segmented, tooltipStyle } from "./ui";

const CLEAR_LEAD = 3;

/** The written conclusion comes from the measured curve, so it can't drift away from the data. */
function readCurve(curve: CurvePoint[]) {
  const first = curve[0];
  const last = curve[curve.length - 1];
  if (!first || !last) return { title: "Detection speed", body: "" };
  const lead = first.changeAware - first.random;
  const finalGap = last.changeAware - last.random;
  const title = lead >= CLEAR_LEAD
    ? Math.abs(finalGap) < CLEAR_LEAD ? "Change-aware testing finds faults sooner, not more" : finalGap >= CLEAR_LEAD ? "Change-aware testing finds faults sooner, and more" : "Change-aware testing starts ahead, then falls behind random"
    : "Change-aware testing shows no clear early lead";
  const searchLead = curve.filter((p) => p.search - p.random >= CLEAR_LEAD).length;
  const search = searchLead >= 2
    ? "Coverage-guided search alone also beats random at several budgets."
    : "Coverage-guided search alone shows no clear gain over random.";
  const why = lead >= CLEAR_LEAD ? " An early lead matters most when each scenario is expensive, such as on hardware rigs." : "";
  return {
    title,
    body: `After ${first.scenarios} scenarios, change-aware testing has found ${first.changeAware}% of faults, against ${first.random}% for random. At ${last.scenarios} scenarios, it's ${last.changeAware}% against ${last.random}%.${why} ${search}`,
  };
}

function Headline({ label, score, note }: { label: string; score: Score; note: string }) {
  return (
    <div className="min-w-0 px-5 py-5 sm:px-8">
      <div className="text-sm text-fg-2">{label}</div>
      <div className="mt-2 text-[34px] font-medium leading-10 tracking-[-0.035em] tabular-nums">{score.percent.toFixed(1)}%</div>
      <div className="num mt-1 text-xs text-fg-2">{score.detected} / {score.total} · 95% range {score.lower95.toFixed(0)}–{score.upper95.toFixed(0)}%</div>
      <div className="relative mt-3 h-1 rounded-full bg-muted" aria-hidden>
        <div className="absolute h-1 rounded-full bg-line-strong" style={{ left: `${score.lower95}%`, width: `${score.upper95 - score.lower95}%` }} />
        <div className="absolute top-[-2px] h-2 w-[2px] bg-fg" style={{ left: `${score.percent}%` }} />
      </div>
      <p className="mt-3 text-xs leading-[18px] text-fg-3">{note}</p>
    </div>
  );
}

function CurveChart({ data }: { data: CurvePoint[] }) {
  const [view, setView] = useState<"chart" | "table">("chart");
  const series = [
    { key: "changeAware" as const, name: "Change-aware", color: "var(--series-v1)" },
    { key: "search" as const, name: "Coverage-guided", color: "var(--series-v2)" },
    { key: "random" as const, name: "Random baseline", color: "var(--series-3)" },
  ];
  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap gap-4 text-xs text-fg-2">
          {series.map((s) => (
            <span key={s.key} className="flex items-center gap-1.5"><span className="h-0.5 w-3" style={{ background: s.color }} /> {s.name}</span>
          ))}
        </div>
        <Segmented value={view} onChange={setView} label="View" options={[{ value: "chart", label: "Chart" }, { value: "table", label: "Table" }]} />
      </div>
      {view === "table" ? (
        <div className="mt-3 overflow-x-auto">
          <table className="num w-full max-w-2xl text-xs">
            <thead className="text-left text-fg-3">
              <tr><th className="py-2 font-medium">Scenarios</th>{series.map((s) => <th key={s.key} className="font-medium">{s.name}</th>)}</tr>
            </thead>
            <tbody>
              {data.map((p) => (
                <tr key={p.scenarios} className="border-t border-line text-fg-2">
                  <td className="py-2">{p.scenarios}</td>{series.map((s) => <td key={s.key}>{p[s.key]}%</td>)}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="mt-3 h-64">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={data} margin={{ top: 8, right: 8, left: -16, bottom: 18 }}>
              <CartesianGrid stroke="var(--border)" vertical={false} />
              <XAxis dataKey="scenarios" type="number" scale="log" domain={[5, 400]} ticks={data.map((p) => p.scenarios)} tick={{ fill: "var(--fg-3)", fontSize: 11 }} tickLine={false} axisLine={{ stroke: "var(--border-strong)" }}
                label={{ value: "Scenarios tested per fault (log scale)", position: "insideBottom", offset: -12, fill: "var(--fg-3)", fontSize: 11 }} />
              <YAxis domain={[0, 100]} ticks={[0, 25, 50, 75, 100]} unit="%" tick={{ fill: "var(--fg-3)", fontSize: 11 }} tickLine={false} axisLine={false} />
              <Tooltip {...tooltipStyle} labelFormatter={(v) => `${v} scenarios`} formatter={(v: number, name: string) => [`${v}%`, name]} />
              {series.map((s) => (
                <Line key={s.key} name={s.name} dataKey={s.key} stroke={s.color} strokeWidth={2} dot={{ r: 3.5, strokeWidth: 1.5, stroke: "var(--bg)", fill: s.color }} isAnimationActive={false} />
              ))}
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  );
}

function survivorText(f: FaultResult) {
  return f.survivorKind === "NO_BEHAVIOUR_CHANGE"
    ? "Braking was identical in every scenario tested. Harmless here, or needs conditions we don't simulate."
    : "Braking changed but stayed within tolerance. A real blind spot of the gate.";
}

function AllFaults({ faults, trials }: { faults: FaultResult[]; trials: number }) {
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<"all" | "caught" | "missed">("all");
  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    return faults.filter((f) => {
      if (filter === "caught" && !f.detected) return false;
      if (filter === "missed" && f.detected) return false;
      return !q || f.title.toLowerCase().includes(q) || f.classLabel.toLowerCase().includes(q);
    });
  }, [faults, query, filter]);
  return (
    <div>
      <div className="flex flex-wrap items-center gap-2">
        <label className="flex h-8 min-w-[220px] items-center gap-2 rounded-md border border-line-strong px-2.5 text-fg-3">
          <Search size={13} />
          <span className="sr-only">Search faults</span>
          <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search faults" className="w-full bg-transparent text-sm text-fg outline-none placeholder:text-fg-3" />
        </label>
        <Segmented value={filter} onChange={setFilter} label="Filter faults" options={[{ value: "all", label: "All" }, { value: "caught", label: "Caught" }, { value: "missed", label: "Missed" }]} />
        <span className="num ml-auto text-xs text-fg-3">{rows.length} faults</span>
      </div>
      <div className="mt-3 max-h-[420px] overflow-auto">
        <table className="w-full min-w-[720px] text-sm">
          <thead className="sticky top-0 bg-canvas text-left text-xs text-fg-3">
            <tr className="border-b border-line">
              <th className="py-2 pr-4 font-medium">Fault</th><th className="pr-4 font-medium">Class</th><th className="pr-4 font-medium">Result</th><th className="pr-4 font-medium">First finding</th><th className="text-right font-medium">Scenarios to catch</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((f) => (
              <tr key={f.id} className="border-b border-line">
                <td className="py-2 pr-4">{f.title}{f.holdout && <span className="ml-2 rounded bg-muted px-1.5 py-0.5 text-[11px] text-fg-2">Held out</span>}</td>
                <td className="pr-4 text-fg-2">{f.classLabel}</td>
                <td className="whitespace-nowrap pr-4"><span className={f.detected ? "text-ok" : "text-fg-3"}>{f.detected ? "Caught" : "Missed"}</span> <span className="num text-xs text-fg-3">{f.detectedTrials}/{trials}</span></td>
                <td className="pr-4 text-fg-2">{f.firstFinding ? FINDING_LABELS[f.firstFinding] ?? f.firstFinding : "–"}</td>
                <td className="num text-right text-fg-2">{f.meanScenariosToDetect ?? "–"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function AllFeatures({ status, features }: { status: CampaignStatus; features: FeatureView[] }) {
  const braking = status.report && status.codeMutation ? {
    name: "Collision braking",
    mutants: status.codeMutation.overall,
    random: status.codeMutation.randomBaseline,
    faults: status.report.overall,
    alarms: status.report.controls.reduce((sum, c) => sum + c.falseAlarms, 0),
    scenarios: status.report.controls.reduce((sum, c) => sum + c.scenarios, 0),
  } : null;
  const rows = [
    ...(braking ? [braking] : []),
    ...features.flatMap((f) => {
      const c = status.features?.[f.id];
      return c ? [{ name: f.name, mutants: c.mutants, random: c.mutantsRandom, faults: c.faults, alarms: c.falseAlarms, scenarios: c.controlScenarios }] : [];
    }),
  ];
  const pending = features.length + 1 - rows.length;
  return (
    <Section title="All features" description="The same process, applied to each feature. Code mutants are created by a tool, and designed faults are chosen by us, so the mutant score is the harder test.">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[640px] text-sm">
          <thead className="text-left text-xs text-fg-3">
            <tr className="border-b border-line">
              <th className="py-2 pr-4 font-medium">Feature</th>
              <th className="pr-4 text-right font-medium">Code mutants caught</th>
              <th className="pr-4 text-right font-medium">Random, same budget</th>
              <th className="pr-4 text-right font-medium">Designed faults caught</th>
              <th className="text-right font-medium">False alarms</th>
            </tr>
          </thead>
          <tbody className="tabular-nums">
            {rows.map((r) => (
              <tr key={r.name} className="border-b border-line last:border-b-0">
                <td className="py-2.5 pr-4 font-medium">{r.name}</td>
                <td className="pr-4 text-right">{r.mutants.detected}/{r.mutants.total} · {r.mutants.percent.toFixed(0)}%</td>
                <td className="pr-4 text-right text-fg-2">{r.random.detected}/{r.random.total} · {r.random.percent.toFixed(0)}%</td>
                <td className="pr-4 text-right text-fg-2">{r.faults.detected}/{r.faults.total} · {r.faults.percent.toFixed(0)}%</td>
                <td className="text-right text-fg-2">{r.alarms} in {r.scenarios.toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {pending > 0 && <p className="mt-3 text-xs text-fg-3">{status.phase}: {pending} still being measured.</p>}
    </Section>
  );
}

export default function ConfidencePage({ status, features, onRerun }: { status: CampaignStatus | null; features: FeatureView[]; onRerun: () => void }) {
  const [scope, setScope] = useState<"holdout" | "all">("holdout");
  const report = status?.report;

  const action = (
    <Button disabled={status?.running} onClick={onRerun}>
      <RotateCw size={13} className={status?.running ? "spin" : ""} />
      {status?.running ? "Measuring" : "Run campaign"}
    </Button>
  );

  if (!report) {
    return (
      <>
        <PageHeader title="Confidence" description="How far you can trust a passing result, measured by injecting known bugs." actions={status?.error ? action : undefined} />
        <div className="px-5 py-8 sm:px-8" aria-live="polite">
          {status?.error
            ? <p role="alert" className="rounded-md bg-danger-bg p-4 text-sm text-danger">{status.error}</p>
            : (
              <div className="max-w-md">
                <p className="text-sm text-fg-2">{status?.phase ?? "Connecting"}…</p>
                <div className="mt-2 h-0.5 bg-muted"><div className="h-0.5 bg-fg transition-all" style={{ width: `${(status?.progress ?? 0) * 100}%` }} /></div>
              </div>
            )}
        </div>
      </>
    );
  }

  const curve = scope === "holdout" ? report.holdoutCurve : report.curve;
  const reading = readCurve(curve);
  const survivors = report.faults.filter((f) => !f.detected);
  const falseAlarms = report.controls.reduce((sum, c) => sum + c.falseAlarms, 0);
  const controlScenarios = report.controls.reduce((sum, c) => sum + c.scenarios, 0);
  const hardened = report.faults.filter((f) => report.hardenedFaults.includes(f.id)).map((f) => f.title);

  return (
    <>
      <PageHeader
        title="Confidence"
        description={`How far you can trust a passing result. We inject ${report.totalFaults} known bugs across ${report.trials} independent runs of up to ${report.budget} scenarios. A fault counts as caught only if most runs find it.`}
        actions={action}
      />

      {status && <AllFeatures status={status} features={features} />}

      <div className="border-b border-line px-5 pb-1 pt-6 sm:px-8">
        <h2 className="text-base font-semibold tracking-[-0.01em]">Collision braking in detail</h2>
      </div>
      <div className="grid border-b border-line sm:grid-cols-2 sm:divide-x sm:divide-line xl:grid-cols-4">
        <Headline label="Generated faults caught" score={report.overall} note="Faults we designed, including some that may be harmless. Read this next to the code mutant score below." />
        <Headline label="Held-out faults caught" score={report.holdout} note="Never fed back into the test corpus. Few faults, so a wide range." />
        <Headline label="Excluding no-change faults" score={report.excludingNoBehaviourChange} note={`Leaves out ${report.noBehaviourChangeFaults} faults that never changed braking in any test. Quote only beside the main score.`} />
        <div className="min-w-0 px-5 py-5 sm:px-8">
          <div className="text-sm text-fg-2">False alarms on safe releases</div>
          <div className="mt-2 text-[34px] font-medium leading-10 tracking-[-0.035em] tabular-nums">{falseAlarms}</div>
          <div className="num mt-1 text-xs text-fg-2">in {controlScenarios.toLocaleString()} scenarios · {report.controls.length} controls</div>
          <p className="mt-6 text-xs leading-[18px] text-fg-3">A gate that blocks everything would catch every fault, so it must also pass safe releases.</p>
        </div>
      </div>

      <CodeMutationSection report={status?.codeMutation ?? null} />

      <Section
        title={reading.title}
        description={reading.body}
        actions={<Segmented value={scope} onChange={setScope} label="Fault set" options={[{ value: "holdout", label: "Held-out faults" }, { value: "all", label: "All faults" }]} />}
      >
        <CurveChart data={curve} />
      </Section>

      <div className="grid border-b border-line xl:grid-cols-2 xl:divide-x xl:divide-line">
        <div className="min-w-0 px-5 py-6 sm:px-8">
          <h2 className="text-base font-semibold tracking-[-0.01em]">Score by fault class</h2>
          <p className="mt-1 text-sm text-fg-2">Small classes have wide ranges, so read them as a direction.</p>
          <div className="mt-4 overflow-x-auto">
            <table className="w-full min-w-[440px] text-sm">
              <thead className="text-left text-xs text-fg-3">
                <tr className="border-b border-line"><th className="py-2 pr-4 font-medium">Class</th><th className="pr-4 font-medium">Caught</th><th className="w-[30%] pr-4 font-medium"><span className="sr-only">Share caught</span></th><th className="text-right font-medium">95% range</th></tr>
              </thead>
              <tbody>
                {report.byClass.map((c) => (
                  <tr key={c.faultClass} className="border-b border-line last:border-b-0">
                    <td className="py-2.5 pr-4">{c.label}</td>
                    <td className="num whitespace-nowrap pr-4 text-xs text-fg-2">{c.score.detected}/{c.score.total}</td>
                    <td className="pr-4"><div className="h-1 rounded-full bg-muted"><div className="h-1 rounded-full bg-fg" style={{ width: `${c.score.percent}%` }} /></div></td>
                    <td className="num whitespace-nowrap text-right text-xs text-fg-2">{c.score.lower95.toFixed(0)}–{c.score.upper95.toFixed(0)}%</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
        <div className="min-w-0 border-t border-line px-5 py-6 sm:px-8 xl:border-t-0">
          <h2 className="text-base font-semibold tracking-[-0.01em]">What it missed, and why</h2>
          <p className="mt-1 text-sm text-fg-2">
            {survivors.length} of {report.totalFaults} faults survived.
            {hardened.length > 0 && ` A deeper search added ${hardened.length === 1 ? "a scenario" : `${hardened.length} scenarios`} to the test corpus, catching ${hardened.join(", ")}.`}
          </p>
          <ul className="mt-4 max-h-[300px] overflow-y-auto border-t border-line">
            {survivors.map((f) => (
              <li key={f.id} className="border-b border-line py-2.5 last:border-b-0">
                <div className="text-sm font-medium">{f.title}</div>
                <div className="mt-0.5 text-xs leading-[18px] text-fg-2">{survivorText(f)}</div>
              </li>
            ))}
          </ul>
        </div>
      </div>

      <Section title="All injected faults"><AllFaults faults={report.faults} trials={report.trials} /></Section>

      <p className="px-5 py-4 text-xs leading-[18px] text-fg-3 tabular-nums sm:px-8">
        Measured live on this machine: {report.simulations.toLocaleString()} simulations in {(report.computeMs / 1000).toFixed(1)} s. Scores apply to this fault set and this simplified simulator, not to all real-world bugs. Held-out faults never built the corpus, but the generator and checks were revised while their results were visible, so this isn't a formally blind test.
      </p>
    </>
  );
}
