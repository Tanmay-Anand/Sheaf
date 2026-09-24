import { cellRef, colToLetters, MAX_COL, MAX_ROW, parseRef, quoteSheet } from "../catalog/a1";
import { planRowChunks, type RowChunk } from "../catalog/chunks";
import type { Sink } from "./ir";
import type { Table } from "./table";
import { type DateSystem, isDate, isError, serialFromDays, type Value } from "./value";

/**
 * What a commit writes, decided before Excel is touched: target, extent, cells and number formats.
 * Pure, so the rules below are unit-tested:
 *
 * - a newSheet sink writes only to a sheet the commit creates, so it can't touch existing data;
 * - an anchor sink writes exactly rows × columns from the cell the user chose, never beyond;
 * - text is written into text-formatted ("@") cells, so Excel can't turn "=SUM(…)" into a formula,
 *   "1-2" into a date or "007" into 7;
 * - dates are written as serials in the workbook's date system with a date format;
 * - writes are chunked by rows so every request stays well under Excel on the web's 5 MB limit.
 */

export type CellOut = string | number | boolean;

/** An output column's type as the type checker rendered it ("currency:INR", "date"…). */
export interface OutputType {
  name: string;
  type: string;
}

export type WriteTarget =
  | { kind: "newSheet"; sheetName: string }
  | { kind: "existing"; sheetId: string; sheetName: string };

export interface WritePlan {
  target: WriteTarget;
  /** 1-based row and 0-based column of the header cell. */
  top: number;
  left: number;
  rows: number;
  cols: number;
  /** The whole written range, e.g. "'Sales 2'!A1:D17". */
  address: string;
  /** Header row first. */
  values: CellOut[][];
  numberFormats: string[][];
  chunks: RowChunk[];
}

/** Cells per write request (values and formats), well under the 5 MB limit. */
export const MAX_CELLS_PER_WRITE = 20_000;

const TEXT = "@";
const SYMBOLS: Record<string, string> = { INR: "₹", USD: "$", EUR: "€", GBP: "£", JPY: "¥" };

export function columnFormat(type: string): string {
  if (type === "date") return "yyyy-mm-dd";
  if (type === "datetime") return "yyyy-mm-dd hh:mm";
  if (type === "percent") return "0.0%";
  if (type.startsWith("currency")) {
    const symbol = SYMBOLS[type.slice("currency:".length)];
    return symbol ? `"${symbol}"#,##0.00` : "#,##0.00";
  }
  if (type === "string" || type === "categorical") return TEXT;
  return "General";
}

function cell(v: Value, columnFmt: string, system: DateSystem): { value: CellOut; format: string } {
  if (v === null) return { value: "", format: columnFmt };
  if (typeof v === "string") return { value: v, format: TEXT };
  if (typeof v === "number" || typeof v === "boolean") return { value: v, format: columnFmt === TEXT ? "General" : columnFmt };
  if (isDate(v)) {
    const fmt = columnFmt.startsWith("yyyy") ? columnFmt : v.v % 1 === 0 ? "yyyy-mm-dd" : "yyyy-mm-dd hh:mm";
    return { value: serialFromDays(v.v, system), format: fmt };
  }
  // An error value: Excel reads the code back as the error, in a cell that isn't text-formatted.
  return { value: isError(v) ? v.code : "", format: "General" };
}

export interface WriteContext {
  dateSystem: DateSystem;
  /** For an anchor sink: the cell the user chose, from the commit request. */
  anchor?: { sheetId: string; sheetName: string; address: string };
}

export class WriteRefused extends Error {}

export function planWrite(result: Table, types: OutputType[], dynamicType: string | undefined, sink: Sink, ctx: WriteContext): WritePlan {
  let target: WriteTarget;
  let top: number;
  let left: number;
  switch (sink.mode) {
    case "newSheet": {
      const at = parseRef(sink.anchor);
      if (!at || at.top !== at.bottom || at.left !== at.right) throw new WriteRefused(`'${sink.anchor}' is not a single cell.`);
      target = { kind: "newSheet", sheetName: sink.name };
      [top, left] = [at.top, at.left];
      break;
    }
    case "anchor": {
      if (!ctx.anchor) throw new WriteRefused("Choose the cell where the result should start.");
      const at = parseRef(ctx.anchor.address.replace(/^.*!/, ""));
      if (!at || at.top !== at.bottom || at.left !== at.right) throw new WriteRefused(`'${ctx.anchor.address}' is not a single cell.`);
      target = { kind: "existing", sheetId: ctx.anchor.sheetId, sheetName: ctx.anchor.sheetName };
      [top, left] = [at.top, at.left];
      break;
    }
    case "template":
      throw new WriteRefused("Template fill writes a new workbook from the template; it arrives with template support (M7).");
  }

  const cols = result.columns.length;
  const rows = result.rows.length + 1;
  if (cols === 0) throw new WriteRefused("The result has no columns.");
  if (top + rows - 1 > MAX_ROW || left + cols - 1 > MAX_COL) {
    throw new WriteRefused(`The result (${rows.toLocaleString("en")} × ${cols} with its header) doesn't fit on the sheet from ${cellRef(top, left)}.`);
  }

  const byName = new Map(types.map((t) => [t.name.toLowerCase(), t.type]));
  const formats = result.columns.map((c) => columnFormat(byName.get(c.toLowerCase()) ?? dynamicType ?? "number"));
  const values: CellOut[][] = [result.columns.slice()];
  const numberFormats: string[][] = [result.columns.map(() => TEXT)];
  for (const r of result.rows) {
    const out: CellOut[] = [];
    const fmt: string[] = [];
    for (let i = 0; i < cols; i++) {
      const c = cell(r[i] ?? null, formats[i]!, ctx.dateSystem);
      out.push(c.value);
      fmt.push(c.format);
    }
    values.push(out);
    numberFormats.push(fmt);
  }
  const sheet = target.sheetName;
  const address = `${quoteSheet(sheet)}!${cellRef(top, left)}:${colToLetters(left + cols - 1)}${top + rows - 1}`;
  return { target, top, left, rows, cols, address, values, numberFormats, chunks: planRowChunks(rows, cols, MAX_CELLS_PER_WRITE) };
}

/** Excel's sheet-name rules (ir-spec §5.4). */
export function validSheetName(name: string): boolean {
  return (
    name.trim().length > 0 &&
    name.length <= 31 &&
    !/[[\]:*?/\\]/.test(name) &&
    !name.startsWith("'") &&
    !name.endsWith("'") &&
    name.toLowerCase() !== "history"
  );
}
