import { describe, expect, it } from "vitest";
import { IdempotentSubmitter } from "./idempotency";

function submitter() {
  let n = 0;
  return new IdempotentSubmitter(() => `key-${++n}`);
}

describe("IdempotentSubmitter", () => {
  it("reuses the key when retrying the same payload after an ambiguous failure", () => {
    const s = submitter();
    const first = s.keyFor({ gain: 1 });
    s.settle("ambiguous");
    expect(s.keyFor({ gain: 1 })).toBe(first);
  });

  it("uses a new key after success, a definitive rejection, or a changed payload", () => {
    const s = submitter();
    const a = s.keyFor({ gain: 1 });
    s.settle("succeeded");
    const b = s.keyFor({ gain: 1 });
    expect(b).not.toBe(a);
    s.settle("rejected");
    const c = s.keyFor({ gain: 1 });
    expect(c).not.toBe(b);
    s.settle("ambiguous");
    expect(s.keyFor({ gain: 2 })).not.toBe(c);
  });
});
