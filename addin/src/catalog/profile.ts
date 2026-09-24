import type { CatalogColumn, ScalarKind } from "@sheaf/contract/catalog";
import type { CellValue } from "./types";
import { classifyFormat, displayText, parseCell } from "./values";

export interface ColumnInput {
  /** Stable column id, assigned by the builder. */
  id: string;
  name: string;
  letter: string;
  index: number;
  /** Data cells only (header and skipped rows already removed). */
  cells: CellValue[];
  formats?: (string | undefined)[];
  formulaFlags?: boolean[];
}

export interface ProfileOptions {
  /** Include up to five sample values, and quote values in warnings. */
  exemplars: boolean;
}

export interface ProfiledColumn {
  column: CatalogColumn;
  /** Lower-cased distinct display values; kept in memory for join inference, never serialised. */
  distinct: Set<string>;
  integerOnly: boolean;
}

export const MAX_EXEMPLARS = 5;
export const MAX_EXEMPLAR_LENGTH = 64;
const CATEGORICAL_MAX_DISTINCT = 50;
/** Label for dates Excel stores as real date values, whatever their display format. */
export const EXCEL_DATE = "Excel date";
const DISTINCT_CAP = 200_000;

const PERCENT_NAME = /(^|[\s_\-(])(pct|percent|percentage|rate)([\s_\-)]|$)|%/i;
const CURRENCY_NAME = /(amount|price|cost|revenue|budget|spend|salary|fee|sales|payment)/i;

function round(n: number, digits = 4): number {
  const f = 10 ** digits;
  return Math.round(n * f) / f;
}

function mostCommon(counts: Map<string, number>): string | undefined {
  let best: string | undefined;
  let bestN = 0;
  for (const [k, n] of counts) if (n > bestN) [best, bestN] = [k, n];
  return best;
}

function plural(n: number, one: string, many = `${one}s`): string {
  return `${n} ${n === 1 ? one : many}`;
}

/** "1 value isn't" / "3 values aren't". */
function agree(n: number, one: string, many: string): string {
  return n === 1 ? one : many;
}

export function profileColumn(input: ColumnInput, opts: ProfileOptions): ProfiledColumn {
  const n = input.cells.length;
  const counts = { null: 0, error: 0, number: 0, currency: 0, percent: 0, date: 0, datetime: 0, boolean: 0, string: 0 };
  const units = new Map<string, number>();
  const dateFormats = new Map<string, number>();
  const numberFormats = new Map<string, number>();
  const errorCodes = new Set<string>();
  const distinct = new Set<string>();
  const exemplars: string[] = [];
  const exemplarKeys = new Set<string>();
  let firstNonNumber: string | undefined;
  let integerOnly = true;
  let min = Infinity;
  let max = -Infinity;
  let formulaCount = 0;
  let realNumbers = 0;
  let textNumbers = 0;
  let textFormattedNumbers = 0;
  let dayFirstEvidence = 0;
  let excelDates = 0;
  let textDates = 0;
  let monthFirstEvidence = 0;
  let ambiguousNumeric = 0;
  let numericSep = "/";
  let numericYearDigits = 4;

  // Real Excel dates (numbers with a date format) are one kind, whatever their display format;
  // dates typed as text are the problem worth reporting, format by format.
  const countDate = (cell: CellValue, format: string) => {
    if (typeof cell === "number") {
      excelDates++;
      dateFormats.set(EXCEL_DATE, (dateFormats.get(EXCEL_DATE) ?? 0) + 1);
    } else {
      textDates++;
      dateFormats.set(format, (dateFormats.get(format) ?? 0) + 1);
    }
  };

  for (let i = 0; i < n; i++) {
    const cell = input.cells[i] ?? null;
    const fmt = input.formats?.[i];
    if (input.formulaFlags?.[i]) formulaCount++;
    if (fmt && fmt !== "General") numberFormats.set(fmt, (numberFormats.get(fmt) ?? 0) + 1);

    const p = parseCell(cell, fmt);
    counts[p.k]++;
    if (p.k === "null") continue;
    if (p.k === "error") {
      errorCodes.add(p.code);
      continue;
    }

    const text = displayText(cell);
    if (distinct.size < DISTINCT_CAP) distinct.add(text.toLowerCase());
    if (opts.exemplars && exemplars.length < MAX_EXEMPLARS && !exemplarKeys.has(text.toLowerCase())) {
      exemplarKeys.add(text.toLowerCase());
      exemplars.push(text.length > MAX_EXEMPLAR_LENGTH ? `${text.slice(0, MAX_EXEMPLAR_LENGTH - 1)}…` : text);
    }

    switch (p.k) {
      case "number":
      case "currency":
      case "percent":
        if (typeof cell === "number") realNumbers++;
        else {
          textNumbers++;
          if (fmt === "@") textFormattedNumbers++;
        }
        if (p.k === "number" && !p.integer) integerOnly = false;
        if (p.k !== "number") integerOnly = integerOnly && Number.isInteger(p.v);
        if (p.k === "currency") units.set(p.unit, (units.get(p.unit) ?? 0) + 1);
        min = Math.min(min, p.v);
        max = Math.max(max, p.v);
        break;
      case "date":
        if (p.numeric) {
          const { a, b } = p.numeric;
          numericSep = p.numeric.sep;
          numericYearDigits = p.numeric.yearDigits;
          if (a > 12 && b <= 12) dayFirstEvidence++;
          else if (b > 12 && a <= 12) monthFirstEvidence++;
          else ambiguousNumeric++;
        }
        countDate(cell, p.format);
        break;
      case "datetime":
        countDate(cell, p.format);
        break;
      case "string":
        firstNonNumber ??= p.v;
        break;
      default:
        break;
    }
  }

  const warnings: string[] = [];
  const typed = n - counts.null - counts.error;
  const numeric = counts.number + counts.currency + counts.percent;
  const dates = counts.date + counts.datetime;
  const quote = (s: string | undefined) => (opts.exemplars && s !== undefined ? `, e.g. "${s}"` : "");

  let kind: ScalarKind;
  let unit: string | undefined;
  let percentScale: number | undefined;
  let resolvedDateFormats: string[] | undefined;

  if (typed === 0) {
    kind = "empty";
  } else if (dates / typed >= 0.8) {
    kind = counts.datetime > 0 && counts.datetime >= counts.date ? "datetime" : "date";
    const yyyy = numericYearDigits === 2 ? "yy" : "yyyy";
    let numericLabel: string;
    if (monthFirstEvidence > 0 && dayFirstEvidence > 0) {
      numericLabel = `dd${numericSep}mm${numericSep}${yyyy} / mm${numericSep}dd${numericSep}${yyyy}`;
      warnings.push("Numeric dates are inconsistent: some are day-first and some month-first.");
    } else if (monthFirstEvidence > 0) {
      numericLabel = `mm${numericSep}dd${numericSep}${yyyy}`;
    } else if (dayFirstEvidence > 0) {
      numericLabel = `dd${numericSep}mm${numericSep}${yyyy}`;
    } else {
      numericLabel = `dd${numericSep}mm${numericSep}${yyyy} or mm${numericSep}dd${numericSep}${yyyy}`;
      if (ambiguousNumeric > 0) {
        warnings.push(`${plural(ambiguousNumeric, "numeric date")} could be day-first or month-first; confirm which.`);
      }
    }
    const textFormats = [...dateFormats.keys()]
      .filter((f) => f !== EXCEL_DATE)
      .map((f) => (f === "numeric" ? numericLabel : f))
      .sort();
    resolvedDateFormats = [...(excelDates > 0 ? [EXCEL_DATE] : []), ...textFormats];
    if (textFormats.length > 1) {
      warnings.push(`${textFormats.length} text date formats in one column: ${textFormats.join(", ")}.`);
    }
    if (excelDates > 0 && textDates > 0) {
      warnings.push(
        `${textDates} of ${excelDates + textDates} dates are stored as text; the rest are real Excel dates. Text dates can't be sorted or calculated with.`,
      );
    }
    if (typed - dates > 0) warnings.push(`${plural(typed - dates, "value")} ${agree(typed - dates, "isn't a date", "aren't dates")}${quote(firstNonNumber)}.`);
  } else if (counts.boolean / typed >= 0.9) {
    kind = "boolean";
  } else if (numeric / typed >= 0.8) {
    const unitMajority = mostCommon(units);
    const formatUnit = (() => {
      const f = mostCommon(numberFormats);
      const cls = classifyFormat(f);
      return cls.kind === "currency" ? cls.unit : undefined;
    })();
    if (counts.percent / numeric >= 0.5) {
      kind = "percent";
      percentScale = 1;
    } else if (PERCENT_NAME.test(input.name) && min >= 0 && max <= 100) {
      kind = "percent";
      percentScale = max <= 1 ? 1 : 100;
    } else if (counts.currency / numeric >= 0.5) {
      kind = "currency";
      unit = unitMajority ?? formatUnit;
    } else if (CURRENCY_NAME.test(input.name)) {
      kind = "currency";
      unit = formatUnit;
      if (!unit) warnings.push("Looks like money, but the currency isn't stated; confirm the unit.");
    } else {
      kind = "number";
    }
    if (counts.currency > 0 && counts.currency / numeric < 0.5) {
      const symbols = [...units.keys()].join(", ");
      warnings.push(
        `${plural(counts.currency, "value")} ${agree(counts.currency, "carries", "carry")} a currency marker (${symbols}) and the rest don't; the marker was not used as the unit.`,
      );
    }
    if (typed - numeric > 0) warnings.push(`${plural(typed - numeric, "value")} ${agree(typed - numeric, "isn't a number", "aren't numbers")}${quote(firstNonNumber)}.`);
  } else {
    const distinctTyped = distinct.size;
    kind = distinctTyped <= CATEGORICAL_MAX_DISTINCT && distinctTyped <= Math.max(2, typed * 0.6) ? "categorical" : "string";
    if (dates > 0 && dates / typed >= 0.2) warnings.push(`${plural(dates, "value")} ${agree(dates, "looks like a date", "look like dates")} among text.`);
  }

  if (errorCodes.size > 0) {
    warnings.push(`${plural(counts.error, "cell")} ${agree(counts.error, "shows an error", "show errors")} (${[...errorCodes].sort().join(", ")}).`);
  }

  // Excel's SUM, AVERAGE and friends skip numbers stored as text, so totals silently come out
  // short. Flag a numeric column that mixes real numbers with text ones, or holds text-formatted
  // ("@") numbers; a column that is text throughout is how a snapshot of typed text looks, not a defect.
  const numericKind = kind === "number" || kind === "currency" || kind === "percent";
  const numbersStoredAsText = numericKind && ((realNumbers > 0 && textNumbers > 0) || textFormattedNumbers > 0);
  if (numbersStoredAsText) {
    const count = textFormattedNumbers > 0 && realNumbers === 0 ? textFormattedNumbers : textNumbers;
    warnings.push(`${plural(count, "number")} ${agree(count, "is", "are")} stored as text; Excel's SUM and AVERAGE skip them.`);
  }

  const keyKinds: ScalarKind[] = ["string", "categorical", "number"];
  const keyCandidate =
    typed >= 2 &&
    counts.null === 0 &&
    counts.error === 0 &&
    distinct.size === typed &&
    keyKinds.includes(kind) &&
    (kind !== "number" || integerOnly);

  const dominantFormat = mostCommon(numberFormats);

  const column: CatalogColumn = {
    id: input.id,
    name: input.name,
    letter: input.letter,
    index: input.index,
    kind,
    ...(unit !== undefined ? { unit } : {}),
    ...(percentScale !== undefined ? { percentScale } : {}),
    nullable: counts.null + counts.error > 0,
    nullRate: n === 0 ? 0 : round((counts.null + counts.error) / n),
    distinctCount: distinct.size,
    keyCandidate,
    formula: n > 0 && formulaCount / n >= 0.5,
    ...(dominantFormat !== undefined ? { numberFormat: dominantFormat } : {}),
    ...(resolvedDateFormats !== undefined ? { dateFormats: resolvedDateFormats } : {}),
    ...(opts.exemplars ? { exemplars } : {}),
    mayContainErrors: counts.error > 0,
    numbersStoredAsText,
    dependents: [],
    warnings,
  };

  return { column, distinct, integerOnly: numeric > 0 && integerOnly };
}
