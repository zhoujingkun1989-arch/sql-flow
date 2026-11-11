import { expect, test, type FrameLocator, type Page } from "@playwright/test";
import type {
  DependencyGraphV1,
  OnFieldClickPayload,
  OnTableNameClickPayload,
  SqlflowJSON
} from "@sqlflow/ui-core/types";
import { SqlFlowApiClient } from "./helpers/sqlflow-client.js";

interface BrowserWidgetController {
  version?: string;
  vendor: { set: (v: string) => void; get: () => string };
  sqltext: { set: (s: string) => void; get: () => string };
  token: { set: (t: string) => void; get?: () => string };
  visualize: () => Promise<unknown>;
  visualizeTableLevel: () => Promise<unknown>;
  visualizeER: () => Promise<unknown>;
  visualizeJSON?: (json: unknown, opts?: { layout?: boolean; sqltext?: string }) => Promise<void>;
  visualizeJson?: (json: unknown, opts?: { layout?: boolean; sqltext?: string }) => Promise<void>;
  visualizeDependencyGraph: (
    dg: DependencyGraphV1,
    opts?: { layout?: boolean }
  ) => Promise<void>;
  addEventListener: (
    event: "onFieldClick" | "onTableNameClick",
    cb: (payload: unknown) => void
  ) => void;
  destroy?: () => void;
}

interface HostWindowWithWidget {
  SQLFlow?: {
    version?: string;
    init: (opts: Record<string, unknown>) => Promise<BrowserWidgetController>;
  };
  __e2eWidget?: BrowserWidgetController;
  __e2eFieldClicks?: OnFieldClickPayload[];
  __e2eTableClicks?: OnTableNameClickPayload[];
}

/**
 * Mounts a minimal same-origin HTML host page on `:8165` loading `/widget/sqlflow.widget.3.6.16.js`
 * so `window.SQLFlow.init(...)` runs in a real same-origin browser context without cross-origin blocks.
 */
async function openBlankWidgetHostPage(page: Page): Promise<void> {
  const baseUrl = (process.env.SQLFLOW_BASE_URL || "http://localhost:8165").replace(/\/+$/, "");
  const hostUrl = `${baseUrl}/__e2e_widget_host__.html#keep-host-hash`;

  await page.route("**/__e2e_widget_host__.html*", async (route) => {
    const html = [
      "<!doctype html>",
      '<html lang="en">',
      "<head>",
      '  <meta charset="utf-8" />',
      "  <title>SQLFlow Widget E2E Host</title>",
      '  <link rel="stylesheet" href="/widget/sqlflow.widget.3.6.16.css" />',
      '  <script src="/widget/sqlflow.widget.3.6.16.js"></script>',
      "</head>",
      '<body style="margin:0;padding:16px;">',
      '  <div id="widget-host"></div>',
      "</body>",
      "</html>"
    ].join("\n");
    await route.fulfill({
      status: 200,
      contentType: "text/html; charset=utf-8",
      body: html
    });
  });

  await page.goto(hostUrl);
  await page.waitForFunction(
    () => {
      const w = window as unknown as HostWindowWithWidget;
      return Boolean(w.SQLFlow && typeof w.SQLFlow.init === "function");
    },
    undefined,
    { timeout: 15_000 }
  );
}

function widgetFrame(page: Page): FrameLocator {
  return page.frameLocator("#widget-host iframe");
}

test.describe("SQLFlow 3.6.16 Embeddable Widget SDK & 16 Official Demos E2E (00 §6.3 #6, 04 §11)", () => {
  test("1. Static widget bundle & stylesheet assets (`/widget/sqlflow.widget.3.6.16.js` & `.css`) are served with HTTP 200", async ({
    request
  }) => {
    const jsRes = await request.get("/widget/sqlflow.widget.3.6.16.js");
    expect(jsRes.status()).toBe(200);
    expect(jsRes.headers()["content-type"] || "").toMatch(/javascript/i);
    const jsBody = await jsRes.text();
    expect(jsBody.length).toBeGreaterThan(1_000);
    expect(jsBody).toContain("SQLFlow");
    expect(jsBody).toContain("3.6.16");

    const cssRes = await request.get("/widget/sqlflow.widget.3.6.16.css");
    expect(cssRes.status()).toBe(200);
    expect(cssRes.headers()["content-type"] || "").toMatch(/css/i);
    const cssBody = await cssRes.text();
    expect(cssBody.length).toBeGreaterThan(100);
  });

  test("2. `window.SQLFlow.init` mounts style-isolated `<iframe>`, preserves host hash, and runs live `vendor.set` + `sqltext.set` + `visualize()`", async ({
    page,
    request
  }) => {
    const api = new SqlFlowApiClient(request);
    const session = await api.bootstrapSession();
    const token = session.loginToken || session.secretKey;

    const pageErrors: Error[] = [];
    page.on("pageerror", (err) => pageErrors.push(err));

    await openBlankWidgetHostPage(page);

    const initMeta = await page.evaluate(
      async (opts: { userId: string; token: string }) => {
        const w = window as unknown as HostWindowWithWidget;
        const hostEl = document.getElementById("widget-host")!;
        const instance = await w.SQLFlow!.init({
          container: hostEl,
          width: 1080,
          height: 640,
          apiPrefix: "/api",
          userId: opts.userId,
          token: opts.token,
          component: {
            sqlEditor: true,
            graphLocate: true,
            minimap: true
          }
        });
        w.__e2eWidget = instance;

        instance.vendor.set("snowflake");
        instance.sqltext.set(
          [
            "INSERT INTO analytics.daily_revenue (rev_date, total_amount)",
            "SELECT DATE_TRUNC('DAY', o.order_ts) AS rev_date,",
            "       SUM(oi.qty * oi.unit_price) AS total_amount",
            "FROM raw.orders o",
            "JOIN raw.order_items oi ON o.order_id = oi.order_id",
            "GROUP BY 1;"
          ].join("\n")
        );
        await instance.visualize();

        return {
          sdkVersion: w.SQLFlow?.version,
          activeVendor: instance.vendor.get(),
          activeSqlLength: instance.sqltext.get().length,
          hostHash: window.location.hash,
          hasIframe: Boolean(hostEl.querySelector("iframe"))
        };
      },
      { userId: session.userId, token }
    );

    expect(initMeta.hasIframe).toBe(true);
    expect(initMeta.activeVendor).toBe("snowflake");
    expect(initMeta.activeSqlLength).toBeGreaterThan(40);
    // MemoryRouter inside widget must NEVER mutate host window's URL hash (`OBL-112`)
    expect(initMeta.hostHash).toBe("#keep-host-hash");

    const frame = widgetFrame(page);
    const iframeNodes = frame.locator(
      '[data-testid^="lineage-node-"], .sqlflow-node-card, .Graph__IyLTG'
    );
    await expect(iframeNodes.first()).toBeVisible({ timeout: 20_000 });
    expect(await iframeNodes.count()).toBeGreaterThanOrEqual(2);

    const iframeEdges = frame.locator("svg path.edge");
    await expect(iframeEdges.first()).toBeAttached();
    expect(await iframeEdges.count()).toBeGreaterThanOrEqual(1);

    expect(pageErrors).toEqual([]);
  });

  test("3. Zero-backend `visualizeJSON` / `visualizeJson`, `visualizeDependencyGraph` (v1.0), and `onFieldClick` + `onTableNameClick` callbacks", async ({
    page
  }) => {
    const pageErrors: Error[] = [];
    page.on("pageerror", (err) => pageErrors.push(err));

    await openBlankWidgetHostPage(page);

    const precomputedJson: SqlflowJSON = {
      mode: "global",
      sqlflow: {
        dbobjs: [
          {
            id: "10",
            name: "raw_events",
            type: "table",
            database: "DEFAULT",
            schema: "DEFAULT",
            columns: [
              { id: "11", name: "event_id", dataType: "BIGINT" },
              { id: "12", name: "payload_bytes", dataType: "INT" }
            ]
          },
          {
            id: "20",
            name: "agg_metrics",
            type: "view",
            database: "analytics_db",
            schema: "mart",
            columns: [
              { id: "21", name: "event_cnt", dataType: "BIGINT" },
              { id: "22", name: "total_bytes", dataType: "BIGINT" }
            ]
          }
        ],
        relationships: [
          {
            id: "100",
            type: "fdd",
            target: {
              id: "21",
              column: "event_cnt",
              parentId: "20",
              parentName: "agg_metrics"
            },
            sources: [
              {
                id: "11",
                column: "event_id",
                parentId: "10",
                parentName: "raw_events"
              }
            ]
          },
          {
            id: "101",
            type: "fdd",
            target: {
              id: "22",
              column: "total_bytes",
              parentId: "20",
              parentName: "agg_metrics"
            },
            sources: [
              {
                id: "12",
                column: "payload_bytes",
                parentId: "10",
                parentName: "raw_events"
              }
            ]
          }
        ]
      }
    };

    const depGraphV1: DependencyGraphV1 = {
      version: "1.0",
      kind: "dependency-graph",
      direction: "downstream",
      focus: { nodeId: "stg_orders" },
      nodes: [
        {
          id: "stg_orders",
          label: "stg.orders",
          table: "orders",
          schema: "stg",
          type: "table",
          columns: [
            { id: "stg_order_id", name: "order_id" },
            { id: "stg_net_amt", name: "net_amt" }
          ]
        },
        {
          id: "fct_revenue",
          label: "mart.fct_revenue",
          table: "fct_revenue",
          schema: "mart",
          type: "view",
          columns: [
            { id: "fct_order_id", name: "order_id" },
            { id: "fct_revenue_usd", name: "revenue_usd" }
          ]
        }
      ],
      edges: [
        {
          id: "edge_stg_to_fct",
          source: "stg_orders",
          target: "fct_revenue",
          columnMappings: [
            { sourceColumnId: "stg_order_id", targetColumnId: "fct_order_id" },
            { sourceColumnId: "stg_net_amt", targetColumnId: "fct_revenue_usd" }
          ]
        }
      ]
    };

    const aliasCheck = await page.evaluate(async (jsonPayload: SqlflowJSON) => {
      const w = window as unknown as HostWindowWithWidget;
      w.__e2eFieldClicks = [];
      w.__e2eTableClicks = [];

      const hostEl = document.getElementById("widget-host")!;
      const instance = await w.SQLFlow!.init({
        container: hostEl,
        width: 1000,
        height: 600,
        component: false
      });
      w.__e2eWidget = instance;

      instance.addEventListener("onFieldClick", (payload) => {
        w.__e2eFieldClicks!.push(payload as OnFieldClickPayload);
      });
      instance.addEventListener("onTableNameClick", (payload) => {
        w.__e2eTableClicks!.push(payload as OnTableNameClickPayload);
      });

      const hasUpperAlias = typeof instance.visualizeJSON === "function";
      const hasCamelAlias = typeof instance.visualizeJson === "function";

      if (instance.visualizeJson) {
        await instance.visualizeJson(jsonPayload, {
          layout: true,
          sqltext:
            "CREATE VIEW mart.agg_metrics AS SELECT COUNT(event_id) AS event_cnt, SUM(payload_bytes) AS total_bytes FROM raw_events;"
        });
      } else if (instance.visualizeJSON) {
        await instance.visualizeJSON(jsonPayload, { layout: true });
      }

      return { hasUpperAlias, hasCamelAlias };
    }, precomputedJson);

    expect(aliasCheck.hasUpperAlias || aliasCheck.hasCamelAlias).toBe(true);

    const frame = widgetFrame(page);
    const rawEventsHeader = frame
      .locator('[data-testid^="node-header-"], .Graph__IyLTG .name')
      .filter({ hasText: /raw_events/i })
      .first();
    await expect(rawEventsHeader).toBeVisible();

    // Click `raw_events` table header -> fires `onTableNameClick` with `"DEFAULT"` database/schema stripped (`04 §11.5`)
    await rawEventsHeader.click();

    const totalBytesRow = frame
      .locator('[data-testid^="column-row-"], .sqlflow-field-row, .Field__FlIAA')
      .filter({ hasText: /total_bytes/i })
      .first();
    await expect(totalBytesRow).toBeVisible();

    // Click `total_bytes` column row -> fires `onFieldClick` with upstream `payload_bytes` source metadata
    await totalBytesRow.click();

    const recordedEvents = await page.evaluate(() => {
      const w = window as unknown as HostWindowWithWidget;
      return {
        tableClicks: w.__e2eTableClicks ?? [],
        fieldClicks: w.__e2eFieldClicks ?? []
      };
    });

    expect(recordedEvents.tableClicks.length).toBeGreaterThanOrEqual(1);
    expect(recordedEvents.tableClicks[0]?.table).toMatch(/raw_events/i);
    // `"DEFAULT"` database/schema must be stripped from event payloads (`04 §11.5`)
    expect(recordedEvents.tableClicks[0]?.database).toBeUndefined();
    expect(recordedEvents.tableClicks[0]?.schema).toBeUndefined();

    expect(recordedEvents.fieldClicks.length).toBeGreaterThanOrEqual(1);
    const clickedField = recordedEvents.fieldClicks[0]!;
    expect(clickedField.column).toMatch(/total_bytes/i);
    expect(clickedField.database).toBe("analytics_db");
    expect(clickedField.schema).toBe("mart");
    expect(clickedField.source.columns.some((c) => /payload_bytes/i.test(c.column))).toBe(true);

    // Exercise `visualizeDependencyGraph` (`version: "1.0", kind: "dependency-graph"`)
    await page.evaluate(async (dg: DependencyGraphV1) => {
      const w = window as unknown as HostWindowWithWidget;
      await w.__e2eWidget!.visualizeDependencyGraph(dg, { layout: true });
    }, depGraphV1);

    await expect(
      frame
        .locator('[data-testid^="lineage-node-"], .sqlflow-node-card, .Graph__IyLTG')
        .filter({ hasText: /fct_revenue/i })
        .first()
    ).toBeVisible();
    await expect(
      frame
        .locator('[data-testid^="column-row-"], .sqlflow-field-row, .Field__FlIAA')
        .filter({ hasText: /revenue_usd/i })
        .first()
    ).toBeVisible();

    expect(pageErrors).toEqual([]);
  });

  for (let demoId = 1; demoId <= 16; demoId++) {
    test(`4.${demoId}. Official widget demo page \`/widget/${demoId}/index.html\` loads HTTP 200, initializes SQLFlow, and mounts widget canvas`, async ({
      page
    }) => {
      const pageErrors: Error[] = [];
      page.on("pageerror", (err) => pageErrors.push(err));

      const response = await page.goto(`/widget/${demoId}/index.html`, {
        waitUntil: "domcontentloaded"
      });
      expect(response).not.toBeNull();
      expect(response!.status()).toBe(200);

      // Every official demo page loads `sqlflow.widget.3.6.16.js` exposing `window.SQLFlow`
      await page.waitForFunction(
        () => {
          const w = window as unknown as HostWindowWithWidget;
          return Boolean(w.SQLFlow && typeof w.SQLFlow.init === "function");
        },
        undefined,
        { timeout: 15_000 }
      );

      // Verify either the sandboxed `<iframe>` or inline `.sqlflow-widget-root` mounts with non-zero geometry
      const mountedSurface = page.locator(
        "iframe.sqlflow-widget-iframe, iframe, .sqlflow-widget-root, [data-testid='sqlflow-widget-shell']"
      );
      await expect(mountedSurface.first()).toBeVisible({ timeout: 20_000 });

      const box = await mountedSurface.first().boundingBox();
      expect(box).not.toBeNull();
      expect(box!.width).toBeGreaterThan(50);
      expect(box!.height).toBeGreaterThan(50);

      // If mounted inside same-origin iframe, verify inner widget shell rendered cleanly
      const iframeCount = await page.locator("iframe").count();
      if (iframeCount > 0) {
        const demoFrame = page.frameLocator("iframe").first();
        await expect(
          demoFrame
            .locator(
              '[data-testid="sqlflow-widget-shell"], .sqlflow-widget-root, #App, [data-testid="sqlflow-lineage-canvas"]'
            )
            .first()
        ).toBeAttached({ timeout: 15_000 });
      }

      expect(pageErrors).toEqual([]);
    });
  }
});
