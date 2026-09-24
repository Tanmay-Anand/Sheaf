import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { compareRows } from "../collate";
import { evaluate } from "../evaluate";
import { evalExpr, type RowScope } from "../expr";
import { isoWeek, roundExcel, toText } from "../format";
import { HANDLED, type Expr, type Predicate, type Step } from "../ir";
import { loadEntity } from "../load";
import type { Table } from "../table";
import { daysFromSerial, daysFromYmd, serialFromDays, type Value, xdate, xerror } from "../value";
import { buildCatalog } from "../../catalog/build";
import { sheetFromRows } from "../../catalog/__tests__/fixtures";
import { query } from "./corpus";

const col = (c: string) => ({ type: "col" as const, col: c });
const lit = (value: unknown) => ({ type: "lit" as const, value });
const eq = (c: string, v: unknown): Predicate => ({ op: "eq", left: col(c), right: lit(v) });

function run(t: Table, steps: Step[], opts = {}) {
  return evaluate(query("T", steps), { T: t }, opts);
}

function expr(e: Expr, row: Value[] = [], columns: string[] = []): Value {
  const scope: RowScope = { table: { columns, rows: [row] }, params: new Map(), onError: () => undefined };
  return evalExpr(e, row, scope);
}

describe("blanks", () => {
  const t: Table = { columns: ["name", "n"], rows: [["a", 1], [null, 2], ["", 3], ["  ", 4], ["b", null]] };

  it("isNull matches empty cells and empty text alike", () => {
    expect(run(t, [{ op: "filter", predicate: { op: "isNull", col: "name" } }]).table.rows.map((r) => r[1])).toEqual([2, 3, 4]);
  });

  it("a blank never satisfies <, >; equality with a value is false and inequality true", () => {
    const lt: Predicate = { op: "lt", left: col("n"), right: lit(100) };
    expect(run(t, [{ op: "filter", predicate: lt }]).table.rows).toHaveLength(4);
    expect(run(t, [{ op: "filter", predicate: { op: "ne", left: col("name"), right: lit("a") } }]).table.rows).toHaveLength(4);
  });

  it("aggregates skip blanks; a group of only blanks sums to null; blank keys group together", () => {
    const r = run(t, [{ op: "aggregate", groupBy: ["name"], measures: [{ fn: "sum", of: "n", as: "s" }, { fn: "count", of: "n", as: "c" }] }]);
    expect(r.table.rows).toEqual([["a", 1, 1], [null, 9, 3], ["b", null, 0]]);
  });
});

describe("error values", () => {
  const t: Table = { columns: ["k", "v"], rows: [["x", 1], ["x", xerror("#N/A")], ["y", 5]] };
  const sum: Step = { op: "aggregate", groupBy: ["k"], measures: [{ fn: "sum", of: "v", as: "s" }] };

  it("are refused in an aggregate, with the count, as Excel's SUM would be an error", () => {
    const r = run(t, [sum]);
    expect(r.issues).toEqual([
      expect.objectContaining({ code: "ERROR_CELLS", count: 1, blocking: true }),
      expect.objectContaining({ code: "ERRORS_IN_OUTPUT", count: 1, blocking: false }),
    ]);
    expect(r.table.rows[0]![1]).toEqual(xerror("#N/A"));
  });

  it("are left out when the user chooses to", () => {
    const r = run(t, [sum], { excludeErrorCells: true });
    expect(r.table.rows).toEqual([["x", 1], ["y", 5]]);
    expect(r.issues).toEqual([expect.objectContaining({ code: "ERROR_CELLS_EXCLUDED", count: 1, blocking: false })]);
  });

  it("make a condition unknown, so neither a test nor its negation selects the row", () => {
    const gt: Predicate = { op: "gt", left: col("v"), right: lit(0) };
    expect(run(t, [{ op: "filter", predicate: gt }], { excludeErrorCells: true }).table.rows).toHaveLength(2);
    expect(run(t, [{ op: "filter", predicate: { op: "not", clause: gt } }], { excludeErrorCells: true }).table.rows).toHaveLength(0);
  });

  it("propagate through expressions, and division by zero is #DIV/0!", () => {
    expect(expr({ type: "add", a: lit(1), b: col("v") }, [xerror("#REF!")], ["v"])).toEqual(xerror("#REF!"));
    expect(expr({ type: "div", a: lit(1), b: lit(0) })).toEqual(xerror("#DIV/0!"));
    expect(expr({ type: "concat", parts: [lit("a"), col("v")] }, [xerror("#N/A")], ["v"])).toEqual(xerror("#N/A"));
  });
});

describe("text expressions and nulls", () => {
  const N = { type: "lit" as const, value: null };

  it("concat: a null part is empty; all-null is null; skipNulls drops blanks with their separator", () => {
    expect(expr({ type: "concat", parts: [lit("a"), N, lit("c")], sep: "-" })).toBe("a--c");
    expect(expr({ type: "concat", parts: [N, N], sep: "-" })).toBeNull();
    expect(expr({ type: "concat", parts: [lit(""), lit("Bangalore"), N, lit("560001")], sep: ", ", skipNulls: true })).toBe("Bangalore, 560001");
    expect(expr({ type: "concat", parts: [lit("n="), lit(0.1 + 0.2)] })).toBe("n=0.3");
    expect(expr({ type: "concat", parts: [lit("on "), { type: "lit", value: "2025-03-08", valueType: "date" }] })).toBe("on 2025-03-08");
  });

  it("trim is Excel's (inner runs collapse); upper and lower; null stays null", () => {
    expect(expr({ type: "trim", arg: lit("  a   b ") })).toBe("a b");
    expect(expr({ type: "upper", arg: lit("abc") })).toBe("ABC");
    expect(expr({ type: "lower", arg: N })).toBeNull();
  });

  it("splitPart is 1-based and null past the end", () => {
    expect(expr({ type: "splitPart", arg: lit("a@b.com"), sep: "@", index: 2 })).toBe("b.com");
    expect(expr({ type: "splitPart", arg: lit("a@b.com"), sep: "@", index: 3 })).toBeNull();
    expect(expr({ type: "splitPart", arg: N, sep: "@", index: 1 })).toBeNull();
  });

  it("toText formats as Excel's TEXT does, rounding half away from zero", () => {
    expect(toText(1234.5, "#,##0")).toBe("1,235");
    expect(toText(-1234.5, "#,##0")).toBe("-1,235");
    expect(toText(2.675, "0.00")).toBe("2.68");
    expect(toText(1234567.891, "#,##0.00")).toBe("1,234,567.89");
    expect(toText(0.1234, "0.0%")).toBe("12.3%");
    expect(toText(xdate(daysFromYmd(2025, 3, 8)), "mmm yyyy")).toBe("Mar 2025");
    expect(toText(xdate(daysFromYmd(2025, 3, 8)), "dd/mm/yyyy")).toBe("08/03/2025");
    expect(toText(null, "0")).toBeNull();
    expect(roundExcel(-0.5, 0)).toBe(-1);
  });

  it("case: first match wins, else otherwise", () => {
    const e: Expr = { type: "case", when: [{ if: eq("s", "active"), then: lit("Active") }], else: { type: "lit", value: null } };
    expect(expr(e, ["ACTIVE"], ["s"])).toBe("Active"); // text compares ignoring case, as in Excel
    expect(expr(e, ["lead"], ["s"])).toBeNull();
  });

  it("bucket: labels by interval, the top interval open", () => {
    const e: Expr = { type: "bucket", col: "a", breaks: [1000, 5000], labels: ["small", "medium", "large"] };
    expect([999, 1000, 4999, 5000].map((v) => expr(e, [v], ["a"]))).toEqual(["small", "medium", "medium", "large"]);
  });
});

describe("sort order matches Excel's", () => {
  const values: Value[] = ["banana", 10, null, true, "Apple", xerror("#N/A"), 2, "co-op", "coop", false, "apple pie", xdate(0)];
  const sorted = (dir: "asc" | "desc") =>
    values.map((v) => [v]).sort((a, b) => compareRows(a, b, [{ index: 0, dir }])).map((r) => r[0]);

  it("ascending: numbers and dates, text ignoring case and hyphens, FALSE, TRUE, errors, blanks", () => {
    expect(sorted("asc")).toEqual([xdate(0), 2, 10, "Apple", "apple pie", "banana", "coop", "co-op", false, true, xerror("#N/A"), null]);
  });

  it("descending reverses everything but blanks, which stay last", () => {
    expect(sorted("desc")).toEqual([xerror("#N/A"), true, false, "co-op", "coop", "banana", "apple pie", "Apple", 10, 2, xdate(0), null]);
  });

  it("is stable", () => {
    const t: Table = { columns: ["k", "i"], rows: [["b", 1], ["A", 2], ["a", 3], ["B", 4]] };
    expect(run(t, [{ op: "sort", by: [{ col: "k", dir: "asc" }] }]).table.rows.map((r) => r[1])).toEqual([2, 3, 1, 4]);
  });
});

describe("dates", () => {
  it("convert Excel serials in both date systems", () => {
    expect(daysFromSerial(45658, "1900")).toBe(daysFromYmd(2025, 1, 1));
    expect(daysFromSerial(45658 - 1462, "1904")).toBe(daysFromYmd(2025, 1, 1));
    expect(daysFromSerial(1, "1900")).toBe(daysFromYmd(1900, 1, 1));
    expect(daysFromSerial(61, "1900")).toBe(daysFromYmd(1900, 3, 1));
    for (const s of [1, 59, 61, 45658]) expect(serialFromDays(daysFromSerial(s, "1900"), "1900")).toBe(s);
  });

  it("ISO weeks match Excel's ISOWEEKNUM, across year ends", () => {
    expect(isoWeek(daysFromYmd(2025, 1, 1))).toBe(1);
    expect(isoWeek(daysFromYmd(2024, 12, 30))).toBe(1);
    expect(isoWeek(daysFromYmd(2021, 1, 3))).toBe(53);
    expect(isoWeek(daysFromYmd(2025, 3, 8))).toBe(10);
  });

  it("text dates load with the column's day-first evidence; unreadable cells become #VALUE!", () => {
    const sheet = sheetFromRows("D", [["when", "n"], ["13/01/2025", "1"], ["02/03/2025", "2"], ["soon", "3"], ["", "4"]]);
    const cat = buildCatalog({ sheets: [sheet], tables: [], names: [], sources: [], validations: [] }, { exemplars: false });
    const e = cat.entities[0]!;
    const { table, stats } = loadEntity({ ...e, columns: e.columns.map((c) => (c.name === "when" ? { ...c, kind: "date", dateFormats: ["dd/mm/yyyy"] } : c)) }, sheet, "1900");
    expect(table.rows.map((r) => r[0])).toEqual([xdate(daysFromYmd(2025, 1, 13)), xdate(daysFromYmd(2025, 3, 2)), xerror("#VALUE!"), null]);
    expect(stats.unreadable).toBe(1);
  });
});

describe("lookups and joins", () => {
  const orders: Table = { columns: ["id", "code"], rows: [[1, "N"], [2, "s"], [3, "X"], [4, null]] };
  const regions: Table = { columns: ["code", "name", "id"], rows: [["N", "North", 10], ["S", "South", 20]] };

  it("lookup keeps every row, matches ignoring case, and names colliding columns after their table", () => {
    const r = evaluate(query("Orders", [{ op: "lookup", with: "Regions", on: { left: "code", right: "code" } }]), { Orders: orders, Regions: regions });
    expect(r.table.columns).toEqual(["id", "code", "name", "id (Regions)"]);
    expect(r.table.rows).toEqual([[1, "N", "North", 10], [2, "s", "South", 20], [3, "X", null, null], [4, null, null, null]]);
  });

  it("a lookup key that repeats in the data is refused, though the catalog said unique", () => {
    const dupes: Table = { columns: ["code", "name"], rows: [["N", "North"], ["n", "Nord"]] };
    const r = evaluate(query("Orders", [{ op: "lookup", with: "R", on: { left: "code", right: "code" } }]), { Orders: orders, R: dupes });
    expect(r.issues).toEqual([expect.objectContaining({ code: "LOOKUP_KEY_NOT_UNIQUE", blocking: true })]);
  });

  it("an inner join drops unmatched rows; a left join keeps them with blanks", () => {
    const on = { left: "code", right: "code" };
    const inner = evaluate(query("Orders", [{ op: "join", with: "Regions", on, kind: "inner" }]), { Orders: orders, Regions: regions });
    const left = evaluate(query("Orders", [{ op: "join", with: "Regions", on, kind: "left" }]), { Orders: orders, Regions: regions });
    expect(inner.table.rows).toHaveLength(2);
    expect(left.table.rows).toHaveLength(4);
    expect(left.table.columns).toEqual(["id", "code", "code (Regions)", "name", "id (Regions)"]);
  });
});

describe("parameters", () => {
  it("take their value from the commit request, typed by the declaration", () => {
    const t: Table = { columns: ["d"], rows: [[xdate(daysFromYmd(2025, 1, 1))], [xdate(daysFromYmd(2025, 2, 1))]] };
    const plan = query("T", [{ op: "filter", predicate: { op: "gte", left: col("d"), right: { type: "param", name: "asOf" } } }], [
      { name: "asOf", valueType: "date" },
    ]);
    expect(evaluate(plan, { T: t }, { params: { ASOF: "2025-01-15" } }).table.rows).toHaveLength(1);
  });
});

describe("the evaluator handles every variant the contract defines", () => {
  it("covers each discriminator in plan.schema.json", () => {
    const schema = readFileSync(resolve(process.cwd(), "../contract/schema/plan.schema.json"), "utf8");
    const consts = new Set([...schema.matchAll(/"const"\s*:\s*"([^"]+)"/g)].map((m) => m[1]!));
    const handled = new Set<string>(Object.values(HANDLED).flat());
    const sortDirs = new Set(["asc", "desc", "inner", "left"]);
    expect([...consts].filter((c) => !handled.has(c) && !sortDirs.has(c))).toEqual([]);
    expect([...handled].filter((h) => !consts.has(h))).toEqual([]);
  });
});

describe("performance", () => {
  it("aggregates 100,000 rows in well under 3 seconds", () => {
    const regions = ["North", "South", "East", "West", "Central"];
    const rows: Value[][] = Array.from({ length: 100_000 }, (_, i) => [regions[i % 5]!, (i * 37) % 1000, i % 7 === 0 ? "returned" : "shipped"]);
    const t: Table = { columns: ["region", "amount", "status"], rows };
    const start = performance.now();
    const r = run(t, [
      { op: "filter", predicate: { op: "ne", left: col("status"), right: lit("returned") } },
      { op: "aggregate", groupBy: ["region"], measures: [{ fn: "sum", of: "amount", as: "revenue" }, { fn: "countDistinct", of: "amount", as: "n" }] },
      { op: "sort", by: [{ col: "revenue", dir: "desc" }] },
    ]);
    const ms = performance.now() - start;
    expect(r.table.rows).toHaveLength(5);
    expect(ms).toBeLessThan(3000);
  });
});
