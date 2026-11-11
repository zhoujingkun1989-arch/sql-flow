package io.sqlflow.contracts.lineage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Multi-file / multi-statement SQL input unit ({@code SqlInfo} in {@code 02 §4.1}, {@code 03 §1.1, §1.2.3}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"sql", "fileName", "filePath", "dbVendor", "originIndex", "hashCode"})
public class SqlInfo {

    private String sql;
    private String fileName;
    private String filePath;
    private String dbVendor;
    private Integer originIndex;
    private String hashCode;

    public SqlInfo() {
    }

    public SqlInfo(String sql, String fileName, String filePath, String dbVendor) {
        this.sql = sql;
        this.fileName = fileName;
        this.filePath = filePath;
        this.dbVendor = dbVendor;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getDbVendor() {
        return dbVendor;
    }

    public void setDbVendor(String dbVendor) {
        this.dbVendor = dbVendor;
    }

    public Integer getOriginIndex() {
        return originIndex;
    }

    public void setOriginIndex(Integer originIndex) {
        this.originIndex = originIndex;
    }

    public String getHashCode() {
        return hashCode;
    }

    public void setHashCode(String hashCode) {
        this.hashCode = hashCode;
    }
}
