package io.sqlflow.graph.slice;

import io.sqlflow.contracts.graph.SqlflowJSON;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.JoinRelationship;
import io.sqlflow.contracts.lineage.Relationship;
import io.sqlflow.contracts.lineage.RelationshipElement;
import io.sqlflow.contracts.lineage.RelationshipType;
import io.sqlflow.contracts.lineage.Transform;
import io.sqlflow.contracts.spi.GraphLayoutSpi.GraphLayoutRequest;
import io.sqlflow.contracts.spi.GraphLayoutSpi.GraphSliceRequest;
import io.sqlflow.contracts.spi.GraphLayoutSpi.SlicingResult;
import io.sqlflow.graph.layout.SugiyamaGraphLayouter;
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
 * Interactive upstream and downstream BFS graph slicer ({@code OBL-054}, {@code 02 §4.3}, {@code 03 §3.1}).
 *
 * <p>Honors {@code maxHopDepth}, {@code relationTypes}, {@code showConstantTable},
 * {@code ignoreRecordSet} transitive path collapsing ({@link RelationshipType#collapseTransitive}),
 * and optional Sugiyama layout into {@link SqlflowJSON}.
 */
public final class GraphSlicer {

    public enum Direction {
        UPSTREAM,
        DOWNSTREAM
    }

    private record FrontierKey(String nodeId, String columnId) {
    }

    private final SugiyamaGraphLayouter layouter;

    public GraphSlicer(SugiyamaGraphLayouter layouter) {
        this.layouter = layouter;
    }

    public SlicingResult slice(GraphSliceRequest request, Direction direction) {
        if (request == null || request.getDataFlow() == null) {
            DataFlow emptyDf = new DataFlow();
            emptyDf.setRelationships(List.of());
            SqlflowJSON emptyGraph = request != null && request.isLayoutResult()
                    ? layouter.layout(GraphLayoutRequest.of(emptyDf, request.getOrientation()))
                    : null;
            return new SlicingResult(emptyDf, emptyGraph, List.of());
        }

        DataFlow dataFlow = request.getDataFlow();
        DataFlowIndex index = DataFlowIndex.build(dataFlow);
        List<String> matchedRootIds = index.matchNodesOrColumns(
                request.getDbObjIds(),
                request.getDatabase(),
                request.getSchema(),
                request.getTable(),
                request.getColumn());

        if (matchedRootIds.isEmpty()) {
            DataFlow emptySlice = CatalogCloneUtil.buildSubDataFlow(index, Set.of(), Set.of(), List.of());
            SqlflowJSON laidOut = request.isLayoutResult()
                    ? buildSlicedLayout(emptySlice, request)
                    : null;
            return new SlicingResult(emptySlice, laidOut, List.of());
        }

        Set<RelationshipType> allowedTypes = request.getRelationTypes();
        List<ResolvedEdge> baseEdges = index.resolveEdges(dataFlow.getRelationships(), allowedTypes);

        // Filter out SQL_CONSTANTS if showConstantTable == false
        if (!request.isShowConstantTable()) {
            baseEdges = baseEdges.stream()
                    .filter(e -> !e.source().node().isConstantTable() && !e.target().node().isConstantTable())
                    .toList();
        }

        // Identify root nodes explicitly targeted so ignoreRecordSet never deletes user-selected roots
        Set<String> protectedRootNodeIds = new HashSet<>();
        for (String rootId : matchedRootIds) {
            NodeEntry n = index.getNode(rootId);
            if (n != null) {
                protectedRootNodeIds.add(n.getId());
            }
            ColumnEntry c = index.getColumn(rootId);
            if (c != null) {
                protectedRootNodeIds.add(c.getParentNodeId());
            }
        }

        List<ResolvedEdge> workingEdges = request.isIgnoreRecordSet()
                ? collapseIntermediateResultSets(baseEdges, protectedRootNodeIds)
                : baseEdges;

        // Index edges by endpoint for O(1) BFS expansion
        Map<FrontierKey, List<ResolvedEdge>> edgesByColEndpoint = new HashMap<>();
        Map<String, List<ResolvedEdge>> edgesByNodeEndpoint = new HashMap<>();
        for (ResolvedEdge edge : workingEdges) {
            ResolvedEndpoint anchor = (direction == Direction.UPSTREAM) ? edge.target() : edge.source();
            String nId = anchor.node().getId();
            edgesByNodeEndpoint.computeIfAbsent(nId, k -> new ArrayList<>()).add(edge);
            if (anchor.column() != null) {
                edgesByColEndpoint
                        .computeIfAbsent(new FrontierKey(nId, anchor.column().getId()), k -> new ArrayList<>())
                        .add(edge);
            } else {
                edgesByColEndpoint
                        .computeIfAbsent(new FrontierKey(nId, null), k -> new ArrayList<>())
                        .add(edge);
            }
        }

        int maxHopDepth = request.getMaxHopDepth() > 0 ? request.getMaxHopDepth() : 20;
        Map<FrontierKey, Integer> visitedDistances = new LinkedHashMap<>();
        Deque<FrontierKey> queue = new ArrayDeque<>();
        Set<String> retainedNodeIds = new LinkedHashSet<>();
        Set<String> retainedColumnIds = new LinkedHashSet<>();

        for (String rootId : matchedRootIds) {
            ColumnEntry rootCol = index.getColumn(rootId);
            if (rootCol != null) {
                FrontierKey fk = new FrontierKey(rootCol.getParentNodeId(), rootCol.getId());
                if (visitedDistances.putIfAbsent(fk, 0) == null) {
                    queue.add(fk);
                }
                retainedNodeIds.add(rootCol.getParentNodeId());
                retainedColumnIds.add(rootCol.getId());
                continue;
            }
            NodeEntry rootNode = index.getNode(rootId);
            if (rootNode != null) {
                retainedNodeIds.add(rootNode.getId());
                FrontierKey nodeKey = new FrontierKey(rootNode.getId(), null);
                if (visitedDistances.putIfAbsent(nodeKey, 0) == null) {
                    queue.add(nodeKey);
                }
                for (ColumnEntry col : rootNode.getColumns()) {
                    retainedColumnIds.add(col.getId());
                    FrontierKey colKey = new FrontierKey(rootNode.getId(), col.getId());
                    if (visitedDistances.putIfAbsent(colKey, 0) == null) {
                        queue.add(colKey);
                    }
                }
            }
        }

        Map<ResolvedEdge, Integer> traversedEdges = new LinkedHashMap<>();

        while (!queue.isEmpty()) {
            FrontierKey current = queue.poll();
            int curDist = visitedDistances.get(current);
            if (curDist >= maxHopDepth) {
                continue;
            }

            List<ResolvedEdge> candidates;
            if (current.columnId() != null) {
                List<ResolvedEdge> colSpecific = edgesByColEndpoint.getOrDefault(current, List.of());
                List<ResolvedEdge> tableLevel = edgesByColEndpoint.getOrDefault(
                        new FrontierKey(current.nodeId(), null), List.of());
                if (tableLevel.isEmpty()) {
                    candidates = colSpecific;
                } else {
                    candidates = new ArrayList<>(colSpecific.size() + tableLevel.size());
                    candidates.addAll(colSpecific);
                    candidates.addAll(tableLevel);
                }
            } else {
                candidates = edgesByNodeEndpoint.getOrDefault(current.nodeId(), List.of());
            }

            for (ResolvedEdge edge : candidates) {
                int nextDist = curDist + 1;
                traversedEdges.merge(edge, nextDist, Math::min);

                ResolvedEndpoint nextEp = (direction == Direction.UPSTREAM) ? edge.source() : edge.target();
                String nextNodeId = nextEp.node().getId();
                String nextColId = nextEp.column() != null ? nextEp.column().getId() : null;
                retainedNodeIds.add(nextNodeId);
                if (nextColId != null) {
                    retainedColumnIds.add(nextColId);
                }

                FrontierKey nextKey = new FrontierKey(nextNodeId, nextColId);
                Integer prevDist = visitedDistances.get(nextKey);
                if (prevDist == null || nextDist < prevDist) {
                    visitedDistances.put(nextKey, nextDist);
                    queue.add(nextKey);
                }
            }
        }

        // Assemble consolidated Relationships preserving traversed source elements & hop distances
        List<Relationship> slicedRelationships = assembleSlicedRelationships(traversedEdges);
        DataFlow slicedDataFlow = CatalogCloneUtil.buildSubDataFlow(
                index, retainedNodeIds, retainedColumnIds, slicedRelationships);

        SqlflowJSON graphJson = null;
        if (request.isLayoutResult()) {
            graphJson = buildSlicedLayout(slicedDataFlow, request);
            graphJson.setDatabase(request.getDatabase());
            graphJson.setSchema(request.getSchema());
            graphJson.setTable(request.getTable());
            graphJson.setColumn(request.getColumn());
        }

        return new SlicingResult(slicedDataFlow, graphJson, matchedRootIds);
    }

    private SqlflowJSON buildSlicedLayout(DataFlow slicedDataFlow, GraphSliceRequest request) {
        GraphLayoutRequest layoutReq = new GraphLayoutRequest();
        layoutReq.setDataFlow(slicedDataFlow);
        layoutReq.setOrientation(request.getOrientation());
        layoutReq.setRelationTypes(request.getRelationTypes());
        return layouter.layout(layoutReq);
    }

    /**
     * Collapses intermediate {@code ResultSet} hops ({@code ignoreRecordSet == true}) while obeying
     * {@link RelationshipType#collapseTransitive} dominance ({@code fdd + fdd -> fdd}, any indirect -> {@code fdr}).
     */
    private List<ResolvedEdge> collapseIntermediateResultSets(
            List<ResolvedEdge> edges,
            Set<String> protectedNodeIds) {
        Map<FrontierKey, List<ResolvedEdge>> outgoingFromEp = new HashMap<>();
        Map<String, List<ResolvedEdge>> outgoingFromNode = new HashMap<>();
        for (ResolvedEdge e : edges) {
            String srcNodeId = e.source().node().getId();
            outgoingFromNode.computeIfAbsent(srcNodeId, k -> new ArrayList<>()).add(e);
            String srcColId = e.source().column() != null ? e.source().column().getId() : null;
            outgoingFromEp.computeIfAbsent(new FrontierKey(srcNodeId, srcColId), k -> new ArrayList<>()).add(e);
        }

        List<ResolvedEdge> collapsed = new ArrayList<>();
        Set<String> dedupKeys = new HashSet<>();
        int synRelSeq = 1;

        for (ResolvedEdge startEdge : edges) {
            NodeEntry srcNode = startEdge.source().node();
            if (srcNode.isResultSetLike() && !protectedNodeIds.contains(srcNode.getId())) {
                // Intermediate hop start; will be reached starting from its upstream non-ResultSet origin
                continue;
            }

            NodeEntry tgtNode = startEdge.target().node();
            if (!tgtNode.isResultSetLike() || protectedNodeIds.contains(tgtNode.getId())) {
                String key = edgeDedupKey(startEdge.source(), startEdge.target(), startEdge.relationType());
                if (dedupKeys.add(key)) {
                    collapsed.add(startEdge);
                }
                continue;
            }

            // Trace forward through intermediate ResultSet nodes to non-ResultSet consumers
            record WalkState(
                    ResolvedEndpoint currentEp,
                    RelationshipType accumulatedType,
                    List<Transform> accumulatedTransforms,
                    Set<FrontierKey> pathVisited) {
            }

            Deque<WalkState> stack = new ArrayDeque<>();
            FrontierKey firstMid = new FrontierKey(
                    tgtNode.getId(),
                    startEdge.target().column() != null ? startEdge.target().column().getId() : null);
            List<Transform> initTransforms = collectTransforms(startEdge);
            Set<FrontierKey> initVisited = new HashSet<>();
            initVisited.add(firstMid);
            stack.push(new WalkState(startEdge.target(), startEdge.relationType(), initTransforms, initVisited));

            while (!stack.isEmpty()) {
                WalkState state = stack.pop();
                FrontierKey curKey = new FrontierKey(
                        state.currentEp().node().getId(),
                        state.currentEp().column() != null ? state.currentEp().column().getId() : null);
                List<ResolvedEdge> nextHops = curKey.columnId() != null
                        ? outgoingFromEp.getOrDefault(curKey, List.of())
                        : outgoingFromNode.getOrDefault(curKey.nodeId(), List.of());

                for (ResolvedEdge hop : nextHops) {
                    RelationshipType mergedType = RelationshipType.collapseTransitive(
                            state.accumulatedType(), hop.relationType());
                    List<Transform> mergedTransforms = new ArrayList<>(state.accumulatedTransforms());
                    mergedTransforms.addAll(collectTransforms(hop));

                    NodeEntry hopTgtNode = hop.target().node();
                    if (!hopTgtNode.isResultSetLike() || protectedNodeIds.contains(hopTgtNode.getId())) {
                        String key = edgeDedupKey(startEdge.source(), hop.target(), mergedType);
                        if (dedupKeys.add(key)) {
                            Relationship synRel = buildCollapsedRelationship(
                                    "collapsed_" + (synRelSeq++),
                                    mergedType,
                                    startEdge,
                                    hop,
                                    mergedTransforms);
                            collapsed.add(new ResolvedEdge(
                                    synRel,
                                    mergedType,
                                    startEdge.source(),
                                    hop.target(),
                                    synRel.getSources().get(0),
                                    synRel.getTarget()));
                        }
                    } else {
                        FrontierKey nextMid = new FrontierKey(
                                hopTgtNode.getId(),
                                hop.target().column() != null ? hop.target().column().getId() : null);
                        if (!state.pathVisited().contains(nextMid)) {
                            Set<FrontierKey> nextVisited = new HashSet<>(state.pathVisited());
                            nextVisited.add(nextMid);
                            stack.push(new WalkState(hop.target(), mergedType, mergedTransforms, nextVisited));
                        }
                    }
                }
            }
        }
        return collapsed;
    }

    private List<Transform> collectTransforms(ResolvedEdge edge) {
        List<Transform> out = new ArrayList<>();
        if (edge.rawSourceElement() != null && edge.rawSourceElement().getTransforms() != null) {
            out.addAll(edge.rawSourceElement().getTransforms());
        }
        if (edge.rawTargetElement() != null && edge.rawTargetElement().getTransforms() != null) {
            out.addAll(edge.rawTargetElement().getTransforms());
        }
        return out;
    }

    private Relationship buildCollapsedRelationship(
            String synId,
            RelationshipType mergedType,
            ResolvedEdge firstEdge,
            ResolvedEdge lastEdge,
            List<Transform> mergedTransforms) {
        Relationship r = mergedType == RelationshipType.join ? new JoinRelationship() : new Relationship();
        r.setId(synId);
        r.setType(mergedType.wireName());
        r.setEffectType(lastEdge.relationship().getEffectType() != null
                ? lastEdge.relationship().getEffectType()
                : firstEdge.relationship().getEffectType());

        RelationshipElement srcEl = CatalogCloneUtil.copyElement(firstEdge.rawSourceElement());
        if (srcEl == null) {
            srcEl = buildElementFromEndpoint(firstEdge.source());
        }
        if (!mergedTransforms.isEmpty()) {
            srcEl.setTransforms(mergedTransforms);
        }

        RelationshipElement tgtEl = CatalogCloneUtil.copyElement(lastEdge.rawTargetElement());
        if (tgtEl == null) {
            tgtEl = buildElementFromEndpoint(lastEdge.target());
        }

        r.setSources(List.of(srcEl));
        r.setTarget(tgtEl);
        return r;
    }

    private RelationshipElement buildElementFromEndpoint(ResolvedEndpoint ep) {
        RelationshipElement el = new RelationshipElement();
        if (ep.column() != null) {
            el.setId(ep.column().getId());
            el.setColumn(ep.column().getName());
            el.setParentId(ep.node().getId());
            el.setParentName(ep.node().getName());
        } else {
            el.setId(ep.node().getId());
            el.setName(ep.node().getName());
            el.setParentId(ep.node().getId());
            el.setParentName(ep.node().getName());
        }
        return el;
    }

    public static List<Relationship> assembleSlicedRelationships(Map<ResolvedEdge, Integer> traversedEdges) {
        if (traversedEdges.isEmpty()) {
            return List.of();
        }
        Map<Relationship, List<Map.Entry<ResolvedEdge, Integer>>> byOrigRel = new LinkedHashMap<>();
        for (Map.Entry<ResolvedEdge, Integer> entry : traversedEdges.entrySet()) {
            byOrigRel.computeIfAbsent(entry.getKey().relationship(), k -> new ArrayList<>()).add(entry);
        }

        List<Relationship> out = new ArrayList<>(byOrigRel.size());
        for (Map.Entry<Relationship, List<Map.Entry<ResolvedEdge, Integer>>> group : byOrigRel.entrySet()) {
            Relationship orig = group.getKey();
            int minDist = Integer.MAX_VALUE;
            List<RelationshipElement> keptSources = new ArrayList<>();
            List<RelationshipElement> keptCallees = new ArrayList<>();
            Set<RelationshipElement> seenRawSources = new HashSet<>();

            for (Map.Entry<ResolvedEdge, Integer> item : group.getValue()) {
                minDist = Math.min(minDist, item.getValue());
                ResolvedEdge re = item.getKey();
                if (re.relationType() == RelationshipType.call
                        || (orig.getCaller() != null && orig.getCallees() != null)) {
                    if (re.rawTargetElement() != null && seenRawSources.add(re.rawTargetElement())) {
                        keptCallees.add(CatalogCloneUtil.copyElement(re.rawTargetElement()));
                    }
                } else {
                    if (re.rawSourceElement() != null && seenRawSources.add(re.rawSourceElement())) {
                        keptSources.add(CatalogCloneUtil.copyElement(re.rawSourceElement()));
                    }
                }
            }

            Relationship copied = CatalogCloneUtil.copyRelationship(orig, minDist);
            if (!keptSources.isEmpty()) {
                copied.setSources(keptSources);
            }
            if (!keptCallees.isEmpty()) {
                copied.setCallees(keptCallees);
            }
            out.add(copied);
        }
        return out;
    }

    private static String edgeDedupKey(ResolvedEndpoint src, ResolvedEndpoint tgt, RelationshipType type) {
        String sId = src.column() != null ? src.column().getId() : src.node().getId();
        String tId = tgt.column() != null ? tgt.column().getId() : tgt.node().getId();
        return sId + "->" + tId + ":" + (type != null ? type.wireName() : "fdd");
    }
}
