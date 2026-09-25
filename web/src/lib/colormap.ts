/**
 * A 256-entry perceptually ordered colour ramp (dark blue → teal → yellow),
 * interpolated from a few anchor colours close to matplotlib's "viridis".
 */
const ANCHORS: [number, number, number][] = [
  [68, 1, 84],
  [59, 82, 139],
  [33, 145, 140],
  [94, 201, 98],
  [253, 231, 37],
];

export const COLORMAP: Uint8ClampedArray = (() => {
  const lut = new Uint8ClampedArray(256 * 3);
  for (let i = 0; i < 256; i++) {
    const t = (i / 255) * (ANCHORS.length - 1);
    const k = Math.min(ANCHORS.length - 2, Math.floor(t));
    const f = t - k;
    for (let c = 0; c < 3; c++) {
      lut[i * 3 + c] = Math.round(ANCHORS[k][c] + (ANCHORS[k + 1][c] - ANCHORS[k][c]) * f);
    }
  }
  return lut;
})();

export function cssGradient(): string {
  const stops = ANCHORS.map(([r, g, b], i) => `rgb(${r} ${g} ${b}) ${(i / (ANCHORS.length - 1)) * 100}%`);
  return `linear-gradient(to right, ${stops.join(", ")})`;
}

export function decodeBase64(data: string): Uint8Array {
  const binary = atob(data);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

/** Inverse of the worker's 8-bit quantization (approximate by construction). */
export function dequantize(q: number, min: number, max: number): number {
  return min + (q / 255) * (max - min);
}
