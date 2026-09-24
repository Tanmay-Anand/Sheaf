/* eslint-disable @typescript-eslint/no-require-imports */
const path = require("path");
const webpack = require("webpack");
const HtmlWebpackPlugin = require("html-webpack-plugin");
const CopyWebpackPlugin = require("copy-webpack-plugin");

const devCerts = require("office-addin-dev-certs");

module.exports = async (env, options) => {
  const dev = options.mode === "development";
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
        __SHEAF_SERVICE_URL__: JSON.stringify(process.env.SHEAF_SERVICE_URL || "https://localhost:8443"),
      }),
      new HtmlWebpackPlugin({
        filename: "taskpane.html",
        template: "./src/taskpane/taskpane.html",
        chunks: ["taskpane"],
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
