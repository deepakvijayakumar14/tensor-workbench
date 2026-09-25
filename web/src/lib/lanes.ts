/**
 * Greedy interval packing for the timeline: each bar goes into the first lane that
 * is free at its start time. For intervals, the number of lanes this produces
 * equals the maximum number of simultaneously active intervals, so the chart's
 * height is itself a picture of peak concurrency.
 */
export interface Interval {
  start: number;
  end: number;
}

export function assignLanes<T extends Interval>(items: T[]): { item: T; lane: number }[] {
  const sorted = [...items].sort((a, b) => a.start - b.start || a.end - b.end);
  const laneEnds: number[] = [];
  return sorted.map((item) => {
    let lane = laneEnds.findIndex((end) => end <= item.start);
    if (lane === -1) {
      lane = laneEnds.length;
      laneEnds.push(item.end);
    } else {
      laneEnds[lane] = item.end;
    }
    return { item, lane };
  });
}

export function peakOverlap(items: Interval[]): number {
  const events = items.flatMap((i) => [
    [i.start, 1],
    [i.end, -1],
  ]);
  // Ends before starts at the same instant: back-to-back work is not overlap.
  events.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  let current = 0;
  let peak = 0;
  for (const [, delta] of events) {
    current += delta;
    peak = Math.max(peak, current);
  }
  return peak;
}
