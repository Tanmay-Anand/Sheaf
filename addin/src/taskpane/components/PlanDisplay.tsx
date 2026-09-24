import * as React from "react";
import {
  Body1,
  Caption1,
  Card,
  CardHeader,
  makeStyles,
  tokens,
} from "@fluentui/react-components";
import type { Plan, PlanRecord } from "../../api/planClient";

interface Props {
  record: PlanRecord;
}

function sinkLabel(sink: Extract<Plan, { kind: "query" }>["sink"]): string {
  switch (sink.mode) {
    case "newSheet":
      return `new sheet "${sink.name}"`;
    case "anchor":
      return "a cell you choose";
    case "template":
      return `template ${sink.templateId}`;
  }
}

const useStyles = makeStyles({
  root: {
    display: "flex",
    flexDirection: "column",
    gap: tokens.spacingVerticalS,
    overflow: "auto",
  },
  pre: {
    backgroundColor: tokens.colorNeutralBackground3,
    borderRadius: tokens.borderRadiusMedium,
    padding: tokens.spacingHorizontalM,
    fontSize: tokens.fontSizeBase200,
    overflowX: "auto",
    whiteSpace: "pre-wrap",
    wordBreak: "break-all",
  },
  label: {
    color: tokens.colorNeutralForeground3,
  },
});

export default function PlanDisplay({ record }: Props) {
  const styles = useStyles();
  const plan = record.envelope.plan;

  return (
    <div className={styles.root}>
      <Card>
        <CardHeader
          header={<Body1>Plan received</Body1>}
          description={
            <Caption1 className={styles.label}>
              {plan.kind === "query"
                ? `Source: ${plan.source} · Steps: ${plan.steps.length} · Writes to: ${sinkLabel(plan.sink)}`
                : `Edits ${plan.target} · Operations: ${plan.ops.length}`}
              {` · IR ${record.envelope.irVersion} · ${record.planHash.slice(0, 15)}…`}
            </Caption1>
          }
        />
      </Card>
      <div>
        <Caption1 className={styles.label}>Plan JSON</Caption1>
        <pre className={styles.pre}>{JSON.stringify(record.envelope, null, 2)}</pre>
      </div>
    </div>
  );
}
