import type { SweepTimeline } from "../api/types";
import { assignLanes } from "../lib/lanes";

const LANE_HEIGHT = 22;
const LANE_GAP = 6;
const LABEL_WIDTH = 56;

/**
 * Each bar is one attempt, from compute start to compute end as reported by the
 * worker. Bars are packed into the fewest lanes that avoid overlap, so the number
 * of lanes equals the peak number of simultaneous computations.
 */
export function Timeline({ timeline }: { timeline: SweepTimeline }) {
  const now = Date.parse(timeline.now);
  const intervals = timeline.intervals.map((i) => ({
    ...i,
    startMs: Date.parse(i.start),
    endMs: i.end ? Date.parse(i.end) : now,
  }));
  if (!intervals.length) {
    return <p className="muted small">Waiting for the first attempt to start…</p>;
  }
  const t0 = Math.min(...intervals.map((i) => i.startMs));
  const t1 = Math.max(now, ...intervals.map((i) => i.endMs));
  const span = Math.max(1, t1 - t0);
  const packed = assignLanes(intervals.map((i) => ({ ...i, start: i.startMs, end: i.endMs })));
  const lanes = Math.max(1, ...packed.map((p) => p.lane + 1));
  const slots = timeline.workerSlots ?? lanes;
  const width = 640;
  const height = Math.max(lanes, slots) * (LANE_HEIGHT + LANE_GAP) + 18;
  const x = (ms: number) => LABEL_WIDTH + ((ms - t0) / span) * (width - LABEL_WIDTH - 8);

  return (
    <figure className="timeline">
      <figcaption>
        <span>
          Peak simultaneous computations: <b>{timeline.peakConcurrency}</b>
          {timeline.workerSlots != null && <> of {timeline.workerSlots} slots</>}
        </span>
        <span className="legend">
          <i className="bar-succeeded" /> succeeded <i className="bar-failed" /> failed <i className="bar-running" /> running
          <i className="bar-lease_expired" /> lease expired
        </span>
      </figcaption>
      <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label={`Timeline with peak concurrency ${timeline.peakConcurrency}`}>
        {Array.from({ length: Math.max(lanes, slots) }, (_, lane) => (
          <g key={lane}>
            <rect
              className={lane < slots ? "lane" : "lane lane-over"}
              x={LABEL_WIDTH}
              y={lane * (LANE_HEIGHT + LANE_GAP)}
              width={width - LABEL_WIDTH - 8}
              height={LANE_HEIGHT}
              rx={4}
            />
            <text className="lane-label" x={0} y={lane * (LANE_HEIGHT + LANE_GAP) + 15}>
              lane {lane + 1}
            </text>
          </g>
        ))}
        {packed.map(({ item, lane }) => {
          const x0 = x(item.startMs);
          const w = Math.max(2, x(item.endMs) - x0);
          const state = item.outcome ? item.outcome.toLowerCase() : "running";
          return (
            <g key={`${item.runId}-${item.attemptNumber}`}>
              <rect className={`bar bar-${state}`} x={x0} y={lane * (LANE_HEIGHT + LANE_GAP) + 2} width={w} height={LANE_HEIGHT - 4} rx={3}>
                <title>
                  #{item.sweepOrdinal} attempt {item.attemptNumber} · {item.outcome ?? "running"}
                  {item.errorCategory ? ` (${item.errorCategory})` : ""} · {item.leaseOwner} ·{" "}
                  {((item.endMs - item.startMs) / 1000).toFixed(2)} s
                </title>
              </rect>
              {w > 26 && (
                <text className="bar-label" x={x0 + 4} y={lane * (LANE_HEIGHT + LANE_GAP) + 15}>
                  #{item.sweepOrdinal}
                  {item.attemptNumber > 1 ? `·${item.attemptNumber}` : ""}
                </text>
              )}
            </g>
          );
        })}
        <text className="axis-label" x={LABEL_WIDTH} y={height - 2}>
          0 s
        </text>
        <text className="axis-label" x={width - 8} y={height - 2} textAnchor="end">
          {(span / 1000).toFixed(1)} s
        </text>
      </svg>
    </figure>
  );
}
