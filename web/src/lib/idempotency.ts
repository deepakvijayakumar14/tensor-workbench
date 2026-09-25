/**
 * Chooses idempotency keys for submissions.
 *
 *  - A deliberate new submission (different payload, or the last one succeeded or
 *    was definitively rejected) gets a fresh key.
 *  - Retrying the same payload after an ambiguous failure (network error, 5xx)
 *    reuses the previous key, so the server returns the original resource instead
 *    of creating a duplicate.
 */
export class IdempotentSubmitter {
  private pending: { key: string; payload: string } | null = null;

  constructor(private readonly newKey: () => string = () => crypto.randomUUID()) {}

  keyFor(payload: unknown): string {
    const serialized = JSON.stringify(payload);
    if (this.pending?.payload === serialized) return this.pending.key;
    this.pending = { key: this.newKey(), payload: serialized };
    return this.pending.key;
  }

  /** Call after the request finished. Only ambiguous failures keep the key for reuse. */
  settle(outcome: "succeeded" | "rejected" | "ambiguous"): void {
    if (outcome !== "ambiguous") this.pending = null;
  }

  get hasPendingRetry(): boolean {
    return this.pending !== null;
  }
}
