import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { buildCatalog } from "../../catalog/build";
import { sheetFromRows } from "../../catalog/__tests__/fixtures";
import type { QueryPlan } from "../ir";
import { preparePreview, PreviewRefused } from "../preview";
import type { Table } from "../table";
import { daysFromYmd, xdate, xerror } from "../value";
import { columnFormat, MAX_CELLS_PER_WRITE, planWrite, validSheetName, WriteRefused } from "../write";

const table: Table = {
  columns: ["region", "revenue", "since", "note"],
  rows: [
    ["North", 1200.5, xdate(daysFromYmd(2025, 1, 1)), "=HYPERLINK(\"x\")"],
    ["South", null, null, "007"],
    ["East", 10, xdate(daysFromYmd(2025, 1, 2)), xerror("#N/A")],
  ],
};
const types = [
  { name: "region", type: "categorical" },
  { name: "revenue", type: "currency:INR" },
  { name: "since", type: "date" },
  { name: "note", type: "string" },
];

describe("write plans", () => {
  it("a new-sheet sink writes only to the sheet it creates, from its anchor", () => {
    const w = planWrite(table, types, undefined, { mode: "newSheet", name: "Revenue 2", anchor: "A1" }, { dateSystem: "1900" });
    expect(w.target).toEqual({ kind: "newSheet", sheetName: "Revenue 2" });
    expect(w.address).toBe("'Revenue 2'!A1:D4");
    expect([w.rows, w.cols]).toEqual([4, 4]);
  });

  it("writes text as text, so formulas, codes and dates in strings survive; dates as serials", () => {
    const w = planWrite(table, types, undefined, { mode: "newSheet", name: "R", anchor: "A1" }, { dateSystem: "1900" });
    expect(w.values[0]).toEqual(["region", "revenue", "since", "note"]);
    expect(w.values[1]).toEqual(["North", 1200.5, 45658, "=HYPERLINK(\"x\")"]);
    expect(w.numberFormats[1]).toEqual(["@", "\"₹\"#,##0.00", "yyyy-mm-dd", "@"]);
    expect(w.values[2]).toEqual(["South", "", "", "007"]);
    expect(w.numberFormats[2]![3]).toBe("@");
    expect(w.values[3]![3]).toBe("#N/A");
    expect(w.numberFormats[3]![3]).toBe("General"); // Excel reads it back as the error value
  });

  it("uses the workbook's date system", () => {
    const w = planWrite(table, types, undefined, { mode: "newSheet", name: "R", anchor: "A1" }, { dateSystem: "1904" });
    expect(w.values[1]![2]).toBe(45658 - 1462);
  });

  it("an anchor sink writes exactly rows × columns from the cell the user chose", () => {
    const w = planWrite(table, types, undefined, { mode: "anchor" }, {
      dateSystem: "1900",
      anchor: { sheetId: "{S}", sheetName: "Summary", address: "$C$5" },
    });
    expect(w.target).toEqual({ kind: "existing", sheetId: "{S}", sheetName: "Summary" });
    expect([w.top, w.left]).toEqual([5, 2]);
    expect(w.address).toBe("Summary!C5:F8");
  });

  it("refuses without an anchor, with a range as anchor, past the sheet's edge, and for templates (M7)", () => {
    const sink = { mode: "anchor" as const };
    expect(() => planWrite(table, types, undefined, sink, { dateSystem: "1900" })).toThrow(WriteRefused);
    expect(() => planWrite(table, types, undefined, sink, { dateSystem: "1900", anchor: { sheetId: "s", sheetName: "S", address: "A1:B2" } })).toThrow(WriteRefused);
    expect(() => planWrite(table, types, undefined, sink, { dateSystem: "1900", anchor: { sheetId: "s", sheetName: "S", address: "XFD1" } })).toThrow(/doesn't fit/);
    expect(() => planWrite(table, types, undefined, { mode: "template", templateId: "t", headerRow: 1, firstDataRow: 2 }, { dateSystem: "1900" })).toThrow(/M7/);
  });

  it("chunks a 100,000-row write under the per-request cell budget", () => {
    const big: Table = { columns: ["a", "b", "c"], rows: Array.from({ length: 100_000 }, (_, i) => [i, `r${i}`, i % 2 === 0]) };
    const w = planWrite(big, [{ name: "a", type: "number" }, { name: "b", type: "string" }, { name: "c", type: "boolean" }], undefined, { mode: "newSheet", name: "Big", anchor: "A1" }, { dateSystem: "1900" });
    expect(w.chunks.every((c) => c.count * w.cols <= MAX_CELLS_PER_WRITE)).toBe(true);
    expect(w.chunks.reduce((n, c) => n + c.count, 0)).toBe(100_001);
  });

  it("formats columns by their checked type", () => {
    expect(["number", "percent", "currency:USD", "currency", "datetime", "boolean"].map(columnFormat)).toEqual([
      "General", "0.0%", "\"$\"#,##0.00", "#,##0.00", "yyyy-mm-dd hh:mm", "General",
    ]);
  });

  it("validates sheet names as Excel does", () => {
    expect(["Revenue", "Q1 2025", "a".repeat(31)].every(validSheetName)).toBe(true);
    expect(["", "a".repeat(32), "Q1/Q2", "'x", "History"].some(validSheetName)).toBe(false);
  });
});

describe("preview", () => {
  const sheet = sheetFromRows("Sales", [["region", "amount"], ["North", "10"], ["South", "#N/A"], ["north", "5"]]);
  const snapshot = { sheets: [sheet], tables: [], names: [], sources: [], validations: [] };
  const catalog = buildCatalog(snapshot, { exemplars: false });
  const e = catalog.entities[0]!;
  const plan: QueryPlan = {
    kind: "query",
    source: "Sales",
    steps: [{ op: "aggregate", groupBy: ["region"], measures: [{ fn: "sum", of: "amount", as: "total" }] }],
    sink: { mode: "newSheet", name: "Totals", anchor: "A1" },
    params: [],
    bindings: { entities: [{ name: e.name, id: e.id, sheetId: e.sheetId, columns: e.columns.map((c) => ({ name: c.name, id: c.id })) }] },
  };
  const output = [{ name: "region", type: "categorical" }, { name: "total", type: "number" }];

  it("reports exact dimensions, guards the source region, and blocks on error cells until excluded", () => {
    const p = preparePreview({ plan, output, catalog, snapshot });
    expect([p.rows, p.cols]).toEqual([3, 2]);
    expect(p.write?.address).toBe("Totals!A1:B3");
    expect(p.guards).toEqual([{ label: "Sales", sheetId: e.sheetId, address: e.address, hash: e.contentHash }]);
    expect(p.issues.find((i) => i.code === "ERROR_CELLS")).toMatchObject({ count: 1, blocking: true });

    const excluded = preparePreview({ plan, output, catalog, snapshot, excludeErrorCells: true });
    expect(excluded.issues.some((i) => i.blocking)).toBe(false);
    expect(excluded.result.table.rows).toEqual([["North", 15], ["South", null]]);
  });

  it("refuses when the scan no longer has what the plan was bound to", () => {
    const stale = { ...plan, bindings: { entities: [{ ...plan.bindings.entities[0]!, columns: [{ name: "amt", id: "h:gone" }] }] } };
    expect(() => preparePreview({ plan: stale, output, catalog, snapshot })).toThrow(PreviewRefused);
  });

  it("refuses an anchor that would overwrite the data the plan reads", () => {
    const anchored = { ...plan, sink: { mode: "anchor" as const } };
    const inside = preparePreview({ plan: anchored, output, catalog, snapshot, excludeErrorCells: true, anchor: { sheetId: e.sheetId, sheetName: "Sales", address: "B3" } });
    expect(inside.issues.find((i) => i.code === "OVERWRITES_SOURCE")).toMatchObject({ blocking: true });
    const beside = preparePreview({ plan: anchored, output, catalog, snapshot, excludeErrorCells: true, anchor: { sheetId: e.sheetId, sheetName: "Sales", address: "D1" } });
    expect(beside.issues.some((i) => i.blocking)).toBe(false);
    expect(beside.write?.address).toBe("Sales!D1:E3");
  });

  it("states that an anchor sink's extent waits for a cell", () => {
    const p = preparePreview({ plan: { ...plan, sink: { mode: "anchor" } }, output, catalog, snapshot });
    expect(p.write).toBeNull();
    expect([p.rows, p.cols]).toEqual([3, 2]);
  });
});

describe("the committer", () => {
  it("never calls an Office.js API that clears the undo stack", () => {
    const source = readFileSync(resolve(process.cwd(), "src/excel/commit.ts"), "utf8").replace(/^export const UNDO_CLEARING_APIS.*$/m, "");
    for (const call of [/insertWorksheetsFromBase64\s*\(/, /\.delete\s*\(/, /\.copy\s*\(/, /\.visibility\s*=/]) {
      expect(source).not.toMatch(call);
    }
  });

  it("writes inside one undo group", () => {
    const source = readFileSync(resolve(process.cwd(), "src/excel/commit.ts"), "utf8");
    expect(source).toMatch(/Excel\.run\(\{ mergeUndoGroup: true \}/);
  });
});
