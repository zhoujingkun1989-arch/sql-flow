package io.sqlflow.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.contracts.graph.DiagnosticCode;
import io.sqlflow.contracts.graph.JoinAnalysisGraph;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.BadgeKind;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.CardinalityHint;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.JoinAnalysisNode;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.JoinClause;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.JoinDiagnostic;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.JoinEdgeKind;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.JoinNodeType;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.JoinPattern;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.JoinType;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.LaneKind;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.LayoutDirection;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.Severity;
import io.sqlflow.contracts.lineage.Column;
import io.sqlflow.contracts.lineage.DatabaseObject;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.SchemaObject;
import io.sqlflow.contracts.lineage.SqlInfo;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.contracts.spi.JoinAnalysisSpi.JoinAnalysisRequest;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JoinAnalysisServiceTest {

    private final JoinAnalysisService service = new JoinAnalysisService();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rule01_missingJoinCondition_isDetectedWithBlockSeverity() {
        String sql = "SELECT o.id, c.name FROM orders o INNER JOIN customers c WHERE o.amount > 100";
        JoinAnalysisGraph g = service.analyzeJoins(JoinAnalysisRequest.of(sql, EDbVendor.dbvpostgresql));

        assertThat(g.getDiagnostics())
                .anySatisfy(d -> {
                    assertThat(d.getCode()).isEqualTo(DiagnosticCode.MISSING_JOIN_CONDITION);
                    assertThat(d.getSeverity()).isEqualTo(Severity.BLOCK);
                    assertThat(d.getEvidenceNodeIds()).isNotEmpty();
                });
        assertThat(g.getSummary().getBlockCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void rule02_cartesianJoin_isDetectedOnCrossJoinCommaJoinAndConstantOnClause() {
        // Case A: Explicit CROSS JOIN
        JoinAnalysisGraph gCross = service.analyzeJoins(JoinAnalysisRequest.of(
                "SELECT a.id, b.id FROM dim_date a CROSS JOIN dim_store b", EDbVendor.dbvsnowflake));
        assertHasCode(gCross, DiagnosticCode.CARTESIAN_JOIN, Severity.BLOCK);

        // Case B: Implicit comma join with no linking WHERE predicate
        JoinAnalysisGraph gComma = service.analyzeJoins(JoinAnalysisRequest.of(
                "SELECT a.id, b.id FROM accounts a, regions b WHERE a.active = 1", EDbVendor.dbvoracle));
        assertHasCode(gComma, DiagnosticCode.CARTESIAN_JOIN, Severity.BLOCK);

        // Case C: Explicit JOIN ON constant tautology 1 = 1
        JoinAnalysisGraph gConst = service.analyzeJoins(JoinAnalysisRequest.of(
                "SELECT a.id, b.id FROM accounts a JOIN regions b ON 1 = 1", EDbVendor.dbvmysql));
        assertHasCode(gConst, DiagnosticCode.CARTESIAN_JOIN, Severity.BLOCK);

        // Verify implicit comma join WITH linking WHERE predicate is NOT flagged as cartesian
        JoinAnalysisGraph gLinkedComma = service.analyzeJoins(JoinAnalysisRequest.of(
                "SELECT o.id, c.name FROM orders o, customers c WHERE o.customer_id = c.id",
                EDbVendor.dbvoracle));
        assertThat(codesOf(gLinkedComma)).doesNotContain(DiagnosticCode.CARTESIAN_JOIN);
    }

    @Test
    void rule03_manyToManyRisk_isDetectedWhenNeitherEquiJoinKeyIsUnique() {
        // Heuristic non-unique join keys (order_id is neither order_items's PK nor shipments's PK)
        JoinAnalysisGraph gHeur = service.analyzeJoins(JoinAnalysisRequest.of(
                "SELECT oi.sku, s.carrier FROM order_items oi JOIN shipments s ON oi.order_id = s.order_id",
                EDbVendor.dbvpostgresql));
        assertHasCode(gHeur, DiagnosticCode.MANY_TO_MANY_RISK, Severity.WARN);
        assertThat(gHeur.getJoins().get(0).getCardinalityHint()).isEqualTo(CardinalityHint.MANY_TO_MANY);

        // Clean PK-FK join must be MANY_TO_ONE and must NOT emit MANY_TO_MANY_RISK
        JoinAnalysisGraph gPkFk = service.analyzeJoins(JoinAnalysisRequest.of(
                "SELECT o.id, c.name FROM orders o JOIN customers c ON o.customer_id = c.id",
                EDbVendor.dbvpostgresql));
        assertThat(codesOf(gPkFk)).doesNotContain(DiagnosticCode.MANY_TO_MANY_RISK);
        assertThat(gPkFk.getJoins().get(0).getCardinalityHint()).isEqualTo(CardinalityHint.MANY_TO_ONE);
    }

    @Test
    void rule04_outerJoinFilterRisk_isDetectedWhenWhereRejectsNullsOnOuterSide() {
        String riskySql = """
                SELECT o.id, c.country
                FROM orders o
                LEFT JOIN customers c ON o.customer_id = c.id
                WHERE c.country = 'US'
                """;
        JoinAnalysisGraph gRisky = service.analyzeJoins(
                JoinAnalysisRequest.of(riskySql, EDbVendor.dbvpostgresql));
        assertHasCode(gRisky, DiagnosticCode.OUTER_JOIN_FILTER_RISK, Severity.WARN);

        // Null-preserving COALESCE filter in WHERE must NOT false-positive
        String safeSql = """
                SELECT o.id, c.country
                FROM orders o
                LEFT JOIN customers c ON o.customer_id = c.id
                WHERE COALESCE(c.country, 'US') = 'US'
                """;
        JoinAnalysisGraph gSafe = service.analyzeJoins(
                JoinAnalysisRequest.of(safeSql, EDbVendor.dbvpostgresql));
        assertThat(codesOf(gSafe)).doesNotContain(DiagnosticCode.OUTER_JOIN_FILTER_RISK);
    }

    @Test
    void rule05_antiJoinDetected_supportsLeftJoinIsNullAndNotExistsAndNotInAndLeftAntiJoin() {
        // Pattern A: LEFT JOIN ... WHERE r.id IS NULL (must emit ANTI_JOIN_DETECTED and ANTI_JOIN_EVIDENCE edge, NOT OUTER_JOIN_FILTER_RISK)
        String leftNullSql = """
                SELECT o.id
                FROM orders o
                LEFT JOIN payments p ON o.id = p.order_id
                WHERE p.id IS NULL
                """;
        JoinAnalysisGraph gLeftNull = service.analyzeJoins(
                JoinAnalysisRequest.of(leftNullSql, EDbVendor.dbvpostgresql));
        assertHasCode(gLeftNull, DiagnosticCode.ANTI_JOIN_DETECTED, Severity.INFO);
        assertThat(codesOf(gLeftNull)).doesNotContain(DiagnosticCode.OUTER_JOIN_FILTER_RISK);
        assertThat(gLeftNull.getEdges())
                .anySatisfy(e -> assertThat(e.getData().getKind()).isEqualTo(JoinEdgeKind.ANTI_JOIN_EVIDENCE));

        // Pattern B: NOT EXISTS correlated subquery
        String notExistsSql = """
                SELECT c.id FROM customers c
                WHERE NOT EXISTS (SELECT 1 FROM refunds r WHERE r.customer_id = c.id)
                """;
        JoinAnalysisGraph gNotExists = service.analyzeJoins(
                JoinAnalysisRequest.of(notExistsSql, EDbVendor.dbvoracle));
        assertHasCode(gNotExists, DiagnosticCode.ANTI_JOIN_DETECTED, Severity.INFO);

        // Pattern C: NOT IN subquery & Spark LEFT ANTI JOIN
        JoinAnalysisGraph gNotIn = service.analyzeJoins(JoinAnalysisRequest.of(
                "SELECT c.id FROM customers c WHERE c.id NOT IN (SELECT customer_id FROM blacklist)",
                EDbVendor.dbvsparksql));
        assertHasCode(gNotIn, DiagnosticCode.ANTI_JOIN_DETECTED, Severity.INFO);

        JoinAnalysisGraph gLeftAnti = service.analyzeJoins(JoinAnalysisRequest.of(
                "SELECT c.id FROM customers c LEFT ANTI JOIN blacklist b ON c.id = b.customer_id",
                EDbVendor.dbvsparksql));
        assertHasCode(gLeftAnti, DiagnosticCode.ANTI_JOIN_DETECTED, Severity.INFO);
    }

    @Test
    void rule06_selfJoinDetected_marksBothTableInstancesAndEmitsInfoDiagnostic() {
        String sql = """
                SELECT e.id, e.name, m.name AS manager_name
                FROM hr.employees e
                LEFT JOIN hr.employees m ON e.manager_id = m.id
                """;
        JoinAnalysisGraph g = service.analyzeJoins(JoinAnalysisRequest.of(sql, EDbVendor.dbvpostgresql));
        assertHasCode(g, DiagnosticCode.SELF_JOIN_DETECTED, Severity.INFO);

        List<JoinAnalysisNode> tableNodes = g.getNodes().stream()
                .filter(n -> n.getType() == JoinNodeType.TABLE)
                .toList();
        assertThat(tableNodes).hasSize(2);
        assertThat(tableNodes)
                .allSatisfy(n -> assertThat(n.getData().getTable().getIsSelfJoinInstance()).isTrue());
        assertThat(g.getJoins().get(0).getPatterns()).contains(JoinPattern.SELF);
    }

    @Test
    void rule07_semiJoinDetected_supportsExistsAndInSubqueriesAndLeftSemiJoin() {
        String existsSql = """
                SELECT c.id, c.name
                FROM customers c
                WHERE EXISTS (SELECT 1 FROM orders o WHERE o.customer_id = c.id)
                """;
        JoinAnalysisGraph gExists = service.analyzeJoins(
                JoinAnalysisRequest.of(existsSql, EDbVendor.dbvpostgresql));
        assertHasCode(gExists, DiagnosticCode.SEMI_JOIN_DETECTED, Severity.INFO);

        JoinAnalysisGraph gIn = service.analyzeJoins(JoinAnalysisRequest.of(
                "SELECT c.id FROM customers c WHERE c.id IN (SELECT o.customer_id FROM orders o)",
                EDbVendor.dbvmysql));
        assertHasCode(gIn, DiagnosticCode.SEMI_JOIN_DETECTED, Severity.INFO);

        JoinAnalysisGraph gSemi = service.analyzeJoins(JoinAnalysisRequest.of(
                "SELECT c.id FROM customers c LEFT SEMI JOIN orders o ON c.id = o.customer_id",
                EDbVendor.dbvsparksql));
        assertHasCode(gSemi, DiagnosticCode.SEMI_JOIN_DETECTED, Severity.INFO);
        assertThat(gSemi.getNodes().stream()
                        .filter(n -> n.getType() == JoinNodeType.JOIN)
                        .findFirst()
                        .orElseThrow()
                        .getData()
                        .getBadges())
                .anySatisfy(b -> assertThat(b.getKind()).isEqualTo(BadgeKind.SEMI_JOIN));
    }

    @Test
    void rule08_nonIndexedJoinKey_isDetectedFromCatalogAndFromInlineDdl() {
        // Case A: Catalog metadata where orders.customer_id has no index while customers.id is PK
        Column cId = new Column("1", "id");
        cId.setPrimaryKey(true);
        DbObject tblCustomers = new DbObject();
        tblCustomers.setName("customers");
        tblCustomers.setColumns(List.of(cId));

        Column oId = new Column("2", "id");
        oId.setPrimaryKey(true);
        Column oCustId = new Column("3", "customer_id");
        oCustId.setPrimaryKey(false);
        oCustId.setUnqiueKey(false);
        oCustId.setIndexKey(false);
        DbObject tblOrders = new DbObject();
        tblOrders.setName("orders");
        tblOrders.setColumns(List.of(oId, oCustId));

        SchemaObject publicSchema = new SchemaObject();
        publicSchema.setName("public");
        publicSchema.setTables(List.of(tblCustomers, tblOrders));
        DatabaseObject db = new DatabaseObject();
        db.setName("shop");
        db.setSchemas(List.of(publicSchema));
        SqlflowCatalog catalog = new SqlflowCatalog();
        catalog.setDatabases(List.of(db));

        JoinAnalysisRequest req = JoinAnalysisRequest.of(
                "SELECT o.id FROM orders o INNER JOIN customers c ON o.customer_id = c.id",
                EDbVendor.dbvpostgresql);
        req.setCatalog(catalog);

        JoinAnalysisGraph gCat = service.analyzeJoins(req);
        assertHasCode(gCat, DiagnosticCode.NON_INDEXED_JOIN_KEY, Severity.WARN);

        // Case B: Inline DDL script with CREATE TABLE + CREATE INDEX covering one table but leaving the FK unindexed
        String ddlAndQuery = """
                CREATE TABLE departments (dept_id INT PRIMARY KEY, dept_name VARCHAR(64));
                CREATE TABLE staff (staff_id INT PRIMARY KEY, dept_id INT, email VARCHAR(128));
                SELECT s.staff_id, d.dept_name
                FROM staff s
                JOIN departments d ON s.dept_id = d.dept_id;
                """;
        JoinAnalysisGraph gDdl = service.analyzeJoins(
                JoinAnalysisRequest.of(ddlAndQuery, EDbVendor.dbvpostgresql));
        assertHasCode(gDdl, DiagnosticCode.NON_INDEXED_JOIN_KEY, Severity.WARN);

        // Adding CREATE INDEX on staff(dept_id) resolves NON_INDEXED_JOIN_KEY!
        String indexedDdlAndQuery = """
                CREATE TABLE departments (dept_id INT PRIMARY KEY, dept_name VARCHAR(64));
                CREATE TABLE staff (staff_id INT PRIMARY KEY, dept_id INT);
                CREATE INDEX idx_staff_dept ON staff (dept_id);
                SELECT s.staff_id, d.dept_name
                FROM staff s
                JOIN departments d ON s.dept_id = d.dept_id;
                """;
        JoinAnalysisGraph gIndexed = service.analyzeJoins(
                JoinAnalysisRequest.of(indexedDdlAndQuery, EDbVendor.dbvpostgresql));
        assertThat(codesOf(gIndexed)).doesNotContain(DiagnosticCode.NON_INDEXED_JOIN_KEY);
    }

    @Test
    void rule09_complexPredicate_isDetectedForFunctionsOrDisjunctionsAndRangeJoins() {
        // Function call predicate
        JoinAnalysisGraph gFn = service.analyzeJoins(JoinAnalysisRequest.of(
                "SELECT u.id FROM users u JOIN leads l ON LOWER(u.email) = LOWER(l.email)",
                EDbVendor.dbvpostgresql));
        assertHasCode(gFn, DiagnosticCode.COMPLEX_PREDICATE, Severity.WARN);

        // OR disjunction predicate
        JoinAnalysisGraph gOr = service.analyzeJoins(JoinAnalysisRequest.of(
                "SELECT a.id FROM t1 a JOIN t2 b ON a.id = b.id OR a.alt_id = b.id",
                EDbVendor.dbvpostgresql));
        assertHasCode(gOr, DiagnosticCode.COMPLEX_PREDICATE, Severity.WARN);

        // Range / BETWEEN join predicate
        JoinAnalysisGraph gBetween = service.analyzeJoins(JoinAnalysisRequest.of(
                "SELECT e.id FROM events e JOIN sessions s ON e.event_ts BETWEEN s.start_ts AND s.end_ts",
                EDbVendor.dbvsnowflake));
        assertHasCode(gBetween, DiagnosticCode.COMPLEX_PREDICATE, Severity.WARN);
    }

    @Test
    void rule10_unparsedCondition_isDetectedOnTemplateMacrosAndMalformedOperators() {
        JoinAnalysisGraph gMacro = service.analyzeJoins(JoinAnalysisRequest.of(
                "SELECT o.id FROM orders o JOIN customers c ON o.customer_id = {{ dbt_customer_key() }}",
                EDbVendor.dbvsnowflake));
        assertHasCode(gMacro, DiagnosticCode.UNPARSED_CONDITION, Severity.WARN);

        JoinAnalysisGraph gBrokenOp = service.analyzeJoins(JoinAnalysisRequest.of(
                "SELECT o.id FROM orders o JOIN customers c ON o.customer_id == c.id",
                EDbVendor.dbvpostgresql));
        assertHasCode(gBrokenOp, DiagnosticCode.UNPARSED_CONDITION, Severity.WARN);
    }

    @Test
    void all10DiagnosticCodesVerifiedExhaustivelyAcrossEnum() {
        Set<DiagnosticCode> triggered = EnumSet.noneOf(DiagnosticCode.class);

        String[] queries = {
            "SELECT * FROM a INNER JOIN b", // 1. MISSING_JOIN_CONDITION
            "SELECT * FROM a CROSS JOIN b", // 2. CARTESIAN_JOIN
            "SELECT * FROM line_items li JOIN deliveries d ON li.order_id = d.order_id", // 3. MANY_TO_MANY_RISK
            "SELECT * FROM orders o LEFT JOIN customers c ON o.cid = c.id WHERE c.tier = 'GOLD'", // 4. OUTER_JOIN_FILTER_RISK
            "SELECT * FROM orders o LEFT JOIN invoices i ON o.id = i.order_id WHERE i.id IS NULL", // 5. ANTI_JOIN_DETECTED
            "SELECT * FROM employees e JOIN employees m ON e.mgr_id = m.id", // 6. SELF_JOIN_DETECTED
            "SELECT * FROM customers c WHERE EXISTS (SELECT 1 FROM orders o WHERE o.cid = c.id)", // 7. SEMI_JOIN_DETECTED
            "CREATE TABLE p (id INT PRIMARY KEY); CREATE TABLE ch (id INT PRIMARY KEY, pid INT); SELECT * FROM ch JOIN p ON ch.pid = p.id;", // 8. NON_INDEXED_JOIN_KEY
            "SELECT * FROM a JOIN b ON COALESCE(a.k, 0) = COALESCE(b.k, 0)", // 9. COMPLEX_PREDICATE
            "SELECT * FROM a JOIN b ON a.id = ${dynamic_join_col}" // 10. UNPARSED_CONDITION
        };

        for (String q : queries) {
            JoinAnalysisGraph g = service.analyzeJoins(JoinAnalysisRequest.of(q, EDbVendor.dbvpostgresql));
            triggered.addAll(codesOf(g));
        }

        assertThat(triggered).containsExactlyInAnyOrder(DiagnosticCode.values());
    }

    @Test
    void multiBlockCteAndDerivedSubqueryGraphSerializesCleanWireJsonAndHandlesErrors() throws Exception {
        String multiBlockSql = """
                WITH active_customers AS (
                    SELECT c.id, c.region_id
                    FROM customers c
                    INNER JOIN regions r ON c.region_id = r.id
                    WHERE c.active = true
                )
                SELECT ac.id, ord_totals.total_amt
                FROM active_customers ac
                LEFT JOIN (
                    SELECT o.customer_id, o.amount AS total_amt
                    FROM orders o
                    USING_placeholder_check:
                    INNER JOIN order_status os USING (status_id)
                ) ord_totals ON ac.id = ord_totals.customer_id
                """.replace("USING_placeholder_check:\n    ", "");

        JoinAnalysisRequest req = JoinAnalysisRequest.of(multiBlockSql, EDbVendor.dbvpostgresql);
        req.setLayoutDirection(LayoutDirection.DOWN);

        JoinAnalysisGraph graph = service.analyzeJoins(req);
        assertThat(graph.getSchemaVersion()).isEqualTo("join-analysis.v1");
        assertThat(graph.getErrors()).isNull();
        assertThat(graph.getLayout().getDirection()).isEqualTo(LayoutDirection.DOWN);
        assertThat(graph.getQueryBlocks())
                .extracting(JoinAnalysisGraph.QueryBlockLane::getKind)
                .containsExactlyInAnyOrder(LaneKind.CTE, LaneKind.SUBQUERY, LaneKind.MAIN);

        // Verify USING clause wire enum
        assertThat(graph.getJoins())
                .anySatisfy(j -> {
                    assertThat(j.getClause()).isEqualTo(JoinClause.USING);
                    assertThat(j.getJoinType()).isEqualTo(JoinType.INNER);
                });

        // Verify non-overlapping positions across all nodes in multi-block DOWN layout
        List<JoinAnalysisNode> nodes = graph.getNodes();
        assertThat(nodes).hasSizeGreaterThanOrEqualTo(9);
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                JoinAnalysisNode a = nodes.get(i);
                JoinAnalysisNode b = nodes.get(j);
                boolean sameSpot = Math.abs(a.getPosition().getX() - b.getPosition().getX()) < 1.0
                        && Math.abs(a.getPosition().getY() - b.getPosition().getY()) < 1.0;
                assertThat(sameSpot)
                        .as("Nodes %s and %s must have distinct coordinates", a.getId(), b.getId())
                        .isFalse();
            }
        }

        // Verify exact Jackson wire contract invariants
        String json = mapper.writeValueAsString(graph);
        JsonNode root = mapper.readTree(json);
        assertThat(root.get("schemaVersion").asText()).isEqualTo("join-analysis.v1");
        assertThat(root.has("code")).isFalse();
        assertThat(root.has("data")).isFalse();
        assertThat(root.get("layout").has("width")).isFalse();
        assertThat(root.get("layout").has("height")).isFalse();
        assertThat(root.get("fragments").isArray()).isTrue();
        assertThat(root.get("fragments").size()).isGreaterThanOrEqualTo(6);

        // Verify sqlUnits fallback and malformed SQL parse_failed error handling
        JoinAnalysisRequest unitsReq = new JoinAnalysisRequest();
        unitsReq.setSqlUnits(List.of(new SqlInfo(
                "SELECT a.id FROM t1 a JOIN t2 b ON a.id = b.a_id", "q.sql", "sql/q.sql", "dbvoracle")));
        JoinAnalysisGraph fromUnits = service.analyzeJoins(unitsReq);
        assertThat(fromUnits.getJoins()).hasSize(1);

        JoinAnalysisGraph broken = service.analyzeJoins(
                JoinAnalysisRequest.of("SELECT FROM (((", EDbVendor.dbvoracle));
        assertThat(broken.getErrors()).isNotEmpty();
        assertThat(broken.getErrors().get(0).getCode()).isEqualTo("parse_failed");
    }

    private static void assertHasCode(JoinAnalysisGraph graph, DiagnosticCode expectedCode, Severity expectedSeverity) {
        assertThat(graph.getDiagnostics())
                .as("Expected diagnostic %s in %s", expectedCode, graph.getDiagnostics())
                .anySatisfy(d -> {
                    assertThat(d.getCode()).isEqualTo(expectedCode);
                    assertThat(d.getSeverity()).isEqualTo(expectedSeverity);
                    assertThat(d.getMessage()).isNotBlank();
                });
    }

    private static List<DiagnosticCode> codesOf(JoinAnalysisGraph graph) {
        if (graph.getDiagnostics() == null) {
            return List.of();
        }
        return graph.getDiagnostics().stream().map(JoinDiagnostic::getCode).toList();
    }
}
