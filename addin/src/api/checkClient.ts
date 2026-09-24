import type { CheckReport } from "@sheaf/contract/check";
import type { WorkbookCatalog } from "@sheaf/contract/catalog";
import { SERVICE_URL } from "../config";

export type { CheckReport };

/**
 * Asks the service to parse, bind and type-check a plan against this workbook's catalog (schema and
 * statistics only, never rows). An invalid plan is a normal answer: `valid: false` with diagnostics.
 */
export async function checkPlan(plan: unknown, catalog: WorkbookCatalog): Promise<CheckReport> {
  const response = await fetch(`${SERVICE_URL}/api/check`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ plan, catalog }),
  });
  if (response.ok) return response.json() as Promise<CheckReport>;
  const text = await response.text().catch(() => response.statusText);
  throw new Error(response.status === 422 ? `The service refused the catalog: ${text}` : `Service error ${response.status}: ${text}`);
}
