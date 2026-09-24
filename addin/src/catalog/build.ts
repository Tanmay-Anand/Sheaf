import type {
  CatalogColumn,
  CatalogCorrection,
  CatalogEntity,
  SkippedRow,
  WorkbookCatalog,
} from "@sheaf/contract/catalog";
import { type Box, boxAddress, colToLetters, parseAddress, sameSheet } from "./a1";
import { inferDateSystem } from "./dateSystem";
import { columnKey, type EntityLayout, MAX_DEPENDENTS_PER_COLUMN, resolveDependents } from "./dependencies";
import { cyrb53 } from "./hash";
import { columnIds, regionIds, renamedColumns, tableEntityId } from "./ids";
import { inferJoins, type JoinColumn } from "./joins";
import { profileColumn } from "./profile";
import { detectRegions } from "./regions";
import type { CellValue, ReferenceSource, SheetSnapshot, WorkbookSnapshot } from "./types";
import { isBlank } from "./values";

export interface BuildOptions {
  /**
   * Include up to five sample values per column. When false, the catalog carries no cell values
   * at all: no exemplars, no section-label text, and string literals in formulas are redacted.
   */
  exemplars: boolean;
  corrections?: CatalogCorrection[];
  /** The last catalog of this workbook, so regions that moved or grew keep their ids. */
  previous?: WorkbookCatalog | null;
  now?: Date;
}

export const CATALOG_VERSION = 2;
const MAX_VALIDATION_VALUES = 200;

// Pick, not Omit: the generated interfaces carry an index signature, and Omit over it erases
// every named key.
type EntityHead = Pick<
  CatalogEntity,
  | "id" | "name" | "kind" | "sheetId" | "sheet" | "address" | "headerRow" | "headerRows" | "firstDataRow" | "lastDataRow"
  | "dataRowCount" | "skippedRows"
> & { tableName?: string };

interface Draft {
  entity: EntityHead;
  box: Box;
  sheet: SheetSnapshot;
  headers: string[];
  columnIds: string[];
  dataRows: number[];
}

function cellAt(sheet: SheetSnapshot, row: number, col: number): CellValue {
  return sheet.values[row - sheet.originRow]?.[col - sheet.originCol] ?? null;
}

function formulaAt(sheet: SheetSnapshot, row: number, col: number): string | undefined {
  const f = sheet.formulas?.[row - sheet.originRow]?.[col - sheet.originCol];
  return typeof f === "string" && f.startsWith("=") ? f : undefined;
}

function formatAt(sheet: SheetSnapshot, row: number, col: number): string | undefined {
  return sheet.numberFormats?.[row - sheet.originRow]?.[col - sheet.originCol];
}

function hashBox(sheet: SheetSnapshot, box: Box): string {
  const parts: string[] = [];
  for (let r = box.top; r <= box.bottom; r++) {
    const row: string[] = [];
    for (let c = box.left; c <= box.right; c++) {
      const v = formulaAt(sheet, r, c) ?? cellAt(sheet, r, c);
      row.push(v === null ? "" : `${typeof v}:${String(v)}`);
    }
    parts.push(row.join("\u0001"));
  }
  return cyrb53(parts.join("\u0002"));
}

function uniqueName(wanted: string, taken: Set<string>): string {
  let name = wanted;
  for (let i = 2; taken.has(name.toLowerCase()); i++) name = `${wanted} ${i}`;
  taken.add(name.toLowerCase());
  return name;
}

export function buildCatalog(wb: WorkbookSnapshot, opts: BuildOptions): WorkbookCatalog {
  const corrections = opts.corrections ?? [];
  const warnings: string[] = [];
  const taken = new Set<string>();
  const drafts: Draft[] = [];
  const sheetByName = (name: string) => wb.sheets.find((s) => sameSheet(s.name, name));

  // 1. Excel Tables are entities as declared; their cells are masked from region detection.
  const masked = new Map<string, Box[]>();
  for (const t of wb.tables) {
    const parsed = parseAddress(t.address);
    const sheet = sheetByName(t.sheet);
    if (!parsed || !sheet) continue;
    const box = parsed.box;
    masked.set(sheet.name, [...(masked.get(sheet.name) ?? []), box]);
    const headerRow = t.showHeaders ? box.top : box.top - 1;
    const last = t.showTotals ? box.bottom - 1 : box.bottom;
    const skipped: SkippedRow[] = [];
    const dataRows: number[] = [];
    for (let r = headerRow + 1; r <= last; r++) {
      let blank = true;
      for (let c = box.left; c <= box.right && blank; c++) blank = isBlank(cellAt(sheet, r, c));
      if (blank) skipped.push({ row: r, reason: "blank" });
      else dataRows.push(r);
    }
    if (t.showTotals) skipped.push({ row: box.bottom, reason: "totals" });
    const headers = t.columns.length === box.right - box.left + 1
      ? t.columns
      : Array.from({ length: box.right - box.left + 1 }, (_, i) => t.columns[i] ?? `Column ${colToLetters(box.left + i)}`);
    drafts.push({
      entity: {
        id: tableEntityId(t.id),
        name: uniqueName(t.name, taken),
        kind: "table",
        sheetId: sheet.id,
        sheet: sheet.name,
        address: boxAddress(sheet.name, box),
        headerRow,
        headerRows: t.showHeaders ? 1 : 0,
        firstDataRow: headerRow + 1,
        lastDataRow: box.bottom,
        dataRowCount: dataRows.length,
        skippedRows: skipped,
        tableName: t.name,
      },
      box,
      sheet,
      headers,
      columnIds: columnIds(headers, t.columnIds?.length === headers.length ? t.columnIds : undefined),
      dataRows,
    });
  }

  // 2. Everything else: detected regions.
  for (const sheet of wb.sheets) {
    const headerOverrides = corrections
      .filter((c) => sameSheet(c.sheet, sheet.name) && c.headerRow !== undefined && c.column === undefined)
      .map((c) => c.headerRow!);
    const regions = detectRegions(sheet, {
      masked: masked.get(sheet.name) ?? [],
      headerOverrides,
      keepLabels: opts.exemplars,
    });
    if (regions.length === 0 && !masked.has(sheet.name) && sheet.values.some((r) => r.some((v) => !isBlank(v)))) {
      warnings.push(`Sheet "${sheet.name}" has content but no table Sheaf can read.`);
    }
    const ids = regionIds(
      regions.map((reg) => ({ sheetId: sheet.id, headerRow: reg.headerRow, left: reg.box.left, headers: reg.headers })),
      opts.previous?.entities,
    );
    regions.forEach((reg, i) => {
      const name = uniqueName(i === 0 ? sheet.name : `${sheet.name} ${i + 1}`, taken);
      if (reg.dataRows.length === 0) warnings.push(`${name} has a header but no data rows.`);
      warnings.push(...reg.warnings.map((w) => `${name}: ${w}`));
      drafts.push({
        entity: {
          id: ids[i]!,
          name,
          kind: "region",
          sheetId: sheet.id,
          sheet: sheet.name,
          address: boxAddress(sheet.name, reg.box),
          headerRow: reg.headerRow,
          headerRows: reg.headerRows,
          firstDataRow: reg.headerRow + 1,
          lastDataRow: reg.box.bottom,
          dataRowCount: reg.dataRows.length,
          skippedRows: reg.skipped,
        },
        box: reg.box,
        sheet,
        headers: reg.headers,
        columnIds: columnIds(reg.headers),
        dataRows: reg.dataRows,
      });
    });
  }

  // 3. Profile every column.
  const joinColumns: JoinColumn[] = [];
  const profiled = drafts.map((d) => {
    const columns: CatalogColumn[] = d.headers.map((header, i) => {
      const col = d.box.left + i;
      const p = profileColumn(
        {
          id: d.columnIds[i]!,
          name: header,
          letter: colToLetters(col),
          index: i,
          cells: d.dataRows.map((r) => cellAt(d.sheet, r, col)),
          formats: d.dataRows.map((r) => formatAt(d.sheet, r, col)),
          formulaFlags: d.dataRows.map((r) => formulaAt(d.sheet, r, col) !== undefined),
        },
        { exemplars: opts.exemplars },
      );
      joinColumns.push({
        entity: d.entity.name,
        column: header,
        kind: p.column.kind,
        keyCandidate: p.column.keyCandidate,
        integerOnly: p.integerOnly,
        distinct: p.distinct,
      });
      return p.column;
    });
    return { draft: d, columns };
  });

  // 4. Validation lists: inline values attach to the column; range sources become dependents.
  const validationSources: ReferenceSource[] = [];
  for (const v of wb.validations) {
    const parsed = parseAddress(v.address);
    if (!parsed) continue;
    if (v.listSource) {
      validationSources.push({ kind: "dataValidation", location: boxAddress(v.sheet, parsed.box), sheet: v.sheet, text: v.listSource });
    }
    if (!v.listValues) continue;
    for (const { draft, columns } of profiled) {
      if (!sameSheet(draft.entity.sheet, v.sheet)) continue;
      columns.forEach((c, i) => {
        const col = draft.box.left + i;
        const rowsOverlap = parsed.box.top <= draft.entity.lastDataRow && parsed.box.bottom >= draft.entity.firstDataRow;
        if (rowsOverlap && col >= parsed.box.left && col <= parsed.box.right) {
          columns[i] = { ...c, validationList: v.listValues!.slice(0, MAX_VALIDATION_VALUES) };
        }
      });
    }
  }

  // 5. Dependencies: every formula cell, every defined name, and every other reference source.
  const layouts: EntityLayout[] = profiled.map(({ draft, columns }) => ({
    name: draft.entity.name,
    sheet: draft.entity.sheet,
    box: draft.box,
    dataTop: draft.entity.firstDataRow,
    ...(draft.entity.tableName !== undefined ? { tableName: draft.entity.tableName } : {}),
    columns: columns.map((c, i) => ({ name: c.name, col: draft.box.left + i })),
  }));
  const formulaSources: ReferenceSource[] = [];
  for (const sheet of wb.sheets) {
    sheet.formulas?.forEach((row, ri) =>
      row.forEach((f, ci) => {
        if (typeof f !== "string" || !f.startsWith("=")) return;
        const r = sheet.originRow + ri;
        const c = sheet.originCol + ci;
        formulaSources.push({ kind: "formula", location: boxAddress(sheet.name, { top: r, bottom: r, left: c, right: c }), sheet: sheet.name, text: f, row: r, col: c });
      }),
    );
  }
  const nameSources: ReferenceSource[] = wb.names.map((n) => ({ kind: "namedRange", location: n.name, sheet: "", text: n.formula }));
  const tableAt = (sheetName: string, row: number, col: number) =>
    layouts.find(
      (l) => l.tableName && sameSheet(l.sheet, sheetName) && row >= l.box.top && row <= l.box.bottom && col >= l.box.left && col <= l.box.right,
    )?.tableName;
  const deps = resolveDependents(layouts, [...formulaSources, ...nameSources, ...validationSources, ...wb.sources], wb.names, {
    tableAt,
    redact: !opts.exemplars,
  });

  // 6. Assemble entities, applying corrections last so they win over inference.
  const entities: CatalogEntity[] = profiled.map(({ draft, columns }) => ({
    ...draft.entity,
    columns: columns.map((c) => {
      const all = deps.byColumn.get(columnKey(draft.entity.name, c.name)) ?? [];
      const extra = all.length - MAX_DEPENDENTS_PER_COLUMN;
      const fix = corrections.find(
        (k) => k.entity === draft.entity.name && k.column !== undefined && k.column.toLowerCase() === c.name.toLowerCase(),
      );
      const corrected: CatalogColumn = fix?.kind
        ? {
            ...c,
            kind: fix.kind,
            ...(fix.unit !== undefined ? { unit: fix.unit } : {}),
            warnings: [...c.warnings, "Type set by you."],
          }
        : c;
      return {
        ...corrected,
        dependents: all.slice(0, MAX_DEPENDENTS_PER_COLUMN),
        warnings: extra > 0 ? [...corrected.warnings, `…and ${extra} more dependents not listed.`] : corrected.warnings,
      };
    }),
    contentHash: hashBox(draft.sheet, draft.box),
  }));

  // Carried-over entities whose headers changed: plans that named the old header must confirm.
  for (const e of entities) {
    const before = opts.previous?.entities.find((p) => p.id === e.id);
    if (!before) continue;
    for (const r of renamedColumns(before, e)) {
      warnings.push(`${e.name}: column "${r.from}" is now "${r.to}"; saved plans that used "${r.from}" will ask before running.`);
    }
  }

  if (deps.untraceable.length > 0) {
    warnings.push(
      `${deps.untraceable.length} reference${deps.untraceable.length === 1 ? "" : "s"} can't be traced (INDIRECT, OFFSET or links to other workbooks); dependency checks can't be complete.`,
    );
  }

  return {
    version: CATALOG_VERSION,
    generatedAt: (opts.now ?? new Date()).toISOString(),
    dateSystem: inferDateSystem(wb.dateProbes ?? []),
    entities,
    joinCandidates: inferJoins(joinColumns),
    untraceable: deps.untraceable,
    corrections,
    warnings,
  };
}
