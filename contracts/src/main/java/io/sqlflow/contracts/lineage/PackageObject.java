package io.sqlflow.contracts.lineage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * PL/SQL or database package container ({@code 02 §5.1}, {@code 03 §1.5.2}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "name", "type", "procedures", "functions", "triggers", "variables", "coordinates"})
public class PackageObject {

    private String id;
    private String name;
    private String type;
    private List<ProcedureObject> procedures;
    private List<ProcedureObject> functions;
    private List<ProcedureObject> triggers;
    private List<DbObject> variables;
    private List<Coordinate> coordinates;

    public PackageObject() {
    }

    public PackageObject(String id, String name) {
        this.id = id;
        this.name = name;
        this.type = "package";
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

    public List<ProcedureObject> getProcedures() {
        return procedures;
    }

    public void setProcedures(List<ProcedureObject> procedures) {
        this.procedures = procedures;
    }

    public List<ProcedureObject> getFunctions() {
        return functions;
    }

    public void setFunctions(List<ProcedureObject> functions) {
        this.functions = functions;
    }

    public List<ProcedureObject> getTriggers() {
        return triggers;
    }

    public void setTriggers(List<ProcedureObject> triggers) {
        this.triggers = triggers;
    }

    public List<DbObject> getVariables() {
        return variables;
    }

    public void setVariables(List<DbObject> variables) {
        this.variables = variables;
    }

    public List<Coordinate> getCoordinates() {
        return coordinates;
    }

    public void setCoordinates(List<Coordinate> coordinates) {
        this.coordinates = coordinates;
    }
}
