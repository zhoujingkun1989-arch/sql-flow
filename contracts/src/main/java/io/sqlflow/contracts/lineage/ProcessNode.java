package io.sqlflow.contracts.lineage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * Statement execution process node in {@code DataFlow} and {@code SchemaObject} ({@code 02 §5.1}, {@code 03 §1.5.2}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({
    "id", "name", "procedureId", "procedureName", "queryHashId",
    "type", "coordinates", "transforms", "uiVisible"
})
public class ProcessNode {

    private String id;
    private String name;
    private String procedureId;
    private String procedureName;
    private String queryHashId;
    private String type;
    private List<Coordinate> coordinates;
    private List<Transform> transforms;
    private Boolean uiVisible;

    public ProcessNode() {
    }

    public ProcessNode(String id, String name, String type) {
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProcedureId() {
        return procedureId;
    }

    public void setProcedureId(String procedureId) {
        this.procedureId = procedureId;
    }

    public String getProcedureName() {
        return procedureName;
    }

    public void setProcedureName(String procedureName) {
        this.procedureName = procedureName;
    }

    public String getQueryHashId() {
        return queryHashId;
    }

    public void setQueryHashId(String queryHashId) {
        this.queryHashId = queryHashId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
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

    public Boolean getUiVisible() {
        return uiVisible;
    }

    public void setUiVisible(Boolean uiVisible) {
        this.uiVisible = uiVisible;
    }
}
