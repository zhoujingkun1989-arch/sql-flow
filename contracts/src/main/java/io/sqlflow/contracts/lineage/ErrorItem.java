package io.sqlflow.contracts.lineage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * Syntax, metadata, or dynamic-SQL diagnostic error item ({@code Error} in {@code DataFlow.errors}) ({@code 03 §1.5.2}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"errorMessage", "errorType", "coordinates"})
public class ErrorItem {

    private String errorMessage;
    private String errorType;
    private List<Coordinate> coordinates;

    public ErrorItem() {
    }

    public ErrorItem(String errorType, String errorMessage) {
        this.errorType = errorType;
        this.errorMessage = errorMessage;
    }

    public ErrorItem(String errorType, String errorMessage, List<Coordinate> coordinates) {
        this.errorType = errorType;
        this.errorMessage = errorMessage;
        this.coordinates = coordinates;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getErrorType() {
        return errorType;
    }

    public void setErrorType(String errorType) {
        this.errorType = errorType;
    }

    public List<Coordinate> getCoordinates() {
        return coordinates;
    }

    public void setCoordinates(List<Coordinate> coordinates) {
        this.coordinates = coordinates;
    }
}
