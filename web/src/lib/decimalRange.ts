/**
 * Client-side preview of a sweep's values, matching the API's exact decimal rule:
 * values are start + k*step while <= end (end included only if hit exactly).
 *
 * Inputs are parsed as scaled integers (BigInt), so "0.1" steps never drift the
 * way repeated floating-point addition does (0.1 + 0.2 !== 0.3).
 */

const DECIMAL = /^-?\d+(\.\d+)?$/;
export const MAX_DECIMALS = 6;

interface Scaled {
  value: bigint;
  decimals: number;
}

export function parseDecimal(text: string): Scaled | null {
  const trimmed = text.trim();
  if (!DECIMAL.test(trimmed)) return null;
  const [whole, fraction = ""] = trimmed.replace("-", "").split(".");
  if (fraction.length > MAX_DECIMALS) return null;
  const magnitude = BigInt(whole + fraction);
  return { value: trimmed.startsWith("-") ? -magnitude : magnitude, decimals: fraction.length };
}

function rescale(s: Scaled, decimals: number): bigint {
  return s.value * 10n ** BigInt(decimals - s.decimals);
}

export function formatScaled(value: bigint, decimals: number): string {
  const negative = value < 0n;
  const digits = (negative ? -value : value).toString().padStart(decimals + 1, "0");
  const whole = digits.slice(0, digits.length - decimals);
  const fraction = decimals > 0 ? digits.slice(digits.length - decimals).replace(/0+$/, "") : "";
  return `${negative ? "-" : ""}${whole}${fraction ? "." + fraction : ""}`;
}

export type RangePreview =
  | { ok: true; count: number; first: string; last: string; values: string[] }
  | { ok: false; error: string };

export function previewRange(start: string, end: string, step: string, maxVariants: number): RangePreview {
  const s = parseDecimal(start);
  const e = parseDecimal(end);
  const st = parseDecimal(step);
  if (!s || !e || !st) return { ok: false, error: `Use plain decimal numbers with at most ${MAX_DECIMALS} decimal places` };
  const decimals = Math.max(s.decimals, e.decimals, st.decimals);
  const a = rescale(s, decimals);
  const b = rescale(e, decimals);
  const d = rescale(st, decimals);
  if (d <= 0n) return { ok: false, error: "Step must be greater than zero" };
  if (a > b) return { ok: false, error: "End must be greater than or equal to start" };
  const count = (b - a) / d + 1n; // BigInt division truncates; both operands are non-negative here
  if (count > BigInt(maxVariants)) {
    return { ok: false, error: `This range produces ${count} variants; the maximum is ${maxVariants}` };
  }
  const values = Array.from({ length: Number(count) }, (_, k) => formatScaled(a + BigInt(k) * d, decimals));
  return { ok: true, count: Number(count), first: values[0], last: values[values.length - 1], values };
}
