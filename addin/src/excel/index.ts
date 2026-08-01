/**
 * Anti-corruption layer (ACL) — the only code that touches Office.js.
 *
 * Every interaction with the Excel object model goes through this module.
 * All other code is pure TypeScript with zero Office dependency.
 *
 * Discipline enforced here (M4 will fill in the implementations):
 *   - Two syncs per run: one bulk read, one bulk write. Never a sync inside a loop.
 *   - load() with explicit property lists, always.
 *   - 2D array writes, never cell-by-cell.
 *   - suspendApiCalculationUntilNextSync() around the commit.
 *   - trackedObjects.remove() on everything, especially under a shared runtime.
 */

/* global Excel */

/** A table read from the grid: column names + rows of raw values. */
export interface RawTable {
  columns: string[];
  rows: unknown[][];
}

/**
 * Reads a used range in one sync and returns it as a RawTable.
 * M2 (Profiler) builds on this.
 */
export async function readRange(address: string): Promise<RawTable> {
  return Excel.run(async (context) => {
    const sheet = context.workbook.worksheets.getActiveWorksheet();
    const range = sheet.getRange(address);

    range.load(["values", "columnCount", "rowCount"]);
    await context.sync();

    const values = range.values as unknown[][];
    if (values.length === 0) return { columns: [], rows: [] };

    const [headerRow, ...dataRows] = values;
    const columns = (headerRow as unknown[]).map(String);
    return { columns, rows: dataRows };
  });
}

/**
 * Writes a 2D array to the given address in one batched sync.
 * M4 (Committer) wraps this with sink validation.
 */
export async function writeRange(
  sheetName: string,
  anchor: string,
  values: unknown[][]
): Promise<void> {
  return Excel.run(async (context) => {
    const sheet = context.workbook.worksheets.getItem(sheetName);
    const startCell = sheet.getRange(anchor);
    const range = startCell.getResizedRange(values.length - 1, values[0]!.length - 1);

    context.application.suspendApiCalculationUntilNextSync();
    range.values = values as string[][];
    await context.sync();
  });
}

/**
 * Creates a new sheet with the given name. Fails if the sheet already exists.
 */
export async function addSheet(name: string): Promise<void> {
  return Excel.run(async (context) => {
    context.workbook.worksheets.add(name);
    await context.sync();
  });
}
