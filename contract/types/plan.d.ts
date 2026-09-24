export type JoinKind = "inner" | "left";
export type Grain = "day" | "week" | "month" | "quarter" | "year";

export interface Plan {
  meta: PlanMeta;
  sink: NewSheetSink | ExistingAnchorSink;
  source: EntityRef;
  steps: (FilterStep | DeriveStep | AggregateStep | SortStep | LimitStep | JoinStep | PivotStep | PeriodCompareStep)[];
  [k: string]: unknown;
}
export interface PlanMeta {
  generatedAt: string;
  modelId: string;
  planHash: string;
  promptVersion: string;
  [k: string]: unknown;
}
export interface NewSheetSink {
  anchor: string;
  name: string;
  mode: "newSheet";
  [k: string]: unknown;
}
export interface ExistingAnchorSink {
  expectedCols: number;
  range: string;
  mode: "existingAnchor";
  [k: string]: unknown;
}
export interface EntityRef {
  ref: string;
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
  left: ColRef | Lit;
  right: ColRef | Lit;
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
  type: "lit";
  [k: string]: unknown;
}
export interface NePredicate {
  left: ColRef | Lit;
  right: ColRef | Lit;
  op: "ne";
  [k: string]: unknown;
}
export interface LtPredicate {
  left: ColRef | Lit;
  right: ColRef | Lit;
  op: "lt";
  [k: string]: unknown;
}
export interface LtePredicate {
  left: ColRef | Lit;
  right: ColRef | Lit;
  op: "lte";
  [k: string]: unknown;
}
export interface GtPredicate {
  left: ColRef | Lit;
  right: ColRef | Lit;
  op: "gt";
  [k: string]: unknown;
}
export interface GtePredicate {
  left: ColRef | Lit;
  right: ColRef | Lit;
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
    | WeekOfExpr
    | DayOfExpr
    | DayOfWeekExpr
    | BucketExpr
    | CoalesceExpr
    | CaseExpr
    | SumAllExpr;
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
export interface WeekOfExpr {
  col: string;
  type: "weekOf";
  [k: string]: unknown;
}
export interface DayOfExpr {
  col: string;
  type: "dayOf";
  [k: string]: unknown;
}
export interface DayOfWeekExpr {
  col: string;
  type: "dayOfWeek";
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
export interface AggregateStep {
  groupBy: string[];
  measures: AggregateMeasure[];
  op: "aggregate";
  [k: string]: unknown;
}
export interface AggregateMeasure {
  as: string;
  fn: string;
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
  dir: string;
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
