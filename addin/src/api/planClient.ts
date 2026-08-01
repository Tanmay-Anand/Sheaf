const SERVICE_URL = "http://localhost:8080";

// Minimal structural types mirroring the Java IR.
// These are replaced by generated types (contract/types/plan.d.ts) once the
// schema generation pipeline is wired. Until then, they keep the add-in compilable.

export interface EntityRef {
  ref: string;
}

export interface PlanMeta {
  planHash: string;
  modelId: string;
  promptVersion: string;
  generatedAt: string;
}

export interface Sink {
  mode: string;
  name?: string;
  anchor?: string;
  range?: string;
  expectedCols?: number;
}

export interface Step {
  op: string;
  [key: string]: unknown;
}

export interface Plan {
  source: EntityRef;
  steps: Step[];
  sink: Sink;
  meta: PlanMeta;
}

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
