import * as React from "react";
import { createContext, useContext, useMemo, useState } from "react";
import type { WorkbookCatalog } from "@sheaf/contract/catalog";
import type { WorkbookSnapshot } from "../catalog/types";

/** The latest scan, shared by the Workbook tab (which makes it) and the Run tab (which uses it). */
interface WorkbookState {
  catalog: WorkbookCatalog | null;
  setCatalog: (c: WorkbookCatalog | null) => void;
  /** Cells of the latest scan, kept in memory only (never persisted, never sent). */
  snapshot: WorkbookSnapshot | null;
  setSnapshot: (s: WorkbookSnapshot | null) => void;
}

const WorkbookContext = createContext<WorkbookState | null>(null);

export function WorkbookProvider({ children }: { children: React.ReactNode }) {
  const [catalog, setCatalog] = useState<WorkbookCatalog | null>(null);
  const [snapshot, setSnapshot] = useState<WorkbookSnapshot | null>(null);
  const value = useMemo(() => ({ catalog, setCatalog, snapshot, setSnapshot }), [catalog, snapshot]);
  return <WorkbookContext.Provider value={value}>{children}</WorkbookContext.Provider>;
}

export function useWorkbook(): WorkbookState {
  const state = useContext(WorkbookContext);
  if (!state) throw new Error("useWorkbook needs a WorkbookProvider.");
  return state;
}
