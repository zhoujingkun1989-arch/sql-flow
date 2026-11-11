/**
 * Categorized `Examples` Dropdown SQL Templates (`lineage`, `join`, `er`, `callgraph`)
 * and Client-Side Custom-SQL / Trial-Length Guards.
 * Reference: docs/re-spec/04-frontend-ui-and-widget.md §5.2, §5.3, §5.5.3.
 */

import {
  type ExampleFeature,
  type UiDialectKey,
  UI_DIALECTS,
  getUiDialect
} from "./catalog.js";
import { ONBOARDING_CUSTOMER_REVENUE_SQL, SAMPLE_SQL } from "./sampleSql.js";

export interface ExampleFeatureDescriptor {
  readonly feature: ExampleFeature;
  readonly label: string;
}

export const EXAMPLE_FEATURE_LABELS: Readonly<Record<ExampleFeature, string>> = {
  lineage: "Column Lineage",
  join: "Join Analysis",
  er: "ER Diagram",
  callgraph: "Call Graph"
};

export interface DialectExampleItem {
  readonly id: string;
  readonly dialect: UiDialectKey;
  readonly feature: ExampleFeature;
  readonly label: string;
  readonly sql: string;
}

function makeLineageSql(opts: {
  aggExpr: string;
  dateExpr: string;
  targetClause?: "view" | "ctas" | "upsert_pg" | "upsert_mysql" | "volatile" | "select_into" | "insert_overwrite";
}): string {
  const selectBody = `SELECT
    c.customer_id,
    c.customer_name,
    ${opts.aggExpr} AS product_list,
    SUM(oi.quantity * p.unit_price) AS total_revenue,
    ${opts.dateExpr} AS last_order
FROM orders o
JOIN order_items oi ON oi.order_id = o.order_id
JOIN products p ON p.product_id = oi.product_id
JOIN customers c ON c.customer_id = o.customer_id
GROUP BY c.customer_id, c.customer_name`;

  switch (opts.targetClause) {
    case "ctas":
      return `CREATE OR REPLACE TABLE customer_revenue AS\n${selectBody};`;
    case "upsert_pg":
      return `INSERT INTO customer_revenue (customer_id, customer_name, product_list, total_revenue, last_order)\n${selectBody}\nON CONFLICT (customer_id) DO UPDATE SET\n    product_list = EXCLUDED.product_list,\n    total_revenue = EXCLUDED.total_revenue,\n    last_order = EXCLUDED.last_order;`;
    case "upsert_mysql":
      return `INSERT INTO customer_revenue (customer_id, customer_name, product_list, total_revenue, last_order)\n${selectBody}\nON DUPLICATE KEY UPDATE\n    product_list = VALUES(product_list),\n    total_revenue = VALUES(total_revenue),\n    last_order = VALUES(last_order);`;
    case "volatile":
      return `CREATE VOLATILE TABLE customer_revenue AS (\n${selectBody}\n) WITH DATA ON COMMIT PRESERVE ROWS;`;
    case "select_into":
      return `SELECT\n    c.customer_id,\n    c.customer_name,\n    ${opts.aggExpr} AS product_list,\n    SUM(oi.quantity * p.unit_price) AS total_revenue,\n    ${opts.dateExpr} AS last_order\nINTO customer_revenue\nFROM orders o\nJOIN order_items oi ON oi.order_id = o.order_id\nJOIN products p ON p.product_id = oi.product_id\nJOIN customers c ON c.customer_id = o.customer_id\nGROUP BY c.customer_id, c.customer_name;`;
    case "insert_overwrite":
      return `INSERT OVERWRITE TABLE customer_revenue\n${selectBody};`;
    default:
      return `CREATE VIEW customer_revenue AS\n${selectBody};`;
  }
}

function makeJoinSql(opts?: {
  hint?: string;
  usingOrder?: boolean;
  tailClause?: string;
  outerType?: "LEFT JOIN" | "FULL OUTER JOIN";
}): string {
  const hint = opts?.hint ? `${opts.hint} ` : "";
  const oiJoin = opts?.usingOrder
    ? "JOIN order_items oi USING (order_id)"
    : "JOIN order_items oi ON oi.order_id = o.order_id";
  const whJoin = opts?.outerType ?? "LEFT JOIN";
  const tail = opts?.tailClause ? `\n${opts.tailClause}` : "";
  return `SELECT ${hint}
    o.order_id,
    c.customer_id,
    c.customer_name,
    p.product_id,
    p.product_name,
    w.warehouse_name,
    oi.quantity * p.unit_price AS line_amount
FROM orders o
${oiJoin}
JOIN products p ON p.product_id = oi.product_id
JOIN customers c ON c.customer_id = o.customer_id
${whJoin} warehouses w ON w.warehouse_id = p.warehouse_id
WHERE o.status = 'SHIPPED'${tail};`;
}

function makeErSql(opts?: {
  tableSuffix?: string;
  fkSuffix?: string;
  teradataIndex?: boolean;
}): string {
  const suffix = opts?.tableSuffix ? ` ${opts.tableSuffix}` : "";
  const fkSuffix = opts?.fkSuffix ? ` ${opts.fkSuffix}` : "";
  if (opts?.teradataIndex) {
    return `CREATE TABLE customers (
    customer_id INTEGER NOT NULL,
    customer_name VARCHAR(100),
    email VARCHAR(150),
    PRIMARY KEY (customer_id)
) UNIQUE PRIMARY INDEX (customer_id);

CREATE TABLE products (
    product_id INTEGER NOT NULL,
    product_name VARCHAR(100),
    unit_price DECIMAL(10,2),
    PRIMARY KEY (product_id)
) UNIQUE PRIMARY INDEX (product_id);

CREATE TABLE orders (
    order_id INTEGER NOT NULL,
    customer_id INTEGER NOT NULL,
    order_date DATE,
    status VARCHAR(20),
    PRIMARY KEY (order_id),
    FOREIGN KEY (customer_id) REFERENCES customers (customer_id)
) UNIQUE PRIMARY INDEX (order_id);

CREATE TABLE order_items (
    order_id INTEGER NOT NULL,
    product_id INTEGER NOT NULL,
    quantity INTEGER,
    PRIMARY KEY (order_id, product_id),
    FOREIGN KEY (order_id) REFERENCES orders (order_id),
    FOREIGN KEY (product_id) REFERENCES products (product_id)
) PRIMARY INDEX (order_id);`;
  }
  return `CREATE TABLE customers (
    customer_id INT PRIMARY KEY,
    customer_name VARCHAR(100) NOT NULL,
    email VARCHAR(150) UNIQUE
)${suffix};

CREATE TABLE products (
    product_id INT PRIMARY KEY,
    product_name VARCHAR(100) NOT NULL,
    unit_price DECIMAL(10,2) NOT NULL
)${suffix};

CREATE TABLE orders (
    order_id INT PRIMARY KEY,
    customer_id INT NOT NULL,
    order_date DATE NOT NULL,
    status VARCHAR(20),
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers (customer_id)${fkSuffix}
)${suffix};

CREATE TABLE order_items (
    order_id INT NOT NULL,
    product_id INT NOT NULL,
    quantity INT NOT NULL,
    CONSTRAINT pk_order_items PRIMARY KEY (order_id, product_id),
    CONSTRAINT fk_oi_order FOREIGN KEY (order_id) REFERENCES orders (order_id)${fkSuffix},
    CONSTRAINT fk_oi_product FOREIGN KEY (product_id) REFERENCES products (product_id)${fkSuffix}
)${suffix};`;
}

const PLSQL_CALLGRAPH_SQL = `CREATE OR REPLACE PACKAGE BODY order_workflow AS
  PROCEDURE log_event(p_order_id NUMBER, p_event VARCHAR2) IS
  BEGIN
    INSERT INTO audit_events (order_id, event_name, created_at)
    VALUES (p_order_id, p_event, SYSDATE);
  END log_event;

  FUNCTION calculate_total(p_order_id NUMBER) RETURN NUMBER IS
    v_total NUMBER;
  BEGIN
    SELECT SUM(oi.quantity * p.unit_price)
      INTO v_total
      FROM order_items oi
      JOIN products p ON p.product_id = oi.product_id
     WHERE oi.order_id = p_order_id;
    RETURN NVL(v_total, 0);
  END calculate_total;

  PROCEDURE process_payment(p_order_id NUMBER, p_amount NUMBER) IS
  BEGIN
    INSERT INTO payments (order_id, amount, paid_at)
    VALUES (p_order_id, p_amount, SYSDATE);
    log_event(p_order_id, 'PAYMENT_PROCESSED');
  END process_payment;

  PROCEDURE create_invoice(p_order_id NUMBER, p_amount NUMBER) IS
  BEGIN
    INSERT INTO invoices (order_id, total_amount, issued_at)
    VALUES (p_order_id, p_amount, SYSDATE);
    log_event(p_order_id, 'INVOICE_CREATED');
  END create_invoice;

  PROCEDURE fulfill_order(p_order_id NUMBER) IS
    v_amount NUMBER;
  BEGIN
    v_amount := calculate_total(p_order_id);
    process_payment(p_order_id, v_amount);
    create_invoice(p_order_id, v_amount);
    log_event(p_order_id, 'ORDER_FULFILLED');
  END fulfill_order;
END order_workflow;`;

const TSQL_CALLGRAPH_SQL = `CREATE PROCEDURE dbo.log_event
    @order_id INT,
    @event_name VARCHAR(60)
AS
BEGIN
    INSERT INTO dbo.audit_events (order_id, event_name, created_at)
    VALUES (@order_id, @event_name, GETDATE());
END;
GO

CREATE PROCEDURE dbo.process_payment
    @order_id INT,
    @amount DECIMAL(12,2)
AS
BEGIN
    INSERT INTO dbo.payments (order_id, amount, paid_at)
    VALUES (@order_id, @amount, GETDATE());
    EXEC dbo.log_event @order_id, 'PAYMENT_PROCESSED';
END;
GO

CREATE PROCEDURE dbo.create_invoice
    @order_id INT,
    @amount DECIMAL(12,2)
AS
BEGIN
    INSERT INTO dbo.invoices (order_id, total_amount, issued_at)
    VALUES (@order_id, @amount, GETDATE());
    EXEC dbo.log_event @order_id, 'INVOICE_CREATED';
END;
GO

CREATE PROCEDURE dbo.fulfill_order
    @order_id INT
AS
BEGIN
    DECLARE @total DECIMAL(12,2);
    SELECT @total = SUM(oi.quantity * p.unit_price)
    FROM dbo.order_items oi
    INNER JOIN dbo.products p ON p.product_id = oi.product_id
    WHERE oi.order_id = @order_id;

    EXEC dbo.process_payment @order_id, @total;
    EXEC dbo.create_invoice @order_id, @total;
    EXEC dbo.log_event @order_id, 'ORDER_FULFILLED';
END;
GO`;

const MYSQL_CALLGRAPH_SQL = `DELIMITER $$

CREATE PROCEDURE log_event(IN p_order_id INT, IN p_event VARCHAR(60))
BEGIN
    INSERT INTO audit_events (order_id, event_name, created_at)
    VALUES (p_order_id, p_event, NOW());
END$$

CREATE PROCEDURE process_payment(IN p_order_id INT, IN p_amount DECIMAL(12,2))
BEGIN
    INSERT INTO payments (order_id, amount, paid_at)
    VALUES (p_order_id, p_amount, NOW());
    CALL log_event(p_order_id, 'PAYMENT_PROCESSED');
END$$

CREATE PROCEDURE create_invoice(IN p_order_id INT, IN p_amount DECIMAL(12,2))
BEGIN
    INSERT INTO invoices (order_id, total_amount, issued_at)
    VALUES (p_order_id, p_amount, NOW());
    CALL log_event(p_order_id, 'INVOICE_CREATED');
END$$

CREATE PROCEDURE fulfill_order(IN p_order_id INT)
BEGIN
    DECLARE v_total DECIMAL(12,2);
    SELECT SUM(oi.quantity * p.unit_price) INTO v_total
    FROM order_items oi
    JOIN products p ON p.product_id = oi.product_id
    WHERE oi.order_id = p_order_id;

    CALL process_payment(p_order_id, v_total);
    CALL create_invoice(p_order_id, v_total);
    CALL log_event(p_order_id, 'ORDER_FULFILLED');
END$$

DELIMITER ;`;

const PG_CALLGRAPH_SQL = `CREATE OR REPLACE FUNCTION log_event(p_order_id INT, p_event TEXT) RETURNS VOID AS $$
BEGIN
    INSERT INTO audit_events (order_id, event_name, created_at)
    VALUES (p_order_id, p_event, CURRENT_TIMESTAMP);
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION calculate_total(p_order_id INT) RETURNS NUMERIC AS $$
DECLARE
    v_total NUMERIC;
BEGIN
    SELECT COALESCE(SUM(oi.quantity * p.unit_price), 0)
      INTO v_total
      FROM order_items oi
      JOIN products p ON p.product_id = oi.product_id
     WHERE oi.order_id = p_order_id;
    RETURN v_total;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION process_payment(p_order_id INT, p_amount NUMERIC) RETURNS VOID AS $$
BEGIN
    INSERT INTO payments (order_id, amount, paid_at)
    VALUES (p_order_id, p_amount, CURRENT_TIMESTAMP);
    PERFORM log_event(p_order_id, 'PAYMENT_PROCESSED');
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION create_invoice(p_order_id INT, p_amount NUMERIC) RETURNS VOID AS $$
BEGIN
    INSERT INTO invoices (order_id, total_amount, issued_at)
    VALUES (p_order_id, p_amount, CURRENT_TIMESTAMP);
    PERFORM log_event(p_order_id, 'INVOICE_CREATED');
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION fulfill_order(p_order_id INT) RETURNS VOID AS $$
DECLARE
    v_amount NUMERIC;
BEGIN
    v_amount := calculate_total(p_order_id);
    PERFORM process_payment(p_order_id, v_amount);
    PERFORM create_invoice(p_order_id, v_amount);
    PERFORM log_event(p_order_id, 'ORDER_FULFILLED');
END;
$$ LANGUAGE plpgsql;`;

const SNOWFLAKE_CALLGRAPH_SQL = `CREATE OR REPLACE PROCEDURE log_event(p_order_id INT, p_event STRING)
RETURNS STRING
LANGUAGE SQL
AS
$$
BEGIN
    INSERT INTO audit_events (order_id, event_name, created_at)
    VALUES (:p_order_id, :p_event, CURRENT_TIMESTAMP());
    RETURN 'OK';
END;
$$;

CREATE OR REPLACE PROCEDURE process_payment(p_order_id INT, p_amount NUMBER(12,2))
RETURNS STRING
LANGUAGE SQL
AS
$$
BEGIN
    INSERT INTO payments (order_id, amount, paid_at)
    VALUES (:p_order_id, :p_amount, CURRENT_TIMESTAMP());
    CALL log_event(:p_order_id, 'PAYMENT_PROCESSED');
    RETURN 'OK';
END;
$$;

CREATE OR REPLACE PROCEDURE create_invoice(p_order_id INT, p_amount NUMBER(12,2))
RETURNS STRING
LANGUAGE SQL
AS
$$
BEGIN
    INSERT INTO invoices (order_id, total_amount, issued_at)
    VALUES (:p_order_id, :p_amount, CURRENT_TIMESTAMP());
    CALL log_event(:p_order_id, 'INVOICE_CREATED');
    RETURN 'OK';
END;
$$;

CREATE OR REPLACE PROCEDURE fulfill_order(p_order_id INT)
RETURNS STRING
LANGUAGE SQL
AS
$$
DECLARE
    v_total NUMBER(12,2);
BEGIN
    SELECT SUM(oi.quantity * p.unit_price) INTO :v_total
    FROM order_items oi
    JOIN products p ON p.product_id = oi.product_id
    WHERE oi.order_id = :p_order_id;

    CALL process_payment(:p_order_id, :v_total);
    CALL create_invoice(:p_order_id, :v_total);
    CALL log_event(:p_order_id, 'ORDER_FULFILLED');
    RETURN 'FULFILLED';
END;
$$;`;

const BIGQUERY_CALLGRAPH_SQL = `CREATE OR REPLACE PROCEDURE analytics.log_event(p_order_id INT64, p_event STRING)
BEGIN
  INSERT INTO analytics.audit_events (order_id, event_name, created_at)
  VALUES (p_order_id, p_event, CURRENT_TIMESTAMP());
END;

CREATE OR REPLACE PROCEDURE analytics.process_payment(p_order_id INT64, p_amount NUMERIC)
BEGIN
  INSERT INTO analytics.payments (order_id, amount, paid_at)
  VALUES (p_order_id, p_amount, CURRENT_TIMESTAMP());
  CALL analytics.log_event(p_order_id, 'PAYMENT_PROCESSED');
END;

CREATE OR REPLACE PROCEDURE analytics.create_invoice(p_order_id INT64, p_amount NUMERIC)
BEGIN
  INSERT INTO analytics.invoices (order_id, total_amount, issued_at)
  VALUES (p_order_id, p_amount, CURRENT_TIMESTAMP());
  CALL analytics.log_event(p_order_id, 'INVOICE_CREATED');
END;

CREATE OR REPLACE PROCEDURE analytics.fulfill_order(p_order_id INT64)
BEGIN
  DECLARE v_total NUMERIC DEFAULT 0;
  SET v_total = (
    SELECT COALESCE(SUM(oi.quantity * p.unit_price), 0)
    FROM analytics.order_items oi
    JOIN analytics.products p ON p.product_id = oi.product_id
    WHERE oi.order_id = p_order_id
  );
  CALL analytics.process_payment(p_order_id, v_total);
  CALL analytics.create_invoice(p_order_id, v_total);
  CALL analytics.log_event(p_order_id, 'ORDER_FULFILLED');
END;`;

const SQLPL_CALLGRAPH_SQL = `CREATE PROCEDURE log_event(IN p_order_id INTEGER, IN p_event VARCHAR(60))
BEGIN
  INSERT INTO audit_events (order_id, event_name, created_at)
  VALUES (p_order_id, p_event, CURRENT_TIMESTAMP);
END;

CREATE PROCEDURE process_payment(IN p_order_id INTEGER, IN p_amount DECIMAL(12,2))
BEGIN
  INSERT INTO payments (order_id, amount, paid_at)
  VALUES (p_order_id, p_amount, CURRENT_TIMESTAMP);
  CALL log_event(p_order_id, 'PAYMENT_PROCESSED');
END;

CREATE PROCEDURE create_invoice(IN p_order_id INTEGER, IN p_amount DECIMAL(12,2))
BEGIN
  INSERT INTO invoices (order_id, total_amount, issued_at)
  VALUES (p_order_id, p_amount, CURRENT_TIMESTAMP);
  CALL log_event(p_order_id, 'INVOICE_CREATED');
END;

CREATE PROCEDURE fulfill_order(IN p_order_id INTEGER)
BEGIN
  DECLARE v_total DECIMAL(12,2);
  SELECT SUM(oi.quantity * p.unit_price) INTO v_total
  FROM order_items oi
  JOIN products p ON p.product_id = oi.product_id
  WHERE oi.order_id = p_order_id;

  CALL process_payment(p_order_id, v_total);
  CALL create_invoice(p_order_id, v_total);
  CALL log_event(p_order_id, 'ORDER_FULFILLED');
END;`;

const EXPLICIT_EXAMPLES_MAP: Partial<Record<UiDialectKey, Readonly<Partial<Record<ExampleFeature, string>>>>> = {
  oracle: {
    lineage: makeLineageSql({
      aggExpr: "LISTAGG(p.product_name, ', ') WITHIN GROUP (ORDER BY p.product_name)",
      dateExpr: "TO_CHAR(MAX(o.order_date), 'YYYY-MM-DD')"
    }),
    join: makeJoinSql({ tailClause: "FETCH FIRST 100 ROWS ONLY" }),
    er: makeErSql(),
    callgraph: PLSQL_CALLGRAPH_SQL
  },
  mysql: {
    lineage: makeLineageSql({
      aggExpr: "GROUP_CONCAT(p.product_name ORDER BY p.product_name SEPARATOR ', ')",
      dateExpr: "DATE_FORMAT(MAX(o.order_date), '%Y-%m-%d')",
      targetClause: "upsert_mysql"
    }),
    join: makeJoinSql({ usingOrder: true, tailClause: "LIMIT 100" }),
    er: makeErSql(),
    callgraph: MYSQL_CALLGRAPH_SQL
  },
  mssql: {
    lineage: makeLineageSql({
      aggExpr: "STRING_AGG(p.product_name, ', ')",
      dateExpr: "CONVERT(VARCHAR(10), MAX(o.order_date), 23)",
      targetClause: "select_into"
    }),
    join: makeJoinSql({ hint: "TOP (100)" }),
    er: makeErSql(),
    callgraph: TSQL_CALLGRAPH_SQL
  },
  postgresql: {
    lineage: makeLineageSql({
      aggExpr: "STRING_AGG(p.product_name, ', ' ORDER BY p.product_name)",
      dateExpr: "TO_CHAR(MAX(o.order_date), 'YYYY-MM-DD')",
      targetClause: "upsert_pg"
    }),
    join: makeJoinSql({ usingOrder: true, outerType: "FULL OUTER JOIN", tailClause: "LIMIT 100" }),
    er: makeErSql(),
    callgraph: PG_CALLGRAPH_SQL
  },
  snowflake: {
    lineage: makeLineageSql({
      aggExpr: "LISTAGG(p.product_name, ', ') WITHIN GROUP (ORDER BY p.product_name)",
      dateExpr: "TO_CHAR(MAX(o.order_date), 'YYYY-MM-DD')",
      targetClause: "ctas"
    }),
    join: makeJoinSql({
      tailClause: "QUALIFY ROW_NUMBER() OVER (PARTITION BY c.customer_id ORDER BY o.order_date DESC) = 1"
    }),
    er: makeErSql({ fkSuffix: "NOT ENFORCED" }),
    callgraph: SNOWFLAKE_CALLGRAPH_SQL
  },
  bigquery: {
    lineage: makeLineageSql({
      aggExpr: "STRING_AGG(p.product_name, ', ' ORDER BY p.product_name)",
      dateExpr: "FORMAT_DATE('%Y-%m-%d', MAX(o.order_date))",
      targetClause: "ctas"
    }),
    join: makeJoinSql({
      usingOrder: true,
      tailClause: "QUALIFY ROW_NUMBER() OVER (PARTITION BY c.customer_id ORDER BY o.order_date DESC) = 1"
    }),
    er: makeErSql({ fkSuffix: "NOT ENFORCED" }),
    callgraph: BIGQUERY_CALLGRAPH_SQL
  },
  athena: {
    lineage: makeLineageSql({
      aggExpr: "array_join(array_agg(p.product_name), ', ')",
      dateExpr: "date_format(MAX(o.order_date), '%Y-%m-%d')"
    }),
    join: makeJoinSql({ usingOrder: true, tailClause: "LIMIT 100" })
  },
  azuresql: {
    lineage: makeLineageSql({
      aggExpr: "STRING_AGG(p.product_name, ', ')",
      dateExpr: "CONVERT(VARCHAR(10), MAX(o.order_date), 23)",
      targetClause: "select_into"
    }),
    join: makeJoinSql({ hint: "TOP (100)" }),
    er: makeErSql(),
    callgraph: TSQL_CALLGRAPH_SQL
  },
  couchbase: {
    lineage: makeLineageSql({
      aggExpr: "ARRAY_AGG(p.product_name)",
      dateExpr: "MAX(o.order_date)"
    }),
    join: makeJoinSql({ tailClause: "LIMIT 100" })
  },
  databricks: {
    lineage: makeLineageSql({
      aggExpr: "concat_ws(', ', collect_list(p.product_name))",
      dateExpr: "date_format(MAX(o.order_date), 'yyyy-MM-dd')",
      targetClause: "ctas"
    }),
    join: makeJoinSql({ hint: "/*+ BROADCAST(c) */", tailClause: "LIMIT 100" }),
    er: makeErSql({ tableSuffix: "USING DELTA" })
  },
  db2: {
    lineage: makeLineageSql({
      aggExpr: "LISTAGG(p.product_name, ', ') WITHIN GROUP (ORDER BY p.product_name)",
      dateExpr: "VARCHAR_FORMAT(MAX(o.order_date), 'YYYY-MM-DD')"
    }),
    join: makeJoinSql({ tailClause: "FETCH FIRST 100 ROWS ONLY WITH UR" }),
    er: makeErSql(),
    callgraph: SQLPL_CALLGRAPH_SQL
  },
  gaussdb: {
    lineage: makeLineageSql({
      aggExpr: "STRING_AGG(p.product_name, ', ')",
      dateExpr: "TO_CHAR(MAX(o.order_date), 'YYYY-MM-DD')"
    }),
    join: makeJoinSql({ tailClause: "LIMIT 100" }),
    er: makeErSql(),
    callgraph: PLSQL_CALLGRAPH_SQL
  },
  greenplum: {
    lineage: makeLineageSql({
      aggExpr: "STRING_AGG(p.product_name, ', ')",
      dateExpr: "TO_CHAR(MAX(o.order_date), 'YYYY-MM-DD')"
    }),
    join: makeJoinSql({ usingOrder: true, tailClause: "LIMIT 100" }),
    er: makeErSql({ tableSuffix: "DISTRIBUTED BY (customer_id)" })
  },
  hana: {
    lineage: makeLineageSql({
      aggExpr: "STRING_AGG(p.product_name, ', ')",
      dateExpr: "TO_VARCHAR(MAX(o.order_date), 'YYYY-MM-DD')"
    }),
    join: makeJoinSql({ tailClause: "LIMIT 100" }),
    er: makeErSql(),
    callgraph: SQLPL_CALLGRAPH_SQL
  },
  hive: {
    lineage: makeLineageSql({
      aggExpr: "concat_ws(', ', collect_list(p.product_name))",
      dateExpr: "date_format(MAX(o.order_date), 'yyyy-MM-dd')",
      targetClause: "insert_overwrite"
    }),
    join: makeJoinSql({ tailClause: "LIMIT 100" }),
    er: makeErSql({ tableSuffix: "STORED AS ORC", fkSuffix: "DISABLE NOVALIDATE" })
  },
  impala: {
    lineage: makeLineageSql({
      aggExpr: "GROUP_CONCAT(p.product_name, ', ')",
      dateExpr: "CAST(MAX(o.order_date) AS STRING)"
    }),
    join: makeJoinSql({ tailClause: "LIMIT 100" }),
    er: makeErSql({ tableSuffix: "STORED AS KUDU" })
  },
  informix: {
    lineage: makeLineageSql({
      aggExpr: "MAX(p.product_name)",
      dateExpr: "TO_CHAR(MAX(o.order_date), '%Y-%m-%d')"
    }),
    join: makeJoinSql(),
    er: makeErSql()
  },
  netezza: {
    lineage: makeLineageSql({
      aggExpr: "GROUP_CONCAT(p.product_name, ', ')",
      dateExpr: "TO_CHAR(MAX(o.order_date), 'YYYY-MM-DD')"
    }),
    join: makeJoinSql({ tailClause: "LIMIT 100" }),
    er: makeErSql(),
    callgraph: PG_CALLGRAPH_SQL
  },
  openedge: {
    lineage: makeLineageSql({
      aggExpr: "MAX(p.product_name)",
      dateExpr: "TO_CHAR(MAX(o.order_date), 'YYYY-MM-DD')"
    }),
    join: makeJoinSql(),
    er: makeErSql()
  },
  presto: {
    lineage: makeLineageSql({
      aggExpr: "array_join(array_agg(p.product_name), ', ')",
      dateExpr: "date_format(MAX(o.order_date), '%Y-%m-%d')"
    }),
    join: makeJoinSql({ usingOrder: true, tailClause: "LIMIT 100" })
  },
  redshift: {
    lineage: makeLineageSql({
      aggExpr: "LISTAGG(p.product_name, ', ') WITHIN GROUP (ORDER BY p.product_name)",
      dateExpr: "TO_CHAR(MAX(o.order_date), 'YYYY-MM-DD')"
    }),
    join: makeJoinSql({ tailClause: "LIMIT 100" }),
    er: makeErSql({ tableSuffix: "DISTSTYLE AUTO" }),
    callgraph: PG_CALLGRAPH_SQL
  },
  sparksql: {
    lineage: makeLineageSql({
      aggExpr: "concat_ws(', ', collect_list(p.product_name))",
      dateExpr: "date_format(MAX(o.order_date), 'yyyy-MM-dd')",
      targetClause: "insert_overwrite"
    }),
    join: makeJoinSql({ hint: "/*+ BROADCAST(c) */", tailClause: "LIMIT 100" }),
    er: makeErSql()
  },
  sybase: {
    lineage: makeLineageSql({
      aggExpr: "MAX(p.product_name)",
      dateExpr: "CONVERT(VARCHAR(10), MAX(o.order_date), 23)",
      targetClause: "select_into"
    }),
    join: makeJoinSql({ hint: "TOP 100" }),
    er: makeErSql(),
    callgraph: TSQL_CALLGRAPH_SQL
  },
  teradata: {
    lineage: makeLineageSql({
      aggExpr: "XMLAGG(TRIM(p.product_name) || ', ' ORDER BY p.product_name) (VARCHAR(1000))",
      dateExpr: "CAST(MAX(o.order_date) AS VARCHAR(10))",
      targetClause: "volatile"
    }),
    join: makeJoinSql({
      tailClause: "QUALIFY ROW_NUMBER() OVER (PARTITION BY c.customer_id ORDER BY o.order_date DESC) = 1"
    }),
    er: makeErSql({ teradataIndex: true }),
    callgraph: SQLPL_CALLGRAPH_SQL
  },
  vertica: {
    lineage: makeLineageSql({
      aggExpr: "LISTAGG(p.product_name USING PARAMETERS separator=', ')",
      dateExpr: "TO_CHAR(MAX(o.order_date), 'YYYY-MM-DD')"
    }),
    join: makeJoinSql({ tailClause: "LIMIT 100" }),
    er: makeErSql(),
    callgraph: SQLPL_CALLGRAPH_SQL
  }
};

/**
 * Returns the categorized `Examples` menu items for a given dialect (`M(vendor)` in `module 84558`, 04 §5.5.3).
 * For the 25 dialects with explicit templates, returns items matching `dialect.exampleFeatures`.
 * For the remaining 14 dialects, falls back to `[{ id: "${vendor}-sample", feature: "lineage", sql: SAMPLE_SQL[vendor] }]`.
 */
export function getDialectExamples(vendor?: string | null): DialectExampleItem[] {
  const dialect = getUiDialect(vendor);
  const key = dialect.shortKey;
  const explicit = EXPLICIT_EXAMPLES_MAP[key];

  if (!explicit || dialect.usesSampleFallbackForExamples) {
    return [
      {
        id: `${key}-sample`,
        dialect: key,
        feature: "lineage",
        label: EXAMPLE_FEATURE_LABELS.lineage,
        sql: SAMPLE_SQL[key]
      }
    ];
  }

  return dialect.exampleFeatures.map((feature) => ({
    id: `${key}-${feature}`,
    dialect: key,
    feature,
    label: EXAMPLE_FEATURE_LABELS[feature],
    sql: explicit[feature] ?? SAMPLE_SQL[key]
  }));
}

let cachedBuiltInSqlSet: Set<string> | null = null;

/**
 * Returns the complete set of all built-in SQL strings (`Object.values(SAMPLE_SQL)`,
 * `ONBOARDING_CUSTOMER_REVENUE_SQL`, and every categorized `Examples` template)
 * used by the unauthenticated custom-SQL check (`04 §5.3`).
 */
export function getAllBuiltInSqlSet(): ReadonlySet<string> {
  if (cachedBuiltInSqlSet) {
    return cachedBuiltInSqlSet;
  }
  const set = new Set<string>();
  const addWithTrim = (sql: string) => {
    set.add(sql);
    set.add(sql.trim());
  };

  addWithTrim(ONBOARDING_CUSTOMER_REVENUE_SQL);
  for (const dialect of UI_DIALECTS) {
    addWithTrim(SAMPLE_SQL[dialect.shortKey]);
    for (const item of getDialectExamples(dialect.shortKey)) {
      addWithTrim(item.sql);
    }
  }
  cachedBuiltInSqlSet = set;
  return set;
}

export function isBuiltInSampleOrExampleSql(sqltext?: string | null): boolean {
  if (!sqltext) {
    return false;
  }
  const builtIns = getAllBuiltInSqlSet();
  return builtIns.has(sqltext) || builtIns.has(sqltext.trim());
}

export const UNAUTHENTICATED_CUSTOM_SQL_WARNING = "please login to analyze custom SQL" as const;
export const TRIAL_SQL_LENGTH_LIMIT = 10_000 as const;
export const TRIAL_SQL_LENGTH_ERROR =
  "SQL length over 10000 characters is not allowed in trial version" as const;

export interface SqlSubmissionGuardOptions {
  sqltext: string;
  isLogin: boolean;
  loginDisable?: boolean;
  trialVersion?: boolean;
}

export type SqlSubmissionGuardResult =
  | { allowed: true }
  | {
      allowed: false;
      reason: "unauthenticated_custom_sql" | "trial_length_exceeded";
      message: string;
    };

/**
 * Evaluates the two client-side pre-flight guards documented in `04 §5.3` (`module 23068`).
 */
export function validateClientSqlSubmission(
  options: SqlSubmissionGuardOptions
): SqlSubmissionGuardResult {
  if (!options.isLogin && !options.loginDisable && !isBuiltInSampleOrExampleSql(options.sqltext)) {
    return {
      allowed: false,
      reason: "unauthenticated_custom_sql",
      message: UNAUTHENTICATED_CUSTOM_SQL_WARNING
    };
  }
  if (options.trialVersion && options.sqltext.length > TRIAL_SQL_LENGTH_LIMIT) {
    return {
      allowed: false,
      reason: "trial_length_exceeded",
      message: TRIAL_SQL_LENGTH_ERROR
    };
  }
  return { allowed: true };
}
