import { describe, expect, it } from "vitest";
import { profileColumn } from "../profile";

describe("profileColumn: dates as Excel stores them after a paste", () => {
  // Excel turned some pasted text into real dates (serial numbers with a date format) and left the
  // rest as text, which is what happened with the corpus's ugly sheet in desktop Excel.
  const cells = [45663, "Jan 13 2025", 45677, 45677, "01/15/2025", "February 3 2025", 45698, "2025-Mar-08"];
  const formats = ["m/d/yyyy", "General", "m/d/yyyy", "d-mmm-yy", "General", "General", "m/d/yyyy", "General"];
  const { column } = profileColumn({ id: "c", name: "Date", letter: "C", index: 2, cells, formats }, { exemplars: false });

  it("groups real Excel dates as one kind, whatever their display format", () => {
    expect(column.kind).toBe("date");
    expect(column.dateFormats).toEqual(["Excel date", "Mon d yyyy", "Month d yyyy", "mm/dd/yyyy", "yyyy-Mon-dd"]);
  });

  it("says plainly that some dates are text", () => {
    expect(column.warnings).toEqual([
      "4 text date formats in one column: Mon d yyyy, Month d yyyy, mm/dd/yyyy, yyyy-Mon-dd.",
      "4 of 8 dates are stored as text; the rest are real Excel dates. Text dates can't be sorted or calculated with.",
    ]);
  });

  it("raises no text-date warning when every date is a real Excel date", () => {
    const clean = profileColumn(
      { id: "c", name: "Date", letter: "C", index: 2, cells: [45663, 45677], formats: ["m/d/yyyy", "d-mmm-yy"] },
      { exemplars: false },
    ).column;
    expect(clean.dateFormats).toEqual(["Excel date"]);
    expect(clean.warnings).toEqual([]);
  });
});
