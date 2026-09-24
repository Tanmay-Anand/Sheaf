import { describe, expect, it } from "vitest";
import { detectRegions } from "../regions";
import { corpusRows, sheetFromRows } from "./fixtures";

describe("detectRegions on the deliberately ugly corpus sheet", () => {
  const sheet = sheetFromRows("Q1 Report", corpusRows("ugly-mixed.csv"));
  const regions = detectRegions(sheet, { keepLabels: true });

  it("finds exactly one table, ignoring the title and the notes", () => {
    expect(regions).toHaveLength(1);
    // Header on row 3; the grand total two rows below the data stays attached.
    expect(regions[0]!.box).toEqual({ top: 3, left: 0, bottom: 22, right: 8 });
    expect(regions[0]!.headerRow).toBe(3);
    expect(regions[0]!.headers).toEqual(["Region", "Rep Name", "Date", "Product", "Qty", "Price", "Amount", "Status", "Notes"]);
  });

  it("skips section labels, subtotals, the blank row and the grand total", () => {
    const skipped = regions[0]!.skipped.map((s) => [s.row, s.reason, s.label]);
    expect(skipped).toEqual([
      [4, "sectionLabel", "North"],
      [9, "totals", "SUBTOTAL NORTH"],
      [10, "sectionLabel", "South"],
      [15, "totals", "SUBTOTAL SOUTH"],
      [16, "sectionLabel", "East"],
      [20, "totals", "SUBTOTAL EAST"],
      [21, "blank", undefined],
      [22, "totals", "GRAND TOTAL"],
    ]);
    expect(regions[0]!.dataRows).toEqual([5, 6, 7, 8, 11, 12, 13, 14, 17, 18, 19]);
  });

  it("leaves labels out when values must not be kept", () => {
    const bare = detectRegions(sheet, { keepLabels: false });
    expect(bare[0]!.skipped.every((s) => s.label === undefined)).toBe(true);
  });
});

describe("detectRegions layouts", () => {
  it("splits side-by-side tables separated by a blank column", () => {
    const sheet = sheetFromRows("Two", [
      ["id", "name", "", "code", "rate"],
      ["1", "a", "", "X", "0.1"],
      ["2", "b", "", "Y", "0.2"],
    ]);
    const regions = detectRegions(sheet, { keepLabels: true });
    expect(regions.map((r) => r.headers)).toEqual([["id", "name"], ["code", "rate"]]);
  });

  it("keeps a table together across a single blank row", () => {
    const sheet = sheetFromRows("Gap", [
      ["item", "qty"],
      ["a", "1"],
      ["", ""],
      ["b", "2"],
    ]);
    const [r] = detectRegions(sheet, { keepLabels: true });
    expect(r!.dataRows).toEqual([2, 4]);
    expect(r!.skipped).toEqual([{ row: 3, reason: "blank" }]);
  });

  it("separates stacked tables when the lower block starts with its own header", () => {
    const sheet = sheetFromRows("Stack", [
      ["item", "qty"],
      ["a", "1"],
      ["", ""],
      ["region", "target"],
      ["North", "100"],
    ]);
    const regions = detectRegions(sheet, { keepLabels: true });
    expect(regions.map((r) => r.headers)).toEqual([["item", "qty"], ["region", "target"]]);
  });

  it("combines merged group labels with the header row beneath them", () => {
    const sheet = sheetFromRows("Merged", [
      ["", "Q1", "", "", "Q2", ""],
      ["Region", "Jan", "Feb", "Mar", "Apr", "May"],
      ["North", "1", "2", "3", "4", "5"],
    ]);
    const [r] = detectRegions(sheet, { keepLabels: true });
    expect(r!.headerRows).toBe(2);
    expect(r!.headers).toEqual(["Region", "Q1 Jan", "Q1 Feb", "Q1 Mar", "Q2 Apr", "Q2 May"]);
  });

  it("treats a lone title in the first column as a title, not a group label", () => {
    const sheet = sheetFromRows("Title", [
      ["Inventory as of March", "", ""],
      ["sku", "qty", "cost"],
      ["A-1", "3", "10"],
    ]);
    const [r] = detectRegions(sheet, { keepLabels: true });
    expect(r!.headerRows).toBe(1);
    expect(r!.headers).toEqual(["sku", "qty", "cost"]);
    expect(r!.skipped).toEqual([{ row: 1, reason: "title", label: "Inventory as of March" }]);
  });

  it("names columns by letter when there is no header row", () => {
    const sheet = sheetFromRows("NoHeader", [
      ["1", "2"],
      ["3", "4"],
    ]);
    const [r] = detectRegions(sheet, { keepLabels: true });
    expect(r!.headerRows).toBe(0);
    expect(r!.headers).toEqual(["Column A", "Column B"]);
    expect(r!.dataRows).toEqual([1, 2]);
  });

  it("uses a corrected header row when the user sets one", () => {
    const sheet = sheetFromRows("Fix", [
      ["notes", "more"],
      ["sku", "qty"],
      ["A-1", "3"],
    ]);
    const [r] = detectRegions(sheet, { keepLabels: true, headerOverrides: [2] });
    expect(r!.headerRow).toBe(2);
    expect(r!.headers).toEqual(["sku", "qty"]);
  });

  it("dedupes repeated headers and names blank ones", () => {
    const sheet = sheetFromRows("Dupes", [
      ["amount", "amount", "", "status"],
      ["1", "2", "3", "ok"],
      ["4", "5", "6", "ok"],
    ]);
    const [r] = detectRegions(sheet, { keepLabels: true });
    expect(r!.headers).toEqual(["amount", "amount (2)", "Column C", "status"]);
    expect(r!.warnings).toHaveLength(2);
  });

  it("respects the used-range origin", () => {
    const sheet = sheetFromRows("Offset", [["a", "b"], ["1", "2"]], 5, 3);
    const [r] = detectRegions(sheet, { keepLabels: true });
    expect(r!.box).toEqual({ top: 5, left: 3, bottom: 6, right: 4 });
  });
});
