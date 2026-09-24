/**
 * Excel on the web refuses any request or response over 5 MB. A sheet read returns values,
 * formulas and number formats for every cell, so a large used range is read in blocks of whole
 * rows, each within a cell budget that keeps its payload well under the limit.
 */

/** Cells per read. At a generous ~60 bytes per cell across the three arrays, about 2.4 MB. */
export const MAX_CELLS_PER_READ = 40_000;

export interface RowChunk {
  /** 0-based offset from the first row of the range. */
  start: number;
  count: number;
}

export function planRowChunks(rowCount: number, columnCount: number, maxCells = MAX_CELLS_PER_READ): RowChunk[] {
  if (rowCount <= 0 || columnCount <= 0) return [];
  const rowsPerChunk = Math.max(1, Math.floor(maxCells / columnCount));
  const chunks: RowChunk[] = [];
  for (let start = 0; start < rowCount; start += rowsPerChunk) {
    chunks.push({ start, count: Math.min(rowsPerChunk, rowCount - start) });
  }
  return chunks;
}
