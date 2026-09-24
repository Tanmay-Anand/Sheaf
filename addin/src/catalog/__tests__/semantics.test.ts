import type { CatalogColumn, CatalogEntity, WorkbookCatalog } from "@sheaf/contract/catalog";
import { describe, expect, it } from "vitest";
import { buildCatalog } from "../build";
import { MAX_CELLS_PER_READ, planRowChunks } from "../chunks";
import { dateFrom1900, dateFrom1904, inferDateSystem } from "../dateSystem";
import { profileColumn } from "../profile";
import type { CellValue } from "../types";
import { isBlank } from "../values";
import { emptyWorkbook, sheetFromRows } from "./fixtures";

const NOW = new Date("2026-09-25T10:00:00Z");

function entity(name: string, catalog: WorkbookCatalog): CatalogEntity {
  const e = catalog.entities.find((x) => x.name === name);
  if (!e) throw new Error(`no entity ${name}`);
  return e;
}

function col(e: CatalogEntity, name: string): CatalogColumn {
  const c = e.columns.find((x) => x.name === name);
  if (!c) throw new Error(`no column ${name}`);
  return c;
}

describe("dependent classes", () => {
  const data = sheetFromRows("Data", [
    ["Qty", "Price", "Amount", "Code"],
    ["2", "10", "20", "A"],
    ["3", "20", "60", "B"],
    ["4", "30", "120", "C"],
  ]);
  const summary = sheetFromRows("Summary", [["x", "0"], ["x", "0"], ["x", "0"], ["x", "0"], ["x", "0"], ["x", "0"]]);
  summary.formulas = [
    [null, "=SUM(Data!A:D)"],
    [null, "=Data!C3"],
    [null, "=SUM(Data!C:C)"],
    [null, "=SUM(Data!B2:B4)"],
    [null, "=SUM(Data!3:3)"],
    [null, "=SUM(Data!A2:B3)"],
  ];
  const catalog = buildCatalog(emptyWorkbook([data, summary]), { exemplars: false, now: NOW });
  const d = entity("Data", catalog);
  const at = (column: string, location: string) => {
    const dep = col(d, column).dependents.find((x) => x.location === location);
    if (!dep) throw new Error(`${column} has no dependent at ${location}`);
    return `${dep.refClass}${dep.fixedRows ? ", fixed rows" : ""}`;
  };

  it("classifies each reference by how it covers the table's columns and rows", () => {
    expect(at("Qty", "Summary!B1")).toBe("spanning"); // =SUM(A:D): dropping a column changes it silently
    expect(at("Amount", "Summary!B2")).toBe("exclusive, fixed rows"); // =C5-style single cell
    expect(at("Amount", "Summary!B3")).toBe("wholeColumn"); // =SUM(C:C)
    expect(at("Price", "Summary!B4")).toBe("exclusive"); // the full data range of one column
    expect(at("Code", "Summary!B5")).toBe("wholeRow, fixed rows"); // =SUM(3:3)
    expect(at("Qty", "Summary!B6")).toBe("spanning, fixed rows"); // two columns, part of the rows
  });

  it("marks [@Col] references as naming a row", () => {
    const sheet = sheetFromRows("Sales", [
      ["Qty", "Price", "Amount"],
      ["2", "10", "20"],
      ["3", "20", "60"],
    ]);
    const report = sheetFromRows("Report", [["0"], ["0"]]);
    report.formulas = [["=SUM(Sales[Amount])"], ["=SUM(Sales[[Qty]:[Price]])"]];
    const wb = {
      ...emptyWorkbook([sheet, report]),
      tables: [{ id: "{T-9}", name: "Sales", sheet: "Sales", address: "Sales!A1:C3", showHeaders: true, showTotals: false, columns: ["Qty", "Price", "Amount"] }],
    };
    sheet.formulas = [[null, null, null], [null, null, "=[@Qty]*[@Price]"], [null, null, "=[@Qty]*[@Price]"]];
    const t = entity("Sales", buildCatalog(wb, { exemplars: false, now: NOW }));
    const qty = col(t, "Qty").dependents;
    expect(qty.find((x) => x.location === "Sales!C2:C3")).toMatchObject({ refClass: "exclusive", fixedRows: true });
    expect(qty.find((x) => x.location === "Report!A2")).toMatchObject({ refClass: "spanning", fixedRows: false });
    expect(col(t, "Amount").dependents).toEqual([
      { kind: "formula", location: "Report!A1", detail: "=SUM(Sales[Amount])", refClass: "exclusive", fixedRows: false },
    ]);
  });
});

describe("stable ids", () => {
  const rows: CellValue[][] = [
    ["Region", "Qty", "Amount"],
    ["North", "2", "20"],
    ["South", "3", "60"],
  ];
  const first = buildCatalog(emptyWorkbook([sheetFromRows("Sales", rows)]), { exemplars: false, now: NOW });
  const e1 = entity("Sales", first);

  it("names a region by its worksheet id and header anchor, and a column by its header", () => {
    expect(e1.id).toBe("r:ws-Sales:A1");
    expect(e1.sheetId).toBe("ws-Sales");
    expect(new Set(e1.columns.map((c) => c.id)).size).toBe(3);
    expect(e1.columns.every((c) => c.id.startsWith("h:"))).toBe(true);
  });

  it("keeps every id when the region moves or grows", () => {
    const moved = buildCatalog(emptyWorkbook([sheetFromRows("Sales", rows, 6, 3)]), { exemplars: false, now: NOW, previous: first });
    expect(entity("Sales", moved).id).toBe(e1.id);
    expect(entity("Sales", moved).columns.map((c) => c.id)).toEqual(e1.columns.map((c) => c.id));

    const grown = buildCatalog(emptyWorkbook([sheetFromRows("Sales", [...rows, ["East", "4", "80"]])]), {
      exemplars: false,
      now: NOW,
      previous: first,
    });
    expect(entity("Sales", grown).id).toBe(e1.id);

    // Without the previous catalog the moved region would get a new, anchor-based id.
    const fresh = buildCatalog(emptyWorkbook([sheetFromRows("Sales", rows, 6, 3)]), { exemplars: false, now: NOW });
    expect(entity("Sales", fresh).id).toBe("r:ws-Sales:D6");
  });

  it("gives a renamed header a new column id, keeps the rest, and says so", () => {
    const renamed = buildCatalog(
      emptyWorkbook([sheetFromRows("Sales", [["Region", "Qty", "Total"], ...rows.slice(1)])]),
      { exemplars: false, now: NOW, previous: first },
    );
    const e2 = entity("Sales", renamed);
    expect(e2.id).toBe(e1.id);
    expect(col(e2, "Region").id).toBe(col(e1, "Region").id);
    expect(col(e2, "Total").id).not.toBe(col(e1, "Amount").id);
    expect(renamed.warnings).toContain('Sales: column "Amount" is now "Total"; saved plans that used "Amount" will ask before running.');
  });

  it("uses Excel's own ids for tables and their columns", () => {
    const wb = {
      ...emptyWorkbook([sheetFromRows("Sales", rows)]),
      tables: [
        { id: "{T-1}", name: "SalesTbl", sheet: "Sales", address: "Sales!A1:C3", showHeaders: true, showTotals: false, columns: ["Region", "Qty", "Amount"], columnIds: ["1", "2", "3"] },
      ],
    };
    const t = entity("SalesTbl", buildCatalog(wb, { exemplars: false, now: NOW }));
    expect(t.id).toBe("t:{T-1}");
    expect(t.columns.map((c) => c.id)).toEqual(["tc:1", "tc:2", "tc:3"]);
  });

  it("never gives two columns of one entity the same id, even with duplicate headers", () => {
    const dupes = buildCatalog(emptyWorkbook([sheetFromRows("D", [["a", "A"], ["1", "2"], ["3", "4"]])]), { exemplars: false, now: NOW });
    const ids = entity("D", dupes).columns.map((c) => c.id);
    expect(new Set(ids).size).toBe(ids.length);
  });
});

describe("column flags", () => {
  const profile = (cells: CellValue[], formats?: string[]) =>
    profileColumn({ id: "c", name: "value", letter: "A", index: 0, cells, ...(formats ? { formats } : {}) }, { exemplars: false }).column;

  it("flags error cells", () => {
    expect(profile([1, 2, "#DIV/0!", 4]).mayContainErrors).toBe(true);
    expect(profile([1, 2, 3]).mayContainErrors).toBe(false);
  });

  it("flags numbers stored as text next to real numbers, and text-formatted numbers", () => {
    const mixed = profile([10, 20, "30", 40]);
    expect(mixed.numbersStoredAsText).toBe(true);
    expect(mixed.warnings).toContain("1 number is stored as text; Excel's SUM and AVERAGE skip them.");
    expect(profile(["10", "20"], ["@", "@"]).numbersStoredAsText).toBe(true);
    expect(profile([10, 20, 30]).numbersStoredAsText).toBe(false);
  });

  it("treats an empty string as blank", () => {
    expect(isBlank("")).toBe(true);
    expect(isBlank("   ")).toBe(true);
    expect(isBlank(null)).toBe(true);
    expect(isBlank(0)).toBe(false);
  });
});

describe("date system", () => {
  it("converts serials the way each system does, including 1900's fictitious leap day", () => {
    expect(dateFrom1900(1)).toEqual({ y: 1900, m: 1, d: 1 });
    expect(dateFrom1900(59)).toEqual({ y: 1900, m: 2, d: 28 });
    expect(dateFrom1900(60)).toEqual({ y: 1900, m: 2, d: 29 });
    expect(dateFrom1900(61)).toEqual({ y: 1900, m: 3, d: 1 });
    expect(dateFrom1900(45658)).toEqual({ y: 2025, m: 1, d: 1 });
    expect(dateFrom1904(0)).toEqual({ y: 1904, m: 1, d: 1 });
    expect(dateFrom1904(45658 - 1462)).toEqual({ y: 2025, m: 1, d: 1 });
  });

  it("infers the system from a displayed date", () => {
    expect(inferDateSystem([{ serial: 45658, text: "1/1/2025", numberFormat: "m/d/yyyy" }])).toBe("1900");
    expect(inferDateSystem([{ serial: 44196, text: "1/1/2025", numberFormat: "m/d/yyyy" }])).toBe("1904");
    expect(inferDateSystem([{ serial: 45658, text: "01-Jan", numberFormat: "dd-mmm" }])).toBe("1900");
    expect(inferDateSystem([{ serial: 45658.75, text: "1/1/25 18:00", numberFormat: "m/d/yy h:mm" }])).toBe("1900");
  });

  it("says unknown rather than guess", () => {
    expect(inferDateSystem([])).toBe("unknown");
    expect(inferDateSystem([{ serial: 45658, text: "Wednesday", numberFormat: "dddd" }])).toBe("unknown");
    expect(
      inferDateSystem([
        { serial: 45658, text: "1/1/2025", numberFormat: "m/d/yyyy" },
        { serial: 44196, text: "1/1/2025", numberFormat: "m/d/yyyy" },
      ]),
    ).toBe("unknown");
  });

  it("is recorded in the catalog", () => {
    const wb = { ...emptyWorkbook([]), dateProbes: [{ serial: 44196, text: "1/1/2025", numberFormat: "m/d/yyyy" }] };
    expect(buildCatalog(wb, { exemplars: false, now: NOW }).dateSystem).toBe("1904");
    expect(buildCatalog(emptyWorkbook([]), { exemplars: false, now: NOW }).dateSystem).toBe("unknown");
  });
});

describe("chunked reads", () => {
  it("splits a large sheet into row blocks within the cell budget, covering every row once", () => {
    const chunks = planRowChunks(100_000, 20);
    expect(chunks.every((c) => c.count * 20 <= MAX_CELLS_PER_READ)).toBe(true);
    expect(chunks.reduce((n, c) => n + c.count, 0)).toBe(100_000);
    expect(chunks[0]).toEqual({ start: 0, count: MAX_CELLS_PER_READ / 20 });
    chunks.slice(1).forEach((c, i) => expect(c.start).toBe(chunks[i]!.start + chunks[i]!.count));
  });

  it("reads a small sheet in one request and a very wide one a row at a time", () => {
    expect(planRowChunks(50, 10)).toEqual([{ start: 0, count: 50 }]);
    expect(planRowChunks(3, MAX_CELLS_PER_READ * 2)).toHaveLength(3);
    expect(planRowChunks(0, 5)).toEqual([]);
  });
});
