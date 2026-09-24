import { isBlank, isDate, isError, type Value } from "./value";

/**
 * Sort order as Excel's SORT and Data › Sort produce it (ir-spec §2.4, v1.2):
 *
 * - by type, ascending: numbers and dates (dates are numbers in Excel), then text, then logicals
 *   (FALSE before TRUE), then errors; descending reverses that;
 * - blanks always last, in both directions;
 * - text ignoring case, and ignoring hyphens and apostrophes, except that of two otherwise equal
 *   strings the one with the hyphen sorts last ("coop" before "co-op"); the rest compared by
 *   Unicode collation (digits before letters);
 * - a declared category order, when the semantic model gives one, instead of text order;
 * - stable: equal keys keep their input order.
 */

const collator = new Intl.Collator("en", { sensitivity: "base", numeric: false });

function rank(v: Value): number {
  if (typeof v === "number" || isDate(v)) return 0;
  if (typeof v === "string") return 1;
  if (typeof v === "boolean") return 2;
  return 3; // error
}

const WORD_SORT_IGNORED = /['’-]/g;

export function compareText(a: string, b: string): number {
  const [xa, xb] = [a.replace(WORD_SORT_IGNORED, ""), b.replace(WORD_SORT_IGNORED, "")];
  const primary = collator.compare(xa, xb);
  if (primary !== 0) return primary;
  // Excel: "if two text strings are the same except for a hyphen, the text with the hyphen is sorted last".
  const ignored = a.length - xa.length - (b.length - xb.length);
  return ignored !== 0 ? ignored : collator.compare(a, b);
}

/** Compares two non-blank values in ascending order. */
function compareValues(a: Value, b: Value, order?: Map<string, number>): number {
  const ra = rank(a);
  const rb = rank(b);
  if (ra !== rb) return ra - rb;
  if (typeof a === "number" || isDate(a)) {
    const x = typeof a === "number" ? a : a.v;
    const y = typeof b === "number" ? b : (b as { v: number }).v;
    return x - y;
  }
  if (typeof a === "string") {
    const s = b as string;
    if (order) {
      const ia = order.get(a.toLowerCase());
      const ib = order.get(s.toLowerCase());
      if (ia !== undefined && ib !== undefined) return ia - ib;
      if (ia !== undefined) return -1; // declared members before undeclared ones
      if (ib !== undefined) return 1;
    }
    return compareText(a, s);
  }
  if (typeof a === "boolean") return Number(a) - Number(b);
  return isError(a) && isError(b) ? a.code.localeCompare(b.code) : 0;
}

export interface SortKeySpec {
  index: number;
  dir: "asc" | "desc";
  /** Declared category order (lower-cased member → position). */
  order?: Map<string, number>;
}

export function compareRows(x: Value[], y: Value[], keys: SortKeySpec[]): number {
  for (const k of keys) {
    const a = x[k.index] ?? null;
    const b = y[k.index] ?? null;
    const ba = isBlank(a);
    const bb = isBlank(b);
    if (ba || bb) {
      if (ba && bb) continue;
      return ba ? 1 : -1; // blanks last either way
    }
    const c = compareValues(a, b, k.order);
    if (c !== 0) return k.dir === "asc" ? c : -c;
  }
  return 0;
}

/** Ascending comparison for lt/gt predicates on two non-blank values of comparable types. */
export function compareForPredicate(a: Value, b: Value, order?: Map<string, number>): number {
  return compareValues(a, b, order);
}
