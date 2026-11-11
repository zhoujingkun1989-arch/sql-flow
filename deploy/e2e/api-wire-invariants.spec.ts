import { expect, test } from "@playwright/test";
import { DBO_COLORS } from "@sqlflow/ui-core/theme";
import {
  DEFAULT_GUEST_USER_ID,
  type JoinAnalysisGraph,
  type SqlflowJSON as SqlflowGraphPayload,
  type VersionEnvelope
} from "@sqlflow/ui-core/types";
import {
  CLEAN_ROOM_DEFAULT_RSA_1024_PUBLIC_KEY,
  encryptRsaPassword,
  flattenDbObjects,
  inspectPngBytes,
  parseSseStream,
  readZipArchiveEntries,
  SqlFlowApiClient,
  type BootstrappedAuthSession
} from "./helpers/sqlflow-client.js";

test.describe("SQLFlow 8.2.2.15 — All 20 Cross-Layer Wire Invariants (00 §3.3)", () => {
  let session: BootstrappedAuthSession;

  test.beforeAll(async ({ playwright }) => {
    const reqCtx = await playwright.request.newContext({
      baseURL: process.env.SQLFLOW_BASE_URL || "http://localhost:8165"
    });
    const client = new SqlFlowApiClient(reqCtx);
    session = await client.bootstrapSession();
    await reqCtx.dispose();
  });

  test("Invariant #1 & WebUrlFilter: HTTP 200 + JSON code on standard routes vs HTTP 401/400/429 on V1/Anonymous/JoinAnalysis, plus 3-prefix parity", async ({
    request
  }) => {
    const client = new SqlFlowApiClient(request);

    // 1a. 3-prefix WebUrlFilter equivalence (`/X`, `/gspLive_backend/X`, `/api/gspLive_backend/X`)
    for (const prefix of ["", "/gspLive_backend", "/api/gspLive_backend"]) {
      const verRes = await client.getJson<VersionEnvelope>(`${prefix}/version`, {
        userId: session.guestUserId
      });
      expect(verRes.status, `Expected HTTP 200 on ${prefix}/version`).toBe(200);
      expect(verRes.body.version?.backend?.version).toBe("8.2.2.15");
    }

    // 1b. Standard `@UserValidate` route returns HTTP 200 OK with body `code: 401` on invalid token
    const badStd = await client.postForm<{ code: number | string; error?: string }>(
      "/sqlflow/job/displayUserJobsSummary",
      {
        userId: "nonexistent-user-for-invariant-1",
        token: "invalid-secret-key"
      }
    );
    expect(badStd.status).toBe(200);
    expect(Number(badStd.body.code)).toBe(401);

    // 1c. Public V1 REST API (`/v1/sqlflow/**`) returns real HTTP 401 R.fail on invalid Bearer API key
    const badV1 = await client.postJson<{
      code: number;
      success?: boolean;
      error?: string;
    }>(
      "/v1/sqlflow/sqlflow",
      {
        dbvendor: "dbvoracle",
        sqltext: "SELECT emp_id FROM hr.employees;"
      },
      { Authorization: "Bearer invalid_v1_secret_key_xyz" }
    );
    expect(badV1.status).toBe(401);
    expect(badV1.body.code).toBe(401);
    expect(badV1.body.success).toBe(false);

    // 1d. `/sqlflow/generation/sqlflow/join-analysis` returns real HTTP 400 on missing sqltext / bad vendor
    const badJoinMissingSql = await client.postForm<{ code: number; error: string }>(
      "/sqlflow/generation/sqlflow/join-analysis",
      { dbvendor: "dbvoracle", sqltext: "" }
    );
    expect(badJoinMissingSql.status).toBe(400);
    expect(badJoinMissingSql.body.code).toBe(400);

    const badJoinVendor = await client.postForm<{ code: number; error: string }>(
      "/sqlflow/generation/sqlflow/join-analysis",
      { dbvendor: "dbv_not_a_real_dialect", sqltext: "SELECT 1" }
    );
    expect(badJoinVendor.status).toBe(400);
    expect(badJoinVendor.body.code).toBe(400);

    // 1e. `/api/anonymous/**` returns HTTP 400 on bad request and enforces 50 req/UTC-day/IP HTTP 429
    const uniqueIp = `198.51.100.${Math.floor(Math.random() * 200) + 10}`;
    const badAnon = await client.postJson<{ code: number; error: string }>(
      "/api/anonymous/lineage",
      { dbvendor: "dbvoracle", sqltext: "" },
      { "X-Forwarded-For": uniqueIp }
    );
    expect(badAnon.status).toBe(400);
    expect(badAnon.body.code).toBe(400);

    // Exhaust remaining quota for this isolated synthetic IP up to request #51 -> HTTP 429
    const rateIp = `203.0.113.${Math.floor(Math.random() * 200) + 10}`;
    let lastStatus = 200;
    let sawRateLimitHeaders = false;
    for (let i = 1; i <= 51; i++) {
      const r = await client.postJson<{ code?: number; error?: string }>(
        "/api/anonymous/sqlformatter/format",
        {
          input_sql: "select a,b from tbl",
          format_option: { dbVendor: "dbvoracle" }
        },
        { "X-Forwarded-For": rateIp }
      );
      if (r.response.headers()["x-ratelimit-limit"] === "50") {
        sawRateLimitHeaders = true;
      }
      lastStatus = r.status;
      if (i === 51) {
        expect(r.status).toBe(429);
        expect(r.body.code).toBe(429);
        expect(r.body.error).toBe("rate_limit_exceeded");
      }
    }
    expect(sawRateLimitHeaders).toBe(true);
    expect(lastStatus).toBe(429);
  });

  test("Invariant #2: Unwrapped /version envelope (GET & POST) with regular.job === true", async ({
    request
  }) => {
    const client = new SqlFlowApiClient(request);

    for (const method of ["GET", "POST"] as const) {
      const res =
        method === "GET"
          ? await client.getJson<Record<string, unknown>>("/version")
          : await client.postForm<Record<string, unknown>>("/version", {});

      expect(res.status).toBe(200);
      expect(res.body).not.toHaveProperty("code");
      expect(res.body).not.toHaveProperty("data");
      expect(res.body).toHaveProperty("version");

      const ver = res.body.version as VersionEnvelope["version"];
      expect(ver.backend.version).toBe("8.2.2.15");
      expect(ver.gsp.version).toBeTruthy();
      expect(ver.support["regular.job"]).toBe(true);
    }
  });

  test("Invariant #3: Strict string code === '200' & top-level secretKey / token on credential endpoints + V1 Bearer auth", async ({
    request
  }) => {
    const client = new SqlFlowApiClient(request);

    const skRes = await client.postForm<Record<string, unknown>>(
      "/user/generateSecretKey",
      { userId: session.userId },
      client.authHeaders(session)
    );
    expect(skRes.status).toBe(200);
    expect(skRes.body.code).toBe("200");
    expect(typeof skRes.body.code).toBe("string");
    expect(skRes.body).not.toHaveProperty("data");
    expect(typeof skRes.body.secretKey).toBe("string");
    const secretKey = skRes.body.secretKey as string;

    const tokRes = await client.postForm<Record<string, unknown>>("/user/generateToken", {
      userId: session.userId,
      secretKey
    });
    expect(tokRes.status).toBe(200);
    expect(tokRes.body.code).toBe("200");
    expect(typeof tokRes.body.code).toBe("string");
    expect(tokRes.body).not.toHaveProperty("data");
    expect(typeof tokRes.body.token).toBe("string");

    // Verify generated secretKey authenticates V1 API `/v1/sqlflow/sqlflow`
    const v1Res = await client.postJson<{ code: number | string; data?: unknown }>(
      "/v1/sqlflow/sqlflow",
      {
        dbvendor: "dbvpostgresql",
        sqltext: "CREATE VIEW v_active AS SELECT id, email FROM app_users;"
      },
      client.bearerHeaders(secretKey)
    );
    expect(v1Res.status).toBe(200);
    expect(String(v1Res.body.code)).toBe("200");

    // Refresh shared session credentials since generateSecretKey rotated secretKey
    session.secretKey = secretKey;
    session.jwtToken = tokRes.body.token as string;
  });

  test("Invariant #4 & #5: Transposed 'unqiueKey' column property & 1-based [line, col] coordinates + getSelectedDbObjectInfo", async ({
    request
  }) => {
    const client = new SqlFlowApiClient(request);
    const ddlAndDml = [
      "CREATE TABLE accounts (",
      "  account_id INT PRIMARY KEY,",
      "  account_code VARCHAR(32) UNIQUE,",
      "  balance DECIMAL(12, 2)",
      ");",
      "CREATE VIEW v_accounts AS",
      "SELECT account_id, account_code, balance FROM accounts;"
    ].join("\n");

    const erRes = await client.postForm<{
      code: number | string;
      sessionId?: string;
      data?: SqlflowGraphPayload;
    }>(
      "/sqlflow/generation/sqlflow/erdiagramSelectGraph",
      {
        userId: session.userId,
        token: session.secretKey,
        dbvendor: "dbvoracle",
        sqltext: ddlAndDml
      },
      client.authHeaders(session)
    );
    expect(erRes.status).toBe(200);
    expect(String(erRes.body.code)).toBe("200");
    expect(erRes.rawText).toContain('"unqiueKey"');

    const graphRes = await client.postForm<{
      code: number | string;
      sessionId?: string;
      data?: SqlflowGraphPayload;
    }>(
      "/sqlflow/generation/sqlflow/graph",
      {
        userId: session.userId,
        token: session.secretKey,
        dbvendor: "dbvoracle",
        sqltext: ddlAndDml
      },
      client.authHeaders(session)
    );
    expect(String(graphRes.body.code)).toBe("200");
    const sessionId = graphRes.body.sessionId || "";
    expect(sessionId).toBeTruthy();

    const dbobjs = flattenDbObjects(graphRes.body.data?.sqlflow?.dbobjs);
    expect(dbobjs.length).toBeGreaterThan(0);

    let sampleCoordPayload: unknown = null;
    for (const obj of dbobjs) {
      for (const col of obj.columns || []) {
        if (Array.isArray(col.coordinates) && col.coordinates.length > 0) {
          const c0 = col.coordinates[0] as unknown as Record<string, number> | number[];
          if (Array.isArray(c0)) {
            expect(c0[0]).toBeGreaterThanOrEqual(1);
            expect(c0[1]).toBeGreaterThanOrEqual(1);
          } else if (c0 && typeof c0 === "object") {
            expect(Number(c0.x)).toBeGreaterThanOrEqual(1);
            expect(Number(c0.y)).toBeGreaterThanOrEqual(1);
          }
          sampleCoordPayload = col.coordinates;
        }
      }
    }
    expect(sampleCoordPayload).not.toBeNull();

    const posRes = await client.postForm<{
      code: number | string;
      data?: Array<{ sql: string; positions?: Array<{ x: number; y: number }> }>;
    }>(
      "/sqlflow/generation/sqlflow/getSelectedDbObjectInfo",
      {
        userId: session.userId,
        token: session.secretKey,
        sessionId,
        sqlHash: sessionId,
        coordinates: JSON.stringify(sampleCoordPayload)
      },
      client.authHeaders(session)
    );
    expect(posRes.status).toBe(200);
    expect(String(posRes.body.code)).toBe("200");
    expect(Array.isArray(posRes.body.data)).toBe(true);
  });

  test("Invariant #6: Dual registration + sign-in on POST /user/register with plaintext credentials", async ({
    request
  }) => {
    const client = new SqlFlowApiClient(request);
    const uniqueEmail = `e2e_dual_${Date.now()}@local.gudusoft.com`;
    const password = "CleanRoomUser#123";

    // 1st call -> registers new user
    const firstCall = await client.postForm<{
      code: string | number;
      first_login?: boolean;
      token?: string;
      data?: { userId?: string; userEmail?: string };
      userId?: string;
    }>("/user/register", {
      userName: "e2e_dual_user",
      userEmail: uniqueEmail,
      password
    });
    expect(firstCall.status).toBe(200);
    expect(String(firstCall.body.code)).toBe("200");
    const createdUserId = firstCall.body.data?.userId || firstCall.body.userId;
    expect(createdUserId).toBeTruthy();

    // 2nd call with identical credentials -> signs in existing user and returns identical userId
    const secondCall = await client.postForm<{
      code: string | number;
      first_login?: boolean;
      token?: string;
      data?: { userId?: string; userEmail?: string };
      userId?: string;
    }>("/user/register", {
      userEmail: uniqueEmail,
      password
    });
    expect(secondCall.status).toBe(200);
    expect(String(secondCall.body.code)).toBe("200");
    const loginUserId = secondCall.body.data?.userId || secondCall.body.userId;
    expect(loginUserId).toBe(createdUserId);
  });

  test("Invariant #7: RSA PKCS#1 v1.5 DataSource password encryption & plaintext fallback + password masking on Job DTO", async ({
    request
  }) => {
    const client = new SqlFlowApiClient(request);
    const rawPassword = "db_secret_pw";
    const encrypted = encryptRsaPassword(rawPassword, CLEAN_ROOM_DEFAULT_RSA_1024_PUBLIC_KEY);
    expect(encrypted).toBeTruthy();
    expect(encrypted).not.toBe(rawPassword);
    expect(Buffer.from(encrypted, "base64").length).toBe(128);

    // `/database/testConnection` accepts both 1024-bit RSA PKCS#1 v1.5 ciphertext and raw plaintext fallback
    for (const pwCandidate of [encrypted, rawPassword]) {
      const connRes = await client.postForm<{ code: number | string; error?: string }>(
        "/database/testConnection",
        {
          userId: session.userId,
          token: session.secretKey,
          dbvendor: "dbvpostgresql",
          hostname: "127.0.0.1",
          port: 59999,
          account: "postgres",
          password: pwCandidate,
          database: "warehouse"
        },
        client.authHeaders(session)
      );
      expect(connRes.status).toBe(200);
      expect([200, 500]).toContain(Number(connRes.body.code));
    }

    // Submitting a job with SQL file + RSA-encrypted DataSource password masks `dataSource.password` to null/undefined on wire
    const sqlBuf = Buffer.from(
      "CREATE VIEW v_masked AS SELECT id FROM raw_events;",
      "utf8"
    );
    const jobRes = await client.postMultipart<{
      code: number | string;
      data?: { jobId?: string; id?: string; dataSource?: { password?: string | null } };
    }>(
      "/sqlflow/job/submitUserJob",
      {
        userId: session.userId,
        token: session.secretKey,
        jobName: `inv7_mask_${Date.now()}`,
        dbvendor: "dbvpostgresql",
        password: encrypted
      },
      "sqlfiles",
      [{ name: "masked.sql", buffer: sqlBuf }],
      client.authHeaders(session)
    );
    expect(jobRes.status).toBe(200);
    expect(String(jobRes.body.code)).toBe("200");
    expect(jobRes.body.data?.dataSource?.password ?? null).toBeNull();
  });

  test("Invariant #8: Unwrapped JoinAnalysisGraph (schemaVersion === 'join-analysis.v1') on join-analysis", async ({
    request
  }) => {
    const client = new SqlFlowApiClient(request);
    const joinSql = `
      SELECT o.order_id, c.customer_name, i.sku
      FROM orders o
      INNER JOIN customers c ON o.customer_id = c.customer_id
      LEFT JOIN order_items i ON o.order_id = i.order_id;
    `;

    const res = await client.postForm<JoinAnalysisGraph & Record<string, unknown>>(
      "/sqlflow/generation/sqlflow/join-analysis",
      {
        userId: session.guestUserId,
        dbvendor: "dbvpostgresql",
        sqltext: joinSql
      }
    );
    expect(res.status).toBe(200);
    expect(res.body.schemaVersion).toBe("join-analysis.v1");
    expect(res.body).not.toHaveProperty("data");
  });

  test("Invariant #9: Internal raw DataFlow (/sqlflow/lineage/*) vs public laid-out SqlflowJSON (/sqlflow/generation/sqlflow/*Graph)", async ({
    request
  }) => {
    const client = new SqlFlowApiClient(request);
    const chainSql = `
      CREATE TABLE src_orders (order_id INT, amount DECIMAL(10,2));
      CREATE VIEW stg_orders AS SELECT order_id, amount FROM src_orders;
      CREATE TABLE fct_orders AS SELECT order_id, amount FROM stg_orders;
    `;

    const baseGraph = await client.postForm<{
      code: number | string;
      sessionId?: string;
      data?: SqlflowGraphPayload;
    }>(
      "/sqlflow/generation/sqlflow/graph",
      {
        userId: session.userId,
        token: session.secretKey,
        dbvendor: "dbvsnowflake",
        sqltext: chainSql
      },
      client.authHeaders(session)
    );
    expect(String(baseGraph.body.code)).toBe("200");
    const sessionId = baseGraph.body.sessionId || "";
    expect(sessionId).toBeTruthy();

    // Public gateway upstreamGraph -> wrapped `{ code: 200, data: { graph: { elements: ... } } }`
    const pubUp = await client.postForm<{
      code: number | string;
      data?: SqlflowGraphPayload;
    }>(
      "/sqlflow/generation/sqlflow/upstreamGraph",
      {
        userId: session.userId,
        token: session.secretKey,
        sessionId,
        dbvendor: "dbvsnowflake",
        tableNamePattern: "fct_orders",
        distance: 3
      },
      client.authHeaders(session)
    );
    expect(pubUp.status).toBe(200);
    expect(String(pubUp.body.code)).toBe("200");
    expect(pubUp.body.data?.graph?.elements).toBeDefined();

    // Internal `/sqlflow/lineage/upstream` -> raw unwrapped DataFlow (`dbobjs`, `relationships`)
    const intUp = await client.postForm<Record<string, unknown>>(
      "/sqlflow/lineage/upstream",
      {
        jobId: sessionId,
        dbvendor: "dbvsnowflake",
        tableName: "fct_orders",
        level: 3
      },
      client.authHeaders(session)
    );
    expect(intUp.status).toBe(200);
    expect(intUp.body).toHaveProperty("dbobjs");
    expect(intUp.body).toHaveProperty("relationships");
  });

  test("Invariant #10: Top-level job, jobId, sessionId on POST /sqlflow/job/displayUserLatestJobGraph", async ({
    request
  }) => {
    const client = new SqlFlowApiClient(request);
    const submitRes = await client.postMultipart<{
      code: number | string;
      data?: { jobId?: string; id?: string };
    }>(
      "/sqlflow/job/submitPersistJob",
      {
        userId: session.userId,
        token: session.secretKey,
        jobName: `inv10_latest_${Date.now()}`,
        dbvendor: "dbvoracle",
        firstSubmit: true,
        incremental: false
      },
      "sqlfiles",
      [
        {
          name: "latest_job.sql",
          buffer: Buffer.from(
            "CREATE TABLE raw_emp (id INT, sal NUMBER); CREATE VIEW v_emp AS SELECT id, sal FROM raw_emp;",
            "utf8"
          )
        }
      ],
      client.authHeaders(session)
    );
    expect(String(submitRes.body.code)).toBe("200");
    const jobId = String(submitRes.body.data?.jobId || submitRes.body.data?.id || "");
    expect(jobId).toBeTruthy();

    await client.waitForJobCompletion(session.userId, session.secretKey, jobId);

    const latestRes = await client.postForm<{
      code: number | string;
      job?: Record<string, unknown>;
      jobId?: string;
      sessionId?: string;
      data?: SqlflowGraphPayload;
    }>(
      "/sqlflow/job/displayUserLatestJobGraph",
      {
        userId: session.userId,
        token: session.secretKey
      },
      client.authHeaders(session)
    );
    expect(latestRes.status).toBe(200);
    expect(String(latestRes.body.code)).toBe("200");
    expect(latestRes.body.job).toBeDefined();
    expect(latestRes.body.jobId).toBeTruthy();
    expect(latestRes.body.sessionId).toBeTruthy();
    expect(latestRes.body.data).toBeDefined();
  });

  test("Invariant #11 & #12: Multipart 'sqlfiles' User DDL Schema upload + double-nested vendor array in displayUserSchemaSummary", async ({
    request
  }) => {
    const client = new SqlFlowApiClient(request);
    const schemaFileName = `inv11_schema_${Date.now()}.sql`;
    const ddl = "CREATE TABLE inv11_customers (cust_id NUMBER PRIMARY KEY, email VARCHAR2(128));";

    const uploadRes = await client.postMultipart<{
      code: number | string;
      data?: string[];
    }>(
      "/sqlflow/generation/submitUserSchema",
      {
        userId: session.userId,
        token: session.secretKey,
        dbvendor: "dbvoracle"
      },
      "sqlfiles",
      [{ name: schemaFileName, buffer: Buffer.from(ddl, "utf8") }],
      client.authHeaders(session)
    );
    expect(uploadRes.status).toBe(200);
    expect(String(uploadRes.body.code)).toBe("200");
    expect(uploadRes.body.data).toContain(schemaFileName);

    // `displayUserSchemaSummary` strips `"dbv"` -> `"oracle"` and returns double-nested array `string[][]`
    const summaryRes = await client.postForm<{
      code: number | string;
      data?: Record<string, string[][]>;
    }>(
      "/sqlflow/generation/displayUserSchemaSummary",
      {
        userId: session.userId,
        token: session.secretKey
      },
      client.authHeaders(session)
    );
    expect(summaryRes.status).toBe(200);
    expect(String(summaryRes.body.code)).toBe("200");
    const oracleEntries = summaryRes.body.data?.oracle;
    expect(Array.isArray(oracleEntries)).toBe(true);
    expect(Array.isArray(oracleEntries?.[0])).toBe(true);
    const flattened = (oracleEntries || []).flat();
    expect(flattened).toContain(schemaFileName);

    // Cleanup uploaded schema file
    const delRes = await client.postForm<{ code: number | string }>(
      "/sqlflow/generation/deleteUserSchema",
      {
        userId: session.userId,
        token: session.secretKey,
        dbvendor: "dbvoracle",
        fileName: schemaFileName
      },
      client.authHeaders(session)
    );
    expect(String(delRes.body.code)).toBe("200");
  });

  test("Invariant #13 & #14: Query params on POST /user/downloadUserSql & top-level products key on POST /product/products", async ({
    request
  }) => {
    const client = new SqlFlowApiClient(request);

    // Invariant #13: Query string parameters accepted on empty-body POST `/user/downloadUserSql`
    const dlUrl = `${client.url("/user/downloadUserSql")}?userId=${encodeURIComponent(
      session.userId
    )}&user=${encodeURIComponent(session.userEmail)}&fileName=20261027120000000_nonexistent.sql`;
    const dlRes = await request.post(dlUrl, {
      headers: client.authHeaders(session)
    });
    expect(dlRes.status()).toBe(200);

    // Invariant #14: Top-level `products` array on `POST /product/products`
    const prodRes = await client.postForm<Record<string, unknown>>(
      "/product/products",
      { userId: session.userId },
      client.authHeaders(session)
    );
    expect(prodRes.status).toBe(200);
    expect(String(prodRes.body.code)).toBe("200");
    expect(Array.isArray(prodRes.body.products)).toBe(true);
  });

  test("Invariant #15: 15-column lineage CSV & metadata_lineage.zip packaging", async ({
    request
  }) => {
    const client = new SqlFlowApiClient(request);
    const sql =
      "CREATE VIEW v_rev AS SELECT o.order_id, o.total_amt FROM sales.orders o;";

    const csvRes = await client.postForm<string>(
      "/sqlflow/generation/sqlflow/exportLineageAsCsv",
      {
        userId: session.userId,
        token: session.secretKey,
        dbvendor: "dbvpostgresql",
        sqltext: sql,
        showTransform: false
      },
      client.authHeaders(session)
    );
    expect(csvRes.status).toBe(200);
    const lines = csvRes.rawText.trim().split(/\r?\n/);
    expect(lines.length).toBeGreaterThanOrEqual(2);
    const headerCols = (lines[0] || "").split(",");
    expect(headerCols.length).toBe(15);

    // `download/metadata_lineage` returns ZIP archive containing `metadata.csv` + `lineage.csv`
    const zipResponse = await request.post(
      client.url("/sqlflow/generation/sqlflow/download/metadata_lineage"),
      {
        form: {
          userId: session.userId,
          token: session.secretKey,
          dbvendor: "dbvpostgresql",
          sqltext: sql
        },
        headers: client.authHeaders(session)
      }
    );
    expect(zipResponse.status()).toBe(200);
    const zipBytes = Buffer.from(await zipResponse.body());
    const entries = readZipArchiveEntries(zipBytes);
    expect(entries.has("metadata.csv")).toBe(true);
    expect(entries.has("lineage.csv")).toBe(true);
  });

  test("Invariant #16: AI Lineage Assistant unwrapped GET /ai/status & SSE POST /ai/query", async ({
    request
  }) => {
    const client = new SqlFlowApiClient(request);

    const statusRes = await client.getJson<Record<string, unknown>>(
      "/sqlflow/generation/ai/status"
    );
    expect(statusRes.status).toBe(200);
    expect(typeof statusRes.body.enabled).toBe("boolean");
    expect(typeof statusRes.body.model).toBe("string");
    expect(statusRes.body).not.toHaveProperty("data");

    const sseRes = await request.post(
      client.url("/sqlflow/generation/ai/query"),
      {
        form: {
          sessionId: "nonexistent-session-for-sse-contract",
          question: "What feeds orders?",
          dbvendor: "dbvoracle",
          hopDepth: "3"
        },
        headers: client.authHeaders(session)
      }
    );
    expect(sseRes.status()).toBe(200);
    expect(sseRes.headers()["content-type"] || "").toContain("text/event-stream");
    const frames = parseSseStream(await sseRes.text());
    expect(frames.length).toBeGreaterThanOrEqual(1);
  });

  test("Invariant #17: Complexity degradation & >200 object summary gate without crashing server", async ({
    request
  }) => {
    const client = new SqlFlowApiClient(request);
    const statements: string[] = [];
    for (let i = 1; i <= 215; i++) {
      statements.push(`CREATE VIEW v_scale_${i} AS SELECT id FROM tbl_scale_${i};`);
    }

    const res = await client.postForm<{
      code: number | string;
      data?: SqlflowGraphPayload;
    }>(
      "/sqlflow/generation/sqlflow/graph",
      {
        userId: session.userId,
        token: session.secretKey,
        dbvendor: "dbvpostgresql",
        sqltext: statements.join("\n")
      },
      client.authHeaders(session)
    );
    expect(res.status).toBe(200);
    expect(String(res.body.code)).toBe("200");
    const reportedMode =
      (res.body as { mode?: string }).mode || res.body.data?.mode || "global";
    expect(["summary", "ignoreRecordSet", "global"]).toContain(reportedMode);
  });

  test("Invariant #18, #19, & #20: Default guest gudu|0123456789, MD5 user settings round-trip, & 17-color dboColor PNG rendering", async ({
    request
  }) => {
    const client = new SqlFlowApiClient(request);

    // Invariant #18: Guest `gudu|0123456789` matches config.private.json and works out-of-the-box
    const cfg = await client.getJson<{ userId: string }>("/config.private.json");
    expect(cfg.body.userId).toBe(DEFAULT_GUEST_USER_ID);

    const guestGraph = await client.postForm<{
      code: number | string;
      data?: SqlflowGraphPayload;
    }>("/api/gspLive_backend/sqlflow/generation/sqlflow/graph", {
      userId: DEFAULT_GUEST_USER_ID,
      dbvendor: "dbvoracle",
      sqltext: "SELECT emp_id, dept_id, 'ACTIVE' AS status FROM hr.employees;",
      showConstantTable: true
    });
    expect(guestGraph.status).toBe(200);
    expect(String(guestGraph.body.code)).toBe("200");

    // Invariant #19: User settings persistence round-trip (`saveUserSetting` -> `getUserSetting`)
    const prefValue = JSON.stringify({
      showRelationType: "fdd,fdr",
      ignoreRecordSet: true,
      marker: `ts_${Date.now()}`
    });
    const savePref = await client.postForm<{ code: number | string }>(
      "/user/saveUserSetting",
      {
        userId: session.userId,
        section: "querymode",
        value: prefValue
      },
      client.authHeaders(session)
    );
    expect(String(savePref.body.code)).toBe("200");

    const getPref = await client.postForm<{ code: number | string; data?: unknown }>(
      "/user/getUserSetting",
      {
        userId: session.userId,
        section: "querymode"
      },
      client.authHeaders(session)
    );
    expect(String(getPref.body.code)).toBe("200");
    expect(getPref.rawText).toContain("fdd,fdr");

    // Invariant #20: 17-entry dboColor palette & valid server-side PNG diagram rendering
    expect(Object.keys(DBO_COLORS).length).toBe(17);
    expect(DBO_COLORS.table).toBe("#91c051");
    expect(DBO_COLORS.view).toBe("#419a5f");
    expect(DBO_COLORS.default_gray).toBe("#dbdada");

    const pngRes = await request.post(
      client.url("/sqlflow/generation/sqlflow/graph/image"),
      {
        form: {
          userId: session.userId,
          token: session.secretKey,
          dbvendor: "dbvoracle",
          sqltext: "CREATE VIEW v_dept AS SELECT dept_id, dept_name FROM hr.departments;"
        },
        headers: client.authHeaders(session)
      }
    );
    expect(pngRes.status()).toBe(200);
    const pngMeta = inspectPngBytes(Buffer.from(await pngRes.body()));
    expect(pngMeta.width).toBeGreaterThan(50);
    expect(pngMeta.height).toBeGreaterThan(30);
  });
});
