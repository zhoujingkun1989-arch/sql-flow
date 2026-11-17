package io.sqlflow.lineage.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.Option;
import io.sqlflow.contracts.lineage.Relationship;
import io.sqlflow.contracts.lineage.RelationshipElement;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.AstSqlRequest;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.FormatRequest;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.FormatResult;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.GFmtOpt;
import io.sqlflow.contracts.spi.LineageEngineSpi.LineageAnalysisRequest;
import io.sqlflow.contracts.spi.LineageEngineSpi.LineageAnalysisResult;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.lineage.LineageEngineService;
import io.sqlflow.lineage.demo.DemoAstService;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Regression test suite for SQL lexing, splitting, parsing, formatting, and column-level lineage
 * accuracy fixes validated against real-world banking & regulatory SQL workloads.
 */
class RealWorldSqlAccuracyRegressionTest {

    private final LineageEngineService engine = new LineageEngineService();
    private final DemoAstService demoAstService = new DemoAstService();

    @Test
    void duplicateUnaliasedNvlSelfJoinAndStarSubqueryPreservePositionalAlignment() {
        String sql = """
                INSERT INTO cbrc_rpt (item_1, item_2, item_3, org_no)
                SELECT NVL(a.bal_1, 0), NVL(a.bal_2, 0), '0', a.org_id
                FROM acct a;

                INSERT INTO emp_mgr (emp_name, mgr_name)
                SELECT e.full_name, m.full_name
                FROM emp e
                LEFT JOIN emp m ON e.mgr_id = m.emp_id;

                INSERT INTO nested_rpt (c1, c2, c3)
                SELECT * FROM (
                    SELECT NVL(s.x1, 0), NVL(s.x2, 0), s.x3
                    FROM stg_bal s
                ) sub;
                """;
        LineageAnalysisResult res = analyze(sql, EDbVendor.dbvoracle);
        assertNoErrors(res);
        assertEquals(
                Set.of(
                        "ACCT.BAL_1 -> CBRC_RPT.ITEM_1",
                        "ACCT.BAL_2 -> CBRC_RPT.ITEM_2",
                        "ACCT.ORG_ID -> CBRC_RPT.ORG_NO",
                        "EMP.FULL_NAME -> EMP_MGR.EMP_NAME",
                        "EMP.FULL_NAME -> EMP_MGR.MGR_NAME",
                        "STG_BAL.X1 -> NESTED_RPT.C1",
                        "STG_BAL.X2 -> NESTED_RPT.C2",
                        "STG_BAL.X3 -> NESTED_RPT.C3"),
                edgeSet(res.getDataFlow(), "fdd"));
    }

    @Test
    void utf8BomManyProcedureVariablesAndAssociativeArrayAssignmentParseCleanly() {
        StringBuilder sb = new StringBuilder("\uFEFFCREATE OR REPLACE PROCEDURE proc_regulatory(p_dt IN VARCHAR2) IS\n");
        for (int i = 1; i <= 35; i++) {
            sb.append("  v_local_").append(i).append(" VARCHAR2(100) DEFAULT NULL;\n");
        }
        sb.append("""
                BEGIN
                  v_arr(1) := 'READY';
                  v_arr(2).status := p_dt;
                  INSERT INTO rpt_target (cust_id, amt)
                  SELECT c.cust_id, c.amt FROM raw_cust c;
                END;
                """);

        LineageAnalysisResult res = analyze(sb.toString(), EDbVendor.dbvoracle);
        assertNoErrors(res);
        assertEquals(
                Set.of(
                        "RAW_CUST.CUST_ID -> RPT_TARGET.CUST_ID",
                        "RAW_CUST.AMT -> RPT_TARGET.AMT"),
                edgeSet(res.getDataFlow(), "fdd"));
    }

    @Test
    void priorDeleteWhereDoesNotHijackColumnLessInsertPositionalMapping() {
        String sql = """
                DELETE FROM rpt_tbl WHERE data_dt = '20260101';
                INSERT INTO rpt_tbl SELECT cust_id, bal, data_dt FROM raw_acct;
                """;
        LineageAnalysisResult res = analyze(sql, EDbVendor.dbvhive);
        assertNoErrors(res);
        assertEquals(
                Set.of(
                        "RAW_ACCT.CUST_ID -> RPT_TBL.CUST_ID",
                        "RAW_ACCT.BAL -> RPT_TBL.BAL",
                        "RAW_ACCT.DATA_DT -> RPT_TBL.DATA_DT"),
                edgeSet(res.getDataFlow(), "fdd"));
    }

    @Test
    void columnLessInsertSanitizesUnaliasedCaseMathAndDuplicatePhysicalColumnNames() {
        String sql = """
                INSERT INTO rpt_unaliased
                SELECT CASE WHEN a > 0 THEN b ELSE c END,
                       d + 1,
                       NVL(e, 0),
                       NVL(f, 0)
                FROM src;
                """;
        LineageAnalysisResult res = analyze(sql, EDbVendor.dbvhive);
        assertNoErrors(res);
        Set<String> fdd = edgeSet(res.getDataFlow(), "fdd");
        assertFalse(fdd.stream().anyMatch(s -> s.contains("CASE") || s.contains("+")));
        assertEquals(
                Set.of(
                        "SRC.A -> RPT_UNALIASED.COL_1",
                        "SRC.B -> RPT_UNALIASED.COL_1",
                        "SRC.C -> RPT_UNALIASED.COL_1",
                        "SRC.D -> RPT_UNALIASED.COL_2",
                        "SRC.E -> RPT_UNALIASED.NVL",
                        "SRC.F -> RPT_UNALIASED.COL_4"),
                fdd);
    }

    @Test
    void sqlFormatLongLineWithTrailingCommentPreservesValidSyntaxAndLineage() {
        String sql = """
                INSERT INTO cbrc_loan_sum (org_id, overdue_amt, norm_amt)
                SELECT t.org_id,
                       SUM(CASE WHEN t.overdue_days > 90 AND t.loan_status = 'ACTIVE' THEN t.loan_bal ELSE 0 END), -- 逾期90天以上贷款余额汇总指标注释说明文字超长测试
                       SUM(t.norm_bal)
                FROM fact_loan_detail t
                GROUP BY t.org_id;
                """;
        FormatResult fmt = demoAstService.sqlformat(new FormatRequest(sql, EDbVendor.dbvhive, new GFmtOpt()));
        assertTrue(fmt.getErrors() == null || fmt.getErrors().isEmpty());
        assertTrue(demoAstService.syntaxCheck(new AstSqlRequest(fmt.getFormattedSql(), EDbVendor.dbvhive)).isValid());

        Set<String> origFdd = edgeSet(analyze(sql, EDbVendor.dbvhive).getDataFlow(), "fdd");
        Set<String> formattedFdd = edgeSet(analyze(fmt.getFormattedSql(), EDbVendor.dbvhive).getDataFlow(), "fdd");
        assertEquals(origFdd, formattedFdd);
        assertTrue(formattedFdd.contains("FACT_LOAN_DETAIL.NORM_BAL -> CBRC_LOAN_SUM.NORM_AMT"));
    }

    @Test
    void cteAndDerivedSubqueryColumnAliasesResolveLineageAccurately() {
        String sql = """
                WITH cte(x, y) AS (
                    SELECT raw_a, raw_b FROM src_t
                )
                INSERT INTO tgt_t (c1, c2, c3, c4)
                SELECT cte.x, cte.y, sub.p, sub.q
                FROM cte
                JOIN (SELECT NVL(m1, 0), NVL(m2, 0) FROM metric_t) sub(p, q) ON cte.x = sub.p;
                """;
        LineageAnalysisResult res = analyze(sql, EDbVendor.dbvpostgresql);
        assertNoErrors(res);
        assertEquals(
                Set.of(
                        "SRC_T.RAW_A -> TGT_T.C1",
                        "SRC_T.RAW_B -> TGT_T.C2",
                        "METRIC_T.M1 -> TGT_T.C3",
                        "METRIC_T.M2 -> TGT_T.C4"),
                edgeSet(res.getDataFlow(), "fdd"));
    }

    @Test
    void unionAllByNameAlignsReversedAndExtraColumnsByName() {
        String sql = """
                INSERT INTO tgt_union (a, b, c)
                SELECT a, b, c
                FROM (
                    SELECT x1 AS a, y1 AS b FROM t1
                    UNION ALL BY NAME
                    SELECT y2 AS b, x2 AS a, z2 AS c FROM t2
                ) u;
                """;
        LineageAnalysisResult res = analyze(sql, EDbVendor.dbvsparksql);
        assertNoErrors(res);
        assertEquals(
                Set.of(
                        "T1.X1 -> TGT_UNION.A",
                        "T2.X2 -> TGT_UNION.A",
                        "T1.Y1 -> TGT_UNION.B",
                        "T2.Y2 -> TGT_UNION.B",
                        "T2.Z2 -> TGT_UNION.C"),
                edgeSet(res.getDataFlow(), "fdd"));
    }

    @Test
    void hiveInceptorDeleteTablePartitionAndMergePartitionQualifiedInsertParseCleanly() {
        String sql = """
                DELETE TABLE pm_rsdata.aml_tmp_1 WHERE dt = '20260101';
                DELETE FROM TABLE pm_rsdata.aml_tmp_2;
                DELETE FROM pm_rsdata.aml_tmp_3 PARTITION (dt = '20260101') WHERE flag = 0;

                MERGE INTO pm_rsdata.diis_tgt PARTITION (rptdate = '20260101') A
                USING pm_rsdata.diis_src B
                ON (A.id = B.id)
                WHEN MATCHED THEN UPDATE SET A.amt = B.amt
                WHEN NOT MATCHED THEN INSERT (A.id, A.amt) VALUES (B.id, B.amt);
                """;
        LineageAnalysisResult res = analyze(sql, EDbVendor.dbvhive);
        assertNoErrors(res);
        assertEquals(
                Set.of(
                        "DIIS_SRC.ID -> DIIS_TGT.ID",
                        "DIIS_SRC.AMT -> DIIS_TGT.AMT"),
                edgeSet(res.getDataFlow(), "fdd"));
    }

    @Test
    void oracleHybridBackslashEscapedQuotesQuadQuotesAndNonNestedCommentsWorkTogether() {
        String sql = """
                /* Outer comment containing inner /* marker with single closing delimiter */
                CREATE OR REPLACE PROCEDURE p_hybrid(p_dt IN VARCHAR2) IS
                  v_sql VARCHAR2(2000);
                  v_quote VARCHAR2(10) := '''';
                BEGIN
                  v_sql := 'INSERT INTO rpt_dyn (org_id, bal) SELECT org_id, bal FROM raw_dyn WHERE org_type = \\'01\\' AND new_cond = '
                           || CHR(39) || p_dt || CHR(39);
                  EXECUTE IMMEDIATE v_sql;
                END;
                """;
        LineageAnalysisResult res = analyze(sql, EDbVendor.dbvoracle);
        assertNoErrors(res);
        assertEquals(
                Set.of(
                        "RAW_DYN.ORG_ID -> RPT_DYN.ORG_ID",
                        "RAW_DYN.BAL -> RPT_DYN.BAL"),
                edgeSet(res.getDataFlow(), "fdd"));
    }

    @Test
    void correlatedWhereExistsEmitsIndirectPredicateLineageWithoutLeakingStarProjection() {
        String sql = """
                INSERT INTO active_dept (dept_id)
                SELECT d.id
                FROM dept d
                WHERE EXISTS (
                    SELECT * FROM emp e WHERE e.dept_id = d.id AND e.active = 1
                );
                """;
        LineageAnalysisResult res = analyze(sql, EDbVendor.dbvoracle);
        assertNoErrors(res);
        assertEquals(Set.of("DEPT.ID -> ACTIVE_DEPT.DEPT_ID"), edgeSet(res.getDataFlow(), "fdd"));
        Set<String> fdr = edgeSet(res.getDataFlow(), "fdr");
        assertTrue(fdr.contains("EMP.DEPT_ID -> ACTIVE_DEPT.DEPT_ID"));
        assertTrue(fdr.contains("EMP.ACTIVE -> ACTIVE_DEPT.DEPT_ID"));
        assertFalse(fdr.contains("EMP.* -> ACTIVE_DEPT.DEPT_ID"));
    }

    private LineageAnalysisResult analyze(String sql, EDbVendor vendor) {
        Option opt = new Option();
        opt.setVendor(vendor);
        opt.setIgnoreRecordSet(true);
        opt.setAnalyzeDynamicSql(true);
        return engine.analyze(LineageAnalysisRequest.ofSql(sql, opt));
    }

    private static void assertNoErrors(LineageAnalysisResult res) {
        assertTrue(
                res.getErrors() == null || res.getErrors().isEmpty(),
                () -> "Unexpected errors: " + res.getErrors().stream().map(e -> e.getErrorMessage()).toList());
    }

    private static Set<String> edgeSet(DataFlow df, String type) {
        Set<String> out = new TreeSet<>();
        if (df == null || df.getRelationships() == null) {
            return out;
        }
        for (Relationship r : df.getRelationships()) {
            if (!type.equals(r.getType()) || r.getTarget() == null || r.getSources() == null) {
                continue;
            }
            String tgt = simple(r.getTarget().getParentName()) + "." + norm(r.getTarget().getColumn());
            for (RelationshipElement s : r.getSources()) {
                out.add(simple(s.getParentName()) + "." + norm(s.getColumn()) + " -> " + tgt);
            }
        }
        return out;
    }

    private static String simple(String name) {
        if (name == null) {
            return "";
        }
        int idx = name.lastIndexOf('.');
        return (idx >= 0 ? name.substring(idx + 1) : name).toUpperCase(Locale.ROOT);
    }

    private static String norm(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }
}
