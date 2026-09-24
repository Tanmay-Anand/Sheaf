import { compareForPredicate } from "./collate";
import { isoDayOfWeek, isoWeek, toText } from "./format";
import type { Expr, Predicate, ValueRef, ValueType } from "./ir";
import { columnIndex, EvalError, requireColumn, type Table } from "./table";
import {
  DIV0,
  displayText,
  isBlank,
  isDate,
  isError,
  keyOf,
  parseIsoDate,
  VALUE,
  type Value,
  ymdFromDays,
} from "./value";

/**
 * Row-level evaluation of expressions and predicates (ir-spec §3, §4), shared by queries, edits
 * (M6) and template fill (M7).
 *
 * Null semantics are the spec's, not Excel's: a null operand makes arithmetic null (Excel would
 * read a blank as 0), and a blank never satisfies <, <=, > or >=. Error values propagate through
 * expressions like Excel's; in a predicate they are reported through `onError` and the row does not
 * match.
 */

export interface RowScope {
  table: Table;
  params: Map<string, Value>;
  /** Grand totals for sumAll, by lower-cased column name. */
  totals?: Map<string, number | null>;
  /** Declared category orders, by lower-cased column name. */
  orders?: Map<string, Map<string, number>>;
  /** Called when a predicate meets an error value; `site` is the column (or "value"). */
  onError: (site: string) => void;
}

export function literal(value: unknown, valueType?: ValueType): Value {
  if (value === null || value === undefined) return null;
  if (valueType === "date" || valueType === "datetime") {
    return typeof value === "string" ? (parseIsoDate(value) ?? VALUE) : VALUE;
  }
  if (typeof value === "number" || typeof value === "string" || typeof value === "boolean") return value;
  return VALUE;
}

/** A parameter value from the commit request, typed by its declaration. */
export function paramValue(raw: unknown, valueType: ValueType): Value {
  if (raw === null || raw === undefined) return null;
  switch (valueType) {
    case "date":
    case "datetime":
      return typeof raw === "string" ? (parseIsoDate(raw) ?? VALUE) : VALUE;
    case "number":
      return typeof raw === "number" ? raw : VALUE;
    case "boolean":
      return typeof raw === "boolean" ? raw : VALUE;
    case "string":
      return typeof raw === "string" ? raw : VALUE;
  }
}

function param(scope: RowScope, name: string): Value {
  const v = scope.params.get(name.toLowerCase());
  if (v === undefined) throw new EvalError(`No value for parameter '${name}'.`);
  return v;
}

function colValue(scope: RowScope, row: Value[], name: string): Value {
  return row[requireColumn(scope.table, name)] ?? null;
}

// ── arithmetic ─────────────────────────────────────────────────────────────────

function numeric(v: Value): number | null | Value {
  if (v === null) return null;
  if (typeof v === "number") return v;
  if (isError(v)) return v;
  return VALUE;
}

function arith(op: "add" | "sub" | "mul" | "div" | "ratio" | "pct", av: Value, bv: Value): Value {
  const a = numeric(av);
  const b = numeric(bv);
  if (typeof a === "object" && a !== null) return a; // error
  if (typeof b === "object" && b !== null) return b;
  if (a === null || b === null) return null;
  const x = a as number;
  const y = b as number;
  switch (op) {
    case "add":
      return x + y;
    case "sub":
      return x - y;
    case "mul":
      return x * y;
    default:
      return y === 0 ? DIV0 : x / y;
  }
}

// ── dates ──────────────────────────────────────────────────────────────────────

function datePart(type: string, v: Value): Value {
  if (v === null) return null;
  if (isError(v)) return v;
  if (!isDate(v)) return VALUE;
  const { y, m, d } = ymdFromDays(v.v);
  switch (type) {
    case "yearOf":
      return y;
    case "quarterOf":
      return Math.floor((m - 1) / 3) + 1;
    case "monthOf":
      return m;
    case "dayOf":
      return d;
    case "isoWeekOf":
      return isoWeek(v.v);
    default:
      return isoDayOfWeek(v.v);
  }
}

// ── text ───────────────────────────────────────────────────────────────────────

/** Excel's TRIM: no leading or trailing spaces, and single spaces inside. */
function excelTrim(s: string): string {
  return s.replace(/ +/g, " ").trim();
}

function textFn(type: "trim" | "upper" | "lower", v: Value): Value {
  if (v === null) return null;
  if (isError(v)) return v;
  const s = displayText(v);
  switch (type) {
    case "trim":
      return excelTrim(s);
    case "upper":
      return s.toUpperCase();
    default:
      return s.toLowerCase();
  }
}

function concat(parts: Value[], sep: string, skipNulls: boolean): Value {
  const err = parts.find(isError);
  if (err) return err;
  if (parts.every((p) => p === null)) return null;
  const texts = skipNulls ? parts.filter((p) => !isBlank(p)).map(displayText) : parts.map(displayText);
  return texts.join(sep);
}

function splitPart(v: Value, sep: string, index: number): Value {
  if (v === null) return null;
  if (isError(v)) return v;
  const pieces = displayText(v).split(sep);
  return index >= 1 && index <= pieces.length ? pieces[index - 1]! : null;
}

// ── expressions ───────────────────────────────────────────────────────────────

export function evalExpr(e: Expr, row: Value[], scope: RowScope): Value {
  switch (e.type) {
    case "col":
      return colValue(scope, row, e.col);
    case "lit":
      return literal(e.value, e.valueType);
    case "param":
      return param(scope, e.name);
    case "add":
    case "sub":
    case "mul":
    case "div":
      return arith(e.type, evalExpr(e.a, row, scope), evalExpr(e.b, row, scope));
    case "ratio":
    case "pct":
      return arith(e.type, evalExpr(e.numerator, row, scope), evalExpr(e.denominator, row, scope));
    case "yearOf":
    case "quarterOf":
    case "monthOf":
    case "isoWeekOf":
    case "dayOf":
    case "isoDayOfWeek":
      return datePart(e.type, colValue(scope, row, e.col));
    case "bucket": {
      const v = colValue(scope, row, e.col);
      if (v === null) return null;
      if (isError(v)) return v;
      if (typeof v !== "number") return VALUE;
      let i = 0;
      while (i < e.breaks.length && v >= e.breaks[i]!) i++;
      return e.labels[i] ?? null;
    }
    case "coalesce": {
      for (const a of e.args) {
        const v = evalExpr(a, row, scope);
        if (v !== null) return v;
      }
      return null;
    }
    case "case": {
      for (const w of e.when) if (evalPredicate(w.if, row, scope)) return evalRef(w.then, row, scope);
      return evalExpr(e.else, row, scope);
    }
    case "sumAll": {
      const total = scope.totals?.get(e.col.toLowerCase());
      if (total === undefined) throw new EvalError(`sumAll(${e.col}) has no grand total at this step.`);
      return total;
    }
    case "concat":
      return concat(e.parts.map((p) => evalExpr(p, row, scope)), e.sep ?? "", e.skipNulls ?? false);
    case "trim":
    case "upper":
    case "lower":
      return textFn(e.type, evalExpr(e.arg, row, scope));
    case "splitPart":
      return splitPart(evalExpr(e.arg, row, scope), e.sep, e.index);
    case "toText":
      return toText(evalExpr(e.arg, row, scope), e.pattern);
  }
}

// ── predicates ────────────────────────────────────────────────────────────────

export function evalRef(r: ValueRef, row: Value[], scope: RowScope): Value {
  switch (r.type) {
    case "col":
      return colValue(scope, row, r.col);
    case "lit":
      return literal(r.value, r.valueType);
    case "param":
      return param(scope, r.name);
  }
}

function site(r: ValueRef): string {
  return r.type === "col" ? r.col : "value";
}

function orderFor(scope: RowScope, r: ValueRef): Map<string, number> | undefined {
  return r.type === "col" ? scope.orders?.get(r.col.toLowerCase()) : undefined;
}

/** A date and a datetime compare as days; everything else by value. */
function ordered(a: Value, b: Value): boolean {
  const kind = (v: Value) => (typeof v === "number" ? "n" : isDate(v) ? "d" : typeof v);
  return kind(a) === kind(b);
}

/** Three-valued: an error operand makes the comparison unknown, and unknown never selects a row. */
type Truth = 1 | 0 | -1; // true, false, unknown (an error was met)

function truth(p: Predicate, row: Value[], scope: RowScope): Truth {
  switch (p.op) {
    case "eq":
    case "ne":
    case "lt":
    case "lte":
    case "gt":
    case "gte": {
      const a = evalRef(p.left, row, scope);
      const b = evalRef(p.right, row, scope);
      if (isError(a) || isError(b)) {
        scope.onError(isError(a) ? site(p.left) : site(p.right));
        return -1;
      }
      if (p.op === "eq") return keyOf(a) === keyOf(b) ? 1 : 0;
      if (p.op === "ne") return keyOf(a) !== keyOf(b) ? 1 : 0;
      if (isBlank(a) || isBlank(b) || !ordered(a, b)) return 0;
      const c = compareForPredicate(a, b, orderFor(scope, p.left) ?? orderFor(scope, p.right));
      const r = p.op === "lt" ? c < 0 : p.op === "lte" ? c <= 0 : p.op === "gt" ? c > 0 : c >= 0;
      return r ? 1 : 0;
    }
    case "in":
    case "notIn": {
      const v = colValue(scope, row, p.col);
      if (isError(v)) {
        scope.onError(p.col);
        return -1;
      }
      const k = keyOf(v);
      const hit = p.values.some((x) => keyOf(literal(x)) === k);
      return (p.op === "in" ? hit : !hit) ? 1 : 0;
    }
    case "isNull":
      return isBlank(colValue(scope, row, p.col)) ? 1 : 0;
    case "isNotNull":
      return isBlank(colValue(scope, row, p.col)) ? 0 : 1;
    case "and": {
      let out: Truth = 1;
      for (const c of p.clauses) {
        const t = truth(c, row, scope);
        if (t === 0) return 0;
        if (t === -1) out = -1;
      }
      return out;
    }
    case "or": {
      let out: Truth = 0;
      for (const c of p.clauses) {
        const t = truth(c, row, scope);
        if (t === 1) return 1;
        if (t === -1) out = -1;
      }
      return out;
    }
    case "not": {
      const t = truth(p.clause, row, scope);
      return t === -1 ? -1 : t === 1 ? 0 : 1;
    }
  }
}

export function evalPredicate(p: Predicate, row: Value[], scope: RowScope): boolean {
  return truth(p, row, scope) === 1;
}

/** Whether a column exists at this step (for callers that probe optional columns). */
export function hasColumn(t: Table, name: string): boolean {
  return columnIndex(t, name) >= 0;
}
