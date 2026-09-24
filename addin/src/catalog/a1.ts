/**
 * A1 addressing helpers. Rows are 1-based sheet rows; columns are 0-based indexes
 * (0 = column A). Boxes are inclusive on all four sides.
 */

export const MAX_ROW = 1_048_576;
export const MAX_COL = 16_383; // XFD

export interface Box {
  top: number;
  left: number;
  bottom: number;
  right: number;
}

export function colToLetters(col: number): string {
  let n = col + 1;
  let s = "";
  while (n > 0) {
    const r = (n - 1) % 26;
    s = String.fromCharCode(65 + r) + s;
    n = Math.floor((n - 1) / 26);
  }
  return s;
}

export function lettersToCol(letters: string): number {
  let n = 0;
  for (const ch of letters.toUpperCase()) n = n * 26 + (ch.charCodeAt(0) - 64);
  return n - 1;
}

/** Quotes a sheet name the way Excel does when it can't appear bare in a reference. */
export function quoteSheet(name: string): string {
  const bare = /^[A-Za-z_][A-Za-z0-9_.]*$/.test(name) && !/^[A-Za-z]{1,3}\d+$/.test(name);
  return bare ? name : `'${name.replace(/'/g, "''")}'`;
}

export function cellRef(row: number, col: number): string {
  return `${colToLetters(col)}${row}`;
}

/** "Sheet1!A1:D5", or "Sheet1!B4" for a single cell. */
export function boxAddress(sheet: string, box: Box): string {
  const a = cellRef(box.top, box.left);
  const z = cellRef(box.bottom, box.right);
  return `${quoteSheet(sheet)}!${a === z ? a : `${a}:${z}`}`;
}

export function intersects(a: Box, b: Box): boolean {
  return a.left <= b.right && b.left <= a.right && a.top <= b.bottom && b.top <= a.bottom;
}

export function contains(outer: Box, inner: Box): boolean {
  return (
    inner.top >= outer.top &&
    inner.bottom <= outer.bottom &&
    inner.left >= outer.left &&
    inner.right <= outer.right
  );
}

const CELL = /^\$?([A-Za-z]{1,3})\$?(\d{1,7})$/;
const COL = /^\$?([A-Za-z]{1,3})$/;
const ROW = /^\$?(\d{1,7})$/;

function validCell(col: number, row: number): boolean {
  return col >= 0 && col <= MAX_COL && row >= 1 && row <= MAX_ROW;
}

/**
 * Parses the part after "!" (or a bare reference): "A1", "$A$1:$D$20", "C:C", "3:5".
 * Returns null when the text is not an A1 reference.
 */
export function parseRef(ref: string): Box | null {
  const [p, q] = ref.split(":");
  if (p === undefined) return null;
  if (q === undefined) {
    const m = CELL.exec(p);
    if (!m) return null;
    const col = lettersToCol(m[1]!);
    const row = Number(m[2]);
    return validCell(col, row) ? { top: row, bottom: row, left: col, right: col } : null;
  }
  const c1 = CELL.exec(p);
  const c2 = CELL.exec(q);
  if (c1 && c2) {
    const a = { col: lettersToCol(c1[1]!), row: Number(c1[2]) };
    const b = { col: lettersToCol(c2[1]!), row: Number(c2[2]) };
    if (!validCell(a.col, a.row) || !validCell(b.col, b.row)) return null;
    return {
      top: Math.min(a.row, b.row),
      bottom: Math.max(a.row, b.row),
      left: Math.min(a.col, b.col),
      right: Math.max(a.col, b.col),
    };
  }
  const k1 = COL.exec(p);
  const k2 = COL.exec(q);
  if (k1 && k2) {
    const a = lettersToCol(k1[1]!);
    const b = lettersToCol(k2[1]!);
    if (a > MAX_COL || b > MAX_COL) return null;
    return { top: 1, bottom: MAX_ROW, left: Math.min(a, b), right: Math.max(a, b) };
  }
  const r1 = ROW.exec(p);
  const r2 = ROW.exec(q);
  if (r1 && r2) {
    const a = Number(r1[1]);
    const b = Number(r2[1]);
    if (a < 1 || b < 1 || a > MAX_ROW || b > MAX_ROW) return null;
    return { top: Math.min(a, b), bottom: Math.max(a, b), left: 0, right: MAX_COL };
  }
  return null;
}

/** Parses "Sheet1!A1:D5", "'My Sheet'!$A$1", or a bare "A1:D5". */
export function parseAddress(address: string): { sheet: string | null; box: Box } | null {
  const bang = address.lastIndexOf("!");
  let sheet: string | null = null;
  let ref = address;
  if (bang >= 0) {
    const raw = address.slice(0, bang);
    sheet = raw.startsWith("'") && raw.endsWith("'") ? raw.slice(1, -1).replace(/''/g, "'") : raw;
    ref = address.slice(bang + 1);
  }
  const box = parseRef(ref.trim());
  return box ? { sheet, box } : null;
}

export function sameSheet(a: string, b: string): boolean {
  return a.localeCompare(b, undefined, { sensitivity: "accent" }) === 0;
}
