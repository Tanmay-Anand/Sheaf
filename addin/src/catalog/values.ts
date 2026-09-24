import type { CellValue } from "./types";

/**
 * Cell-level parsing. Excel hands over typed values (numbers with a number format); CSVs and
 * pasted data hand over strings. Both end up as one of these.
 */
export type Parsed =
  | { k: "null" }
  | { k: "error"; code: string }
  | { k: "number"; v: number; integer: boolean }
  | { k: "currency"; v: number; unit: string }
  | { k: "percent"; v: number }
  | { k: "date"; format: string; numeric?: NumericDate }
  | { k: "datetime"; format: string }
  | { k: "boolean" }
  | { k: "string"; v: string };

/** A day/month/year written only in digits, where day-first vs month-first is undecided. */
export interface NumericDate {
  a: number;
  b: number;
  sep: string;
  yearDigits: number;
}

const NULL_TOKENS = new Set(["", "n/a", "#n/a", "-", "—", "–", "null"]);
const ERROR_RE = /^#(N\/A|DIV\/0!|VALUE!|REF!|NAME\?|NUM!|NULL!|SPILL!|CALC!|GETTING_DATA|FIELD!|BUSY!|BLOCKED!|CONNECT!|UNKNOWN!)$/i;

const SYMBOL_UNITS: Record<string, string> = {
  $: "USD",
  us$: "USD",
  usd: "USD",
  "₹": "INR",
  rs: "INR",
  "rs.": "INR",
  inr: "INR",
  "€": "EUR",
  eur: "EUR",
  "£": "GBP",
  gbp: "GBP",
  "¥": "JPY",
  jpy: "JPY",
};

const NUM = String.raw`(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?|\.\d+`;
const NUMBER_RE = new RegExp(`^[-+]?(?:${NUM})$`);
const SCI_RE = /^[-+]?\d+(?:\.\d+)?[eE][-+]?\d+$/;
const ACCOUNTING_RE = new RegExp(`^\\((${NUM})\\)$`);
const PERCENT_RE = new RegExp(`^([-+]?(?:${NUM}))\\s*%$`);
const CURRENCY_PREFIX_RE = new RegExp(`^([-+]?)\\s*(US\\$|\\$|₹|Rs\\.?|INR|USD|€|EUR|£|GBP|¥|JPY)\\s*([-+]?(?:${NUM}))$`, "i");
const CURRENCY_SUFFIX_RE = new RegExp(`^([-+]?(?:${NUM}))\\s*(INR|USD|EUR|GBP|JPY|₹|€|£)$`, "i");

function toNumber(text: string): number {
  return Number(text.replace(/,/g, ""));
}

const MONTHS: Record<string, number> = {
  jan: 1, january: 1, feb: 2, february: 2, mar: 3, march: 3, apr: 4, april: 4, may: 5,
  jun: 6, june: 6, jul: 7, july: 7, aug: 8, august: 8, sep: 9, sept: 9, september: 9,
  oct: 10, october: 10, nov: 11, november: 11, dec: 12, december: 12,
};
const MONTH = "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)";

const ISO_DATE = /^(\d{4})-(\d{1,2})-(\d{1,2})$/;
const ISO_DATETIME = /^(\d{4})-(\d{1,2})-(\d{1,2})[T ](\d{1,2}):(\d{2})(?::\d{2}(?:\.\d+)?)?(?:Z|[+-]\d{2}:?\d{2})?$/;
const YMD_SLASH = /^(\d{4})\/(\d{1,2})\/(\d{1,2})$/;
const NUMERIC_DATE = /^(\d{1,2})([/.-])(\d{1,2})\2(\d{4}|\d{2})$/;
const MONTH_D_Y = new RegExp(`^${MONTH}\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})$`, "i");
const D_MONTH_Y = new RegExp(`^(\\d{1,2})[-\\s]${MONTH}\\.?[-\\s,]+(\\d{4}|\\d{2})$`, "i");
const Y_MONTH_D = new RegExp(`^(\\d{4})[-\\s]${MONTH}[-\\s](\\d{1,2})$`, "i");

function validYmd(y: number, m: number, d: number): boolean {
  return y >= 1900 && y <= 2200 && m >= 1 && m <= 12 && d >= 1 && d <= 31;
}

function monthLabel(word: string): string {
  return word.length > 3 && word.toLowerCase() !== "sept" ? "Month" : "Mon";
}

function yearFrom(text: string): number {
  const n = Number(text);
  return text.length === 2 ? 2000 + n : n;
}

export function parseDate(s: string): Parsed | null {
  let m = ISO_DATE.exec(s);
  if (m) return validYmd(+m[1]!, +m[2]!, +m[3]!) ? { k: "date", format: "yyyy-mm-dd" } : null;
  m = ISO_DATETIME.exec(s);
  if (m) return validYmd(+m[1]!, +m[2]!, +m[3]!) ? { k: "datetime", format: "yyyy-mm-dd hh:mm" } : null;
  m = YMD_SLASH.exec(s);
  if (m) return validYmd(+m[1]!, +m[2]!, +m[3]!) ? { k: "date", format: "yyyy/mm/dd" } : null;
  m = NUMERIC_DATE.exec(s);
  if (m) {
    const a = +m[1]!;
    const b = +m[3]!;
    const y = yearFrom(m[4]!);
    const dayFirst = a <= 31 && b <= 12;
    const monthFirst = a <= 12 && b <= 31;
    if (!(dayFirst || monthFirst) || y < 1900 || y > 2200 || a < 1 || b < 1) return null;
    return { k: "date", format: "numeric", numeric: { a, b, sep: m[2]!, yearDigits: m[4]!.length } };
  }
  m = MONTH_D_Y.exec(s);
  if (m) {
    const mon = MONTHS[m[1]!.toLowerCase()];
    if (mon && validYmd(+m[3]!, mon, +m[2]!)) return { k: "date", format: `${monthLabel(m[1]!)} d yyyy` };
    return null;
  }
  m = D_MONTH_Y.exec(s);
  if (m) {
    const mon = MONTHS[m[2]!.toLowerCase()];
    const sep = s.includes("-") ? "-" : " ";
    const yy = m[3]!.length === 2 ? "yy" : "yyyy";
    if (mon && validYmd(yearFrom(m[3]!), mon, +m[1]!)) return { k: "date", format: `d${sep}${monthLabel(m[2]!)}${sep}${yy}` };
    return null;
  }
  m = Y_MONTH_D.exec(s);
  if (m) {
    const mon = MONTHS[m[2]!.toLowerCase()];
    const sep = s.includes("-") ? "-" : " ";
    if (mon && validYmd(+m[1]!, mon, +m[3]!)) return { k: "date", format: `yyyy${sep}${monthLabel(m[2]!)}${sep}dd` };
    return null;
  }
  return null;
}

export type FormatClass =
  | { kind: "general" | "number" | "percent" | "text" | "date" | "datetime" }
  | { kind: "currency"; unit: string };

/** Classifies an Excel number format string such as "$#,##0.00", "0%", or "dd-mmm-yy". */
export function classifyFormat(fmt: string | undefined): FormatClass {
  if (!fmt || fmt === "General") return { kind: "general" };
  if (fmt === "@") return { kind: "text" };
  const bracketCurrency = /\[\$([^\]-]+)(?:-[^\]]*)?\]/.exec(fmt);
  // Drop quoted literals, escaped characters, and [color]/[locale] blocks before looking at tokens.
  const bare = fmt.replace(/"[^"]*"/g, "").replace(/\\./g, "").replace(/\[[^\]]*\]/g, "");
  if (bracketCurrency) {
    const unit = SYMBOL_UNITS[bracketCurrency[1]!.trim().toLowerCase()];
    if (unit) return { kind: "currency", unit };
  }
  const symbol = /(US\$|\$|₹|€|£|¥)/.exec(bare);
  if (symbol) return { kind: "currency", unit: SYMBOL_UNITS[symbol[1]!.toLowerCase()]! };
  if (bare.includes("%")) return { kind: "percent" };
  const hasDate = /[dy]/i.test(bare) || /m{3,}/i.test(bare);
  const hasTime = /[hs]/i.test(bare) || /AM\/PM/i.test(bare);
  if (hasDate && hasTime) return { kind: "datetime" };
  if (hasDate) return { kind: "date" };
  if (hasTime) return { kind: "datetime" };
  if (/[0#?]/.test(bare)) return { kind: "number" };
  return { kind: "general" };
}

export function isBlank(v: CellValue | undefined): boolean {
  return v === null || v === undefined || (typeof v === "string" && v.trim() === "");
}

export function parseCell(value: CellValue | undefined, fmt?: string): Parsed {
  if (value === null || value === undefined) return { k: "null" };
  if (typeof value === "boolean") return { k: "boolean" };
  if (typeof value === "number") {
    const cls = classifyFormat(fmt);
    switch (cls.kind) {
      case "date":
        return { k: "date", format: fmt ?? "date" };
      case "datetime":
        return { k: "datetime", format: fmt ?? "datetime" };
      case "percent":
        return { k: "percent", v: value };
      case "currency":
        return { k: "currency", v: value, unit: cls.unit };
      default:
        return { k: "number", v: value, integer: Number.isInteger(value) };
    }
  }

  const s = value.trim();
  if (NULL_TOKENS.has(s.toLowerCase())) return { k: "null" };
  if (ERROR_RE.test(s)) return { k: "error", code: s.toUpperCase() };
  if (/^(true|false)$/i.test(s)) return { k: "boolean" };

  let m = PERCENT_RE.exec(s);
  if (m) return { k: "percent", v: toNumber(m[1]!) / 100 };

  m = CURRENCY_PREFIX_RE.exec(s);
  if (m) {
    const sign = m[1] === "-" || m[3]!.startsWith("-") ? -1 : 1;
    return { k: "currency", v: sign * Math.abs(toNumber(m[3]!)), unit: SYMBOL_UNITS[m[2]!.toLowerCase()]! };
  }
  m = CURRENCY_SUFFIX_RE.exec(s);
  if (m) return { k: "currency", v: toNumber(m[1]!), unit: SYMBOL_UNITS[m[2]!.toLowerCase()]! };

  // Leading zeros ("007", "01234") are identifiers or postcodes, not numbers.
  if (/^[-+]?0\d/.test(s)) return parseDate(s) ?? { k: "string", v: s };
  if (NUMBER_RE.test(s) || SCI_RE.test(s)) {
    const v = toNumber(s);
    return { k: "number", v, integer: Number.isInteger(v) };
  }
  m = ACCOUNTING_RE.exec(s);
  if (m) {
    const v = -toNumber(m[1]!);
    return { k: "number", v, integer: Number.isInteger(v) };
  }

  return parseDate(s) ?? { k: "string", v: s };
}

/** The value as a user would read it; used for distinct counts, exemplars and joins. */
export function displayText(value: CellValue): string {
  if (value === null) return "";
  if (typeof value === "number") return Number.isInteger(value) ? String(value) : String(+value.toPrecision(12));
  return String(value).trim();
}
