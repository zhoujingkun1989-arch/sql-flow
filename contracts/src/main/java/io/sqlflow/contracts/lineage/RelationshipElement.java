package io.sqlflow.contracts.lineage;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * Source, target, caller, or callee endpoint element of a {@link Relationship} ({@code 02 §5.1}, {@code 03 §1.5.2}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({
    "id", "name", "column", "columnType", "sourceId", "sourceName",
    "targetId", "targetName", "parentId", "parentName", "parentAlias",
    "coordinates", "transforms", "clauseType", "candidateParents",
    "value", "source", "type", "function"
})
public class RelationshipElement {

    private String id;
    private String name;
    private String column;

    @JsonAlias({"column_type", "columnType"})
    private String columnType;

    @JsonAlias({"source_id", "sourceId"})
    private String sourceId;

    @JsonAlias({"source_name", "sourceName"})
    private String sourceName;

    @JsonAlias({"target_id", "targetId"})
    private String targetId;

    @JsonAlias({"target_name", "targetName"})
    private String targetName;

    @JsonAlias({"parent_id", "parentId"})
    private String parentId;

    @JsonAlias({"parent_name", "parentName"})
    private String parentName;

    @JsonAlias({"parent_alias", "parentAlias"})
    private String parentAlias;

    private List<Coordinate> coordinates;
    private List<Transform> transforms;
    private String clauseType;
    private List<CandidateTable> candidateParents;
    private String value;
    private String source;
    private String type;
    private String function;

    public RelationshipElement() {
    }

    public RelationshipElement(String id, String column, String parentId, String parentName) {
        this.id = id;
        this.column = column;
        this.parentId = parentId;
        this.parentName = parentName;
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

    public String getColumn() {
        return column;
    }

    public void setColumn(String column) {
        this.column = column;
    }

    public String getColumnType() {
        return columnType;
    }

    public void setColumnType(String columnType) {
        this.columnType = columnType;
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

    public List<Coordinate> getCoordinates() {
        return coordinates;
    }

    public void setCoordinates(List<Coordinate> coordinates) {
        this.coordinates = coordinates;
    }

    public List<Transform> getTransforms() {
        return transforms;
    }

    public void setTransforms(List<Transform> transforms) {
        this.transforms = transforms;
    }

    public String getClauseType() {
        return clauseType;
    }

    public void setClauseType(String clauseType) {
        this.clauseType = clauseType;
    }

    public List<CandidateTable> getCandidateParents() {
        return candidateParents;
    }

    public void setCandidateParents(List<CandidateTable> candidateParents) {
        this.candidateParents = candidateParents;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
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

    public String getFunction() {
        return function;
    }

    public void setFunction(String function) {
        this.function = function;
    }
}
