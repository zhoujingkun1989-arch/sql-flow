package io.sqlflow.server.spi;

import io.sqlflow.contracts.graph.DbObjectPosition;
import io.sqlflow.contracts.graph.DboColorPalette;
import io.sqlflow.contracts.graph.LeftMostSourceTable;
import io.sqlflow.contracts.graph.SqlflowJSON;
import io.sqlflow.contracts.lineage.Column;
import io.sqlflow.contracts.lineage.Coordinate;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.Orientation;
import io.sqlflow.contracts.lineage.Relationship;
import io.sqlflow.contracts.lineage.RelationshipElement;
import io.sqlflow.contracts.lineage.RelationshipType;
import io.sqlflow.contracts.lineage.SqlInfo;
import io.sqlflow.contracts.spi.GraphLayoutSpi;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Deterministic layered graph layout, BFS slicer, coordinate highlighter, GraphML exporter,
 * and headless Java2D PNG renderer implementing {@link GraphLayoutSpi}.
 */
public class FallbackGraphLayoutSpi implements GraphLayoutSpi {

    private static final double NODE_WIDTH = 220.0;
    private static final double HEADER_HEIGHT = 30.0;
    private static final double COL_HEIGHT = 22.0;
    private static final double H_SPACING = 110.0;
    private static final double V_SPACING = 44.0;
    private static final double PADDING = 40.0;

    @Override
    public SqlflowJSON layoutGraph(GraphLayoutRequest request) {
        DataFlow df = request != null ? request.getDataFlow() : null;
        Orientation orientation = request != null && request.getOrientation() != null
                ? request.getOrientation()
                : Orientation.LEFT_TO_RIGHT;
        boolean tableLevel = request != null && request.isTableLevel();
        int maxCols = request != null && request.getMaxColumnsVisible() > 0 ? request.getMaxColumnsVisible() : 50;

        SqlflowJSON out = new SqlflowJSON();
        out.setMode("full");
        out.setOrientation(orientation);
        out.setTableLevel(tableLevel);
        if (df == null) {
            SqlflowJSON.GraphElements empty = new SqlflowJSON.GraphElements(List.of(), List.of(), List.of());
            out.setElements(empty);
            out.setWidth(200.0);
            out.setHeight(120.0);
            return out;
        }
        out.setSqlflow(df);
        out.setSummary(df.getSummary() != null ? df.getSummary() : CatalogUtils.computeSummary(df));

        List<DbObject> objects = CatalogUtils.collectAllDbObjects(df);
        Map<String, DbObject> objById = new LinkedHashMap<>();
        for (DbObject obj : objects) {
            if (obj.getId() != null) {
                objById.put(obj.getId(), obj);
            }
        }

        List<Relationship> rels = df.getRelationships() != null ? df.getRelationships() : List.of();
        Set<String> allowedTypes = null;
        if (request != null && request.getRelationTypes() != null && !request.getRelationTypes().isEmpty()) {
            allowedTypes = new LinkedHashSet<>();
            for (RelationshipType rt : request.getRelationTypes()) {
                allowedTypes.add(rt.wireName().toLowerCase(Locale.ROOT));
            }
        }

        // Compute DAG topological rank per table
        Map<String, Set<String>> outgoingTables = new LinkedHashMap<>();
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        for (String id : objById.keySet()) {
            outgoingTables.put(id, new LinkedHashSet<>());
            inDegree.put(id, 0);
        }

        List<Relationship> activeRels = new ArrayList<>();
        for (Relationship rel : rels) {
            if (rel == null) {
                continue;
            }
            if (allowedTypes != null
                    && (rel.getType() == null || !allowedTypes.contains(rel.getType().toLowerCase(Locale.ROOT)))) {
                continue;
            }
            activeRels.add(rel);
            String tgtTableId = rel.getTarget() != null ? rel.getTarget().getParentId() : null;
            if (rel.getSources() != null && tgtTableId != null && objById.containsKey(tgtTableId)) {
                for (RelationshipElement src : rel.getSources()) {
                    String srcTableId = src != null ? src.getParentId() : null;
                    if (srcTableId != null
                            && !srcTableId.equals(tgtTableId)
                            && objById.containsKey(srcTableId)
                            && outgoingTables.get(srcTableId).add(tgtTableId)) {
                        inDegree.merge(tgtTableId, 1, Integer::sum);
                    }
                }
            }
        }

        Map<String, Integer> layerById = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) {
                layerById.put(e.getKey(), 0);
                queue.add(e.getKey());
            }
        }
        while (!queue.isEmpty()) {
            String u = queue.poll();
            int uLayer = layerById.getOrDefault(u, 0);
            for (String v : outgoingTables.getOrDefault(u, Set.of())) {
                layerById.merge(v, uLayer + 1, Math::max);
                int deg = inDegree.merge(v, -1, Integer::sum);
                if (deg == 0) {
                    queue.add(v);
                }
            }
        }
        for (String id : objById.keySet()) {
            layerById.putIfAbsent(id, 0);
        }

        Map<Integer, List<DbObject>> layers = new LinkedHashMap<>();
        for (DbObject obj : objects) {
            int l = layerById.getOrDefault(obj.getId(), 0);
            layers.computeIfAbsent(l, k -> new ArrayList<>()).add(obj);
        }

        List<SqlflowJSON.GraphTable> graphTables = new ArrayList<>();
        Map<String, SqlflowJSON.GraphTable> graphTableById = new LinkedHashMap<>();
        Map<String, SqlflowJSON.PortPoint> portCenterById = new LinkedHashMap<>();
        Map<String, List<String>> listIdMap = new LinkedHashMap<>();
        Map<String, String> tooltip = new LinkedHashMap<>();
        List<String> dbObjIds = new ArrayList<>();

        double maxWidth = 200.0;
        double maxHeight = 140.0;
        boolean vertical = orientation == Orientation.TOP_TO_BOTTOM || orientation == Orientation.BOTTOM_TO_TOP;

        for (Map.Entry<Integer, List<DbObject>> entry : layers.entrySet()) {
            int layerIdx = entry.getKey();
            double cursorOffset = PADDING;
            for (DbObject obj : entry.getValue()) {
                SqlflowJSON.GraphTable gt = new SqlflowJSON.GraphTable();
                gt.setId(obj.getId());
                gt.setName(obj.getName());
                String disp = obj.getDisplayName() != null ? obj.getDisplayName() : obj.getName();
                gt.setDisplayName(disp);
                gt.setAlias(obj.getAlias());
                gt.setType(obj.getType() != null ? obj.getType() : "table");
                gt.setSubType(obj.getSubType());
                gt.setServer(obj.getServer());
                gt.setDatabase(obj.getDatabase());
                gt.setSchema(obj.getSchema());

                List<SqlflowJSON.GraphColumn> gCols = new ArrayList<>();
                List<String> colIds = new ArrayList<>();
                if (!tableLevel && obj.getColumns() != null) {
                    int visibleCount = Math.min(maxCols, obj.getColumns().size());
                    if (obj.getColumns().size() > maxCols) {
                        gt.setMore(Boolean.TRUE);
                    }
                    for (int ci = 0; ci < visibleCount; ci++) {
                        Column col = obj.getColumns().get(ci);
                        if (col == null) {
                            continue;
                        }
                        SqlflowJSON.GraphColumn gc = new SqlflowJSON.GraphColumn();
                        gc.setId(col.getId());
                        gc.setName(col.getName());
                        gc.setDisplayName(col.getDisplayName() != null ? col.getDisplayName() : col.getName());
                        gc.setDataType(col.getDataType());
                        gc.setX(0);
                        gc.setY(HEADER_HEIGHT + ci * COL_HEIGHT);
                        gc.setWidth(NODE_WIDTH);
                        gc.setHeight(COL_HEIGHT);
                        gc.setLabel(new SqlflowJSON.GraphLabel(col.getName(), 8, 4, NODE_WIDTH - 16, COL_HEIGHT - 4));
                        gc.setPrimaryKey(col.getPrimaryKey());
                        gc.setUnqiueKey(col.getUnqiueKey());
                        gc.setForeignKey(col.getForeignKey());
                        gc.setIndexKey(col.getIndexKey());
                        gc.setNullable(col.getNullable());
                        gc.setStar(col.getStar());
                        gCols.add(gc);
                        if (col.getId() != null) {
                            colIds.add(col.getId());
                        }
                    }
                }
                gt.setColumns(gCols);

                double nodeH = HEADER_HEIGHT + gCols.size() * COL_HEIGHT;
                double nodeX = vertical ? cursorOffset : PADDING + layerIdx * (NODE_WIDTH + H_SPACING);
                double nodeY = vertical ? PADDING + layerIdx * (160.0 + V_SPACING) : cursorOffset;
                gt.setX(nodeX);
                gt.setY(nodeY);
                gt.setWidth(NODE_WIDTH);
                gt.setHeight(nodeH);
                gt.setLabel(new SqlflowJSON.GraphLabel(disp, 8, 6, NODE_WIDTH - 16, HEADER_HEIGHT - 8));

                graphTables.add(gt);
                if (gt.getId() != null) {
                    dbObjIds.add(gt.getId());
                    graphTableById.put(gt.getId(), gt);
                    listIdMap.put(gt.getId(), colIds);
                    tooltip.put(gt.getId(), (gt.getDatabase() != null ? gt.getDatabase() + "." : "") + disp);
                    portCenterById.put(gt.getId(), new SqlflowJSON.PortPoint(nodeX + NODE_WIDTH / 2.0, nodeY + HEADER_HEIGHT / 2.0));
                }
                for (SqlflowJSON.GraphColumn gc : gCols) {
                    if (gc.getId() != null) {
                        portCenterById.put(
                                gc.getId(),
                                new SqlflowJSON.PortPoint(nodeX + NODE_WIDTH / 2.0, nodeY + gc.getY() + COL_HEIGHT / 2.0));
                    }
                }

                cursorOffset += (vertical ? NODE_WIDTH + H_SPACING : nodeH + V_SPACING);
                maxWidth = Math.max(maxWidth, nodeX + NODE_WIDTH + PADDING);
                maxHeight = Math.max(maxHeight, nodeY + nodeH + PADDING);
            }
        }

        List<SqlflowJSON.GraphEdge> graphEdges = new ArrayList<>();
        Map<String, List<String>> relationshipIdMap = new LinkedHashMap<>();
        Set<String> dedupEdgeKeys = new LinkedHashSet<>();
        int edgeSeq = 1;

        for (Relationship rel : activeRels) {
            RelationshipElement tgt = rel.getTarget();
            List<RelationshipElement> sources = rel.getSources();
            if (tgt == null && rel.getCaller() != null && rel.getCallees() != null) {
                // Procedure call relationship
                sources = List.of(rel.getCaller());
                tgt = rel.getCallees().isEmpty() ? null : rel.getCallees().get(0);
            }
            if (tgt == null || sources == null) {
                continue;
            }
            String tgtPort = tableLevel || tgt.getId() == null || !portCenterById.containsKey(tgt.getId())
                    ? tgt.getParentId()
                    : tgt.getId();
            if (tgtPort == null || !portCenterById.containsKey(tgtPort)) {
                continue;
            }
            for (RelationshipElement src : sources) {
                if (src == null) {
                    continue;
                }
                String srcPort = tableLevel || src.getId() == null || !portCenterById.containsKey(src.getId())
                        ? src.getParentId()
                        : src.getId();
                if (srcPort == null || !portCenterById.containsKey(srcPort) || srcPort.equals(tgtPort)) {
                    continue;
                }
                String dedupKey = srcPort + "->" + tgtPort + ":" + rel.getType();
                if (!dedupEdgeKeys.add(dedupKey)) {
                    relationshipIdMap
                            .computeIfAbsent(srcPort + "->" + tgtPort, k -> new ArrayList<>())
                            .add(rel.getId());
                    continue;
                }
                SqlflowJSON.GraphEdge ge = new SqlflowJSON.GraphEdge();
                String edgeId = "edge_" + (edgeSeq++);
                ge.setId(edgeId);
                ge.setSourceId(srcPort);
                ge.setTargetId(tgtPort);
                ge.setRelationId(rel.getId());
                ge.setType(rel.getType() != null ? rel.getType() : "fdd");
                ge.setEffectType(rel.getEffectType());

                SqlflowJSON.PortPoint p1 = portCenterById.get(srcPort);
                SqlflowJSON.PortPoint p2 = portCenterById.get(tgtPort);
                SqlflowJSON.GraphTable srcTable = graphTableById.get(src.getParentId() != null ? src.getParentId() : srcPort);
                SqlflowJSON.GraphTable tgtTable = graphTableById.get(tgt.getParentId() != null ? tgt.getParentId() : tgtPort);
                double startX = srcTable != null ? srcTable.getX() + srcTable.getWidth() : p1.getX();
                double endX = tgtTable != null ? tgtTable.getX() : p2.getX();
                double midX = (startX + endX) / 2.0;
                ge.setPoints(List.of(
                        new SqlflowJSON.PortPoint(startX, p1.getY()),
                        new SqlflowJSON.PortPoint(midX, p1.getY()),
                        new SqlflowJSON.PortPoint(midX, p2.getY()),
                        new SqlflowJSON.PortPoint(endX, p2.getY())));
                graphEdges.add(ge);
                if (rel.getId() != null) {
                    relationshipIdMap.computeIfAbsent(edgeId, k -> new ArrayList<>()).add(rel.getId());
                }
            }
        }

        SqlflowJSON.GraphElements elements = new SqlflowJSON.GraphElements(graphTables, graphEdges, List.of());
        out.setDbObjIds(dbObjIds);
        out.setElements(elements);
        out.setWidth(maxWidth);
        out.setHeight(maxHeight);
        out.setRelationshipIdMap(relationshipIdMap);
        out.setListIdMap(listIdMap);
        out.setTooltip(tooltip);

        SqlflowJSON.Graph nested = new SqlflowJSON.Graph();
        nested.setElements(elements);
        nested.setWidth(maxWidth);
        nested.setHeight(maxHeight);
        nested.setOrientation(orientation);
        nested.setRelationshipIdMap(relationshipIdMap);
        nested.setListIdMap(listIdMap);
        nested.setTooltip(tooltip);

        if (request != null && request.isIncludeGraphML()) {
            String gml = exportGraphML(out);
            out.setGraphML(gml);
            nested.setGraphML(gml);
        }
        if (request != null && request.isIncludeSvg()) {
            String svg = renderSimpleSvg(out);
            out.setSvg(svg);
            nested.setSvg(svg);
        }
        out.setGraph(nested);
        return out;
    }

    @Override
    public SlicingResult sliceUpstream(GraphSliceRequest request) {
        return sliceBfs(request, true);
    }

    @Override
    public SlicingResult sliceDownstream(GraphSliceRequest request) {
        return sliceBfs(request, false);
    }

    private SlicingResult sliceBfs(GraphSliceRequest request, boolean upstream) {
        DataFlow df = request != null ? request.getDataFlow() : null;
        if (df == null) {
            DataFlow empty = new DataFlow();
            return new SlicingResult(empty, layoutGraph(GraphLayoutRequest.of(empty, Orientation.LEFT_TO_RIGHT)), List.of());
        }
        List<DbObject> allObjects = CatalogUtils.collectAllDbObjects(df);
        Map<String, DbObject> objById = new LinkedHashMap<>();
        for (DbObject o : allObjects) {
            if (o.getId() != null) {
                objById.put(o.getId(), o);
            }
        }

        List<String> rootTableIds = matchRootTableIds(request, allObjects);
        Set<String> visitedTables = new LinkedHashSet<>(rootTableIds);
        Deque<IdDepth> queue = new ArrayDeque<>();
        for (String id : rootTableIds) {
            queue.add(new IdDepth(id, 0));
        }

        int maxDepth = request != null && request.getMaxHopDepth() > 0 ? request.getMaxHopDepth() : 20;
        List<Relationship> allRels = df.getRelationships() != null ? df.getRelationships() : List.of();
        Set<String> keptRelIds = new LinkedHashSet<>();

        while (!queue.isEmpty()) {
            IdDepth cur = queue.poll();
            if (cur.depth() >= maxDepth) {
                continue;
            }
            for (Relationship rel : allRels) {
                if (rel == null || rel.getTarget() == null || rel.getSources() == null) {
                    continue;
                }
                String tgtParent = rel.getTarget().getParentId();
                if (upstream) {
                    if (cur.id().equals(tgtParent)) {
                        keptRelIds.add(rel.getId());
                        for (RelationshipElement src : rel.getSources()) {
                            if (src != null && src.getParentId() != null && visitedTables.add(src.getParentId())) {
                                queue.add(new IdDepth(src.getParentId(), cur.depth() + 1));
                            }
                        }
                    }
                } else {
                    for (RelationshipElement src : rel.getSources()) {
                        if (src != null && cur.id().equals(src.getParentId()) && tgtParent != null) {
                            keptRelIds.add(rel.getId());
                            if (visitedTables.add(tgtParent)) {
                                queue.add(new IdDepth(tgtParent, cur.depth() + 1));
                            }
                        }
                    }
                }
            }
        }

        List<DbObject> slicedObjs = new ArrayList<>();
        for (String id : visitedTables) {
            DbObject o = objById.get(id);
            if (o != null) {
                if (request != null && request.isIgnoreRecordSet() && "resultset".equalsIgnoreCase(o.getType())) {
                    continue;
                }
                slicedObjs.add(o);
            }
        }
        List<Relationship> slicedRels = allRels.stream()
                .filter(r -> r != null && r.getId() != null && keptRelIds.contains(r.getId()))
                .toList();

        DataFlow sliced = new DataFlow();
        sliced.setDbvendor(df.getDbvendor());
        sliced.setOrientation(request != null ? request.getOrientation() : Orientation.LEFT_TO_RIGHT);
        sliced.setDbobjs(CatalogUtils.buildCatalog(null, df.getDbvendor(), slicedObjs));
        sliced.setRelationships(slicedRels);
        sliced.setSummary(CatalogUtils.computeSummary(sliced));

        SqlflowJSON graph = null;
        if (request == null || request.isLayoutResult()) {
            GraphLayoutRequest layoutReq = GraphLayoutRequest.of(
                    sliced, request != null ? request.getOrientation() : Orientation.LEFT_TO_RIGHT);
            graph = layoutGraph(layoutReq);
        }
        return new SlicingResult(sliced, graph, rootTableIds);
    }

    private List<String> matchRootTableIds(GraphSliceRequest req, List<DbObject> allObjects) {
        if (req == null) {
            return allObjects.stream().map(DbObject::getId).toList();
        }
        List<String> matched = new ArrayList<>();
        if (req.getDbObjIds() != null && !req.getDbObjIds().isEmpty()) {
            Set<String> wanted = new LinkedHashSet<>(req.getDbObjIds());
            for (DbObject o : allObjects) {
                if (wanted.contains(o.getId())) {
                    matched.add(o.getId());
                } else if (o.getColumns() != null) {
                    for (Column c : o.getColumns()) {
                        if (c != null && wanted.contains(c.getId())) {
                            matched.add(o.getId());
                            break;
                        }
                    }
                }
            }
        }
        if (matched.isEmpty() && req.getTable() != null && !req.getTable().isBlank()) {
            String targetTable = CatalogUtils.stripQuotes(req.getTable());
            for (DbObject o : allObjects) {
                boolean nameMatch = o.getName() != null
                        && (o.getName().equalsIgnoreCase(targetTable)
                                || (targetTable.contains(".")
                                        && targetTable.substring(targetTable.lastIndexOf('.') + 1).equalsIgnoreCase(o.getName())));
                boolean schemaMatch = req.getSchema() == null
                        || req.getSchema().isBlank()
                        || (o.getSchema() != null && o.getSchema().equalsIgnoreCase(req.getSchema()));
                boolean dbMatch = req.getDatabase() == null
                        || req.getDatabase().isBlank()
                        || (o.getDatabase() != null && o.getDatabase().equalsIgnoreCase(req.getDatabase()));
                if (nameMatch && schemaMatch && dbMatch) {
                    matched.add(o.getId());
                }
            }
        }
        if (matched.isEmpty() && !allObjects.isEmpty()) {
            matched.add(allObjects.get(0).getId());
        }
        return matched;
    }

    @Override
    public List<DbObjectPosition> getSelectedDbObjectInfo(SelectedObjectRequest request) {
        if (request == null || request.getDataFlow() == null) {
            return List.of();
        }
        List<Coordinate> coords = new ArrayList<>();
        List<DbObject> objects = CatalogUtils.collectAllDbObjects(request.getDataFlow());
        for (DbObject obj : objects) {
            if (request.getDbObjId() != null && request.getDbObjId().equals(obj.getId()) && obj.getCoordinates() != null) {
                coords.addAll(obj.getCoordinates());
            }
            if (obj.getColumns() != null) {
                for (Column col : obj.getColumns()) {
                    if (col != null
                            && ((request.getColumnId() != null && request.getColumnId().equals(col.getId()))
                                    || (request.getDbObjId() != null && request.getDbObjId().equals(col.getId())))
                            && col.getCoordinates() != null) {
                        coords.addAll(col.getCoordinates());
                    }
                }
            }
        }
        if (request.getRelationIds() != null && request.getDataFlow().getRelationships() != null) {
            Set<String> wantedRels = new LinkedHashSet<>(request.getRelationIds());
            for (Relationship rel : request.getDataFlow().getRelationships()) {
                if (rel != null && rel.getId() != null && wantedRels.contains(rel.getId())) {
                    if (rel.getTarget() != null && rel.getTarget().getCoordinates() != null) {
                        coords.addAll(rel.getTarget().getCoordinates());
                    }
                    if (rel.getSources() != null) {
                        for (RelationshipElement src : rel.getSources()) {
                            if (src != null && src.getCoordinates() != null) {
                                coords.addAll(src.getCoordinates());
                            }
                        }
                    }
                }
            }
        }

        List<SqlInfo> sqlUnits = request.getSqlUnits() != null ? request.getSqlUnits() : List.of();
        String defaultFile = !sqlUnits.isEmpty() && sqlUnits.get(0).getFileName() != null
                ? sqlUnits.get(0).getFileName()
                : "input.sql";
        String defaultSql = !sqlUnits.isEmpty() ? sqlUnits.get(0).getSql() : "";

        List<DbObjectPosition.PositionPoint> points = new ArrayList<>();
        for (Coordinate c : coords) {
            if (c != null && c.getX() >= 1 && c.getY() >= 1) {
                points.add(new DbObjectPosition.PositionPoint((int) c.getX(), (int) c.getY()));
            }
        }
        if (points.isEmpty()) {
            points.add(new DbObjectPosition.PositionPoint(1, 1));
            points.add(new DbObjectPosition.PositionPoint(1, Math.max(2, Math.min(20, defaultSql != null ? defaultSql.length() : 5))));
        }
        return List.of(new DbObjectPosition(defaultFile, defaultSql, 0, points));
    }

    @Override
    public LeftMostSourceTable computeLeftMostSourceTable(
            DataFlow dataFlow, String targetTableId, boolean ignoreRecordSet) {
        LeftMostSourceTable out = new LeftMostSourceTable();
        if (dataFlow == null) {
            out.setElements(List.of());
            out.setTargetTables(List.of());
            out.setAttributes(List.of());
            return out;
        }
        List<DbObject> objects = CatalogUtils.collectAllDbObjects(dataFlow);
        Map<String, DbObject> objById = new LinkedHashMap<>();
        Set<String> hasUpstream = new LinkedHashSet<>();
        for (DbObject o : objects) {
            if (o.getId() != null) {
                objById.put(o.getId(), o);
            }
        }
        List<Relationship> rels = dataFlow.getRelationships() != null ? dataFlow.getRelationships() : List.of();
        for (Relationship r : rels) {
            if (r != null && r.getTarget() != null && r.getTarget().getParentId() != null) {
                hasUpstream.add(r.getTarget().getParentId());
            }
        }

        List<LeftMostSourceTable.TargetTable> targetTables = new ArrayList<>();
        List<LeftMostSourceTable.TableColumn> attributes = new ArrayList<>();

        for (DbObject obj : objects) {
            if (ignoreRecordSet && "resultset".equalsIgnoreCase(obj.getType())) {
                continue;
            }
            if (targetTableId != null
                    && !targetTableId.isBlank()
                    && !targetTableId.equals(obj.getId())
                    && !targetTableId.equalsIgnoreCase(obj.getName())) {
                continue;
            }
            if (!hasUpstream.contains(obj.getId()) && targetTableId == null) {
                continue;
            }

            LeftMostSourceTable.TargetTable tt = new LeftMostSourceTable.TargetTable();
            tt.setId(obj.getId());
            tt.setServer(obj.getServer());
            tt.setDatabase(obj.getDatabase());
            tt.setDatabaseName(obj.getDatabase());
            tt.setSchema(obj.getSchema());
            tt.setSchemaName(obj.getSchema());
            tt.setName(obj.getName());
            tt.setRelationName(obj.getName());
            tt.setType(obj.getType());

            List<LeftMostSourceTable.TargetColumn> tCols = new ArrayList<>();
            if (obj.getColumns() != null) {
                for (Column col : obj.getColumns()) {
                    if (col == null || "system".equalsIgnoreCase(col.getSource())) {
                        continue;
                    }
                    LeftMostSourceTable.TargetColumn tc = new LeftMostSourceTable.TargetColumn();
                    tc.setId(col.getId());
                    tc.setName(col.getName());
                    tc.setDataType(col.getDataType());
                    List<LeftMostSourceTable.SourceTableColumn> rootSources =
                            traceRootSourceColumns(col.getId(), obj.getId(), rels, objById, hasUpstream);
                    tc.setSourceColumns(rootSources);
                    tCols.add(tc);

                    LeftMostSourceTable.TableColumn attr = new LeftMostSourceTable.TableColumn();
                    attr.setServer(obj.getServer());
                    attr.setDatabaseName(obj.getDatabase());
                    attr.setSchemaName(obj.getSchema());
                    attr.setRelationName(obj.getName());
                    attr.setAttributeName(col.getName());
                    attributes.add(attr);
                }
            }
            tt.setColumns(tCols);
            targetTables.add(tt);
        }

        out.setElements(targetTables);
        out.setTargetTables(targetTables);
        out.setAttributes(attributes);
        return out;
    }

    private List<LeftMostSourceTable.SourceTableColumn> traceRootSourceColumns(
            String colId,
            String tableId,
            List<Relationship> rels,
            Map<String, DbObject> objById,
            Set<String> hasUpstream) {
        List<LeftMostSourceTable.SourceTableColumn> result = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        if (colId != null) {
            queue.add(colId);
            visited.add(colId);
        }
        while (!queue.isEmpty()) {
            String curCol = queue.poll();
            boolean expanded = false;
            for (Relationship rel : rels) {
                if (rel == null || rel.getTarget() == null || rel.getSources() == null) {
                    continue;
                }
                if (curCol.equals(rel.getTarget().getId())) {
                    for (RelationshipElement src : rel.getSources()) {
                        if (src == null) {
                            continue;
                        }
                        expanded = true;
                        String srcTableId = src.getParentId();
                        if (src.getId() != null && hasUpstream.contains(srcTableId) && visited.add(src.getId())) {
                            queue.add(src.getId());
                        } else {
                            DbObject srcObj = srcTableId != null ? objById.get(srcTableId) : null;
                            LeftMostSourceTable.SourceTableColumn stc = new LeftMostSourceTable.SourceTableColumn();
                            stc.setDatabase(srcObj != null ? srcObj.getDatabase() : null);
                            stc.setSchema(srcObj != null ? srcObj.getSchema() : null);
                            stc.setTableName(src.getParentName() != null ? src.getParentName() : (srcObj != null ? srcObj.getName() : null));
                            stc.setName(src.getColumn() != null ? src.getColumn() : src.getName());
                            stc.setType(srcObj != null ? srcObj.getType() : "table");
                            stc.setRelationType(rel.getType());
                            result.add(stc);
                        }
                    }
                }
            }
            if (!expanded && !curCol.equals(colId)) {
                // Leaf column reached
            }
        }
        return result;
    }

    @Override
    public DataFlow computeOutgoingClosure(DataFlow dataFlow, List<String> rootObjectIds, int maxDepth) {
        GraphSliceRequest req = new GraphSliceRequest();
        req.setDataFlow(dataFlow);
        req.setDbObjIds(rootObjectIds);
        req.setMaxHopDepth(maxDepth > 0 ? maxDepth : 20);
        req.setLayoutResult(false);
        return sliceDownstream(req).getSlicedDataFlow();
    }

    @Override
    public String exportGraphML(SqlflowJSON graph) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">\n");
        sb.append("  <key id=\"d_name\" for=\"node\" attr.name=\"name\" attr.type=\"string\"/>\n");
        sb.append("  <key id=\"d_type\" for=\"node\" attr.name=\"type\" attr.type=\"string\"/>\n");
        sb.append("  <key id=\"e_type\" for=\"edge\" attr.name=\"relationType\" attr.type=\"string\"/>\n");
        sb.append("  <graph id=\"G\" edgedefault=\"directed\">\n");

        SqlflowJSON.GraphElements els = graph != null ? graph.resolveElements() : null;
        if (els != null) {
            if (els.getTables() != null) {
                for (SqlflowJSON.GraphTable gt : els.getTables()) {
                    sb.append("    <node id=\"").append(escapeXml(gt.getId())).append("\">\n");
                    sb.append("      <data key=\"d_name\">").append(escapeXml(gt.getName())).append("</data>\n");
                    sb.append("      <data key=\"d_type\">").append(escapeXml(gt.getType())).append("</data>\n");
                    if (gt.getColumns() != null) {
                        for (SqlflowJSON.GraphColumn gc : gt.getColumns()) {
                            sb.append("      <port name=\"").append(escapeXml(gc.getId())).append("\"/>\n");
                        }
                    }
                    sb.append("    </node>\n");
                    if (gt.getColumns() != null) {
                        for (SqlflowJSON.GraphColumn gc : gt.getColumns()) {
                            sb.append("    <node id=\"").append(escapeXml(gc.getId())).append("\">\n");
                            sb.append("      <data key=\"d_name\">").append(escapeXml(gc.getName())).append("</data>\n");
                            sb.append("      <data key=\"d_type\">column</data>\n");
                            sb.append("    </node>\n");
                        }
                    }
                }
            }
            if (els.getEdges() != null) {
                for (SqlflowJSON.GraphEdge ge : els.getEdges()) {
                    sb.append("    <edge id=\"").append(escapeXml(ge.getId()))
                            .append("\" source=\"").append(escapeXml(ge.getSourceId()))
                            .append("\" target=\"").append(escapeXml(ge.getTargetId())).append("\">\n");
                    sb.append("      <data key=\"e_type\">").append(escapeXml(ge.getType())).append("</data>\n");
                    sb.append("    </edge>\n");
                }
            }
        }
        sb.append("  </graph>\n</graphml>\n");
        return sb.toString();
    }

    @Override
    public byte[] renderPng(SqlflowJSON graph, PngRenderOptions options) {
        PngRenderOptions opts = options != null ? options : new PngRenderOptions();
        double scale = opts.getScale() > 0 ? opts.getScale() : 1.0;
        int pad = Math.max(8, opts.getPadding());
        int baseW = graph != null && graph.getWidth() != null ? (int) Math.ceil(graph.getWidth()) : 480;
        int baseH = graph != null && graph.getHeight() != null ? (int) Math.ceil(graph.getHeight()) : 320;
        int imgW = Math.max(120, Math.min(4096, (int) Math.ceil((baseW + pad * 2) * scale)));
        int imgH = Math.max(80, Math.min(4096, (int) Math.ceil((baseH + pad * 2) * scale)));

        BufferedImage image = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.scale(scale, scale);

            g2.setColor(Color.decode(DboColorPalette.CANVAS_BACKGROUND));
            g2.fillRect(0, 0, baseW + pad * 2, baseH + pad * 2);
            g2.translate(pad, pad);

            SqlflowJSON.GraphElements els = graph != null ? graph.resolveElements() : null;
            if (els != null) {
                if (els.getEdges() != null) {
                    g2.setColor(Color.decode(DboColorPalette.EDGE_STROKE));
                    for (SqlflowJSON.GraphEdge edge : els.getEdges()) {
                        List<SqlflowJSON.PortPoint> pts = edge.getPoints();
                        if (pts != null && pts.size() >= 2) {
                            for (int i = 0; i + 1 < pts.size(); i++) {
                                g2.drawLine(
                                        (int) pts.get(i).getX(),
                                        (int) pts.get(i).getY(),
                                        (int) pts.get(i + 1).getX(),
                                        (int) pts.get(i + 1).getY());
                            }
                        }
                    }
                }
                if (els.getTables() != null) {
                    Font headerFont = new Font("SansSerif", Font.BOLD, 12);
                    Font colFont = new Font("SansSerif", Font.PLAIN, 11);
                    for (SqlflowJSON.GraphTable gt : els.getTables()) {
                        int x = (int) gt.getX();
                        int y = (int) gt.getY();
                        int w = Math.max(100, (int) gt.getWidth());
                        int h = Math.max(30, (int) gt.getHeight());

                        g2.setColor(Color.decode(DboColorPalette.COLUMN_BACKGROUND));
                        g2.fillRect(x, y, w, h);

                        String headerHex = DboColorPalette.resolveHeaderColor(gt.getType(), gt.getSubType());
                        g2.setColor(Color.decode(headerHex));
                        g2.fillRect(x, y, w, (int) HEADER_HEIGHT);

                        g2.setColor(Color.WHITE);
                        g2.setFont(headerFont);
                        String title = gt.getDisplayName() != null ? gt.getDisplayName() : gt.getName();
                        if (title != null) {
                            g2.drawString(title, x + 8, y + 19);
                        }

                        if (gt.getColumns() != null) {
                            g2.setFont(colFont);
                            for (SqlflowJSON.GraphColumn gc : gt.getColumns()) {
                                int cy = y + (int) gc.getY();
                                g2.setColor(Color.decode(DboColorPalette.COLUMN_DIVIDER));
                                g2.drawLine(x, cy, x + w, cy);
                                g2.setColor(Color.DARK_GRAY);
                                if (gc.getName() != null) {
                                    g2.drawString(gc.getName(), x + 8, cy + 15);
                                }
                            }
                        }

                        g2.setColor(Color.decode(DboColorPalette.CONTAINER_BORDER));
                        g2.drawRect(x, y, w, h);
                    }
                }
            }
        } finally {
            g2.dispose();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", baos);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode PNG image", e);
        }
        return baos.toByteArray();
    }

    private String renderSimpleSvg(SqlflowJSON graph) {
        int w = graph.getWidth() != null ? graph.getWidth().intValue() : 400;
        int h = graph.getHeight() != null ? graph.getHeight().intValue() : 250;
        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(w).append("\" height=\"").append(h).append("\">");
        SqlflowJSON.GraphElements els = graph.resolveElements();
        if (els != null && els.getTables() != null) {
            for (SqlflowJSON.GraphTable gt : els.getTables()) {
                String fill = DboColorPalette.resolveHeaderColor(gt.getType(), gt.getSubType());
                sb.append("<rect x=\"").append((int) gt.getX())
                        .append("\" y=\"").append((int) gt.getY())
                        .append("\" width=\"").append((int) gt.getWidth())
                        .append("\" height=\"").append((int) HEADER_HEIGHT)
                        .append("\" fill=\"").append(fill).append("\"/>");
            }
        }
        sb.append("</svg>");
        return sb.toString();
    }

    private static String escapeXml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private record IdDepth(String id, int depth) {
    }
}
