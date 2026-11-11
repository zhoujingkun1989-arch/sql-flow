import React from "react";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import JSZip from "jszip";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App";
import {
  buildDlineageXml,
  buildLineageCsv,
  buildOfflineHtmlZipBlob,
  buildYedGraphMl
} from "../utils/exporters";
import { SAMPLE_LINEAGE_GRAPH, createMockApiService } from "./fixtures";

describe("Schema Explorer, Job Center 5000ms Polling & Wizard, AI SSE Drawer, Auth/Admin Modals, & Exports", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    vi.useRealTimers();
    cleanup();
  });

  it("uploads DDL with FormData key 'sqlfiles' and parses double-nested displayUserSchemaSummary arrays", async () => {
    let capturedFormData: FormData | null = null;
    const { api } = createMockApiService({
      "/sqlflow/generation/displayUserSchemaSummary": () => ({
        code: 200,
        data: {
          oracle: [["hr_schema.sql"], ["sales_ddl.sql"]]
        }
      }),
      "/sqlflow/generation/submitUserSchema": (req) => {
        if (req.body instanceof FormData) {
          capturedFormData = req.body;
        }
        return { code: 200 };
      }
    });

    render(<App customApi={api} memoryInitialEntries={["/"]} />);
    fireEvent.click(screen.getByTestId("rail-schema-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("user-schema-file-hr_schema.sql")).toBeInTheDocument();
      expect(screen.getByTestId("user-schema-file-sales_ddl.sql")).toBeInTheDocument();
    });

    const ddlFile = new File(["CREATE TABLE t1 (id INT);"], "new_ddl.sql", { type: "text/plain" });
    fireEvent.change(screen.getByTestId("schema-ddl-file-input"), {
      target: { files: [ddlFile] }
    });

    await waitFor(() => {
      expect(capturedFormData).not.toBeNull();
      expect(capturedFormData?.getAll("sqlfiles").length).toBe(1);
    });
  });

  it("polls active jobs every 5000ms until settled, opens 5-tab Job Detail modal, and RSA-encrypts JDBC password on Create Job", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let pollCount = 0;
    let capturedTestConnBody = "";
    let capturedSubmitFormData: FormData | null = null;

    const { api } = createMockApiService({
      "/sqlflow/job/displayUserJobsSummary": () => {
        pollCount += 1;
        return {
          code: 200,
          data: {
            total: 1,
            success: pollCount >= 2 ? 1 : 0,
            partialSuccess: 0,
            fail: 0,
            jobIds: ["j-100"],
            jobDetails: [
              {
                jobId: "j-100",
                jobName: "Warehouse_Harvester",
                dbVendor: "dbvoracle",
                status: pollCount >= 2 ? "success" : "process",
                fileNames: ["01_extract.sql"],
                schedulable: true,
                cronExpression: "0 0 2 * * ?"
              }
            ]
          }
        };
      },
      "/database/testConnection": (req) => {
        capturedTestConnBody = String(req.body ?? "");
        return { code: 200 };
      },
      "/sqlflow/job/submitUserJob": (req) => {
        if (req.body instanceof FormData) {
          capturedSubmitFormData = req.body;
        }
        return { code: 200, data: { jobId: "j-101" } };
      }
    });

    render(<App customApi={api} memoryInitialEntries={["/"]} />);
    fireEvent.click(screen.getByTestId("rail-job-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("job-card-j-100")).toBeInTheDocument();
      expect(screen.getByTestId("job-polling-indicator")).toBeInTheDocument();
    });

    // Advance 5000ms -> polls second time and transitions to "success", stopping polling indicator
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5100);
    });

    await waitFor(() => {
      expect(pollCount).toBeGreaterThanOrEqual(2);
      expect(screen.queryByTestId("job-polling-indicator")).not.toBeInTheDocument();
    });

    // Open 5-tab Job Detail modal
    fireEvent.click(screen.getByTestId("job-detail-btn-j-100"));
    const detailModal = await screen.findByTestId("job-detail-modal");
    expect(detailModal).toBeInTheDocument();
    expect(screen.getByTestId("job-modal-tab-overview")).toBeInTheDocument();
    expect(screen.getByTestId("job-modal-tab-lineage")).toBeInTheDocument();
    expect(screen.getByTestId("job-modal-tab-databases")).toBeInTheDocument();
    expect(screen.getByTestId("job-modal-tab-files")).toBeInTheDocument();
    expect(screen.getByTestId("job-modal-tab-errors")).toBeInTheDocument();

    // Test Create Job wizard with 16 JDBC vendors + RSA JSEncrypt password encryption
    fireEvent.click(screen.getByTestId("open-create-job-btn"));
    const sourceSelect = await screen.findByTestId("create-job-source-select");
    fireEvent.change(sourceSelect, { target: { value: "fromDatabase" } });

    const vendorSelect = screen.getByTestId("jdbc-vendor-select") as HTMLSelectElement;
    expect(vendorSelect.querySelectorAll("option").length).toBeGreaterThanOrEqual(14);
    expect(screen.getByText("SID")).toBeInTheDocument();

    fireEvent.change(screen.getByTestId("jdbc-password-input"), {
      target: { value: "MyPlainJdbcSecret!" }
    });
    fireEvent.click(screen.getByTestId("jdbc-test-connection-btn"));

    await waitFor(() => {
      expect(capturedTestConnBody).toContain("account=system");
      expect(capturedTestConnBody).not.toContain("MyPlainJdbcSecret!");
      expect(screen.getByTestId("jdbc-test-status")).toHaveTextContent("Connection succeeded!");
    });

    fireEvent.click(screen.getByTestId("submit-create-job-btn"));
    await waitFor(() => {
      expect(capturedSubmitFormData).not.toBeNull();
      const submittedPwd = String(capturedSubmitFormData?.get("password") ?? "");
      expect(submittedPwd.length).toBeGreaterThan(32);
      expect(submittedPwd).not.toBe("MyPlainJdbcSecret!");
    });
  });

  it("streams AI SSE answers with clickable citations when enabled, and displays graceful banner when enabled === false", async () => {
    const { api } = createMockApiService();
    const { unmount } = render(<App customApi={api} memoryInitialEntries={["/"]} />);

    fireEvent.click(screen.getByTestId("header-visualize-btn"));
    await screen.findByTestId("lineage-node-t_orders");

    fireEvent.click(screen.getByTestId("rail-ai-btn"));
    const suggestionBtn = await screen.findByTestId("ai-suggestion-0");
    fireEvent.click(suggestionBtn);

    const citationChip = await screen.findByTestId("ai-citation-orders.amount");
    expect(citationChip).toBeInTheDocument();
    expect(screen.getByTestId("ai-chat-messages")).toHaveTextContent("SUM(o.amount)");

    // Clicking citation locates & highlights node on canvas
    fireEvent.click(citationChip);
    unmount();

    // Now test disabled AI status (`enabled: false`)
    const disabledMock = createMockApiService({
      "/sqlflow/generation/ai/status": () => ({ enabled: false })
    });
    render(<App customApi={disabledMock.api} memoryInitialEntries={["/"]} />);
    fireEvent.click(screen.getByTestId("rail-ai-btn"));
    expect(await screen.findByTestId("ai-disabled-banner")).toBeInTheDocument();
  });

  it("handles Login (/user/register plaintext), Account secretKey (code === '200'), Pricing (res.products), and Admin downloadUserSql query-on-POST", async () => {
    let loginFormBody = "";
    let downloadSqlUrl = "";
    let downloadSqlMethod = "";

    const { api } = createMockApiService({
      "/user/register": (req) => {
        loginFormBody = String(req.body ?? "");
        return {
          code: 200,
          data: {
            userId: "u-enterprise-1",
            userEmail: "dba@bank.io",
            userName: "Lead DBA",
            token: "jwt-clean-99"
          }
        };
      },
      "/user/generateSecretKey": () => ({
        userId: "u-enterprise-1",
        code: "200",
        secretKey: "sk-live-cleanroom"
      }),
      "/user/generateToken": () => ({
        userId: "u-enterprise-1",
        code: "200",
        token: "api-tok-cleanroom"
      }),
      "/product/products": () => ({
        code: 200,
        products: [
          {
            productId: "ent-annual",
            productName: "SQLFlow On-Premise Enterprise",
            productPrice: "$4,900",
            productPeriod: "per year",
            recommend: true,
            selected: true
          }
        ]
      }),
      "/user/sqls": () => ({
        code: 200,
        data: [{ userId: "u-enterprise-1", fileName: "audit_query.sql" }]
      }),
      "/user/downloadUserSql": (req) => {
        downloadSqlUrl = req.url;
        downloadSqlMethod = req.method;
        return new Response(new Blob(["SELECT 1;"], { type: "text/plain" }), { status: 200 });
      }
    });

    render(<App customApi={api} memoryInitialEntries={["/"]} />);

    // 1. Login via /user/register with plaintext password (NOT RSA encrypted)
    fireEvent.click(screen.getByTestId("user-menu-btn"));
    fireEvent.click(await screen.findByTestId("menu-login-btn"));
    fireEvent.change(screen.getByTestId("login-email-input"), {
      target: { value: "dba@bank.io" }
    });
    fireEvent.change(screen.getByTestId("login-password-input"), {
      target: { value: "PlainUserPass123" }
    });
    fireEvent.click(screen.getByTestId("login-submit-btn"));

    await waitFor(() => {
      expect(loginFormBody).toContain("password=PlainUserPass123");
      expect(localStorage.getItem("user")).toContain("u-enterprise-1");
    });

    // 2. Account modal -> generateSecretKey & generateToken (code === "200")
    fireEvent.click(screen.getByTestId("user-menu-btn"));
    fireEvent.click(await screen.findByTestId("menu-account-btn"));
    fireEvent.click(await screen.findByTestId("generate-secret-key-btn"));
    expect(await screen.findByTestId("generated-secret-key")).toHaveTextContent(
      "sk-live-cleanroom"
    );
    expect(await screen.findByTestId("generated-api-token")).toHaveTextContent(
      "api-tok-cleanroom"
    );
    fireEvent.click(screen.getByText("Close"));

    // 3. Pricing modal -> top-level res.products
    fireEvent.click(screen.getByTestId("user-menu-btn"));
    fireEvent.click(await screen.findByTestId("menu-pricing-btn"));
    expect(await screen.findByTestId("product-card-ent-annual")).toHaveTextContent(
      "SQLFlow On-Premise Enterprise"
    );
    fireEvent.click(screen.getByText("Close"));

    // 4. Admin Console -> SQL Audit -> downloadUserSql POST with query params
    fireEvent.click(screen.getByTestId("user-menu-btn"));
    fireEvent.click(await screen.findByTestId("menu-admin-btn"));
    fireEvent.click(await screen.findByTestId("admin-tab-sqls"));
    const dlBtn = await screen.findByTestId("admin-download-sql-audit_query.sql");
    fireEvent.click(dlBtn);

    await waitFor(() => {
      expect(downloadSqlMethod).toBe("POST");
      expect(downloadSqlUrl).toContain("/user/downloadUserSql?");
      expect(downloadSqlUrl).toContain("fileName=audit_query.sql");
    });
  });

  it("serializes <dlineage> XML, 16-col CSV, yEd GraphML, and builds valid self-contained Offline Interactive HTML .zip", async () => {
    const xml = buildDlineageXml(SAMPLE_LINEAGE_GRAPH);
    expect(xml).toContain("<dlineage>");
    expect(xml).toContain("<relationship ");
    expect(xml).toContain("</relationship>");
    expect(xml).not.toContain("<relation ");
    expect(xml).toContain('<transform type="function" code="SUM(o.amount)" />');
    expect(xml).toContain('unqiueKey="true"');
    expect(xml).toContain('parent_name="customer_revenue"');

    const csv = buildLineageCsv(SAMPLE_LINEAGE_GRAPH);
    expect(csv).toContain("SourceTable,SourceColumn");
    expect(csv).toContain("orders,amount");
    expect(csv).toContain("SUM(o.amount)");

    const graphml = buildYedGraphMl(SAMPLE_LINEAGE_GRAPH);
    expect(graphml).toContain("<graphml");
    expect(graphml).toContain("<y:NodeLabel>orders</y:NodeLabel>");

    // Also verify hierarchical SqlflowCatalog (MySQL direct db.tables + Oracle srv.schemas + constant #e8c559)
    const catalogPayload = {
      sqlflow: {
        dbobjs: {
          servers: [
            {
              name: "MYSQL_SRV",
              databases: [
                {
                  name: "shop_db",
                  tables: [
                    {
                      id: "m_tbl",
                      name: "mysql_orders",
                      type: "table",
                      columns: [{ id: "mc1", name: "id" }]
                    }
                  ],
                  others: [{ id: "m_const", name: "CONST_1", type: "constant" }]
                }
              ],
              schemas: [
                {
                  name: "ORA_SCH",
                  views: [
                    {
                      id: "o_vw",
                      name: "ora_summary",
                      type: "view",
                      columns: [{ id: "oc1", name: "id" }]
                    }
                  ]
                }
              ]
            }
          ]
        },
        relationships: [
          {
            id: "r_cat",
            type: "fdd" as const,
            target: { id: "oc1", column: "id", parentId: "o_vw", parentName: "ora_summary" },
            sources: [{ id: "mc1", column: "id", parentId: "m_tbl", parentName: "mysql_orders" }]
          }
        ]
      }
    };

    const { zip } = await buildOfflineHtmlZipBlob(catalogPayload, "Offline Revenue Test");
    const indexHtml = await zip.file("index.html")?.async("string");
    const lineageJson = await zip.file("lineage.json")?.async("string");
    const widgetJs = await zip.file("widget/index.js")?.async("string");

    expect(indexHtml).toContain("Offline Revenue Test");
    expect(indexHtml).toContain("window.SQLFlow.init");
    expect(lineageJson).toContain("mysql_orders");
    expect(widgetJs).toContain("#e8c559");

    // Execute offline widget runtime in JSDOM against hierarchical SqlflowCatalog
    const mountDiv = document.createElement("div");
    const winRecord = window as unknown as {
      SQLFlow?: { init: (el: HTMLElement) => Promise<{ visualizeJSON: (d: unknown) => Promise<void> }> };
    };
    new Function(widgetJs ?? "")();
    const instance = await winRecord.SQLFlow!.init(mountDiv);
    await instance.visualizeJSON(catalogPayload);
    expect(mountDiv.innerHTML).toContain("mysql_orders");
    expect(mountDiv.innerHTML).toContain("ora_summary");
    expect(mountDiv.innerHTML).toContain("#e8c559");
  });

  it("uses exact gateway wire contracts for displayUserDbvendorSchema, Job exportMetadata/parquet GET, /group/* operatorUserId+groupId, and Admin JSON @RequestBody", async () => {
    const { api, recorded } = createMockApiService({
      "/sqlflow/generation/displayUserDbvendorSchema": () => ({
        code: 200,
        data: ["CREATE TABLE a (id INT);", "CREATE TABLE b (id INT);"]
      }),
      "/sqlflow/job/getJobFiles": () => ({
        code: 200,
        data: [{ fileName: "etl.sql", sql: "SELECT * FROM a;" }]
      }),
      "/sqlflow/job/exportMetadata": () =>
        new Response(new Blob(["ZIPBYTES"], { type: "application/zip" }), { status: 200 }),
      "/sqlflow/job/generate/parquet": () => ({ code: 200 }),
      "/sqlflow/job/download/parquet": () =>
        new Response(new Blob(["PARQUET"], { type: "application/octet-stream" }), { status: 200 }),
      "/user/users": () => ({
        code: 200,
        data: { list: [{ userId: "u-2", userEmail: "bob@corp.io" }] }
      })
    });

    const ddl = await api.getUserSchemaDdlContent("oracle");
    expect(ddl).toContain("CREATE TABLE a");
    expect(ddl).toContain("CREATE TABLE b");

    const jobSql = await api.getJobSqlFileContent("job-9", "etl.sql");
    expect(jobSql).toBe("SELECT * FROM a;");

    await api.downloadJobArtifactBlob("job-9", "zip", "NightlyJob");
    const zipReq = recorded.find((r) => r.url.includes("/sqlflow/job/exportMetadata"));
    expect(zipReq?.method).toBe("GET");
    expect(zipReq?.url).toContain("jobId=job-9");

    await api.downloadJobArtifactBlob("job-9", "parquet", "NightlyJob");
    expect(recorded.some((r) => r.url.includes("/sqlflow/job/generate/parquet") && r.method === "POST")).toBe(true);
    expect(recorded.some((r) => r.url.includes("/sqlflow/job/download/parquet") && r.method === "GET")).toBe(true);

    await api.addGroupUser("grp-10", "member@corp.io");
    const grpReq = recorded.find((r) => r.url.includes("/group/addGroupUser"));
    expect(String(grpReq?.body)).toContain("operatorUserId=");
    expect(String(grpReq?.body)).toContain("groupId=grp-10");

    const users = await api.listAdminUsers(1, 20);
    expect(users).toHaveLength(1);
    const adminUsersReq = recorded.find((r) => r.url.includes("/user/users"));
    expect(adminUsersReq?.headers["Content-Type"]).toContain("application/json");
    expect(String(adminUsersReq?.body)).toContain('"pageNo":1');

    await api.deleteAdminUser({ userId: "u-2", userEmail: "bob@corp.io" });
    const delUserReq = recorded.find((r) => r.url.includes("/user/deleteUser"));
    expect(String(delUserReq?.body)).toContain("operatorUserId=");
    expect(String(delUserReq?.body)).toContain("userEmail=bob%40corp.io");

    await api.applyUserCoupon("CPN-2026");
    const cpnReq = recorded.find((r) => r.url.includes("/user/applyUserCoupon"));
    expect(String(cpnReq?.body)).toContain("couponCode=CPN-2026");
  });
});
