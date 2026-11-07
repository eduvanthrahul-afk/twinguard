import { ArrowLeft, ArrowRight, BatteryCharging, Check, Code2, FileCheck2, Footprints, Gauge, Lock, Moon, RotateCw, Route, ShieldAlert, ShieldCheck, ShieldX, Sun, TriangleAlert } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import type { FeatureRunState, Page, RunState, Theme } from "../App";
import type { CampaignStatus, FeatureView, ReleaseView, Score, Verdict } from "../api";
import CarlaRecheck from "./CarlaRecheck";
import CoverageMap from "./CoverageMap";
import { FeatureCoverage, FeatureFindings } from "./FeatureRunPage";
import { CheckRow, Findings } from "./RunPage";
import { Button, Metric, StatusPill } from "./ui";

const STEPS = [
  { title: "Choose update", hint: "Pick the OTA candidate" },
  { title: "Review change", hint: "What differs from v1" },
  { title: "Shadow test", hint: "v1 and v2 side by side" },
  { title: "Verdict", hint: "Ship or stop" },
  { title: "Trust & ship", hint: "How sure are we?" },
] as const;

const BRAKING = "braking";
const FEATURE_ICONS: Record<string, typeof Gauge> = { [BRAKING]: ShieldAlert, battery: BatteryCharging, cruise: Gauge, lane: Route, pedestrian: Footprints };

/** One OTA candidate, whichever feature it belongs to. */
interface Candidate {
  id: string;
  title: string;
  summary: string;
  component: string;
  changedSettings: string[];
  intent: string;
}

/** The parts of a run the wizard needs, the same for braking and plugin features. */
interface RunView {
  running: boolean;
  error: string | null;
  done: boolean;
  verdict: Verdict | null;
  reason: string;
  targetTitle: string;
  legacyPassed: number;
  legacyTotal: number;
  evaluations: number;
  failing: number;
  progressDone: number;
  progressBudget: number;
  progressFailures: number;
  coveredCells: number;
  totalCells: number;
  evidenceUrl: string | null;
}

function brakingView(r: RunState): RunView {
  const rep = r.report;
  const p = r.progress;
  return {
    running: r.running, error: r.error, done: !!rep, verdict: rep?.verdict ?? null, reason: rep?.reason ?? "", targetTitle: rep?.targetTitle ?? r.title,
    legacyPassed: rep?.legacy.passed ?? 0, legacyTotal: rep?.legacy.total ?? 0, evaluations: rep?.evaluations ?? 0, failing: rep?.failingScenarios ?? 0,
    progressDone: rep?.evaluations ?? p?.done ?? 0, progressBudget: p?.budget ?? 1, progressFailures: rep?.failingScenarios ?? p?.failures ?? 0,
    coveredCells: rep?.coveredCells ?? p?.coveredCells ?? 0, totalCells: rep?.totalCells ?? p?.totalCells ?? 0,
    evidenceUrl: r.runId ? `/api/runs/${r.runId}` : null,
  };
}

function featureView(r: FeatureRunState): RunView {
  const rep = r.report;
  const p = r.progress;
  return {
    running: r.running, error: r.error, done: !!rep, verdict: rep?.verdict ?? null, reason: rep?.reason ?? "", targetTitle: rep?.targetTitle ?? r.title,
    legacyPassed: rep?.conventionalPassed ?? 0, legacyTotal: rep?.conventionalTotal ?? 0, evaluations: rep?.evaluations ?? 0, failing: rep?.failingScenarios ?? 0,
    progressDone: rep?.evaluations ?? p?.done ?? 0, progressBudget: p?.budget ?? 1, progressFailures: rep?.failingScenarios ?? p?.failures ?? 0,
    coveredCells: rep?.coveredCells ?? p?.coveredCells ?? 0, totalCells: rep?.totalCells ?? p?.totalCells ?? 64,
    evidenceUrl: r.runId ? `/api/feature-runs/${r.runId}` : null,
  };
}

interface Props {
  releases: ReleaseView[];
  features: FeatureView[];
  run: RunState | null;
  results: Record<string, RunState>;
  featureRun: FeatureRunState | null;
  featureResults: Record<string, FeatureRunState>;
  campaign: CampaignStatus | null;
  theme: Theme;
  onToggleTheme: () => void;
  onRun: (release: ReleaseView) => void;
  onRunFeature: (feature: FeatureView, candidate: Candidate) => void;
  onOpenConsole: (page: Page) => void;
}

function Logo() {
  return (
    <span aria-hidden className="flex h-[22px] w-[22px] items-center justify-center rounded-[5px] bg-fg">
      <svg viewBox="0 0 20 20" className="h-3.5 w-3.5" style={{ color: "var(--bg)" }}>
        <rect x="1.5" y="1.5" width="11" height="11" rx="2.5" fill="none" stroke="currentColor" strokeWidth="2" />
        <rect x="7.5" y="7.5" width="11" height="11" rx="2.5" fill="currentColor" />
      </svg>
    </span>
  );
}

function Stepper({ step, unlocked, onGo }: { step: number; unlocked: number; onGo: (i: number) => void }) {
  return (
    <ol className="flex gap-1 overflow-x-auto px-4 py-3 sm:px-8">
      {STEPS.map((s, i) => {
        const done = i < step && i <= unlocked;
        const active = i === step;
        const locked = i > unlocked;
        return (
          <li key={s.title} className="flex min-w-[150px] flex-1 items-center gap-2">
            <button
              type="button"
              disabled={locked}
              aria-current={active ? "step" : undefined}
              onClick={() => onGo(i)}
              className={`flex min-w-0 flex-1 items-center gap-2.5 rounded-md px-2 py-1.5 text-left transition-colors ${active ? "bg-muted" : locked ? "cursor-not-allowed opacity-45" : "hover:bg-subtle"}`}
            >
              <span className={`num flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-xs font-medium ${active ? "bg-fg text-canvas" : done ? "bg-ok-bg text-ok" : "border border-line-strong text-fg-3"}`}>
                {done ? <Check size={13} strokeWidth={3} /> : locked ? <Lock size={11} /> : String(i + 1).padStart(2, "0")}
              </span>
              <span className="min-w-0">
                <span className={`block truncate text-[13px] font-medium ${active ? "text-fg" : "text-fg-2"}`}>{s.title}</span>
                <span className="block truncate text-xs text-fg-3">{s.hint}</span>
              </span>
            </button>
            {i < STEPS.length - 1 && <span aria-hidden className={`hidden h-px w-5 shrink-0 lg:block ${i < unlocked ? "bg-ok" : "bg-line-strong"}`} />}
          </li>
        );
      })}
    </ol>
  );
}

function StepIntro({ index, title, children }: { index: number; title: string; children: ReactNode }) {
  return (
    <div className="mb-6">
      <div className="num text-xs text-fg-3">STEP {String(index + 1).padStart(2, "0")} / {String(STEPS.length).padStart(2, "0")}</div>
      <h1 className="mt-1 text-[24px] font-semibold leading-8 tracking-[-0.02em]">{title}</h1>
      <p className="mt-1.5 max-w-[70ch] text-base text-fg-2">{children}</p>
    </div>
  );
}

function Card({ children, className = "" }: { children: ReactNode; className?: string }) {
  return <div className={`rounded-lg border border-line bg-canvas p-5 ${className}`}>{children}</div>;
}

interface ChooseProps {
  tracks: { id: string; name: string; summary: string }[];
  track: string;
  onTrack: (id: string) => void;
  candidates: Candidate[];
  verdictOf: (id: string) => Verdict | null;
  selected: string | null;
  onSelect: (id: string) => void;
  onOpenConsole: (p: Page) => void;
}

function ChooseStep({ tracks, track, onTrack, candidates, verdictOf, selected, onSelect, onOpenConsole }: ChooseProps) {
  const current = tracks.find((t) => t.id === track);
  return (
    <>
      <StepIntro index={0} title="Which OTA update do you want to check?">
        The approved v1 software is already on the fleet. Pick a vehicle feature, then a candidate update. TwinGuard decides whether it is safe to send to every car.
      </StepIntro>

      <div className="mb-2 text-sm font-medium">1. Vehicle feature</div>
      <div role="radiogroup" aria-label="Vehicle feature" className="flex flex-wrap gap-2">
        {tracks.map((t) => {
          const Icon = FEATURE_ICONS[t.id] ?? Gauge;
          const on = t.id === track;
          return (
            <button
              key={t.id}
              type="button"
              role="radio"
              aria-checked={on}
              onClick={() => onTrack(t.id)}
              className={`inline-flex h-9 items-center gap-2 rounded-full border px-3.5 text-sm font-medium transition-colors ${on ? "border-fg bg-fg text-canvas" : "border-line-strong text-fg-2 hover:bg-muted hover:text-fg"}`}
            >
              <Icon size={15} strokeWidth={1.8} /> {t.name}
            </button>
          );
        })}
      </div>
      {current && <p className="mt-2 max-w-[80ch] text-sm text-fg-3">{current.summary}</p>}

      <div className="mb-2 mt-6 text-sm font-medium">2. Candidate update</div>
      <div role="radiogroup" aria-label="OTA candidate" className="grid gap-3 md:grid-cols-2">
        {candidates.map((r) => {
          const on = r.id === selected;
          const verdict = verdictOf(r.id);
          return (
            <button
              key={r.id}
              type="button"
              role="radio"
              aria-checked={on}
              onClick={() => onSelect(r.id)}
              className={`rounded-lg border p-5 text-left transition-all ${on ? "border-fg bg-subtle shadow-[0_0_0_1px_var(--fg)]" : "border-line hover:border-line-strong hover:bg-subtle"}`}
            >
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <div className="text-xs text-fg-3">{r.component}</div>
                  <div className="mt-0.5 text-[15px] font-medium">{r.title}</div>
                </div>
                <span className={`mt-1 flex h-4 w-4 shrink-0 items-center justify-center rounded-full border ${on ? "border-fg bg-fg" : "border-line-strong"}`}>
                  {on && <span className="h-1.5 w-1.5 rounded-full bg-canvas" />}
                </span>
              </div>
              <p className="mt-2 text-sm text-fg-2">{r.summary}</p>
              <div className="mt-3 flex flex-wrap items-center gap-1.5">
                {r.changedSettings.map((s) => <code key={s} className="num rounded bg-muted px-1.5 py-0.5 text-xs text-fg-2">{s}</code>)}
                {verdict && <span className="ml-auto"><StatusPill status={verdict} /></span>}
              </div>
            </button>
          );
        })}
      </div>
      <p className="mt-5 text-sm text-fg-3">
        Testing a supplier's controller or your own bug instead?{" "}
        <button type="button" className="font-medium text-fg-2 underline underline-offset-2 hover:text-fg" onClick={() => onOpenConsole("fmu")}>Upload an FMU</button>
        {" or "}
        <button type="button" className="font-medium text-fg-2 underline underline-offset-2 hover:text-fg" onClick={() => onOpenConsole("lab")}>open the Fault Lab</button>.
      </p>
    </>
  );
}

function ReviewStep({ candidate, featureName }: { candidate: Candidate; featureName: string }) {
  const plan = [
    ["Conventional tests", "The existing fixed regression suite, the way most teams test today."],
    ["Shadow run", "v1 and the update run the same simulated scenarios with the same sensor noise, so any difference comes from the update."],
    ["Smart search", "Scenarios near a safety limit, or linked to the settings this update changed, are explored first."],
    ["Confirm & shrink", "A finding must repeat on more than one noise seed, then is shrunk to the simplest failing case."],
  ];
  return (
    <>
      <StepIntro index={1} title="Here is what this update changes">
        Check the difference from the approved {featureName.toLowerCase()} software. Changes the update declares on purpose are allowed. Anything else counts as a regression.
      </StepIntro>
      <div className="grid gap-3 md:grid-cols-2">
        <Card>
          <div className="text-xs text-fg-3">On the fleet now</div>
          <div className="mt-0.5 text-[15px] font-medium">Approved v1</div>
          <p className="mt-2 text-sm text-fg-2">The baseline every result is compared with.</p>
        </Card>
        <Card className="border-fg">
          <div className="text-xs text-fg-3">Candidate · {candidate.component}</div>
          <div className="mt-0.5 text-[15px] font-medium">{candidate.title}</div>
          <p className="mt-2 text-sm text-fg-2">{candidate.summary}</p>
        </Card>
      </div>
      <Card className="mt-3">
        <div className="text-sm font-medium">Changed settings</div>
        <div className="mt-2 flex flex-wrap gap-1.5">
          {candidate.changedSettings.map((s) => <code key={s} className="num rounded bg-muted px-2 py-1 text-xs">{s}</code>)}
        </div>
        <div className="mt-4 text-sm font-medium">Declared on purpose (change manifest)</div>
        <p className="mt-1 text-sm text-fg-2">{candidate.intent || "Nothing. This update says the feature should behave exactly like v1."}</p>
        <p className="mt-3 text-xs text-fg-3">The feature's fixed safety rules can never be waived by a manifest.</p>
      </Card>
      <div className="mt-6 text-sm font-medium">When you start, TwinGuard will</div>
      <ol className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        {plan.map(([t, d], i) => (
          <li key={t} className="rounded-lg bg-subtle p-4">
            <div className="num text-xs text-fg-3">{i + 1}</div>
            <div className="mt-1 text-sm font-medium">{t}</div>
            <p className="mt-1 text-xs text-fg-2">{d}</p>
          </li>
        ))}
      </ol>
    </>
  );
}

function TestStep({ view, coverage, onRetry }: { view: RunView; coverage: ReactNode; onRetry: () => void }) {
  const percent = view.done ? 100 : Math.min(100, (view.progressDone / view.progressBudget) * 100);
  const legacyOk = view.legacyPassed === view.legacyTotal;
  return (
    <>
      <StepIntro index={2} title={view.running ? "Shadow testing the update…" : view.error ? "The test stopped" : "Shadow test complete"}>
        {view.running ? "Thousands of scenarios are running on both versions. The map fills in as the test space is explored." : view.error ? "Nothing was decided. Check the server, then try again." : "Every scenario has been run and every finding confirmed. Continue to see the verdict."}
      </StepIntro>
      {view.error ? (
        <div role="alert" className="flex flex-wrap items-center justify-between gap-3 rounded-lg bg-danger-bg p-4 text-sm text-danger">
          {view.error}
          <Button onClick={onRetry}><RotateCw size={13} /> Try again</Button>
        </div>
      ) : (
        <>
          <div className="h-1.5 overflow-hidden rounded-full bg-muted" role="progressbar" aria-valuenow={Math.round(percent)} aria-valuemin={0} aria-valuemax={100}>
            <div className="h-full rounded-full bg-fg transition-all duration-200" style={{ width: `${percent}%` }} />
          </div>
          <div className="num mt-1.5 text-right text-xs text-fg-3">{Math.round(percent)}%</div>
          <div className="mt-3 grid gap-3 lg:grid-cols-2">
            <Card>
              <CheckRow state={!view.done ? "running" : legacyOk ? "pass" : "fail"} name="Conventional test suite" detail={view.done ? `${view.legacyPassed} of ${view.legacyTotal} passed` : "Running"} />
              <CheckRow
                state={!view.done ? "running" : view.failing > 0 ? "fail" : "pass"}
                name="TwinGuard differential gate"
                detail={view.done ? `${view.failing.toLocaleString()} of ${view.evaluations.toLocaleString()} failed` : `${view.progressDone.toLocaleString()} run, ${view.progressFailures.toLocaleString()} failed`}
              />
            </Card>
            <Card className="grid grid-cols-3 gap-4">
              <Metric label="Scenarios" value={view.progressDone.toLocaleString()} />
              <Metric label="Failures" value={view.progressFailures.toLocaleString()} />
              <Metric label="Coverage" value={`${view.coveredCells} / ${view.totalCells}`} />
            </Card>
          </div>
          <Card className="mt-3">
            <div className="mb-4 text-sm font-medium">Test space explored</div>
            {coverage}
          </Card>
        </>
      )}
    </>
  );
}

const VERDICT = {
  BLOCK: { icon: ShieldX, title: "Blocked: this update must not ship", cls: "bg-danger-bg text-danger" },
  REVIEW: { icon: TriangleAlert, title: "Needs an engineer's review", cls: "bg-warn-bg text-warn" },
  APPROVE: { icon: ShieldCheck, title: "Approved for the next stage", cls: "bg-ok-bg text-ok" },
} as const;

function VerdictStep({ view, findings, extra }: { view: RunView; findings: ReactNode | null; extra?: ReactNode }) {
  const v = VERDICT[view.verdict ?? "APPROVE"];
  const legacyOk = view.legacyPassed === view.legacyTotal;
  return (
    <>
      <StepIntro index={3} title="The verdict">
        {view.targetTitle}, compared with approved v1 across {view.evaluations.toLocaleString()} scenarios.
      </StepIntro>
      <div className={`flex items-start gap-4 rounded-lg p-5 ${v.cls}`}>
        <v.icon size={30} strokeWidth={1.6} className="shrink-0" />
        <div className="min-w-0">
          <div className="text-[19px] font-semibold tracking-[-0.015em]">{v.title}</div>
          <p className="mt-1 text-sm opacity-90">{view.reason}</p>
          {legacyOk && view.verdict === "BLOCK" && (
            <p className="mt-2 text-sm font-medium">All {view.legacyTotal} conventional tests passed. A fixed test suite would have shipped this bug to the fleet.</p>
          )}
        </div>
      </div>
      {findings ? (
        <div className="mt-4 overflow-hidden rounded-lg border border-line [&>section]:border-b-0">{findings}</div>
      ) : (
        <Card className="mt-4 text-sm text-fg-2">No confirmed regression in the scenarios tested. This is evidence, not proof of safety.</Card>
      )}
      {extra}
    </>
  );
}

function ScoreTile({ label, score, note }: { label: string; score: Score | undefined; note: string }) {
  return (
    <Card>
      <div className="text-sm text-fg-2">{label}</div>
      <div className="mt-1 text-[30px] font-medium leading-9 tracking-[-0.03em] tabular-nums">{score ? `${score.percent.toFixed(0)}%` : "…"}</div>
      <div className="num mt-0.5 text-xs text-fg-3">{score ? `${score.detected} of ${score.total} · 95% ${score.lower95.toFixed(0)}–${score.upper95.toFixed(0)}%` : "Measuring"}</div>
      <p className="mt-2 text-xs text-fg-2">{note}</p>
    </Card>
  );
}

interface TrustScores {
  mutants?: Score;
  faults?: Score;
  falseAlarms?: number;
  controlScenarios?: number;
}

function TrustStep({ view, featureName, scores, campaign, onOpenConsole }: { view: RunView; featureName: string; scores: TrustScores; campaign: CampaignStatus | null; onOpenConsole: (p: Page) => void }) {
  const blocked = view.verdict === "BLOCK";
  const measured = scores.falseAlarms !== undefined;
  return (
    <>
      <StepIntro index={4} title="Can you trust this verdict?">
        A gate is only useful if it catches real bugs and leaves safe updates alone. We measure both for {featureName.toLowerCase()} by planting bugs we know about and counting how many get caught.
      </StepIntro>
      {campaign?.running && (
        <p className="mb-3 rounded-lg bg-subtle p-3 text-sm text-fg-2">Still measuring: {campaign.phase} · {Math.round(campaign.progress * 100)}%</p>
      )}
      <div className="grid gap-3 md:grid-cols-3">
        <ScoreTile label="Real code bugs caught" score={scores.mutants} note="Bugs a tool wrote by rewriting the compiled logic. Nobody chose them, so this is the harder score." />
        <ScoreTile label="Designed faults caught" score={scores.faults} note="Faults we designed to look like real OTA mistakes." />
        <Card>
          <div className="text-sm text-fg-2">False alarms on safe updates</div>
          <div className="mt-1 text-[30px] font-medium leading-9 tracking-[-0.03em] tabular-nums">{measured ? scores.falseAlarms : "…"}</div>
          <div className="num mt-0.5 text-xs text-fg-3">{measured ? `in ${(scores.controlScenarios ?? 0).toLocaleString()} scenarios` : "Measuring"}</div>
          <p className="mt-2 text-xs text-fg-2">Known-safe software that must never be blocked.</p>
        </Card>
      </div>

      <Card className="mt-6">
        <div className="flex items-start gap-3">
          <FileCheck2 size={22} strokeWidth={1.6} className={blocked ? "text-danger" : "text-ok"} />
          <div className="min-w-0 flex-1">
            <div className="text-[15px] font-medium">{blocked ? "Stopped before it reached a single car" : view.verdict === "APPROVE" ? "Cleared for hardware-in-the-loop testing" : "Waiting for an engineer's sign-off"}</div>
            <p className="mt-1 text-sm text-fg-2">
              {blocked
                ? "Send the evidence back to the team. It has the shrunk failing scenario and a side-by-side trace, so the bug can be reproduced exactly."
                : "TwinGuard is a fast filter before hardware rigs, not a replacement. Attach the evidence to the release."}
            </p>
            <div className="mt-4 flex flex-wrap gap-2">
              {view.evidenceUrl && (
                <a href={view.evidenceUrl} target="_blank" rel="noreferrer" className="inline-flex h-8 items-center gap-1.5 rounded-md border border-line-strong px-3 text-sm font-medium hover:bg-muted">
                  <Code2 size={14} /> Evidence report (JSON)
                </a>
              )}
              <Button variant="ghost" onClick={() => onOpenConsole("confidence")}>Full confidence analysis</Button>
              <Button variant="ghost" onClick={() => onOpenConsole("pipeline")}>See it in CI</Button>
            </div>
          </div>
        </div>
      </Card>
      <p className="mt-4 text-xs text-fg-3">Real computed results from a simulated car, not real vehicle data. Scores apply to this fault set and this simulator.</p>
    </>
  );
}

export default function Workflow(props: Props) {
  const { releases, features, run, results, featureRun, featureResults, campaign, theme, onToggleTheme, onRun, onRunFeature, onOpenConsole } = props;
  const [step, setStep] = useState(0);
  const [track, setTrack] = useState(BRAKING);
  const [selected, setSelected] = useState<string | null>(null);
  const topRef = useRef<HTMLDivElement>(null);

  const feature = features.find((f) => f.id === track) ?? null;
  const isBraking = track === BRAKING || !feature;
  const featureName = isBraking ? "Collision braking" : feature.name;
  const tracks = [
    { id: BRAKING, name: "Collision braking", summary: "Automatic emergency braking, with the radar, gateway and infotainment network that feed it." },
    ...features.map((f) => ({ id: f.id, name: f.name, summary: f.summary })),
  ];
  const candidates: Candidate[] = isBraking
    ? releases
    : feature.releases.map((r) => ({ ...r, component: feature.name }));
  const candidate = candidates.find((c) => c.id === selected) ?? null;

  // The run for the selected candidate: the live one if it matches, else the last finished one.
  const brakingRun = isBraking && selected ? (run?.targetId === selected ? run : results[selected] ?? null) : null;
  const fKey = !isBraking && selected ? `${feature.id}:${selected}` : null;
  const fRun = fKey ? (featureRun?.key === fKey ? featureRun : featureResults[fKey] ?? null) : null;
  const view = brakingRun ? brakingView(brakingRun) : fRun ? featureView(fRun) : null;

  const verdictOf = (id: string): Verdict | null =>
    isBraking ? results[id]?.report?.verdict ?? null : featureResults[`${feature.id}:${id}`]?.report?.verdict ?? null;

  // Each step opens only once the one before it is done.
  const unlocked = !candidate ? 0 : !view ? 1 : !view.done ? 2 : 4;

  useEffect(() => {
    if (step > unlocked) setStep(unlocked);
  }, [step, unlocked]);

  useEffect(() => {
    topRef.current?.scrollIntoView({ block: "start" });
  }, [step]);

  const go = (i: number) => setStep(Math.min(i, unlocked));
  const busy = (run?.running ?? false) || (featureRun?.running ?? false);
  const start = () => {
    if (!candidate || busy) return;
    if (isBraking) {
      const release = releases.find((r) => r.id === candidate.id);
      if (release) onRun(release);
    } else {
      onRunFeature(feature, candidate);
    }
    setStep(2);
  };
  const reset = () => {
    setSelected(null);
    setStep(0);
  };
  const chooseTrack = (id: string) => {
    if (id !== track) {
      setTrack(id);
      setSelected(null);
    }
  };

  let next: { label: string; onClick: () => void; disabled?: boolean; why?: string };
  switch (step) {
    case 0:
      next = { label: "Review the change", onClick: () => go(1), disabled: !candidate, why: "Pick an update to continue" };
      break;
    case 1:
      next = { label: "Start shadow test", onClick: start, disabled: busy, why: busy ? "Another test is still running" : undefined };
      break;
    case 2:
      next = { label: "See the verdict", onClick: () => go(3), disabled: !view?.done, why: view?.error ? undefined : "Waiting for the test to finish" };
      break;
    case 3:
      next = { label: "Can we trust it?", onClick: () => go(4) };
      break;
    default:
      next = { label: "Test another update", onClick: reset };
  }

  // Feature-specific pieces, plugged into the shared steps.
  const coverage = brakingRun
    ? <CoverageMap cells={brakingRun.report?.coverage ?? brakingRun.progress?.cells ?? []} coveredCells={view?.coveredCells ?? 0} totalCells={view?.totalCells ?? 0} />
    : fRun && feature
      ? <FeatureCoverage feature={feature} cells={fRun.report?.coverage ?? fRun.progress?.cells ?? []} covered={view?.coveredCells ?? 0} total={view?.totalCells ?? 64} />
      : null;
  const findings = brakingRun?.report && brakingRun.report.counterexamples.length > 0
    ? <Findings report={brakingRun.report} theme={theme} />
    : fRun?.report && feature && fRun.report.counterexamples.length > 0
      ? <FeatureFindings feature={feature} report={fRun.report} />
      : null;
  const extra = brakingRun?.report && brakingRun.runId && brakingRun.report.counterexamples.length > 0
    ? <div className="mt-4 overflow-hidden rounded-lg border border-line [&>section]:border-b-0"><CarlaRecheck runId={brakingRun.runId} failures={brakingRun.report.counterexamples.length} /></div>
    : feature && !isBraking
      ? <p className="mt-4 text-xs text-fg-3"><span className="font-medium text-fg-2">Limits of this model: </span>{feature.limitations}</p>
      : undefined;
  const fc = !isBraking ? campaign?.features?.[feature.id] : undefined;
  const scores: TrustScores = isBraking
    ? {
        mutants: campaign?.codeMutation?.overall,
        faults: campaign?.report?.overall,
        falseAlarms: campaign?.report ? campaign.report.controls.reduce((n, x) => n + x.falseAlarms, 0) : undefined,
        controlScenarios: campaign?.report?.controls.reduce((n, x) => n + x.scenarios, 0),
      }
    : { mutants: fc?.mutants, faults: fc?.faults, falseAlarms: fc?.falseAlarms, controlScenarios: fc?.controlScenarios };

  return (
    <div className="flex min-h-screen flex-col">
      <header className="sticky top-0 z-20 border-b border-line bg-canvas/95 backdrop-blur">
        <div className="flex h-12 items-center gap-3 px-4 sm:px-8">
          <Logo />
          <span className="text-sm font-semibold tracking-[-0.01em]">TwinGuard</span>
          <span className="hidden text-sm text-fg-3 md:inline">OTA release safety gate</span>
          <span className="num ml-auto rounded-full bg-muted px-2.5 py-0.5 text-xs text-fg-2">Step {step + 1}/{STEPS.length} · {STEPS[step].title}</span>
          <Button variant="ghost" onClick={() => onOpenConsole("releases")} className="hidden sm:inline-flex">Open full console</Button>
          <button
            type="button"
            aria-label={theme === "light" ? "Switch to dark theme" : "Switch to light theme"}
            onClick={onToggleTheme}
            className="flex h-7 w-7 items-center justify-center rounded-md text-fg-3 hover:bg-muted hover:text-fg"
          >
            {theme === "light" ? <Moon size={15} /> : <Sun size={15} />}
          </button>
        </div>
        <Stepper step={step} unlocked={unlocked} onGo={go} />
      </header>

      <div ref={topRef} className="scroll-mt-32" />
      <main className="mx-auto w-full max-w-[1100px] flex-1 px-4 py-8 sm:px-8">
        {step === 0 && (
          <ChooseStep tracks={tracks} track={track} onTrack={chooseTrack} candidates={candidates} verdictOf={verdictOf} selected={selected} onSelect={setSelected} onOpenConsole={onOpenConsole} />
        )}
        {step === 1 && candidate && <ReviewStep candidate={candidate} featureName={featureName} />}
        {step === 2 && view && <TestStep view={view} coverage={coverage} onRetry={start} />}
        {step === 3 && view?.done && <VerdictStep view={view} findings={findings} extra={extra} />}
        {step === 4 && view?.done && <TrustStep view={view} featureName={featureName} scores={scores} campaign={campaign} onOpenConsole={onOpenConsole} />}
      </main>

      <footer className="sticky bottom-0 z-20 border-t border-line bg-canvas/95 backdrop-blur">
        <div className="mx-auto flex w-full max-w-[1100px] items-center gap-3 px-4 py-3 sm:px-8">
          <Button variant="ghost" disabled={step === 0} onClick={() => go(step - 1)}><ArrowLeft size={14} /> Back</Button>
          {next.disabled && next.why && <span className="ml-auto text-xs text-fg-3">{next.why}</span>}
          <Button variant="primary" className={`${next.disabled && next.why ? "" : "ml-auto"} h-9 px-4`} disabled={next.disabled} onClick={next.onClick}>
            {next.label} {step < STEPS.length - 1 && <ArrowRight size={14} />}
          </Button>
        </div>
      </footer>
    </div>
  );
}
