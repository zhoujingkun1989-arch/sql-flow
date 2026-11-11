package io.sqlflow.contracts.lineage;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * Database synonym definition in {@code SqlflowCatalog} ({@code 02 §5.1}, {@code 03 §1.5.2}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({
    "id", "database", "schema", "name", "sourceId",
    "sourceName", "sourceSchema", "sourceDatabase", "sourceServer", "sourceDbLinkName", "coordinates"
})
public class SynonymObject {

    private String id;
    private String database;
    private String schema;
    private String name;
    private String sourceId;
    private String sourceName;
    private String sourceSchema;
    private String sourceDatabase;
    private String sourceServer;

    @JsonAlias({"sourceDbLink", "sourceDbLinkName"})
    private String sourceDbLinkName;

    private List<Coordinate> coordinates;

    public SynonymObject() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public String getSourceName() {
        return sourceName;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public String getSourceSchema() {
        return sourceSchema;
    }

    public void setSourceSchema(String sourceSchema) {
        this.sourceSchema = sourceSchema;
    }

    public String getSourceDatabase() {
        return sourceDatabase;
    }

    public void setSourceDatabase(String sourceDatabase) {
        this.sourceDatabase = sourceDatabase;
    }

    public String getSourceServer() {
        return sourceServer;
    }

    public void setSourceServer(String sourceServer) {
        this.sourceServer = sourceServer;
    }

    public String getSourceDbLinkName() {
        return sourceDbLinkName;
    }

    public void setSourceDbLinkName(String sourceDbLinkName) {
        this.sourceDbLinkName = sourceDbLinkName;
    }

    public List<Coordinate> getCoordinates() {
        return coordinates;
    }

    public void setCoordinates(List<Coordinate> coordinates) {
        this.coordinates = coordinates;
    }
}
