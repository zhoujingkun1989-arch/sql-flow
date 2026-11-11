package io.sqlflow.contracts.lineage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * Unified database object representation ({@code Table} / {@code DBObject}) used across {@code DataFlow.dbobjs}
 * ({@code tables}, {@code views}, {@code others}), selected-object views, call views, and exported metadata
 * ({@code 02 §5.1}, {@code 03 §1.3.2, §1.5.2}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({
    "id", "server", "database", "schema", "name", "displayName", "alias", "uri",
    "type", "subType", "fileType", "fileFormat", "location", "dataSourceName", "namespace",
    "dbLink", "isView", "isTarget", "fromDDL", "createdInSql", "endpointKind", "endpointIntroduction",
    "normalizedName", "templatedParts", "procedureId", "queryHashId", "processIds",
    "arguments", "procedures", "columns", "candidateTables", "coordinates", "more"
})
public class DbObject {

    private String id;
    private String server;
    private String database;
    private String schema;
    private String name;
    private String displayName;
    private String alias;
    private String uri;
    private String type;
    private String subType;
    private String fileType;
    private String fileFormat;
    private String location;
    private String dataSourceName;
    private String namespace;
    private String dbLink;
    private String isView;
    private String isTarget;
    private Boolean fromDDL;
    private Boolean createdInSql;
    private String endpointKind;
    private String endpointIntroduction;
    private String normalizedName;
    private String templatedParts;
    private String procedureId;
    private String queryHashId;
    private String processIds;
    private List<ArgumentObject> arguments;
    private List<DbObject> procedures;
    private List<Column> columns;
    private List<CandidateTable> candidateTables;
    private List<Coordinate> coordinates;
    private Boolean more;

    public DbObject() {
    }

    public DbObject(String id, String name, String type) {
        this.id = id;
        this.name = name;
        this.type = type;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getSubType() {
        return subType;
    }

    public void setSubType(String subType) {
        this.subType = subType;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public String getFileFormat() {
        return fileFormat;
    }

    public void setFileFormat(String fileFormat) {
        this.fileFormat = fileFormat;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getDataSourceName() {
        return dataSourceName;
    }

    public void setDataSourceName(String dataSourceName) {
        this.dataSourceName = dataSourceName;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getDbLink() {
        return dbLink;
    }

    public void setDbLink(String dbLink) {
        this.dbLink = dbLink;
    }

    public String getIsView() {
        return isView;
    }

    public void setIsView(String isView) {
        this.isView = isView;
    }

    public String getIsTarget() {
        return isTarget;
    }

    public void setIsTarget(String isTarget) {
        this.isTarget = isTarget;
    }

    public Boolean getFromDDL() {
        return fromDDL;
    }

    public void setFromDDL(Boolean fromDDL) {
        this.fromDDL = fromDDL;
    }

    public Boolean getCreatedInSql() {
        return createdInSql;
    }

    public void setCreatedInSql(Boolean createdInSql) {
        this.createdInSql = createdInSql;
    }

    public String getEndpointKind() {
        return endpointKind;
    }

    public void setEndpointKind(String endpointKind) {
        this.endpointKind = endpointKind;
    }

    public String getEndpointIntroduction() {
        return endpointIntroduction;
    }

    public void setEndpointIntroduction(String endpointIntroduction) {
        this.endpointIntroduction = endpointIntroduction;
    }

    public String getNormalizedName() {
        return normalizedName;
    }

    public void setNormalizedName(String normalizedName) {
        this.normalizedName = normalizedName;
    }

    public String getTemplatedParts() {
        return templatedParts;
    }

    public void setTemplatedParts(String templatedParts) {
        this.templatedParts = templatedParts;
    }

    public String getProcedureId() {
        return procedureId;
    }

    public void setProcedureId(String procedureId) {
        this.procedureId = procedureId;
    }

    public String getQueryHashId() {
        return queryHashId;
    }

    public void setQueryHashId(String queryHashId) {
        this.queryHashId = queryHashId;
    }

    public String getProcessIds() {
        return processIds;
    }

    public void setProcessIds(String processIds) {
        this.processIds = processIds;
    }

    public List<ArgumentObject> getArguments() {
        return arguments;
    }

    public void setArguments(List<ArgumentObject> arguments) {
        this.arguments = arguments;
    }

    public List<DbObject> getProcedures() {
        return procedures;
    }

    public void setProcedures(List<DbObject> procedures) {
        this.procedures = procedures;
    }

    public List<Column> getColumns() {
        return columns;
    }

    public void setColumns(List<Column> columns) {
        this.columns = columns;
    }

    public List<CandidateTable> getCandidateTables() {
        return candidateTables;
    }

    public void setCandidateTables(List<CandidateTable> candidateTables) {
        this.candidateTables = candidateTables;
    }

    public List<Coordinate> getCoordinates() {
        return coordinates;
    }

    public void setCoordinates(List<Coordinate> coordinates) {
        this.coordinates = coordinates;
    }

    public Boolean getMore() {
        return more;
    }

    public void setMore(Boolean more) {
        this.more = more;
    }
}
