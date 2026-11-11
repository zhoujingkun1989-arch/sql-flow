package io.sqlflow.lineage.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.sqlflow.contracts.spi.DemoAstServiceSpi;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.AstSqlRequest;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.CallRelationResult;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.ColumnImpactResult;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.FindConstantsResult;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.FormatConfiguration;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.FormatRequest;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.FormatResult;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.GFmtOpt;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.JoinConvertRequest;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.KeywordCheckResult;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.RemoveSchemaRequest;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.RenameColumnRequest;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.RenameTableRequest;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.RewriteSqlResult;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.SqlInjectionResult;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.SyntaxCheckResult;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.TableColumnResult;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.TokenizeResult;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.XmlAstResult;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DemoAstServiceSpiTest {

    private DemoAstService service;

    @BeforeEach
    void setUp() {
        service = new DemoAstService();
    }

    @Test
    void serviceLoaderDiscoversDemoAstService() {
        List<DemoAstServiceSpi> providers = ServiceLoader.load(DemoAstServiceSpi.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        assertThat(providers).isNotEmpty();
        assertThat(providers).anyMatch(p -> p instanceof DemoAstService);
    }

    @Test
    void sqlFormatHonorsGFmtOptCasingCommasAlignmentAndTokenPreservingFallback() {
        GFmtOpt opt = new GFmtOpt();
        opt.setCaseKeywords("Lower");
        opt.setCaseIdentifier("Upper");
        opt.setCaseFuncname("InitCap");
        opt.setIndentLen(4);
        opt.setSelectColumnlistStyle("Stacked");
        opt.setSelectColumnlistComma("Before");
        opt.setAndOrUnderWhere(true);

        String sql = "SELECT u.id AS user_id, COUNT(u.name) AS total_cnt FROM users u "
                + "LEFT JOIN orders o ON u.id = o.user_id "
                + "WHERE u.age BETWEEN 18 AND 65 AND u.active = 1";
        FormatResult res = service.sqlformat(new FormatRequest(sql, EDbVendor.dbvpostgresql, opt));

        assertThat(res.getErrors()).isNull();
        assertThat(res.getFormattedSql())
                .contains("select")
                .contains(", Count(U.NAME)")
                .contains("from USERS U")
                .contains("left join ORDERS O")
                .contains("between 18 and 65\n    and U.ACTIVE = 1");

        // Nested 19-group format_options map + line numbers
        FormatConfiguration cfg = new FormatConfiguration("dbvoracle");
        cfg.setFormatOptions(Map.of(
                "capitalization", Map.of("case_keywords", "upper", "case_datatype", "lower"),
                "line_number", Map.of("enabled", true)));
        FormatResult ddlFmt = service.sqlformat(new FormatRequest(
                "create table emp (emp_id int, full_name varchar(100))", cfg));
        assertThat(ddlFmt.getFormattedSql()).contains("1: CREATE TABLE emp").contains("varchar(100)");

        // Malformed SQL triggers token-preserving SQLion fallback without corrupting comments
        FormatResult fallback = service.sqlformat(new FormatRequest(
                "/* keep header */\nselect a, from broken_tbl -- trailing note",
                EDbVendor.dbvoracle,
                new GFmtOpt()));
        assertThat(fallback.getFormattedSql())
                .contains("/* keep header */")
                .contains("-- trailing note")
                .contains("SELECT");
        assertThat(fallback.getMessage()).contains("SQLion");
    }

    @Test
    void getTableColumnsResolvesAliasesCrudOrphansAndFormatsCsv() {
        String sql = """
                CREATE TABLE hr.departments (id INT, dept_name VARCHAR(64));
                INSERT INTO audit_log (emp_id, dept_name)
                SELECT e.id, dept_name
                FROM hr.employees e
                JOIN hr.departments d ON e.dept_id = d.id
                WHERE ghost.missing_col = 1;
                """;
        TableColumnResult tc = service.getTableColumns(new AstSqlRequest(sql, EDbVendor.dbvpostgresql));

        assertThat(tc.getTables()).containsKeys("hr.departments", "audit_log", "hr.employees");
        assertThat(tc.getTables()).doesNotContainKeys("e", "d");
        assertThat(tc.getTables().get("hr.employees")).contains("id", "dept_id");
        assertThat(tc.getTables().get("hr.departments")).contains("id", "dept_name");
        assertThat(tc.getTables().get("audit_log")).contains("emp_id", "dept_name");
        assertThat(tc.getOrphanColumns()).contains("ghost.missing_col");

        var detailed = service.inspectTableColumnsDetailed(sql, EDbVendor.dbvpostgresql, null);
        assertThat(detailed.statementSummaries())
                .extracting(TableColumnInspector.StatementCrudSummary::crudOperation)
                .containsExactly("CREATE_TABLE", "INSERT");

        String summaryCsv = service.formatTableColumnsCsv(
                "SELECT u.id, u.email FROM users u", EDbVendor.dbvpostgresql, "summary", ",");
        assertThat(summaryCsv).contains("\"table\",\"column\",\"dataType\"").contains("\"users\",\"id\"");

        String detailCsv = service.formatTableColumnsCsv(
                "SELECT u.id FROM users u JOIN orders o ON u.id = o.user_id",
                EDbVendor.dbvpostgresql, "detail", ",");
        assertThat(detailCsv).contains("\"effectType\",\"joinColumn\",\"coordinates\"").contains("\"true\"");
    }

    @Test
    void syntaxCheckTokenizeAndKeywordCheckWorkAcrossDialects() {
        SyntaxCheckResult ok = service.syntaxCheck(
                new AstSqlRequest("SELECT id, name FROM employees WHERE dept_id = 10", EDbVendor.dbvoracle));
        assertThat(ok.isValid()).isTrue();
        assertThat(ok.getErrors()).isNull();
        assertThat(ok.getErrorInfos()).isEmpty();

        SyntaxCheckResult bad = service.syntaxCheck(
                new AstSqlRequest("SELECT id,\nFROM employees WHERE (((;", EDbVendor.dbvoracle));
        assertThat(bad.isValid()).isFalse();
        assertThat(bad.getErrors()).isNotEmpty();
        assertThat(bad.getErrors().get(0).getCoordinates().get(0).getLine()).isGreaterThanOrEqualTo(1L);

        SyntaxCheckResult unclosedStr = service.syntaxCheck(
                new AstSqlRequest("SELECT 'unterminated FROM dual", EDbVendor.dbvoracle));
        assertThat(unclosedStr.isValid()).isFalse();

        TokenizeResult tokens = service.tokenize(
                new AstSqlRequest("-- hdr\nSELECT u.id FROM users u", EDbVendor.dbvpostgresql));
        assertThat(tokens.getTokens()).isNotEmpty();
        assertThat(tokens.getTokens().get(0).getTokenType()).isEqualTo("LINE_COMMENT");
        assertThat(tokens.getTokens().get(1).getLine()).isEqualTo(2L);

        KeywordCheckResult kwInvalid = service.keywordCheck(
                new AstSqlRequest("SELECT,emp_custom_col,ROWNUM", EDbVendor.dbvoracle));
        assertThat(kwInvalid.isValid()).isFalse();
        assertThat(kwInvalid.getReservedKeywordsUsedAsIdentifiers())
                .extracting(DemoAstServiceSpi.TokenItem::getText)
                .containsExactly("SELECT", "ROWNUM");

        KeywordCheckResult kwValid = service.keywordCheck(
                new AstSqlRequest("emp_id,emp_custom_col", EDbVendor.dbvoracle));
        assertThat(kwValid.isValid()).isTrue();
    }

    @Test
    void antiSqlInjectionDetectsAllMajorAttackPatternsAndPassesCleanSql() {
        SqlInjectionResult clean = service.antiSqlInjection(
                new AstSqlRequest("SELECT id, email FROM users WHERE id = :userId AND active = 1", EDbVendor.dbvpostgresql));
        assertThat(clean.isFound()).isFalse();

        SqlInjectionResult tautology = service.antiSqlInjection(
                new AstSqlRequest("SELECT * FROM users WHERE username = '' OR 'a'='a'", EDbVendor.dbvmysql));
        assertThat(tautology.isFound()).isTrue();
        assertThat(tautology.getErrors()).anyMatch(f -> "always_true_condition".equals(f.getType()));

        SqlInjectionResult unionInj = service.antiSqlInjection(
                new AstSqlRequest("SELECT id, name FROM products WHERE id = 1 UNION SELECT table_name, table_schema FROM information_schema.tables", EDbVendor.dbvmysql));
        assertThat(unionInj.isFound()).isTrue();
        assertThat(unionInj.getErrors()).anyMatch(f -> "union_piggybacking".equals(f.getType()));

        SqlInjectionResult stacked = service.antiSqlInjection(
                new AstSqlRequest("SELECT * FROM accounts WHERE id = 5; DROP TABLE accounts;", EDbVendor.dbvpostgresql));
        assertThat(stacked.isFound()).isTrue();
        assertThat(stacked.getErrors()).anyMatch(f -> "stacked_query".equals(f.getType()));

        SqlInjectionResult commentTrunc = service.antiSqlInjection(
                new AstSqlRequest("SELECT * FROM users WHERE username = 'admin' -- AND password = 'secret'", EDbVendor.dbvoracle));
        assertThat(commentTrunc.isFound()).isTrue();
        assertThat(commentTrunc.getErrors()).anyMatch(f -> "comment_truncation".equals(f.getType()));

        SqlInjectionResult timeDelay = service.antiSqlInjection(
                new AstSqlRequest("SELECT * FROM users WHERE id = 1; WAITFOR DELAY '0:0:5'", EDbVendor.dbvmssql));
        assertThat(timeDelay.isFound()).isTrue();
        assertThat(timeDelay.getErrors()).anyMatch(f -> "blind_time_delay".equals(f.getType()));

        SqlInjectionResult pgSleep = service.antiSqlInjection(
                new AstSqlRequest("SELECT pg_sleep(5) FROM users", EDbVendor.dbvpostgresql));
        assertThat(pgSleep.isFound()).isTrue();
    }

    @Test
    void callRelationExtractsPackageProcedureAndFunctionHierarchy() {
        String plsql = """
                CREATE OR REPLACE FUNCTION calc_bonus(p_sal NUMBER) RETURN NUMBER IS
                BEGIN
                    RETURN p_sal * 0.1;
                END;
                /
                CREATE OR REPLACE PROCEDURE run_payroll(p_dept NUMBER) IS
                    v_bonus NUMBER;
                BEGIN
                    CALL audit_pkg.log_start(p_dept);
                    SELECT calc_bonus(salary) INTO v_bonus FROM employees WHERE dept_id = p_dept;
                END;
                /
                """;
        CallRelationResult cr = service.callRelation(new AstSqlRequest(plsql, EDbVendor.dbvoracle));
        assertThat(cr.getCalls()).hasSize(2);
        assertThat(cr.getCalls())
                .anyMatch(e -> "run_payroll".equals(e.getCaller()) && "audit_pkg.log_start".equals(e.getCallee()))
                .anyMatch(e -> "run_payroll".equals(e.getCaller()) && "calc_bonus".equals(e.getCallee()));
        assertThat(cr.getXml()).contains("<callRelations").contains("audit_pkg.log_start");
    }

    @Test
    void astRefactoringXmlTranslationAndInspectionOpsWorkEndToEnd() {
        // 1. joinConvert (Oracle (+) -> ANSI LEFT JOIN preserving non-join WHERE)
        JoinConvertRequest jReq = new JoinConvertRequest();
        jReq.setDbvendor(EDbVendor.dbvoracle);
        jReq.setSql("SELECT e.name, d.dept_name FROM emp e, dept d WHERE e.dept_id = d.id(+) AND e.active = 1");
        RewriteSqlResult joined = service.joinConvert(jReq);
        assertThat(joined.getReplacements()).isEqualTo(1);
        assertThat(joined.getSql()).contains("LEFT JOIN dept d ON e.dept_id = d.id").contains("WHERE e.active = 1");

        // 2. renameTable, renameColumn (preserving string literals), removeSchema
        RenameTableRequest rt = new RenameTableRequest();
        rt.setDbvendor(EDbVendor.dbvpostgresql);
        rt.setSql("SELECT emp.id, 'emp' AS tag FROM emp WHERE emp.id = 1");
        rt.setSourceTable("emp");
        rt.setTargetTable("employees");
        RewriteSqlResult rtRes = service.renameTable(rt);
        assertThat(rtRes.getSql()).contains("SELECT employees.id, 'emp' AS tag FROM employees WHERE employees.id = 1");

        RenameColumnRequest rc = new RenameColumnRequest();
        rc.setDbvendor(EDbVendor.dbvpostgresql);
        rc.setSql("SELECT u.email, o.email FROM users u JOIN orders o ON u.id = o.user_id");
        rc.setSourceColumn("users.email");
        rc.setTargetColumn("contact_email");
        RewriteSqlResult rcRes = service.renameColumn(rc);
        assertThat(rcRes.getSql()).contains("u.contact_email").contains("o.email");

        RemoveSchemaRequest rs = new RemoveSchemaRequest();
        rs.setDbvendor(EDbVendor.dbvoracle);
        rs.setSql("SELECT hr.emp.id FROM hr.emp JOIN sales.orders ON hr.emp.id = sales.orders.emp_id");
        rs.setSchemaName("hr");
        RewriteSqlResult rsRes = service.removeSchema(rs);
        assertThat(rsRes.getReplacements()).isEqualTo(3);
        assertThat(rsRes.getSql()).doesNotContain("hr.").contains("sales.orders");

        // 3. columnImpact, findConstants, toXml
        ColumnImpactResult impact = service.columnImpact(
                new AstSqlRequest("CREATE VIEW v_emp AS SELECT e.salary * 12 AS annual_pay FROM emp e", EDbVendor.dbvoracle));
        assertThat(impact.getImpacts().get("emp.salary")).contains("v_emp.annual_pay");
        assertThat(impact.getXml()).contains("<columnImpact>");

        FindConstantsResult consts = service.findConstants(
                new AstSqlRequest("SELECT 'USD', 42, TRUE FROM dual", EDbVendor.dbvoracle));
        assertThat(consts.getConstants()).hasSize(3);

        XmlAstResult xml = service.toXml(
                new AstSqlRequest("SELECT id, name FROM users WHERE active = 1", EDbVendor.dbvpostgresql));
        assertThat(xml.getXml()).contains("<sqlscript").contains("<select_list>").contains("<where_clause>");

        // 4. joins, whereCondition, removeCondition, alterScript, sqlTranslate, analyzeExpressions
        assertThat(service.joins(new AstSqlRequest(joined.getSql(), EDbVendor.dbvpostgresql))).hasSize(1);
        assertThat(service.whereCondition(new AstSqlRequest(joined.getSql(), EDbVendor.dbvpostgresql))).hasSize(1);

        RewriteSqlResult removed = service.removeCondition(
                new AstSqlRequest("SELECT * FROM orders WHERE tenant_id = 7 AND status = 'PAID'", EDbVendor.dbvpostgresql),
                "tenant_id");
        assertThat(removed.getSql()).isEqualTo("SELECT * FROM orders WHERE status = 'PAID'");

        RewriteSqlResult alter = service.alterScript(
                new AstSqlRequest("CREATE TABLE users (id INT, legacy VARCHAR(10));", EDbVendor.dbvpostgresql),
                new AstSqlRequest("CREATE TABLE users (id BIGINT, email VARCHAR(120));", EDbVendor.dbvpostgresql));
        assertThat(alter.getSql())
                .contains("ADD COLUMN email VARCHAR(120)")
                .contains("ALTER COLUMN id TYPE BIGINT")
                .contains("DROP COLUMN legacy");

        RewriteSqlResult translated = service.sqlTranslate(
                new AstSqlRequest("SELECT NVL(name, 'N/A'), SYSDATE FROM \"emp\" WHERE ROWNUM <= 10", EDbVendor.dbvoracle),
                EDbVendor.dbvmysql);
        assertThat(translated.getSql())
                .contains("IFNULL(name, 'N/A')")
                .contains("CURRENT_TIMESTAMP")
                .contains("`emp`")
                .contains("LIMIT 10");

        assertThat(service.analyzeExpressions(
                new AstSqlRequest("SELECT UPPER(a) + b FROM t WHERE c > 10", EDbVendor.dbvpostgresql))).hasSize(2);
    }

    @Test
    void multiDialectWireContractsPackageCallsAndEdgeCases() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        // 1. Verify valid SyntaxCheckResult omits `errors` field on JSON wire (`@JsonInclude(NON_NULL)`)
        for (EDbVendor dialect : List.of(
                EDbVendor.dbvoracle, EDbVendor.dbvpostgresql, EDbVendor.dbvmysql,
                EDbVendor.dbvmssql, EDbVendor.dbvsnowflake, EDbVendor.dbvbigquery,
                EDbVendor.dbvsparksql, EDbVendor.dbvduckdb, EDbVendor.dbvclickhouse)) {
            SyntaxCheckResult syn = service.syntaxCheck(
                    new AstSqlRequest("SELECT a, b FROM sales_fact WHERE region_id = 1", dialect));
            assertThat(syn.isValid()).as("dialect=%s", dialect).isTrue();
            var jsonNode = mapper.readTree(mapper.writeValueAsString(syn));
            assertThat(jsonNode.has("errors")).isFalse();
            assertThat(jsonNode.path("errorInfos")).isEmpty();
        }

        // 2. Formatter compact mode, quoted identifier casing, & whitespace padding options
        GFmtOpt compactOpt = new GFmtOpt();
        compactOpt.setCompactMode("Compact");
        compactOpt.setCaseQuotedIdentifier("Upper");
        compactOpt.setWsPaddingOperatorArithmetic(false);
        compactOpt.setWsPaddingParenthesesInFunctionCall(true);
        FormatResult compactRes = service.sqlformat(new FormatRequest(
                "SELECT COALESCE(a, 0) + 1 FROM `my_table`\nWHERE x = 2",
                EDbVendor.dbvmysql,
                compactOpt));
        assertThat(compactRes.getFormattedSql())
                .doesNotContain("\n")
                .contains("`MY_TABLE`")
                .contains("Coalesce( a, 0 )+1");

        // 3. Legitimate domain UNION ALL is NOT flagged as SQLi, while DBMS_PIPE / inline block comment IS flagged
        SqlInjectionResult safeUnion = service.antiSqlInjection(new AstSqlRequest(
                "SELECT order_id, total FROM orders_2025 UNION ALL SELECT order_id, total FROM orders_2026",
                EDbVendor.dbvpostgresql));
        assertThat(safeUnion.isFound()).isFalse();

        SqlInjectionResult dbmsPipe = service.antiSqlInjection(new AstSqlRequest(
                "SELECT DBMS_PIPE.RECEIVE_MESSAGE('chr', 5) FROM dual",
                EDbVendor.dbvoracle));
        assertThat(dbmsPipe.isFound()).isTrue();
        assertThat(dbmsPipe.getErrors()).anyMatch(f -> "blind_time_delay".equals(f.getType()));

        SqlInjectionResult obfComment = service.antiSqlInjection(new AstSqlRequest(
                "SELECT * FROM users WHERE id = 1 UN/**/ION SELECT 1, 2 FROM dual",
                EDbVendor.dbvmysql));
        assertThat(obfComment.isFound()).isTrue();
        assertThat(obfComment.getErrors()).anyMatch(f -> "comment_truncation".equals(f.getType()));

        // 4. Package body intra-package call + dynamic EXECUTE IMMEDIATE call hierarchy
        String pkgSql = """
                CREATE OR REPLACE PACKAGE BODY fin_pkg IS
                    PROCEDURE helper_calc(p_id NUMBER) IS
                    BEGIN
                        NULL;
                    END;
                    PROCEDURE run_close(p_id NUMBER) IS
                    BEGIN
                        CALL helper_calc(p_id);
                        EXECUTE IMMEDIATE 'CALL ext_audit.record_close(1)';
                    END;
                END fin_pkg;
                """;
        CallRelationResult pkgCalls = service.callRelation(new AstSqlRequest(pkgSql, EDbVendor.dbvoracle));
        assertThat(pkgCalls.getCalls())
                .anyMatch(e -> "fin_pkg.run_close".equals(e.getCaller()) && "fin_pkg.helper_calc".equals(e.getCallee()))
                .anyMatch(e -> "fin_pkg.run_close".equals(e.getCaller()) && "ext_audit.record_close".equals(e.getCallee()));

        // 5. removeCondition drops entire WHERE clause cleanly when single predicate is removed
        RewriteSqlResult noWhere = service.removeCondition(
                new AstSqlRequest("SELECT id FROM orders WHERE tenant_id = :tid", EDbVendor.dbvpostgresql),
                "tenant_id");
        assertThat(noWhere.getSql()).isEqualTo("SELECT id FROM orders");
    }
}
