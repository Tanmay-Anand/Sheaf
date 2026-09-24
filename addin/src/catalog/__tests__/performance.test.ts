import { describe, expect, it } from "vitest";
import { buildCatalog } from "../build";
import type { CellValue } from "../types";
import { emptyWorkbook, sheetFromRows } from "./fixtures";

const ROWS = 50_000;
const REGIONS = ["North", "South", "East", "West", "Central", "Northeast"];

function bigSheet(): CellValue[][] {
  const rows: CellValue[][] = [
    ["order_id", "order_date", "region", "channel", "product", "quantity", "unit_price", "amount", "discount_pct", "status", "customer", "notes"],
  ];
  for (let i = 0; i < ROWS; i++) {
    const d = new Date(Date.UTC(2024, 0, 1) + (i % 700) * 86_400_000).toISOString().slice(0, 10);
    rows.push([
      `ORD-${i}`,
      d,
      REGIONS[i % 6]!,
      ["online", "retail", "wholesale", "partner"][i % 4]!,
      `Product ${i % 400}`,
      (i % 20) + 1,
      ((i * 37) % 5000) + 99,
      ((i * 91) % 90000) + 199,
      i % 15,
      ["shipped", "returned", "pending"][i % 3]!,
      `Customer ${i % 3000}`,
      i % 10 === 0 ? "priority" : null,
    ]);
  }
  return rows;
}

describe("performance", () => {
  it(`catalogues a ${ROWS.toLocaleString("en")}-row sheet in under 2 seconds`, () => {
    const wb = emptyWorkbook([sheetFromRows("Orders", bigSheet())]);
    const start = performance.now();
    const catalog = buildCatalog(wb, { exemplars: true });
    const ms = performance.now() - start;
    expect(catalog.entities[0]!.dataRowCount).toBe(ROWS);
    expect(ms).toBeLessThan(2000);
  });
});
