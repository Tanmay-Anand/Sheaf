import type { CatalogColumn, CatalogEntity } from "@sheaf/contract/catalog";
import { parseAddress } from "../catalog/a1";
import type { CellValue, SheetSnapshot } from "../catalog/types";
import { dateFromText, parseCell } from "../catalog/values";
import type { Table } from "./table";
import { type DateSystem, daysFromSerial, daysFromYmd, isBlank, numberText, VALUE, type Value, xdate, xerror } from "./value";

/**
 * Catalogued entity + the sheet's cells → a typed table. Each column is read as its catalogued kind:
 *
 * - numeric columns take numbers; a number stored as text ("1,299", "₹4,398", "12%") is read as the
 *   number it shows, and counted, because Excel's own SUM would skip it;
 * - date columns take real Excel dates (converted with the workbook's date system) and text dates
 *   in any format the catalog recognises, using the column's day-first/month-first evidence;
 * - text columns take text; a number in one is its displayed digits, so "1001" = 1001;
 * - an error value ("#N/A", "#DIV/0!"…) stays an error; a blank or all-space cell is null;
 * - anything a column can't hold (text in a number column) becomes #VALUE!, so the error policy
 *   covers it instead of the value vanishing.
 */

export interface LoadStats {
  /** Numbers stored as text that were read as numbers. */
  textNumbers: number;
  /** Cells that didn't fit their column's type and became #VALUE!. */
  unreadable: number;
}

export interface LoadedTable {
  table: Table;
  stats: LoadStats;
}

const ERROR_RE = /^#(N\/A|DIV\/0!|VALUE!|REF!|NAME\?|NUM!|NULL!|SPILL!|CALC!|GETTING_DATA|FIELD!|BUSY!|BLOCKED!|CONNECT!|UNKNOWN!)$/i;

/** true: day-first; false: month-first; undefined: no evidence either way. */
function dayFirst(c: CatalogColumn): boolean | undefined {
  const formats = c.dateFormats ?? [];
  const dmy = formats.some((f) => /^dd[/.-]mm/.test(f) && !f.includes(" / "));
  const mdy = formats.some((f) => /^mm[/.-]dd/.test(f) && !f.includes(" / "));
  return dmy && !mdy ? true : mdy && !dmy ? false : undefined;
}

function convert(cell: CellValue, c: CatalogColumn, system: DateSystem, dmy: boolean | undefined, stats: LoadStats): Value {
  if (cell === null || cell === undefined) return null;
  if (typeof cell === "string") {
    if (isBlank(cell)) return null;
    if (ERROR_RE.test(cell.trim())) return xerror(cell.trim().toUpperCase());
  }
  const unreadable = () => {
    stats.unreadable++;
    return VALUE;
  };
  switch (c.kind) {
    case "number":
    case "currency":
    case "percent": {
      const scale = c.kind === "percent" && c.percentScale === 100 ? 100 : 1;
      if (typeof cell === "number") return cell / scale;
      if (typeof cell === "boolean") return unreadable();
      const p = parseCell(cell);
      if (p.k === "null") return null;
      if (p.k === "number" || p.k === "currency") {
        stats.textNumbers++;
        return p.v / scale;
      }
      if (p.k === "percent") {
        stats.textNumbers++;
        return p.v; // "12%" is 0.12 whatever the column's scale
      }
      return unreadable();
    }
    case "date":
    case "datetime": {
      if (typeof cell === "number") return xdate(daysFromSerial(cell, system));
      if (typeof cell === "boolean") return unreadable();
      const d = dateFromText(cell, dmy);
      if (d) return xdate(daysFromYmd(d.y, d.m, d.d) + d.time);
      return parseCell(cell).k === "null" ? null : unreadable();
    }
    case "boolean": {
      if (typeof cell === "boolean") return cell;
      const s = String(cell).trim().toUpperCase();
      return s === "TRUE" ? true : s === "FALSE" ? false : unreadable();
    }
    default:
      if (typeof cell === "number") return numberText(cell);
      if (typeof cell === "boolean") return cell ? "TRUE" : "FALSE";
      return cell;
  }
}

/** Reads an entity's data rows (header and skipped rows excluded) from a sheet snapshot that contains them. */
export function loadEntity(entity: CatalogEntity, sheet: SheetSnapshot, system: DateSystem): LoadedTable {
  const box = parseAddress(entity.address)?.box;
  if (!box) throw new Error(`${entity.name} has an unreadable address, ${entity.address}.`);
  const skipped = new Set(entity.skippedRows.map((s) => s.row));
  const stats: LoadStats = { textNumbers: 0, unreadable: 0 };
  const dmy = entity.columns.map(dayFirst);
  const rows: Value[][] = [];
  for (let r = entity.firstDataRow; r <= entity.lastDataRow; r++) {
    if (skipped.has(r)) continue;
    const src = sheet.values[r - sheet.originRow];
    rows.push(
      entity.columns.map((c, i) => {
        const cell = src?.[box.left + c.index - sheet.originCol] ?? null;
        return convert(cell, c, system, dmy[i], stats);
      }),
    );
  }
  return { table: { columns: entity.columns.map((c) => c.name), rows }, stats };
}
