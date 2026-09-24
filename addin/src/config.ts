/** Replaced at build time by webpack's DefinePlugin (see webpack.config.js). */
declare const __SHEAF_SERVICE_URL__: string;

/**
 * Base URL of the Sheaf service. Defaults to the HTTPS dev profile (https://localhost:8443);
 * override by setting SHEAF_SERVICE_URL when building or starting the dev server.
 */
export const SERVICE_URL: string = __SHEAF_SERVICE_URL__;
