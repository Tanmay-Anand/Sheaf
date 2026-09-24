import type { SkippedRow } from "@sheaf/contract/catalog";
import { type Box, colToLetters, contains } from "./a1";
import type { CellValue, SheetSnapshot } from "./types";
import { isBlank, parseCell } from "./values";

/**
 * Finds the tables inside a sheet's used range without any help from Excel: blank rows and
 * columns separate blocks; titles, notes and stray cells are ignored; a table interrupted by a
 * blank row or followed by a grand-total row stays one table.
 */

export interface DetectedRegion {
  /** Full bounds including header rows. */
  box: Box;
  /** 1-based sheet row of the (last) header row; top - 1 when no header was found. */
  headerRow: number;
  /** 0 when no header row was found and columns are named by letter. */
  headerRows: number;
  headers: string[];
  dataRows: number[];
  skipped: SkippedRow[];
  warnings: string[];
}

export interface DetectOptions {
  /** Cells that belong to Excel Tables; they are handled separately and treated as blank here. */
  masked?: Box[];
  /** User corrections: an absolute header row per region, keyed by any row inside the region. */
  headerOverrides?: number[];
  /** Keep section labels and totals-row text (they are cell values). */
  keepLabels: boolean;
}

const TOTAL_RE = /\b(grand\s*total|sub-?\s*total|total)s?\b/i;
const MAX_MERGE_GAP = 2;

interface Grid {
  get(row: number, col: number): CellValue;
  filled(row: number, col: number): boolean;
  top: number;
  bottom: number;
  left: number;
  right: number;
}

function makeGrid(sheet: SheetSnapshot, masked: Box[]): Grid {
  const width = sheet.values.reduce((w, r) => Math.max(w, r.length), 0);
  const isMasked = (row: number, col: number) =>
    masked.some((b) => row >= b.top && row <= b.bottom && col >= b.left && col <= b.right);
  const get = (row: number, col: number): CellValue =>
    sheet.values[row - sheet.originRow]?.[col - sheet.originCol] ?? null;
  return {
    get,
    filled: (row, col) => !isMasked(row, col) && !isBlank(get(row, col)),
    top: sheet.originRow,
    bottom: sheet.originRow + sheet.values.length - 1,
    left: sheet.originCol,
    right: sheet.originCol + width - 1,
  };
}

function rowFilledCount(g: Grid, row: number, left: number, right: number): number {
  let n = 0;
  for (let c = left; c <= right; c++) if (g.filled(row, c)) n++;
  return n;
}

function text(g: Grid, row: number, col: number): string {
  const v = g.get(row, col);
  return v === null ? "" : String(v).trim();
}

function firstText(g: Grid, row: number, left: number, right: number): string {
  for (let c = left; c <= right; c++) if (g.filled(row, c)) return text(g, row, c);
  return "";
}

/** Splits the used range into blocks separated by fully blank rows, then by fully blank columns. */
function findBlocks(g: Grid): Box[] {
  const blocks: Box[] = [];
  let r = g.top;
  while (r <= g.bottom) {
    if (rowFilledCount(g, r, g.left, g.right) === 0) {
      r++;
      continue;
    }
    const bandTop = r;
    while (r <= g.bottom && rowFilledCount(g, r, g.left, g.right) > 0) r++;
    const bandBottom = r - 1;

    let c = g.left;
    while (c <= g.right) {
      const colHas = (col: number) => {
        for (let row = bandTop; row <= bandBottom; row++) if (g.filled(row, col)) return true;
        return false;
      };
      if (!colHas(c)) {
        c++;
        continue;
      }
      const left = c;
      while (c <= g.right && colHas(c)) c++;
      const right = c - 1;
      let top = bandTop;
      let bottom = bandBottom;
      while (top < bottom && rowFilledCount(g, top, left, right) === 0) top++;
      while (bottom > top && rowFilledCount(g, bottom, left, right) === 0) bottom--;
      blocks.push({ top, left, bottom, right });
    }
  }
  return blocks.sort((a, b) => a.top - b.top || a.left - b.left);
}

function filledIn(g: Grid, b: Box): number {
  let n = 0;
  for (let r = b.top; r <= b.bottom; r++) n += rowFilledCount(g, r, b.left, b.right);
  return n;
}

function hasTotalKeyword(g: Grid, row: number, left: number, right: number): string | undefined {
  for (let c = left; c <= right; c++) {
    const v = g.get(row, c);
    if (typeof v === "string" && TOTAL_RE.test(v)) return v.trim();
  }
  return undefined;
}

/**
 * Every filled cell is text that doesn't read as a number or date, and most cells are filled.
 * Repeated names are allowed (real headers repeat); they are renamed later.
 */
function isHeaderLike(g: Grid, row: number, left: number, right: number): boolean {
  const width = right - left + 1;
  let n = 0;
  for (let c = left; c <= right; c++) {
    if (!g.filled(row, c)) continue;
    const v = g.get(row, c);
    if (typeof v !== "string" || parseCell(v).k !== "string") return false;
    n++;
  }
  return n >= (width === 1 ? 1 : Math.max(2, Math.ceil(width * 0.5)));
}

/** A sparse row of text above the header: group labels from merged cells ("Q1" over Jan/Feb/Mar). */
function isGroupLabelRow(g: Grid, row: number, left: number, right: number): boolean {
  const width = right - left + 1;
  let n = 0;
  let onlyFirst = true;
  for (let c = left; c <= right; c++) {
    if (!g.filled(row, c)) continue;
    const v = g.get(row, c);
    if (typeof v !== "string" || parseCell(v).k !== "string") return false;
    if (c !== left) onlyFirst = false;
    n++;
  }
  // A single cell in the first column over a wide block is a title, not a group label.
  return n >= 1 && n < width && !(n === 1 && onlyFirst && width > 2);
}

function mergeBlocks(g: Grid, blocks: Box[]): Box[] {
  const isAnnotation = (b: Box) => b.bottom - b.top <= 1 && filledIn(g, b) <= 2;
  const isTotals = (b: Box) => b.bottom - b.top <= 1 && hasTotalKeyword(g, b.top, b.left, b.right) !== undefined;
  const startsNewTable = (b: Box) =>
    b.bottom > b.top && isHeaderLike(g, b.top, b.left, b.right) && !isHeaderLike(g, b.top + 1, b.left, b.right);

  const regions: Box[] = [];
  const used = new Set<number>();
  blocks.forEach((block, i) => {
    if (used.has(i) || isAnnotation(block) || isTotals(block)) return;
    const cur = { ...block };
    for (let j = i + 1; j < blocks.length; j++) {
      if (used.has(j)) continue;
      const next = blocks[j]!;
      if (next.top <= cur.bottom) continue;
      if (next.top - cur.bottom - 1 > MAX_MERGE_GAP) break;
      if (next.left < cur.left || next.right > cur.right) continue;
      // A short block is data only when it is dense relative to the table above it; a sparse
      // one ("Notes:" under a 9-column table) is an annotation.
      const width = cur.right - cur.left + 1;
      const dense = next.bottom - next.top >= 2 || filledIn(g, next) > width * 0.5;
      if (isTotals(next) || (dense && !startsNewTable(next))) {
        cur.bottom = next.bottom;
        used.add(j);
      }
    }
    used.add(i);
    regions.push(cur);
  });
  return regions.filter((r, i) => !regions.some((o, j) => j !== i && contains(o, r)));
}

function headerNames(g: Grid, box: Box, headerRow: number, groupRow: number | undefined, warnings: string[]): string[] {
  const names: string[] = [];
  let group = "";
  for (let c = box.left; c <= box.right; c++) {
    if (groupRow !== undefined) {
      const label = text(g, groupRow, c);
      if (label) group = label;
    }
    let name = text(g, headerRow, c).replace(/\s+/g, " ");
    if (!name) {
      name = `Column ${colToLetters(c)}`;
      warnings.push(`Column ${colToLetters(c)} has no header; named "${name}".`);
    }
    names.push(group ? `${group} ${name}` : name);
  }
  const seen = new Map<string, number>();
  return names.map((n) => {
    const key = n.toLowerCase();
    const count = (seen.get(key) ?? 0) + 1;
    seen.set(key, count);
    if (count === 1) return n;
    warnings.push(`Header "${n}" appears more than once; the repeat is named "${n} (${count})".`);
    return `${n} (${count})`;
  });
}

export function detectRegions(sheet: SheetSnapshot, opts: DetectOptions): DetectedRegion[] {
  const g = makeGrid(sheet, opts.masked ?? []);
  if (g.bottom < g.top || g.right < g.left) return [];
  const regions = mergeBlocks(g, findBlocks(g));

  return regions.map((box) => {
    const warnings: string[] = [];
    const skipped: SkippedRow[] = [];
    const width = box.right - box.left + 1;

    let headerRow: number | undefined = opts.headerOverrides?.find((r) => r >= box.top && r <= box.bottom);
    if (headerRow === undefined) {
      for (let r = box.top; r <= Math.min(box.top + 4, box.bottom - 1); r++) {
        if (isHeaderLike(g, r, box.left, box.right)) {
          headerRow = r;
          break;
        }
      }
    }

    let groupRow: number | undefined;
    let headerRows = 1;
    let headers: string[];
    if (headerRow === undefined) {
      headerRows = 0;
      headerRow = box.top - 1;
      headers = Array.from({ length: width }, (_, i) => `Column ${colToLetters(box.left + i)}`);
      warnings.push("No header row found; columns are named by letter. Set the header row if this is wrong.");
    } else {
      if (headerRow > box.top && isGroupLabelRow(g, headerRow - 1, box.left, box.right)) {
        groupRow = headerRow - 1;
        headerRows = 2;
      }
      headers = headerNames(g, box, headerRow, groupRow, warnings);
      const firstHeader = groupRow ?? headerRow;
      for (let r = box.top; r < firstHeader; r++) {
        if (rowFilledCount(g, r, box.left, box.right) === 0) continue;
        const label = firstText(g, r, box.left, box.right);
        skipped.push(opts.keepLabels && label ? { row: r, reason: "title", label } : { row: r, reason: "title" });
      }
    }

    const dataRows: number[] = [];
    for (let r = headerRow + 1; r <= box.bottom; r++) {
      const filled = rowFilledCount(g, r, box.left, box.right);
      if (filled === 0) {
        skipped.push({ row: r, reason: "blank" });
        continue;
      }
      const total = hasTotalKeyword(g, r, box.left, box.right);
      if (total !== undefined && filled <= Math.ceil(width * 0.7)) {
        skipped.push({ row: r, reason: "totals", ...(opts.keepLabels ? { label: total } : {}) });
        continue;
      }
      if (filled === 1 && width >= 3) {
        const label = firstText(g, r, box.left, box.right);
        if (parseCell(label).k === "string") {
          skipped.push({ row: r, reason: "sectionLabel", ...(opts.keepLabels ? { label } : {}) });
          continue;
        }
      }
      dataRows.push(r);
    }

    return { box, headerRow, headerRows, headers, dataRows, skipped, warnings };
  });
}
