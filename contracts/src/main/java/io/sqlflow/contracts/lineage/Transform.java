package io.sqlflow.contracts.lineage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * SQL expression transformation snippet attached to a relationship endpoint or process node ({@code 03 §1.5.2, §3.2.3}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"type", "code", "coordinates"})
public class Transform {

    private String type;
    private String code;
    private List<Coordinate> coordinates;

    public Transform() {
    }

    public Transform(String type, String code) {
        this.type = type;
        this.code = code;
    }

    public Transform(String type, String code, List<Coordinate> coordinates) {
        this.type = type;
        this.code = code;
        this.coordinates = coordinates;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public List<Coordinate> getCoordinates() {
        return coordinates;
    }

    public void setCoordinates(List<Coordinate> coordinates) {
        this.coordinates = coordinates;
    }
}
