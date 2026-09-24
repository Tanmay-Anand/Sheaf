import { describe, expect, it } from "vitest";
import { classifyFormat, parseCell } from "../values";

describe("parseCell", () => {
  it.each([
    ["2025-01-20", "yyyy-mm-dd"],
    ["Jan 13 2025", "Mon d yyyy"],
    ["February 3 2025", "Month d yyyy"],
    ["20-Jan-25", "d-Mon-yy"],
    ["2025-Mar-08", "yyyy-Mon-dd"],
    ["13 Jan 2025", "d Mon yyyy"],
  ])("reads %s as a date (%s)", (text, format) => {
    expect(parseCell(text)).toEqual({ k: "date", format });
  });

  it("keeps numeric dates undecided until the column shows evidence", () => {
    expect(parseCell("01/15/2025")).toEqual({ k: "date", format: "numeric", numeric: { a: 1, b: 15, sep: "/", yearDigits: 4 } });
  });

  it("reads ISO timestamps as datetimes", () => {
    expect(parseCell("2025-01-20T09:30:00Z")).toEqual({ k: "datetime", format: "yyyy-mm-dd hh:mm" });
  });

  it("rejects impossible dates", () => {
    expect(parseCell("2025-13-40").k).toBe("string");
    expect(parseCell("Feb 45 2025").k).toBe("string");
  });

  it.each([
    ["$5196", 5196, "USD"],
    ["₹1,299.50", 1299.5, "INR"],
    ["Rs. 499", 499, "INR"],
    ["1200 EUR", 1200, "EUR"],
    ["-£20", -20, "GBP"],
  ])("reads %s as currency", (text, v, unit) => {
    expect(parseCell(text)).toEqual({ k: "currency", v, unit });
  });

  it("reads percentages as fractions", () => {
    expect(parseCell("12.5%")).toEqual({ k: "percent", v: 0.125 });
  });

  it("reads thousands separators and accounting negatives", () => {
    expect(parseCell("1,234,567")).toEqual({ k: "number", v: 1234567, integer: true });
    expect(parseCell("(1,250.75)")).toEqual({ k: "number", v: -1250.75, integer: false });
  });

  it("keeps leading-zero codes as text", () => {
    expect(parseCell("007")).toEqual({ k: "string", v: "007" });
    expect(parseCell("01234")).toEqual({ k: "string", v: "01234" });
  });

  it("treats placeholders as null and Excel errors as errors", () => {
    expect(parseCell("N/A")).toEqual({ k: "null" });
    expect(parseCell("  ")).toEqual({ k: "null" });
    expect(parseCell("#DIV/0!")).toEqual({ k: "error", code: "#DIV/0!" });
  });

  it("uses the number format for typed Excel values", () => {
    expect(parseCell(45678, "dd-mmm-yy")).toEqual({ k: "date", format: "dd-mmm-yy" });
    expect(parseCell(0.25, "0%")).toEqual({ k: "percent", v: 0.25 });
    expect(parseCell(99.5, "[$₹-4009] #,##0.00")).toEqual({ k: "currency", v: 99.5, unit: "INR" });
    expect(parseCell(42, "General")).toEqual({ k: "number", v: 42, integer: true });
  });
});

describe("classifyFormat", () => {
  it.each([
    ["General", "general"],
    ["@", "text"],
    ["#,##0.00", "number"],
    ["0.0%", "percent"],
    ["yyyy-mm-dd", "date"],
    ["m/d/yyyy h:mm", "datetime"],
    ["h:mm AM/PM", "datetime"],
    ['0.00" units"', "number"],
  ])("%s is %s", (fmt, kind) => {
    expect(classifyFormat(fmt).kind).toBe(kind);
  });

  it("finds the currency in symbols and locale blocks", () => {
    expect(classifyFormat("$#,##0.00")).toEqual({ kind: "currency", unit: "USD" });
    expect(classifyFormat("[$€-x-euro2] #,##0")).toEqual({ kind: "currency", unit: "EUR" });
  });
});
