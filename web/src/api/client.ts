import type {
  Dataset,
  ErrorBody,
  Page,
  PreviewSlice,
  Run,
  RunDetail,
  Sweep,
  SweepTimeline,
  SystemInfo,
  SystemStatus,
} from "./types";

/**
 * An error from the API (it answered) versus a transport failure (we don't know
 * whether the request was processed). The distinction decides whether a
 * submission may reuse its idempotency key.
 */
export class ApiError extends Error {
  constructor(public readonly status: number, public readonly body: ErrorBody) {
    super(body.message);
  }

  /** 5xx responses are ambiguous: the work may or may not have been accepted. */
  get ambiguous(): boolean {
    return this.status >= 500;
  }
}

export class NetworkError extends Error {
  readonly ambiguous = true;
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      ...init,
      headers: { Accept: "application/json", ...(init.body ? { "Content-Type": "application/json" } : {}), ...init.headers },
    });
  } catch (e) {
    if (e instanceof DOMException && e.name === "AbortError") throw e;
    throw new NetworkError("Network error: the API could not be reached");
  }
  if (!response.ok) {
    let body: ErrorBody;
    try {
      body = await response.json();
    } catch {
      body = { status: response.status, error: "HTTP_ERROR", message: `HTTP ${response.status}`, fieldErrors: [] };
    }
    throw new ApiError(response.status, body);
  }
  return (response.status === 204 ? undefined : await response.json()) as T;
}

function post<T>(path: string, body: unknown, idempotencyKey?: string): Promise<T> {
  return request<T>(path, {
    method: "POST",
    body: JSON.stringify(body),
    headers: idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {},
  });
}

const page = (p: number, size: number) => `page=${p}&size=${size}`;

export interface CreateDatasetBody {
  shape: number[];
  seed: number;
}

export interface SubmitRunBody {
  datasetId: string;
  gain: number;
  bias: number;
  implementationVersion: string;
  faultInjection?: string | null;
}

export interface SubmitSweepBody {
  datasetId: string;
  parameter: "gain" | "bias";
  start: number;
  end: number;
  step: number;
  gain?: number;
  bias?: number;
  implementationVersion: string;
  demoTransientFailure: boolean;
}

export const api = {
  systemInfo: () => request<SystemInfo>("/api/system"),
  systemStatus: (signal?: AbortSignal) => request<SystemStatus>("/api/system/status", { signal }),

  datasets: (p = 0, size = 8, signal?: AbortSignal) => request<Page<Dataset>>(`/api/datasets?${page(p, size)}`, { signal }),
  createDataset: (key: string, body: CreateDatasetBody) => post<Dataset>("/api/datasets", body, key),

  submitRun: (key: string, body: SubmitRunBody) => post<Run>("/api/runs", body, key),
  runs: (p = 0, size = 10, signal?: AbortSignal) => request<Page<Run>>(`/api/runs?${page(p, size)}`, { signal }),
  run: (id: string, signal?: AbortSignal) => request<RunDetail>(`/api/runs/${id}`, { signal }),
  retryRun: (id: string) => post<Run>(`/api/runs/${id}/retry`, {}),
  preview: (id: string, axis: number, index: number, signal?: AbortSignal) =>
    request<PreviewSlice>(`/api/runs/${id}/preview?axis=${axis}&index=${index}`, { signal }),

  submitSweep: (key: string, body: SubmitSweepBody) => post<Sweep>("/api/sweeps", body, key),
  sweeps: (p = 0, size = 10, signal?: AbortSignal) => request<Page<Sweep>>(`/api/sweeps?${page(p, size)}`, { signal }),
  sweep: (id: string, signal?: AbortSignal) => request<Sweep>(`/api/sweeps/${id}`, { signal }),
  sweepRuns: (id: string, p: number, size: number, signal?: AbortSignal) =>
    request<Page<Run>>(`/api/sweeps/${id}/runs?${page(p, size)}`, { signal }),
  sweepTimeline: (id: string, signal?: AbortSignal) => request<SweepTimeline>(`/api/sweeps/${id}/timeline`, { signal }),
  retryFailedInSweep: (id: string) => post<{ requeued: number }>(`/api/sweeps/${id}/retry-failed`, {}),

  // Full downloads are plain navigations to a redirecting endpoint, never fetched into JS memory.
  runDownloadUrl: (id: string) => `/api/runs/${id}/download`,
  datasetDownloadUrl: (id: string) => `/api/datasets/${id}/download`,
};

export function describeError(e: unknown): string {
  if (e instanceof ApiError) return e.body.message;
  if (e instanceof Error) return e.message;
  return String(e);
}
