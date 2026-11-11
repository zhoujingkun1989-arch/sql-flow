package io.sqlflow.lineage.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.contracts.graph.SummaryInfo;
import io.sqlflow.contracts.lineage.Coordinate;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.Option;
import io.sqlflow.contracts.lineage.Relationship;
import io.sqlflow.contracts.lineage.SqlInfo;
import io.sqlflow.contracts.spi.LineageEngineSpi;
import io.sqlflow.contracts.spi.LineageEngineSpi.DegradationMode;
import io.sqlflow.contracts.spi.LineageEngineSpi.LineageAnalysisRequest;
import io.sqlflow.contracts.spi.LineageEngineSpi.LineageAnalysisResult;
import io.sqlflow.contracts.spi.LineageEngineSpi.LineageInputType;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.lineage.LineageEngineService;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link ServiceLoader} discovery, {@code "unqiueKey"} wire invariant, 1-based coordinates,
 * partial-failure error recovery, {@link Option} flags, Grabit/SQLDep JSON inputs, {@code sqlenvSnapshot},
 * {@code mergeDataFlows}, {@code summarize}, {@code splitLargeSql}, and complexity degradation
 * ({@code OBL-042..OBL-047, OBL-050}).
 */
class OptionsSerializationAndSpiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final LineageEngineSpi engine = ServiceLoader.load(LineageEngineSpi.class)
            .findFirst()
            .orElseThrow(() -> new AssertionError("LineageEngineSpi ServiceLoader registration missing"));

    @Test
    void serviceLoaderFindsLineageEngineService() {
        assertTrue(engine instanceof LineageEngineService);
    }

    @Test
    void unqiueKeySpellingAndOneBasedCoordinatesPreservedInJsonAndXml() throws Exception {
        String sql = """
                CREATE TABLE users (
                    user_id INT PRIMARY KEY,
                    email VARCHAR(120) UNIQUE
                );
                INSERT INTO users_copy (uid, mail)
                SELECT user_id, email FROM users;
                """;
        Option opt = new Option();
        opt.setVendor(EDbVendor.dbvpostgresql);
        opt.setShowERDiagram(true);
        opt.setOutput(true);

        LineageAnalysisResult res = engine.analyze(LineageAnalysisRequest.ofSql(sql, opt));
        String jsonWire = JSON.writeValueAsString(res.getDataFlow());
        String xmlWire = res.getXmlString();

        assertTrue(jsonWire.contains("\"unqiueKey\":true"), "JSON wire must spell unqiueKey: " + jsonWire);
        assertFalse(jsonWire.contains("\"uniqueKey\""), "JSON wire must not spell uniqueKey");
        assertTrue(xmlWire.contains("unqiueKey=\"true\""), "XML wire must spell unqiueKey: " + xmlWire);

        // Verify 1-based [line, col] coordinates on single-script input
        List<Coordinate> coords = res.getDataFlow().getRelationships().stream()
                .filter(r -> "fdd".equals(r.getType()))
                .findFirst().orElseThrow()
                .getTarget().getCoordinates();
        assertNotNull(coords);
        assertFalse(coords.isEmpty());
        assertTrue(coords.get(0).getX() >= 1L && coords.get(0).getY() >= 1L);

        // Verify 1-based [line, col, hashCode] 3-tuple coordinates on multi-unit SqlInfo[] input
        LineageAnalysisRequest multiReq = new LineageAnalysisRequest();
        multiReq.setInputType(LineageInputType.SQL_UNITS);
        multiReq.setOption(opt);
        multiReq.setSqlUnits(List.of(
                new SqlInfo("CREATE TABLE users (user_id INT, email VARCHAR(120));", "u1.sql", null, "dbvpostgresql"),
                new SqlInfo("INSERT INTO users_copy (uid, mail) SELECT user_id, email FROM users;", "u2.sql", null, "dbvpostgresql")));
        LineageAnalysisResult multiRes = engine.analyze(multiReq);
        Coordinate multiCoord = multiRes.getDataFlow().getRelationships().get(0).getTarget().getCoordinates().get(0);
        assertTrue(multiCoord.getX() >= 1L && multiCoord.getY() >= 1L);
        assertNotNull(multiCoord.getHashCode());

        // ignoreCoordinate = true strips coordinates
        Option noCoordOpt = new Option();
        noCoordOpt.setVendor(EDbVendor.dbvpostgresql);
        noCoordOpt.setIgnoreCoordinate(true);
        LineageAnalysisResult noCoordRes = engine.analyze(LineageAnalysisRequest.ofSql(sql, noCoordOpt));
        assertNull(noCoordRes.getDataFlow().getRelationships().get(0).getTarget().getCoordinates());
    }

    @Test
    void partialFailurePreservesValidLineageBeforeAndAfterBrokenStatement() {
        String sql = """
                INSERT INTO t_before (id) SELECT id FROM src_one;
                SELECT FROM WHERE ??? BROKEN SYNTAX ((( ;
                INSERT INTO t_after (name) SELECT name FROM src_two;
                """;
        Option opt = new Option();
        opt.setVendor(EDbVendor.dbvoracle);
        opt.setIgnoreRecordSet(true);

        LineageAnalysisResult res = engine.analyze(LineageAnalysisRequest.ofSql(sql, opt));
        assertNotNull(res.getErrors());
        assertFalse(res.getErrors().isEmpty(), "Syntax error must be captured in errors[]");

        List<Relationship> fdds = res.getDataFlow().getRelationships().stream()
                .filter(r -> "fdd".equals(r.getType()))
                .toList();
        assertTrue(fdds.stream().anyMatch(r -> "T_BEFORE".equals(r.getTarget().getParentName())));
        assertTrue(fdds.stream().anyMatch(r -> "T_AFTER".equals(r.getTarget().getParentName())));
    }

    @Test
    void constantTableTransformCodeFunctionNodesTraceSqlAndCommentBindings() {
        String sql = """
                -- Bind customer transformation comment
                INSERT INTO crm_target (cust_id, upper_name, status_literal)
                SELECT c.cust_id, UPPER(c.raw_name), 'ACTIVE'
                FROM crm_source c;
                """;
        Option opt = new Option();
        opt.setVendor(EDbVendor.dbvoracle);
        opt.setShowConstantTable(true);
        opt.setSimpleOutput(true);
        opt.setSimpleShowFunction(true);
        opt.setTransform(true);
        opt.setTransformCoordinate(true);
        opt.setTraceSQL(true);
        opt.setCollectCommentBindings(true);

        LineageAnalysisResult res = engine.analyze(LineageAnalysisRequest.ofSql(sql, opt));
        DataFlow df = res.getDataFlow();

        // SQL_CONSTANTS placed in others[]
        assertNotNull(df.getDbobjs().getServers().get(0).getSchemas().get(0).getOthers());
        assertTrue(df.getDbobjs().getServers().get(0).getSchemas().get(0).getOthers().stream()
                .anyMatch(o -> "SQL_CONSTANTS".equals(o.getName()) || o.getName().startsWith("RESULT_OF_")));

        // Transform code attached
        assertTrue(df.getRelationships().stream()
                .flatMap(r -> r.getSources().stream())
                .anyMatch(s -> s.getTransforms() != null && !s.getTransforms().isEmpty()));

        // traceSQL emits <process>
        assertNotNull(df.getProcesses());
        assertFalse(df.getProcesses().isEmpty());

        // Comment bindings populated
        assertNotNull(df.getCommentBindings());
        assertFalse(df.getCommentBindings().comments().isEmpty());
    }

    @Test
    void grabitExporterAndSqldepJsonInputsProduceCatalogExpandedLineage() {
        String exporterJson = """
                {
                  "dialect": "dbvsnowflake",
                  "servers": [{
                    "name": "SF_PROD",
                    "databases": [{
                      "name": "RAW_DB",
                      "schemas": [{
                        "name": "PUBLIC",
                        "tables": [{
                          "name": "CUSTOMERS",
                          "columns": [
                            {"name": "CUST_ID", "dataType": "NUMBER", "primaryKey": true},
                            {"name": "EMAIL", "dataType": "VARCHAR", "unqiueKey": true}
                          ]
                        }]
                      }]
                    }]
                  }],
                  "queries": [{
                    "sourceCode": "CREATE VIEW RAW_DB.PUBLIC.V_CUST AS SELECT * FROM RAW_DB.PUBLIC.CUSTOMERS;"
                  }]
                }
                """;
        LineageAnalysisRequest req = new LineageAnalysisRequest();
        req.setInputType(LineageInputType.EXPORTER_METADATA_JSON);
        req.setMetadataJson(exporterJson);
        Option opt = new Option();
        opt.setSimpleOutput(true);
        req.setOption(opt);

        DataFlow df = engine.analyze(req).getDataFlow();
        assertEquals("dbvsnowflake", df.getDbvendor());
        assertEquals(2L, df.getRelationships().stream().filter(r -> "fdd".equals(r.getType())).count());

        String sqldepJson = """
                {
                  "dialect": "postgresql",
                  "queries": [
                    {"sourceCode": "INSERT INTO mart.orders (id) SELECT id FROM raw.orders;"}
                  ]
                }
                """;
        LineageAnalysisRequest depReq = new LineageAnalysisRequest();
        depReq.setInputType(LineageInputType.SQLDEP_JSON);
        depReq.setMetadataJson(sqldepJson);
        Option depOpt = new Option();
        depOpt.setSimpleOutput(true);
        depReq.setOption(depOpt);
        DataFlow depDf = engine.analyze(depReq).getDataFlow();
        assertEquals("dbvpostgresql", depDf.getDbvendor());
        assertFalse(depDf.getRelationships().isEmpty());
    }

    @Test
    void incrementalSqlenvSnapshotMergeSummarizeSplitAndDegradationModes() {
        Option opt = new Option();
        opt.setVendor(EDbVendor.dbvsnowflake);
        opt.setSimpleOutput(true);

        // Batch 1: DDL defines table schema and returns sqlenvSnapshot
        LineageAnalysisResult batch1 = engine.analyze(LineageAnalysisRequest.ofSql(
                "CREATE TABLE analytics.public.items (item_id INT, sku VARCHAR(50), price NUMBER);", opt));
        assertNotNull(batch1.getSqlenvSnapshot());
        assertTrue(batch1.getSqlenvSnapshot().length > 0);

        // Batch 2: Consumes sqlenvSnapshot so SELECT * expands to all 3 columns without DDL in batch 2
        LineageAnalysisRequest req2 = LineageAnalysisRequest.ofSql(
                "CREATE TABLE analytics.public.items_backup AS SELECT * FROM analytics.public.items;", opt);
        req2.setSqlenvSnapshot(batch1.getSqlenvSnapshot());
        LineageAnalysisResult batch2 = engine.analyze(req2);
        assertEquals(3L, batch2.getDataFlow().getRelationships().stream()
                .filter(r -> "fdd".equals(r.getType())).count());

        // Merge batches & summarize
        DataFlow merged = engine.mergeDataFlows(List.of(batch1.getDataFlow(), batch2.getDataFlow()), opt);
        SummaryInfo summary = engine.summarize(merged);
        assertEquals(2, summary.getTable());
        assertTrue(summary.getColumn() >= 6);
        assertEquals(3, summary.getRelationship());
        assertFalse(summary.getMostRelationTables().isEmpty());

        // splitLargeSql keeps PL/SQL package intact while splitting statements
        String multiScript = "INSERT INTO t1 (a) SELECT a FROM s1;\n"
                + "CREATE PROCEDURE p1 AS BEGIN INSERT INTO t2 (b) SELECT b FROM s2; END;\n"
                + "INSERT INTO t3 (c) SELECT c FROM s3;\n";
        List<String> chunks = engine.splitLargeSql(multiScript, EDbVendor.dbvoracle, 60);
        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.stream().anyMatch(c -> c.contains("CREATE PROCEDURE p1") && c.contains("END;")));

        // Explicit TABLE_ONLY degradation mode via option.traceSQL
        Option tblOpt = new Option();
        tblOpt.setVendor(EDbVendor.dbvoracle);
        tblOpt.setTraceSQL(true);
        LineageAnalysisResult tblRes = engine.analyze(LineageAnalysisRequest.ofSql(
                "INSERT INTO dst (x) SELECT x FROM src;", tblOpt));
        assertEquals(DegradationMode.TABLE_ONLY, tblRes.getMode());
    }
}
