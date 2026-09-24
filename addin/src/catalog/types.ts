import type { DependentKind } from "@sheaf/contract/catalog";

/**
 * What the Office.js layer hands to the pure catalog builder. Nothing in src/catalog imports
 * Office.js; tests build these snapshots from the corpus CSVs.
 */

export type CellValue = string | number | boolean | null;

export interface SheetSnapshot {
  name: string;
  /** 1-based sheet row of values[0]. */
  originRow: number;
  /** 0-based sheet column of values[r][0]. */
  originCol: number;
  values: CellValue[][];
  /** Same shape as values; a string starting with "=" is a formula. */
  formulas?: CellValue[][];
  numberFormats?: string[][];
}

export interface TableSnapshot {
  name: string;
  sheet: string;
  /** Full table address including header and totals rows, e.g. "Sales!A1:H200". */
  address: string;
  showHeaders: boolean;
  showTotals: boolean;
  columns: string[];
}

export interface NamedItemSnapshot {
  name: string;
  /** The name's definition, e.g. "=Sales!$G$2:$G$200". */
  formula: string;
}

/**
 * Anything that holds reference text: a chart series, a PivotTable source, a conditional-format
 * rule, a validation list source. Formula cells are added by the builder from the sheet grids.
 */
export interface ReferenceSource {
  kind: DependentKind;
  location: string;
  /** Sheet that unqualified references in `text` resolve against. */
  sheet: string;
  text: string;
  /** Position of a formula cell, used to group a column of formulas into one dependent. */
  row?: number;
  col?: number;
}

export interface ValidationSnapshot {
  sheet: string;
  address: string;
  /** Inline list values ("Yes,No" becomes ["Yes", "No"]). */
  listValues?: string[];
  /** Range source, e.g. "=Lists!$A$2:$A$7". */
  listSource?: string;
}

export interface WorkbookSnapshot {
  sheets: SheetSnapshot[];
  tables: TableSnapshot[];
  names: NamedItemSnapshot[];
  sources: ReferenceSource[];
  validations: ValidationSnapshot[];
}
