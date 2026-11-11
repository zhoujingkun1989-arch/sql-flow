package io.sqlflow.contracts.lineage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * Stored procedure, user-defined function, trigger, or macro inside {@code SqlflowCatalog} ({@code 02 §5.1}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "name", "type", "arguments", "coordinates"})
public class ProcedureObject {

    private String id;
    private String name;
    private String type;
    private List<ArgumentObject> arguments;
    private List<Coordinate> coordinates;

    public ProcedureObject() {
    }

    public ProcedureObject(String id, String name, String type) {
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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<ArgumentObject> getArguments() {
        return arguments;
    }

    public void setArguments(List<ArgumentObject> arguments) {
        this.arguments = arguments;
    }

    public List<Coordinate> getCoordinates() {
        return coordinates;
    }

    public void setCoordinates(List<Coordinate> coordinates) {
        this.coordinates = coordinates;
    }
}
