package io.sqlflow.server.spi;

import io.sqlflow.contracts.graph.DiagnosticCode;
import io.sqlflow.contracts.graph.JoinAnalysisGraph;
import io.sqlflow.contracts.spi.JoinAnalysisSpi;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Built-in fallback implementation of {@link JoinAnalysisSpi} producing valid {@code join-analysis.v1}
 * graphs with nodes, edges, query blocks, fragments, and diagnostics.
 */
public class FallbackJoinAnalysisSpi implements JoinAnalysisSpi {

    private static final Pattern FROM_TABLE_PATTERN = Pattern.compile(
            "(?is)\\bFROM\\s+([\\w.\"`\\[\\]]+)(?:\\s+(?:AS\\s+)?(?!INNER\\b|LEFT\\b|RIGHT\\b|FULL\\b|CROSS\\b|JOIN\\b|WHERE\\b|GROUP\\b|ORDER\\b|ON\\b)([\\w\"`\\[\\]]+))?");
    private static final Pattern JOIN_CLAUSE_PATTERN = Pattern.compile(
            "(?is)\\b(INNER\\s+JOIN|LEFT(?:\\s+OUTER)?\\s+JOIN|RIGHT(?:\\s+OUTER)?\\s+JOIN|FULL(?:\\s+OUTER)?\\s+JOIN|CROSS\\s+JOIN|JOIN)\\s+([\\w.\"`\\[\\]]+)(?:\\s+(?:AS\\s+)?(?!ON\\b|USING\\b|INNER\\b|LEFT\\b|RIGHT\\b|FULL\\b|CROSS\\b|JOIN\\b|WHERE\\b|GROUP\\b|ORDER\\b)([\\w\"`\\[\\]]+))?(?:\\s+(ON|USING)\\s+(.+?))?(?=\\b(?:INNER\\s+JOIN|LEFT(?:\\s+OUTER)?\\s+JOIN|RIGHT(?:\\s+OUTER)?\\s+JOIN|FULL(?:\\s+OUTER)?\\s+JOIN|CROSS\\s+JOIN|JOIN|WHERE|GROUP\\s+BY|ORDER\\s+BY|HAVING|LIMIT|;|$))");
    private static final Pattern WHERE_PATTERN = Pattern.compile(
            "(?is)\\bWHERE\\s+(.+?)(?=\\b(?:GROUP\\s+BY|ORDER\\s+BY|HAVING|LIMIT|;|$))");

    @Override
    public JoinAnalysisGraph analyzeJoins(JoinAnalysisRequest request) {
        String sql = request != null && request.getSqlText() != null ? request.getSqlText() : "";
        EDbVendor vendor = request != null && request.getVendor() != null ? request.getVendor() : EDbVendor.dbvoracle;

        JoinAnalysisGraph graph = new JoinAnalysisGraph();
        graph.setSchemaVersion(JoinAnalysisGraph.SCHEMA_VERSION);
        graph.setGraphId("ja_" + UUID.randomUUID().toString().substring(0, 8));
        graph.setSqlText(sql);
        graph.setDbvendor(vendor);

        JoinAnalysisGraph.LayoutDirection dir = request != null && request.getLayoutDirection() != null
                ? request.getLayoutDirection()
                : JoinAnalysisGraph.LayoutDirection.RIGHT;
        graph.setLayout(new JoinAnalysisGraph.Layout(JoinAnalysisGraph.LayoutEngine.ELK, dir, 96, 48));

        int totalLines = Math.max(1, sql.split("\n", -1).length);
        JoinAnalysisGraph.SourceRange fullRange = new JoinAnalysisGraph.SourceRange(
                1, 1, totalLines, Math.max(1, sql.length()), JoinAnalysisGraph.SourceRangeQuality.APPROXIMATE);

        JoinAnalysisGraph.QueryBlockLane mainLane = new JoinAnalysisGraph.QueryBlockLane();
        mainLane.setQueryBlockId("qb_main");
        mainLane.setLabel("Main Query");
        mainLane.setKind(JoinAnalysisGraph.LaneKind.MAIN);
        mainLane.setSourceRange(fullRange);
        mainLane.setCollapsedByDefault(Boolean.FALSE);
        graph.setQueryBlocks(List.of(mainLane));

        List<JoinAnalysisGraph.JoinAnalysisNode> nodes = new ArrayList<>();
        List<JoinAnalysisGraph.JoinAnalysisEdge> edges = new ArrayList<>();
        List<JoinAnalysisGraph.SqlFragment> fragments = new ArrayList<>();
        List<JoinAnalysisGraph.JoinDiagnostic> diagnostics = new ArrayList<>();
        List<JoinAnalysisGraph.TableData> tables = new ArrayList<>();
        List<JoinAnalysisGraph.JoinDetail> joins = new ArrayList<>();
        List<JoinAnalysisGraph.JoinPredicate> allPredicates = new ArrayList<>();
        List<JoinAnalysisGraph.CardinalityItem> cardinalities = new ArrayList<>();

        Map<String, String> tableNameCounts = new LinkedHashMap<>();
        String prevNodeId = null;
        int nodeIdx = 0;

        Matcher fromMatcher = FROM_TABLE_PATTERN.matcher(sql);
        if (fromMatcher.find()) {
            String rawTable = CatalogUtils.stripQuotes(fromMatcher.group(1));
            String alias = CatalogUtils.stripQuotes(fromMatcher.group(2));
            String nodeId = "tbl_" + (++nodeIdx);
            tableNameCounts.merge(rawTable.toUpperCase(Locale.ROOT), "1", (a, b) -> "2");

            JoinAnalysisGraph.TableData td = buildTableData(nodeId, rawTable, alias, false);
            tables.add(td);

            JoinAnalysisGraph.NodeData nd = new JoinAnalysisGraph.NodeData();
            nd.setLabel(alias != null ? rawTable + " (" + alias + ")" : rawTable);
            nd.setQueryBlockId("qb_main");
            nd.setFragmentId("frag_" + nodeId);
            nd.setTable(td);

            nodes.add(new JoinAnalysisGraph.JoinAnalysisNode(
                    nodeId,
                    JoinAnalysisGraph.JoinNodeType.TABLE,
                    new JoinAnalysisGraph.Position(40.0, 60.0),
                    nd));
            fragments.add(buildFragment("frag_" + nodeId, List.of(nodeId), JoinAnalysisGraph.FragmentKind.TABLE_REF, fromMatcher.group(0), fullRange));
            prevNodeId = nodeId;
        }

        Matcher whereMatcher = WHERE_PATTERN.matcher(sql);
        String whereClause = whereMatcher.find() ? whereMatcher.group(1).trim() : "";

        Matcher joinMatcher = JOIN_CLAUSE_PATTERN.matcher(sql);
        int joinOrder = 0;
        while (joinMatcher.find()) {
            joinOrder++;
            String joinKw = joinMatcher.group(1).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
            String rightTableRaw = CatalogUtils.stripQuotes(joinMatcher.group(2));
            String rightAlias = CatalogUtils.stripQuotes(joinMatcher.group(3));
            String clauseKw = joinMatcher.group(4);
            String condRaw = joinMatcher.group(5) != null ? joinMatcher.group(5).trim() : null;

            boolean selfJoin = tableNameCounts.containsKey(rightTableRaw.toUpperCase(Locale.ROOT));
            tableNameCounts.put(rightTableRaw.toUpperCase(Locale.ROOT), "1");

            String tblNodeId = "tbl_" + (++nodeIdx);
            JoinAnalysisGraph.TableData td = buildTableData(tblNodeId, rightTableRaw, rightAlias, selfJoin);
            tables.add(td);

            JoinAnalysisGraph.NodeData tblNd = new JoinAnalysisGraph.NodeData();
            tblNd.setLabel(rightAlias != null ? rightTableRaw + " (" + rightAlias + ")" : rightTableRaw);
            tblNd.setQueryBlockId("qb_main");
            tblNd.setTable(td);
            nodes.add(new JoinAnalysisGraph.JoinAnalysisNode(
                    tblNodeId,
                    JoinAnalysisGraph.JoinNodeType.TABLE,
                    new JoinAnalysisGraph.Position(40.0, 60.0 + joinOrder * 110.0),
                    tblNd));

            JoinAnalysisGraph.JoinType jt = resolveJoinType(joinKw);
            JoinAnalysisGraph.JoinClause jc = clauseKw == null
                    ? JoinAnalysisGraph.JoinClause.NONE
                    : ("USING".equalsIgnoreCase(clauseKw) ? JoinAnalysisGraph.JoinClause.USING : JoinAnalysisGraph.JoinClause.ON);

            String joinNodeId = "join_" + joinOrder;
            String fragId = "frag_" + joinNodeId;
            List<JoinAnalysisGraph.JoinPredicate> joinPreds = new ArrayList<>();
            if (condRaw != null && !condRaw.isEmpty()) {
                JoinAnalysisGraph.JoinPredicate pred = new JoinAnalysisGraph.JoinPredicate();
                pred.setPredicateId("pred_" + joinOrder);
                pred.setRaw(condRaw);
                pred.setKind(condRaw.contains("=") && !condRaw.contains("<") && !condRaw.contains(">") && !condRaw.toUpperCase(Locale.ROOT).contains(" OR ")
                        ? JoinAnalysisGraph.PredicateKind.EQUI
                        : JoinAnalysisGraph.PredicateKind.COMPLEX);
                pred.setOp(condRaw.contains("=") ? "=" : "expr");
                pred.setSourceRange(fullRange);
                joinPreds.add(pred);
                allPredicates.add(pred);
            }

            JoinAnalysisGraph.JoinDetail jd = new JoinAnalysisGraph.JoinDetail();
            jd.setJoinType(jt);
            jd.setClause(jc);
            jd.setOrder(joinOrder);
            jd.setPredicates(joinPreds);
            jd.setCardinalityHint(
                    jt == JoinAnalysisGraph.JoinType.CROSS
                            ? JoinAnalysisGraph.CardinalityHint.MANY_TO_MANY
                            : JoinAnalysisGraph.CardinalityHint.ONE_TO_MANY);
            jd.setSourceRange(fullRange);
            List<JoinAnalysisGraph.JoinPattern> patterns = new ArrayList<>();
            List<JoinAnalysisGraph.JoinBadge> badges = new ArrayList<>();
            if (selfJoin) {
                patterns.add(JoinAnalysisGraph.JoinPattern.SELF);
                badges.add(new JoinAnalysisGraph.JoinBadge(JoinAnalysisGraph.BadgeKind.SELF_JOIN, JoinAnalysisGraph.FanOutLevel.LOW, Boolean.FALSE));
                diagnostics.add(buildDiag(
                        "diag_self_" + joinOrder,
                        DiagnosticCode.SELF_JOIN_DETECTED,
                        "Self-join detected on table " + rightTableRaw,
                        List.of(tblNodeId, joinNodeId),
                        List.of(fragId)));
            }
            if (jt == JoinAnalysisGraph.JoinType.CROSS) {
                patterns.add(JoinAnalysisGraph.JoinPattern.CARTESIAN);
                badges.add(new JoinAnalysisGraph.JoinBadge(JoinAnalysisGraph.BadgeKind.CROSS_JOIN, JoinAnalysisGraph.FanOutLevel.HIGH, Boolean.FALSE));
                diagnostics.add(buildDiag(
                        "diag_cart_" + joinOrder,
                        DiagnosticCode.CARTESIAN_JOIN,
                        "CROSS JOIN produces a Cartesian product with " + rightTableRaw,
                        List.of(tblNodeId, joinNodeId),
                        List.of(fragId)));
            } else if (condRaw == null || condRaw.isBlank()) {
                diagnostics.add(buildDiag(
                        "diag_missing_on_" + joinOrder,
                        DiagnosticCode.MISSING_JOIN_CONDITION,
                        "Join on " + rightTableRaw + " is missing an ON/USING predicate",
                        List.of(tblNodeId, joinNodeId),
                        List.of(fragId)));
            }
            if ((jt == JoinAnalysisGraph.JoinType.LEFT || jt == JoinAnalysisGraph.JoinType.RIGHT)
                    && !whereClause.isEmpty()) {
                String checkAlias = rightAlias != null ? rightAlias : rightTableRaw;
                if (whereClause.toUpperCase(Locale.ROOT).contains((checkAlias + ".").toUpperCase(Locale.ROOT))
                        && whereClause.toUpperCase(Locale.ROOT).contains("IS NULL")) {
                    patterns.add(JoinAnalysisGraph.JoinPattern.ANTI);
                    badges.add(new JoinAnalysisGraph.JoinBadge(JoinAnalysisGraph.BadgeKind.ANTI_JOIN, JoinAnalysisGraph.FanOutLevel.LOW, Boolean.FALSE));
                    diagnostics.add(buildDiag(
                            "diag_anti_" + joinOrder,
                            DiagnosticCode.ANTI_JOIN_DETECTED,
                            "Outer join with IS NULL filter acts as an anti-join on " + rightTableRaw,
                            List.of(joinNodeId),
                            List.of(fragId)));
                } else if (whereClause.toUpperCase(Locale.ROOT).contains((checkAlias + ".").toUpperCase(Locale.ROOT))) {
                    diagnostics.add(buildDiag(
                            "diag_outer_filter_" + joinOrder,
                            DiagnosticCode.OUTER_JOIN_FILTER_RISK,
                            "WHERE filter references nullable side '" + checkAlias + "' and may turn OUTER JOIN into INNER JOIN",
                            List.of(joinNodeId),
                            List.of(fragId)));
                }
            }
            if (!patterns.isEmpty()) {
                jd.setPatterns(patterns);
            }
            joins.add(jd);

            JoinAnalysisGraph.NodeData joinNd = new JoinAnalysisGraph.NodeData();
            joinNd.setLabel(jt.getWire() + " JOIN");
            joinNd.setSummary(condRaw != null ? condRaw : jt.getWire());
            joinNd.setQueryBlockId("qb_main");
            joinNd.setFragmentId(fragId);
            joinNd.setJoin(jd);
            if (!badges.isEmpty()) {
                joinNd.setBadges(badges);
            }
            nodes.add(new JoinAnalysisGraph.JoinAnalysisNode(
                    joinNodeId,
                    JoinAnalysisGraph.JoinNodeType.JOIN,
                    new JoinAnalysisGraph.Position(280.0 + joinOrder * 180.0, 90.0 + (joinOrder - 1) * 70.0),
                    joinNd));
            fragments.add(buildFragment(fragId, List.of(joinNodeId, tblNodeId), JoinAnalysisGraph.FragmentKind.JOIN_CLAUSE, joinMatcher.group(0), fullRange));

            if (prevNodeId != null) {
                JoinAnalysisGraph.EdgeData edLeft = new JoinAnalysisGraph.EdgeData();
                edLeft.setKind(prevNodeId.startsWith("join_")
                        ? JoinAnalysisGraph.JoinEdgeKind.JOIN_TO_JOIN
                        : JoinAnalysisGraph.JoinEdgeKind.TABLE_TO_JOIN);
                edges.add(new JoinAnalysisGraph.JoinAnalysisEdge("edge_l_" + joinOrder, prevNodeId, joinNodeId, edLeft));
            }
            JoinAnalysisGraph.EdgeData edRight = new JoinAnalysisGraph.EdgeData();
            edRight.setKind(JoinAnalysisGraph.JoinEdgeKind.TABLE_TO_JOIN);
            edges.add(new JoinAnalysisGraph.JoinAnalysisEdge("edge_r_" + joinOrder, tblNodeId, joinNodeId, edRight));

            JoinAnalysisGraph.CardinalityItem card = new JoinAnalysisGraph.CardinalityItem();
            card.setJoinId(joinNodeId);
            card.setCardinality(jd.getCardinalityHint());
            card.setFanOutRisk(jt == JoinAnalysisGraph.JoinType.CROSS
                    ? JoinAnalysisGraph.FanOutLevel.HIGH
                    : JoinAnalysisGraph.FanOutLevel.MEDIUM);
            card.setReason("Heuristic static SQL join cardinality estimate");
            cardinalities.add(card);

            prevNodeId = joinNodeId;
        }

        // Output node
        String outNodeId = "out_main";
        JoinAnalysisGraph.NodeData outNd = new JoinAnalysisGraph.NodeData();
        outNd.setLabel("Query Output");
        outNd.setQueryBlockId("qb_main");
        nodes.add(new JoinAnalysisGraph.JoinAnalysisNode(
                outNodeId,
                JoinAnalysisGraph.JoinNodeType.OUTPUT,
                new JoinAnalysisGraph.Position(340.0 + (joinOrder + 1) * 180.0, 90.0),
                outNd));
        if (prevNodeId != null) {
            JoinAnalysisGraph.EdgeData outEdge = new JoinAnalysisGraph.EdgeData();
            outEdge.setKind(JoinAnalysisGraph.JoinEdgeKind.JOIN_TO_OUTPUT);
            edges.add(new JoinAnalysisGraph.JoinAnalysisEdge("edge_out", prevNodeId, outNodeId, outEdge));
        }

        JoinAnalysisGraph.JoinSummary sum = new JoinAnalysisGraph.JoinSummary();
        sum.setTableCount(tables.size());
        sum.setJoinCount(joins.size());
        sum.setPredicateCount(allPredicates.size());
        sum.setDiagnosticCount(diagnostics.size());
        sum.setBlockCount((int) diagnostics.stream().filter(d -> d.getSeverity() == JoinAnalysisGraph.Severity.BLOCK).count());
        sum.setWarnCount((int) diagnostics.stream().filter(d -> d.getSeverity() == JoinAnalysisGraph.Severity.WARN).count());
        sum.setInfoCount((int) diagnostics.stream().filter(d -> d.getSeverity() == JoinAnalysisGraph.Severity.INFO).count());

        graph.setNodes(nodes);
        graph.setEdges(edges);
        graph.setFragments(fragments);
        graph.setDiagnostics(diagnostics);
        graph.setSummary(sum);
        graph.setTables(tables);
        graph.setJoins(joins);
        graph.setPredicates(allPredicates);
        graph.setCardinality(cardinalities);
        return graph;
    }

    private static JoinAnalysisGraph.TableData buildTableData(String id, String rawName, String alias, boolean selfJoin) {
        JoinAnalysisGraph.TableData td = new JoinAnalysisGraph.TableData();
        td.setTableId(id);
        String[] parts = rawName.split("\\.");
        if (parts.length >= 2) {
            td.setSchema(parts[parts.length - 2]);
            td.setName(parts[parts.length - 1]);
        } else {
            td.setName(rawName);
        }
        td.setAlias(alias);
        td.setIsSelfJoinInstance(selfJoin);
        return td;
    }

    private static JoinAnalysisGraph.JoinType resolveJoinType(String joinKw) {
        if (joinKw.startsWith("LEFT")) {
            return JoinAnalysisGraph.JoinType.LEFT;
        }
        if (joinKw.startsWith("RIGHT")) {
            return JoinAnalysisGraph.JoinType.RIGHT;
        }
        if (joinKw.startsWith("FULL")) {
            return JoinAnalysisGraph.JoinType.FULL;
        }
        if (joinKw.startsWith("CROSS")) {
            return JoinAnalysisGraph.JoinType.CROSS;
        }
        return JoinAnalysisGraph.JoinType.INNER;
    }

    private static JoinAnalysisGraph.SqlFragment buildFragment(
            String id, List<String> nodeIds, JoinAnalysisGraph.FragmentKind kind, String text, JoinAnalysisGraph.SourceRange range) {
        JoinAnalysisGraph.SqlFragment f = new JoinAnalysisGraph.SqlFragment();
        f.setFragmentId(id);
        f.setNodeIds(nodeIds);
        f.setKind(kind);
        f.setText(text);
        f.setSourceRange(range);
        return f;
    }

    private static JoinAnalysisGraph.JoinDiagnostic buildDiag(
            String id, DiagnosticCode code, String msg, List<String> nodeIds, List<String> fragIds) {
        JoinAnalysisGraph.JoinDiagnostic d = new JoinAnalysisGraph.JoinDiagnostic();
        d.setId(id);
        d.setCode(code);
        d.setSeverity(code.getDefaultSeverity());
        d.setRoles(List.of(JoinAnalysisGraph.Role.ALL));
        d.setMessage(msg);
        d.setEvidenceNodeIds(nodeIds);
        d.setFragmentIds(fragIds);
        return d;
    }
}
