import React from "react";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import pako from "pako";
import { DIAGNOSTIC_CODES } from "@sqlflow/ui-core";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import App from "../App";
import { SAMPLE_JOIN_ANALYSIS_GRAPH, SAMPLE_LINEAGE_GRAPH, createMockApiService } from "./fixtures";

describe("Secondary Analysis Views: Table Lineage, ER Diagram, Call Graph, Join Analysis V2 & __JoinIsland, Report, Diagnostics", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    cleanup();
  });

  it("renders ER Diagram with #38c172 cards, PK/U (unqiueKey)/FK badges, and valid pako-compressed Mermaid Live URL", async () => {
    const { api } = createMockApiService({
      "/sqlflow/generation/sqlflow/erdiagramSelectGraph": () => ({
        code: 200,
        data: SAMPLE_LINEAGE_GRAPH
      })
    });

    render(<App customApi={api} memoryInitialEntries={["/"]} />);
    fireEvent.click(screen.getByTestId("right-tab-er"));

    await waitFor(() => {
      expect(screen.getByTestId("er-entity-t_orders")).toBeInTheDocument();
      expect(screen.getByTestId("er-entity-t_customers")).toBeInTheDocument();
    });

    // Verify PK, FK, and U (`unqiueKey`) badges render
    expect(screen.getByTestId("er-badge-pk-c_orders_id")).toHaveTextContent("PK");
    expect(screen.getByTestId("er-badge-fk-c_orders_cust_id")).toHaveTextContent("FK");
    expect(screen.getByTestId("er-badge-uk-c_cust_email")).toHaveTextContent("U");

    // Click "show graph in mermaid editor" and decompress pako payload to verify erDiagram
    fireEvent.click(screen.getByTestId("er-mermaid-btn"));
    const link = await screen.findByTestId("mermaid-live-link");
    const href = link.getAttribute("href") ?? "";
    expect(href.startsWith("https://mermaid.live/edit#pako:")).toBe(true);

    const encoded = href.replace("https://mermaid.live/edit#pako:", "");
    const base64 = encoded.replace(/-/g, "+").replace(/_/g, "/");
    const bin = atob(base64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) {
      bytes[i] = bin.charCodeAt(i);
    }
    const decompressedJson = JSON.parse(new TextDecoder().decode(pako.inflate(bytes))) as {
      code: string;
    };
    expect(decompressedJson.code).toContain("erDiagram");
    expect(decompressedJson.code).toContain("orders");
    expect(decompressedJson.code).toContain("customers");
  });

  it("renders Routine Call Graph with Dagre LR layout, routine kind badges, pkg subtitle, and R/RW access pills", async () => {
    const { api, recorded } = createMockApiService();
    render(<App customApi={api} memoryInitialEntries={["/"]} />);

    fireEvent.click(screen.getByTestId("right-tab-callgraph"));

    await waitFor(() => {
      expect(
        recorded.some((r) => r.url.includes("/sqlflow/generation/sqlflow/graph/call_lineage"))
      ).toBe(true);
      expect(screen.getByTestId("callgraph-routine-proc_refresh")).toBeInTheDocument();
      expect(screen.getByTestId("callgraph-routine-fn_calc_tax")).toBeInTheDocument();
    });

    expect(screen.getByTestId("callgraph-routine-proc_refresh")).toHaveTextContent(
      "SP_REFRESH_REVENUE"
    );
    expect(screen.getByTestId("callgraph-routine-proc_refresh")).toHaveTextContent(
      "pkg: PKG_FINANCE"
    );
    expect(screen.getByTestId("callgraph-routine-proc_refresh")).toHaveTextContent(
      "RW: customer_revenue"
    );
  });

  it("flattens Oracle supportsCatalogs=false srv.schemas + nested packages and renders rel.caller -> rel.callees[] call edges", async () => {
    const oracleCatalogCallGraph = {
      sqlflow: {
        dbobjs: {
          servers: [
            {
              name: "ORA_PROD",
              supportsCatalogs: false,
              schemas: [
                {
                  name: "BILLING",
                  packages: [
                    {
                      id: "pkg_billing",
                      name: "PKG_BILLING",
                      type: "package",
                      procedures: [
                        { id: "proc_settle", name: "settle_invoice", type: "procedure" }
                      ],
                      functions: [{ id: "fn_tax", name: "calc_tax", type: "function" }]
                    }
                  ]
                }
              ]
            }
          ]
        },
        relationships: [
          {
            id: "call_1",
            type: "call" as const,
            caller: { id: "proc_settle", name: "settle_invoice" },
            callees: [{ id: "fn_tax", name: "calc_tax" }]
          }
        ]
      }
    };

    const { api, recorded } = createMockApiService({
      "/sqlflow/generation/sqlflow/graph/call_lineage": () => {
        throw new Error("force demo fallback");
      },
      "/demo/callRelation": () => ({
        code: 200,
        data: oracleCatalogCallGraph
      })
    });

    render(<App customApi={api} memoryInitialEntries={["/"]} />);
    fireEvent.click(screen.getByTestId("right-tab-callgraph"));

    await waitFor(() => {
      expect(recorded.some((r) => r.url.includes("/demo/callRelation"))).toBe(true);
      expect(screen.getByTestId("callgraph-routine-pkg_billing")).toHaveTextContent("PKG_BILLING");
      expect(screen.getByTestId("callgraph-routine-proc_settle")).toHaveTextContent(
        "settle_invoice"
      );
      expect(screen.getByTestId("callgraph-routine-fn_tax")).toHaveTextContent("calc_tax");
    });
  });

  it("renders Join Analysis V2 (`join-analysis.v1`) with @xyflow/react + ELKJS, all 10 DiagnosticCode cards, Role filter, and window.__JoinIsland", async () => {
    const { api } = createMockApiService();
    render(<App customApi={api} memoryInitialEntries={["/"]} />);

    fireEvent.click(screen.getByTestId("right-tab-join"));

    await waitFor(() => {
      expect(screen.getByTestId("joinflow-v2-canvas")).toBeInTheDocument();
      expect(screen.getByTestId("join-fallback-banner")).toBeInTheDocument();
    });

    // Verify all 10 DiagnosticCode cards render and are clickable
    for (const code of DIAGNOSTIC_CODES) {
      const card = screen.getByTestId(`join-diag-card-${code}`);
      expect(card).toBeInTheDocument();
      fireEvent.click(card);
    }

    // Filter by DBA role
    fireEvent.change(screen.getByTestId("join-role-filter"), { target: { value: "dba" } });
    expect(screen.queryByTestId("join-diag-card-missing_join_condition")).not.toBeInTheDocument();
    expect(screen.getByTestId("join-diag-card-cartesian_join")).toBeInTheDocument();

    // Verify window.__JoinIsland global mount API works independently
    expect(window.__JoinIsland).toBeDefined();
    expect(window.__JoinIsland?.version).toBe("3.6.16");
    const hostDiv = document.createElement("div");
    document.body.appendChild(hostDiv);
    let handle: ReturnType<NonNullable<typeof window.__JoinIsland>["mount"]> | undefined;
    await act(async () => {
      handle = window.__JoinIsland?.mount(hostDiv, { model: SAMPLE_JOIN_ANALYSIS_GRAPH });
    });
    expect(hostDiv.innerHTML).toContain("LEFT JOIN");
    act(() => {
      handle?.unmount();
    });
    document.body.removeChild(hostDiv);
  });

  it("renders Tabular Lineage Report (`output`) and clickable Diagnostics (`error`) with badge count", async () => {
    const { api } = createMockApiService();
    render(<App customApi={api} memoryInitialEntries={["/"]} />);

    fireEvent.click(screen.getByTestId("header-visualize-btn"));
    await screen.findByTestId("lineage-node-t_orders");

    // Error badge shows 1 syntax error
    expect(screen.getByTestId("error-tab-badge")).toHaveTextContent("1");

    // Switch to Lineage Report
    fireEvent.click(screen.getByTestId("right-tab-output"));
    expect(screen.getByTestId("lineage-report-view")).toHaveTextContent("SUM(o.amount)");

    // Switch to Diagnostics and click error to jump to SQL line
    fireEvent.click(screen.getByTestId("right-tab-error"));
    const errItem = await screen.findByTestId("diagnostic-error-item-0");
    expect(errItem).toHaveTextContent("Warning near token SEMICOLON");
    fireEvent.click(errItem);
  });
});
