/**
 * Runtime values of the evaluator. Dates are tagged so they are never confused with numbers
 * (Excel stores both as numbers; the catalog knows which is which, and loading applies it).
 * Error values are tagged too, so they propagate like Excel's instead of turning into text.
 */

export interface XDate {
  readonly t: "d";
  /** Days since 1970-01-01 (UTC); the fraction is the time of day. */
  readonly v: number;
}

export interface XError {
  readonly t: "e";
  /** Excel's error code, e.g. "#N/A", "#DIV/0!", "#VALUE!". */
  readonly code: string;
}

export type Value = null | number | string | boolean | XDate | XError;

export function xdate(days: number): XDate {
  return { t: "d", v: days };
}

export function xerror(code: string): XError {
  return { t: "e", code };
}

export const DIV0 = xerror("#DIV/0!");
export const VALUE = xerror("#VALUE!");

export function isDate(v: Value): v is XDate {
  return typeof v === "object" && v !== null && v.t === "d";
}

export function isError(v: Value): v is XError {
  return typeof v === "object" && v !== null && v.t === "e";
}

/** Blank is blank whether the cell is empty or holds "" (ir-spec §5.6). */
export function isBlank(v: Value): boolean {
  return v === null || (typeof v === "string" && v.trim() === "");
}

// ── dates ────────────────────────────────────────────────────────────────────

const DAY_MS = 86_400_000;

export function daysFromYmd(y: number, m: number, d: number): number {
  return Date.UTC(y, m - 1, d) / DAY_MS;
}

export function ymdFromDays(days: number): { y: number; m: number; d: number } {
  const t = new Date(Math.floor(days) * DAY_MS);
  return { y: t.getUTCFullYear(), m: t.getUTCMonth() + 1, d: t.getUTCDate() };
}

/** Reads "yyyy-mm-dd" (and, for datetimes, "yyyy-mm-ddThh:mm[:ss]"). Null when invalid. */
export function parseIsoDate(s: string): XDate | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2}))?)?$/.exec(s.trim());
  if (!m) return null;
  const [y, mo, d] = [+m[1]!, +m[2]!, +m[3]!];
  const days = daysFromYmd(y, mo, d);
  const back = ymdFromDays(days);
  if (back.y !== y || back.m !== mo || back.d !== d) return null;
  const time = m[4] === undefined ? 0 : (+m[4] * 3600 + +m[5]! * 60 + +(m[6] ?? 0)) / 86_400;
  if (time >= 1) return null;
  return xdate(days + time);
}

export type DateSystem = "1900" | "1904" | "unknown";

/** Excel serial → days since 1970. The 1900 system counts a fictitious 29 Feb 1900 (serial 60). */
export function daysFromSerial(serial: number, system: DateSystem): number {
  if (system === "1904") return serial - 24_107;
  return serial < 60 ? serial - 25_568 : serial - 25_569;
}

export function serialFromDays(days: number, system: DateSystem): number {
  if (system === "1904") return days + 24_107;
  const serial = days + 25_569;
  return serial < 61 ? serial - 1 : serial;
}

// ── text, as Excel shows a value in a formula result ────────────────────────────

/** A number as Excel's General format writes it when concatenated: up to 15 significant digits. */
export function numberText(n: number): string {
  if (!Number.isFinite(n)) return VALUE.code;
  const r = Number(n.toPrecision(15));
  if (Object.is(r, -0)) return "0";
  const abs = Math.abs(r);
  if (abs !== 0 && (abs >= 1e21 || abs < 1e-9)) return r.toExponential().replace("e+", "E+").replace("e-", "E-");
  return String(r);
}

export function isoText(d: XDate): string {
  const { y, m, d: day } = ymdFromDays(d.v);
  const date = `${String(y).padStart(4, "0")}-${String(m).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
  const frac = d.v - Math.floor(d.v);
  if (frac === 0) return date;
  const minutes = Math.round(frac * 1440);
  return `${date} ${String(Math.floor(minutes / 60)).padStart(2, "0")}:${String(minutes % 60).padStart(2, "0")}`;
}

/** The value as text inside a concat: numbers in General, dates as ISO, booleans as TRUE/FALSE. */
export function displayText(v: Value): string {
  if (v === null) return "";
  if (typeof v === "string") return v;
  if (typeof v === "number") return numberText(v);
  if (typeof v === "boolean") return v ? "TRUE" : "FALSE";
  return isDate(v) ? isoText(v) : v.code;
}

// ── equality and grouping keys (Excel compares text ignoring case) ─────────────────

export function fold(s: string): string {
  return s.toLowerCase();
}

/**
 * A key that is equal for values Excel's "=" treats as equal: text ignoring case, a date and the
 * same day at midnight, numbers by value. Blank ("" or empty) is one key.
 */
export function keyOf(v: Value): string {
  if (isBlank(v)) return "\u0000";
  if (typeof v === "string") return `s${fold(v)}`;
  if (typeof v === "number") return `n${v}`;
  if (typeof v === "boolean") return v ? "bT" : "bF";
  if (isDate(v)) return `d${v.v}`;
  return isError(v) ? `e${v.code}` : "\u0000";
}

/** Excel's "=" on two non-error values. Blank equals only blank. */
export function valuesEqual(a: Value, b: Value): boolean {
  return keyOf(a) === keyOf(b);
}
