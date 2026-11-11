package io.sqlflow.lineage.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.Option;
import io.sqlflow.contracts.lineage.Relationship;
import io.sqlflow.contracts.lineage.ServerObject;
import io.sqlflow.contracts.spi.LineageEngineSpi.LineageAnalysisRequest;
import io.sqlflow.contracts.spi.LineageEngineSpi.LineageAnalysisResult;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.lineage.LineageEngineService;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies all 42 active {@link EDbVendor} dialects, 6 stubbed dialects, dbt Jinja templates,
 * Power Query M, MDX, Snowflake/S3 stages, and catalog hierarchy profiles ({@code OBL-029..OBL-035}).
 */
class DialectCoverageAnalyzerTest {

    private final LineageEngineService engine = new LineageEngineService();

    static Stream<EDbVendor> activeDialects() {
        return Arrays.stream(EDbVendor.values()).filter(EDbVendor::isImplemented);
    }

    static Stream<EDbVendor> stubbedDialects() {
        return Arrays.stream(EDbVendor.values()).filter(v -> !v.isImplemented());
    }

    @Test
    void dialectEnumCountsMatchContract() {
        assertEquals(42L, activeDialects().count());
        assertEquals(6L, stubbedDialects().count());
    }

    @ParameterizedTest(name = "active dialect {0}")
    @MethodSource("activeDialects")
    void all42ActiveDialectsProduceDirectLineage(EDbVendor vendor) {
        String sql = sampleSqlForDialect(vendor);
        Option opt = new Option();
        opt.setVendor(vendor);
        opt.setSimpleOutput(true);
        opt.setOutput(true);

        LineageAnalysisResult res = engine.analyze(LineageAnalysisRequest.ofSql(sql, opt));
        assertNotNull(res);
        DataFlow df = res.getDataFlow();
        assertNotNull(df);
        assertEquals(vendor.wireName(), df.getDbvendor());
        assertNotNull(res.getXmlString());
        assertTrue(res.getXmlString().contains("<dlineage"));
        assertNotNull(df.getRelationships());
        assertFalse(df.getRelationships().isEmpty(), "Expected lineage edges for dialect " + vendor);
    }

    @ParameterizedTest(name = "stub dialect {0}")
    @MethodSource("stubbedDialects")
    void stubbedDialectsReturnGracefulUnsupportedError(EDbVendor vendor) {
        Option opt = new Option();
        opt.setVendor(vendor);

        LineageAnalysisResult res = engine.analyze(LineageAnalysisRequest.ofSql("SELECT id FROM emp;", opt));
        assertNotNull(res);
        assertNotNull(res.getDataFlow());
        assertEquals(vendor.wireName(), res.getDataFlow().getDbvendor());
        assertNotNull(res.getErrors());
        assertFalse(res.getErrors().isEmpty());
        assertEquals("UnsupportedDialect", res.getErrors().get(0).getErrorType());
    }

    @Test
    void dbtJinjaRefAndSourcePreserveCoordinatesAndEndpoints() {
        String sql = """
                {% config(materialized='table') %}
                INSERT INTO analytics.fct_orders (order_id, customer_id, total_amt)
                SELECT o.order_id, c.customer_id, o.amount
                FROM {{ ref('stg_orders') }} o
                JOIN {{ source('raw_crm', 'customers') }} c ON o.customer_id = c.customer_id;
                """;
        Option opt = new Option();
        opt.setVendor(EDbVendor.dbvsnowflake);
        opt.setIgnoreRecordSet(true);

        LineageAnalysisResult res = engine.analyze(LineageAnalysisRequest.ofSql(sql, opt));
        DataFlow df = res.getDataFlow();
        assertTrue(df.getErrors() == null || df.getErrors().isEmpty(), "Unexpected errors: " + df.getErrors());

        List<Relationship> fddEdges = df.getRelationships().stream()
                .filter(r -> "fdd".equals(r.getType()))
                .toList();
        assertEquals(3, fddEdges.size());
        assertTrue(fddEdges.stream().anyMatch(r ->
                r.getSources().stream().anyMatch(s -> s.getParentName().toUpperCase().contains("STG_ORDERS"))));
        assertTrue(fddEdges.stream().anyMatch(r ->
                r.getSources().stream().anyMatch(s -> s.getParentName().toUpperCase().contains("CUSTOMERS"))));
    }

    @Test
    void snowflakeCopyIntoStageAndS3UnloadLineage() {
        String sql = """
                COPY INTO mart.sales_loaded (sale_id, revenue)
                FROM (SELECT $1, $2 FROM @ext_sales_stage/2026/)
                FILE_FORMAT = (TYPE = CSV);

                UNLOAD ('SELECT sale_id, revenue FROM mart.sales_loaded')
                TO 's3://corp-lake/exports/sales/'
                IAM_ROLE 'arn:aws:iam::123456789012:role/RedshiftExport';
                """;
        Option opt = new Option();
        opt.setVendor(EDbVendor.dbvsnowflake);
        opt.setSimpleOutput(true);

        LineageAnalysisResult res = engine.analyze(LineageAnalysisRequest.ofSql(sql, opt));
        assertNotNull(res.getDlineageXml().getStages(), "Expected <stage> element in XML");
        assertNotNull(res.getDlineageXml().getPaths(), "Expected <path> element in XML");
        assertFalse(res.getDataFlow().getRelationships().isEmpty());
    }

    @Test
    void catalogContainerHierarchyRespectsVendorSupportsCatalogsAndSchemas() {
        // 1. Oracle: supportsCatalogs = false, supportsSchemas = true -> ServerObject.schemas
        Option oraOpt = new Option();
        oraOpt.setVendor(EDbVendor.dbvoracle);
        DataFlow oraDf = engine.analyze(LineageAnalysisRequest.ofSql(
                "INSERT INTO hr.tgt (id) SELECT id FROM hr.src;", oraOpt)).getDataFlow();
        ServerObject oraSrv = oraDf.getDbobjs().getServers().get(0);
        assertFalse(oraSrv.getSupportsCatalogs());
        assertTrue(oraSrv.getSupportsSchemas());
        assertNotNull(oraSrv.getSchemas());

        // 2. MySQL: supportsCatalogs = true, supportsSchemas = false -> ServerObject.databases.tables
        Option myOpt = new Option();
        myOpt.setVendor(EDbVendor.dbvmysql);
        DataFlow myDf = engine.analyze(LineageAnalysisRequest.ofSql(
                "INSERT INTO `shop`.`tgt` (`id`) SELECT `id` FROM `shop`.`src`;", myOpt)).getDataFlow();
        ServerObject mySrv = myDf.getDbobjs().getServers().get(0);
        assertTrue(mySrv.getSupportsCatalogs());
        assertFalse(mySrv.getSupportsSchemas());
        assertNotNull(mySrv.getDatabases());
        List<DbObject> myTables = mySrv.getDatabases().get(0).getTables();
        assertNotNull(myTables);
        assertEquals(2, myTables.size());

        // 3. PostgreSQL: supportsCatalogs = true, supportsSchemas = true -> ServerObject.databases.schemas.tables
        Option pgOpt = new Option();
        pgOpt.setVendor(EDbVendor.dbvpostgresql);
        DataFlow pgDf = engine.analyze(LineageAnalysisRequest.ofSql(
                "INSERT INTO app.public.tgt (id) SELECT id::int FROM app.public.src;", pgOpt)).getDataFlow();
        ServerObject pgSrv = pgDf.getDbobjs().getServers().get(0);
        assertTrue(pgSrv.getSupportsCatalogs());
        assertTrue(pgSrv.getSupportsSchemas());
        assertNotNull(pgSrv.getDatabases().get(0).getSchemas());
    }

    private static String sampleSqlForDialect(EDbVendor vendor) {
        if (vendor == EDbVendor.dbvmdx) {
            return "SELECT {[Measures].[Sales Amount]} ON COLUMNS, {[Date].[Calendar Year].Members} ON ROWS FROM [SalesCube]";
        }
        if (vendor == EDbVendor.dbvpowerquery) {
            return "let Source = Value.NativeQuery(Sql.Database(\"srv\", \"db\"), \"SELECT emp_id, dept_id FROM hr_employees\") in Source";
        }
        if (vendor == EDbVendor.dbvsoql) {
            return "SELECT Id, Name, AccountId FROM Contact WHERE IsDeleted = false";
        }
        if (vendor == EDbVendor.dbvmssql || vendor == EDbVendor.dbvazuresql) {
            return "INSERT INTO [dbo].[target_tbl] ([id], [name]) SELECT [id], [name] FROM [dbo].[source_tbl]\nGO\n";
        }
        if (vendor == EDbVendor.dbvmysql || vendor == EDbVendor.dbvbigquery || vendor == EDbVendor.dbvsparksql) {
            return "INSERT INTO `target_tbl` (`id`, `name`) SELECT `id`, `name` FROM `source_tbl`;";
        }
        return "INSERT INTO target_tbl (id, name) SELECT s.id, s.name FROM source_tbl s WHERE s.active = 1;";
    }
}
