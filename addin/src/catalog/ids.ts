import type { CatalogEntity } from "@sheaf/contract/catalog";
import { colToLetters, parseAddress } from "./a1";
import { cyrb53 } from "./hash";

/**
 * Stable ids, so a plan bound to "Sales.Amount" still means the same thing after the table moves,
 * grows or its sheet is renamed.
 *
 * - An Excel Table is `t:<table id>` (Excel keeps that id through renames and moves).
 * - A detected region is `r:<worksheet id>:<header anchor>`, first seen; on a rescan it keeps its
 *   previous id when it is recognisably the same region: the same headers anywhere on the same
 *   sheet (it moved or grew), or the same anchor with most headers unchanged (a header was renamed).
 * - A table column is `tc:<column id>`; a region column is `h:<hash of its lower-cased header>`,
 *   so an unchanged header keeps its id, and a renamed header gets a new one (plans that used the
 *   old one then fall back to matching by header, with confirmation).
 */

export function tableEntityId(tableId: string): string {
  return `t:${tableId}`;
}

export function regionEntityId(sheetId: string, headerRow: number, left: number): string {
  return `r:${sheetId}:${colToLetters(left)}${headerRow}`;
}

const key = (header: string) => header.trim().toLowerCase();

export function columnIds(headers: string[], tableColumnIds?: string[]): string[] {
  const taken = new Set<string>();
  return headers.map((h, i) => {
    const base = tableColumnIds?.[i] !== undefined ? `tc:${tableColumnIds[i]}` : `h:${cyrb53(key(h))}`;
    let id = base;
    for (let n = 2; taken.has(id); n++) id = `${base}~${n}`;
    taken.add(id);
    return id;
  });
}

export interface RegionDraft {
  sheetId: string;
  headerRow: number;
  left: number;
  headers: string[];
}

function signature(headers: string[]): string {
  return headers.map(key).join("\u0001");
}

function overlap(a: string[], b: string[]): number {
  const sa = new Set(a.map(key));
  const shared = b.map(key).filter((h) => sa.has(h)).length;
  return shared / Math.max(a.length, b.length, 1);
}

/** Ids for this scan's regions, carrying over ids from the previous catalog where the region is the same. */
export function regionIds(drafts: RegionDraft[], previous: CatalogEntity[] | undefined): string[] {
  const prev = (previous ?? [])
    .filter((e) => e.kind === "region")
    .map((e) => ({ e, left: parseAddress(e.address)?.box.left, headers: e.columns.map((c) => c.name) }));
  const claimed = new Set<string>();
  const ids: (string | undefined)[] = drafts.map(() => undefined);

  // Pass 1: identical headers on the same sheet (moved or grown).
  drafts.forEach((d, i) => {
    const sig = signature(d.headers);
    const match = prev.filter((p) => p.e.sheetId === d.sheetId && !claimed.has(p.e.id) && signature(p.headers) === sig);
    if (match.length === 1) {
      ids[i] = match[0]!.e.id;
      claimed.add(match[0]!.e.id);
    }
  });
  // Pass 2: same anchor, most headers unchanged (a header was renamed or a column added).
  drafts.forEach((d, i) => {
    if (ids[i] !== undefined) return;
    const match = prev.find(
      (p) =>
        p.e.sheetId === d.sheetId && !claimed.has(p.e.id) && p.e.headerRow === d.headerRow && p.left === d.left &&
        overlap(p.headers, d.headers) >= 0.5,
    );
    if (match) {
      ids[i] = match.e.id;
      claimed.add(match.e.id);
    }
  });
  // Everything else is new: anchor-based, never reusing an id another region kept.
  return drafts.map((d, i) => {
    const found = ids[i];
    if (found !== undefined) return found;
    const base = regionEntityId(d.sheetId, d.headerRow, d.left);
    let id = base;
    for (let n = 2; claimed.has(id); n++) id = `${base}~${n}`;
    claimed.add(id);
    return id;
  });
}

/**
 * Columns of a carried-over entity whose header changed: pairs of (old, new) names at the same
 * position, where the old id is gone and the new id is new.
 */
export function renamedColumns(before: CatalogEntity, after: CatalogEntity): { from: string; to: string }[] {
  const oldIds = new Set(before.columns.map((c) => c.id));
  const newIds = new Set(after.columns.map((c) => c.id));
  const gone = before.columns.filter((c) => !newIds.has(c.id));
  const added = after.columns.filter((c) => !oldIds.has(c.id));
  return added
    .map((c) => ({ to: c, from: gone.find((g) => g.index === c.index) }))
    .filter((x): x is { to: typeof x.to; from: NonNullable<typeof x.from> } => x.from !== undefined)
    .map((x) => ({ from: x.from.name, to: x.to.name }));
}
