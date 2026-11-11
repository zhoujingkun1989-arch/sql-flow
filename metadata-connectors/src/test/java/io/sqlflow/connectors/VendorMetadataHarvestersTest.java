package io.sqlflow.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.sqlflow.connectors.jdbc.CatalogBuilder;
import io.sqlflow.connectors.jdbc.JdbcMetadataConnectorService;
import io.sqlflow.connectors.jdbc.SchemaFilterMatcher;
import io.sqlflow.connectors.jdbc.harvester.HiveImpalaMetadataHarvester;
import io.sqlflow.connectors.jdbc.harvester.SnowflakeMetadataHarvester;
import io.sqlflow.connectors.jdbc.harvester.TeradataMetadataHarvester;
import io.sqlflow.connectors.util.ConnectorsJson;
import io.sqlflow.contracts.domain.DataSource;
import io.sqlflow.contracts.lineage.Column;
import io.sqlflow.contracts.lineage.DatabaseObject;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.SchemaObject;
import io.sqlflow.contracts.lineage.ServerObject;
import io.sqlflow.contracts.lineage.SqlInfo;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.contracts.spi.MetadataConnectorSpi.MetadataHarvestResult;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VendorMetadataHarvestersTest {

    private final JdbcMetadataConnectorService connector = new JdbcMetadataConnectorService();

    @Test
    void oracleHarvesterExtractsTablesConstraintsViewsMviewsPackagesRoutinesSynonymsSequencesAndDbLinks() throws Exception {
        String h2Url = uniqueH2Url("ora_catalog");
        try (Connection conn = DriverManager.getConnection(h2Url, "sa", "");
                Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE ALL_OBJECTS (OWNER VARCHAR(64), OBJECT_NAME VARCHAR(64), OBJECT_TYPE VARCHAR(32))");
            st.execute("CREATE TABLE ALL_TAB_COLUMNS (OWNER VARCHAR(64), TABLE_NAME VARCHAR(64), COLUMN_NAME VARCHAR(64), DATA_TYPE VARCHAR(32), DATA_LENGTH INT, DATA_PRECISION INT, DATA_SCALE INT, NULLABLE VARCHAR(4), DATA_DEFAULT VARCHAR(128), COLUMN_ID INT)");
            st.execute("CREATE TABLE ALL_COL_COMMENTS (OWNER VARCHAR(64), TABLE_NAME VARCHAR(64), COLUMN_NAME VARCHAR(64), COMMENTS VARCHAR(256))");
            st.execute("CREATE TABLE ALL_CONSTRAINTS (OWNER VARCHAR(64), CONSTRAINT_NAME VARCHAR(64), TABLE_NAME VARCHAR(64), CONSTRAINT_TYPE VARCHAR(4))");
            st.execute("CREATE TABLE ALL_CONS_COLUMNS (OWNER VARCHAR(64), CONSTRAINT_NAME VARCHAR(64), TABLE_NAME VARCHAR(64), COLUMN_NAME VARCHAR(64), POSITION INT)");
            st.execute("CREATE TABLE ALL_VIEWS (OWNER VARCHAR(64), VIEW_NAME VARCHAR(64), TEXT VARCHAR(4000))");
            st.execute("CREATE TABLE ALL_MVIEWS (OWNER VARCHAR(64), MVIEW_NAME VARCHAR(64), QUERY VARCHAR(4000))");
            st.execute("CREATE TABLE ALL_SOURCE (OWNER VARCHAR(64), NAME VARCHAR(64), TYPE VARCHAR(32), LINE INT, TEXT VARCHAR(1000))");
            st.execute("CREATE TABLE ALL_ARGUMENTS (OWNER VARCHAR(64), PACKAGE_NAME VARCHAR(64), OBJECT_NAME VARCHAR(64), ARGUMENT_NAME VARCHAR(64), DATA_TYPE VARCHAR(32), IN_OUT VARCHAR(16), POSITION INT, DATA_LEVEL INT)");
            st.execute("CREATE TABLE ALL_SYNONYMS (OWNER VARCHAR(64), SYNONYM_NAME VARCHAR(64), TABLE_OWNER VARCHAR(64), TABLE_NAME VARCHAR(64), DB_LINK VARCHAR(64))");
            st.execute("CREATE TABLE ALL_SEQUENCES (SEQUENCE_OWNER VARCHAR(64), SEQUENCE_NAME VARCHAR(64), MIN_VALUE VARCHAR(32), MAX_VALUE VARCHAR(32), INCREMENT_BY VARCHAR(32), CYCLE_FLAG VARCHAR(4), ORDER_FLAG VARCHAR(4), CACHE_SIZE VARCHAR(32))");
            st.execute("CREATE TABLE ALL_DB_LINKS (OWNER VARCHAR(64), DB_LINK VARCHAR(128), USERNAME VARCHAR(64), HOST VARCHAR(128))");

            st.execute("INSERT INTO ALL_OBJECTS VALUES ('HR', 'EMPLOYEES', 'TABLE'), ('HR', 'EMP_ACTIVE_V', 'VIEW'), ('SYS', 'DUAL', 'TABLE')");
            st.execute("INSERT INTO ALL_TAB_COLUMNS VALUES ('HR', 'EMPLOYEES', 'EMP_ID', 'NUMBER', 22, 10, 0, 'N', NULL, 1)");
            st.execute("INSERT INTO ALL_TAB_COLUMNS VALUES ('HR', 'EMPLOYEES', 'EMAIL', 'VARCHAR2', 120, NULL, NULL, 'N', NULL, 2)");
            st.execute("INSERT INTO ALL_TAB_COLUMNS VALUES ('HR', 'EMPLOYEES', 'DEPT_ID', 'NUMBER', 22, 6, 0, 'Y', '10', 3)");
            st.execute("INSERT INTO ALL_TAB_COLUMNS VALUES ('HR', 'EMP_ACTIVE_V', 'EMP_ID', 'NUMBER', 22, 10, 0, 'N', NULL, 1)");
            st.execute("INSERT INTO ALL_TAB_COLUMNS VALUES ('SYS', 'DUAL', 'DUMMY', 'VARCHAR2', 1, NULL, NULL, 'Y', NULL, 1)");
            st.execute("INSERT INTO ALL_COL_COMMENTS VALUES ('HR', 'EMPLOYEES', 'EMAIL', 'Corporate unique email')");

            st.execute("INSERT INTO ALL_CONSTRAINTS VALUES ('HR', 'PK_EMP', 'EMPLOYEES', 'P'), ('HR', 'UQ_EMP_EMAIL', 'EMPLOYEES', 'U'), ('HR', 'FK_EMP_DEPT', 'EMPLOYEES', 'R')");
            st.execute("INSERT INTO ALL_CONS_COLUMNS VALUES ('HR', 'PK_EMP', 'EMPLOYEES', 'EMP_ID', 1), ('HR', 'UQ_EMP_EMAIL', 'EMPLOYEES', 'EMAIL', 1), ('HR', 'FK_EMP_DEPT', 'EMPLOYEES', 'DEPT_ID', 1)");

            st.execute("INSERT INTO ALL_VIEWS VALUES ('HR', 'EMP_ACTIVE_V', 'SELECT EMP_ID, EMAIL FROM HR.EMPLOYEES')");
            st.execute("INSERT INTO ALL_MVIEWS VALUES ('HR', 'EMP_DEPT_MV', 'SELECT DEPT_ID, COUNT(*) AS CNT FROM HR.EMPLOYEES GROUP BY DEPT_ID')");

            st.execute("INSERT INTO ALL_SOURCE VALUES ('HR', 'SYNC_EMP_PROC', 'PROCEDURE', 1, 'PROCEDURE SYNC_EMP_PROC(p_dept IN NUMBER) IS\n')");
            st.execute("INSERT INTO ALL_SOURCE VALUES ('HR', 'SYNC_EMP_PROC', 'PROCEDURE', 2, 'BEGIN NULL; END;')");
            st.execute("INSERT INTO ALL_SOURCE VALUES ('HR', 'HR_PKG', 'PACKAGE', 1, 'PACKAGE HR_PKG AS PROCEDURE RUN_PAYROLL; END HR_PKG;')");
            st.execute("INSERT INTO ALL_ARGUMENTS VALUES ('HR', NULL, 'SYNC_EMP_PROC', 'P_DEPT', 'NUMBER', 'IN', 1, 0)");

            st.execute("INSERT INTO ALL_SYNONYMS VALUES ('HR', 'EMP_SYN', 'HR', 'EMPLOYEES', 'ERP_LINK.CORP')");
            st.execute("INSERT INTO ALL_SEQUENCES VALUES ('HR', 'EMP_SEQ', '1', '999999999', '1', 'N', 'Y', '20')");
            st.execute("INSERT INTO ALL_DB_LINKS VALUES ('HR', 'ERP_LINK.CORP', 'ERP_READER', 'erp.corp:1521/ERP')");

            DataSource ds = h2DataSource(h2Url, "ora-prod");
            MetadataHarvestResult res = connector.harvestMetadata(EDbVendor.dbvoracle, ds);

            ServerObject srv = res.getCatalog().getServers().get(0);
            assertThat(srv.getSupportsCatalogs()).isFalse();
            assertThat(srv.getSupportsSchemas()).isTrue();
            // SYS must be filtered out automatically
            assertThat(srv.getSchemas()).extracting(SchemaObject::getName).containsExactly("HR");

            SchemaObject hr = srv.getSchemas().get(0);
            DbObject emp = hr.getTables().get(0);
            assertThat(emp.getName()).isEqualTo("EMPLOYEES");
            assertThat(emp.getColumns()).hasSize(3);

            Column pkCol = emp.getColumns().get(0);
            assertThat(pkCol.getPrimaryKey()).isTrue();
            assertThat(pkCol.getDataType()).isEqualTo("NUMBER(10)");

            Column uqCol = emp.getColumns().get(1);
            assertThat(uqCol.getUnqiueKey()).isTrue();
            assertThat(uqCol.getComment()).isEqualTo("Corporate unique email");

            Column fkCol = emp.getColumns().get(2);
            assertThat(fkCol.getForeignKey()).isTrue();

            // Verify Wire Invariant #4: unqiueKey on exported JSON
            JsonNode jsonTree = ConnectorsJson.mapper().readTree(res.getExporterMetadataJson());
            assertThat(res.getExporterMetadataJson()).contains("\"unqiueKey\":true");
            assertThat(jsonTree.get("dialect").asText()).isEqualTo("dbvoracle");

            assertThat(hr.getViews()).extracting(DbObject::getName).contains("EMP_ACTIVE_V", "EMP_DEPT_MV");
            assertThat(hr.getPackages()).hasSize(1);
            assertThat(hr.getProcedures()).hasSize(1);
            assertThat(hr.getProcedures().get(0).getArguments()).hasSize(1);
            assertThat(hr.getSynonyms()).hasSize(1);
            assertThat(hr.getSequences()).hasSize(1);
            assertThat(res.getCatalog().getDbLinks()).hasSize(1);
            assertThat(res.getExtractedSqlUnits()).hasSizeGreaterThanOrEqualTo(4);
        }
    }

    @Test
    void sqlServerAndAzureSqlHarvesterExtractsSysTablesIndexesModulesParametersSynonymsAndLinkedServers() throws Exception {
        String h2Url = uniqueH2Url("mssql_catalog");
        try (Connection conn = DriverManager.getConnection(h2Url, "sa", "");
                Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA sys");
            st.execute("CREATE TABLE sys.databases (name VARCHAR(64), state INT)");
            st.execute("CREATE TABLE sys.schemas (schema_id INT, name VARCHAR(64))");
            st.execute("CREATE TABLE sys.objects (object_id INT, schema_id INT, name VARCHAR(64), type VARCHAR(8), is_ms_shipped INT)");
            st.execute("CREATE TABLE sys.columns (object_id INT, column_id INT, name VARCHAR(64), user_type_id INT, max_length INT, precision INT, scale INT, is_nullable INT, is_identity INT, is_computed INT, default_object_id INT)");
            st.execute("CREATE TABLE sys.types (user_type_id INT, name VARCHAR(32))");
            st.execute("CREATE TABLE sys.default_constraints (object_id INT, definition VARCHAR(128))");
            st.execute("CREATE TABLE sys.computed_columns (object_id INT, column_id INT, definition VARCHAR(256))");
            st.execute("CREATE TABLE sys.indexes (object_id INT, index_id INT, is_primary_key INT, is_unique_constraint INT, is_unique INT)");
            st.execute("CREATE TABLE sys.index_columns (object_id INT, index_id INT, column_id INT)");
            st.execute("CREATE TABLE sys.foreign_key_columns (parent_object_id INT, parent_column_id INT)");
            st.execute("CREATE TABLE sys.sql_modules (object_id INT, definition VARCHAR(4000))");
            st.execute("CREATE TABLE sys.parameters (object_id INT, parameter_id INT, name VARCHAR(64), user_type_id INT, is_output INT)");
            st.execute("CREATE TABLE sys.synonyms (schema_id INT, name VARCHAR(64), base_object_name VARCHAR(256))");
            st.execute("CREATE TABLE sys.servers (name VARCHAR(64), data_source VARCHAR(128), provider VARCHAR(64), is_linked INT)");

            st.execute("INSERT INTO sys.databases VALUES ('SalesDW', 0), ('master', 0)");
            st.execute("INSERT INTO sys.schemas VALUES (1, 'dbo'), (2, 'sys')");
            st.execute("INSERT INTO sys.types VALUES (56, 'int'), (231, 'nvarchar'), (106, 'decimal')");
            st.execute("INSERT INTO sys.objects VALUES (100, 1, 'Orders', 'U', 0), (101, 1, 'vw_Orders', 'V', 0), (102, 1, 'usp_SyncOrders', 'P', 0)");
            st.execute("INSERT INTO sys.columns VALUES (100, 1, 'OrderId', 56, 4, 10, 0, 0, 1, 0, 0)");
            st.execute("INSERT INTO sys.columns VALUES (100, 2, 'CustomerCode', 231, 100, 0, 0, 0, 0, 0, 0)");
            st.execute("INSERT INTO sys.columns VALUES (100, 3, 'TotalWithTax', 106, 9, 18, 2, 1, 0, 1, 0)");
            st.execute("INSERT INTO sys.computed_columns VALUES (100, 3, '([Subtotal]*(1.08))')");
            st.execute("INSERT INTO sys.indexes VALUES (100, 1, 1, 0, 1)");
            st.execute("INSERT INTO sys.index_columns VALUES (100, 1, 1)");
            st.execute("INSERT INTO sys.sql_modules VALUES (101, 'CREATE VIEW dbo.vw_Orders AS SELECT OrderId, CustomerCode FROM dbo.Orders')");
            st.execute("INSERT INTO sys.sql_modules VALUES (102, 'CREATE PROCEDURE dbo.usp_SyncOrders @BatchId INT AS SELECT * FROM dbo.Orders')");
            st.execute("INSERT INTO sys.parameters VALUES (102, 1, '@BatchId', 56, 0)");
            st.execute("INSERT INTO sys.synonyms VALUES (1, 'syn_Orders', '[RemoteSrv].[SalesDW].[dbo].[Orders]')");
            st.execute("INSERT INTO sys.servers VALUES ('RemoteSrv', 'remote-sql.corp', 'SQLNCLI', 1)");

            DataSource ds = h2DataSource(h2Url, "sqlserver-prod");
            ds.setDatabase("SalesDW");
            MetadataHarvestResult res = connector.harvestMetadata(EDbVendor.dbvmssql, ds);

            DatabaseObject db = res.getCatalog().getDatabases().get(0);
            assertThat(db.getName()).isEqualTo("SalesDW");
            SchemaObject dbo = db.getSchemas().get(0);
            assertThat(dbo.getName()).isEqualTo("dbo");

            DbObject orders = dbo.getTables().get(0);
            assertThat(orders.getColumns()).hasSize(3);
            assertThat(orders.getColumns().get(0).getPrimaryKey()).isTrue();
            assertThat(orders.getColumns().get(0).getIdentity()).isTrue();
            // nvarchar max_length 100 bytes -> nvarchar(50)
            assertThat(orders.getColumns().get(1).getDataType()).isEqualTo("nvarchar(50)");
            assertThat(orders.getColumns().get(2).getComputed()).isTrue();
            assertThat(orders.getColumns().get(2).getComputedDefinition()).isEqualTo("([Subtotal]*(1.08))");

            assertThat(dbo.getViews()).extracting(DbObject::getName).containsExactly("vw_Orders");
            assertThat(dbo.getProcedures()).hasSize(1);
            assertThat(dbo.getProcedures().get(0).getArguments()).hasSize(1);
            assertThat(dbo.getSynonyms()).hasSize(1);
            assertThat(res.getCatalog().getDbLinks()).hasSize(1);
        }
    }

    @Test
    void greenplumPostgresAndMySqlHarvestersEnforcePartitionExclusionsAndCatalogHierarchies() throws Exception {
        String h2Url = uniqueH2Url("gp_pg_mysql");
        try (Connection conn = DriverManager.getConnection(h2Url, "sa", "");
                Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA analytics");
            st.execute("CREATE TABLE analytics.sales (sale_id BIGINT PRIMARY KEY, region VARCHAR(32) NOT NULL)");
            st.execute("CREATE TABLE analytics.sales_1_prt_p2026 (sale_id BIGINT, region VARCHAR(32))");
            st.execute("CREATE VIEW analytics.v_regional_sales AS SELECT region, COUNT(*) AS cnt FROM analytics.sales GROUP BY region");

            DataSource ds = h2DataSource(h2Url, "gp-master");
            ds.setDatabase("warehouse");

            // Greenplum excludes _1_prt_ partition child table automatically
            MetadataHarvestResult gpRes = connector.harvestMetadata(EDbVendor.dbvgreenplum, ds);
            SchemaObject gpAnalytics = gpRes.getCatalog().getDatabases().get(0).getSchemas().stream()
                    .filter(s -> s.getName().equalsIgnoreCase("analytics"))
                    .findFirst()
                    .orElseThrow();
            assertThat(gpAnalytics.getTables()).extracting(DbObject::getName).containsExactly("SALES");
            assertThat(gpAnalytics.getViews()).extracting(DbObject::getName).containsExactly("V_REGIONAL_SALES");

            // MySQL mode over standard H2 MySQL-compatible INFORMATION_SCHEMA views
            String mysqlH2 = uniqueH2Url("mysql_cat");
            try (Connection mConn = DriverManager.getConnection(mysqlH2, "sa", "");
                    Statement mst = mConn.createStatement()) {
                mst.execute("CREATE SCHEMA shop_db");
                mst.execute("CREATE TABLE shop_db.customers (cust_id INT PRIMARY KEY, full_name VARCHAR(80))");
                mst.execute("CREATE VIEW shop_db.v_customers AS SELECT cust_id, full_name FROM shop_db.customers");

                DataSource mDs = h2DataSource(mysqlH2, "mysql-prod");
                mDs.setExtractedDbsSchemas("SHOP_DB");
                MetadataHarvestResult mysqlRes = connector.harvestMetadata(EDbVendor.dbvmysql, mDs);
                ServerObject mysqlSrv = mysqlRes.getCatalog().getServers().get(0);
                assertThat(mysqlSrv.getSupportsCatalogs()).isTrue();
                assertThat(mysqlSrv.getSupportsSchemas()).isFalse();
                DatabaseObject shopDb = mysqlRes.getCatalog().getDatabases().get(0);
                assertThat(shopDb.getName()).isEqualToIgnoringCase("SHOP_DB");
                assertThat(shopDb.getTables()).extracting(DbObject::getName).containsExactly("CUSTOMERS");
                assertThat(shopDb.getViews()).extracting(DbObject::getName).containsExactly("V_CUSTOMERS");
            }
        }
    }

    @Test
    void teradataDb2AndNetezzaHarvestersDecode34TypeCodesOverflowDdlAndCatalogViews() throws Exception {
        // Verify Teradata 34+ 2-character type decoder directly + live DBC.* harvest
        assertThat(TeradataMetadataHarvester.decodeTeradataColumnType("CV", 128, null, null)).isEqualTo("VARCHAR(128)");
        assertThat(TeradataMetadataHarvester.decodeTeradataColumnType("CF", 10, null, null)).isEqualTo("CHAR(10)");
        assertThat(TeradataMetadataHarvester.decodeTeradataColumnType("D", null, 18, 4)).isEqualTo("DECIMAL(18,4)");
        assertThat(TeradataMetadataHarvester.decodeTeradataColumnType("I1", null, null, null)).isEqualTo("BYTEINT");
        assertThat(TeradataMetadataHarvester.decodeTeradataColumnType("I2", null, null, null)).isEqualTo("SMALLINT");
        assertThat(TeradataMetadataHarvester.decodeTeradataColumnType("I", null, null, null)).isEqualTo("INTEGER");
        assertThat(TeradataMetadataHarvester.decodeTeradataColumnType("I8", null, null, null)).isEqualTo("BIGINT");
        assertThat(TeradataMetadataHarvester.decodeTeradataColumnType("DA", null, null, null)).isEqualTo("DATE");
        assertThat(TeradataMetadataHarvester.decodeTeradataColumnType("TS", null, null, 6)).isEqualTo("TIMESTAMP(6)");
        assertThat(TeradataMetadataHarvester.decodeTeradataColumnType("SZ", null, null, 6)).isEqualTo("TIMESTAMP(6) WITH TIME ZONE");
        assertThat(TeradataMetadataHarvester.decodeTeradataColumnType("JN", null, null, null)).isEqualTo("JSON");
        assertThat(TeradataMetadataHarvester.decodeTeradataColumnType("PD", null, null, null)).isEqualTo("PERIOD(DATE)");
        assertThat(TeradataMetadataHarvester.decodeTeradataColumnType("DS", null, null, null)).isEqualTo("INTERVAL DAY TO SECOND");

        // Teradata DBC.* + TableTextV multi-line overflow reassembly
        String tdUrl = uniqueH2Url("td_dbc");
        try (Connection conn = DriverManager.getConnection(tdUrl, "sa", "");
                Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA DBC");
            st.execute("CREATE TABLE DBC.TablesV (DatabaseName VARCHAR(64), TableName VARCHAR(64), TableKind VARCHAR(4), RequestText VARCHAR(4000), RequestTxtOverFlow VARCHAR(4))");
            st.execute("CREATE TABLE DBC.ColumnsV (DatabaseName VARCHAR(64), TableName VARCHAR(64), ColumnName VARCHAR(64), ColumnType VARCHAR(8), ColumnLength INT, DecimalTotalDigits INT, DecimalFractionalDigits INT, Nullable VARCHAR(4), DefaultValue VARCHAR(64), CommentString VARCHAR(128), IdColType VARCHAR(8), ColumnId INT)");
            st.execute("CREATE TABLE DBC.IndicesV (DatabaseName VARCHAR(64), TableName VARCHAR(64), ColumnName VARCHAR(64), IndexType VARCHAR(4), UniqueFlag VARCHAR(4), ColumnPosition INT)");
            st.execute("CREATE TABLE DBC.All_RI_ChildrenV (ChildDB VARCHAR(64), ChildTable VARCHAR(64), ChildKeyColumn VARCHAR(64))");
            st.execute("CREATE TABLE DBC.TableTextV (DatabaseName VARCHAR(64), TableName VARCHAR(64), TableKind VARCHAR(4), LineNo INT, RequestText VARCHAR(2000))");

            st.execute("INSERT INTO DBC.TablesV VALUES ('RETAIL_DW', 'TXN_FACT', 'T', NULL, 'N'), ('RETAIL_DW', 'V_TXN_SUMMARY', 'V', 'TRUNCATED', 'Y'), ('RETAIL_DW', 'M_LOAD_TXN', 'M', 'REPLACE MACRO RETAIL_DW.M_LOAD_TXN AS (SELECT * FROM RETAIL_DW.TXN_FACT;);', 'N')");
            st.execute("INSERT INTO DBC.ColumnsV VALUES ('RETAIL_DW', 'TXN_FACT', 'TXN_ID', 'I8', 8, NULL, NULL, 'N', NULL, 'Primary key', 'GA', 1)");
            st.execute("INSERT INTO DBC.ColumnsV VALUES ('RETAIL_DW', 'TXN_FACT', 'AMOUNT', 'D', 8, 15, 2, 'Y', '0.00', NULL, NULL, 2)");
            st.execute("INSERT INTO DBC.IndicesV VALUES ('RETAIL_DW', 'TXN_FACT', 'TXN_ID', 'K', 'Y', 1)");
            st.execute("INSERT INTO DBC.TableTextV VALUES ('RETAIL_DW', 'V_TXN_SUMMARY', 'V', 1, 'REPLACE VIEW RETAIL_DW.V_TXN_SUMMARY AS ')");
            st.execute("INSERT INTO DBC.TableTextV VALUES ('RETAIL_DW', 'V_TXN_SUMMARY', 'V', 2, 'SELECT TXN_ID, AMOUNT FROM RETAIL_DW.TXN_FACT;')");

            MetadataHarvestResult tdRes = connector.harvestMetadata(EDbVendor.dbvteradata, h2DataSource(tdUrl, "td-prod"));
            DatabaseObject retailDb = tdRes.getCatalog().getDatabases().get(0);
            assertThat(retailDb.getName()).isEqualTo("RETAIL_DW");
            DbObject txnFact = retailDb.getTables().get(0);
            assertThat(txnFact.getColumns().get(0).getDataType()).isEqualTo("BIGINT");
            assertThat(txnFact.getColumns().get(0).getPrimaryKey()).isTrue();
            assertThat(txnFact.getColumns().get(1).getDataType()).isEqualTo("DECIMAL(15,2)");
            assertThat(tdRes.getExtractedSqlUnits())
                    .extracting(SqlInfo::getSql)
                    .anyMatch(s -> s.contains("REPLACE VIEW RETAIL_DW.V_TXN_SUMMARY AS SELECT TXN_ID, AMOUNT"));
        }

        // DB2 SYSCAT.*
        String db2Url = uniqueH2Url("db2_syscat");
        try (Connection conn = DriverManager.getConnection(db2Url, "sa", "");
                Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA SYSCAT");
            st.execute("CREATE TABLE SYSCAT.TABLES (TABSCHEMA VARCHAR(64), TABNAME VARCHAR(64), TYPE VARCHAR(4), BASE_TABSCHEMA VARCHAR(64), BASE_TABNAME VARCHAR(64))");
            st.execute("CREATE TABLE SYSCAT.COLUMNS (TABSCHEMA VARCHAR(64), TABNAME VARCHAR(64), COLNAME VARCHAR(64), TYPENAME VARCHAR(32), LENGTH INT, SCALE INT, NULLS VARCHAR(4), \"DEFAULT\" VARCHAR(64), \"IDENTITY\" VARCHAR(4), GENERATED VARCHAR(4), TEXT VARCHAR(256), REMARKS VARCHAR(128), KEYSEQ INT, COLNO INT)");
            st.execute("CREATE TABLE SYSCAT.VIEWS (VIEWSCHEMA VARCHAR(64), VIEWNAME VARCHAR(64), VIEWTEXTID INT, TEXT VARCHAR(2000))");
            st.execute("CREATE TABLE SYSCAT.ROUTINES (ROUTINESCHEMA VARCHAR(64), ROUTINENAME VARCHAR(64), SPECIFICNAME VARCHAR(64), ROUTINETYPE VARCHAR(4), TEXT VARCHAR(2000))");

            st.execute("INSERT INTO SYSCAT.TABLES VALUES ('FIN', 'LEDGER', 'T', NULL, NULL), ('FIN', 'V_LEDGER', 'V', NULL, NULL), ('FIN', 'LEDGER_ALIAS', 'A', 'FIN', 'LEDGER')");
            st.execute("INSERT INTO SYSCAT.COLUMNS VALUES ('FIN', 'LEDGER', 'ENTRY_ID', 'BIGINT', 8, 0, 'N', NULL, 'Y', ' ', NULL, 'PK', 1, 0)");
            st.execute("INSERT INTO SYSCAT.VIEWS VALUES ('FIN', 'V_LEDGER', 1, 'CREATE VIEW FIN.V_LEDGER AS '), ('FIN', 'V_LEDGER', 2, 'SELECT ENTRY_ID FROM FIN.LEDGER')");
            st.execute("INSERT INTO SYSCAT.ROUTINES VALUES ('FIN', 'CLOSE_BOOKS', 'CLOSE_BOOKS_1', 'P', 'CREATE PROCEDURE FIN.CLOSE_BOOKS() BEGIN END')");

            MetadataHarvestResult db2Res = connector.harvestMetadata(EDbVendor.dbvdb2, h2DataSource(db2Url, "db2-luw"));
            SchemaObject fin = db2Res.getCatalog().getDatabases().get(0).getSchemas().get(0);
            assertThat(fin.getTables()).extracting(DbObject::getName).containsExactly("LEDGER");
            assertThat(fin.getSynonyms()).hasSize(1);
            assertThat(fin.getProcedures()).hasSize(1);
            assertThat(db2Res.getExtractedSqlUnits()).hasSize(2);
        }

        // Netezza _v_*
        String nzUrl = uniqueH2Url("nz_cat");
        try (Connection conn = DriverManager.getConnection(nzUrl, "sa", "");
                Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE _v_relation_column (DATABASE VARCHAR(64), SCHEMA VARCHAR(64), NAME VARCHAR(64), TYPE VARCHAR(32), ATTNAME VARCHAR(64), FORMAT_TYPE VARCHAR(64), ATTNOTNULL BOOLEAN, COLDEFAULT VARCHAR(64), DESCRIPTION VARCHAR(128), ATTNUM INT)");
            st.execute("CREATE TABLE _v_view (DATABASE VARCHAR(64), SCHEMA VARCHAR(64), VIEWNAME VARCHAR(64), DEFINITION VARCHAR(2000))");
            st.execute("INSERT INTO _v_relation_column VALUES ('PROD', 'ADMIN', 'CLAIMS', 'TABLE', 'CLAIM_ID', 'BIGINT', TRUE, NULL, 'Claim key', 1)");
            st.execute("INSERT INTO _v_view VALUES ('PROD', 'ADMIN', 'V_CLAIMS', 'SELECT CLAIM_ID FROM ADMIN.CLAIMS')");

            MetadataHarvestResult nzRes = connector.harvestMetadata(EDbVendor.dbvnetezza, h2DataSource(nzUrl, "nz-host"));
            SchemaObject admin = nzRes.getCatalog().getDatabases().get(0).getSchemas().get(0);
            assertThat(admin.getTables()).extracting(DbObject::getName).containsExactly("CLAIMS");
            assertThat(admin.getViews()).extracting(DbObject::getName).containsExactly("V_CLAIMS");
        }
    }

    @Test
    void snowflakeRedshiftClickHouseBigQueryAndSybaseHarvestersReassembleQueryHistoryAndCatalogs() throws Exception {
        assertThat(SnowflakeMetadataHarvester.SUPPORTED_71_QUERY_TYPES.size()).isGreaterThanOrEqualTo(71);

        // 1a. Snowflake SNOWFLAKE.ACCOUNT_USAGE + STAGES + QUERY_HISTORY (71 statement types filter)
        String sfAuUrl = "jdbc:h2:mem:SNOWFLAKE;DB_CLOSE_DELAY=-1";
        try (Connection conn = DriverManager.getConnection(sfAuUrl, "sa", "");
                Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS ACCOUNT_USAGE");
            st.execute("CREATE TABLE ACCOUNT_USAGE.TABLES (TABLE_CATALOG VARCHAR(64), TABLE_SCHEMA VARCHAR(64), TABLE_NAME VARCHAR(64), TABLE_TYPE VARCHAR(32), DELETED TIMESTAMP)");
            st.execute("CREATE TABLE ACCOUNT_USAGE.COLUMNS (TABLE_CATALOG VARCHAR(64), TABLE_SCHEMA VARCHAR(64), TABLE_NAME VARCHAR(64), COLUMN_NAME VARCHAR(64), DATA_TYPE VARCHAR(32), CHARACTER_MAXIMUM_LENGTH INT, NUMERIC_PRECISION INT, NUMERIC_SCALE INT, IS_NULLABLE VARCHAR(8), COLUMN_DEFAULT VARCHAR(64), IS_IDENTITY VARCHAR(8), COMMENT VARCHAR(128), ORDINAL_POSITION INT, DELETED TIMESTAMP)");
            st.execute("CREATE TABLE ACCOUNT_USAGE.VIEWS (TABLE_CATALOG VARCHAR(64), TABLE_SCHEMA VARCHAR(64), TABLE_NAME VARCHAR(64), VIEW_DEFINITION VARCHAR(2000), IS_MATERIALIZED VARCHAR(8), DELETED TIMESTAMP)");
            st.execute("CREATE TABLE ACCOUNT_USAGE.STAGES (STAGE_CATALOG VARCHAR(64), STAGE_SCHEMA VARCHAR(64), STAGE_NAME VARCHAR(64), STAGE_URL VARCHAR(256), STAGE_TYPE VARCHAR(32), DELETED TIMESTAMP)");
            st.execute("CREATE TABLE ACCOUNT_USAGE.QUERY_HISTORY (QUERY_ID VARCHAR(64), QUERY_TEXT VARCHAR(2000), DATABASE_NAME VARCHAR(64), SCHEMA_NAME VARCHAR(64), QUERY_TYPE VARCHAR(64), EXECUTION_STATUS VARCHAR(32), START_TIME BIGINT)");

            st.execute("INSERT INTO ACCOUNT_USAGE.TABLES VALUES ('FIN_DW', 'MART', 'REV_FACT', 'BASE TABLE', NULL)");
            st.execute("INSERT INTO ACCOUNT_USAGE.COLUMNS VALUES ('FIN_DW', 'MART', 'REV_FACT', 'REV_ID', 'NUMBER', NULL, 38, 0, 'NO', NULL, 'YES', 'Surrogate key', 1, NULL)");
            st.execute("INSERT INTO ACCOUNT_USAGE.VIEWS VALUES ('FIN_DW', 'MART', 'MV_REV', 'SELECT REV_ID FROM FIN_DW.MART.REV_FACT', 'YES', NULL)");
            st.execute("INSERT INTO ACCOUNT_USAGE.STAGES VALUES ('FIN_DW', 'MART', 'S3_RAW_STAGE', 's3://corp-lake/raw/', 'EXTERNAL', NULL)");
            st.execute("INSERT INTO ACCOUNT_USAGE.QUERY_HISTORY VALUES ('01b-merge', 'MERGE INTO FIN_DW.MART.REV_FACT t USING STG s ON t.REV_ID = s.REV_ID WHEN MATCHED THEN UPDATE SET t.REV_ID = s.REV_ID', 'FIN_DW', 'MART', 'MERGE', 'SUCCESS', 200)");
            st.execute("INSERT INTO ACCOUNT_USAGE.QUERY_HISTORY VALUES ('01b-show', 'SHOW WAREHOUSES', 'FIN_DW', 'MART', 'SHOW', 'SUCCESS', 100)");

            MetadataHarvestResult sfAuRes = connector.harvestMetadata(EDbVendor.dbvsnowflake, h2DataSource(sfAuUrl, "sf-au"));
            SchemaObject mart = sfAuRes.getCatalog().getDatabases().stream()
                    .filter(d -> d.getName().equals("FIN_DW"))
                    .findFirst().orElseThrow()
                    .getSchemas().get(0);
            assertThat(mart.getTables()).extracting(DbObject::getName).containsExactly("REV_FACT");
            assertThat(mart.getViews()).extracting(DbObject::getName).containsExactly("MV_REV");
            assertThat(mart.getOthers()).extracting(DbObject::getName).containsExactly("@S3_RAW_STAGE");
            assertThat(sfAuRes.getExtractedSqlUnits())
                    .extracting(SqlInfo::getSql)
                    .anyMatch(s -> s.startsWith("MERGE INTO FIN_DW.MART.REV_FACT"))
                    .noneMatch(s -> s.contains("SHOW WAREHOUSES"));
        }

        // 1b. Snowflake INFORMATION_SCHEMA fallback when ACCOUNT_USAGE is restricted
        String sfUrl = uniqueH2Url("sf_info_schema");
        try (Connection conn = DriverManager.getConnection(sfUrl, "sa", "");
                Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA CORE");
            st.execute("CREATE TABLE CORE.CUSTOMERS (CUST_ID INT PRIMARY KEY, EMAIL VARCHAR(120))");
            st.execute("CREATE VIEW CORE.V_VIP AS SELECT CUST_ID, EMAIL FROM CORE.CUSTOMERS");

            MetadataHarvestResult sfRes = connector.harvestMetadata(EDbVendor.dbvsnowflake, h2DataSource(sfUrl, "sf-acct"));
            SchemaObject core = sfRes.getCatalog().getDatabases().get(0).getSchemas().stream()
                    .filter(s -> s.getName().equalsIgnoreCase("CORE"))
                    .findFirst()
                    .orElseThrow();
            assertThat(core.getTables()).extracting(DbObject::getName).containsExactly("CUSTOMERS");
            assertThat(core.getViews()).extracting(DbObject::getName).containsExactly("V_VIP");
        }

        // 2. Redshift SVV_COLUMNS + SVV_EXTERNAL_COLUMNS + multi-part STL_QUERYTEXT reassembly
        String rsUrl = uniqueH2Url("redshift_cat");
        try (Connection conn = DriverManager.getConnection(rsUrl, "sa", "");
                Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE SVV_COLUMNS (table_catalog VARCHAR(64), table_schema VARCHAR(64), table_name VARCHAR(64), column_name VARCHAR(64), data_type VARCHAR(32), character_maximum_length INT, numeric_precision INT, numeric_scale INT, is_nullable VARCHAR(8), column_default VARCHAR(64), remarks VARCHAR(128), ordinal_position INT)");
            st.execute("CREATE TABLE SVV_EXTERNAL_COLUMNS (schemaname VARCHAR(64), tablename VARCHAR(64), columnname VARCHAR(64), external_type VARCHAR(64), columnnum INT)");
            st.execute("CREATE TABLE PG_VIEWS (schemaname VARCHAR(64), viewname VARCHAR(64), definition VARCHAR(2000))");
            st.execute("CREATE TABLE STL_QUERY (query INT, database VARCHAR(64))");
            st.execute("CREATE TABLE STL_QUERYTEXT (userid INT, query INT, sequence INT, text VARCHAR(256))");

            st.execute("INSERT INTO SVV_COLUMNS VALUES ('dev', 'public', 'events', 'event_id', 'bigint', NULL, 64, 0, 'NO', NULL, 'Event PK', 1)");
            st.execute("INSERT INTO SVV_EXTERNAL_COLUMNS VALUES ('spectrum_s3', 'raw_clicks', 'click_url', 'varchar(512)', 1)");
            st.execute("INSERT INTO PG_VIEWS VALUES ('public', 'v_events', 'SELECT event_id FROM public.events WITH NO SCHEMA BINDING')");
            st.execute("INSERT INTO STL_QUERY VALUES (501, 'dev')");
            st.execute("INSERT INTO STL_QUERYTEXT VALUES (100, 501, 0, 'INSERT INTO public.events ')");
            st.execute("INSERT INTO STL_QUERYTEXT VALUES (100, 501, 1, 'SELECT 1 AS event_id;')");
            // userid = 1 internal rdsdb query should be ignored
            st.execute("INSERT INTO STL_QUERYTEXT VALUES (1, 502, 0, 'SELECT internal_rds_check FROM stv_slices')");

            DataSource rsDs = h2DataSource(rsUrl, "rs-cluster");
            rsDs.setDatabase("dev");
            MetadataHarvestResult rsRes = connector.harvestMetadata(EDbVendor.dbvredshift, rsDs);
            assertThat(rsRes.getCatalog().getDatabases().get(0).getSchemas())
                    .extracting(SchemaObject::getName)
                    .containsExactlyInAnyOrder("public", "spectrum_s3");
            assertThat(rsRes.getExtractedSqlUnits())
                    .extracting(SqlInfo::getSql)
                    .anyMatch(s -> s.equals("INSERT INTO public.events SELECT 1 AS event_id;"))
                    .noneMatch(s -> s.contains("internal_rds_check"));
        }

        // 3. ClickHouse system.columns + system.tables + system.query_log
        String chUrl = uniqueH2Url("ch_system") + ";DATABASE_TO_LOWER=TRUE";
        try (Connection conn = DriverManager.getConnection(chUrl, "sa", "");
                Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA system");
            st.execute("CREATE TABLE system.tables (database VARCHAR(64), name VARCHAR(64), engine VARCHAR(64), create_table_query VARCHAR(2000), as_select VARCHAR(2000), is_temporary INT)");
            st.execute("CREATE TABLE system.columns (database VARCHAR(64), \"table\" VARCHAR(64), name VARCHAR(64), type VARCHAR(64), default_kind VARCHAR(32), default_expression VARCHAR(128), comment VARCHAR(128), is_in_primary_key INT, is_in_sorting_key INT, position INT)");
            st.execute("CREATE TABLE system.query_log (query_id VARCHAR(64), current_database VARCHAR(64), type VARCHAR(32), is_initial_query INT, query_kind VARCHAR(32), event_time BIGINT, query VARCHAR(2000))");

            st.execute("INSERT INTO system.tables VALUES ('telemetry', 'spans', 'MergeTree', 'CREATE TABLE telemetry.spans ...', NULL, 0)");
            st.execute("INSERT INTO system.tables VALUES ('telemetry', 'mv_error_spans', 'MaterializedView', 'CREATE MATERIALIZED VIEW telemetry.mv_error_spans TO telemetry.errors AS SELECT trace_id FROM telemetry.spans', NULL, 0)");
            st.execute("INSERT INTO system.columns VALUES ('telemetry', 'spans', 'trace_id', 'String', '', '', 'Trace ID', 1, 1, 1)");
            st.execute("INSERT INTO system.columns VALUES ('telemetry', 'spans', 'err_msg', 'Nullable(String)', '', '', '', 0, 0, 2)");
            st.execute("INSERT INTO system.query_log VALUES ('q-99', 'telemetry', 'QueryFinish', 1, 'Insert', 1000, 'INSERT INTO telemetry.spans SELECT * FROM telemetry.staging_spans')");

            MetadataHarvestResult chRes = connector.harvestMetadata(EDbVendor.dbvclickhouse, h2DataSource(chUrl, "ch-node"));
            DatabaseObject telemetry = chRes.getCatalog().getDatabases().get(0);
            assertThat(telemetry.getName()).isEqualTo("telemetry");
            assertThat(telemetry.getTables().get(0).getColumns().get(0).getPrimaryKey()).isTrue();
            assertThat(telemetry.getTables().get(0).getColumns().get(1).getNullable()).isTrue();
            assertThat(chRes.getExtractedSqlUnits()).hasSize(2);
        }

        // 4. Sybase sysobjects + syscolumns + multi-chunk syscomments
        String sybUrl = uniqueH2Url("syb_cat");
        try (Connection conn = DriverManager.getConnection(sybUrl, "sa", "");
                Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE sysusers (uid INT, name VARCHAR(64))");
            st.execute("CREATE TABLE systypes (usertype INT, name VARCHAR(32))");
            st.execute("CREATE TABLE sysobjects (id INT, uid INT, name VARCHAR(64), type VARCHAR(4))");
            st.execute("CREATE TABLE syscolumns (id INT, colid INT, name VARCHAR(64), usertype INT, length INT, prec INT, scale INT, status INT)");
            st.execute("CREATE TABLE syscomments (id INT, colid INT, text VARCHAR(1000))");

            st.execute("INSERT INTO sysusers VALUES (1, 'dbo')");
            st.execute("INSERT INTO systypes VALUES (7, 'int'), (39, 'varchar')");
            st.execute("INSERT INTO sysobjects VALUES (10, 1, 'trades', 'U'), (20, 1, 'sp_settle', 'P')");
            st.execute("INSERT INTO syscolumns VALUES (10, 1, 'trade_id', 7, 4, 10, 0, 128)");
            st.execute("INSERT INTO syscomments VALUES (20, 1, 'CREATE PROC dbo.sp_settle AS '), (20, 2, 'SELECT trade_id FROM dbo.trades')");

            MetadataHarvestResult sybRes = connector.harvestMetadata(EDbVendor.dbvsybase, h2DataSource(sybUrl, "syb-ase"));
            SchemaObject dbo = sybRes.getCatalog().getDatabases().get(0).getSchemas().get(0);
            assertThat(dbo.getTables()).extracting(DbObject::getName).containsExactly("trades");
            assertThat(dbo.getProcedures()).extracting(p -> p.getName()).containsExactly("sp_settle");
            assertThat(sybRes.getExtractedSqlUnits().get(0).getSql())
                    .isEqualTo("CREATE PROC dbo.sp_settle AS SELECT trade_id FROM dbo.trades");
        }
    }

    @Test
    void sqliteLiveDatabaseHarvesterAndSchemaIncludeExcludeFiltersWorkEndToEnd(@TempDir Path tempDir) throws Exception {
        Path sqliteFile = tempDir.resolve("local_warehouse.sqlite");
        String sqliteUrl = "jdbc:sqlite:" + sqliteFile.toAbsolutePath();

        try (Connection conn = DriverManager.getConnection(sqliteUrl);
                Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("CREATE TABLE departments (dept_id INTEGER PRIMARY KEY, code TEXT NOT NULL UNIQUE)");
            st.execute("CREATE TABLE employees (emp_id INTEGER PRIMARY KEY, dept_id INTEGER REFERENCES departments(dept_id), salary REAL DEFAULT 1000.0)");
            st.execute("CREATE VIEW v_dept_payroll AS SELECT d.code, SUM(e.salary) AS total_pay FROM departments d JOIN employees e ON d.dept_id = e.dept_id GROUP BY d.code");
            st.execute("CREATE TRIGGER trg_emp_audit AFTER INSERT ON employees BEGIN SELECT NEW.emp_id; END");
        }

        DataSource ds = new DataSource();
        ds.setDatabase(sqliteFile.toAbsolutePath().toString());
        ds.setExtractedViews("v_dept_*");
        MetadataHarvestResult res = connector.harvestMetadata(EDbVendor.dbvsqlite, ds);

        SchemaObject main = res.getCatalog().getDatabases().get(0).getSchemas().get(0);
        assertThat(main.getTables()).extracting(DbObject::getName).containsExactlyInAnyOrder("departments", "employees");
        assertThat(main.getViews()).extracting(DbObject::getName).containsExactly("v_dept_payroll");
        assertThat(main.getTriggers()).hasSize(1);

        DbObject dept = main.getTables().stream().filter(t -> t.getName().equals("departments")).findFirst().orElseThrow();
        assertThat(dept.getColumns().stream().filter(c -> c.getName().equals("dept_id")).findFirst().orElseThrow().getPrimaryKey()).isTrue();
        assertThat(dept.getColumns().stream().filter(c -> c.getName().equals("code")).findFirst().orElseThrow().getUnqiueKey()).isTrue();

        DbObject emp = main.getTables().stream().filter(t -> t.getName().equals("employees")).findFirst().orElseThrow();
        assertThat(emp.getColumns().stream().filter(c -> c.getName().equals("dept_id")).findFirst().orElseThrow().getForeignKey()).isTrue();

        // Exclude main schema -> empty catalog
        ds.setExcludedDbsSchemas("main");
        MetadataHarvestResult excludedRes = connector.harvestMetadata(EDbVendor.dbvsqlite, ds);
        assertThat(excludedRes.getCatalog().getDatabases()).isEmpty();
    }

    @Test
    void bigQueryAthenaHiveImpalaAndDuckDbHarvestersExtractCatalogsPartitionsAndDdl(@TempDir Path tempDir) throws Exception {
        // 1. BigQuery & Athena INFORMATION_SCHEMA + JOBS_BY_PROJECT
        String bqUrl = uniqueH2Url("bq_project");
        try (Connection conn = DriverManager.getConnection(bqUrl, "sa", "");
                Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA dataset_finance");
            st.execute("CREATE TABLE dataset_finance.invoices (invoice_id BIGINT PRIMARY KEY, total_usd DECIMAL(18,2))");
            st.execute("CREATE VIEW dataset_finance.v_large_invoices AS SELECT invoice_id, total_usd FROM dataset_finance.invoices WHERE total_usd > 1000");

            DataSource bqDs = h2DataSource(bqUrl, "bigquery.googleapis.com");
            bqDs.setDatabase("corp-analytics-prod");
            bqDs.setExtractedDbsSchemas("DATASET_FINANCE");

            MetadataHarvestResult bqRes = connector.harvestMetadata(EDbVendor.dbvbigquery, bqDs);
            SchemaObject bqSchema = bqRes.getCatalog().getDatabases().get(0).getSchemas().get(0);
            assertThat(bqSchema.getTables()).extracting(DbObject::getName).containsExactly("INVOICES");
            assertThat(bqSchema.getViews()).extracting(DbObject::getName).containsExactly("V_LARGE_INVOICES");

            MetadataHarvestResult athenaRes = connector.harvestMetadata(EDbVendor.dbvathena, bqDs);
            assertThat(athenaRes.getCatalog().getDatabases().get(0).getSchemas().get(0).getTables())
                    .extracting(DbObject::getName).containsExactly("INVOICES");
        }

        // 2. Hive / Impala SHOW DATABASES + SHOW TABLES/VIEWS + DESCRIBE FORMATTED + SHOW CREATE TABLE
        String hiveBackingUrl = uniqueH2Url("hive_hs2_mock");
        try (Connection backing = DriverManager.getConnection(hiveBackingUrl, "sa", "");
                Statement st = backing.createStatement()) {
            st.execute("CREATE TABLE mock_dbs (database_name VARCHAR(64))");
            st.execute("INSERT INTO mock_dbs VALUES ('lakehouse')");
            st.execute("CREATE TABLE mock_tables (tab_name VARCHAR(64))");
            st.execute("INSERT INTO mock_tables VALUES ('events_ext'), ('v_daily_events')");
            st.execute("CREATE TABLE mock_views (tab_name VARCHAR(64))");
            st.execute("INSERT INTO mock_views VALUES ('v_daily_events')");

            st.execute("CREATE TABLE desc_events (ord INT, col_name VARCHAR(128), data_type VARCHAR(128), comment VARCHAR(256))");
            st.execute("INSERT INTO desc_events VALUES "
                    + "(1, '# col_name', 'data_type', 'comment'),"
                    + "(2, 'event_id', 'string', 'Unique ID'),"
                    + "(3, 'amount', 'decimal(18,2)', 'Order amount'),"
                    + "(4, '# Partition Information', NULL, NULL),"
                    + "(5, '# col_name', 'data_type', 'comment'),"
                    + "(6, 'dt', 'string', NULL),"
                    + "(7, '# Detailed Table Information', NULL, NULL),"
                    + "(8, 'Table Type:', 'EXTERNAL_TABLE', NULL),"
                    + "(9, 'Location:', 's3a://datalake/warehouse/events_ext', NULL)");

            st.execute("CREATE TABLE desc_view (ord INT, col_name VARCHAR(128), data_type VARCHAR(128), comment VARCHAR(256))");
            st.execute("INSERT INTO desc_view VALUES "
                    + "(1, 'dt', 'string', NULL),"
                    + "(2, 'total_amt', 'decimal(18,2)', NULL),"
                    + "(3, '# Detailed Table Information', NULL, NULL),"
                    + "(4, 'Table Type:', 'VIRTUAL_VIEW', NULL)");

            st.execute("CREATE TABLE create_view_ddl (ord INT, createtab_stmt VARCHAR(512))");
            st.execute("INSERT INTO create_view_ddl VALUES "
                    + "(1, 'CREATE VIEW `lakehouse`.`v_daily_events` AS'),"
                    + "(2, 'SELECT dt, SUM(amount) AS total_amt FROM `lakehouse`.`events_ext` GROUP BY dt')");

            Connection hiveProxy = createHiveProtocolConnectionProxy(backing);
            DataSource hiveDs = new DataSource();
            hiveDs.setHostname("hive-hs2.corp");
            CatalogBuilder builder = new CatalogBuilder(EDbVendor.dbvhive, "hive-hs2.corp");
            new HiveImpalaMetadataHarvester().harvest(
                    hiveProxy, EDbVendor.dbvhive, hiveDs, new SchemaFilterMatcher(EDbVendor.dbvhive, hiveDs), builder, 10);

            SqlflowCatalog hiveCat = builder.buildCatalog();
            DatabaseObject lakehouse = hiveCat.getDatabases().get(0);
            assertThat(lakehouse.getName()).isEqualTo("lakehouse");
            assertThat(lakehouse.getTables()).hasSize(1);
            DbObject extTable = lakehouse.getTables().get(0);
            assertThat(extTable.getName()).isEqualTo("events_ext");
            assertThat(extTable.getLocation()).isEqualTo("s3a://datalake/warehouse/events_ext");
            assertThat(extTable.getColumns()).extracting(Column::getName).containsExactly("event_id", "amount", "dt");
            assertThat(extTable.getColumns().get(2).getComment()).isEqualTo("PARTITION COLUMN");

            assertThat(lakehouse.getViews()).extracting(DbObject::getName).containsExactly("v_daily_events");
            assertThat(builder.buildSqlUnits())
                    .extracting(SqlInfo::getSql)
                    .anyMatch(s -> s.contains("SELECT dt, SUM(amount) AS total_amt FROM `lakehouse`.`events_ext`"));
        }

        // 3. DuckDB live embedded JDBC harvest
        Path duckFile = tempDir.resolve("local_analytics.duckdb");
        String duckUrl = "jdbc:duckdb:" + duckFile.toAbsolutePath();
        try (Connection conn = DriverManager.getConnection(duckUrl);
                Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE orders (order_id INTEGER PRIMARY KEY, total DOUBLE)");
            st.execute("CREATE VIEW v_high_orders AS SELECT order_id, total FROM orders WHERE total > 50");
        }
        DataSource duckDs = new DataSource();
        duckDs.setDatabase(duckFile.toAbsolutePath().toString());
        MetadataHarvestResult duckRes = connector.harvestMetadata(EDbVendor.dbvduckdb, duckDs);
        SchemaObject duckMain = duckRes.getCatalog().getDatabases().get(0).getSchemas().stream()
                .filter(s -> s.getName().equalsIgnoreCase("main"))
                .findFirst().orElseThrow();
        assertThat(duckMain.getTables()).extracting(DbObject::getName).containsExactly("orders");
        assertThat(duckMain.getViews()).extracting(DbObject::getName).containsExactly("v_high_orders");
    }

    private static Connection createHiveProtocolConnectionProxy(Connection backing) {
        DatabaseMetaData metaProxy = (DatabaseMetaData) Proxy.newProxyInstance(
                VendorMetadataHarvestersTest.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class},
                (proxy, method, args) -> {
                    if ("getURL".equals(method.getName())) {
                        return "jdbc:hive2://hive-hs2.corp:10000/default";
                    }
                    return method.invoke(backing.getMetaData(), args);
                });

        return (Connection) Proxy.newProxyInstance(
                VendorMetadataHarvestersTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("getMetaData".equals(method.getName())) {
                        return metaProxy;
                    }
                    if ("createStatement".equals(method.getName()) && (args == null || args.length == 0)) {
                        Statement realStmt = backing.createStatement();
                        return Proxy.newProxyInstance(
                                VendorMetadataHarvestersTest.class.getClassLoader(),
                                new Class<?>[]{Statement.class},
                                (sProxy, sMethod, sArgs) -> {
                                    if ("executeQuery".equals(sMethod.getName()) && sArgs != null && sArgs.length == 1) {
                                        String q = ((String) sArgs[0]).trim();
                                        if (q.equalsIgnoreCase("SHOW DATABASES")) {
                                            return realStmt.executeQuery("SELECT database_name FROM mock_dbs");
                                        }
                                        if (q.startsWith("SHOW VIEWS IN")) {
                                            return realStmt.executeQuery("SELECT tab_name FROM mock_views");
                                        }
                                        if (q.startsWith("SHOW TABLES IN")) {
                                            return realStmt.executeQuery("SELECT tab_name FROM mock_tables");
                                        }
                                        if (q.startsWith("DESCRIBE FORMATTED `lakehouse`.`events_ext`")) {
                                            return realStmt.executeQuery("SELECT col_name, data_type, comment FROM desc_events ORDER BY ord");
                                        }
                                        if (q.startsWith("DESCRIBE FORMATTED `lakehouse`.`v_daily_events`")) {
                                            return realStmt.executeQuery("SELECT col_name, data_type, comment FROM desc_view ORDER BY ord");
                                        }
                                        if (q.startsWith("SHOW CREATE TABLE `lakehouse`.`v_daily_events`")) {
                                            return realStmt.executeQuery("SELECT createtab_stmt FROM create_view_ddl ORDER BY ord");
                                        }
                                    }
                                    return sMethod.invoke(realStmt, sArgs);
                                });
                    }
                    return method.invoke(backing, args);
                });
    }

    private static String uniqueH2Url(String prefix) {
        return "jdbc:h2:mem:" + prefix + "_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
    }

    private static DataSource h2DataSource(String url, String hostLabel) {
        DataSource ds = new DataSource();
        ds.setHostname(hostLabel);
        ds.setAccount("sa");
        ds.setPassword("");
        ds.setUrl(url);
        return ds;
    }
}
