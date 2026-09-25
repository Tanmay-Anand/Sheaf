import type { WorkbookCatalog } from "@sheaf/contract/catalog";
import { SERVICE_URL } from "../config";
import type { CheckReport } from "./checkClient";
import { keys } from "./keyStore";

/** Mirrors LanguageModels.Provider on the service. Never carries a key. */
export interface ProviderInfo {
  id: string;
  label: string;
  needsKey: boolean;
  local: boolean;
  defaultBaseUrl: string;
  /** The service holds its own key for this provider (self-hosted), so none needs to be entered here. */
  serverKey: boolean;
  customBaseUrl: boolean;
  defaultModel: string;
  suggestedModels: string[];
}

/** Mirrors Planning.Status. */
export interface PlannerStatus {
  defaultProvider: string;
  zeroEgressForced: boolean;
  promptVersion: string;
  providers: ProviderInfo[];
}

/** The user's choice of model, kept in this browser (not secret; the key is kept apart). */
export interface ModelChoice {
  provider: string;
  model: string;
  baseUrl?: string | undefined;
  zeroEgress: boolean;
}

/** Mirrors Planner.Run: which model saw the question, where, what it cost, what it produced. */
export interface RunRecord {
  provider: string;
  endpoint: string;
  model: string;
  promptVersion: string;
  calls: number;
  promptTokens: number;
  completionTokens: number;
  cost?: number;
  planHash?: string;
}

/** Mirrors Planning.Answer (the planner's outcome plus the catalog's hash). */
export interface AskOutcome {
  kind: "plan" | "clarify" | "refuse" | "explain" | "failed";
  report?: CheckReport;
  annotations?: { assumptions: string[]; columns: { column: string; confidence: number; reason?: string }[] };
  question?: string;
  options?: string[];
  understood?: string;
  closestSupported?: string[];
  answer?: string;
  diagnostics: CheckReport["diagnostics"];
  repairs: string[];
  /** Exactly what described the workbook to the model (no rows). */
  sent: string;
  run: RunRecord;
  catalogHash: string;
}

export class AskFailed extends Error {
  constructor(
    message: string,
    readonly code: string,
  ) {
    super(message);
  }
}

/** The key travels only in this header (never a URL or body), and only over HTTPS. */
export const KEY_HEADER = "X-Sheaf-Api-Key";

function headers(provider: string): Record<string, string> {
  const h: Record<string, string> = { "Content-Type": "application/json" };
  const key = keys.get(provider);
  if (key) h[KEY_HEADER] = key;
  return h;
}

async function failure(response: Response): Promise<AskFailed> {
  const body = (await response.json().catch(() => ({}))) as { error?: string; message?: string; violations?: string[] };
  if (body.violations) return new AskFailed(`The service refused the catalog: ${body.violations.join(" ")}`, "CATALOG");
  return new AskFailed(body.message ?? `Service error ${response.status}`, body.error ?? "SERVICE");
}

export async function plannerStatus(): Promise<PlannerStatus> {
  const response = await fetch(`${SERVICE_URL}/api/planner`);
  if (!response.ok) throw await failure(response);
  return response.json() as Promise<PlannerStatus>;
}

function providerBody(choice: ModelChoice): string {
  return JSON.stringify({ provider: choice.provider, baseUrl: choice.baseUrl || undefined, zeroEgress: choice.zeroEgress });
}

/** Proves provider, endpoint and key work, without paying for a completion. */
export async function testConnection(choice: ModelChoice): Promise<void> {
  const response = await fetch(`${SERVICE_URL}/api/planner/test`, { method: "POST", headers: headers(choice.provider), body: providerBody(choice) });
  if (!response.ok) throw await failure(response);
}

export async function listModels(choice: ModelChoice): Promise<string[]> {
  const response = await fetch(`${SERVICE_URL}/api/planner/models`, { method: "POST", headers: headers(choice.provider), body: providerBody(choice) });
  if (!response.ok) throw await failure(response);
  return ((await response.json()) as { models: string[] }).models;
}

/** The hash the service gave for each catalog it has seen, so a catalog is sent once. */
const knownHashes = new WeakMap<WorkbookCatalog, string>();

/**
 * Sends the question and the catalog (schema and statistics, never rows) to the planner. A catalog
 * sent before goes as its hash only; if the service no longer has it (409), it is sent again.
 */
export async function ask(question: string, catalog: WorkbookCatalog, choice: ModelChoice): Promise<AskOutcome> {
  const send = async (full: boolean) => {
    const hash = knownHashes.get(catalog);
    return fetch(`${SERVICE_URL}/api/ask`, {
      method: "POST",
      headers: headers(choice.provider),
      body: JSON.stringify({
        question,
        model: choice.model,
        provider: choice.provider,
        baseUrl: choice.baseUrl || undefined,
        zeroEgress: choice.zeroEgress,
        ...(full || !hash ? { catalog } : { catalogHash: hash }),
      }),
    });
  };
  let response = await send(false);
  if (response.status === 409) response = await send(true);
  if (!response.ok) throw await failure(response);
  const outcome = (await response.json()) as AskOutcome;
  knownHashes.set(catalog, outcome.catalogHash);
  return outcome;
}
