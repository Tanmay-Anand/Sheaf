import * as React from "react";
import { createRoot } from "react-dom/client";
import { FluentProvider, webLightTheme } from "@fluentui/react-components";
import App from "./components/App";

/* global document, Office */

Office.onReady(() => {
  const container = document.getElementById("root");
  if (!container) throw new Error("Root element not found");

  createRoot(container).render(
    <React.StrictMode>
      <FluentProvider theme={webLightTheme}>
        <App />
      </FluentProvider>
    </React.StrictMode>
  );
});
