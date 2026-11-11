package io.sqlflow.graph.slice;

import io.sqlflow.contracts.graph.LeftMostSourceTable;
import io.sqlflow.contracts.graph.LeftMostSourceTable.SourceTableColumn;
import io.sqlflow.contracts.graph.LeftMostSourceTable.SourceTableRef;
import io.sqlflow.contracts.graph.LeftMostSourceTable.TableColumn;
import io.sqlflow.contracts.graph.LeftMostSourceTable.TargetColumn;
import io.sqlflow.contracts.graph.LeftMostSourceTable.TargetTable;
import io.sqlflow.contracts.graph.LeftMostSourceTable.UpstreamAttributesIndirect;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.Relationship;
import io.sqlflow.contracts.lineage.RelationshipType;
import io.sqlflow.graph.model.CatalogCloneUtil;
import io.sqlflow.graph.model.DataFlowIndex;
import io.sqlflow.graph.model.DataFlowIndex.ColumnEntry;
import io.sqlflow.graph.model.DataFlowIndex.NodeEntry;
import io.sqlflow.graph.model.DataFlowIndex.ResolvedEdge;
import io.sqlflow.graph.model.DataFlowIndex.ResolvedEndpoint;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes transitive root-origin upstream closures ({@link LeftMostSourceTable}) and
 * forward impact reachability closures ({@code computeOutgoingClosure})
 * ({@code OBL-056}, {@code 02 §5.2 & §6.2}, {@code 03 §1.5.4}).
 */
public final class LineageClosureCalculator {

    private record ColKey(String nodeId, String columnId) {
    }

    private record UpstreamWalkStep(
            ResolvedEndpoint endpoint,
            RelationshipType dominantType,
            String clause,
            String timestampMin,
            String timestampMax,
            Set<ColKey> pathVisited) {
    }

    private LineageClosureCalculator() {
    }

    public static LeftMostSourceTable computeLeftMostSourceTable(
            DataFlow dataFlow,
            String targetTableId,
            boolean ignoreRecordSet) {
        LeftMostSourceTable result = new LeftMostSourceTable();
        if (dataFlow == null) {
            result.setElements(List.of());
            result.setTargetTables(List.of());
            result.setAttributes(List.of());
            return result;
        }

        DataFlowIndex index = DataFlowIndex.build(dataFlow);
        List<ResolvedEdge> allEdges = index.resolveEdges(dataFlow.getRelationships(), null);

        // Build incoming adjacency (target -> list of incoming ResolvedEdges)
        Map<ColKey, List<ResolvedEdge>> incomingByCol = new HashMap<>();
        Map<String, List<ResolvedEdge>> incomingByNode = new HashMap<>();
        Set<String> nodesWithIncoming = new LinkedHashSet<>();

        for (ResolvedEdge edge : allEdges) {
            // Skip structural ER/call edges that aren't column/table dataflow unless no fdd/fdr edges exist
            if (edge.relationType() == RelationshipType.join) {
                continue;
            }
            String tgtNodeId = edge.target().node().getId();
            nodesWithIncoming.add(tgtNodeId);
            incomingByNode.computeIfAbsent(tgtNodeId, k -> new ArrayList<>()).add(edge);
            String tgtColId = edge.target().column() != null ? edge.target().column().getId() : null;
            incomingByCol.computeIfAbsent(new ColKey(tgtNodeId, tgtColId), k -> new ArrayList<>()).add(edge);
        }

        List<NodeEntry> targetNodes = selectTargetNodes(index, targetTableId, ignoreRecordSet, nodesWithIncoming);
        List<TargetTable> targetTables = new ArrayList<>(targetNodes.size());
        List<TableColumn> allAttributes = new ArrayList<>();

        for (NodeEntry tgtNode : targetNodes) {
            TargetTable tt = new TargetTable();
            tt.setId(tgtNode.getId());
            tt.setServer(tgtNode.getServer());
            tt.setDatabase(tgtNode.getDatabase());
            tt.setDatabaseName(tgtNode.getDatabase());
            tt.setSchema(tgtNode.getSchema());
            tt.setSchemaName(tgtNode.getSchema());
            tt.setName(tgtNode.getName());
            tt.setRelationName(tgtNode.getName());
            tt.setAlias(tgtNode.getAlias());
            tt.setType(tgtNode.getType());

            List<TargetColumn> targetColumns = new ArrayList<>();
            List<TableColumn> tableRootNodes = new ArrayList<>();
            Set<String> seenTableRootKeys = new HashSet<>();

            for (ColumnEntry tgtCol : tgtNode.getColumns()) {
                if (tgtCol.isSystemRelationRows() || Boolean.FALSE.equals(tgtCol.getUiVisible())) {
                    continue;
                }

                List<ResolvedEdge> directIncoming = getIncomingForColumn(
                        tgtNode.getId(), tgtCol.getId(), incomingByCol);
                if (directIncoming.isEmpty()) {
                    continue;
                }

                Map<String, SourceTableColumn> rootSourceCols = new LinkedHashMap<>();
                Map<String, SourceTableRef> rootSourceRefs = new LinkedHashMap<>();
                Map<String, TableColumn> rootAttrNodes = new LinkedHashMap<>();
                String minTs = null;
                String maxTs = null;

                Deque<UpstreamWalkStep> stack = new ArrayDeque<>();
                ColKey startKey = new ColKey(tgtNode.getId(), tgtCol.getId());
                for (ResolvedEdge first : directIncoming) {
                    if (first.source().node().isConstantTable()) {
                        continue;
                    }
                    Set<ColKey> visited = new HashSet<>();
                    visited.add(startKey);
                    String clause = first.rawSourceElement() != null && first.rawSourceElement().getClauseType() != null
                            ? first.rawSourceElement().getClauseType()
                            : (first.relationship().getClause() != null ? first.relationship().getClause() : "select");
                    stack.push(new UpstreamWalkStep(
                            first.source(),
                            first.relationType(),
                            clause,
                            first.relationship().getTimestampMin(),
                            first.relationship().getTimestampMax(),
                            visited));
                }

                while (!stack.isEmpty()) {
                    UpstreamWalkStep step = stack.pop();
                    if (step.timestampMin() != null && (minTs == null || step.timestampMin().compareTo(minTs) < 0)) {
                        minTs = step.timestampMin();
                    }
                    if (step.timestampMax() != null && (maxTs == null || step.timestampMax().compareTo(maxTs) > 0)) {
                        maxTs = step.timestampMax();
                    }

                    NodeEntry curNode = step.endpoint().node();
                    ColumnEntry curCol = step.endpoint().column();
                    ColKey curKey = new ColKey(curNode.getId(), curCol != null ? curCol.getId() : null);

                    List<ResolvedEdge> upstreamEdges = curCol != null
                            ? getIncomingForColumn(curNode.getId(), curCol.getId(), incomingByCol)
                            : incomingByNode.getOrDefault(curNode.getId(), List.of());

                    List<ResolvedEdge> nonCycleUpstream = new ArrayList<>();
                    for (ResolvedEdge ue : upstreamEdges) {
                        if (ue.source().node().isConstantTable()) {
                            continue;
                        }
                        ColKey nextKey = new ColKey(
                                ue.source().node().getId(),
                                ue.source().column() != null ? ue.source().column().getId() : null);
                        if (!step.pathVisited().contains(nextKey)) {
                            nonCycleUpstream.add(ue);
                        }
                    }

                    if (nonCycleUpstream.isEmpty()) {
                        // Reached a left-most root origin!
                        if (ignoreRecordSet && curNode.isResultSetLike()) {
                            continue;
                        }
                        String dedupKey = curKey.nodeId() + "::" + (curKey.columnId() != null ? curKey.columnId() : "*");
                        SourceTableColumn stc = rootSourceCols.get(dedupKey);
                        if (stc == null) {
                            stc = new SourceTableColumn();
                            stc.setDatabase(curNode.getDatabase());
                            stc.setSchema(curNode.getSchema());
                            stc.setTableName(curNode.getName());
                            stc.setTableAlias(curNode.getAlias());
                            stc.setName(curCol != null ? curCol.getName() : curNode.getName());
                            stc.setType(curNode.getType());
                            stc.setClause(step.clause());
                            stc.setRelationType(step.dominantType().wireName());
                            rootSourceCols.put(dedupKey, stc);
                        } else if ("fdd".equals(step.dominantType().wireName())) {
                            stc.setRelationType("fdd");
                        }

                        SourceTableRef ref = new SourceTableRef();
                        ref.setId(curNode.getId());
                        ref.setName(curNode.getName());
                        ref.setDatabase(curNode.getDatabase());
                        ref.setSchema(curNode.getSchema());
                        if (curCol != null) {
                            ref.setColumnId(curCol.getId());
                            ref.setColumnName(curCol.getName());
                        }
                        rootSourceRefs.putIfAbsent(dedupKey, ref);

                        TableColumn rootTc = toTableColumn(curNode, curCol);
                        rootAttrNodes.putIfAbsent(dedupKey, rootTc);
                        if (seenTableRootKeys.add(dedupKey)) {
                            tableRootNodes.add(rootTc);
                        }
                    } else {
                        for (ResolvedEdge ue : nonCycleUpstream) {
                            ColKey nextKey = new ColKey(
                                    ue.source().node().getId(),
                                    ue.source().column() != null ? ue.source().column().getId() : null);
                            Set<ColKey> nextVisited = new HashSet<>(step.pathVisited());
                            nextVisited.add(nextKey);
                            RelationshipType mergedType = RelationshipType.collapseTransitive(
                                    step.dominantType(), ue.relationType());
                            String nextClause = ue.rawSourceElement() != null
                                            && ue.rawSourceElement().getClauseType() != null
                                    ? ue.rawSourceElement().getClauseType()
                                    : step.clause();
                            stack.push(new UpstreamWalkStep(
                                    ue.source(),
                                    mergedType,
                                    nextClause,
                                    ue.relationship().getTimestampMin() != null
                                            ? ue.relationship().getTimestampMin()
                                            : step.timestampMin(),
                                    ue.relationship().getTimestampMax() != null
                                            ? ue.relationship().getTimestampMax()
                                            : step.timestampMax(),
                                    nextVisited));
                        }
                    }
                }

                if (!rootSourceCols.isEmpty()) {
                    TargetColumn tc = new TargetColumn();
                    tc.setId(tgtCol.getId());
                    tc.setName(tgtCol.getName());
                    tc.setDataType(tgtCol.getDataType());
                    tc.setSourceColumns(new ArrayList<>(rootSourceCols.values()));
                    tc.setSourceTables(new ArrayList<>(rootSourceRefs.values()));
                    targetColumns.add(tc);

                    TableColumn leafAttr = toTableColumn(tgtNode, tgtCol);
                    leafAttr.setTimestampMin(minTs);
                    leafAttr.setTimestampMax(maxTs);
                    leafAttr.setUpstreamAttributesIndirect(
                            new UpstreamAttributesIndirect(new ArrayList<>(rootAttrNodes.values())));
                    allAttributes.add(leafAttr);
                }
            }

            if (!targetColumns.isEmpty() || (targetTableId != null && !targetTableId.isBlank())) {
                tt.setColumns(targetColumns);
                if (!tableRootNodes.isEmpty()) {
                    tt.setUpstreamAttributesIndirect(new UpstreamAttributesIndirect(tableRootNodes));
                }
                targetTables.add(tt);
            }
        }

        result.setElements(targetTables);
        result.setTargetTables(targetTables);
        result.setAttributes(allAttributes);
        return result;
    }

    public static DataFlow computeOutgoingClosure(
            DataFlow dataFlow,
            List<String> rootObjectIds,
            int maxDepth) {
        if (dataFlow == null) {
            DataFlow empty = new DataFlow();
            empty.setRelationships(List.of());
            return empty;
        }
        DataFlowIndex index = DataFlowIndex.build(dataFlow);
        if (rootObjectIds == null || rootObjectIds.isEmpty()) {
            return CatalogCloneUtil.buildSubDataFlow(index, Set.of(), Set.of(), List.of());
        }

        List<String> matchedIds = index.matchNodesOrColumns(rootObjectIds, null, null, null, null);
        if (matchedIds.isEmpty()) {
            return CatalogCloneUtil.buildSubDataFlow(index, Set.of(), Set.of(), List.of());
        }

        int effectiveMaxDepth = maxDepth > 0 ? maxDepth : Integer.MAX_VALUE;
        List<ResolvedEdge> allEdges = index.resolveEdges(dataFlow.getRelationships(), null);

        Map<ColKey, List<ResolvedEdge>> outgoingByCol = new HashMap<>();
        Map<String, List<ResolvedEdge>> outgoingByNode = new HashMap<>();
        for (ResolvedEdge edge : allEdges) {
            String srcNodeId = edge.source().node().getId();
            outgoingByNode.computeIfAbsent(srcNodeId, k -> new ArrayList<>()).add(edge);
            String srcColId = edge.source().column() != null ? edge.source().column().getId() : null;
            outgoingByCol.computeIfAbsent(new ColKey(srcNodeId, srcColId), k -> new ArrayList<>()).add(edge);
        }

        Map<ColKey, Integer> visitedDist = new LinkedHashMap<>();
        Deque<ColKey> queue = new ArrayDeque<>();
        Set<String> retainedNodeIds = new LinkedHashSet<>();
        Set<String> retainedColIds = new LinkedHashSet<>();

        for (String id : matchedIds) {
            ColumnEntry col = index.getColumn(id);
            if (col != null) {
                ColKey ck = new ColKey(col.getParentNodeId(), col.getId());
                if (visitedDist.putIfAbsent(ck, 0) == null) {
                    queue.add(ck);
                }
                retainedNodeIds.add(col.getParentNodeId());
                retainedColIds.add(col.getId());
                continue;
            }
            NodeEntry node = index.getNode(id);
            if (node != null) {
                retainedNodeIds.add(node.getId());
                ColKey nk = new ColKey(node.getId(), null);
                if (visitedDist.putIfAbsent(nk, 0) == null) {
                    queue.add(nk);
                }
                for (ColumnEntry c : node.getColumns()) {
                    retainedColIds.add(c.getId());
                    ColKey ck = new ColKey(node.getId(), c.getId());
                    if (visitedDist.putIfAbsent(ck, 0) == null) {
                        queue.add(ck);
                    }
                }
            }
        }

        Map<ResolvedEdge, Integer> traversed = new LinkedHashMap<>();
        while (!queue.isEmpty()) {
            ColKey cur = queue.poll();
            int dist = visitedDist.get(cur);
            if (dist >= effectiveMaxDepth) {
                continue;
            }

            List<ResolvedEdge> nextEdges;
            if (cur.columnId() != null) {
                List<ResolvedEdge> byCol = outgoingByCol.getOrDefault(cur, List.of());
                List<ResolvedEdge> byTbl = outgoingByCol.getOrDefault(new ColKey(cur.nodeId(), null), List.of());
                if (byTbl.isEmpty()) {
                    nextEdges = byCol;
                } else {
                    nextEdges = new ArrayList<>(byCol.size() + byTbl.size());
                    nextEdges.addAll(byCol);
                    nextEdges.addAll(byTbl);
                }
            } else {
                nextEdges = outgoingByNode.getOrDefault(cur.nodeId(), List.of());
            }

            for (ResolvedEdge edge : nextEdges) {
                int nextDist = dist + 1;
                traversed.merge(edge, nextDist, Math::min);
                String tgtNodeId = edge.target().node().getId();
                String tgtColId = edge.target().column() != null ? edge.target().column().getId() : null;
                retainedNodeIds.add(tgtNodeId);
                if (tgtColId != null) {
                    retainedColIds.add(tgtColId);
                }
                ColKey nextKey = new ColKey(tgtNodeId, tgtColId);
                Integer prev = visitedDist.get(nextKey);
                if (prev == null || nextDist < prev) {
                    visitedDist.put(nextKey, nextDist);
                    queue.add(nextKey);
                }
            }
        }

        List<Relationship> outRels = GraphSlicer.assembleSlicedRelationships(traversed);
        return CatalogCloneUtil.buildSubDataFlow(index, retainedNodeIds, retainedColIds, outRels);
    }

    private static List<NodeEntry> selectTargetNodes(
            DataFlowIndex index,
            String targetTableId,
            boolean ignoreRecordSet,
            Set<String> nodesWithIncoming) {
        if (targetTableId != null && !targetTableId.isBlank()) {
            List<String> matched = index.matchNodesOrColumns(
                    List.of(targetTableId), null, null, targetTableId, null);
            List<NodeEntry> found = new ArrayList<>();
            for (String id : matched) {
                NodeEntry n = index.getNode(id);
                if (n != null && !found.contains(n)) {
                    found.add(n);
                }
            }
            return found;
        }

        List<NodeEntry> candidates = new ArrayList<>();
        List<NodeEntry> fallbackResultSets = new ArrayList<>();
        for (String nId : nodesWithIncoming) {
            NodeEntry node = index.getNode(nId);
            if (node == null || node.isConstantTable()) {
                continue;
            }
            if (node.isResultSetLike()) {
                fallbackResultSets.add(node);
                if (!ignoreRecordSet) {
                    candidates.add(node);
                }
            } else {
                candidates.add(node);
            }
        }
        return candidates.isEmpty() ? fallbackResultSets : candidates;
    }

    private static List<ResolvedEdge> getIncomingForColumn(
            String nodeId,
            String colId,
            Map<ColKey, List<ResolvedEdge>> incomingByCol) {
        List<ResolvedEdge> exact = incomingByCol.getOrDefault(new ColKey(nodeId, colId), List.of());
        List<ResolvedEdge> tableWide = incomingByCol.getOrDefault(new ColKey(nodeId, null), List.of());
        if (tableWide.isEmpty()) {
            return exact;
        }
        List<ResolvedEdge> combined = new ArrayList<>(exact.size() + tableWide.size());
        combined.addAll(exact);
        combined.addAll(tableWide);
        return combined;
    }

    private static TableColumn toTableColumn(NodeEntry node, ColumnEntry col) {
        TableColumn tc = new TableColumn();
        tc.setServer(node.getServer());
        tc.setDatabaseName(node.getDatabase());
        tc.setSchemaName(node.getSchema());
        tc.setRelationName(node.getName());
        tc.setAttributeName(col != null ? col.getName() : node.getName());
        return tc;
    }
}
