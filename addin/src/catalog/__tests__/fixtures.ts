import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import type { CellValue, SheetSnapshot, WorkbookSnapshot } from "../types";

/** Minimal RFC 4180 parser: quoted fields, doubled quotes, CRLF or LF. */
export function parseCsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = "";
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i]!;
    if (quoted) {
      if (ch === '"' && text[i + 1] === '"') {
        field += '"';
        i++;
      } else if (ch === '"') quoted = false;
      else field += ch;
    } else if (ch === '"') quoted = true;
    else if (ch === ",") {
      row.push(field);
      field = "";
    } else if (ch === "\n" || ch === "\r") {
      if (ch === "\r" && text[i + 1] === "\n") i++;
      row.push(field);
      rows.push(row);
      row = [];
      field = "";
    } else field += ch;
  }
  if (field !== "" || row.length > 0) {
    row.push(field);
    rows.push(row);
  }
  return rows;
}

export const CORPUS_DIR = resolve(process.cwd(), "../corpus");

export function corpusRows(file: string): string[][] {
  return parseCsv(readFileSync(resolve(CORPUS_DIR, file), "utf8"));
}

/** A sheet as Excel's used range would hand it over: text cells, blanks as null. */
export function sheetFromRows(name: string, rows: CellValue[][], originRow = 1, originCol = 0): SheetSnapshot {
  return {
    name,
    originRow,
    originCol,
    values: rows.map((r) => r.map((v) => (v === "" ? null : v))),
  };
}

export const CORPUS_SHEETS: Record<string, string> = {
  Orders: "sales-orders.csv",
  Regions: "regions-lookup.csv",
  Budget: "team-budget.csv",
  Tasks: "project-tracker.csv",
  Inventory: "inventory-log.csv",
  "Q1 Report": "ugly-mixed.csv",
};

export function corpusWorkbook(): WorkbookSnapshot {
  return {
    sheets: Object.entries(CORPUS_SHEETS).map(([name, file]) => sheetFromRows(name, corpusRows(file))),
    tables: [],
    names: [],
    sources: [],
    validations: [],
  };
}

export function emptyWorkbook(sheets: SheetSnapshot[]): WorkbookSnapshot {
  return { sheets, tables: [], names: [], sources: [], validations: [] };
}
