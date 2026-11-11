/**
 * Shared Clean-Room Datasets & Helper Utilities for SQLFlow Widget SDK 3.6.16 Demos (1..16).
 */
(function (global) {
  "use strict";

  var ECOMMERCE_SQL = [
    "WITH customer_ltv AS (",
    "  SELECT",
    "    c.customer_id,",
    "    c.email,",
    "    c.region_code,",
    "    COUNT(o.order_id) AS order_count,",
    "    SUM(oi.quantity * oi.unit_price * (1 - oi.discount_pct)) AS net_revenue",
    "  FROM sales.customers c",
    "  INNER JOIN sales.orders o ON c.customer_id = o.customer_id",
    "  INNER JOIN sales.order_items oi ON o.order_id = oi.order_id",
    "  WHERE o.order_status = 'COMPLETED'",
    "  GROUP BY c.customer_id, c.email, c.region_code",
    ")",
    "INSERT INTO analytics.mart_customer_revenue (",
    "  customer_id,",
    "  customer_email,",
    "  region_name,",
    "  lifetime_orders,",
    "  lifetime_revenue",
    ")",
    "SELECT",
    "  ltv.customer_id,",
    "  LOWER(ltv.email) AS customer_email,",
    "  r.region_name,",
    "  ltv.order_count AS lifetime_orders,",
    "  ROUND(ltv.net_revenue, 2) AS lifetime_revenue",
    "FROM customer_ltv ltv",
    "LEFT JOIN ref.dim_regions r ON ltv.region_code = r.region_code;"
  ].join("\n");

  var ECOMMERCE_LINEAGE_JSON = {
    mode: "global",
    sqlflow: {
      dbobjs: [
        {
          id: "tbl_customers",
          database: "DEFAULT",
          schema: "sales",
          name: "customers",
          type: "table",
          coordinates: [{ x: 9, y: 8, x1: 9, y1: 23 }],
          columns: [
            { id: "col_cust_id", name: "customer_id", dataType: "BIGINT", primaryKey: true, coordinates: [{ x: 3, y: 7, x1: 3, y1: 18 }] },
            { id: "col_cust_email", name: "email", dataType: "VARCHAR(255)", unqiueKey: true, coordinates: [{ x: 4, y: 7, x1: 4, y1: 12 }] },
            { id: "col_cust_region", name: "region_code", dataType: "VARCHAR(16)", foreignKey: true, coordinates: [{ x: 5, y: 7, x1: 5, y1: 18 }] }
          ]
        },
        {
          id: "tbl_orders",
          database: "DEFAULT",
          schema: "sales",
          name: "orders",
          type: "table",
          coordinates: [{ x: 10, y: 14, x1: 10, y1: 26 }],
          columns: [
            { id: "col_ord_id", name: "order_id", dataType: "BIGINT", primaryKey: true, coordinates: [{ x: 6, y: 13, x1: 6, y1: 21 }] },
            { id: "col_ord_cust_id", name: "customer_id", dataType: "BIGINT", foreignKey: true, coordinates: [{ x: 10, y: 50, x1: 10, y1: 61 }] },
            { id: "col_ord_status", name: "order_status", dataType: "VARCHAR(32)", coordinates: [{ x: 12, y: 11, x1: 12, y1: 23 }] }
          ]
        },
        {
          id: "tbl_order_items",
          database: "DEFAULT",
          schema: "sales",
          name: "order_items",
          type: "table",
          coordinates: [{ x: 11, y: 14, x1: 11, y1: 31 }],
          columns: [
            { id: "col_oi_order_id", name: "order_id", dataType: "BIGINT", foreignKey: true, coordinates: [{ x: 11, y: 55, x1: 11, y1: 63 }] },
            { id: "col_oi_qty", name: "quantity", dataType: "NUMERIC(10,2)", coordinates: [{ x: 7, y: 12, x1: 7, y1: 20 }] },
            { id: "col_oi_price", name: "unit_price", dataType: "NUMERIC(12,2)", coordinates: [{ x: 7, y: 26, x1: 7, y1: 36 }] },
            { id: "col_oi_disc", name: "discount_pct", dataType: "NUMERIC(5,2)", coordinates: [{ x: 7, y: 47, x1: 7, y1: 59 }] }
          ]
        },
        {
          id: "tbl_dim_regions",
          database: "DEFAULT",
          schema: "ref",
          name: "dim_regions",
          type: "table",
          coordinates: [{ x: 29, y: 11, x1: 29, y1: 26 }],
          columns: [
            { id: "col_reg_code", name: "region_code", dataType: "VARCHAR(16)", primaryKey: true, coordinates: [{ x: 29, y: 51, x1: 29, y1: 62 }] },
            { id: "col_reg_name", name: "region_name", dataType: "VARCHAR(120)", unqiueKey: true, coordinates: [{ x: 25, y: 5, x1: 25, y1: 16 }] }
          ]
        },
        {
          id: "cte_customer_ltv",
          database: "DEFAULT",
          schema: "DEFAULT",
          name: "customer_ltv",
          type: "view",
          subType: "with_cte",
          coordinates: [{ x: 1, y: 6, x1: 1, y1: 18 }],
          columns: [
            { id: "col_ltv_cust_id", name: "customer_id", dataType: "BIGINT" },
            { id: "col_ltv_email", name: "email", dataType: "VARCHAR(255)" },
            { id: "col_ltv_region", name: "region_code", dataType: "VARCHAR(16)" },
            { id: "col_ltv_cnt", name: "order_count", dataType: "BIGINT" },
            { id: "col_ltv_rev", name: "net_revenue", dataType: "NUMERIC(14,2)" }
          ]
        },
        {
          id: "tbl_mart_customer_revenue",
          database: "warehouse",
          schema: "analytics",
          name: "mart_customer_revenue",
          type: "table",
          coordinates: [{ x: 15, y: 13, x1: 15, y1: 44 }],
          columns: [
            { id: "col_mart_cust_id", name: "customer_id", dataType: "BIGINT", primaryKey: true },
            { id: "col_mart_email", name: "customer_email", dataType: "VARCHAR(255)", unqiueKey: true },
            { id: "col_mart_region", name: "region_name", dataType: "VARCHAR(120)" },
            { id: "col_mart_orders", name: "lifetime_orders", dataType: "BIGINT" },
            { id: "col_mart_rev", name: "lifetime_revenue", dataType: "NUMERIC(14,2)" }
          ]
        }
      ],
      relationships: [
        {
          id: "rel_1",
          type: "fdd",
          effectType: "select",
          sources: [{ id: "col_cust_id", parentId: "tbl_customers", transforms: [{ type: "simple", code: "c.customer_id" }] }],
          target: { id: "col_ltv_cust_id", parentId: "cte_customer_ltv" }
        },
        {
          id: "rel_2",
          type: "fdd",
          effectType: "select",
          sources: [{ id: "col_cust_email", parentId: "tbl_customers", transforms: [{ type: "simple", code: "c.email" }] }],
          target: { id: "col_ltv_email", parentId: "cte_customer_ltv" }
        },
        {
          id: "rel_3",
          type: "fdd",
          effectType: "select",
          sources: [{ id: "col_cust_region", parentId: "tbl_customers", transforms: [{ type: "simple", code: "c.region_code" }] }],
          target: { id: "col_ltv_region", parentId: "cte_customer_ltv" }
        },
        {
          id: "rel_4",
          type: "fdd",
          effectType: "select",
          sources: [{ id: "col_ord_id", parentId: "tbl_orders", transforms: [{ type: "function", code: "COUNT(o.order_id)" }] }],
          target: { id: "col_ltv_cnt", parentId: "cte_customer_ltv" }
        },
        {
          id: "rel_5",
          type: "fdd",
          effectType: "select",
          sources: [{ id: "col_oi_qty", parentId: "tbl_order_items", transforms: [{ type: "expression", code: "SUM(oi.quantity * oi.unit_price * (1 - oi.discount_pct))" }] }],
          target: { id: "col_ltv_rev", parentId: "cte_customer_ltv" }
        },
        {
          id: "rel_6",
          type: "fdd",
          effectType: "select",
          sources: [{ id: "col_oi_price", parentId: "tbl_order_items", transforms: [{ type: "expression", code: "SUM(oi.quantity * oi.unit_price * (1 - oi.discount_pct))" }] }],
          target: { id: "col_ltv_rev", parentId: "cte_customer_ltv" }
        },
        {
          id: "rel_7",
          type: "fdd",
          effectType: "select",
          sources: [{ id: "col_oi_disc", parentId: "tbl_order_items", transforms: [{ type: "expression", code: "1 - oi.discount_pct" }] }],
          target: { id: "col_ltv_rev", parentId: "cte_customer_ltv" }
        },
        {
          id: "rel_8",
          type: "fdr",
          effectType: "select",
          sources: [{ id: "col_ord_status", parentId: "tbl_orders", transforms: [{ type: "expression", code: "o.order_status = 'COMPLETED'" }] }],
          target: { id: "col_ltv_rev", parentId: "cte_customer_ltv" }
        },
        {
          id: "rel_9",
          type: "fdd",
          effectType: "insert",
          sources: [{ id: "col_ltv_cust_id", parentId: "cte_customer_ltv" }],
          target: { id: "col_mart_cust_id", parentId: "tbl_mart_customer_revenue" }
        },
        {
          id: "rel_10",
          type: "fdd",
          effectType: "insert",
          sources: [{ id: "col_ltv_email", parentId: "cte_customer_ltv", transforms: [{ type: "function", code: "LOWER(ltv.email)" }] }],
          target: { id: "col_mart_email", parentId: "tbl_mart_customer_revenue" }
        },
        {
          id: "rel_11",
          type: "fdd",
          effectType: "insert",
          sources: [{ id: "col_reg_name", parentId: "tbl_dim_regions" }],
          target: { id: "col_mart_region", parentId: "tbl_mart_customer_revenue" }
        },
        {
          id: "rel_12",
          type: "fdr",
          effectType: "join",
          sources: [{ id: "col_ltv_region", parentId: "cte_customer_ltv", transforms: [{ type: "expression", code: "ltv.region_code = r.region_code" }] }],
          target: { id: "col_mart_region", parentId: "tbl_mart_customer_revenue" }
        },
        {
          id: "rel_13",
          type: "fdd",
          effectType: "insert",
          sources: [{ id: "col_ltv_cnt", parentId: "cte_customer_ltv" }],
          target: { id: "col_mart_orders", parentId: "tbl_mart_customer_revenue" }
        },
        {
          id: "rel_14",
          type: "fdd",
          effectType: "insert",
          sources: [{ id: "col_ltv_rev", parentId: "cte_customer_ltv", transforms: [{ type: "function", code: "ROUND(ltv.net_revenue, 2)" }] }],
          target: { id: "col_mart_rev", parentId: "tbl_mart_customer_revenue" }
        }
      ]
    }
  };

  var FINANCE_LEDGER_JSON = {
    mode: "global",
    sqlflow: {
      dbobjs: [
        {
          id: "fin_gl_entries",
          schema: "erp",
          name: "gl_journal_entries",
          type: "table",
          columns: [
            { id: "gl_entry_id", name: "entry_id", dataType: "BIGINT", primaryKey: true },
            { id: "gl_acct_code", name: "account_code", dataType: "VARCHAR(32)", foreignKey: true },
            { id: "gl_debit", name: "debit_amt", dataType: "DECIMAL(18,2)" },
            { id: "gl_credit", name: "credit_amt", dataType: "DECIMAL(18,2)" }
          ]
        },
        {
          id: "fin_chart_accounts",
          schema: "erp",
          name: "chart_of_accounts",
          type: "table",
          columns: [
            { id: "coa_code", name: "account_code", dataType: "VARCHAR(32)", primaryKey: true },
            { id: "coa_category", name: "ifrs_category", dataType: "VARCHAR(64)", unqiueKey: true }
          ]
        },
        {
          id: "fin_trial_balance",
          database: "finance_dw",
          schema: "reporting",
          name: "rpt_trial_balance",
          type: "view",
          columns: [
            { id: "tb_category", name: "ifrs_category", dataType: "VARCHAR(64)" },
            { id: "tb_net_balance", name: "net_balance", dataType: "DECIMAL(18,2)" }
          ]
        }
      ],
      relationships: [
        {
          id: "fin_r1",
          type: "fdd",
          effectType: "create_view",
          sources: [{ id: "coa_category", parentId: "fin_chart_accounts" }],
          target: { id: "tb_category", parentId: "fin_trial_balance" }
        },
        {
          id: "fin_r2",
          type: "fdd",
          effectType: "create_view",
          sources: [{ id: "gl_debit", parentId: "fin_gl_entries", transforms: [{ type: "expression", code: "SUM(gl.debit_amt - gl.credit_amt)" }] }],
          target: { id: "tb_net_balance", parentId: "fin_trial_balance" }
        },
        {
          id: "fin_r3",
          type: "fdd",
          effectType: "create_view",
          sources: [{ id: "gl_credit", parentId: "fin_gl_entries", transforms: [{ type: "expression", code: "SUM(gl.debit_amt - gl.credit_amt)" }] }],
          target: { id: "tb_net_balance", parentId: "fin_trial_balance" }
        }
      ]
    }
  };

  var ER_DDL_SQL = [
    "CREATE TABLE commerce.customers (",
    "  customer_id BIGINT PRIMARY KEY,",
    "  email VARCHAR(255) UNIQUE NOT NULL,",
    "  tier_code VARCHAR(24) NOT NULL,",
    "  created_at TIMESTAMP",
    ");",
    "",
    "CREATE TABLE commerce.products (",
    "  product_id BIGINT PRIMARY KEY,",
    "  sku VARCHAR(64) UNIQUE NOT NULL,",
    "  product_name VARCHAR(200) NOT NULL,",
    "  unit_price DECIMAL(12,2) NOT NULL",
    ");",
    "",
    "CREATE TABLE commerce.orders (",
    "  order_id BIGINT PRIMARY KEY,",
    "  customer_id BIGINT NOT NULL REFERENCES commerce.customers(customer_id),",
    "  order_number VARCHAR(40) UNIQUE NOT NULL,",
    "  ordered_at TIMESTAMP NOT NULL",
    ");",
    "",
    "CREATE TABLE commerce.order_items (",
    "  order_item_id BIGINT PRIMARY KEY,",
    "  order_id BIGINT NOT NULL REFERENCES commerce.orders(order_id),",
    "  product_id BIGINT NOT NULL REFERENCES commerce.products(product_id),",
    "  quantity INT NOT NULL,",
    "  line_amount DECIMAL(14,2) NOT NULL",
    ");"
  ].join("\n");

  var ER_DIAGRAM_JSON = {
    mode: "global",
    sqlflow: {
      dbobjs: [
        {
          id: "er_customers",
          schema: "commerce",
          name: "customers",
          type: "table",
          columns: [
            { id: "er_c_id", name: "customer_id", dataType: "BIGINT", primaryKey: true },
            { id: "er_c_email", name: "email", dataType: "VARCHAR(255)", unqiueKey: true },
            { id: "er_c_tier", name: "tier_code", dataType: "VARCHAR(24)" },
            { id: "er_c_created", name: "created_at", dataType: "TIMESTAMP" }
          ]
        },
        {
          id: "er_products",
          schema: "commerce",
          name: "products",
          type: "table",
          columns: [
            { id: "er_p_id", name: "product_id", dataType: "BIGINT", primaryKey: true },
            { id: "er_p_sku", name: "sku", dataType: "VARCHAR(64)", unqiueKey: true },
            { id: "er_p_name", name: "product_name", dataType: "VARCHAR(200)" },
            { id: "er_p_price", name: "unit_price", dataType: "DECIMAL(12,2)" }
          ]
        },
        {
          id: "er_orders",
          schema: "commerce",
          name: "orders",
          type: "table",
          columns: [
            { id: "er_o_id", name: "order_id", dataType: "BIGINT", primaryKey: true },
            { id: "er_o_cid", name: "customer_id", dataType: "BIGINT", foreignKey: true },
            { id: "er_o_num", name: "order_number", dataType: "VARCHAR(40)", unqiueKey: true },
            { id: "er_o_ts", name: "ordered_at", dataType: "TIMESTAMP" }
          ]
        },
        {
          id: "er_order_items",
          schema: "commerce",
          name: "order_items",
          type: "table",
          columns: [
            { id: "er_oi_id", name: "order_item_id", dataType: "BIGINT", primaryKey: true },
            { id: "er_oi_oid", name: "order_id", dataType: "BIGINT", foreignKey: true },
            { id: "er_oi_pid", name: "product_id", dataType: "BIGINT", foreignKey: true },
            { id: "er_oi_qty", name: "quantity", dataType: "INT" },
            { id: "er_oi_amt", name: "line_amount", dataType: "DECIMAL(14,2)" }
          ]
        }
      ],
      relationships: [
        {
          id: "fk_orders_customers",
          type: "er",
          effectType: "foreign_key",
          sources: [{ id: "er_o_cid", parentId: "er_orders" }],
          target: { id: "er_c_id", parentId: "er_customers" }
        },
        {
          id: "fk_items_orders",
          type: "er",
          effectType: "foreign_key",
          sources: [{ id: "er_oi_oid", parentId: "er_order_items" }],
          target: { id: "er_o_id", parentId: "er_orders" }
        },
        {
          id: "fk_items_products",
          type: "er",
          effectType: "foreign_key",
          sources: [{ id: "er_oi_pid", parentId: "er_order_items" }],
          target: { id: "er_p_id", parentId: "er_products" }
        }
      ]
    }
  };

  var DEPENDENCY_GRAPH_V1 = {
    version: "1.0",
    kind: "dependency-graph",
    direction: "upstream",
    focus: {
      nodeId: "node_exec_kpi"
    },
    nodes: [
      {
        id: "node_raw_payments",
        label: "raw.stripe_charges",
        table: "stripe_charges",
        database: "lakehouse",
        schema: "raw",
        type: "table",
        expandable: true,
        columns: [
          { id: "c_charge_id", name: "charge_id", dataType: "STRING", primaryKey: true },
          { id: "c_amount_cents", name: "amount_cents", dataType: "BIGINT" },
          { id: "c_currency", name: "currency", dataType: "STRING" }
        ]
      },
      {
        id: "node_fx_rates",
        label: "ref.daily_fx_rates",
        table: "daily_fx_rates",
        database: "lakehouse",
        schema: "ref",
        type: "table",
        columns: [
          { id: "c_fx_ccy", name: "currency_code", dataType: "STRING", primaryKey: true },
          { id: "c_fx_usd", name: "usd_rate", dataType: "DECIMAL(12,6)" }
        ]
      },
      {
        id: "node_stg_settlements",
        label: "stg.normalized_settlements",
        table: "normalized_settlements",
        database: "lakehouse",
        schema: "stg",
        type: "view",
        columns: [
          { id: "c_stg_id", name: "settlement_id", dataType: "STRING" },
          { id: "c_stg_usd", name: "settled_usd", dataType: "DECIMAL(16,2)" }
        ]
      },
      {
        id: "node_exec_kpi",
        label: "mart.fct_executive_arr",
        table: "fct_executive_arr",
        database: "lakehouse",
        schema: "mart",
        type: "table",
        columns: [
          { id: "c_kpi_txn_cnt", name: "settled_charge_count", dataType: "BIGINT" },
          { id: "c_kpi_arr_usd", name: "recognized_arr_usd", dataType: "DECIMAL(18,2)" }
        ]
      }
    ],
    edges: [
      {
        id: "dg_edge_1",
        source: "node_raw_payments",
        target: "node_stg_settlements",
        relationType: "fdd",
        columnMappings: [
          { sourceColumnId: "c_charge_id", targetColumnId: "c_stg_id", relationType: "fdd" },
          {
            sourceColumnId: "c_amount_cents",
            targetColumnId: "c_stg_usd",
            relationType: "fdd",
            transformCode: "(amount_cents / 100.0) * fx.usd_rate"
          }
        ]
      },
      {
        id: "dg_edge_2",
        source: "node_fx_rates",
        target: "node_stg_settlements",
        relationType: "fdd",
        columnMappings: [
          {
            sourceColumnId: "c_fx_usd",
            targetColumnId: "c_stg_usd",
            relationType: "fdd",
            transformCode: "fx.usd_rate"
          },
          {
            sourceColumnId: "c_fx_ccy",
            targetColumnId: "c_stg_usd",
            relationType: "fdr",
            transformCode: "p.currency = fx.currency_code"
          }
        ]
      },
      {
        id: "dg_edge_3",
        source: "node_stg_settlements",
        target: "node_exec_kpi",
        relationType: "fdd",
        columnMappings: [
          {
            sourceColumnId: "c_stg_id",
            targetColumnId: "c_kpi_txn_cnt",
            relationType: "fdd",
            transformCode: "COUNT(DISTINCT settlement_id)"
          },
          {
            sourceColumnId: "c_stg_usd",
            targetColumnId: "c_kpi_arr_usd",
            relationType: "fdd",
            transformCode: "SUM(settled_usd) * 12"
          }
        ]
      }
    ]
  };

  var JOIN_ANALYSIS_SQL = [
    "SELECT",
    "  o.order_id,",
    "  c.email,",
    "  s.shipment_status,",
    "  promo.campaign_code,",
    "  mgr.email AS account_owner_email",
    "FROM sales.orders o",
    "INNER JOIN sales.customers c ON o.customer_id = c.customer_id",
    "LEFT JOIN logistics.shipments s ON o.order_id = s.order_id",
    "CROSS JOIN marketing.active_campaigns promo",
    "LEFT JOIN sales.customers mgr ON c.account_manager_id = mgr.customer_id",
    "WHERE s.delivered_at IS NOT NULL;"
  ].join("\n");

  var JOIN_ANALYSIS_GRAPH = {
    schemaVersion: "join-analysis.v1",
    sqlText: JOIN_ANALYSIS_SQL,
    dbvendor: "dbvpostgresql",
    summary: {
      tableCount: 5,
      joinCount: 4,
      diagnosticCount: 3,
      queryBlockCount: 1
    },
    queryBlocks: [
      { queryBlockId: "qb_main", label: "Main Query", kind: "main" }
    ],
    nodes: [
      {
        id: "jn_orders",
        type: "table",
        data: {
          label: "sales.orders (o)",
          queryBlockId: "qb_main",
          table: { schema: "sales", name: "orders", alias: "o" }
        }
      },
      {
        id: "jn_customers",
        type: "table",
        data: {
          label: "sales.customers (c)",
          queryBlockId: "qb_main",
          table: { schema: "sales", name: "customers", alias: "c" }
        }
      },
      {
        id: "jn_join_1",
        type: "join",
        data: {
          label: "INNER JOIN customers",
          queryBlockId: "qb_main",
          join: {
            joinType: "INNER",
            clause: "ON",
            cardinalityHint: "many_to_one",
            predicates: [
              { predicateId: "p1", raw: "o.customer_id = c.customer_id", kind: "equi" }
            ],
            sourceRange: { startLine: 8, startColumn: 1, endLine: 8, endColumn: 62 }
          }
        }
      },
      {
        id: "jn_shipments",
        type: "table",
        data: {
          label: "logistics.shipments (s)",
          queryBlockId: "qb_main",
          table: { schema: "logistics", name: "shipments", alias: "s" }
        }
      },
      {
        id: "jn_join_2",
        type: "join",
        data: {
          label: "LEFT JOIN shipments",
          queryBlockId: "qb_main",
          badges: [{ kind: "fan_out", level: "medium" }],
          join: {
            joinType: "LEFT",
            clause: "ON",
            cardinalityHint: "one_to_many",
            predicates: [
              { predicateId: "p2", raw: "o.order_id = s.order_id", kind: "equi" }
            ],
            sourceRange: { startLine: 9, startColumn: 1, endLine: 9, endColumn: 59 }
          }
        }
      },
      {
        id: "jn_campaigns",
        type: "table",
        data: {
          label: "marketing.active_campaigns (promo)",
          queryBlockId: "qb_main",
          table: { schema: "marketing", name: "active_campaigns", alias: "promo" }
        }
      },
      {
        id: "jn_join_3",
        type: "join",
        data: {
          label: "CROSS JOIN active_campaigns",
          queryBlockId: "qb_main",
          badges: [{ kind: "cartesian_risk", level: "high" }],
          join: {
            joinType: "CROSS",
            clause: "NONE",
            cardinalityHint: "many_to_many",
            predicates: [],
            sourceRange: { startLine: 10, startColumn: 1, endLine: 10, endColumn: 44 }
          }
        }
      },
      {
        id: "jn_join_4",
        type: "join",
        data: {
          label: "LEFT SELF JOIN customers (mgr)",
          queryBlockId: "qb_main",
          badges: [{ kind: "self_join" }],
          join: {
            joinType: "LEFT",
            clause: "ON",
            patterns: ["self"],
            cardinalityHint: "many_to_one",
            predicates: [
              { predicateId: "p4", raw: "c.account_manager_id = mgr.customer_id", kind: "equi" }
            ],
            sourceRange: { startLine: 11, startColumn: 1, endLine: 11, endColumn: 74 }
          }
        }
      }
    ],
    edges: [
      { id: "je_1", source: "jn_orders", target: "jn_join_1" },
      { id: "je_2", source: "jn_customers", target: "jn_join_1" },
      { id: "je_3", source: "jn_join_1", target: "jn_join_2" },
      { id: "je_4", source: "jn_shipments", target: "jn_join_2" },
      { id: "je_5", source: "jn_join_2", target: "jn_join_3" },
      { id: "je_6", source: "jn_campaigns", target: "jn_join_3", data: { kind: "table_to_join", dashed: true } },
      { id: "je_7", source: "jn_join_3", target: "jn_join_4" }
    ],
    fragments: [
      {
        fragmentId: "frag_cross",
        nodeIds: ["jn_join_3", "jn_campaigns"],
        kind: "join_clause",
        text: "CROSS JOIN marketing.active_campaigns promo",
        sourceRange: { startLine: 10, startColumn: 1, endLine: 10, endColumn: 44 }
      },
      {
        fragmentId: "frag_where_outer",
        nodeIds: ["jn_join_2", "jn_shipments"],
        kind: "where_filter",
        text: "WHERE s.delivered_at IS NOT NULL",
        sourceRange: { startLine: 12, startColumn: 1, endLine: 12, endColumn: 33 }
      }
    ],
    diagnostics: [
      {
        id: "diag_cartesian",
        severity: "block",
        roles: ["all"],
        code: "cartesian_join",
        message: "Unconstrained CROSS JOIN against marketing.active_campaigns multiplies output cardinality",
        evidenceNodeIds: ["jn_join_3", "jn_campaigns"],
        fragmentIds: ["frag_cross"]
      },
      {
        id: "diag_outer_filter",
        severity: "warn",
        roles: ["data_engineer", "data_analyst"],
        code: "outer_join_filter_risk",
        message: "WHERE s.delivered_at IS NOT NULL converts LEFT JOIN logistics.shipments into implicit INNER JOIN",
        evidenceNodeIds: ["jn_join_2", "jn_shipments"],
        fragmentIds: ["frag_where_outer"]
      },
      {
        id: "diag_self_join",
        severity: "info",
        roles: ["all"],
        code: "self_join_detected",
        message: "Self-join detected on sales.customers (c -> mgr)",
        evidenceNodeIds: ["jn_join_4", "jn_customers"]
      }
    ]
  };

  var CALL_GRAPH_PAYLOAD = {
    callGraph: {
      nodes: [
        {
          id: "proc_nightly_close",
          name: "sp_run_nightly_close",
          kind: "PROCEDURE",
          package: "PKG_FINANCE_BATCH",
          tableAccesses: [{ table: "BATCH_CONTROL_LOG", access: "READ_WRITE" }]
        },
        {
          id: "fn_calc_fx",
          name: "fn_convert_to_usd",
          kind: "FUNCTION",
          package: "PKG_FX_RATES",
          tableAccesses: [{ table: "DAILY_FX_RATES", access: "READ" }]
        },
        {
          id: "proc_post_gl",
          name: "sp_post_gl_subledger",
          kind: "PROCEDURE",
          package: "PKG_FINANCE_BATCH",
          tableAccesses: [
            { table: "STG_INVOICES", access: "READ" },
            { table: "GL_JOURNAL_ENTRIES", access: "WRITE" }
          ]
        },
        {
          id: "trg_audit_gl",
          name: "trg_gl_audit_trail",
          kind: "TRIGGER",
          tableAccesses: [{ table: "GL_AUDIT_HISTORY", access: "WRITE" }]
        }
      ],
      edges: [
        { id: "cge_1", source: "proc_nightly_close", target: "proc_post_gl", callStmt: "PKG_FINANCE_BATCH.sp_post_gl_subledger()" },
        { id: "cge_2", source: "proc_post_gl", target: "fn_calc_fx", callStmt: "PKG_FX_RATES.fn_convert_to_usd(amount, ccy)" },
        { id: "cge_3", source: "proc_post_gl", target: "trg_audit_gl", callStmt: "AFTER INSERT ON GL_JOURNAL_ENTRIES" }
      ]
    }
  };

  /**
   * Generates a deterministic large enterprise warehouse lineage graph (`count` default 240 objects)
   * to exercise the `>200`-object Summary Interstitial Gate (`04 §6.5`, Demo 13).
   */
  function generateLargeWarehouseGraph(totalObjects) {
    var count = totalObjects || 240;
    var layers = ["raw", "stg", "int", "dim", "fct", "mart"];
    var domains = ["orders", "payments", "inventory", "logistics", "crm", "billing", "risk", "ledger"];
    var dbobjs = [];
    var relationships = [];
    var perLayer = Math.ceil(count / layers.length);

    for (var l = 0; l < layers.length; l++) {
      var layer = layers[l];
      for (var i = 0; i < perLayer && dbobjs.length < count; i++) {
        var domain = domains[i % domains.length];
        var id = "wh_" + layer + "_" + i;
        var tblName = layer + "_" + domain + "_" + String(i + 1).padStart(3, "0");
        dbobjs.push({
          id: id,
          database: "enterprise_dw",
          schema: layer,
          name: tblName,
          type: l >= 4 ? "view" : "table",
          columns: [
            { id: id + "_pk", name: domain + "_id", dataType: "BIGINT", primaryKey: true },
            { id: id + "_metric", name: domain + "_amount", dataType: "DECIMAL(18,2)" },
            { id: id + "_ts", name: "updated_at", dataType: "TIMESTAMP" }
          ]
        });

        if (l > 0) {
          var upstreamIdx = (l - 1) * perLayer + (i % perLayer);
          if (upstreamIdx < dbobjs.length) {
            var upObj = dbobjs[upstreamIdx];
            relationships.push({
              id: "wh_rel_" + l + "_" + i,
              type: "fdd",
              effectType: "select",
              sources: [{ id: upObj.id + "_metric", parentId: upObj.id }],
              target: { id: id + "_metric", parentId: id }
            });
          }
        }
      }
    }

    return {
      mode: "global",
      summary: {
        process: relationships.length,
        database: 1,
        schema: layers.length,
        table: Math.floor(dbobjs.length * 0.67),
        view: Math.ceil(dbobjs.length * 0.33),
        column: dbobjs.length * 3,
        relationship: relationships.length
      },
      sqlflow: {
        dbobjs: dbobjs,
        relationships: relationships
      }
    };
  }

  global.SQLFlowDemoFixtures = {
    ECOMMERCE_SQL: ECOMMERCE_SQL,
    ECOMMERCE_LINEAGE_JSON: ECOMMERCE_LINEAGE_JSON,
    FINANCE_LEDGER_JSON: FINANCE_LEDGER_JSON,
    ER_DDL_SQL: ER_DDL_SQL,
    ER_DIAGRAM_JSON: ER_DIAGRAM_JSON,
    DEPENDENCY_GRAPH_V1: DEPENDENCY_GRAPH_V1,
    JOIN_ANALYSIS_SQL: JOIN_ANALYSIS_SQL,
    JOIN_ANALYSIS_GRAPH: JOIN_ANALYSIS_GRAPH,
    CALL_GRAPH_PAYLOAD: CALL_GRAPH_PAYLOAD,
    generateLargeWarehouseGraph: generateLargeWarehouseGraph
  };
})(typeof window !== "undefined" ? window : globalThis);
