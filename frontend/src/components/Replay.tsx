import { Pause, Play, RotateCcw } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { CartesianGrid, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { Theme } from "../App";
import type { Counterexample, TracePoint } from "../api";
import { Button, Segmented, tooltipStyle } from "./ui";

const GHOST_START = 1.0;
const GHOST_VISIBLE_UNTIL = 1.3;
const LANE_HEIGHT = 92;
const CAR_W = 36;
const CAR_H = 16;

function indexAt(trace: TracePoint[], t: number): number {
  let lo = 0;
  let hi = trace.length - 1;
  if (hi < 0) return 0;
  if (t >= trace[hi].t) return hi;
  while (lo < hi) {
    const mid = Math.floor((lo + hi) / 2);
    if (trace[mid].t < t) lo = mid + 1;
    else hi = mid;
  }
  return lo;
}

/** Distance travelled at each trace point. */
function travelOf(trace: TracePoint[], example: Counterexample): number[] {
  if (example.scenario.type !== "GHOST") {
    return trace.map((p) => example.scenario.distanceM - p.gapM);
  }
  const out: number[] = [];
  let sum = 0;
  trace.forEach((p, i) => {
    if (i > 0) sum += ((p.speedKph / 3.6) * (p.t - trace[i - 1].t));
    out.push(sum);
  });
  return out;
}

interface Lane {
  label: string;
  color: string;
  trace: TracePoint[];
  travel: number[];
  collided: boolean;
}

function drawLane(ctx: CanvasRenderingContext2D, css: CSSStyleDeclaration, width: number, top: number, lane: Lane, t: number, example: Counterexample, maxTravel: number, ruler: boolean) {
  const token = (name: string) => css.getPropertyValue(name).trim();
  const left = CAR_W + 2;
  const right = width - 12;
  const y = top + 54;
  const toX = (metres: number) => left + (right - left) * Math.min(1, metres / maxTravel);
  const i = indexAt(lane.trace, t);
  const point = lane.trace[i];
  if (!point) return;
  const ghost = example.scenario.type === "GHOST";
  const atEnd = t >= lane.trace[lane.trace.length - 1].t;
  const x = toX(lane.travel[i]);
  const sans = '"Geist Variable", system-ui, sans-serif';
  const mono = '"Geist Mono Variable", Consolas, monospace';

  ctx.textBaseline = "alphabetic";
  ctx.font = `500 13px ${sans}`;
  ctx.fillStyle = token("--fg");
  ctx.textAlign = "left";
  ctx.fillText(lane.label, 0, top + 15);
  ctx.font = `12px ${mono}`;
  ctx.fillStyle = token("--fg-2");
  ctx.textAlign = "right";
  const gap = ghost ? "" : `${point.gapM.toFixed(1)} m  `;
  ctx.fillText(`${gap}${point.speedKph.toFixed(0)} km/h  brake ${(point.brakeLevel * 100).toFixed(0)}%`, width, top + 15);

  // Track
  ctx.fillStyle = token("--track");
  ctx.fillRect(left, y - 1, right - left, 2);

  // Distance ticks
  const step = maxTravel > 100 ? 50 : maxTravel > 40 ? 25 : 10;
  ctx.font = `10px ${mono}`;
  ctx.textAlign = "center";
  for (let m = 0; m <= maxTravel; m += step) {
    const tx = toX(m);
    ctx.fillStyle = token("--border-strong");
    ctx.fillRect(tx, y + 12, 1, 4);
    if (ruler) {
      ctx.fillStyle = token("--fg-3");
      ctx.fillText(`${m} m`, Math.max(14, tx), y + 28);
    }
  }

  // Path driven, drawn heavier once the brakes were applied
  const brakeIndex = lane.trace.findIndex((p) => p.brakeLevel > 0);
  const brakeX = brakeIndex >= 0 && brakeIndex <= i ? toX(lane.travel[brakeIndex]) : null;
  ctx.fillStyle = lane.color;
  ctx.globalAlpha = 0.45;
  ctx.fillRect(left, y - 1, x - left, 2);
  ctx.globalAlpha = 1;
  if (brakeX !== null) {
    ctx.fillRect(brakeX, y - 2, Math.max(0, x - brakeX), 4);
    ctx.fillRect(brakeX, y - 7, 1, 14);
    ctx.font = `11px ${sans}`;
    ctx.fillStyle = token("--fg-2");
    ctx.textAlign = "center";
    ctx.fillText("brake", brakeX, y - 11);
  }

  // Obstacle or false return
  if (!ghost) {
    ctx.fillStyle = token("--fg");
    ctx.fillRect(right, y - 15, 3, 30);
  } else if (t >= GHOST_START && t < GHOST_VISIBLE_UNTIL) {
    const gx = Math.min(right - 20, x + 90);
    ctx.save();
    ctx.setLineDash([3, 3]);
    ctx.strokeStyle = token("--fg-2");
    ctx.strokeRect(gx, y - 13, 20, 26);
    ctx.restore();
    ctx.font = `11px ${sans}`;
    ctx.fillStyle = token("--fg-2");
    ctx.textAlign = "center";
    ctx.fillText("false return", gx + 10, y - 18);
  }

  // Vehicle
  ctx.fillStyle = lane.color;
  ctx.beginPath();
  ctx.roundRect(x - CAR_W, y - CAR_H / 2, CAR_W, CAR_H, 4);
  ctx.fill();
  ctx.fillStyle = point.brakeLevel > 0 ? "#ff3b30" : "rgba(255,255,255,0.35)";
  ctx.fillRect(x - CAR_W + 1, y - 6, 2, 4);
  ctx.fillRect(x - CAR_W + 1, y + 2, 2, 4);

  // Outcome
  if (atEnd && !ghost) {
    ctx.font = `500 12px ${sans}`;
    ctx.textAlign = "right";
    if (lane.collided) {
      ctx.fillStyle = token("--danger");
      ctx.fillText(`Impact at ${point.speedKph.toFixed(0)} km/h`, right - 6, y - 19);
    } else {
      ctx.fillStyle = token("--ok");
      ctx.fillText(`Stopped ${point.gapM.toFixed(1)} m short`, right - 6, y - 19);
    }
  }
}

function Timeline({ title, unit, data, keys, time, maxY }: { title: string; unit: string; data: Record<string, number | null>[]; keys: [string, string]; time: number; maxY?: number }) {
  return (
    <div className="min-w-0">
      <p className="mb-2 text-xs font-medium text-fg-2">{title}</p>
      <div className="h-32">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={data} margin={{ top: 4, right: 4, left: -22, bottom: 0 }}>
            <CartesianGrid stroke="var(--border)" vertical={false} />
            <XAxis dataKey="t" type="number" domain={[0, "dataMax"]} tick={{ fill: "var(--fg-3)", fontSize: 10 }} tickLine={false} axisLine={{ stroke: "var(--border-strong)" }} tickFormatter={(v) => `${Number(Number(v).toFixed(1))}s`} tickCount={6} />
            <YAxis domain={[0, maxY ?? "auto"]} tick={{ fill: "var(--fg-3)", fontSize: 10 }} tickLine={false} axisLine={false} />
            <Tooltip {...tooltipStyle} labelFormatter={(v) => `${Number(v).toFixed(2)} s`} formatter={(v: number, name: string) => [`${Number(v).toFixed(0)} ${unit}`, name]} />
            <ReferenceLine x={time} stroke="var(--fg-3)" />
            <Line name="v1" type="stepAfter" dataKey={keys[0]} stroke="var(--series-v1)" strokeWidth={2} dot={false} isAnimationActive={false} connectNulls={false} />
            <Line name="v2" type="stepAfter" dataKey={keys[1]} stroke="var(--series-v2)" strokeWidth={2} dot={false} isAnimationActive={false} connectNulls={false} />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}

export default function Replay({ example, theme }: { example: Counterexample; theme: Theme }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const wrapRef = useRef<HTMLDivElement>(null);
  const timeRef = useRef(0);
  const [time, setTime] = useState(0);
  const [playing, setPlaying] = useState(true);
  const [rate, setRate] = useState(1);

  const { duration, v1Travel, v2Travel, maxTravel } = useMemo(() => {
    const last = (trace: TracePoint[]) => (trace.length ? trace[trace.length - 1].t : 0);
    const a = travelOf(example.v1Trace, example);
    const b = travelOf(example.v2Trace, example);
    const furthest = example.scenario.type === "GHOST"
      ? Math.max(a[a.length - 1] ?? 1, b[b.length - 1] ?? 1, 1)
      : example.scenario.distanceM;
    return { duration: Math.max(last(example.v1Trace), last(example.v2Trace)), v1Travel: a, v2Travel: b, maxTravel: furthest };
  }, [example]);

  const chartData = useMemo(() => {
    const longest = example.v1Trace.length >= example.v2Trace.length ? example.v1Trace : example.v2Trace;
    const step = Math.max(1, Math.floor(longest.length / 160));
    const v1End = example.v1Trace[example.v1Trace.length - 1]?.t ?? 0;
    const v2End = example.v2Trace[example.v2Trace.length - 1]?.t ?? 0;
    return longest.filter((_, i) => i % step === 0).map((p) => {
      const a = example.v1Trace[indexAt(example.v1Trace, p.t)];
      const b = example.v2Trace[indexAt(example.v2Trace, p.t)];
      return {
        t: p.t,
        v1Brake: p.t <= v1End ? a.brakeLevel * 100 : null,
        v2Brake: p.t <= v2End ? b.brakeLevel * 100 : null,
        v1Age: p.t <= v1End ? a.sensorAgeMs : null,
        v2Age: p.t <= v2End ? b.sensorAgeMs : null,
      };
    });
  }, [example]);

  const draw = useCallback(() => {
    const canvas = canvasRef.current;
    const wrap = wrapRef.current;
    if (!canvas || !wrap) return;
    const width = wrap.clientWidth;
    const height = LANE_HEIGHT * 2 + 8;
    const ratio = window.devicePixelRatio || 1;
    if (canvas.width !== Math.round(width * ratio)) {
      canvas.width = Math.round(width * ratio);
      canvas.height = Math.round(height * ratio);
      canvas.style.height = `${height}px`;
    }
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    const css = getComputedStyle(document.documentElement);
    ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
    ctx.clearRect(0, 0, width, height);
    const t = timeRef.current;
    drawLane(ctx, css, width, 0, { label: "v1 approved", color: css.getPropertyValue("--series-v1").trim(), trace: example.v1Trace, travel: v1Travel, collided: example.v1.collision }, t, example, maxTravel, false);
    drawLane(ctx, css, width, LANE_HEIGHT, { label: "v2 candidate", color: css.getPropertyValue("--series-v2").trim(), trace: example.v2Trace, travel: v2Travel, collided: example.v2.collision }, t, example, maxTravel, true);
  }, [example, v1Travel, v2Travel, maxTravel]);

  useEffect(() => {
    timeRef.current = 0;
    setTime(0);
    setPlaying(true);
  }, [example]);

  useEffect(() => {
    draw();
    const onResize = () => draw();
    window.addEventListener("resize", onResize);
    document.fonts?.ready.then(draw);
    return () => window.removeEventListener("resize", onResize);
  }, [draw, theme]);

  useEffect(() => {
    if (!playing) return;
    let frame = 0;
    let previous = performance.now();
    let lastUi = 0;
    const tick = (now: number) => {
      const dt = (now - previous) / 1000;
      previous = now;
      timeRef.current = Math.min(duration, timeRef.current + dt * rate);
      draw();
      if (now - lastUi > 100) {
        lastUi = now;
        setTime(timeRef.current);
      }
      if (timeRef.current >= duration) {
        setTime(duration);
        setPlaying(false);
        return;
      }
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [playing, rate, duration, draw]);

  const onScrub = (value: number) => {
    timeRef.current = value;
    setTime(value);
    setPlaying(false);
    draw();
  };

  const toggle = () => {
    if (timeRef.current >= duration) {
      timeRef.current = 0;
      setTime(0);
    }
    setPlaying((p) => !p);
  };

  const finished = !playing && time >= duration;

  return (
    <div>
      <div ref={wrapRef} className="w-full">
        <canvas ref={canvasRef} role="img" aria-label={`Replay of both versions. ${example.primary.message}`} className="block w-full" />
      </div>

      <div className="mt-2 flex flex-wrap items-center gap-3">
        <Button aria-label={playing ? "Pause" : finished ? "Replay" : "Play"} className="w-8 !px-0" onClick={toggle}>
          {playing ? <Pause size={14} /> : finished ? <RotateCcw size={14} /> : <Play size={14} />}
        </Button>
        <input aria-label="Replay time" type="range" min={0} max={duration} step={0.02} value={time} onChange={(e) => onScrub(Number(e.target.value))} className="min-w-[120px] flex-1" />
        <span className="num text-xs text-fg-2">{time.toFixed(2)} / {duration.toFixed(2)} s</span>
        <Segmented<number> label="Replay speed" value={rate} onChange={setRate} options={[{ value: 1, label: "1×" }, { value: 0.5, label: "0.5×" }, { value: 0.25, label: "0.25×" }]} />
      </div>

      <div className="mt-6 flex items-center gap-4 text-xs text-fg-2">
        <span className="flex items-center gap-1.5"><span className="h-0.5 w-3" style={{ background: "var(--series-v1)" }} /> v1 approved</span>
        <span className="flex items-center gap-1.5"><span className="h-0.5 w-3" style={{ background: "var(--series-v2)" }} /> v2 candidate</span>
      </div>
      <div className="mt-3 grid gap-6 md:grid-cols-2">
        <Timeline title="Brake request (%)" unit="%" data={chartData} keys={["v1Brake", "v2Brake"]} time={time} maxY={100} />
        <Timeline title="Age of radar data at the braking ECU (ms)" unit="ms" data={chartData} keys={["v1Age", "v2Age"]} time={time} />
      </div>
    </div>
  );
}
