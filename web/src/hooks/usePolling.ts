import { useCallback, useEffect, useRef, useState } from "react";

interface PollingOptions<T> {
  intervalMs: number;
  /** Return false to stop polling (for example, once work reached a terminal state). */
  keepPolling?: (data: T) => boolean;
  enabled?: boolean;
}

/**
 * Fetches immediately, then again every intervalMs while keepPolling(data) holds.
 * Pauses while the tab is hidden, aborts in-flight requests on change/unmount, and
 * ignores responses that arrive after a newer request started.
 */
export function usePolling<T>(fetcher: (signal: AbortSignal) => Promise<T>, deps: unknown[], options: PollingOptions<T>) {
  const { intervalMs, keepPolling = () => true, enabled = true } = options;
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);
  const keepPollingRef = useRef(keepPolling);
  keepPollingRef.current = keepPolling;

  const refresh = useCallback(() => setTick((t) => t + 1), []);

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let controller: AbortController | undefined;

    const schedule = () => {
      timer = setTimeout(run, intervalMs);
    };

    const run = async () => {
      if (cancelled) return;
      if (document.visibilityState === "hidden") {
        schedule();
        return;
      }
      controller = new AbortController();
      try {
        const result = await fetcher(controller.signal);
        if (cancelled) return;
        setData(result);
        setError(null);
        setLoading(false);
        if (keepPollingRef.current(result)) schedule();
      } catch (e) {
        if (cancelled || (e instanceof DOMException && e.name === "AbortError")) return;
        setError(e);
        setLoading(false);
        schedule(); // transient errors: keep trying at the normal cadence
      }
    };

    setLoading(true);
    run();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
      controller?.abort();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick, enabled, intervalMs]);

  return { data, error, loading, refresh };
}
