import type { ReactNode } from "react";
import type { DatasetStatus, RunState } from "../api/types";

const STATE_LABEL: Record<string, string> = {
  QUEUED: "Queued",
  RUNNING: "Running",
  GENERATING: "Generating",
  SUCCEEDED: "Succeeded",
  READY: "Ready",
  FAILED: "Failed",
};

export function StateBadge({ state }: { state: RunState | DatasetStatus }) {
  return <span className={`badge badge-${state.toLowerCase()}`}>{STATE_LABEL[state] ?? state}</span>;
}

export function Panel({ title, step, actions, children }: { title: string; step?: string; actions?: ReactNode; children: ReactNode }) {
  return (
    <section className="panel">
      <header className="panel-header">
        <h2>
          {step && <span className="step">{step}</span>}
          {title}
        </h2>
        {actions && <div className="panel-actions">{actions}</div>}
      </header>
      {children}
    </section>
  );
}

export function Pager({ page, totalPages, onChange }: { page: number; totalPages: number; onChange: (page: number) => void }) {
  if (totalPages <= 1) return null;
  return (
    <div className="pager">
      <button className="ghost small" disabled={page === 0} onClick={() => onChange(page - 1)}>
        ‹ Prev
      </button>
      <span>
        Page {page + 1} of {totalPages}
      </span>
      <button className="ghost small" disabled={page >= totalPages - 1} onClick={() => onChange(page + 1)}>
        Next ›
      </button>
    </div>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="empty">{children}</p>;
}

export function Loading({ label = "Loading…" }: { label?: string }) {
  return <p className="loading">{label}</p>;
}

export function ErrorNote({ children }: { children: ReactNode }) {
  return (
    <p className="error-note" role="alert">
      {children}
    </p>
  );
}

export function Field({ label, error, hint, children }: { label: string; error?: string; hint?: ReactNode; children: ReactNode }) {
  return (
    <label className={`field ${error ? "has-error" : ""}`}>
      <span className="field-label">{label}</span>
      {children}
      {error ? <span className="field-error">{error}</span> : hint ? <span className="field-hint">{hint}</span> : null}
    </label>
  );
}
