import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import SQLFlow, {
  type DependencyGraphV1,
  type SQLFlowWidgetInstance,
  type SqlflowJSON
} from "../index.js";

const SAMPLE_LINEAGE_JSON: SqlflowJSON = {
  mode: "global",
  sqlflow: {
    dbobjs: [
      {
        id: "t_customers",
        database: "DEFAULT",
        schema: "sales",
        name: "customers",
        type: "table",
        coordinates: [{ x: 4, y: 6, x1: 4, y1: 21 }],
        columns: [
          { id: "c_cust_id", name: "customer_id", dataType: "BIGINT", primaryKey: true },
          { id: "c_cust_email", name: "email", dataType: "VARCHAR(255)", unqiueKey: true }
        ]
      },
      {
        id: "t_orders",
        database: "DEFAULT",
        schema: "sales",
        name: "orders",
        type: "table",
        columns: [
          { id: "c_ord_id", name: "order_id", dataType: "BIGINT", primaryKey: true },
          { id: "c_ord_amt", name: "amount", dataType: "DECIMAL(12,2)" }
        ]
      },
      {
        id: "t_mart",
        database: "dw",
        schema: "analytics",
        name: "mart_revenue",
        type: "view",
        columns: [
          { id: "c_mart_email", name: "customer_email", dataType: "VARCHAR(255)" },
          { id: "c_mart_rev", name: "total_revenue", dataType: "DECIMAL(14,2)" }
        ]
      }
    ],
    relationships: [
      {
        id: "rel_email",
        type: "fdd",
        effectType: "select",
        sources: [
          {
            id: "c_cust_email",
            parentId: "t_customers",
            transforms: [{ type: "function", code: "LOWER(c.email)" }]
          }
        ],
        target: { id: "c_mart_email", parentId: "t_mart" }
      },
      {
        id: "rel_rev",
        type: "fdd",
        effectType: "select",
        sources: [
          {
            id: "c_ord_amt",
            parentId: "t_orders",
            transforms: [{ type: "function", code: "SUM(o.amount)" }]
          }
        ],
        target: { id: "c_mart_rev", parentId: "t_mart" }
      }
    ]
  }
};

const VALID_DEPENDENCY_GRAPH: DependencyGraphV1 = {
  version: "1.0",
  kind: "dependency-graph",
  direction: "upstream",
  focus: { nodeId: "dg_mart" },
  nodes: [
    {
      id: "dg_raw",
      label: "raw.events",
      table: "events",
      schema: "raw",
      type: "table",
      expandable: true,
      columns: [
        { id: "dg_raw_id", name: "event_id", dataType: "STRING", primaryKey: true },
        { id: "dg_raw_val", name: "payload_bytes", dataType: "BIGINT" }
      ]
    },
    {
      id: "dg_mart",
      label: "mart.event_metrics",
      table: "event_metrics",
      schema: "mart",
      type: "view",
      columns: [
        { id: "dg_mart_cnt", name: "event_count", dataType: "BIGINT" },
        { id: "dg_mart_bytes", name: "total_bytes", dataType: "BIGINT" }
      ]
    }
  ],
  edges: [
    {
      id: "dg_e1",
      source: "dg_raw",
      target: "dg_mart",
      relationType: "fdd",
      columnMappings: [
        {
          sourceColumnId: "dg_raw_id",
          targetColumnId: "dg_mart_cnt",
          relationType: "fdd",
          transformCode: "COUNT(event_id)"
        },
        {
          sourceColumnId: "dg_raw_val",
          targetColumnId: "dg_mart_bytes",
          relationType: "fdd",
          transformCode: "SUM(payload_bytes)"
        }
      ]
    }
  ]
};

describe("SQLFlow Widget SDK — Visualization, Navigation, Highlighting, Exports & Event Bus", () => {
  const activeWidgets: SQLFlowWidgetInstance[] = [];
  let hostEl: HTMLDivElement;

  beforeEach(() => {
    document.body.innerHTML = "";
    hostEl = document.createElement("div");
    hostEl.id = "sqlflow-canvas-host";
    hostEl.style.width = "1000px";
    hostEl.style.height = "680px";
    document.body.appendChild(hostEl);
  });

  afterEach(() => {
    while (activeWidgets.length > 0) {
      activeWidgets.pop()?.destroy();
    }
    document.body.innerHTML = "";
    vi.restoreAllMocks();
  });

  it("renders precomputed JSON via visualizeJson with ZERO backend HTTP calls and preserves unqiueKey badge (OBL-114)", async () => {
    const fetchSpy = vi.fn();
    const widget = await SQLFlow.init({
      container: hostEl,
      fetchImpl: fetchSpy as unknown as typeof fetch
    });
    activeWidgets.push(widget);

    const startEvents: unknown[] = [];
    const successEvents: Array<{ nodeCount: number; edgeCount: number }> = [];
    widget.on("visualizeStart", (e) => startEvents.push(e));
    widget.on("visualizeSuccess", (e) => successEvents.push(e));

    await widget.visualizeJson(SAMPLE_LINEAGE_JSON);

    expect(fetchSpy).not.toHaveBeenCalled();
    expect(startEvents).toHaveLength(1);
    expect(successEvents).toHaveLength(1);
    expect(successEvents[0]?.nodeCount).toBe(3);
    expect(successEvents[0]?.edgeCount).toBe(2);

    const doc = widget.getIframe()!.contentDocument!;
    expect(doc.querySelectorAll("[data-node-id]")).toHaveLength(3);
    expect(doc.querySelector('[data-column-id="c_cust_email"]')?.textContent).toContain("UK");

    // Rejects malformed JSON and fires visualizeError
    const errorEvents: string[] = [];
    widget.on("visualizeError", (e) => errorEvents.push(e.error));
    await expect(
      // @ts-expect-error testing invalid input
      widget.visualizeJson({ unexpected: true })
    ).rejects.toThrow(/Unrecognized lineage JSON/i);
    expect(errorEvents).toHaveLength(1);
  });

  it("validates and renders client-only DependencyGraph v1.0 via Dagre with ZERO backend calls (OBL-115)", async () => {
    const fetchSpy = vi.fn();
    const widget = await SQLFlow.init({
      container: hostEl,
      fetchImpl: fetchSpy as unknown as typeof fetch
    });
    activeWidgets.push(widget);

    await widget.visualizeDependencyGraph(VALID_DEPENDENCY_GRAPH);
    expect(fetchSpy).not.toHaveBeenCalled();

    const doc = widget.getIframe()!.contentDocument!;
    expect(doc.querySelector('[data-node-id="dg_raw"]')).not.toBeNull();
    expect(doc.querySelector('[data-node-id="dg_mart"]')).not.toBeNull();
    expect(doc.querySelector('[data-column-id="dg_mart_bytes"]')).not.toBeNull();

    //Also test Topological BFS fallback (`layout: false`)
    await widget.visualizeDependencyGraph(VALID_DEPENDENCY_GRAPH, { layout: false });
    expect(doc.querySelector('[data-node-id="dg_mart"]')).not.toBeNull();

    // Reject invalid DependencyGraph version or missing focus node
    const errors: string[] = [];
    widget.on("visualizeError", (e) => errors.push(e.error));
    await expect(
      widget.visualizeDependencyGraph({
        ...VALID_DEPENDENCY_GRAPH,
        focus: { nodeId: "unknown_node_id" }
      })
    ).rejects.toThrow(/focus\.nodeId/i);
    expect(errors).toHaveLength(1);
  });

  it("supports highlightTable, highlightColumn, clearHighlight, search, zoom, setOrientation, and XML/CSV/JSON/PNG exports (OBL-116)", async () => {
    const widget = await SQLFlow.init({
      container: hostEl,
      minimap: true,
      search: true
    });
    activeWidgets.push(widget);

    await widget.visualizeJson(SAMPLE_LINEAGE_JSON);
    const doc = widget.getIframe()!.contentDocument!;

    // Table & column path highlighting
    expect(widget.highlightTable("non_existent_table")).toBe(false);
    expect(widget.highlightTable("sales.customers")).toBe(true);

    expect(widget.highlightColumn("mart_revenue", "total_revenue")).toBe(true);
    const revColRow = doc.querySelector('[data-column-id="c_mart_rev"]');
    const srcAmtRow = doc.querySelector('[data-column-id="c_ord_amt"]');
    expect(revColRow?.classList.contains("highlight")).toBe(true);
    expect(srcAmtRow?.classList.contains("highlight")).toBe(true);

    widget.clearHighlight();
    expect(revColRow?.classList.contains("highlight")).toBe(false);

    // Search
    const hits = widget.search("customers");
    expect(hits.map((h) => h.name)).toEqual(["customers"]);

    // Zoom controls
    const initialScale = widget.getScale();
    const zoomedIn = widget.zoomIn();
    expect(zoomedIn).toBeGreaterThan(initialScale);
    const zoomedOut = widget.zoomOut();
    expect(zoomedOut).toBeLessThan(zoomedIn);
    expect(widget.resetZoom()).toBe(1);
    expect(widget.zoomToFit()).toBeGreaterThan(0);

    // Orientation LR <-> TB
    expect(widget.getOrientation()).toBe("LR");
    widget.setOrientation("TB");
    expect(widget.getOrientation()).toBe("TB");

    // Client-side Upstream / Downstream slicing & resetSlice
    await widget.upstream("mart_revenue.total_revenue", 1);
    expect(doc.querySelector('[data-node-id="t_orders"]')).not.toBeNull();
    expect(doc.querySelector('[data-node-id="t_customers"]')).toBeNull();

    widget.resetSlice();
    expect(doc.querySelector('[data-node-id="t_customers"]')).not.toBeNull();

    // Exports: JSON, <dlineage> XML, 15-col CSV, PNG data URL
    const exportedJson = JSON.parse(widget.exportJson()) as SqlflowJSON;
    expect(exportedJson.sqlflow?.dbobjs).toBeDefined();

    const xml = await widget.exportXml();
    expect(xml).toContain("<dlineage>");
    expect(xml).toContain('unqiueKey="true"');
    expect(xml).toContain("<relation ");

    const csv = await widget.exportCsv();
    const csvLines = csv.trim().split("\n");
    expect(csvLines[0]).toBe(
      "SourceServer,SourceDatabase,SourceSchema,SourceTable,SourceColumn,TargetServer,TargetDatabase,TargetSchema,TargetTable,TargetColumn,RelationType,EffectType,Coordinate,SqlFile,Transforms"
    );
    expect(csvLines[0]!.split(",")).toHaveLength(15);
    expect(csv).toContain("customers,email");
    expect(csv).toContain("LOWER(c.email)");

    const png = await widget.exportImage();
    expect(png.startsWith("data:image/png;base64,")).toBe(true);
  });

  it("emits typed Event Bus payloads and strips 'DEFAULT' qualifiers on click callbacks (OBL-117, 04 §11.5)", async () => {
    const onFieldPayloads: unknown[] = [];
    const onTablePayloads: unknown[] = [];
    const nodeClicks: unknown[] = [];
    const columnClicks: unknown[] = [];
    const edgeClicks: unknown[] = [];
    const canvasClicks: unknown[] = [];
    const sqlChanges: unknown[] = [];
    const vendorChanges: unknown[] = [];

    const widget = await SQLFlow.init({
      container: hostEl,
      onFieldClick: (p) => onFieldPayloads.push(p),
      onTableNameClick: (p) => onTablePayloads.push(p)
    });
    activeWidgets.push(widget);

    widget
      .on("nodeClick", (p) => nodeClicks.push(p))
      .on("columnClick", (p) => columnClicks.push(p))
      .on("edgeClick", (p) => edgeClicks.push(p))
      .on("canvasClick", (p) => canvasClicks.push(p))
      .on("sqlChange", (p) => sqlChanges.push(p))
      .on("vendorChange", (p) => vendorChanges.push(p));

    await widget.visualizeJson(SAMPLE_LINEAGE_JSON);
    const doc = widget.getIframe()!.contentDocument!;

    // Click table header -> strips database: "DEFAULT", preserves schema: "sales"
    const custHeader = doc.querySelector<HTMLElement>(
      '[data-testid="node-header-t_customers"]'
    );
    expect(custHeader).not.toBeNull();
    custHeader!.click();
    expect(onTablePayloads[0]).toEqual({
      schema: "sales",
      table: "customers"
    });
    expect(nodeClicks).toHaveLength(1);

    // Click column row -> strips database: "DEFAULT", preserves target database: "dw" (`04 §11.5`)
    const emailRow = doc.querySelector<HTMLElement>('[data-column-id="c_cust_email"]');
    emailRow!.click();
    expect(onFieldPayloads[0]).toEqual({
      schema: "sales",
      table: "customers",
      tableType: "table",
      column: "email",
      source: {
        columns: [],
        tables: []
      },
      target: {
        columns: [
          {
            database: "dw",
            schema: "analytics",
            table: "mart_revenue",
            tableType: "view",
            column: "customer_email"
          }
        ],
        tables: [
          {
            database: "dw",
            schema: "analytics",
            table: "mart_revenue",
            tableType: "view"
          }
        ]
      }
    });
    expect(columnClicks).toHaveLength(1);

    // Click edge hit path (`${rel.id}_${i}` -> `rel_email_0`)
    const edgeHit = doc.querySelector<SVGPathElement>('[data-testid="edge-hit-rel_email_0"]');
    expect(edgeHit).not.toBeNull();
    const IframeMouseEvent = doc.defaultView?.MouseEvent ?? MouseEvent;
    edgeHit!.dispatchEvent(new IframeMouseEvent("click", { bubbles: true }));
    expect(edgeClicks).toHaveLength(1);

    // Click canvas background
    const bgSurface = doc.querySelector<HTMLElement>('[data-testid="sqlflow-scaled-surface"]');
    bgSurface!.dispatchEvent(new IframeMouseEvent("click", { bubbles: true }));
    expect(canvasClicks).toHaveLength(1);

    // Vendor & SQL changes + removeEventListener (`off`)
    widget.vendor.set("bigquery");
    widget.sqltext.set("SELECT * FROM sales.orders;");
    expect(vendorChanges).toEqual([{ dbvendor: "bigquery", previousVendor: "oracle" }]);
    expect(sqlChanges).toEqual([
      { sqltext: "SELECT * FROM sales.orders;", dbvendor: "bigquery" }
    ]);

    const onceSpy = vi.fn();
    widget.once("vendorChange", onceSpy);
    widget.vendor.set("mysql");
    widget.vendor.set("snowflake");
    expect(onceSpy).toHaveBeenCalledTimes(1);
  });

  it("executes backend visualization methods (SQL, ER, Join, Job, Upstream) with exact wire headers and params", async () => {
    const requests: Array<{ url: string; method: string; auth: string; body: string }> = [];

    const mockFetch: typeof fetch = async (input, initReq) => {
      const url = String(input);
      const headers = (initReq?.headers ?? {}) as Record<string, string>;
      const rawBody =
        typeof initReq?.body === "string"
          ? initReq.body
          : initReq?.body instanceof FormData
            ? Array.from(initReq.body.entries())
                .map(([k, v]) => `${k}=${String(v)}`)
                .join("&")
            : "";

      requests.push({
        url,
        method: initReq?.method ?? "GET",
        auth: headers.Authorization ?? "",
        body: rawBody
      });

      if (url.endsWith("/sqlflow/generation/sqlflow/join-analysis")) {
        return new Response(
          JSON.stringify({
            schemaVersion: "join-analysis.v1",
            queryBlocks: [{ queryBlockId: "qb1", label: "Main", kind: "main" }],
            nodes: [
              { id: "n1", type: "table", data: { label: "orders" } },
              {
                id: "j1",
                type: "join",
                data: {
                  label: "CROSS JOIN promos",
                  join: { joinType: "CROSS", clause: "NONE", predicates: [] }
                }
              }
            ],
            edges: [{ id: "e1", source: "n1", target: "j1" }],
            diagnostics: [
              {
                id: "d1",
                severity: "block",
                roles: ["all"],
                code: "cartesian_join",
                message: "Cartesian join detected"
              }
            ]
          }),
          { status: 200 }
        );
      }

      if (url.endsWith("/sqlflow/job/submitPersistJob")) {
        return new Response(JSON.stringify({ code: 200, data: { jobId: "job_persist_99" } }), {
          status: 200
        });
      }

      return new Response(
        JSON.stringify({
          code: 200,
          sessionId: "sess_abc_123",
          data: SAMPLE_LINEAGE_JSON
        }),
        { status: 200 }
      );
    };

    const widget = await SQLFlow.init({
      container: hostEl,
      token: "tok_secret_42",
      fetchImpl: mockFetch
    });
    activeWidgets.push(widget);

    const jobEvents: string[] = [];
    widget.on("jobSelect", (e) => jobEvents.push(e.jobId));

    // 1. visualizeSQL
    await widget.visualizeSQL("SELECT * FROM sales.customers", "snowflake");
    expect(requests[0]?.url).toBe("/gspLive_backend/sqlflow/generation/sqlflow/graph");
    expect(requests[0]?.auth).toBe("Token tok_secret_42");
    expect(requests[0]?.body).toContain("userId=gudu%7C0123456789");
    expect(requests[0]?.body).toContain("dbvendor=dbvsnowflake");

    // 2. Backend upstream (since sessionId === "sess_abc_123" is active)
    await widget.upstream("sales.customers", 3);
    expect(requests[1]?.url).toBe("/gspLive_backend/sqlflow/generation/sqlflow/upstreamGraph");
    expect(requests[1]?.body).toContain("distance=3");

    // 3. visualizeER
    await widget.visualizeER("CREATE TABLE t (id INT PRIMARY KEY);", "postgresql");
    expect(requests[2]?.url).toBe(
      "/gspLive_backend/sqlflow/generation/sqlflow/erdiagramSelectGraph"
    );
    const doc = widget.getIframe()!.contentDocument!;
    expect(doc.querySelector('[data-testid="sqlflow-er-canvas"]')).not.toBeNull();

    // 4. visualizeJoin
    await widget.visualizeJoin("SELECT * FROM orders CROSS JOIN promos;", "postgresql");
    expect(requests[3]?.url).toBe(
      "/gspLive_backend/sqlflow/generation/sqlflow/join-analysis"
    );
    expect(doc.querySelector('[data-testid="join-diagnostic-cartesian_join"]')).not.toBeNull();

    // 5. visualizeJob + job.lineage.getNodes()
    await widget.visualizeJob("job_warehouse_01", { schema: "sales", table: "customers" });
    expect(jobEvents).toContain("job_warehouse_01");
    expect(widget.job.lineage.getNodes().length).toBeGreaterThan(0);

    // 6. submitPersistJob RSA-encrypts JDBC password (never sends plaintext)
    const persistRes = await widget.submitPersistJob({
      dbvendor: "postgresql",
      jobName: "nightly_sync",
      sqlsource: "fromDatabase",
      hostname: "db.internal",
      port: 5432,
      account: "etl_user",
      password: "SuperSecretJdbcPassword!"
    });
    expect(persistRes).toEqual({ jobId: "job_persist_99" });
    const persistReq = requests.find((r) => r.url.endsWith("/sqlflow/job/submitPersistJob"))!;
    expect(persistReq.body).not.toContain("SuperSecretJdbcPassword!");
    expect(persistReq.body).toContain("password=");
  });

  it("activates >200-object Summary Interstitial Gate on large warehouse graphs (Demo 13)", async () => {
    const widget = await SQLFlow.init({ container: hostEl });
    activeWidgets.push(widget);

    const dbobjs = Array.from({ length: 215 }, (_, idx) => ({
      id: `tbl_${idx}`,
      schema: "dw",
      name: `fact_table_${idx}`,
      type: "table",
      columns: [{ id: `col_${idx}`, name: "metric_val", dataType: "NUMERIC" }]
    }));

    await widget.visualizeJson({
      mode: "global",
      sqlflow: { dbobjs, relationships: [] }
    });

    const doc = widget.getIframe()!.contentDocument!;
    expect(doc.querySelector('[data-testid="sqlflow-summary-interstitial"]')).not.toBeNull();
  });
});
