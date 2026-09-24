import { describe, expect, it } from "vitest";
import { buildCatalog } from "../build";
import { decodeCatalogXml, encodeCatalogXml, staleEntities } from "../persist";
import { corpusWorkbook, emptyWorkbook, sheetFromRows } from "./fixtures";

describe("custom XML persistence", () => {
  it("round-trips a catalog, including characters XML would choke on", () => {
    const sheet = sheetFromRows("A&B <Q1>", [["name", "note"], ["x", "<tag> & \"quotes\""], ["y", "]]>"]]);
    const catalog = buildCatalog(emptyWorkbook([sheet]), { exemplars: true, now: new Date(0) });
    const xml = encodeCatalogXml(catalog);
    expect(xml.startsWith('<sheafCatalog xmlns="urn:sheaf:catalog:v1" version="1">')).toBe(true);
    expect(decodeCatalogXml(xml)).toEqual(catalog);
  });

  it("rejects anything that isn't a version-1 catalog", () => {
    expect(decodeCatalogXml("<other/>")).toBeNull();
    expect(decodeCatalogXml('<sheafCatalog xmlns="urn:sheaf:catalog:v1">{"version":2,"entities":[]}</sheafCatalog>')).toBeNull();
    expect(decodeCatalogXml('<sheafCatalog xmlns="urn:sheaf:catalog:v1">not json</sheafCatalog>')).toBeNull();
  });
});

describe("staleness", () => {
  const catalog = buildCatalog(corpusWorkbook(), { exemplars: false, now: new Date(0) });

  it("marks only the entity whose cells changed", () => {
    expect(staleEntities(catalog, "Orders", "Orders!C10")).toEqual({ entities: ["Orders"], outsideEntities: false });
    expect(staleEntities(catalog, "Orders", "C10:D12")).toEqual({ entities: ["Orders"], outsideEntities: false });
  });

  it("flags edits outside every entity so the sheet is rescanned", () => {
    expect(staleEntities(catalog, "Orders", "Orders!P3")).toEqual({ entities: [], outsideEntities: true });
    // The ugly sheet's title row sits outside its table.
    expect(staleEntities(catalog, "Q1 Report", "'Q1 Report'!A1")).toEqual({ entities: [], outsideEntities: true });
  });
});
