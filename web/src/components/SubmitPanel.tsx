import { useState, type FormEvent } from "react";
import { api, type SubmitRunBody, type SubmitSweepBody } from "../api/client";
import type { Dataset, Run, Sweep, SystemInfo } from "../api/types";
import { fieldError, useSubmit } from "../hooks/useSubmit";
import { parseDecimal, previewRange } from "../lib/decimalRange";
import { shortId } from "../lib/format";
import { ErrorNote, Field, Panel } from "./common";

type Mode = "sweep" | "single";

export function SubmitPanel({
  info,
  dataset,
  onSweep,
  onRun,
}: {
  info: SystemInfo | null;
  dataset: Dataset | null;
  onSweep: (sweep: Sweep) => void;
  onRun: (run: Run) => void;
}) {
  const [mode, setMode] = useState<Mode>("sweep");
  const [parameter, setParameter] = useState<"gain" | "bias">("gain");
  const [start, setStart] = useState("1");
  const [end, setEnd] = useState("20");
  const [step, setStep] = useState("1");
  const [fixed, setFixed] = useState("0");
  const [demoFailure, setDemoFailure] = useState(true);
  const [gain, setGain] = useState("2.5");
  const [bias, setBias] = useState("0");
  const [fault, setFault] = useState("");
  const version = info?.supportedVersions[0] ?? "1.0.0";

  const sweepSubmit = useSubmit((key: string, body: SubmitSweepBody) => api.submitSweep(key, body));
  const runSubmit = useSubmit((key: string, body: SubmitRunBody) => api.submitRun(key, body));
  const active = mode === "sweep" ? sweepSubmit : runSubmit;

  const range = previewRange(start, end, step, info?.limits.maxSweepVariants ?? 100);
  const fixedValid = parseDecimal(fixed) !== null;
  const singleValid = parseDecimal(gain) !== null && parseDecimal(bias) !== null;
  const ready = dataset?.status === "READY";
  const canSubmit = ready && !active.inFlight && (mode === "sweep" ? range.ok && fixedValid : singleValid);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!canSubmit || !dataset) return;
    if (mode === "sweep") {
      const body: SubmitSweepBody = {
        datasetId: dataset.id,
        parameter,
        start: Number(start),
        end: Number(end),
        step: Number(step),
        ...(parameter === "gain" ? { bias: Number(fixed) } : { gain: Number(fixed) }),
        implementationVersion: version,
        demoTransientFailure: demoFailure,
      };
      const sweep = await sweepSubmit.submit(body);
      if (sweep) onSweep(sweep);
    } else {
      const body: SubmitRunBody = {
        datasetId: dataset.id,
        gain: Number(gain),
        bias: Number(bias),
        implementationVersion: version,
        faultInjection: fault || null,
      };
      const run = await runSubmit.submit(body);
      if (run) onRun(run);
    }
  };

  const errs = active.fieldErrors;
  const other = parameter === "gain" ? "bias" : "gain";

  return (
    <Panel step="2" title="Submit work">
      {!dataset ? (
        <p className="muted">Select a ready dataset first.</p>
      ) : (
        <p className="target">
          Input <span className="mono">{shortId(dataset.id)}</span> · {dataset.shape.join("×")} int32 · function{" "}
          <span className="mono">
            {info?.functionName ?? "affine-material-map"}@{version}
          </span>
        </p>
      )}
      <div className="segmented" role="tablist">
        <button role="tab" aria-selected={mode === "sweep"} className={mode === "sweep" ? "on" : ""} onClick={() => setMode("sweep")}>
          Parameter sweep
        </button>
        <button role="tab" aria-selected={mode === "single"} className={mode === "single" ? "on" : ""} onClick={() => setMode("single")}>
          Single run
        </button>
      </div>

      <form className="form" onSubmit={onSubmit} noValidate>
        {mode === "sweep" ? (
          <>
            <div className="grid-4">
              <Field label="Sweep">
                <select value={parameter} onChange={(e) => setParameter(e.target.value as "gain" | "bias")}>
                  <option value="gain">gain</option>
                  <option value="bias">bias</option>
                </select>
              </Field>
              <Field label="From" error={fieldError(errs, "start")}>
                <input inputMode="decimal" value={start} onChange={(e) => setStart(e.target.value)} />
              </Field>
              <Field label="To (inclusive)" error={fieldError(errs, "end")}>
                <input inputMode="decimal" value={end} onChange={(e) => setEnd(e.target.value)} />
              </Field>
              <Field label="Step" error={fieldError(errs, "step")}>
                <input inputMode="decimal" value={step} onChange={(e) => setStep(e.target.value)} />
              </Field>
            </div>
            <Field label={`Fixed ${other}`} error={fieldError(errs, other) ?? (fixedValid ? undefined : "Enter a decimal number")}>
              <input inputMode="decimal" value={fixed} onChange={(e) => setFixed(e.target.value)} />
            </Field>
            <div className={`estimate ${range.ok ? "" : "estimate-bad"}`}>
              {range.ok ? (
                <>
                  <b>{range.count}</b> variant{range.count === 1 ? "" : "s"}: {parameter} ={" "}
                  {range.count <= 6 ? range.values.join(", ") : `${range.values.slice(0, 3).join(", ")}, …, ${range.last}`}
                  <div className="muted small">Exact decimal steps; the end is included only when a step lands on it.</div>
                </>
              ) : (
                range.error
              )}
            </div>
            {info?.allowFaultInjection && (
              <label className="check">
                <input type="checkbox" checked={demoFailure} onChange={(e) => setDemoFailure(e.target.checked)} />
                <span>
                  <b>Demo:</b> make one child fail transiently on its first attempt (it is then retried automatically)
                </span>
              </label>
            )}
          </>
        ) : (
          <>
            <div className="grid-2">
              <Field label="gain" error={fieldError(errs, "gain")}>
                <input inputMode="decimal" value={gain} onChange={(e) => setGain(e.target.value)} />
              </Field>
              <Field label="bias" error={fieldError(errs, "bias")}>
                <input inputMode="decimal" value={bias} onChange={(e) => setBias(e.target.value)} />
              </Field>
            </div>
            {info?.allowFaultInjection && (
              <Field label="Fault injection (demo/test)" error={fieldError(errs, "faultInjection")}>
                <select value={fault} onChange={(e) => setFault(e.target.value)}>
                  <option value="">None</option>
                  <option value="TRANSIENT_ON_FIRST_ATTEMPT">Transient failure on first attempt (retried)</option>
                  <option value="INVALID_INPUT">Invalid input (permanent, not retried)</option>
                </select>
              </Field>
            )}
          </>
        )}
        <p className="formula">
          output = gain × coefficient[material code] + bias, as float32 <span className="muted">(synthetic)</span>
        </p>
        {fieldError(errs, "implementationVersion") && <ErrorNote>{fieldError(errs, "implementationVersion")}</ErrorNote>}
        {active.error && !errs.length && (
          <ErrorNote>
            {active.error}
            {active.retryPending && " — submitting again reuses the same idempotency key, so no duplicate is created."}
          </ErrorNote>
        )}
        <button type="submit" className="primary" disabled={!canSubmit}>
          {active.inFlight
            ? "Submitting…"
            : active.retryPending
              ? "Retry submission"
              : mode === "sweep"
                ? `Submit sweep${range.ok ? ` (${range.count} runs)` : ""}`
                : "Submit run"}
        </button>
      </form>
    </Panel>
  );
}
