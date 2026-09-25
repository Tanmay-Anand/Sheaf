import * as React from "react";
import { useCallback, useEffect, useRef, useState } from "react";
import {
  Accordion,
  AccordionHeader,
  AccordionItem,
  AccordionPanel,
  Badge,
  Body1,
  Button,
  Caption1,
  Dropdown,
  MessageBar,
  MessageBarBody,
  Option,
  Spinner,
  Switch,
  Tooltip,
  makeStyles,
  tokens,
} from "@fluentui/react-components";
import type {
  CatalogColumn,
  CatalogCorrection,
  CatalogEntity,
  ScalarKind,
  WorkbookCatalog,
} from "@sheaf/contract/catalog";
import { buildCatalog } from "../../catalog/build";
import { staleEntities } from "../../catalog/persist";
import { submitCatalog, type CatalogSummary } from "../../api/catalogClient";
import {
  type ChangeWatch,
  getExemplarsSetting,
  loadCatalog,
  saveCatalog,
  scanWorkbook,
  setExemplarsSetting,
  watchChanges,
} from "../../excel/catalog";
import { useWorkbook } from "../workbookContext";

const KINDS: ScalarKind[] = ["string", "categorical", "number", "currency", "percent", "date", "datetime", "boolean", "empty"];

const useStyles = makeStyles({
  root: { display: "flex", flexDirection: "column", gap: tokens.spacingVerticalM },
  row: { display: "flex", alignItems: "center", gap: tokens.spacingHorizontalS, flexWrap: "wrap" },
  muted: { color: tokens.colorNeutralForeground3 },
  column: {
    display: "grid",
    gridTemplateColumns: "minmax(0, 1fr) auto",
    gap: `${tokens.spacingVerticalXXS} ${tokens.spacingHorizontalS}`,
    padding: `${tokens.spacingVerticalXS} 0`,
    borderBottom: `1px solid ${tokens.colorNeutralStroke2}`,
  },
  name: { fontWeight: tokens.fontWeightSemibold, overflowWrap: "anywhere" },
  kind: { minWidth: "120px" },
  warn: { color: tokens.colorPaletteMarigoldForeground1 },
  list: { margin: 0, paddingLeft: tokens.spacingHorizontalL },
});

function dependentSummary(c: CatalogColumn): string {
  if (c.dependents.length === 0) return "Nothing in the workbook reads this column.";
  return c.dependents.map((d) => `${d.location} (${d.kind})`).join("\n");
}

function ColumnRow({
  entity,
  column,
  onCorrect,
}: {
  entity: CatalogEntity;
  column: CatalogColumn;
  onCorrect: (entity: CatalogEntity, column: CatalogColumn, kind: ScalarKind) => void;
}) {
  const styles = useStyles();
  const pct = Math.round(column.nullRate * 100);
  return (
    <div className={styles.column}>
      <div>
        <div className={styles.name}>
          {column.name} <Caption1 className={styles.muted}>({column.letter})</Caption1>
        </div>
        <Caption1 className={styles.muted}>
          {column.kind === "currency" && column.unit ? `${column.unit} · ` : ""}
          {pct > 0 ? `${pct}% blank · ` : ""}
          {column.distinctCount.toLocaleString()} distinct
          {column.keyCandidate ? " · unique" : ""}
          {column.formula ? " · formulas" : ""}
        </Caption1>
        {column.warnings.map((w) => (
          <div key={w}>
            <Caption1 className={styles.warn}>{w}</Caption1>
          </div>
        ))}
      </div>
      <div className={styles.row}>
        <Dropdown
          className={styles.kind}
          size="small"
          aria-label={`Type of ${column.name}`}
          value={column.kind}
          selectedOptions={[column.kind]}
          onOptionSelect={(_, d) => d.optionValue && onCorrect(entity, column, d.optionValue as ScalarKind)}
        >
          {KINDS.map((k) => (
            <Option key={k} value={k}>
              {k}
            </Option>
          ))}
        </Dropdown>
        <Tooltip content={dependentSummary(column)} relationship="description">
          <Badge appearance={column.dependents.length ? "filled" : "outline"} color={column.dependents.length ? "warning" : "informative"}>
            used by {column.dependents.length}
          </Badge>
        </Tooltip>
      </div>
    </div>
  );
}

export default function WorkbookView() {
  const styles = useStyles();
  const { catalog, setCatalog, snapshot, setSnapshot } = useWorkbook();
  const [exemplars, setExemplars] = useState<boolean>(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [service, setService] = useState<CatalogSummary | string | null>(null);
  const [stale, setStale] = useState<{ entities: Set<string>; outside: boolean }>({ entities: new Set(), outside: false });
  const catalogRef = useRef<WorkbookCatalog | null>(null);
  catalogRef.current = catalog;

  const commit = useCallback(async (next: WorkbookCatalog) => {
    setCatalog(next);
    await saveCatalog(next);
    try {
      setService(await submitCatalog(next));
    } catch {
      setService("The Sheaf service isn't reachable. The catalog is saved in this workbook; the service will get it next time.");
    }
  }, []);

  const scan = useCallback(async () => {
    setBusy("Reading the workbook…");
    setError(null);
    try {
      const result = await scanWorkbook({
        exemplars,
        corrections: catalogRef.current?.corrections ?? [],
        previous: catalogRef.current,
      });
      setSnapshot(result.snapshot);
      setStale({ entities: new Set(), outside: false });
      await commit(result.catalog);
    } catch (e) {
      setError(e instanceof Error ? e.message : "The scan failed.");
    } finally {
      setBusy(null);
    }
  }, [exemplars, commit]);

  useEffect(() => {
    let watch: ChangeWatch | null = null;
    setExemplars(getExemplarsSetting());
    loadCatalog()
      .then((saved) => saved && setCatalog(saved))
      .catch(() => undefined);
    watchChanges((sheet, address) => {
      const current = catalogRef.current;
      if (!current) return;
      const s = staleEntities(current, sheet, address);
      setStale((prev) => ({ entities: new Set([...prev.entities, ...s.entities]), outside: prev.outside || s.outsideEntities }));
    })
      .then((w) => (watch = w))
      .catch(() => undefined);
    return () => {
      void watch?.stop();
    };
  }, []);

  const correct = useCallback(
    async (entity: CatalogEntity, column: CatalogColumn, kind: ScalarKind) => {
      if (!catalog || kind === column.kind) return;
      const corrections: CatalogCorrection[] = [
        ...catalog.corrections.filter((c) => !(c.entity === entity.name && c.column === column.name)),
        { sheet: entity.sheet, entity: entity.name, column: column.name, kind },
      ];
      if (snapshot) {
        await commit(buildCatalog(snapshot, { exemplars, corrections, previous: catalog }));
        return;
      }
      // Loaded from the workbook without a fresh read: rescan so the correction is applied to real data.
      setCatalog({ ...catalog, corrections });
      catalogRef.current = { ...catalog, corrections };
      await scan();
    },
    [catalog, snapshot, exemplars, commit, scan],
  );

  const toggleExemplars = async (value: boolean) => {
    setExemplars(value);
    await setExemplarsSetting(value).catch(() => undefined);
  };

  const columnCount = catalog?.entities.reduce((n, e) => n + e.columns.length, 0) ?? 0;

  return (
    <div className={styles.root}>
      <Body1>
        Sheaf reads the structure of every sheet: tables, columns, types, and what depends on each column. Rows stay in
        this workbook.
      </Body1>
      <div className={styles.row}>
        <Button appearance="primary" onClick={() => void scan()} disabled={busy !== null}>
          {catalog ? "Rescan workbook" : "Scan workbook"}
        </Button>
        <Switch
          checked={exemplars}
          label="Include up to 5 sample values per column"
          onChange={(_, d) => void toggleExemplars(d.checked)}
        />
      </div>
      {busy && <Spinner size="tiny" label={busy} />}
      {error && (
        <MessageBar layout="multiline" intent="error">
          <MessageBarBody>{error}</MessageBarBody>
        </MessageBar>
      )}
      {(stale.entities.size > 0 || stale.outside) && (
        <MessageBar layout="multiline" intent="warning">
          <MessageBarBody>
            Changed since the last scan: {[...stale.entities].join(", ") || "cells outside known tables"}. Rescan to update.
          </MessageBarBody>
        </MessageBar>
      )}

      {catalog && (
        <>
          <Caption1 className={styles.muted}>
            {catalog.entities.length} {catalog.entities.length === 1 ? "table" : "tables"} · {columnCount} {columnCount === 1 ? "column" : "columns"} · scanned {new Date(catalog.generatedAt).toLocaleString()}
          </Caption1>
          {typeof service === "string" && (
            <MessageBar layout="multiline" intent="warning">
              <MessageBarBody>{service}</MessageBarBody>
            </MessageBar>
          )}
          {service && typeof service !== "string" && !service.accepted && (
            <MessageBar layout="multiline" intent="error">
              <MessageBarBody>The service refused the catalog: {service.violations.join(" ")}</MessageBarBody>
            </MessageBar>
          )}
          {catalog.warnings.length > 0 && (
            <MessageBar layout="multiline" intent="warning">
              <MessageBarBody>
                <ul className={styles.list}>
                  {catalog.warnings.map((w) => (
                    <li key={w}>{w}</li>
                  ))}
                </ul>
              </MessageBarBody>
            </MessageBar>
          )}

          <Accordion multiple collapsible>
            {catalog.entities.map((e) => (
              <AccordionItem key={e.name} value={e.name}>
                <AccordionHeader>
                  <div>
                    <div>
                      {e.name} {stale.entities.has(e.name) && <Badge color="warning">changed</Badge>}
                    </div>
                    <Caption1 className={styles.muted}>
                      {e.address} · {e.dataRowCount.toLocaleString()} rows
                      {e.skippedRows.length ? ` · ${e.skippedRows.length} skipped` : ""}
                      {e.kind === "table" ? " · Excel Table" : ""}
                    </Caption1>
                  </div>
                </AccordionHeader>
                <AccordionPanel>
                  {e.columns.map((c) => (
                    <ColumnRow key={c.name} entity={e} column={c} onCorrect={(en, co, k) => void correct(en, co, k)} />
                  ))}
                </AccordionPanel>
              </AccordionItem>
            ))}
          </Accordion>

          {catalog.joinCandidates.length > 0 && (
            <div>
              <Body1>Possible links between tables</Body1>
              <ul className={styles.list}>
                {catalog.joinCandidates.map((j) => (
                  <li key={`${j.fromEntity}.${j.fromColumn}>${j.toEntity}.${j.toColumn}`}>
                    <Caption1>
                      {j.fromEntity}.{j.fromColumn} → {j.toEntity}.{j.toColumn} ({Math.round(j.overlap * 100)}% of values match,{" "}
                      {j.cardinality}) · not yet approved
                    </Caption1>
                  </li>
                ))}
              </ul>
            </div>
          )}

          {catalog.untraceable.length > 0 && (
            <div>
              <Body1>References Sheaf can&apos;t follow</Body1>
              <ul className={styles.list}>
                {catalog.untraceable.map((u) => (
                  <li key={`${u.location}-${u.kind}`}>
                    <Caption1>
                      {u.location}: {u.detail}
                    </Caption1>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </>
      )}
    </div>
  );
}
