import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig, devices } from "@playwright/test";

const __dirname = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(__dirname, "../..");
const BASE_URL = process.env.SQLFLOW_BASE_URL || "http://localhost:8165";
const EPHEMERAL_HOME = join(tmpdir(), "sqlflow-pw-ephemeral-runtime");

export default defineConfig({
  testDir: ".",
  testMatch: ["**/*.spec.ts"],
  outputDir: process.env.PLAYWRIGHT_OUTPUT_DIR || join(tmpdir(), "sqlflow-playwright-results"),
  timeout: 90_000,
  expect: {
    timeout: 15_000
  },
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter:
    process.env.PLAYWRIGHT_HTML_REPORT === "1"
      ? [
          ["list"],
          [
            "html",
            {
              open: "never",
              outputFolder: join(tmpdir(), "sqlflow-playwright-report")
            }
          ]
        ]
      : [["list"]],
  webServer:
    process.env.SQLFLOW_SKIP_WEBSERVER === "1" || Boolean(process.env.SQLFLOW_BASE_URL)
      ? undefined
      : {
          command: [
            `rm -rf "${EPHEMERAL_HOME}"`,
            `mkdir -p "${EPHEMERAL_HOME}/bin" "${EPHEMERAL_HOME}/conf.defaults" "${EPHEMERAL_HOME}/lib" "${EPHEMERAL_HOME}/static" "${EPHEMERAL_HOME}/widget"`,
            `cp -R "${REPO_ROOT}/deploy/bin/"* "${EPHEMERAL_HOME}/bin/"`,
            `cp -R "${REPO_ROOT}/deploy/conf/"* "${EPHEMERAL_HOME}/conf.defaults/"`,
            `cp "${REPO_ROOT}/deploy/docker-entrypoint.sh" "${EPHEMERAL_HOME}/docker-entrypoint.sh"`,
            `cp "${REPO_ROOT}/backend-service/target/backend-service-8.2.2.15.jar" "${EPHEMERAL_HOME}/lib/sqlflow-server.jar"`,
            `[ -d "${REPO_ROOT}/frontend-ui/dist" ] && cp -R "${REPO_ROOT}/frontend-ui/dist/"* "${EPHEMERAL_HOME}/static/" || true`,
            `[ -d "${REPO_ROOT}/widget-sdk/dist" ] && cp -R "${REPO_ROOT}/widget-sdk/dist/"* "${EPHEMERAL_HOME}/widget/" || true`,
            `chmod +x "${EPHEMERAL_HOME}/docker-entrypoint.sh" "${EPHEMERAL_HOME}/bin/"*.sh`,
            `SQLFLOW_HOME="${EPHEMERAL_HOME}" "${EPHEMERAL_HOME}/docker-entrypoint.sh"`
          ].join(" && "),
          url: BASE_URL,
          reuseExistingServer: true,
          timeout: 90_000
        },
  use: {
    baseURL: BASE_URL,
    viewport: { width: 1440, height: 900 },
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off",
    ignoreHTTPSErrors: true
  },
  projects: [
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"]
      }
    }
  ]
});
