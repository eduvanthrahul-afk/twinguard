import { Check, ChevronLeft, LoaderCircle, RotateCw, X } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { FeatureRunState } from "../App";
import type { FeatureCell, FeatureCounterexample, FeatureParam, FeatureReport, FeatureView, Finding } from "../api";
import { Button, Metric, PageHeader, Section, StatusPill, tooltipStyle } from "./ui";

const BINS = 8;
const SEVERITY: Record<Finding["severity"], { dot: string; label: string }> = {
  CRITICAL: { dot: "var(--fail)", label: "Critical" },
  HIGH: { dot: "var(--dot-serious)", label: "High" },
  MEDIUM: { dot: "var(--dot-warn)", label: "Medium" },
};

function CheckRow({ state, name, detail }: { state: "pass" | "fail" | "running"; name: string; detail: string }) {
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

function binRange(p: FeatureParam, i: number) {
  const size = (p.max - p.min) / BINS;
  const digits = p.max - p.min < 1 ? 3 : p.max - p.min < 10 ? 1 : 0;
  return `${(p.min + i * size).toFixed(digits)}–${(p.min + (i + 1) * size).toFixed(digits)}`;
}

export function FeatureCoverage({ feature, cells, covered, total }: { feature: FeatureView; cells: FeatureCell[]; covered: number; total: number }) {
  const px = feature.params[feature.coverageX];
  const py = feature.params[feature.coverageY];
  const [hover, setHover] = useState<string | null>(null);
  const { grid, max } = useMemo(() => {
    const map = new Map<string, FeatureCell>();
    let largest = 1;
    for (const c of cells) {
      map.set(`${c.x}-${c.y}`, c);
      largest = Math.max(largest, c.tests);
    }
    return { grid: map, max: largest };
  }, [cells]);
  const background = (c?: FeatureCell) => {
    if (!c) return "transparent";
    if (c.failures > 0) return `color-mix(in srgb, var(--fail) ${30 + Math.round((c.failures / c.tests) * 70)}%, var(--bg))`;
    return `color-mix(in srgb, var(--series-v1) ${12 + Math.round((Math.log(c.tests + 1) / Math.log(max + 1)) * 58)}%, var(--bg))`;
  };
  const unit = (p: FeatureParam) => (p.unit ? ` (${p.unit})` : "");

  return (
    <div className="max-w-[860px]">
      <div className="flex items-baseline justify-between gap-3">
        <p className="text-xs text-fg-3">{py.label}{unit(py)}</p>
        <span className="num text-xs text-fg-2">{covered} / {total} cells</span>
      </div>
      <div className="mt-1.5 grid gap-[2px]" style={{ gridTemplateColumns: `68px repeat(${BINS}, minmax(0, 1fr))` }} onMouseLeave={() => setHover(null)}>
        {Array.from({ length: BINS }, (_, row) => BINS - 1 - row).map((y) => (
          <div key={y} className="contents">
            <div className="num flex items-center justify-end pr-2.5 text-[10px] text-fg-3">{binRange(py, y)}</div>
            {Array.from({ length: BINS }, (_, x) => {
              const cell = grid.get(`${x}-${y}`);
              const pale = cell ? cell.failures / cell.tests < 0.45 : false;
              return (
                <div
                  key={x}
                  onMouseEnter={() => setHover(`${px.label} ${binRange(px, x)} · ${py.label} ${binRange(py, y)} · ${cell ? `${cell.tests} tests, ${cell.failures} failing` : "not tested"}`)}
                  className="flex h-7 items-center justify-center rounded-[3px] text-[15px] font-medium leading-none transition-colors duration-150 hover:outline hover:outline-1 hover:outline-fg"
                  style={{ background: background(cell), color: pale ? "var(--fg)" : "#ffffff", boxShadow: cell ? undefined : "inset 0 0 0 1px var(--border)" }}
                >
                  {cell && cell.failures > 0 && <span aria-hidden>×</span>}
                </div>
              );
            })}
          </div>
        ))}
        <div />
        {Array.from({ length: BINS }, (_, x) => <div key={x} className="num pt-1 text-center text-[10px] text-fg-3">{binRange(px, x)}</div>)}
      </div>
      <p className="mt-1 pl-[68px] text-center text-xs text-fg-3">{px.label}{unit(px)}</p>
      <div className="mt-3 flex flex-wrap items-center justify-between gap-x-5 gap-y-2 text-xs text-fg-2">
        <div className="flex flex-wrap items-center gap-4">
          <span className="flex items-center gap-1.5"><span className="h-2.5 w-2.5 rounded-[2px]" style={{ boxShadow: "inset 0 0 0 1px var(--border-strong)" }} /> Not tested</span>
          <span className="flex items-center gap-1.5"><span className="h-2.5 w-2.5 rounded-[2px]" style={{ background: "color-mix(in srgb, var(--series-v1) 50%, var(--bg))" }} /> Passed, darker means more tests</span>
          <span className="flex items-center gap-1.5"><span className="flex h-2.5 w-2.5 items-center justify-center rounded-[2px] text-[9px] leading-none text-white" style={{ background: "var(--fail)" }}>×</span> Confirmed failure</span>
        </div>
        <span className="text-fg-3">{hover ?? "Hover a cell for details"}</span>
      </div>
    </div>
  );
}

function ChannelChart({ title, unit, data, index }: { title: string; unit: string; data: Record<string, number | null>[]; index: number }) {
  return (
    <div className="min-w-0">
      <p className="mb-2 text-xs font-medium text-fg-2">{title}{unit ? ` (${unit})` : ""}</p>
      <div className="h-36">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={data} margin={{ top: 4, right: 4, left: -14, bottom: 0 }}>
            <CartesianGrid stroke="var(--border)" vertical={false} />
            <XAxis dataKey="t" type="number" domain={[0, "dataMax"]} tick={{ fill: "var(--fg-3)", fontSize: 10 }} tickLine={false} axisLine={{ stroke: "var(--border-strong)" }} tickFormatter={(v) => `${Number(Number(v).toFixed(1))}s`} tickCount={6} />
            <YAxis domain={["auto", "auto"]} tick={{ fill: "var(--fg-3)", fontSize: 10 }} tickLine={false} axisLine={false} tickFormatter={(v) => `${Number(Number(v).toFixed(2))}`} />
            <Tooltip {...tooltipStyle} labelFormatter={(v) => `${Number(v).toFixed(2)} s`} formatter={(v: number, name: string) => [`${Number(v).toFixed(3)} ${unit}`, name]} />
            <Line name="v1" dataKey={`a${index}`} stroke="var(--series-v1)" strokeWidth={2} dot={false} isAnimationActive={false} connectNulls={false} />
            <Line name="v2" dataKey={`b${index}`} stroke="var(--series-v2)" strokeWidth={2} dot={false} isAnimationActive={false} connectNulls={false} />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}

export function FeatureFindings({ feature, report }: { feature: FeatureView; report: FeatureReport }) {
  const [index, setIndex] = useState(0);
  useEffect(() => setIndex(0), [report]);
  const current: FeatureCounterexample = report.counterexamples[Math.min(index, report.counterexamples.length - 1)];

  const data = useMemo(() => {
    const rows = new Map<number, Record<string, number | null>>();
    const add = (trace: number[][], prefix: string) => {
      for (const row of trace) {
        const t = Math.round(row[0] * 1000) / 1000;
        const entry = rows.get(t) ?? { t };
        feature.channels.forEach((_, i) => {
          entry[`${prefix}${i}`] = row[i + 1];
        });
        rows.set(t, entry);
      }
    };
    add(current.v1Trace, "a");
    add(current.v2Trace, "b");
    const sorted = [...rows.values()].sort((p, q) => (p.t as number) - (q.t as number));
    const step = Math.max(1, Math.floor(sorted.length / 200));
    return sorted.filter((_, i) => i % step === 0);
  }, [current, feature]);

  return (
    <section className="grid border-b border-line lg:grid-cols-[280px_minmax(0,1fr)]">
      <div className="border-b border-line py-6 lg:border-b-0 lg:border-r">
        <h2 className="px-5 text-base font-semibold tracking-[-0.01em] sm:px-8 lg:pl-8 lg:pr-4">Findings</h2>
        <p className="mt-1 px-5 text-xs text-fg-3 sm:px-8 lg:pl-8 lg:pr-4">Each is shrunk to the simplest scenario that still fails.</p>
        <div role="tablist" aria-label="Findings" aria-orientation="vertical" className="mt-4">
          {report.counterexamples.map((e, i) => (
            <button
              key={e.primary.type}
              role="tab"
              type="button"
              aria-selected={i === index}
              onClick={() => setIndex(i)}
              className={`flex w-full items-center gap-2.5 border-l-2 py-2 pl-5 pr-4 text-left text-sm transition-colors sm:pl-8 ${i === index ? "border-fg bg-subtle font-medium text-fg" : "border-transparent text-fg-2 hover:bg-subtle hover:text-fg"}`}
            >
              <span aria-hidden className="h-1.5 w-1.5 shrink-0 rounded-full" style={{ background: SEVERITY[e.primary.severity].dot }} />
              <span className="min-w-0 flex-1 truncate">{e.primary.label}</span>
              <span className="num text-xs text-fg-3">{(report.findingCounts[e.primary.type] ?? 0).toLocaleString()}</span>
            </button>
          ))}
        </div>
      </div>
      <div className="min-w-0 px-5 py-6 sm:px-8">
        <div className="flex items-center gap-2 text-xs text-fg-3">
          <span aria-hidden className="h-1.5 w-1.5 rounded-full" style={{ background: SEVERITY[current.primary.severity].dot }} />
          {SEVERITY[current.primary.severity].label} severity
        </div>
        <p className="mt-1 text-[17px] font-medium leading-6 tracking-[-0.015em]">{current.primary.message}</p>
        <dl className="mt-4 flex flex-wrap gap-x-7 gap-y-2">
          {feature.params.map((p) => (
            <div key={p.key}>
              <dt className="text-xs text-fg-3">{p.label}</dt>
              <dd className="num mt-0.5 text-sm">{Number((current.scenario[p.key] ?? 0).toFixed(3))}{p.unit ? ` ${p.unit}` : ""}</dd>
            </div>
          ))}
        </dl>
        <div className="mt-6 flex items-center gap-4 text-xs text-fg-2">
          <span className="flex items-center gap-1.5"><span className="h-[3px] w-3" style={{ background: "var(--series-v1)" }} /> v1 approved</span>
          <span className="flex items-center gap-1.5"><span className="h-[3px] w-3" style={{ background: "var(--series-v2)" }} /> v2 candidate</span>
        </div>
        <div className="mt-3 grid gap-6 md:grid-cols-2">
          {feature.channels.map((c, i) => <ChannelChart key={c.key} title={c.label} unit={c.unit} data={data} index={i} />)}
        </div>
      </div>
    </section>
  );
}

export default function FeatureRunPage({ feature, run, onBack, onRerun }: { feature: FeatureView; run: FeatureRunState; onBack: () => void; onRerun: () => void }) {
  const { report, progress, running, error } = run;
  const percent = progress ? (progress.done / progress.budget) * 100 : 0;
  const status = running ? "RUNNING" : error ? "FAILED" : report?.verdict ?? "IDLE";
  const conventionalOk = report ? report.conventionalPassed === report.conventionalTotal : false;

  return (
    <>
      <PageHeader
        breadcrumb={<button type="button" onClick={onBack} className="inline-flex items-center gap-1 hover:text-fg"><ChevronLeft size={14} /> {feature.name}</button>}
        title={<>{report?.targetTitle ?? run.title}<StatusPill status={status} /></>}
        description={run.summary}
        actions={<Button disabled={running} onClick={onRerun}><RotateCw size={13} /> Run again</Button>}
      />
      <div className="h-px bg-line">{running && <div className="h-px bg-fg transition-all duration-100" style={{ width: `${percent}%` }} />}</div>

      {error && <div role="alert" className="mx-5 mt-5 rounded-md bg-danger-bg p-4 text-sm text-danger sm:mx-8">{error}</div>}

      {!error && (
        <section className="grid border-b border-line lg:grid-cols-2 lg:divide-x lg:divide-line">
          <div className="px-5 py-5 sm:px-8">
            <CheckRow state={!report ? "running" : conventionalOk ? "pass" : "fail"} name="Conventional test suite" detail={report ? `${report.conventionalPassed} of ${report.conventionalTotal} passed` : "Running"} />
            <CheckRow
              state={!report ? "running" : report.failingScenarios > 0 ? "fail" : "pass"}
              name="TwinGuard differential gate"
              detail={report ? `${report.failingScenarios.toLocaleString()} of ${report.evaluations.toLocaleString()} failed` : progress ? `${progress.done.toLocaleString()} of ${progress.budget.toLocaleString()}, ${progress.failures.toLocaleString()} failed` : "Starting"}
            />
            {report && conventionalOk && report.verdict === "BLOCK" && <p className="mt-3 border-t border-line pt-3 text-sm text-fg-2">Every conventional test passed, so a fixed suite would have shipped this bug.</p>}
            {report && report.verdict === "APPROVE" && <p className="mt-3 border-t border-line pt-3 text-sm text-fg-2">No confirmed regression in the scenarios tested. This is evidence, not proof of safety.</p>}
            {report && report.verdict === "REVIEW" && <p className="mt-3 border-t border-line pt-3 text-sm text-fg-2">Only medium-severity differences were found. An engineer should review them before release.</p>}
          </div>
          <div className="grid grid-cols-2 gap-5 border-t border-line px-5 py-5 sm:grid-cols-4 sm:px-8 lg:border-t-0">
            <Metric label="Scenarios" value={(report?.evaluations ?? progress?.done ?? 0).toLocaleString()} />
            <Metric label="Simulations" value={report ? report.simulations.toLocaleString() : "–"} />
            <Metric label="Coverage" value={`${report?.coveredCells ?? progress?.coveredCells ?? 0} / ${report?.totalCells ?? progress?.totalCells ?? 64}`} />
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

      {report && report.counterexamples.length > 0 && <FeatureFindings feature={feature} report={report} />}

      {!error && (
        <Section title="Coverage" description="Cells of the test space that have been explored.">
          <FeatureCoverage feature={feature} cells={report?.coverage ?? progress?.cells ?? []} covered={report?.coveredCells ?? progress?.coveredCells ?? 0} total={report?.totalCells ?? progress?.totalCells ?? 64} />
        </Section>
      )}
      <p className="px-5 py-4 text-xs leading-[18px] text-fg-3 sm:px-8"><span className="font-medium text-fg-2">Limits of this model: </span>{feature.limitations}</p>
    </>
  );
}
