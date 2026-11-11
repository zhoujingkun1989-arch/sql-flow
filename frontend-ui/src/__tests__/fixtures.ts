import {
  DIAGNOSTIC_CODES,
  JOIN_ANALYSIS_SCHEMA_VERSION,
  type JoinAnalysisGraph,
  type SqlflowJSON
} from "@sqlflow/ui-core";
import { SpaApiService } from "../services/spaApi";

export const SAMPLE_LINEAGE_GRAPH: SqlflowJSON = {
  sqlflow: {
    dbobjs: [
      {
        id: "t_orders",
        name: "orders",
        type: "table",
        database: "SALES_DW",
        schema: "PUBLIC",
        coordinates: [
          { x: 3, y: 6 },
          { x: 3, y: 12 }
        ],
        columns: [
          {
            id: "c_orders_id",
            name: "order_id",
            dataType: "BIGINT",
            primaryKey: true,
            coordinates: [
              { x: 2, y: 5 },
              { x: 2, y: 13 }
            ]
          },
          {
            id: "c_orders_cust_id",
            name: "customer_id",
            dataType: "BIGINT",
            foreignKey: true,
            coordinates: [
              { x: 2, y: 15 },
              { x: 2, y: 26 }
            ]
          },
          {
            id: "c_orders_amount",
            name: "amount",
            dataType: "DECIMAL(18,2)",
            coordinates: [
              { x: 2, y: 28 },
              { x: 2, y: 34 }
            ]
          }
        ]
      },
      {
        id: "t_customers",
        name: "customers",
        type: "table",
        database: "SALES_DW",
        schema: "PUBLIC",
        coordinates: [
          { x: 4, y: 6 },
          { x: 4, y: 15 }
        ],
        columns: [
          {
            id: "c_cust_id",
            name: "customer_id",
            dataType: "BIGINT",
            primaryKey: true
          },
          {
            id: "c_cust_email",
            name: "email",
            dataType: "VARCHAR(255)",
            unqiueKey: true
          }
        ]
      },
      {
        id: "v_revenue",
        name: "customer_revenue",
        type: "view",
        database: "SALES_DW",
        schema: "MART",
        coordinates: [
          { x: 1, y: 13 },
          { x: 1, y: 29 }
        ],
        columns: [
          {
            id: "c_rev_cust_id",
            name: "customer_id",
            dataType: "BIGINT"
          },
          {
            id: "c_rev_total",
            name: "total_revenue",
            dataType: "DECIMAL(18,2)"
          }
        ]
      }
    ],
    relationships: [
      {
        id: "rel_1",
        type: "fdd",
        effectType: "select",
        target: {
          id: "c_rev_total",
          column: "total_revenue",
          parentId: "v_revenue",
          parentName: "customer_revenue",
          transforms: [
            {
              type: "function",
              code: "SUM(o.amount)",
              coordinates: [
                { x: 2, y: 28 },
                { x: 2, y: 41 }
              ]
            }
          ],
          coordinates: [
            { x: 2, y: 28 },
            { x: 2, y: 41 }
          ]
        },
        sources: [
          {
            id: "c_orders_amount",
            column: "amount",
            parentId: "t_orders",
            parentName: "orders"
          }
        ]
      },
      {
        id: "rel_2",
        type: "fdr",
        effectType: "join",
        target: {
          id: "c_rev_cust_id",
          column: "customer_id",
          parentId: "v_revenue",
          parentName: "customer_revenue"
        },
        sources: [
          {
            id: "c_cust_id",
            column: "customer_id",
            parentId: "t_customers",
            parentName: "customers"
          }
        ]
      }
    ],
    errors: [
      {
        errorType: "SyntaxError",
        errorMessage: "Warning near token SEMICOLON at line 4",
        coordinates: [{ x: 4, y: 1 }]
      }
    ]
  },
  graph: {
    elements: {
      tables: [
        {
          id: "t_orders",
          x: 40,
          y: 60,
          width: 180,
          height: 86,
          name: "orders",
          type: "table",
          more: true,
          columns: [
            { id: "c_orders_id", x: 0, y: 22, width: 180, height: 16, name: "order_id", primaryKey: true },
            { id: "c_orders_cust_id", x: 0, y: 38, width: 180, height: 16, name: "customer_id", foreignKey: true },
            { id: "c_orders_amount", x: 0, y: 54, width: 180, height: 16, name: "amount" }
          ]
        },
        {
          id: "t_customers",
          x: 40,
          y: 190,
          width: 180,
          height: 70,
          name: "customers",
          type: "table",
          columns: [
            { id: "c_cust_id", x: 0, y: 22, width: 180, height: 16, name: "customer_id", primaryKey: true },
            { id: "c_cust_email", x: 0, y: 38, width: 180, height: 16, name: "email", unqiueKey: true }
          ]
        },
        {
          id: "v_revenue",
          x: 340,
          y: 110,
          width: 200,
          height: 70,
          name: "customer_revenue",
          type: "view",
          more: true,
          columns: [
            { id: "c_rev_cust_id", x: 0, y: 22, width: 200, height: 16, name: "customer_id" },
            { id: "c_rev_total", x: 0, y: 38, width: 200, height: 16, name: "total_revenue" }
          ]
        }
      ],
      edges: [
        {
          id: "e_1",
          sourceId: "c_orders_amount",
          targetId: "c_rev_total",
          type: "fdd",
          transforms: [{ type: "function", code: "SUM(o.amount)" }]
        },
        {
          id: "e_2",
          sourceId: "c_cust_id",
          targetId: "c_rev_cust_id",
          type: "fdr"
        }
      ]
    }
  }
};

export function createLargeSummaryLineageFixture(tableCount = 215): SqlflowJSON {
  const dbobjs = Array.from({ length: tableCount }, (_, idx) => ({
    id: `tbl_${idx}`,
    name: `fact_table_${idx}`,
    type: "table",
    columns: [{ id: `col_${idx}`, name: "id", dataType: "INT" }]
  }));
  return {
    sqlflow: {
      dbobjs,
      relationships: [],
      summary: {
        process: 10,
        database: 2,
        schema: 4,
        table: tableCount,
        view: 5,
        column: tableCount,
        relationship: 120
      }
    }
  };
}

export const SAMPLE_JOIN_ANALYSIS_GRAPH: JoinAnalysisGraph = {
  schemaVersion: JOIN_ANALYSIS_SCHEMA_VERSION,
  graphId: "ja-1",
  dbvendor: "dbvoracle",
  summary: {
    tableCount: 3,
    joinCount: 2,
    diagnosticCount: 10,
    queryBlockCount: 2
  },
  layout: {
    engine: "elk",
    direction: "RIGHT",
    rankSpacing: 90,
    nodeSpacing: 40
  },
  queryBlocks: [
    { queryBlockId: "qb_main", label: "Main SELECT", kind: "main" },
    { queryBlockId: "qb_cte", label: "WITH active_orders", kind: "cte" }
  ],
  nodes: [
    {
      id: "jn_orders",
      type: "table",
      data: {
        label: "orders",
        queryBlockId: "qb_main",
        table: {
          name: "orders",
          alias: "o",
          database: "SALES_DW",
          schema: "PUBLIC",
          columnsContributed: [{ table: "orders", column: "customer_id" }]
        }
      }
    },
    {
      id: "jn_customers",
      type: "table",
      data: {
        label: "customers",
        queryBlockId: "qb_cte",
        table: {
          name: "customers",
          alias: "c",
          isSelfJoinInstance: true,
          columnsContributed: [{ table: "customers", column: "customer_id" }]
        }
      }
    },
    {
      id: "jn_join_1",
      type: "join",
      data: {
        label: "LEFT JOIN",
        queryBlockId: "qb_main",
        fragmentId: "frag_1",
        badges: [{ kind: "fan_out", level: "high" }, { kind: "cartesian_risk" }],
        join: {
          joinType: "LEFT",
          clause: "ON",
          cardinalityHint: "many_to_many",
          sourceRange: { startLine: 2, startColumn: 1, endLine: 2, endColumn: 35 },
          predicates: [
            {
              predicateId: "pred_1",
              raw: "o.customer_id = c.customer_id",
              kind: "equi",
              sourceRange: { startLine: 2, startColumn: 12, endLine: 2, endColumn: 35 }
            }
          ]
        }
      }
    },
    {
      id: "jn_out",
      type: "output",
      data: {
        label: "Final Result",
        queryBlockId: "qb_main"
      }
    }
  ],
  edges: [
    {
      id: "je_1",
      source: "jn_orders",
      target: "jn_join_1",
      data: { kind: "table_to_join", columns: [{ table: "orders", column: "customer_id" }] }
    },
    {
      id: "je_2",
      source: "jn_customers",
      target: "jn_join_1",
      data: { kind: "correlation", dashed: true }
    },
    {
      id: "je_3",
      source: "jn_join_1",
      target: "jn_out",
      data: { kind: "join_to_output" }
    }
  ],
  fragments: [
    {
      fragmentId: "frag_1",
      nodeIds: ["jn_join_1"],
      kind: "join_clause",
      text: "LEFT JOIN customers c ON o.customer_id = c.customer_id",
      sourceRange: { startLine: 2, startColumn: 1, endLine: 2, endColumn: 35 }
    }
  ],
  diagnostics: DIAGNOSTIC_CODES.map((code, idx) => ({
    id: `diag_${idx}`,
    code,
    severity: idx < 2 ? "block" : idx < 6 ? "warn" : "info",
    roles: idx % 2 === 0 ? ["data_engineer"] : ["dba", "data_analyst"],
    message: `Detected ${code} on join clause`,
    evidenceNodeIds: ["jn_join_1"],
    fragmentIds: ["frag_1"],
    edgeIds: ["je_1"]
  })),
  fallback: {
    source: "backend_degraded",
    limitations: ["Statistics estimated without live table row counts"]
  }
};

export interface RecordedRequest {
  url: string;
  method: string;
  headers: Record<string, string>;
  body: unknown;
}

export function createMockApiService(
  customHandlers?: Record<string, (req: RecordedRequest) => unknown | Response>
): {
  api: SpaApiService;
  recorded: RecordedRequest[];
} {
  const recorded: RecordedRequest[] = [];

  const fetchMock: typeof fetch = async (input, init) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
    const method = (init?.method ?? "GET").toUpperCase();
    const headers: Record<string, string> = {};
    if (init?.headers && typeof init.headers === "object") {
      for (const [k, v] of Object.entries(init.headers as Record<string, string>)) {
        headers[k] = String(v);
      }
    }

    const reqRecord: RecordedRequest = {
      url,
      method,
      headers,
      body: init?.body
    };
    recorded.push(reqRecord);

    if (customHandlers) {
      for (const [pattern, handler] of Object.entries(customHandlers)) {
        if (url.includes(pattern)) {
          const out = handler(reqRecord);
          if (out instanceof Response) {
            return out;
          }
          return new Response(JSON.stringify(out), {
            status: 200,
            headers: { "Content-Type": "application/json" }
          });
        }
      }
    }

    if (url.endsWith("config.private.json")) {
      return new Response(
        JSON.stringify({
          SERVER: "/api/gspLive_backend",
          Product: "private",
          ApiPrefix: "/api",
          userId: "gudu|0123456789",
          trialVersion: false
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    }

    if (url.includes("/version")) {
      return new Response(
        JSON.stringify({
          version: {
            gsp: { version: "3.0.9.5", "release.date": "2026-01-01" },
            backend: { version: "8.2.2.15", "release.date": "2026-01-01" },
            support: {
              "password.security.validate": false,
              "regular.job": true,
              "login.disable": false
            },
            "machine.code": "MC-9988-CLEAN"
          }
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    }

    if (url.includes("/license")) {
      return new Response(
        JSON.stringify({
          code: 200,
          data: {
            "Product name": "Gudu SQLFlow",
            "Supported databases": "All 48 supported SQL dialects"
          }
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    }

    if (url.includes("/user/info")) {
      return new Response(
        JSON.stringify({
          code: 200,
          data: {
            userId: "gudu|0123456789",
            userEmail: "guest@sqlflow.local",
            userType: "enterprise"
          }
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    }

    if (url.includes("/user/getUserSetting")) {
      return new Response(
        JSON.stringify({
          code: 200,
          data: { showTransform: true, defaultDistance: 3 }
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    }

    if (
      url.includes("/sqlflow/generation/sqlflow/graph/call_lineage") ||
      url.includes("/demo/callRelation")
    ) {
      return new Response(
        JSON.stringify({
          code: 200,
          data: {
            callGraph: {
              nodes: [
                {
                  id: "proc_refresh",
                  name: "SP_REFRESH_REVENUE",
                  kind: "PROCEDURE",
                  package: "PKG_FINANCE",
                  tableAccesses: [
                    { table: "orders", access: "READ" },
                    { table: "customer_revenue", access: "READ_WRITE" }
                  ],
                  coordinates: [{ x: 1, y: 1 }]
                },
                {
                  id: "fn_calc_tax",
                  name: "FN_CALC_TAX",
                  kind: "FUNCTION",
                  package: "PKG_FINANCE",
                  tableAccesses: [{ table: "tax_rates", access: "READ" }]
                }
              ],
              edges: [{ id: "ce_1", source: "proc_refresh", target: "fn_calc_tax" }]
            }
          }
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    }

    if (url.includes("/sqlflow/generation/sqlflow/join-analysis")) {
      return new Response(JSON.stringify(SAMPLE_JOIN_ANALYSIS_GRAPH), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }

    if (
      url.includes("/sqlflow/generation/sqlflow/") ||
      url.includes("/sqlflow/share") ||
      url.includes("/getSharedGraph")
    ) {
      if (url.includes("/graph/image")) {
        return new Response(new Blob(["PNGDATA"], { type: "image/png" }), { status: 200 });
      }
      if (url.includes("listTableColumns")) {
        return new Response(
          JSON.stringify({
            code: 200,
            data: [
              { id: "col1", name: "order_id", dataType: "BIGINT", primaryKey: true },
              { id: "col2", name: "email", dataType: "VARCHAR(255)", unqiueKey: true }
            ]
          }),
          { status: 200, headers: { "Content-Type": "application/json" } }
        );
      }
      return new Response(
        JSON.stringify({
          code: 200,
          sessionId: "sess-clean-101",
          data: SAMPLE_LINEAGE_GRAPH
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    }

    if (url.includes("/demo/sqlformat")) {
      return new Response(
        JSON.stringify({
          code: 200,
          data: "SELECT\n  o.order_id,\n  SUM(o.amount) AS total_revenue\nFROM orders o;"
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    }

    if (url.includes("/sqlflow/generation/ai/status")) {
      return new Response(JSON.stringify({ enabled: true, model: "gemini-2.5-flash" }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }

    if (url.includes("/sqlflow/generation/ai/query")) {
      const sseStream = [
        'data: {"type":"metadata","nodes":3,"edges":2,"truncated":false}\n\n',
        'data: {"type":"chunk","text":"`customer_revenue.total_revenue` derives from "}\n\n',
        'data: {"type":"chunk","text":"`orders.amount` via `SUM(o.amount)`."}\n\n',
        'data: {"type":"complete","response":{"answer":"`customer_revenue.total_revenue` derives from `orders.amount` via `SUM(o.amount)`.","confidence":"high","referencedNodes":["t_orders"],"citations":[{"table":"orders","column":"amount"}]}}\n\n'
      ].join("");
      return new Response(sseStream, {
        status: 200,
        headers: { "Content-Type": "text/event-stream" }
      });
    }

    if (
      url.includes("/sqlflow/generation/ai/suggest") ||
      url.includes("/sqlflow/generation/ai/suggestions")
    ) {
      return new Response(
        JSON.stringify({
          code: 200,
          data: [{ question: "Where does total_revenue come from?", category: "upstream" }]
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    }

    return new Response(JSON.stringify({ code: 200, data: {} }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    });
  };

  const api = new SpaApiService({ fetchImpl: fetchMock });
  return { api, recorded };
}
