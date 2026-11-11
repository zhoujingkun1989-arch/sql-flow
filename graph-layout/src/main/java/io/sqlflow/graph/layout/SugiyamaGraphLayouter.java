package io.sqlflow.graph.layout;

import io.sqlflow.contracts.graph.SqlflowJSON;
import io.sqlflow.contracts.graph.SqlflowJSON.Graph;
import io.sqlflow.contracts.graph.SqlflowJSON.GraphColumn;
import io.sqlflow.contracts.graph.SqlflowJSON.GraphEdge;
import io.sqlflow.contracts.graph.SqlflowJSON.GraphElements;
import io.sqlflow.contracts.graph.SqlflowJSON.GraphLabel;
import io.sqlflow.contracts.graph.SqlflowJSON.GraphPackage;
import io.sqlflow.contracts.graph.SqlflowJSON.GraphTable;
import io.sqlflow.contracts.graph.SqlflowJSON.PortPoint;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.Orientation;
import io.sqlflow.contracts.lineage.RelationshipType;
import io.sqlflow.contracts.spi.GraphLayoutSpi.GraphLayoutRequest;
import io.sqlflow.graph.export.GraphMlExporter;
import io.sqlflow.graph.export.SqlflowPngRenderer;
import io.sqlflow.graph.layout.BezierEdgeRouter.AnchorBox;
import io.sqlflow.graph.model.DataFlowIndex;
import io.sqlflow.graph.model.DataFlowIndex.ColumnEntry;
import io.sqlflow.graph.model.DataFlowIndex.NodeEntry;
import io.sqlflow.graph.model.DataFlowIndex.PackageEntry;
import io.sqlflow.graph.model.DataFlowIndex.ResolvedEdge;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Clean-room hierarchical Sugiyama DAG + {@code RowLayouter} + compound {@code Package} container
 * layout engine implementing {@code OBL-051..OBL-053} ({@code 03 §4.1–§4.4}).
 */
public final class SugiyamaGraphLayouter {

    public static final double MIN_CARD_WIDTH = 162.0;
    public static final double HEADER_LABEL_HEIGHT = 18.0;
    public static final double FIRST_ROW_OFFSET_Y = 22.0;
    public static final double ROW_HEIGHT = 16.0;
    public static final double CARD_BOTTOM_INSET = 4.0;
    public static final double CANVAS_PADDING = 30.0;
    public static final double STANDARD_LAYER_GAP = 80.0;
    public static final double JOIN_LAYER_GAP = 250.0;
    public static final double NODE_STACK_GAP = 28.0;
    public static final double PACKAGE_EXTRA_GAP = 42.0;
    public static final double PACKAGE_PAD_X = 14.0;
    public static final double PACKAGE_PAD_TOP = 30.0;
    public static final double PACKAGE_PAD_BOTTOM = 12.0;

    private static final class LayoutVertex {
        final int vertexIndex;
        final boolean dummy;
        final String modelNodeId;
        final String visualId;
        final String packageModelId;
        final GraphTable graphTable;
        double width;
        double height;
        int rank;
        int orderInRank;
        double x;
        double y;

        LayoutVertex(
                int vertexIndex,
                boolean dummy,
                String modelNodeId,
                String visualId,
                String packageModelId,
                GraphTable graphTable,
                double width,
                double height) {
            this.vertexIndex = vertexIndex;
            this.dummy = dummy;
            this.modelNodeId = modelNodeId;
            this.visualId = visualId;
            this.packageModelId = packageModelId;
            this.graphTable = graphTable;
            this.width = width;
            this.height = height;
        }
    }

    private static final class DagArc {
        final int u;
        final int v;
        final double uPortFraction;
        final double vPortFraction;
        boolean reversed;

        DagArc(int u, int v, double uPortFraction, double vPortFraction) {
            this.u = u;
            this.v = v;
            this.uPortFraction = uPortFraction;
            this.vPortFraction = vPortFraction;
        }

        int effectiveFrom() {
            return reversed ? v : u;
        }

        int effectiveTo() {
            return reversed ? u : v;
        }

        double effectiveFromFraction() {
            return reversed ? vPortFraction : uPortFraction;
        }

        double effectiveToFraction() {
            return reversed ? uPortFraction : vPortFraction;
        }
    }

    private static final class ConsolidatedEdgeSpec {
        final String edgeVisualId;
        final String sourceVisualId;
        final String targetVisualId;
        final int sourceTableVertexIdx;
        final int targetTableVertexIdx;
        final int sourceColRowIndex;
        final int targetColRowIndex;
        final List<String> relationshipIds = new ArrayList<>();
        RelationshipType dominantType;
        String effectType;

        ConsolidatedEdgeSpec(
                String edgeVisualId,
                String sourceVisualId,
                String targetVisualId,
                int sourceTableVertexIdx,
                int targetTableVertexIdx,
                int sourceColRowIndex,
                int targetColRowIndex,
                RelationshipType initialType,
                String effectType) {
            this.edgeVisualId = edgeVisualId;
            this.sourceVisualId = sourceVisualId;
            this.targetVisualId = targetVisualId;
            this.sourceTableVertexIdx = sourceTableVertexIdx;
            this.targetTableVertexIdx = targetTableVertexIdx;
            this.sourceColRowIndex = sourceColRowIndex;
            this.targetColRowIndex = targetColRowIndex;
            this.dominantType = initialType;
            this.effectType = effectType;
        }
    }

    public SqlflowJSON layout(GraphLayoutRequest request) {
        long startNanos = System.nanoTime();
        if (request == null || request.getDataFlow() == null) {
            return emptyResult(request != null ? request.getOrientation() : Orientation.LEFT_TO_RIGHT);
        }

        DataFlow dataFlow = request.getDataFlow();
        Orientation orientation = request.getOrientation() != null
                ? request.getOrientation()
                : (dataFlow.getOrientation() != null ? dataFlow.getOrientation() : Orientation.LEFT_TO_RIGHT);
        boolean tableLevel = request.isTableLevel();
        int maxCols = request.getMaxColumnsVisible();
        Set<RelationshipType> allowedTypes = request.getRelationTypes();
        boolean erMode = allowedTypes != null && allowedTypes.contains(RelationshipType.er);

        DataFlowIndex index = DataFlowIndex.build(dataFlow);
        List<ResolvedEdge> resolvedEdges = index.resolveEdges(dataFlow.getRelationships(), allowedTypes);

        boolean hasJoin = request.isShowRelationType()
                || resolvedEdges.stream().anyMatch(e -> e.relationType() == RelationshipType.join);

        // Identify participating model nodes & columns
        Set<String> activeNodeIds = new LinkedHashSet<>();
        Set<String> activeColumnIds = new LinkedHashSet<>();
        for (ResolvedEdge edge : resolvedEdges) {
            activeNodeIds.add(edge.source().node().getId());
            activeNodeIds.add(edge.target().node().getId());
            if (edge.source().column() != null) {
                activeColumnIds.add(edge.source().column().getId());
            }
            if (edge.target().column() != null) {
                activeColumnIds.add(edge.target().column().getId());
            }
        }

        // Select retained nodes (prune isolated nodes unless erMode or standalone 0-edge catalog)
        List<NodeEntry> retainedNodes = new ArrayList<>();
        boolean noRelationshipsInModel = dataFlow.getRelationships() == null || dataFlow.getRelationships().isEmpty();
        for (NodeEntry node : index.getNodesById().values()) {
            if (activeNodeIds.contains(node.getId())) {
                retainedNodes.add(node);
            } else if (erMode) {
                if ("table".equalsIgnoreCase(node.getType()) || "view".equalsIgnoreCase(node.getType())) {
                    retainedNodes.add(node);
                }
            } else if (noRelationshipsInModel && activeNodeIds.isEmpty()) {
                if (!node.isConstantTable()) {
                    retainedNodes.add(node);
                }
            }
        }

        if (retainedNodes.isEmpty()) {
            SqlflowJSON empty = emptyResult(orientation);
            empty.setSqlflow(dataFlow);
            empty.setTableLevel(tableLevel);
            if (request.isIncludeGraphML()) {
                String gml = GraphMlExporter.export(empty);
                empty.setGraphML(gml);
                empty.getGraph().setGraphML(gml);
            }
            if (request.isIncludeSvg()) {
                String svg = SqlflowPngRenderer.renderSvg(empty);
                empty.setSvg(svg);
                empty.getGraph().setSvg(svg);
            }
            return empty;
        }

        // Build visual Table vertices + RowLayouter column stacks + tooltip + listIdMap
        Map<String, List<String>> listIdMap = new LinkedHashMap<>();
        Map<String, String> tooltip = new LinkedHashMap<>();
        List<LayoutVertex> realVertices = new ArrayList<>(retainedNodes.size());
        Map<String, Integer> modelNodeToVertexIdx = new HashMap<>();
        Map<String, String> modelColToVisualId = new HashMap<>();
        Map<String, Integer> modelColToRowIndex = new HashMap<>();
        List<String> dbObjIds = new ArrayList<>(retainedNodes.size());

        for (int i = 0; i < retainedNodes.size(); i++) {
            NodeEntry node = retainedNodes.get(i);
            String visualTableId = "n" + i;
            dbObjIds.add(node.getId());
            listIdMap.computeIfAbsent(node.getId(), k -> new ArrayList<>()).add(visualTableId);

            String rawHeader = node.resolveHeaderLabel();
            String shortHeader = TextMetricsHelper.getShortLabel(rawHeader, TextMetricsHelper.SHORT_LABEL_LIMIT);
            if (!shortHeader.equals(rawHeader)) {
                tooltip.put(visualTableId, rawHeader);
            }
            double maxRowWidth = TextMetricsHelper.measureHeaderWidth(TextMetricsHelper.get50Char(rawHeader)) + 28.0;

            List<GraphColumn> graphColumns = new ArrayList<>();
            boolean truncatedMore = Boolean.TRUE.equals(
                    node.getRawDbObject() != null ? node.getRawDbObject().getMore() : null);

            if (!tableLevel) {
                List<ColumnEntry> candidateCols = new ArrayList<>();
                for (ColumnEntry col : node.getColumns()) {
                    boolean usedInEdge = activeColumnIds.contains(col.getId());
                    if (Boolean.FALSE.equals(col.getUiVisible()) && !usedInEdge) {
                        continue;
                    }
                    if (col.isSystemRelationRows() && !usedInEdge) {
                        continue;
                    }
                    candidateCols.add(col);
                }

                List<ColumnEntry> visibleCols;
                if (maxCols > 0 && candidateCols.size() > maxCols) {
                    truncatedMore = true;
                    Set<ColumnEntry> chosen = new LinkedHashSet<>();
                    for (ColumnEntry c : candidateCols) {
                        if (activeColumnIds.contains(c.getId()) && chosen.size() < maxCols) {
                            chosen.add(c);
                        }
                    }
                    for (ColumnEntry c : candidateCols) {
                        if (chosen.size() >= maxCols) {
                            break;
                        }
                        chosen.add(c);
                    }
                    visibleCols = new ArrayList<>(chosen);
                } else {
                    visibleCols = candidateCols;
                }

                // RowLayouter: stack columns monotonically at y = 22.0 + j * 16.0
                for (int j = 0; j < visibleCols.size(); j++) {
                    ColumnEntry col = visibleCols.get(j);
                    String visualColId = visualTableId + "::n" + j;
                    listIdMap.computeIfAbsent(col.getId(), k -> new ArrayList<>()).add(visualColId);
                    modelColToVisualId.put(col.getId(), visualColId);
                    modelColToRowIndex.put(col.getId(), j);

                    String rawColName = col.getDisplayName() != null && !col.getDisplayName().isBlank()
                            ? col.getDisplayName()
                            : col.getName();
                    String shortColName = TextMetricsHelper.getShortLabel(
                            rawColName, TextMetricsHelper.SHORT_LABEL_LIMIT);
                    if (!shortColName.equals(rawColName)) {
                        tooltip.put(visualColId, rawColName);
                    }

                    double badgeExtra = 0.0;
                    if (Boolean.TRUE.equals(col.getPrimaryKey())) {
                        badgeExtra += 22.0;
                    }
                    if (Boolean.TRUE.equals(col.getUniqueKey())) {
                        badgeExtra += 22.0;
                    }
                    if (Boolean.TRUE.equals(col.getForeignKey())) {
                        badgeExtra += 22.0;
                    }
                    if (Boolean.TRUE.equals(col.getIndexKey())) {
                        badgeExtra += 20.0;
                    }
                    double typeExtra = (col.getDataType() != null && !col.getDataType().isBlank())
                            ? TextMetricsHelper.measureColumnWidth(TextMetricsHelper.getShortLabel(col.getDataType(), 16)) + 12.0
                            : 0.0;

                    double textW = TextMetricsHelper.measureColumnWidth(TextMetricsHelper.get50Char(rawColName));
                    maxRowWidth = Math.max(maxRowWidth, textW + badgeExtra + typeExtra + 28.0);

                    GraphColumn gc = new GraphColumn();
                    gc.setId(visualColId);
                    gc.setName(col.getName());
                    gc.setDisplayName(col.getDisplayName());
                    gc.setDataType(col.getDataType());
                    gc.setX(1.0);
                    gc.setY(FIRST_ROW_OFFSET_Y + j * ROW_HEIGHT);
                    gc.setHeight(ROW_HEIGHT);
                    gc.setPrimaryKey(col.getPrimaryKey());
                    gc.setUnqiueKey(col.getUniqueKey());
                    gc.setIndexKey(col.getIndexKey());
                    gc.setForeignKey(col.getForeignKey());
                    gc.setNullable(col.getNullable());
                    gc.setStar(col.getStar());

                    GraphLabel colLabel = new GraphLabel(shortColName, 16.0, 0.0, round2(textW), ROW_HEIGHT);
                    colLabel.setFontFamily(TextMetricsHelper.GRAPHML_FONT_FAMILY);
                    colLabel.setFontSize(12);
                    gc.setLabel(colLabel);
                    graphColumns.add(gc);
                }
            }

            double cardWidth = round2(Math.max(MIN_CARD_WIDTH, maxRowWidth));
            double cardHeight = round2(FIRST_ROW_OFFSET_Y + graphColumns.size() * ROW_HEIGHT + CARD_BOTTOM_INSET);

            for (GraphColumn gc : graphColumns) {
                gc.setWidth(round2(cardWidth - 2.0));
            }

            GraphTable gt = new GraphTable();
            gt.setId(visualTableId);
            gt.setName(node.getName());
            gt.setDisplayName(node.getDisplayName());
            gt.setAlias(node.getAlias());
            gt.setType(node.getType());
            gt.setSubType(node.getSubType());
            gt.setServer(node.getServer());
            gt.setDatabase(node.getDatabase());
            gt.setSchema(node.getSchema());
            gt.setWidth(cardWidth);
            gt.setHeight(cardHeight);
            gt.setColumns(graphColumns);
            if (truncatedMore) {
                gt.setMore(true);
            }

            GraphLabel headerLabel = new GraphLabel(shortHeader, 0.0, 0.0, cardWidth, HEADER_LABEL_HEIGHT);
            headerLabel.setFontFamily(TextMetricsHelper.GRAPHML_FONT_FAMILY);
            headerLabel.setFontSize(TextMetricsHelper.HEADER_FONT_SIZE);
            gt.setLabel(headerLabel);

            LayoutVertex lv = new LayoutVertex(
                    i,
                    false,
                    node.getId(),
                    visualTableId,
                    node.getParentPackageId(),
                    gt,
                    cardWidth,
                    cardHeight);
            realVertices.add(lv);
            modelNodeToVertexIdx.put(node.getId(), i);
        }

        // Consolidate semantic edges into visual GraphEdges & relationshipIdMap
        Map<String, ConsolidatedEdgeSpec> edgeMap = new LinkedHashMap<>();
        int edgeSeq = 0;
        for (ResolvedEdge re : resolvedEdges) {
            Integer srcTblIdx = modelNodeToVertexIdx.get(re.source().node().getId());
            Integer tgtTblIdx = modelNodeToVertexIdx.get(re.target().node().getId());
            if (srcTblIdx == null || tgtTblIdx == null) {
                continue;
            }
            LayoutVertex srcV = realVertices.get(srcTblIdx);
            LayoutVertex tgtV = realVertices.get(tgtTblIdx);

            String srcVisId = srcV.visualId;
            int srcRowIdx = -1;
            if (!tableLevel && re.source().column() != null) {
                String cVis = modelColToVisualId.get(re.source().column().getId());
                if (cVis != null) {
                    srcVisId = cVis;
                    srcRowIdx = modelColToRowIndex.getOrDefault(re.source().column().getId(), 0);
                }
            }

            String tgtVisId = tgtV.visualId;
            int tgtRowIdx = -1;
            if (!tableLevel && re.target().column() != null) {
                String cVis = modelColToVisualId.get(re.target().column().getId());
                if (cVis != null) {
                    tgtVisId = cVis;
                    tgtRowIdx = modelColToRowIndex.getOrDefault(re.target().column().getId(), 0);
                }
            }

            // Collapse self-table-level degenerate edge in tableLevel mode if identical table
            if (tableLevel && srcTblIdx.equals(tgtTblIdx)) {
                continue;
            }

            String pairKey = srcVisId + "->" + tgtVisId;
            final int currentEdgeSeq = edgeSeq;
            final int finalSrcRow = srcRowIdx;
            final int finalTgtRow = tgtRowIdx;
            final String finalSrcVis = srcVisId;
            final String finalTgtVis = tgtVisId;
            ConsolidatedEdgeSpec spec = edgeMap.computeIfAbsent(pairKey, k -> new ConsolidatedEdgeSpec(
                    "e" + currentEdgeSeq,
                    finalSrcVis,
                    finalTgtVis,
                    srcTblIdx,
                    tgtTblIdx,
                    finalSrcRow,
                    finalTgtRow,
                    re.relationType(),
                    re.relationship().getEffectType()));
            if (spec.relationshipIds.isEmpty()) {
                edgeSeq++;
            }
            String relId = re.relationship().getId() != null
                    ? re.relationship().getId()
                    : String.valueOf(spec.relationshipIds.size() + 1);
            if (!spec.relationshipIds.contains(relId)) {
                spec.relationshipIds.add(relId);
            }
            // Direct dataflow ('fdd') dominates visual stroke over indirect ('fdr') on shared visual edge
            if (re.relationType() == RelationshipType.fdd) {
                spec.dominantType = RelationshipType.fdd;
            } else if (spec.dominantType == null) {
                spec.dominantType = re.relationType();
            }
            if (spec.effectType == null && re.relationship().getEffectType() != null) {
                spec.effectType = re.relationship().getEffectType();
            }
        }

        // Execute Sugiyama 4-phase layered layout over realVertices + inter-table arcs
        runSugiyamaLayout(realVertices, new ArrayList<>(edgeMap.values()), orientation, hasJoin);

        // Compute compound Package bounding boxes & enforce positive canvas margins
        List<GraphPackage> graphPackages = buildPackageContainers(index, realVertices, listIdMap, tooltip);
        normalizePositiveCanvas(realVertices, graphPackages);

        // Build final GraphTables, GraphEdges (with exact Bézier PortPoints), and canvas bounds
        List<GraphTable> outTables = new ArrayList<>(realVertices.size());
        Map<String, AnchorBox> anchorLookup = new HashMap<>();
        double maxCanvasRight = 0.0;
        double maxCanvasBottom = 0.0;

        for (LayoutVertex lv : realVertices) {
            GraphTable gt = lv.graphTable;
            gt.setX(round2(lv.x));
            gt.setY(round2(lv.y));
            outTables.add(gt);

            maxCanvasRight = Math.max(maxCanvasRight, gt.getX() + gt.getWidth());
            maxCanvasBottom = Math.max(maxCanvasBottom, gt.getY() + gt.getHeight());

            double headerMidY = gt.getY() + FIRST_ROW_OFFSET_Y / 2.0;
            anchorLookup.put(gt.getId(), new AnchorBox(
                    gt.getX(),
                    gt.getY(),
                    gt.getWidth(),
                    gt.getHeight(),
                    gt.getX(),
                    gt.getX() + gt.getWidth(),
                    gt.getX() + gt.getWidth() / 2.0,
                    gt.getY(),
                    gt.getY() + FIRST_ROW_OFFSET_Y,
                    headerMidY));

            if (gt.getColumns() != null) {
                for (GraphColumn gc : gt.getColumns()) {
                    double absColY = gt.getY() + gc.getY();
                    double midColY = absColY + gc.getHeight() / 2.0;
                    anchorLookup.put(gc.getId(), new AnchorBox(
                            gt.getX(),
                            gt.getY(),
                            gt.getWidth(),
                            gt.getHeight(),
                            gt.getX(),
                            gt.getX() + gt.getWidth(),
                            gt.getX() + gt.getWidth() / 2.0,
                            absColY,
                            absColY + gc.getHeight(),
                            midColY));
                }
            }
        }

        for (GraphPackage gp : graphPackages) {
            maxCanvasRight = Math.max(maxCanvasRight, gp.getX() + gp.getWidth());
            maxCanvasBottom = Math.max(maxCanvasBottom, gp.getY() + gp.getHeight());
        }

        Map<String, List<String>> relationshipIdMap = new LinkedHashMap<>();
        List<GraphEdge> outEdges = new ArrayList<>(edgeMap.size());
        for (ConsolidatedEdgeSpec spec : edgeMap.values()) {
            AnchorBox srcAnchor = anchorLookup.get(spec.sourceVisualId);
            AnchorBox tgtAnchor = anchorLookup.get(spec.targetVisualId);
            if (srcAnchor == null || tgtAnchor == null) {
                continue;
            }
            List<PortPoint> points = BezierEdgeRouter.computeControlPoints(srcAnchor, tgtAnchor, orientation);
            for (PortPoint pt : points) {
                maxCanvasRight = Math.max(maxCanvasRight, pt.getX());
                maxCanvasBottom = Math.max(maxCanvasBottom, pt.getY());
            }

            GraphEdge ge = new GraphEdge();
            ge.setId(spec.edgeVisualId);
            ge.setSourceId(spec.sourceVisualId);
            ge.setTargetId(spec.targetVisualId);
            ge.setRelationId(spec.relationshipIds.isEmpty() ? null : spec.relationshipIds.get(0));
            String relTypeStr = spec.dominantType != null ? spec.dominantType.wireName() : "fdd";
            ge.setType(relTypeStr);
            ge.setEffectType(spec.effectType != null ? spec.effectType : relTypeStr);
            ge.setPoints(points);

            outEdges.add(ge);
            relationshipIdMap.put(spec.edgeVisualId, List.copyOf(spec.relationshipIds));
        }

        double canvasWidth = round2(Math.max(320.0, maxCanvasRight + CANVAS_PADDING));
        double canvasHeight = round2(Math.max(200.0, maxCanvasBottom + CANVAS_PADDING));

        GraphElements elements = new GraphElements(
                outTables,
                outEdges,
                graphPackages);

        Graph nestedGraph = new Graph();
        nestedGraph.setElements(elements);
        nestedGraph.setWidth(canvasWidth);
        nestedGraph.setHeight(canvasHeight);
        nestedGraph.setOrientation(orientation);
        nestedGraph.setListIdMap(listIdMap);
        nestedGraph.setRelationshipIdMap(relationshipIdMap);
        nestedGraph.setTooltip(tooltip);

        SqlflowJSON result = new SqlflowJSON();
        result.setMode("global");
        result.setSqlflow(dataFlow);
        result.setDbObjIds(dbObjIds);
        result.setTableLevel(tableLevel);
        result.setGraph(nestedGraph);
        result.setElements(elements);
        result.setWidth(canvasWidth);
        result.setHeight(canvasHeight);
        result.setOrientation(orientation);
        result.setListIdMap(listIdMap);
        result.setRelationshipIdMap(relationshipIdMap);
        result.setTooltip(tooltip);

        if (request.isIncludeGraphML()) {
            String gml = GraphMlExporter.export(result);
            result.setGraphML(gml);
            nestedGraph.setGraphML(gml);
        }
        if (request.isIncludeSvg()) {
            String svg = SqlflowPngRenderer.renderSvg(result);
            result.setSvg(svg);
            nestedGraph.setSvg(svg);
        }

        long elapsedMs = Math.max(1L, (System.nanoTime() - startNanos) / 1_000_000L);
        result.setSpendTime(elapsedMs);
        return result;
    }

    private void runSugiyamaLayout(
            List<LayoutVertex> realVertices,
            List<ConsolidatedEdgeSpec> edges,
            Orientation orientation,
            boolean hasJoin) {
        int n = realVertices.size();
        if (n == 0) {
            return;
        }
        if (n == 1) {
            realVertices.get(0).x = CANVAS_PADDING;
            realVertices.get(0).y = CANVAS_PADDING;
            return;
        }

        // Consolidate table-to-table arcs for Sugiyama ranking & crossing minimization
        List<DagArc> arcs = new ArrayList<>();
        Map<Long, List<ConsolidatedEdgeSpec>> pairGroups = new LinkedHashMap<>();
        for (ConsolidatedEdgeSpec e : edges) {
            if (e.sourceTableVertexIdx == e.targetTableVertexIdx) {
                continue;
            }
            long key = (((long) e.sourceTableVertexIdx) << 32) | (e.targetTableVertexIdx & 0xffffffffL);
            pairGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }
        for (List<ConsolidatedEdgeSpec> group : pairGroups.values()) {
            ConsolidatedEdgeSpec first = group.get(0);
            LayoutVertex uV = realVertices.get(first.sourceTableVertexIdx);
            LayoutVertex vV = realVertices.get(first.targetTableVertexIdx);
            int uCols = uV.graphTable.getColumns() != null ? Math.max(1, uV.graphTable.getColumns().size()) : 1;
            int vCols = vV.graphTable.getColumns() != null ? Math.max(1, vV.graphTable.getColumns().size()) : 1;
            double avgU = 0.0;
            double avgV = 0.0;
            for (ConsolidatedEdgeSpec ce : group) {
                avgU += ce.sourceColRowIndex >= 0 ? (ce.sourceColRowIndex + 0.5) / uCols : 0.5;
                avgV += ce.targetColRowIndex >= 0 ? (ce.targetColRowIndex + 0.5) / vCols : 0.5;
            }
            avgU /= group.size();
            avgV /= group.size();
            arcs.add(new DagArc(first.sourceTableVertexIdx, first.targetTableVertexIdx, avgU, avgV));
        }

        // Phase 1: Cycle removal via DFS feedback arc set starting from high-(out - in) roots
        breakCyclesDfs(n, arcs);

        // Phase 2: Longest-path rank assignment + slack-tightening pull-right optimization
        int[] ranks = assignRanks(n, arcs, realVertices);
        int maxRank = 0;
        for (int i = 0; i < n; i++) {
            realVertices.get(i).rank = ranks[i];
            maxRank = Math.max(maxRank, ranks[i]);
        }

        // Phase 3: Insert dummy vertices on multi-span arcs & run barycenter + transpose sweeps
        List<LayoutVertex> allVertices = new ArrayList<>(realVertices);
        List<DagArc> unitArcs = new ArrayList<>();
        for (DagArc arc : arcs) {
            int from = arc.effectiveFrom();
            int to = arc.effectiveTo();
            int span = allVertices.get(to).rank - allVertices.get(from).rank;
            if (span <= 1) {
                unitArcs.add(arc);
            } else {
                int prev = from;
                double prevFrac = arc.effectiveFromFraction();
                for (int r = allVertices.get(from).rank + 1; r < allVertices.get(to).rank; r++) {
                    int dummyIdx = allVertices.size();
                    LayoutVertex dummy = new LayoutVertex(
                            dummyIdx, true, null, "dummy_" + dummyIdx, null, null, 16.0, 12.0);
                    dummy.rank = r;
                    allVertices.add(dummy);
                    DagArc seg = new DagArc(prev, dummyIdx, prevFrac, 0.5);
                    unitArcs.add(seg);
                    prev = dummyIdx;
                    prevFrac = 0.5;
                }
                unitArcs.add(new DagArc(prev, to, prevFrac, arc.effectiveToFraction()));
            }
        }

        List<List<LayoutVertex>> layers = new ArrayList<>(maxRank + 1);
        for (int r = 0; r <= maxRank; r++) {
            layers.add(new ArrayList<>());
        }
        for (LayoutVertex v : allVertices) {
            layers.get(v.rank).add(v);
        }
        for (List<LayoutVertex> layer : layers) {
            groupPackageSiblingsInLayer(layer);
            updateLayerOrder(layer);
        }

        minimizeCrossings(layers, allVertices.size(), unitArcs);

        // Phase 4: Coordinate assignment (primary rank axis + balanced secondary stack axis)
        boolean horizontalFlow = orientation == Orientation.LEFT_TO_RIGHT
                || orientation == Orientation.RIGHT_TO_LEFT;
        double layerGap = hasJoin ? JOIN_LAYER_GAP : STANDARD_LAYER_GAP;

        double[] rankPrimaryPos = new double[maxRank + 1];
        double[] rankPrimarySize = new double[maxRank + 1];
        for (int r = 0; r <= maxRank; r++) {
            double maxDim = 60.0;
            for (LayoutVertex v : layers.get(r)) {
                if (!v.dummy) {
                    maxDim = Math.max(maxDim, horizontalFlow ? v.width : v.height);
                }
            }
            rankPrimarySize[r] = maxDim;
        }

        if (orientation == Orientation.LEFT_TO_RIGHT || orientation == Orientation.TOP_TO_BOTTOM) {
            double cursor = CANVAS_PADDING;
            for (int r = 0; r <= maxRank; r++) {
                rankPrimaryPos[r] = cursor;
                cursor += rankPrimarySize[r] + layerGap;
            }
        } else {
            double cursor = CANVAS_PADDING;
            for (int r = maxRank; r >= 0; r--) {
                rankPrimaryPos[r] = cursor;
                cursor += rankPrimarySize[r] + layerGap;
            }
        }

        // Secondary axis: initial centered stack + neighbor barycenter alignment without overlaps
        double[] secPos = new double[allVertices.size()];
        double[] secSize = new double[allVertices.size()];
        for (LayoutVertex v : allVertices) {
            secSize[v.vertexIndex] = horizontalFlow ? v.height : v.width;
        }

        double maxLayerSpan = 0.0;
        double[] layerSpans = new double[maxRank + 1];
        for (int r = 0; r <= maxRank; r++) {
            List<LayoutVertex> layer = layers.get(r);
            double span = 0.0;
            for (int i = 0; i < layer.size(); i++) {
                LayoutVertex cur = layer.get(i);
                span += secSize[cur.vertexIndex];
                if (i + 1 < layer.size()) {
                    span += gapBetween(cur, layer.get(i + 1));
                }
            }
            layerSpans[r] = span;
            maxLayerSpan = Math.max(maxLayerSpan, span);
        }

        for (int r = 0; r <= maxRank; r++) {
            List<LayoutVertex> layer = layers.get(r);
            double cursor = CANVAS_PADDING + (maxLayerSpan - layerSpans[r]) / 2.0;
            for (int i = 0; i < layer.size(); i++) {
                LayoutVertex cur = layer.get(i);
                secPos[cur.vertexIndex] = cursor;
                cursor += secSize[cur.vertexIndex];
                if (i + 1 < layer.size()) {
                    cursor += gapBetween(cur, layer.get(i + 1));
                }
            }
        }

        // Build adjacency lists on unitArcs for Brandes-Köpf style iterative alignment
        List<List<DagArc>> inArcs = new ArrayList<>(allVertices.size());
        List<List<DagArc>> outArcs = new ArrayList<>(allVertices.size());
        for (int i = 0; i < allVertices.size(); i++) {
            inArcs.add(new ArrayList<>());
            outArcs.add(new ArrayList<>());
        }
        for (DagArc ua : unitArcs) {
            outArcs.get(ua.effectiveFrom()).add(ua);
            inArcs.get(ua.effectiveTo()).add(ua);
        }

        for (int iter = 0; iter < 8; iter++) {
            boolean forward = (iter % 2 == 0);
            int startR = forward ? 1 : maxRank - 1;
            int endR = forward ? maxRank + 1 : -1;
            int step = forward ? 1 : -1;

            for (int r = startR; r != endR; r += step) {
                List<LayoutVertex> layer = layers.get(r);
                for (int i = 0; i < layer.size(); i++) {
                    LayoutVertex v = layer.get(i);
                    List<DagArc> adj = forward ? inArcs.get(v.vertexIndex) : outArcs.get(v.vertexIndex);
                    if (adj.isEmpty()) {
                        continue;
                    }
                    double sumTargetTop = 0.0;
                    for (DagArc a : adj) {
                        int nbrIdx = forward ? a.effectiveFrom() : a.effectiveTo();
                        double nbrFrac = forward ? a.effectiveFromFraction() : a.effectiveToFraction();
                        double myFrac = forward ? a.effectiveToFraction() : a.effectiveFromFraction();
                        double nbrAnchor = secPos[nbrIdx] + nbrFrac * secSize[nbrIdx];
                        sumTargetTop += (nbrAnchor - myFrac * secSize[v.vertexIndex]);
                    }
                    double desired = sumTargetTop / adj.size();
                    double minAllowed = (i == 0)
                            ? CANVAS_PADDING
                            : secPos[layer.get(i - 1).vertexIndex]
                                    + secSize[layer.get(i - 1).vertexIndex]
                                    + gapBetween(layer.get(i - 1), v);
                    double maxAllowed = (i + 1 == layer.size())
                            ? Double.POSITIVE_INFINITY
                            : secPos[layer.get(i + 1).vertexIndex]
                                    - gapBetween(v, layer.get(i + 1))
                                    - secSize[v.vertexIndex];
                    if (minAllowed <= maxAllowed) {
                        secPos[v.vertexIndex] = Math.max(minAllowed, Math.min(maxAllowed, desired));
                    }
                }
            }
        }

        // Commit coordinates back to real vertices
        for (LayoutVertex v : realVertices) {
            if (horizontalFlow) {
                v.x = rankPrimaryPos[v.rank];
                v.y = secPos[v.vertexIndex];
            } else {
                v.x = secPos[v.vertexIndex];
                v.y = rankPrimaryPos[v.rank];
            }
        }
    }

    private double gapBetween(LayoutVertex a, LayoutVertex b) {
        if (!Objects.equals(a.packageModelId, b.packageModelId)
                && (a.packageModelId != null || b.packageModelId != null)) {
            return NODE_STACK_GAP + PACKAGE_EXTRA_GAP;
        }
        return NODE_STACK_GAP;
    }

    private void breakCyclesDfs(int n, List<DagArc> arcs) {
        int[] inDeg = new int[n];
        int[] outDeg = new int[n];
        List<List<DagArc>> outgoing = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            outgoing.add(new ArrayList<>());
        }
        for (DagArc arc : arcs) {
            outDeg[arc.u]++;
            inDeg[arc.v]++;
            outgoing.get(arc.u).add(arc);
        }

        List<Integer> startOrder = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            startOrder.add(i);
        }
        startOrder.sort((a, b) -> {
            boolean aRoot = inDeg[a] == 0 && outDeg[a] > 0;
            boolean bRoot = inDeg[b] == 0 && outDeg[b] > 0;
            if (aRoot != bRoot) {
                return aRoot ? -1 : 1;
            }
            return Integer.compare(outDeg[b] - inDeg[b], outDeg[a] - inDeg[a]);
        });

        int[] state = new int[n]; // 0 = unvisited, 1 = active stack, 2 = finished
        for (int root : startOrder) {
            if (state[root] == 0) {
                dfsVisit(root, state, outgoing);
            }
        }
    }

    private void dfsVisit(int u, int[] state, List<List<DagArc>> outgoing) {
        state[u] = 1;
        for (DagArc arc : outgoing.get(u)) {
            int v = arc.v;
            if (state[v] == 1) {
                arc.reversed = true;
            } else if (state[v] == 0) {
                dfsVisit(v, state, outgoing);
            }
        }
        state[u] = 2;
    }

    private int[] assignRanks(int n, List<DagArc> arcs, List<LayoutVertex> vertices) {
        int[] inDeg = new int[n];
        List<List<Integer>> adj = new ArrayList<>(n);
        List<List<Integer>> revAdj = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            adj.add(new ArrayList<>());
            revAdj.add(new ArrayList<>());
        }
        for (DagArc arc : arcs) {
            int from = arc.effectiveFrom();
            int to = arc.effectiveTo();
            adj.get(from).add(to);
            revAdj.get(to).add(from);
            inDeg[to]++;
        }

        int[] rank = new int[n];
        List<Integer> topo = new ArrayList<>(n);
        List<Integer> queue = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (inDeg[i] == 0) {
                queue.add(i);
            }
        }
        int head = 0;
        while (head < queue.size()) {
            int u = queue.get(head++);
            topo.add(u);
            for (int v : adj.get(u)) {
                rank[v] = Math.max(rank[v], rank[u] + 1);
                if (--inDeg[v] == 0) {
                    queue.add(v);
                }
            }
        }
        // Fallback safety if any residual cycle escaped
        if (topo.size() < n) {
            for (int i = 0; i < n; i++) {
                if (!topo.contains(i)) {
                    topo.add(i);
                }
            }
        }

        // Slack-tightening pass: pull source/low-inDegree nodes closer to their earliest consumer
        for (int pass = 0; pass < 3; pass++) {
            for (int idx = topo.size() - 1; idx >= 0; idx--) {
                int u = topo.get(idx);
                if (adj.get(u).isEmpty()) {
                    continue;
                }
                if (revAdj.get(u).size() <= adj.get(u).size()) {
                    int minChildRank = Integer.MAX_VALUE;
                    for (int v : adj.get(u)) {
                        minChildRank = Math.min(minChildRank, rank[v]);
                    }
                    int maxParentRank = -1;
                    for (int p : revAdj.get(u)) {
                        maxParentRank = Math.max(maxParentRank, rank[p]);
                    }
                    if (minChildRank != Integer.MAX_VALUE && minChildRank - 1 > maxParentRank) {
                        rank[u] = minChildRank - 1;
                    }
                }
            }
        }

        // Align unconstrained package siblings into the same rank when valid
        Map<String, List<Integer>> pkgMembers = new HashMap<>();
        for (int i = 0; i < n; i++) {
            String pkgId = vertices.get(i).packageModelId;
            if (pkgId != null) {
                pkgMembers.computeIfAbsent(pkgId, k -> new ArrayList<>()).add(i);
            }
        }
        for (List<Integer> members : pkgMembers.values()) {
            if (members.size() < 2) {
                continue;
            }
            int targetRank = members.get(0);
            int sharedRank = rank[targetRank];
            for (int m : members) {
                int minAllowed = 0;
                for (int p : revAdj.get(m)) {
                    minAllowed = Math.max(minAllowed, rank[p] + 1);
                }
                int maxAllowed = Integer.MAX_VALUE;
                for (int c : adj.get(m)) {
                    maxAllowed = Math.min(maxAllowed, rank[c] - 1);
                }
                if (sharedRank >= minAllowed && sharedRank <= maxAllowed) {
                    rank[m] = sharedRank;
                }
            }
        }

        // Re-zero minimum rank to 0
        int minRank = Integer.MAX_VALUE;
        for (int r : rank) {
            minRank = Math.min(minRank, r);
        }
        if (minRank > 0 && minRank != Integer.MAX_VALUE) {
            for (int i = 0; i < n; i++) {
                rank[i] -= minRank;
            }
        }
        return rank;
    }

    private void minimizeCrossings(
            List<List<LayoutVertex>> layers,
            int totalVertices,
            List<DagArc> unitArcs) {
        if (layers.size() <= 1) {
            return;
        }
        List<List<DagArc>> inByVertex = new ArrayList<>(totalVertices);
        List<List<DagArc>> outByVertex = new ArrayList<>(totalVertices);
        for (int i = 0; i < totalVertices; i++) {
            inByVertex.add(new ArrayList<>());
            outByVertex.add(new ArrayList<>());
        }
        for (DagArc a : unitArcs) {
            outByVertex.get(a.effectiveFrom()).add(a);
            inByVertex.get(a.effectiveTo()).add(a);
        }

        double[] barycenter = new double[totalVertices];
        for (int sweep = 0; sweep < 12; sweep++) {
            if (sweep % 2 == 0) {
                for (int r = 1; r < layers.size(); r++) {
                    sortLayerByBarycenter(layers.get(r), inByVertex, true, barycenter);
                    transposeReduceCrossings(layers.get(r - 1), layers.get(r), outByVertex, true);
                }
            } else {
                for (int r = layers.size() - 2; r >= 0; r--) {
                    sortLayerByBarycenter(layers.get(r), outByVertex, false, barycenter);
                    transposeReduceCrossings(layers.get(r), layers.get(r + 1), outByVertex, false);
                }
            }
        }
    }

    private void sortLayerByBarycenter(
            List<LayoutVertex> layer,
            List<List<DagArc>> adjByVertex,
            boolean incoming,
            double[] barycenter) {
        if (layer.size() <= 1) {
            return;
        }
        for (LayoutVertex v : layer) {
            List<DagArc> incident = adjByVertex.get(v.vertexIndex);
            if (incident.isEmpty()) {
                barycenter[v.vertexIndex] = v.orderInRank;
            } else {
                double sum = 0.0;
                for (DagArc a : incident) {
                    int nbrIdx = incoming ? a.effectiveFrom() : a.effectiveTo();
                    double nbrFrac = incoming ? a.effectiveFromFraction() : a.effectiveToFraction();
                    double myFrac = incoming ? a.effectiveToFraction() : a.effectiveFromFraction();
                    // Incorporate neighbor's orderInRank + column port offset
                    sum += findOrderOf(nbrIdx, a, incoming) + (nbrFrac - 0.5) * 0.45 + (myFrac - 0.5) * 0.15;
                }
                barycenter[v.vertexIndex] = sum / incident.size();
            }
        }
        layer.sort(Comparator.comparingDouble((LayoutVertex v) -> barycenter[v.vertexIndex])
                .thenComparingInt(v -> v.orderInRank));
        groupPackageSiblingsInLayer(layer);
        updateLayerOrder(layer);
    }

    // Store vertex orderInRank in a static-free lookup via DagArc vertices? Wait: we have LayoutVertex.orderInRank!
    // Let's pass allVertices or store LayoutVertex refs in DagArc! Wait: let's check findOrderOf:
    private double findOrderOf(int nbrIdx, DagArc a, boolean incoming) {
        return vertexOrdersCache != null && nbrIdx < vertexOrdersCache.length
                ? vertexOrdersCache[nbrIdx]
                : 0.0;
    }

    private int[] vertexOrdersCache;

    private void updateLayerOrder(List<LayoutVertex> layer) {
        int maxIdx = 0;
        for (LayoutVertex v : layer) {
            maxIdx = Math.max(maxIdx, v.vertexIndex);
        }
        if (vertexOrdersCache == null || vertexOrdersCache.length <= maxIdx) {
            int[] next = new int[Math.max(maxIdx + 16, vertexOrdersCache != null ? vertexOrdersCache.length * 2 : 64)];
            if (vertexOrdersCache != null) {
                System.arraycopy(vertexOrdersCache, 0, next, 0, vertexOrdersCache.length);
            }
            vertexOrdersCache = next;
        }
        for (int i = 0; i < layer.size(); i++) {
            LayoutVertex v = layer.get(i);
            v.orderInRank = i;
            vertexOrdersCache[v.vertexIndex] = i;
        }
    }

    private void groupPackageSiblingsInLayer(List<LayoutVertex> layer) {
        if (layer.size() <= 2) {
            return;
        }
        boolean hasPkg = false;
        for (LayoutVertex v : layer) {
            if (v.packageModelId != null) {
                hasPkg = true;
                break;
            }
        }
        if (!hasPkg) {
            return;
        }
        List<LayoutVertex> regrouped = new ArrayList<>(layer.size());
        Set<String> emittedPkgs = new HashSet<>();
        for (LayoutVertex v : layer) {
            if (v.packageModelId == null) {
                regrouped.add(v);
            } else if (emittedPkgs.add(v.packageModelId)) {
                for (LayoutVertex sibling : layer) {
                    if (v.packageModelId.equals(sibling.packageModelId)) {
                        regrouped.add(sibling);
                    }
                }
            }
        }
        layer.clear();
        layer.addAll(regrouped);
    }

    private void transposeReduceCrossings(
            List<LayoutVertex> leftLayer,
            List<LayoutVertex> rightLayer,
            List<List<DagArc>> outByVertex,
            boolean tuneRight) {
        List<LayoutVertex> target = tuneRight ? rightLayer : leftLayer;
        if (target.size() <= 1) {
            return;
        }
        int currentCrossings = countBipartiteCrossings(leftLayer, outByVertex);
        if (currentCrossings == 0) {
            return;
        }
        for (int i = 0; i + 1 < target.size(); i++) {
            LayoutVertex a = target.get(i);
            LayoutVertex b = target.get(i + 1);
            // Preserve package contiguity
            if (!Objects.equals(a.packageModelId, b.packageModelId)
                    && a.packageModelId != null
                    && b.packageModelId != null) {
                continue;
            }
            Collections.swap(target, i, i + 1);
            updateLayerOrder(target);
            int swappedCrossings = countBipartiteCrossings(leftLayer, outByVertex);
            if (swappedCrossings < currentCrossings) {
                currentCrossings = swappedCrossings;
            } else {
                Collections.swap(target, i, i + 1);
                updateLayerOrder(target);
            }
        }
    }

    private int countBipartiteCrossings(List<LayoutVertex> leftLayer, List<List<DagArc>> outByVertex) {
        List<double[]> chords = new ArrayList<>();
        for (LayoutVertex u : leftLayer) {
            for (DagArc a : outByVertex.get(u.vertexIndex)) {
                double uPos = u.orderInRank + a.effectiveFromFraction() * 0.5;
                double vPos = vertexOrdersCache[a.effectiveTo()] + a.effectiveToFraction() * 0.5;
                chords.add(new double[] {uPos, vPos});
            }
        }
        int crossings = 0;
        for (int i = 0; i < chords.size(); i++) {
            double u1 = chords.get(i)[0];
            double v1 = chords.get(i)[1];
            for (int j = i + 1; j < chords.size(); j++) {
                double u2 = chords.get(j)[0];
                double v2 = chords.get(j)[1];
                if ((u1 < u2 && v1 > v2) || (u1 > u2 && v1 < v2)) {
                    crossings++;
                }
            }
        }
        return crossings;
    }

    private List<GraphPackage> buildPackageContainers(
            DataFlowIndex index,
            List<LayoutVertex> realVertices,
            Map<String, List<String>> listIdMap,
            Map<String, String> tooltip) {
        Map<String, List<LayoutVertex>> byPkg = new LinkedHashMap<>();
        for (LayoutVertex v : realVertices) {
            if (v.packageModelId != null) {
                byPkg.computeIfAbsent(v.packageModelId, k -> new ArrayList<>()).add(v);
            }
        }
        if (byPkg.isEmpty()) {
            return List.of();
        }
        List<GraphPackage> packages = new ArrayList<>(byPkg.size());
        int pkgIdx = 0;
        for (Map.Entry<String, List<LayoutVertex>> entry : byPkg.entrySet()) {
            PackageEntry pkgEntry = index.getPackage(entry.getKey());
            if (pkgEntry == null) {
                continue;
            }
            List<LayoutVertex> children = entry.getValue();
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxRight = Double.NEGATIVE_INFINITY;
            double maxBottom = Double.NEGATIVE_INFINITY;
            List<GraphTable> childTables = new ArrayList<>(children.size());

            String visualPkgId = "p" + (pkgIdx++);
            listIdMap.computeIfAbsent(pkgEntry.getId(), k -> new ArrayList<>()).add(visualPkgId);

            for (LayoutVertex child : children) {
                minX = Math.min(minX, child.x);
                minY = Math.min(minY, child.y);
                maxRight = Math.max(maxRight, child.x + child.width);
                maxBottom = Math.max(maxBottom, child.y + child.height);
                child.graphTable.setParent(visualPkgId);
                childTables.add(child.graphTable);
            }

            String rawPkgName = pkgEntry.getName();
            String shortPkgName = TextMetricsHelper.getShortLabel(rawPkgName, TextMetricsHelper.SHORT_LABEL_LIMIT);
            if (!shortPkgName.equals(rawPkgName)) {
                tooltip.put(visualPkgId, rawPkgName);
            }
            double headerWidth = TextMetricsHelper.measureHeaderWidth(TextMetricsHelper.get50Char(rawPkgName)) + 32.0;
            double boxX = minX - PACKAGE_PAD_X;
            double boxY = minY - PACKAGE_PAD_TOP;
            double boxWidth = Math.max(headerWidth, (maxRight - minX) + PACKAGE_PAD_X * 2.0);
            double boxHeight = (maxBottom - minY) + PACKAGE_PAD_TOP + PACKAGE_PAD_BOTTOM;

            GraphPackage gp = new GraphPackage();
            gp.setId(visualPkgId);
            gp.setName(pkgEntry.getName());
            gp.setType(pkgEntry.getType());
            gp.setServer(pkgEntry.getServer());
            gp.setDatabase(pkgEntry.getDatabase());
            gp.setSchema(pkgEntry.getSchema());
            gp.setX(round2(boxX));
            gp.setY(round2(boxY));
            gp.setWidth(round2(boxWidth));
            gp.setHeight(round2(boxHeight));
            gp.setTables(childTables);

            GraphLabel pkgLabel = new GraphLabel(shortPkgName, 0.0, 0.0, round2(boxWidth), 22.0);
            pkgLabel.setFontFamily(TextMetricsHelper.GRAPHML_FONT_FAMILY);
            pkgLabel.setFontSize(TextMetricsHelper.HEADER_FONT_SIZE);
            gp.setLabel(pkgLabel);
            packages.add(gp);
        }
        return packages;
    }

    private void normalizePositiveCanvas(List<LayoutVertex> realVertices, List<GraphPackage> packages) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        for (LayoutVertex v : realVertices) {
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
        }
        for (GraphPackage gp : packages) {
            minX = Math.min(minX, gp.getX());
            minY = Math.min(minY, gp.getY());
        }
        double shiftX = minX < CANVAS_PADDING ? (CANVAS_PADDING - minX) : 0.0;
        double shiftY = minY < CANVAS_PADDING ? (CANVAS_PADDING - minY) : 0.0;
        if (shiftX > 0.0 || shiftY > 0.0) {
            for (LayoutVertex v : realVertices) {
                v.x += shiftX;
                v.y += shiftY;
            }
            for (GraphPackage gp : packages) {
                gp.setX(round2(gp.getX() + shiftX));
                gp.setY(round2(gp.getY() + shiftY));
            }
        }
    }

    private SqlflowJSON emptyResult(Orientation orientation) {
        GraphElements elements = new GraphElements(List.of(), List.of(), List.of());
        Graph g = new Graph();
        g.setElements(elements);
        g.setWidth(320.0);
        g.setHeight(200.0);
        g.setOrientation(orientation);
        g.setListIdMap(Map.of());
        g.setRelationshipIdMap(Map.of());
        g.setTooltip(Map.of());

        SqlflowJSON res = new SqlflowJSON();
        res.setMode("global");
        res.setGraph(g);
        res.setElements(elements);
        res.setWidth(320.0);
        res.setHeight(200.0);
        res.setOrientation(orientation);
        res.setListIdMap(Map.of());
        res.setRelationshipIdMap(Map.of());
        res.setTooltip(Map.of());
        return res;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
