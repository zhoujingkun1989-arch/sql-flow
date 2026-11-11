package io.sqlflow.graph;

import io.sqlflow.contracts.graph.DiagnosticCode;
import io.sqlflow.contracts.graph.JoinAnalysisGraph;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.BadgeKind;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.CardinalityHint;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.CardinalityItem;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.ColumnContribution;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.ColumnRef;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.EdgeData;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.FanOutLevel;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.FragmentKind;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.JoinAnalysisEdge;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.JoinAnalysisError;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.JoinAnalysisNode;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.JoinBadge;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.JoinClause;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.JoinDetail;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.JoinDiagnostic;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.JoinEdgeKind;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.JoinNodeType;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.JoinPattern;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.JoinPredicate;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.JoinSummary;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.JoinType;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.LaneKind;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.Layout;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.LayoutDirection;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.LayoutEngine;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.NodeData;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.Position;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.PredicateKind;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.QueryBlockLane;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.Role;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.Severity;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.SourceRange;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.SourceRangeQuality;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.SqlFragment;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.TableData;
import io.sqlflow.contracts.lineage.SqlInfo;
import io.sqlflow.contracts.spi.JoinAnalysisSpi;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.graph.join.JoinCatalogMetadata;
import io.sqlflow.graph.join.JoinCatalogMetadata.ColumnMeta;
import io.sqlflow.graph.join.JoinCatalogMetadata.TableMeta;
import io.sqlflow.graph.join.SqlJoinAstParser;
import io.sqlflow.graph.join.SqlJoinAstParser.JoinStepModel;
import io.sqlflow.graph.join.SqlJoinAstParser.ParseErrorItem;
import io.sqlflow.graph.join.SqlJoinAstParser.ParseOutcome;
import io.sqlflow.graph.join.SqlJoinAstParser.ParsedColRef;
import io.sqlflow.graph.join.SqlJoinAstParser.ParsedPredicate;
import io.sqlflow.graph.join.SqlJoinAstParser.QueryBlockModel;
import io.sqlflow.graph.join.SqlJoinAstParser.TableRefModel;
import io.sqlflow.graph.join.SqlJoinAstParser.WhereFilterModel;
import io.sqlflow.graph.model.DataFlowIndex;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Clean-room implementation of {@link JoinAnalysisSpi} producing {@code join-analysis.v1}
 * ({@link JoinAnalysisGraph}) with multi-block lanes, ELK-compatible coordinates, key/cardinality
 * inference, and all 10 {@link DiagnosticCode} rules ({@code OBL-057}..{@code OBL-058}).
 */
public class JoinAnalysisService implements JoinAnalysisSpi {

    private static final int DEFAULT_RANK_SPACING = 140;
    private static final int DEFAULT_NODE_SPACING = 72;
    private static final double NODE_WIDTH = 220.0;
    private static final double NODE_HEIGHT = 84.0;
    private static final double LANE_GAP = 104.0;
    private static final double CANVAS_MARGIN = 40.0;

    public JoinAnalysisService() {
    }

    @Override
    public JoinAnalysisGraph analyzeJoins(JoinAnalysisRequest request) {
        JoinAnalysisRequest req = request != null ? request : new JoinAnalysisRequest();
        EDbVendor vendor = req.getVendor() != null ? req.getVendor() : EDbVendor.dbvoracle;
        LayoutDirection direction = req.getLayoutDirection() != null
                ? req.getLayoutDirection()
                : LayoutDirection.RIGHT;
        String resolvedSql = resolveSqlText(req);

        JoinAnalysisGraph graph = new JoinAnalysisGraph();
        graph.setSchemaVersion(JoinAnalysisGraph.SCHEMA_VERSION);
        graph.setDbvendor(vendor);
        graph.setSqlText(resolvedSql != null ? resolvedSql : "");
        graph.setGraphId("jg-" + Integer.toUnsignedString(
                Objects.hash(graph.getSqlText(), vendor.name(), req.getJobId()), 16));
        graph.setLayout(new Layout(
                LayoutEngine.ELK, direction, DEFAULT_RANK_SPACING, DEFAULT_NODE_SPACING));

        if (resolvedSql == null || resolvedSql.isBlank()) {
            JoinAnalysisError err = new JoinAnalysisError(
                    "parse_failed",
                    "sqltext is required",
                    new SourceRange(1, 1, 1, 1, SourceRangeQuality.MISSING));
            err.setErrorType("ValidationError");
            err.setErrorMessage("sqltext is required");
            graph.setQueryBlocks(List.of());
            graph.setNodes(List.of());
            graph.setEdges(List.of());
            graph.setFragments(List.of());
            graph.setDiagnostics(List.of());
            graph.setErrors(List.of(err));
            graph.setSummary(new JoinSummary());
            return graph;
        }

        JoinCatalogMetadata catalogMeta = JoinCatalogMetadata.fromCatalog(req.getCatalog());
        ParseOutcome outcome = SqlJoinAstParser.parse(
                resolvedSql, catalogMeta, req.getDefaultDatabase(), req.getDefaultSchema());

        BuilderContext ctx = new BuilderContext(catalogMeta, direction);
        for (QueryBlockModel block : outcome.queryBlocks()) {
            ctx.processBlock(block);
        }
        ctx.wireCrossBlockEdges(outcome.queryBlocks());

        graph.setQueryBlocks(ctx.lanes);
        graph.setNodes(ctx.nodes);
        graph.setEdges(ctx.edges);
        graph.setFragments(ctx.fragments);
        graph.setDiagnostics(ctx.diagnostics);
        graph.setTables(ctx.allTables);
        graph.setJoins(ctx.allJoins);
        graph.setPredicates(ctx.allPredicates);
        graph.setKeys(ctx.allKeys);
        graph.setCardinality(ctx.allCardinalities);
        graph.setSummary(ctx.buildSummary());

        if (!outcome.errors().isEmpty()) {
            List<JoinAnalysisError> errs = new ArrayList<>(outcome.errors().size());
            for (ParseErrorItem pe : outcome.errors()) {
                JoinAnalysisError err = new JoinAnalysisError(pe.code(), pe.message(), pe.sourceRange());
                err.setErrorType("SyntaxError");
                err.setErrorMessage(pe.message());
                errs.add(err);
            }
            graph.setErrors(errs);
        }

        return graph;
    }

    private static String resolveSqlText(JoinAnalysisRequest req) {
        if (req.getSqlText() != null && !req.getSqlText().isBlank()) {
            return req.getSqlText();
        }
        if (req.getSqlUnits() != null && !req.getSqlUnits().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (SqlInfo unit : req.getSqlUnits()) {
                if (unit != null && unit.getSql() != null && !unit.getSql().isBlank()) {
                    if (sb.length() > 0) {
                        sb.append(";\n");
                    }
                    sb.append(unit.getSql().trim());
                }
            }
            return sb.toString();
        }
        return req.getSqlText();
    }

    // -------------------------------------------------------------------------
    // Per-Request Graph & Diagnostic Assembly Context
    // -------------------------------------------------------------------------

    private static final class ResolvedColKey {
        final ColumnRef columnRef;
        final TableRefModel ownerTable;
        final boolean catalogBacked;

        ResolvedColKey(ColumnRef columnRef, TableRefModel ownerTable, boolean catalogBacked) {
            this.columnRef = columnRef;
            this.ownerTable = ownerTable;
            this.catalogBacked = catalogBacked;
        }
    }

    private static final class FilterNodeRecord {
        final WhereFilterModel model;
        final String nodeId;
        final String fragmentId;

        FilterNodeRecord(WhereFilterModel model, String nodeId, String fragmentId) {
            this.model = model;
            this.nodeId = nodeId;
            this.fragmentId = fragmentId;
        }
    }

    private static final class JoinNodeRecord {
        final JoinStepModel step;
        final String joinNodeId;
        final String joinFragmentId;
        final String conditionFragmentId;
        final String leftInputEdgeId;
        final String rightInputEdgeId;
        final JoinDetail joinDetail;
        final List<JoinBadge> badges;
        final List<JoinPattern> patterns;

        JoinNodeRecord(
                JoinStepModel step,
                String joinNodeId,
                String joinFragmentId,
                String conditionFragmentId,
                String leftInputEdgeId,
                String rightInputEdgeId,
                JoinDetail joinDetail,
                List<JoinBadge> badges,
                List<JoinPattern> patterns) {
            this.step = step;
            this.joinNodeId = joinNodeId;
            this.joinFragmentId = joinFragmentId;
            this.conditionFragmentId = conditionFragmentId;
            this.leftInputEdgeId = leftInputEdgeId;
            this.rightInputEdgeId = rightInputEdgeId;
            this.joinDetail = joinDetail;
            this.badges = badges;
            this.patterns = patterns;
        }
    }

    private static final class BuilderContext {
        final JoinCatalogMetadata catalogMeta;
        final LayoutDirection direction;

        final List<QueryBlockLane> lanes = new ArrayList<>();
        final List<JoinAnalysisNode> nodes = new ArrayList<>();
        final List<JoinAnalysisEdge> edges = new ArrayList<>();
        final List<SqlFragment> fragments = new ArrayList<>();
        final List<JoinDiagnostic> diagnostics = new ArrayList<>();

        final List<TableData> allTables = new ArrayList<>();
        final List<JoinDetail> allJoins = new ArrayList<>();
        final List<JoinPredicate> allPredicates = new ArrayList<>();
        final List<ColumnRef> allKeys = new ArrayList<>();
        final List<CardinalityItem> allCardinalities = new ArrayList<>();

        final Map<String, String> blockOutputNodeIds = new LinkedHashMap<>();
        final Map<String, String> tableInstanceNodeIds = new LinkedHashMap<>();

        int fragSeq = 0;
        int edgeSeq = 0;
        int predSeq = 0;
        int diagSeq = 0;
        double currentLaneOffset = CANVAS_MARGIN;

        BuilderContext(JoinCatalogMetadata catalogMeta, LayoutDirection direction) {
            this.catalogMeta = catalogMeta;
            this.direction = direction;
        }

        void processBlock(QueryBlockModel block) {
            QueryBlockLane lane = new QueryBlockLane();
            lane.setQueryBlockId(block.queryBlockId);
            lane.setLabel(block.label);
            lane.setKind(block.kind);
            lane.setParentQueryBlockId(block.parentQueryBlockId);
            lane.setSourceRange(block.sourceRange);
            lane.setCollapsedByDefault(false);
            lanes.add(lane);

            // Query-block fragment
            List<String> blockNodeIds = new ArrayList<>();
            SqlFragment blockFrag = addFragment(
                    FragmentKind.QUERY_BLOCK, block.rawSql, block.sourceRange, blockNodeIds);

            // Determine rank/row slots for tables in this block
            Set<String> joinedRightTableIds = new LinkedHashSet<>();
            for (JoinStepModel js : block.joins) {
                joinedRightTableIds.add(js.rightTable.instanceId);
            }

            Map<String, JoinAnalysisNode> blockTableNodes = new LinkedHashMap<>();
            Map<String, String> blockTableFragIds = new LinkedHashMap<>();
            int standaloneTableRow = 0;
            for (TableRefModel tr : block.tables) {
                String tNodeId = "n-" + block.queryBlockId + "-" + tr.instanceId;
                tableInstanceNodeIds.put(tr.instanceId, tNodeId);
                blockNodeIds.add(tNodeId);

                SqlFragment tFrag = addFragment(
                        FragmentKind.TABLE_REF, tr.rawSql, tr.sourceRange, List.of(tNodeId));
                blockTableFragIds.put(tr.instanceId, tFrag.getFragmentId());

                TableData td = buildTableData(tr);
                allTables.add(td);

                NodeData nd = new NodeData();
                nd.setLabel(formatTableLabel(tr));
                nd.setSummary(buildQualSummary(tr));
                nd.setQueryBlockId(block.queryBlockId);
                nd.setFragmentId(tFrag.getFragmentId());
                nd.setTable(td);
                if (tr.selfJoinInstance) {
                    nd.setBadges(List.of(new JoinBadge(BadgeKind.SELF_JOIN, null, false)));
                }

                JoinNodeType nodeType = tr.isCteRef
                        ? JoinNodeType.CTE
                        : (tr.isSubquery ? JoinNodeType.SUBQUERY : JoinNodeType.TABLE);

                // Initial position for base/unjoined tables; right-hand join tables get positioned at their step rank
                Position pos = joinedRightTableIds.contains(tr.instanceId)
                        ? new Position(0, 0)
                        : computePosition(0, standaloneTableRow++);

                JoinAnalysisNode tNode = new JoinAnalysisNode(tNodeId, nodeType, pos, nd);
                nodes.add(tNode);
                blockTableNodes.put(tr.instanceId, tNode);
            }

            int maxRankInBlock = 0;
            int maxRowInBlock = Math.max(0, standaloneTableRow - 1);

            // Pre-create WHERE filter nodes so outer-join diagnostics & anti-join evidence can reference them
            List<FilterNodeRecord> filterRecords = new ArrayList<>(block.whereFilters.size());
            int filterBaseRank = block.joins.size() + 1;
            for (int fi = 0; fi < block.whereFilters.size(); fi++) {
                WhereFilterModel wf = block.whereFilters.get(fi);
                String fNodeId = "n-" + block.queryBlockId + "-filter-" + (fi + 1);
                blockNodeIds.add(fNodeId);
                SqlFragment fFrag = addFragment(
                        FragmentKind.WHERE_FILTER, wf.rawText, wf.sourceRange, List.of(fNodeId));

                NodeData fData = new NodeData();
                fData.setLabel("WHERE");
                fData.setSummary(wf.rawText);
                fData.setQueryBlockId(block.queryBlockId);
                fData.setFragmentId(fFrag.getFragmentId());

                int fRank = filterBaseRank + fi;
                maxRankInBlock = Math.max(maxRankInBlock, fRank);
                JoinAnalysisNode fNode = new JoinAnalysisNode(
                        fNodeId, JoinNodeType.FILTER, computePosition(fRank, 0), fData);
                filterRecords.add(new FilterNodeRecord(wf, fNodeId, fFrag.getFragmentId()));
                // Added to ctx.nodes after join nodes so topological order in nodes[] stays natural
                fData.setDetails(fNode);
            }

            // Build join chain
            String currentStreamNodeId = block.tables.isEmpty()
                    ? null
                    : blockTableNodes.get(block.tables.get(0).instanceId).getId();
            JoinNodeType currentStreamType = block.tables.isEmpty()
                    ? null
                    : blockTableNodes.get(block.tables.get(0).instanceId).getType();

            Map<String, TableRefModel> seenCanonicalTables = new LinkedHashMap<>();
            if (!block.tables.isEmpty() && !block.tables.get(0).isSubquery) {
                TableRefModel first = block.tables.get(0);
                seenCanonicalTables.put(first.canonicalTableIdentity(), first);
            }

            List<JoinNodeRecord> joinRecords = new ArrayList<>(block.joins.size());
            for (int ji = 0; ji < block.joins.size(); ji++) {
                JoinStepModel step = block.joins.get(ji);
                int joinRank = ji + 1;
                maxRankInBlock = Math.max(maxRankInBlock, joinRank);

                // Place right table directly feeding this join at (joinRank - 1, row = 1)
                JoinAnalysisNode rightTableNode = blockTableNodes.get(step.rightTable.instanceId);
                if (rightTableNode != null) {
                    int rightTableRank = Math.max(0, joinRank - 1);
                    int rightTableRow = Math.max(1, (rightTableRank == 0 ? standaloneTableRow++ : 1));
                    rightTableNode.setPosition(computePosition(rightTableRank, rightTableRow));
                    maxRowInBlock = Math.max(maxRowInBlock, rightTableRow);
                }

                String jNodeId = "n-" + block.queryBlockId + "-join-" + step.order;
                blockNodeIds.add(jNodeId);

                SqlFragment jFrag = addFragment(
                        FragmentKind.JOIN_CLAUSE, step.rawJoinSql, step.joinRange, List.of(jNodeId));
                SqlFragment condFrag = null;
                if (step.rawConditionSql != null && step.conditionRange != null) {
                    condFrag = addFragment(
                            FragmentKind.JOIN_CONDITION,
                            step.rawConditionSql,
                            step.conditionRange,
                            List.of(jNodeId));
                }

                List<JoinPattern> patterns = new ArrayList<>();
                List<JoinBadge> badges = new ArrayList<>();

                // Check self-join against already-seen tables in this block
                TableRefModel priorSameTable = null;
                if (!step.rightTable.isSubquery) {
                    String canon = step.rightTable.canonicalTableIdentity();
                    priorSameTable = seenCanonicalTables.get(canon);
                    seenCanonicalTables.putIfAbsent(canon, step.rightTable);
                }
                boolean isSelfJoinStep = priorSameTable != null;
                if (isSelfJoinStep) {
                    patterns.add(JoinPattern.SELF);
                    badges.add(new JoinBadge(BadgeKind.SELF_JOIN, null, false));
                }
                if (step.explicitSemi) {
                    patterns.add(JoinPattern.SEMI);
                    badges.add(new JoinBadge(BadgeKind.SEMI_JOIN, FanOutLevel.LOW, false));
                }
                if (step.explicitAnti) {
                    patterns.add(JoinPattern.ANTI);
                    badges.add(new JoinBadge(BadgeKind.ANTI_JOIN, FanOutLevel.LOW, false));
                }

                // Resolve predicates & column keys
                List<JoinPredicate> wirePreds = new ArrayList<>(step.predicates.size());
                List<ResolvedColKey> stepLeftKeys = new ArrayList<>();
                List<ResolvedColKey> stepRightKeys = new ArrayList<>();
                List<ColumnContribution> leftEdgeCols = new ArrayList<>();
                List<ColumnContribution> rightEdgeCols = new ArrayList<>();
                List<JoinPredicate> complexPreds = new ArrayList<>();
                List<JoinPredicate> unparsedPreds = new ArrayList<>();
                List<ResolvedColKey> unindexedCatalogKeys = new ArrayList<>();

                boolean anyCrossTableColumnLink = false;
                boolean anyUnparsed = false;

                for (ParsedPredicate pp : step.predicates) {
                    String predId = "p-" + (++predSeq);
                    addFragment(FragmentKind.JOIN_PREDICATE, pp.raw, pp.sourceRange, List.of(jNodeId));

                    ResolvedColKey rLeft = pp.left != null
                            ? resolveColumnRef(block, step, pp.left, true)
                            : null;
                    ResolvedColKey rRight = pp.right != null
                            ? resolveColumnRef(block, step, pp.right, false)
                            : null;

                    JoinPredicate wp = new JoinPredicate();
                    wp.setPredicateId(predId);
                    wp.setRaw(pp.raw);
                    wp.setKind(pp.kind);
                    wp.setOp(pp.op);
                    if (rLeft != null) {
                        wp.setLeft(rLeft.columnRef);
                        allKeys.add(rLeft.columnRef);
                    }
                    if (rRight != null) {
                        wp.setRight(rRight.columnRef);
                        allKeys.add(rRight.columnRef);
                    }
                    wp.setSourceRange(pp.sourceRange);
                    wirePreds.add(wp);
                    allPredicates.add(wp);

                    if (pp.unparsed || pp.kind == PredicateKind.UNKNOWN) {
                        anyUnparsed = true;
                        unparsedPreds.add(wp);
                    } else if (pp.complex) {
                        complexPreds.add(wp);
                    }

                    // Determine if this predicate links left scope and right table
                    boolean touchesLeft = false;
                    boolean touchesRight = false;
                    for (ParsedColRef cr : pp.allColRefs) {
                        TableRefModel owner = findOwnerTable(block, step, cr, true);
                        if (owner != null) {
                            if (owner.instanceId.equals(step.rightTable.instanceId)) {
                                touchesRight = true;
                                rightEdgeCols.add(new ColumnContribution(owner.name, cr.column));
                            } else {
                                touchesLeft = true;
                                leftEdgeCols.add(new ColumnContribution(owner.name, cr.column));
                            }
                        }
                    }
                    if (touchesLeft && touchesRight) {
                        anyCrossTableColumnLink = true;
                    }

                    // Orient equi-join keys to left/right sides for cardinality & index checks
                    if (rLeft != null && rRight != null && !pp.unparsed) {
                        ResolvedColKey actualLeftSide = null;
                        ResolvedColKey actualRightSide = null;
                        if (rLeft.ownerTable != null
                                && rLeft.ownerTable.instanceId.equals(step.rightTable.instanceId)) {
                            actualRightSide = rLeft;
                        } else {
                            actualLeftSide = rLeft;
                        }
                        if (rRight.ownerTable != null
                                && rRight.ownerTable.instanceId.equals(step.rightTable.instanceId)) {
                            actualRightSide = rRight;
                        } else if (actualLeftSide == null) {
                            actualLeftSide = rRight;
                        } else if (actualRightSide == null) {
                            actualRightSide = rRight;
                        }

                        if (pp.kind == PredicateKind.EQUI && actualLeftSide != null && actualRightSide != null) {
                            stepLeftKeys.add(actualLeftSide);
                            stepRightKeys.add(actualRightSide);
                        }
                        if (actualLeftSide != null
                                && actualLeftSide.catalogBacked
                                && !Boolean.TRUE.equals(actualLeftSide.columnRef.getIsIndexed())) {
                            unindexedCatalogKeys.add(actualLeftSide);
                        }
                        if (actualRightSide != null
                                && actualRightSide.catalogBacked
                                && !Boolean.TRUE.equals(actualRightSide.columnRef.getIsIndexed())) {
                            unindexedCatalogKeys.add(actualRightSide);
                        }
                    }
                }

                // Detect Cartesian join
                boolean isCartesian = !step.missingCondition
                        && !step.explicitSemi
                        && !step.explicitAnti
                        && step.clause != JoinClause.NATURAL
                        && step.clause != JoinClause.USING
                        && !anyUnparsed
                        && (step.joinType == JoinType.CROSS
                                || (step.implicitCommaJoin && step.predicates.isEmpty())
                                || (!step.predicates.isEmpty() && !anyCrossTableColumnLink));

                if (isCartesian) {
                    patterns.add(JoinPattern.CARTESIAN);
                    badges.add(new JoinBadge(
                            step.joinType == JoinType.CROSS ? BadgeKind.CROSS_JOIN : BadgeKind.CARTESIAN_RISK,
                            FanOutLevel.HIGH,
                            false));
                }

                // Estimate CardinalityHint & FanOutLevel
                boolean usedHeuristicCardinality = catalogMeta.isEmpty();
                CardinalityHint cardinalityHint = inferCardinality(
                        step, isCartesian, stepLeftKeys, stepRightKeys);
                FanOutLevel fanOut = inferFanOut(cardinalityHint, isCartesian, step);
                if (!step.explicitSemi && !step.explicitAnti && !isCartesian) {
                    badges.add(new JoinBadge(BadgeKind.FAN_OUT, fanOut, usedHeuristicCardinality));
                }

                JoinDetail detail = new JoinDetail();
                detail.setJoinType(step.joinType);
                detail.setPatterns(patterns);
                detail.setClause(step.clause);
                detail.setPredicates(wirePreds);
                detail.setOrder(step.order);
                detail.setCardinalityHint(cardinalityHint);
                detail.setSourceRange(step.joinRange);
                allJoins.add(detail);

                CardinalityItem cardItem = new CardinalityItem();
                cardItem.setJoinId(jNodeId);
                cardItem.setCardinality(cardinalityHint);
                cardItem.setFanOutRisk(fanOut);
                cardItem.setReason(describeCardinalityReason(cardinalityHint, isCartesian, usedHeuristicCardinality));
                allCardinalities.add(cardItem);

                NodeData jNodeData = new NodeData();
                jNodeData.setLabel(formatJoinLabel(step));
                jNodeData.setSummary(step.rawConditionSql != null
                        ? step.rawConditionSql
                        : step.clause.getWire());
                jNodeData.setQueryBlockId(block.queryBlockId);
                jNodeData.setFragmentId(jFrag.getFragmentId());
                jNodeData.setJoin(detail);
                jNodeData.setBadges(badges);

                JoinAnalysisNode jNode = new JoinAnalysisNode(
                        jNodeId, JoinNodeType.JOIN, computePosition(joinRank, 0), jNodeData);
                nodes.add(jNode);

                // Input edges into Join node
                String leftEdgeId = null;
                if (currentStreamNodeId != null) {
                    EdgeData led = new EdgeData();
                    led.setKind(currentStreamType == JoinNodeType.JOIN
                            ? JoinEdgeKind.JOIN_TO_JOIN
                            : JoinEdgeKind.TABLE_TO_JOIN);
                    if (!leftEdgeCols.isEmpty()) {
                        led.setColumns(leftEdgeCols);
                    }
                    led.setNote("left input");
                    led.setDashed(step.joinType == JoinType.RIGHT || step.joinType == JoinType.FULL);
                    led.setAnimated(false);
                    leftEdgeId = addEdge(currentStreamNodeId, jNodeId, led).getId();
                }

                String rightEdgeId = null;
                if (rightTableNode != null) {
                    EdgeData red = new EdgeData();
                    red.setKind((step.subquerySemi || step.subqueryAnti)
                            ? JoinEdgeKind.CORRELATION
                            : JoinEdgeKind.TABLE_TO_JOIN);
                    if (!rightEdgeCols.isEmpty()) {
                        red.setColumns(rightEdgeCols);
                    }
                    red.setNote("right input");
                    red.setDashed(step.joinType == JoinType.LEFT
                            || step.joinType == JoinType.FULL
                            || step.explicitAnti);
                    red.setAnimated(false);
                    rightEdgeId = addEdge(rightTableNode.getId(), jNodeId, red).getId();
                }

                currentStreamNodeId = jNodeId;
                currentStreamType = JoinNodeType.JOIN;

                JoinNodeRecord jRec = new JoinNodeRecord(
                        step,
                        jNodeId,
                        jFrag.getFragmentId(),
                        condFrag != null ? condFrag.getFragmentId() : jFrag.getFragmentId(),
                        leftEdgeId,
                        rightEdgeId,
                        detail,
                        badges,
                        patterns);
                joinRecords.add(jRec);

                // Emit per-step diagnostics (Rules 1, 2, 3, 5-explicit, 6, 7, 8, 9, 10)
                emitStepDiagnostics(
                        block,
                        jRec,
                        priorSameTable,
                        blockTableNodes,
                        blockTableFragIds,
                        isCartesian,
                        cardinalityHint,
                        stepLeftKeys,
                        stepRightKeys,
                        unindexedCatalogKeys,
                        complexPreds,
                        unparsedPreds);
            }

            // Append WHERE filter nodes & evaluate outer-join WHERE interactions (Rules 4 & 5-outer-null)
            for (FilterNodeRecord fRec : filterRecords) {
                JoinAnalysisNode fNode = (JoinAnalysisNode) nodesOrExtracted(fRec);
                nodes.add(fNode);
                if (currentStreamNodeId != null) {
                    EdgeData fed = new EdgeData();
                    fed.setKind(JoinEdgeKind.DATA_FLOW);
                    fed.setDashed(false);
                    fed.setAnimated(false);
                    addEdge(currentStreamNodeId, fRec.nodeId, fed);
                }
                currentStreamNodeId = fRec.nodeId;
                currentStreamType = JoinNodeType.FILTER;
            }

            evaluateOuterJoinWhereDiagnostics(block, joinRecords, filterRecords, blockTableNodes);

            // Block Output node
            int outRank = maxRankInBlock + 1;
            String outNodeId = "n-" + block.queryBlockId + "-out";
            blockNodeIds.add(outNodeId);
            blockOutputNodeIds.put(block.queryBlockId, outNodeId);

            String outSql = block.selectRawSql != null && !block.selectRawSql.isBlank()
                    ? block.selectRawSql
                    : "SELECT *";
            SourceRange outRange = block.selectRange != null ? block.selectRange : block.sourceRange;
            SqlFragment outFrag = addFragment(
                    FragmentKind.OUTPUT_COLUMN, outSql, outRange, List.of(outNodeId));

            NodeData outData = new NodeData();
            outData.setLabel(block.kind == LaneKind.MAIN ? "Query Output" : block.label + " Output");
            outData.setSummary(outSql);
            outData.setQueryBlockId(block.queryBlockId);
            outData.setFragmentId(outFrag.getFragmentId());

            JoinAnalysisNode outNode = new JoinAnalysisNode(
                    outNodeId, JoinNodeType.OUTPUT, computePosition(outRank, 0), outData);
            nodes.add(outNode);

            if (currentStreamNodeId != null) {
                EdgeData oed = new EdgeData();
                oed.setKind(currentStreamType == JoinNodeType.JOIN
                        ? JoinEdgeKind.JOIN_TO_OUTPUT
                        : JoinEdgeKind.DATA_FLOW);
                oed.setDashed(false);
                oed.setAnimated(false);
                addEdge(currentStreamNodeId, outNodeId, oed);
            }

            blockFrag.setNodeIds(blockNodeIds);

            // Advance lane offset for next QueryBlockLane
            if (direction == LayoutDirection.RIGHT) {
                double laneHeight = (maxRowInBlock + 1) * (NODE_HEIGHT + DEFAULT_NODE_SPACING);
                currentLaneOffset += laneHeight + LANE_GAP;
            } else {
                double laneWidth = (maxRowInBlock + 1) * (NODE_WIDTH + DEFAULT_NODE_SPACING);
                currentLaneOffset += laneWidth + LANE_GAP;
            }
        }

        private JoinAnalysisNode nodesOrExtracted(FilterNodeRecord fRec) {
            // Unwrap temporary JoinAnalysisNode stashed during pre-creation and clear details
            for (SqlFragment frag : fragments) {
                if (frag.getFragmentId().equals(fRec.fragmentId)) {
                    break;
                }
            }
            return buildFilterNodeClean(fRec);
        }

        private JoinAnalysisNode buildFilterNodeClean(FilterNodeRecord fRec) {
            // Re-extract rank cleanly from fRec sequence
            int idx = Integer.parseInt(fRec.nodeId.substring(fRec.nodeId.lastIndexOf('-') + 1));
            NodeData fData = new NodeData();
            fData.setLabel("WHERE");
            fData.setSummary(fRec.model.rawText);
            String qbId = fRec.nodeId.substring(2, fRec.nodeId.indexOf("-filter-"));
            fData.setQueryBlockId(qbId);
            fData.setFragmentId(fRec.fragmentId);
            int joinCountForQb = 0;
            for (JoinAnalysisNode n : nodes) {
                if (n.getType() == JoinNodeType.JOIN
                        && qbId.equals(n.getData().getQueryBlockId())) {
                    joinCountForQb++;
                }
            }
            return new JoinAnalysisNode(
                    fRec.nodeId,
                    JoinNodeType.FILTER,
                    computePosition(joinCountForQb + idx, 0),
                    fData);
        }

        void wireCrossBlockEdges(List<QueryBlockModel> blocks) {
            for (QueryBlockModel block : blocks) {
                for (TableRefModel tr : block.tables) {
                    String targetNodeId = tableInstanceNodeIds.get(tr.instanceId);
                    if (targetNodeId == null) {
                        continue;
                    }
                    String upstreamBlockId = tr.isCteRef
                            ? tr.referencedCteBlockId
                            : (tr.isSubquery ? tr.subqueryBlockId : null);
                    if (upstreamBlockId != null) {
                        String sourceOutId = blockOutputNodeIds.get(upstreamBlockId);
                        if (sourceOutId != null) {
                            EdgeData ed = new EdgeData();
                            ed.setKind(JoinEdgeKind.LINEAGE);
                            ed.setNote(tr.isCteRef ? "CTE reference" : "Subquery feed");
                            ed.setDashed(true);
                            ed.setAnimated(false);
                            addEdge(sourceOutId, targetNodeId, ed);
                        }
                    }
                }
            }
        }

        private void emitStepDiagnostics(
                QueryBlockModel block,
                JoinNodeRecord jRec,
                TableRefModel priorSameTable,
                Map<String, JoinAnalysisNode> blockTableNodes,
                Map<String, String> blockTableFragIds,
                boolean isCartesian,
                CardinalityHint cardinalityHint,
                List<ResolvedColKey> stepLeftKeys,
                List<ResolvedColKey> stepRightKeys,
                List<ResolvedColKey> unindexedCatalogKeys,
                List<JoinPredicate> complexPreds,
                List<JoinPredicate> unparsedPreds) {
            JoinStepModel step = jRec.step;
            List<String> stepEdges = nonNullList(jRec.leftInputEdgeId, jRec.rightInputEdgeId);

            // 1. MISSING_JOIN_CONDITION (BLOCK)
            if (step.missingCondition) {
                addDiagnostic(
                        DiagnosticCode.MISSING_JOIN_CONDITION,
                        List.of(Role.ALL),
                        "Join clause is missing an ON or USING condition.",
                        List.of(jRec.joinNodeId),
                        List.of(jRec.joinFragmentId),
                        List.of(),
                        stepEdges);
            }

            // 2. CARTESIAN_JOIN (BLOCK)
            if (isCartesian) {
                addDiagnostic(
                        DiagnosticCode.CARTESIAN_JOIN,
                        List.of(Role.ALL),
                        "This join has no condition linking its two sides, producing every row combination (a cartesian product).",
                        List.of(jRec.joinNodeId),
                        List.of(jRec.joinFragmentId),
                        wirePredIds(jRec.joinDetail.getPredicates()),
                        stepEdges);
            }

            // 3. MANY_TO_MANY_RISK (WARN)
            if (!isCartesian
                    && !step.missingCondition
                    && !step.explicitSemi
                    && !step.explicitAnti
                    && cardinalityHint == CardinalityHint.MANY_TO_MANY
                    && !stepLeftKeys.isEmpty()
                    && !stepRightKeys.isEmpty()) {
                addDiagnostic(
                        DiagnosticCode.MANY_TO_MANY_RISK,
                        List.of(Role.DATA_ENGINEER, Role.DATA_ANALYST),
                        "Both sides of this join key are non-unique, so the join can multiply rows (a many-to-many fan-out).",
                        List.of(jRec.joinNodeId),
                        List.of(jRec.conditionFragmentId),
                        wirePredIds(jRec.joinDetail.getPredicates()),
                        stepEdges);
            }

            // 5a. ANTI_JOIN_DETECTED (INFO) - explicit ANTI JOIN / NOT IN / NOT EXISTS
            if (step.explicitAnti) {
                String msg = step.subqueryAnti
                        ? "This join is an anti-join (NOT IN / NOT EXISTS): it returns rows from the outer table only where no match exists in the subquery."
                        : "Explicit ANTI JOIN returns rows from the left side only where no matching row exists on the right side.";
                addDiagnostic(
                        DiagnosticCode.ANTI_JOIN_DETECTED,
                        List.of(Role.ALL),
                        msg,
                        List.of(jRec.joinNodeId),
                        List.of(jRec.joinFragmentId),
                        wirePredIds(jRec.joinDetail.getPredicates()),
                        stepEdges);
            }

            // 6. SELF_JOIN_DETECTED (INFO)
            if (priorSameTable != null) {
                List<String> evNodes = new ArrayList<>(3);
                JoinAnalysisNode n1 = blockTableNodes.get(priorSameTable.instanceId);
                JoinAnalysisNode n2 = blockTableNodes.get(step.rightTable.instanceId);
                if (n1 != null) {
                    evNodes.add(n1.getId());
                }
                if (n2 != null) {
                    evNodes.add(n2.getId());
                }
                evNodes.add(jRec.joinNodeId);

                List<String> evFrags = new ArrayList<>(3);
                if (blockTableFragIds.get(priorSameTable.instanceId) != null) {
                    evFrags.add(blockTableFragIds.get(priorSameTable.instanceId));
                }
                if (blockTableFragIds.get(step.rightTable.instanceId) != null) {
                    evFrags.add(blockTableFragIds.get(step.rightTable.instanceId));
                }
                evFrags.add(jRec.joinFragmentId);

                addDiagnostic(
                        DiagnosticCode.SELF_JOIN_DETECTED,
                        List.of(Role.ALL),
                        "The same table is joined to itself under two aliases.",
                        evNodes,
                        evFrags,
                        wirePredIds(jRec.joinDetail.getPredicates()),
                        stepEdges);
            }

            // 7. SEMI_JOIN_DETECTED (INFO)
            if (step.explicitSemi) {
                addDiagnostic(
                        DiagnosticCode.SEMI_JOIN_DETECTED,
                        List.of(Role.ALL),
                        "This join is a semi-join (IN / EXISTS): it returns rows from the outer table only where a match exists in the subquery, without multiplying rows.",
                        List.of(jRec.joinNodeId),
                        List.of(jRec.joinFragmentId),
                        wirePredIds(jRec.joinDetail.getPredicates()),
                        stepEdges);
            }

            // 8. NON_INDEXED_JOIN_KEY (WARN)
            if (!unindexedCatalogKeys.isEmpty()) {
                Set<String> seenUnindexedCols = new LinkedHashSet<>();
                List<String> evNodes = new ArrayList<>();
                evNodes.add(jRec.joinNodeId);
                for (ResolvedColKey rk : unindexedCatalogKeys) {
                    String keySig = rk.columnRef.getTableName() + "." + rk.columnRef.getColumn();
                    seenUnindexedCols.add(keySig);
                    if (rk.ownerTable != null) {
                        JoinAnalysisNode tn = blockTableNodes.get(rk.ownerTable.instanceId);
                        if (tn != null && !evNodes.contains(tn.getId())) {
                            evNodes.add(tn.getId());
                        }
                    }
                }
                addDiagnostic(
                        DiagnosticCode.NON_INDEXED_JOIN_KEY,
                        List.of(Role.DBA, Role.DATA_ENGINEER),
                        "A column used as a join key is not covered by an index, which can make this join slow. ("
                                + String.join(", ", seenUnindexedCols) + ")",
                        evNodes,
                        List.of(jRec.conditionFragmentId),
                        wirePredIds(jRec.joinDetail.getPredicates()),
                        stepEdges);
            }

            // 9. COMPLEX_PREDICATE (WARN)
            if (!complexPreds.isEmpty()) {
                addDiagnostic(
                        DiagnosticCode.COMPLEX_PREDICATE,
                        List.of(Role.DATA_ENGINEER, Role.DBA),
                        "This join uses a predicate that could not be decomposed into a simple column comparison.",
                        List.of(jRec.joinNodeId),
                        List.of(jRec.conditionFragmentId),
                        wirePredIds(complexPreds),
                        stepEdges);
            }

            // 10. UNPARSED_CONDITION (WARN)
            if (!unparsedPreds.isEmpty()) {
                addDiagnostic(
                        DiagnosticCode.UNPARSED_CONDITION,
                        List.of(Role.ALL),
                        "Join condition contains unparsed syntax or template expressions: "
                                + unparsedPreds.get(0).getRaw(),
                        List.of(jRec.joinNodeId),
                        List.of(jRec.conditionFragmentId),
                        wirePredIds(unparsedPreds),
                        stepEdges);
            }
        }

        private void evaluateOuterJoinWhereDiagnostics(
                QueryBlockModel block,
                List<JoinNodeRecord> joinRecords,
                List<FilterNodeRecord> filterRecords,
                Map<String, JoinAnalysisNode> blockTableNodes) {
            if (joinRecords.isEmpty() || filterRecords.isEmpty()) {
                return;
            }

            for (JoinNodeRecord jRec : joinRecords) {
                JoinStepModel step = jRec.step;
                JoinType jt = step.joinType;
                if (jt != JoinType.LEFT && jt != JoinType.RIGHT && jt != JoinType.FULL) {
                    continue;
                }
                if (step.explicitAnti || step.explicitSemi) {
                    continue;
                }

                for (FilterNodeRecord fRec : filterRecords) {
                    WhereFilterModel wf = fRec.model;

                    // Check if wf touches the null-producing side of this outer join
                    TableRefModel nullSideMatched = findNullProducingSideTable(block, step, wf.colRefs);
                    if (nullSideMatched == null) {
                        continue;
                    }

                    // Case A: WHERE <null_side_col> IS NULL -> ANTI_JOIN_DETECTED!
                    if (wf.isNullCheckOnly && wf.nullCheckedCol != null) {
                        TableRefModel nullColOwner = findNullProducingSideTable(
                                block, step, List.of(wf.nullCheckedCol));
                        if (nullColOwner != null) {
                            if (!jRec.patterns.contains(JoinPattern.ANTI)) {
                                jRec.patterns.add(JoinPattern.ANTI);
                                jRec.badges.add(new JoinBadge(BadgeKind.ANTI_JOIN, FanOutLevel.LOW, false));
                            }
                            EdgeData aed = new EdgeData();
                            aed.setKind(JoinEdgeKind.ANTI_JOIN_EVIDENCE);
                            aed.setColumns(List.of(
                                    new ColumnContribution(nullColOwner.name, wf.nullCheckedCol.column)));
                            aed.setNote("IS NULL anti-join filter");
                            aed.setDashed(true);
                            aed.setAnimated(false);
                            JoinAnalysisEdge evEdge = addEdge(fRec.nodeId, jRec.joinNodeId, aed);

                            List<String> evNodes = new ArrayList<>();
                            evNodes.add(jRec.joinNodeId);
                            evNodes.add(fRec.nodeId);
                            JoinAnalysisNode nullTblNode = blockTableNodes.get(nullColOwner.instanceId);
                            if (nullTblNode != null) {
                                evNodes.add(nullTblNode.getId());
                            }

                            addDiagnostic(
                                    DiagnosticCode.ANTI_JOIN_DETECTED,
                                    List.of(Role.ALL),
                                    "An outer join filtered by IS NULL on the join key keeps only rows with no match (an anti-join).",
                                    evNodes,
                                    List.of(jRec.joinFragmentId, fRec.fragmentId),
                                    wirePredIds(jRec.joinDetail.getPredicates()),
                                    List.of(evEdge.getId()));
                            continue;
                        }
                    }

                    // Case B: Null-rejecting WHERE filter on null-producing side -> OUTER_JOIN_FILTER_RISK!
                    if (wf.nullRejecting) {
                        List<String> evNodes = new ArrayList<>();
                        evNodes.add(jRec.joinNodeId);
                        evNodes.add(fRec.nodeId);
                        JoinAnalysisNode nullTblNode = blockTableNodes.get(nullSideMatched.instanceId);
                        if (nullTblNode != null) {
                            evNodes.add(nullTblNode.getId());
                        }

                        addDiagnostic(
                                DiagnosticCode.OUTER_JOIN_FILTER_RISK,
                                List.of(Role.DATA_ENGINEER, Role.DATA_ANALYST),
                                "A WHERE filter on the null-producing side of this outer join discards the unmatched rows, silently turning it into an inner join.",
                                evNodes,
                                List.of(jRec.joinFragmentId, fRec.fragmentId),
                                wirePredIds(jRec.joinDetail.getPredicates()),
                                nonNullList(jRec.rightInputEdgeId, jRec.leftInputEdgeId));
                    }
                }
            }
        }

        private TableRefModel findNullProducingSideTable(
                QueryBlockModel block,
                JoinStepModel step,
                List<ParsedColRef> refs) {
            for (ParsedColRef ref : refs) {
                TableRefModel owner = resolveTableInBlock(block, ref);
                if (owner == null) {
                    continue;
                }
                if (step.joinType == JoinType.LEFT || step.joinType == JoinType.FULL) {
                    if (owner.instanceId.equals(step.rightTable.instanceId)) {
                        return owner;
                    }
                }
                if (step.joinType == JoinType.RIGHT || step.joinType == JoinType.FULL) {
                    if (!owner.instanceId.equals(step.rightTable.instanceId)
                            && (step.leftScopeKeys.contains(owner.effectiveLookupKey())
                                    || owner.instanceId.equals(step.leftPrimaryTable.instanceId))) {
                        return owner;
                    }
                }
            }
            return null;
        }

        private ResolvedColKey resolveColumnRef(
                QueryBlockModel block,
                JoinStepModel step,
                ParsedColRef parsed,
                boolean preferLeftWhenUnqualified) {
            TableRefModel owner = findOwnerTable(block, step, parsed, preferLeftWhenUnqualified);
            ColumnRef cr = new ColumnRef();
            cr.setColumn(parsed.column);
            cr.setDisplayName(parsed.rawDisplay);
            cr.setSourceRange(parsed.sourceRange);

            boolean catalogBacked = false;
            boolean pk = false;
            boolean uk = false;
            boolean indexed = false;

            if (owner != null) {
                cr.setTableId(owner.instanceId);
                cr.setTableName(owner.name);
                cr.setAlias(owner.alias);

                TableMeta tm = (!owner.isSubquery && !owner.isCteRef)
                        ? catalogMeta.findTable(owner.database, owner.schema, owner.name)
                        : null;
                if (tm != null) {
                    catalogBacked = true;
                    ColumnMeta cm = tm.findColumn(parsed.column);
                    if (cm != null) {
                        pk = cm.isPrimaryKey();
                        uk = cm.isUniqueKey() || pk;
                        indexed = cm.isIndexed() || pk || uk;
                    }
                } else if (!owner.isSubquery && !owner.isCteRef) {
                    boolean heurPk = isHeuristicPrimaryKeyColumn(owner.name, parsed.column);
                    pk = heurPk;
                    uk = heurPk;
                    indexed = heurPk;
                }
            } else if (parsed.qualifier != null) {
                cr.setAlias(parsed.qualifier);
                cr.setTableName(parsed.qualifier);
                boolean heurPk = isHeuristicPrimaryKeyColumn(parsed.qualifier, parsed.column);
                pk = heurPk;
                uk = heurPk;
                indexed = heurPk;
            }

            cr.setIsPrimaryKey(pk);
            cr.setIsUniqueKey(uk);
            cr.setIsIndexed(indexed);
            return new ResolvedColKey(cr, owner, catalogBacked);
        }

        private TableRefModel findOwnerTable(
                QueryBlockModel block,
                JoinStepModel step,
                ParsedColRef ref,
                boolean preferLeftWhenUnqualified) {
            TableRefModel exact = resolveTableInBlock(block, ref);
            if (exact != null) {
                return exact;
            }
            if (ref.qualifier == null) {
                return preferLeftWhenUnqualified ? step.leftPrimaryTable : step.rightTable;
            }
            return null;
        }

        private TableRefModel resolveTableInBlock(QueryBlockModel block, ParsedColRef ref) {
            if (ref.qualifier != null && !ref.qualifier.isBlank()) {
                String normQ = DataFlowIndex.normalizeIdentifier(ref.qualifier);
                // 1. Exact match on effective lookup key (alias if aliased, else table name)
                for (TableRefModel tr : block.tables) {
                    if (tr.effectiveLookupKey().equals(normQ)) {
                        return tr;
                    }
                }
                // 2. Fallback to unaliased table name when unambiguous
                for (TableRefModel tr : block.tables) {
                    if ((tr.alias == null || tr.alias.isBlank())
                            && DataFlowIndex.normalizeIdentifier(tr.name).equals(normQ)) {
                        return tr;
                    }
                }
                for (TableRefModel tr : block.tables) {
                    if (!tr.selfJoinInstance
                            && DataFlowIndex.normalizeIdentifier(tr.name).equals(normQ)) {
                        return tr;
                    }
                }
                return null;
            }

            // Unqualified column: check catalog metadata first
            TableRefModel catalogMatch = null;
            int matchCount = 0;
            for (TableRefModel tr : block.tables) {
                TableMeta tm = (!tr.isSubquery && !tr.isCteRef)
                        ? catalogMeta.findTable(tr.database, tr.schema, tr.name)
                        : null;
                if (tm != null && tm.findColumn(ref.column) != null) {
                    catalogMatch = tr;
                    matchCount++;
                }
            }
            if (matchCount == 1) {
                return catalogMatch;
            }
            if (block.tables.size() == 1) {
                return block.tables.get(0);
            }
            return null;
        }

        private static boolean isHeuristicPrimaryKeyColumn(String tableName, String columnName) {
            if (tableName == null || columnName == null) {
                return false;
            }
            String col = DataFlowIndex.normalizeIdentifier(columnName);
            if ("id".equals(col)) {
                return true;
            }
            String tbl = DataFlowIndex.normalizeIdentifier(tableName);
            if (col.equals(tbl + "_id") || col.equals(tbl + "id")) {
                return true;
            }
            String singular = singularize(tbl);
            return col.equals(singular + "_id") || col.equals(singular + "id");
        }

        private static String singularize(String tbl) {
            if (tbl.endsWith("ies") && tbl.length() > 3) {
                return tbl.substring(0, tbl.length() - 3) + "y";
            }
            if ((tbl.endsWith("ses") || tbl.endsWith("xes") || tbl.endsWith("zes")
                    || tbl.endsWith("ches") || tbl.endsWith("shes")) && tbl.length() > 3) {
                return tbl.substring(0, tbl.length() - 2);
            }
            if (tbl.endsWith("s") && !tbl.endsWith("ss") && tbl.length() > 1) {
                return tbl.substring(0, tbl.length() - 1);
            }
            return tbl;
        }

        private static CardinalityHint inferCardinality(
                JoinStepModel step,
                boolean isCartesian,
                List<ResolvedColKey> leftKeys,
                List<ResolvedColKey> rightKeys) {
            if (isCartesian) {
                return CardinalityHint.MANY_TO_MANY;
            }
            if (step.explicitSemi || step.explicitAnti) {
                return CardinalityHint.ONE_TO_ONE;
            }
            if (step.missingCondition || leftKeys.isEmpty() || rightKeys.isEmpty()) {
                return CardinalityHint.UNKNOWN;
            }
            boolean leftUnique = leftKeys.stream()
                    .anyMatch(k -> Boolean.TRUE.equals(k.columnRef.getIsUniqueKey()));
            boolean rightUnique = rightKeys.stream()
                    .anyMatch(k -> Boolean.TRUE.equals(k.columnRef.getIsUniqueKey()));

            if (leftUnique && rightUnique) {
                return CardinalityHint.ONE_TO_ONE;
            }
            if (leftUnique) {
                return CardinalityHint.ONE_TO_MANY;
            }
            if (rightUnique) {
                return CardinalityHint.MANY_TO_ONE;
            }
            return CardinalityHint.MANY_TO_MANY;
        }

        private static FanOutLevel inferFanOut(
                CardinalityHint hint,
                boolean isCartesian,
                JoinStepModel step) {
            if (isCartesian || step.missingCondition || hint == CardinalityHint.MANY_TO_MANY) {
                return FanOutLevel.HIGH;
            }
            if (hint == CardinalityHint.ONE_TO_MANY || hint == CardinalityHint.UNKNOWN) {
                return FanOutLevel.MEDIUM;
            }
            return FanOutLevel.LOW;
        }

        private static String describeCardinalityReason(
                CardinalityHint hint,
                boolean isCartesian,
                boolean heuristic) {
            if (isCartesian) {
                return "Cartesian join without linking column predicate";
            }
            String source = heuristic ? "identifier naming heuristic" : "schema key metadata";
            return switch (hint) {
                case ONE_TO_ONE -> "Both join sides constrained by unique keys (" + source + ")";
                case ONE_TO_MANY -> "Left side unique, right side non-unique (" + source + ")";
                case MANY_TO_ONE -> "Left side non-unique, right side unique (" + source + ")";
                case MANY_TO_MANY -> "Neither join side is uniquely constrained (" + source + ")";
                case UNKNOWN -> "Insufficient equijoin key information";
            };
        }

        private Position computePosition(int rank, int row) {
            if (direction == LayoutDirection.RIGHT) {
                double x = CANVAS_MARGIN + rank * (NODE_WIDTH + DEFAULT_RANK_SPACING);
                double y = currentLaneOffset + row * (NODE_HEIGHT + DEFAULT_NODE_SPACING);
                return new Position(x, y);
            } else {
                double x = currentLaneOffset + row * (NODE_WIDTH + DEFAULT_NODE_SPACING);
                double y = CANVAS_MARGIN + rank * (NODE_HEIGHT + DEFAULT_RANK_SPACING);
                return new Position(x, y);
            }
        }

        private static TableData buildTableData(TableRefModel tr) {
            TableData td = new TableData();
            td.setTableId(tr.instanceId);
            td.setDatabase(tr.database);
            td.setSchema(tr.schema);
            td.setName(tr.name);
            td.setAlias(tr.alias);
            td.setIsSelfJoinInstance(tr.selfJoinInstance);
            List<ColumnContribution> cols = new ArrayList<>(tr.contributedColumns.size());
            for (String c : tr.contributedColumns) {
                cols.add(new ColumnContribution(tr.name, c));
            }
            td.setColumnsContributed(cols);
            return td;
        }

        private static String formatTableLabel(TableRefModel tr) {
            if (tr.alias != null && !tr.alias.isBlank() && !tr.alias.equalsIgnoreCase(tr.name)) {
                return tr.name + " (" + tr.alias + ")";
            }
            return tr.name;
        }

        private static String buildQualSummary(TableRefModel tr) {
            if (tr.isCteRef) {
                return "CTE";
            }
            if (tr.isSubquery) {
                return "Subquery";
            }
            StringBuilder sb = new StringBuilder();
            if (tr.database != null && !tr.database.isBlank()) {
                sb.append(tr.database).append('.');
            }
            if (tr.schema != null && !tr.schema.isBlank()) {
                sb.append(tr.schema).append('.');
            }
            sb.append(tr.name);
            return sb.toString();
        }

        private static String formatJoinLabel(JoinStepModel step) {
            if (step.explicitSemi) {
                return step.subquerySemi ? "SEMI JOIN (IN/EXISTS)" : "LEFT SEMI JOIN";
            }
            if (step.explicitAnti) {
                return step.subqueryAnti ? "ANTI JOIN (NOT IN/EXISTS)" : "LEFT ANTI JOIN";
            }
            if (step.implicitCommaJoin) {
                return step.predicates.isEmpty() ? "COMMA CROSS JOIN" : "IMPLICIT INNER JOIN";
            }
            if (step.clause == JoinClause.NATURAL) {
                return "NATURAL " + step.joinType.getWire() + " JOIN";
            }
            return step.joinType.getWire() + " JOIN";
        }

        private SqlFragment addFragment(
                FragmentKind kind,
                String text,
                SourceRange range,
                List<String> nodeIds) {
            SqlFragment frag = new SqlFragment();
            frag.setFragmentId("frag-" + (++fragSeq));
            frag.setKind(kind);
            frag.setText(text);
            frag.setSourceRange(range);
            frag.setNodeIds(nodeIds);
            fragments.add(frag);
            return frag;
        }

        private JoinAnalysisEdge addEdge(String source, String target, EdgeData data) {
            JoinAnalysisEdge edge = new JoinAnalysisEdge(
                    "e-" + (++edgeSeq), source, target, data);
            edges.add(edge);
            return edge;
        }

        private void addDiagnostic(
                DiagnosticCode code,
                List<Role> roles,
                String message,
                List<String> evidenceNodeIds,
                List<String> fragmentIds,
                List<String> predicateIds,
                List<String> edgeIds) {
            JoinDiagnostic d = new JoinDiagnostic();
            d.setId("diag-" + (++diagSeq));
            d.setCode(code);
            d.setSeverity(code.getDefaultSeverity());
            d.setRoles(roles);
            d.setMessage(message);
            d.setEvidenceNodeIds(evidenceNodeIds);
            d.setFragmentIds(fragmentIds);
            d.setPredicateIds(predicateIds);
            d.setEdgeIds(edgeIds);
            diagnostics.add(d);
        }

        private static List<String> wirePredIds(List<JoinPredicate> preds) {
            if (preds == null || preds.isEmpty()) {
                return List.of();
            }
            List<String> ids = new ArrayList<>(preds.size());
            for (JoinPredicate p : preds) {
                if (p.getPredicateId() != null) {
                    ids.add(p.getPredicateId());
                }
            }
            return ids;
        }

        private static List<String> nonNullList(String... items) {
            List<String> out = new ArrayList<>(items.length);
            for (String s : items) {
                if (s != null) {
                    out.add(s);
                }
            }
            return out;
        }

        JoinSummary buildSummary() {
            JoinSummary s = new JoinSummary();
            s.setTableCount(allTables.size());
            s.setJoinCount(allJoins.size());
            s.setPredicateCount(allPredicates.size());
            s.setDiagnosticCount(diagnostics.size());
            int blocks = 0;
            int warns = 0;
            int infos = 0;
            for (JoinDiagnostic d : diagnostics) {
                if (d.getSeverity() == Severity.BLOCK) {
                    blocks++;
                } else if (d.getSeverity() == Severity.WARN) {
                    warns++;
                } else if (d.getSeverity() == Severity.INFO) {
                    infos++;
                }
            }
            s.setBlockCount(blocks);
            s.setWarnCount(warns);
            s.setInfoCount(infos);
            return s;
        }
    }
}
