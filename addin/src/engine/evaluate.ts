import { compareRows, type SortKeySpec } from "./collate";
import { evalExpr, evalPredicate, paramValue, type RowScope } from "./expr";
import { isoDayOfWeek } from "./format";
import type { AggFn, Grain, Measure, Predicate, QueryPlan, Step } from "./ir";
import { columnIndex, EvalError, requireColumn, type Table, uniqueName } from "./table";
import { daysFromYmd, displayText, isBlank, isDate, isError, keyOf, type Value, xdate } from "./value";

/**
 * The evaluator: a pure function (bound query plan, source tables, options) → result. No Office,
 * no I/O, deterministic. The preview is computed from its result; the committer writes exactly
 * that result. Semantics follow ir-spec v1.2 (§2 operators, §3 expressions, §5.6 Excel semantics).
 */

export interface MetricDef {
  entity: string;
  fn: AggFn;
  of: string;
  filters: Predicate[];
}

export interface TimeDimensionDef {
  entity: string;
  column: string;
  weekStart?: "monday" | "sunday";
}

export interface EvalOptions {
  /** Parameter values from the commit request (raw JSON), by name. */
  params?: Record<string, unknown>;
  /** Skip error values in aggregates, and rows whose conditions meet one, instead of refusing. */
  excludeErrorCells?: boolean;
  metrics?: Record<string, MetricDef>;
  timeDimensions?: Record<string, TimeDimensionDef>;
  /** Declared category orders, by column name. */
  orders?: Record<string, string[]>;
}

export type IssueCode =
  | "ERROR_CELLS"
  | "ERROR_CELLS_EXCLUDED"
  | "LOOKUP_KEY_NOT_UNIQUE"
  | "ERRORS_IN_OUTPUT"
  | "LOAD_NOTE"
  | "OVERWRITES_SOURCE"
  | "OVERWRITES_TABLE";

export interface Issue {
  code: IssueCode;
  message: string;
  count: number;
  /** A blocking issue stops the commit. */
  blocking: boolean;
}

export interface EvalResult {
  table: Table;
  issues: Issue[];
}

interface Ctx {
  tables: Map<string, Table>;
  params: Map<string, Value>;
  opts: EvalOptions;
  orders: Map<string, Map<string, number>>;
  /** Error values met where they would break the result, by column. */
  errorSites: Map<string, number>;
  duplicateLookupKeys: number;
}

function scope(ctx: Ctx, table: Table, totals?: Map<string, number | null>): RowScope {
  return {
    table,
    params: ctx.params,
    orders: ctx.orders,
    ...(totals ? { totals } : {}),
    onError: (site) => ctx.errorSites.set(site, (ctx.errorSites.get(site) ?? 0) + 1),
  };
}

function tableNamed(ctx: Ctx, name: string): Table {
  const t = ctx.tables.get(name.toLowerCase());
  if (!t) throw new EvalError(`No data loaded for table '${name}'.`);
  return t;
}

// ── aggregates ────────────────────────────────────────────────────────────────

/** Reduces one measure over a group's rows. Error values are reported, or skipped when excluded. */
function reduce(fn: AggFn, values: Value[], site: string, ctx: Ctx): Value {
  if (fn === "count" || fn === "countIf") return values.filter((v) => !isBlank(v)).length;
  if (fn === "countDistinct") return new Set(values.filter((v) => !isBlank(v)).map(keyOf)).size;

  let firstError: Value = null;
  const nums: number[] = [];
  let dates = false;
  for (const v of values) {
    if (isBlank(v)) continue;
    if (isError(v)) {
      ctx.errorSites.set(site, (ctx.errorSites.get(site) ?? 0) + 1);
      firstError ??= v;
      continue;
    }
    if (typeof v === "number") nums.push(v);
    else if (isDate(v)) {
      nums.push(v.v);
      dates = true;
    }
  }
  if (firstError !== null && !ctx.opts.excludeErrorCells) return firstError;
  if (nums.length === 0) return null;
  let out = fn === "min" || fn === "max" ? nums[0]! : 0;
  for (const n of nums) {
    if (fn === "min") out = n < out ? n : out;
    else if (fn === "max") out = n > out ? n : out;
    else out += n;
  }
  if (fn === "avg") out /= nums.length;
  return dates && (fn === "min" || fn === "max") ? xdate(out) : out;
}

function measureValues(m: Measure, rows: Value[][], s: RowScope): Value[] {
  const matching = m.where ? rows.filter((r) => evalPredicate(m.where!, r, s)) : rows;
  if (m.of === "*") return matching.map(() => 1);
  const i = requireColumn(s.table, m.of);
  return matching.map((r) => r[i] ?? null);
}

function aggregate(t: Table, groupBy: string[], measures: Measure[], ctx: Ctx): Table {
  const s = scope(ctx, t);
  const gi = groupBy.map((g) => requireColumn(t, g));
  const groups = new Map<string, Value[][]>();
  for (const row of t.rows) {
    const key = gi.map((i) => keyOf(row[i] ?? null)).join("\u0001");
    const g = groups.get(key);
    if (g) g.push(row);
    else groups.set(key, [row]);
  }
  // No groupBy: one row over the whole table, even when it is empty.
  if (gi.length === 0 && groups.size === 0) groups.set("", []);
  const rows: Value[][] = [];
  for (const members of groups.values()) {
    const keys = gi.map((i) => members[0]?.[i] ?? null); // the first spelling seen, as Excel's GROUPBY
    const values = measures.map((m) => {
      const vals = measureValues(m, members, s);
      if ((m.fn === "sum" || m.fn === "avg" || m.fn === "min" || m.fn === "max") && m.where && vals.length === 0) return null;
      return reduce(m.fn, vals, m.of, ctx);
    });
    rows.push([...keys, ...values]);
  }
  return { columns: [...gi.map((i) => t.columns[i]!), ...measures.map((m) => m.as)], rows };
}

/** Grand totals of every numeric column, for sumAll right after an aggregate. */
function totalsOf(t: Table): Map<string, number | null> {
  const totals = new Map<string, number | null>();
  t.columns.forEach((c, i) => {
    let sum: number | null = null;
    for (const r of t.rows) {
      const v = r[i];
      if (typeof v === "number") sum = (sum ?? 0) + v;
    }
    totals.set(c.toLowerCase(), sum);
  });
  return totals;
}

// ── joins ─────────────────────────────────────────────────────────────────────

function join(left: Table, withName: string, on: { left: string; right: string }, kind: "inner" | "left", ctx: Ctx): Table {
  const right = tableNamed(ctx, withName);
  const li = requireColumn(left, on.left);
  const ri = requireColumn(right, on.right);
  const index = new Map<string, Value[][]>();
  for (const r of right.rows) {
    const v = r[ri] ?? null;
    if (isBlank(v) || isError(v)) continue; // blanks and errors match nothing
    const k = keyOf(v);
    const list = index.get(k);
    if (list) list.push(r);
    else index.set(k, [r]);
  }
  const taken = new Set(left.columns.map((c) => c.toLowerCase()));
  const columns = [...left.columns, ...right.columns.map((c) => uniqueName(c, withName, taken))];
  const empty = right.columns.map(() => null);
  const rows: Value[][] = [];
  for (const l of left.rows) {
    const v = l[li] ?? null;
    const matches = isBlank(v) || isError(v) ? undefined : index.get(keyOf(v));
    if (matches) for (const r of matches) rows.push([...l, ...r]);
    else if (kind === "left") rows.push([...l, ...empty]);
  }
  return { columns, rows };
}

function lookup(left: Table, withName: string, on: { left: string; right: string }, take: string[] | undefined, ctx: Ctx): Table {
  const right = tableNamed(ctx, withName);
  const li = requireColumn(left, on.left);
  const ri = requireColumn(right, on.right);
  const takeIdx = take
    ? take.map((c) => requireColumn(right, c))
    : right.columns.map((_, i) => i).filter((i) => i !== ri);
  const index = new Map<string, Value[]>();
  for (const r of right.rows) {
    const v = r[ri] ?? null;
    if (isBlank(v) || isError(v)) continue;
    const k = keyOf(v);
    if (index.has(k)) ctx.duplicateLookupKeys++; // the catalog said unique; the data disagrees
    else index.set(k, r);
  }
  const taken = new Set(left.columns.map((c) => c.toLowerCase()));
  const columns = [...left.columns, ...takeIdx.map((i) => uniqueName(right.columns[i]!, withName, taken))];
  const rows = left.rows.map((l) => {
    const v = l[li] ?? null;
    const match = isBlank(v) || isError(v) ? undefined : index.get(keyOf(v));
    return [...l, ...takeIdx.map((i) => (match ? (match[i] ?? null) : null))];
  });
  return { columns, rows };
}

// ── pivot ─────────────────────────────────────────────────────────────────────

function pivot(t: Table, rowsBy: string[], cols: string, valuesCol: string, ctx: Ctx): Table {
  const ri = rowsBy.map((r) => requireColumn(t, r));
  const ci = requireColumn(t, cols);
  const vi = requireColumn(t, valuesCol);
  const headers = new Map<string, Value>(); // key → first value seen
  const cells = new Map<string, Map<string, Value[]>>();
  const rowKeys = new Map<string, Value[]>();
  for (const row of t.rows) {
    const rk = ri.map((i) => keyOf(row[i] ?? null)).join("\u0001");
    if (!rowKeys.has(rk)) rowKeys.set(rk, ri.map((i) => row[i] ?? null));
    const cv = row[ci] ?? null;
    const ck = keyOf(cv);
    if (!headers.has(ck)) headers.set(ck, cv);
    let byCol = cells.get(rk);
    if (!byCol) cells.set(rk, (byCol = new Map()));
    const list = byCol.get(ck);
    const v = row[vi] ?? null;
    if (list) list.push(v);
    else byCol.set(ck, [v]);
  }
  // Column headers in Excel's ascending sort order, like PIVOTBY.
  const headerKeys = [...headers.entries()]
    .map(([k, v]) => ({ k, row: [v] }))
    .sort((a, b) => compareRows(a.row, b.row, [{ index: 0, dir: "asc" }]))
    .map((x) => x.k);
  const taken = new Set(ri.map((i) => t.columns[i]!.toLowerCase()));
  const headerNames = headerKeys.map((k) => {
    const v = headers.get(k)!;
    const name = isBlank(v) ? "(blank)" : displayText(v);
    let unique = name;
    for (let n = 2; taken.has(unique.toLowerCase()); n++) unique = `${name} ${n}`;
    taken.add(unique.toLowerCase());
    return unique;
  });
  const rows: Value[][] = [];
  for (const [rk, keys] of rowKeys) {
    const byCol = cells.get(rk)!;
    rows.push([...keys, ...headerKeys.map((ck) => {
      const vals = byCol.get(ck);
      return vals ? reduce("sum", vals, valuesCol, ctx) : null;
    })]);
  }
  return { columns: [...ri.map((i) => t.columns[i]!), ...headerNames], rows };
}

// ── periodCompare ──────────────────────────────────────────────────────────────

/** [start, end) in days for a period literal of the given grain. */
export function periodRange(lit: string, grain: Grain, weekStart: "monday" | "sunday" = "monday"): [number, number] {
  const bad = () => new EvalError(`'${lit}' is not a ${grain} period.`);
  switch (grain) {
    case "year": {
      const m = /^(\d{4})$/.exec(lit);
      if (!m) throw bad();
      return [daysFromYmd(+m[1]!, 1, 1), daysFromYmd(+m[1]! + 1, 1, 1)];
    }
    case "quarter": {
      const m = /^(\d{4})-Q([1-4])$/.exec(lit);
      if (!m) throw bad();
      const first = (+m[2]! - 1) * 3 + 1;
      return [daysFromYmd(+m[1]!, first, 1), daysFromYmd(+m[1]!, first + 3, 1)];
    }
    case "month": {
      const m = /^(\d{4})-(\d{2})$/.exec(lit);
      if (!m) throw bad();
      return [daysFromYmd(+m[1]!, +m[2]!, 1), daysFromYmd(+m[1]!, +m[2]! + 1, 1)];
    }
    case "week": {
      const m = /^(\d{4})-W(\d{2})$/.exec(lit);
      if (!m) throw bad();
      // ISO week 1 is the week with the year's first Thursday; it starts on that week's Monday.
      const jan4 = daysFromYmd(+m[1]!, 1, 4);
      const monday = jan4 - (isoDayOfWeek(jan4) - 1) + (+m[2]! - 1) * 7;
      const start = weekStart === "sunday" ? monday - 1 : monday;
      return [start, start + 7];
    }
    case "day": {
      const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(lit);
      if (!m) throw bad();
      const d = daysFromYmd(+m[1]!, +m[2]!, +m[3]!);
      return [d, d + 1];
    }
  }
}

function periodCompare(t: Table, step: Extract<Step, { op: "periodCompare" }>, ctx: Ctx): Table {
  const metric = ctx.opts.metrics?.[step.metric];
  const dim = ctx.opts.timeDimensions?.[step.timeDim];
  if (!metric || !dim) throw new EvalError(`The semantic model doesn't define '${step.metric}' over '${step.timeDim}'.`);
  const s = scope(ctx, t);
  const di = requireColumn(t, dim.column);
  const groupBy = step.groupBy ?? [];
  const measure: Measure = { fn: metric.fn, of: metric.of, as: "value" };
  const inPeriod = (lit: string) => {
    const [from, to] = periodRange(lit, step.grain, dim.weekStart);
    const rows = t.rows.filter((r) => {
      const d = r[di] ?? null;
      if (!isDate(d) || d.v < from || d.v >= to) return false;
      return metric.filters.every((f) => evalPredicate(f, r, s));
    });
    return aggregate({ columns: t.columns, rows }, groupBy, [measure], ctx);
  };
  // The type checker's naming: "Revenue" → current_revenue, "Order Count" → current_order_count.
  const slug = step.metric.toLowerCase().replace(/[^a-z0-9]+/g, "_");
  const cur = inPeriod(step.current);
  const pri = inPeriod(step.prior);
  const g = groupBy.length;
  const keyOfRow = (r: Value[]) => r.slice(0, g).map((v) => keyOf(v)).join("\u0001");
  const prior = new Map(pri.rows.map((r) => [keyOfRow(r), r]));
  const seen = new Set<string>();
  const rows: Value[][] = [];
  const emit = (keys: Value[], c: Value, p: Value) => {
    const num = (v: Value) => (typeof v === "number" ? v : null);
    const [cn, pn] = [num(c), num(p)];
    const delta = cn !== null && pn !== null ? cn - pn : null;
    const pct = delta === null || pn === null ? null : pn === 0 ? null : delta / pn;
    rows.push([...keys, c, p, delta, pct]);
  };
  for (const r of cur.rows) {
    const k = keyOfRow(r);
    seen.add(k);
    emit(r.slice(0, g), r[g] ?? null, prior.get(k)?.[g] ?? null);
  }
  for (const r of pri.rows) if (!seen.has(keyOfRow(r))) emit(r.slice(0, g), null, r[g] ?? null);
  return {
    columns: [...cur.columns.slice(0, g), `current_${slug}`, `prior_${slug}`, "delta", "delta_pct"],
    rows,
  };
}

// ── the pipeline ──────────────────────────────────────────────────────────────

function applyStep(t: Table, step: Step, prev: Step | undefined, ctx: Ctx): Table {
  switch (step.op) {
    case "filter": {
      const s = scope(ctx, t);
      return { columns: t.columns, rows: t.rows.filter((r) => evalPredicate(step.predicate, r, s)) };
    }
    case "derive": {
      const s = scope(ctx, t, prev?.op === "aggregate" ? totalsOf(t) : undefined);
      return { columns: [...t.columns, step.as], rows: t.rows.map((r) => [...r, evalExpr(step.expr, r, s)]) };
    }
    case "project": {
      const s = scope(ctx, t, prev?.op === "aggregate" ? totalsOf(t) : undefined);
      return {
        columns: step.columns.map((c) => c.as),
        rows: t.rows.map((r) => step.columns.map((c) => evalExpr(c.expr, r, s))),
      };
    }
    case "aggregate":
      return aggregate(t, step.groupBy, step.measures, ctx);
    case "sort": {
      const keys: SortKeySpec[] = step.by.map((k) => {
        const order = ctx.orders.get(k.col.toLowerCase());
        return { index: requireColumn(t, k.col), dir: k.dir, ...(order ? { order } : {}) };
      });
      return { columns: t.columns, rows: [...t.rows].sort((a, b) => compareRows(a, b, keys)) };
    }
    case "limit":
      return { columns: t.columns, rows: t.rows.slice(0, step.n) };
    case "join":
      return join(t, step.with, step.on, step.kind, ctx);
    case "lookup":
      return lookup(t, step.with, step.on, step.take, ctx);
    case "pivot":
      return pivot(t, step.rows, step.cols, step.values, ctx);
    case "periodCompare":
      return periodCompare(t, step, ctx);
  }
}

function plural(n: number, one: string, many = `${one}s`): string {
  return `${n.toLocaleString("en")} ${n === 1 ? one : many}`;
}

export function evaluate(plan: QueryPlan, tables: Record<string, Table>, opts: EvalOptions = {}): EvalResult {
  const params = new Map<string, Value>();
  for (const p of plan.params) {
    const raw = Object.entries(opts.params ?? {}).find(([k]) => k.toLowerCase() === p.name.toLowerCase());
    if (raw) params.set(p.name.toLowerCase(), paramValue(raw[1], p.valueType));
  }
  const orders = new Map<string, Map<string, number>>();
  for (const [col, members] of Object.entries(opts.orders ?? {})) {
    orders.set(col.toLowerCase(), new Map(members.map((m, i) => [m.toLowerCase(), i])));
  }
  const ctx: Ctx = {
    tables: new Map(Object.entries(tables).map(([k, v]) => [k.toLowerCase(), v])),
    params,
    opts,
    orders,
    errorSites: new Map(),
    duplicateLookupKeys: 0,
  };

  let t = tableNamed(ctx, plan.source);
  plan.steps.forEach((step, i) => {
    t = applyStep(t, step, plan.steps[i - 1], ctx);
  });

  const issues: Issue[] = [];
  const errors = [...ctx.errorSites.values()].reduce((a, b) => a + b, 0);
  if (errors > 0) {
    const where = [...ctx.errorSites.keys()].join(", ");
    issues.push(
      opts.excludeErrorCells
        ? {
            code: "ERROR_CELLS_EXCLUDED",
            message: `${plural(errors, "cell")} with error values (in ${where}) ${errors === 1 ? "was" : "were"} left out.`,
            count: errors,
            blocking: false,
          }
        : {
            code: "ERROR_CELLS",
            message: `${plural(errors, "cell")} with error values (in ${where}) would make the result an error, as in Excel. Fix them, or choose to leave them out.`,
            count: errors,
            blocking: true,
          },
    );
  }
  if (ctx.duplicateLookupKeys > 0) {
    issues.push({
      code: "LOOKUP_KEY_NOT_UNIQUE",
      message: `The lookup key repeats in ${plural(ctx.duplicateLookupKeys, "row")}, so a lookup could pick either match. Rescan the workbook, or look up on a key.`,
      count: ctx.duplicateLookupKeys,
      blocking: true,
    });
  }
  let outErrors = 0;
  for (const r of t.rows) for (const v of r) if (isError(v)) outErrors++;
  if (outErrors > 0) {
    issues.push({
      code: "ERRORS_IN_OUTPUT",
      message: `${plural(outErrors, "cell")} of the result ${outErrors === 1 ? "is an error value" : "are error values"} (e.g. a division by zero).`,
      count: outErrors,
      blocking: false,
    });
  }
  // The column list must match the type checker's; a mismatch is caught by tests, not users.
  if (new Set(t.columns.map((c) => c.toLowerCase())).size !== t.columns.length) {
    throw new EvalError(`The result has duplicate column names: ${t.columns.join(", ")}.`);
  }
  return { table: t, issues };
}

export { columnIndex };
