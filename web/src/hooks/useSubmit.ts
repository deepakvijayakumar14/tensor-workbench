import { useCallback, useRef, useState } from "react";
import { ApiError } from "../api/client";
import type { FieldError } from "../api/types";
import { IdempotentSubmitter } from "../lib/idempotency";

export interface SubmitState {
  inFlight: boolean;
  error: string | null;
  fieldErrors: FieldError[];
  /** True after an ambiguous failure: the next click retries with the same key. */
  retryPending: boolean;
}

/**
 * Wraps a submission with double-submit protection and idempotency-key reuse.
 */
export function useSubmit<Body, Result>(send: (key: string, body: Body) => Promise<Result>) {
  const submitter = useRef(new IdempotentSubmitter());
  const inFlight = useRef(false);
  const [state, setState] = useState<SubmitState>({ inFlight: false, error: null, fieldErrors: [], retryPending: false });

  const submit = useCallback(
    async (body: Body): Promise<Result | null> => {
      if (inFlight.current) return null; // a click while a request is in flight does nothing
      inFlight.current = true;
      setState((s) => ({ ...s, inFlight: true, error: null, fieldErrors: [] }));
      const key = submitter.current.keyFor(body);
      try {
        const result = await send(key, body);
        submitter.current.settle("succeeded");
        setState({ inFlight: false, error: null, fieldErrors: [], retryPending: false });
        return result;
      } catch (e) {
        const ambiguous = !(e instanceof ApiError) || e.ambiguous;
        submitter.current.settle(ambiguous ? "ambiguous" : "rejected");
        setState({
          inFlight: false,
          error: e instanceof Error ? e.message : String(e),
          fieldErrors: e instanceof ApiError ? e.body.fieldErrors : [],
          retryPending: ambiguous,
        });
        return null;
      } finally {
        inFlight.current = false;
      }
    },
    [send],
  );

  return { submit, ...state };
}

export function fieldError(errors: FieldError[], field: string): string | undefined {
  return errors.find((e) => e.field === field || e.field.startsWith(field + "["))?.message;
}
