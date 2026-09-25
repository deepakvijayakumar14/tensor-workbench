import { api } from "../api/client";
import type { SystemInfo } from "../api/types";
import { usePolling } from "../hooks/usePolling";

/** Live capacity: one dot per worker slot, filled while a computation occupies it. */
export function StatusBar({ info }: { info: SystemInfo | null }) {
  const { data: status } = usePolling((signal) => api.systemStatus(signal), [], { intervalMs: 2000 });
  const slots = status?.totalSlots ?? 0;
  const busy = (status?.runs.RUNNING ?? 0) + (status?.datasets.GENERATING ?? 0);
  const queued = (status?.runs.QUEUED ?? 0) + (status?.datasets.QUEUED ?? 0);

  return (
    <header className="topbar">
      <div className="brand">
        <span className="logo" aria-hidden>
          <i />
          <i />
          <i />
          <i />
        </span>
        <div>
          <h1>Tensor Workbench</h1>
          <p className="tagline">{info?.disclaimer ?? "Synthetic workload. Not a physics solver."}</p>
        </div>
      </div>
      <div className="capacity" aria-live="polite">
        {status == null ? (
          <span className="muted">Connecting…</span>
        ) : slots === 0 ? (
          <span className="warn">No worker connected</span>
        ) : (
          <>
            <span className="capacity-label">Worker slots</span>
            <span className="slots" title={`${busy} of ${slots} slots busy`}>
              {Array.from({ length: slots }, (_, i) => (
                <span key={i} className={`slot ${i < busy ? "busy" : ""}`} />
              ))}
            </span>
            <span className="capacity-numbers">
              <b>{Math.min(busy, slots)}</b>/{slots} busy · <b>{queued}</b> queued
            </span>
          </>
        )}
      </div>
    </header>
  );
}
