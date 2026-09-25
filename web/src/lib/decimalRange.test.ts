import { describe, expect, it } from "vitest";
import { previewRange } from "./decimalRange";

describe("previewRange", () => {
  it("includes the end when a step lands on it", () => {
    const r = previewRange("1", "20", "1", 100);
    expect(r.ok && r.count).toBe(20);
    expect(r.ok && [r.first, r.last]).toEqual(["1", "20"]);
  });

  it("excludes an end that is not reached exactly", () => {
    const r = previewRange("0", "1", "0.3", 100);
    expect(r.ok && r.values).toEqual(["0", "0.3", "0.6", "0.9"]);
  });

  it("does not drift with decimal steps", () => {
    const r = previewRange("0.1", "0.3", "0.1", 100);
    // Repeated float addition gives 0.30000000000000004 and would drop the endpoint.
    expect(r.ok && r.values).toEqual(["0.1", "0.2", "0.3"]);
  });

  it("handles negative ranges", () => {
    const r = previewRange("-1", "1", "0.5", 100);
    expect(r.ok && r.values).toEqual(["-1", "-0.5", "0", "0.5", "1"]);
  });

  it("rejects invalid ranges", () => {
    expect(previewRange("5", "1", "1", 100).ok).toBe(false);
    expect(previewRange("1", "5", "0", 100).ok).toBe(false);
    expect(previewRange("1", "5", "abc", 100).ok).toBe(false);
    expect(previewRange("1", "1000", "1", 100)).toMatchObject({ ok: false, error: expect.stringContaining("1000 variants") });
  });
});
