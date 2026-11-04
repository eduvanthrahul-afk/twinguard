import { ChevronRight } from "lucide-react";
import { useState } from "react";
import type { FeatureCampaignReport, FeatureRunRequest, FeatureView } from "../api";
import type { FeatureRunState } from "../App";
import { Button, PageHeader, Section, Segmented, StatusPill } from "./ui";

interface Props {
  feature: FeatureView;
  campaign: FeatureCampaignReport | undefined;
  results: Record<string, FeatureRunState>;
  active: FeatureRunState | null;
  onRun: (feature: FeatureView, targetId: string, title: string, summary: string, request: FeatureRunRequest) => void;
  onOpen: (state: FeatureRunState) => void;
}

function Stat({ label, value, sub }: { label: string; value: string; sub: string }) {
  return (
    <div className="min-w-0 px-5 py-5 sm:px-8">
      <div className="text-sm text-fg-2">{label}</div>
      <div className="mt-1.5 text-[28px] font-medium leading-9 tracking-[-0.03em] tabular-nums">{value}</div>
      <div className="mt-0.5 text-xs text-fg-3 tabular-nums">{sub}</div>
    </div>
  );
}

export default function FeaturePage({ feature, campaign, results, active, onRun, onOpen }: Props) {
  const [mode, setMode] = useState<"fault" | "mutant">("fault");
  const [faultId, setFaultId] = useState("");
  const [mutantId, setMutantId] = useState("");
  const running = active?.running ?? false;
  const mutant = feature.mutants.find((m) => m.id === mutantId);
  const fault = feature.faults.find((f) => f.id === faultId);
  const survivors = campaign?.mutantResults.filter((m) => !m.detected) ?? [];

  return (
    <>
      <PageHeader title={feature.name} description={feature.summary} />

      {campaign ? (
        <div className="grid border-b border-line sm:grid-cols-2 sm:divide-x sm:divide-line xl:grid-cols-4">
          <Stat label="Code mutants caught" value={`${campaign.mutants.percent.toFixed(0)}%`} sub={`${campaign.mutants.detected} of ${campaign.mutants.total} · 95% range ${campaign.mutants.lower95.toFixed(0)}–${campaign.mutants.upper95.toFixed(0)}%`} />
          <Stat label="Random testing, same budget" value={`${campaign.mutantsRandom.percent.toFixed(0)}%`} sub={`${campaign.mutantsRandom.detected} of ${campaign.mutantsRandom.total} code mutants`} />
          <Stat label="Designed faults caught" value={`${campaign.faults.percent.toFixed(0)}%`} sub={`${campaign.faults.detected} of ${campaign.faults.total}. Chosen by us, so an easier test`} />
          <Stat label="False alarms on safe software" value={String(campaign.falseAlarms)} sub={`in ${campaign.controlScenarios.toLocaleString()} scenarios`} />
        </div>
      ) : (
        <p className="border-b border-line px-5 py-4 text-sm text-fg-3 sm:px-8">Measuring this feature's scores…</p>
      )}

      <div className="overflow-x-auto">
        <table className="w-full min-w-[720px] text-sm">
          <thead>
            <tr className="border-b border-line text-left text-xs text-fg-3">
              <th scope="col" className="py-2.5 pl-5 pr-4 font-medium sm:pl-8">Release</th>
              <th scope="col" className="px-4 font-medium">Changed settings</th>
              <th scope="col" className="px-4 font-medium">Status</th>
              <th scope="col" className="px-4 text-right font-medium">Failing</th>
              <th scope="col" className="pl-4 pr-5 sm:pr-8"><span className="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            {feature.releases.map((r) => {
              const key = `${feature.id}:${r.id}`;
              const isActive = active?.running && active.key === key;
              const result = results[key];
              const status = isActive ? "RUNNING" : result?.error ? "FAILED" : result?.report?.verdict ?? "IDLE";
              return (
                <tr key={r.id} className="border-b border-line transition-colors hover:bg-subtle">
                  <td className="py-3.5 pl-5 pr-4 sm:pl-8">
                    <div className="font-medium">{r.title}</div>
                    <div className="mt-0.5 max-w-[52ch] text-fg-3">{r.summary}</div>
                  </td>
                  <td className="num px-4 text-xs text-fg-2">{r.changedSettings.map((s) => <div key={s}>{s}</div>)}</td>
                  <td className="px-4"><StatusPill status={status} /></td>
                  <td className="num px-4 text-right text-fg-2">{result?.report ? `${result.report.failingScenarios.toLocaleString()} / ${result.report.evaluations.toLocaleString()}` : "–"}</td>
                  <td className="pl-4 pr-5 sm:pr-8">
                    <div className="flex items-center justify-end gap-1.5">
                      <Button variant={result ? "secondary" : "primary"} disabled={running} onClick={() => onRun(feature, r.id, r.title, r.summary, { kind: "release", id: r.id })}>
                        {isActive ? "Running" : result ? "Run again" : "Run gate"}
                      </Button>
                      <Button variant="ghost" aria-label={`Open results for ${r.title}`} disabled={!result || running} className="w-8 !px-0" onClick={() => result && onOpen(result)}>
                        <ChevronRight size={15} />
                      </Button>
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <Section title="Inject a bug" description="Pick a designed fault, or a real bug created by rewriting the compiled code, and run it through the gate.">
        <div className="max-w-2xl">
          <Segmented value={mode} onChange={setMode} label="Bug source" options={[{ value: "fault", label: `Designed faults · ${feature.faults.length}` }, { value: "mutant", label: `Code mutants · ${feature.mutants.length}` }]} />
          {mode === "fault" ? (
            <>
              <select aria-label="Designed fault" value={faultId} onChange={(e) => setFaultId(e.target.value)} className="mt-3 h-9 w-full rounded-md border border-line-strong px-2.5 text-sm">
                <option value="">Choose a fault</option>
                {feature.faults.map((f) => <option key={f.id} value={f.id}>{f.faultClass}: {f.title}</option>)}
              </select>
              <Button variant="primary" className="mt-3" disabled={running || !fault} onClick={() => fault && onRun(feature, fault.id, `Injected fault: ${fault.title}`, fault.faultClass, { kind: "fault", id: fault.id })}>
                {running ? "A run is in progress" : "Run gate"}
              </Button>
            </>
          ) : (
            <>
              <select aria-label="Code mutant" value={mutantId} onChange={(e) => setMutantId(e.target.value)} className="mt-3 h-9 w-full rounded-md border border-line-strong px-2.5 text-sm">
                <option value="">Choose a mutant</option>
                {feature.mutants.map((m) => <option key={m.id} value={m.id}>{m.id}, line {m.line}: {m.operator}</option>)}
              </select>
              {mutant && (
                <div className="mt-3 rounded-md border border-line bg-subtle p-3">
                  <code className="num block overflow-x-auto whitespace-pre text-xs text-fg">{mutant.sourceLine}</code>
                  <p className="mt-2 text-sm text-fg-2">{mutant.description}.</p>
                </div>
              )}
              <Button variant="primary" className="mt-3" disabled={running || !mutant} onClick={() => mutant && onRun(feature, mutant.id, `Code mutant ${mutant.id}`, `Line ${mutant.line}: ${mutant.description}.`, { kind: "mutant", id: mutant.id })}>
                {running ? "A run is in progress" : "Run gate"}
              </Button>
            </>
          )}
        </div>
      </Section>

      {campaign && (
        <Section title={`What it missed: ${survivors.length} of ${campaign.mutants.total} code mutants`} description="Mutants that never changed behaviour in any test aren't proven harmless. Some need conditions we don't test.">
          <ul className="max-h-[320px] overflow-y-auto border-t border-line">
            {survivors.map((m) => (
              <li key={m.id} className="border-b border-line py-2.5 last:border-b-0">
                <div className="flex flex-wrap items-baseline justify-between gap-x-3">
                  <span className="text-sm font-medium">{m.description}</span>
                  <span className="num text-xs text-fg-3">{m.id} · line {m.line}</span>
                </div>
                <code className="num mt-1 block overflow-x-auto whitespace-pre text-xs text-fg-2">{m.sourceLine}</code>
                <div className="mt-1 text-xs text-fg-3">{m.survivorKind === "WITHIN_TOLERANCE" ? "Changed behaviour, stayed within tolerance" : "No behaviour change in any test"}</div>
              </li>
            ))}
          </ul>
        </Section>
      )}

      <Section title="The software contract" description="A third-party FMU can stand in for this feature's software if it offers these signals.">
        <div className="flex flex-wrap gap-x-10 gap-y-3 text-sm">
          <div><div className="text-xs text-fg-3">Inputs</div><div className="num mt-1 text-xs text-fg-2">{feature.inputNames.join(", ")}</div></div>
          <div><div className="text-xs text-fg-3">Outputs</div><div className="num mt-1 text-xs text-fg-2">{feature.outputNames.join(", ")}</div></div>
          <div><div className="text-xs text-fg-3">Step size</div><div className="num mt-1 text-xs text-fg-2">{feature.stepSeconds} s</div></div>
        </div>
      </Section>

      <p className="px-5 py-4 text-xs leading-[18px] text-fg-3 sm:px-8"><span className="font-medium text-fg-2">Limits of this model: </span>{feature.limitations} Results are computed live by the simulator, and every scenario is synthetic.</p>
    </>
  );
}
