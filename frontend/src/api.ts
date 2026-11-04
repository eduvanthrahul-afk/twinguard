export type Verdict = "BLOCK" | "REVIEW" | "APPROVE";

export interface ReleaseView {
  id: string;
  title: string;
  summary: string;
  component: string;
  changedSettings: string[];
  intent: string;
}

export interface FaultView {
  id: string;
  faultClass: string;
  classLabel: string;
  title: string;
  description: string;
  holdout: boolean;
}

export interface SettingView {
  key: string;
  label: string;
  unit: string;
  min: number;
  max: number;
  step: number;
  approved: number;
}

export interface MutantView {
  id: string;
  method: string;
  line: number;
  operator: string;
  description: string;
  sourceLine: string;
}

export interface Catalog {
  releases: ReleaseView[];
  faults: FaultView[];
  settings: SettingView[];
  mutants: MutantView[];
}

export interface MutantResult extends MutantView {
  detected: boolean;
  detectedTrials: number;
  meanScenariosToDetect: number | null;
  detectedByRandom: boolean;
  firstFinding: string | null;
  survivorKind: "NO_BEHAVIOUR_CHANGE" | "WITHIN_TOLERANCE" | null;
}

export interface CodeMutationReport {
  totalMutants: number;
  trials: number;
  budget: number;
  overall: Score;
  randomBaseline: Score;
  excludingNoBehaviourChange: Score;
  noBehaviourChangeMutants: number;
  crashingMutants: number;
  byOperator: { operator: string; score: Score }[];
  mutants: MutantResult[];
  simulations: number;
  computeMs: number;
}

export interface Cell {
  speed: number;
  distance: number;
  friction: number;
  load: number;
  ghost: boolean;
  tests: number;
  failures: number;
  bestFitness: number;
}

export interface Progress {
  done: number;
  budget: number;
  failures: number;
  coveredCells: number;
  totalCells: number;
  coveragePercent: number;
  cells: Cell[];
}

export interface Finding {
  type: string;
  severity: "CRITICAL" | "HIGH" | "MEDIUM";
  message: string;
}

export interface Scenario {
  type: "OBSTACLE" | "GHOST";
  speedKph: number;
  distanceM: number;
  friction: number;
  noiseM: number;
  infotainmentLoad: number;
  seed: number;
}

export interface Metrics {
  brakeOnsetS: number | null;
  fullBrakeS: number | null;
  peakDecel: number;
  peakBrakeRequest: number;
  minGapM: number;
  collision: boolean;
  impactKph: number;
  braked: boolean;
  maxSensorAgeMs: number;
  maxQueueDepth: number;
  droppedMessages: number;
}

export interface TracePoint {
  t: number;
  gapM: number;
  speedKph: number;
  brakeLevel: number;
  decel: number;
  sensorAgeMs: number;
  queueDepth: number;
}

export interface Counterexample {
  primary: Finding;
  findings: Finding[];
  scenario: Scenario;
  originalScenario: Scenario;
  v1: Metrics;
  v2: Metrics;
  v1Trace: TracePoint[];
  v2Trace: TracePoint[];
}

export interface GateReport {
  targetId: string;
  targetTitle: string;
  targetKind: string;
  verdict: Verdict;
  reason: string;
  changedSettings: string[];
  strategy: string;
  legacy: { passed: number; total: number };
  evaluations: number;
  simulations: number;
  computeMs: number;
  failingScenarios: number;
  findingCounts: Record<string, number>;
  coveragePercent: number;
  coveredCells: number;
  totalCells: number;
  coverage: Cell[];
  counterexamples: Counterexample[];
}

export interface Score {
  detected: number;
  total: number;
  percent: number;
  lower95: number;
  upper95: number;
}

export interface CurvePoint {
  scenarios: number;
  random: number;
  search: number;
  changeAware: number;
}

export interface FaultResult {
  id: string;
  faultClass: string;
  classLabel: string;
  title: string;
  description: string;
  holdout: boolean;
  detected: boolean;
  detectedTrials: number;
  meanScenariosToDetect: number | null;
  randomMeanScenariosToDetect: number | null;
  firstFinding: string | null;
  hardened: boolean;
  survivorKind: "NO_BEHAVIOUR_CHANGE" | "WITHIN_TOLERANCE" | null;
}

export interface CampaignReport {
  totalFaults: number;
  trials: number;
  budget: number;
  overall: Score;
  excludingNoBehaviourChange: Score;
  noBehaviourChangeFaults: number;
  development: Score;
  holdout: Score;
  holdoutBeforeHardening: Score;
  overallBeforeHardening: Score;
  corpusScenariosAdded: number;
  hardenedFaults: string[];
  byClass: { faultClass: string; label: string; score: Score }[];
  curve: CurvePoint[];
  holdoutCurve: CurvePoint[];
  faults: FaultResult[];
  controls: { name: string; scenarios: number; falseAlarms: number; passed: boolean }[];
  simulations: number;
  computeMs: number;
  simulationsPerSecond: number;
}

export interface CampaignStatus {
  running: boolean;
  phase: string;
  progress: number;
  error: string | null;
  report: CampaignReport | null;
  codeMutation: CodeMutationReport | null;
  features: Record<string, FeatureCampaignReport>;
}

export interface RunRequest {
  kind: "release" | "fault" | "custom" | "mutant" | "fmu";
  id?: string;
  settings?: Record<string, number>;
  budget?: number;
  baseline?: string;
  candidate?: string;
}

export interface FeatureParam {
  key: string;
  label: string;
  unit: string;
  min: number;
  max: number;
  nominal: number;
}

export interface FeatureChannel {
  key: string;
  label: string;
  unit: string;
}

export interface FeatureView {
  id: string;
  name: string;
  summary: string;
  limitations: string;
  params: FeatureParam[];
  coverageX: number;
  coverageY: number;
  channels: FeatureChannel[];
  inputNames: string[];
  outputNames: string[];
  stepSeconds: number;
  releases: { id: string; title: string; summary: string; changedSettings: string[]; intent: string }[];
  faults: { id: string; faultClass: string; title: string }[];
  mutants: MutantView[];
}

export interface FeatureFinding {
  type: string;
  label: string;
  severity: Finding["severity"];
  message: string;
}

export interface FeatureCell {
  x: number;
  y: number;
  tests: number;
  failures: number;
}

export interface FeatureCounterexample {
  primary: FeatureFinding;
  findings: FeatureFinding[];
  scenario: Record<string, number>;
  v1: Record<string, number>;
  v2: Record<string, number>;
  v1Trace: number[][];
  v2Trace: number[][];
}

export interface FeatureReport {
  featureId: string;
  targetId: string;
  targetTitle: string;
  verdict: Verdict;
  reason: string;
  changedSettings: string[];
  conventionalPassed: number;
  conventionalTotal: number;
  evaluations: number;
  simulations: number;
  computeMs: number;
  failingScenarios: number;
  findingCounts: Record<string, number>;
  coveredCells: number;
  totalCells: number;
  coverage: FeatureCell[];
  counterexamples: FeatureCounterexample[];
}

export interface FeatureProgress {
  done: number;
  budget: number;
  failures: number;
  coveredCells: number;
  totalCells: number;
  cells: FeatureCell[];
}

export interface FeatureMutantResult extends MutantView {
  detected: boolean;
  detectedByRandom: boolean;
  meanScenariosToDetect: number | null;
  firstFinding: string | null;
  survivorKind: "NO_BEHAVIOUR_CHANGE" | "WITHIN_TOLERANCE" | null;
}

export interface FeatureCampaignReport {
  featureId: string;
  trials: number;
  budget: number;
  faults: Score;
  faultsRandom: Score;
  mutants: Score;
  mutantsRandom: Score;
  excludingNoChange: Score;
  noChangeMutants: number;
  falseAlarms: number;
  controlScenarios: number;
  mutantResults: FeatureMutantResult[];
  simulations: number;
  computeMs: number;
}

export interface FmuEntry {
  file: string;
  modelName: string;
  description: string;
  feature: string | null;
  usable: boolean;
  problem: string | null;
}

export interface FeatureRunRequest {
  kind: "release" | "fault" | "mutant" | "fmu";
  id?: string;
  baseline?: string;
  candidate?: string;
  budget?: number;
}

export async function fetchFeatures(): Promise<FeatureView[]> {
  const response = await fetch("/api/features");
  if (!response.ok) throw new Error(await readError(response));
  return response.json();
}

export async function fetchFmus(): Promise<{ folder: string; files: FmuEntry[] }> {
  const response = await fetch("/api/fmus");
  if (!response.ok) throw new Error(await readError(response));
  return response.json();
}

export async function startFeatureRun(featureId: string, request: FeatureRunRequest): Promise<string> {
  const response = await fetch(`/api/features/${featureId}/runs`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
  });
  if (!response.ok) throw new Error(await readError(response));
  return (await response.json()).runId;
}

export interface CarlaStatus {
  connected: boolean;
  backend: string | null;
  map: string | null;
  version: string | null;
  testDouble: boolean;
  calibrated: boolean;
  message: string | null;
}

export interface RecheckItem {
  findingType: string;
  scenario: Scenario;
  reproduced: boolean;
  inconclusive: boolean;
  withinCalibration: boolean;
  findingsSeen: string[];
  controlFindings: string[];
  v1: Metrics;
  v2: Metrics;
}

export interface RecheckReport {
  backend: string;
  map: string;
  version: string;
  testDouble: boolean;
  calibrated: boolean;
  checked: number;
  reproduced: number;
  inconclusive: number;
  reproducedWithinCalibration: number;
  withinCalibration: number;
  controlFalseAlarms: number;
  items: RecheckItem[];
  computeMs: number;
}

export async function fetchCarlaStatus(): Promise<CarlaStatus> {
  const response = await fetch("/api/carla");
  if (!response.ok) throw new Error(await readError(response));
  return response.json();
}

export async function runRecheck(runId: string): Promise<RecheckReport> {
  const response = await fetch(`/api/runs/${runId}/recheck`, { method: "POST" });
  if (!response.ok) throw new Error(await readError(response));
  return response.json();
}

async function readError(response: Response): Promise<string> {
  try {
    const body = await response.json();
    return body.message ?? `Request failed (${response.status})`;
  } catch {
    return `Request failed (${response.status})`;
  }
}

export async function fetchCatalog(): Promise<Catalog> {
  const response = await fetch("/api/catalog");
  if (!response.ok) throw new Error(await readError(response));
  return response.json();
}

export async function startRun(request: RunRequest): Promise<string> {
  const response = await fetch("/api/runs", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
  });
  if (!response.ok) throw new Error(await readError(response));
  return (await response.json()).runId;
}

export async function restartCampaign(): Promise<void> {
  const response = await fetch("/api/campaign", { method: "POST" });
  if (!response.ok) throw new Error(await readError(response));
}

export const FINDING_LABELS: Record<string, string> = {
  SOFTWARE_CRASH: "Software crash",
  SAFETY_VIOLATION: "Avoidable collision",
  NEW_COLLISION: "New collision",
  WORSE_IMPACT: "Harder impact",
  MISSED_BRAKE: "Missed braking",
  PHANTOM_BRAKE: "Phantom braking",
  LATE_BRAKE: "Late braking",
  WEAK_BRAKE: "Weak braking",
  REDUCED_MARGIN: "Reduced stopping gap",
  EARLY_BRAKE: "Early braking",
};

export const SEVERITY_COLOR: Record<Finding["severity"], string> = {
  CRITICAL: "#d03b3b",
  HIGH: "#ec835a",
  MEDIUM: "#fab219",
};
