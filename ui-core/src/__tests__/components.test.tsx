import React from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import {
  type DataFlow,
  LineageCanvas,
  SqlCodeEditor,
  layoutSqlflowGraph
} from "../index.js";

const INTERACTIVE_FLOW: DataFlow = {
  dbobjs: [
    {
      id: "t_src",
      database: "DEFAULT",
      schema: "CRM",
      name: "CUSTOMERS",
      type: "table",
      more: true,
      columns: [
        {
          id: "c_src_id",
          name: "CUSTOMER_ID",
          primaryKey: true,
          coordinates: [{ x: 3, y: 5, x1: 3, y1: 18 }]
        },
        {
          id: "c_src_email",
          name: "EMAIL",
          unqiueKey: true,
          foreignKey: true
        }
      ]
    },
    {
      id: "v_tgt",
      database: "DW",
      schema: "MART",
      name: "CUSTOMER_SUMMARY",
      type: "view",
      columns: [
        {
          id: "c_tgt_id",
          name: "CUST_ID",
          coordinates: [{ x: 2, y: 12, x1: 2, y1: 19 }]
        },
        {
          id: "c_tgt_cnt",
          name: "CUST_COUNT"
        }
      ]
    }
  ],
  relationships: [
    {
      id: "rel_direct",
      type: "fdd",
      sources: [
        {
          id: "c_src_id",
          parentId: "t_src",
          transforms: [{ type: "function", code: "TRIM(c.CUSTOMER_ID)" }]
        }
      ],
      target: { id: "c_tgt_id", parentId: "v_tgt" }
    },
    {
      id: "rel_indirect",
      type: "fdr",
      sources: [{ id: "c_src_email", parentId: "t_src" }],
      target: { id: "c_tgt_cnt", parentId: "v_tgt" }
    }
  ]
};

describe("<LineageCanvas /> & <SqlCodeEditor /> Shared Components", () => {
  it("renders hybrid HTML cards, PK/UK(unqiueKey)/FK badges, fdd solid & fdr dashed Bézier edges, f(x) popover, path highlighting & zoom/minimap", () => {
    const onFieldClick = vi.fn();
    const onTableNameClick = vi.fn();
    const onStreamExpandClick = vi.fn();
    const onTransformClick = vi.fn();

    render(
      <LineageCanvas
        data={INTERACTIVE_FLOW}
        layoutOptions={{ layout: true }}
        showTransform={true}
        showMinimap={true}
        showLocateBar={true}
        onFieldClick={onFieldClick}
        onTableNameClick={onTableNameClick}
        onStreamExpandClick={onStreamExpandClick}
        onTransformClick={onTransformClick}
      />
    );

    // Node cards & badges (`unqiueKey` -> UK badge!)
    expect(screen.getByTestId("lineage-node-t_src")).toBeInTheDocument();
    expect(screen.getByTestId("lineage-node-v_tgt")).toBeInTheDocument();
    expect(screen.getByTestId("badge-pk-c_src_id")).toHaveTextContent("PK");
    expect(screen.getByTestId("badge-uk-c_src_email")).toHaveTextContent("UK");
    expect(screen.getByTestId("badge-fk-c_src_email")).toHaveTextContent("FK");

    // Direct (`fdd` solid) vs Indirect (`fdr` dashed `4 2`) SVG edges + 10x7 arrowheads
    const directPath = screen.getByTestId("edge-path-rel_direct_0");
    const indirectPath = screen.getByTestId("edge-path-rel_indirect_0");
    expect(directPath.getAttribute("d")).toMatch(/^M .* C .* S .*$/);
    expect(directPath.getAttribute("stroke-dasharray")).toBeNull();
    expect(indirectPath.getAttribute("stroke-dasharray")).toBe("4 2");
    expect(screen.getByTestId("edge-arrow-rel_direct_0").getAttribute("points")).toContain(",");

    // Upstream/downstream hover & click path highlighting
    const srcColRow = screen.getByTestId("column-row-c_src_id");
    const tgtColRow = screen.getByTestId("column-row-c_tgt_id");
    fireEvent.mouseEnter(srcColRow);
    expect(srcColRow).toHaveAttribute("data-hovered", "true");
    expect(tgtColRow).toHaveAttribute("data-hovered", "true");

    fireEvent.click(tgtColRow);
    expect(srcColRow).toHaveAttribute("data-pinned", "true");
    expect(tgtColRow).toHaveAttribute("data-pinned", "true");
    expect(onFieldClick).toHaveBeenCalledTimes(1);
    expect(onFieldClick.mock.calls[0]?.[0]).toMatchObject({
      database: "DW",
      schema: "MART",
      table: "CUSTOMER_SUMMARY",
      column: "CUST_ID",
      source: {
        columns: [{ schema: "CRM", table: "CUSTOMERS", column: "CUSTOMER_ID" }]
      }
    });

    // Clicking table header fires `onTableNameClick` with `"DEFAULT"` stripped
    fireEvent.click(screen.getByTestId("node-header-t_src"));
    expect(onTableNameClick).toHaveBeenCalledWith(
      { schema: "CRM", table: "CUSTOMERS" },
      expect.objectContaining({ id: "t_src" })
    );

    // Clicking f(x) transform badge opens popover
    fireEvent.click(screen.getByTestId("edge-transform-badge-rel_direct_0"));
    const popover = screen.getByTestId("sqlflow-transform-popover");
    expect(popover).toHaveTextContent("TRIM(c.CUSTOMER_ID)");
    fireEvent.click(screen.getByText("TRIM(c.CUSTOMER_ID)"));
    expect(onTransformClick).toHaveBeenCalledTimes(1);

    // Stream +/- buttons
    fireEvent.click(screen.getByTestId("stream-up-t_src"));
    expect(onStreamExpandClick).toHaveBeenCalledWith(
      "upstream",
      expect.objectContaining({ id: "t_src" }),
      false
    );

    // Scale controls & minimap
    expect(screen.getByTestId("sqlflow-minimap")).toBeInTheDocument();
    const scaledSurface = screen.getByTestId("sqlflow-scaled-surface");
    expect(scaledSurface).toHaveAttribute("data-scale", "1");
    fireEvent.click(screen.getByTestId("zoom-in-btn"));
    expect(scaledSurface).toHaveAttribute("data-scale", "1.2");
    fireEvent.click(screen.getByTestId("zoom-reset-btn"));
    expect(scaledSurface).toHaveAttribute("data-scale", "1");
  });

  it("activates the >200 object Summary Interstitial gate and allows interactive bypass", () => {
    const manyTables: DataFlow = {
      dbobjs: Array.from({ length: 205 }, (_, i) => ({
        id: `tbl_${i}`,
        name: `TABLE_${i}`,
        type: "table",
        columns: [{ id: `col_${i}`, name: "ID" }]
      })),
      relationships: []
    };
    const onShowSchemaExplorer = vi.fn();

    render(
      <LineageCanvas
        data={manyTables}
        layoutOptions={{ layout: false }}
        onShowSchemaExplorer={onShowSchemaExplorer}
      />
    );

    const summaryGate = screen.getByTestId("sqlflow-summary-interstitial");
    expect(summaryGate).toBeInTheDocument();
    expect(summaryGate).toHaveTextContent("205");

    fireEvent.click(screen.getByTestId("summary-show-schema-explorer"));
    expect(onShowSchemaExplorer).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByTestId("summary-render-anyway"));
    expect(screen.queryByTestId("sqlflow-summary-interstitial")).toBeNull();
    expect(screen.getByTestId("lineage-node-tbl_0")).toBeInTheDocument();
  });

  it("renders <SqlCodeEditor /> with line numbers, syntax tokens, 1-based range highlights, scrollIntoView & error gutter markers", () => {
    const scrollSpy = vi.spyOn(HTMLElement.prototype, "scrollIntoView");
    const onErrorMarkerClick = vi.fn();
    const onTokenHover = vi.fn();

    const sql = [
      "-- Revenue View",
      "CREATE VIEW customer_summary AS",
      "SELECT c.CUSTOMER_ID FROM CRM.CUSTOMERS c;"
    ].join("\n");

    const model = layoutSqlflowGraph(INTERACTIVE_FLOW, { layout: true });

    render(
      <SqlCodeEditor
        value={sql}
        readOnly={true}
        tokenIndex={model.tokenIndex}
        hoverCoordinates={[{ x: 2, y: 13, x1: 2, y1: 29 }]}
        highlightCoordinates={[{ x: 3, y: 8, x1: 3, y1: 21 }]}
        errors={[
          {
            errorType: "SyntaxError",
            errorMessage: "Unexpected semicolon near line 3",
            coordinates: [{ x: 3, y: 42, x1: 3, y1: 43 }]
          }
        ]}
        onErrorMarkerClick={onErrorMarkerClick}
        onTokenHover={onTokenHover}
      />
    );

    expect(screen.getByTestId("gutter-line-1")).toHaveTextContent("1");
    expect(screen.getByTestId("gutter-line-3")).toHaveTextContent("3");

    // Error gutter marker on line 3
    const errBtn = screen.getByTestId("error-gutter-marker-3");
    fireEvent.click(errBtn);
    expect(onErrorMarkerClick).toHaveBeenCalledWith(
      expect.objectContaining({ errorType: "SyntaxError" }),
      3
    );

    // Pinned highlight (`#443b3b`) + hover highlight (`#dadada`) + scrollIntoView
    const container = screen.getByTestId("sql-editor-highlight-layer");
    expect(container.querySelectorAll(".highlight").length).toBeGreaterThan(0);
    expect(container.querySelectorAll(".mouseover").length).toBeGreaterThan(0);
    expect(scrollSpy).toHaveBeenCalled();

    scrollSpy.mockRestore();
  });

  it("supports toggle-unpin on second click, blank canvas deselection, Escape unpin, and filters pass-through alias f(x) badges", () => {
    const flowWithAliasAndFn: DataFlow = {
      dbobjs: INTERACTIVE_FLOW.dbobjs,
      relationships: [
        {
          id: "rel_fn",
          type: "fdd",
          sources: [
            {
              id: "c_src_id",
              parentId: "t_src",
              transforms: [{ type: "function", code: "SUM(c.CUSTOMER_ID)" }]
            }
          ],
          target: { id: "c_tgt_id", parentId: "v_tgt" }
        },
        {
          id: "rel_alias",
          type: "fdd",
          sources: [
            {
              id: "c_src_email",
              parentId: "t_src",
              transforms: [{ type: "alias", code: "c.EMAIL" }]
            }
          ],
          target: { id: "c_tgt_cnt", parentId: "v_tgt" }
        }
      ]
    };
    const onPinnedTargetChange = vi.fn();
    render(
      <LineageCanvas
        data={flowWithAliasAndFn}
        layoutOptions={{ layout: true }}
        showTransform={true}
        onPinnedTargetChange={onPinnedTargetChange}
      />
    );

    // Meaningful function transform gets f(x) badge; plain alias pass-through does not
    expect(screen.getByTestId("edge-transform-badge-rel_fn_0")).toBeInTheDocument();
    expect(screen.queryByTestId("edge-transform-badge-rel_alias_0")).toBeNull();

    const tgtColRow = screen.getByTestId("column-row-c_tgt_id");
    // 1st click pins
    fireEvent.click(tgtColRow);
    expect(tgtColRow).toHaveAttribute("data-pinned", "true");
    expect(onPinnedTargetChange).toHaveBeenLastCalledWith({
      kind: "column",
      columnId: "c_tgt_id"
    });

    // 2nd click on same column toggles unpin
    fireEvent.click(tgtColRow);
    expect(tgtColRow).toHaveAttribute("data-pinned", "false");
    expect(onPinnedTargetChange).toHaveBeenLastCalledWith(null);

    // Pin again, then click blank canvas surface -> unpins cleanly
    fireEvent.click(tgtColRow);
    expect(tgtColRow).toHaveAttribute("data-pinned", "true");
    fireEvent.click(screen.getByTestId("sqlflow-scaled-surface"));
    expect(tgtColRow).toHaveAttribute("data-pinned", "false");
    expect(onPinnedTargetChange).toHaveBeenLastCalledWith(null);

    // Pin again, then press Escape -> unpins cleanly
    fireEvent.click(tgtColRow);
    expect(tgtColRow).toHaveAttribute("data-pinned", "true");
    fireEvent.keyDown(window, { key: "Escape" });
    expect(tgtColRow).toHaveAttribute("data-pinned", "false");
    expect(onPinnedTargetChange).toHaveBeenLastCalledWith(null);
  });
});
