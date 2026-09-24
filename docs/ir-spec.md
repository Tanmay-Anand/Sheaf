# Sheaf IR — Specification v1

**Status:** frozen at M0. Changes after this point require a written design rationale and a version bump.

---

## 1. Plan shape

```
Plan := {
  source:  EntityRef
  steps:   Step[]          -- ordered pipeline; may be empty
  sink:    Sink
  meta:    PlanMeta
}

EntityRef := { ref: string }   -- name must match an approved entity in the semantic model

Sink :=
  | { mode: "newSheet",       name: string, anchor: CellRef }
  | { mode: "existingAnchor", range: RangeRef }

CellRef   := "A1" | "B3" | ...
RangeRef  := "Sheet1!A1:D20" | ...

PlanMeta := {
  planHash:      string     -- SHA-256 of canonical plan JSON
  modelId:       string     -- model identity (e.g. "claude-sonnet-4-6")
  promptVersion: string     -- semver of the prompt artifact used
  generatedAt:   datetime   -- ISO 8601
}
```

---

## 2. Operators

### 2.1 `filter`

Selects a subset of rows. Shape-preserving: output has the same columns as input.

```
filter(T) → T

fields:
  predicate: Predicate
```

**Signature constraints:**
- All columns referenced in `predicate` must exist in the input table type `T`.
- Comparison operands must satisfy the comparability rules in §4.

**Nullability:** `filter` does not change the nullability of any column. A predicate that would exclude nulls (e.g. `eq(col, "x")`) does not make `col` non-nullable in the output type — the type system is conservative.

---

### 2.2 `derive`

Adds a single new column computed from a closed expression. Shape-extending.

```
derive(T) → T + { as: ExprType }

fields:
  as:   string       -- name for the new column; must not collide with existing columns
  expr: Expr
```

**Signature constraints:**
- `as` must not duplicate an existing column name.
- The column type of the new column is determined by the expression type rules in §3.
- If any input column referenced in `expr` is nullable, the output column is nullable unless wrapped in `coalesce`.
- `sumAll` may only appear in a `derive` step that immediately follows an `aggregate` step on the same output.

**Multiple derives:** apply multiple `derive` steps sequentially. Each step can reference columns added by preceding `derive` steps.

---

### 2.3 `aggregate`

Groups rows and reduces each group to one row. Shape-replacing: output columns are exactly the `groupBy` columns plus the declared measures.

```
aggregate(T) → { groupBy_cols..., measure_cols... }

fields:
  groupBy:  string[]           -- column names; must exist in T
  measures: AggregateMeasure[]
```

```
AggregateMeasure := {
  fn:    AggFn
  of:    string | "*"          -- column name, or "*" for count(*)
  where: Predicate?            -- optional conditional filter (countIf / sumIf pattern)
  as:    string                -- output column name
}

AggFn :=
  | "count"          -- count(*) or count(col), ignores nulls when col specified
  | "countDistinct"  -- count(distinct col)
  | "countIf"        -- count(*) where predicate
  | "sum"            -- sum(col)
  | "avg"            -- avg(col), ignores nulls
  | "min"            -- min(col), ignores nulls
  | "max"            -- max(col), ignores nulls
```

**Aggregate function type rules:**

| `fn` | Allowed `of` types | Output type |
|---|---|---|
| `count`, `countDistinct`, `countIf` | any, or `*` | `number` |
| `sum` | `number`, `currency:X`, `percent` | same as input |
| `avg` | `number`, `currency:X`, `percent` | same as input |
| `min`, `max` | `number`, `currency:X`, `percent`, `date`, `datetime` | same as input |

**Nullability of output:**
- `groupBy` columns: nullable if the source column is nullable.
- Measure columns: nullable if the source column is nullable and the function propagates nulls (`sum`, `avg`, `min`, `max` return null when all inputs are null; `count` always returns non-null).

---

### 2.4 `sort`

Orders rows. Shape-preserving: same columns, same types.

```
sort(T) → T

fields:
  by: SortKey[]

SortKey := {
  col: string
  dir: "asc" | "desc"
}
```

**Constraints:**
- Each `col` must exist in `T`.
- Sorting a `categorical<D>` column is allowed only if the domain `D` has a declared ordering in the semantic model. If no ordering is declared, the type checker emits `E_SORT_CATEGORICAL_UNORDERED`.
- Sorting by a nullable column places nulls last (ascending) or nulls first (descending). This is not configurable in v1.

---

### 2.5 `limit`

Returns the first `n` rows. Always follows a `sort` to be meaningful; the type checker emits a warning (not an error) if `limit` is not immediately preceded by `sort`.

```
limit(T) → T

fields:
  n: integer   -- must be > 0
```

**Shape-preserving.** Output type is identical to input type; output row count ≤ `n`.

---

### 2.6 `join`

Combines two tables along a declared, approved join edge.

```
join(T1, T2) → T1 ∪ T2

fields:
  with: string        -- entity name (must match an approved entity)
  on:   JoinKey
  kind: "inner" | "left"

JoinKey := {
  left:  string       -- column in T1
  right: string       -- column in T2
}
```

**Constraints:**
- The pair `(source_entity, with_entity)` or its reverse must be a declared, **approved** join edge in the semantic model. Any join that traverses an unapproved edge fails with `E_UNAPPROVED_JOIN`.
- `left` and `right` column types must be comparable per §4.
- Output column names are the union of both schemas. Name collisions are resolved by prefixing with the entity name: if `T1` and `T2` both have a column `name`, the output has `Orders.name` and `Regions.name`. The type checker records the resolved names.

**Nullability:**
- `inner` join: columns from both sides retain their existing nullability.
- `left` join: all columns from the right-hand entity become nullable in the output.

---

### 2.7 `pivot`

Reshapes a table from long to wide by spreading one column's distinct values into multiple output columns.

```
pivot(T) → T'

fields:
  rows:   string[]   -- columns that become row identifiers
  cols:   string     -- column whose distinct values become output column headers
  values: string     -- column whose values populate the cells
```

**Constraints:**
- `cols` column must exist in `T` and be of type `categorical<D>` or `string`. Pivoting on `number`, `date`, `boolean` is disallowed (`E_PIVOT_NON_CATEGORICAL_COLS`).
- `values` column must be numeric: `number`, `currency:X`, or `percent` (`E_PIVOT_VALUE_NOT_NUMERIC`).
- `rows` columns must all exist in `T`.
- **Cardinality guard:** if the semantic model reports the `cols` column's cardinality > 50, the type checker emits `E_PIVOT_HIGH_CARDINALITY` and refuses. This is a validation-time error, not a runtime one.
- Implicit aggregation: cells are aggregated using `sum` when multiple rows map to the same (row_key, col_key). The type checker assumes `sum` semantics; the aggregation function is not configurable in v1.

**Output type:** `{ rows_cols..., colVal1: values_type | null, colVal2: values_type | null, ... }` — output column count depends on distinct values in the `cols` column at run time. This is the **one acknowledged static-typing exception** in the algebra.

---

### 2.8 `periodCompare`

First-class period-over-period comparison. Not sugar over filter + aggregate + join.

```
periodCompare(T) → T'

fields:
  metric:  string    -- approved metric name from the semantic model
  timeDim: string    -- approved time dimension name
  grain:   Grain
  current: PeriodLit
  prior:   PeriodLit
  groupBy: string[]  -- optional; dimension columns for breakdown
```

```
Grain :=
  | "day" | "week" | "month" | "quarter" | "year"

PeriodLit :=
  | "YYYY-Q#"        -- e.g. "2025-Q1"
  | "YYYY-MM"        -- e.g. "2025-03"
  | "YYYY"           -- e.g. "2024"
  | "YYYY-Www"       -- ISO week, e.g. "2025-W04"
  | "YYYY-MM-DD"     -- specific day
```

**Output columns (statically known):**
```
{ groupBy_cols...,
  current_{metric}: metric_type | null,
  prior_{metric}:   metric_type | null,
  delta:            metric_type | null,
  delta_pct:        percent | null }
```

Prior-period columns are nullable because a group member may not exist in the prior period.

**Constraints:**
- `metric` must reference an approved metric whose base entity matches `source`.
- `timeDim` must reference an approved time dimension whose base entity matches `source`.
- The declared `grain` must be in the time dimension's supported `grains` list.
- `groupBy` columns must be approved dimensions on the source entity.
- `current` and `prior` must be valid period literals for the given `grain`.
- The metric's own filters are applied within each period evaluation.

**Week-start convention:** inherited from the time dimension's `weekStart` property in the semantic model (`"monday"` or `"sunday"`). The type checker validates that `grain: "week"` is only used when `weekStart` is declared.

---

## 3. Expression types (for `derive`)

### 3.1 Arithmetic

| Expression | Input types | Output type | Notes |
|---|---|---|---|
| `{ "add": [a, b] }` | both `number` | `number` | |
| `{ "add": [a, b] }` | both `currency:X` | `currency:X` | units must match |
| `{ "sub": [a, b] }` | both `number` | `number` | |
| `{ "sub": [a, b] }` | both `currency:X` | `currency:X` | |
| `{ "mul": [a, b] }` | `number × number` | `number` | |
| `{ "mul": [a, b] }` | `currency:X × number` | `currency:X` | commutative |
| `{ "div": [a, b] }` | `number / number` | `number` | b must be non-zero (runtime check) |
| `{ "div": [a, b] }` | `currency:X / number` | `currency:X` | |
| `{ "ratio": [a, b] }` | both numeric | `number` | a/b as a plain ratio |
| `{ "pct": [a, b] }` | both numeric | `percent` | a/b expressed as percent |

**Currency unit rule:** `add`, `sub`, and arithmetic comparisons between `currency:X` and `currency:Y` (where X ≠ Y) are type errors (`E_CURRENCY_UNIT_MISMATCH`). There is no implicit currency conversion.

### 3.2 Date extraction

| Expression | Input type | Output type |
|---|---|---|
| `{ "yearOf": col }` | `date \| datetime` | `number` (4-digit year) |
| `{ "quarterOf": col }` | `date \| datetime` | `number` (1–4) |
| `{ "monthOf": col }` | `date \| datetime` | `number` (1–12) |
| `{ "weekOf": col }` | `date \| datetime` | `number` (ISO week 1–53) |
| `{ "dayOf": col }` | `date \| datetime` | `number` (1–31) |
| `{ "dayOfWeek": col }` | `date \| datetime` | `number` (1–7, Mon=1) |

Applying date extraction to a `string`, `number`, or non-date column is a type error (`E_DATE_EXTRACT_NON_DATE`).

### 3.3 Bucketing

```json
{ "bucket": { "col": "amount", "breaks": [0, 1000, 5000, 10000], "labels": ["<1k", "1k–5k", "5k–10k", ">10k"] } }
```

- Input: any ordered type (`number`, `currency:*`, `date`, `datetime`)
- Output: `categorical<labels>` — the domain is the declared `labels` array
- `breaks` has length N; `labels` has length N+1 (one label per interval, including the open ends)
- The output domain is statically known from the expression ✓

### 3.4 Coalesce

```json
{ "coalesce": ["col_a", "col_b", { "lit": 0 }] }
```

- Evaluates left-to-right; returns the first non-null value
- All arguments must have compatible types
- Output type: the common type of the arguments, non-nullable

### 3.5 Case

```json
{
  "case": {
    "when": [
      { "if": { "eq": ["status", "shipped"] }, "then": { "lit": "Delivered" } },
      { "if": { "eq": ["status", "returned"] }, "then": { "lit": "Returned" } }
    ],
    "else": { "lit": "Other" }
  }
}
```

- `then` values must all have compatible types
- `else` is required (no implicit null else — use `{ "lit": null }` explicitly if null is intended)
- Output type: common type of all `then` and `else` values

### 3.6 Literals

```json
{ "lit": 0 }
{ "lit": "returned" }
{ "lit": null }
{ "lit": "2025-01-01" }   -- parsed as date if the context expects a date
```

### 3.7 Column reference (in expressions)

```json
{ "col": "column_name" }
```

Used when a column name is needed as a value (e.g. in `pct`, `coalesce`, arithmetic). The plain string form `"column_name"` is also accepted as shorthand.

### 3.8 `sumAll` (restricted)

```json
{ "sumAll": "revenue" }
```

- Only valid inside a `derive` step that immediately follows an `aggregate` step on the same pipeline.
- References the grand total of a measure column from the preceding `aggregate` output.
- Returns the same type as the column it references, non-nullable.
- Purpose: enables the percentage-of-total pattern without a general window function.

---

## 4. Predicate grammar

```
Predicate :=
  | { "eq":       [ValueRef, ValueRef] }
  | { "ne":       [ValueRef, ValueRef] }
  | { "lt":       [ValueRef, ValueRef] }
  | { "lte":      [ValueRef, ValueRef] }
  | { "gt":       [ValueRef, ValueRef] }
  | { "gte":      [ValueRef, ValueRef] }
  | { "in":       [ValueRef, Value[]] }
  | { "notIn":    [ValueRef, Value[]] }
  | { "isNull":   colName }
  | { "isNotNull": colName }
  | { "and":      Predicate[] }
  | { "or":       Predicate[] }
  | { "not":      Predicate }

ValueRef :=
  | string           -- shorthand for column reference
  | { "col": string }   -- explicit column reference
  | { "lit": Value }    -- literal value

Value := number | string | boolean | null
```

**Comparability rules:** two `ValueRef`s are comparable if their resolved types satisfy:
- `number` ↔ `number` ✓
- `currency:X` ↔ `currency:X` ✓ (same unit)
- `currency:X` ↔ `currency:Y` ✗ (`E_CURRENCY_UNIT_MISMATCH`)
- `percent` ↔ `percent` ✓
- `date` ↔ `date` ✓
- `date` ↔ `datetime` ✓ (date is promoted to datetime at midnight)
- `datetime` ↔ `datetime` ✓
- `string` ↔ `string` ✓
- `boolean` ↔ `boolean` ✓
- `categorical<D>` ↔ `string` ✓ (string is treated as a domain member; a warning is emitted if the literal is not a known member of D)
- `categorical<D>` ↔ `categorical<D>` ✓
- Any other combination → `E_TYPE_INCOMPARABLE`

`eq` and `ne` are valid for any comparable type. Ordering comparisons (`lt`, `lte`, `gt`, `gte`) are valid only for ordered types: `number`, `currency:*`, `percent`, `date`, `datetime`. Applying them to `string`, `boolean`, or `categorical` without a declared order is `E_ORDER_COMPARISON_NON_ORDERED`.

---

## 5. Type system

### 5.1 Scalar types

```
ScalarType :=
  | number
  | currency:<unit>    -- unit is a ISO 4217 code or custom string, e.g. currency:INR
  | percent            -- stored as 0.0–1.0; rendered as 0%–100%
  | date               -- calendar date; no time component
  | datetime           -- timestamp; sub-second precision not required
  | string
  | boolean
  | categorical<D>     -- D is a domain identifier; members are a finite, declared set
```

Every scalar type may be nullable: `T | null`. Nullability is tracked per-column in the table type. The type checker propagates nullability through every operator.

### 5.2 Table type

A table type is an ordered record of named column types:

```
TableType := { colName₁: ScalarType₁, colName₂: ScalarType₂, ... }
```

Ordered because the evaluator and committer preserve column order and the sink compatibility check is order-sensitive for ranged writes.

### 5.3 Type checking algorithm

Type checking is a left-fold over the pipeline:

1. **Resolve source.** Look up `source.ref` in the semantic model. The entity's columns, as inferred by the profiler and confirmed in the semantic model, give the initial `TableType`.
2. **Fold over steps.** For each step, apply its operator signature to the current `TableType` to produce the next `TableType`, or emit a `Diagnostic` and halt.
3. **Check sink.** Verify the final `TableType` is compatible with the declared `Sink`.

The type checker is a pure function: `(Plan, SemanticModel) → TypeCheckResult`, where `TypeCheckResult` is either `Valid(finalType: TableType)` or `Invalid(diagnostics: Diagnostic[])`.

### 5.4 Sink compatibility

| Sink mode | Compatibility check |
|---|---|
| `newSheet` | Always compatible. Creates a new sheet; cannot overwrite existing data by definition. |
| `existingAnchor` | The number of output columns must be declared in the plan (the plan must include `expectedCols: number`). The type checker validates that the final column count matches. For plans containing `pivot`, exact column count is unknown at type-check time; compatibility is deferred to post-evaluation. |

### 5.5 Numeric coercions (allowed)

The only implicit coercions in the type system:
- `date` → `datetime`: allowed in comparisons only; a `date` value is treated as midnight on that date.
- `categorical<D>` → `string`: allowed in comparisons and `sort`; the string value is the domain member's canonical name.

No other implicit coercions. `number` and `currency:X` are not interchangeable without an explicit `mul` or `div` expression.

---

## 6. Pipeline validation invariants

These invariants are checked as part of type checking. Violation of any invariant is a type error.

1. **Source exists.** `source.ref` names an approved entity.
2. **No forward references.** A step may only reference column names that exist in the table type produced by all preceding steps.
3. **No post-aggregate column references (except in groupBy and measures).** After an `aggregate` step, only the declared `groupBy` columns and measure output columns exist. Referencing a pre-aggregate column in a subsequent step is `E_COLUMN_NOT_IN_OUTPUT`.
4. **`limit` preceded by `sort`.** Not a type error but a warning (`W_LIMIT_WITHOUT_SORT`). The type checker emits the warning; the plan is still valid.
5. **Join edge approved.** Every `join` step must traverse an edge present in the semantic model's join graph with `approved: true`.
6. **`sumAll` placement.** `sumAll` in a `derive` expression is only valid if the immediately preceding step is `aggregate`.
7. **`periodCompare` placement.** `periodCompare` may only appear as the first step after any `filter` steps. It cannot appear after `derive`, `aggregate`, `sort`, `limit`, or `join`.
8. **`pivot` placement.** `pivot` must be the last step in the pipeline (before the sink). Steps after `pivot` are disallowed because the output column schema is unknown.
9. **No empty pipeline.** A plan with zero steps is valid only if the sink is `newSheet` and the source entity is small. The type checker emits `W_TRIVIAL_PLAN`; this is a warning, not an error.
10. **Sink consistency for `existingAnchor`.** `expectedCols` must be provided and must match the statically-known column count of the final table type. For pivot plans, this check is deferred.
