import { describe, expect, it } from "vitest";
import { boxAddress, colToLetters, lettersToCol, MAX_ROW, parseAddress, quoteSheet } from "../a1";

describe("A1 helpers", () => {
  it("converts column letters both ways", () => {
    for (const [i, s] of [[0, "A"], [25, "Z"], [26, "AA"], [701, "ZZ"], [702, "AAA"], [16383, "XFD"]] as const) {
      expect(colToLetters(i)).toBe(s);
      expect(lettersToCol(s)).toBe(i);
    }
  });

  it("quotes sheet names only when Excel would", () => {
    expect(quoteSheet("Sales")).toBe("Sales");
    expect(quoteSheet("Q1 Report")).toBe("'Q1 Report'");
    expect(quoteSheet("Bob's")).toBe("'Bob''s'");
    expect(quoteSheet("AB12")).toBe("'AB12'");
  });

  it("parses sheet-qualified, quoted, absolute, and whole-column addresses", () => {
    expect(parseAddress("'Q1 Report'!$A$3:$I$23")).toEqual({ sheet: "Q1 Report", box: { top: 3, left: 0, bottom: 23, right: 8 } });
    expect(parseAddress("Sales!C:C")).toEqual({ sheet: "Sales", box: { top: 1, left: 2, bottom: MAX_ROW, right: 2 } });
    expect(parseAddress("B4")).toEqual({ sheet: null, box: { top: 4, left: 1, bottom: 4, right: 1 } });
    expect(parseAddress("Sales!NotARef")).toBeNull();
  });

  it("formats boxes the way Excel reports addresses", () => {
    expect(boxAddress("Q1 Report", { top: 3, left: 0, bottom: 23, right: 8 })).toBe("'Q1 Report'!A3:I23");
    expect(boxAddress("Summary", { top: 4, left: 1, bottom: 4, right: 1 })).toBe("Summary!B4");
  });
});
