package io.sqlflow.contracts.lineage;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * Routine parameter / return argument in {@code ProcedureObject} and {@code DbObject} ({@code 02 §5.1}, {@code 03 §1.5.2}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "name", "dataType", "inout", "coordinates"})
public class ArgumentObject {

    private String id;
    private String name;
    @JsonAlias({"datatype", "dataType"})
    private String dataType;
    @JsonAlias({"inOut", "inout"})
    private String inout;
    private List<Coordinate> coordinates;

    public ArgumentObject() {
    }

    public ArgumentObject(String id, String name, String dataType, String inout) {
        this.id = id;
        this.name = name;
        this.dataType = dataType;
        this.inout = inout;
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

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public String getInout() {
        return inout;
    }

    public void setInout(String inout) {
        this.inout = inout;
    }

    public List<Coordinate> getCoordinates() {
        return coordinates;
    }

    public void setCoordinates(List<Coordinate> coordinates) {
        this.coordinates = coordinates;
    }
}
