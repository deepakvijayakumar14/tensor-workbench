import { useEffect, useRef, useState, type MouseEvent } from "react";
import { api, describeError } from "../api/client";
import type { PreviewInfo, PreviewSlice } from "../api/types";
import { COLORMAP, cssGradient, decodeBase64, dequantize } from "../lib/colormap";
import { formatNumber } from "../lib/format";

const AXIS_NAMES = ["X", "Y", "Z"];
const DISPLAY_SIZE = 384;

/**
 * Shows one slice of a run's output. Each slider position fetches one bounded,
 * pre-rendered slice (at most maxDimension² bytes); the full array never leaves
 * storage. Rapid slider moves abort superseded requests, and a response is drawn
 * only if it is still the latest one requested.
 */
export function SliceViewer({ runId, preview }: { runId: string; preview: PreviewInfo }) {
  // Default to an X slice: it cuts through the layers, vias and inclusions at once.
  const [axis, setAxis] = useState(0);
  const axisInfo = preview.axes[axis];
  const [index, setIndex] = useState(Math.floor(preview.axes[0].sliceCount / 2));
  const [slice, setSlice] = useState<PreviewSlice | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [hover, setHover] = useState<{ row: number; col: number; value: number } | null>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const latest = useRef(0);
  const pixels = useRef<Uint8Array | null>(null);

  const changeAxis = (next: number) => {
    setAxis(next);
    setIndex(Math.floor(preview.axes[next].sliceCount / 2));
  };

  useEffect(() => {
    const requestId = ++latest.current;
    const controller = new AbortController();
    // A short debounce keeps a dragged slider from issuing a request per pixel.
    const timer = setTimeout(async () => {
      try {
        const result = await api.preview(runId, axis, index, controller.signal);
        if (requestId !== latest.current) return; // an older response arriving late is ignored
        setSlice(result);
        setError(null);
      } catch (e) {
        if (e instanceof DOMException && e.name === "AbortError") return;
        if (requestId === latest.current) setError(describeError(e));
      }
    }, 40);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [runId, axis, index]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || !slice) return;
    const values = decodeBase64(slice.data);
    pixels.current = values;
    canvas.width = slice.width;
    canvas.height = slice.height;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    const image = ctx.createImageData(slice.width, slice.height);
    for (let i = 0; i < values.length; i++) {
      const q = values[i];
      image.data[i * 4] = COLORMAP[q * 3];
      image.data[i * 4 + 1] = COLORMAP[q * 3 + 1];
      image.data[i * 4 + 2] = COLORMAP[q * 3 + 2];
      image.data[i * 4 + 3] = 255;
    }
    ctx.putImageData(image, 0, 0);
  }, [slice]);

  const onMove = (e: MouseEvent<HTMLCanvasElement>) => {
    if (!slice || !pixels.current) return;
    const rect = e.currentTarget.getBoundingClientRect();
    const col = Math.min(slice.width - 1, Math.floor(((e.clientX - rect.left) / rect.width) * slice.width));
    const row = Math.min(slice.height - 1, Math.floor(((e.clientY - rect.top) / rect.height) * slice.height));
    setHover({ row, col, value: dequantize(pixels.current[row * slice.width + col], slice.valueMin, slice.valueMax) });
  };

  const [rowAxis, colAxis] = [0, 1, 2].filter((a) => a !== axis);
  const downsampled = axisInfo.height < axisInfo.sourceHeight || axisInfo.width < axisInfo.sourceWidth;
  const aspect = axisInfo.width / axisInfo.height;
  const displayWidth = aspect >= 1 ? DISPLAY_SIZE : Math.round(DISPLAY_SIZE * aspect);
  const displayHeight = aspect >= 1 ? Math.round(DISPLAY_SIZE / aspect) : DISPLAY_SIZE;

  return (
    <div className="slice-viewer">
      <div className="slice-controls">
        <div className="segmented small" role="radiogroup" aria-label="Slice axis">
          {AXIS_NAMES.map((name, a) => (
            <button key={name} role="radio" aria-checked={axis === a} className={axis === a ? "on" : ""} onClick={() => changeAxis(a)}>
              {name}
            </button>
          ))}
        </div>
        <label className="slider">
          <span>
            {AXIS_NAMES[axis]} = <b className="mono">{index}</b> / {axisInfo.sliceCount - 1}
          </span>
          <input
            type="range"
            min={0}
            max={axisInfo.sliceCount - 1}
            value={index}
            onChange={(e) => setIndex(Number(e.target.value))}
            aria-label={`Slice index along ${AXIS_NAMES[axis]}`}
          />
        </label>
      </div>

      <div className="heatmap-frame" style={{ width: displayWidth }}>
        <canvas
          ref={canvasRef}
          className="heatmap"
          style={{ width: displayWidth, height: displayHeight }}
          onMouseMove={onMove}
          onMouseLeave={() => setHover(null)}
        />
        <span className="axis-tag axis-rows">{AXIS_NAMES[rowAxis]} ↓</span>
        <span className="axis-tag axis-cols">{AXIS_NAMES[colAxis]} →</span>
        {!slice && !error && <div className="heatmap-overlay">Loading slice…</div>}
      </div>

      <div className="colorbar">
        <span className="mono">{formatNumber(preview.valueMin)}</span>
        <span className="colorbar-ramp" style={{ background: cssGradient() }} />
        <span className="mono">{formatNumber(preview.valueMax)}</span>
      </div>
      <p className="muted small readout">
        {hover
          ? `${AXIS_NAMES[rowAxis]} row ${hover.row}, ${AXIS_NAMES[colAxis]} col ${hover.col}: ≈ ${formatNumber(hover.value)}`
          : "Hover the heatmap to read approximate values."}
      </p>
      {error && <p className="error-note">{error}</p>}
      <p className="muted small">
        Preview: {axisInfo.height}×{axisInfo.width} of {axisInfo.sourceHeight}×{axisInfo.sourceWidth}
        {downsampled ? " (nearest-neighbour downsampled)" : " (full resolution)"}, 8-bit quantized, max {preview.maxDimension}² per
        slice. Download the result for exact float32 values.
      </p>
    </div>
  );
}
