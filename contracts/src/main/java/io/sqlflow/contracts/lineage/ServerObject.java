package io.sqlflow.contracts.lineage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * Top-level server node in {@code SqlflowCatalog} ({@code 02 §5.1}, {@code 03 §1.5.2}).
 *
 * <p>When {@code supportsCatalogs == false} and {@code supportsSchemas == true} (e.g., Oracle, Dameng),
 * {@link #getSchemas()} is populated directly on {@code ServerObject}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({
    "name", "dbVendor", "supportsCatalogs", "supportsSchemas",
    "databases", "schemas", "dbLinks", "queries"
})
public class ServerObject {

    private String name;
    private String dbVendor;
    private Boolean supportsCatalogs;
    private Boolean supportsSchemas;
    private List<DatabaseObject> databases;
    private List<SchemaObject> schemas;
    private List<DbLink> dbLinks;
    private List<QueryObject> queries;

    public ServerObject() {
    }

    public ServerObject(String name, String dbVendor, Boolean supportsCatalogs, Boolean supportsSchemas) {
        this.name = name;
        this.dbVendor = dbVendor;
        this.supportsCatalogs = supportsCatalogs;
        this.supportsSchemas = supportsSchemas;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDbVendor() {
        return dbVendor;
    }

    public void setDbVendor(String dbVendor) {
        this.dbVendor = dbVendor;
    }

    public Boolean getSupportsCatalogs() {
        return supportsCatalogs;
    }

    public void setSupportsCatalogs(Boolean supportsCatalogs) {
        this.supportsCatalogs = supportsCatalogs;
    }

    public Boolean getSupportsSchemas() {
        return supportsSchemas;
    }

    public void setSupportsSchemas(Boolean supportsSchemas) {
        this.supportsSchemas = supportsSchemas;
    }

    public List<DatabaseObject> getDatabases() {
        return databases;
    }

    public void setDatabases(List<DatabaseObject> databases) {
        this.databases = databases;
    }

    public List<SchemaObject> getSchemas() {
        return schemas;
    }

    public void setSchemas(List<SchemaObject> schemas) {
        this.schemas = schemas;
    }

    public List<DbLink> getDbLinks() {
        return dbLinks;
    }

    public void setDbLinks(List<DbLink> dbLinks) {
        this.dbLinks = dbLinks;
    }

    public List<QueryObject> getQueries() {
        return queries;
    }

    public void setQueries(List<QueryObject> queries) {
        this.queries = queries;
    }
}
