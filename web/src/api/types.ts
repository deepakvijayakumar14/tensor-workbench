// Shapes of the public API's JSON. Decimals (gain, bias, range) arrive as JSON numbers.

export type DatasetStatus = "QUEUED" | "GENERATING" | "READY" | "FAILED";
export type RunState = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED";
export type AttemptOutcome = "SUCCEEDED" | "FAILED" | "LEASE_EXPIRED" | null;

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface ArtifactView {
  kind: "INPUT_TENSOR" | "OUTPUT_TENSOR" | "PREVIEW_STACK";
  objectKey: string;
  sizeBytes: number;
  sha256: string;
  dtype: string;
  shape: number[];
}

export interface Dataset {
  id: string;
  shape: number[];
  dtype: string;
  seed: number;
  generatorVersion: string;
  status: DatasetStatus;
  attemptCount: number;
  elementCount: number;
  estimatedInputBytes: number;
  errorCategory: string | null;
  errorMessage: string | null;
  input: ArtifactView | null;
  createdAt: string;
  readyAt: string | null;
}

export interface RunResult {
  acceptedAttemptNumber: number;
  min: number | null;
  max: number | null;
  mean: number | null;
  std: number | null;
  computeSeconds: number | null;
  outputSizeBytes: number | null;
}

export interface Run {
  id: string;
  datasetId: string;
  sweepId: string | null;
  sweepOrdinal: number | null;
  gain: number;
  bias: number;
  implementationVersion: string;
  state: RunState;
  stage: string;
  attemptCount: number;
  maxAttempts: number;
  nextAttemptAt: string | null;
  runningSince: string | null;
  runningOn: string | null;
  lastErrorCategory: string | null;
  lastErrorMessage: string | null;
  faultInjection: string | null;
  createdAt: string;
  finishedAt: string | null;
  result: RunResult | null;
}

export interface Attempt {
  attemptNumber: number;
  outcome: AttemptOutcome;
  accepted: boolean;
  leaseOwner: string;
  claimedAt: string;
  computeStartedAt: string | null;
  computeFinishedAt: string | null;
  finishedAt: string | null;
  errorCategory: string | null;
  errorMessage: string | null;
}

export interface PreviewAxis {
  axis: number;
  sliceCount: number;
  height: number;
  width: number;
  sourceHeight: number;
  sourceWidth: number;
}

export interface PreviewInfo {
  maxDimension: number;
  valueMin: number;
  valueMax: number;
  axes: PreviewAxis[];
}

export interface RunDetail {
  run: Run;
  attempts: Attempt[];
  artifacts: ArtifactView[];
  preview: PreviewInfo | null;
  configSnapshot: Record<string, unknown>;
  configHash: string;
}

export interface SweepCounts {
  queued: number;
  running: number;
  succeeded: number;
  failed: number;
  total: number;
}

export interface Sweep {
  id: string;
  datasetId: string;
  parameter: "gain" | "bias";
  start: number;
  end: number;
  step: number;
  variantCount: number;
  implementationVersion: string;
  fixedGain: number | null;
  fixedBias: number | null;
  demoTransientFailureOrdinal: number | null;
  counts: SweepCounts;
  retries: number;
  done: boolean;
  createdAt: string;
}

export interface AttemptInterval {
  runId: string;
  sweepOrdinal: number | null;
  attemptNumber: number;
  leaseOwner: string;
  outcome: AttemptOutcome;
  errorCategory: string | null;
  start: string;
  end: string | null;
  measured: boolean;
}

export interface SweepTimeline {
  sweepId: string;
  workerSlots: number | null;
  peakConcurrency: number;
  intervals: AttemptInterval[];
  now: string;
}

export interface PreviewSlice {
  runId: string;
  axis: number;
  index: number;
  sliceCount: number;
  width: number;
  height: number;
  sourceWidth: number;
  sourceHeight: number;
  valueMin: number;
  valueMax: number;
  encoding: "uint8";
  data: string;
}

export interface SystemInfo {
  disclaimer: string;
  functionName: string;
  functionDescription: string;
  supportedVersions: string[];
  generatorVersion: string;
  limits: {
    maxDimension: number;
    maxElements: number;
    maxSweepVariants: number;
    maxAbsParameter: number;
    previewMaxDimension: number;
  };
  maxAttempts: number;
  leaseSeconds: number;
  allowFaultInjection: boolean;
}

export interface SystemStatus {
  workers: { instance: string; slots: number; lastSeen: string }[];
  totalSlots: number | null;
  runs: Record<RunState, number>;
  datasets: Record<DatasetStatus, number>;
}

export interface FieldError {
  field: string;
  message: string;
}

export interface ErrorBody {
  status: number;
  error: string;
  message: string;
  fieldErrors: FieldError[];
}
