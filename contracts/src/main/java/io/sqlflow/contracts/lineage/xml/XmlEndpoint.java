package io.sqlflow.contracts.lineage.xml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import java.util.List;

/**
 * {@code <target>}, {@code <source>}, {@code <caller>}, or {@code <callee>} endpoint element in {@code <dlineage>} XML ({@code 03 §1.3}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class XmlEndpoint {

    @JacksonXmlProperty(isAttribute = true, localName = "id")
    private String id;

    @JacksonXmlProperty(isAttribute = true, localName = "column")
    private String column;

    @JacksonXmlProperty(isAttribute = true, localName = "name")
    private String name;

    @JacksonXmlProperty(isAttribute = true, localName = "function")
    private String function;

    @JacksonXmlProperty(isAttribute = true, localName = "value")
    private String value;

    @JacksonXmlProperty(isAttribute = true, localName = "target_id")
    private String targetId;

    @JacksonXmlProperty(isAttribute = true, localName = "target_name")
    private String targetName;

    @JacksonXmlProperty(isAttribute = true, localName = "source_id")
    private String sourceId;

    @JacksonXmlProperty(isAttribute = true, localName = "source_name")
    private String sourceName;

    @JacksonXmlProperty(isAttribute = true, localName = "column_type")
    private String columnType;

    @JacksonXmlProperty(isAttribute = true, localName = "parent_id")
    private String parentId;

    @JacksonXmlProperty(isAttribute = true, localName = "parent_name")
    private String parentName;

    @JacksonXmlProperty(isAttribute = true, localName = "parent_alias")
    private String parentAlias;

    @JacksonXmlProperty(isAttribute = true, localName = "coordinate")
    private String coordinate;

    @JacksonXmlProperty(isAttribute = true, localName = "clauseType")
    private String clauseType;

    @JacksonXmlProperty(isAttribute = true, localName = "source")
    private String source;

    @JacksonXmlProperty(isAttribute = true, localName = "type")
    private String type;

    @JacksonXmlElementWrapper(localName = "transforms")
    @JacksonXmlProperty(localName = "transform")
    private List<XmlTransform> transforms;

    @JacksonXmlElementWrapper(localName = "candidateTables")
    @JacksonXmlProperty(localName = "candidateTable")
    private List<XmlCandidateTable> candidateTables;

    public XmlEndpoint() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getColumn() {
        return column;
    }

    public void setColumn(String column) {
        this.column = column;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFunction() {
        return function;
    }

    public void setFunction(String function) {
        this.function = function;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
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

    public String getColumnType() {
        return columnType;
    }

    public void setColumnType(String columnType) {
        this.columnType = columnType;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public String getParentName() {
        return parentName;
    }

    public void setParentName(String parentName) {
        this.parentName = parentName;
    }

    public String getParentAlias() {
        return parentAlias;
    }

    public void setParentAlias(String parentAlias) {
        this.parentAlias = parentAlias;
    }

    public String getCoordinate() {
        return coordinate;
    }

    public void setCoordinate(String coordinate) {
        this.coordinate = coordinate;
    }

    public String getClauseType() {
        return clauseType;
    }

    public void setClauseType(String clauseType) {
        this.clauseType = clauseType;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<XmlTransform> getTransforms() {
        return transforms;
    }

    public void setTransforms(List<XmlTransform> transforms) {
        this.transforms = transforms;
    }

    public List<XmlCandidateTable> getCandidateTables() {
        return candidateTables;
    }

    public void setCandidateTables(List<XmlCandidateTable> candidateTables) {
        this.candidateTables = candidateTables;
    }
}
