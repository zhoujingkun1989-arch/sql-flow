package io.sqlflow.contracts.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Database connection and extraction filter configuration ({@code DataSource}) ({@code 02 §5.5}).
 *
 * <p>Note: {@link #maskedCopy()} returns a copy with {@code password = null} for outbound API responses
 * such as {@code queryJobDetail}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DataSource {

    private String hostname;
    private String port;
    private String account;
    private String password;
    private String database;
    private String extractedDbsSchemas;
    private String excludedDbsSchemas;
    private String extractedStoredProcedures;
    private String extractedViews;
    private String url;

    public DataSource() {
    }

    public DataSource maskedCopy() {
        DataSource copy = new DataSource();
        copy.setHostname(hostname);
        copy.setPort(port);
        copy.setAccount(account);
        copy.setPassword(null);
        copy.setDatabase(database);
        copy.setExtractedDbsSchemas(extractedDbsSchemas);
        copy.setExcludedDbsSchemas(excludedDbsSchemas);
        copy.setExtractedStoredProcedures(extractedStoredProcedures);
        copy.setExtractedViews(extractedViews);
        copy.setUrl(url);
        return copy;
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public String getPort() {
        return port;
    }

    public void setPort(String port) {
        this.port = port;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getExtractedDbsSchemas() {
        return extractedDbsSchemas;
    }

    public void setExtractedDbsSchemas(String extractedDbsSchemas) {
        this.extractedDbsSchemas = extractedDbsSchemas;
    }

    public String getExcludedDbsSchemas() {
        return excludedDbsSchemas;
    }

    public void setExcludedDbsSchemas(String excludedDbsSchemas) {
        this.excludedDbsSchemas = excludedDbsSchemas;
    }

    public String getExtractedStoredProcedures() {
        return extractedStoredProcedures;
    }

    public void setExtractedStoredProcedures(String extractedStoredProcedures) {
        this.extractedStoredProcedures = extractedStoredProcedures;
    }

    public String getExtractedViews() {
        return extractedViews;
    }

    public void setExtractedViews(String extractedViews) {
        this.extractedViews = extractedViews;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
