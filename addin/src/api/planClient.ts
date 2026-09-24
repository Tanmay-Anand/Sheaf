import type { Plan } from "@sheaf/contract";
import { SERVICE_URL } from "../config";

// Generated from the Java IR (contract/types/plan.d.ts). Change a Java record without
// regenerating and this module stops compiling, which is the point.
export type { Plan };

export async function fetchPlan(question: string): Promise<Plan> {
  const response = await fetch(`${SERVICE_URL}/api/plan`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ question }),
  });

  if (!response.ok) {
    const text = await response.text().catch(() => response.statusText);
    throw new Error(`Service error ${response.status}: ${text}`);
  }

  return response.json() as Promise<Plan>;
}
