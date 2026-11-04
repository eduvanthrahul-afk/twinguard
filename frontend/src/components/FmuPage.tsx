import { TriangleAlert } from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import { fetchFmus } from "../api";
import type { FeatureView, FmuEntry } from "../api";
import { Button, PageHeader, Section } from "./ui";

interface Props {
  features: FeatureView[];
  running: boolean;
  error: string | null;
  onRunBraking: (baseline: string, candidate: string) => void;
  onRunFeature: (feature: FeatureView, baseline: string, candidate: string) => void;
}

export default function FmuPage({ features, running, error, onRunBraking, onRunFeature }: Props) {
  const [files, setFiles] = useState<FmuEntry[]>([]);
  const [folder, setFolder] = useState("");
  const [loadError, setLoadError] = useState<string | null>(null);
  const [baseline, setBaseline] = useState("");
  const [candidate, setCandidate] = useState("");

  const refresh = useCallback(() => {
    fetchFmus()
      .then((r) => {
        setFiles(r.files);
        setFolder(r.folder);
        setLoadError(null);
      })
      .catch((e) => setLoadError(e.message));
  }, []);

  useEffect(refresh, [refresh]);

  const usable = useMemo(() => files.filter((f) => f.usable), [files]);
  const base = usable.find((f) => f.file === baseline);
  const compatible = usable.filter((f) => f.feature === base?.feature && f.file !== baseline);
  const label = (id: string | null) => (id === "braking" ? "Forward collision braking" : features.find((f) => f.id === id)?.name ?? "No matching feature");

  const run = () => {
    if (!base || !candidate) return;
    if (base.feature === "braking") {
      onRunBraking(baseline, candidate);
      return;
    }
    const feature = features.find((f) => f.id === base.feature);
    if (feature) onRunFeature(feature, baseline, candidate);
  };

  return (
    <>
      <PageHeader
        title="Your software"
        description="Test software TwinGuard didn't write. Any FMI 2.0 Co-Simulation FMU with the right signals can run in the same virtual car, against the same safety rules."
        actions={<Button onClick={refresh}>Refresh</Button>}
      />

      <Section title="Compare two versions" description="TwinGuard treats each file as a black box, and compares how the two behave.">
        <div className="grid max-w-3xl gap-3 sm:grid-cols-2">
          <div>
            <label htmlFor="fmu-baseline" className="text-sm font-medium">Approved version</label>
            <select id="fmu-baseline" value={baseline} onChange={(e) => { setBaseline(e.target.value); setCandidate(""); }} className="mt-1.5 h-9 w-full rounded-md border border-line-strong px-2.5 text-sm">
              <option value="">Choose an FMU</option>
              {usable.map((f) => <option key={f.file} value={f.file}>{f.file}</option>)}
            </select>
          </div>
          <div>
            <label htmlFor="fmu-candidate" className="text-sm font-medium">Candidate</label>
            <select id="fmu-candidate" value={candidate} disabled={!base} onChange={(e) => setCandidate(e.target.value)} className="mt-1.5 h-9 w-full rounded-md border border-line-strong px-2.5 text-sm disabled:opacity-50">
              <option value="">{base ? "Choose an FMU" : "Choose the approved version first"}</option>
              {compatible.map((f) => <option key={f.file} value={f.file}>{f.file}</option>)}
            </select>
          </div>
        </div>
        <Button variant="primary" className="mt-3" disabled={running || !base || !candidate} onClick={run}>
          {running ? "A run is in progress" : "Run gate"}
        </Button>
        {error && <p role="alert" className="mt-3 max-w-3xl rounded-md bg-danger-bg p-3 text-sm text-danger">{error}</p>}
      </Section>

      <Section title={`FMUs found: ${files.length}`} description={folder ? `Folder: ${folder}` : undefined}>
        {loadError && <p role="alert" className="rounded-md bg-danger-bg p-3 text-sm text-danger">{loadError}</p>}
        {files.length === 0 && !loadError && <p className="text-sm text-fg-2">No FMU files here yet. Copy a .fmu file into the folder, then click Refresh.</p>}
        {files.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[640px] text-sm">
              <thead className="text-left text-xs text-fg-3">
                <tr className="border-b border-line"><th className="py-2 pr-4 font-medium">File</th><th className="pr-4 font-medium">Model</th><th className="font-medium">Works with</th></tr>
              </thead>
              <tbody>
                {files.map((f) => (
                  <tr key={f.file} className="border-b border-line last:border-b-0 align-top">
                    <td className="num py-2.5 pr-4 text-xs">{f.file}</td>
                    <td className="py-2.5 pr-4 text-fg-2">{f.modelName}{f.description && <span className="block text-xs text-fg-3">{f.description}</span>}</td>
                    <td className="py-2.5">{f.usable ? <span className="text-fg-2">{label(f.feature)}</span> : <span className="text-warn">{f.problem}</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Section>

      <div className="flex gap-2 px-5 py-4 text-xs leading-[18px] text-fg-2 sm:px-8">
        <TriangleAlert size={14} className="mt-0.5 shrink-0 text-warn" />
        <p>An FMU contains native code, and testing one runs that code on this PC with your permissions, so only use files you trust. That's why there's no upload button. Files have to be copied into the folder. Windows may also block unsigned FMUs through an Application Control policy, and TwinGuard doesn't bypass that.</p>
      </div>
    </>
  );
}
