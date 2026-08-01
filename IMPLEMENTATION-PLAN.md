<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="Resources/Sheaf-logo-dark-glass.png">
    <img src="Resources/Sheaf-logo-light-glass.png" alt="Sheaf" width="200">
  </picture>
</p>

# Sheaf: Implementation Plan

**Status:** v1 · Read [`ARCHITECTURE.md`](./ARCHITECTURE.md) first.

---

## How this plan is run

One milestone at a time. Each has a **goal**, a **deliverable**, and **exit criteria** that are objectively checkable. A milestone isn't done because the code exists — it's done when the exit criteria pass and the work has been reviewed as if it were going to production.

**M0 and M1 are specified in full below.** M2 onward are stated at goal and exit-criteria level deliberately; they get expanded when the preceding milestone closes, because what M3 should look like depends on what M2 actually taught us. Planning further than that in detail is planning fiction.

**Rule:** no code is written for a milestone until its plan is agreed.

---

## Milestone map

| # | Milestone | Goal | Rough size |
|---|---|---|---|
| **M0** | Corpus & IR design | The algebra, derived from real questions. No code. | 1 week |
| **M1** | Foundations | Add-in skeleton + service + IR contract pipeline + CI | 1 week |
| **M2** | Profiler | Range → physical schema, on messy real workbooks | 1 week |
| **M3** | IR core | Parser, type system, validator, diagnostics (Java) | 1.5 weeks |
| **M4** | Evaluator & committer | Values backend, evaluate/commit split, bounded writes | 1.5 weeks |
| **M5** | Planner | LLM port, prompt assembly, structured output, repair loop | 1 week |
| **M6** | Semantic model | Proposal, approval UI, persistence, accuracy lift | 2 weeks |
| **M7** | Diagnostics | Contribution analysis + narration | 1 week |
| **M8** | Product surface | Chart specs, run history, replay, error UX, telemetry | 1.5 weeks |
| **M9** | Hardening *(optional)* | NAA auth, local model, distribution | 1 week |

**Critical path: M0 → M3 → M4.** The IR and its type system are the project; everything else is scaffolding around them. If time runs short, cut M8 and M9, not M0.

**v1 is complete at M8.** Formula backend, cell-level provenance graph, and DuckDB-WASM are explicitly v2.

---

## M0 — Corpus & IR design

**Goal:** design the algebra from evidence, not intuition. This is the highest-leverage week in the project and it produces no code.

### Why it comes first

The single largest risk (ARCHITECTURE §11.1) is an IR that is either too narrow to be useful or too broad to be verifiable. That risk is only resolvable against real questions about real workbooks. Designing the operators first and discovering later that they can't express what people ask means rewriting the type checker, the evaluator, and the prompts.

### Tasks

**1. Assemble a workbook corpus — 5 workbooks.**
Real ones, not synthetic. Suggested mix: a sales/orders export, a personal or team budget, a project or resource tracker, an inventory or ops log, and one deliberately ugly one (merged headers, embedded totals rows, inconsistent dates). Anonymise anything sensitive. These become the fixtures for every later milestone.

**2. Collect 20 questions.**
Written as a user would type them, not as an engineer would phrase them. Cover, at minimum:
- simple aggregation ("total revenue by region")
- filtered aggregation ("...excluding cancelled orders")
- time grain ("monthly", "by quarter")
- ranking and top-N
- ratios and percentages ("conversion rate by channel")
- period comparison ("vs last quarter")
- cross-range ("...with the region names from the lookup sheet")
- at least 3 that are genuinely ambiguous
- at least 2 that **should be out of scope** (forecasting, "make this look nicer")

**3. Hand-write the IR for each of the 20.**
Longhand, on paper or in a scratch file. This is the actual design activity. Every time you can't express a question, you've found either a missing operator or a genuine scope boundary — record which, and why.

**4. Freeze the v1 operator set.**
Target: cover **15 of 20**. Not 20 — the ones you can't express are the boundary, and the boundary is a design output, not a failure. For every operator that made the cut, one sentence on what it exists for. For every operator you considered and rejected, one sentence on why verifiability won.

**5. Write the type signatures.**
Every operator's input/output table-type mapping, and the scalar type lattice (what's comparable to what, what coerces, what doesn't). Currency units and nullability are decided here, not later.

**6. Write the diagnostic taxonomy.**
Error codes and their repair hints. Do this now, while the type rules are fresh — retrofitting good diagnostics onto a finished checker never happens.

### Deliverables

- `corpus/` — 5 workbooks + `questions.md` with all 20 and their hand-written IR
- `docs/ir-spec.md` — operators, expression set, type lattice, signatures
- `docs/ir-decisions.md` — every operator accepted or rejected, with rationale
- `docs/diagnostics.md` — error codes and hints

### Exit criteria

- [ ] 15 of 20 corpus questions expressible in the frozen operator set
- [ ] The 5 that aren't are documented as scope boundaries with reasons
- [ ] Every operator has a written type signature
- [ ] Every accepted and rejected operator has a one-line rationale
- [ ] Diagnostic taxonomy covers every way a plan can fail type-checking

### Review gate

The `ir-decisions.md` document is the artifact under review. If a decision can't be defended in two sentences, it isn't decided yet. This document is also, bluntly, the thing that will most impress a senior interviewer — it's the evidence of judgment that code alone never provides.

---

## M1 — Foundations

**Goal:** an end-to-end skeleton with the IR contract pipeline working. A hardcoded plan travels from the service to the pane and back with types intact.

### Tasks

**1. Repository layout.**
```
sheaf/
├── addin/                  Office Add-in (TypeScript + React)
├── service/                Spring Boot (Java 21)
├── contract/               generated JSON Schema + TS types
├── corpus/                 M0 fixtures
└── docs/
```
Monorepo. The `contract/` directory is generated, gitignored except for a checked-in snapshot used to detect drift.

**2. Scaffold the add-in.**
```
yo office   # Task Pane, TypeScript, Excel
```
Then: React 18 + Fluent UI v9, the anti-corruption layer skeleton (`src/excel/`), and the folder structure from the guide. XML manifest for now — unified-manifest conversion is an M9 concern, and doing it before the app exists adds tooling risk for no benefit.

**3. Scaffold the service.**
Spring Boot 3, Java 21, hexagonal layout per ARCHITECTURE §9. `domain/` has no Spring imports — enforce it with ArchUnit from day one, because it's trivial now and painful to retrofit.

**4. Build the IR contract pipeline. ← the point of this milestone**
- Define `Plan`, `Step`, `Expr` as **sealed interfaces + records** in `domain/ir/`. Just the shape; no validation logic yet.
- Generate JSON Schema from the records at build time.
- Generate TypeScript types from the JSON Schema into `contract/`.
- Wire a CI check that fails if the generated output differs from the committed snapshot.

This is worth doing before anything interesting, because it's the mechanism that keeps the two-language split honest. If it's awkward, better to find out in week two than week six.

**5. Thin vertical slice.**
`POST /api/plan` accepts a question, ignores it, and returns a hardcoded valid `Plan`. The pane calls it and renders the JSON. No LLM, no Excel reads. Proves the round trip, the types, the dev certs, and the sideload all work.

**6. CI.**
Lint → typecheck → `mvn verify` → contract-drift check → `office-addin-manifest validate` → build.

### Exit criteria

- [ ] `npm start` sideloads into Excel on Windows; pane renders
- [ ] Pane calls the service and displays a `Plan` typed end-to-end
- [ ] Changing a Java record and forgetting to regenerate **fails CI**
- [ ] ArchUnit fails the build if `domain/` imports Spring
- [ ] Debugger attaches to WebView2 with working source maps
- [ ] CI green on a clean clone

### Notes

Excel on the web is a faster inner loop than desktop for anything that isn't platform-specific — sideload there for day-to-day work and verify on desktop at milestone boundaries.

---

## M2 — Profiler

**Goal:** turn a range into a trustworthy physical schema, on workbooks that are actually messy.

**Deliverable:** `src/excel/profiler.ts` — one bulk read, then pure inference: column types, cardinality, null rate, key candidates, date-format detection, currency detection, value exemplars. Header detection that survives merged cells and blank leading rows. Totals-row detection so summary rows don't pollute the type inference.

**Exit criteria**
- [ ] Correct schema on all 5 corpus workbooks, including the deliberately ugly one
- [ ] One `context.sync()` per profile run
- [ ] Profiles a 50k-row range in under 2 seconds
- [ ] Pure inference functions unit-tested with zero Office dependency

**Watch out:** this is the milestone most likely to overrun. Budget generously and resist gold-plating — 90% correct with a visible "is this right?" confirmation beats 99% correct and two extra weeks.

---

## M3 — IR core

**Goal:** the type checker. The heart of the project.

**Deliverable:** parser, type system, validator, and diagnostics in `domain/`. Exhaustive pattern matching over sealed types. No I/O, no Spring, no LLM.

**Exit criteria**
- [ ] Golden tests: 20 valid plans from M0 all type-check
- [ ] 20 hand-crafted invalid plans each produce the *correct* diagnostic code
- [ ] Output table type computed correctly for every operator chain in the corpus
- [ ] Join validation rejects any path not in the approved join set
- [ ] Adding an operator to the sealed interface without handling it everywhere **fails compilation**
- [ ] Property test: no valid plan can reference a column absent from the source schema

---

## M4 — Evaluator & committer

**Goal:** deterministic execution with the evaluate/commit split and structurally bounded writes.

**Deliverable:** pure TypeScript evaluator `(Plan, Table[]) → Result`; a committer whose signature makes it impossible to write outside the validated plan's sink; preview computation from the evaluated result.

**Exit criteria**
- [ ] Hand-written plans produce correct results on all corpus workbooks
- [ ] Evaluator unit-tested with zero Office dependency
- [ ] Exactly two `context.sync()` calls per run (one read, one write)
- [ ] Ctrl+Z after a commit restores the workbook
- [ ] Preview reports exact row × column dimensions before any write
- [ ] `newSheet` sink provably cannot touch existing data
- [ ] Existing-anchor sink states the overwrite extent and requires confirmation
- [ ] 100k-row aggregation completes in under 3 seconds

**Decision deferred to this point:** whether the hand-written evaluator holds up on large workbooks, or whether DuckDB-WASM becomes necessary. Decide with the benchmark in hand, not before.

---

## M5 — Planner

**Goal:** natural language in, valid IR out.

**Deliverable:** `LanguageModelPort` + one hosted adapter, versioned prompt artifacts, structured-output parsing, and the two-attempt repair loop feeding structured diagnostics back.

**Exit criteria**
- [ ] ≥70% of the 15 in-scope corpus questions produce a valid plan within 2 repairs
- [ ] The 2 out-of-scope questions are refused legibly, not answered wrongly
- [ ] Zero raw workbook rows appear in any outbound payload (asserted in test)
- [ ] Every run records model identity and prompt version
- [ ] Repair loop terminates — no unbounded retries under any input
- [ ] Prompt-injection fixture (hostile cell content) cannot produce a plan reading outside approved entities

**This is the milestone that establishes the accuracy baseline.** Record the number. M6 exists to beat it.

---

## M6 — Semantic model

**Goal:** the persisted layer that makes the planner accurate, and the approval UX that makes it survivable.

**Deliverable:** semantic model schema and store; model-proposed metrics, dimensions, time dimensions, synonyms, and join candidates; the approval UI; schema-drift detection on workbook open.

**Exit criteria**
- [ ] Semantic model persists in `document.settings` and survives save/close/reopen/email
- [ ] Join candidates inferred with confidence scores; unapproved joins are unusable in plans
- [ ] Setup flow completes in under 3 minutes on a corpus workbook
- [ ] **Corpus accuracy measurably exceeds the M5 baseline** — this is the milestone's whole justification
- [ ] Schema drift on reopen surfaces a re-approval prompt, not a silent break
- [ ] Approval UI is usable at 300px

**Biggest product risk in the project** (ARCHITECTURE §11.4). If setup is tedious, nobody finishes it and the accuracy gain never lands. Prototype the flow before building it.

---

## M7 — Diagnostics

**Goal:** "why did X change?" answered by deterministic contribution analysis.

**Deliverable:** intent router; `ContributionAnalyser` in `domain/analysis/`; bounded dimension fan-out; narration over the computed table.

**Exit criteria**
- [ ] Correct contribution ranking on corpus questions with a known answer
- [ ] Fan-out bounded — hard cap on dimensions and drill-down evaluations
- [ ] Narration cites only figures present in the computed findings (asserted, not assumed)
- [ ] Mix-vs-rate decomposition where both are computable
- [ ] Completes in under 5 seconds on the largest corpus workbook

**The demo milestone.** This is what makes Sheaf legible to someone watching for ninety seconds.

---

## M8 — Product surface

**Goal:** the things that make it a tool rather than a prototype.

**Deliverable:** chart-spec selector + client rendering; run history with replay; plan editing; the error/refusal UX; first-run experience; telemetry with correlation IDs.

**Exit criteria**
- [ ] Chart type correct for every result shape in ARCHITECTURE §7
- [ ] Saved plan replays against updated data in one click
- [ ] A user can edit one step of a plan and re-evaluate
- [ ] Refusals explain what was understood and what wasn't
- [ ] Every `OfficeExtension.Error` maps to a domain error with a user-facing message
- [ ] Run log queryable in-pane: plan, ranges, counts, timestamp
- [ ] Cross-platform check: Windows desktop, Excel on the web, and one older build

---

## M9 — Hardening *(optional)*

NAA authentication with dialog fallback; local model adapter (Ollama) for zero-egress mode; unified-manifest conversion; Integrated Apps deployment to a dev tenant.

Do this only if the project is going somewhere real. For portfolio purposes M8 is a complete story, and the local-model adapter is the one item here with genuine demo value.

---

## Cross-cutting

### Testing

| Layer | What | Where |
|---|---|---|
| Domain unit | Type checker, contribution analysis | JUnit, `domain/` |
| Evaluator unit | Every operator, edge cases, nulls | Vitest, zero Office |
| Golden | Corpus plans → expected results | Both sides, corpus fixtures |
| Contract | Excel ACL against mocked Office.js | `office-addin-mock` |
| Property | Invariants over generated plans | jqwik |
| E2E | Real sideload, real workbook | Playwright vs Excel on the web |

**Golden tests over the corpus are the regression suite that matters.** Every milestone from M3 onward adds to it. A change that breaks a corpus answer is a bug regardless of what the unit tests say.

### Definition of done

A milestone is done when: exit criteria pass; tests are green in CI; the corpus golden suite hasn't regressed; the design decisions made during the milestone are written down; and the code has been reviewed as production code, not as a personal project.

### Risk register

| Risk | Likelihood | Mitigation |
|---|---|---|
| IR too narrow / too broad | High | M0 corpus-driven design; written rationale per operator |
| Profiler overruns on messy sheets | High | Timebox M2; ship "confirm this schema" UX rather than perfect inference |
| Repair loop doesn't converge | Medium | Hard cap at 2; legible refusal is an acceptable outcome |
| Semantic-model setup too tedious | Medium | Prototype the flow before building; 3-minute target as an exit criterion |
| Evaluator too slow on big workbooks | Medium | Benchmark at M4; DuckDB-WASM is the prepared fallback |
| Pivot column explosion | Low | Cardinality guard at validation time |
| Project reads as an LLM wrapper | Medium | Lead the README with the failure mode; keep `ir-decisions.md` prominent |

### What v1 is not

No formula backend. No cell-level provenance graph. No multi-workbook. No scheduling. No collaboration. No AppSource listing. Each of those is defensible as a v2 item, and saying so explicitly is part of what the project demonstrates.
