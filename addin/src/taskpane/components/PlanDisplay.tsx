import * as React from "react";
import {
  Body1,
  Caption1,
  Card,
  CardHeader,
  makeStyles,
  tokens,
} from "@fluentui/react-components";
import type { Plan } from "../../api/planClient";

interface Props {
  plan: Plan;
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

export default function PlanDisplay({ plan }: Props) {
  const styles = useStyles();

  return (
    <div className={styles.root}>
      <Card>
        <CardHeader
          header={<Body1>Plan received</Body1>}
          description={
            <Caption1 className={styles.label}>
              Source: {plan.source.ref} · Steps: {plan.steps.length} ·
              Sink: {plan.sink.mode}
            </Caption1>
          }
        />
      </Card>
      <div>
        <Caption1 className={styles.label}>Plan JSON</Caption1>
        <pre className={styles.pre}>{JSON.stringify(plan, null, 2)}</pre>
      </div>
    </div>
  );
}
