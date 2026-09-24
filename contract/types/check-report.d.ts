export type DiagnosticCode =
  | "E_PARSE_INVALID_JSON"
  | "E_PARSE_UNKNOWN_VARIANT"
  | "E_PARSE_UNKNOWN_FIELD"
  | "E_PARSE_MISSING_FIELD"
  | "E_PARSE_INVALID_VALUE"
  | "E_UNKNOWN_SOURCE"
  | "E_SINK_NEW_SHEET_NAME_MISSING"
  | "E_SINK_SHEET_NAME_INVALID"
  | "E_SINK_SHEET_NAME_TAKEN"
  | "E_SINK_ANCHOR_INVALID"
  | "E_EMPTY_PIPELINE_WRITE"
  | "E_UNKNOWN_COLUMN"
  | "E_COLUMN_NOT_IN_OUTPUT"
  | "E_DERIVE_COLUMN_COLLISION"
  | "E_DUPLICATE_OUTPUT_COLUMN"
  | "E_TYPE_MISMATCH"
  | "E_TYPE_INCOMPARABLE"
  | "E_CURRENCY_UNIT_MISMATCH"
  | "E_ORDER_COMPARISON_NON_ORDERED"
  | "E_INVALID_ARGUMENT"
  | "E_TOTEXT_OUTSIDE_CONCAT"
  | "E_UNKNOWN_PARAM"
  | "E_DATE_EXTRACT_NON_DATE"
  | "E_UNKNOWN_METRIC"
  | "E_PERIOD_COMPARE_NO_TIME_DIM"
  | "E_PERIOD_COMPARE_UNSUPPORTED_GRAIN"
  | "E_PERIOD_COMPARE_INVALID_PERIOD"
  | "E_PERIOD_COMPARE_PLACEMENT"
  | "E_UNAPPROVED_JOIN"
  | "E_JOIN_KEY_TYPE_MISMATCH"
  | "E_JOIN_ENTITY_UNKNOWN"
  | "E_LOOKUP_KEY_NOT_UNIQUE"
  | "E_PIVOT_VALUE_NOT_NUMERIC"
  | "E_PIVOT_NON_CATEGORICAL_COLS"
  | "E_PIVOT_HIGH_CARDINALITY"
  | "E_PIVOT_NOT_TERMINAL"
  | "E_SUMALL_INVALID_PLACEMENT"
  | "E_SUMALL_UNKNOWN_COLUMN"
  | "E_BUCKET_BREAK_COUNT"
  | "E_CASE_NO_ELSE"
  | "E_SORT_CATEGORICAL_UNORDERED"
  | "E_AGGREGATE_GROUPBY_UNKNOWN"
  | "E_COUNTIF_NO_PREDICATE"
  | "E_LIMIT_NOT_POSITIVE"
  | "E_EDIT_EMPTY"
  | "E_EDIT_UNKNOWN_COLUMN"
  | "E_EDIT_COLUMN_COLLISION"
  | "E_RENAME_COLLISION"
  | "E_EDIT_DROPS_EVERY_COLUMN"
  | "E_TEMPLATE_UNKNOWN"
  | "E_TEMPLATE_COLUMN_MISSING"
  | "E_TEMPLATE_EXTRA_COLUMN"
  | "E_TEMPLATE_COLUMN_ORDER"
  | "E_TEMPLATE_TYPE_INCOMPATIBLE"
  | "E_TEMPLATE_VALUE_NOT_IN_LIST"
  | "E_TEMPLATE_DYNAMIC_COLUMNS"
  | "E_COMMIT_PLAN_MISMATCH"
  | "E_SINK_ANCHOR_MISSING"
  | "E_DROP_HAS_DEPENDENTS"
  | "E_PARAM_MISSING"
  | "E_PARAM_TYPE"
  | "W_LIMIT_WITHOUT_SORT"
  | "W_TRIVIAL_PLAN"
  | "W_CATEGORICAL_LITERAL_NOT_IN_DOMAIN"
  | "W_OVERWRITES_FORMULAS"
  | "W_UNMAPPED_TEMPLATE_COLUMN"
  | "W_UNTRACEABLE_REFERENCES"
  | "W_DEPENDENTS_NEED_CONSENT"
  | "W_MAY_CONTAIN_ERRORS"
  | "W_NUMBERS_STORED_AS_TEXT";
export type ValueType = "string" | "number" | "boolean" | "date" | "datetime";
export type AggFn = "count" | "countDistinct" | "countIf" | "sum" | "avg" | "min" | "max";
export type SortDir = "asc" | "desc";
export type JoinKind = "inner" | "left";
export type Grain = "day" | "week" | "month" | "quarter" | "year";
export type Cause = "columnRemoved" | "rowsRemoved";
export type DependentKind =
  "formula" | "namedRange" | "dataValidation" | "conditionalFormat" | "chartSeries" | "pivotTable";
export type DependentClass = "exclusive" | "spanning" | "wholeColumn" | "wholeRow";

export interface CheckReport {
  diagnostics: Diagnostic[];
  dynamicValue?: OutputColumn;
  envelope?: PlanEnvelope;
  impact?: ImpactReport;
  output: OutputColumn[];
  planHash?: string;
  valid: boolean;
  [k: string]: unknown;
}
export interface Diagnostic {
  code: DiagnosticCode;
  context: {
    [k: string]: unknown;
  };
  hint: string;
  message: string;
  operator?: string;
  pointer: string;
  step?: number;
  [k: string]: unknown;
}
export interface OutputColumn {
  name: string;
  nullable: boolean;
  type: string;
  [k: string]: unknown;
}
export interface PlanEnvelope {
  irVersion: string;
  plan: QueryPlan | EditPlan;
  [k: string]: unknown;
}
export interface QueryPlan {
  bindings: Bindings;
  params: ParamDecl[];
  sink: NewSheetSink | AnchorSink | TemplateSink;
  source: string;
  steps: (
    | FilterStep
    | DeriveStep
    | AggregateStep
    | SortStep
    | LimitStep
    | JoinStep
    | PivotStep
    | PeriodCompareStep
    | ProjectStep
    | LookupStep
  )[];
  kind: "query";
  [k: string]: unknown;
}
export interface Bindings {
  entities: EntityBinding[];
  [k: string]: unknown;
}
export interface EntityBinding {
  columns: ColumnBinding[];
  id: string;
  name: string;
  sheetId: string;
  [k: string]: unknown;
}
export interface ColumnBinding {
  id: string;
  name: string;
  [k: string]: unknown;
}
export interface ParamDecl {
  name: string;
  valueType: ValueType;
  [k: string]: unknown;
}
export interface NewSheetSink {
  anchor: string;
  name: string;
  mode: "newSheet";
  [k: string]: unknown;
}
export interface AnchorSink {
  mode: "anchor";
  [k: string]: unknown;
}
export interface TemplateSink {
  firstDataRow: number;
  headerRow: number;
  templateId: string;
  mode: "template";
  [k: string]: unknown;
}
export interface FilterStep {
  predicate:
    | EqPredicate
    | NePredicate
    | LtPredicate
    | LtePredicate
    | GtPredicate
    | GtePredicate
    | InPredicate
    | NotInPredicate
    | IsNullPredicate
    | IsNotNullPredicate
    | AndPredicate
    | OrPredicate
    | NotPredicate;
  op: "filter";
  [k: string]: unknown;
}
export interface EqPredicate {
  left: ColRef | Lit | ParamRef;
  right: ColRef | Lit | ParamRef;
  op: "eq";
  [k: string]: unknown;
}
export interface ColRef {
  col: string;
  type: "col";
  [k: string]: unknown;
}
export interface Lit {
  value?: unknown;
  valueType?: ValueType;
  type: "lit";
  [k: string]: unknown;
}
export interface ParamRef {
  name: string;
  type: "param";
  [k: string]: unknown;
}
export interface NePredicate {
  left: ColRef | Lit | ParamRef;
  right: ColRef | Lit | ParamRef;
  op: "ne";
  [k: string]: unknown;
}
export interface LtPredicate {
  left: ColRef | Lit | ParamRef;
  right: ColRef | Lit | ParamRef;
  op: "lt";
  [k: string]: unknown;
}
export interface LtePredicate {
  left: ColRef | Lit | ParamRef;
  right: ColRef | Lit | ParamRef;
  op: "lte";
  [k: string]: unknown;
}
export interface GtPredicate {
  left: ColRef | Lit | ParamRef;
  right: ColRef | Lit | ParamRef;
  op: "gt";
  [k: string]: unknown;
}
export interface GtePredicate {
  left: ColRef | Lit | ParamRef;
  right: ColRef | Lit | ParamRef;
  op: "gte";
  [k: string]: unknown;
}
export interface InPredicate {
  col: string;
  values: unknown[];
  op: "in";
  [k: string]: unknown;
}
export interface NotInPredicate {
  col: string;
  values: unknown[];
  op: "notIn";
  [k: string]: unknown;
}
export interface IsNullPredicate {
  col: string;
  op: "isNull";
  [k: string]: unknown;
}
export interface IsNotNullPredicate {
  col: string;
  op: "isNotNull";
  [k: string]: unknown;
}
export interface AndPredicate {
  op: "and";
  [k: string]: unknown;
}
export interface OrPredicate {
  op: "or";
  [k: string]: unknown;
}
export interface NotPredicate {
  op: "not";
  [k: string]: unknown;
}
export interface DeriveStep {
  as: string;
  expr:
    | ColRef1
    | Lit1
    | AddExpr
    | SubExpr
    | MulExpr
    | DivExpr
    | RatioExpr
    | PctExpr
    | YearOfExpr
    | QuarterOfExpr
    | MonthOfExpr
    | IsoWeekOfExpr
    | DayOfExpr
    | IsoDayOfWeekExpr
    | BucketExpr
    | CoalesceExpr
    | CaseExpr
    | SumAllExpr
    | ConcatExpr
    | TrimExpr
    | UpperExpr
    | LowerExpr
    | SplitPartExpr
    | ToTextExpr
    | ParamRef1;
  op: "derive";
  [k: string]: unknown;
}
export interface ColRef1 {
  col: string;
  type: "col";
  [k: string]: unknown;
}
export interface Lit1 {
  value?: unknown;
  valueType?: ValueType;
  type: "lit";
  [k: string]: unknown;
}
export interface AddExpr {
  type: "add";
  [k: string]: unknown;
}
export interface SubExpr {
  type: "sub";
  [k: string]: unknown;
}
export interface MulExpr {
  type: "mul";
  [k: string]: unknown;
}
export interface DivExpr {
  type: "div";
  [k: string]: unknown;
}
export interface RatioExpr {
  type: "ratio";
  [k: string]: unknown;
}
export interface PctExpr {
  type: "pct";
  [k: string]: unknown;
}
export interface YearOfExpr {
  col: string;
  type: "yearOf";
  [k: string]: unknown;
}
export interface QuarterOfExpr {
  col: string;
  type: "quarterOf";
  [k: string]: unknown;
}
export interface MonthOfExpr {
  col: string;
  type: "monthOf";
  [k: string]: unknown;
}
export interface IsoWeekOfExpr {
  col: string;
  type: "isoWeekOf";
  [k: string]: unknown;
}
export interface DayOfExpr {
  col: string;
  type: "dayOf";
  [k: string]: unknown;
}
export interface IsoDayOfWeekExpr {
  col: string;
  type: "isoDayOfWeek";
  [k: string]: unknown;
}
export interface BucketExpr {
  breaks: number[];
  col: string;
  labels: string[];
  type: "bucket";
  [k: string]: unknown;
}
export interface CoalesceExpr {
  type: "coalesce";
  [k: string]: unknown;
}
export interface CaseExpr {
  type: "case";
  [k: string]: unknown;
}
export interface SumAllExpr {
  col: string;
  type: "sumAll";
  [k: string]: unknown;
}
export interface ConcatExpr {
  type: "concat";
  [k: string]: unknown;
}
export interface TrimExpr {
  type: "trim";
  [k: string]: unknown;
}
export interface UpperExpr {
  type: "upper";
  [k: string]: unknown;
}
export interface LowerExpr {
  type: "lower";
  [k: string]: unknown;
}
export interface SplitPartExpr {
  type: "splitPart";
  [k: string]: unknown;
}
export interface ToTextExpr {
  type: "toText";
  [k: string]: unknown;
}
export interface ParamRef1 {
  name: string;
  type: "param";
  [k: string]: unknown;
}
export interface AggregateStep {
  groupBy: string[];
  measures: AggregateMeasure[];
  op: "aggregate";
  [k: string]: unknown;
}
export interface AggregateMeasure {
  as: string;
  fn: AggFn;
  of: string;
  where?:
    | EqPredicate
    | NePredicate
    | LtPredicate
    | LtePredicate
    | GtPredicate
    | GtePredicate
    | InPredicate
    | NotInPredicate
    | IsNullPredicate
    | IsNotNullPredicate
    | AndPredicate
    | OrPredicate
    | NotPredicate;
  [k: string]: unknown;
}
export interface SortStep {
  by: SortKey[];
  op: "sort";
  [k: string]: unknown;
}
export interface SortKey {
  col: string;
  dir: SortDir;
  [k: string]: unknown;
}
export interface LimitStep {
  n: number;
  op: "limit";
  [k: string]: unknown;
}
export interface JoinStep {
  kind: JoinKind;
  on: JoinKey;
  with: string;
  op: "join";
  [k: string]: unknown;
}
export interface JoinKey {
  left: string;
  right: string;
  [k: string]: unknown;
}
export interface PivotStep {
  cols: string;
  rows: string[];
  values: string;
  op: "pivot";
  [k: string]: unknown;
}
export interface PeriodCompareStep {
  current: string;
  grain: Grain;
  groupBy?: string[];
  metric: string;
  prior: string;
  timeDim: string;
  op: "periodCompare";
  [k: string]: unknown;
}
export interface ProjectStep {
  columns: ProjectColumn[];
  op: "project";
  [k: string]: unknown;
}
export interface ProjectColumn {
  as: string;
  expr:
    | ColRef1
    | Lit1
    | AddExpr
    | SubExpr
    | MulExpr
    | DivExpr
    | RatioExpr
    | PctExpr
    | YearOfExpr
    | QuarterOfExpr
    | MonthOfExpr
    | IsoWeekOfExpr
    | DayOfExpr
    | IsoDayOfWeekExpr
    | BucketExpr
    | CoalesceExpr
    | CaseExpr
    | SumAllExpr
    | ConcatExpr
    | TrimExpr
    | UpperExpr
    | LowerExpr
    | SplitPartExpr
    | ToTextExpr
    | ParamRef1;
  [k: string]: unknown;
}
export interface LookupStep {
  on: JoinKey;
  take?: string[];
  with: string;
  op: "lookup";
  [k: string]: unknown;
}
export interface EditPlan {
  bindings: Bindings;
  ops: (AddColumn | SetColumn | DropColumn | RenameColumn | MoveColumn | DropRows)[];
  params: ParamDecl[];
  target: string;
  kind: "edit";
  [k: string]: unknown;
}
export interface AddColumn {
  as: string;
  expr:
    | ColRef1
    | Lit1
    | AddExpr
    | SubExpr
    | MulExpr
    | DivExpr
    | RatioExpr
    | PctExpr
    | YearOfExpr
    | QuarterOfExpr
    | MonthOfExpr
    | IsoWeekOfExpr
    | DayOfExpr
    | IsoDayOfWeekExpr
    | BucketExpr
    | CoalesceExpr
    | CaseExpr
    | SumAllExpr
    | ConcatExpr
    | TrimExpr
    | UpperExpr
    | LowerExpr
    | SplitPartExpr
    | ToTextExpr
    | ParamRef1;
  position: First | Last | After;
  op: "addColumn";
  [k: string]: unknown;
}
export interface First {
  at: "first";
  [k: string]: unknown;
}
export interface Last {
  at: "last";
  [k: string]: unknown;
}
export interface After {
  column: string;
  at: "after";
  [k: string]: unknown;
}
export interface SetColumn {
  col: string;
  expr:
    | ColRef1
    | Lit1
    | AddExpr
    | SubExpr
    | MulExpr
    | DivExpr
    | RatioExpr
    | PctExpr
    | YearOfExpr
    | QuarterOfExpr
    | MonthOfExpr
    | IsoWeekOfExpr
    | DayOfExpr
    | IsoDayOfWeekExpr
    | BucketExpr
    | CoalesceExpr
    | CaseExpr
    | SumAllExpr
    | ConcatExpr
    | TrimExpr
    | UpperExpr
    | LowerExpr
    | SplitPartExpr
    | ToTextExpr
    | ParamRef1;
  where?:
    | EqPredicate
    | NePredicate
    | LtPredicate
    | LtePredicate
    | GtPredicate
    | GtePredicate
    | InPredicate
    | NotInPredicate
    | IsNullPredicate
    | IsNotNullPredicate
    | AndPredicate
    | OrPredicate
    | NotPredicate;
  op: "setColumn";
  [k: string]: unknown;
}
export interface DropColumn {
  col: string;
  op: "dropColumn";
  [k: string]: unknown;
}
export interface RenameColumn {
  col: string;
  to: string;
  op: "renameColumn";
  [k: string]: unknown;
}
export interface MoveColumn {
  col: string;
  position: First | Last | After;
  op: "moveColumn";
  [k: string]: unknown;
}
export interface DropRows {
  where:
    | EqPredicate
    | NePredicate
    | LtPredicate
    | LtePredicate
    | GtPredicate
    | GtePredicate
    | InPredicate
    | NotInPredicate
    | IsNullPredicate
    | IsNotNullPredicate
    | AndPredicate
    | OrPredicate
    | NotPredicate;
  op: "dropRows";
  [k: string]: unknown;
}
export interface ImpactReport {
  added: string[];
  dependentsNeedingConsent: AffectedDependent[];
  formulaColumnsOverwritten: string[];
  moved: string[];
  overwritten: string[];
  removed: string[];
  removesRows: boolean;
  renamed: Rename[];
  untraceableReferences: boolean;
  [k: string]: unknown;
}
export interface AffectedDependent {
  cause: Cause;
  column: string;
  dependent: ColumnDependent;
  [k: string]: unknown;
}
export interface ColumnDependent {
  detail: string;
  fixedRows: boolean;
  kind: DependentKind;
  location: string;
  refClass: DependentClass;
  [k: string]: unknown;
}
export interface Rename {
  from: string;
  to: string;
  [k: string]: unknown;
}
