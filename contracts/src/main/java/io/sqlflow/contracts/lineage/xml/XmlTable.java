package io.sqlflow.contracts.lineage.xml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import java.util.List;

/**
 * {@code <table>}, {@code <view>}, {@code <resultset>}, {@code <database>}, {@code <schema>}, {@code <stage>},
 * {@code <sequence>}, {@code <datasource>}, {@code <stream>}, {@code <path>}, or {@code <variable>} element
 * in {@code <dlineage>} XML ({@code 03 §1.3}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class XmlTable {

    @JacksonXmlProperty(isAttribute = true, localName = "id")
    private String id;

    @JacksonXmlProperty(isAttribute = true, localName = "server")
    private String server;

    @JacksonXmlProperty(isAttribute = true, localName = "userName")
    private String userName;

    @JacksonXmlProperty(isAttribute = true, localName = "database")
    private String database;

    @JacksonXmlProperty(isAttribute = true, localName = "schema")
    private String schema;

    @JacksonXmlProperty(isAttribute = true, localName = "name")
    private String name;

    @JacksonXmlProperty(isAttribute = true, localName = "displayName")
    private String displayName;

    @JacksonXmlProperty(isAttribute = true, localName = "alias")
    private String alias;

    @JacksonXmlProperty(isAttribute = true, localName = "uri")
    private String uri;

    @JacksonXmlProperty(isAttribute = true, localName = "type")
    private String type;

    @JacksonXmlProperty(isAttribute = true, localName = "subType")
    private String subType;

    @JacksonXmlProperty(isAttribute = true, localName = "processIds")
    private String processIds;

    @JacksonXmlProperty(isAttribute = true, localName = "procedureId")
    private String procedureId;

    @JacksonXmlProperty(isAttribute = true, localName = "fileType")
    private String fileType;

    @JacksonXmlProperty(isAttribute = true, localName = "fileFormat")
    private String fileFormat;

    @JacksonXmlProperty(isAttribute = true, localName = "location")
    private String location;

    @JacksonXmlProperty(isAttribute = true, localName = "dataSourceName")
    private String dataSourceName;

    @JacksonXmlProperty(isAttribute = true, localName = "namespace")
    private String namespace;

    @JacksonXmlProperty(isAttribute = true, localName = "isTarget")
    private String isTarget;

    @JacksonXmlProperty(isAttribute = true, localName = "coordinate")
    private String coordinate;

    @JacksonXmlProperty(isAttribute = true, localName = "parent")
    private String parent;

    @JacksonXmlProperty(isAttribute = true, localName = "more")
    private Boolean more;

    @JacksonXmlProperty(isAttribute = true, localName = "fromDDL")
    private Boolean fromDDL;

    @JacksonXmlProperty(isAttribute = true, localName = "endpointKind")
    private String endpointKind;

    @JacksonXmlProperty(isAttribute = true, localName = "endpointIntroduction")
    private String endpointIntroduction;

    @JacksonXmlProperty(isAttribute = true, localName = "createdInSql")
    private Boolean createdInSql;

    @JacksonXmlProperty(isAttribute = true, localName = "normalizedName")
    private String normalizedName;

    @JacksonXmlProperty(isAttribute = true, localName = "templatedParts")
    private Boolean templatedParts;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "column")
    private List<XmlColumn> columns;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "candidate")
    private List<XmlCandidateTable> candidates;

    public XmlTable() {
    }

    public XmlTable(String id, String name, String type) {
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

    public String getProcessIds() {
        return processIds;
    }

    public void setProcessIds(String processIds) {
        this.processIds = processIds;
    }

    public String getProcedureId() {
        return procedureId;
    }

    public void setProcedureId(String procedureId) {
        this.procedureId = procedureId;
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

    public String getIsTarget() {
        return isTarget;
    }

    public void setIsTarget(String isTarget) {
        this.isTarget = isTarget;
    }

    public String getCoordinate() {
        return coordinate;
    }

    public void setCoordinate(String coordinate) {
        this.coordinate = coordinate;
    }

    public String getParent() {
        return parent;
    }

    public void setParent(String parent) {
        this.parent = parent;
    }

    public Boolean getMore() {
        return more;
    }

    public void setMore(Boolean more) {
        this.more = more;
    }

    public Boolean getFromDDL() {
        return fromDDL;
    }

    public void setFromDDL(Boolean fromDDL) {
        this.fromDDL = fromDDL;
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

    public Boolean getCreatedInSql() {
        return createdInSql;
    }

    public void setCreatedInSql(Boolean createdInSql) {
        this.createdInSql = createdInSql;
    }

    public String getNormalizedName() {
        return normalizedName;
    }

    public void setNormalizedName(String normalizedName) {
        this.normalizedName = normalizedName;
    }

    public Boolean getTemplatedParts() {
        return templatedParts;
    }

    public void setTemplatedParts(Boolean templatedParts) {
        this.templatedParts = templatedParts;
    }

    public List<XmlColumn> getColumns() {
        return columns;
    }

    public void setColumns(List<XmlColumn> columns) {
        this.columns = columns;
    }

    public List<XmlCandidateTable> getCandidates() {
        return candidates;
    }

    public void setCandidates(List<XmlCandidateTable> candidates) {
        this.candidates = candidates;
    }
}
