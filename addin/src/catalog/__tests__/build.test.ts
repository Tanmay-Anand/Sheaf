import { describe, expect, it } from "vitest";
import type { CatalogColumn, CatalogEntity } from "@sheaf/contract/catalog";
import { buildCatalog } from "../build";
import { CORPUS_SHEETS, corpusRows, corpusWorkbook, emptyWorkbook, sheetFromRows } from "./fixtures";

const NOW = new Date("2026-09-24T10:00:00Z");

function entity(name: string, entities: CatalogEntity[]): CatalogEntity {
  const e = entities.find((x) => x.name === name);
  if (!e) throw new Error(`no entity ${name}`);
  return e;
}

function col(e: CatalogEntity, name: string): CatalogColumn {
  const c = e.columns.find((x) => x.name === name);
  if (!c) throw new Error(`no column ${e.name}.${name}`);
  return c;
}

describe("catalog of the corpus workbook", () => {
  const catalog = buildCatalog(corpusWorkbook(), { exemplars: true, now: NOW });

  it("finds one entity per sheet, named after the sheet", () => {
    expect(catalog.entities.map((e) => [e.name, e.address, e.dataRowCount])).toEqual([
      ["Orders", "Orders!A1:L76", 75],
      ["Regions", "Regions!A1:E7", 6],
      ["Budget", "Budget!A1:I33", 32],
      ["Tasks", "Tasks!A1:K26", 25],
      ["Inventory", "Inventory!A1:I25", 24],
      ["Q1 Report", "'Q1 Report'!A3:I22", 11],
    ]);
  });

  it("infers the Orders schema", () => {
    const orders = entity("Orders", catalog.entities);
    const kinds = Object.fromEntries(orders.columns.map((c) => [c.name, c.kind]));
    expect(kinds).toEqual({
      order_id: "string",
      order_date: "date",
      region: "categorical",
      channel: "categorical",
      product_category: "categorical",
      product_name: "string",
      quantity: "number",
      unit_price: "currency",
      amount: "currency",
      discount_pct: "percent",
      status: "categorical",
      customer_type: "categorical",
    });
    expect(col(orders, "order_id").keyCandidate).toBe(true);
    expect(col(orders, "order_date").dateFormats).toEqual(["yyyy-mm-dd"]);
    expect(col(orders, "discount_pct").percentScale).toBe(100);
    expect(col(orders, "status").distinctCount).toBe(3);
    expect(col(orders, "region").distinctCount).toBe(6);
    // No symbol, no number format: the unit is left for the user to confirm.
    expect(col(orders, "amount").unit).toBeUndefined();
    expect(col(orders, "amount").warnings).toContain("Looks like money, but the currency isn't stated; confirm the unit.");
    expect(col(orders, "status").exemplars).toEqual(["shipped", "returned", "pending"]);
  });

  it("copes with the ugly sheet: mixed dates, a stray $, sparse notes", () => {
    const ugly = entity("Q1 Report", catalog.entities);
    const date = col(ugly, "Date");
    expect(date.kind).toBe("date");
    // 01/15/2025 proves the numeric dates are month-first.
    expect(date.dateFormats).toEqual(["Mon d yyyy", "Month d yyyy", "d-Mon-yy", "mm/dd/yyyy", "yyyy-Mon-dd", "yyyy-mm-dd"]);
    expect(date.warnings[0]).toBe("6 text date formats in one column: Mon d yyyy, Month d yyyy, d-Mon-yy, mm/dd/yyyy, yyyy-Mon-dd, yyyy-mm-dd.");

    const amount = col(ugly, "Amount");
    expect(amount.kind).toBe("currency");
    expect(amount.unit).toBeUndefined();
    expect(amount.warnings).toContain(
      "1 value carries a currency marker (USD) and the rest don't; the marker was not used as the unit.",
    );
    expect(col(ugly, "Qty").kind).toBe("number");
    expect(col(ugly, "Rep Name").kind).toBe("categorical");
    expect(col(ugly, "Notes").nullRate).toBeCloseTo(7 / 11, 4);
    expect(ugly.skippedRows.filter((s) => s.reason === "totals")).toHaveLength(4);
  });

  it("proposes the Orders → Regions join, unapproved", () => {
    const j = catalog.joinCandidates.find((x) => x.fromEntity === "Orders" && x.fromColumn === "region");
    expect(j).toEqual({
      fromEntity: "Orders",
      fromColumn: "region",
      toEntity: "Regions",
      toColumn: "code",
      cardinality: "many-to-one",
      overlap: 1,
      confidence: 0.9,
      approved: false,
    });
  });

  it("changes the content hash when a cell changes, and only for that entity", () => {
    const wb = corpusWorkbook();
    wb.sheets[0]!.values[5]![8] = "99999";
    const after = buildCatalog(wb, { exemplars: true, now: NOW });
    const changed = after.entities.filter((e, i) => e.contentHash !== catalog.entities[i]!.contentHash).map((e) => e.name);
    expect(changed).toEqual(["Orders"]);
  });
});

describe("egress: exemplars off", () => {
  it("serialises no cell values at all", () => {
    const catalog = buildCatalog(corpusWorkbook(), { exemplars: false, now: NOW });
    const json = JSON.stringify(catalog);

    const headers = new Set<string>();
    const values = new Set<string>();
    for (const file of Object.values(CORPUS_SHEETS)) {
      const rows = corpusRows(file);
      for (const row of rows) for (const cell of row) values.add(cell.trim());
    }
    for (const e of catalog.entities) for (const c of e.columns) headers.add(c.name);

    // Words that are both a header somewhere and a value elsewhere ("North" is not a header) are
    // schema, not data. Numbers and short tokens can collide with statistics, so check text only.
    const leaked = [...values].filter(
      (v) => v.length >= 4 && !/^[\d.,$%/-]+$/.test(v) && !headers.has(v) && json.includes(JSON.stringify(v).slice(1, -1)),
    );
    expect(leaked).toEqual([]);
    expect(catalog.entities.every((e) => e.columns.every((c) => c.exemplars === undefined))).toBe(true);
  });
});

describe("dependency map", () => {
  const data = sheetFromRows("Data", [
    ["Qty", "Price", "Amount", "Code"],
    ["2", "10", "20", "A"],
    ["3", "20", "60", "B"],
    ["4", "30", "120", "C"],
  ]);
  data.formulas = [
    [null, null, null, null],
    [null, null, "=A2*B2", null],
    [null, null, "=A3*B3", null],
    [null, null, "=A4*B4", null],
  ];
  const summary = sheetFromRows("Summary", [
    ["Total", "20"],
    ["Avg price", "20"],
    ["First qty", "2"],
    ["Max price", "30"],
    ["Dynamic", "0"],
    ["Linked", "0"],
    ["Label", "x"],
  ]);
  summary.formulas = [
    [null, "=SUM(Data!C:C)"],
    [null, "=AVERAGE(Data!$B$2:$B$4)"],
    [null, "='Data'!A2"],
    [null, "=MAX(Prices)"],
    [null, '=SUM(INDIRECT("Data!A"&B1))'],
    [null, "=[Budget.xlsx]Sheet1!A1"],
    [null, '=IF(Data!D2="A","secret label","other")'],
  ];
  const wb = {
    ...emptyWorkbook([data, summary]),
    names: [{ name: "Prices", formula: "=Data!$B$2:$B$4" }],
    sources: [
      { kind: "chartSeries" as const, location: "Summary chart 1 · series Amount", sheet: "Summary", text: "=Data!$C$2:$C$4" },
      { kind: "conditionalFormat" as const, location: "Data!A2:D4", sheet: "Data", text: "=$D2=1" },
    ],
  };

  it("finds every reader of every column, grouping a column of formulas", () => {
    const catalog = buildCatalog(wb, { exemplars: true, now: NOW });
    const d = entity("Data", catalog.entities);
    const where = (name: string) => col(d, name).dependents.map((x) => `${x.kind} ${x.location}`).sort();

    expect(where("Qty")).toEqual(["formula Data!C2:C4", "formula Summary!B3"]);
    expect(where("Price")).toEqual(["formula Data!C2:C4", "formula Summary!B2", "formula Summary!B4", "namedRange Prices"]);
    // Amount's own formulas live in Amount: they go away with it and are not dependents.
    expect(where("Amount")).toEqual(["chartSeries Summary chart 1 · series Amount", "formula Summary!B1"]);
    expect(where("Code")).toEqual(["conditionalFormat Data!A2:D4", "formula Summary!B7"]);
    expect(col(d, "Qty").dependents.find((x) => x.location === "Data!C2:C4")!.detail).toBe("3 formulas, e.g. =A2*B2");
  });

  it("reports what it cannot trace", () => {
    const catalog = buildCatalog(wb, { exemplars: true, now: NOW });
    expect(catalog.untraceable.map((u) => `${u.kind} ${u.location}`)).toEqual(["indirect Summary!B5", "externalLink Summary!B6"]);
    expect(catalog.warnings).toContain(
      "2 references can't be traced (INDIRECT, OFFSET or links to other workbooks); dependency checks can't be complete.",
    );
  });

  it("redacts string literals in formula details when exemplars are off", () => {
    const catalog = buildCatalog(wb, { exemplars: false, now: NOW });
    const code = col(entity("Data", catalog.entities), "Code");
    expect(code.dependents.find((d) => d.location === "Summary!B7")!.detail).toBe('=IF(Data!D2="…","…","…")');
    expect(JSON.stringify(catalog)).not.toContain("secret label");
  });
});

describe("Excel Tables", () => {
  const sheet = sheetFromRows("Sales", [
    ["Region", "Qty", "Price", "Amount"],
    ["North", "2", "10", "20"],
    ["South", "3", "20", "60"],
    ["Total", "", "", "80"],
    ["", "", "", ""],
    ["Report", "", "", ""],
    ["Grand", "=SUM(Sales[Amount])", "", ""],
  ]);
  sheet.formulas = [
    [null, null, null, null],
    [null, null, null, "=[@Qty]*[@Price]"],
    [null, null, null, "=[@Qty]*[@Price]"],
    [null, null, null, "=SUBTOTAL(109,[Amount])"],
    [null, null, null, null],
    [null, null, null, null],
    [null, "=SUM(Sales[Amount])", null, null],
  ];
  const wb = {
    ...emptyWorkbook([sheet]),
    tables: [{ id: "{T-1}", name: "Sales", sheet: "Sales", address: "Sales!A1:D4", showHeaders: true, showTotals: true, columns: ["Region", "Qty", "Price", "Amount"] }],
  };
  const catalog = buildCatalog(wb, { exemplars: true, now: NOW });

  it("uses the table as an entity and keeps its cells out of region detection", () => {
    const t = entity("Sales", catalog.entities);
    expect(t.kind).toBe("table");
    expect(t.tableName).toBe("Sales");
    expect(t.dataRowCount).toBe(2);
    expect(t.skippedRows).toEqual([{ row: 4, reason: "totals" }]);
    expect(catalog.entities.map((e) => e.name)).toEqual(["Sales", "Sales 2"]);
  });

  it("resolves structured references, including implicit [@Col] inside the table", () => {
    const t = entity("Sales", catalog.entities);
    expect(col(t, "Qty").dependents.map((d) => d.location)).toEqual(["Sales!D2:D3"]);
    expect(col(t, "Amount").dependents.map((d) => d.location)).toEqual(["Sales!B7"]);
    expect(col(t, "Amount").formula).toBe(true);
  });
});

describe("validation lists and corrections", () => {
  const sheet = sheetFromRows("Leads", [
    ["name", "stage", "value"],
    ["Asha", "Won", "1200"],
    ["Ben", "Lost", "300"],
    ["Chen", "Open", "800"],
  ]);
  const lists = sheetFromRows("Lists", [["stages"], ["Won"], ["Lost"], ["Open"]]);
  const wb = {
    ...emptyWorkbook([sheet, lists]),
    validations: [
      { sheet: "Leads", address: "Leads!B2:B4", listValues: ["Won", "Lost", "Open"] },
      { sheet: "Leads", address: "Leads!B2:B4", listSource: "=Lists!$A$2:$A$4" },
    ],
  };

  it("attaches inline list values and records a range source as a dependent", () => {
    const catalog = buildCatalog(wb, { exemplars: true, now: NOW });
    expect(col(entity("Leads", catalog.entities), "stage").validationList).toEqual(["Won", "Lost", "Open"]);
    expect(col(entity("Lists", catalog.entities), "stages").dependents).toEqual([
      { kind: "dataValidation", location: "Leads!B2:B4", detail: "=Lists!$A$2:$A$4", refClass: "exclusive", fixedRows: false },
    ]);
  });

  it("lets a user correction override the inferred type", () => {
    const catalog = buildCatalog(wb, {
      exemplars: true,
      now: NOW,
      corrections: [{ sheet: "Leads", entity: "Leads", column: "value", kind: "currency", unit: "EUR" }],
    });
    const value = col(entity("Leads", catalog.entities), "value");
    expect(value.kind).toBe("currency");
    expect(value.unit).toBe("EUR");
    expect(value.warnings).toContain("Type set by you.");
  });
});
