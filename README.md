<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="Resources/Sheaf-logo-dark-glass.png">
    <img src="Resources/Sheaf-logo-light-glass.png" alt="Sheaf" width="200">
  </picture>
</p>

# Sheaf

**A typed transformation engine for spreadsheets.**

Ask a question in plain English. Sheaf compiles it into a typed program you can read, type-check, edit, and replay — then executes it deterministically against your workbook. The language model plans. It never touches a cell.

---

## The problem

Every AI-for-Excel tool shipping today works like this:

```
prompt + cell dump ──► LLM ──► cells get written
```

The model is the executor. Which means the result is:

- **unauditable** — you cannot see what rule produced a number
- **irreproducible** — same question next month, different answer
- **unreviewable** — you discover what it did by inspecting the damage
- **unusable** anywhere that matters — finance, audit, regulated reporting

Meanwhile the workbook's contents get shipped to a model provider, which is a non-starter for most of the people who need this most.

## The approach

Sheaf inverts the model's role. It is a **compiler frontend**, not an executor.

```
prompt + semantic model ──► LLM ──► program in a typed IR
                                          │
                                    type-check
                                          │
                                     evaluate (in memory)
                                          │
                                      preview
                                          │
                                       commit ──► cells
```

Everything after planning is ordinary, testable, deterministic software. The IR is a **closed algebra** — no user-defined functions, no loops, no recursion — so every program provably terminates and its output columns are statically known. The plan is data: inspectable, editable, diffable, storable, re-runnable.

**Workbook contents never leave the machine.** The model sees an inferred schema — column names, types, cardinalities, a handful of value exemplars — never rows.

---

## Worked example

Sales sheet, 4,021 rows. User types:

> *"quarterly revenue by region, excluding returns"*

**1 — Profile** (client-side). Sheaf infers structure, not values:

```
Sheet1!A1:H4021, header row 1
  order_id    string, unique, key-candidate
  order_date  date, 2024-01-03 .. 2026-06-30
  region      categorical, 6 distinct
  amount      currency(INR), 0.2% null
  status      categorical, 3 distinct {shipped, returned, pending}
```

**2 — Plan.** The model receives that schema plus the approved semantic model, and emits IR:

```json
{
  "source": { "ref": "Orders" },
  "steps": [
    { "op": "filter",    "predicate": { "ne": ["status", "returned"] } },
    { "op": "derive",    "as": "quarter", "expr": { "quarterOf": "order_date" } },
    { "op": "aggregate", "groupBy": ["region", "quarter"],
                         "measures": [{ "fn": "sum", "of": "amount", "as": "revenue" }] },
    { "op": "pivot",     "rows": ["region"], "cols": "quarter", "values": "revenue" }
  ],
  "sink": { "mode": "newSheet", "name": "Analysis", "anchor": "A1" }
}
```

No cell addresses invented. No formulas hallucinated. No arbitrary code.

**3 — Type-check.** `sum` requires numeric; `amount` is `currency` ✓. `quarterOf` requires `date`; `order_date` ✓. Had the model written `{"fn":"sum","of":"region"}`, the checker rejects it — `E_TYPE: sum expects numeric, got categorical(region)` — and that diagnostic goes back to the model as a repair hint. Compiler loop, not "try again."

**4 — Evaluate.** The plan runs in memory. Nothing is written yet.

**5 — Preview.** Real numbers, real dimensions, before any cell changes:

> Reads `Orders` (`Sheet1!A1:H4021`). Excludes **312** rows where `status = returned`. Groups by region × quarter, sums `amount`. Result: **6 rows × 11 columns**. Writes to a new sheet `Analysis` at `A1`. **No existing data is overwritten.**

**6 — Commit.** One batched write. The plan, the ranges read, the ranges written, and the row counts are recorded.

**7 — Replay.** Next quarter, new data, same plan, one click.

---

## What makes it different

| | Direct-write AI add-ins | Sheaf |
|---|---|---|
| What the model produces | cell writes | a typed program |
| Reviewable before running | no | yes — with real numbers |
| Reproducible | no | yes — the plan is the artifact |
| Data sent to the model | rows | schema only |
| Failure mode | plausible wrong numbers | explicit "I can't express that" |
| Write blast radius | anywhere | structurally bounded to the declared sink |
| Auditable | no | plan + ranges + counts recorded per run |

---

## Core concepts

**Semantic model** — a per-workbook layer above the physical schema: named entities, metrics with aggregation rules, dimensions, time dimensions, synonyms, and validated join paths between ranges. Proposed by the model, approved once by the user, persisted with the workbook. This is what turns *"exclude the returns"* from something the user must remember to say into part of the definition of Revenue.

**The IR** — a closed relational algebra with spreadsheet extensions. Six operators in v1: `filter`, `derive`, `aggregate`, `sort`, `join`, `pivot`, plus `periodCompare`. Deliberately small. Every proposed seventh operator has to justify itself against verifiability.

**The validator** — a type system over the IR. Checks every referenced metric and dimension exists, every operator's signature is satisfied, every join traverses a declared path, and computes the output column type statically. Emits structured diagnostics, not prose.

**Evaluate / commit split** — evaluation is pure and in-memory; commit is the only thing that touches the grid, in a single batched write bounded to the sink declared in the validated plan. No plan can write outside what the preview showed, regardless of what the prompt said.

**Contribution analysis** — *"why did revenue drop last quarter?"* is answered by deterministic code, not by a model: resolve metric and periods, compute the delta, fan out across every dimension, rank members by contribution. The model only narrates a table Sheaf computed.

**Provenance** — every run records the plan, the model and prompt version, the ranges read, the ranges written, and the row counts. Reproducible, diffable, auditable.

---

## Status

Design complete. Implementation not started. See [`IMPLEMENTATION-PLAN.md`](./IMPLEMENTATION-PLAN.md) for the milestone map and [`ARCHITECTURE.md`](./ARCHITECTURE.md) for the system design and the decisions behind it.

## Non-goals

- **Not a chatbot in a task pane.** If the answer isn't a verifiable program, Sheaf declines.
- **Not an arbitrary code runner.** The closed algebra is the product, not a limitation to be relaxed later.
- **Not a spreadsheet linter.** Static analysis of existing formulas is a separate concern.
- **Not a BI tool.** Sheaf operates on the workbook in front of you, not a warehouse.

## License

MIT.
