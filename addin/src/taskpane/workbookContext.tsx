import * as React from "react";
import { createContext, useContext, useMemo, useState } from "react";
import type { WorkbookCatalog } from "@sheaf/contract/catalog";
import type { CheckReport } from "../api/checkClient";
import type { WorkbookSnapshot } from "../catalog/types";

/** The latest scan, shared by the Workbook tab (which makes it) and the Run tab (which uses it). */
interface WorkbookState {
  catalog: WorkbookCatalog | null;
  setCatalog: (c: WorkbookCatalog | null) => void;
  /** Cells of the latest scan, kept in memory only (never persisted, never sent). */
  snapshot: WorkbookSnapshot | null;
  setSnapshot: (s: WorkbookSnapshot | null) => void;
  /** A plan the Ask tab produced, waiting for the Run tab to preview it. */
  handoff: Handoff | null;
  setHandoff: (h: Handoff | null) => void;
}

export interface Handoff {
  report: CheckReport;
  /** The unbound plan as JSON text, shown (and editable) in the Run tab. */
  text: string;
}

const WorkbookContext = createContext<WorkbookState | null>(null);

export function WorkbookProvider({ children }: { children: React.ReactNode }) {
  const [catalog, setCatalog] = useState<WorkbookCatalog | null>(null);
  const [snapshot, setSnapshot] = useState<WorkbookSnapshot | null>(null);
  const [handoff, setHandoff] = useState<Handoff | null>(null);
  const value = useMemo(() => ({ catalog, setCatalog, snapshot, setSnapshot, handoff, setHandoff }), [catalog, snapshot, handoff]);
  return <WorkbookContext.Provider value={value}>{children}</WorkbookContext.Provider>;
}

export function useWorkbook(): WorkbookState {
  const state = useContext(WorkbookContext);
  if (!state) throw new Error("useWorkbook needs a WorkbookProvider.");
  return state;
}
