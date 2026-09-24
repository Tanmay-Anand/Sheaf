import { isDate, VALUE, type Value, type XDate, ymdFromDays } from "./value";

/**
 * toText's whitelisted patterns (ir-spec §3.9), producing what Excel's TEXT(value, pattern) does
 * in an English locale. Rounding is half away from zero, as Excel's.
 */

const MONTH_ABBR = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** Rounds half away from zero at `digits` decimals, after trimming binary noise (2.675 → 2.68). */
export function roundExcel(x: number, digits: number): number {
  const f = 10 ** digits;
  const scaled = Number((Math.abs(x) * f).toPrecision(15));
  return (Math.sign(x) * Math.round(scaled)) / f;
}

function fixed(x: number, digits: number, thousands: boolean): string {
  const r = roundExcel(x, digits);
  const [int, frac] = Math.abs(r).toFixed(digits).split(".");
  const grouped = thousands ? int!.replace(/\B(?=(\d{3})+(?!\d))/g, ",") : int!;
  const sign = r < 0 ? "-" : "";
  return `${sign}${grouped}${frac !== undefined ? `.${frac}` : ""}`;
}

const pad2 = (n: number) => String(n).padStart(2, "0");

function dateText(d: XDate, pattern: string): string | null {
  const { y, m, d: day } = ymdFromDays(d.v);
  switch (pattern) {
    case "yyyy-mm-dd":
      return `${y}-${pad2(m)}-${pad2(day)}`;
    case "dd/mm/yyyy":
      return `${pad2(day)}/${pad2(m)}/${y}`;
    case "mm/dd/yyyy":
      return `${pad2(m)}/${pad2(day)}/${y}`;
    case "yyyy-mm":
      return `${y}-${pad2(m)}`;
    case "mmm yyyy":
      return `${MONTH_ABBR[m - 1]} ${y}`;
    case "yyyy":
      return String(y);
    default:
      return null;
  }
}

function numberText(x: number, pattern: string): string | null {
  switch (pattern) {
    case "0":
      return fixed(x, 0, false);
    case "0.00":
      return fixed(x, 2, false);
    case "#,##0":
      return fixed(x, 0, true);
    case "#,##0.00":
      return fixed(x, 2, true);
    case "0%":
      return `${fixed(x * 100, 0, false)}%`;
    case "0.0%":
      return `${fixed(x * 100, 1, false)}%`;
    default:
      return null;
  }
}

/** toText of one value. Null stays null; anything the pattern can't format is #VALUE!. */
export function toText(v: Value, pattern: string): Value {
  if (v === null) return null;
  if (typeof v === "number") return numberText(v, pattern) ?? VALUE;
  if (isDate(v)) return dateText(v, pattern) ?? VALUE;
  if (typeof v === "object") return v; // an error propagates
  return VALUE;
}

// ── date parts ──────────────────────────────────────────────────────────────────

/** Monday = 1 … Sunday = 7 (Excel's WEEKDAY(d, 2)). */
export function isoDayOfWeek(days: number): number {
  const dow = new Date(Math.floor(days) * 86_400_000).getUTCDay(); // Sunday = 0
  return dow === 0 ? 7 : dow;
}

/** ISO 8601 week number (Excel's ISOWEEKNUM). */
export function isoWeek(days: number): number {
  const d = Math.floor(days);
  const thursday = d + 4 - isoDayOfWeek(d);
  const { y } = ymdFromDays(thursday);
  const jan1 = Date.UTC(y, 0, 1) / 86_400_000;
  return 1 + Math.floor((thursday - jan1) / 7);
}

/** The ISO week-numbering year of a day (the year its Thursday falls in). */
export function isoWeekYear(days: number): number {
  const d = Math.floor(days);
  return ymdFromDays(d + 4 - isoDayOfWeek(d)).y;
}
