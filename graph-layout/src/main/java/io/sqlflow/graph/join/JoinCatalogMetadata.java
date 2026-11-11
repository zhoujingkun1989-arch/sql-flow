package io.sqlflow.graph.join;

import io.sqlflow.contracts.lineage.Column;
import io.sqlflow.contracts.lineage.DatabaseObject;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.SchemaObject;
import io.sqlflow.contracts.lineage.ServerObject;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.graph.model.DataFlowIndex;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves table and column key/index metadata from {@link SqlflowCatalog} and inline
 * {@code CREATE TABLE} / {@code CREATE [UNIQUE] INDEX} / {@code ALTER TABLE} DDL statements
 * for cardinality estimation and {@code NON_INDEXED_JOIN_KEY} / {@code MANY_TO_MANY_RISK} detection.
 */
public final class JoinCatalogMetadata {

    public static final class ColumnMeta {
        private final String name;
        private boolean primaryKey;
        private boolean uniqueKey;
        private boolean indexed;

        public ColumnMeta(String name, boolean primaryKey, boolean uniqueKey, boolean indexed) {
            this.name = name;
            this.primaryKey = primaryKey;
            this.uniqueKey = uniqueKey;
            this.indexed = indexed || primaryKey || uniqueKey;
        }

        public String getName() {
            return name;
        }

        public boolean isPrimaryKey() {
            return primaryKey;
        }

        public void setPrimaryKey(boolean primaryKey) {
            this.primaryKey = primaryKey;
            if (primaryKey) {
                this.uniqueKey = true;
                this.indexed = true;
            }
        }

        public boolean isUniqueKey() {
            return uniqueKey;
        }

        public void setUniqueKey(boolean uniqueKey) {
            this.uniqueKey = uniqueKey;
            if (uniqueKey) {
                this.indexed = true;
            }
        }

        public boolean isIndexed() {
            return indexed;
        }

        public void setIndexed(boolean indexed) {
            this.indexed = indexed;
        }
    }

    public static final class TableMeta {
        private final String database;
        private final String schema;
        private final String name;
        private final Map<String, ColumnMeta> columns = new LinkedHashMap<>();

        public TableMeta(String database, String schema, String name) {
            this.database = database;
            this.schema = schema;
            this.name = name;
        }

        public String getDatabase() {
            return database;
        }

        public String getSchema() {
            return schema;
        }

        public String getName() {
            return name;
        }

        public ColumnMeta getOrCreateColumn(String colName) {
            String norm = DataFlowIndex.normalizeIdentifier(colName);
            return columns.computeIfAbsent(norm, k -> new ColumnMeta(colName, false, false, false));
        }

        public ColumnMeta findColumn(String colName) {
            if (colName == null || colName.isBlank()) {
                return null;
            }
            return columns.get(DataFlowIndex.normalizeIdentifier(colName));
        }

        public boolean hasColumns() {
            return !columns.isEmpty();
        }
    }

    private final Map<String, TableMeta> byQualified = new HashMap<>();
    private final Map<String, TableMeta> bySimple = new HashMap<>();

    public static JoinCatalogMetadata fromCatalog(SqlflowCatalog catalog) {
        JoinCatalogMetadata meta = new JoinCatalogMetadata();
        if (catalog == null) {
            return meta;
        }
        if (catalog.getServers() != null) {
            for (ServerObject srv : catalog.getServers()) {
                if (srv == null) {
                    continue;
                }
                if (srv.getDatabases() != null) {
                    for (DatabaseObject db : srv.getDatabases()) {
                        meta.indexDatabase(db);
                    }
                }
                if (srv.getSchemas() != null) {
                    for (SchemaObject sch : srv.getSchemas()) {
                        meta.indexSchema(null, sch);
                    }
                }
            }
        }
        if (catalog.getDatabases() != null) {
            for (DatabaseObject db : catalog.getDatabases()) {
                meta.indexDatabase(db);
            }
        }
        return meta;
    }

    public TableMeta getOrCreateTable(String database, String schema, String tableName) {
        String normSimple = DataFlowIndex.normalizeIdentifier(tableName);
        String qKey = qualifiedKey(database, schema, tableName);
        TableMeta existing = byQualified.get(qKey);
        if (existing != null) {
            return existing;
        }
        TableMeta created = new TableMeta(database, schema, tableName);
        byQualified.put(qKey, created);
        bySimple.putIfAbsent(normSimple, created);
        return created;
    }

    public TableMeta findTable(String database, String schema, String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return null;
        }
        TableMeta exact = byQualified.get(qualifiedKey(database, schema, tableName));
        if (exact != null) {
            return exact;
        }
        return bySimple.get(DataFlowIndex.normalizeIdentifier(tableName));
    }

    public boolean isEmpty() {
        return bySimple.isEmpty();
    }

    private void indexDatabase(DatabaseObject db) {
        if (db == null) {
            return;
        }
        if (db.getSchemas() != null) {
            for (SchemaObject sch : db.getSchemas()) {
                indexSchema(db.getName(), sch);
            }
        }
        indexObjects(db.getName(), null, db.getTables());
        indexObjects(db.getName(), null, db.getViews());
    }

    private void indexSchema(String dbName, SchemaObject sch) {
        if (sch == null) {
            return;
        }
        indexObjects(dbName, sch.getName(), sch.getTables());
        indexObjects(dbName, sch.getName(), sch.getViews());
    }

    private void indexObjects(String dbName, String schemaName, List<DbObject> objs) {
        if (objs == null) {
            return;
        }
        for (DbObject obj : objs) {
            if (obj == null || obj.getName() == null) {
                continue;
            }
            String effDb = obj.getDatabase() != null ? obj.getDatabase() : dbName;
            String effSch = obj.getSchema() != null ? obj.getSchema() : schemaName;
            TableMeta tm = getOrCreateTable(effDb, effSch, obj.getName());
            if (obj.getColumns() != null) {
                for (Column col : obj.getColumns()) {
                    if (col == null || col.getName() == null) {
                        continue;
                    }
                    ColumnMeta cm = tm.getOrCreateColumn(col.getName());
                    if (Boolean.TRUE.equals(col.getPrimaryKey())) {
                        cm.setPrimaryKey(true);
                    }
                    if (Boolean.TRUE.equals(col.getUnqiueKey())) {
                        cm.setUniqueKey(true);
                    }
                    if (Boolean.TRUE.equals(col.getIndexKey())) {
                        cm.setIndexed(true);
                    }
                }
            }
        }
    }

    private static String qualifiedKey(String db, String schema, String name) {
        StringBuilder sb = new StringBuilder();
        if (db != null && !DataFlowIndex.isDefaultName(db)) {
            sb.append(DataFlowIndex.normalizeIdentifier(db)).append('.');
        }
        if (schema != null && !DataFlowIndex.isDefaultName(schema)) {
            sb.append(DataFlowIndex.normalizeIdentifier(schema)).append('.');
        }
        sb.append(DataFlowIndex.normalizeIdentifier(name));
        return sb.toString();
    }
}
