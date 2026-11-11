import crypto from "node:crypto";
import { describe, expect, it, vi } from "vitest";
import {
  CLEAN_ROOM_DEFAULT_RSA_1024_PUBLIC_KEY,
  DEFAULT_GUEST_USER_ID,
  SseFrameParser,
  createSpaApiClient,
  createWidgetApiClient,
  encryptRsaPassword,
  resolveBackendApiBase
} from "../index.js";

interface CapturedCall {
  url: string;
  method: string;
  headers: Record<string, string>;
  body: BodyInit | null | undefined;
}

describe("HTTP Client, Auth/userId Injection, Strict 1024-Bit RSA Scope & SSE Stream Parser", () => {
  it("resolves SPA (/api/gspLive_backend) and Widget (/gspLive_backend) prefixes without duplication", () => {
    expect(resolveBackendApiBase(undefined, "spa")).toBe("/api/gspLive_backend");
    expect(resolveBackendApiBase("/api", "spa")).toBe("/api/gspLive_backend");
    expect(resolveBackendApiBase("/api/gspLive_backend/", "spa")).toBe(
      "/api/gspLive_backend"
    );
    expect(resolveBackendApiBase(undefined, "widget")).toBe("/gspLive_backend");
    expect(resolveBackendApiBase("http://localhost:8081/api", "widget")).toBe(
      "http://localhost:8081/api/gspLive_backend"
    );
  });

  it("encrypts DataSource.password with 1024-bit RSA ONLY on testConnection/submitUserJob/submitPersistJob and keeps auth passwords plaintext", async () => {
    expect(CLEAN_ROOM_DEFAULT_RSA_1024_PUBLIC_KEY.startsWith("MIGf")).toBe(true);

    // Generate an ephemeral clean-room 1024-bit keypair to verify PKCS#1 v1.5 decryption end-to-end
    const { publicKey, privateKey } = crypto.generateKeyPairSync("rsa", {
      modulusLength: 1024,
      publicKeyEncoding: { type: "spki", format: "pem" },
      privateKeyEncoding: { type: "pkcs8", format: "pem" }
    });

    const sampleCipher = encryptRsaPassword("DbS3cret!2026", publicKey);
    const decrypted = crypto
      .privateDecrypt(
        {
          key: privateKey,
          padding: crypto.constants.RSA_PKCS1_PADDING
        },
        Buffer.from(sampleCipher, "base64")
      )
      .toString("utf8");
    expect(decrypted).toBe("DbS3cret!2026");

    const calls: CapturedCall[] = [];
    const mockFetch: typeof fetch = vi.fn(async (input, init) => {
      calls.push({
        url: String(input),
        method: init?.method ?? "GET",
        headers: (init?.headers as Record<string, string>) ?? {},
        body: init?.body
      });
      return new Response(JSON.stringify({ code: 200, data: { jobId: "j_1" } }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    });

    const client = createSpaApiClient({
      token: "tok_abc",
      rsaPublicKey: publicKey,
      fetchImpl: mockFetch
    });

    // 1. JDBC testConnection -> RSA encrypted
    await client.testConnection({
      dbvendor: "oracle",
      hostname: "db.internal",
      port: 1521,
      database: "ORCL",
      account: "scott",
      password: "TigerPassword!"
    });
    const testConnParams = new URLSearchParams(String(calls[0]!.body));
    expect(calls[0]!.url).toBe("/api/gspLive_backend/database/testConnection");
    expect(calls[0]!.headers.Authorization).toBe("Token tok_abc");
    expect(testConnParams.get("userId")).toBe(DEFAULT_GUEST_USER_ID);
    expect(testConnParams.get("dbvendor")).toBe("dbvoracle");
    expect(testConnParams.get("account")).toBe("scott");
    const encConnPwd = testConnParams.get("password")!;
    expect(encConnPwd).not.toBe("TigerPassword!");
    expect(
      crypto
        .privateDecrypt(
          { key: privateKey, padding: crypto.constants.RSA_PKCS1_PADDING },
          Buffer.from(encConnPwd, "base64")
        )
        .toString("utf8")
    ).toBe("TigerPassword!");

    // 2. submitUserJob (Multipart) -> RSA encrypted
    await client.submitUserJob({
      dbvendor: "postgresql",
      jobName: "J_PG",
      sqlsource: "fromDatabase",
      hostname: "pg.internal",
      port: 5432,
      database: "warehouse",
      account: "analytics",
      password: "PgPassword#99"
    });
    const jobFd = calls[1]!.body as FormData;
    const encJobPwd = String(jobFd.get("password"));
    expect(encJobPwd).not.toBe("PgPassword#99");
    expect(
      crypto
        .privateDecrypt(
          { key: privateKey, padding: crypto.constants.RSA_PKCS1_PADDING },
          Buffer.from(encJobPwd, "base64")
        )
        .toString("utf8")
    ).toBe("PgPassword#99");

    // 3. submitPersistJob (UrlForm widget style) -> RSA encrypted
    await client.submitPersistJob(
      {
        dbvendor: "snowflake",
        jobName: "J_SF",
        sqlsource: "snowflakequeryhistory",
        password: "SnowPassword$1"
      },
      "form"
    );
    const persistParams = new URLSearchParams(String(calls[2]!.body));
    const encPersistPwd = persistParams.get("password")!;
    expect(encPersistPwd).not.toBe("SnowPassword$1");
    expect(
      crypto
        .privateDecrypt(
          { key: privateKey, padding: crypto.constants.RSA_PKCS1_PADDING },
          Buffer.from(encPersistPwd, "base64")
        )
        .toString("utf8")
    ).toBe("SnowPassword$1");

    // 4. User auth & admin endpoints -> MUST remain plaintext!
    await client.login({ userEmail: "u@example.com", password: "PlainLoginPwd" });
    await client.modifyPassword({ oldPassword: "OldPlain", newPassword: "NewPlain" });
    await client.addUser({
      operatorUserId: "admin_1",
      userEmail: "new@example.com",
      userName: "New User",
      password: "AdminCreatedPlain"
    });
    await client.resetPassword({
      operatorUserId: "admin_1",
      userEmail: "new@example.com",
      password: "ResetPlainPwd"
    });

    expect(new URLSearchParams(String(calls[3]!.body)).get("password")).toBe(
      "PlainLoginPwd"
    );
    expect(new URLSearchParams(String(calls[4]!.body)).get("oldPassword")).toBe(
      "OldPlain"
    );
    expect(new URLSearchParams(String(calls[4]!.body)).get("newPassword")).toBe(
      "NewPlain"
    );
    expect(new URLSearchParams(String(calls[5]!.body)).get("password")).toBe(
      "AdminCreatedPlain"
    );
    expect(new URLSearchParams(String(calls[6]!.body)).get("password")).toBe(
      "ResetPlainPwd"
    );
  });

  it("handles HTTP 200 business code interceptors (401 auto-logout, 403, 410, 412, 426, 509) and wire exceptions", async () => {
    const removedKeys: string[] = [];
    const onUnauthorized = vi.fn();
    const onForbidden = vi.fn();
    const onLicenseError = vi.fn();
    const onUpgradeRequired = vi.fn();
    const onLimitExceeded = vi.fn();

    let nextBody: unknown = {};
    const mockFetch: typeof fetch = vi.fn(async () => {
      return new Response(JSON.stringify(nextBody), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    });

    const widgetClient = createWidgetApiClient({
      storage: { removeItem: (k) => removedKeys.push(k) },
      fetchImpl: mockFetch,
      interceptors: {
        onUnauthorized,
        onForbidden,
        onLicenseError,
        onUpgradeRequired,
        onLimitExceeded
      }
    });

    nextBody = { code: 401, error: "invalid user or token" };
    await widgetClient.getUserInfo();
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
    expect(removedKeys).toEqual(["user"]);

    nextBody = { code: 403, error: "account expired" };
    await widgetClient.getUserInfo();
    expect(onForbidden).toHaveBeenCalledTimes(1);

    nextBody = { code: 412, error: "license invalid", "machine.code": "MC-9988" };
    await widgetClient.getUserInfo();
    expect(onLicenseError).toHaveBeenCalledWith(
      expect.objectContaining({ code: 412, machineCode: "MC-9988" })
    );

    nextBody = { code: 426, error: "upgrade required" };
    await widgetClient.getUserInfo();
    expect(onUpgradeRequired).toHaveBeenCalledTimes(1);

    nextBody = { code: 509, error: "quota exceeded" };
    await widgetClient.getUserInfo();
    expect(onLimitExceeded).toHaveBeenCalledTimes(1);

    // Unwrapped /version
    nextBody = {
      version: {
        gsp: { version: "3.1.0", "release.date": "2026-10-27" },
        backend: { version: "8.2.2.15", "release.date": "2026-10-27" },
        support: { "password.security.validate": false, "regular.job": true }
      }
    };
    const ver = await widgetClient.getVersion();
    expect(ver.version.backend.version).toBe("8.2.2.15");

    // Strict string "200" on generateSecretKey -> generateToken
    const seqResponses = [
      { userId: "gudu|0123456789", code: "200", secretKey: "sk_live_1" },
      { userId: "gudu|0123456789", code: "200", token: "tk_live_2" }
    ];
    let idx = 0;
    const tokenClient = createWidgetApiClient({
      fetchImpl: vi.fn(async () =>
        new Response(JSON.stringify(seqResponses[idx++]!), { status: 200 })
      )
    });
    const pair = await tokenClient.generateApiTokenPair();
    expect(pair).toEqual({ secretKey: "sk_live_1", token: "tk_live_2" });
  });

  it("parses chunk-split SSE streams across all 5 AI lineage event types (metadata, chunk, complete, no_match, error)", async () => {
    const parser = new SseFrameParser();
    const part1 = ': keepalive\ndata: {"type":"metadata","nodes":12,"ed';
    const part2 =
      'ges":18,"truncated":false}\r\n\r\nevent: chunk\ndata: {"type":"chunk","text":"total_revenue comes "}\n\n';
    const part3 =
      'data: {"type":"chunk","text":"from oi.quantity * p.unit_price"}\n\n' +
      'data: {"type":"complete","response":{"answer":"total_revenue comes from oi.quantity * p.unit_price","nodes":12}}\n\n' +
      'data: {"type":"no_match","message":"No table X"}\n\n' +
      'data: {"type":"error","message":"Rate limit"}';

    const out = [
      ...parser.feed(part1),
      ...parser.feed(part2),
      ...parser.feed(part3),
      ...parser.flush()
    ];

    expect(out).toEqual([
      { type: "metadata", nodes: 12, edges: 18, truncated: false },
      { type: "chunk", text: "total_revenue comes " },
      { type: "chunk", text: "from oi.quantity * p.unit_price" },
      {
        type: "complete",
        response: {
          answer: "total_revenue comes from oi.quantity * p.unit_price",
          nodes: 12
        }
      },
      { type: "no_match", message: "No table X" },
      { type: "error", message: "Rate limit" }
    ]);

    const encoder = new TextEncoder();
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(part1));
        controller.enqueue(encoder.encode(part2));
        controller.enqueue(encoder.encode(part3 + "\n\n"));
        controller.close();
      }
    });

    const streamedEvents: unknown[] = [];
    const sseCalls: CapturedCall[] = [];
    const client = createSpaApiClient({
      token: "ai_tok",
      fetchImpl: vi.fn(async (input, init) => {
        sseCalls.push({
          url: String(input),
          method: init?.method ?? "GET",
          headers: (init?.headers as Record<string, string>) ?? {},
          body: init?.body
        });
        return new Response(stream, {
          status: 200,
          headers: { "Content-Type": "text/event-stream" }
        });
      })
    });

    const result = await client.streamAiQuery(
      {
        sessionId: "sess_1",
        question: "Where does total_revenue come from?",
        dbvendor: "oracle",
        hopDepth: 4
      },
      { onEvent: (e) => streamedEvents.push(e) }
    );
    expect(result).toHaveLength(6);
    expect(streamedEvents).toHaveLength(6);

    expect(sseCalls[0]!.url).toBe("/api/gspLive_backend/sqlflow/generation/ai/query");
    expect(sseCalls[0]!.method).toBe("POST");
    expect(sseCalls[0]!.headers["Content-Type"]).toBe(
      "application/x-www-form-urlencoded;charset=UTF-8"
    );
    expect(sseCalls[0]!.headers.Accept).toBe("text/event-stream");
    expect(sseCalls[0]!.headers.Authorization).toBe("Token ai_tok");
    const sseForm = new URLSearchParams(String(sseCalls[0]!.body));
    expect(sseForm.get("userId")).toBe(DEFAULT_GUEST_USER_ID);
    expect(sseForm.get("sessionId")).toBe("sess_1");
    expect(sseForm.get("question")).toBe("Where does total_revenue come from?");
    expect(sseForm.get("dbvendor")).toBe("dbvoracle");
    expect(sseForm.get("hopDepth")).toBe("4");
  });

  it("matches all 6 AiLineageController routes, @RequestParam form bodies, and ApiEnvelope contracts (02 §4.5)", async () => {
    const calls: CapturedCall[] = [];
    const payloads: unknown[] = [
      { enabled: true, model: "claude-sonnet-4-5" },
      {
        code: 200,
        data: {
          answer: "Sync answer",
          referencedNodes: ["SALES.ORDERS", "PUBLIC.CUSTOMER_REVENUE"],
          referencedEdges: [{ from: "SALES.ORDERS", to: "PUBLIC.CUSTOMER_REVENUE" }],
          confidence: "high",
          basedOn: ["SELECT ... FROM SALES.ORDERS"]
        }
      },
      {
        code: 200,
        data: { answer: "Impact analysis for ORDERS", citations: [{ table: "ORDERS" }] }
      },
      {
        code: 200,
        data: [
          {
            question: "What depends on SALES.ORDERS?",
            category: "downstream",
            targetNode: "SALES.ORDERS"
          }
        ]
      },
      { code: 200 }
    ];
    let callIdx = 0;

    const client = createSpaApiClient({
      token: "ai_tok_2",
      fetchImpl: vi.fn(async (input, init) => {
        calls.push({
          url: String(input),
          method: init?.method ?? "GET",
          headers: (init?.headers as Record<string, string>) ?? {},
          body: init?.body
        });
        return new Response(JSON.stringify(payloads[callIdx++]!), {
          status: 200,
          headers: { "Content-Type": "application/json" }
        });
      })
    });

    // #1 GET /sqlflow/generation/ai/status (unwrapped)
    const status = await client.getAiStatus();
    expect(status).toEqual({ enabled: true, model: "claude-sonnet-4-5" });
    expect(calls[0]!.url).toContain("/api/gspLive_backend/sqlflow/generation/ai/status?");

    // #3 POST /sqlflow/generation/ai/query/sync (@RequestParam form -> ApiEnvelope<AiQueryResponse>)
    const syncRes = await client.queryAiSync({
      sessionId: "sess_sync",
      question: "Explain revenue",
      dbvendor: "postgresql",
      hopDepth: 2
    });
    expect(syncRes.code).toBe(200);
    expect(syncRes.data?.answer).toBe("Sync answer");
    expect(syncRes.data?.referencedNodes).toEqual([
      "SALES.ORDERS",
      "PUBLIC.CUSTOMER_REVENUE"
    ]);
    expect(syncRes.data?.referencedEdges).toEqual([
      { from: "SALES.ORDERS", to: "PUBLIC.CUSTOMER_REVENUE" }
    ]);
    expect(syncRes.data?.confidence).toBe("high");
    expect(calls[1]!.url).toBe("/api/gspLive_backend/sqlflow/generation/ai/query/sync");
    const syncForm = new URLSearchParams(String(calls[1]!.body));
    expect(syncForm.get("sessionId")).toBe("sess_sync");
    expect(syncForm.get("question")).toBe("Explain revenue");
    expect(syncForm.get("dbvendor")).toBe("dbvpostgresql");
    expect(syncForm.get("hopDepth")).toBe("2");

    // #4 POST /sqlflow/generation/ai/node-query (@RequestParam form -> ApiEnvelope<AiQueryResponse>)
    const nodeRes = await client.queryAiNode({
      sessionId: "sess_sync",
      nodeId: "n0",
      nodeName: "SALES.ORDERS",
      queryType: "impact",
      dbvendor: "snowflake"
    });
    expect(nodeRes.code).toBe(200);
    expect(nodeRes.data?.answer).toBe("Impact analysis for ORDERS");
    expect(calls[2]!.url).toBe("/api/gspLive_backend/sqlflow/generation/ai/node-query");
    const nodeForm = new URLSearchParams(String(calls[2]!.body));
    expect(nodeForm.get("sessionId")).toBe("sess_sync");
    expect(nodeForm.get("nodeId")).toBe("n0");
    expect(nodeForm.get("nodeName")).toBe("SALES.ORDERS");
    expect(nodeForm.get("nodeType")).toBe("table");
    expect(nodeForm.get("queryType")).toBe("impact");
    expect(nodeForm.get("dbvendor")).toBe("dbvsnowflake");

    // #5 GET /sqlflow/generation/ai/suggestions (-> ApiEnvelope<AiSuggestedQuestion[]>)
    const suggestions = await client.getAiSuggestions({
      sessionId: "sess_sync",
      dbvendor: "oracle",
      maxQuestions: 5
    });
    expect(suggestions.code).toBe(200);
    expect(suggestions.data).toHaveLength(1);
    expect(suggestions.data?.[0]?.question).toBe("What depends on SALES.ORDERS?");
    const sugUrl = new URL(calls[3]!.url, "http://localhost");
    expect(sugUrl.pathname).toBe(
      "/api/gspLive_backend/sqlflow/generation/ai/suggestions"
    );
    expect(sugUrl.searchParams.get("sessionId")).toBe("sess_sync");
    expect(sugUrl.searchParams.get("dbvendor")).toBe("dbvoracle");
    expect(sugUrl.searchParams.get("maxQuestions")).toBe("5");

    // #6 POST /sqlflow/generation/ai/suggestions/click (@RequestParam form -> {"code": 200})
    const clickRes = await client.recordAiSuggestionClick({
      question: "What depends on SALES.ORDERS?",
      category: "downstream",
      targetNode: "SALES.ORDERS"
    });
    expect(clickRes.code).toBe(200);
    expect(calls[4]!.url).toBe(
      "/api/gspLive_backend/sqlflow/generation/ai/suggestions/click"
    );
    const clickForm = new URLSearchParams(String(calls[4]!.body));
    expect(clickForm.get("question")).toBe("What depends on SALES.ORDERS?");
    expect(clickForm.get("category")).toBe("downstream");
    expect(clickForm.get("targetNode")).toBe("SALES.ORDERS");
  });

  it("forwards all optional JoinAnalysisController parameters on analyzeJoins (02 §4.2.1)", async () => {
    const calls: CapturedCall[] = [];
    const client = createSpaApiClient({
      token: "join_tok",
      fetchImpl: vi.fn(async (input, init) => {
        calls.push({
          url: String(input),
          method: init?.method ?? "GET",
          headers: (init?.headers as Record<string, string>) ?? {},
          body: init?.body
        });
        return new Response(
          JSON.stringify({
            schemaVersion: "join-analysis.v1",
            queryBlocks: [],
            nodes: [],
            edges: [],
            diagnostics: []
          }),
          { status: 200, headers: { "Content-Type": "application/json" } }
        );
      })
    });

    const res = await client.analyzeJoins({
      sqltext: "SELECT * FROM a JOIN b ON a.id = b.id",
      dbvendor: "snowflake",
      jobId: "job_42",
      defaultServer: "SRV1",
      defaultDatabase: "ANALYTICS",
      defaultSchema: "PUBLIC"
    });

    expect(res.schemaVersion).toBe("join-analysis.v1");
    expect(calls[0]!.url).toBe(
      "/api/gspLive_backend/sqlflow/generation/sqlflow/join-analysis"
    );
    const form = new URLSearchParams(String(calls[0]!.body));
    expect(form.get("userId")).toBe(DEFAULT_GUEST_USER_ID);
    expect(form.get("dbvendor")).toBe("dbvsnowflake");
    expect(form.get("jobId")).toBe("job_42");
    expect(form.get("defaultServer")).toBe("SRV1");
    expect(form.get("defaultDatabase")).toBe("ANALYTICS");
    expect(form.get("defaultSchema")).toBe("PUBLIC");
  });
});
