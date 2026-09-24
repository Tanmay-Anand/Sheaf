import type { ColumnDependent, UntraceableReference } from "@sheaf/contract/catalog";
import { type Box, cellRef, colToLetters, intersects, quoteSheet, sameSheet } from "./a1";
import { extractReferences, type FormulaRef } from "./formulaRefs";
import type { NamedItemSnapshot, ReferenceSource } from "./types";

export interface EntityLayout {
  name: string;
  sheet: string;
  box: Box;
  tableName?: string;
  /** Absolute 0-based sheet column of each entity column, in entity order. */
  columns: { name: string; col: number }[];
}

export interface DependencyResult {
  /** Keyed by columnKey(entity, column). */
  byColumn: Map<string, ColumnDependent[]>;
  untraceable: UntraceableReference[];
}

export const MAX_DEPENDENTS_PER_COLUMN = 50;
const MAX_UNTRACEABLE = 200;
const MAX_DETAIL = 200;
const MAX_NAME_DEPTH = 4;

export function columnKey(entity: string, column: string): string {
  return `${entity}\u0000${column}`;
}

function clip(text: string): string {
  return text.length > MAX_DETAIL ? `${text.slice(0, MAX_DETAIL - 1)}…` : text;
}

/** Replaces string literals so formula details can't carry cell values when exemplars are off. */
export function redactLiterals(formula: string): string {
  return formula.replace(/"(?:[^"]|"")*"/g, '"…"');
}

interface Hit {
  entity: EntityLayout;
  column: string;
}

export interface ResolveOptions {
  /** Which Excel Table (if any) a formula cell sits in; resolves implicit [@Col] references. */
  tableAt?: (sheet: string, row: number, col: number) => string | undefined;
  redact: boolean;
}

export function resolveDependents(
  entities: EntityLayout[],
  sources: ReferenceSource[],
  names: NamedItemSnapshot[],
  opts: ResolveOptions,
): DependencyResult {
  const nameMap = new Map<string, string>();
  for (const n of names) nameMap.set(n.name.toUpperCase(), n.formula);
  const byTable = new Map<string, EntityLayout>();
  for (const e of entities) if (e.tableName) byTable.set(e.tableName.toUpperCase(), e);

  const untraceable: UntraceableReference[] = [];
  const pushUntraceable = (u: UntraceableReference) => {
    if (untraceable.length < MAX_UNTRACEABLE) untraceable.push(u);
  };

  function hitsFor(ref: FormulaRef, src: ReferenceSource, depth: number, seenNames: Set<string>): Hit[] {
    switch (ref.kind) {
      case "range": {
        const sheet = ref.sheet ?? src.sheet;
        const hits: Hit[] = [];
        for (const e of entities) {
          if (!sameSheet(e.sheet, sheet) || !intersects(e.box, ref.box)) continue;
          for (const c of e.columns) if (c.col >= ref.box.left && c.col <= ref.box.right) hits.push({ entity: e, column: c.name });
        }
        return hits;
      }
      case "structured": {
        const tableName =
          ref.table ?? (src.row !== undefined && src.col !== undefined ? opts.tableAt?.(src.sheet, src.row, src.col) : undefined);
        const e = tableName ? byTable.get(tableName.toUpperCase()) : undefined;
        if (!e) return [];
        if (ref.columns === "all") return e.columns.map((c) => ({ entity: e, column: c.name }));
        const index = (n: string) => e.columns.findIndex((c) => c.name.toLowerCase() === n.toLowerCase());
        if (ref.span) {
          const [a, b] = [index(ref.span[0]), index(ref.span[1])];
          if (a < 0 || b < 0) return [];
          return e.columns.slice(Math.min(a, b), Math.max(a, b) + 1).map((c) => ({ entity: e, column: c.name }));
        }
        return ref.columns
          .map(index)
          .filter((i) => i >= 0)
          .map((i) => ({ entity: e, column: e.columns[i]!.name }));
      }
      case "name": {
        const key = ref.name.toUpperCase();
        const definition = nameMap.get(key);
        if (definition === undefined || seenNames.has(key) || depth >= MAX_NAME_DEPTH) return [];
        const next = new Set(seenNames).add(key);
        return extractReferences(definition).refs.flatMap((r) => hitsFor(r, src, depth + 1, next));
      }
    }
  }

  // Formula cells are grouped per sheet column so 4,000 identical formulas become one dependent.
  const formulaGroups = new Map<string, { hit: Hit; sheet: string; col: number; rows: number[]; example: string }>();
  const byColumn = new Map<string, ColumnDependent[]>();
  const add = (key: string, d: ColumnDependent) => {
    const list = byColumn.get(key) ?? [];
    if (!list.some((x) => x.kind === d.kind && x.location === d.location)) list.push(d);
    byColumn.set(key, list);
  };

  for (const src of sources) {
    const { refs, untraceable: u } = extractReferences(src.text);
    for (const x of u) pushUntraceable({ kind: x.kind, location: src.location, detail: clip(opts.redact ? redactLiterals(x.detail) : x.detail) });

    const hits = new Map<string, Hit>();
    for (const ref of refs) {
      for (const h of hitsFor(ref, src, 0, new Set())) {
        // A formula living inside the column it references goes away with that column; skip it.
        const col = h.entity.columns.find((c) => c.name === h.column)!.col;
        const inside =
          src.row !== undefined && src.col === col && sameSheet(src.sheet, h.entity.sheet) &&
          src.row >= h.entity.box.top && src.row <= h.entity.box.bottom;
        if (!inside) hits.set(columnKey(h.entity.name, h.column), h);
      }
    }

    const detail = clip(opts.redact ? redactLiterals(src.text) : src.text);
    for (const [key, hit] of hits) {
      if (src.kind === "formula" && src.row !== undefined && src.col !== undefined) {
        const gk = `${key}\u0000${src.sheet}\u0000${src.col}`;
        const g = formulaGroups.get(gk) ?? { hit, sheet: src.sheet, col: src.col, rows: [], example: detail };
        g.rows.push(src.row);
        formulaGroups.set(gk, g);
      } else {
        add(key, { kind: src.kind, location: src.location, detail });
      }
    }
  }

  for (const [gk, g] of formulaGroups) {
    const key = gk.split("\u0000").slice(0, 2).join("\u0000");
    const rows = [...new Set(g.rows)].sort((a, b) => a - b);
    // Contiguous runs become one range each: Sales!G5:G21.
    let start = rows[0]!;
    let prev = start;
    const flush = (a: number, b: number) => {
      const loc = a === b ? cellRef(a, g.col) : `${colToLetters(g.col)}${a}:${colToLetters(g.col)}${b}`;
      const count = b - a + 1;
      add(key, {
        kind: "formula",
        location: `${quoteSheet(g.sheet)}!${loc}`,
        detail: count === 1 ? g.example : `${count} formulas, e.g. ${g.example}`,
      });
    };
    for (const r of rows.slice(1)) {
      if (r !== prev + 1) {
        flush(start, prev);
        start = r;
      }
      prev = r;
    }
    flush(start, prev);
  }

  return { byColumn, untraceable };
}
