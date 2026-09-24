import { describe, expect, it } from "vitest";
import { MAX_ROW } from "../a1";
import { extractReferences } from "../formulaRefs";

const ranges = (f: string) =>
  extractReferences(f).refs.filter((r) => r.kind === "range");

describe("extractReferences", () => {
  it("reads cells, ranges, whole columns and rows", () => {
    expect(ranges("=SUM(A1:B5)+C7-D:D+SUM(3:4)")).toEqual([
      { kind: "range", sheet: null, box: { top: 1, left: 0, bottom: 5, right: 1 } },
      { kind: "range", sheet: null, box: { top: 7, left: 2, bottom: 7, right: 2 } },
      { kind: "range", sheet: null, box: { top: 1, left: 3, bottom: MAX_ROW, right: 3 } },
      { kind: "range", sheet: null, box: { top: 3, left: 0, bottom: 4, right: 16383 } },
    ]);
  });

  it("reads sheet-qualified and quoted references with absolute markers", () => {
    expect(ranges("=Sales!$G$2:$G$20*'Q1 Report'!B4+'Bob''s'!A1")).toEqual([
      { kind: "range", sheet: "Sales", box: { top: 2, left: 6, bottom: 20, right: 6 } },
      { kind: "range", sheet: "Q1 Report", box: { top: 4, left: 1, bottom: 4, right: 1 } },
      { kind: "range", sheet: "Bob's", box: { top: 1, left: 0, bottom: 1, right: 0 } },
    ]);
  });

  it("ignores text inside string literals and function names that look like cells", () => {
    expect(ranges('=IF(A1="B2 is not a ref",LOG10(C3),ATAN2(1,2))')).toEqual([
      { kind: "range", sheet: null, box: { top: 1, left: 0, bottom: 1, right: 0 } },
      { kind: "range", sheet: null, box: { top: 3, left: 2, bottom: 3, right: 2 } },
    ]);
  });

  it("reads structured references", () => {
    const refs = extractReferences("=SUM(Sales[Amount])+Sales[[#This Row],[Qty]]*[@Price]+SUM(Sales[[Jan]:[Mar]])+COUNTA(Sales[#All])").refs;
    expect(refs.filter((r) => r.kind === "structured")).toEqual([
      { kind: "structured", table: "Sales", columns: ["Amount"] },
      { kind: "structured", table: "Sales", columns: ["Qty"] },
      { kind: "structured", table: null, columns: ["Price"] },
      { kind: "structured", table: "Sales", columns: ["Jan", "Mar"], span: ["Jan", "Mar"] },
      { kind: "structured", table: "Sales", columns: "all" },
    ]);
  });

  it("reports names as candidates for the caller to resolve", () => {
    const refs = extractReferences("=MAX(Prices)*TaxRate+TRUE").refs;
    expect(refs).toEqual([
      { kind: "name", name: "Prices" },
      { kind: "name", name: "TaxRate" },
    ]);
  });

  it("flags references that can only be known at run time", () => {
    const { untraceable, refs } = extractReferences('=SUM(INDIRECT("B"&A1))+OFFSET(C1,1,0)');
    expect(untraceable.map((u) => u.kind)).toEqual(["indirect", "offset"]);
    // The anchors themselves are still real references.
    expect(refs.filter((r) => r.kind === "range")).toHaveLength(2);
  });

  it("flags links to other workbooks without misreading them as local sheets", () => {
    const { untraceable, refs } = extractReferences("=[Budget.xlsx]Sheet1!A1+'C:\\Data\\[Old.xlsx]Plan'!B2+[1]Sheet1!C3");
    expect(untraceable.map((u) => u.kind)).toEqual(["externalLink", "externalLink", "externalLink"]);
    expect(refs).toEqual([]);
  });

  it("accepts source strings without a leading =", () => {
    expect(ranges("Data!$B$2:$B$10")).toEqual([
      { kind: "range", sheet: "Data", box: { top: 2, left: 1, bottom: 10, right: 1 } },
    ]);
  });
});
