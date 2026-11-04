import { useMemo, useState } from "react";
import type { FaultView, MutantView, RunRequest, SettingView } from "../api";
import { Button, PageHeader, Segmented } from "./ui";

interface Props {
  settings: SettingView[];
  faults: FaultView[];
  mutants: MutantView[];
  running: boolean;
  onRun: (id: string, title: string, summary: string, request: RunRequest) => void;
}

const decimals = (s: SettingView) => (Number.isInteger(s.step) ? 0 : 2);
const show = (value: number, s: SettingView) => `${value.toFixed(decimals(s))}${s.unit ? ` ${s.unit}` : ""}`;

export default function FaultLabPage({ settings, faults, mutants, running, onRun }: Props) {
  const [mode, setMode] = useState<"custom" | "catalog" | "code">("custom");
  const [mutantId, setMutantId] = useState("");
  const mutant = mutants.find((m) => m.id === mutantId);
  const methods = useMemo(() => {
    const map = new Map<string, MutantView[]>();
    for (const m of mutants) map.set(m.method, [...(map.get(m.method) ?? []), m]);
    return [...map.entries()];
  }, [mutants]);
  const initial = useMemo(() => Object.fromEntries(settings.map((s) => [s.key, s.approved])), [settings]);
  const [values, setValues] = useState<Record<string, number>>(initial);
  const [faultId, setFaultId] = useState("");

  const changed = settings.filter((s) => values[s.key] !== s.approved);
  const classes = useMemo(() => {
    const map = new Map<string, FaultView[]>();
    for (const f of faults) map.set(f.classLabel, [...(map.get(f.classLabel) ?? []), f]);
    return [...map.entries()];
  }, [faults]);
  const selected = faults.find((f) => f.id === faultId);

  const runCustom = () => {
    const payload = Object.fromEntries(changed.map((s) => [s.key, values[s.key]]));
    const summary = changed.map((s) => `${s.label}: ${show(s.approved, s)} to ${show(values[s.key], s)}`).join(". ");
    onRun("custom", "Custom fault", summary, { kind: "custom", settings: payload });
  };

  return (
    <>
      <PageHeader
        title="Fault Lab"
        description="Create a bug and see whether the gate catches it. Nothing is scripted, so anyone in the room can choose."
        actions={<Segmented value={mode} onChange={setMode} label="Fault source" options={[{ value: "custom", label: "Custom bug" }, { value: "catalog", label: `Catalog · ${faults.length}` }, { value: "code", label: `Code mutants · ${mutants.length}` }]} />}
      />

      {mode === "custom" ? (
        <div className="grid lg:grid-cols-[minmax(0,1fr)_340px]">
          <div className="min-w-0 divide-y divide-line border-b border-line lg:border-b-0">
            {settings.map((s) => {
              const value = values[s.key];
              const isChanged = value !== s.approved;
              return (
                <div key={s.key} className="grid items-center gap-x-6 gap-y-1 px-5 py-3.5 sm:grid-cols-[minmax(0,1fr)_minmax(180px,1fr)_96px] sm:px-8">
                  <label htmlFor={s.key} className="text-sm font-medium">{s.label}</label>
                  <input
                    id={s.key}
                    type="range"
                    min={s.min}
                    max={s.max}
                    step={s.step}
                    value={value}
                    onChange={(e) => setValues((v) => ({ ...v, [s.key]: Number(e.target.value) }))}
                    className="w-full"
                  />
                  <button
                    type="button"
                    disabled={!isChanged}
                    title={isChanged ? "Reset to approved" : undefined}
                    onClick={() => setValues((v) => ({ ...v, [s.key]: s.approved }))}
                    className={`num text-left text-xs sm:text-right ${isChanged ? "font-medium text-fg underline decoration-dotted underline-offset-2" : "text-fg-3"}`}
                  >
                    {show(value, s)}
                  </button>
                </div>
              );
            })}
          </div>

          <aside className="border-line bg-subtle px-5 py-6 sm:px-8 lg:sticky lg:top-0 lg:h-[calc(100vh-117px)] lg:border-l lg:px-6">
            <h2 className="text-sm font-medium">Diff against approved v1</h2>
            <div className="num mt-3 min-h-[96px] rounded-md border border-line bg-canvas p-3 text-xs leading-6">
              {changed.length === 0 && <p className="text-fg-3">No changes yet. Move a slider.</p>}
              {changed.map((s) => (
                <div key={s.key} className="mb-2 last:mb-0">
                  <div className="truncate text-fg-3">{s.key}</div>
                  <div className="text-danger">- {show(s.approved, s)}</div>
                  <div className="text-ok">+ {show(values[s.key], s)}</div>
                </div>
              ))}
            </div>
            <div className="mt-3 flex gap-2">
              <Button variant="primary" className="flex-1" disabled={running || changed.length === 0} onClick={runCustom}>
                {running ? "A run is in progress" : "Run gate"}
              </Button>
              <Button disabled={changed.length === 0} onClick={() => setValues(initial)}>Reset</Button>
            </div>
            <p className="mt-3 text-xs leading-[18px] text-fg-3">Small changes can stay inside the tolerances and pass. That's a stated limit of the gate.</p>
          </aside>
        </div>
      ) : mode === "code" ? (
        <div className="max-w-2xl px-5 py-6 sm:px-8">
          <label htmlFor="mutant" className="text-sm font-medium">Code mutant</label>
          <p className="mt-0.5 text-sm text-fg-2">Each is a real bug, created by rewriting one instruction in the compiled braking code.</p>
          <select id="mutant" value={mutantId} onChange={(e) => setMutantId(e.target.value)} className="mt-2 h-9 w-full rounded-md border border-line-strong px-2.5 text-sm">
            <option value="">Choose a mutant</option>
            {methods.map(([method, members]) => (
              <optgroup key={method} label={`${method}()`}>
                {members.map((m) => <option key={m.id} value={m.id}>{m.id}, line {m.line}: {m.operator}</option>)}
              </optgroup>
            ))}
          </select>
          {mutant && (
            <div className="mt-3 rounded-md border border-line bg-subtle p-3">
              <div className="num text-xs text-fg-3">BrakingLogic.java:{mutant.line}, {mutant.method}()</div>
              <code className="num mt-1.5 block overflow-x-auto whitespace-pre text-xs text-fg">{mutant.sourceLine}</code>
              <p className="mt-2 text-sm text-fg-2">{mutant.description}.</p>
            </div>
          )}
          <Button
            variant="primary"
            className="mt-4"
            disabled={running || !mutant}
            onClick={() => mutant && onRun(mutant.id, `Code mutant ${mutant.id}`, `BrakingLogic.java line ${mutant.line}: ${mutant.description}.`, { kind: "mutant", id: mutant.id })}
          >
            {running ? "A run is in progress" : "Run gate"}
          </Button>
        </div>
      ) : (
        <div className="max-w-xl px-5 py-6 sm:px-8">
          <label htmlFor="fault" className="text-sm font-medium">Fault</label>
          <select id="fault" value={faultId} onChange={(e) => setFaultId(e.target.value)} className="mt-1.5 h-9 w-full rounded-md border border-line-strong px-2.5 text-sm">
            <option value="">Choose a fault</option>
            {classes.map(([label, members]) => (
              <optgroup key={label} label={label}>
                {members.map((f) => <option key={f.id} value={f.id}>{f.title}</option>)}
              </optgroup>
            ))}
          </select>
          {selected && <p className="mt-2 text-sm text-fg-2">{selected.description}.</p>}
          <Button
            variant="primary"
            className="mt-4"
            disabled={running || !selected}
            onClick={() => selected && onRun(selected.id, selected.title, selected.description, { kind: "fault", id: selected.id })}
          >
            {running ? "A run is in progress" : "Run gate"}
          </Button>
        </div>
      )}
    </>
  );
}
