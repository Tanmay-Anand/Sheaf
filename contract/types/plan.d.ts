export interface PlanSchema {
  meta?: {
    generatedAt?: string;
    modelId?: string;
    planHash?: string;
    promptVersion?: string;
    [k: string]: unknown;
  };
  sink?:
    | {
        anchor?: string;
        name?: string;
        mode: "newSheet";
        [k: string]: unknown;
      }
    | {
        expectedCols?: number;
        range?: string;
        mode: "existingAnchor";
        [k: string]: unknown;
      };
  source?: {
    ref?: string;
    [k: string]: unknown;
  };
  steps?: (
    | {
        predicate?:
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
          | AndPredicate2
          | OrPredicate2
          | NotPredicate2;
        op: "filter";
        [k: string]: unknown;
      }
    | {
        as?: string;
        expr?:
          | ColRef2
          | Lit2
          | AddExpr2
          | SubExpr2
          | MulExpr2
          | DivExpr2
          | RatioExpr2
          | PctExpr2
          | YearOfExpr
          | QuarterOfExpr
          | MonthOfExpr
          | WeekOfExpr
          | DayOfExpr
          | DayOfWeekExpr
          | BucketExpr
          | CoalesceExpr2
          | CaseExpr2
          | SumAllExpr;
        op: "derive";
        [k: string]: unknown;
      }
    | {
        groupBy?: string[];
        measures?: {
          as?: string;
          fn?: string;
          of?: string;
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
            | AndPredicate2
            | OrPredicate2
            | NotPredicate2;
          [k: string]: unknown;
        }[];
        op: "aggregate";
        [k: string]: unknown;
      }
    | {
        by?: {
          col?: string;
          dir?: string;
          [k: string]: unknown;
        }[];
        op: "sort";
        [k: string]: unknown;
      }
    | {
        n?: number;
        op: "limit";
        [k: string]: unknown;
      }
    | {
        kind?: "inner" | "left";
        on?: {
          left?: string;
          right?: string;
          [k: string]: unknown;
        };
        with?: string;
        op: "join";
        [k: string]: unknown;
      }
    | {
        cols?: string;
        rows?: string[];
        values?: string;
        op: "pivot";
        [k: string]: unknown;
      }
    | {
        current?: string;
        grain?: "day" | "week" | "month" | "quarter" | "year";
        groupBy?: string[];
        metric?: string;
        prior?: string;
        timeDim?: string;
        op: "periodCompare";
        [k: string]: unknown;
      }
  )[];
  [k: string]: unknown;
}
export interface EqPredicate {
  left?: ColRef1 | Lit1;
  right?: ColRef1 | Lit1;
  op: "eq";
  [k: string]: unknown;
}
export interface ColRef1 {
  col?: string;
  type: "col";
  [k: string]: unknown;
}
export interface Lit1 {
  value?: unknown;
  type: "lit";
  [k: string]: unknown;
}
export interface NePredicate {
  left?: ColRef1 | Lit1;
  right?: ColRef1 | Lit1;
  op: "ne";
  [k: string]: unknown;
}
export interface LtPredicate {
  left?: ColRef1 | Lit1;
  right?: ColRef1 | Lit1;
  op: "lt";
  [k: string]: unknown;
}
export interface LtePredicate {
  left?: ColRef1 | Lit1;
  right?: ColRef1 | Lit1;
  op: "lte";
  [k: string]: unknown;
}
export interface GtPredicate {
  left?: ColRef1 | Lit1;
  right?: ColRef1 | Lit1;
  op: "gt";
  [k: string]: unknown;
}
export interface GtePredicate {
  left?: ColRef1 | Lit1;
  right?: ColRef1 | Lit1;
  op: "gte";
  [k: string]: unknown;
}
export interface InPredicate {
  col?: string;
  values?: unknown[];
  op: "in";
  [k: string]: unknown;
}
export interface NotInPredicate {
  col?: string;
  values?: unknown[];
  op: "notIn";
  [k: string]: unknown;
}
export interface IsNullPredicate {
  col?: string;
  op: "isNull";
  [k: string]: unknown;
}
export interface IsNotNullPredicate {
  col?: string;
  op: "isNotNull";
  [k: string]: unknown;
}
export interface AndPredicate2 {
  op: "and";
  [k: string]: unknown;
}
export interface OrPredicate2 {
  op: "or";
  [k: string]: unknown;
}
export interface NotPredicate2 {
  op: "not";
  [k: string]: unknown;
}
export interface ColRef2 {
  col?: string;
  type: "col";
  [k: string]: unknown;
}
export interface Lit2 {
  value?: unknown;
  type: "lit";
  [k: string]: unknown;
}
export interface AddExpr2 {
  type: "add";
  [k: string]: unknown;
}
export interface SubExpr2 {
  type: "sub";
  [k: string]: unknown;
}
export interface MulExpr2 {
  type: "mul";
  [k: string]: unknown;
}
export interface DivExpr2 {
  type: "div";
  [k: string]: unknown;
}
export interface RatioExpr2 {
  type: "ratio";
  [k: string]: unknown;
}
export interface PctExpr2 {
  type: "pct";
  [k: string]: unknown;
}
export interface YearOfExpr {
  col?: string;
  type: "yearOf";
  [k: string]: unknown;
}
export interface QuarterOfExpr {
  col?: string;
  type: "quarterOf";
  [k: string]: unknown;
}
export interface MonthOfExpr {
  col?: string;
  type: "monthOf";
  [k: string]: unknown;
}
export interface WeekOfExpr {
  col?: string;
  type: "weekOf";
  [k: string]: unknown;
}
export interface DayOfExpr {
  col?: string;
  type: "dayOf";
  [k: string]: unknown;
}
export interface DayOfWeekExpr {
  col?: string;
  type: "dayOfWeek";
  [k: string]: unknown;
}
export interface BucketExpr {
  breaks?: number[];
  col?: string;
  labels?: string[];
  type: "bucket";
  [k: string]: unknown;
}
export interface CoalesceExpr2 {
  type: "coalesce";
  [k: string]: unknown;
}
export interface CaseExpr2 {
  type: "case";
  [k: string]: unknown;
}
export interface SumAllExpr {
  col?: string;
  type: "sumAll";
  [k: string]: unknown;
}
