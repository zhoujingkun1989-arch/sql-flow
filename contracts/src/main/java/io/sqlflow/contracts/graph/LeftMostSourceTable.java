package io.sqlflow.contracts.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * Root-origin closure payload ({@code LeftMostSourceTable} / {@code TableFlow}) returned by
 * {@code /sqlflow/generation/sqlflow/leftMostSourceTable} ({@code 01 §3.5}, {@code 02 §5.2},
 * {@code 03 §1.5.4}).
 *
 * <p>Supports both the engine {@code TableFlow} shape ({@code elements[] -> TargetTable ->
 * TargetColumn -> SourceTableColumn}) and the web/API {@code leftMostSourceTable} shape
 * ({@code targetTables[]} and {@code attributes[]} of {@link TableColumn} entries with recursive
 * {@link UpstreamAttributesIndirect}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"elements", "targetTables", "attributes"})
public class LeftMostSourceTable {

    private List<TargetTable> elements;
    private List<TargetTable> targetTables;
    private List<TableColumn> attributes;

    public LeftMostSourceTable() {
    }

    public List<TargetTable> getElements() {
        return elements;
    }

    public void setElements(List<TargetTable> elements) {
        this.elements = elements;
    }

    public List<TargetTable> getTargetTables() {
        return targetTables;
    }

    public void setTargetTables(List<TargetTable> targetTables) {
        this.targetTables = targetTables;
    }

    public List<TableColumn> getAttributes() {
        return attributes;
    }

    public void setAttributes(List<TableColumn> attributes) {
        this.attributes = attributes;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({
        "id",
        "server",
        "userName",
        "database",
        "databaseName",
        "schema",
        "schemaName",
        "name",
        "relationName",
        "alias",
        "type",
        "columns",
        "upstreamAttributesIndirect"
    })
    public static class TargetTable {
        private String id;
        private String server;
        private String userName;
        private String database;
        private String databaseName;
        private String schema;
        private String schemaName;
        private String name;
        private String relationName;
        private String alias;
        private String type;
        private List<TargetColumn> columns;
        private UpstreamAttributesIndirect upstreamAttributesIndirect;

        public TargetTable() {
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getServer() {
            return server;
        }

        public void setServer(String server) {
            this.server = server;
        }

        public String getUserName() {
            return userName;
        }

        public void setUserName(String userName) {
            this.userName = userName;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public String getDatabaseName() {
            return databaseName;
        }

        public void setDatabaseName(String databaseName) {
            this.databaseName = databaseName;
        }

        public String getSchema() {
            return schema;
        }

        public void setSchema(String schema) {
            this.schema = schema;
        }

        public String getSchemaName() {
            return schemaName;
        }

        public void setSchemaName(String schemaName) {
            this.schemaName = schemaName;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getRelationName() {
            return relationName;
        }

        public void setRelationName(String relationName) {
            this.relationName = relationName;
        }

        public String getAlias() {
            return alias;
        }

        public void setAlias(String alias) {
            this.alias = alias;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public List<TargetColumn> getColumns() {
            return columns;
        }

        public void setColumns(List<TargetColumn> columns) {
            this.columns = columns;
        }

        public UpstreamAttributesIndirect getUpstreamAttributesIndirect() {
            return upstreamAttributesIndirect;
        }

        public void setUpstreamAttributesIndirect(UpstreamAttributesIndirect upstreamAttributesIndirect) {
            this.upstreamAttributesIndirect = upstreamAttributesIndirect;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"id", "name", "dataType", "sourceColumns", "sourceTables"})
    public static class TargetColumn {
        private String id;
        private String name;
        private String dataType;
        private List<SourceTableColumn> sourceColumns;
        private List<SourceTableRef> sourceTables;

        public TargetColumn() {
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDataType() {
            return dataType;
        }

        public void setDataType(String dataType) {
            this.dataType = dataType;
        }

        public List<SourceTableColumn> getSourceColumns() {
            return sourceColumns;
        }

        public void setSourceColumns(List<SourceTableColumn> sourceColumns) {
            this.sourceColumns = sourceColumns;
        }

        public List<SourceTableRef> getSourceTables() {
            return sourceTables;
        }

        public void setSourceTables(List<SourceTableRef> sourceTables) {
            this.sourceTables = sourceTables;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({
        "database",
        "schema",
        "tableName",
        "tableAlias",
        "name",
        "type",
        "clause",
        "relationType"
    })
    public static class SourceTableColumn {
        private String database;
        private String schema;
        private String tableName;
        private String tableAlias;
        private String name;
        private String type;
        private String clause;
        private String relationType;

        public SourceTableColumn() {
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public String getSchema() {
            return schema;
        }

        public void setSchema(String schema) {
            this.schema = schema;
        }

        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }

        public String getTableAlias() {
            return tableAlias;
        }

        public void setTableAlias(String tableAlias) {
            this.tableAlias = tableAlias;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getClause() {
            return clause;
        }

        public void setClause(String clause) {
            this.clause = clause;
        }

        public String getRelationType() {
            return relationType;
        }

        public void setRelationType(String relationType) {
            this.relationType = relationType;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({
        "server",
        "userName",
        "databaseName",
        "schemaName",
        "relationName",
        "attributeName",
        "timestampMin",
        "timestampMax",
        "upstreamAttributesIndirect"
    })
    public static class TableColumn {
        private String server;
        private String userName;
        private String databaseName;
        private String schemaName;
        private String relationName;
        private String attributeName;
        private String timestampMin;
        private String timestampMax;
        private UpstreamAttributesIndirect upstreamAttributesIndirect;

        public TableColumn() {
        }

        public String getServer() {
            return server;
        }

        public void setServer(String server) {
            this.server = server;
        }

        public String getUserName() {
            return userName;
        }

        public void setUserName(String userName) {
            this.userName = userName;
        }

        public String getDatabaseName() {
            return databaseName;
        }

        public void setDatabaseName(String databaseName) {
            this.databaseName = databaseName;
        }

        public String getSchemaName() {
            return schemaName;
        }

        public void setSchemaName(String schemaName) {
            this.schemaName = schemaName;
        }

        public String getRelationName() {
            return relationName;
        }

        public void setRelationName(String relationName) {
            this.relationName = relationName;
        }

        public String getAttributeName() {
            return attributeName;
        }

        public void setAttributeName(String attributeName) {
            this.attributeName = attributeName;
        }

        public String getTimestampMin() {
            return timestampMin;
        }

        public void setTimestampMin(String timestampMin) {
            this.timestampMin = timestampMin;
        }

        public String getTimestampMax() {
            return timestampMax;
        }

        public void setTimestampMax(String timestampMax) {
            this.timestampMax = timestampMax;
        }

        public UpstreamAttributesIndirect getUpstreamAttributesIndirect() {
            return upstreamAttributesIndirect;
        }

        public void setUpstreamAttributesIndirect(UpstreamAttributesIndirect upstreamAttributesIndirect) {
            this.upstreamAttributesIndirect = upstreamAttributesIndirect;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"nodes"})
    public static class UpstreamAttributesIndirect {
        private List<TableColumn> nodes;

        public UpstreamAttributesIndirect() {
        }

        public UpstreamAttributesIndirect(List<TableColumn> nodes) {
            this.nodes = nodes;
        }

        public List<TableColumn> getNodes() {
            return nodes;
        }

        public void setNodes(List<TableColumn> nodes) {
            this.nodes = nodes;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SourceTableRef {
        private String id;
        private String name;
        private String database;
        private String schema;
        private String columnId;
        private String columnName;

        public SourceTableRef() {
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public String getSchema() {
            return schema;
        }

        public void setSchema(String schema) {
            this.schema = schema;
        }

        public String getColumnId() {
            return columnId;
        }

        public void setColumnId(String columnId) {
            this.columnId = columnId;
        }

        public String getColumnName() {
            return columnName;
        }

        public void setColumnName(String columnName) {
            this.columnName = columnName;
        }
    }
}
