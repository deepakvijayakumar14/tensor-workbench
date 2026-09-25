import { useState } from "react";
import { api, describeError } from "../api/client";
import type { Run } from "../api/types";
import { usePolling } from "../hooks/usePolling";
import { formatBytes, formatNumber, formatSeconds, secondsBetween, shortId } from "../lib/format";
import { ErrorNote, Loading, Pager, StateBadge } from "./common";
import { Timeline } from "./Timeline";

const PAGE_SIZE = 10;

export function SweepDetail({
  sweepId,
  selectedRunId,
  onSelectRun,
}: {
  sweepId: string;
  selectedRunId: string | null;
  onSelectRun: (id: string) => void;
}) {
  const [page, setPage] = useState(0);
  const [retrying, setRetrying] = useState(false);
  const [retryError, setRetryError] = useState<string | null>(null);
  const unfinished = (s: { done: boolean }) => !s.done;

  const sweep = usePolling((signal) => api.sweep(sweepId, signal), [sweepId], { intervalMs: 1000, keepPolling: unfinished });
  const done = sweep.data?.done ?? false;
  // Children and timeline refresh on the same cadence and stop once the sweep is done.
  const runs = usePolling((signal) => api.sweepRuns(sweepId, page, PAGE_SIZE, signal), [sweepId, page, done], {
    intervalMs: 1000,
    keepPolling: () => !done,
  });
  const timeline = usePolling((signal) => api.sweepTimeline(sweepId, signal), [sweepId, done], {
    intervalMs: 1000,
    keepPolling: () => !done,
  });

  if (sweep.loading && !sweep.data) return <Loading />;
  if (!sweep.data) return <ErrorNote>{describeError(sweep.error)}</ErrorNote>;
  const s = sweep.data;
  const c = s.counts;

  const retryFailed = async () => {
    setRetrying(true);
    setRetryError(null);
    try {
      await api.retryFailedInSweep(s.id);
      sweep.refresh();
      runs.refresh();
      timeline.refresh();
    } catch (e) {
      setRetryError(describeError(e));
    } finally {
      setRetrying(false);
    }
  };

  return (
    <div className="sweep-detail">
      <div className="sweep-head">
        <div>
          <h3>
            Sweep <span className="mono">{shortId(s.id)}</span>
          </h3>
          <p className="muted small">
            {s.parameter} from {formatNumber(s.start)} to {formatNumber(s.end)} step {formatNumber(s.step)} · {s.variantCount} runs ·{" "}
            {s.parameter === "gain" ? `bias ${formatNumber(s.fixedBias)}` : `gain ${formatNumber(s.fixedGain)}`} · v{s.implementationVersion}
            {s.demoTransientFailureOrdinal != null && <> · demo failure on #{s.demoTransientFailureOrdinal}</>}
          </p>
        </div>
        {c.failed > 0 && (
          <button className="secondary small" onClick={retryFailed} disabled={retrying}>
            {retrying ? "Requeuing…" : `Retry ${c.failed} failed`}
          </button>
        )}
      </div>
      {retryError && <ErrorNote>{retryError}</ErrorNote>}

      <div className="counts">
        <Count label="Queued" value={c.queued} kind="queued" />
        <Count label="Running" value={c.running} kind="running" />
        <Count label="Succeeded" value={c.succeeded} kind="succeeded" />
        <Count label="Failed" value={c.failed} kind="failed" />
        <Count label="Retry attempts" value={s.retries} kind="retries" />
      </div>
      {!done && c.succeeded > 0 && <p className="muted small">Partial results are available below while the rest run.</p>}

      {timeline.data && <Timeline timeline={timeline.data} />}

      <div className="table-wrap">
        <table className="results">
          <thead>
            <tr>
              <th>#</th>
              <th>{s.parameter}</th>
              <th>Status</th>
              <th className="num">Attempts</th>
              <th className="num">Time</th>
              <th className="num">Min</th>
              <th className="num">Max</th>
              <th className="num">Mean</th>
              <th className="num">Size</th>
            </tr>
          </thead>
          <tbody>
            {runs.data?.items.map((r) => (
              <ResultRow key={r.id} run={r} parameter={s.parameter} selected={r.id === selectedRunId} onSelect={onSelectRun} />
            ))}
          </tbody>
        </table>
      </div>
      {runs.data && <Pager page={runs.data.page} totalPages={runs.data.totalPages} onChange={setPage} />}
    </div>
  );
}

function Count({ label, value, kind }: { label: string; value: number; kind: string }) {
  return (
    <div className={`count count-${kind}`}>
      <span className="count-value">{value}</span>
      <span className="count-label">{label}</span>
    </div>
  );
}

function ResultRow({ run, parameter, selected, onSelect }: { run: Run; parameter: "gain" | "bias"; selected: boolean; onSelect: (id: string) => void }) {
  const r = run.result;
  const elapsed = run.state === "RUNNING" ? secondsBetween(run.runningSince, null) : r?.computeSeconds ?? null;
  return (
    <tr className={`${selected ? "selected" : ""} ${run.state === "SUCCEEDED" ? "clickable" : ""}`} onClick={() => onSelect(run.id)} title={run.stage}>
      <td className="mono">{run.sweepOrdinal}</td>
      <td className="mono">{formatNumber(parameter === "gain" ? run.gain : run.bias)}</td>
      <td>
        <StateBadge state={run.state} />
        {run.state === "QUEUED" && run.attemptCount > 0 && <span className="retry-tag">retry</span>}
        {run.state === "FAILED" && <span className="muted small"> {run.lastErrorCategory}</span>}
      </td>
      <td className="num">{run.attemptCount}</td>
      <td className="num">{run.state === "RUNNING" ? <span className="muted">{formatSeconds(elapsed)}</span> : formatSeconds(elapsed)}</td>
      <td className="num">{formatNumber(r?.min)}</td>
      <td className="num">{formatNumber(r?.max)}</td>
      <td className="num">{formatNumber(r?.mean)}</td>
      <td className="num">{formatBytes(r?.outputSizeBytes)}</td>
    </tr>
  );
}
