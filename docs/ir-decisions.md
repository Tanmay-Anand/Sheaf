# IR Decisions — v1

**Purpose:** every operator accepted or rejected, with rationale. Every expression accepted or rejected. Every edge case where expressiveness and verifiability conflicted, and which side won.

This document is the review artifact for M0. If a decision can't be defended in two sentences, it isn't decided yet.

---

## Operators in v1

### `filter`

**In.** Row selection by predicate over existing columns. No alternative — every useful query restricts rows at some point. Verifiability cost: none; predicates are a closed grammar with decidable type rules. Output is a subset of input rows; type is identical to input.

### `derive`

**In.** Computed columns from a closed expression set. The closed expression set is the verifiability control: it enumerates every allowed computation. Without `derive`, date grain extraction, ratios, and percentage-of-total would require either a general expression engine (unsafe) or operator proliferation (unmanageable). One column per step; multiple `derive` steps chain cleanly and make intermediate types explicit.

### `aggregate`

**In.** Group-and-reduce is the most common transformation in spreadsheet analysis. Without it, the IR cannot express any summary query. Output columns are statically declared in the step, which is how the type system stays decidable despite changing row count.

### `sort`

**In.** Ordering is semantically necessary for ranked output and for `limit` to be meaningful. Shape-preserving, zero type complexity. Categorical sort order must be declared in the semantic model — this is where the decision gets interesting, because it forces the domain to be explicit rather than implicit in the evaluator.

### `limit`

**In.** Every corpus question about "top N" requires it. Without `limit`, the IR cannot express ranking, which makes it unable to answer a large class of natural questions ("top 5 products", "bottom 3 regions"). The operator is shape-preserving, trivially verifiable, and has no type-system implications. Expressiveness clearly outweighs any verifiability cost here — there is essentially no cost.

### `join`

**In.** Multi-entity questions are common ("revenue by region with full names from the lookup table"). The verifiability control is the approved join graph: a join is only valid along a declared, approved edge. This is the mechanism that prevents hallucinated cross-table references. The type system tracks which columns come from which entity, and name collision resolution is deterministic. Cost: the semantic model must have the join declared before the plan can reference it. This is a feature, not a limitation.

### `pivot`

**In.** The output format users actually want for cross-tabulation queries. Without `pivot`, the IR can produce the long-form aggregation (region × quarter) but not the wide-form table users expect. The exception it creates — column count is data-dependent — is acknowledged and documented. Mitigated by: (a) the cardinality guard at validation time, (b) the fact that `pivot` is always the terminal step, so no downstream operator can be confused by the unknown schema, (c) post-evaluation the actual column count is known and reported in the preview.

### `periodCompare`

**In.** Period-over-period is the single most commonly asked analytical question on time-series data. The alternative — expressing it as filter + aggregate + self-join — requires a self-join on a derived intermediate table, which is not representable without either a general subquery operator or a stored intermediate. Making it first-class means the evaluator handles the semantics once, correctly: partial period handling, calendar alignment, week-start conventions, zero-vs-null prior period behaviour. The type signature is statically known. There is no verifiability downside to making this first-class; the downside is operator count, which is modest.

---

## Operators considered and rejected

### `union` / `vstack`

**Rejected.** Stack two tables vertically. The type constraint is that both tables must have identical schemas — a constraint the type checker can enforce. However, every corpus question is answered within a single entity or via a join. No corpus question requires stacking tables. Adding an operator to satisfy zero corpus questions violates the corpus-driven design principle. **Revisit at v2 if real-world questions require it.**

### `window` / `running_total` / `cumulative_sum`

**Rejected.** Q16 (running total of revenue by week) requires this. Rejected because: a general window operator (with `rowsBetween`, `orderBy`, and partition semantics) opens a large surface of computation that is difficult to type statically; the set of window functions is open-ended; and cumulative aggregation can't easily be explained to a user as a verifiable operation in the same way as group-and-reduce can. The one question that needs it (Q16) is an acceptable scope boundary. **If real usage shows this is a common request, the case for a restricted `cumulative` operator (sum only, over a single time dimension, no partitioning) is plausible for v2.**

### `unpivot` / `melt`

**Rejected.** The inverse of pivot: wide to long. No corpus question requires it. The risk of including it is that it increases IR complexity, and its output type is more complex to derive statically than pivot's (because the "melted" column names become string values). Zero corpus demand; non-trivial type complexity. **Rejected for v1; revisit if corpus grows to include wide-format inputs.**

### `sample` (random N rows)

**Rejected.** Non-deterministic output violates design principle 7: "every run is reproducible." A plan containing `sample` would produce a different result on every run. The closed algebra is deterministic by construction; sampling would be the single exception. **Rejected on principle; the algebra's determinism is load-bearing.**

### `distinct`

**Rejected.** Removing duplicate rows from a table. The use case ("show me unique regions") is better expressed as `aggregate(groupBy=["region"], measures=[])` — a zero-measure aggregate is a degenerate but valid form. Adding `distinct` as a separate operator would be redundant and confusing. The aggregate form is already in the operator set.

### `rename`

**Rejected.** Renaming a column. All plans write to a declared sink; the user sees the column names in the preview before committing. Renaming is a presentation concern (the chart spec can supply display names) not a transformation concern. Adding `rename` would complexify the type checker for no analytical value. **Not needed.**

### `unnest` / `explode`

**Rejected.** Expanding an array-valued column into multiple rows. No corpus workbook has array-typed columns. Spreadsheet data is tabular; array-valued cells do exist in the wild but profiling them is a separate, harder problem. Out of scope entirely.

### `subquery` / nested `Plan`

**Rejected strongly.** A `Step` whose content is a complete `Plan`. This would make the algebra Turing-incomplete but still arbitrarily deep, and the type checker would need to recurse. More importantly, nesting plans would make the IR opaque: a user reviewing a plan would have to follow recursive references to understand what it does. The whole point of the IR is legibility. Self-joins, correlated sub-selects, and CTEs are all closed off by this decision. **The corpus questions that seem to need them (Q16) are better served by new first-class operators or by an explicit scope boundary.**

---

## Aggregate functions: decisions

### `countIf` / `sumIf` (conditional aggregate)

**In.** Equivalent to `COUNT(*) FILTER (WHERE predicate)`. Needed for Q10 (return rate = returned / total). The alternative — a self-join or two separate aggregate steps — is not available. The `where` predicate inside a measure is a restricted form: it can only reference columns from the input table type, uses the same predicate grammar as `filter`, and its type rules are the same. No additional type-system complexity beyond what `filter` already required.

### `median`, `percentile`, `stddev`, `variance`

**Rejected.** Not present in any corpus question. More importantly, these are not naturally expressible in Excel's formula language, which is the v2 formula-backend target. Introducing them now would create a gap between the IR evaluator and the formula compiler. The cost of adding them later (a new `AggFn` variant, type rules already established) is low; the cost of removing a dependency on them from the formula backend design would be high. **Deferred to v2.**

### `first` / `last` (first/last value in group)

**Rejected.** "First order in a group" or "most recent entry per customer" — useful, but requires either an ordering specified per measure (complex type rules) or is assumed to be insertion order (non-deterministic). Neither is acceptable without careful design. No corpus question requires it. **Deferred.**

---

## Expression decisions

### `sumAll` (post-aggregate grand total reference)

**In with restrictions.** Needed for Q9 (percentage of total). The alternative — a window function or a self-join — was rejected above. `sumAll` is the narrowest possible escape: it is only valid immediately after an `aggregate` step, it references only measure columns from that aggregate, and its type is the same as the referenced column. It is not a general window operation; it cannot partition by anything; it cannot be used outside its one valid position in the pipeline. The restriction is enforced by the type checker, not by convention.

### Date extraction (`yearOf`, `quarterOf`, `monthOf`, `weekOf`, `dayOf`, `dayOfWeek`)

**In.** The entire time-grain analysis class (Q5, Q6, Q7) requires these. All return `number`, which has known type rules. The input constraint (must be `date | datetime`) is trivially checkable. No verifiability cost.

### `dateAdd` / `dateSub` / `dateDiff`

**Rejected.** Adding an interval to a date (e.g. "90 days ago"). Useful for "last N days" queries, but the plan would then encode a relative expression that produces different results at different times — violating reproducibility unless evaluated at plan-generation time. The decision: relative dates are resolved by the planner to literal date values before the IR is written. The IR carries only `{ "lit": "2025-01-20" }` — never `{ "dateAdd": ["today", -90] }`. Cost: the plan has a baked-in date that "ages." This is acceptable and honest: a plan is a snapshot. Regenerating the plan refreshes the date.

### String functions (`substring`, `concat`, `upper`, `lower`, `trim`, `regex`)

**Rejected.** No corpus question requires string manipulation. String-producing expressions cannot be used in aggregates (no numeric output), and string-consuming transforms (normalising inconsistent region names) belong in the profiler or semantic model, not the IR. Opening the expression set to string functions would make the `derive` operator a general text-processing tool, which is not what the IR is for. **Not in scope.**

### `bucket`

**In.** Discretising a continuous numeric column into labelled bins. Common analytical pattern ("order size: small / medium / large"). Output is `categorical<labels>` — statically known from the expression itself. No verifiability cost; the output domain is declared inline.

### `coalesce`

**In.** Null-handling is necessary for real data. `coalesce` is the standard null-resolution expression, and its output is non-nullable, which is important for expressions like `pct` where a null denominator would produce a null result. Minimal complexity; standard semantics.

### `case`

**In.** The general conditional, needed when `bucket` doesn't apply (non-numeric inputs, overlapping conditions, custom labels). Requiring an `else` clause is deliberate — it prevents accidental null introductions and keeps the output type decidable.

---

## Predicate decisions

### Column-to-column comparison (`{ "lt": ["qty_on_hand", { "col": "reorder_level" }] }`)

**In.** Needed for Q15 (items below reorder level). The pattern is: one column compared to another column of the same type. The type checker resolves both `ValueRef`s to column types and applies the comparability rules. The implementation cost is trivial (extend `ValueRef` to include `{ "col": ... }` as a variant); the analytical value is real.

### Regex predicates (`match`, `like`, `startsWith`, `endsWith`)

**Rejected.** No corpus question requires pattern matching. String pattern predicates are hard to reason about statically (they don't constrain the output type), and their presence would encourage queries that should instead be handled by cleaning the data upstream. A `categorical` column whose values need regex filtering is a profiling problem, not a query problem. **Rejected.**

---

## Type system decisions

### Currency units are explicit and non-interchangeable

**Decision.** `currency:INR` and `currency:USD` are distinct types. Adding them or comparing them without conversion is a type error. This forces the semantic model to be explicit about which currency each metric uses. The alternative — treating all currencies as `number` — loses the information needed to detect unit mismatches and to format output correctly. The cost is that the planner must emit the correct unit string; this is a prompt engineering requirement documented in the prompt artifacts (M5).

### `percent` is a distinct type, not `number`

**Decision.** `percent` is stored as a 0.0–1.0 value but displayed as 0%–100%. Keeping it distinct from `number` prevents: (a) the type checker from allowing `sum(discount_pct)` to produce a result the user would interpret as a sum of percentages (which is meaningless), and (b) the evaluator from formatting a percent value as a plain number. The cost is slightly more complex arithmetic rules. The benefit is that the IR carries enough type information for the evaluator to format output without inference.

### `categorical<D>` is not equivalent to `string`

**Decision.** A `categorical` column has a known, finite domain. This is used by: (a) the profiler to infer cardinality, (b) the type checker to validate that `in` predicates use known members (warning, not error), (c) the `pivot` cardinality guard, (d) the chart spec selector (low-cardinality categorical → bar chart; high-cardinality → table). Treating categorical as string would lose all of this. Comparisons between `categorical<D>` and `string` are allowed (the string is treated as a domain member), with a warning if the literal isn't a known member — this handles the common case of users typing literal values.

### Nullability is tracked per-column

**Decision.** Every column in a table type carries an `isNullable: boolean` flag. This is more work in the type checker but it means: (a) the evaluator knows which columns might produce nulls and can handle them correctly, (b) the committer formats null cells as blank rather than zero or "null", (c) `periodCompare` output columns are correctly marked nullable (a region in the current period may have no prior-period data). The alternative — treating all columns as potentially nullable — is simpler but loses the ability to detect avoidable null-propagation bugs in plans.

---

## Scope boundaries — summary

| Boundary | Class | Notes |
|---|---|---|
| Cumulative / running total | Missing operator | Narrow `cumulative` operator is plausible for v2 |
| Relative date expressions in IR | Design constraint | Resolved to literals at plan-generation time |
| String manipulation | Missing expressions | Belongs upstream (profiler/semantic model) |
| Forecasting | Wrong class of computation | Not a transformation; no IR extension changes this |
| Formatting / styling | Non-goal | Presentation concern; explicitly excluded from scope |
| Ambiguous questions | Planning concern | IR cannot encode ambiguity; planner must resolve before generating |
| Missing semantic model entities | Model gap | Questions about undeclared data cannot be answered by design |
| Self-join / correlated query | Rejected operator | Closed off to preserve legibility and avoid recursive type-checking |
| Statistical aggregates (median, stddev) | Deferred | Incompatible with formula backend target in v2 |
