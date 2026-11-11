package io.sqlflow.contracts.lineage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Embedded SQL query/view/procedure source item inside SQLFlow/Grabit/SQLDep metadata JSON ({@code 02 §5.1, §6.1.1}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"database", "schema", "name", "type", "sourceCode", "groupName"})
public class QueryObject {

    private String database;
    private String schema;
    private String name;
    private String type;
    private String sourceCode;
    private String groupName;

    public QueryObject() {
    }

    public QueryObject(String database, String schema, String name, String type, String sourceCode) {
        this.database = database;
        this.schema = schema;
        this.name = name;
        this.type = type;
        this.sourceCode = sourceCode;
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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public void setSourceCode(String sourceCode) {
        this.sourceCode = sourceCode;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }
}
