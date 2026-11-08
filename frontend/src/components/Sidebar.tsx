import { Activity, ListChecks, BatteryCharging, Check, FileUp, FlaskConical, Footprints, Gauge, GitBranch, History, LoaderCircle, Moon, Package, Route, ShieldAlert, Sun, TriangleAlert, X } from "lucide-react";
import type { ReactNode } from "react";
import type { Page, RunState, Theme } from "../App";
import type { CampaignStatus, CarlaStatus, FeatureView } from "../api";

const FEATURE_ICONS: Record<string, typeof Gauge> = {
  battery: BatteryCharging,
  cruise: Gauge,
  lane: Route,
  pedestrian: Footprints,
};

const MAX_RECENT = 5;
const ROW = "flex h-[30px] items-center gap-2 rounded-md px-2 text-left text-[13.5px] transition-colors";

function GroupLabel({ children }: { children: ReactNode }) {
  return <div className="mb-0.5 px-2 text-xs font-medium text-sb-label">{children}</div>;
}

function Logo() {
  return (
    <span aria-hidden className="flex h-[22px] w-[22px] items-center justify-center rounded-[5px] bg-sb-strong">
      <svg viewBox="0 0 20 20" className="h-3.5 w-3.5" style={{ color: "var(--sb-bg)" }}>
        <rect x="1.500" y="1.500" width="11" height="11" rx="2.500" fill="none" stroke="currentColor" strokeWidth="2" />
        <rect x="7.500" y="7.500" width="11" height="11" rx="2.500" fill="currentColor" />
      </svg>
    </span>
  );
}

function RunIcon({ run }: { run: RunState }) {
  if (run.running) return <LoaderCircle size={14} className="spin shrink-0 text-sb-label" aria-label="Running" />;
  if (run.error || run.report?.verdict === "BLOCK") {
    return <X size={14} strokeWidth={2.4} className="shrink-0 text-danger" aria-label={run.error ? "Run failed" : "Blocked"} />;
  }
  if (run.report?.verdict === "REVIEW") return <TriangleAlert size={14} className="shrink-0 text-warn" aria-label="Needs review" />;
  return <Check size={14} strokeWidth={2.4} className="shrink-0 text-ok" aria-label="Approved" />;
}

function CarlaRow({ status }: { status: CarlaStatus | null }) {
  const connected = status?.connected ?? false;
  const realCarla = connected && !status?.testDouble;
  const color = realCarla ? "var(--dot-ok)" : connected ? "var(--dot-warn)" : "var(--border-strong)";
  const title = !connected ? "CARLA not connected" : status?.testDouble ? "Test double, not CARLA" : status?.version ? `CARLA ${status.version} connected` : "CARLA re-check running";
  const detail = !connected ? "Failing braking runs can't be re-checked until CARLA is started" : status?.testDouble ? "For connection testing only" : status?.map ?? "Replaying failures";
  return (
    <div className="flex h-[30px] items-center gap-2 px-2 text-[13px] text-sb-fg" title={detail}>
      <span className="flex h-4 w-4 shrink-0 items-center justify-center" aria-hidden>
        <span className="h-[7px] w-[7px] rounded-full" style={{ background: color }} />
      </span>
      <span className="truncate">{title}</span>
    </div>
  );
}

/** Totals across braking and every plugin feature, once they have all been measured. */
function Scores({ campaign, featureCount, onOpen }: { campaign: CampaignStatus | null; featureCount: number; onOpen: () => void }) {
  const report = campaign?.report;
  const mutation = campaign?.codeMutation;
  const features = Object.values(campaign?.features ?? {});
  const row = `${ROW} w-full justify-between text-[13px] text-sb-fg hover:bg-sb-hover`;
  if (campaign?.error) {
    return <button type="button" onClick={onOpen} className={`${row} text-danger`}>Measurement failed</button>;
  }
  if (!report || !mutation || features.length < featureCount) {
    return (
      <div className="flex h-[30px] items-center justify-between px-2 text-[13px] text-sb-label">
        Measuring <span className="tabular-nums">{Math.round((campaign?.progress ?? 0) * 100)}%</span>
      </div>
    );
  }
  const mutantHits = mutation.overall.detected + features.reduce((sum, f) => sum + f.mutants.detected, 0);
  const mutantTotal = mutation.overall.total + features.reduce((sum, f) => sum + f.mutants.total, 0);
  const faultHits = report.overall.detected + features.reduce((sum, f) => sum + f.faults.detected, 0);
  const faultTotal = report.overall.total + features.reduce((sum, f) => sum + f.faults.total, 0);
  return (
    <>
      <button type="button" onClick={onOpen} title={`${mutantHits} of ${mutantTotal} across all features`} className={row}>
        Code mutants caught <span className="font-medium text-sb-strong tabular-nums">{Math.round((100 * mutantHits) / mutantTotal)}%</span>
      </button>
      <button type="button" onClick={onOpen} title={`${faultHits} of ${faultTotal} across all features`} className={row}>
        Designed faults caught <span className="font-medium text-sb-strong tabular-nums">{Math.round((100 * faultHits) / faultTotal)}%</span>
      </button>
    </>
  );
}

interface Props {
  page: Page;
  featureId: string | null;
  features: FeatureView[];
  theme: Theme;
  campaign: CampaignStatus | null;
  carla: CarlaStatus | null;
  run: RunState | null;
  results: Record<string, RunState>;
  busy: boolean;
  onNavigate: (page: Page) => void;
  onOpenFeature: (id: string) => void;
  onToggleTheme: () => void;
  onGuided: () => void;
  onOpenRun: (state: RunState) => void;
}

export default function Sidebar({ page, featureId, features, theme, campaign, carla, run, results, busy, onNavigate, onOpenFeature, onToggleTheme, onGuided, onOpenRun }: Props) {
  const running = run?.running ?? false;
  const recent = Object.values(results).filter((r) => !(running && r.targetId === run?.targetId));
  const list = (running && run ? [run, ...recent] : recent).slice(0, MAX_RECENT);

  const item = (key: string, label: string, Icon: typeof Gauge, selected: boolean, onClick: () => void) => (
    <button
      key={key}
      type="button"
      aria-current={selected ? "page" : undefined}
      onClick={onClick}
      className={`${ROW} shrink-0 whitespace-nowrap md:w-full ${selected ? "bg-sb-active font-medium text-sb-strong" : "text-sb-fg hover:bg-sb-hover"}`}
    >
      <Icon size={16} strokeWidth={1.7} className={`shrink-0 ${selected ? "text-sb-strong" : "text-sb-label"}`} />
      <span className="truncate">{label}</span>
    </button>
  );

  return (
    <aside className="z-10 flex flex-col border-b border-line bg-sb md:sticky md:top-0 md:h-screen md:border-b-0 md:border-r">
      <div className="flex h-12 shrink-0 items-center gap-2 px-3 md:px-4">
        <Logo />
        <span className="min-w-0 flex-1 truncate text-sm font-semibold tracking-[-0.01em] text-sb-strong">TwinGuard</span>
        <button
          type="button"
          aria-label={theme === "light" ? "Switch to dark theme" : "Switch to light theme"}
          onClick={onToggleTheme}
          className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md text-sb-label transition-colors hover:bg-sb-hover hover:text-sb-strong"
        >
          {theme === "light" ? <Moon size={15} strokeWidth={1.7} /> : <Sun size={15} strokeWidth={1.7} />}
        </button>
      </div>

      <div className="min-h-0 md:flex-1 md:overflow-y-auto">
        <nav aria-label="Main" className="flex gap-0.5 overflow-x-auto px-2 pb-2 md:block md:space-y-3.5 md:overflow-visible md:pb-0 md:pt-1">
          {item("guided", "Guided check", ListChecks, false, onGuided)}
          <div className="contents md:block">
            <div className="hidden md:block"><GroupLabel>Features</GroupLabel></div>
            <div className="contents md:block md:space-y-px">
              {item("releases", "Collision braking", ShieldAlert, page === "releases", () => onNavigate("releases"))}
              {features.map((f) => item(f.id, f.name, FEATURE_ICONS[f.id] ?? Gauge, (page === "feature" || page === "featureRun") && featureId === f.id, () => onOpenFeature(f.id)))}
            </div>
          </div>
          <div className="contents md:block">
            <div className="hidden md:block"><GroupLabel>Test</GroupLabel></div>
            <div className="contents md:block md:space-y-px">
              {item("import", "Import a release", FileUp, page === "import", () => onNavigate("import"))}
              {item("fmu", "Your software", Package, page === "fmu", () => onNavigate("fmu"))}
              {item("lab", "Braking Fault Lab", FlaskConical, page === "lab", () => onNavigate("lab"))}
            </div>
          </div>
          <div className="contents md:block">
            <div className="hidden md:block"><GroupLabel>Evidence</GroupLabel></div>
            <div className="contents md:block md:space-y-px">
              {item("history", "History", History, page === "history", () => onNavigate("history"))}
              {item("confidence", "Confidence", Activity, page === "confidence", () => onNavigate("confidence"))}
              {item("pipeline", "Pipeline", GitBranch, page === "pipeline", () => onNavigate("pipeline"))}
            </div>
          </div>
        </nav>

        {list.length > 0 && (
          <div className="mt-3.5 hidden px-2 pb-2 md:block">
            <GroupLabel>Recent braking runs</GroupLabel>
            <div className="space-y-px">
              {list.map((entry) => {
                const selected = page === "run" && run?.targetId === entry.targetId;
                const isLive = entry.running;
                const title = entry.report?.targetTitle ?? entry.title;
                return (
                  <button
                    key={entry.targetId}
                    type="button"
                    title={title}
                    disabled={busy && !isLive}
                    aria-current={selected ? "page" : undefined}
                    onClick={() => (isLive ? onNavigate("run") : onOpenRun(entry))}
                    className={`${ROW} w-full disabled:opacity-50 ${selected ? "bg-sb-active font-medium text-sb-strong" : "text-sb-fg enabled:hover:bg-sb-hover"}`}
                  >
                    <span className="flex h-4 w-4 shrink-0 items-center justify-center"><RunIcon run={entry} /></span>
                    <span className="truncate">{title}</span>
                  </button>
                );
              })}
            </div>
          </div>
        )}
      </div>

      <div className="hidden shrink-0 px-2 pb-2 pt-2 md:block">
        <Scores campaign={campaign} featureCount={features.length} onOpen={() => onNavigate("confidence")} />
        <div className="my-1.5 h-px bg-line" />
        <CarlaRow status={carla} />
      </div>
    </aside>
  );
}
