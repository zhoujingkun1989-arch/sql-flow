package io.sqlflow.graph.slice;

import io.sqlflow.contracts.graph.DbObjectPosition;
import io.sqlflow.contracts.graph.DbObjectPosition.PositionPoint;
import io.sqlflow.contracts.lineage.Coordinate;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.Relationship;
import io.sqlflow.contracts.lineage.RelationshipElement;
import io.sqlflow.contracts.lineage.SqlInfo;
import io.sqlflow.contracts.lineage.Transform;
import io.sqlflow.contracts.spi.GraphLayoutSpi.SelectedObjectRequest;
import io.sqlflow.graph.model.DataFlowIndex;
import io.sqlflow.graph.model.DataFlowIndex.ColumnEntry;
import io.sqlflow.graph.model.DataFlowIndex.NodeEntry;
import io.sqlflow.graph.model.DataFlowIndex.PackageEntry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves 1-based SQL token highlight spans ({@link DbObjectPosition}) across {@link SqlInfo} units
 * for a selected {@code dbObjId}, {@code columnId}, and/or {@code relationIds}
 * ({@code OBL-055}, {@code 02 §4.1 & §5.2}, {@code 00 §3.3 #5}).
 */
public final class SelectedObjectResolver {

    private record SpanPair(PositionPoint start, PositionPoint end) {
    }

    private SelectedObjectResolver() {
    }

    public static List<DbObjectPosition> resolve(SelectedObjectRequest request) {
        if (request == null || request.getDataFlow() == null) {
            return List.of();
        }
        DataFlow dataFlow = request.getDataFlow();
        List<SqlInfo> sqlUnits = request.getSqlUnits() != null ? request.getSqlUnits() : List.of();
        DataFlowIndex index = DataFlowIndex.build(dataFlow);

        List<List<Coordinate>> collectedCoordLists = new ArrayList<>();

        String dbObjId = trimToNull(request.getDbObjId());
        String columnId = trimToNull(request.getColumnId());
        List<String> relationIds = request.getRelationIds();

        // 1. Resolve columnId if specified
        if (columnId != null) {
            ColumnEntry col = index.getColumn(columnId);
            if (col == null && dbObjId != null) {
                NodeEntry parent = index.getNode(dbObjId);
                if (parent != null) {
                    col = parent.findColumnByName(columnId);
                }
            }
            if (col != null) {
                if (!col.getCoordinates().isEmpty()) {
                    collectedCoordLists.add(col.getCoordinates());
                }
                collectMatchingEndpointCoordinates(dataFlow.getRelationships(), null, col.getId(), collectedCoordLists);
            } else {
                collectMatchingEndpointCoordinates(dataFlow.getRelationships(), null, columnId, collectedCoordLists);
            }
        } else if (dbObjId != null) {
            // 2. Resolve dbObjId (table, view, resultset, procedure, process, or package)
            NodeEntry node = index.getNode(dbObjId);
            if (node != null) {
                if (!node.getCoordinates().isEmpty()) {
                    collectedCoordLists.add(node.getCoordinates());
                } else {
                    collectMatchingEndpointCoordinates(
                            dataFlow.getRelationships(), node.getId(), null, collectedCoordLists);
                }
            } else {
                PackageEntry pkg = index.getPackage(dbObjId);
                if (pkg != null) {
                    if (!pkg.getCoordinates().isEmpty()) {
                        collectedCoordLists.add(pkg.getCoordinates());
                    }
                    for (String childId : pkg.getChildNodeIds()) {
                        NodeEntry child = index.getNode(childId);
                        if (child != null && !child.getCoordinates().isEmpty()) {
                            collectedCoordLists.add(child.getCoordinates());
                        }
                    }
                } else {
                    collectMatchingEndpointCoordinates(dataFlow.getRelationships(), dbObjId, null, collectedCoordLists);
                }
            }
        }

        // 3. Resolve relationIds if specified
        if (relationIds != null && !relationIds.isEmpty() && dataFlow.getRelationships() != null) {
            Set<String> targetRelIds = new HashSet<>();
            for (String rid : relationIds) {
                if (rid != null && !rid.isBlank()) {
                    targetRelIds.add(rid.trim());
                }
            }
            for (Relationship rel : dataFlow.getRelationships()) {
                if (rel == null || rel.getId() == null || !targetRelIds.contains(rel.getId())) {
                    continue;
                }
                collectElementCoords(rel.getTarget(), collectedCoordLists);
                if (rel.getSources() != null) {
                    for (RelationshipElement src : rel.getSources()) {
                        collectElementCoords(src, collectedCoordLists);
                    }
                }
                collectElementCoords(rel.getCaller(), collectedCoordLists);
                if (rel.getCallees() != null) {
                    for (RelationshipElement callee : rel.getCallees()) {
                        collectElementCoords(callee, collectedCoordLists);
                    }
                }
                if (rel.getCallCoordinate() != null && !rel.getCallCoordinate().isEmpty()) {
                    collectedCoordLists.add(rel.getCallCoordinate());
                }
            }
        }

        if (collectedCoordLists.isEmpty()) {
            return List.of();
        }

        // Group 1-based [start, end] span pairs by SqlInfo unit index
        Map<Integer, Set<SpanPair>> spansBySqlIndex = new LinkedHashMap<>();
        for (List<Coordinate> coordList : collectedCoordLists) {
            extractSpans(coordList, sqlUnits, spansBySqlIndex);
        }

        if (spansBySqlIndex.isEmpty()) {
            return List.of();
        }

        List<DbObjectPosition> positions = new ArrayList<>(spansBySqlIndex.size());
        for (Map.Entry<Integer, Set<SpanPair>> entry : spansBySqlIndex.entrySet()) {
            int unitIdx = entry.getKey();
            Set<SpanPair> pairs = entry.getValue();
            if (pairs.isEmpty()) {
                continue;
            }
            List<PositionPoint> flatPoints = new ArrayList<>(pairs.size() * 2);
            for (SpanPair sp : pairs) {
                flatPoints.add(sp.start());
                flatPoints.add(sp.end());
            }

            String file = null;
            String sql = null;
            if (unitIdx >= 0 && unitIdx < sqlUnits.size()) {
                SqlInfo unit = sqlUnits.get(unitIdx);
                if (unit != null) {
                    file = unit.getFileName() != null && !unit.getFileName().isBlank()
                            ? unit.getFileName()
                            : unit.getFilePath();
                    sql = unit.getSql();
                }
            }
            positions.add(new DbObjectPosition(file, sql, unitIdx, flatPoints));
        }
        return positions;
    }

    private static void collectMatchingEndpointCoordinates(
            List<Relationship> relationships,
            String targetParentId,
            String targetColId,
            List<List<Coordinate>> sink) {
        if (relationships == null) {
            return;
        }
        for (Relationship rel : relationships) {
            if (rel == null) {
                continue;
            }
            checkAndCollect(rel.getTarget(), targetParentId, targetColId, sink);
            if (rel.getSources() != null) {
                for (RelationshipElement src : rel.getSources()) {
                    checkAndCollect(src, targetParentId, targetColId, sink);
                }
            }
            checkAndCollect(rel.getCaller(), targetParentId, targetColId, sink);
            if (rel.getCallees() != null) {
                for (RelationshipElement callee : rel.getCallees()) {
                    checkAndCollect(callee, targetParentId, targetColId, sink);
                }
            }
        }
    }

    private static void checkAndCollect(
            RelationshipElement el,
            String targetParentId,
            String targetColId,
            List<List<Coordinate>> sink) {
        if (el == null) {
            return;
        }
        if (targetColId != null && targetColId.equals(el.getId())) {
            collectElementCoords(el, sink);
        } else if (targetParentId != null
                && (targetParentId.equals(el.getParentId()) || targetParentId.equals(el.getId()))) {
            collectElementCoords(el, sink);
        }
    }

    private static void collectElementCoords(RelationshipElement el, List<List<Coordinate>> sink) {
        if (el == null) {
            return;
        }
        if (el.getCoordinates() != null && !el.getCoordinates().isEmpty()) {
            sink.add(el.getCoordinates());
        }
        if (el.getTransforms() != null) {
            for (Transform t : el.getTransforms()) {
                if (t != null && t.getCoordinates() != null && !t.getCoordinates().isEmpty()) {
                    sink.add(t.getCoordinates());
                }
            }
        }
    }

    private static void extractSpans(
            List<Coordinate> coords,
            List<SqlInfo> sqlUnits,
            Map<Integer, Set<SpanPair>> spansBySqlIndex) {
        if (coords == null || coords.isEmpty()) {
            return;
        }
        int i = 0;
        while (i < coords.size()) {
            Coordinate c = coords.get(i);
            if (c == null || !c.isOneBasedValid()) {
                i++;
                continue;
            }
            if (c.getX1() != null && c.getY1() != null && c.getX1() >= 1 && c.getY1() >= 1) {
                int unitIdx = matchSqlUnitIndex(c.getHashCode(), sqlUnits);
                spansBySqlIndex
                        .computeIfAbsent(unitIdx, k -> new java.util.LinkedHashSet<>())
                        .add(new SpanPair(
                                new PositionPoint((int) c.getX(), (int) c.getY()),
                                new PositionPoint(c.getX1().intValue(), c.getY1().intValue())));
                i++;
            } else if (i + 1 < coords.size()
                    && coords.get(i + 1) != null
                    && coords.get(i + 1).isOneBasedValid()
                    && coords.get(i + 1).getX1() == null) {
                Coordinate end = coords.get(i + 1);
                String hash = c.getHashCode() != null ? c.getHashCode() : end.getHashCode();
                int unitIdx = matchSqlUnitIndex(hash, sqlUnits);
                spansBySqlIndex
                        .computeIfAbsent(unitIdx, k -> new java.util.LinkedHashSet<>())
                        .add(new SpanPair(
                                new PositionPoint((int) c.getX(), (int) c.getY()),
                                new PositionPoint((int) end.getX(), (int) end.getY())));
                i += 2;
            } else {
                int unitIdx = matchSqlUnitIndex(c.getHashCode(), sqlUnits);
                spansBySqlIndex
                        .computeIfAbsent(unitIdx, k -> new java.util.LinkedHashSet<>())
                        .add(new SpanPair(
                                new PositionPoint((int) c.getX(), (int) c.getY()),
                                new PositionPoint((int) c.getX(), (int) c.getY())));
                i++;
            }
        }
    }

    private static int matchSqlUnitIndex(String hashCode, List<SqlInfo> sqlUnits) {
        if (sqlUnits == null || sqlUnits.isEmpty()) {
            if (hashCode != null && !hashCode.isBlank()) {
                try {
                    int parsed = Integer.parseInt(hashCode.trim());
                    if (parsed >= 0) {
                        return parsed;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            return 0;
        }
        if (hashCode != null && !hashCode.isBlank()) {
            String trimmed = hashCode.trim();
            for (int idx = 0; idx < sqlUnits.size(); idx++) {
                SqlInfo info = sqlUnits.get(idx);
                if (info == null) {
                    continue;
                }
                if (trimmed.equals(info.getHashCode())) {
                    return idx;
                }
                if (info.getOriginIndex() != null && trimmed.equals(String.valueOf(info.getOriginIndex()))) {
                    return idx;
                }
            }
            try {
                int asIdx = Integer.parseInt(trimmed);
                if (asIdx >= 0 && asIdx < sqlUnits.size()) {
                    return asIdx;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
