import { useState } from "react";
import { api, describeError } from "../api/client";
import type { RunDetail } from "../api/types";
import { usePolling } from "../hooks/usePolling";
import { formatBytes, formatNumber, formatSeconds, secondsBetween, shortId } from "../lib/format";
import { ErrorNote, Loading, StateBadge } from "./common";
import { SliceViewer } from "./SliceViewer";

const terminal = (d: RunDetail) => d.run.state === "SUCCEEDED" || d.run.state === "FAILED";

export function RunPanel({ runId }: { runId: string }) {
  const detail = usePolling((signal) => api.run(runId, signal), [runId], {
    intervalMs: 1000,
    keepPolling: (d) => !terminal(d),
  });
  const [retryError, setRetryError] = useState<string | null>(null);
  const [retrying, setRetrying] = useState(false);

  if (detail.loading && !detail.data) return <Loading />;
  if (!detail.data) return <ErrorNote>{describeError(detail.error)}</ErrorNote>;
  const { run, attempts, artifacts, preview, configHash } = detail.data;
  const output = artifacts.find((a) => a.kind === "OUTPUT_TENSOR");

  const retry = async () => {
    setRetrying(true);
    setRetryError(null);
    try {
      await api.retryRun(run.id);
      detail.refresh();
    } catch (e) {
      setRetryError(describeError(e));
    } finally {
      setRetrying(false);
    }
  };

  return (
    <div className="run-panel">
      <div className="run-head">
        <div>
          <h3>
            Run <span className="mono">{shortId(run.id)}</span>
            {run.sweepOrdinal != null && <span className="muted"> · sweep child #{run.sweepOrdinal}</span>}
          </h3>
          <p className="muted small">
            gain {formatNumber(run.gain)} · bias {formatNumber(run.bias)} · v{run.implementationVersion} · config{" "}
            <span className="mono" title={configHash}>
              {configHash.slice(0, 10)}
            </span>
            {run.faultInjection && <> · fault injection: {run.faultInjection}</>}
          </p>
        </div>
        <StateBadge state={run.state} />
      </div>

      <p className={`stage stage-${run.state.toLowerCase()}`}>{run.stage}</p>

      {run.state === "FAILED" && (
        <div className="failure">
          <p>
            <b>{run.lastErrorCategory}</b>: {run.lastErrorMessage}
          </p>
          <button className="secondary small" onClick={retry} disabled={retrying}>
            {retrying ? "Requeuing…" : "Retry this run"}
          </button>
          {retryError && <ErrorNote>{retryError}</ErrorNote>}
        </div>
      )}

      {run.state === "SUCCEEDED" && run.result && (
        <>
          <dl className="stats">
            <div>
              <dt>min</dt>
              <dd>{formatNumber(run.result.min)}</dd>
            </div>
            <div>
              <dt>max</dt>
              <dd>{formatNumber(run.result.max)}</dd>
            </div>
            <div>
              <dt>mean</dt>
              <dd>{formatNumber(run.result.mean)}</dd>
            </div>
            <div>
              <dt>std</dt>
              <dd>{formatNumber(run.result.std)}</dd>
            </div>
            <div>
              <dt>compute</dt>
              <dd>{formatSeconds(run.result.computeSeconds)}</dd>
            </div>
          </dl>
          {preview && <SliceViewer key={run.id} runId={run.id} preview={preview} />}
          <div className="downloads">
            {/* Plain links: the browser follows the API's redirect to storage; nothing is loaded into JS memory. */}
            <a className="button primary" href={api.runDownloadUrl(run.id)} download>
              Download full result (.npy, {formatBytes(output?.sizeBytes)})
            </a>
            <a className="button ghost" href={api.datasetDownloadUrl(run.datasetId)} download>
              Download input
            </a>
          </div>
          {output && (
            <p className="muted small">
              {output.dtype} {output.shape.join("×")} · SHA-256 <span className="mono">{output.sha256.slice(0, 16)}…</span>
            </p>
          )}
        </>
      )}

      <h4 className="subhead">Attempts</h4>
      {attempts.length === 0 ? (
        <p className="muted small">No attempts yet: waiting for a free worker slot.</p>
      ) : (
        <ol className="attempts">
          {attempts.map((a) => (
            <li key={a.attemptNumber} className={a.accepted ? "accepted" : ""}>
              <span className="mono">#{a.attemptNumber}</span>
              <span className={`outcome outcome-${(a.outcome ?? "running").toLowerCase()}`}>{a.outcome ?? "RUNNING"}</span>
              <span className="muted small">{a.leaseOwner}</span>
              <span className="muted small">
                {formatSeconds(secondsBetween(a.computeStartedAt ?? a.claimedAt, a.computeFinishedAt ?? a.finishedAt))}
              </span>
              {a.accepted && <span className="accepted-tag">accepted</span>}
              {a.errorCategory && (
                <span className="attempt-error">
                  {a.errorCategory}: {a.errorMessage}
                </span>
              )}
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
