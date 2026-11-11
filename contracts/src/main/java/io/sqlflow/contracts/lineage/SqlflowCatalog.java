package io.sqlflow.contracts.lineage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * Hierarchical catalog object ({@code Sqlflow}) used both as {@code DataFlow.dbobjs} and as the top-level
 * SQLFlow / Grabit / SQLDep / JDBC Exporter Metadata JSON schema ({@code 02 §5.1, §6.1.1, §6.3.4}, {@code 03 §1.5.2}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({
    "createdBy", "createTime", "createdAt", "exportTime", "dialect",
    "exportId", "userAccountId", "customSqlSetName",
    "servers", "databases", "queries", "dbLinks",
    "errorMessages", "physicalInstance"
})
public class SqlflowCatalog {

    private String createdBy;
    private Object createTime;
    private String createdAt;
    private Long exportTime;
    private String dialect;
    private String exportId;
    private String userAccountId;
    private String customSqlSetName;
    private List<ServerObject> servers;
    private List<DatabaseObject> databases;
    private List<QueryObject> queries;
    private List<DbLink> dbLinks;
    private List<ErrorItem> errorMessages;
    private String physicalInstance;

    public SqlflowCatalog() {
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Object getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Object createTime) {
        this.createTime = createTime;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public Long getExportTime() {
        return exportTime;
    }

    public void setExportTime(Long exportTime) {
        this.exportTime = exportTime;
    }

    public String getDialect() {
        return dialect;
    }

    public void setDialect(String dialect) {
        this.dialect = dialect;
    }

    public String getExportId() {
        return exportId;
    }

    public void setExportId(String exportId) {
        this.exportId = exportId;
    }

    public String getUserAccountId() {
        return userAccountId;
    }

    public void setUserAccountId(String userAccountId) {
        this.userAccountId = userAccountId;
    }

    public String getCustomSqlSetName() {
        return customSqlSetName;
    }

    public void setCustomSqlSetName(String customSqlSetName) {
        this.customSqlSetName = customSqlSetName;
    }

    public List<ServerObject> getServers() {
        return servers;
    }

    public void setServers(List<ServerObject> servers) {
        this.servers = servers;
    }

    public List<DatabaseObject> getDatabases() {
        return databases;
    }

    public void setDatabases(List<DatabaseObject> databases) {
        this.databases = databases;
    }

    public List<QueryObject> getQueries() {
        return queries;
    }

    public void setQueries(List<QueryObject> queries) {
        this.queries = queries;
    }

    public List<DbLink> getDbLinks() {
        return dbLinks;
    }

    public void setDbLinks(List<DbLink> dbLinks) {
        this.dbLinks = dbLinks;
    }

    public List<ErrorItem> getErrorMessages() {
        return errorMessages;
    }

    public void setErrorMessages(List<ErrorItem> errorMessages) {
        this.errorMessages = errorMessages;
    }

    public String getPhysicalInstance() {
        return physicalInstance;
    }

    public void setPhysicalInstance(String physicalInstance) {
        this.physicalInstance = physicalInstance;
    }
}
