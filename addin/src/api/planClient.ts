import type { PlanEnvelope } from "@sheaf/contract";
import { SERVICE_URL } from "../config";

// Generated from the Java IR (contract/types/plan.d.ts). Change a Java record without
// regenerating and this module stops compiling, which is the point.
export type { PlanEnvelope };
export type Plan = PlanEnvelope["plan"];

/** Provenance: kept next to the plan, never part of its hash. */
export interface PlanMeta {
  modelId: string;
  promptVersion: string;
  generatedAt: string;
}

/** What POST /api/plan returns: the hashed envelope, its hash, and where it came from. */
export interface PlanRecord {
  envelope: PlanEnvelope;
  planHash: string;
  meta: PlanMeta;
}

export async function fetchPlan(question: string): Promise<PlanRecord> {
  const response = await fetch(`${SERVICE_URL}/api/plan`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ question }),
  });

  if (!response.ok) {
    const text = await response.text().catch(() => response.statusText);
    throw new Error(`Service error ${response.status}: ${text}`);
  }

  return response.json() as Promise<PlanRecord>;
}
