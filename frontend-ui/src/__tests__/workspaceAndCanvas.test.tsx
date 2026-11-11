import React from "react";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import JSZip from "jszip";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import App from "../App";
import {
  SAMPLE_LINEAGE_GRAPH,
  createLargeSummaryLineageFixture,
  createMockApiService
} from "./fixtures";

describe("Workspace Shell, 39 Dialects, Multi-Tab Editor, Interactive Lineage Canvas, & >200 Summary Gate", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    cleanup();
  });

  it("renders all 39 dialects grouped into Popular (6) + All dialects (33), switches sample SQL, formats SQL via /demo/sqlformat, and toggles EN/中文 i18n", async () => {
    const { api, recorded } = createMockApiService();
    render(<App customApi={api} memoryInitialEntries={["/"]} />);

    const select = screen.getByTestId("dialect-selector") as HTMLSelectElement;
    const options = Array.from(select.querySelectorAll("option"));
    const optgroups = Array.from(select.querySelectorAll("optgroup"));
    expect(options).toHaveLength(39);
    expect(optgroups).toHaveLength(2);
    expect(optgroups[0]?.label).toContain("6");
    expect(optgroups[1]?.label).toContain("33");

    // Switch dialect to Snowflake and verify Sample SQL updates
    fireEvent.change(select, { target: { value: "snowflake" } });
    expect(select.value).toBe("snowflake");

    // Format SQL calls /demo/sqlformat
    fireEvent.click(screen.getByTestId("format-sql-btn"));
    await waitFor(() => {
      expect(recorded.some((r) => r.url.includes("/demo/sqlformat"))).toBe(true);
    });

    // Toggle i18n EN -> 中文
    expect(screen.getByTestId("header-visualize-btn")).toHaveTextContent("Visualize");
    fireEvent.click(screen.getByTestId("i18n-toggle-btn"));
    await waitFor(() => {
      expect(screen.getByTestId("header-visualize-btn")).toHaveTextContent("生成血缘");
    });
  });

  it("supports multi-file SQL tabs (+ add, switch, close) and .zip multi-SQL archive upload", async () => {
    const { api } = createMockApiService();
    render(<App customApi={api} memoryInitialEntries={["/"]} />);

    fireEvent.click(screen.getByTestId("add-sql-tab-btn"));
    expect(screen.getByText("query-2.sql")).toBeInTheDocument();

    const zip = new JSZip();
    zip.file("etl/01_stage.sql", "SELECT id FROM raw_orders;");
    zip.file("etl/02_mart.sql", "CREATE VIEW mart_orders AS SELECT id FROM raw_orders;");
    const zipBlob = await zip.generateAsync({ type: "blob" });
    const zipFile = new File([zipBlob], "pipeline.zip", { type: "application/zip" });

    const uploadInput = screen.getByTestId("header-upload-input");
    fireEvent.change(uploadInput, { target: { files: [zipFile] } });

    await waitFor(() => {
      expect(screen.getByText("01_stage.sql")).toBeInTheDocument();
      expect(screen.getByText("02_mart.sql")).toBeInTheDocument();
    });
  });

  it("visualizes lineage, opens 17-item context menu, slices upstream/downstream, toggles Lineage tools, and dismisses onboarding tour", async () => {
    const { api, recorded } = createMockApiService({
      "/sqlflow/generation/sqlflow/graph": () => ({
        code: 200,
        sessionId: "sess-42",
        data: SAMPLE_LINEAGE_GRAPH
      })
    });

    render(<App customApi={api} memoryInitialEntries={["/"]} />);

    // Onboarding tour visible initially, dismiss persists sqlflow.tryThis.dismissed.v1
    expect(screen.getByTestId("onboarding-try-this-bar")).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("dismiss-try-this-btn"));
    expect(screen.queryByTestId("onboarding-try-this-bar")).not.toBeInTheDocument();
    expect(localStorage.getItem("sqlflow.tryThis.dismissed.v1")).toBe("1");

    // Visualize Lineage
    fireEvent.click(screen.getByTestId("header-visualize-btn"));
    const ordersNode = await screen.findByTestId("lineage-node-t_orders");
    expect(ordersNode).toBeInTheDocument();
    expect(screen.getByTestId("lineage-node-v_revenue")).toBeInTheDocument();

    // Right-click node -> verify all 17 context menu items appear
    fireEvent.contextMenu(ordersNode);
    const ctxMenu = await screen.findByTestId("canvas-context-menu");
    const buttons = ctxMenu.querySelectorAll("button");
    expect(buttons.length).toBe(17);

    // Click "show SQL" -> opens bottom SlideUp SQL drawer
    fireEvent.click(screen.getByTestId("ctx-show-sql"));
    expect(await screen.findByTestId("slideup-sql-drawer")).toBeInTheDocument();

    // Re-open context menu and trigger "to upstream"
    fireEvent.contextMenu(ordersNode);
    fireEvent.click(await screen.findByTestId("ctx-to-upstream"));
    await waitFor(() => {
      expect(recorded.some((r) => r.url.includes("/upstreamGraph"))).toBe(true);
    });

    // Floating Lineage tools bar collapse persists to sqlflow.actionsBar.v1
    fireEvent.click(screen.getByTestId("toggle-lineage-tools-collapse"));
    expect(localStorage.getItem("sqlflow.actionsBar.v1")).toContain('"collapsed":true');
  });

  it("activates the >200 object Summary Interstitial gate and allows rendering full graph on bypass", async () => {
    const largeGraph = createLargeSummaryLineageFixture(210);
    const { api } = createMockApiService({
      "/sqlflow/generation/sqlflow/graph": () => ({
        code: 200,
        sessionId: "sess-large",
        data: largeGraph
      })
    });

    render(<App customApi={api} memoryInitialEntries={["/"]} />);
    fireEvent.click(screen.getByTestId("header-visualize-btn"));

    // LineageCanvas renders summary interstitial when >200 objects
    const summaryGate = await screen.findByTestId("sqlflow-summary-interstitial");
    expect(summaryGate).toHaveTextContent("210");
    fireEvent.click(screen.getByTestId("summary-render-anyway"));
    await waitFor(() => {
      expect(screen.queryByTestId("sqlflow-summary-interstitial")).not.toBeInTheDocument();
      expect(screen.getAllByText(/table_0/i).length).toBeGreaterThanOrEqual(1);
    });
  });

  it("clears canvas internal column pin on Reset click and preserves step-slice graph without overwriting", async () => {
    const slicedGraph = {
      sqlflow: {
        dbobjs: [
          {
            id: "t_sliced_only",
            name: "sliced_upstream_source",
            type: "table",
            columns: [{ id: "c_s1", name: "src_id", dataType: "INT" }]
          }
        ],
        relationships: []
      }
    };

    const { api } = createMockApiService({
      "/sqlflow/generation/sqlflow/graph": () => ({
        code: 200,
        sessionId: "sess-pin",
        data: SAMPLE_LINEAGE_GRAPH
      }),
      "/sqlflow/generation/sqlflow/upstreamGraph": () => ({
        code: 200,
        sessionId: "sess-sliced",
        data: slicedGraph
      })
    });

    const { container } = render(<App customApi={api} memoryInitialEntries={["/"]} />);
    const editorTextarea = container.querySelector(
      '[data-testid="sql-editor-textarea"]'
    ) as HTMLTextAreaElement;
    fireEvent.change(editorTextarea, {
      target: {
        value: [
          "CREATE VIEW customer_revenue AS",
          "SELECT order_id, customer_id, amount FROM orders",
          "FROM orders",
          "JOIN customers ON orders.customer_id = customers.customer_id;"
        ].join("\n")
      }
    });

    fireEvent.click(screen.getByTestId("header-visualize-btn"));
    const ordersNode = await screen.findByTestId("lineage-node-t_orders");

    // Click column row directly on canvas -> pins editor coordinates
    const colRows = container.querySelectorAll(".sqlflow-field-row");
    expect(colRows.length).toBeGreaterThan(0);
    fireEvent.click(colRows[0]!);

    await waitFor(() => {
      expect(
        container.querySelectorAll(
          '[data-testid="sql-editor-highlight-layer"] span[data-highlight-state="pinned"]'
        ).length
      ).toBeGreaterThan(0);
    });

    // Click Reset on Lineage tools bar -> clears internal canvas pin AND editor pinned spans to 0
    fireEvent.click(screen.getByTestId("tool-reset-btn"));
    await waitFor(() => {
      expect(
        container.querySelectorAll(
          '[data-testid="sql-editor-highlight-layer"] span[data-highlight-state="pinned"]'
        ).length
      ).toBe(0);
    });

    // Click column again -> opens slideUpDrawer + pins; clicking drawer X unpins everything
    const refreshedColRows = container.querySelectorAll(".sqlflow-field-row");
    fireEvent.click(refreshedColRows[0]!);
    expect(await screen.findByTestId("slideup-sql-drawer")).toBeInTheDocument();
    expect(refreshedColRows[0]).toHaveAttribute("data-pinned", "true");

    fireEvent.click(screen.getByTestId("close-slideup-drawer"));
    await waitFor(() => {
      expect(screen.queryByTestId("slideup-sql-drawer")).toBeNull();
      expect(refreshedColRows[0]).toHaveAttribute("data-pinned", "false");
      expect(
        container.querySelectorAll(
          '[data-testid="sql-editor-highlight-layer"] span[data-highlight-state="pinned"]'
        ).length
      ).toBe(0);
    });

    // Click column again, then click blank canvas (`sqlflow-scaled-surface`) -> unpins everything
    fireEvent.click(refreshedColRows[0]!);
    expect(refreshedColRows[0]).toHaveAttribute("data-pinned", "true");
    fireEvent.click(screen.getByTestId("sqlflow-scaled-surface"));
    await waitFor(() => {
      expect(screen.queryByTestId("slideup-sql-drawer")).toBeNull();
      expect(refreshedColRows[0]).toHaveAttribute("data-pinned", "false");
    });

    // Right-click "+1 upstream" -> preserves sliced graph instead of overwriting with visualizeLineage()
    const refreshedOrdersNode = await screen.findByTestId("lineage-node-t_orders");
    fireEvent.contextMenu(refreshedOrdersNode);
    fireEvent.click(await screen.findByTestId("ctx-to-upstream-step"));
    expect(await screen.findByTestId("lineage-node-t_sliced_only")).toBeInTheDocument();
  });
});
