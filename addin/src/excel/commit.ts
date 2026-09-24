/* global Excel, Office */
import { type Box, parseAddress } from "../catalog/a1";
import { hashBox } from "../catalog/build";
import { planRowChunks } from "../catalog/chunks";
import type { CellValue, SheetSnapshot } from "../catalog/types";
import type { GuardedRegion } from "../engine/preview";
import type { WritePlan } from "../engine/write";

/**
 * The committer: the only code that writes to the workbook. It writes exactly a WritePlan, which
 * was computed from the previewed result, and nothing else.
 *
 * - Guard: before writing, it re-reads every region the preview was computed from (and, for an
 *   anchor sink, the cells it will overwrite), re-hashes them, and refuses if any changed.
 * - One undo step: everything runs in one Excel.run with mergeUndoGroup (ExcelApi 1.20), so
 *   Ctrl+Z reverses the whole commit. Where 1.20 is missing, the preview says how many undo steps
 *   the commit takes instead.
 * - It never calls an API that clears the undo stack (UNDO_CLEARING_APIS); a test checks this file.
 */

/** Office.js calls that clear Excel's undo stack (Microsoft Learn, "undo support"). Never used here. */
export const UNDO_CLEARING_APIS = ["insertWorksheetsFromBase64", "Worksheet.delete", "Worksheet.copy", "Worksheet.visibility"];

export class CommitRefused extends Error {}

function supports(version: string): boolean {
  return Office.context.requirements.isSetSupported("ExcelApi", version);
}

/** Whether the whole commit can be one undo step on this host. */
export function oneUndoStep(): boolean {
  return supports("1.20");
}

function toCell(v: unknown): CellValue {
  if (v === null || v === undefined || v === "") return null;
  if (typeof v === "string" || typeof v === "number" || typeof v === "boolean") return v;
  return String(v);
}

/** Reads a box's values and formulas in row blocks under the payload limit, as a sheet snapshot. */
async function readBox(context: Excel.RequestContext, ws: Excel.Worksheet, sheetName: string, box: Box): Promise<SheetSnapshot> {
  const rows = box.bottom - box.top + 1;
  const cols = box.right - box.left + 1;
  const values: CellValue[][] = [];
  const formulas: CellValue[][] = [];
  for (const c of planRowChunks(rows, cols)) {
    const range = ws.getRangeByIndexes(box.top - 1 + c.start, box.left, c.count, cols);
    range.load(["values", "formulas"]);
    await context.sync();
    values.push(...range.values.map((r) => r.map(toCell)));
    formulas.push(...range.formulas.map((r) => r.map(toCell)));
    context.trackedObjects.remove(range);
  }
  return { id: "", name: sheetName, originRow: box.top, originCol: box.left, values, formulas };
}

function boxOf(address: string): Box {
  const parsed = parseAddress(address);
  if (!parsed) throw new CommitRefused(`'${address}' is not a range address.`);
  return parsed.box;
}

async function hashRegion(context: Excel.RequestContext, r: { sheetId: string; address: string }): Promise<string> {
  const ws = context.workbook.worksheets.getItemOrNullObject(r.sheetId);
  ws.load(["isNullObject", "name"]);
  await context.sync();
  if (ws.isNullObject) return "sheet-missing";
  const box = boxOf(r.address);
  return hashBox(await readBox(context, ws, ws.name, box), box);
}

export interface TargetInfo {
  sheetId: string;
  sheetName: string;
  /** The range the result will occupy, e.g. "Summary!B2:E20". */
  address: string;
  nonEmptyCells: number;
  hash: string;
}

/** What an anchor sink would overwrite: shown in the preview, and guarded at commit. */
export async function readTarget(sheetId: string, top: number, left: number, rows: number, cols: number): Promise<TargetInfo> {
  return Excel.run(async (context) => {
    const ws = context.workbook.worksheets.getItem(sheetId);
    ws.load("name");
    await context.sync();
    const box: Box = { top, bottom: top + rows - 1, left, right: left + cols - 1 };
    const snap = await readBox(context, ws, ws.name, box);
    const nonEmpty = snap.values.reduce((n, r) => n + r.filter((v) => v !== null).length, 0);
    const range = ws.getRangeByIndexes(top - 1, left, rows, cols);
    range.load("address");
    await context.sync();
    return { sheetId, sheetName: ws.name, address: range.address, nonEmptyCells: nonEmpty, hash: hashBox(snap, box) };
  });
}

/** The cell the user has selected: the anchor for an anchor sink. */
export async function selectedCell(): Promise<{ sheetId: string; sheetName: string; address: string }> {
  return Excel.run(async (context) => {
    const range = context.workbook.getSelectedRange().getCell(0, 0);
    range.load("address");
    const ws = range.worksheet;
    ws.load(["id", "name"]);
    await context.sync();
    return { sheetId: ws.id, sheetName: ws.name, address: range.address.replace(/^.*!/, "") };
  });
}

export interface CommitOutcome {
  address: string;
  sheetName: string;
  undoSteps: number;
}

/**
 * Writes the plan, after checking nothing the preview depended on has changed.
 * @param guards The source regions (and the anchor target) with the hashes the preview saw.
 */
export async function commitWrite(plan: WritePlan, guards: GuardedRegion[]): Promise<CommitOutcome> {
  const merged = oneUndoStep();
  const run = async (context: Excel.RequestContext): Promise<CommitOutcome> => {
    // 1. Guard: the preview must still describe the workbook.
    for (const g of guards) {
      if ((await hashRegion(context, g)) !== g.hash) {
        throw new CommitRefused(`${g.label} changed since the preview. Preview again to see the current result.`);
      }
    }

    // 2. Target: a sheet this commit creates, or the sheet the user chose.
    let ws: Excel.Worksheet;
    if (plan.target.kind === "newSheet") {
      const sheets = context.workbook.worksheets;
      sheets.load("items/name");
      await context.sync();
      const wanted = plan.target.sheetName.toLowerCase();
      if (sheets.items.some((s) => s.name.toLowerCase() === wanted)) {
        throw new CommitRefused(`A sheet named '${plan.target.sheetName}' now exists. Check the plan again for a new name.`);
      }
      ws = sheets.add(plan.target.sheetName);
    } else {
      ws = context.workbook.worksheets.getItem(plan.target.sheetId);
    }

    // 3. Write in row blocks: formats first, so text lands in text cells.
    let syncs = 0;
    for (const c of plan.chunks) {
      const range = ws.getRangeByIndexes(plan.top - 1 + c.start, plan.left, c.count, plan.cols);
      range.numberFormat = plan.numberFormats.slice(c.start, c.start + c.count);
      range.values = plan.values.slice(c.start, c.start + c.count);
      if (supports("1.6")) context.application.suspendApiCalculationUntilNextSync();
      await context.sync();
      syncs++;
    }
    ws.getRangeByIndexes(plan.top - 1, plan.left, 1, plan.cols).format.font.bold = true;
    ws.activate();
    await context.sync();
    syncs++;
    return { address: plan.address, sheetName: plan.target.sheetName, undoSteps: merged ? 1 : syncs };
  };
  return merged ? Excel.run({ mergeUndoGroup: true }, run) : Excel.run(run);
}
