import { useMemo, useState } from "react";
import type { Cell } from "../api";
import { Segmented } from "./ui";

const SPEED_BINS = 8;
const DIST_BINS = 8;
const FRICTION_LABELS = ["0.2–0.4", "0.4–0.6", "0.6–0.8", "0.8–1.0"];
const LOAD_LABELS = ["0–25%", "25–50%", "50–75%", "75–100%"];

const speedRange = (i: number) => `${Math.round(20 + i * 13.75)}–${Math.round(20 + (i + 1) * 13.75)}`;
const distRange = (i: number) => `${Math.round(10 + i * 18.75)}–${Math.round(10 + (i + 1) * 18.75)}`;

interface Agg {
  tests: number;
  failures: number;
}

type Mode = "obstacle" | "ghost";

const selectClass = "h-8 rounded-md border border-line-strong px-2 text-xs font-medium text-fg";

export default function CoverageMap({ cells, coveredCells, totalCells }: { cells: Cell[]; coveredCells: number; totalCells: number }) {
  const [mode, setMode] = useState<Mode>("obstacle");
  const [friction, setFriction] = useState(-1);
  const [load, setLoad] = useState(-1);
  const [hover, setHover] = useState<{ s: number; d: number; agg?: Agg } | null>(null);

  const { grid, maxTests, failures } = useMemo(() => {
    const map = new Map<string, Agg>();
    let max = 1;
    const totals = { obstacle: 0, ghost: 0 };
    for (const c of cells) {
      totals[c.ghost ? "ghost" : "obstacle"] += c.failures;
      if (c.ghost !== (mode === "ghost")) continue;
      if (mode === "obstacle" && friction >= 0 && c.friction !== friction) continue;
      if (mode === "obstacle" && load >= 0 && c.load !== load) continue;
      const key = `${c.speed}-${c.distance}`;
      const a = map.get(key) ?? { tests: 0, failures: 0 };
      a.tests += c.tests;
      a.failures += c.failures;
      map.set(key, a);
      max = Math.max(max, a.tests);
    }
    return { grid: map, maxTests: max, failures: totals };
  }, [cells, mode, friction, load]);

  const background = (agg?: Agg) => {
    if (!agg) return "transparent";
    if (agg.failures > 0) {
      const share = 30 + Math.round((agg.failures / agg.tests) * 70);
      return `color-mix(in srgb, var(--fail) ${share}%, var(--bg))`;
    }
    const depth = 12 + Math.round((Math.log(agg.tests + 1) / Math.log(maxTests + 1)) * 58);
    return `color-mix(in srgb, var(--series-v1) ${depth}%, var(--bg))`;
  };

  const percent = totalCells ? (coveredCells / totalCells) * 100 : 0;

  return (
    <div>
      <div className="flex flex-wrap items-center gap-2">
        <Segmented<Mode>
          label="Scenario type"
          value={mode}
          onChange={setMode}
          options={[
            { value: "obstacle", label: `Real obstacle · ${failures.obstacle.toLocaleString()} failing` },
            { value: "ghost", label: `False radar return · ${failures.ghost.toLocaleString()} failing` },
          ]}
        />
        {mode === "obstacle" && (
          <>
            <select aria-label="Road friction" className={selectClass} value={friction} onChange={(e) => setFriction(Number(e.target.value))}>
              <option value={-1}>All friction</option>
              {FRICTION_LABELS.map((label, i) => <option key={label} value={i}>Friction {label}</option>)}
            </select>
            <select aria-label="Media load" className={selectClass} value={load} onChange={(e) => setLoad(Number(e.target.value))}>
              <option value={-1}>All media load</option>
              {LOAD_LABELS.map((label, i) => <option key={label} value={i}>Media load {label}</option>)}
            </select>
          </>
        )}
        <span className="num ml-auto text-xs text-fg-2">{coveredCells} / {totalCells} cells · {percent.toFixed(1)}%</span>
      </div>

      <div className="mt-4 max-w-[860px]">
        <p className="mb-1.5 text-xs text-fg-3">Distance to obstacle (m)</p>
        <div className="grid gap-[2px]" style={{ gridTemplateColumns: `56px repeat(${SPEED_BINS}, minmax(0, 1fr))` }} onMouseLeave={() => setHover(null)}>
          {Array.from({ length: DIST_BINS }, (_, row) => DIST_BINS - 1 - row).map((d) => (
            <div key={d} className="contents">
              <div className="num flex items-center justify-end pr-2.5 text-[11px] text-fg-3">{distRange(d)}</div>
              {Array.from({ length: SPEED_BINS }, (_, s) => {
                const agg = grid.get(`${s}-${d}`);
                const paleFailure = agg ? agg.failures / agg.tests < 0.45 : false;
                return (
                  <div
                    key={s}
                    onMouseEnter={() => setHover({ s, d, agg })}
                    className="flex h-7 items-center justify-center rounded-[3px] text-[15px] font-medium leading-none transition-colors duration-150 hover:outline hover:outline-1 hover:outline-fg sm:h-8"
                    style={{ background: background(agg), color: paleFailure ? "var(--fg)" : "#ffffff", boxShadow: agg ? undefined : "inset 0 0 0 1px var(--border)" }}
                  >
                    {agg && agg.failures > 0 && <span aria-hidden>×</span>}
                  </div>
                );
              })}
            </div>
          ))}
          <div />
          {Array.from({ length: SPEED_BINS }, (_, s) => (
            <div key={s} className="num pt-1 text-center text-[11px] text-fg-3">{speedRange(s)}</div>
          ))}
        </div>
        <p className="mt-1 pl-14 text-center text-xs text-fg-3">Vehicle speed (km/h)</p>
      </div>

      <div className="mt-3 flex max-w-[860px] flex-wrap items-center justify-between gap-x-5 gap-y-2 text-xs text-fg-2">
        <div className="flex flex-wrap items-center gap-4">
          <span className="flex items-center gap-1.5"><span className="h-2.5 w-2.5 rounded-[2px]" style={{ boxShadow: "inset 0 0 0 1px var(--border-strong)" }} /> Not tested</span>
          <span className="flex items-center gap-1.5"><span className="h-2.5 w-2.5 rounded-[2px]" style={{ background: "color-mix(in srgb, var(--series-v1) 50%, var(--bg))" }} /> Passed, darker means more tests</span>
          <span className="flex items-center gap-1.5"><span className="flex h-2.5 w-2.5 items-center justify-center rounded-[2px] text-[9px] leading-none text-white" style={{ background: "var(--fail)" }}>×</span> Confirmed failure</span>
        </div>
        <span className="num text-fg-3">
          {hover ? `${speedRange(hover.s)} km/h · ${distRange(hover.d)} m · ${hover.agg ? `${hover.agg.tests} tests, ${hover.agg.failures} failing` : "not tested"}` : "Hover a cell for details"}
        </span>
      </div>
    </div>
  );
}
