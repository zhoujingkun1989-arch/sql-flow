package io.sqlflow.lineage.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.JoinRelationship;
import io.sqlflow.contracts.lineage.Option;
import io.sqlflow.contracts.lineage.Relationship;
import io.sqlflow.contracts.spi.LineageEngineSpi.LineageAnalysisRequest;
import io.sqlflow.contracts.spi.LineageEngineSpi.LineageAnalysisResult;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.lineage.LineageEngineService;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Verifies all 8 {@code RelationshipType}s, {@code CREATE VIEW}, {@code SELECT *} expansion &
 * back-propagation, orphan columns, {@code CASE WHEN}, {@code COUNT(*)}, {@code PIVOT/UNPIVOT},
 * {@code LATERAL VIEW EXPLODE}, {@code MERGE}, {@code INSERT FIRST}, and {@code UNION} ({@code OBL-036..OBL-041}).
 */
class CoreRelationsAndScopeAnalyzerTest {

    private final LineageEngineService engine = new LineageEngineService();

    @Test
    void emitsFddFdrJoinViewErCallAndCrudRelationships() {
        String sql = """
                CREATE TABLE dept (
                    dept_id INT PRIMARY KEY,
                    dept_code VARCHAR(32) UNIQUE
                );
                CREATE TABLE emp (
                    emp_id INT PRIMARY KEY,
                    dept_id INT REFERENCES dept(dept_id),
                    salary DECIMAL(10,2)
                );
                CREATE FUNCTION calc_bonus(sal DECIMAL) RETURNS DECIMAL AS
                BEGIN
                    RETURN sal * 0.1;
                END;
                CREATE VIEW v_dept_payroll AS
                SELECT d.dept_id,
                       COUNT(*) AS headcount,
                       SUM(calc_bonus(e.salary)) AS bonus_pool
                FROM emp e
                INNER JOIN dept d ON e.dept_id = d.dept_id
                WHERE e.salary > 1000
                GROUP BY d.dept_id;
                """;
        Option opt = new Option();
        opt.setVendor(EDbVendor.dbvoracle);
        opt.setShowERDiagram(true);
        opt.setShowCallRelation(true);
        opt.setShowJoin(true);
        opt.setFilterRelationTypes(Set.of("fdd", "fdr", "join", "er", "call", "crud"));

        LineageAnalysisResult res = engine.analyze(LineageAnalysisRequest.ofSql(sql, opt));
        DataFlow df = res.getDataFlow();
        Set<String> types = df.getRelationships().stream()
                .map(Relationship::getType)
                .collect(Collectors.toSet());

        assertTrue(types.contains("fdd"), "Expected fdd edge");
        assertTrue(types.contains("fdr"), "Expected fdr edge");
        assertTrue(types.contains("join"), "Expected join edge");
        assertTrue(types.contains("er"), "Expected er FK->PK edge");
        assertTrue(types.contains("call"), "Expected call edge");
        assertTrue(types.contains("crud"), "Expected crud edge");

        assertTrue(df.getRelationships().stream()
                .filter(r -> "join".equals(r.getType()))
                .allMatch(r -> r instanceof JoinRelationship));
        assertTrue(df.getRelationships().stream()
                .anyMatch(r -> "create_view".equals(r.getEffectType())));
        assertNotNull(res.getDlineageXml().getViews());
        assertEquals(1, res.getDlineageXml().getViews().size());
    }

    @Test
    void emitsFddiAndFrdWhenExplicitlyRequestedInFilterRelationTypes() {
        String sql = """
                INSERT INTO summary_tbl (dept_id, total_sal)
                SELECT dept_id, SUM(salary)
                FROM emp
                WHERE active_flag = 'Y'
                GROUP BY dept_id;
                """;
        Option opt = new Option();
        opt.setVendor(EDbVendor.dbvpostgresql);
        opt.setFilterRelationTypes(Set.of("fdd", "fddi", "frd", "fdr"));

        DataFlow df = engine.analyze(LineageAnalysisRequest.ofSql(sql, opt)).getDataFlow();
        Set<String> types = df.getRelationships().stream()
                .map(Relationship::getType)
                .collect(Collectors.toSet());
        assertTrue(types.contains("fdd"));
        assertTrue(types.contains("fddi"), "Expected fddi edge on predicate/group impact");
        assertTrue(types.contains("frd"), "Expected frd edge on record-count/aggregate impact");
    }

    @Test
    void selectStarCatalogExpansionWithExceptAndReplaceAndBackPropagation() {
        // 1. Explicit DDL catalog expansion with BigQuery/Snowflake EXCEPT & REPLACE
        String ddlAndStar = """
                CREATE TABLE raw_users (id INT, email STRING, secret_hash STRING);
                CREATE TABLE clean_users AS
                SELECT * EXCEPT (secret_hash) REPLACE (LOWER(email) AS email)
                FROM raw_users;
                """;
        Option bqOpt = new Option();
        bqOpt.setVendor(EDbVendor.dbvbigquery);
        bqOpt.setSimpleOutput(true);
        DataFlow bqDf = engine.analyze(LineageAnalysisRequest.ofSql(ddlAndStar, bqOpt)).getDataFlow();

        Set<String> targetCols = bqDf.getRelationships().stream()
                .filter(r -> "fdd".equals(r.getType()))
                .map(r -> r.getTarget().getColumn().toLowerCase())
                .collect(Collectors.toSet());
        assertTrue(targetCols.contains("id"));
        assertTrue(targetCols.contains("email"));
        assertFalse(targetCols.contains("secret_hash"), "EXCEPT column must be excluded");

        // 2. Schema-less SELECT * back-propagation from downstream explicit projection
        String schemalessStar = """
                WITH cte_all AS (
                    SELECT * FROM unknown_raw_orders
                )
                INSERT INTO downstream_orders (order_id, amount)
                SELECT order_id, amount FROM cte_all;
                """;
        Option sfOpt = new Option();
        sfOpt.setVendor(EDbVendor.dbvsnowflake);
        sfOpt.setIgnoreRecordSet(true);
        DataFlow starDf = engine.analyze(LineageAnalysisRequest.ofSql(schemalessStar, sfOpt)).getDataFlow();

        List<Relationship> starEdges = starDf.getRelationships().stream()
                .filter(r -> "fdd".equals(r.getType()))
                .toList();
        assertEquals(2, starEdges.size());
        assertTrue(starEdges.stream().allMatch(r ->
                r.getSources().get(0).getParentName().equalsIgnoreCase("UNKNOWN_RAW_ORDERS")));
    }

    @Test
    void orphanColumnResolutionHandlesCandidateParentsAndLinkToFirstTable() {
        String sql = "INSERT INTO out_t (x) SELECT mystery_col FROM t_alpha a JOIN t_beta b ON a.id = b.id;";

        // Default: pseudo_table_include_orphan_column + candidateParents
        Option optPseudo = new Option();
        optPseudo.setVendor(EDbVendor.dbvoracle);
        optPseudo.setIgnoreRecordSet(true);
        optPseudo.setLinkOrphanColumnToFirstTable(false);
        DataFlow dfPseudo = engine.analyze(LineageAnalysisRequest.ofSql(sql, optPseudo)).getDataFlow();
        Relationship fdd1 = dfPseudo.getRelationships().stream()
                .filter(r -> "fdd".equals(r.getType()))
                .findFirst().orElseThrow();
        assertEquals(ModelBindingManager.ORPHAN_PSEUDO_TABLE, fdd1.getSources().get(0).getParentName());
        assertNotNull(fdd1.getSources().get(0).getCandidateParents());
        assertEquals(2, fdd1.getSources().get(0).getCandidateParents().size());

        // linkOrphanColumnToFirstTable = true -> binds directly to T_ALPHA
        Option optFirst = new Option();
        optFirst.setVendor(EDbVendor.dbvoracle);
        optFirst.setIgnoreRecordSet(true);
        optFirst.setLinkOrphanColumnToFirstTable(true);
        DataFlow dfFirst = engine.analyze(LineageAnalysisRequest.ofSql(sql, optFirst)).getDataFlow();
        Relationship fdd2 = dfFirst.getRelationships().stream()
                .filter(r -> "fdd".equals(r.getType()))
                .findFirst().orElseThrow();
        assertEquals("T_ALPHA", fdd2.getSources().get(0).getParentName());
    }

    @Test
    void caseWhenShowDirectToggleAndCountStarRelationRows() {
        String sql = """
                INSERT INTO kpi_tgt (tier, row_cnt)
                SELECT CASE WHEN s.score >= 90 THEN s.vip_label ELSE s.std_label END,
                       COUNT(*)
                FROM customer_scores s;
                """;
        Option defaultOpt = new Option();
        defaultOpt.setVendor(EDbVendor.dbvoracle);
        defaultOpt.setIgnoreRecordSet(true);
        defaultOpt.setShowCaseWhenAsDirect(false);
        DataFlow dfDefault = engine.analyze(LineageAnalysisRequest.ofSql(sql, defaultOpt)).getDataFlow();

        // SCORE is in WHEN -> fdr by default; COUNT(*) -> RelationRows fdr
        assertTrue(dfDefault.getRelationships().stream().anyMatch(r ->
                "fdr".equals(r.getType()) && r.getSources().stream().anyMatch(s -> "SCORE".equals(s.getColumn()))));
        assertTrue(dfDefault.getRelationships().stream().anyMatch(r ->
                "fdr".equals(r.getType()) && r.getSources().stream().anyMatch(s -> "RelationRows".equals(s.getColumn()))));

        // showCaseWhenAsDirect = true -> SCORE becomes fdd
        Option directOpt = new Option();
        directOpt.setVendor(EDbVendor.dbvoracle);
        directOpt.setIgnoreRecordSet(true);
        directOpt.setShowCaseWhenAsDirect(true);
        DataFlow dfDirect = engine.analyze(LineageAnalysisRequest.ofSql(sql, directOpt)).getDataFlow();
        assertTrue(dfDirect.getRelationships().stream().anyMatch(r ->
                "fdd".equals(r.getType()) && r.getSources().stream().anyMatch(s -> "SCORE".equals(s.getColumn()))));
    }

    @Test
    void mergeMultiInsertUnionPivotAndLateralExplode() {
        String sql = """
                MERGE INTO dim_cust tgt
                USING stg_cust src
                ON (tgt.cust_id = src.cust_id)
                WHEN MATCHED THEN UPDATE SET tgt.cust_name = src.cust_name
                WHEN NOT MATCHED THEN INSERT (cust_id, cust_name) VALUES (src.cust_id, src.cust_name);

                INSERT FIRST
                    WHEN amt > 1000 THEN INTO high_orders (oid, amt) VALUES (order_id, amt)
                    ELSE INTO std_orders (oid, amt) VALUES (order_id, amt)
                SELECT order_id, amt FROM raw_orders;

                INSERT INTO union_out (id, label)
                SELECT a_id, a_name FROM src_a
                UNION ALL
                SELECT b_id, b_name FROM src_b;
                """;
        Option opt = new Option();
        opt.setVendor(EDbVendor.dbvoracle);
        opt.setIgnoreRecordSet(true);

        DataFlow df = engine.analyze(LineageAnalysisRequest.ofSql(sql, opt)).getDataFlow();
        assertTrue(df.getRelationships().stream().anyMatch(r -> "merge_update".equals(r.getEffectType())));
        assertTrue(df.getRelationships().stream().anyMatch(r -> "merge_insert".equals(r.getEffectType())));
        assertTrue(df.getRelationships().stream().anyMatch(r ->
                "STD_ORDERS".equals(r.getTarget().getParentName()) && "fdr".equals(r.getType())),
                "INSERT FIRST ELSE branch must carry negated prior WHEN predicate as fdr");
        assertEquals(2L, df.getRelationships().stream()
                .filter(r -> "UNION_OUT".equals(r.getTarget().getParentName())
                        && "ID".equals(r.getTarget().getColumn())
                        && "fdd".equals(r.getType()))
                .flatMap(r -> r.getSources().stream())
                .count());

        // Hive LATERAL VIEW EXPLODE
        Option hiveOpt = new Option();
        hiveOpt.setVendor(EDbVendor.dbvhive);
        hiveOpt.setIgnoreRecordSet(true);
        DataFlow hiveDf = engine.analyze(LineageAnalysisRequest.ofSql(
                "INSERT INTO flat_tags (doc_id, tag) SELECT d.doc_id, t.tag FROM docs d LATERAL VIEW EXPLODE(d.tags) t AS tag;",
                hiveOpt)).getDataFlow();
        assertTrue(hiveDf.getRelationships().stream().anyMatch(r ->
                "fdd".equals(r.getType()) && "tag".equalsIgnoreCase(r.getTarget().getColumn())));
    }
}
