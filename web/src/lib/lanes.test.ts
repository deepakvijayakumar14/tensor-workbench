import { describe, expect, it } from "vitest";
import { assignLanes, peakOverlap } from "./lanes";

describe("timeline lanes", () => {
  const intervals = [
    { start: 0, end: 10 },
    { start: 0, end: 5 },
    { start: 1, end: 6 },
    { start: 5, end: 9 }, // starts exactly when one ends: reuses its lane
    { start: 6, end: 12 },
  ];

  it("uses as many lanes as the peak overlap", () => {
    const lanes = Math.max(...assignLanes(intervals).map((l) => l.lane)) + 1;
    expect(peakOverlap(intervals)).toBe(3);
    expect(lanes).toBe(3);
  });

  it("never places overlapping intervals in the same lane", () => {
    const packed = assignLanes(intervals);
    for (const a of packed) {
      for (const b of packed) {
        if (a !== b && a.lane === b.lane) {
          expect(a.item.end <= b.item.start || b.item.end <= a.item.start).toBe(true);
        }
      }
    }
  });
});
