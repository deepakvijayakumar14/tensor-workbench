import { useState } from "react";
import { api } from "../api/client";
import { usePolling } from "../hooks/usePolling";
import { formatNumber, shortId, timeAgo } from "../lib/format";
import { Empty, Loading, Pager, StateBadge } from "./common";

type Tab = "sweeps" | "runs";

/** Recent submissions. Polls only while something in view is unfinished. */
export function WorkList({
  selectedSweepId,
  selectedRunId,
  onSelectSweep,
  onSelectRun,
  refreshKey,
}: {
  selectedSweepId: string | null;
  selectedRunId: string | null;
  onSelectSweep: (id: string) => void;
  onSelectRun: (id: string) => void;
  refreshKey: number;
}) {
  const [tab, setTab] = useState<Tab>("sweeps");
  const [sweepPage, setSweepPage] = useState(0);
  const [runPage, setRunPage] = useState(0);

  const sweeps = usePolling((signal) => api.sweeps(sweepPage, 5, signal), [sweepPage, refreshKey], {
    intervalMs: 2000,
    keepPolling: (p) => p.items.some((s) => !s.done),
    enabled: tab === "sweeps",
  });
  const runs = usePolling((signal) => api.runs(runPage, 5, signal), [runPage, refreshKey], {
    intervalMs: 1500,
    keepPolling: (p) => p.items.some((r) => r.state === "QUEUED" || r.state === "RUNNING"),
    enabled: tab === "runs",
  });

  return (
    <div className="worklist">
      <div className="segmented small" role="tablist">
        <button role="tab" aria-selected={tab === "sweeps"} className={tab === "sweeps" ? "on" : ""} onClick={() => setTab("sweeps")}>
          Sweeps
        </button>
        <button role="tab" aria-selected={tab === "runs"} className={tab === "runs" ? "on" : ""} onClick={() => setTab("runs")}>
          Single runs
        </button>
      </div>

      {tab === "sweeps" ? (
        sweeps.loading && !sweeps.data ? (
          <Loading />
        ) : !sweeps.data?.items.length ? (
          <Empty>No sweeps yet. Submit one to see bounded scheduling in action.</Empty>
        ) : (
          <>
            <ul className="work-items">
              {sweeps.data.items.map((s) => {
                const c = s.counts;
                return (
                  <li key={s.id}>
                    <button className={`work-row ${selectedSweepId === s.id ? "selected" : ""}`} onClick={() => onSelectSweep(s.id)}>
                      <span className="mono">{shortId(s.id)}</span>
                      <span>
                        {s.parameter} {formatNumber(s.start)} → {formatNumber(s.end)} step {formatNumber(s.step)}
                      </span>
                      <span className="progress" aria-label={`${c.succeeded} of ${c.total} succeeded`}>
                        <i className="p-succeeded" style={{ flexGrow: c.succeeded }} />
                        <i className="p-failed" style={{ flexGrow: c.failed }} />
                        <i className="p-running" style={{ flexGrow: c.running }} />
                        <i className="p-queued" style={{ flexGrow: c.queued }} />
                      </span>
                      <span className="muted small">
                        {c.succeeded}/{c.total} done{c.failed ? ` · ${c.failed} failed` : ""} · {timeAgo(s.createdAt)}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
            <Pager page={sweeps.data.page} totalPages={sweeps.data.totalPages} onChange={setSweepPage} />
          </>
        )
      ) : runs.loading && !runs.data ? (
        <Loading />
      ) : !runs.data?.items.length ? (
        <Empty>No single runs yet.</Empty>
      ) : (
        <>
          <ul className="work-items">
            {runs.data.items.map((r) => (
              <li key={r.id}>
                <button className={`work-row ${selectedRunId === r.id ? "selected" : ""}`} onClick={() => onSelectRun(r.id)}>
                  <span className="mono">{shortId(r.id)}</span>
                  <span>
                    gain {formatNumber(r.gain)} · bias {formatNumber(r.bias)}
                  </span>
                  <StateBadge state={r.state} />
                  <span className="muted small">{r.stage}</span>
                </button>
              </li>
            ))}
          </ul>
          <Pager page={runs.data.page} totalPages={runs.data.totalPages} onChange={setRunPage} />
        </>
      )}
    </div>
  );
}
