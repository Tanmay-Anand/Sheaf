import { describe, expect, it } from "vitest";
import { corpusRows } from "../../catalog/__tests__/fixtures";
import { evaluate } from "../evaluate";
import type { QueryPlan } from "../ir";
import { columnIndex, type Table } from "../table";
import { isoText, isDate, type Value } from "../value";
import { bindForTest, CORPUS_MODEL, corpusTables, goldenPlans } from "./corpus";

/**
 * Every corpus query plan evaluates on the corpus workbook, with exactly the output columns the
 * type checker recorded for it (corpus/plans/*.json → expect.output), and the results match
 * answers computed independently from the CSVs.
 */

const TABLES = corpusTables();
const PLANS = goldenPlans();

function run(file: string, params: Record<string, unknown> = {}): Table {
  const g = PLANS.find((p) => p.file === file)!;
  const r = evaluate(bindForTest(g.plan) as QueryPlan, TABLES, { ...CORPUS_MODEL, params });
  expect(r.issues.filter((i) => i.blocking)).toEqual([]);
  return r.table;
}

function column(t: Table, name: string): Value[] {
  const i = columnIndex(t, name);
  return t.rows.map((r) => r[i] ?? null);
}

/** The CSV as objects, for independent answers. */
function csv(file: string): Record<string, string>[] {
  const [header, ...rows] = corpusRows(file);
  return rows.filter((r) => r.some((c) => c !== "")).map((r) => Object.fromEntries(header!.map((h, i) => [h, r[i] ?? ""])));
}

describe("every corpus query plan", () => {
  for (const g of PLANS.filter((p) => p.plan.kind === "query")) {
    it(`${g.file}: ${g.question}`, () => {
      const t = run(g.file, { asOf: "2025-01-20" });
      const expected = (g.expect.output ?? []).map((o) => o.slice(0, o.indexOf(": ")));
      const dynamic = expected.indexOf("*");
      if (dynamic >= 0) {
        expect(t.columns.slice(0, dynamic)).toEqual(expected.slice(0, dynamic));
        expect(t.columns.length).toBeGreaterThan(dynamic);
      } else {
        expect(t.columns).toEqual(expected);
      }
    });
  }
});

describe("answers match independent computation", () => {
  const orders = csv("sales-orders.csv");

  it("Q1 revenue by region, excluding returns, largest first", () => {
    const t = run("q01.json");
    const want = new Map<string, number>();
    for (const o of orders) if (o.status !== "returned") want.set(o.region!, (want.get(o.region!) ?? 0) + Number(o.amount));
    expect(new Map(t.rows.map((r) => [r[0], r[1]]))).toEqual(want);
    const revenue = column(t, "revenue") as number[];
    expect([...revenue].sort((a, b) => b - a)).toEqual(revenue);
  });

  it("Q5 monthly revenue in 2025 uses typed date literals", () => {
    const t = run("q05.json");
    const want = new Map<number, number>();
    for (const o of orders) {
      if (o.status === "returned" || !o.order_date!.startsWith("2025")) continue;
      const m = Number(o.order_date!.slice(5, 7));
      want.set(m, (want.get(m) ?? 0) + Number(o.amount));
    }
    expect(new Map(t.rows.map((r) => [r[0], r[1]]))).toEqual(want);
    expect(column(t, "month")).toEqual([...want.keys()].sort((a, b) => a - b));
  });

  it("Q6 pivots quarters into columns in ascending order", () => {
    const t = run("q06.json");
    const quarters = t.columns.slice(1);
    expect(quarters).toEqual([...quarters].sort());
    const north = t.rows.find((r) => r[0] === "North")!;
    const q1North = orders
      .filter((o) => o.region === "North" && o.status !== "returned" && ["01", "02", "03"].includes(o.order_date!.slice(5, 7)))
      .reduce((a, o) => a + Number(o.amount), 0);
    expect(north[1 + quarters.indexOf("1")]).toBe(q1North);
  });

  it("Q8 top five products, and Q9 shares add up to 100%", () => {
    const top = run("q08.json");
    expect(top.rows).toHaveLength(5);
    const shares = column(run("q09.json"), "revenue_share") as number[];
    expect(shares.reduce((a, b) => a + b, 0)).toBeCloseTo(1, 10);
  });

  it("Q10 return rate is returned / total per category", () => {
    const t = run("q10.json");
    for (const r of t.rows) expect(r[3]).toBeCloseTo((r[2] as number) / (r[1] as number), 12);
  });

  it("Q11 compares a quarter with the one before", () => {
    const t = run("q11.json");
    expect(t.columns).toEqual(["region", "current_revenue", "prior_revenue", "delta", "delta_pct"]);
    const inQ = (o: Record<string, string>, y: string, months: string[]) =>
      o.order_date!.startsWith(y) && months.includes(o.order_date!.slice(5, 7)) && o.status !== "returned";
    for (const r of t.rows) {
      const cur = orders.filter((o) => o.region === r[0] && inQ(o, "2025", ["01", "02", "03"])).reduce((a, o) => a + Number(o.amount), 0);
      const pri = orders.filter((o) => o.region === r[0] && inQ(o, "2024", ["10", "11", "12"])).reduce((a, o) => a + Number(o.amount), 0);
      expect(r[1]).toBe(cur || null);
      expect(r[2]).toBe(pri || null);
    }
  });

  it("Q14 sorts by project name, then priority in its declared order", () => {
    const t = run("q14.json");
    const order = ["low", "medium", "high", "critical"];
    const pi = columnIndex(t, "project_name");
    const pr = columnIndex(t, "priority");
    for (let i = 1; i < t.rows.length; i++) {
      const [a, b] = [t.rows[i - 1]!, t.rows[i]!];
      if (a[pi] === b[pi]) expect(order.indexOf(a[pr] as string)).toBeLessThanOrEqual(order.indexOf(b[pr] as string));
    }
  });

  it("Q15 compares two columns row by row", () => {
    const t = run("q15.json");
    const inv = csv("inventory-log.csv").filter((r) => Number(r.qty_on_hand) < Number(r.reorder_level));
    expect(t.rows).toHaveLength(inv.length);
  });

  it("Q31 fills the enriched template: lookup, split, concat, dates as text, mapped status", () => {
    const t = run("q31.json");
    const contacts = csv("contacts.csv").filter((c) => c.status !== "test");
    expect(t.rows).toHaveLength(contacts.length);
    const first = contacts[0]!;
    const row = t.rows[0]!;
    expect(row[0]).toBe(`${first.first_name} ${first.last_name}`);
    expect(row[1]).toBe(first.email!.split("@")[1]);
    const region = csv("regions-lookup.csv").find((r) => r.code === first.region);
    expect(row[2]).toBe(region?.region_name ?? null);
    expect(row[3]).toMatch(/^[A-Z][a-z]{2} \d{4}$/);
    expect(["Active", "Prospect", "Former", null]).toContain(row[4]);
    expect(row[5]).toBeNull();
  });

  it("the ugly sheet: totals by region equal the sheet's own subtotals ($5196 and all)", () => {
    const plan: QueryPlan = {
      kind: "query",
      source: "Q1 Report",
      steps: [{ op: "aggregate", groupBy: ["Region"], measures: [{ fn: "sum", of: "Amount", as: "amount" }, { fn: "sum", of: "Qty", as: "qty" }] }],
      sink: { mode: "newSheet", name: "R", anchor: "A1" },
      params: [],
      bindings: { entities: [] },
    };
    const r = evaluate(plan, TABLES, {});
    expect(r.issues.filter((i) => i.blocking)).toEqual([]);
    expect(r.table.rows).toEqual([["North", 22893, 7], ["South", 13568, 32], ["East", 23985, 15]]);
    // Subtotal, section-label and grand-total rows are not data.
    expect(TABLES["Q1 Report"]!.rows).toHaveLength(11);
  });

  it("the ugly sheet's mixed date formats all load as dates", () => {
    const dates = TABLES["Q1 Report"]!.rows.map((r) => r[2]!);
    expect(dates.every((d) => isDate(d))).toBe(true);
    expect(dates.slice(1, 4).map((d) => isDate(d) && isoText(d))).toEqual(["2025-01-13", "2025-01-20", "2025-01-20"]);
  });

  it("loads text dates as dates", () => {
    const d = TABLES.Orders!.rows[0]![1]!;
    expect(isDate(d) && isoText(d)).toBe(orders[0]!.order_date);
  });
});
