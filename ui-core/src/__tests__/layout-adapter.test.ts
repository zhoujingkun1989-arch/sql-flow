import { describe, expect, it } from "vitest";
import {
  type DataFlow,
  type DependencyGraphV1,
  type SqlflowJSON,
  buildOnFieldClickPayload,
  buildOnTableNameClickPayload,
  convertDependencyGraphToSqlflow,
  layoutDependencyGraphV1,
  layoutSqlflowGraph,
  lookupTokenAtPosition,
  moveCanvasNode,
  traceLineageHighlight,
  validateDependencyGraphV1
} from "../index.js";

const SAMPLE_DATAFLOW: DataFlow = {
  dbobjs: [
    {
      id: "pkg_1",
      name: "ORDER_WORKFLOW",
      type: "oracle package"
    },
    {
      id: "tbl_orders",
      database: "DEFAULT",
      schema: "SALES",
      name: "ORDERS",
      type: "table",
      parent: "pkg_1",
      coordinates: [{ x: 6, y: 6, x1: 6, y1: 12 }],
      columns: [
        {
          id: "c_ord_id",
          name: "ORDER_ID",
          primaryKey: true,
          coordinates: [{ x: 7, y: 27, x1: 7, y1: 37 }]
        },
        {
          id: "c_ord_amt",
          name: "AMOUNT",
          unqiueKey: true,
          coordinates: [{ x: 4, y: 9, x1: 4, y1: 17 }]
        }
      ]
    },
    {
      id: "rs_1",
      database: "DEFAULT",
      schema: "DEFAULT",
      name: "RS_AGG",
      type: "select_list",
      columns: [{ id: "c_rs_sum", name: "SUM_AMOUNT" }]
    },
    {
      id: "vw_rev",
      database: "ANALYTICS_DB",
      schema: "PUBLIC",
      name: "CUSTOMER_REVENUE",
      type: "view",
      columns: [
        {
          id: "c_rev_total",
          name: "TOTAL_REVENUE",
          coordinates: [{ x: 5, y: 20, x1: 5, y1: 33 }]
        }
      ]
    }
  ],
  relationships: [
    {
      id: "r1",
      type: "fdd",
      sources: [
        {
          id: "c_ord_amt",
          parentId: "tbl_orders",
          transforms: [{ type: "function", code: "SUM(o.AMOUNT)" }]
        }
      ],
      target: { id: "c_rs_sum", parentId: "rs_1" }
    },
    {
      id: "r2",
      type: "fdd",
      sources: [{ id: "c_rs_sum", parentId: "rs_1" }],
      target: { id: "c_rev_total", parentId: "vw_rev" }
    }
  ]
};

describe("Client-Side @dagrejs/dagre Layout & DependencyGraph v1.0 Adapter", () => {
  it("lays out DataFlow in LR and TB rankdirs with positive coordinates and compound containers", () => {
    const lrModel = layoutSqlflowGraph(SAMPLE_DATAFLOW, { layout: true, rankdir: "LR" });
    expect(lrModel.nodes).toHaveLength(3);
    expect(lrModel.containers).toHaveLength(1);
    expect(lrModel.containers[0]?.name).toBe("ORDER_WORKFLOW");
    expect(lrModel.edges).toHaveLength(2);

    const srcNode = lrModel.nodeById.get("tbl_orders")!;
    const midNode = lrModel.nodeById.get("rs_1")!;
    const tgtNode = lrModel.nodeById.get("vw_rev")!;

    expect(srcNode.x).toBeGreaterThanOrEqual(35);
    expect(srcNode.y).toBeGreaterThanOrEqual(70);
    expect(midNode.x).toBeGreaterThan(srcNode.x);
    expect(tgtNode.x).toBeGreaterThan(midNode.x);

    const tbModel = layoutSqlflowGraph(SAMPLE_DATAFLOW, { layout: true, rankdir: "TB" });
    expect(tbModel.rankdir).toBe("TB");
    expect(tbModel.nodeById.get("vw_rev")!.y).toBeGreaterThan(
      tbModel.nodeById.get("tbl_orders")!.y
    );

    const moved = moveCanvasNode(lrModel, "vw_rev", 720, 260);
    expect(moved.nodeById.get("vw_rev")?.x).toBe(720);
    expect(moved.edges[1]?.bezier.x2).toBe(720);
  });

  it("recursively traces upstream & downstream paths, indexes 1-based tokens, and strips DEFAULT in widget payloads", () => {
    const model = layoutSqlflowGraph(SAMPLE_DATAFLOW, { layout: true });

    const highlightFromMid = traceLineageHighlight(model, {
      kind: "column",
      columnId: "c_rs_sum"
    });
    expect(Array.from(highlightFromMid.columnIds).sort()).toEqual([
      "c_ord_amt",
      "c_rev_total",
      "c_rs_sum"
    ]);
    expect(highlightFromMid.edgeIds.size).toBe(2);

    const hitCol = lookupTokenAtPosition(model.tokenIndex, 4, 12);
    expect(hitCol).toEqual({
      kind: "column",
      nodeId: "tbl_orders",
      columnId: "c_ord_amt"
    });

    const tableClick = buildOnTableNameClickPayload(model.nodeById.get("tbl_orders")!);
    expect(tableClick).toEqual({
      schema: "SALES",
      table: "ORDERS"
    });
    expect("database" in tableClick).toBe(false);

    const fieldClick = buildOnFieldClickPayload(model, "c_rs_sum")!;
    expect(fieldClick.table).toBe("RS_AGG");
    expect("database" in fieldClick).toBe(false);
    expect("schema" in fieldClick).toBe(false);
    expect(fieldClick.source.columns[0]?.table).toBe("ORDERS");
    expect(fieldClick.source.columns[0]?.schema).toBe("SALES");
    expect(fieldClick.target.columns[0]?.database).toBe("ANALYTICS_DB");
    expect(fieldClick.target.columns[0]?.table).toBe("CUSTOMER_REVENUE");
  });

  it("strictly validates DependencyGraph v1.0 and converts/lays out unpositioned graphs", () => {
    const dg: DependencyGraphV1 = {
      version: "1.0",
      kind: "dependency-graph",
      direction: "downstream",
      focus: { nodeId: "n_raw" },
      nodes: [
        {
          id: "n_raw",
          label: "RAW.EVENTS",
          table: "EVENTS",
          schema: "RAW",
          type: "table",
          expandable: true,
          columns: [{ id: "e_id", name: "EVENT_ID", primaryKey: true }]
        },
        {
          id: "n_mart",
          label: "MART.DAILY_EVENTS",
          table: "DAILY_EVENTS",
          schema: "MART",
          type: "view",
          columns: [{ id: "m_cnt", name: "EVENT_COUNT" }]
        }
      ],
      edges: [
        {
          id: "dg_e1",
          source: "n_raw",
          target: "n_mart",
          columnMappings: [
            {
              sourceColumnId: "e_id",
              targetColumnId: "m_cnt",
              relationType: "fdd",
              transformCode: "COUNT(EVENT_ID)"
            }
          ]
        }
      ]
    };

    expect(() => validateDependencyGraphV1(dg)).not.toThrow();
    expect(() =>
      validateDependencyGraphV1({ ...dg, version: "2.0" })
    ).toThrow(/version/);
    expect(() =>
      validateDependencyGraphV1({ ...dg, focus: { nodeId: "missing" } })
    ).toThrow(/focus\.nodeId/);

    const converted = convertDependencyGraphToSqlflow(dg, {
      useTopologicalBfsPositioning: true
    });
    expect(converted.graph?.elements.tables[0]?.x).toBe(35);
    expect(converted.graph?.elements.tables[1]?.x).toBe(335);

    const laidOut = layoutDependencyGraphV1(dg, { layout: true });
    expect(laidOut.nodes).toHaveLength(2);
    expect(laidOut.edges).toHaveLength(1);
    expect(laidOut.edges[0]?.transforms[0]?.code).toBe("COUNT(EVENT_ID)");
  });

  it("bridges server SqlflowJSON graph.listIdMap, relationshipIdMap, and tooltip (03 §1.5.3) with non-overlapping server coordinates", () => {
    const serverPayload: SqlflowJSON = {
      mode: "global",
      sqlflow: {
        dbobjs: [
          {
            id: "1",
            database: "RAW_DB",
            schema: "SALES",
            name: "ORDERS",
            type: "table",
            columns: [{ id: "2", name: "ORDER_ID", primaryKey: true }]
          },
          {
            id: "5",
            database: "very_long_database",
            schema: "very_long_schema",
            name: "customer_daily_aggregates",
            displayName: "very_long_database.very_l...",
            type: "view",
            columns: [{ id: "6", name: "AGG_ORDER_ID" }]
          }
        ],
        relationships: [
          {
            id: "1",
            type: "fdd",
            sources: [{ id: "2", parentId: "1" }],
            target: { id: "6", parentId: "5" }
          }
        ]
      },
      graph: {
        elements: {
          tables: [
            {
              id: "n0",
              name: "ORDERS",
              x: 52,
              y: 90,
              width: 170,
              height: 64,
              columns: [{ id: "n0::n0", name: "ORDER_ID", x: 52, y: 112, width: 170, height: 16 }]
            },
            {
              id: "n1",
              name: "very_long_database.very_l...",
              x: 410,
              y: 145,
              width: 230,
              height: 64,
              columns: [{ id: "n1::n0", name: "AGG_ORDER_ID", x: 410, y: 167, width: 230, height: 16 }]
            }
          ],
          edges: [
            {
              id: "e0",
              sourceId: "n0::n0",
              targetId: "n1::n0"
            }
          ]
        },
        listIdMap: {
          "1": ["n0"],
          "2": ["n0::n0"],
          "5": ["n1"],
          "6": ["n1::n0"]
        },
        relationshipIdMap: {
          "e0": ["1", "4"]
        },
        tooltip: {
          n1: "very_long_database.very_long_schema.customer_daily_aggregates",
          "n1::n0": "AGG_ORDER_ID : NUMBER(38,0)"
        }
      }
    };

    const bridged = layoutSqlflowGraph(serverPayload);
    const node1 = bridged.nodeById.get("1")!;
    const node5 = bridged.nodeById.get("5")!;

    expect(node1.x).toBe(52);
    expect(node1.y).toBe(90);
    expect(node1.width).toBe(170);
    expect(node5.x).toBe(410);
    expect(node5.y).toBe(145);
    expect(node5.width).toBe(230);
    expect(node5.tooltipTitle).toBe(
      "very_long_database.very_long_schema.customer_daily_aggregates"
    );
    expect(node5.columns[0]?.tooltip).toBe("AGG_ORDER_ID : NUMBER(38,0)");
    expect(bridged.edges).toHaveLength(1);
    expect(bridged.edges[0]?.bezier.x1).toBe(52 + 170);
    expect(bridged.edges[0]?.bezier.x2).toBe(410);

    // Also verify GraphML edge resolution (`n0::n0 -> n1::n0`) when `sqlflow.relationships` is absent
    const graphEdgesOnly = layoutSqlflowGraph({
      ...serverPayload,
      sqlflow: { ...serverPayload.sqlflow, relationships: [] }
    });
    expect(graphEdgesOnly.edges).toHaveLength(1);
    expect(graphEdgesOnly.edges[0]?.sourceNodeId).toBe("1");
    expect(graphEdgesOnly.edges[0]?.sourceColumnId).toBe("2");
    expect(graphEdgesOnly.edges[0]?.targetNodeId).toBe("5");
    expect(graphEdgesOnly.edges[0]?.targetColumnId).toBe("6");

    // Verify safe Dagre fallback when `listIdMap` is missing so nodes never stack at (35, 70)
    const unmappedFallback = layoutSqlflowGraph({
      ...serverPayload,
      graph: { ...serverPayload.graph!, listIdMap: undefined }
    });
    const fb1 = unmappedFallback.nodeById.get("1")!;
    const fb5 = unmappedFallback.nodeById.get("5")!;
    expect(fb5.x).toBeGreaterThan(fb1.x + fb1.width);

    // Verify catalog-only fdr tables pruned from server `graph.elements.tables` never overlap at (0,0)
    const withPrunedFdrCatalogObj: SqlflowJSON = {
      ...serverPayload,
      sqlflow: {
        dbobjs: [
          ...(serverPayload.sqlflow!.dbobjs as any[]),
          {
            id: "9",
            database: "RAW_DB",
            schema: "SALES",
            name: "FILTER_ONLY_LOOKUP",
            type: "table",
            columns: [{ id: "10", name: "STATUS_CODE" }]
          }
        ],
        relationships: [
          ...serverPayload.sqlflow!.relationships!,
          {
            id: "2",
            type: "fdr",
            sources: [{ id: "10", parentId: "9" }],
            target: { id: "6", parentId: "5" }
          }
        ]
      }
    };
    const fddOnlyView = layoutSqlflowGraph(withPrunedFdrCatalogObj, {
      includeDirect: true,
      includeIndirect: false
    });
    expect(fddOnlyView.nodeById.has("9")).toBe(false);
    expect(fddOnlyView.nodes).toHaveLength(2);

    const expandedWithIndirect = layoutSqlflowGraph(withPrunedFdrCatalogObj, {
      includeDirect: true,
      includeIndirect: true
    });
    expect(expandedWithIndirect.nodeById.has("9")).toBe(true);
    expect(expandedWithIndirect.nodes).toHaveLength(3);
  });
});
