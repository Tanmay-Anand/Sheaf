import * as React from "react";
import { useEffect, useState } from "react";
import {
  Body1,
  Button,
  Caption1,
  Field,
  Input,
  MessageBar,
  MessageBarBody,
  Spinner,
  makeStyles,
  tokens,
} from "@fluentui/react-components";
import { ask, AskFailed, plannerStatus, type AskOutcome, type ModelChoice, type PlannerStatus } from "../../api/askClient";
import ModelSettings, { loadChoice, providerOf, ready, saveChoice } from "./ModelSettings";
import type { Plan } from "../../engine/ir";
import { useWorkbook } from "../workbookContext";

/**
 * A question in plain words → a checked plan (opened in Run for preview and commit), a clarifying
 * question, or a refusal with what Sheaf can do instead. Only the catalog leaves the pane.
 */

const useStyles = makeStyles({
  root: { display: "flex", flexDirection: "column", gap: tokens.spacingVerticalM },
  row: { display: "flex", alignItems: "center", gap: tokens.spacingHorizontalS, flexWrap: "wrap" },
  muted: { color: tokens.colorNeutralForeground3 },
  list: { margin: 0, paddingLeft: tokens.spacingHorizontalL },
  sent: {
    maxHeight: "240px",
    overflow: "auto",
    whiteSpace: "pre-wrap",
    fontFamily: tokens.fontFamilyMonospace,
    fontSize: tokens.fontSizeBase100,
    backgroundColor: tokens.colorNeutralBackground3,
    padding: tokens.spacingHorizontalS,
  },
});

/** The bound plan back in the unbound form the Run tab edits and re-checks. */
function unboundText(plan: Plan): string {
  const intent =
    plan.kind !== "query"
      ? undefined
      : plan.sink.mode === "newSheet"
        ? { mode: "newSheet", name: plan.sink.name }
        : plan.sink.mode === "template"
          ? { mode: "template", templateId: plan.sink.templateId }
          : { mode: "anchor" };
  const body =
    plan.kind === "query"
      ? { kind: "query", source: plan.source, steps: plan.steps, sink: intent, params: plan.params }
      : { kind: "edit", target: plan.target, ops: plan.ops, params: plan.params };
  return JSON.stringify(body, null, 2);
}

function money(n: number | undefined): string {
  return n === undefined ? "" : ` · $${n.toFixed(4)}`;
}

export default function AskView({ onRun }: { onRun: () => void }) {
  const styles = useStyles();
  const { catalog, setHandoff } = useWorkbook();
  const [status, setStatus] = useState<PlannerStatus | null>(null);
  const [choice, setChoice] = useState<ModelChoice>({ provider: "", model: "", zeroEgress: false, ...loadChoice() });
  const [showSettings, setShowSettings] = useState(false);
  const [question, setQuestion] = useState("");
  const [outcome, setOutcome] = useState<AskOutcome | null>(null);
  const [asked, setAsked] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    plannerStatus()
      .then((s) => {
        setStatus(s);
        setChoice((c) => {
          if (c.provider && s.providers.some((p) => p.id === c.provider)) return c;
          const p = s.providers.find((x) => x.id === s.defaultProvider);
          return { ...c, provider: s.defaultProvider, model: c.model || p?.defaultModel || "" };
        });
      })
      .catch(() => setError("The Sheaf service isn't reachable."));
  }, []);

  async function run(q: string) {
    if (!catalog || !q.trim()) return;
    setBusy(true);
    setError(null);
    setOutcome(null);
    setAsked(q);
    saveChoice(choice);
    try {
      setOutcome(await ask(q, catalog, choice));
    } catch (e) {
      setError(e instanceof AskFailed || e instanceof Error ? e.message : "The question couldn't be planned.");
    } finally {
      setBusy(false);
    }
  }

  function openInRun() {
    const report = outcome?.report;
    const plan = report?.envelope?.plan as unknown as Plan | undefined;
    if (!report || !plan) return;
    setHandoff({ report, text: unboundText(plan) });
    onRun();
  }

  if (!catalog) return <Body1>Scan the workbook first (Workbook tab): questions are planned against what Sheaf knows about it.</Body1>;

  const plan = outcome?.report?.envelope?.plan as unknown as Plan | undefined;

  return (
    <div className={styles.root}>
      <Field label="Ask about your data, or ask for a change">
        <Input
          value={question}
          onChange={(_, d) => setQuestion(d.value)}
          placeholder="e.g. total revenue by region, largest first"
          disabled={busy}
          onKeyDown={(e) => e.key === "Enter" && void run(question)}
        />
      </Field>
      <div className={styles.row}>
        <Button appearance="primary" onClick={() => void run(question)} disabled={busy || !question.trim() || !ready(status, choice)}>
          Plan
        </Button>
        <Button appearance="subtle" onClick={() => setShowSettings((v) => !v)}>
          {providerOf(status, choice.provider)?.label.split(" (")[0] ?? "Model"} · {choice.model || "choose a model"}
        </Button>
      </div>
      {status && (showSettings || !ready(status, choice)) && (
        <ModelSettings
          status={status}
          choice={choice}
          onChange={(c) => {
            setChoice(c);
            saveChoice(c);
          }}
        />
      )}
      {busy && <Spinner size="tiny" label="Planning…" />}
      {error && (
        <MessageBar layout="multiline" intent="error">
          <MessageBarBody>{error}</MessageBarBody>
        </MessageBar>
      )}

      {outcome?.kind === "plan" && plan && (
        <>
          <Body1>
            A plan that {plan.kind === "query" ? `reads ${plan.source}` : `edits ${plan.target}`} and checks against this workbook.
          </Body1>
          {(outcome.annotations?.assumptions.length ?? 0) > 0 && (
            <MessageBar layout="multiline" intent="info">
              <MessageBarBody>
                Assumed:
                <ul className={styles.list}>
                  {outcome.annotations!.assumptions.map((a) => (
                    <li key={a}>{a}</li>
                  ))}
                </ul>
              </MessageBarBody>
            </MessageBar>
          )}
          {outcome.report?.diagnostics.filter((d) => d.code.startsWith("W_")).map((d) => (
            <MessageBar layout="multiline" key={`${d.code}${d.pointer}`} intent="warning">
              <MessageBarBody>{d.message}</MessageBarBody>
            </MessageBar>
          ))}
          <div className={styles.row}>
            <Button appearance="primary" onClick={openInRun}>
              Preview in Run
            </Button>
          </div>
        </>
      )}

      {outcome?.kind === "explain" && (
        <MessageBar layout="multiline" intent="info">
          <MessageBarBody>{outcome.answer}</MessageBarBody>
        </MessageBar>
      )}

      {outcome?.kind === "clarify" && (
        <>
          <Body1>{outcome.question}</Body1>
          <div className={styles.row}>
            {(outcome.options ?? []).map((o) => (
              <Button key={o} onClick={() => void run(`${asked} (${o})`)} disabled={busy}>
                {o}
              </Button>
            ))}
          </div>
        </>
      )}

      {outcome?.kind === "refuse" && (
        <MessageBar layout="multiline" intent="warning">
          <MessageBarBody>
            Sheaf can&apos;t do that here. It understood: {outcome.understood}
            {(outcome.closestSupported?.length ?? 0) > 0 && (
              <>
                {" "}
                It can:
                <ul className={styles.list}>
                  {outcome.closestSupported!.map((c) => (
                    <li key={c}>
                      <Button appearance="transparent" size="small" onClick={() => void run(c)} disabled={busy}>
                        {c}
                      </Button>
                    </li>
                  ))}
                </ul>
              </>
            )}
          </MessageBarBody>
        </MessageBar>
      )}

      {outcome?.kind === "failed" && (
        <MessageBar layout="multiline" intent="error">
          <MessageBarBody>
            The model couldn&apos;t produce a plan that checks after {outcome.run.calls} tries. Try rephrasing, or another model.
            <ul className={styles.list}>
              {outcome.diagnostics.map((d) => (
                <li key={`${d.code}${d.pointer}`}>
                  {d.code}: {d.message}
                </li>
              ))}
            </ul>
          </MessageBarBody>
        </MessageBar>
      )}

      {outcome && (
        <Caption1 className={styles.muted}>
          {outcome.run.provider} · {outcome.run.model} · {outcome.run.calls} {outcome.run.calls === 1 ? "call" : "calls"} ·{" "}
          {(outcome.run.promptTokens + outcome.run.completionTokens).toLocaleString()} tokens{money(outcome.run.cost)} · prompt {outcome.run.promptVersion}
        </Caption1>
      )}
      {outcome && (
        <details>
          <summary>
            <Caption1>What was sent to {outcome.run.endpoint}</Caption1>
          </summary>
          <Caption1 className={styles.muted}>
            Your question, Sheaf&apos;s instructions ({outcome.run.promptVersion}), and this description of the workbook. No rows.
          </Caption1>
          <div className={styles.sent}>{outcome.sent}</div>
        </details>
      )}
    </div>
  );
}
