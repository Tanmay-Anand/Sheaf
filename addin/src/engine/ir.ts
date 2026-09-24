/**
 * The bound plan as the evaluator reads it: the wire format of contract/schema/plan.schema.json,
 * typed precisely. (The generated plan.d.ts loses the fields of recursive expressions, so the engine
 * keeps its own types; ir.test.ts checks every discriminator in the schema is handled here.)
 */

export type ValueType = "string" | "number" | "boolean" | "date" | "datetime";
export type AggFn = "count" | "countDistinct" | "countIf" | "sum" | "avg" | "min" | "max";
export type Grain = "day" | "week" | "month" | "quarter" | "year";

export type ValueRef =
  | { type: "col"; col: string }
  | { type: "lit"; value: unknown; valueType?: ValueType }
  | { type: "param"; name: string };

export type Predicate =
  | { op: "eq" | "ne" | "lt" | "lte" | "gt" | "gte"; left: ValueRef; right: ValueRef }
  | { op: "in" | "notIn"; col: string; values: unknown[] }
  | { op: "isNull" | "isNotNull"; col: string }
  | { op: "and" | "or"; clauses: Predicate[] }
  | { op: "not"; clause: Predicate };

export type Expr =
  | { type: "col"; col: string }
  | { type: "lit"; value: unknown; valueType?: ValueType }
  | { type: "param"; name: string }
  | { type: "add" | "sub" | "mul" | "div"; a: Expr; b: Expr }
  | { type: "ratio" | "pct"; numerator: Expr; denominator: Expr }
  | { type: "yearOf" | "quarterOf" | "monthOf" | "isoWeekOf" | "dayOf" | "isoDayOfWeek"; col: string }
  | { type: "bucket"; col: string; breaks: number[]; labels: string[] }
  | { type: "coalesce"; args: Expr[] }
  | { type: "case"; when: { if: Predicate; then: ValueRef }[]; else: Expr }
  | { type: "sumAll"; col: string }
  | { type: "concat"; parts: Expr[]; sep?: string; skipNulls?: boolean }
  | { type: "trim" | "upper" | "lower"; arg: Expr }
  | { type: "splitPart"; arg: Expr; sep: string; index: number }
  | { type: "toText"; arg: Expr; pattern: string };

export interface JoinKey {
  left: string;
  right: string;
}

export interface Measure {
  fn: AggFn;
  of: string;
  where?: Predicate;
  as: string;
}

export type Step =
  | { op: "filter"; predicate: Predicate }
  | { op: "derive"; as: string; expr: Expr }
  | { op: "aggregate"; groupBy: string[]; measures: Measure[] }
  | { op: "sort"; by: { col: string; dir: "asc" | "desc" }[] }
  | { op: "limit"; n: number }
  | { op: "join"; with: string; on: JoinKey; kind: "inner" | "left" }
  | { op: "lookup"; with: string; on: JoinKey; take?: string[] }
  | { op: "pivot"; rows: string[]; cols: string; values: string }
  | { op: "periodCompare"; metric: string; timeDim: string; grain: Grain; current: string; prior: string; groupBy?: string[] }
  | { op: "project"; columns: { as: string; expr: Expr }[] };

export type Position = { at: "first" } | { at: "last" } | { at: "after"; column: string };

export type EditOp =
  | { op: "addColumn"; as: string; expr: Expr; position: Position }
  | { op: "setColumn"; col: string; expr: Expr; where?: Predicate }
  | { op: "dropColumn"; col: string }
  | { op: "renameColumn"; col: string; to: string }
  | { op: "moveColumn"; col: string; position: Position }
  | { op: "dropRows"; where: Predicate };

export type Sink =
  | { mode: "newSheet"; name: string; anchor: string }
  | { mode: "anchor" }
  | { mode: "template"; templateId: string; headerRow: number; firstDataRow: number };

export interface ParamDecl {
  name: string;
  valueType: ValueType;
}

export interface EntityBinding {
  name: string;
  id: string;
  sheetId: string;
  columns: { name: string; id: string }[];
}

export interface QueryPlan {
  kind: "query";
  source: string;
  steps: Step[];
  sink: Sink;
  params: ParamDecl[];
  bindings: { entities: EntityBinding[] };
}

export interface EditPlan {
  kind: "edit";
  target: string;
  ops: EditOp[];
  params: ParamDecl[];
  bindings: { entities: EntityBinding[] };
}

export type Plan = QueryPlan | EditPlan;

/** Every discriminator the evaluator handles, by discriminator field. Checked against the schema. */
export const HANDLED = {
  kind: ["query", "edit"],
  step: ["filter", "derive", "aggregate", "sort", "limit", "join", "lookup", "pivot", "periodCompare", "project"],
  predicate: ["eq", "ne", "lt", "lte", "gt", "gte", "in", "notIn", "isNull", "isNotNull", "and", "or", "not"],
  editOp: ["addColumn", "setColumn", "dropColumn", "renameColumn", "moveColumn", "dropRows"],
  expr: [
    "col", "lit", "param", "add", "sub", "mul", "div", "ratio", "pct", "yearOf", "quarterOf", "monthOf", "isoWeekOf",
    "dayOf", "isoDayOfWeek", "bucket", "coalesce", "case", "sumAll", "concat", "trim", "upper", "lower", "splitPart", "toText",
  ],
  mode: ["newSheet", "anchor", "template"],
  at: ["first", "last", "after"],
} as const;
