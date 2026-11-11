package io.sqlflow.lineage.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.DynamicSqlTrustMode;
import io.sqlflow.contracts.lineage.Option;
import io.sqlflow.contracts.lineage.Relationship;
import io.sqlflow.contracts.spi.LineageEngineSpi.LineageAnalysisRequest;
import io.sqlflow.contracts.spi.LineageEngineSpi.LineageAnalysisResult;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.lineage.LineageEngineService;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies PL/SQL packages/procedures/cursors/variables, T-SQL {@code @vars} & {@code sp_executesql},
 * triggers ({@code :NEW}/{@code :OLD}), {@code EXECUTE IMMEDIATE} dynamic SQL, and {@code call} edges
 * ({@code OBL-048..OBL-049}).
 */
class ProceduralAndDynamicSqlAnalyzerTest {

    private final LineageEngineService engine = new LineageEngineService();

    @Test
    void oraclePackageProcedureCursorLoopAndCallGraph() {
        String sql = """
                CREATE OR REPLACE PACKAGE BODY pkg_payroll AS
                    PROCEDURE audit_log(p_emp_id NUMBER) IS
                    BEGIN
                        INSERT INTO payroll_audit (emp_id) VALUES (p_emp_id);
                    END audit_log;

                    PROCEDURE sync_salaries IS
                        CURSOR c_emp IS SELECT emp_id, base_sal FROM hr_employees WHERE active = 1;
                        v_net NUMBER;
                    BEGIN
                        FOR rec IN c_emp LOOP
                            v_net := rec.base_sal * 0.85;
                            INSERT INTO payroll_summary (emp_id, net_pay) VALUES (rec.emp_id, v_net);
                            audit_log(rec.emp_id);
                        END LOOP;
                    END sync_salaries;
                END pkg_payroll;
                """;
        Option opt = new Option();
        opt.setVendor(EDbVendor.dbvoracle);
        opt.setShowCallRelation(true);
        opt.setIgnoreRecordSet(true);

        LineageAnalysisResult res = engine.analyze(LineageAnalysisRequest.ofSql(sql, opt));
        DataFlow df = res.getDataFlow();

        assertNotNull(res.getDlineageXml().getPackages());
        assertEquals(1, res.getDlineageXml().getPackages().size());
        assertEquals("PKG_PAYROLL", res.getDlineageXml().getPackages().get(0).getName());

        // HR_EMPLOYEES.BASE_SAL -> v_net -> PAYROLL_SUMMARY.NET_PAY collapses in ignoreRecordSet mode!
        assertTrue(df.getRelationships().stream().anyMatch(r ->
                "fdd".equals(r.getType())
                        && "PAYROLL_SUMMARY".equals(r.getTarget().getParentName())
                        && "NET_PAY".equals(r.getTarget().getColumn())
                        && r.getSources().stream().anyMatch(s ->
                                "HR_EMPLOYEES".equals(s.getParentName()) && "BASE_SAL".equals(s.getColumn()))));

        // Call edge from sync_salaries -> audit_log
        assertTrue(df.getRelationships().stream().anyMatch(r ->
                "call".equals(r.getType())
                        && r.getCaller() != null
                        && r.getCallees() != null
                        && r.getCallees().stream().anyMatch(c -> c.getName().toUpperCase().contains("AUDIT_LOG"))));
    }

    @Test
    void tsqlVariablesOutputClauseAndSpExecutesqlDynamicSql() {
        String sql = """
                CREATE PROCEDURE dbo.usp_load_daily_sales
                AS
                BEGIN
                    DECLARE @src_tbl NVARCHAR(100) = 'dbo.raw_sales';
                    DECLARE @sql NVARCHAR(MAX);
                    SET @sql = 'INSERT INTO dbo.clean_sales (sale_id, amt) SELECT sale_id, amt FROM ' + @src_tbl;
                    EXEC sp_executesql @sql;
                END
                GO
                """;
        Option opt = new Option();
        opt.setVendor(EDbVendor.dbvmssql);
        opt.setIgnoreRecordSet(true);
        opt.setDynamicSqlTrustMode(DynamicSqlTrustMode.LEGACY);

        DataFlow df = engine.analyze(LineageAnalysisRequest.ofSql(sql, opt)).getDataFlow();
        List<Relationship> fdds = df.getRelationships().stream()
                .filter(r -> "fdd".equals(r.getType()))
                .toList();
        assertEquals(2, fdds.size(), "sp_executesql static concat must resolve inner INSERT..SELECT lineage");
        assertTrue(fdds.stream().anyMatch(r ->
                r.getTarget().getParentName().toLowerCase().contains("clean_sales")
                        && r.getSources().get(0).getParentName().toLowerCase().contains("raw_sales")));
    }

    @Test
    void oracleTriggerNewAndOldPseudoRecordsBindToTriggerTable() {
        String sql = """
                CREATE OR REPLACE TRIGGER trg_orders_hist
                AFTER UPDATE ON orders
                FOR EACH ROW
                BEGIN
                    INSERT INTO orders_audit (order_id, old_status, new_status)
                    VALUES (:OLD.order_id, :OLD.status, :NEW.status);
                END;
                """;
        Option opt = new Option();
        opt.setVendor(EDbVendor.dbvoracle);
        opt.setIgnoreRecordSet(true);

        DataFlow df = engine.analyze(LineageAnalysisRequest.ofSql(sql, opt)).getDataFlow();
        List<Relationship> auditEdges = df.getRelationships().stream()
                .filter(r -> "fdd".equals(r.getType()) && "ORDERS_AUDIT".equals(r.getTarget().getParentName()))
                .toList();
        assertEquals(3, auditEdges.size());
        assertTrue(auditEdges.stream().allMatch(r ->
                r.getSources().stream().anyMatch(s -> "ORDERS".equals(s.getParentName()))));
    }

    @Test
    void unresolvableDynamicSqlReportsDiagnosticWhenConfigured() {
        String sql = """
                CREATE PROCEDURE run_opaque(p_query VARCHAR2) AS
                BEGIN
                    EXECUTE IMMEDIATE p_query;
                END;
                """;
        Option opt = new Option();
        opt.setVendor(EDbVendor.dbvoracle);
        opt.setReportDynamicSqlSitesAsErrors(true);

        LineageAnalysisResult res = engine.analyze(LineageAnalysisRequest.ofSql(sql, opt));
        assertNotNull(res.getErrors());
        assertFalse(res.getErrors().isEmpty());
        assertTrue(res.getErrors().stream().anyMatch(e -> "DynamicSqlUnresolved".equals(e.getErrorType())));
    }
}
