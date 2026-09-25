import * as React from "react";
import { useState } from "react";
import { Tab, TabList, makeStyles, tokens } from "@fluentui/react-components";
import AskView from "./AskView";
import RunView from "./RunView";
import WorkbookView from "./WorkbookView";
import { WorkbookProvider } from "../workbookContext";

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
});

type TabId = "ask" | "workbook" | "run";

export default function App() {
  const styles = useStyles();
  const [tab, setTab] = useState<TabId>("workbook");

  return (
    <WorkbookProvider>
      <div className={styles.root}>
        <div className={styles.header}>Sheaf</div>
        <TabList selectedValue={tab} onTabSelect={(_, d) => setTab(d.value as TabId)} size="small">
          <Tab value="workbook">Workbook</Tab>
          <Tab value="ask">Ask</Tab>
          <Tab value="run">Run</Tab>
        </TabList>
        {/* Kept mounted so its scan and change watch survive tab switches. */}
        <div hidden={tab !== "workbook"}>
          <WorkbookView />
        </div>
        {tab === "run" && <RunView />}
        {tab === "ask" && <AskView onRun={() => setTab("run")} />}
      </div>
    </WorkbookProvider>
  );
}
