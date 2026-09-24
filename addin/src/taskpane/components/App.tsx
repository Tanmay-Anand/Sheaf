import * as React from "react";
import { useState } from "react";
import {
  Button,
  Field,
  Input,
  Spinner,
  Tab,
  TabList,
  makeStyles,
  tokens,
} from "@fluentui/react-components";
import { fetchPlan } from "../../api/planClient";
import PlanDisplay from "./PlanDisplay";
import WorkbookView from "./WorkbookView";
import type { PlanRecord } from "../../api/planClient";

const useStyles = makeStyles({
  root: {
    display: "flex",
    flexDirection: "column",
    gap: tokens.spacingVerticalM,
    padding: tokens.spacingHorizontalL,
    height: "100%",
  },
  header: {
    fontWeight: tokens.fontWeightSemibold,
    fontSize: tokens.fontSizeBase500,
  },
  form: {
    display: "flex",
    flexDirection: "column",
    gap: tokens.spacingVerticalS,
  },
  error: {
    color: tokens.colorStatusDangerForeground1,
    fontSize: tokens.fontSizeBase200,
  },
});

type TabId = "ask" | "workbook";

function AskView() {
  const styles = useStyles();
  const [question, setQuestion] = useState("");
  const [plan, setPlan] = useState<PlanRecord | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleAsk() {
    if (!question.trim()) return;
    setLoading(true);
    setError(null);
    setPlan(null);
    try {
      const result = await fetchPlan(question);
      setPlan(result);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Unexpected error");
    } finally {
      setLoading(false);
    }
  }

  return (
    <>
      <div className={styles.form}>
        <Field label="Ask a question about your data">
          <Input
            value={question}
            onChange={(_, data) => setQuestion(data.value)}
            placeholder="e.g. total revenue by region"
            disabled={loading}
            onKeyDown={(e) => e.key === "Enter" && handleAsk()}
          />
        </Field>
        <Button
          appearance="primary"
          onClick={handleAsk}
          disabled={loading || !question.trim()}
        >
          {loading ? <Spinner size="tiny" /> : "Plan"}
        </Button>
      </div>

      {error && <div className={styles.error}>{error}</div>}
      {plan && <PlanDisplay record={plan} />}
    </>
  );
}

export default function App() {
  const styles = useStyles();
  const [tab, setTab] = useState<TabId>("workbook");

  return (
    <div className={styles.root}>
      <div className={styles.header}>Sheaf</div>
      <TabList selectedValue={tab} onTabSelect={(_, d) => setTab(d.value as TabId)} size="small">
        <Tab value="workbook">Workbook</Tab>
        <Tab value="ask">Ask</Tab>
      </TabList>
      {tab === "workbook" ? <WorkbookView /> : <AskView />}
    </div>
  );
}
