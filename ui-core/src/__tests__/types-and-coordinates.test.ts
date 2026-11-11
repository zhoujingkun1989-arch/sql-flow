import { describe, expect, it } from "vitest";
import {
  type Column,
  type JoinAnalysisGraph,
  DEFAULT_ENGINE_OPTIONS,
  DEFAULT_GUEST_USER_ID,
  DIAGNOSTIC_CODES,
  EDB_VENDORS,
  EDB_VENDOR_IDS,
  EFFECT_TYPES,
  JOIN_ANALYSIS_SCHEMA_VERSION,
  JOIN_DIAGNOSTIC_DESCRIPTORS,
  RELATIONSHIP_TYPES,
  RESULT_SET_TYPES,
  SPA_DEFAULT_API_PREFIX,
  UI_DIAGNOSTIC_IDS,
  WIDGET_DEFAULT_API_PREFIX,
  formatCoordinateString,
  fromCodeMirrorPosition,
  isPositionInSpan,
  normalizeCoordinateSpans,
  normalizeDbVendor,
  parseCoordinateString,
  toApiDbVendor,
  toCodeMirrorRange,
  toShortVendorKey,
  toShowRelationType
} from "../index.js";

describe("Wire TypeScript Contracts & Coordinate Utilities", () => {
  it("defines all 48 EDbVendor entries in exact enum ordinal order (0..47)", () => {
    expect(EDB_VENDOR_IDS).toHaveLength(48);
    expect(EDB_VENDORS).toHaveLength(48);
    expect(EDB_VENDORS[0]?.id).toBe("dbvaccess");
    expect(EDB_VENDORS[26]?.id).toBe("dbvoracle");
    expect(EDB_VENDORS[31]?.id).toBe("dbvsnowflake");
    expect(EDB_VENDORS[47]?.id).toBe("dbvsybaseiq");

    for (let i = 0; i < EDB_VENDORS.length; i++) {
      expect(EDB_VENDORS[i]?.ordinal).toBe(i);
    }

    expect(normalizeDbVendor("oracle")).toBe("dbvoracle");
    expect(normalizeDbVendor("SQL Server")).toBe("dbvmssql");
    expect(normalizeDbVendor("dbvsnowflake")).toBe("dbvsnowflake");
    expect(toShortVendorKey("dbvpostgresql")).toBe("postgresql");
    expect(toApiDbVendor("bigquery")).toBe("dbvbigquery");
  });

  it('preserves intentional wire spelling "unqiueKey" on Column (Invariant #4)', () => {
    const col: Column = {
      id: "col_1",
      name: "email",
      primaryKey: false,
      unqiueKey: true,
      foreignKey: false
    };
    expect(col.unqiueKey).toBe(true);
    expect(Object.prototype.hasOwnProperty.call(col, "unqiueKey")).toBe(true);
  });

  it("exposes JoinAnalysisGraph v1 schema version, exact wire enum casing, and all 10 diagnostic codes", () => {
    expect(JOIN_ANALYSIS_SCHEMA_VERSION).toBe("join-analysis.v1");
    expect(DIAGNOSTIC_CODES).toEqual([
      "missing_join_condition",
      "cartesian_join",
      "many_to_many_risk",
      "outer_join_filter_risk",
      "anti_join_detected",
      "self_join_detected",
      "semi_join_detected",
      "non_indexed_join_key",
      "complex_predicate",
      "unparsed_condition"
    ]);
    expect(UI_DIAGNOSTIC_IDS).toHaveLength(10);
    expect(JOIN_DIAGNOSTIC_DESCRIPTORS).toHaveLength(10);
    expect(
      JOIN_DIAGNOSTIC_DESCRIPTORS.find((d) => d.code === "complex_predicate")
        ?.defaultSeverity
    ).toBe("warn");

    const sampleJoinGraph: JoinAnalysisGraph = {
      schemaVersion: "join-analysis.v1",
      graphId: "jg_1",
      sqlText: "SELECT o.id FROM orders o LEFT JOIN customers c ON o.cid = c.id",
      dbvendor: "dbvoracle",
      layout: {
        engine: "elk",
        direction: "RIGHT",
        rankSpacing: 80,
        nodeSpacing: 40
      },
      queryBlocks: [
        {
          queryBlockId: "qb_main",
          label: "Main Query",
          kind: "main",
          collapsedByDefault: false
        }
      ],
      nodes: [
        {
          id: "t_orders",
          type: "table",
          position: { x: 20, y: 40 },
          data: {
            label: "orders o",
            queryBlockId: "qb_main",
            table: {
              tableId: "tbl_1",
              name: "orders",
              alias: "o",
              columnsContributed: [{ table: "orders", column: "cid" }],
              isSelfJoinInstance: false
            }
          }
        },
        {
          id: "j_1",
          type: "join",
          position: { x: 240, y: 60 },
          data: {
            label: "LEFT JOIN",
            queryBlockId: "qb_main",
            badges: [{ kind: "fan_out", level: "medium", heuristic: true }],
            join: {
              joinType: "LEFT",
              clause: "ON",
              patterns: ["anti"],
              cardinalityHint: "many_to_one",
              order: 1,
              predicates: [
                {
                  predicateId: "p_1",
                  raw: "o.cid = c.id",
                  kind: "equi",
                  op: "=",
                  left: {
                    tableId: "tbl_1",
                    tableName: "orders",
                    alias: "o",
                    column: "cid",
                    isIndexed: false
                  },
                  right: {
                    tableId: "tbl_2",
                    tableName: "customers",
                    alias: "c",
                    column: "id",
                    isPrimaryKey: true,
                    isUniqueKey: true,
                    isIndexed: true
                  },
                  sourceRange: {
                    startLine: 1,
                    startColumn: 52,
                    endLine: 1,
                    endColumn: 64,
                    quality: "exact"
                  }
                }
              ]
            }
          }
        }
      ],
      edges: [
        {
          id: "e_1",
          source: "t_orders",
          target: "j_1",
          data: {
            kind: "table_to_join",
            columns: [{ table: "orders", column: "cid" }],
            dashed: false
          }
        }
      ],
      fragments: [
        {
          fragmentId: "f_1",
          nodeIds: ["j_1"],
          kind: "join_condition",
          text: "o.cid = c.id"
        }
      ],
      diagnostics: [
        {
          id: "d_1",
          severity: "warn",
          roles: ["data_engineer", "dba"],
          code: "non_indexed_join_key",
          message:
            "A column used as a join key is not covered by an index, which can make this join slow.",
          evidenceNodeIds: ["j_1"],
          predicateIds: ["p_1"]
        }
      ],
      errors: [{ code: "parse_failed", message: "optional parse warning" }],
      fallback: {
        source: "legacy_graph",
        limitations: ["heuristic cardinality"]
      }
    };

    expect(sampleJoinGraph.layout?.engine).toBe("elk");
    expect(sampleJoinGraph.nodes[0]?.data.table?.columnsContributed?.[0]?.table).toBe(
      "orders"
    );
    expect(sampleJoinGraph.nodes[1]?.data.join?.predicates[0]?.right?.isPrimaryKey).toBe(
      true
    );
  });

  it("defines all 74 EngineOption fields, relationship/effect enums, and API defaults", () => {
    expect(Object.keys(DEFAULT_ENGINE_OPTIONS)).toHaveLength(74);
    expect(RELATIONSHIP_TYPES).toEqual([
      "fdd",
      "fddi",
      "frd",
      "fdr",
      "join",
      "call",
      "er",
      "crud"
    ]);
    expect(EFFECT_TYPES).toHaveLength(36);
    expect(RESULT_SET_TYPES).toHaveLength(18);
    expect(DEFAULT_GUEST_USER_ID).toBe("gudu|0123456789");
    expect(SPA_DEFAULT_API_PREFIX).toBe("/api/gspLive_backend");
    expect(WIDGET_DEFAULT_API_PREFIX).toBe("/gspLive_backend");

    expect(toShowRelationType({ dataflow: true, impact: false })).toBe("fdd");
    expect(toShowRelationType({ dataflow: false, impact: true })).toBe("fdr");
    expect(toShowRelationType({ dataflow: true, impact: true })).toBe("fdd,fdr");
    expect(toShowRelationType({ dataflow: true, impact: false }, true)).toBe("join");
  });

  it("parses, formats, and converts 1-based 2-tuple and 3-tuple coordinates accurately", () => {
    const twoTuple = parseCoordinateString("[4,5],[4,16]");
    expect(twoTuple).toEqual([
      { x: 4, y: 5 },
      { x: 4, y: 16 }
    ]);
    expect(formatCoordinateString(twoTuple)).toBe("[4,5],[4,16]");

    const threeTuple = parseCoordinateString("[12,8,h0],[12,19,h0],[20,4,h1],[20,15,h1]");
    expect(threeTuple).toHaveLength(4);
    expect(threeTuple[0]).toEqual({ x: 12, y: 8, hashCode: "h0" });
    expect(formatCoordinateString(threeTuple)).toBe(
      "[12,8,h0],[12,19,h0],[20,4,h1],[20,15,h1]"
    );

    const spans = normalizeCoordinateSpans(threeTuple);
    expect(spans).toEqual([
      { startLine: 12, startCol: 8, endLine: 12, endCol: 19, hashCode: "h0" },
      { startLine: 20, startCol: 4, endLine: 20, endCol: 15, hashCode: "h1" }
    ]);

    const cmRange = toCodeMirrorRange(spans[0]!);
    expect(cmRange).toEqual({
      from: { line: 11, ch: 7 },
      to: { line: 11, ch: 18 }
    });
    expect(fromCodeMirrorPosition({ line: 11, ch: 7 })).toEqual({ line: 12, column: 8 });

    expect(isPositionInSpan(12, 8, spans[0]!)).toBe(true);
    expect(isPositionInSpan(12, 18, spans[0]!)).toBe(true);
    expect(isPositionInSpan(12, 19, spans[0]!)).toBe(false);
  });
});
