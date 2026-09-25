# Planner accuracy baselines

Each file is one run of `PlannerEvalTest` over every corpus question (26 in scope, 6 out of scope): a valid, checked plan within 2 repairs for in-scope questions; a clarifying question or a refusal (never a guessed plan) for the rest. "Same columns as golden" means the plan's output columns equal the hand-written plan's in `corpus/plans/`. The semantic model (`CorpusEnvironment`) includes approved links, metrics and templates.

| Date | Model | Prompt | In scope | Out of scope | Cost |
|---|---|---|---|---|---|
| 2026-09-25 | anthropic/claude-sonnet-4.6 | planner-v1 | 21/26 (81%) | 6/6 | $0.46 |
| 2026-09-25 | anthropic/claude-sonnet-4.6 | planner-v2 | 22/26 (85%) | 6/6 | $0.50 |
| 2026-09-25 | google/gemini-3.8-flash | planner-v2 | 23/26 (88%) | 6/6 | $0.30 |

M8 (semantic model) must beat these. Re-run with:

```bash
cd service
mvn test -Dtest=PlannerEvalTest -Dsheaf.eval.model=<openrouter model id> -Dsheaf.eval.budget=0.60
```

`planner-v3` (2026-09-25) differs from v2 only in one sentence: a new sheet is the default destination, and an existing sheet is used only when the user asks for one. v3 hasn't been re-measured; the v2 numbers above are expected to hold.

`planner-v4` (2026-09-25) adds one rule: filter only on what the question says or a listed metric defines. In a real workbook, "total amount by region" had excluded returned orders on its own, prompted by the worked example (whose question does ask for that). Unasked filters are now reported in assumptions instead of applied. Not yet re-measured.

`planner-v5` (2026-09-25) adds the `explain` answer, and lists in the workbook description what reads each column. JSON mode is on for OpenRouter (checked live with Sonnet 4.6: q01 planned in one call). Not yet re-measured.

The local-model row (Ollama) is still to do. Run it on your machine with `-Dsheaf.eval.provider=ollama`.
