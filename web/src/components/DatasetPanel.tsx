import { useEffect, useState, type FormEvent } from "react";
import { api, type CreateDatasetBody } from "../api/client";
import type { Dataset, SystemInfo } from "../api/types";
import { usePolling } from "../hooks/usePolling";
import { fieldError, useSubmit } from "../hooks/useSubmit";
import { formatBytes, formatCount, shortId, timeAgo } from "../lib/format";
import { Empty, ErrorNote, Field, Loading, Panel, StateBadge } from "./common";

const NPY_HEADER = 128;

export function DatasetPanel({
  info,
  selectedId,
  onSelect,
}: {
  info: SystemInfo | null;
  selectedId: string | null;
  onSelect: (dataset: Dataset) => void;
}) {
  const [dims, setDims] = useState(["128", "128", "128"]);
  const [seed, setSeed] = useState("42");
  const list = usePolling((signal) => api.datasets(0, 6, signal), [], {
    intervalMs: 1500,
    keepPolling: (page) => page.items.some((d) => d.status === "QUEUED" || d.status === "GENERATING"),
  });
  // After a reload, default to the newest ready dataset so the submit form is usable immediately.
  useEffect(() => {
    const newestReady = list.data?.items.find((d) => d.status === "READY");
    if (!selectedId && newestReady) onSelect(newestReady);
  }, [list.data, selectedId, onSelect]);

  const { submit, inFlight, error, fieldErrors, retryPending } = useSubmit((key: string, body: CreateDatasetBody) =>
    api.createDataset(key, body),
  );

  const shape = dims.map((d) => Number(d));
  const limits = info?.limits;
  const validDims = shape.every((d) => Number.isInteger(d) && d >= 1 && d <= (limits?.maxDimension ?? 512));
  const elements = validDims ? shape.reduce((a, b) => a * b, 1) : 0;
  const tooLarge = limits ? elements > limits.maxElements : false;
  const seedValue = Number(seed);
  const validSeed = Number.isInteger(seedValue) && seedValue >= 0 && seedValue <= 2147483647;
  const canSubmit = validDims && !tooLarge && validSeed && !inFlight;

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!canSubmit) return;
    const created = await submit({ shape, seed: seedValue });
    if (created) {
      onSelect(created);
      list.refresh();
    }
  };

  return (
    <Panel step="1" title="Generate an input tensor">
      <form className="form" onSubmit={onSubmit} noValidate>
        <div className="dims">
          {["X", "Y", "Z"].map((axis, i) => (
            <Field key={axis} label={axis} error={fieldError(fieldErrors, `shape[${i}]`)}>
              <input
                inputMode="numeric"
                value={dims[i]}
                onChange={(e) => setDims(dims.map((d, j) => (j === i ? e.target.value : d)))}
                aria-invalid={!validDims}
              />
            </Field>
          ))}
          <Field label="Seed" error={fieldError(fieldErrors, "seed") ?? (validSeed ? undefined : "0 – 2147483647")}>
            <input inputMode="numeric" value={seed} onChange={(e) => setSeed(e.target.value)} />
          </Field>
        </div>
        <div className={`estimate ${tooLarge || !validDims ? "estimate-bad" : ""}`}>
          {!validDims ? (
            <>Each dimension must be an integer from 1 to {limits?.maxDimension ?? 512}.</>
          ) : (
            <>
              <b>{formatCount(elements)}</b> elements · input ≈ <b>{formatBytes(elements * 4 + NPY_HEADER)}</b> (int32) ·
              each result ≈ <b>{formatBytes(elements * 4 + NPY_HEADER)}</b> (float32)
              {tooLarge && limits && <div>Over the configured limit of {formatCount(limits.maxElements)} elements.</div>}
            </>
          )}
        </div>
        {fieldError(fieldErrors, "shape") && <ErrorNote>{fieldError(fieldErrors, "shape")}</ErrorNote>}
        {error && !fieldErrors.length && (
          <ErrorNote>
            {error}
            {retryPending && " — submitting again reuses the same idempotency key, so no duplicate is created."}
          </ErrorNote>
        )}
        <button type="submit" className="primary" disabled={!canSubmit}>
          {inFlight ? "Queuing…" : retryPending ? "Retry generation" : "Generate dataset"}
        </button>
      </form>

      <h3 className="subhead">Recent datasets</h3>
      {list.loading && !list.data ? (
        <Loading />
      ) : !list.data?.items.length ? (
        <Empty>No datasets yet. Generate one above; the default 128³ takes about a second.</Empty>
      ) : (
        <ul className="dataset-list">
          {list.data.items.map((d) => (
            <li key={d.id}>
              <button
                className={`dataset-row ${selectedId === d.id ? "selected" : ""}`}
                disabled={d.status !== "READY"}
                onClick={() => onSelect(d)}
                title={d.status === "READY" ? "Use this dataset for new runs" : d.errorMessage ?? "Not ready yet"}
              >
                <span className="mono">{shortId(d.id)}</span>
                <span>{d.shape.join("×")}</span>
                <span className="muted">seed {d.seed}</span>
                <span className="muted">{d.input ? formatBytes(d.input.sizeBytes) : timeAgo(d.createdAt)}</span>
                <StateBadge state={d.status} />
              </button>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}
