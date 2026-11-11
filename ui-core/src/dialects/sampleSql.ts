/**
 * Complete Verbatim Built-In Sample SQL Catalog for All 39 Dialects
 * plus Onboarding Default SQL (`customer_revenue`).
 * Reference: docs/re-spec/04-frontend-ui-and-widget.md §5.5.1–§5.5.2.
 */

import { type UiDialectKey, getUiDialect } from "./catalog.js";

/**
 * Onboarding default SQL (`D` — `customer_revenue`, 04 §5.5.1).
 */
export const ONBOARDING_CUSTOMER_REVENUE_SQL = `-- Where does each column in customer_revenue come from?
-- Click a column in the graph to trace its lineage.
CREATE VIEW customer_revenue AS
SELECT
    c.customer_id,
    c.customer_name,
    SUM(oi.quantity * p.unit_price) AS total_revenue
FROM orders o
JOIN order_items oi ON oi.order_id   = o.order_id
JOIN products    p  ON p.product_id  = oi.product_id
JOIN customers   c  ON c.customer_id = o.customer_id
GROUP BY c.customer_id, c.customer_name;`;

/**
 * Verbatim built-in `sampleSQL` map for all 39 dialects (04 §5.5.2).
 */
export const SAMPLE_SQL: Readonly<Record<UiDialectKey, string>> = {
  athena: `INSERT INTO cities_usa (city,state)
SELECT city,state FROM cities_world WHERE country='usa';`,

  azuresql: `-- azure sql sample SQL
CREATE VIEW [SalesLT].[vProductAndDescription]
WITH SCHEMABINDING
AS
-- View (indexed or standard) to display products and product descriptions by language.
SELECT
    p.[ProductID]
    ,p.[Name]
    ,pm.[Name] AS [ProductModel]
    ,pmx.[Culture]
    ,pd.[Description]
FROM [SalesLT].[Product] p
    INNER JOIN [SalesLT].[ProductModel] pm
    ON p.[ProductModelID] = pm.[ProductModelID]
    INNER JOIN [SalesLT].[ProductModelProductDescription] pmx
    ON pm.[ProductModelID] = pmx.[ProductModelID]
    INNER JOIN [SalesLT].[ProductDescription] pd
    ON pmx.[ProductDescriptionID] = pd.[ProductDescriptionID];`,

  bigquery: `-- bigquery sample SQL
MERGE dataset.DetailedInventory T
USING dataset.Inventory S
ON T.product = S.product
WHEN NOT MATCHED AND s.quantity < 20 THEN
  INSERT(product, quantity, supply_constrained, comments)
  VALUES(product, quantity, true, ARRAY<STRUCT<created DATE, comment STRING>>[(DATE('2016-01-01'), 'comment1')])
WHEN NOT MATCHED THEN
  INSERT(product, quantity, supply_constrained)
  VALUES(product, quantity, false)
;`,

  clickhouse: `-- clickhouse sample sql
CREATE TABLE orders (
    user_id UInt32,
    order_id UInt32,
    amount Float64,
    order_time DateTime
) ENGINE = MergeTree()
ORDER BY (user_id, order_time);

SELECT
    user_id,
    count() AS order_count,
    sum(amount) AS total_spent,
    avg(amount) AS avg_order_value
FROM orders
WHERE order_time >= now() - INTERVAL 30 DAY
GROUP BY user_id
HAVING total_spent > 1000
ORDER BY total_spent DESC;`,

  couchbase: `-- Couchbase
SELECT t1.country, array_agg(t1.city), sum(t1.city_cnt) as apnum
FROM (SELECT city, city_cnt, array_agg(airportname) as apnames, country
      FROM \`travel-sample\` WHERE type = "airport"
      GROUP BY city, country LETTING city_cnt = count(city) ) AS t1
WHERE t1.city_cnt > 5
GROUP BY t1.country;

INSERT INTO \`travel-sample\` (key UUID(), value _country)
    SELECT _country FROM \`travel-sample\` _country
      WHERE type = "airport" AND airportname = "Heathrow";
\t  
MERGE INTO all_empts a USING emps_deptb b ON KEY b.empId
WHEN MATCHED THEN
     UPDATE SET a.depts = a.depts + 1,
     a.title = b.title || ", " || b.title
WHEN NOT MATCHED THEN
     INSERT  { "name": b.name, "title": b.title, "depts": b.depts, "empId": b.empId, "dob": b.dob }
;

UPDATE \`travel-sample\`
SET foo = 9
WHERE city = (SELECT raw city FROM \`beer-sample\` WHERE type = "brewery")
;`,

  dameng: `-- dameng sample sql
CREATE TABLE departments (
    dept_id INT PRIMARY KEY,
    dept_name VARCHAR(50)
);

CREATE TABLE employees (
    emp_id INT PRIMARY KEY,
    emp_name VARCHAR(50),
    dept_id INT,
    salary DECIMAL(10,2)
);

SELECT 
    e.emp_name,
    d.dept_name,
    e.salary
FROM employees e
JOIN departments d ON e.dept_id = d.dept_id
WHERE e.salary > (
    SELECT AVG(salary)
    FROM employees
    WHERE dept_id = e.dept_id
);`,

  databricks: `-- databricks
CREATE OR REPLACE VIEW experienced_employee
    (id COMMENT 'Unique identification number', Name)
    COMMENT 'View for experienced employees'
    AS SELECT id, name
         FROM all_employee
        WHERE working_years > 5;`,

  db2: `-- DB2
SELECT PART, SUPPLIER, PRODNUM, PRODUCT
FROM (SELECT PART, PROD# AS PRODNUM, SUPPLIER
FROM PARTS
WHERE PROD# < 200) AS PARTX
LEFT OUTER JOIN PRODUCTS
ON PRODNUM = PROD#;


SELECT D.DEPTNO, D.DEPTNAME,
EMPINFO.AVGSAL, EMPINFO.EMPCOUNT
FROM DEPT D,
TABLE (SELECT AVG(E.SALARY) AS AVGSAL,
COUNT(*) AS EMPCOUNT
FROM EMP E
WHERE E.WORKDEPT = D.DEPTNO)
AS EMPINFO;

DELETE FROM EMP X
WHERE ABSENT = (SELECT MAX(ABSENT) FROM EMP Y
WHERE X.WORKDEPT = Y.WORKDEPT);

INSERT INTO SMITH.TEMPEMPL
SELECT *
FROM DSN8B10.EMP;

INSERT INTO B.EMP_PHOTO_RESUME
-- OVERRIDING USER VALUE
SELECT * FROM DSN8B10.EMP_PHOTO_RESUME;

MERGE INTO RECORDS AR
USING (VALUES (:hv_activity, :hv_description)
--FOR :hv_nrows ROWS
)
AS AC (ACTIVITY, DESCRIPTION)
ON (AR.ACTIVITY = AC.ACTIVITY)
WHEN MATCHED THEN UPDATE SET DESCRIPTION = AC.DESCRIPTION
WHEN NOT MATCHED THEN INSERT (ACTIVITY, DESCRIPTION)
VALUES (AC.ACTIVITY, AC.DESCRIPTION)
-- NOT ATOMIC CONTINUE ON SQLEXCEPTION
;

CREATE TABLE T1 (COL1 CHAR(7), COL2 INT);
INSERT INTO T1 VALUES ('abc', 10);
MERGE INTO T1 AS A
USING TABLE (VALUES ('rsk', 3 ) ) AS T (ID, AMOUNT)
ON A.COL1 = T.ID
WHEN MATCHED
THEN UPDATE SET COL2 = CARDINALITY(CHARA)
WHEN NOT MATCHED
THEN INSERT (COL1, COL2 ) VALUES (T.ID, CARDINALITY(INTA));

UPDATE DSN8B10.EMP
SET PROJSIZE = (SELECT COUNT(*)
FROM DSN8B10.PROJ
WHERE DEPTNO = 'E21')
WHERE WORKDEPT = 'E21';

CREATE VIEW DSN8B10.FIRSTQTR (SNO, CHARGES, DATE) AS
SELECT SNO, CHARGES, DATE
FROM MONTH1
WHERE DATE BETWEEN '01/01/2000' and '01/31/2000'
UNION All
SELECT SNO, CHARGES, DATE
FROM MONTH2
WHERE DATE BETWEEN '02/01/2000' and '02/29/2000'
UNION All
SELECT SNO, CHARGES, DATE
FROM MONTH3
WHERE DATE BETWEEN '03/01/2000' and '03/31/2000';

/* not supported yet

EXEC SQL
DECLARE C1 CURSOR FOR
SELECT BONUS
FROM DSN8710.EMP
WHERE WORKDEPT = 'E12'
FOR UPDATE OF BONUS;

EXEC SQL
UPDATE DSN8710.EMP
SET BONUS = ( SELECT .10 * SALARY FROM DSN8710.EMP Y
WHERE EMPNO = Y.EMPNO )
WHERE CURRENT OF C1;
*/`,

  doris: `-- doris sample sql
CREATE TABLE user_activity (
    user_id INT,
    session_id VARCHAR(50),
    activity_time DATETIME
)
DUPLICATE KEY(user_id, session_id)
DISTRIBUTED BY HASH(user_id) BUCKETS 10;

SELECT
    user_id,
    COUNT(DISTINCT session_id) AS session_count,
    COUNT(*) AS event_count
FROM user_activity
WHERE activity_time >= NOW() - INTERVAL 7 DAY
GROUP BY user_id
HAVING session_count >= 3;`,

  duckdb: `-- duckdb sample sql
CREATE TABLE sales AS
SELECT * FROM read_csv_auto('sales_data.csv');

WITH monthly AS (
    SELECT 
        DATE_TRUNC('month', sale_date) AS month,
        SUM(amount) AS revenue,
        COUNT(*) AS orders
    FROM sales
    GROUP BY 1
)
SELECT *,
       revenue / orders AS avg_order_value
FROM monthly
ORDER BY month;`,

  edb: `-- edb sample sql
CREATE TABLE sales (
    sale_id INT,
    sale_date DATE,
    quantity INT,
    unit_price DECIMAL(10,2)
);

SELECT 
    DATE_TRUNC('month', sale_date) AS month,
    SUM(quantity * unit_price) AS revenue,
    COUNT(*) AS orders
FROM sales
GROUP BY 1
ORDER BY month;`,

  flink: `-- flink sample sql
CREATE TABLE orders (
    order_id STRING,
    order_time TIMESTAMP(3),
    user_id STRING,
    price DECIMAL(10,2),
    status STRING
) WITH (
    'connector' = 'kafka',
    'topic' = 'orders',
    'properties.bootstrap.servers' = 'localhost:9092',
    'format' = 'json'
);

SELECT
    user_id,
    COUNT(*) AS order_count,
    SUM(price) AS total_spent
FROM orders
WHERE status = 'completed'
GROUP BY user_id
HAVING SUM(price) > 1000;`,

  gaussdb: `-- gaussdb
 CREATE TABLE hr.section
(
  id           INTEGER      NOT NULL,  
  code         VARCHAR(16)  NOT NULL,  
  name         VARCHAR(50)  NOT NULL, 
  place_id     INTEGER      NOT NULL,
  place_name   VARCHAR(50)  NOT NULL                    
);

CREATE UNIQUE INDEX hr_section_index1 ON hr.section(id);

CREATE TABLE hr.deptinfo AS SELECT * FROM hr.deptbase WHERE id > 365;

CREATE VIEW deptinView AS
    SELECT * FROM hr.deptinfo WHERE id > 100;
    
CREATE OR REPLACE PROCEDURE dept_proc()
AS 
declare
    DEPT_NAME VARCHAR(100);
    DEPT_LOC  INTEGER;
    CURSOR C1 IS 
        SELECT name, place_id FROM hr.section WHERE place_id <= 50;
BEGIN
    OPEN C1;
    LOOP
        FETCH C1 INTO DEPT_NAME, DEPT_LOC;
        EXIT WHEN C1%NOTFOUND;
        DBMS_OUTPUT.PUT_LINE(DEPT_NAME||'---'||DEPT_LOC);
    END LOOP;
    CLOSE C1;
END;`,

  greenplum: `-- greenplum sample SQL

WITH regional_sales AS (
     SELECT region, SUM(amount) AS total_sales
     FROM orders
     GROUP BY region
  ), top_regions AS (
     SELECT region
     FROM regional_sales
     WHERE total_sales > (SELECT SUM(total_sales)/10 FROM regional_sales)
  )
SELECT region,
    product,
    SUM(quantity) AS product_units,
    SUM(amount) AS product_sales
FROM orders
WHERE region IN (SELECT region FROM top_regions)
GROUP BY region, product;

WITH RECURSIVE search_graph(id, link, data, depth, path, cycle) AS (
        SELECT g.id, g.link, g.data, 1,
          ARRAY[g.id],
          false
        FROM graph g
      UNION ALL
        SELECT g.id, g.link, g.data, sg.depth + 1,
          path || g.id,
          g.id = ANY(path)
        FROM graph g, search_graph sg
        WHERE g.id = sg.link AND NOT cycle
)
SELECT * FROM search_graph;

INSERT INTO films SELECT * FROM tmp_films WHERE date_prod < '2004-05-07';

UPDATE employees SET sales_count = sales_count + 1 WHERE id =
  (SELECT id FROM accounts WHERE name = 'Acme Corporation');
  
  
UPDATE accounts SET (contact_last_name, contact_first_name) =
    (SELECT last_name, first_name FROM salesmen
     WHERE salesmen.id = accounts.sales_id);  `,

  hana: `-- sap hana sample SQLCODE

CREATE TABLE t1(a INT, b INT, c INT);  
CREATE TABLE t2(a INT, b INT, c INT);
INSERT INTO t1 VALUES(1,12,13);            
INSERT INTO t2 VALUES(2,120,130);
            
INSERT INTO t1 WITH alias1 AS (SELECT * FROM t1) SELECT * FROM alias1;
INSERT INTO t1 WITH w1 AS (SELECT * FROM t2) SELECT * FROM w1;

SELECT TA.a1, TB.b1 FROM TA, LATERAL (SELECT b1, b2 FROM TB WHERE b3 = TA.a3) TB WHERE TA.a2 = TB.b2;

UPDATE T1 SET C1 = ARRAY( SELECT C1 FROM T0 ) WHERE ID = 1;

UPSERT T1 VALUES ( 1, ARRAY ( SELECT C1 FROM T0 ) ) WHERE ID = 1;


MERGE INTO "my_schema".t1 USING "my_schema".t2 ON "my_schema".t1.a = "my_schema".t2.a
 WHEN MATCHED THEN UPDATE SET "my_schema".t1.b = "my_schema".t2.b
 WHEN NOT MATCHED THEN INSERT VALUES("my_schema".t2.a, "my_schema".t2.b);
 
 MERGE INTO T1 USING T2 ON T1.A = T2.A
 WHEN MATCHED AND T1.A > 1 THEN UPDATE SET B = T2.B
 WHEN NOT MATCHED AND T1.A > 3 THEN INSERT VALUES (T2.A, T2.B);
 
 
 /* not support yet
 CREATE VIEW Illness_K_Anon (ID, Gender, Location, Illness)
            AS SELECT ID, Gender, City AS Location, Illness
            FROM Illness
            WITH ANONYMIZATION ( ALGORITHM 'K-ANONYMITY'
            PARAMETERS '{"data_change_strategy": "qualified", "k": 2}'
            COLUMN ID PARAMETERS '{"is_sequence": true}'
            COLUMN Gender PARAMETERS '{"is_quasi_identifier":true, "hierarchy":{"embedded": [["F"], ["M"]]}}'
            COLUMN Location PARAMETERS '{"is_quasi_identifier":true, "hierarchy":{"embedded": [["Paris", "France"], ["Munich", "Germany"], ["Nice", "France"]]}}');
*/`,

  hive: `-- Hive sample sql
SELECT pageid, adid
FROM pageAds LATERAL VIEW explode(adid_list) adTable AS adid;

CREATE DATABASE merge_data;
 
CREATE TABLE merge_data.transactions(
\tID int,
\tTranValue string,
\tlast_update_user string)
PARTITIONED BY (tran_date string)
CLUSTERED BY (ID) into 5 buckets 
STORED AS ORC TBLPROPERTIES ('transactional'='true');
 
CREATE TABLE merge_data.merge_source(
\tID int,
\tTranValue string,
\ttran_date string)
STORED AS ORC;

 MERGE INTO merge_data.transactions AS T 
 USING merge_data.merge_source AS S
 ON T.ID = S.ID and T.tran_date = S.tran_date
 WHEN MATCHED AND (T.TranValue != S.TranValue AND S.TranValue IS NOT NULL) THEN UPDATE SET TranValue = S.TranValue, last_update_user = 'merge_update'
 WHEN MATCHED AND S.TranValue IS NULL THEN DELETE
 WHEN NOT MATCHED THEN INSERT VALUES (S.ID, S.TranValue, 'merge_insert', S.tran_date)`,

  impala: `-- Impala sample sql
insert into t2 select * from t1;
insert into t2 select c1, c2 from t1;

CREATE VIEW v5 AS SELECT c1, CAST(c3 AS STRING) c3, CONCAT(c4,c5) c5, TRIM(c6) c6, "Constant" c8 FROM t1;

CREATE VIEW v7 (c1 COMMENT 'Comment for c1', c2) COMMENT 'Comment for v7' AS SELECT t1.c1, t1.c2 FROM t1;`,

  informix: `-- Informix sample sql
CREATE VIEW myview (cola, colb) AS
SELECT colx, coly from firsttab
UNION
SELECT colx, colz from secondtab;

CREATE VIEW palo_alto AS
SELECT * FROM customer WHERE city = 'Palo Alto'
WITH CHECK OPTION
;

MERGE INTO t2 AS o USING t1 AS n ON o.f1 = n.f1
WHEN NOT MATCHED THEN INSERT ( o.f1,o.f2)
VALUES ( n.f1,n.f2)
;

INSERT INTO t2(f1, f2)
SELECT t1.f1, t1.f2 FROM t1
WHERE NOT EXISTS
(SELECT f1, f2 FROM t2
WHERE t2.f1 = t1.f1);

MERGE INTO sale USING new_sale AS n
ON sale.cust_id = n.cust_id
WHEN MATCHED THEN UPDATE
SET sale.salecount = sale.salecount + n.salecount
WHEN NOT MATCHED THEN INSERT (cust_id, salecount)
VALUES (n.cust_id, n.salecount);


MERGE INTO customer c
USING ext_customer e
ON c.customer_num=e.customer_num AND c.fname=e.fname AND c.lname=e.lname
WHEN NOT MATCHED THEN
INSERT
(c.fname, c.lname, c.company, c.address1, c.address2,
c.city, c.state, c.zipcode, c.phone)
VALUES
(e.fname, e.lname, e.company, e.address1, e.address2,
e.city, e.state, e.zipcode, e.phone)
WHEN MATCHED THEN UPDATE
SET c.fname = e.fname,
c.lname = e.lname,
c.company = e.company,
c.address1 = e.address1,
c.address2 = e.address2,
c.city = e.city,
c.state = e.state,
c.zipcode = e.zipcode,
c.phone = e.phone ;


UPDATE nmosdb@wnmserver1:test
SET name=(SELECT name FROM test
WHERE test.id = nmosdb@wnmserver1:test.id)
WHERE EXISTS(
SELECT 1 FROM test WHERE test.id = nmosdb@wnmserver1:test.id
);

UPDATE orders
SET ship_charge =
(SELECT SUM(total_price) * .07 FROM items
WHERE orders.order_num = items.order_num)
WHERE orders.order_num = 1001;`,

  mdx: `mdx`,

  mssql: `-- sql server sample sql
CREATE TABLE dbo.EmployeeSales  
( DataSource   varchar(20) NOT NULL,  
  BusinessEntityID   varchar(11) NOT NULL,  
  LastName     varchar(40) NOT NULL,  
  SalesDollars money NOT NULL  
);  
GO  
CREATE PROCEDURE dbo.uspGetEmployeeSales   
AS   
    SET NOCOUNT ON;  
    SELECT 'PROCEDURE', sp.BusinessEntityID, c.LastName,   
        sp.SalesYTD   
    FROM Sales.SalesPerson AS sp    
    INNER JOIN Person.Person AS c  
        ON sp.BusinessEntityID = c.BusinessEntityID  
    WHERE sp.BusinessEntityID LIKE '2%'  
    ORDER BY sp.BusinessEntityID, c.LastName;  
GO  
--INSERT...SELECT example  
INSERT INTO dbo.EmployeeSales  
    SELECT 'SELECT', sp.BusinessEntityID, c.LastName, sp.SalesYTD   
    FROM Sales.SalesPerson AS sp  
    INNER JOIN Person.Person AS c  
        ON sp.BusinessEntityID = c.BusinessEntityID  
    WHERE sp.BusinessEntityID LIKE '2%'  
    ORDER BY sp.BusinessEntityID, c.LastName;  
GO  


CREATE VIEW hiredate_view  
AS   
SELECT p.FirstName, p.LastName, e.BusinessEntityID, e.HireDate  
FROM HumanResources.Employee e   
JOIN Person.Person AS p ON e.BusinessEntityID = p.BusinessEntityID ;  
GO  

CREATE VIEW view1  
AS 
SELECT fis.CustomerKey, fis.ProductKey, fis.OrderDateKey, 
  fis.SalesTerritoryKey, dst.SalesTerritoryRegion  
FROM FactInternetSales AS fis   
LEFT OUTER JOIN DimSalesTerritory AS dst   
ON (fis.SalesTerritoryKey=dst.SalesTerritoryKey);  

GO  
SELECT ROW_NUMBER() OVER(PARTITION BY s.PostalCode ORDER BY s.SalesYTD DESC) AS "Row Number",   
    p.LastName, s.SalesYTD, a.PostalCode  
FROM Sales.SalesPerson AS s   
    INNER JOIN Person.Person AS p   
        ON s.BusinessEntityID = p.BusinessEntityID  
    INNER JOIN Person.Address AS a   
        ON a.AddressID = p.BusinessEntityID  
WHERE s.TerritoryID IS NOT NULL   
    AND s.SalesYTD <> 0  
ORDER BY s.PostalCode;`,

  mysql: `-- mysql sample sql

SELECT
  salesperson.name,
  -- find maximum sale size for this salesperson
  (SELECT MAX(amount) AS amount
    FROM all_sales
    WHERE all_sales.salesperson_id = salesperson.id)
  AS amount,
  -- find customer for this maximum size
  (SELECT customer_name
    FROM all_sales
    WHERE all_sales.salesperson_id = salesperson.id
    AND all_sales.amount =
         -- find maximum size, again
         (SELECT MAX(amount) AS amount
           FROM all_sales
           WHERE all_sales.salesperson_id = salesperson.id))
  AS customer_name
FROM
  salesperson;
  
  
SELECT
  salesperson.name,
  max_sale.amount,
  max_sale_customer.customer_name
FROM
  salesperson,
  -- calculate maximum size, cache it in transient derived table max_sale
  (SELECT MAX(amount) AS amount
    FROM all_sales
    WHERE all_sales.salesperson_id = salesperson.id)
  AS max_sale,
  -- find customer, reusing cached maximum size
  (SELECT customer_name
    FROM all_sales
    WHERE all_sales.salesperson_id = salesperson.id
    AND all_sales.amount =
        -- the cached maximum size
        max_sale.amount)
  AS max_sale_customer;

SELECT
  salesperson.name,
  max_sale.amount,
  max_sale.customer_name
FROM
  salesperson,
  -- find maximum size and customer at same time
  LATERAL
  (SELECT amount, customer_name
    FROM all_sales
    WHERE all_sales.salesperson_id = salesperson.id
    ORDER BY amount DESC LIMIT 1)
  AS max_sale;

WITH RECURSIVE employee_paths (id, name, path) AS
(
  SELECT id, name, CAST(id AS CHAR(200))
    FROM employees
    WHERE manager_id IS NULL
  UNION ALL
  SELECT e.id, e.name, CONCAT(ep.path, ',', e.id)
    FROM employee_paths AS ep JOIN employees AS e
      ON ep.id = e.manager_id
)
SELECT * FROM employee_paths ORDER BY path;

UPDATE table1 t1 
JOIN table2 t2 ON t1.field1 = t2.field1 
JOIN table3 t3 ON (t3.field1=t2.field2 AND t3.field3 IS NOT NULL) 
SET t1.field9=t3.field9
WHERE t1.field5=1
AND t1.field9 IS NULL  `,

  netezza: `-- netezza sample sql

INSERT INTO films SELECT * FROM tmp;

INSERT INTO emp_copy WITH employee AS (select * from
emp) SELECT * FROM employee;

UPDATE emp_copy SET grp = 'gone' WHERE id =
(WITH employee AS (select * from emp) SELECT id FROM employee WHERE id
= 1);

DELETE FROM emp_copy WHERE id IN
(WITH employee AS (SELECT * FROM emp_copy where grp = 'gone')
SELECT id FROM employee);


WITH manager (mgr_id, mgr_name, mgr_dept) AS
\t(SELECT id, name, grp
\tFROM emp_copy
\tWHERE mgr = id AND grp != 'gone'),
employee (emp_id, emp_name, emp_mgr) AS
\t(SELECT id, name, mgr_id
\tFROM emp_copy JOIN manager ON grp = mgr_dept),
mgr_cnt (mgr_id, mgr_reports) AS
\t(SELECT mgr, COUNT (*)
\tFROM emp_copy
\tWHERE mgr != id
\tGROUP BY mgr)
SELECT *
FROM employee JOIN manager ON emp_mgr = mgr_id 
\tJOIN mgr_cnt ON emp_mgr = mgr_id 
WHERE emp_id != mgr_id
ORDER BY mgr_dept;`,

  oceanbase: `-- oceanbase sample sql
CREATE TABLE orders (
    order_id INT PRIMARY KEY,
    user_id INT,
    order_date TIMESTAMP,
    total_amount DECIMAL(12,2)
);

SELECT
    user_id,
    COUNT(*) AS order_count,
    SUM(total_amount) AS total_spent
FROM orders
WHERE order_date >= CURRENT_DATE - INTERVAL 1 YEAR
GROUP BY user_id
HAVING total_spent > 5000
ORDER BY total_spent DESC;`,

  odbc: `-- odbc sample sql
CREATE TABLE inventory (
    product_id INT,
    product_name VARCHAR(100),
    quantity INT,
    last_updated DATETIME
);

SELECT 
    product_name,
    quantity,
    CASE
        WHEN quantity = 0 THEN 'Out of Stock'
        WHEN quantity < 20 THEN 'Low Stock'
        ELSE 'In Stock'
    END AS status
FROM inventory
WHERE last_updated >= DATEADD(day, -7, GETDATE());`,

  openedge: `-- openedge sample sql

CREATE VIEW ne_customers AS
SELECT name, address, city, state
FROM customer
WHERE state IN ( 'NH', 'MA', 'ME', 'RI', 'CT', 'VT' )
WITH CHECK OPTION ;

INSERT INTO neworders (order_no, product, qty)
SELECT order_no, product, qty
FROM orders
WHERE order_date = SYSDATE ;

UPDATE OrderLine
SET (ItemNum, Price) =
(SELECT ItemNum, Price * 3
FROM Item
WHERE ItemName = 'gloves')
WHERE OrderNum = 21 ;

Update Orderline
SET (Itemnum) =
(Select Itemnum
FROM Item
WHERE Itemname = 'Tennis balls')
WHERE Ordernum = 20;`,

  oracle: `CREATE VIEW vsal 
AS 
  SELECT a.deptno                  "Department", 
         a.num_emp / b.total_count "Employees", 
         a.sal_sum / b.total_sal   "Salary" 
  FROM   (SELECT deptno, 
                 Count()  num_emp, 
                 SUM(sal) sal_sum 
          FROM   scott.emp 
          WHERE  city = 'NYC' 
          GROUP  BY deptno) a, 
         (SELECT Count()  total_count, 
                 SUM(sal) total_sal 
          FROM   scott.emp 
          WHERE  city = 'NYC') b 
;

INSERT ALL
\tWHEN ottl < 100000 THEN
\t\tINTO small_orders
\t\t\tVALUES(oid, ottl, sid, cid)
\tWHEN ottl > 100000 and ottl < 200000 THEN
\t\tINTO medium_orders
\t\t\tVALUES(oid, ottl, sid, cid)
\tWHEN ottl > 200000 THEN
\t\tinto large_orders
\t\t\tVALUES(oid, ottl, sid, cid)
\tWHEN ottl > 290000 THEN
\t\tINTO special_orders
SELECT o.order_id oid, o.customer_id cid, o.order_total ottl,
o.sales_rep_id sid, c.credit_limit cl, c.cust_email cem
FROM orders o, customers c
WHERE o.customer_id = c.customer_id;

create table scott.dept(   
  deptno     number(2,0),   
  dname      varchar2(14),   
  loc        varchar2(13),   
  constraint pk_dept primary key (deptno)   
);

create table scott.emp(   
  empno    number(4,0),   
  ename    varchar2(10),   
  job      varchar2(9),   
  mgr      number(4,0),   
  hiredate date,   
  sal      number(7,2),   
  comm     number(7,2),   
  deptno   number(2,0),   
  constraint pk_emp primary key (empno),
  constraint fk_deptno foreign key (deptno) references dept (deptno) 
);`,

  postgresql: `-- postgresql sample sql

create view v2 as 
SELECT distributors.name
FROM distributors
WHERE distributors.name LIKE 'W%'
UNION
SELECT actors.name
FROM actors
WHERE actors.name LIKE 'W%';


WITH t AS (
SELECT random() as x FROM generate_series(1, 3)
)
SELECT * FROM t
UNION ALL
SELECT * FROM t
;

create view v3 as 
WITH RECURSIVE employee_recursive(distance, employee_name, manager_name) AS (
SELECT 1, employee_name, manager_name
FROM employee
WHERE manager_name = 'Mary'
UNION ALL
SELECT er.distance + 1, e.employee_name, e.manager_name
FROM employee_recursive er, employee e
WHERE er.employee_name = e.manager_name
)
SELECT distance, employee_name FROM employee_recursive;

WITH upd AS (
UPDATE employees SET sales_count = sales_count + 1 WHERE id =
(SELECT sales_person FROM accounts WHERE name = 'Acme Corporation')
RETURNING *
)
INSERT INTO employees_log SELECT *, current_timestamp FROM upd;


/* not implemented
CREATE RECURSIVE VIEW nums_1_100 (n) AS
VALUES (1)
UNION ALL
SELECT n+1 FROM nums_1_100 WHERE n < 100;
*/`,

  powerquery: `let
    Source = Sql.Database("your_server", "your_db"),
    Result = Value.NativeQuery(
        Source,
        "
        SELECT
            u.user_id,
            u.user_name,
            COUNT(o.order_id) AS order_count,
            SUM(o.amount) AS total_spent
        FROM users u
        LEFT JOIN orders o ON u.user_id = o.user_id
        WHERE o.order_date >= DATEADD(DAY, -30, GETDATE())
        GROUP BY u.user_id, u.user_name
        HAVING SUM(o.amount) > @minAmount
        ",
        [minAmount = 100]
    )
in
    Result`,

  presto: `CREATE TABLE orders_column_aliased (order_date, total_price)
AS
SELECT orderdate, totalprice
FROM orders;`,

  redshift: `-- redshift sample sql
Create table sales(
 dateid int,
  venuestate char(80),
  venuecity char(40),
 venuename char(100),
catname char(50),
Qtr int,
qtysold int,
  pricepaid int,
Year date
);
create view sales_vw as
select * from public.sales
union all
select * from spectrum.sales
;

insert into category_ident(catgroup,catname,catdesc)
select catgroup,catname,catdesc from category;

UPDATE category 
SET    catdesc = 'Broadway Musical' 
WHERE  category.catid IN (SELECT category.catid 
                          FROM   category 
                                 JOIN event 
                                   ON category.catid = event.catid 
                                 JOIN venue 
                                   ON venue.venueid = event.venueid 
                                 JOIN sales 
                                   ON sales.eventid = event.eventid 
                          WHERE  venuecity = 'New York City' 
                                 AND catname = 'Musicals'); 

update category set catid=100
from event join category cat on event.catid=cat.catid
where cat.catgroup='Concerts';

SELECT qtr, 
       Sum(pricepaid)           AS qtrsales, 
       (SELECT Sum(pricepaid) 
        FROM   sales 
               JOIN date 
                 ON sales.dateid = date.dateid 
        WHERE  qtr = '1' 
               AND year = 2008) AS q1sales 
FROM   sales 
       JOIN date 
         ON sales.dateid = date.dateid 
WHERE  qtr IN( '2', '3' ) 
       AND year = 2008 
GROUP  BY qtr 
ORDER  BY qtr; 


WITH venue_sales 
     AS (SELECT venuename, 
                venuecity, 
                Sum(pricepaid) AS venuename_sales 
         FROM   sales, 
                venue, 
                event 
         WHERE  venue.venueid = event.venueid 
                AND event.eventid = sales.eventid 
         GROUP  BY venuename, 
                   venuecity), 
     top_venues 
     AS (SELECT venuename 
         FROM   venue_sales 
         WHERE  venuename_sales > 800000) 
SELECT venuename, 
       venuecity, 
       venuestate, 
       Sum(qtysold)   AS venue_qty, 
       Sum(pricepaid) AS venue_sales 
FROM   sales, 
       venue, 
       event 
WHERE  venue.venueid = event.venueid 
       AND event.eventid = sales.eventid 
       AND venuename IN(SELECT venuename 
                        FROM   top_venues) 
GROUP  BY venuename, 
          venuecity, 
          venuestate 
ORDER  BY venuename; `,

  snowflake: `-- snowflake sample sql

create or replace secure view myview comment='Test secure view' as select * from mytable;


insert into employees(first_name, last_name, workphone, city,postal_code)
  select
    contractor_first,contractor_last,worknum,null,zip_code
  from contractors
  where contains(worknum,'650');
  
insert into employees (first_name,last_name,workphone,city,postal_code)
  with cte as
    (select contractor_first as first_name,contractor_last as last_name,worknum as workphone,city,zip_code as postal_code
     from contractors)
  select first_name,last_name,workphone,city,postal_code
  from cte;

insert into emp (id,first_name,last_name,city,postal_code,ph)
  select a.id,a.first_name,a.last_name,a.city,a.postal_code,b.ph
  from emp_addr a
  inner join emp_ph b on a.id = b.id;

insert overwrite all
  into t1
  into t1 (c1, c2, c3) values (n2, n1, default)
  into t2 (c1, c2, c3)
  into t2 values (n3, n2, n1)
select n1, n2, n3 from src;

insert all
  when n1 > 100 then
    into t1
  when n1 > 10 then
    into t1
    into t2
  else
    into t2
select n1 from src;

insert all
into t1 values (key, a)
select src1.key as key, src1.a as a
from src1, src2 where src1.key = src2.key;


merge into target
  using src on target.k = src.k
  when matched and src.v = 11 then delete
  when matched then update set target.v = src.v;

merge into target using (select k, max(v) as v from src group by k) as b on target.k = b.k
  when matched then update set target.v = b.v
  when not matched then insert (k, v) values (b.k, b.v);

merge into members m
  using (
  select id, date
  from signup
  where datediff(day, current_date(), signup.date::date) < -30) s on m.id = s.id
  when matched then update set m.fee = 40;

update t1
  set t1.number_column = t1.number_column + t2.number_column, t1.text_column = 'ASDF'
  from t2
  where t1.key_column = t2.t1_key and t1.number_column < 10;

update target set v = b.v
  from (select k, min(v) v from src group by k) b
  where target.k = b.k;  `,

  soql: `-- soql sample sql
SELECT Id, Name, Account.Name, Amount, CloseDate, StageName,
       (SELECT Id, Quantity, UnitPrice, PricebookEntry.Product2.Name
        FROM OpportunityLineItems)
FROM Opportunity
WHERE StageName = 'Closed Won'
AND CloseDate >= LAST_N_DAYS:30
ORDER BY Amount DESC
LIMIT 100`,

  sparksql: `-- sparksql sample sql

CREATE TABLE person (id INT, name STRING, age INT, class INT, address STRING);

SELECT * FROM person
    PIVOT (
        SUM(age) AS a, AVG(class) AS c
        FOR name IN ('John' AS john, 'Mike' AS mike)
    );`,

  sqlite: `-- sqlite sample sql
CREATE TABLE employees (
    emp_id INTEGER PRIMARY KEY,
    name TEXT,
    department TEXT,
    salary REAL,
    hire_date TEXT
);

WITH dept_salary AS (
    SELECT department,
           AVG(salary) AS avg_salary,
           MAX(salary) AS max_salary
    FROM employees
    GROUP BY department
)
SELECT e.name, e.department, e.salary,
       d.avg_salary,
       ROUND((e.salary - d.avg_salary) / d.avg_salary * 100, 2) AS pct_above_avg
FROM employees e
JOIN dept_salary d ON e.department = d.department
WHERE e.salary > d.avg_salary
ORDER BY pct_above_avg DESC;`,

  starrocks: `-- starrocks sample sql
CREATE TABLE user_orders (
    user_id INT,
    order_id INT,
    order_time DATETIME,
    total_amount DECIMAL(10,2)
) DUPLICATE KEY(user_id, order_id)
DISTRIBUTED BY HASH(user_id) BUCKETS 10;

SELECT user_id, DATE_TRUNC('day', order_time) AS order_date,
       COUNT(DISTINCT order_id) AS orders_per_day,
       SUM(total_amount) AS daily_revenue,
       AVG(total_amount) AS avg_order_value
FROM user_orders
WHERE order_time >= DATE_SUB(NOW(), INTERVAL 30 DAY)
GROUP BY user_id, DATE_TRUNC('day', order_time)
HAVING SUM(total_amount) > 100
ORDER BY daily_revenue DESC;`,

  sybase: `-- sybase sample sql

create view accounts (title, advance, amt_due)
as select title, advance, price * total_sales
from titles
where price > $5

create view cities
(authorname, acity, publishername, pcity)
as select authors.au_lname, authors.city, publishers.pub_name,
publishers.city
from authors, publishers
where authors.city = publishers.city;

create view cities2
as select authorname = authors.au_lname,
acity = authors.city, publishername = authors.pub_name, pcity =
publishers.city
from authors, publishers
where authors.city = publishers.city
;

create view psych_titles as
select *
from (select * from titles
where type = "psychology") dt_psych
;

insert newpublishers (pub_id, pub_name)
select pub_id, pub_name
from publishers
where pub_name="New Age Data"
;

merge into GlobalSales
(Item_number, Description, Quantity)as G
using DailySales as D
ON D.Item_number = G.Item_number
when not matched
then
insert (Item_number, Description, Quantity )
values (D.Item_number, D.Description, D.Quantity)
when matched
then update set
G.Quantity = G.Quantity + D.Quantity
;

update titles
set total_sales = sales.total_sales + sales.qty
from titles, salesdetail, sales
where titles.title_id = salesdetail.title_id
and salesdetail.stor_id = sales.stor_id
and salesdetail.ord_num = sales.ord_num
and sales.date in
(select max (sales.date) from sales)
;`,

  teradata: `-- teradata sample sql

INSERT INTO promotion (deptnum, empname, yearsexp)
SELECT deptno, name, yrsexp
FROM employee
WHERE yrsexp > 10 ;

-- Teradata Database interprets t1 in the scalar subquery as a distinct instance of
-- t1 rather than as the target table t1 of the insert operation
/* not supported yet
INSERT INTO t1 VALUES (1,2,3 (SELECT d2
FROM t2
WHERE a2=t1.a1));
*/


INSERT INTO t1
SELECT a, PERIOD(b, c)
FROM t2;

USING (empno INTEGER,
name VARCHAR(50),
salary INTEGER)
MERGE INTO employee AS t
USING VALUES (:empno, :name, :salary) AS s(empno, name, salary)
ON t.empno=s.empno
WHEN MATCHED THEN UPDATE
SET salary=s.salary
WHEN NOT MATCHED THEN INSERT (empno, name, salary)
VALUES (s.empno, s.name, s.salary);

USING (empno INTEGER,
name VARCHAR(50),
salary INTEGER)
UPDATE employee
SET salary=:salary
WHERE empno=:empno
ELSE INSERT INTO employee
(empno, name, salary) VALUES ( :empno, :name, :salary);

USING (empno INTEGER,
salary INTEGER)
MERGE INTO employee AS t
USING (SELECT :empno, :salary, name
FROM names
WHERE empno=:empno) AS s(empno, salary, name)
ON t.empno=s.empno
WHEN MATCHED THEN UPDATE
SET salary=s.salary, name = s.name
WHEN NOT MATCHED THEN INSERT (empno, name, salary)
VALUES (s.empno, s.name, s.salary);

MERGE INTO emp
USING VALUES (100, 'cc', 200, 3333) AS emp1 (empnum, name,
deptno, sal)
ON emp1.empnum=emp.s_no
WHEN MATCHED THEN
UPDATE SET sal=DEFAULT
WHEN NOT MATCHED THEN
INSERT VALUES (emp1.empnum, emp1.name, emp1.deptno, emp1.sal);
MERGE INTO emp
USING VALUES (100, 'cc', 200, 3333) AS emp1 (empnum, name,
deptno, sal)
ON emp1.empnum=emp.s_no
WHEN MATCHED THEN
UPDATE SET sal=DEFAULT(emp.sal)
WHEN NOT MATCHED THEN
INSERT VALUES (emp1.empnum, emp1.name, emp1.deptno, emp1.sal)
;

MERGE INTO t1
USING t2
ON t1.x1=t2.z2 AND t1.y1=t2.y2
WHEN MATCHED THEN
UPDATE SET z1=10
WHEN NOT MATCHED THEN
INSERT (z2,y2,x2);

MERGE INTO t1
USING (SELECT *
FROM t2
WHERE y2=10) AS s
ON x1=10
WHEN MATCHED THEN
UPDATE SET z1=z2
WHEN NOT MATCHED THEN
INSERT (x2, y2, z2);

UPDATE sales_sum_table AS ss
SET total_sales = (SELECT sum(amount)
FROM sales_table AS s
WHERE s.day_of_sale BETWEEN ss.period_start
AND ss.period_end);

-- The following UPDATE request updates the NoPI table nopi012_t1 aliased as t1.
UPDATE t1
FROM nopi012_t1 AS t1, nopi012_t2 AS t2
SET c3 = t1.c3 * 1.05
WHERE t1.c2 = t2.c2;`,

  trino: `-- trino sample sql
CREATE TABLE sales_data AS
SELECT * FROM raw_sales WHERE region IS NOT NULL;

WITH regional_summary AS (
    SELECT region, product_category,
           SUM(revenue) AS total_revenue,
           COUNT(DISTINCT customer_id) AS unique_customers
    FROM sales_data
    WHERE order_date >= DATE '2023-01-01'
    GROUP BY region, product_category
)
SELECT region, product_category, total_revenue, unique_customers,
       total_revenue / unique_customers AS revenue_per_customer
FROM regional_summary
ORDER BY total_revenue DESC
LIMIT 20;`,

  vertica: `-- hp vertica sample sql

CREATE VIEW myview AS
SELECT SUM(annual_income), customer_state
FROM public.customer_dimension
WHERE customer_key IN
(SELECT customer_key
FROM store.store_sales_fact)
GROUP BY customer_state
ORDER BY customer_state ASC;

INSERT INTO t1 (col1, col2) (SELECT 'abc', mycolumn FROM mytable);

MERGE INTO t USING s ON (t.c1 = s.c1)
WHEN NOT MATCHED THEN INSERT (c1, c2) VALUES (s.c1, s.c2);

-- First WITH clause,regional_sales
WITH
regional_sales AS (
SELECT region, SUM(amount) AS total_sales
FROM orders
GROUP BY region),
-- Second WITH clause top_regions
top_regions AS (
SELECT region
FROM regional_sales
WHERE total_sales > (SELECT SUM (total_sales)/10 FROM regional_sales) )
-- End defining WITH clause statement
-- Begin main primary query
SELECT region,
product,
SUM(quantity) AS product_units,
SUM(amount) AS product_sales
FROM orders
WHERE region IN (SELECT region FROM top_regions)
;`
};

export function getSampleSql(vendor?: string | null): string {
  const dialect = getUiDialect(vendor);
  return SAMPLE_SQL[dialect.shortKey];
}
