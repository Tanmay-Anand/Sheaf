import * as React from "react";
import { useEffect, useMemo, useState } from "react";
import {
  Body1,
  Button,
  Caption1,
  Checkbox,
  Field,
  Input,
  MessageBar,
  MessageBarBody,
  Spinner,
  Textarea,
  makeStyles,
  tokens,
} from "@fluentui/react-components";
import type { WorkbookCatalog } from "@sheaf/contract/catalog";
import { checkPlan, type CheckReport } from "../../api/checkClient";
import type { Plan } from "../../engine/ir";
import { preparePreview, PreviewRefused, type GuardedRegion, type Preview } from "../../engine/preview";
import { displayText } from "../../engine/value";
import { getExemplarsSetting, scanWorkbook } from "../../excel/catalog";
import { CommitRefused, commitWrite, oneUndoStep, readTarget, selectedCell, type TargetInfo } from "../../excel/commit";
import { useWorkbook } from "../workbookContext";

/**
 * Check → preview → commit, for a plan pasted as JSON. The planner (M5) will write the plan from a
 * question; everything after that is this flow. Nothing is written until Commit, and Commit writes
 * exactly what the preview shows.
 */

const PREVIEW_ROWS = 20;

const useStyles = makeStyles({
  root: { display: "flex", flexDirection: "column", gap: tokens.spacingVerticalM },
  row: { display: "flex", alignItems: "center", gap: tokens.spacingHorizontalS, flexWrap: "wrap" },
  muted: { color: tokens.colorNeutralForeground3 },
  mono: { fontFamily: tokens.fontFamilyMonospace, fontSize: tokens.fontSizeBase200 },
  tableWrap: { overflowX: "auto", maxHeight: "320px", border: `1px solid ${tokens.colorNeutralStroke2}` },
  table: { borderCollapse: "collapse", fontSize: tokens.fontSizeBase200, width: "max-content" },
  th: {
    position: "sticky",
    top: 0,
    backgroundColor: tokens.colorNeutralBackground3,
    textAlign: "left",
    padding: `${tokens.spacingVerticalXXS} ${tokens.spacingHorizontalS}`,
    fontWeight: tokens.fontWeightSemibold,
  },
  td: {
    padding: `${tokens.spacingVerticalXXS} ${tokens.spacingHorizontalS}`,
    borderTop: `1px solid ${tokens.colorNeutralStroke2}`,
    whiteSpace: "nowrap",
  },
  list: { margin: 0, paddingLeft: tokens.spacingHorizontalL },
});

function example(catalog: WorkbookCatalog | null): string {
  const e = catalog?.entities[0];
  const plan = {
    kind: "query",
    source: e?.name ?? "Sheet1",
    steps: e?.columns[0] ? [{ op: "sort", by: [{ col: e.columns[0].name, dir: "asc" }] }] : [],
    sink: { mode: "newSheet", name: "Sheaf result" },
    params: [],
  };
  return JSON.stringify(plan, null, 2);
}

function cellText(v: unknown): string {
  return displayText(v as Parameters<typeof displayText>[0]);
}

export default function RunView() {
  const styles = useStyles();
  const { catalog, setCatalog, setSnapshot, handoff, setHandoff } = useWorkbook();
  const [text, setText] = useState<string>(() => example(catalog));
  const [report, setReport] = useState<CheckReport | null>(null);
  const [params, setParams] = useState<Record<string, string>>({});
  const [exclude, setExclude] = useState(false);
  const [anchor, setAnchor] = useState<{ sheetId: string; sheetName: string; address: string } | null>(null);
  const [target, setTarget] = useState<TargetInfo | null>(null);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  // A plan from the Ask tab arrives already checked: show it and go straight to Preview.
  useEffect(() => {
    if (!handoff) return;
    setText(handoff.text);
    setReport(handoff.report);
    setPreview(null);
    setTarget(null);
    setDone(null);
    setError(null);
    setHandoff(null);
  }, [handoff, setHandoff]);

  const plan = report?.envelope?.plan as unknown as Plan | undefined;
  const declared = plan?.params ?? [];
  const typedParams = useMemo(() => {
    const out: Record<string, unknown> = {};
    for (const p of declared) {
      const raw = params[p.name];
      if (raw === undefined || raw === "") continue;
      out[p.name] = p.valueType === "number" ? Number(raw) : p.valueType === "boolean" ? raw.toLowerCase() === "true" : raw;
    }
    return out;
  }, [declared, params]);

  const reset = () => {
    setPreview(null);
    setTarget(null);
    setDone(null);
    setError(null);
  };

  async function check(source = text) {
    if (!catalog) return;
    reset();
    setReport(null);
    setBusy("Checking the plan…");
    try {
      let body: unknown = source;
      try {
        body = JSON.parse(source);
      } catch {
        // Not JSON: send the text as is; the service reports exactly what is wrong with it.
      }
      setReport(await checkPlan(body, catalog));
    } catch (e) {
      setError(e instanceof Error ? e.message : "The check failed.");
    } finally {
      setBusy(null);
    }
  }

  async function runPreview(nextAnchor = anchor, nextExclude = exclude) {
    if (!report?.envelope || !plan) return;
    reset();
    setBusy("Reading the workbook…");
    try {
      // A fresh read: the preview must describe the workbook as it is now.
      const scan = await scanWorkbook({ exemplars: getExemplarsSetting(), corrections: catalog?.corrections ?? [], previous: catalog });
      setCatalog(scan.catalog);
      setSnapshot(scan.snapshot);
      setBusy("Evaluating…");
      const p = preparePreview({
        plan,
        output: report.output,
        ...(report.dynamicValue ? { dynamicType: report.dynamicValue.type } : {}),
        catalog: scan.catalog,
        snapshot: scan.snapshot,
        params: typedParams,
        excludeErrorCells: nextExclude,
        ...(nextAnchor ? { anchor: nextAnchor } : {}),
      });
      if (p.write?.target.kind === "existing") {
        setTarget(await readTarget(p.write.target.sheetId, p.write.top, p.write.left, p.write.rows, p.write.cols));
      }
      setPreview(p);
    } catch (e) {
      setError(e instanceof PreviewRefused || e instanceof Error ? e.message : "The preview failed.");
    } finally {
      setBusy(null);
    }
  }

  /** The same plan, written to a new sheet: a different plan (and hash), so it is checked again. */
  async function toNewSheet() {
    try {
      const unbound = JSON.parse(text) as Record<string, unknown>;
      unbound.sink = { mode: "newSheet", name: "Sheaf result" };
      const next = JSON.stringify(unbound, null, 2);
      setText(next);
      setAnchor(null);
      await check(next);
    } catch {
      setError("The plan text isn't valid JSON; set its sink to {\"mode\": \"newSheet\"} by hand.");
    }
  }

  async function chooseAnchor() {
    try {
      const cell = await selectedCell();
      setAnchor(cell);
      await runPreview(cell);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't read the selection.");
    }
  }

  async function commit() {
    if (!preview?.write) return;
    setBusy("Writing…");
    setError(null);
    try {
      const guards: GuardedRegion[] = [...preview.guards];
      if (target) guards.push({ label: "The cells to be overwritten", sheetId: target.sheetId, address: target.address, hash: target.hash });
      const out = await commitWrite(preview.write, guards);
      setDone(`Wrote ${out.address}. ${out.undoSteps === 1 ? "Ctrl+Z undoes it in one step." : `This version of Excel recorded it as ${out.undoSteps} undo steps.`}`);
      setPreview(null);
    } catch (e) {
      setError(e instanceof CommitRefused || e instanceof Error ? e.message : "The commit failed.");
    } finally {
      setBusy(null);
    }
  }

  const blocking = preview?.issues.filter((i) => i.blocking) ?? [];
  const hasErrorCells = preview?.issues.some((i) => i.code === "ERROR_CELLS" || i.code === "ERROR_CELLS_EXCLUDED") ?? false;
  const missingParams = declared.filter((p) => !(p.name in typedParams)).map((p) => p.name);

  if (!catalog) {
    return <Body1>Scan the workbook first (Workbook tab): plans are checked against what Sheaf knows about it.</Body1>;
  }

  return (
    <div className={styles.root}>
      <Body1>Paste a plan, check it against this workbook, preview the result, then commit. Nothing is written before Commit.</Body1>
      <Field label="Plan (JSON)">
        <Textarea className={styles.mono} value={text} onChange={(_, d) => setText(d.value)} rows={10} resize="vertical" />
      </Field>
      <div className={styles.row}>
        <Button appearance="primary" onClick={() => void check()} disabled={busy !== null}>
          Check
        </Button>
        <Button onClick={() => setText(example(catalog))} disabled={busy !== null}>
          Example
        </Button>
      </div>
      {busy && <Spinner size="tiny" label={busy} />}
      {error && (
        <MessageBar layout="multiline" intent="error">
          <MessageBarBody>{error}</MessageBarBody>
        </MessageBar>
      )}
      {done && (
        <MessageBar layout="multiline" intent="success">
          <MessageBarBody>{done}</MessageBarBody>
        </MessageBar>
      )}

      {report && !report.valid && (
        <MessageBar layout="multiline" intent="error">
          <MessageBarBody>
            <ul className={styles.list}>
              {report.diagnostics
                .filter((d) => d.code.startsWith("E_"))
                .map((d) => (
                  <li key={`${d.code}${d.pointer}`}>
                    <strong>{d.code}</strong> at <span className={styles.mono}>{d.pointer || "/"}</span>: {d.message} <em>{d.hint}</em>
                  </li>
                ))}
            </ul>
          </MessageBarBody>
        </MessageBar>
      )}

      {report?.valid && plan && (
        <>
          <Caption1 className={styles.muted}>
            Checked · {plan.kind === "query" ? `reads ${plan.source}` : `edits ${plan.target}`} ·{" "}
            {plan.kind === "query" && plan.sink.mode === "newSheet" ? `writes a new sheet "${plan.sink.name}"` : plan.kind === "query" ? `writes to ${plan.sink.mode}` : ""} ·{" "}
            <span className={styles.mono}>{report.planHash?.slice(0, 15)}…</span>
          </Caption1>
          {report.diagnostics.filter((d) => d.code.startsWith("W_")).map((d) => (
            <MessageBar layout="multiline" key={`${d.code}${d.pointer}`} intent="warning">
              <MessageBarBody>{d.message}</MessageBarBody>
            </MessageBar>
          ))}
          {declared.map((p) => (
            <Field key={p.name} label={`${p.name} (${p.valueType}${p.valueType === "date" ? ", yyyy-mm-dd" : ""})`}>
              <Input value={params[p.name] ?? ""} onChange={(_, d) => setParams({ ...params, [p.name]: d.value })} />
            </Field>
          ))}
          <div className={styles.row}>
            <Button onClick={() => void runPreview()} disabled={busy !== null || missingParams.length > 0}>
              Preview
            </Button>
            {plan.kind === "query" && plan.sink.mode === "anchor" && (
              <>
                <Button onClick={() => void chooseAnchor()} disabled={busy !== null}>
                  {anchor ? `Start at ${anchor.sheetName}!${anchor.address} (change)` : "Use the selected cell"}
                </Button>
                <Button appearance="subtle" onClick={() => void toNewSheet()} disabled={busy !== null}>
                  Write to a new sheet instead
                </Button>
              </>
            )}
          </div>
        </>
      )}

      {preview && (
        <>
          <Body1>
            <strong>
              {(preview.rows - 1).toLocaleString()} rows × {preview.cols} columns
            </strong>{" "}
            (plus a header row){preview.write ? ` → ${preview.write.address}` : ""}
          </Body1>
          {preview.writeRefusal && (
            <MessageBar layout="multiline" intent="warning">
              <MessageBarBody>{preview.writeRefusal}</MessageBarBody>
            </MessageBar>
          )}
          {!preview.write && !preview.writeRefusal && <Caption1 className={styles.muted}>Choose the cell where the result should start.</Caption1>}
          {target && (
            <MessageBar layout="multiline" intent={target.nonEmptyCells > 0 ? "warning" : "info"}>
              <MessageBarBody>
                Writes {target.address}:{" "}
                {target.nonEmptyCells > 0 ? `${target.nonEmptyCells.toLocaleString()} non-empty cells there will be replaced.` : "those cells are empty."}
              </MessageBarBody>
            </MessageBar>
          )}
          {preview.issues.map((i) => (
            <MessageBar layout="multiline" key={`${i.code}${i.message}`} intent={i.blocking ? "error" : "info"}>
              <MessageBarBody>{i.message}</MessageBarBody>
            </MessageBar>
          ))}
          {hasErrorCells && (
            <Checkbox
              checked={exclude}
              label="Leave out cells with error values"
              onChange={(_, d) => {
                const v = d.checked === true;
                setExclude(v);
                void runPreview(anchor, v);
              }}
            />
          )}
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr>
                  {preview.result.table.columns.map((c) => (
                    <th key={c} className={styles.th}>
                      {c}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {preview.result.table.rows.slice(0, PREVIEW_ROWS).map((r, i) => (
                  <tr key={i}>
                    {r.map((v, j) => (
                      <td key={j} className={styles.td}>
                        {cellText(v)}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {preview.result.table.rows.length > PREVIEW_ROWS && (
            <Caption1 className={styles.muted}>First {PREVIEW_ROWS} of {preview.result.table.rows.length.toLocaleString()} rows.</Caption1>
          )}
          {!oneUndoStep() && preview.write && (
            <Caption1 className={styles.muted}>
              This version of Excel can&apos;t group a commit into one undo step; this one takes {preview.write.chunks.length + 1}.
            </Caption1>
          )}
          <div className={styles.row}>
            <Button appearance="primary" onClick={() => void commit()} disabled={busy !== null || !preview.write || blocking.length > 0}>
              Commit
            </Button>
            {blocking.length > 0 && <Caption1 className={styles.muted}>Resolve the issues above to commit.</Caption1>}
          </div>
        </>
      )}
    </div>
  );
}
