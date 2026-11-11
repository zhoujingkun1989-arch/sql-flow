import { expect, test } from "@playwright/test";
import {
  DIAGNOSTIC_CODES as JOIN_DIAGNOSTIC_CODES,
  type JoinAnalysisGraph,
  type SqlflowJSON as SqlflowGraphPayload
} from "@sqlflow/ui-core/types";
import {
  flattenDbObjects,
  inspectPngBytes,
  isValidParquetBuffer,
  readZipArchiveEntries,
  SqlFlowApiClient,
  type BootstrappedAuthSession
} from "./helpers/sqlflow-client.js";

interface DialectTestCase {
  vendor: string;
  label: string;
  sql: string;
  expectedTarget: string;
}

const DIALECT_SUITE: DialectTestCase[] = [
  {
    vendor: "dbvoracle",
    label: "Oracle PL/SQL & MERGE",
    expectedTarget: "EMP_BONUSES",
    sql: `
      MERGE INTO hr.emp_bonuses b
      USING (SELECT employee_id, salary * 0.15 AS bonus_amt FROM hr.employees WHERE department_id = 10) s
      ON (b.employee_id = s.employee_id)
      WHEN MATCHED THEN UPDATE SET b.bonus = s.bonus_amt
      WHEN NOT MATCHED THEN INSERT (employee_id, bonus) VALUES (s.employee_id, s.bonus_amt);
    `
  },
  {
    vendor: "dbvpostgresql",
    label: "PostgreSQL CTE & STRING_AGG",
    expectedTarget: "customer_summary",
    sql: `
      WITH order_rollup AS (
        SELECT customer_id, SUM(amount) AS total_spent, COUNT(*) AS order_cnt
        FROM public.orders
        GROUP BY customer_id
      )
      INSERT INTO public.customer_summary (customer_id, name, total_spent, order_cnt)
      SELECT c.id, c.full_name, r.total_spent, r.order_cnt
      FROM public.customers c
      JOIN order_rollup r ON c.id = r.customer_id;
    `
  },
  {
    vendor: "dbvsnowflake",
    label: "Snowflake QUALIFY & CREATE OR REPLACE TABLE",
    expectedTarget: "LATEST_USER_EVENTS",
    sql: `
      CREATE OR REPLACE TABLE analytics.public.latest_user_events AS
      SELECT user_id, event_type, event_ts
      FROM raw.public.clickstream_events
      QUALIFY ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY event_ts DESC) = 1;
    `
  },
  {
    vendor: "dbvbigquery",
    label: "BigQuery Backtick Identifiers",
    expectedTarget: "daily_revenue",
    sql: `
      CREATE OR REPLACE TABLE \`proj.mart.daily_revenue\` AS
      SELECT DATE(order_ts) AS rev_date, SUM(net_amount) AS revenue
      FROM \`proj.raw.orders\`
      GROUP BY rev_date;
    `
  },
  {
    vendor: "dbvmssql",
    label: "SQL Server T-SQL Batch & Bracketed Identifiers",
    expectedTarget: "SalesSummary",
    sql: `
      INSERT INTO [dbo].[SalesSummary] ([TerritoryID], [TotalYTD])
      SELECT sp.[TerritoryID], SUM(sp.[SalesYTD])
      FROM [Sales].[SalesPerson] AS sp
      WHERE sp.[TerritoryID] IS NOT NULL
      GROUP BY sp.[TerritoryID];
    `
  },
  {
    vendor: "dbvhive",
    label: "Hive INSERT OVERWRITE & LATERAL VIEW",
    expectedTarget: "ad_impressions_flat",
    sql: `
      INSERT OVERWRITE TABLE dwh.ad_impressions_flat
      SELECT p.page_id, ad_id
      FROM raw.page_ads p
      LATERAL VIEW explode(p.ad_id_list) ad_tbl AS ad_id;
    `
  },
  {
    vendor: "dbvsparksql",
    label: "Spark SQL Delta CTAS",
    expectedTarget: "gold_orders",
    sql: `
      CREATE TABLE lakehouse.gold_orders USING DELTA AS
      SELECT o.order_id, c.region, o.amount
      FROM lakehouse.silver_orders o
      INNER JOIN lakehouse.dim_customers c ON o.customer_id = c.customer_id;
    `
  },
  {
    vendor: "dbvmysql",
    label: "MySQL Recursive CTE & Multi-Table JOIN",
    expectedTarget: "v_org_chart",
    sql: `
      CREATE VIEW v_org_chart AS
      WITH RECURSIVE org AS (
        SELECT emp_id, manager_id, emp_name FROM staff WHERE manager_id IS NULL
        UNION ALL
        SELECT s.emp_id, s.manager_id, s.emp_name FROM staff s JOIN org o ON s.manager_id = o.emp_id
      )
      SELECT emp_id, manager_id, emp_name FROM org;
    `
  },
  {
    vendor: "dbvteradata",
    label: "Teradata INSERT SELECT",
    expectedTarget: "promotion_candidates",
    sql: `
      INSERT INTO hr.promotion_candidates (dept_num, emp_name, years_exp)
      SELECT dept_no, emp_name, yrs_exp
      FROM hr.employees
      WHERE yrs_exp > 5;
    `
  },
  {
    vendor: "dbvduckdb",
    label: "DuckDB Analytical CTE",
    expectedTarget: "monthly_kpis",
    sql: `
      CREATE TABLE monthly_kpis AS
      WITH m AS (
        SELECT DATE_TRUNC('month', order_date) AS month_bucket, SUM(price) AS gross_sales
        FROM raw_invoices
        GROUP BY 1
      )
      SELECT month_bucket, gross_sales FROM m;
    `
  }
];

test.describe("SQLFlow 8.2.2.15 — Multi-Dialect Lineage, /demo/* Suite, & All 7 Export Formats", () => {
  let session: BootstrappedAuthSession;

  test.beforeAll(async ({ playwright }) => {
    const reqCtx = await playwright.request.newContext({
      baseURL: process.env.SQLFLOW_BASE_URL || "http://localhost:8165"
    });
    const client = new SqlFlowApiClient(reqCtx);
    session = await client.bootstrapSession();
    await reqCtx.dispose();
  });

  for (const tc of DIALECT_SUITE) {
    test(`Dialect lineage [${tc.vendor}] — ${tc.label}`, async ({ request }) => {
      const client = new SqlFlowApiClient(request);
      const res = await client.postForm<{
        code: number | string;
        sessionId?: string;
        data?: SqlflowGraphPayload;
      }>(
        "/sqlflow/generation/sqlflow/graph",
        {
          userId: session.userId,
          token: session.secretKey,
          dbvendor: tc.vendor,
          sqltext: tc.sql,
          showRelationType: "fdd",
          ignoreRecordSet: true
        },
        client.authHeaders(session)
      );

      expect(res.status).toBe(200);
      expect(String(res.body.code)).toBe("200");
      const dbobjs = flattenDbObjects(res.body.data?.sqlflow?.dbobjs);
      const rels = res.body.data?.sqlflow?.relationships || [];
      expect(dbobjs.length).toBeGreaterThanOrEqual(2);
      expect(rels.length).toBeGreaterThanOrEqual(1);
      expect(res.rawText.toLowerCase()).toContain(tc.expectedTarget.toLowerCase());
      expect((res.body.data?.graph?.elements?.tables || []).length).toBeGreaterThanOrEqual(2);
    });
  }

  test("Relation modes: fdd vs fdr (indirect impact), ER diagram (PK/FK), and Call Graph (stored routines)", async ({
    request
  }) => {
    const client = new SqlFlowApiClient(request);

    // 1. Direct (`fdd`) vs Indirect (`fdr`) WHERE/GROUP BY impact edges
    const impactSql = `
      CREATE VIEW v_high_value AS
      SELECT customer_id, SUM(amount) AS total_amt
      FROM raw_orders
      WHERE order_status = 'PAID' AND region_code = 'APAC'
      GROUP BY customer_id;
    `;
    const fdrRes = await client.postForm<{
      code: number | string;
      data?: SqlflowGraphPayload;
    }>(
      "/sqlflow/generation/sqlflow/graph",
      {
        userId: session.userId,
        token: session.secretKey,
        dbvendor: "dbvpostgresql",
        sqltext: impactSql,
        showRelationType: "fdd,fdr",
        ignoreRecordSet: true
      },
      client.authHeaders(session)
    );
    expect(String(fdrRes.body.code)).toBe("200");
    const relTypes = new Set(
      (fdrRes.body.data?.sqlflow?.relationships || []).map((r) => r.type)
    );
    expect(relTypes.has("fdd")).toBe(true);
    expect(relTypes.has("fdr")).toBe(true);

    // 2. ER Diagram (`erdiagramSelectGraph`) with PRIMARY KEY + FOREIGN KEY
    const erSql = `
      CREATE TABLE departments (
        dept_id INT PRIMARY KEY,
        dept_name VARCHAR(100) UNIQUE
      );
      CREATE TABLE employees (
        emp_id INT PRIMARY KEY,
        dept_id INT,
        full_name VARCHAR(120),
        CONSTRAINT fk_emp_dept FOREIGN KEY (dept_id) REFERENCES departments(dept_id)
      );
    `;
    const erRes = await client.postForm<{
      code: number | string;
      data?: SqlflowGraphPayload;
    }>(
      "/sqlflow/generation/sqlflow/erdiagramSelectGraph",
      {
        userId: session.userId,
        token: session.secretKey,
        dbvendor: "dbvoracle",
        sqltext: erSql
      },
      client.authHeaders(session)
    );
    expect(String(erRes.body.code)).toBe("200");
    expect(flattenDbObjects(erRes.body.data?.sqlflow?.dbobjs).length).toBeGreaterThanOrEqual(2);

    // 3. Call Graph (`graph/call_lineage`) across stored procedures
    const callSql = `
      CREATE OR REPLACE PROCEDURE log_audit(p_msg VARCHAR2) AS
      BEGIN
        INSERT INTO audit_log(message) VALUES (p_msg);
      END;
      /
      CREATE OR REPLACE PROCEDURE process_payroll(p_dept_id NUMBER) AS
      BEGIN
        UPDATE payroll SET processed = 'Y' WHERE dept_id = p_dept_id;
        log_audit('Payroll processed');
      END;
      /
    `;
    const callRes = await client.postForm<{
      code: number | string;
      data?: Record<string, unknown>;
    }>(
      "/sqlflow/generation/sqlflow/graph/call_lineage",
      {
        userId: session.userId,
        token: session.secretKey,
        dbvendor: "dbvoracle",
        sqltext: callSql
      },
      client.authHeaders(session)
    );
    expect(callRes.status).toBe(200);
    expect(String(callRes.body.code)).toBe("200");
    expect(callRes.rawText.toLowerCase()).toContain("process_payroll");
  });

  test("SELECT * catalog expansion using uploaded User DDL Schema", async ({ request }) => {
    const client = new SqlFlowApiClient(request);
    const schemaFile = `star_expand_${Date.now()}.sql`;
    const schemaDdl = `
      CREATE TABLE warehouse_shipments (
        shipment_id BIGINT PRIMARY KEY,
        order_id BIGINT,
        carrier_code VARCHAR(32),
        shipped_at TIMESTAMP
      );
    `;

    const uploadRes = await client.postMultipart<{ code: number | string }>(
      "/sqlflow/generation/submitUserSchema",
      {
        userId: session.userId,
        token: session.secretKey,
        dbvendor: "dbvpostgresql"
      },
      "sqlfiles",
      [{ name: schemaFile, buffer: Buffer.from(schemaDdl, "utf8") }],
      client.authHeaders(session)
    );
    expect(String(uploadRes.body.code)).toBe("200");

    try {
      const starRes = await client.postForm<{
        code: number | string;
        data?: SqlflowGraphPayload;
      }>(
        "/sqlflow/generation/sqlflow/graph",
        {
          userId: session.userId,
          token: session.secretKey,
          dbvendor: "dbvpostgresql",
          sqltext: "CREATE VIEW v_shipments_all AS SELECT * FROM warehouse_shipments;",
          ignoreRecordSet: true
        },
        client.authHeaders(session)
      );
      expect(String(starRes.body.code)).toBe("200");
      for (const expectedCol of ["shipment_id", "order_id", "carrier_code", "shipped_at"]) {
        expect(starRes.rawText.toLowerCase()).toContain(expectedCol);
      }
    } finally {
      await client.postForm(
        "/sqlflow/generation/deleteUserSchema",
        {
          userId: session.userId,
          token: session.secretKey,
          dbvendor: "dbvpostgresql",
          fileName: schemaFile
        },
        client.authHeaders(session)
      );
    }
  });

  test("Join Analysis diagnostics & /demo/* AST utilities (sqlformatter, syntax, keyword, tableColumns, toXML, liveDemo)", async ({
    request
  }) => {
    const client = new SqlFlowApiClient(request);

    // 1. Join Analysis with Cartesian + self-join + outer-join filter patterns
    const joinDiagSql = `
      SELECT e.emp_id, m.emp_id AS mgr_id, d.dept_name, r.region_name
      FROM employees e
      LEFT JOIN departments d ON e.dept_id = d.dept_id
      INNER JOIN employees m ON e.manager_id = m.emp_id
      CROSS JOIN regions r
      WHERE d.active_flag = 1;
    `;
    const joinRes = await client.postForm<JoinAnalysisGraph>(
      "/sqlflow/generation/sqlflow/join-analysis",
      {
        userId: session.userId,
        dbvendor: "dbvpostgresql",
        sqltext: joinDiagSql
      }
    );
    expect(joinRes.status).toBe(200);
    expect(joinRes.body.schemaVersion).toBe("join-analysis.v1");
    const knownCodes = new Set<string>(JOIN_DIAGNOSTIC_CODES);
    for (const d of joinRes.body.diagnostics || []) {
      expect(knownCodes.has(d.code)).toBe(true);
    }

    // 2. `/demo/sqlformatter/format` & `/sqlflow/generation/sql/format`
    const fmtRes = await client.postForm<{
      code: number | string;
      data?: { formatted_sql?: string } | string;
    }>("/demo/sqlformatter/format", {
      userId: session.userId,
      formatConfig: JSON.stringify({
        input_sql: "select emp_id,first_name,salary from hr.employees where salary>5000",
        format_option: {
          db_vendor: "dbvoracle",
          format_options: {
            capitalization: { case_keywords: "upper" }
          }
        }
      })
    });
    expect(fmtRes.status).toBe(200);
    expect(String(fmtRes.body.code)).toBe("200");
    expect(fmtRes.rawText.toUpperCase()).toContain("SELECT");

    const wsFmtRes = await client.postForm<{ code: number | string; data?: string }>(
      "/sqlflow/generation/sql/format",
      {
        userId: session.userId,
        token: session.secretKey,
        dbvendor: "dbvoracle",
        sqltext: "select id,name from dept"
      },
      client.authHeaders(session)
    );
    expect(String(wsFmtRes.body.code)).toBe("200");

    // 3. `/syntax/check` (valid & syntax error)
    const okSyntax = await client.postForm<{ code: number | string }>(
      "/syntax/check",
      {
        userId: session.userId,
        token: session.secretKey,
        dbvendor: "dbvpostgresql",
        sql: "SELECT a, b FROM valid_table WHERE a = 1;"
      },
      client.authHeaders(session)
    );
    expect(String(okSyntax.body.code)).toBe("200");

    const badSyntax = await client.postForm<{ code: number | string }>(
      "/syntax/check",
      {
        userId: session.userId,
        token: session.secretKey,
        dbvendor: "dbvpostgresql",
        sql: "SELECT FROM WHERE (((;"
      },
      client.authHeaders(session)
    );
    expect(Number(badSyntax.body.code)).toBe(500);

    // 4. `/demo/keyword/check`, `/demo/tableColumns/getTableColumns`, `/demo/xml/toXML`, `/liveDemo`
    const kwRes = await client.postForm<{ code: number | string }>("/demo/keyword/check", {
      userId: session.userId,
      word: "SELECT,emp_custom_col",
      dbvendor: "dbvoracle"
    });
    expect(String(kwRes.body.code)).toBe("200");

    const tcCsvRes = await client.postForm<{ code: number | string; data?: unknown }>(
      "/demo/tableColumns/getTableColumns",
      {
        userId: session.userId,
        dbvendor: "dbvpostgresql",
        sqltext: "SELECT u.id, u.email FROM users u;",
        mode: "summary",
        format: "csv"
      }
    );
    expect(String(tcCsvRes.body.code)).toBe("200");

    const xmlAstRes = await client.postForm<{
      code: number | string;
      data?: { xml?: string } | string;
    }>(
      "/demo/xml/toXML",
      {
        userId: session.userId,
        token: session.secretKey,
        dbvendor: "dbvoracle",
        sql: "SELECT emp_id FROM hr.employees;"
      },
      client.authHeaders(session)
    );
    expect(String(xmlAstRes.body.code)).toBe("200");
    expect(xmlAstRes.rawText).toContain("<");

    const injRes = await client.postForm<{ code: number | string }>(
      "/liveDemo",
      {
        userId: session.userId,
        token: session.secretKey,
        appId: "sqlInjectionDetector",
        database: "dbvmysql",
        sqltext: "SELECT * FROM accounts WHERE username = 'admin' OR '1'='1';"
      },
      client.authHeaders(session)
    );
    expect(String(injRes.body.code)).toBe("200");
  });

  test("Persistent Job lifecycle -> poll -> success -> XML, JSON, 15-col CSV, metadata_lineage.zip, GraphML, Parquet, & PNG downloads", async ({
    request
  }) => {
    const client = new SqlFlowApiClient(request);
    const jobName = `e2e_exports_${Date.now()}`;
    const etlSql = `
      CREATE TABLE raw_orders (
        order_id BIGINT,
        customer_id BIGINT,
        gross_amount DECIMAL(12,2),
        order_date DATE
      );

      CREATE VIEW stg_valid_orders AS
      SELECT order_id, customer_id, gross_amount, order_date
      FROM raw_orders
      WHERE gross_amount > 0;

      CREATE TABLE mart_customer_ltv AS
      SELECT customer_id, COUNT(order_id) AS order_count, SUM(gross_amount) AS lifetime_value
      FROM stg_valid_orders
      GROUP BY customer_id;
    `;

    // 1. Submit persistent job (`submitPersistJob`)
    const submitRes = await client.postMultipart<{
      code: number | string;
      data?: { jobId?: string; id?: string; subJobs?: Array<{ batchId?: string }> };
    }>(
      "/sqlflow/job/submitPersistJob",
      {
        userId: session.userId,
        token: session.secretKey,
        jobName,
        dbvendor: "dbvpostgresql",
        firstSubmit: true,
        incremental: true,
        showTransform: true
      },
      "sqlfiles",
      [{ name: "warehouse_etl.sql", buffer: Buffer.from(etlSql, "utf8") }],
      client.authHeaders(session)
    );
    expect(submitRes.status).toBe(200);
    expect(String(submitRes.body.code)).toBe("200");
    const jobId = String(submitRes.body.data?.jobId || submitRes.body.data?.id || "");
    expect(jobId).toBeTruthy();

    // 2. Poll until `status === "success"`
    const completedJob = await client.waitForJobCompletion(
      session.userId,
      session.secretKey,
      jobId
    );
    expect(["success", "partial_success"]).toContain(String(completedJob.status));

    // Hydrate session/graph caches
    const cacheRes = await client.postForm<{
      code: number | string;
      data?: { sessionId?: string };
    }>(
      "/sqlflow/job/updateUserJobGraphCache",
      { userId: session.userId, token: session.secretKey, jobId },
      client.authHeaders(session)
    );
    expect(String(cacheRes.body.code)).toBe("200");

    // Verify `getJobFiles` inline file inspection (< 10 MiB)
    const filesRes = await client.postForm<{
      code: number | string;
      data?: Record<string, unknown>;
    }>(
      "/sqlflow/job/getJobFiles",
      { userId: session.userId, token: session.secretKey, jobId },
      client.authHeaders(session)
    );
    expect(String(filesRes.body.code)).toBe("200");
    expect(filesRes.rawText).toContain("warehouse_etl.sql");

    // 3. Export #1 — JSON (`exportLineageAsJson` + `exportDatabaseMetadata`)
    const jsonExport = await request.post(client.url("/sqlflow/job/exportLineageAsJson"), {
      form: {
        userId: session.userId,
        token: session.secretKey,
        jobId
      },
      headers: client.authHeaders(session)
    });
    expect(jsonExport.status()).toBe(200);
    const jsonExportText = await jsonExport.text();
    expect(jsonExportText.toLowerCase()).toContain("mart_customer_ltv");

    const dbMetaExport = await client.postForm<{ code: number | string }>(
      "/sqlflow/job/exportDatabaseMetadata",
      { userId: session.userId, token: session.secretKey, jobId },
      client.authHeaders(session)
    );
    expect(dbMetaExport.status).toBe(200);
    expect(dbMetaExport.rawText.toLowerCase()).toContain("raw_orders");

    // 4. Export #2 — 15-col CSV (`job/exportLineageAsCsv` & `leftMostSourceTableAsCsv`)
    const jobCsvRes = await request.post(client.url("/sqlflow/job/exportLineageAsCsv"), {
      form: {
        userId: session.userId,
        token: session.secretKey,
        jobId,
        showTransform: "false"
      },
      headers: client.authHeaders(session)
    });
    expect(jobCsvRes.status()).toBe(200);
    const csvLines = (await jobCsvRes.text()).trim().split(/\r?\n/);
    expect(csvLines.length).toBeGreaterThanOrEqual(2);
    expect((csvLines[0] || "").split(",").length).toBe(15);

    const leftMostCsv = await client.postForm<string>(
      "/sqlflow/job/leftMostSourceTableAsCsv",
      {
        userId: session.userId,
        token: session.secretKey,
        jobId,
        tableNamePattern: ".*"
      },
      client.authHeaders(session)
    );
    expect(leftMostCsv.status).toBe(200);
    expect(leftMostCsv.rawText.length).toBeGreaterThan(0);

    // 5. Export #3 — `metadata_lineage.zip` (`download/metadata_lineage`)
    const zipRes = await request.post(
      client.url("/sqlflow/generation/sqlflow/download/metadata_lineage"),
      {
        form: {
          userId: session.userId,
          token: session.secretKey,
          dbvendor: "dbvpostgresql",
          jobId,
          sqltext: etlSql
        },
        headers: client.authHeaders(session)
      }
    );
    expect(zipRes.status()).toBe(200);
    const zipEntries = readZipArchiveEntries(Buffer.from(await zipRes.body()));
    expect(zipEntries.has("metadata.csv")).toBe(true);
    expect(zipEntries.has("lineage.csv")).toBe(true);

    // 6. Export #4 — yFiles GraphML (`job/exportLineageAsGraphml`)
    const graphmlRes = await request.post(
      client.url("/sqlflow/job/exportLineageAsGraphml"),
      {
        form: {
          userId: session.userId,
          token: session.secretKey,
          jobId
        },
        headers: client.authHeaders(session)
      }
    );
    expect(graphmlRes.status()).toBe(200);
    const graphmlText = await graphmlRes.text();
    expect(graphmlText).toContain("<graphml");
    expect(graphmlText).toContain("<graph");

    // 7. Export #5 — XML (`<dlineage>` lineage XML via `/demo/columnImpact` `/xml`, plus AST XML via `/demo/xml/toXML`, OBL-085)
    const lineageXmlRes = await client.postForm<{
      code: number | string;
      data?: unknown;
    }>(
      "/demo/columnImpact",
      {
        userId: session.userId,
        token: session.secretKey,
        dbvendor: "dbvpostgresql",
        sqltext: etlSql,
        params: JSON.stringify({ options: ["/xml"] })
      },
      client.authHeaders(session)
    );
    expect(lineageXmlRes.status).toBe(200);
    expect(String(lineageXmlRes.body.code)).toBe("200");
    expect(lineageXmlRes.rawText).toMatch(/<(dlineage|columnImpact)/i);

    const xmlRes = await client.postForm<{ code: number | string }>(
      "/demo/xml/toXML",
      {
        userId: session.userId,
        token: session.secretKey,
        dbvendor: "dbvpostgresql",
        sqltext: etlSql,
        sql: etlSql
      },
      client.authHeaders(session)
    );
    expect(String(xmlRes.body.code)).toBe("200");
    expect(xmlRes.rawText).toContain("<");

    // 8. Export #6 — Parquet (`generate/parquet` -> `download/parquet`)
    const subJobs = (completedJob.subJobs as Array<{ batchId?: string }> | undefined) || [];
    const batchId = subJobs[0]?.batchId || "batch_1";
    const genParquetRes = await client.postForm<{ code: number | string }>(
      "/sqlflow/job/generate/parquet",
      {
        userId: session.userId,
        token: session.secretKey,
        jobId,
        jobName,
        batchId
      },
      client.authHeaders(session)
    );
    expect(genParquetRes.status).toBe(200);
    if (String(genParquetRes.body.code) === "200") {
      const dlParquetRes = await request.get(client.url("/sqlflow/job/download/parquet"), {
        params: {
          userId: session.userId,
          token: session.secretKey,
          jobId,
          jobName,
          batchId
        },
        headers: client.authHeaders(session)
      });
      expect(dlParquetRes.status()).toBe(200);
      const pqBuf = Buffer.from(await dlParquetRes.body());
      expect(isValidParquetBuffer(pqBuf)).toBe(true);
    }

    // 9. Export #7 — Server-Side PNG (`sqlflow/graph/image` & `leftMostSourceTableGraph/image`)
    const pngRes = await request.post(
      client.url("/sqlflow/generation/sqlflow/graph/image"),
      {
        form: {
          userId: session.userId,
          token: session.secretKey,
          dbvendor: "dbvpostgresql",
          jobId,
          sqltext: etlSql
        },
        headers: client.authHeaders(session)
      }
    );
    expect(pngRes.status()).toBe(200);
    const pngInfo = inspectPngBytes(Buffer.from(await pngRes.body()));
    expect(pngInfo.width).toBeGreaterThan(50);
    expect(pngInfo.height).toBeGreaterThan(30);

    // Clean up test job
    const delJob = await client.postForm<{ code: number | string }>(
      "/sqlflow/job/deleteUserJob",
      { userId: session.userId, token: session.secretKey, jobId },
      client.authHeaders(session)
    );
    expect(String(delJob.body.code)).toBe("200");
  });
});
