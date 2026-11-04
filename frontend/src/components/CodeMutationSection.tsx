import { useMemo, useState } from "react";
import type { CodeMutationReport, MutantResult } from "../api";
import { Section, Segmented } from "./ui";

const KIND_LABEL: Record<NonNullable<MutantResult["survivorKind"]>, string> = {
  WITHIN_TOLERANCE: "Changed braking, stayed within tolerance",
  NO_BEHAVIOUR_CHANGE: "No braking change in any test",
};

function Stat({ label, value, sub }: { label: string; value: string; sub: string }) {
  return (
    <div className="min-w-0">
      <div className="text-sm text-fg-2">{label}</div>
      <div className="mt-1 text-[26px] font-medium leading-8 tracking-[-0.03em] tabular-nums">{value}</div>
      <div className="mt-0.5 text-xs text-fg-3 tabular-nums">{sub}</div>
    </div>
  );
}

export default function CodeMutationSection({ report }: { report: CodeMutationReport | null }) {
  const [filter, setFilter] = useState<"missed" | "tolerance" | "unchanged">("tolerance");
  const missed = useMemo(() => {
    if (!report) return [];
    return report.mutants.filter((m) => {
      if (m.detected) return false;
      if (filter === "tolerance") return m.survivorKind === "WITHIN_TOLERANCE";
      if (filter === "unchanged") return m.survivorKind === "NO_BEHAVIOUR_CHANGE";
      return true;
    });
  }, [report, filter]);

  if (!report) {
    return (
      <Section title="Real code mutants" description="Bugs created automatically from the compiled braking code.">
        <p className="text-sm text-fg-3">Measuring…</p>
      </Section>
    );
  }

  const { overall, excludingNoBehaviourChange: excl, randomBaseline: random } = report;
  const survivors = report.mutants.filter((m) => !m.detected).length;
  const tolerance = report.mutants.filter((m) => m.survivorKind === "WITHIN_TOLERANCE").length;

  return (
    <Section
      title="Real code mutants"
      description="Nobody chose these bugs. A tool rewrites one instruction of the compiled braking code at a time, and we never tuned the gate against them, so this is the harder and more independent score."
    >
      <div className="grid grid-cols-1 gap-6 sm:grid-cols-2 xl:grid-cols-4">
        <Stat label="Code mutants caught" value={`${overall.percent.toFixed(1)}%`} sub={`${overall.detected} of ${overall.total} · 95% range ${overall.lower95.toFixed(0)}–${overall.upper95.toFixed(0)}%`} />
        <Stat label="Random testing, same budget" value={`${random.percent.toFixed(1)}%`} sub={`${random.detected} of ${random.total}`} />
        <Stat label="Excluding no-change mutants" value={`${excl.percent.toFixed(1)}%`} sub={`${excl.detected} of ${excl.total}. Quote only beside the main score`} />
        <Stat label="Never changed braking" value={String(report.noBehaviourChangeMutants)} sub="Not proven harmless. Some need conditions we don't test" />
      </div>

      <div className="mt-7 grid gap-8 xl:grid-cols-[minmax(0,360px)_minmax(0,1fr)]">
        <div className="min-w-0">
          <h3 className="text-sm font-medium">By type of bug</h3>
          <table className="mt-2 w-full text-sm">
            <tbody>
              {report.byOperator.map((o) => (
                <tr key={o.operator} className="border-t border-line">
                  <td className="py-2 pr-3">{o.operator}</td>
                  <td className="num whitespace-nowrap text-right text-xs text-fg-2">{o.score.detected}/{o.score.total} · {o.score.percent.toFixed(0)}%</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="mt-3 text-xs leading-[18px] text-fg-3">
            {report.crashingMutants} mutants crashed the braking software during testing. Results use {report.trials} runs of up to {report.budget} scenarios each, and {report.simulations.toLocaleString()} simulations in {(report.computeMs / 1000).toFixed(1)} s.
          </p>
        </div>

        <div className="min-w-0">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h3 className="text-sm font-medium">{survivors} mutants survived</h3>
            <Segmented
              value={filter}
              onChange={setFilter}
              label="Filter survivors"
              options={[
                { value: "tolerance", label: `Within tolerance · ${tolerance}` },
                { value: "unchanged", label: `No change · ${report.noBehaviourChangeMutants}` },
                { value: "missed", label: `All · ${survivors}` },
              ]}
            />
          </div>
          <ul className="mt-2 max-h-[320px] overflow-y-auto border-t border-line">
            {missed.map((m) => (
              <li key={m.id} className="border-b border-line py-2.5 last:border-b-0">
                <div className="flex flex-wrap items-baseline justify-between gap-x-3">
                  <span className="text-sm font-medium">{m.description}</span>
                  <span className="num text-xs text-fg-3">{m.id} · line {m.line}</span>
                </div>
                <code className="num mt-1 block overflow-x-auto whitespace-pre text-xs text-fg-2">{m.sourceLine}</code>
                {m.survivorKind && <div className="mt-1 text-xs text-fg-3">{KIND_LABEL[m.survivorKind]}</div>}
              </li>
            ))}
          </ul>
        </div>
      </div>
    </Section>
  );
}
