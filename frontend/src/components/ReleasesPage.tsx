import { ChevronRight } from "lucide-react";
import type { RunState } from "../App";
import type { ReleaseView } from "../api";
import { Button, PageHeader, StatusPill } from "./ui";

interface Props {
  releases: ReleaseView[];
  results: Record<string, RunState>;
  activeRun: RunState | null;
  onRun: (release: ReleaseView) => void;
  onOpen: (state: RunState) => void;
  onOpenActive: () => void;
}

export default function ReleasesPage({ releases, results, activeRun, onRun, onOpen, onOpenActive }: Props) {
  const anyRunning = activeRun?.running ?? false;
  return (
    <>
      <PageHeader title="Releases" description="OTA candidates tested against approved v1, on identical road conditions and sensor noise." />
      <div className="overflow-x-auto">
        <table className="w-full min-w-[780px] text-sm">
          <thead>
            <tr className="border-b border-line text-left text-xs text-fg-3">
              <th scope="col" className="py-2.5 pl-5 pr-4 font-medium sm:pl-8">Release</th>
              <th scope="col" className="px-4 font-medium">Component</th>
              <th scope="col" className="px-4 font-medium">Changed settings</th>
              <th scope="col" className="px-4 font-medium">Status</th>
              <th scope="col" className="px-4 text-right font-medium">Failing</th>
              <th scope="col" className="pl-4 pr-5 sm:pr-8"><span className="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            {releases.map((r) => {
              const isActive = activeRun?.running && activeRun.targetId === r.id;
              const result = results[r.id];
              const status = isActive ? "RUNNING" : result?.error ? "FAILED" : result?.report?.verdict ?? "IDLE";
              const canOpen = isActive || !!result;
              return (
                <tr key={r.id} className="group border-b border-line transition-colors hover:bg-subtle">
                  <td className="py-3.5 pl-5 pr-4 sm:pl-8">
                    <div className="font-medium">{r.title}</div>
                    <div className="mt-0.5 max-w-[46ch] text-fg-3">{r.summary}</div>
                  </td>
                  <td className="px-4 text-fg-2">{r.component}</td>
                  <td className="num px-4 text-xs text-fg-2">
                    {r.changedSettings.map((s) => <div key={s}>{s}</div>)}
                  </td>
                  <td className="px-4"><StatusPill status={status} /></td>
                  <td className="num px-4 text-right text-fg-2">
                    {result?.report ? `${result.report.failingScenarios.toLocaleString()} / ${result.report.evaluations.toLocaleString()}` : "–"}
                  </td>
                  <td className="pl-4 pr-5 sm:pr-8">
                    <div className="flex items-center justify-end gap-1.5">
                      <Button variant={result ? "secondary" : "primary"} disabled={anyRunning} onClick={() => onRun(r)}>
                        {isActive ? "Running" : result ? "Run again" : "Run gate"}
                      </Button>
                      <Button
                        variant="ghost"
                        aria-label={`Open results for ${r.title}`}
                        disabled={!canOpen || (anyRunning && !isActive)}
                        className="w-8 !px-0"
                        onClick={() => (isActive ? onOpenActive() : onOpen(result))}
                      >
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
      <p className="px-5 py-4 text-xs text-fg-3 sm:px-8">Results are computed live by the simulator. Road scenarios and sensor data are synthetic, not recorded from a real vehicle.</p>
    </>
  );
}
