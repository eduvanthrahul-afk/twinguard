import { Check, ChevronLeft, Code2, LoaderCircle, RotateCw, X } from "lucide-react";
import { useEffect, useState } from "react";
import type { RunState, Theme } from "../App";
import { FINDING_LABELS } from "../api";
import type { Counterexample, Finding, GateReport, Scenario } from "../api";
import CarlaRecheck from "./CarlaRecheck";
import CoverageMap from "./CoverageMap";
import Replay from "./Replay";
import { Button, Metric, PageHeader, Section, StatusPill } from "./ui";

const SEVERITY: Record<Finding["severity"], { dot: string; label: string }> = {
  CRITICAL: { dot: "var(--fail)", label: "Critical" },
  HIGH: { dot: "var(--dot-serious)", label: "High" },
  MEDIUM: { dot: "var(--dot-warn)", label: "Medium" },
};

export function CheckRow({ state, name, detail }: { state: "pass" | "fail" | "running"; name: string; detail: string }) {
  return (
    <div className="flex items-center gap-3 border-t border-line py-3 first:border-t-0 first:pt-0 last:pb-0">
      <span className={`flex h-5 w-5 shrink-0 items-center justify-center rounded-full ${state === "pass" ? "bg-ok-bg text-ok" : state === "fail" ? "bg-danger-bg text-danger" : "bg-muted text-fg-2"}`}>
        {state === "pass" && <Check size={12} strokeWidth={3} />}
        {state === "fail" && <X size={12} strokeWidth={3} />}
        {state === "running" && <LoaderCircle size={12} className="spin" />}
      </span>
      <span className="min-w-0 flex-1 text-sm font-medium">{name}</span>
      <span className="num shrink-0 text-right text-xs text-fg-2">{detail}</span>
    </div>
  );
}

function ScenarioFacts({ s }: { s: Scenario }) {
  const facts: [string, string][] = s.type === "GHOST"
    ? [["Speed", `${s.speedKph.toFixed(0)} km/h`], ["Real obstacle", "None"], ["False return at", `${s.distanceM.toFixed(0)} m`]]
    : [
        ["Speed", `${s.speedKph.toFixed(0)} km/h`],
        ["Obstacle", `${s.distanceM.toFixed(0)} m`],
        ["Friction", s.friction.toFixed(2)],
        ["Radar noise", `${s.noiseM.toFixed(2)} m`],
        ["Media load", `${(s.infotainmentLoad * 100).toFixed(0)}%`],
      ];
  return (
    <dl className="flex flex-wrap gap-x-7 gap-y-2">
      {facts.map(([k, v]) => (
        <div key={k}>
          <dt className="text-xs text-fg-3">{k}</dt>
          <dd className="num mt-0.5 text-sm">{v}</dd>
        </div>
      ))}
    </dl>
  );
}

export function Findings({ report, theme }: { report: GateReport; theme: Theme }) {
  const examples = report.counterexamples;
  const [index, setIndex] = useState(0);
  useEffect(() => setIndex(0), [report]);
  const current: Counterexample = examples[Math.min(index, examples.length - 1)];

  return (
    <section className="grid border-b border-line lg:grid-cols-[280px_minmax(0,1fr)]">
      <div className="border-b border-line py-6 lg:border-b-0 lg:border-r">
        <h2 className="px-5 text-base font-semibold tracking-[-0.01em] sm:px-8 lg:pl-8 lg:pr-4">Findings</h2>
        <p className="mt-1 px-5 text-xs text-fg-3 sm:px-8 lg:pl-8 lg:pr-4">Each is shrunk to the simplest scenario that still fails.</p>
        <div role="tablist" aria-label="Findings" aria-orientation="vertical" className="mt-4">
          {examples.map((e, i) => (
            <button
              key={e.primary.type}
              role="tab"
              type="button"
              aria-selected={i === index}
              onClick={() => setIndex(i)}
              className={`flex w-full items-center gap-2.5 border-l-2 py-2 pl-5 pr-4 text-left text-sm transition-colors sm:pl-8 ${i === index ? "border-fg bg-subtle font-medium text-fg" : "border-transparent text-fg-2 hover:bg-subtle hover:text-fg"}`}
            >
              <span aria-hidden className="h-1.5 w-1.5 shrink-0 rounded-full" style={{ background: SEVERITY[e.primary.severity].dot }} />
              <span className="min-w-0 flex-1 truncate">{FINDING_LABELS[e.primary.type] ?? e.primary.type}</span>
              <span className="num text-xs text-fg-3">{(report.findingCounts[e.primary.type] ?? 0).toLocaleString()}</span>
            </button>
          ))}
        </div>
      </div>

      <div className="min-w-0 px-5 py-6 sm:px-8">
        <div className="flex flex-wrap items-center gap-2 text-xs text-fg-3">
          <span aria-hidden className="h-1.5 w-1.5 rounded-full" style={{ background: SEVERITY[current.primary.severity].dot }} />
          {SEVERITY[current.primary.severity].label} severity
        </div>
        <p className="mt-1 text-[17px] font-medium leading-6 tracking-[-0.015em]">{current.primary.message}</p>
        <div className="mt-4"><ScenarioFacts s={current.scenario} /></div>
        <div className="mt-6"><Replay example={current} theme={theme} /></div>
      </div>
    </section>
  );
}

export default function RunPage({ run, theme, onBack, onRerun }: { run: RunState; theme: Theme; onBack: () => void; onRerun: () => void }) {
  const { report, progress, running, error } = run;
  const percent = progress ? (progress.done / progress.budget) * 100 : 0;
  const status = running ? "RUNNING" : error ? "FAILED" : report?.verdict ?? "IDLE";
  const legacyOk = report ? report.legacy.passed === report.legacy.total : false;
  const gateFails = report ? report.failingScenarios > 0 : false;
  const cells = report?.coverage ?? progress?.cells ?? [];

  return (
    <>
      <PageHeader
        breadcrumb={
          <button type="button" onClick={onBack} className="inline-flex items-center gap-1 hover:text-fg"><ChevronLeft size={14} /> Releases</button>
        }
        title={<>{report?.targetTitle ?? run.title}<StatusPill status={status} /></>}
        description={run.summary}
        actions={
          <>
            {report && run.runId && (
              <a href={`/api/runs/${run.runId}`} target="_blank" rel="noreferrer" className="inline-flex h-8 items-center gap-1.5 rounded-md px-3 text-sm font-medium text-fg-2 transition-colors hover:bg-muted hover:text-fg">
                <Code2 size={14} /> Raw report
              </a>
            )}
            <Button disabled={running} onClick={onRerun}><RotateCw size={13} /> Run again</Button>
          </>
        }
      />
      <div className="h-px bg-line" aria-hidden={!running}>
        {running && <div className="h-px bg-fg transition-all duration-100" style={{ width: `${percent}%` }} />}
      </div>

      {error && <div role="alert" className="mx-5 mt-5 rounded-md bg-danger-bg p-4 text-sm text-danger sm:mx-8">{error}</div>}

      {!error && (
        <section className="grid border-b border-line lg:grid-cols-2 lg:divide-x lg:divide-line">
          <div className="px-5 py-5 sm:px-8">
            <CheckRow
              state={!report ? "running" : legacyOk ? "pass" : "fail"}
              name="Conventional test suite"
              detail={report ? `${report.legacy.passed} of ${report.legacy.total} passed` : "Running"}
            />
            <CheckRow
              state={!report ? "running" : gateFails ? "fail" : "pass"}
              name="TwinGuard differential gate"
              detail={report
                ? `${report.failingScenarios.toLocaleString()} of ${report.evaluations.toLocaleString()} failed`
                : progress ? `${progress.done.toLocaleString()} of ${progress.budget.toLocaleString()}, ${progress.failures.toLocaleString()} failed` : "Starting"}
            />
            {report && legacyOk && report.verdict === "BLOCK" && (
              <p className="mt-3 border-t border-line pt-3 text-sm text-fg-2">Every conventional test passed, so a fixed suite would have shipped this bug.</p>
            )}
            {report && report.verdict === "APPROVE" && (
              <p className="mt-3 border-t border-line pt-3 text-sm text-fg-2">No confirmed regression in the scenarios tested. This is evidence, not proof of safety. Next stop: hardware-in-the-loop testing.</p>
            )}
            {report && report.verdict === "REVIEW" && (
              <p className="mt-3 border-t border-line pt-3 text-sm text-fg-2">Only medium-severity differences were found. An engineer should review them before release.</p>
            )}
          </div>
          <div className="grid grid-cols-2 gap-5 border-t border-line px-5 py-5 sm:grid-cols-4 sm:px-8 lg:border-t-0">
            <Metric label="Scenarios" value={(report?.evaluations ?? progress?.done ?? 0).toLocaleString()} />
            <Metric label="Simulations" value={report ? report.simulations.toLocaleString() : "–"} />
            <Metric label="Coverage" value={`${(report?.coveragePercent ?? progress?.coveragePercent ?? 0).toFixed(1)}%`} />
            <Metric label="Compute" value={report ? (report.computeMs < 1000 ? `${report.computeMs} ms` : `${(report.computeMs / 1000).toFixed(1)} s`) : "–"} />
          </div>
        </section>
      )}

      {report && report.changedSettings.length > 0 && (
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 border-b border-line px-5 py-3 text-xs sm:px-8">
          <span className="text-fg-3">Changed from v1</span>
          {report.changedSettings.map((s) => <code key={s} className="num rounded bg-muted px-1.5 py-0.5 text-fg-2">{s}</code>)}
        </div>
      )}

      {report && run.runId && <CarlaRecheck runId={run.runId} failures={report.counterexamples.length} />}
      {report && report.counterexamples.length > 0 && <Findings report={report} theme={theme} />}

      {!error && (
        <Section title="Coverage" description="Cells of the test space that have been explored. Search spends more scenarios where the two versions diverge.">
          <CoverageMap
            cells={cells}
            coveredCells={report?.coveredCells ?? progress?.coveredCells ?? 0}
            totalCells={report?.totalCells ?? progress?.totalCells ?? 0}
          />
        </Section>
      )}
    </>
  );
}
