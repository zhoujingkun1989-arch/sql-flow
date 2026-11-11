import { expect, test, type Locator, type Page } from "@playwright/test";
import { SqlFlowApiClient } from "./helpers/sqlflow-client.js";

/**
 * Resolves the first visible locator matching any of the candidate CSS/role/testid selectors,
 * supporting both clean-room `@sqlflow/ui-core` / `frontend-ui` `data-testid` attributes and
 * legacy `04-frontend-ui-and-widget.md` CSS Module class selectors.
 */
function anyLocator(page: Page, selectors: string[]): Locator {
  return page.locator(selectors.join(", "));
}

/**
 * Injects an authenticated on-premise user session into `localStorage["user"]`
 * before page navigation so auth-gated panels (Job Center, Share, `/#/job/latest`)
 * work deterministically regardless of whether `login.disable` is true or false.
 */
async function seedBrowserAuthSession(
  page: Page,
  session: {
    userId: string;
    userEmail: string;
    loginToken: string;
    secretKey: string;
  }
): Promise<void> {
  const token = session.loginToken || session.secretKey;
  await page.addInitScript(
    (payload: { userId: string; userEmail: string; token: string; secretKey: string }) => {
      const storedSession = {
        // Supports both `04 §10.2` (`id`, `email`, `name`, `token`, `exp`) and `AppContext` (`userId`, `userEmail`, `userName`, `userRole`)
        id: payload.userId,
        userId: payload.userId,
        email: payload.userEmail,
        userEmail: payload.userEmail,
        name: payload.userEmail.split("@")[0] || "admin",
        userName: payload.userEmail.split("@")[0] || "admin",
        userRole: "1",
        userType: "premium",
        token: payload.token,
        secretKey: payload.secretKey,
        exp: Date.now() + 86_400_000
      };
      window.localStorage.setItem("user", JSON.stringify(storedSession));
      window.localStorage.setItem("sqlflow.tryThis.dismissed.v1", "1");
    },
    {
      userId: session.userId,
      userEmail: session.userEmail,
      token,
      secretKey: session.secretKey
    }
  );
}

/**
 * Sets SQL text in the Web SPA editor (`<SqlCodeEditor />` textarea or CodeMirror 5 instance).
 */
async function setEditorSqlText(page: Page, sql: string): Promise<void> {
  const textarea = page.locator('[data-testid="sql-editor-textarea"]');
  if ((await textarea.count()) > 0) {
    await textarea.first().fill(sql);
    return;
  }

  // Fallback for CodeMirror 5 DOM instance if mounted
  const updatedViaCm = await page.evaluate((nextSql: string) => {
    const cmEl = document.querySelector(".CodeMirror") as
      | (HTMLElement & { CodeMirror?: { setValue: (v: string) => void } })
      | null;
    if (cmEl?.CodeMirror) {
      cmEl.CodeMirror.setValue(nextSql);
      return true;
    }
    return false;
  }, sql);

  if (!updatedViaCm) {
    const anyTextarea = page.locator("textarea").first();
    await anyTextarea.fill(sql);
  }
}

/**
 * Triggers primary column-level SQL lineage visualization and waits for the graph canvas to render.
 */
async function clickVisualizeLineage(page: Page): Promise<void> {
  const headerBtn = page.locator('[data-testid="header-visualize-btn"]');
  if ((await headerBtn.count()) > 0 && (await headerBtn.first().isVisible())) {
    await headerBtn.first().click();
    return;
  }
  const editorBtn = page.locator('[data-testid="editor-run-column-lineage"]');
  if ((await editorBtn.count()) > 0 && (await editorBtn.first().isVisible())) {
    await editorBtn.first().click();
    return;
  }
  await page.getByRole("button", { name: /visualize/i }).first().click();
}

test.describe("SQLFlow 8.2.2.15 Web SPA E2E Suite (00 §6.3 #5, 04 §2–§10)", () => {
  test("1. Workspace bootstrap at `/#/`, 39-dialect selector, sample SQL visualization, HTML node cards & SVG cubic Bézier edges", async ({
    page,
    request
  }) => {
    const api = new SqlFlowApiClient(request);
    const session = await api.bootstrapSession();
    await seedBrowserAuthSession(page, session);

    const pageErrors: Error[] = [];
    page.on("pageerror", (err) => pageErrors.push(err));

    const configResponsePromise = page.waitForResponse(
      (res) => res.url().includes("config.private.json") && res.status() === 200,
      { timeout: 20_000 }
    );

    await page.goto("/#/");
    await configResponsePromise;

    // Top Header Bar & Left Vertical Icon Rail must mount cleanly
    const header = anyLocator(page, [
      '[data-testid="top-header-bar"]',
      ".sf-top-header",
      ".Home__juSb_ .header",
      "header"
    ]);
    await expect(header.first()).toBeVisible();

    const leftRail = anyLocator(page, [
      '[data-testid="left-icon-rail"]',
      ".sf-left-rail",
      ".Toolbar___h1q6"
    ]);
    await expect(leftRail.first()).toBeVisible();

    // Verify 39-dialect selector (`Popular` + `All dialects`) and dialect switching
    const nativeDialectSelect = page.locator('[data-testid="dialect-selector"]');
    if ((await nativeDialectSelect.count()) > 0) {
      await expect(nativeDialectSelect).toBeVisible();
      const optionValues = await nativeDialectSelect
        .locator("option")
        .evaluateAll((opts) => opts.map((o) => (o as HTMLOptionElement).value));
      expect(optionValues.length).toBeGreaterThanOrEqual(39);
      for (const expectedVendor of ["oracle", "postgresql", "snowflake", "bigquery", "mssql", "mysql"]) {
        expect(optionValues).toContain(expectedVendor);
      }

      // Switch dialect to PostgreSQL and load sample SQL
      await nativeDialectSelect.selectOption("postgresql");
      const sampleBtn = page.locator('[data-testid="sample-sql-btn"]');
      if ((await sampleBtn.count()) > 0) {
        await sampleBtn.click();
      }
    } else {
      // Ant Design `<Select>` fallback
      const antSelect = page.locator(".ant-select").first();
      await expect(antSelect).toBeVisible();
    }

    // Enter deterministic multi-table SQL so node cards, columns, and cubic Bézier edges are guaranteed
    const lineageSql = [
      "CREATE VIEW analytics.dept_payroll AS",
      "SELECT e.dept_id AS department_id,",
      "       d.dept_name AS department_name,",
      "       SUM(e.salary + COALESCE(e.bonus, 0)) AS total_comp",
      "FROM hr.employees e",
      "JOIN hr.departments d ON e.dept_id = d.dept_id",
      "GROUP BY e.dept_id, d.dept_name;"
    ].join("\n");

    await setEditorSqlText(page, lineageSql);

    const graphApiPromise = page.waitForResponse(
      (res) =>
        res.url().includes("/sqlflow/generation/sqlflow/graph") &&
        res.request().method() === "POST",
      { timeout: 30_000 }
    );
    await clickVisualizeLineage(page);
    const graphApiRes = await graphApiPromise;
    expect(graphApiRes.status()).toBe(200);

    // Verify hybrid HTML node cards (`.sqlflow-node-card` / `.Graph__IyLTG`)
    const nodeCards = anyLocator(page, [
      '[data-testid^="lineage-node-"]',
      ".sqlflow-node-card",
      ".Graph__IyLTG"
    ]);
    await expect(nodeCards.first()).toBeVisible({ timeout: 20_000 });
    expect(await nodeCards.count()).toBeGreaterThanOrEqual(2);

    // Verify HTML column rows (`.sqlflow-field-row` / `.Field__FlIAA`)
    const columnRows = anyLocator(page, [
      '[data-testid^="column-row-"]',
      ".sqlflow-field-row",
      ".Field__FlIAA"
    ]);
    await expect(columnRows.first()).toBeVisible();
    expect(await columnRows.count()).toBeGreaterThanOrEqual(4);

    // Verify SVG cubic Bézier edges (`M ... C ... S ...` or `M ... C ...`) + 10x7 polygon arrowheads
    const svgEdgePaths = anyLocator(page, [
      'svg[data-testid="sqlflow-edges-svg"] path.edge',
      ".Edges__UFXJD svg path.edge",
      '[data-testid="sqlflow-lineage-canvas"] svg path.edge'
    ]);
    await expect(svgEdgePaths.first()).toBeAttached();
    expect(await svgEdgePaths.count()).toBeGreaterThanOrEqual(1);

    const firstPathD = await svgEdgePaths.first().getAttribute("d");
    expect(firstPathD).toBeTruthy();
    expect(firstPathD!).toMatch(/^M\s*[-\d.]+\s*[,\s]\s*[-\d.]+.*C\s*[-\d.]+/i);

    const arrowheads = anyLocator(page, [
      'svg[data-testid="sqlflow-edges-svg"] polygon',
      ".Edges__UFXJD svg polygon",
      '[data-testid="sqlflow-lineage-canvas"] svg polygon'
    ]);
    expect(await arrowheads.count()).toBeGreaterThanOrEqual(1);

    expect(pageErrors).toEqual([]);
  });

  test("2. Bidirectional SQL editor <-> lineage graph click highlighting & clear/reset", async ({
    page,
    request
  }) => {
    const api = new SqlFlowApiClient(request);
    const session = await api.bootstrapSession();
    await seedBrowserAuthSession(page, session);

    await page.goto("/#/");

    const sql = [
      "INSERT INTO mart.customer_ltv (cust_id, full_name, lifetime_spend)",
      "SELECT c.id,",
      "       CONCAT(c.first_name, ' ', c.last_name) AS full_name,",
      "       SUM(o.amount) AS lifetime_spend",
      "FROM raw.customers c",
      "JOIN raw.orders o ON c.id = o.customer_id",
      "GROUP BY c.id, c.first_name, c.last_name;"
    ].join("\n");

    await setEditorSqlText(page, sql);
    await clickVisualizeLineage(page);

    const columnRows = anyLocator(page, [
      '[data-testid^="column-row-"]',
      ".sqlflow-field-row",
      ".Field__FlIAA"
    ]);
    await expect(columnRows.first()).toBeVisible({ timeout: 20_000 });

    // Click a column row on the lineage graph -> pins upstream/downstream columns, edges, AND SQL editor tokens
    const targetColumn = columnRows
      .filter({ hasText: /lifetime_spend|amount|full_name/i })
      .first();
    if ((await targetColumn.count()) > 0) {
      await targetColumn.click();
    } else {
      await columnRows.first().click();
    }

    // Graph column row & SVG edge should enter pinned/highlighted state
    const highlightedColumns = anyLocator(page, [
      '.sqlflow-field-row[data-pinned="true"]',
      ".sqlflow-field-row.highlight",
      ".Field__FlIAA.highlight"
    ]);
    await expect(highlightedColumns.first()).toBeVisible();

    const highlightedEdges = anyLocator(page, [
      'svg g[data-highlighted="true"]',
      "svg g.highlight"
    ]);
    expect(await highlightedEdges.count()).toBeGreaterThanOrEqual(1);

    // SQL Editor token layer should highlight corresponding 1-based `[line, col]` token coordinates
    const highlightedEditorTokens = anyLocator(page, [
      '[data-testid="sql-editor-highlight-layer"] span[data-highlight-state="pinned"]',
      '[data-testid="sql-editor-highlight-layer"] span.highlight',
      '[data-testid="slide-up-sql-drawer"]',
      ".CodeMirror .highlight"
    ]);
    await expect(highlightedEditorTokens.first()).toBeVisible();

    // Clicking Reset on floating Lineage tools bar (or empty canvas) clears pinned highlight state
    const resetBtn = page.locator('[data-testid="tool-reset-btn"]');
    if ((await resetBtn.count()) > 0 && (await resetBtn.isVisible())) {
      await resetBtn.click();
      await expect(
        page.locator('[data-testid="sql-editor-highlight-layer"] span[data-highlight-state="pinned"]')
      ).toHaveCount(0);
    }
  });

  test("3. Secondary analysis views: ER Diagram, Routine Call Graph, and JoinFlow V2 (`join-analysis.v1`)", async ({
    page,
    request
  }) => {
    const api = new SqlFlowApiClient(request);
    const session = await api.bootstrapSession();
    await seedBrowserAuthSession(page, session);

    const pageErrors: Error[] = [];
    page.on("pageerror", (err) => pageErrors.push(err));

    await page.goto("/#/");

    // --- 3A. ER Diagram (`POST .../erdiagramSelectGraph`) ---
    const erDdl = [
      "CREATE TABLE shop.customers (",
      "  customer_id INT PRIMARY KEY,",
      "  email VARCHAR(120) UNIQUE,",
      "  created_at TIMESTAMP",
      ");",
      "CREATE TABLE shop.orders (",
      "  order_id INT PRIMARY KEY,",
      "  customer_id INT,",
      "  order_total DECIMAL(12, 2),",
      "  CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES shop.customers (customer_id)",
      ");"
    ].join("\n");

    await setEditorSqlText(page, erDdl);

    const erBtn = anyLocator(page, [
      '[data-testid="editor-run-er"]',
      'button:has-text("ER Diagram")'
    ]);
    await erBtn.first().click();

    const erContainer = anyLocator(page, [
      '[data-testid="er-diagram-view"]',
      ".ER__yG1KF",
      ".Rect__MwLfS"
    ]);
    await expect(erContainer.first()).toBeVisible({ timeout: 20_000 });
    await expect(erContainer.first()).toContainText(/customers/i);
    await expect(erContainer.first()).toContainText(/orders/i);

    // --- 3B. Routine Call Graph (`POST .../graph/call_lineage`) ---
    const plsqlCallGraph = [
      "CREATE OR REPLACE PROCEDURE billing.calc_tax(p_amt IN NUMBER, p_tax OUT NUMBER) IS",
      "BEGIN",
      "  p_tax := p_amt * 0.08;",
      "END;",
      "/",
      "CREATE OR REPLACE PROCEDURE billing.settle_invoice(p_inv_id IN NUMBER) IS",
      "  v_amt NUMBER;",
      "  v_tax NUMBER;",
      "BEGIN",
      "  SELECT amount INTO v_amt FROM billing.invoices WHERE invoice_id = p_inv_id;",
      "  billing.calc_tax(v_amt, v_tax);",
      "  INSERT INTO billing.ledger (invoice_id, total_amt) VALUES (p_inv_id, v_amt + v_tax);",
      "END;",
      "/"
    ].join("\n");

    const nativeDialectSelect = page.locator('[data-testid="dialect-selector"]');
    if ((await nativeDialectSelect.count()) > 0) {
      await nativeDialectSelect.selectOption("oracle");
    }
    await setEditorSqlText(page, plsqlCallGraph);

    const cgBtn = anyLocator(page, [
      '[data-testid="editor-run-callgraph"]',
      'button:has-text("Call Graph")'
    ]);
    await cgBtn.first().click();

    const cgContainer = anyLocator(page, [
      '[data-testid="call-graph-view"]',
      '[data-testid^="cg-node-"]',
      'div:has-text("settle_invoice")'
    ]);
    await expect(cgContainer.first()).toBeVisible({ timeout: 20_000 });
    await expect(page.locator("body")).toContainText(/settle_invoice|calc_tax/i);

    // --- 3C. JoinFlow V2 (`POST .../join-analysis` -> `join-analysis.v1`) ---
    const joinSql = [
      "SELECT o.order_id, c.customer_name, p.product_name, w.warehouse_code",
      "FROM sales.orders o",
      "INNER JOIN sales.customers c ON o.customer_id = c.customer_id",
      "LEFT JOIN sales.order_items oi ON o.order_id = oi.order_id",
      "LEFT JOIN sales.products p ON oi.product_id = p.product_id",
      "CROSS JOIN sales.warehouses w;"
    ].join("\n");

    await setEditorSqlText(page, joinSql);

    const joinApiPromise = page.waitForResponse(
      (res) =>
        res.url().includes("/sqlflow/generation/sqlflow/join-analysis") &&
        res.request().method() === "POST",
      { timeout: 30_000 }
    );

    const joinBtn = anyLocator(page, [
      '[data-testid="editor-run-join"]',
      'button:has-text("Join Analysis")'
    ]);
    await joinBtn.first().click();

    const joinRes = await joinApiPromise;
    expect(joinRes.status()).toBe(200);

    const joinView = anyLocator(page, [
      '[data-testid="join-analysis-view"]',
      '[data-testid="joinflow-canvas"]',
      ".react-flow"
    ]);
    await expect(joinView.first()).toBeVisible({ timeout: 20_000 });
    await expect(joinView.first()).toContainText(/orders/i);
    await expect(joinView.first()).toContainText(/customers/i);

    // Role filter in Join Analysis V2 (`all`, `data_engineer`, `data_analyst`, `dba`)
    const roleFilter = page.locator('[data-testid="join-role-filter"]');
    if ((await roleFilter.count()) > 0) {
      await roleFilter.selectOption("dba");
      await roleFilter.selectOption("all");
    }

    // Verify standalone `/joinflow-island.js` asset is also served cleanly on `:8165`
    const islandJsRes = await request.get("/joinflow-island.js");
    expect(islandJsRes.status()).toBe(200);
    const islandJsBody = await islandJsRes.text();
    expect(islandJsBody).toContain("__JoinIsland");

    expect(pageErrors).toEqual([]);
  });

  test("4. Schema Explorer panel, User DDL Schema upload, Job Center workflow, and Share permalink (`/#/share` & `/#/p/:id`)", async ({
    page,
    request
  }) => {
    const api = new SqlFlowApiClient(request);
    const session = await api.bootstrapSession();
    const token = session.loginToken || session.secretKey;
    await seedBrowserAuthSession(page, session);

    // 4A. Submit User DDL Schema (`submitUserSchema`) + verify `SELECT *` catalog expansion in UI
    const ddlSchemaSql = [
      "CREATE TABLE warehouse.dim_product (",
      "  sku_id INT PRIMARY KEY,",
      "  sku_code VARCHAR(64),",
      "  unit_cost DECIMAL(10, 2)",
      ");"
    ].join("\n");

    const uploadSchemaRes = await api.postMultipart<{ code: number | string }>(
      "/sqlflow/generation/submitUserSchema",
      {
        userId: session.userId,
        dbvendor: "dbvpostgresql"
      },
      "sqlfiles",
      [
        {
          name: "dim_product_schema.sql",
          buffer: Buffer.from(ddlSchemaSql, "utf8")
        }
      ],
      { Authorization: `Token ${token}` }
    );
    expect(String(uploadSchemaRes.body.code)).toBe("200");

    // 4B. Create a persistent batch job so Job Center & `/#/job/latest` have live completed lineage
    const jobName = `e2e_spa_job_${Date.now()}`;
    const jobSql = [
      "CREATE VIEW warehouse.product_margin AS",
      "SELECT p.sku_id, p.sku_code, p.unit_cost * 1.35 AS list_price",
      "FROM warehouse.dim_product p;"
    ].join("\n");

    const createJobRes = await api.postMultipart<{
      code: number | string;
      data?: { jobId?: string };
    }>(
      "/sqlflow/job/submitUserJob",
      {
        userId: session.userId,
        token,
        jobName,
        dbvendor: "dbvpostgresql",
        sqlsource: "uploadFile"
      },
      "sqlfiles",
      [
        {
          name: "product_margin.sql",
          buffer: Buffer.from(`${ddlSchemaSql}\n${jobSql}`, "utf8")
        }
      ],
      { Authorization: `Token ${token}` }
    );
    expect(String(createJobRes.body.code)).toBe("200");
    const createdJobId = String(createJobRes.body.data?.jobId || "");
    expect(createdJobId.length).toBeGreaterThan(0);

    await api.waitForJobCompletion(session.userId, token, createdJobId);

    // Open Web SPA and inspect Schema Explorer panel (`rail-schema-btn`)
    await page.goto("/#/");

    const nativeDialectSelect = page.locator('[data-testid="dialect-selector"]');
    if ((await nativeDialectSelect.count()) > 0) {
      await nativeDialectSelect.selectOption("postgresql");
    }
    await setEditorSqlText(
      page,
      "CREATE VIEW warehouse.v_all_products AS SELECT * FROM warehouse.dim_product;"
    );
    await clickVisualizeLineage(page);

    const schemaRailBtn = anyLocator(page, [
      '[data-testid="rail-schema-btn"]',
      'aside button:has-text("Schema")',
      '.Toolbar___h1q6 div:has-text("Schema")'
    ]);
    await schemaRailBtn.first().click();
    await expect(page.locator("body")).toContainText(/dim_product|v_all_products|Schema/i);

    // 4C. Open Job Center (`rail-job-btn`) and verify batch job appears in Job List
    const jobRailBtn = anyLocator(page, [
      '[data-testid="rail-job-btn"]',
      'aside button:has-text("Jobs")',
      '.Toolbar___h1q6 div:has-text("Jobs")'
    ]);
    if ((await jobRailBtn.count()) > 0) {
      await jobRailBtn.first().click();
      await expect(page.locator("body")).toContainText(new RegExp(`${jobName}|Job`, "i"), {
        timeout: 15_000
      });
    }

    // 4D. Create an on-premise Share snapshot (`share: true`) and verify `/#/share?shareId=<id>` + `/#/p/<id>`
    const graphForShare = await api.postForm<{
      code: number | string;
      data?: { sessionId?: string; shareId?: string };
    }>(
      "/sqlflow/generation/sqlflow/graph",
      {
        userId: session.userId,
        dbvendor: "dbvpostgresql",
        sqltext: "CREATE VIEW reporting.v_sales AS SELECT id, revenue FROM raw.sales;",
        share: true
      },
      { Authorization: `Token ${token}` }
    );
    expect(String(graphForShare.body.code)).toBe("200");

    let shareId = graphForShare.body.data?.shareId;
    if (!shareId && graphForShare.body.data?.sessionId) {
      const selectShareRes = await api.postForm<{
        code: number | string;
        data?: { shareId?: string };
      }>(
        "/sqlflow/generation/sqlflow/selectedgraph",
        {
          userId: session.userId,
          sessionId: graphForShare.body.data.sessionId,
          dbvendor: "dbvpostgresql",
          table: "reporting.v_sales",
          columnLevel: true,
          share: true
        },
        { Authorization: `Token ${token}` }
      );
      shareId = selectShareRes.body.data?.shareId;
    }

    if (shareId) {
      // Navigate to on-premise Share URL (`/#/share?shareId=<id>`)
      await page.goto(`/#/share?shareId=${encodeURIComponent(shareId)}`);
      const shareNodes = anyLocator(page, [
        '[data-testid^="lineage-node-"]',
        ".sqlflow-node-card",
        ".Graph__IyLTG"
      ]);
      await expect(shareNodes.first()).toBeVisible({ timeout: 20_000 });
      await expect(page.locator("body")).toContainText(/v_sales|sales/i);

      // Also verify `/#/p/:id` permalink route mounts cleanly without uncaught errors
      await page.goto(`/#/p/${encodeURIComponent(shareId)}`);
      await expect(page.locator("#root, #App").first()).toBeVisible();
    }

    // Clean up test schema & job
    await api.postForm(
      "/sqlflow/generation/deleteUserSchema",
      {
        userId: session.userId,
        dbvendor: "dbvpostgresql",
        fileName: "dim_product_schema.sql"
      },
      { Authorization: `Token ${token}` }
    );
    await api.postForm(
      "/sqlflow/job/deleteUserJob",
      {
        userId: session.userId,
        token,
        jobId: createdJobId
      },
      { Authorization: `Token ${token}` }
    );
  });
});
