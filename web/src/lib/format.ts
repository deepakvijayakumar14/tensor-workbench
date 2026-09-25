export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null) return "—";
  const units = ["B", "KiB", "MiB", "GiB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value < 10 && unit > 0 ? value.toFixed(1) : Math.round(value)} ${units[unit]}`;
}

export function formatCount(n: number): string {
  return n.toLocaleString("en-US");
}

export function formatNumber(n: number | null | undefined, digits = 4): string {
  if (n == null) return "—";
  if (n !== 0 && (Math.abs(n) >= 1e6 || Math.abs(n) < 1e-3)) return n.toExponential(2);
  return Number(n.toPrecision(digits)).toString();
}

export function formatSeconds(s: number | null | undefined): string {
  if (s == null) return "—";
  return s < 10 ? `${s.toFixed(2)} s` : `${s.toFixed(1)} s`;
}

export function secondsBetween(from: string | null, to: string | null | Date): number | null {
  if (!from) return null;
  const end = to instanceof Date ? to.getTime() : to ? Date.parse(to) : Date.now();
  return (end - Date.parse(from)) / 1000;
}

export function shortId(id: string): string {
  return id.slice(0, 8);
}

export function timeAgo(iso: string, now = Date.now()): string {
  const seconds = Math.max(0, Math.round((now - Date.parse(iso)) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  return `${Math.round(minutes / 60)}h ago`;
}
