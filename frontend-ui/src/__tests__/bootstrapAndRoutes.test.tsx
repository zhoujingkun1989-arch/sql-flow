import React from "react";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import App from "../App";
import { SAMPLE_LINEAGE_GRAPH, createMockApiService } from "./fixtures";

describe("Bootstrap Sequence, HashRouter Routes, & HTTP 200 Business Interceptors", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    cleanup();
  });

  it("executes startup bootstrap sequence: config.private.json -> /version -> /license -> /user/info + /user/getUserSetting", async () => {
    const { api, recorded } = createMockApiService();
    render(<App customApi={api} memoryInitialEntries={["/"]} />);

    await waitFor(() => {
      expect(recorded.some((r) => r.url.includes("config.private.json"))).toBe(true);
      expect(recorded.some((r) => r.url.includes("/version"))).toBe(true);
      expect(recorded.some((r) => r.url.includes("/license"))).toBe(true);
      expect(recorded.some((r) => r.url.includes("/user/info"))).toBe(true);
      expect(recorded.some((r) => r.url.includes("/user/getUserSetting"))).toBe(true);
    });

    expect(screen.getByTestId("top-header-bar")).toBeInTheDocument();
    expect(screen.getByTestId("left-icon-rail")).toBeInTheDocument();
    expect(api.client.getUserId()).toBe("gudu|0123456789");
  });

  it("applies login.disable + login.userId override and hides regular.job rail buttons when disabled in /version", async () => {
    const { api } = createMockApiService({
      "/version": () => ({
        version: {
          gsp: { version: "3.0.9.5", "release.date": "2026-01-01" },
          backend: { version: "8.2.2.15", "release.date": "2026-01-01" },
          support: {
            "password.security.validate": false,
            "regular.job": false,
            "login.disable": true,
            "login.userId": "sso_enterprise_admin"
          }
        }
      })
    });

    render(<App customApi={api} memoryInitialEntries={["/"]} />);

    await waitFor(() => {
      expect(api.client.getUserId()).toBe("sso_enterprise_admin");
    });
    expect(screen.queryByTestId("rail-job-btn")).not.toBeInTheDocument();
    expect(screen.getByTestId("user-menu-btn")).toHaveTextContent("sso_enterprise_admin");
  });

  it("opens Login modal on /?show=login and handles #tablename=... deep link", async () => {
    const { api, recorded } = createMockApiService();
    render(<App customApi={api} memoryInitialEntries={["/?show=login&tablename=orders"]} />);

    await waitFor(() => {
      expect(screen.getByTestId("login-modal")).toBeInTheDocument();
      expect(recorded.some((r) => r.url.includes("selectedgraph"))).toBe(true);
    });
  });

  it("loads shared lineage snapshot on /share?shareId=sh-900", async () => {
    const { api, recorded } = createMockApiService();
    render(<App customApi={api} memoryInitialEntries={["/share?shareId=sh-900"]} />);

    await waitFor(() => {
      expect(screen.getByTestId("share-route-banner")).toHaveTextContent("sh-900");
      expect(recorded.some((r) => r.url.includes("/graph/share/sh-900"))).toBe(true);
    });
  });

  it("loads permalink SQL & dialect on /p/:permalinkId", async () => {
    const { api, recorded } = createMockApiService({
      "/permalink/getPermalinkData": () => ({
        code: 200,
        data: {
          permalinkId: "perm-77",
          dbvendor: "dbvsnowflake",
          sqltext: "SELECT a FROM snow_tbl;",
          graph: SAMPLE_LINEAGE_GRAPH
        }
      })
    });

    render(<App customApi={api} memoryInitialEntries={["/p/perm-77"]} />);

    await waitFor(() => {
      expect(screen.getByTestId("permalink-route-banner")).toHaveTextContent("perm-77");
      expect(recorded.some((r) => r.url.includes("/permalink/getPermalinkData"))).toBe(true);
      expect((screen.getByTestId("dialect-selector") as HTMLSelectElement).value).toBe(
        "snowflake"
      );
    });
  });

  it("loads latest job graph on /job/latest consuming top-level res.job, res.jobId, res.sessionId, res.data", async () => {
    const { api } = createMockApiService({
      "/sqlflow/job/displayUserLatestJobGraph": () => ({
        code: 200,
        jobId: "job-latest-1",
        sessionId: "sess-latest-1",
        job: {
          jobId: "job-latest-1",
          jobName: "Nightly_Warehouse_ETL",
          dbVendor: "dbvpostgresql",
          status: "success"
        },
        data: SAMPLE_LINEAGE_GRAPH
      })
    });

    render(<App customApi={api} memoryInitialEntries={["/job/latest?view=overview"]} />);

    await waitFor(() => {
      expect(screen.getByTestId("latest-job-route-banner")).toHaveTextContent(
        "Nightly_Warehouse_ETL"
      );
      expect(screen.getByTestId("job-detail-modal")).toBeInTheDocument();
    });
  });

  it("intercepts HTTP 200 business code 401 (clears localStorage['user']) and 412 (opens License modal with machine.code)", async () => {
    localStorage.setItem(
      "user",
      JSON.stringify({ userId: "alice", token: "expired-tok", userEmail: "alice@corp.io" })
    );

    let mode: "401" | "412" = "401";
    const { api } = createMockApiService({
      "/sqlflow/generation/sqlflow/graph": () =>
        mode === "401"
          ? { code: 401, error: "Invalid user or token" }
          : { code: 412, error: "License expired", "machine.code": "HW-ABC-777" }
    });

    render(<App customApi={api} memoryInitialEntries={["/"]} />);

    await waitFor(() => expect(screen.getByTestId("header-visualize-btn")).toBeInTheDocument());
    fireEvent.click(screen.getByTestId("header-visualize-btn"));

    await waitFor(() => {
      expect(localStorage.getItem("user")).toBeNull();
      expect(screen.getByTestId("business-error-banner")).toHaveTextContent("Code 401");
    });

    mode = "412";
    fireEvent.click(screen.getByTestId("header-visualize-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("business-error-banner")).toHaveTextContent("HW-ABC-777");
      expect(screen.getByTestId("license-modal")).toBeInTheDocument();
    });
  });

  it("renders zero lock icons when Supported databases is 'All 48 supported SQL dialects' and marks unlicensed dialects when restricted", async () => {
    const { api: openApi } = createMockApiService();
    render(<App customApi={openApi} memoryInitialEntries={["/"]} />);

    await waitFor(() => {
      const select = screen.getByTestId("dialect-selector") as HTMLSelectElement;
      const options = Array.from(select.querySelectorAll("option"));
      expect(options.length).toBe(39);
      expect(options.some((opt) => opt.textContent?.includes("🔒"))).toBe(false);
    });

    cleanup();

    const { api: restrictedApi } = createMockApiService({
      "/license": () => ({
        code: 200,
        data: {
          "Product name": "Gudu SQLFlow",
          "Supported databases": "Oracle, SQL Server"
        }
      })
    });
    render(<App customApi={restrictedApi} memoryInitialEntries={["/"]} />);

    await waitFor(() => {
      const select = screen.getByTestId("dialect-selector") as HTMLSelectElement;
      const options = Array.from(select.querySelectorAll("option"));
      const byValue = new Map(options.map((o) => [o.value, o.textContent ?? ""]));
      expect(byValue.get("oracle")).not.toContain("🔒");
      expect(byValue.get("mssql")).not.toContain("🔒");
      expect(byValue.get("mysql")).toContain("🔒");
    });
  });
});
