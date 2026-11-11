package io.sqlflow.graph.model;

import io.sqlflow.contracts.lineage.ArgumentObject;
import io.sqlflow.contracts.lineage.Column;
import io.sqlflow.contracts.lineage.Coordinate;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.DatabaseObject;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.PackageObject;
import io.sqlflow.contracts.lineage.ProcedureObject;
import io.sqlflow.contracts.lineage.ProcessNode;
import io.sqlflow.contracts.lineage.Relationship;
import io.sqlflow.contracts.lineage.RelationshipElement;
import io.sqlflow.contracts.lineage.RelationshipType;
import io.sqlflow.contracts.lineage.SchemaObject;
import io.sqlflow.contracts.lineage.ServerObject;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Non-mutating index over a {@link DataFlow} semantic model supporting fast lookup of packages,
 * tables/views/resultsets/procedures/processes, columns, and relationship endpoints across all
 * catalog nesting shapes ({@code Server -> Database -> Schema}, Oracle {@code Server -> Schema},
 * MySQL/Hive {@code Server -> Database}, and standalone {@code relationships}).
 */
public final class DataFlowIndex {

    public static final class PackageEntry {
        private final String id;
        private final String name;
        private final String type;
        private final String server;
        private final String database;
        private final String schema;
        private final List<Coordinate> coordinates;
        private final List<String> childNodeIds = new ArrayList<>();

        public PackageEntry(
                String id,
                String name,
                String type,
                String server,
                String database,
                String schema,
                List<Coordinate> coordinates) {
            this.id = id;
            this.name = name;
            this.type = type != null && !type.isBlank() ? type : "package";
            this.server = server;
            this.database = database;
            this.schema = schema;
            this.coordinates = coordinates != null ? List.copyOf(coordinates) : List.of();
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        public String getServer() {
            return server;
        }

        public String getDatabase() {
            return database;
        }

        public String getSchema() {
            return schema;
        }

        public List<Coordinate> getCoordinates() {
            return coordinates;
        }

        public List<String> getChildNodeIds() {
            return Collections.unmodifiableList(childNodeIds);
        }

        void addChildNodeId(String nodeId) {
            if (nodeId != null && !childNodeIds.contains(nodeId)) {
                childNodeIds.add(nodeId);
            }
        }
    }

    public static final class ColumnEntry {
        private final String id;
        private final String parentNodeId;
        private final String name;
        private final String displayName;
        private final String dataType;
        private final String source;
        private final Boolean uiVisible;
        private final Boolean primaryKey;
        private final Boolean uniqueKey;
        private final Boolean indexKey;
        private final Boolean foreignKey;
        private final Boolean nullable;
        private final Boolean star;
        private final List<Coordinate> coordinates;
        private final Column rawColumn;

        public ColumnEntry(String id, String parentNodeId, Column col) {
            this.id = id;
            this.parentNodeId = parentNodeId;
            this.name = col.getName() != null ? col.getName() : id;
            this.displayName = col.getDisplayName();
            this.dataType = col.getDataType();
            this.source = col.getSource();
            this.uiVisible = col.getUiVisible();
            this.primaryKey = col.getPrimaryKey();
            this.uniqueKey = col.getUnqiueKey();
            this.indexKey = col.getIndexKey();
            this.foreignKey = col.getForeignKey();
            this.nullable = col.getNullable();
            this.star = col.getStar();
            this.coordinates = col.getCoordinates() != null ? List.copyOf(col.getCoordinates()) : List.of();
            this.rawColumn = col;
        }

        public String getId() {
            return id;
        }

        public String getParentNodeId() {
            return parentNodeId;
        }

        public String getName() {
            return name;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDataType() {
            return dataType;
        }

        public String getSource() {
            return source;
        }

        public Boolean getUiVisible() {
            return uiVisible;
        }

        public Boolean getPrimaryKey() {
            return primaryKey;
        }

        public Boolean getUniqueKey() {
            return uniqueKey;
        }

        public Boolean getIndexKey() {
            return indexKey;
        }

        public Boolean getForeignKey() {
            return foreignKey;
        }

        public Boolean getNullable() {
            return nullable;
        }

        public Boolean getStar() {
            return star;
        }

        public List<Coordinate> getCoordinates() {
            return coordinates;
        }

        public Column getRawColumn() {
            return rawColumn;
        }

        public boolean isSystemRelationRows() {
            return "system".equalsIgnoreCase(source) || "RelationRows".equalsIgnoreCase(name);
        }
    }

    public static final class NodeEntry {
        private final String id;
        private final String name;
        private final String displayName;
        private final String alias;
        private final String type;
        private final String subType;
        private final String server;
        private final String database;
        private final String schema;
        private final String parentPackageId;
        private final List<Coordinate> coordinates;
        private final DbObject rawDbObject;
        private final ProcedureObject rawProcedure;
        private final ProcessNode rawProcess;
        private final Map<String, ColumnEntry> columnsById = new LinkedHashMap<>();
        private final Map<String, ColumnEntry> columnsByNormName = new LinkedHashMap<>();

        public NodeEntry(
                String id,
                String name,
                String displayName,
                String alias,
                String type,
                String subType,
                String server,
                String database,
                String schema,
                String parentPackageId,
                List<Coordinate> coordinates,
                DbObject rawDbObject,
                ProcedureObject rawProcedure,
                ProcessNode rawProcess) {
            this.id = id;
            this.name = name != null ? name : id;
            this.displayName = displayName;
            this.alias = alias;
            this.type = type != null && !type.isBlank() ? type : "table";
            this.subType = subType;
            this.server = server;
            this.database = database;
            this.schema = schema;
            this.parentPackageId = parentPackageId;
            this.coordinates = coordinates != null ? List.copyOf(coordinates) : List.of();
            this.rawDbObject = rawDbObject;
            this.rawProcedure = rawProcedure;
            this.rawProcess = rawProcess;
        }

        void addColumn(ColumnEntry col) {
            columnsById.put(col.getId(), col);
            columnsByNormName.putIfAbsent(normalizeIdentifier(col.getName()), col);
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getAlias() {
            return alias;
        }

        public String getType() {
            return type;
        }

        public String getSubType() {
            return subType;
        }

        public String getServer() {
            return server;
        }

        public String getDatabase() {
            return database;
        }

        public String getSchema() {
            return schema;
        }

        public String getParentPackageId() {
            return parentPackageId;
        }

        public List<Coordinate> getCoordinates() {
            return coordinates;
        }

        public DbObject getRawDbObject() {
            return rawDbObject;
        }

        public ProcedureObject getRawProcedure() {
            return rawProcedure;
        }

        public ProcessNode getRawProcess() {
            return rawProcess;
        }

        public List<ColumnEntry> getColumns() {
            return List.copyOf(columnsById.values());
        }

        public ColumnEntry findColumnByName(String colName) {
            if (colName == null || colName.isBlank()) {
                return null;
            }
            return columnsByNormName.get(normalizeIdentifier(colName));
        }

        public boolean isResultSetLike() {
            String t = type.toLowerCase(Locale.ROOT);
            if ("table".equals(t) || "view".equals(t) || "materialized view".equals(t)
                    || "stage".equals(t) || "path".equals(t) || "stream".equals(t)
                    || "datasource".equals(t) || "procedure".equals(t) || "function".equals(t)
                    || "trigger".equals(t) || "package".equals(t) || "process".equals(t)) {
                return false;
            }
            if (isConstantTable()) {
                return false;
            }
            return "resultset".equals(t)
                    || "select_list".equals(t)
                    || "with_cte".equals(t)
                    || "cte".equals(t)
                    || "insert-select".equals(t)
                    || "insert_select".equals(t)
                    || "update-select".equals(t)
                    || "update_select".equals(t)
                    || "update-set".equals(t)
                    || "update_set".equals(t)
                    || "merge-insert".equals(t)
                    || "merge_insert".equals(t)
                    || "merge-update".equals(t)
                    || "merge_update".equals(t)
                    || "select_union".equals(t)
                    || "pivot-table".equals(t)
                    || "unpivot-table".equals(t)
                    || "result_of".equals(t)
                    || "case-when".equals(t)
                    || "array".equals(t)
                    || "struct".equals(t)
                    || "output".equals(t)
                    || "alias".equals(t);
        }

        public boolean isConstantTable() {
            if ("constant".equalsIgnoreCase(type)) {
                return true;
            }
            String simple = extractSimpleTableName(name);
            return "SQL_CONSTANTS".equalsIgnoreCase(name)
                    || "$constant".equalsIgnoreCase(name)
                    || "SQL_CONSTANTS".equalsIgnoreCase(simple)
                    || "$constant".equalsIgnoreCase(simple);
        }

        public String resolveHeaderLabel() {
            if (displayName != null && !displayName.isBlank()) {
                return stripDefaultPrefix(displayName);
            }
            StringBuilder sb = new StringBuilder();
            if (database != null && !database.isBlank() && !isDefaultName(database)) {
                sb.append(database).append('.');
            }
            if (schema != null && !schema.isBlank() && !isDefaultName(schema)) {
                sb.append(schema).append('.');
            }
            sb.append(stripDefaultPrefix(name));
            return sb.toString();
        }
    }

    public record ResolvedEndpoint(NodeEntry node, ColumnEntry column) {
    }

    public record ResolvedEdge(
            Relationship relationship,
            RelationshipType relationType,
            ResolvedEndpoint source,
            ResolvedEndpoint target,
            RelationshipElement rawSourceElement,
            RelationshipElement rawTargetElement) {
    }

    private final DataFlow dataFlow;
    private final Map<String, PackageEntry> packagesById = new LinkedHashMap<>();
    private final Map<String, NodeEntry> nodesById = new LinkedHashMap<>();
    private final Map<String, ColumnEntry> columnsById = new LinkedHashMap<>();
    private final Map<String, List<NodeEntry>> nodesBySimpleName = new LinkedHashMap<>();
    private final Map<String, NodeEntry> nodesByQualifiedKey = new LinkedHashMap<>();
    private int syntheticCounter = 100000;

    private DataFlowIndex(DataFlow dataFlow) {
        this.dataFlow = dataFlow;
        if (dataFlow != null) {
            indexCatalog(dataFlow.getDbobjs());
            indexTopLevelProcesses(dataFlow.getProcesses());
            ensureRelationshipEndpointsIndexed(dataFlow.getRelationships());
        }
    }

    public static DataFlowIndex build(DataFlow dataFlow) {
        return new DataFlowIndex(dataFlow);
    }

    public DataFlow getDataFlow() {
        return dataFlow;
    }

    public Map<String, PackageEntry> getPackagesById() {
        return Collections.unmodifiableMap(packagesById);
    }

    public Map<String, NodeEntry> getNodesById() {
        return Collections.unmodifiableMap(nodesById);
    }

    public Map<String, ColumnEntry> getColumnsById() {
        return Collections.unmodifiableMap(columnsById);
    }

    public NodeEntry getNode(String id) {
        return id != null ? nodesById.get(id) : null;
    }

    public ColumnEntry getColumn(String id) {
        return id != null ? columnsById.get(id) : null;
    }

    public PackageEntry getPackage(String id) {
        return id != null ? packagesById.get(id) : null;
    }

    /**
     * Resolves matching node or column IDs from a {@code GraphSliceRequest} or targetTable query.
     */
    public List<String> matchNodesOrColumns(
            List<String> explicitIds,
            String database,
            String schema,
            String table,
            String column) {
        Set<String> matched = new LinkedHashSet<>();
        if (explicitIds != null) {
            for (String rawId : explicitIds) {
                if (rawId == null || rawId.isBlank()) {
                    continue;
                }
                String trimmed = rawId.trim();
                if (nodesById.containsKey(trimmed) || columnsById.containsKey(trimmed)) {
                    matched.add(trimmed);
                } else if (packagesById.containsKey(trimmed)) {
                    matched.addAll(packagesById.get(trimmed).getChildNodeIds());
                } else {
                    // Try matching by name if caller passed a table name in dbObjIds
                    for (NodeEntry node : nodesById.values()) {
                        if (matchesObjectName(node, null, null, trimmed)) {
                            matched.add(node.getId());
                        }
                    }
                }
            }
        }

        if (table != null && !table.isBlank()) {
            for (NodeEntry node : nodesById.values()) {
                if (matchesObjectName(node, database, schema, table)) {
                    if (column != null && !column.isBlank()) {
                        ColumnEntry col = node.findColumnByName(column);
                        if (col != null) {
                            matched.add(col.getId());
                        }
                    } else {
                        matched.add(node.getId());
                    }
                }
            }
        } else if (column != null && !column.isBlank() && matched.isEmpty()) {
            for (ColumnEntry col : columnsById.values()) {
                if (normalizeIdentifier(col.getName()).equals(normalizeIdentifier(column))) {
                    matched.add(col.getId());
                }
            }
        }
        return new ArrayList<>(matched);
    }

    public boolean matchesObjectName(NodeEntry node, String dbFilter, String schemaFilter, String tableFilter) {
        if (tableFilter == null || tableFilter.isBlank()) {
            return true;
        }
        String normFilter = normalizeIdentifier(stripDefaultPrefix(tableFilter));
        String normNodeName = normalizeIdentifier(stripDefaultPrefix(node.getName()));
        String normDisplay = node.getDisplayName() != null
                ? normalizeIdentifier(stripDefaultPrefix(node.getDisplayName()))
                : "";

        // Handle dotted tableFilter like "db.schema.tbl" or "schema.tbl"
        String effectiveDb = dbFilter;
        String effectiveSchema = schemaFilter;
        String effectiveTable = normFilter;
        if (tableFilter.contains(".") && !normNodeName.equals(normFilter) && !normDisplay.equals(normFilter)) {
            String[] parts = stripDefaultPrefix(tableFilter).split("\\.");
            effectiveTable = normalizeIdentifier(parts[parts.length - 1]);
            if (parts.length >= 2 && (effectiveSchema == null || effectiveSchema.isBlank())) {
                effectiveSchema = parts[parts.length - 2];
            }
            if (parts.length >= 3 && (effectiveDb == null || effectiveDb.isBlank())) {
                effectiveDb = parts[parts.length - 3];
            }
        }

        String nodeSimple = extractSimpleTableName(node.getName());
        boolean tableMatches = normNodeName.equals(normFilter)
                || normDisplay.equals(normFilter)
                || normalizeIdentifier(nodeSimple).equals(effectiveTable)
                || normalizeIdentifier(node.getId()).equals(normFilter);
        if (!tableMatches) {
            return false;
        }
        if (effectiveDb != null && !effectiveDb.isBlank() && !isDefaultName(effectiveDb)) {
            if (node.getDatabase() != null && !node.getDatabase().isBlank() && !isDefaultName(node.getDatabase())) {
                if (!normalizeIdentifier(node.getDatabase()).equals(normalizeIdentifier(effectiveDb))) {
                    return false;
                }
            }
        }
        if (effectiveSchema != null && !effectiveSchema.isBlank() && !isDefaultName(effectiveSchema)) {
            if (node.getSchema() != null && !node.getSchema().isBlank() && !isDefaultName(node.getSchema())) {
                if (!normalizeIdentifier(node.getSchema()).equals(normalizeIdentifier(effectiveSchema))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Expands all relationships in {@code DataFlow} (or a custom list) into resolved directed
     * {@code (source -> target)} edges.
     */
    public List<ResolvedEdge> resolveEdges(List<Relationship> relationships, Set<RelationshipType> allowedTypes) {
        if (relationships == null || relationships.isEmpty()) {
            return List.of();
        }
        List<ResolvedEdge> result = new ArrayList<>();
        for (Relationship rel : relationships) {
            if (rel == null) {
                continue;
            }
            RelationshipType relType = parseRelType(rel.getType());
            if (allowedTypes != null && !allowedTypes.isEmpty() && (relType == null || !allowedTypes.contains(relType))) {
                continue;
            }
            if (rel.getCaller() != null && rel.getCallees() != null && !rel.getCallees().isEmpty()) {
                ResolvedEndpoint callerEp = resolveEndpoint(rel.getCaller(), false);
                if (callerEp != null) {
                    for (RelationshipElement callee : rel.getCallees()) {
                        ResolvedEndpoint calleeEp = resolveEndpoint(callee, false);
                        if (calleeEp != null) {
                            result.add(new ResolvedEdge(rel, relType != null ? relType : RelationshipType.call,
                                    callerEp, calleeEp, rel.getCaller(), callee));
                        }
                    }
                }
                continue;
            }
            if (rel.getCaller() != null && rel.getTarget() != null
                    && (rel.getSources() == null || rel.getSources().isEmpty())) {
                ResolvedEndpoint callerEp = resolveEndpoint(rel.getCaller(), false);
                ResolvedEndpoint targetEp = resolveEndpoint(rel.getTarget(), false);
                if (callerEp != null && targetEp != null) {
                    result.add(new ResolvedEdge(rel, relType != null ? relType : RelationshipType.call,
                            callerEp, targetEp, rel.getCaller(), rel.getTarget()));
                }
                continue;
            }

            ResolvedEndpoint targetEp = resolveEndpoint(rel.getTarget(), false);
            if (targetEp == null) {
                continue;
            }
            if (rel.getSources() != null && !rel.getSources().isEmpty()) {
                for (RelationshipElement src : rel.getSources()) {
                    ResolvedEndpoint srcEp = resolveEndpoint(src, false);
                    if (srcEp != null) {
                        result.add(new ResolvedEdge(
                                rel,
                                relType != null ? relType : RelationshipType.fdd,
                                srcEp,
                                targetEp,
                                src,
                                rel.getTarget()));
                    }
                }
            }
        }
        return result;
    }

    public ResolvedEndpoint resolveEndpoint(RelationshipElement el, boolean synthesizeIfMissing) {
        if (el == null) {
            return null;
        }
        String elId = el.getId();
        String parentId = el.getParentId();
        String colName = el.getColumn();
        String parentName = el.getParentName() != null ? el.getParentName() : el.getName();

        // 1. Direct column lookup by element.id
        if (elId != null && columnsById.containsKey(elId)) {
            ColumnEntry col = columnsById.get(elId);
            NodeEntry parent = nodesById.get(col.getParentNodeId());
            if (parent != null) {
                return new ResolvedEndpoint(parent, col);
            }
        }

        // 2. Direct node lookup by parentId
        NodeEntry parentNode = parentId != null ? nodesById.get(parentId) : null;

        // 3. Direct node lookup by element.id (table-level / call / crud relationship)
        if (parentNode == null && elId != null && nodesById.containsKey(elId)) {
            parentNode = nodesById.get(elId);
            if (colName == null || colName.isBlank()
                    || normalizeIdentifier(colName).equals(normalizeIdentifier(parentNode.getName()))) {
                return new ResolvedEndpoint(parentNode, null);
            }
        }

        // 4. Match parent node by name
        if (parentNode == null && parentName != null && !parentName.isBlank()) {
            parentNode = findNodeByName(parentName);
        }

        if (parentNode != null) {
            if (colName != null && !colName.isBlank()) {
                ColumnEntry col = parentNode.findColumnByName(colName);
                if (col == null && synthesizeIfMissing) {
                    String synColId = (elId != null && !elId.isBlank() && !elId.equals(parentNode.getId()))
                            ? elId
                            : nextSyntheticId("col");
                    Column rawCol = new Column(synColId, colName);
                    rawCol.setSource(el.getSource());
                    rawCol.setCoordinates(el.getCoordinates());
                    col = new ColumnEntry(synColId, parentNode.getId(), rawCol);
                    parentNode.addColumn(col);
                    columnsById.put(col.getId(), col);
                }
                return new ResolvedEndpoint(parentNode, col);
            }
            return new ResolvedEndpoint(parentNode, null);
        }

        if (!synthesizeIfMissing) {
            return null;
        }

        // 5. Synthesize missing parent node & column when relationship references unindexed objects
        String synNodeId = (parentId != null && !parentId.isBlank())
                ? parentId
                : (colName == null && elId != null && !elId.isBlank() ? elId : nextSyntheticId("obj"));
        String synNodeName = (parentName != null && !parentName.isBlank()) ? parentName : synNodeId;
        String synType = (el.getType() != null && !el.getType().isBlank()) ? el.getType() : "table";
        DbObject synObj = new DbObject(synNodeId, synNodeName, synType);
        NodeEntry createdNode = registerNode(new NodeEntry(
                synNodeId,
                synNodeName,
                synNodeName,
                el.getParentAlias(),
                synType,
                null,
                null,
                null,
                null,
                null,
                el.getCoordinates(),
                synObj,
                null,
                null));
        ColumnEntry createdCol = null;
        if (colName != null && !colName.isBlank()) {
            String synColId = (elId != null && !elId.isBlank() && !elId.equals(synNodeId))
                    ? elId
                    : nextSyntheticId("col");
            Column rawCol = new Column(synColId, colName);
            rawCol.setSource(el.getSource());
            rawCol.setCoordinates(el.getCoordinates());
            createdCol = new ColumnEntry(synColId, createdNode.getId(), rawCol);
            createdNode.addColumn(createdCol);
            columnsById.put(createdCol.getId(), createdCol);
        }
        return new ResolvedEndpoint(createdNode, createdCol);
    }

    private void indexCatalog(SqlflowCatalog catalog) {
        if (catalog == null) {
            return;
        }
        if (catalog.getServers() != null) {
            for (ServerObject server : catalog.getServers()) {
                if (server == null) {
                    continue;
                }
                String serverName = server.getName();
                if (server.getDatabases() != null) {
                    for (DatabaseObject db : server.getDatabases()) {
                        indexDatabase(serverName, db);
                    }
                }
                if (server.getSchemas() != null) {
                    for (SchemaObject schema : server.getSchemas()) {
                        indexSchema(serverName, null, schema);
                    }
                }
            }
        }
        if (catalog.getDatabases() != null) {
            for (DatabaseObject db : catalog.getDatabases()) {
                indexDatabase(null, db);
            }
        }
    }

    private void indexDatabase(String serverName, DatabaseObject db) {
        if (db == null) {
            return;
        }
        String dbName = db.getName();
        if (db.getSchemas() != null) {
            for (SchemaObject schema : db.getSchemas()) {
                indexSchema(serverName, dbName, schema);
            }
        }
        indexObjectLists(
                serverName,
                dbName,
                null,
                db.getTables(),
                db.getViews(),
                db.getOthers(),
                db.getPackages(),
                db.getProcedures(),
                db.getFunctions(),
                db.getTriggers(),
                db.getProcesses());
    }

    private void indexSchema(String serverName, String dbName, SchemaObject schema) {
        if (schema == null) {
            return;
        }
        indexObjectLists(
                serverName,
                dbName,
                schema.getName(),
                schema.getTables(),
                schema.getViews(),
                schema.getOthers(),
                schema.getPackages(),
                schema.getProcedures(),
                schema.getFunctions(),
                schema.getTriggers(),
                schema.getProcesses());
    }

    private void indexObjectLists(
            String serverName,
            String dbName,
            String schemaName,
            List<DbObject> tables,
            List<DbObject> views,
            List<DbObject> others,
            List<PackageObject> packages,
            List<ProcedureObject> procedures,
            List<ProcedureObject> functions,
            List<ProcedureObject> triggers,
            List<ProcessNode> processes) {
        if (packages != null) {
            for (PackageObject pkg : packages) {
                indexPackage(serverName, dbName, schemaName, pkg);
            }
        }
        indexDbObjects(serverName, dbName, schemaName, null, tables, "table");
        indexDbObjects(serverName, dbName, schemaName, null, views, "view");
        indexDbObjects(serverName, dbName, schemaName, null, others, "resultset");
        indexProcedures(serverName, dbName, schemaName, null, procedures, "procedure");
        indexProcedures(serverName, dbName, schemaName, null, functions, "function");
        indexProcedures(serverName, dbName, schemaName, null, triggers, "trigger");
        if (processes != null) {
            for (ProcessNode procNode : processes) {
                indexProcess(serverName, dbName, schemaName, procNode);
            }
        }
    }

    private void indexPackage(String serverName, String dbName, String schemaName, PackageObject pkg) {
        if (pkg == null) {
            return;
        }
        String pkgId = pkg.getId() != null && !pkg.getId().isBlank() ? pkg.getId() : nextSyntheticId("pkg");
        PackageEntry entry = packagesById.computeIfAbsent(
                pkgId,
                k -> new PackageEntry(
                        k,
                        pkg.getName() != null ? pkg.getName() : k,
                        pkg.getType(),
                        serverName,
                        dbName,
                        schemaName,
                        pkg.getCoordinates()));
        indexProcedures(serverName, dbName, schemaName, entry.getId(), pkg.getProcedures(), "procedure");
        indexProcedures(serverName, dbName, schemaName, entry.getId(), pkg.getFunctions(), "function");
        indexProcedures(serverName, dbName, schemaName, entry.getId(), pkg.getTriggers(), "trigger");
        indexDbObjects(serverName, dbName, schemaName, entry.getId(), pkg.getVariables(), "variable");
    }

    private void indexDbObjects(
            String serverName,
            String dbName,
            String schemaName,
            String parentPkgId,
            List<DbObject> objects,
            String fallbackType) {
        if (objects == null) {
            return;
        }
        for (DbObject obj : objects) {
            if (obj == null) {
                continue;
            }
            // Handle DbObject representing an oracle package wrapper with nested procedures
            if ("package".equalsIgnoreCase(obj.getType())
                    && obj.getProcedures() != null
                    && !obj.getProcedures().isEmpty()) {
                String pkgId = obj.getId() != null && !obj.getId().isBlank() ? obj.getId() : nextSyntheticId("pkg");
                PackageEntry pkgEntry = packagesById.computeIfAbsent(
                        pkgId,
                        k -> new PackageEntry(
                                k,
                                obj.getName() != null ? obj.getName() : k,
                                "package",
                                obj.getServer() != null ? obj.getServer() : serverName,
                                obj.getDatabase() != null ? obj.getDatabase() : dbName,
                                obj.getSchema() != null ? obj.getSchema() : schemaName,
                                obj.getCoordinates()));
                indexDbObjects(serverName, dbName, schemaName, pkgEntry.getId(), obj.getProcedures(), "procedure");
                continue;
            }

            String id = obj.getId() != null && !obj.getId().isBlank() ? obj.getId() : nextSyntheticId("obj");
            String effectiveServer = obj.getServer() != null ? obj.getServer() : serverName;
            String effectiveDb = obj.getDatabase() != null ? obj.getDatabase() : dbName;
            String effectiveSchema = obj.getSchema() != null ? obj.getSchema() : schemaName;
            String type = obj.getType() != null && !obj.getType().isBlank() ? obj.getType() : fallbackType;

            NodeEntry node = registerNode(new NodeEntry(
                    id,
                    obj.getName(),
                    obj.getDisplayName(),
                    obj.getAlias(),
                    type,
                    obj.getSubType(),
                    effectiveServer,
                    effectiveDb,
                    effectiveSchema,
                    parentPkgId,
                    obj.getCoordinates(),
                    obj,
                    null,
                    null));

            if (parentPkgId != null && packagesById.containsKey(parentPkgId)) {
                packagesById.get(parentPkgId).addChildNodeId(node.getId());
            }

            if (obj.getColumns() != null) {
                for (Column col : obj.getColumns()) {
                    if (col == null) {
                        continue;
                    }
                    String colId = col.getId() != null && !col.getId().isBlank()
                            ? col.getId()
                            : nextSyntheticId("col");
                    ColumnEntry colEntry = new ColumnEntry(colId, node.getId(), col);
                    node.addColumn(colEntry);
                    columnsById.put(colId, colEntry);
                }
            }
            if (obj.getArguments() != null && node.getColumns().isEmpty()) {
                for (ArgumentObject arg : obj.getArguments()) {
                    if (arg == null) {
                        continue;
                    }
                    String argId = arg.getId() != null && !arg.getId().isBlank()
                            ? arg.getId()
                            : nextSyntheticId("arg");
                    Column argCol = new Column(argId, arg.getName(), arg.getDataType());
                    argCol.setCoordinates(arg.getCoordinates());
                    ColumnEntry colEntry = new ColumnEntry(argId, node.getId(), argCol);
                    node.addColumn(colEntry);
                    columnsById.put(argId, colEntry);
                }
            }
        }
    }

    private void indexProcedures(
            String serverName,
            String dbName,
            String schemaName,
            String parentPkgId,
            List<ProcedureObject> procedures,
            String fallbackType) {
        if (procedures == null) {
            return;
        }
        for (ProcedureObject proc : procedures) {
            if (proc == null) {
                continue;
            }
            String id = proc.getId() != null && !proc.getId().isBlank() ? proc.getId() : nextSyntheticId("proc");
            String type = proc.getType() != null && !proc.getType().isBlank() ? proc.getType() : fallbackType;
            NodeEntry node = registerNode(new NodeEntry(
                    id,
                    proc.getName(),
                    proc.getName(),
                    null,
                    type,
                    null,
                    serverName,
                    dbName,
                    schemaName,
                    parentPkgId,
                    proc.getCoordinates(),
                    null,
                    proc,
                    null));

            if (parentPkgId != null && packagesById.containsKey(parentPkgId)) {
                packagesById.get(parentPkgId).addChildNodeId(node.getId());
            }

            if (proc.getArguments() != null && !proc.getArguments().isEmpty()) {
                for (ArgumentObject arg : proc.getArguments()) {
                    if (arg == null) {
                        continue;
                    }
                    String argId = arg.getId() != null && !arg.getId().isBlank()
                            ? arg.getId()
                            : nextSyntheticId("arg");
                    Column argCol = new Column(argId, arg.getName(), arg.getDataType());
                    argCol.setCoordinates(arg.getCoordinates());
                    ColumnEntry colEntry = new ColumnEntry(argId, node.getId(), argCol);
                    node.addColumn(colEntry);
                    columnsById.put(argId, colEntry);
                }
            } else {
                String descId = id + ":body";
                Column bodyCol = new Column(descId, type);
                ColumnEntry colEntry = new ColumnEntry(descId, node.getId(), bodyCol);
                node.addColumn(colEntry);
                columnsById.put(descId, colEntry);
            }
        }
    }

    private void indexTopLevelProcesses(List<ProcessNode> processes) {
        if (processes == null) {
            return;
        }
        for (ProcessNode proc : processes) {
            indexProcess(null, null, null, proc);
        }
    }

    private void indexProcess(String serverName, String dbName, String schemaName, ProcessNode proc) {
        if (proc == null) {
            return;
        }
        String id = proc.getId() != null && !proc.getId().isBlank() ? proc.getId() : nextSyntheticId("process");
        if (nodesById.containsKey(id)) {
            return;
        }
        registerNode(new NodeEntry(
                id,
                proc.getName(),
                proc.getName(),
                null,
                "process",
                proc.getType(),
                serverName,
                dbName,
                schemaName,
                null,
                proc.getCoordinates(),
                null,
                null,
                proc));
    }

    private void ensureRelationshipEndpointsIndexed(List<Relationship> relationships) {
        if (relationships == null) {
            return;
        }
        for (Relationship rel : relationships) {
            if (rel == null) {
                continue;
            }
            if (rel.getCaller() != null) {
                resolveEndpoint(rel.getCaller(), true);
            }
            if (rel.getCallees() != null) {
                for (RelationshipElement callee : rel.getCallees()) {
                    resolveEndpoint(callee, true);
                }
            }
            if (rel.getTarget() != null) {
                resolveEndpoint(rel.getTarget(), true);
            }
            if (rel.getSources() != null) {
                for (RelationshipElement src : rel.getSources()) {
                    resolveEndpoint(src, true);
                }
            }
        }
    }

    private NodeEntry registerNode(NodeEntry node) {
        NodeEntry existing = nodesById.get(node.getId());
        if (existing != null) {
            return existing;
        }
        nodesById.put(node.getId(), node);
        String simple = normalizeIdentifier(extractSimpleTableName(node.getName()));
        nodesBySimpleName.computeIfAbsent(simple, k -> new ArrayList<>()).add(node);
        nodesByQualifiedKey.putIfAbsent(buildQualifiedKey(node.getDatabase(), node.getSchema(), node.getName()), node);
        nodesByQualifiedKey.putIfAbsent(normalizeIdentifier(stripDefaultPrefix(node.getName())), node);
        return node;
    }

    private NodeEntry findNodeByName(String rawName) {
        String norm = normalizeIdentifier(stripDefaultPrefix(rawName));
        NodeEntry exact = nodesByQualifiedKey.get(norm);
        if (exact != null) {
            return exact;
        }
        String simple = normalizeIdentifier(extractSimpleTableName(rawName));
        List<NodeEntry> candidates = nodesBySimpleName.get(simple);
        if (candidates != null && !candidates.isEmpty()) {
            return candidates.get(0);
        }
        return null;
    }

    private String nextSyntheticId(String prefix) {
        return prefix + "_" + (++syntheticCounter);
    }

    public static RelationshipType parseRelType(String raw) {
        if (raw == null || raw.isBlank()) {
            return RelationshipType.fdd;
        }
        try {
            return RelationshipType.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return RelationshipType.fdd;
        }
    }

    public static String normalizeIdentifier(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '"' && c != '`' && c != '[' && c != ']') {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    public static String stripDefaultPrefix(String name) {
        if (name == null) {
            return "";
        }
        String res = name.trim();
        boolean changed = true;
        while (changed) {
            changed = false;
            String upper = res.toUpperCase(Locale.ROOT);
            if (upper.startsWith("DEFAULT_SERVER.")) {
                res = res.substring("DEFAULT_SERVER.".length());
                changed = true;
            } else if (upper.startsWith("DEFAULT.")) {
                res = res.substring("DEFAULT.".length());
                changed = true;
            }
        }
        return res;
    }

    public static boolean isDefaultName(String s) {
        if (s == null || s.isBlank()) {
            return true;
        }
        String norm = normalizeIdentifier(s);
        return "default".equals(norm) || "default_server".equals(norm);
    }

    private static String extractSimpleTableName(String raw) {
        if (raw == null) {
            return "";
        }
        String stripped = stripDefaultPrefix(raw);
        int dot = stripped.lastIndexOf('.');
        return dot >= 0 ? stripped.substring(dot + 1) : stripped;
    }

    private static String buildQualifiedKey(String db, String schema, String name) {
        StringBuilder sb = new StringBuilder();
        if (db != null && !isDefaultName(db)) {
            sb.append(normalizeIdentifier(db)).append('.');
        }
        if (schema != null && !isDefaultName(schema)) {
            sb.append(normalizeIdentifier(schema)).append('.');
        }
        sb.append(normalizeIdentifier(extractSimpleTableName(name)));
        return sb.toString();
    }
}
