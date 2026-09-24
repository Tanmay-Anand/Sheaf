/* global Excel, Office */
import type { CatalogCorrection, WorkbookCatalog } from "@sheaf/contract/catalog";
import { buildCatalog } from "../catalog/build";
import { planRowChunks } from "../catalog/chunks";
import { CATALOG_NAMESPACE, decodeCatalogXml, encodeCatalogXml } from "../catalog/persist";
import type {
  CellValue,
  DateProbe,
  NamedItemSnapshot,
  ReferenceSource,
  SheetSnapshot,
  TableSnapshot,
  ValidationSnapshot,
  WorkbookSnapshot,
} from "../catalog/types";
import { classifyFormat } from "../catalog/values";

/**
 * Office.js side of the workbook catalog. The only module in src/catalog's orbit that touches
 * Excel. Reads are bulk (one sync per sheet, or per block of rows when a sheet is large enough to
 * approach Excel on the web's 5 MB payload limit), everything optional is feature-gated and
 * best-effort, and a failure in an optional read never fails the scan.
 */

/** Sheaf's own hidden sheets (snapshots, M6) are never catalogued. */
const INTERNAL_SHEET = /^__sheaf/i;
const MAX_VALIDATION_COLUMNS = 300;
/** Date cells whose displayed text is read to infer the 1900/1904 date system. */
const MAX_DATE_PROBES = 5;

function supports(version: string): boolean {
  return Office.context.requirements.isSetSupported("ExcelApi", version);
}

function toCell(v: unknown): CellValue {
  if (v === null || v === undefined || v === "") return null;
  if (typeof v === "string" || typeof v === "number" || typeof v === "boolean") return v;
  return String(v);
}

interface SheetRef {
  id: string;
  name: string;
}

interface Grid {
  values: unknown[][];
  formulas: unknown[][];
  numberFormat: unknown[][];
}

async function readGrid(
  context: Excel.RequestContext,
  ws: Excel.Worksheet,
  row: number,
  col: number,
  rows: number,
  cols: number,
): Promise<Grid> {
  const range = ws.getRangeByIndexes(row, col, rows, cols);
  range.load(["values", "formulas", "numberFormat"]);
  await context.sync();
  const grid = { values: range.values, formulas: range.formulas, numberFormat: range.numberFormat };
  context.trackedObjects.remove(range);
  return grid;
}

async function readSheets(context: Excel.RequestContext, refs: SheetRef[]): Promise<SheetSnapshot[]> {
  const out: SheetSnapshot[] = [];
  const chunked = supports("1.7"); // getRangeByIndexes
  for (const { id, name } of refs) {
    const ws = context.workbook.worksheets.getItem(name);
    const used = ws.getUsedRangeOrNullObject(true);
    if (chunked) used.load(["isNullObject", "rowIndex", "columnIndex", "rowCount", "columnCount"]);
    else used.load(["isNullObject", "rowIndex", "columnIndex", "values", "formulas", "numberFormat"]);
    await context.sync();
    if (used.isNullObject) continue;

    let grid: Grid;
    if (chunked) {
      // Each block is its own request, so no single payload grows with the sheet.
      grid = { values: [], formulas: [], numberFormat: [] };
      for (const c of planRowChunks(used.rowCount, used.columnCount)) {
        const part = await readGrid(context, ws, used.rowIndex + c.start, used.columnIndex, c.count, used.columnCount);
        grid.values.push(...part.values);
        grid.formulas.push(...part.formulas);
        grid.numberFormat.push(...part.numberFormat);
      }
    } else {
      grid = { values: used.values, formulas: used.formulas, numberFormat: used.numberFormat };
    }
    out.push({
      id,
      name,
      originRow: used.rowIndex + 1,
      originCol: used.columnIndex,
      values: grid.values.map((r) => r.map(toCell)),
      formulas: grid.formulas.map((r) => r.map(toCell)),
      numberFormats: grid.numberFormat.map((r) => r.map((f) => String(f ?? "General"))),
    });
    context.trackedObjects.remove(used);
  }
  return out;
}

/**
 * A few date cells as Excel displays them. Office.js can't report the date system
 * (Workbook.use1904DateSystem is preview-only), so the catalog infers it from these.
 */
async function readDateProbes(context: Excel.RequestContext, sheets: SheetSnapshot[]): Promise<DateProbe[]> {
  const picks: { sheet: string; row: number; col: number; serial: number; numberFormat: string }[] = [];
  for (const s of sheets) {
    s.values.forEach((row, r) =>
      row.forEach((v, c) => {
        if (picks.length >= MAX_DATE_PROBES || typeof v !== "number" || v < 61) return;
        const fmt = s.numberFormats?.[r]?.[c];
        const kind = classifyFormat(fmt).kind;
        if (fmt && (kind === "date" || kind === "datetime")) {
          picks.push({ sheet: s.name, row: s.originRow - 1 + r, col: s.originCol + c, serial: v, numberFormat: fmt });
        }
      }),
    );
    if (picks.length >= MAX_DATE_PROBES) break;
  }
  if (picks.length === 0) return [];
  try {
    const cells = picks.map((p) => {
      const cell = context.workbook.worksheets.getItem(p.sheet).getCell(p.row, p.col);
      cell.load("text");
      return cell;
    });
    await context.sync();
    return picks.map((p, i) => ({ serial: p.serial, numberFormat: p.numberFormat, text: String(cells[i]!.text?.[0]?.[0] ?? "") }));
  } catch {
    return []; // the date system is then "unknown", which only means date values can't be written
  }
}

async function readTables(context: Excel.RequestContext): Promise<TableSnapshot[]> {
  const tables = context.workbook.tables;
  tables.load("items/id,items/name,items/showHeaders,items/showTotals");
  await context.sync();
  const parts = tables.items.map((t) => {
    const range = t.getRange();
    range.load("address");
    const ws = t.worksheet;
    ws.load("name");
    const cols = t.columns;
    cols.load("items/id,items/name");
    return { t, range, ws, cols };
  });
  await context.sync();
  return parts
    .filter((p) => !INTERNAL_SHEET.test(p.ws.name))
    .map((p) => ({
      id: p.t.id,
      name: p.t.name,
      sheet: p.ws.name,
      address: p.range.address,
      showHeaders: p.t.showHeaders,
      showTotals: p.t.showTotals,
      columns: p.cols.items.map((c) => c.name),
      columnIds: p.cols.items.map((c) => String(c.id)),
    }));
}

async function readNames(context: Excel.RequestContext): Promise<NamedItemSnapshot[]> {
  const names = context.workbook.names;
  names.load("items/name,items/type,items/formula");
  await context.sync();
  return names.items
    .filter((n) => n.type === "Range" || n.type === "Error" || typeof n.formula === "string")
    .map((n) => ({ name: n.name, formula: String(n.formula ?? "") }))
    .filter((n) => n.formula.length > 0);
}

/** Chart series and PivotTable sources (ExcelApi 1.15). Best effort. */
async function readChartAndPivotSources(context: Excel.RequestContext, sheets: string[]): Promise<ReferenceSource[]> {
  if (!supports("1.15")) return [];
  const out: ReferenceSource[] = [];
  try {
    const perSheet = sheets.map((name) => {
      const charts = context.workbook.worksheets.getItem(name).charts;
      charts.load("items/name");
      return { name, charts };
    });
    const pivots = context.workbook.pivotTables;
    pivots.load("items/name");
    await context.sync();

    const seriesParts = perSheet.flatMap(({ name, charts }) =>
      charts.items.map((chart) => {
        const series = chart.series;
        series.load("items/name");
        return { sheet: name, chart, series };
      }),
    );
    const pivotParts = pivots.items.map((p) => {
      const ws = p.worksheet;
      ws.load("name");
      return { pivot: p, ws, source: p.getDataSourceString() };
    });
    await context.sync();

    const dataSources = seriesParts.flatMap(({ sheet, chart, series }) =>
      series.items.map((s) => ({
        sheet,
        location: `${sheet} · chart "${chart.name}" · series "${s.name}"`,
        values: s.getDimensionDataSourceString(Excel.ChartSeriesDimension.values),
      })),
    );
    await context.sync();

    for (const d of dataSources) out.push({ kind: "chartSeries", location: d.location, sheet: d.sheet, text: d.values.value });
    for (const p of pivotParts) {
      out.push({ kind: "pivotTable", location: `${p.ws.name} · PivotTable "${p.pivot.name}"`, sheet: p.ws.name, text: p.source.value });
    }
  } catch {
    // Unsupported chart types and external pivot sources throw; the scan goes on without them.
  }
  return out;
}

/** Custom conditional-format rules (ExcelApi 1.6). Best effort. */
async function readConditionalFormats(context: Excel.RequestContext, sheets: string[]): Promise<ReferenceSource[]> {
  if (!supports("1.6")) return [];
  const out: ReferenceSource[] = [];
  try {
    const perSheet = sheets.map((name) => {
      const formats = context.workbook.worksheets.getItem(name).getRange().conditionalFormats;
      formats.load("items/type");
      return { name, formats };
    });
    await context.sync();
    const custom = perSheet.flatMap(({ name, formats }) =>
      formats.items
        .filter((f) => f.type === Excel.ConditionalFormatType.custom)
        .map((f) => {
          const rule = f.custom.rule;
          rule.load("formula");
          const range = f.getRange();
          range.load("address");
          return { name, rule, range };
        }),
    );
    await context.sync();
    for (const c of custom) out.push({ kind: "conditionalFormat", location: c.range.address, sheet: c.name, text: c.rule.formula });
  } catch {
    // Conditional formats are optional evidence; never fail the scan over them.
  }
  return out;
}

/** List-validation rules on the data rows of each catalogued column (ExcelApi 1.8). */
async function readValidations(context: Excel.RequestContext, draft: WorkbookCatalog): Promise<ValidationSnapshot[]> {
  if (!supports("1.8")) return [];
  const targets = draft.entities
    .flatMap((e) =>
      e.columns.map((c) => ({
        sheet: e.sheet,
        address: `${c.letter}${e.firstDataRow}:${c.letter}${Math.max(e.firstDataRow, e.lastDataRow)}`,
      })),
    )
    .slice(0, MAX_VALIDATION_COLUMNS);
  const out: ValidationSnapshot[] = [];
  try {
    const parts = targets.map((t) => {
      const dv = context.workbook.worksheets.getItem(t.sheet).getRange(t.address).dataValidation;
      dv.load("type,rule");
      return { ...t, dv };
    });
    await context.sync();
    for (const p of parts) {
      if (p.dv.type !== Excel.DataValidationType.list) continue;
      const source = String(p.dv.rule.list?.source ?? "").trim();
      if (!source) continue;
      const address = `${p.sheet}!${p.address}`;
      if (source.startsWith("=")) out.push({ sheet: p.sheet, address, listSource: source });
      else out.push({ sheet: p.sheet, address, listValues: source.split(",").map((s) => s.trim()).filter(Boolean) });
    }
  } catch {
    // Mixed rules across a column throw on some hosts; the column simply has no list.
  }
  return out;
}

export interface ScanOptions {
  exemplars: boolean;
  corrections: CatalogCorrection[];
  /** The previous catalog, so regions that moved or grew keep their ids. */
  previous: WorkbookCatalog | null;
}

export interface ScanResult {
  catalog: WorkbookCatalog;
  /** Kept in memory (never persisted) so a correction can rebuild without re-reading Excel. */
  snapshot: WorkbookSnapshot;
}

/** Reads the workbook and builds its catalog. Nothing is written to the workbook here. */
export async function scanWorkbook(opts: ScanOptions): Promise<ScanResult> {
  return Excel.run(async (context) => {
    const worksheets = context.workbook.worksheets;
    worksheets.load("items/id,items/name");
    await context.sync();
    const refs = worksheets.items.filter((w) => !INTERNAL_SHEET.test(w.name)).map((w) => ({ id: w.id, name: w.name }));
    const sheetNames = refs.map((r) => r.name);

    const tables = await readTables(context);
    const names = await readNames(context);
    const sheets = await readSheets(context, refs);
    const dateProbes = await readDateProbes(context, sheets);
    const sources = [
      ...(await readChartAndPivotSources(context, sheetNames)),
      ...(await readConditionalFormats(context, sheetNames)),
    ];

    const snapshot: WorkbookSnapshot = { sheets, tables, names, sources, validations: [], dateProbes };
    const build = { exemplars: opts.exemplars, corrections: opts.corrections, previous: opts.previous };
    const draft = buildCatalog(snapshot, build);
    snapshot.validations = await readValidations(context, draft);
    const catalog =
      snapshot.validations.length > 0
        ? buildCatalog(snapshot, build)
        : draft;
    return { catalog, snapshot };
  });
}

// ── Persistence: a custom XML part travels with the file and stays out of the grid ─────────────

export async function loadCatalog(): Promise<WorkbookCatalog | null> {
  if (!supports("1.5")) return null;
  return Excel.run(async (context) => {
    const parts = context.workbook.customXmlParts.getByNamespace(CATALOG_NAMESPACE);
    parts.load("items/id");
    await context.sync();
    const first = parts.items[0];
    if (!first) return null;
    const xml = first.getXml();
    await context.sync();
    return decodeCatalogXml(xml.value);
  });
}

export async function saveCatalog(catalog: WorkbookCatalog): Promise<void> {
  if (!supports("1.5")) throw new Error("This version of Excel can't store the catalog in the workbook (needs ExcelApi 1.5).");
  await Excel.run(async (context) => {
    const parts = context.workbook.customXmlParts.getByNamespace(CATALOG_NAMESPACE);
    parts.load("items/id");
    await context.sync();
    parts.items.forEach((p) => p.delete());
    context.workbook.customXmlParts.add(encodeCatalogXml(catalog));
    await context.sync();
  });
}

// ── Change tracking ───────────────────────────────────────────────────────────────────────────

export interface ChangeWatch {
  stop(): Promise<void>;
}

/**
 * Calls `onChange(sheet, address)` whenever cells change, so the pane can mark only the affected
 * entities stale. Uses the workbook-level event (ExcelApi 1.9); returns null where unsupported.
 */
export async function watchChanges(onChange: (sheet: string, address: string) => void): Promise<ChangeWatch | null> {
  if (!supports("1.9")) return null;
  return Excel.run(async (context) => {
    const worksheets = context.workbook.worksheets;
    worksheets.load("items/id,items/name");
    await context.sync();
    const nameById = new Map(worksheets.items.map((w) => [w.id, w.name]));
    const handler = worksheets.onChanged.add(async (event: Excel.WorksheetChangedEventArgs) => {
      const sheet = nameById.get(event.worksheetId);
      if (sheet && !INTERNAL_SHEET.test(sheet)) onChange(sheet, event.address);
    });
    await context.sync();
    return {
      async stop() {
        await Excel.run(handler.context, async (ctx) => {
          handler.remove();
          await ctx.sync();
        });
      },
    };
  });
}

// ── Per-workbook preferences ─────────────────────────────────────────────────────────────────

const EXEMPLARS_SETTING = "sheaf.catalog.exemplars";

export function getExemplarsSetting(): boolean {
  const v = Office.context.document.settings.get(EXEMPLARS_SETTING) as unknown;
  return typeof v === "boolean" ? v : true;
}

export function setExemplarsSetting(value: boolean): Promise<void> {
  Office.context.document.settings.set(EXEMPLARS_SETTING, value);
  return new Promise((resolve, reject) =>
    Office.context.document.settings.saveAsync((r) =>
      r.status === Office.AsyncResultStatus.Succeeded ? resolve() : reject(new Error(r.error.message)),
    ),
  );
}
