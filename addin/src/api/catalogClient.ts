import type { WorkbookCatalog } from "@sheaf/contract/catalog";
import { SERVICE_URL } from "../config";

/** Mirrors CatalogController.CatalogSummary on the service. */
export interface CatalogSummary {
  accepted: boolean;
  entities: number;
  columns: number;
  dependents: number;
  joinCandidates: number;
  violations: string[];
}

/**
 * Sends the catalog (schema and statistics only, never rows) to the service, which checks it
 * against the egress policy. A 422 still carries a summary listing each violation.
 */
export async function submitCatalog(catalog: WorkbookCatalog): Promise<CatalogSummary> {
  const response = await fetch(`${SERVICE_URL}/api/catalog`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(catalog),
  });

  if (response.ok || response.status === 422) {
    return response.json() as Promise<CatalogSummary>;
  }
  const text = await response.text().catch(() => response.statusText);
  throw new Error(`Service error ${response.status}: ${text}`);
}
