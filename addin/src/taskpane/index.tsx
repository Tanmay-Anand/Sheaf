import * as React from "react";
import { createRoot } from "react-dom/client";
import { FluentProvider, webDarkTheme, webLightTheme } from "@fluentui/react-components";
import App from "./components/App";

/* global document, Office */

/** Follows Excel's own theme (Dark Grey / Black vs Colorful / White) so the pane doesn't glare. */
function isDarkOfficeTheme(): boolean {
  const hex = Office.context.officeTheme?.bodyBackgroundColor?.replace("#", "");
  if (!hex || hex.length !== 6) return false;
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255);
  return 0.2126 * r! + 0.7152 * g! + 0.0722 * b! < 0.5;
}

Office.onReady(() => {
  const container = document.getElementById("root");
  if (!container) throw new Error("Root element not found");

  const theme = isDarkOfficeTheme() ? webDarkTheme : webLightTheme;
  document.body.style.backgroundColor = theme.colorNeutralBackground1;

  createRoot(container).render(
    <React.StrictMode>
      <FluentProvider theme={theme} style={{ minHeight: "100%" }}>
        <App />
      </FluentProvider>
    </React.StrictMode>
  );
});
