export type ScalarKind =
  "number" | "currency" | "percent" | "date" | "datetime" | "string" | "boolean" | "categorical" | "empty";
export type DependentKind =
  "formula" | "namedRange" | "dataValidation" | "conditionalFormat" | "chartSeries" | "pivotTable";
export type EntityKind = "table" | "region";
export type SkipReason = "blank" | "sectionLabel" | "totals" | "title";
export type UntraceableKind = "indirect" | "offset" | "externalLink" | "unparsed";

export interface WorkbookCatalog {
  corrections: CatalogCorrection[];
  entities: CatalogEntity[];
  generatedAt: string;
  joinCandidates: JoinCandidate[];
  untraceable: UntraceableReference[];
  version: number;
  warnings: string[];
  [k: string]: unknown;
}
export interface CatalogCorrection {
  column?: string;
  entity: string;
  headerRow?: number;
  kind?: ScalarKind;
  sheet: string;
  unit?: string;
  [k: string]: unknown;
}
export interface CatalogEntity {
  address: string;
  columns: CatalogColumn[];
  contentHash: string;
  dataRowCount: number;
  firstDataRow: number;
  headerRow: number;
  headerRows: number;
  kind: EntityKind;
  lastDataRow: number;
  name: string;
  sheet: string;
  skippedRows: SkippedRow[];
  tableName?: string;
  [k: string]: unknown;
}
export interface CatalogColumn {
  dateFormats?: string[];
  dependents: ColumnDependent[];
  distinctCount: number;
  exemplars?: string[];
  formula: boolean;
  index: number;
  keyCandidate: boolean;
  kind: ScalarKind;
  letter: string;
  name: string;
  nullRate: number;
  nullable: boolean;
  numberFormat?: string;
  percentScale?: number;
  unit?: string;
  validationList?: string[];
  warnings: string[];
  [k: string]: unknown;
}
export interface ColumnDependent {
  detail: string;
  kind: DependentKind;
  location: string;
  [k: string]: unknown;
}
export interface SkippedRow {
  label?: string;
  reason: SkipReason;
  row: number;
  [k: string]: unknown;
}
export interface JoinCandidate {
  approved: boolean;
  cardinality: string;
  confidence: number;
  fromColumn: string;
  fromEntity: string;
  overlap: number;
  toColumn: string;
  toEntity: string;
  [k: string]: unknown;
}
export interface UntraceableReference {
  detail: string;
  kind: UntraceableKind;
  location: string;
  [k: string]: unknown;
}
