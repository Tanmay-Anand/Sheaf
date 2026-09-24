import type { DateSystem } from "@sheaf/contract/catalog";
import type { DateProbe } from "./types";

/**
 * The workbook's date system, inferred from what Excel displays. Office.js can't report it
 * (Workbook.use1904DateSystem is preview-only), and the two systems put the same serial number
 * four years and one day apart, so a displayed date tells them apart: its year, or failing that,
 * its day of the month.
 */

interface Ymd {
  y: number;
  m: number;
  d: number;
}

const DAY_MS = 86_400_000;

function fromEpoch(epochUtc: number, days: number): Ymd {
  const t = new Date(epochUtc + days * DAY_MS);
  return { y: t.getUTCFullYear(), m: t.getUTCMonth() + 1, d: t.getUTCDate() };
}

/** Serial → calendar date in the 1900 system, which keeps Lotus's fictitious 29 Feb 1900 (serial 60). */
export function dateFrom1900(serial: number): Ymd {
  const whole = Math.floor(serial);
  if (whole === 60) return { y: 1900, m: 2, d: 29 };
  // Serial 1 is 1 Jan 1900; past the fake leap day the offset is one day less.
  return whole < 60 ? fromEpoch(Date.UTC(1899, 11, 31), whole) : fromEpoch(Date.UTC(1899, 11, 30), whole);
}

/** Serial → calendar date in the 1904 system: serial 0 is 1 Jan 1904. */
export function dateFrom1904(serial: number): Ymd {
  return fromEpoch(Date.UTC(1904, 0, 1), Math.floor(serial));
}

function fits(date: Ymd, text: string, format: string): boolean {
  const tokens = text.match(/\d+/g) ?? [];
  const fourDigit = tokens.filter((t) => t.length === 4);
  const f = format.toLowerCase().replace(/"[^"]*"/g, "").replace(/\[[^\]]*\]/g, "");
  if (fourDigit.length > 0) {
    if (!fourDigit.includes(String(date.y))) return false;
  } else if (/yy/.test(f)) {
    if (!tokens.some((t) => t.length <= 2 && Number(t) === date.y % 100)) return false;
  }
  // "d" or "dd" is the day of the month; "ddd"/"dddd" is a weekday name and says nothing here.
  if (/(^|[^d])d{1,2}([^d]|$)/.test(f) && !tokens.some((t) => t.length <= 2 && Number(t) === date.d)) return false;
  return true;
}

export function inferDateSystem(probes: DateProbe[]): DateSystem {
  let verdict: DateSystem | undefined;
  for (const p of probes) {
    if (!Number.isFinite(p.serial) || p.serial < 1 || !p.text.trim()) continue;
    const a = fits(dateFrom1900(p.serial), p.text, p.numberFormat);
    const b = fits(dateFrom1904(p.serial), p.text, p.numberFormat);
    if (a === b) continue; // this probe can't tell them apart
    const v: DateSystem = a ? "1900" : "1904";
    if (verdict !== undefined && verdict !== v) return "unknown";
    verdict = v;
  }
  return verdict ?? "unknown";
}
