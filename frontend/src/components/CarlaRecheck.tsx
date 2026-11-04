import { Check, LoaderCircle, Minus, TriangleAlert } from "lucide-react";
import { useEffect, useState } from "react";
import { FINDING_LABELS, fetchCarlaStatus, runRecheck } from "../api";
import type { CarlaStatus, RecheckReport } from "../api";
import { Button, Section } from "./ui";

const outcome = (collision: boolean, gap: number, impact: number) =>
  collision ? `Impact at ${impact.toFixed(0)} km/h` : `Stopped ${gap.toFixed(1)} m short`;

export default function CarlaRecheck({ runId, failures }: { runId: string; failures: number }) {
  const [status, setStatus] = useState<CarlaStatus | null>(null);
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<RecheckReport | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    setResult(null);
    setError(null);
    fetchCarlaStatus().then((s) => active && setStatus(s)).catch((e) => active && setError(e.message));
    return () => {
      active = false;
    };
  }, [runId]);

  const start = async () => {
    setRunning(true);
    setError(null);
    try {
      setResult(await runRecheck(runId));
    } catch (e) {
      setError(e instanceof Error ? e.message : "The re-check failed.");
    } finally {
      setRunning(false);
    }
  };

  const connected = status?.connected ?? false;

  return (
    <Section
      title="CARLA re-check"
      description={failures > 0
        ? "These results come from the fast simulator. This step replays each minimal failure with the same braking software on CARLA's vehicle physics, to test whether it holds up."
        : "This run had no failures, so there's nothing to replay on CARLA. Only failures are re-checked, so an approval comes from the fast simulator alone."}
      actions={failures > 0 ? (
        <Button variant="primary" disabled={!connected || running} onClick={start}>
          {running && <LoaderCircle size={13} className="spin" />}
          {running ? "Re-checking, about 2 min" : "Re-check in CARLA"}
        </Button>
      ) : undefined}
    >
      <div className="flex flex-wrap items-center gap-2 text-sm text-fg-2">
        {!status && "Checking the connection…"}
        {status && !connected && <><Minus size={14} className="text-fg-3" /> CARLA isn't connected. Run start-carla.ps1, then reload this page.</>}
        {status && connected && !status.testDouble && status.map && status.calibrated && <><Check size={14} className="text-ok" /> Connected to CARLA {status.version} on {status.map}. Braking is calibrated for road friction 0.2 to 0.9.</>}
        {status && connected && !status.testDouble && status.map && !status.calibrated && <span className="inline-flex items-center gap-1.5 rounded bg-warn-bg px-2 py-1 text-xs font-medium text-warn"><TriangleAlert size={13} /> Connected to CARLA {status.version}, but braking isn't calibrated, so results can't be compared. Run calibrate.py first.</span>}
        {status && connected && status.testDouble && <span className="inline-flex items-center gap-1.5 rounded bg-warn-bg px-2 py-1 text-xs font-medium text-warn"><TriangleAlert size={13} /> Connected to a test double, not real CARLA. Results only prove that the connection works.</span>}
      </div>

      {error && <p role="alert" className="mt-3 rounded-md bg-danger-bg p-3 text-sm text-danger">{error}</p>}

      {result && (
        <div className="mt-4">
          <p className="text-sm font-medium tabular-nums">
            {result.reproduced} of {result.checked} failures reproduced {result.testDouble ? "on the test double" : `on CARLA ${result.version}`}{result.inconclusive > 0 ? `, ${result.inconclusive} inconclusive` : ""}, in {(result.computeMs / 1000).toFixed(1)} s
          </p>
          <p className="mt-1 text-xs leading-[18px] text-fg-2 tabular-nums">
            Control: running v1 against an identical v1 raised findings on {result.controlFalseAlarms} of {result.checked} scenarios{result.controlFalseAlarms > 0 ? ", so small differences here may come from CARLA, not the release" : ""}. This re-check is advisory, and doesn't change the release decision.
          </p>
          <div className="mt-2 overflow-x-auto">
            <table className="w-full min-w-[640px] text-sm">
              <thead className="text-left text-xs text-fg-3">
                <tr className="border-b border-line">
                  <th className="py-2 pr-4 font-medium">Finding</th>
                  <th className="pr-4 font-medium">Result</th>
                  <th className="pr-4 font-medium">v1 approved</th>
                  <th className="font-medium">v2 candidate</th>
                </tr>
              </thead>
              <tbody>
                {result.items.map((item) => (
                  <tr key={item.findingType} className="border-b border-line last:border-b-0">
                    <td className="py-2 pr-4">{FINDING_LABELS[item.findingType] ?? item.findingType}</td>
                    <td className={`pr-4 ${item.reproduced ? "text-danger" : item.inconclusive ? "text-warn" : "text-fg-2"}`}>
                      {item.reproduced ? "Reproduced" : item.inconclusive ? "Inconclusive" : "Not reproduced"}
                      {item.inconclusive && <span className="block text-xs text-fg-3">v1 against v1 raised this too</span>}
                      {!item.withinCalibration && <span className="block text-xs text-warn">Friction {item.scenario.friction.toFixed(2)} is outside calibration</span>}
                    </td>
                    <td className="pr-4 text-fg-2 tabular-nums">{item.scenario.type === "GHOST" ? (item.v1.braked ? "Braked" : "Did not brake") : outcome(item.v1.collision, item.v1.minGapM, item.v1.impactKph)}</td>
                    <td className="text-fg-2 tabular-nums">{item.scenario.type === "GHOST" ? (item.v2.braked ? "Braked" : "Did not brake") : outcome(item.v2.collision, item.v2.minGapM, item.v2.impactKph)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </Section>
  );
}
