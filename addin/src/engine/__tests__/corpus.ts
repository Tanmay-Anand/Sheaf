import { readFileSync, readdirSync } from "node:fs";
import { resolve } from "node:path";
import { buildCatalog } from "../../catalog/build";
import { CORPUS_SHEETS, corpusRows, sheetFromRows } from "../../catalog/__tests__/fixtures";
import type { EvalOptions } from "../evaluate";
import type { Plan, QueryPlan, Sink } from "../ir";
import { loadEntity } from "../load";
import type { Table } from "../table";

/** The corpus workbook as tables, loaded the way the pane loads a scanned workbook. */
export function corpusTables(): Record<string, Table> {
  const sheets = Object.entries({ ...CORPUS_SHEETS, Contacts: "contacts.csv" }).map(([name, file]) => sheetFromRows(name, corpusRows(file)));
  const catalog = buildCatalog({ sheets, tables: [], names: [], sources: [], validations: [] }, { exemplars: false, now: new Date(0) });
  const out: Record<string, Table> = {};
  for (const e of catalog.entities) {
    const sheet = sheets.find((s) => s.name === e.sheet)!;
    out[e.name] = loadEntity(e, sheet, catalog.dateSystem).table;
  }
  return out;
}

/** The semantic model corpus/questions.md assumes (metrics, time dimensions, category orders). */
export const CORPUS_MODEL: EvalOptions = {
  metrics: {
    Revenue: { entity: "Orders", fn: "sum", of: "amount", filters: [{ op: "ne", left: { type: "col", col: "status" }, right: { type: "lit", value: "returned" } }] },
    OrderCount: { entity: "Orders", fn: "count", of: "*", filters: [] },
    EffortHours: { entity: "Tasks", fn: "sum", of: "effort_hours", filters: [] },
  },
  timeDimensions: {
    order_date: { entity: "Orders", column: "order_date", weekStart: "monday" },
    task_end_date: { entity: "Tasks", column: "end_date" },
  },
  orders: { priority: ["low", "medium", "high", "critical"], month: ["Jan", "Feb", "Mar", "Apr"] },
};

export interface Golden {
  file: string;
  question: string;
  expect: { valid: boolean; output?: string[] };
  plan: Record<string, unknown>;
}

export function goldenPlans(): Golden[] {
  const dir = resolve(process.cwd(), "../corpus/plans");
  return readdirSync(dir)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => ({ file: f, ...(JSON.parse(readFileSync(resolve(dir, f), "utf8")) as Omit<Golden, "file">) }));
}

/**
 * A stand-in for the service's binder, enough to evaluate corpus plans: table names are resolved
 * by the evaluator (ignoring case), so only the sink needs a concrete form.
 */
export function bindForTest(unbound: Record<string, unknown>): Plan {
  const intent = unbound.sink as { mode: string; name?: string; templateId?: string } | undefined;
  const sink: Sink =
    intent?.mode === "template"
      ? { mode: "template", templateId: intent.templateId!, headerRow: 1, firstDataRow: 2 }
      : intent?.mode === "anchor"
        ? { mode: "anchor" }
        : { mode: "newSheet", name: intent?.name ?? "Result", anchor: "A1" };
  return { ...(unbound as object), ...(unbound.kind === "query" ? { sink } : {}), bindings: { entities: [] } } as unknown as Plan;
}

export function query(source: string, steps: QueryPlan["steps"], params: QueryPlan["params"] = []): QueryPlan {
  return { kind: "query", source, steps, sink: { mode: "newSheet", name: "Result", anchor: "A1" }, params, bindings: { entities: [] } };
}
