/* eslint-disable @typescript-eslint/no-require-imports */
const path = require("path");
const webpack = require("webpack");
const HtmlWebpackPlugin = require("html-webpack-plugin");
const CopyWebpackPlugin = require("copy-webpack-plugin");

const devCerts = require("office-addin-dev-certs");

/**
 * Content Security Policy for the pane. Store mode keeps API keys in this origin's storage, so no
 * script may run here except the bundle and Microsoft's Office.js, and the pane may talk only to
 * the Sheaf service and Microsoft (Office.js). Styles need 'unsafe-inline' (Fluent UI injects them).
 */
function contentSecurityPolicy(serviceUrl, dev) {
  const microsoft = "https://appsforoffice.microsoft.com https://*.microsoft.com https://*.office.com https://*.office.net https://*.officeapps.live.com";
  return [
    "default-src 'self'",
    `script-src 'self' ${microsoft}`,
    `connect-src 'self' ${serviceUrl} ${microsoft}${dev ? " wss://localhost:3000 https://localhost:3000" : ""}`,
    "style-src 'self' 'unsafe-inline'",
    `img-src 'self' data: ${microsoft}`,
    `font-src 'self' data: ${microsoft}`,
    `frame-src ${microsoft}`,
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'none'",
  ].join("; ");
}

module.exports = async (env, options) => {
  const dev = options.mode === "development";
  const serviceUrl = process.env.SHEAF_SERVICE_URL || "https://localhost:8443";
  const config = {
    devtool: dev ? "source-map" : false,
    entry: {
      taskpane: ["./src/taskpane/index.tsx"],
    },
    output: {
      path: path.resolve(__dirname, "dist"),
      clean: true,
    },
    resolve: {
      extensions: [".ts", ".tsx", ".js"],
      alias: {
        "@sheaf/contract$": path.resolve(__dirname, "../contract/types/plan.d.ts"),
        "@sheaf/contract/catalog$": path.resolve(__dirname, "../contract/types/catalog.d.ts"),
        "@sheaf/contract/unbound$": path.resolve(__dirname, "../contract/types/unbound-plan.d.ts"),
        "@sheaf/contract/response$": path.resolve(__dirname, "../contract/types/planner-response.d.ts"),
        "@sheaf/contract/commit$": path.resolve(__dirname, "../contract/types/commit-request.d.ts"),
        "@sheaf/contract/check$": path.resolve(__dirname, "../contract/types/check-report.d.ts"),
      },
    },
    module: {
      rules: [
        {
          test: /\.tsx?$/,
          use: "ts-loader",
          exclude: /node_modules/,
        },
        {
          test: /\.css$/,
          use: ["style-loader", "css-loader"],
        },
      ],
    },
    plugins: [
      new webpack.DefinePlugin({
        __SHEAF_SERVICE_URL__: JSON.stringify(serviceUrl),
      }),
      new HtmlWebpackPlugin({
        filename: "taskpane.html",
        template: "./src/taskpane/taskpane.html",
        chunks: ["taskpane"],
        csp: contentSecurityPolicy(serviceUrl, dev),
      }),
      new CopyWebpackPlugin({
        patterns: [{ from: "assets", to: "assets", noErrorOnMissing: true }],
      }),
    ],
    devServer: {
      port: 3000,
      server: dev
        ? {
            type: "https",
            options: await devCerts.getHttpsServerOptions(),
          }
        : "http",
      headers: {
        "Access-Control-Allow-Origin": "*",
      },
    },
  };

  return config;
};
