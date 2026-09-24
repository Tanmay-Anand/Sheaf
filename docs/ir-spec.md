# Sheaf IR — Specification v1.2

**Status:** v1.2, from the M3b design review. v1 was frozen at M0. v1.1 added edit plans (§7), the `project` step (§2.9), text expressions (§3.9) and the `template` sink (§5.4), and fixed the wire format (§8). v1.2 splits the contract into three layers (§1), adds stable ids, the envelope and hash (§1.4), `lookup` (§2.10), parameters (§1.5), typed literals (§3.6), explicit positions (§7) and Excel's own semantics (§5.6). Changes after this point require a written design rationale and a version bump.

The JSON in this document uses a short reading notation (`{ "eq": ["status", "returned"] }`). Plans on the wire use the tagged format in §8; `corpus/plans/` holds every corpus plan in that format.

---

## 1. Plan shape

A plan passes through three layers. Each is a separate contract document in `contract/schema/`.

| Layer | Written by | Holds | Never holds |
|---|---|---|---|
| **Unbound plan** (`unbound-plan.schema.json`) | the model | table and column **names**, a sink **intent**, declared parameters | ids, sheet names to create, cell addresses, row numbers, consent |
| **Bound plan** (`plan.schema.json`, inside an envelope) | the binder | the same plan with names normalised, **bindings** to stable ids, a concrete sink | consent, parameter values, provenance |
| **Commit request** (`commit-request.schema.json`) | the user, through the pane | anchor cell, `onDependents`, error-cell exclusion, parameter values, region hashes | anything about the plan's logic |

A model can't write a location or a consent because the schema it is shown has no field for either. A test (`ContractSchemaTest`) walks the generated schema to prove it.

### 1.1 Unbound plan (model output)

```
UnboundPlan := UnboundQuery | UnboundEdit        -- discriminated by "kind"

UnboundQuery := {
  kind:   "query"
  source: string           -- table name; matched ignoring case
  steps:  Step[]           -- ordered pipeline; may be empty
  sink:   SinkIntent
  params: ParamDecl[]      -- §1.5; may be empty
}

UnboundEdit := {                                  -- §7
  kind:   "edit"
  target: string
  ops:    EditOp[]
  params: ParamDecl[]
}

SinkIntent :=
  | { mode: "newSheet", name?: string }   -- a suggested name; the binder makes it valid and unused
  | { mode: "anchor" }                    -- into an existing sheet, at a cell the user chooses at commit
  | { mode: "template", templateId: string }
```

A model's whole answer is a `PlannerResponse` (`planner-response.schema.json`):

```
PlannerResponse :=
  | { response: "plan",    plan: UnboundPlan, annotations: Annotations }
  | { response: "clarify", question: string, options: string[] }
  | { response: "refuse",  understood: string, closestSupported: string[] }

Annotations := {
  assumptions: string[]                                        -- shown in the preview
  columns: { column: string, confidence: number, reason?: string }[]   -- e.g. a template mapping review
}
```

Annotations are never part of the plan or its hash.

### 1.2 Binding

`Binder.bind(UnboundPlan, catalog) → Plan | diagnostics` is pure. It:

- resolves `source`/`target` and every `join`/`lookup` `with` to the catalog entity, ignoring case, and rewrites each to the catalog's spelling (so `orders` and `Orders` bind to the same plan and the same hash). An unknown name is `E_UNKNOWN_SOURCE` with ranked `candidates`;
- records **bindings**: every entity the plan reads, with its id, its worksheet id and the id of each column;
- turns the sink intent into a concrete sink: a `newSheet` name is stripped of `[ ] : * ? / \`, of leading and trailing apostrophes, cut to 31 characters, replaced by `Sheaf result` when empty or `History`, and suffixed ` 2`, ` 3`… until no sheet uses it (ignoring case), starting at `A1`; a template intent takes the template's header and first data rows (`E_TEMPLATE_UNKNOWN` with candidates otherwise).

It never decides what the user must decide.

### 1.3 Bound plan

```
Plan := QueryPlan | EditPlan                     -- discriminated by "kind"

QueryPlan := { kind: "query", source: string, steps: Step[], sink: Sink, params: ParamDecl[], bindings: Bindings }
EditPlan  := { kind: "edit",  target: string, ops: EditOp[], params: ParamDecl[], bindings: Bindings }

Sink :=
  | { mode: "newSheet", name: string, anchor: CellRef }
  | { mode: "anchor" }                                            -- the cell comes from the commit request
  | { mode: "template", templateId: string, headerRow: number, firstDataRow: number }

Bindings      := { entities: EntityBinding[] }
EntityBinding := { name: string, id: string, sheetId: string, columns: { name: string, id: string }[] }
```

Ids come from the catalog: an Excel Table is `t:<table id>`; a detected region is `r:<worksheet id>:<header anchor>` when first seen and keeps that id on a rescan when it moved or grew. A table column is `tc:<column id>`; a region column is `h:<hash of its header>`, so a renamed header gets a new id. Sheets are referenced by `worksheet.id`, which survives renames.

### 1.4 Envelope, hash and provenance

```
PlanEnvelope := { irVersion: "1.2", plan: Plan }
planHash     := "sha256:" + hex(SHA-256(JCS(PlanEnvelope)))
PlanMeta     := { modelId: string, promptVersion: string, generatedAt: datetime }   -- outside the hash
```

JCS is RFC 8785: members sorted by UTF-16 code units, no whitespace, strings escaped as `JSON.stringify` does, numbers written as ECMAScript writes a double. Absent optional fields are omitted, not `null`. So the same logical plan has the same hash whatever model wrote it, when, and in what key order (a property test shuffles keys). `POST /api/plan` returns `{ envelope, planHash, meta }`.

### 1.5 Parameters

```
ParamDecl := { name: string, valueType: ValueType }
ValueType := "string" | "number" | "boolean" | "date" | "datetime"
```

A plan declares what it needs at run time ("as of" dates, a region to report on) and refers to it with `{ type: "param", name }` wherever a literal may appear. A reference to an undeclared parameter is `E_UNKNOWN_PARAM`. A parameter is typed like a literal of its `valueType`. Its value arrives in the commit request, never in the plan, so re-running a saved plan with a new date keeps its hash.

### 1.6 Commit request

```
CommitRequest := {
  planHash:          string                            -- must equal the previewed plan's (E_COMMIT_PLAN_MISMATCH)
  anchor?:           { sheetId: string, address: CellRef }   -- required for an anchor sink
  onDependents:      "block" | "convertToValues"
  excludeErrorCells: boolean
  params:            { name: string, value: Value }[]
  regionHashes:      { entityId: string, contentHash: string }[]   -- the committer refuses if the workbook changed
}
```

`ConsentCheck.check(plan, planHash, checkResult, request)` refuses a commit that doesn't give the plan what it needs: `E_COMMIT_PLAN_MISMATCH`, `E_SINK_ANCHOR_MISSING`/`E_SINK_ANCHOR_INVALID`, `E_DROP_HAS_DEPENDENTS` (the plan removes something still read and the user chose `block`), `E_PARAM_MISSING`, `E_PARAM_TYPE`, `E_UNKNOWN_PARAM`.

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
  where: Predicate?            -- optional row filter for this measure only; required for countIf.
                               -- On sum/avg/min/max it gives the sumIf pattern, and the
                               -- measure becomes nullable (a group may have no matching rows).
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
- Sorting a `categorical<D>` column depends on the domain's ordering (§5.1). **Nominal** domains (regions, names) sort alphabetically. **Declared** ordinal domains (priority: low, medium, high, critical) sort in their declared order. A domain the semantic model marks ordinal **without** a declared order is refused with `E_SORT_CATEGORICAL_UNORDERED`, because sorting it alphabetically would be silently wrong (high < low < medium). *(v1.1: v1 refused every unordered categorical, which contradicted corpus Q4 and Q14.)*
- Sorting by a nullable column places nulls last (ascending) or nulls first (descending). This is not configurable in v1.

---

### 2.5 `limit`

Returns the first `n` rows. Always follows a `sort` to be meaningful; the type checker emits a warning (not an error) if `limit` is not immediately preceded by `sort`.

```
limit(T) → T

fields:
  n: integer   -- required; must be > 0 (an absent n is E_PARSE_MISSING_FIELD, never read as 0)
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
- Output column names are the union of both schemas. A right-hand name that collides with a left-hand one **ignoring case** is renamed `name (Entity)`, then `name (Entity) 2`… if that is taken too: if `Orders` and `Regions` both have `name`, the output has `name` and `name (Regions)`. The left side keeps its names, so steps written before the join stay valid. *(v1.2; v1.1 prefixed both sides.)*

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
- `cols` column must exist in `T` and be of type `categorical<D>` or `string`, **or** a `number` with a static bound on its distinct values. Only date parts have one: `quarterOf` (4), `monthOf` (12), `weekOf` (53), `dayOf` (31), `dayOfWeek` (7). Pivoting on other numbers, dates or booleans is disallowed (`E_PIVOT_NON_CATEGORICAL_COLS`). *(v1.1: needed by corpus Q6, which pivots on `quarterOf(order_date)`.)*
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

### 2.9 `project` *(v1.1)*

Selects, renames, reorders and computes columns in one step. The output is **exactly** the listed columns, in order.

```
project(T) → { as₁: type(expr₁), as₂: type(expr₂), ... }

fields:
  columns: [{ as: string, expr: Expr }]    -- at least one
```

**Constraints:**
- `as` names must be distinct (`E_DUPLICATE_OUTPUT_COLUMN`).
- Every expression is typed against `T`. A plain column reference carries its type, nullability, cardinality and origin through unchanged.
- A null literal (`{ "lit": null }`) is allowed and means "leave this column empty"; it types as a nullable string. Before a `template` sink, it marks the template column as deliberately unmapped (`W_UNMAPPED_TEMPLATE_COLUMN`).
- Columns of `T` that are not listed no longer exist after the step (`E_COLUMN_NOT_IN_OUTPUT` if referenced later).
- `sumAll` is allowed in a `project` that immediately follows an `aggregate`, as in `derive`.

**Why it exists:** template fill needs an output whose columns are someone else's headers, in someone else's order. See `ir-decisions` (v1.1).

---

### 2.10 `lookup` *(v1.2)*

Adds columns from another table by key, like `XLOOKUP`: every row stays, and at most one row matches.

```
lookup(T1, T2) → T1 ∪ take(T2)

fields:
  with: string       -- entity name
  on:   JoinKey      -- left: column in T1; right: column in T2
  take: string[]?    -- T2 columns to add; absent = every T2 column except the key
```

**Constraints:**
- The pair must be an approved join edge, as for `join` (`E_UNAPPROVED_JOIN`).
- `on.right` must be a **key** of `T2`: unique and never blank in the catalog (`E_LOOKUP_KEY_NOT_UNIQUE`, with the table's keys as candidates). The evaluator checks again on the actual values, since data can change between cataloguing and running.
- Taken columns are always nullable (an unmatched row gets blanks). Name collisions follow `join`.

**Why not `join`:** a join on a non-unique key silently multiplies rows. Most "bring in the region name" requests mean a many-to-one lookup, and saying so lets the checker refuse the dangerous case.

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
| `{ "isoWeekOf": col }` | `date \| datetime` | `number` (ISO 8601 week 1–53, Excel's `ISOWEEKNUM`) |
| `{ "dayOf": col }` | `date \| datetime` | `number` (1–31) |
| `{ "isoDayOfWeek": col }` | `date \| datetime` | `number` (1–7, Monday = 1, Excel's `WEEKDAY(d, 2)`) |

*(v1.2 renamed `weekOf` and `dayOfWeek`: Excel's `WEEKNUM` and `WEEKDAY` default to other conventions, and the name now says which one is meant.)*

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

### 3.6 Literals *(typed in v1.2)*

```json
{ "type": "lit", "value": 0 }
{ "type": "lit", "value": "returned" }
{ "type": "lit", "value": null }
{ "type": "lit", "value": "2025-01-01", "valueType": "date" }
{ "type": "param", "name": "asOf" }
```

A literal is what its JSON value is (number, string, boolean or null) unless `valueType` says otherwise. A string is a date **only** with `valueType: "date"` (or `"datetime"`), and must then be a valid ISO date (`E_INVALID_ARGUMENT` for `2025-13-01`). Without it, `"2025-01-01"` is text, and comparing it with a date column is `E_TYPE_INCOMPARABLE`. Guessing from the shape of a string is how spreadsheets turn part numbers into dates.

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

### 3.9 Text *(v1.1)*

| Expression | Input | Output | Notes |
|---|---|---|---|
| `{ "concat": { "parts": [e…], "sep": s?, "skipNulls": b? } }` | any types | `string` | A null part counts as empty. With `skipNulls`, null and blank parts are dropped together with their separator. Null only when every part is nullable. |
| `{ "trim": e }` | `string`, `categorical` | `string` | |
| `{ "upper": e }`, `{ "lower": e }` | `string`, `categorical` | `string` | |
| `{ "splitPart": { "arg": e, "sep": s, "index": n } }` | `string`, `categorical` | `string?` | 1-based; `sep` is a non-empty literal; null when the piece doesn't exist. |
| `{ "toText": { "arg": e, "pattern": p } }` | `number`, `currency`, `percent`, `date`, `datetime` | `string` | `p` from a fixed whitelist: dates `yyyy-mm-dd`, `dd/mm/yyyy`, `mm/dd/yyyy`, `yyyy-mm`, `mmm yyyy`, `yyyy`; numbers `0`, `0.00`, `#,##0`, `#,##0.00`, `0%`, `0.0%`. Allowed only inside a `concat`, or as the whole value of a text-formatted (`@`) template column (`E_TOTEXT_OUTSIDE_CONCAT`, v1.2). |

The arguments are expressions, not column names, so text functions compose: `trim(splitPart(email, "@", 2))`. Invalid separators, indexes and patterns are `E_INVALID_ARGUMENT`; non-text arguments are `E_TYPE_MISMATCH`.

**Still rejected:** `regex`, computed-offset `substring`, and user-supplied format strings.

**Why `toText` is fenced (v1.2):** written to a cell on its own, a formatted number or date is text that looks like a value: it sorts wrongly, `SUM` skips it, and Excel may re-parse it on edit. Formatting belongs to the cell's number format. Inside a sentence (`concat`) or into a column the template itself formats as text, text is what is wanted.

### 3.10 Case with text outputs *(v1.1)*

When every `then` and the `else` of a `case` are text literals (or null), the result is a `categorical` whose domain is exactly those literals. That makes value mappings statically checkable against a template's validation list, for example `active → Active`.

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
  | string                                   -- reading notation for a column reference
  | { "col": string }                        -- column reference
  | { "lit": Value, "valueType"?: ValueType }   -- literal (§3.6)
  | { "param": string }                      -- declared parameter (§1.5)

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

**Literals adapt (v1.1):** a numeric literal is unit-less and compares with any numeric type (`gt(amount, 1000)` against `currency:INR`). In `add`/`sub` it takes the other operand's type. A string literal compares with text and categorical columns only; a date literal needs `valueType: "date"` (v1.2, §3.6).

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

**Categorical ordering (v1.1).** Every `categorical<D>` domain carries an ordering:

- `none`: nominal values; sort alphabetically; `lt`/`gt` refused.
- `declared`: an ordinal domain whose order is given; sort and `lt`/`gt` use it.
- `missing`: an ordinal domain with no declared order yet; sorting is refused (`E_SORT_CATEGORICAL_UNORDERED`).

`bucket` outputs are `declared` (the labels are in break order). `case` outputs are `none`.

Every scalar type may be nullable: `T | null`. Nullability is tracked per-column in the table type. The type checker propagates nullability through every operator.

### 5.2 Table type

A table type is an ordered record of named column types:

```
TableType := { colName₁: ScalarType₁, colName₂: ScalarType₂, ... }
```

Ordered because the evaluator and committer preserve column order and the sink compatibility check is order-sensitive for ranged writes.

### 5.3 Type checking algorithm

Type checking is a left-fold over the pipeline:

1. **Resolve source.** Look up `source` (already bound, §1.2) in the catalog. The entity's columns, as inferred by the profiler and confirmed in the semantic model, give the initial `TableType`.
2. **Fold over steps.** For each step, apply its operator signature to the current `TableType` to produce the next `TableType`, or emit a `Diagnostic` and halt.
3. **Check sink.** Verify the final `TableType` is compatible with the declared `Sink`.

The type checker is a pure function: `(Plan, TypeEnvironment) → CheckResult { output, diagnostics, impact }`. `TypeChecker.parseBindAndCheck` runs the whole chain from model JSON: parse → bind → check → envelope and hash.

### 5.4 Sink compatibility

| Sink mode | Compatibility check |
|---|---|
| `newSheet` | Creates a new sheet, so it can't overwrite data. The name must be a valid Excel sheet name (`E_SINK_SHEET_NAME_INVALID`: at most 31 characters, none of `[ ] : * ? / \`, not starting or ending with `'`, not `History`) that no sheet uses, ignoring case (`E_SINK_SHEET_NAME_TAKEN`); the anchor is one cell (`E_SINK_ANCHOR_INVALID`). The binder only produces names that pass. |
| `anchor` *(v1.2, was `existingAnchor`)* | The plan carries no location: the user picks the top-left cell at commit (`E_SINK_ANCHOR_MISSING`/`E_SINK_ANCHOR_INVALID` there). The committer (M4) refuses to overwrite non-empty cells outside what the preview showed. A plan with no steps can't write here (`E_EMPTY_PIPELINE_WRITE`). |
| `template` *(v1.1)* | The template is named by id and must be an imported template (`E_TEMPLATE_UNKNOWN`). The final column names must equal the template's **non-formula** header columns, in the same order (`E_TEMPLATE_COLUMN_MISSING`, `E_TEMPLATE_EXTRA_COLUMN`, `E_TEMPLATE_COLUMN_ORDER`); formula columns fill themselves and are never written. Each column's type must suit the template column's number format (`E_TEMPLATE_TYPE_INCOMPATIBLE`; a text or General column accepts anything). A categorical output with known members must stay inside the template column's validation list (`E_TEMPLATE_VALUE_NOT_IN_LIST`); members unknown until run time are checked at evaluation. A pivot can't feed a template (`E_TEMPLATE_DYNAMIC_COLUMNS`). |

### 5.5 Numeric coercions (allowed)

The only implicit coercions in the type system:
- `date` → `datetime`: allowed in comparisons only; a `date` value is treated as midnight on that date.
- `categorical<D>` → `string`: allowed in comparisons and `sort`; the string value is the domain member's canonical name.

No other implicit coercions. `number` and `currency:X` are not interchangeable without an explicit `mul` or `div` expression.

### 5.6 Excel semantics *(v1.2)*

- **Names ignore case,** as Excel's do. Every lookup of a table, column, sheet or template matches case-insensitively, and every "is this name free?" check (derive, measures, project, joins, edits, sheet names) refuses a name that differs from an existing one only in case, so Excel never gets the chance to rename a column to `Name2`. The collation is `toLowerCase(Locale.ROOT)`; output keeps the spelling the plan or catalog used.
- **Blank is `""`.** `isNull` matches empty cells and cells holding an empty (or all-space) string alike, as Excel's `COUNTBLANK` does. The catalog already profiles both as blank; the evaluator (M4) applies the same rule.
- **Error cells.** A column the catalog marks `mayContainErrors` warns when aggregated (`W_MAY_CONTAIN_ERRORS`): one `#N/A` makes Excel's `SUM` an error. The commit request's `excludeErrorCells` decides whether those rows are skipped or the run stops (M4).
- **Numbers stored as text.** A numeric column the catalog marks `numbersStoredAsText` warns when aggregated (`W_NUMBERS_STORED_AS_TEXT`): Excel's `SUM` and `AVERAGE` skip text, so totals come out short.
- **Date system.** The catalog records whether the workbook counts dates from 1900 or 1904 (inferred from a displayed date, because Office.js exposes the setting only in preview). The evaluator (M4) converts date values with it when writing.

---

## 6. Pipeline validation invariants

These invariants are checked as part of type checking. Violation of any invariant is a type error.

1. **Source exists.** `source` names a catalog entity (checked when binding).
2. **No forward references.** A step may only reference column names that exist in the table type produced by all preceding steps.
3. **No post-aggregate column references (except in groupBy and measures).** After an `aggregate` step, only the declared `groupBy` columns and measure output columns exist. Referencing a pre-aggregate column in a subsequent step is `E_COLUMN_NOT_IN_OUTPUT`.
4. **`limit` preceded by `sort`.** Not a type error but a warning (`W_LIMIT_WITHOUT_SORT`). The type checker emits the warning; the plan is still valid.
5. **Join edge approved.** Every `join` step must traverse an edge present in the semantic model's join graph with `approved: true`.
6. **`sumAll` placement.** `sumAll` in a `derive` expression is only valid if the immediately preceding step is `aggregate`.
7. **`periodCompare` placement.** `periodCompare` may only appear as the first step after any `filter` steps. It cannot appear after `derive`, `aggregate`, `sort`, `limit`, or `join`.
8. **`pivot` placement.** `pivot` must be the last step in the pipeline (before the sink). Steps after `pivot` are disallowed because the output column schema is unknown.
9. **No empty pipeline.** A plan with zero steps into a `newSheet` copies the table, with `W_TRIVIAL_PLAN`; into an `anchor` sink it is `E_EMPTY_PIPELINE_WRITE`.
10. **Parameters are declared.** Every `param` reference names a declared parameter (`E_UNKNOWN_PARAM`).

---

## 7. Edit plans *(v1.1)*

An edit plan changes an existing entity in place. Its operations address **columns and row predicates, never cells**.

```
EditOp :=
  | { op: "addColumn",    as: string, expr: Expr, position: Position }
  | { op: "setColumn",    col: string, expr: Expr, where?: Predicate }
  | { op: "dropColumn",   col: string }
  | { op: "renameColumn", col: string, to: string }
  | { op: "moveColumn",   col: string, position: Position }
  | { op: "dropRows",     where: Predicate }

Position := { at: "first" } | { at: "last" } | { at: "after", column: string }   -- required (v1.2)
```

*(v1.1 had an optional `after` whose absence meant "last" for `addColumn` but "first" for `moveColumn`. v1.2 makes the position explicit and required.)*

**Type checking** is a left fold over `ops`, like a query pipeline. Each column carries its **origin** (the target column it still is), so renames and moves keep a column's identity. That way `renameColumn(lifetime_value → ltv)` followed by `dropColumn(ltv)` still finds `lifetime_value`'s dependents.

| Operation | Rules |
|---|---|
| `addColumn` | `as` is new, ignoring case (`E_EDIT_COLUMN_COLLISION`); an `after` column exists (`E_EDIT_UNKNOWN_COLUMN`); `expr` typed against the current table. `sumAll` is not allowed. |
| `setColumn` | `col` exists; the value is assignable to the column's type (`E_TYPE_MISMATCH`, `E_CURRENCY_UNIT_MISMATCH`); overwriting a formula column warns (`W_OVERWRITES_FORMULAS`). |
| `dropColumn` | `col` exists. Everything in the workbook that reads the column (formulas, names, charts, pivots, validation, conditional formats), whatever its class, needs the user's consent: the plan stays valid, `W_DEPENDENTS_NEED_CONSENT` names each one, and the impact lists them with cause `columnRemoved`. The user decides at commit (`onDependents`, §1.6). Dropping the last column is `E_EDIT_DROPS_EVERY_COLUMN`. |
| `renameColumn` | `to` is new, ignoring case (`E_RENAME_COLLISION`); a rename that only changes case is allowed. A header rename doesn't break A1 references, and Excel updates structured references itself. |
| `moveColumn` | an `after` column exists and is not `col` (`E_INVALID_ARGUMENT`). |
| `dropRows` | `where` is typed. The only operation that changes row count. Dependents whose references name **fixed rows** (`=C5`, `B2:B9` inside the data, `[@Col]`) need consent with cause `rowsRemoved`; references that follow the whole column (`C:C`, the full data range) are meant to change and don't. |

**Dependent classes (catalog, v1.2).** Each dependent records how its reference covers the table: `exclusive` (part of one column), `spanning` (several of its columns, e.g. `SUM(A:D)`, which silently changes when one goes), `wholeColumn` (`C:C`) or `wholeRow` (`5:5`), plus whether it names `fixedRows`.

**Consent is not in the plan (v1.2).** v1.1 had `onDependents` on the edit plan, so the model decided whether formulas could be broken. It is now the user's choice in the commit request, made after the preview names every affected dependent.

An empty `ops` list is `E_EDIT_EMPTY`. When the workbook has references Sheaf can't follow and the plan removes columns or rows, `W_UNTRACEABLE_REFERENCES` is added.

**Merge** is not an operation. "Merge A and B into C" is `addColumn(C, concat([A, B], " "))`, then `dropColumn(A)` and `dropColumn(B)` when the sources should go.

**Impact report.** Type checking an edit plan also returns what it changes: columns `added`, `removed`, `overwritten`, `renamed` and `moved` (all by original name), whether it `removesRows`, which `formulaColumnsOverwritten`, the `dependentsNeedingConsent` (each with its column, dependent and cause), and whether `untraceableReferences` exist. Row counts are filled in by evaluation (M6).

---

## 8. Wire format *(v1.1, extended in v1.2)*

Plans travel as the tagged JSON the generated contract schemas describe (`unbound-plan`, `planner-response`, `plan` (the envelope), `commit-request` and `catalog` in `contract/schema/`). Every variant carries a discriminator: `kind` for plans, `op` for steps, predicates and edit operations, `type` for expressions and value references, `mode` for sinks and sink intents, `at` for positions, `response` for planner responses. Column references and literals are always explicit.

| Reading notation | Wire format |
|---|---|
| `{ "ne": ["status", "returned"] }` | `{ "op": "ne", "left": { "type": "col", "col": "status" }, "right": { "type": "lit", "value": "returned" } }` |
| `{ "monthOf": "order_date" }` | `{ "type": "monthOf", "col": "order_date" }` |
| `{ "pct": ["revenue", { "sumAll": "revenue" }] }` | `{ "type": "pct", "numerator": { "type": "col", "col": "revenue" }, "denominator": { "type": "sumAll", "col": "revenue" } }` |

**Why one tagged format:** the reading notation is ambiguous. In `["status", "returned"]` nothing says the first string is a column and the second a value. The tagged format is generated from the Java records, so the schema, the TypeScript types and the parser can't drift apart, and a model's structured output can be validated against the same schema in M5.

**Parsing** rejects unknown fields and variants instead of ignoring them (`E_PARSE_UNKNOWN_FIELD`, with the allowed fields as candidates; `E_PARSE_UNKNOWN_VARIANT`; `E_PARSE_INVALID_VALUE`). A required field that is absent is `E_PARSE_MISSING_FIELD`, numbers included: numeric fields are boxed, so an absent `limit.n` is reported, never read as 0 (v1.2). A `case` without `else` is `E_CASE_NO_ELSE`. Text that isn't JSON is `E_PARSE_INVALID_JSON`. A location (`range`) or consent (`onDependents`) written into an unbound plan is `E_PARSE_UNKNOWN_FIELD`.

**Diagnostics (v1.2)** carry a JSON Pointer to the offending field (`/steps/0/predicate/left/col`), the step index and operator, a message and a repair hint, and context: `received`, `expected`/`actual`, and for unknown names up to five `candidates`, closest first. See `docs/diagnostics.md`.
