import { ChevronRight, RotateCw } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { fetchHistory } from "../api";
import type { FeatureView, HistoryEntry } from "../api";
import { Button, PageHeader, StatusPill } from "./ui";

interface Props {
  features: FeatureView[];
  busy: boolean;
  refreshKey: number;
  onOpen: (entry: HistoryEntry) => void;
}

function duration(entry: HistoryEntry) {
  if (!entry.finishedAt) return "–";
  const s = (new Date(entry.finishedAt).getTime() - new Date(entry.startedAt).getTime()) / 1000;
  return s < 60 ? `${s.toFixed(0)} s` : `${(s / 60).toFixed(1)} min`;
}

export default function HistoryPage({ features, busy, refreshKey, onOpen }: Props) {
  const [entries, setEntries] = useState<HistoryEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState("all");

  const load = useCallback(() => {
    fetchHistory().then((e) => { setEntries(e); setError(null); }).catch((e) => setError(e.message));
  }, []);

  useEffect(load, [load, refreshKey]);

  const featureName = (e: HistoryEntry) => (e.scope === "gate" ? "Collision braking" : features.find((f) => f.id === e.feature)?.name ?? e.feature ?? "");
  const shown = (entries ?? []).filter((e) => filter === "all" || (filter === "braking" ? e.scope === "gate" : e.feature === filter));

  return (
    <>
      <PageHeader
        title="History"
        description="Every release decision, saved with its evidence. Open one to see its findings and replays again."
        actions={<Button onClick={load}><RotateCw size={13} />Refresh</Button>}
      />
      <div className="flex flex-wrap items-center gap-2 border-b border-line px-5 py-3 sm:px-8">
        <label htmlFor="history-filter" className="text-sm text-fg-2">Feature</label>
        <select id="history-filter" value={filter} onChange={(e) => setFilter(e.target.value)} className="h-8 rounded-md border border-line-strong bg-canvas px-2 text-sm">
          <option value="all">All features</option>
          <option value="braking">Collision braking</option>
          {features.map((f) => <option key={f.id} value={f.id}>{f.name}</option>)}
        </select>
      </div>
      {error && <p role="alert" className="m-5 rounded-md bg-danger-bg p-4 text-sm text-danger sm:mx-8">{error}</p>}
      {!entries && !error && <p className="px-5 py-6 text-sm text-fg-3 sm:px-8">Loading…</p>}
      {entries && shown.length === 0 && <p className="px-5 py-6 text-sm text-fg-3 sm:px-8">No runs yet. Results appear here as soon as a gate run finishes.</p>}
      {shown.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[820px] text-sm">
            <thead>
              <tr className="border-b border-line text-left text-xs text-fg-3">
                <th scope="col" className="py-2.5 pl-5 pr-4 font-medium sm:pl-8">Started</th>
                <th scope="col" className="px-4 font-medium">Tested</th>
                <th scope="col" className="px-4 font-medium">Feature</th>
                <th scope="col" className="px-4 font-medium">Decision</th>
                <th scope="col" className="px-4 text-right font-medium">Failing</th>
                <th scope="col" className="px-4 text-right font-medium">Took</th>
                <th scope="col" className="pl-4 pr-5 sm:pr-8"><span className="sr-only">Open</span></th>
              </tr>
            </thead>
            <tbody>
              {shown.map((e) => {
                const status = e.status === "RUNNING" ? "RUNNING" : e.status === "FAILED" ? "FAILED" : e.verdict ?? "IDLE";
                return (
                  <tr key={e.id} className="border-b border-line transition-colors hover:bg-subtle">
                    <td className="num whitespace-nowrap py-3 pl-5 pr-4 text-xs text-fg-2 sm:pl-8">{new Date(e.startedAt).toLocaleString()}</td>
                    <td className="px-4">
                      <div className="font-medium">{e.title}</div>
                      {e.error ? <div className="mt-0.5 max-w-[52ch] text-xs text-danger">{e.error}</div> : e.summary && <div className="mt-0.5 max-w-[52ch] text-xs text-fg-3">{e.summary}</div>}
                    </td>
                    <td className="px-4 text-fg-2">{featureName(e)}</td>
                    <td className="px-4"><StatusPill status={status} /></td>
                    <td className="num px-4 text-right text-fg-2">{e.failing != null && e.evaluations != null ? `${e.failing.toLocaleString()} / ${e.evaluations.toLocaleString()}` : "–"}</td>
                    <td className="num px-4 text-right text-fg-2">{duration(e)}</td>
                    <td className="pl-4 pr-5 sm:pr-8">
                      <div className="flex justify-end">
                        <Button variant="ghost" aria-label={`Open ${e.title}`} disabled={e.status !== "DONE" || busy} className="w-8 !px-0" onClick={() => onOpen(e)}>
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
      )}
    </>
  );
}
