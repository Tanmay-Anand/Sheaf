# Diagnostic Taxonomy — v1

Every way a plan can fail type-checking, with structured error codes, the step context each applies to, and the repair hint surfaced to the LLM (not the user). Written while the type rules are fresh — retrofitting good diagnostics never happens.

**Format:**
- `code` — machine-readable identifier; prefix `E_` for errors, `W_` for warnings.
- `step` — the step index (0-based) at which the error occurs, or `null` for plan-level errors.
- `message` — human-readable description (shown to the user in the refusal message).
- `hint` — repair signal sent back to the model in the repair loop. Specific and actionable.

---

## Plan-level errors

### `E_UNKNOWN_SOURCE`

```json
{
  "code":    "E_UNKNOWN_SOURCE",
  "step":    null,
  "message": "Entity 'Widgets' is not in the semantic model.",
  "hint":    "Available entities: Orders, Regions, Budget, Tasks, Inventory. Use one of these as the source.",
  "context": { "received": "Widgets" }
}
```

**When:** `source.ref` does not match any approved entity in the semantic model.

---

### `E_SINK_NEW_SHEET_NAME_MISSING`

```json
{
  "code":    "E_SINK_NEW_SHEET_NAME_MISSING",
  "step":    null,
  "message": "A newSheet sink requires a name for the new sheet.",
  "hint":    "Add a 'name' field to the sink, e.g. { \"mode\": \"newSheet\", \"name\": \"Analysis\", \"anchor\": \"A1\" }."
}
```

**When:** `sink.mode` is `"newSheet"` but `sink.name` is absent or empty.

---

### `E_SINK_ANCHOR_INVALID`

```json
{
  "code":    "E_SINK_ANCHOR_INVALID",
  "step":    null,
  "message": "Sink anchor 'Z999' is not a valid cell reference.",
  "hint":    "Use a standard A1-notation cell reference, e.g. \"A1\"."
}
```

**When:** `sink.anchor` or `sink.range` is not a valid cell or range reference.

---

### `E_EMPTY_PIPELINE_WRITE`

```json
{
  "code":    "E_EMPTY_PIPELINE_WRITE",
  "step":    null,
  "message": "A plan with no steps writes the entire source entity to the sink. Confirm this is intended.",
  "hint":    "If you meant to filter or transform the data, add at least one step. If you genuinely want to write the full entity, this plan is valid — the user will see the full row count in the preview."
}
```

**When:** `steps` is empty and `sink.mode` is `"existingAnchor"`. Treated as an error because overwriting an existing range with all rows is almost certainly unintentional.

---

## Column reference errors

### `E_UNKNOWN_COLUMN`

```json
{
  "code":     "E_UNKNOWN_COLUMN",
  "step":     2,
  "operator": "aggregate",
  "message":  "Column 'amt' does not exist. Did you mean 'amount'?",
  "hint":     "Available columns at this step: order_id (string), order_date (date), region (categorical), channel (categorical), product_category (categorical), product_name (string), quantity (number), unit_price (currency:INR), amount (currency:INR), discount_pct (percent), status (categorical), customer_type (categorical).",
  "context":  { "received": "amt", "available": ["order_id", "order_date", "region", "channel", "product_category", "product_name", "quantity", "unit_price", "amount", "discount_pct", "status", "customer_type"] }
}
```

**When:** any operator references a column name that does not exist in the current table type at that step.

**Hint strategy:** include the full list of available columns at the failing step, and a nearest-neighbour suggestion (edit distance ≤ 2) when available.

---

### `E_COLUMN_NOT_IN_OUTPUT`

```json
{
  "code":     "E_COLUMN_NOT_IN_OUTPUT",
  "step":     3,
  "operator": "sort",
  "message":  "Column 'order_date' was removed by the preceding aggregate step and cannot be referenced here.",
  "hint":     "After aggregating, only groupBy columns and declared measures remain. Available columns after the aggregate: region (categorical), revenue (currency:INR).",
  "context":  { "received": "order_date", "removedBy": 2 }
}
```

**When:** a step references a column that existed in a prior step but was eliminated by an intervening operator (most commonly: referencing a pre-aggregate column after an `aggregate` step).

---

### `E_DERIVE_COLUMN_COLLISION`

```json
{
  "code":     "E_DERIVE_COLUMN_COLLISION",
  "step":     1,
  "operator": "derive",
  "message":  "Column name 'amount' already exists. Derived columns must have unique names.",
  "hint":     "Choose a different name for the derived column, e.g. 'adjusted_amount' or 'net_amount'.",
  "context":  { "received": "amount", "existingColumns": ["order_id", "order_date", "region", "channel", "product_category", "product_name", "quantity", "unit_price", "amount", "discount_pct", "status", "customer_type"] }
}
```

**When:** `derive.as` is the same as an existing column name in the current table type.

---

## Type mismatch errors

### `E_TYPE_MISMATCH`

```json
{
  "code":     "E_TYPE_MISMATCH",
  "step":     2,
  "operator": "aggregate",
  "field":    "measures[0]",
  "message":  "sum expects a numeric column (number, currency, or percent), but 'region' is categorical.",
  "hint":     "sum requires a numeric column. Available numeric columns at this step: quantity (number), unit_price (currency:INR), amount (currency:INR), discount_pct (percent).",
  "context":  {
    "fn":       "sum",
    "of":       "region",
    "expected": "number | currency:* | percent",
    "actual":   "categorical(region)"
  }
}
```

**When:** an aggregate function receives a column whose type does not satisfy the function's input constraint.

---

### `E_TYPE_INCOMPARABLE`

```json
{
  "code":     "E_TYPE_INCOMPARABLE",
  "step":     0,
  "operator": "filter",
  "message":  "Cannot compare 'amount' (currency:INR) to 'status' (categorical). These types are not comparable.",
  "hint":     "Compare currency columns only to other currency:INR values or numeric literals. Compare categorical columns to string literals or other categorical columns with the same domain.",
  "context":  {
    "left":  { "col": "amount", "type": "currency:INR" },
    "right": { "col": "status", "type": "categorical" }
  }
}
```

**When:** a predicate or arithmetic expression compares two operands of incompatible types.

---

### `E_CURRENCY_UNIT_MISMATCH`

```json
{
  "code":     "E_CURRENCY_UNIT_MISMATCH",
  "step":     3,
  "operator": "derive",
  "message":  "Cannot add currency:INR and currency:USD. Currency units must match.",
  "hint":     "Both operands must have the same currency unit. The 'amount' column is currency:INR. If you need to convert, apply a conversion factor using mul() before adding.",
  "context":  {
    "left":  { "col": "amount",    "type": "currency:INR" },
    "right": { "col": "usd_price", "type": "currency:USD" }
  }
}
```

**When:** arithmetic or comparison involves two `currency` columns with different units.

---

### `E_ORDER_COMPARISON_NON_ORDERED`

```json
{
  "code":     "E_ORDER_COMPARISON_NON_ORDERED",
  "step":     0,
  "operator": "filter",
  "message":  "Cannot use 'lt' (less-than) on column 'channel' (categorical). Ordering comparisons require an ordered type.",
  "hint":     "Use 'eq' or 'ne' for categorical columns, or 'in' / 'notIn' for membership tests. Ordering comparisons (lt, lte, gt, gte) are valid for number, currency, percent, date, and datetime columns.",
  "context":  { "predicate": "lt", "col": "channel", "type": "categorical" }
}
```

**When:** `lt`, `lte`, `gt`, or `gte` is applied to a `string`, `boolean`, or `categorical` column without a declared domain order.

---

## Date and time errors

### `E_DATE_EXTRACT_NON_DATE`

```json
{
  "code":     "E_DATE_EXTRACT_NON_DATE",
  "step":     1,
  "operator": "derive",
  "message":  "monthOf requires a date or datetime column, but 'channel' is categorical.",
  "hint":     "Available date/datetime columns at this step: order_date (date).",
  "context":  { "fn": "monthOf", "col": "channel", "type": "categorical" }
}
```

**When:** a date extraction function (`yearOf`, `quarterOf`, `monthOf`, `weekOf`, `dayOf`, `dayOfWeek`) is applied to a column that is not `date` or `datetime`.

---

### `E_PERIOD_COMPARE_NO_TIME_DIM`

```json
{
  "code":     "E_PERIOD_COMPARE_NO_TIME_DIM",
  "step":     1,
  "operator": "periodCompare",
  "message":  "Time dimension 'sale_date' is not in the semantic model for entity 'Orders'.",
  "hint":     "Available time dimensions for Orders: order_date (grains: day, week, month, quarter, year).",
  "context":  { "received": "sale_date" }
}
```

**When:** `periodCompare.timeDim` does not match an approved time dimension on the source entity.

---

### `E_PERIOD_COMPARE_UNSUPPORTED_GRAIN`

```json
{
  "code":     "E_PERIOD_COMPARE_UNSUPPORTED_GRAIN",
  "step":     1,
  "operator": "periodCompare",
  "message":  "Grain 'day' is not in the supported grains for time dimension 'order_date'.",
  "hint":     "Supported grains for order_date: week, month, quarter, year.",
  "context":  { "grain": "day", "supported": ["week", "month", "quarter", "year"] }
}
```

**When:** `periodCompare.grain` is not listed in the time dimension's declared `grains`.

---

### `E_PERIOD_COMPARE_INVALID_PERIOD`

```json
{
  "code":     "E_PERIOD_COMPARE_INVALID_PERIOD",
  "step":     1,
  "operator": "periodCompare",
  "message":  "Period literal '2025-Q5' is not valid for grain 'quarter'.",
  "hint":     "Valid quarter literals have the form 'YYYY-Q#' where # is 1–4, e.g. '2025-Q1'.",
  "context":  { "received": "2025-Q5", "grain": "quarter" }
}
```

**When:** `periodCompare.current` or `periodCompare.prior` is not a syntactically valid period literal for the declared grain.

---

### `E_PERIOD_COMPARE_PLACEMENT`

```json
{
  "code":     "E_PERIOD_COMPARE_PLACEMENT",
  "step":     2,
  "operator": "periodCompare",
  "message":  "periodCompare must be the first non-filter step in the pipeline.",
  "hint":     "Move the periodCompare step to the start of the pipeline. You may precede it with filter steps only."
}
```

**When:** `periodCompare` appears after a `derive`, `aggregate`, `sort`, `limit`, or `join` step.

---

## Join errors

### `E_UNAPPROVED_JOIN`

```json
{
  "code":     "E_UNAPPROVED_JOIN",
  "step":     2,
  "operator": "join",
  "message":  "There is no approved join path between 'Orders' and 'Tasks'.",
  "hint":     "Approved join paths: Orders → Regions (on Orders.region = Regions.code). To join Orders and Tasks, a join edge must be declared and approved in the semantic model.",
  "context":  {
    "source":     "Orders",
    "joinTarget": "Tasks",
    "approvedPaths": [{ "from": "Orders", "to": "Regions" }]
  }
}
```

**When:** the `join.with` entity has no approved edge to (or from) the current source entity in the join graph.

---

### `E_JOIN_KEY_TYPE_MISMATCH`

```json
{
  "code":     "E_JOIN_KEY_TYPE_MISMATCH",
  "step":     2,
  "operator": "join",
  "message":  "Join key type mismatch: Orders.order_id (string) is not comparable to Regions.code (categorical).",
  "hint":     "The join key columns must have comparable types. The approved join edge is Orders.region (categorical) = Regions.code (categorical). Use 'region' as the left key.",
  "context":  {
    "left":  { "col": "order_id",    "type": "string" },
    "right": { "col": "code",        "type": "categorical" }
  }
}
```

**When:** the types of the left and right join key columns are not comparable.

---

### `E_JOIN_ENTITY_UNKNOWN`

```json
{
  "code":     "E_JOIN_ENTITY_UNKNOWN",
  "step":     2,
  "operator": "join",
  "message":  "Entity 'RegionMaster' is not in the semantic model.",
  "hint":     "Available entities: Orders, Regions, Budget, Tasks, Inventory.",
  "context":  { "received": "RegionMaster" }
}
```

**When:** `join.with` references an entity name not in the semantic model.

---

## Pivot errors

### `E_PIVOT_VALUE_NOT_NUMERIC`

```json
{
  "code":     "E_PIVOT_VALUE_NOT_NUMERIC",
  "step":     3,
  "operator": "pivot",
  "message":  "Pivot values must be numeric, but 'product_name' is string.",
  "hint":     "The 'values' column must be number, currency, or percent. Available numeric columns at this step: revenue (currency:INR).",
  "context":  { "col": "product_name", "type": "string" }
}
```

**When:** `pivot.values` references a column that is not `number`, `currency:*`, or `percent`.

---

### `E_PIVOT_NON_CATEGORICAL_COLS`

```json
{
  "code":     "E_PIVOT_NON_CATEGORICAL_COLS",
  "step":     3,
  "operator": "pivot",
  "message":  "Pivot column 'amount' is currency:INR. Only categorical or string columns can be spread into pivot columns.",
  "hint":     "The 'cols' field specifies which column's distinct values become headers. Use a categorical or string column, e.g. 'quarter' or 'channel'.",
  "context":  { "col": "amount", "type": "currency:INR" }
}
```

**When:** `pivot.cols` references a column that is not `categorical` or `string`.

---

### `E_PIVOT_HIGH_CARDINALITY`

```json
{
  "code":     "E_PIVOT_HIGH_CARDINALITY",
  "step":     3,
  "operator": "pivot",
  "message":  "Column 'product_name' has cardinality 47 in the semantic model, which would produce 47 output columns. Maximum allowed is 50.",
  "hint":     "Use a column with fewer distinct values as the pivot column, or aggregate to a lower-cardinality grouping first. Available low-cardinality columns: channel (4), product_category (5), region (6), status (3).",
  "context":  {
    "col":        "product_name",
    "cardinality": 47,
    "limit":       50
  }
}
```

**When:** the semantic model reports the `pivot.cols` column's cardinality exceeds the guard threshold (50).

---

### `E_PIVOT_NOT_TERMINAL`

```json
{
  "code":     "E_PIVOT_NOT_TERMINAL",
  "step":     3,
  "operator": "pivot",
  "message":  "pivot must be the last step in the pipeline. Steps after pivot are not allowed because the output column schema is unknown until run time.",
  "hint":     "Remove any steps after the pivot step, or move the transformation before the pivot."
}
```

**When:** there are steps in the pipeline after a `pivot` step.

---

## Derive / expression errors

### `E_SUMALL_INVALID_PLACEMENT`

```json
{
  "code":     "E_SUMALL_INVALID_PLACEMENT",
  "step":     1,
  "operator": "derive",
  "message":  "sumAll can only be used in a derive step that immediately follows an aggregate step.",
  "hint":     "sumAll references the grand total of an aggregated measure column. Move this derive step to immediately follow the aggregate step.",
  "context":  { "precedingStep": { "op": "filter" } }
}
```

**When:** a `sumAll` expression appears in a `derive` step whose immediately preceding step is not `aggregate`.

---

### `E_SUMALL_UNKNOWN_COLUMN`

```json
{
  "code":     "E_SUMALL_UNKNOWN_COLUMN",
  "step":     2,
  "operator": "derive",
  "message":  "sumAll references 'total', which is not a measure column in the preceding aggregate.",
  "hint":     "Available measure columns from the preceding aggregate: revenue (currency:INR).",
  "context":  { "received": "total", "available": ["revenue"] }
}
```

**When:** `sumAll(colName)` references a column name that is not a declared measure in the immediately preceding `aggregate` step.

---

### `E_BUCKET_BREAK_COUNT`

```json
{
  "code":     "E_BUCKET_BREAK_COUNT",
  "step":     2,
  "operator": "derive",
  "message":  "bucket has 3 breaks but 3 labels. Labels must have exactly one more entry than breaks.",
  "hint":     "With 3 breaks [0, 1000, 5000], you need 4 labels: one for each interval — below 0, 0–1000, 1000–5000, above 5000.",
  "context":  { "breaks": 3, "labels": 3, "expected_labels": 4 }
}
```

**When:** `bucket.labels.length !== bucket.breaks.length + 1`.

---

### `E_CASE_NO_ELSE`

```json
{
  "code":     "E_CASE_NO_ELSE",
  "step":     2,
  "operator": "derive",
  "message":  "case expressions must have an else clause.",
  "hint":     "Add an 'else' value to handle rows that don't match any when condition. Use { \"lit\": null } if null is acceptable for unmatched rows."
}
```

**When:** a `case` expression is missing its `else` field.

---

## Sort errors

### `E_SORT_CATEGORICAL_UNORDERED`

```json
{
  "code":     "E_SORT_CATEGORICAL_UNORDERED",
  "step":     4,
  "operator": "sort",
  "message":  "Cannot sort by 'priority' (categorical) because no domain order is declared in the semantic model.",
  "hint":     "To sort a categorical column, declare its value ordering in the semantic model (e.g. priority: [low, medium, high, critical]). Until then, sort by a numeric column instead.",
  "context":  { "col": "priority", "type": "categorical" }
}
```

**When:** a `sort` step sorts by a `categorical` column that does not have a declared ordering in the semantic model.

---

## `sumAll` / aggregate errors (additional)

### `E_AGGREGATE_GROUPBY_UNKNOWN`

```json
{
  "code":     "E_AGGREGATE_GROUPBY_UNKNOWN",
  "step":     2,
  "operator": "aggregate",
  "field":    "groupBy[1]",
  "message":  "Column 'chnl' does not exist. Did you mean 'channel'?",
  "hint":     "Available columns to group by: order_id, order_date, region, channel, product_category, product_name, quantity, unit_price, amount, discount_pct, status, customer_type.",
  "context":  { "received": "chnl" }
}
```

**When:** a column name in `aggregate.groupBy` does not exist in the current table type.

---

## Warnings (non-fatal)

### `W_LIMIT_WITHOUT_SORT`

```json
{
  "code":    "W_LIMIT_WITHOUT_SORT",
  "step":    3,
  "message": "limit step is not immediately preceded by a sort step. Row order is undefined; results may be non-deterministic.",
  "hint":    "Add a sort step before limit to produce deterministic top-N output."
}
```

**When:** a `limit` step's immediately preceding step is not `sort`. The plan is valid but the output order is implementation-defined.

---

### `W_TRIVIAL_PLAN`

```json
{
  "code":    "W_TRIVIAL_PLAN",
  "step":    null,
  "message": "This plan has no transformation steps and writes the entire Orders entity to the sink.",
  "hint":    "Confirm this is intentional. If the user asked a question that implies filtering or transformation, the plan may be incomplete."
}
```

**When:** `steps` is empty and `sink.mode` is `"newSheet"`.

---

### `W_CATEGORICAL_LITERAL_NOT_IN_DOMAIN`

```json
{
  "code":    "W_CATEGORICAL_LITERAL_NOT_IN_DOMAIN",
  "step":    0,
  "message": "Predicate value 'canceled' is not a known member of the 'status' domain {shipped, returned, pending}.",
  "hint":    "The known values for status are: shipped, returned, pending. 'canceled' is not among them. If this is a new status value not yet in the semantic model, the filter will match zero rows."
}
```

**When:** a predicate compares a `categorical<D>` column to a string literal that is not a declared member of domain `D`.

---

## Repair loop protocol

The repair loop sends the full `Diagnostic[]` array back to the model with the following wrapper:

```json
{
  "attempt":     2,
  "maxAttempts": 2,
  "diagnostics": [...],
  "instruction": "The plan above failed type-checking. Correct every error listed in diagnostics and return a new, complete plan. Do not explain your changes — return only the corrected plan JSON."
}
```

On the second (final) attempt, `attempt === maxAttempts`. If the model's second attempt also fails, the service returns a structured refusal to the client:

```json
{
  "outcome":      "refusal",
  "understood":   "I interpreted your question as: quarterly revenue by region excluding returns.",
  "failureReason": "The plan could not be expressed in Sheaf's type-safe IR after two repair attempts.",
  "lastDiagnostics": [...],
  "suggestion":   "Try rephrasing your question, or check whether the columns it references are in the semantic model."
}
```

The refusal message is the legible surface described in design principle 6: "a tool that admits defeat is more trustworthy than one that guesses."
