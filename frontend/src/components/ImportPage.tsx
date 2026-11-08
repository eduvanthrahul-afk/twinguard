import { Download, FileUp, Trash2 } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { deleteRelease, fetchImportedReleases, fetchPackageSchema, fetchPackageTemplate, importRelease, PackageError } from "../api";
import type { ImportedRelease, PackageSchema } from "../api";
import { Button, PageHeader, Section } from "./ui";

interface Props {
  busy: boolean;
  onChanged: () => void;
  onOpenFeature: (feature: string) => void;
}

function formatValue(v: number, kind: string) {
  if (kind === "BOOLEAN") return v ? "true" : "false";
  return Number.isInteger(v) ? String(v) : String(Math.round(v * 10000) / 10000);
}

export default function ImportPage({ busy, onChanged, onOpenFeature }: Props) {
  const [schema, setSchema] = useState<PackageSchema[]>([]);
  const [feature, setFeature] = useState("braking");
  const [text, setText] = useState("");
  const [problems, setProblems] = useState<{ message: string; list: string[] } | null>(null);
  const [done, setDone] = useState<ImportedRelease | null>(null);
  const [saving, setSaving] = useState(false);
  const [imported, setImported] = useState<ImportedRelease[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const reload = () => fetchImportedReleases().then(setImported).catch((e) => setLoadError(e.message));

  useEffect(() => {
    fetchPackageSchema().then(setSchema).catch((e) => setLoadError(e.message));
    reload();
  }, []);

  const selected = schema.find((s) => s.feature === feature);
  const featureName = (id: string) => schema.find((s) => s.feature === id)?.name ?? id;

  const useTemplate = async () => {
    try {
      const template = await fetchPackageTemplate(feature);
      setText(JSON.stringify(template, null, 2));
      setProblems(null);
      setDone(null);
    } catch (e) {
      setProblems({ message: e instanceof Error ? e.message : "Couldn't load the template.", list: [] });
    }
  };

  const readFile = async (file: File | undefined) => {
    if (!file) return;
    setText(await file.text());
    setProblems(null);
    setDone(null);
    if (fileRef.current) fileRef.current.value = "";
  };

  const submit = async () => {
    setSaving(true);
    setProblems(null);
    setDone(null);
    try {
      const result = await importRelease(text);
      setDone(result);
      reload();
      onChanged();
    } catch (e) {
      if (e instanceof PackageError) setProblems({ message: e.problems.length > 1 ? `${e.problems.length} problems to fix:` : e.message, list: e.problems.length > 1 ? e.problems : [] });
      else setProblems({ message: e instanceof Error ? e.message : "The package couldn't be imported.", list: [] });
    } finally {
      setSaving(false);
    }
  };

  const remove = async (id: string) => {
    try {
      await deleteRelease(id);
      reload();
      onChanged();
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : "Couldn't delete the release.");
    }
  };

  return (
    <>
      <PageHeader
        title="Import a release"
        description="Describe a real OTA candidate as a release package: which feature it changes, which settings differ from approved v1, and any behaviour change it declares. TwinGuard checks it, then it appears beside the built-in releases, ready to gate."
      />

      <Section title="Release package" description="Paste JSON, open a .json file, or start from a template for the feature you're changing.">
        <div className="flex flex-wrap items-center gap-2">
          <label className="text-sm text-fg-2" htmlFor="pkg-feature">Feature</label>
          <select id="pkg-feature" value={feature} onChange={(e) => setFeature(e.target.value)} className="h-8 rounded-md border border-line-strong bg-canvas px-2 text-sm">
            {schema.map((s) => <option key={s.feature} value={s.feature}>{s.name}</option>)}
          </select>
          <Button onClick={useTemplate} disabled={!schema.length}><Download size={13} />Use template</Button>
          <Button onClick={() => fileRef.current?.click()}><FileUp size={13} />Open file</Button>
          <input ref={fileRef} type="file" accept=".json,application/json" className="hidden" onChange={(e) => readFile(e.target.files?.[0])} />
        </div>
        <textarea
          aria-label="Release package JSON"
          value={text}
          onChange={(e) => { setText(e.target.value); setDone(null); }}
          spellCheck={false}
          placeholder={'{\n  "format": "twinguard-release/1",\n  "id": "v2.5-brake-tune",\n  "title": "v2.5 Brake tuning",\n  "feature": "braking",\n  "changes": { "stopMargin": 1.1 }\n}'}
          className="num mt-3 h-72 w-full max-w-3xl resize-y rounded-md border border-line-strong bg-canvas p-3 text-xs leading-5"
        />
        <div className="mt-3 flex flex-wrap items-center gap-3">
          <Button variant="primary" disabled={!text.trim() || saving || busy} onClick={submit}>{saving ? "Checking…" : "Check and import"}</Button>
          {busy && <span className="text-xs text-fg-3">Wait for the current run to finish.</span>}
        </div>

        <div aria-live="polite">
          {problems && (
            <div role="alert" className="mt-4 max-w-3xl rounded-md bg-danger-bg p-4 text-sm text-danger">
              <p>{problems.message}</p>
              {problems.list.length > 0 && <ul className="mt-2 list-disc space-y-1 pl-5">{problems.list.map((p) => <li key={p}>{p}</li>)}</ul>}
            </div>
          )}
          {done && (
            <div className="mt-4 flex max-w-3xl flex-wrap items-center justify-between gap-3 rounded-md bg-ok-bg p-4 text-sm text-ok">
              <span>Imported <span className="font-medium">{done.pkg.title}</span>. It changes {done.changedSettings.join(", ")} and is ready to gate.</span>
              <Button onClick={() => onOpenFeature(done.pkg.feature)}>Open {featureName(done.pkg.feature)}</Button>
            </div>
          )}
        </div>
      </Section>

      {selected && (
        <Section
          title={`What a ${selected.name.toLowerCase()} package can change`}
          description={selected.usesAllowances
            ? "Declare an intended change with manifest.allowedEarlyS, allowedLateS (seconds) and allowedGapLossM (metres). Fixed safety rules still apply."
            : Object.keys(selected.declarableChanges).length
              ? "Declare an intended change by listing it in manifest.declaredChanges. Fixed safety rules still apply."
              : "This feature has no declarable changes."}
        >
          {!selected.usesAllowances && Object.keys(selected.declarableChanges).length > 0 && (
            <ul className="mb-4 text-sm">
              {Object.entries(selected.declarableChanges).map(([k, v]) => (
                <li key={k}><code className="num text-xs">{k}</code><span className="text-fg-2">: {v}</span></li>
              ))}
            </ul>
          )}
          <div className="max-h-[360px] overflow-auto">
            <table className="w-full min-w-[560px] text-sm">
              <thead>
                <tr className="border-b border-line text-left text-xs text-fg-3">
                  <th scope="col" className="py-2 pr-4 font-medium">Setting</th>
                  <th scope="col" className="px-4 font-medium">Meaning</th>
                  <th scope="col" className="px-4 text-right font-medium">v1</th>
                  <th scope="col" className="pl-4 text-right font-medium">Allowed</th>
                </tr>
              </thead>
              <tbody>
                {selected.settings.map((s) => (
                  <tr key={s.key} className="border-b border-line last:border-b-0">
                    <td className="num py-2 pr-4 text-xs">{s.key}</td>
                    <td className="px-4 text-fg-2">{s.label === s.key ? "" : s.label}</td>
                    <td className="num px-4 text-right text-xs">{formatValue(s.v1, s.kind)}</td>
                    <td className="num pl-4 text-right text-xs text-fg-2">{s.kind === "BOOLEAN" ? "true / false" : `${formatValue(s.min, s.kind)} to ${formatValue(s.max, s.kind)}${s.kind === "INTEGER" ? ", whole" : ""}`}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Section>
      )}

      <Section title={`Imported releases · ${imported.length}`} description="Kept across restarts. Importing a package with the same id replaces it.">
        {loadError && <p role="alert" className="mb-3 text-sm text-danger">{loadError}</p>}
        {imported.length === 0 ? (
          <p className="text-sm text-fg-3">Nothing imported yet.</p>
        ) : (
          <ul className="max-w-3xl divide-y divide-line border-y border-line">
            {imported.map((r) => (
              <li key={r.pkg.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                <div className="min-w-0">
                  <div className="font-medium">{r.pkg.title}</div>
                  <div className="mt-0.5 text-xs text-fg-3">
                    <span className="num">{r.pkg.id}</span> · {featureName(r.pkg.feature)} · changes {r.changedSettings.join(", ")} · imported {new Date(r.importedAt).toLocaleString()}
                  </div>
                </div>
                <div className="flex items-center gap-1.5">
                  <Button onClick={() => onOpenFeature(r.pkg.feature)}>Open</Button>
                  <Button variant="ghost" aria-label={`Delete ${r.pkg.title}`} disabled={busy} className="w-8 !px-0" onClick={() => remove(r.pkg.id)}><Trash2 size={14} /></Button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Section>
    </>
  );
}
