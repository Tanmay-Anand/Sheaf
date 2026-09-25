import type { WorkbookCatalog } from "@sheaf/contract/catalog";
import { intersects, parseAddress } from "../catalog/a1";
import type { WorkbookSnapshot } from "../catalog/types";
import { evaluate, type EvalResult, type Issue } from "./evaluate";
import type { Plan, QueryPlan } from "./ir";
import { loadEntity } from "./load";
import type { Table } from "./table";
import { planWrite, type OutputType, WriteRefused, type WritePlan } from "./write";

/**
 * From a checked plan to what the user sees before anything is written: the evaluated result, its
 * exact extent, every issue, and the regions the commit must find unchanged. Pure: the caller
 * supplies a fresh scan of the workbook (catalog + snapshot).
 */

export interface GuardedRegion {
  label: string;
  sheetId: string;
  address: string;
  hash: string;
}

export interface PreviewInput {
  plan: Plan;
  output: OutputType[];
  dynamicType?: string;
  catalog: WorkbookCatalog;
  snapshot: WorkbookSnapshot;
  params?: Record<string, unknown>;
  excludeErrorCells?: boolean;
  anchor?: { sheetId: string; sheetName: string; address: string };
}

export interface Preview {
  result: EvalResult;
  /** Load notes and evaluation issues together; any blocking one stops the commit. */
  issues: Issue[];
  /** Null when the extent isn't known yet (an anchor sink before a cell is chosen) or can't be written. */
  write: WritePlan | null;
  /** Why there is no write plan, when there isn't one. */
  writeRefusal: string | null;
  guards: GuardedRegion[];
  rows: number;
  cols: number;
}

export class PreviewRefused extends Error {}

function note(message: string, count: number): Issue {
  return { code: "LOAD_NOTE", message, count, blocking: false };
}

export function preparePreview(input: PreviewInput): Preview {
  const { plan, catalog, snapshot } = input;
  if (plan.kind !== "query") throw new PreviewRefused("Edit plans are previewed and committed from M6. This build runs query plans.");
  const query = plan as QueryPlan;

  // The plan was bound to ids at check time; the fresh scan must still have them.
  const tables: Record<string, Table> = {};
  const guards: GuardedRegion[] = [];
  let textNumbers = 0;
  let unreadable = 0;
  for (const b of query.bindings.entities) {
    const e = catalog.entities.find((x) => x.id === b.id);
    if (!e) throw new PreviewRefused(`${b.name} is no longer in the workbook (or moved beyond recognition). Check the plan again.`);
    const ids = new Set(e.columns.map((c) => c.id));
    const missing = b.columns.filter((c) => !ids.has(c.id)).map((c) => c.name);
    if (missing.length) throw new PreviewRefused(`${b.name} changed since the plan was checked (${missing.join(", ")}). Check the plan again.`);
    const sheet = snapshot.sheets.find((s) => s.id === e.sheetId);
    if (!sheet) throw new PreviewRefused(`The sheet holding ${b.name} wasn't read.`);
    const loaded = loadEntity(e, sheet, catalog.dateSystem);
    tables[b.name] = loaded.table;
    textNumbers += loaded.stats.textNumbers;
    unreadable += loaded.stats.unreadable;
    guards.push({ label: b.name, sheetId: e.sheetId, address: e.address, hash: e.contentHash });
  }

  const result = evaluate(query, tables, {
    ...(input.params ? { params: input.params } : {}),
    ...(input.excludeErrorCells ? { excludeErrorCells: true } : {}),
  });

  const issues: Issue[] = [];
  const n = (count: number, one: string, many: string) => `${count.toLocaleString("en")} ${count === 1 ? one : many}`;
  if (textNumbers > 0) {
    issues.push(note(`${n(textNumbers, "number stored as text was", "numbers stored as text were")} read as the number${textNumbers === 1 ? "" : "s"} shown (Excel's own SUM would skip ${textNumbers === 1 ? "it" : "them"}).`, textNumbers));
  }
  if (unreadable > 0) {
    issues.push(note(`${n(unreadable, "cell didn't", "cells didn't")} fit the column's type and count${unreadable === 1 ? "s" : ""} as #VALUE! error${unreadable === 1 ? "" : "s"}.`, unreadable));
  }
  if (catalog.dateSystem === "unknown") issues.push(note("The workbook's date system couldn't be confirmed; dates are read as the 1900 system (Excel's default).", 0));
  issues.push(...result.issues);

  let write: WritePlan | null = null;
  let writeRefusal: string | null = null;
  try {
    if (query.sink.mode !== "anchor" || input.anchor) {
      write = planWrite(result.table, input.output, input.dynamicType, query.sink, {
        dateSystem: catalog.dateSystem,
        ...(input.anchor ? { anchor: input.anchor } : {}),
      });
    }
  } catch (e) {
    if (!(e instanceof WriteRefused)) throw e;
    writeRefusal = e.message;
  }

  // An anchor sink writes into an existing sheet: never over the data the plan reads, and never
  // silently over another table.
  if (write && write.target.kind === "existing") {
    const box = { top: write.top, bottom: write.top + write.rows - 1, left: write.left, right: write.left + write.cols - 1 };
    const sheetId = write.target.sheetId;
    const sources = new Set(query.bindings.entities.map((b) => b.id));
    for (const e of catalog.entities) {
      const eb = parseAddress(e.address)?.box;
      if (e.sheetId !== sheetId || !eb || !intersects(eb, box)) continue;
      issues.push(
        sources.has(e.id)
          ? { code: "OVERWRITES_SOURCE", message: `${write.address} overlaps ${e.name}, which this plan reads. Choose a cell outside it.`, count: 0, blocking: true }
          : { code: "OVERWRITES_TABLE", message: `${write.address} overlaps the table ${e.name}; its cells there will be replaced.`, count: 0, blocking: false },
      );
    }
  }
  return {
    result,
    issues,
    write,
    writeRefusal,
    guards,
    rows: result.table.rows.length + 1,
    cols: result.table.columns.length,
  };
}
