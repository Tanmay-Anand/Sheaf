import type { WorkbookCatalog } from "@sheaf/contract/catalog";
import { type Box, contains, intersects, parseAddress, sameSheet } from "./a1";

/** Namespace of the custom XML part that stores the catalog inside the workbook file. */
export const CATALOG_NAMESPACE = "urn:sheaf:catalog:v1";

const ROOT = "sheafCatalog";

function escapeXml(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function unescapeXml(s: string): string {
  return s.replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&");
}

export function encodeCatalogXml(catalog: WorkbookCatalog): string {
  return `<${ROOT} xmlns="${CATALOG_NAMESPACE}" version="${catalog.version}">${escapeXml(JSON.stringify(catalog))}</${ROOT}>`;
}

/** Returns null for anything that isn't a catalog this build understands. */
export function decodeCatalogXml(xml: string): WorkbookCatalog | null {
  const m = new RegExp(`<${ROOT}\\b[^>]*>([\\s\\S]*)</${ROOT}>`).exec(xml);
  if (!m) return null;
  try {
    const parsed = JSON.parse(unescapeXml(m[1]!)) as WorkbookCatalog;
    return parsed.version === 1 && Array.isArray(parsed.entities) ? parsed : null;
  } catch {
    return null;
  }
}

export interface Staleness {
  /** Entities whose cells changed. */
  entities: string[];
  /** The change touched cells outside every known entity: the sheet may hold a new table. */
  outsideEntities: boolean;
}

/**
 * Maps a change event (sheet + address, as reported by Excel's onChanged) to the entities it
 * makes stale, so only those are re-profiled.
 */
export function staleEntities(catalog: WorkbookCatalog, sheet: string, address: string): Staleness {
  const changed = parseAddress(address);
  if (!changed) return { entities: [], outsideEntities: true };
  const onSheet = catalog.entities
    .filter((e) => sameSheet(e.sheet, sheet))
    .map((e) => ({ name: e.name, box: parseAddress(e.address)?.box }))
    .filter((e): e is { name: string; box: Box } => e.box !== undefined);
  const hit = onSheet.filter((e) => intersects(e.box, changed.box));
  return {
    entities: hit.map((e) => e.name),
    outsideEntities: !onSheet.some((e) => contains(e.box, changed.box)),
  };
}
