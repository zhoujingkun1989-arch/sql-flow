package io.sqlflow.contracts.lineage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * Database / catalog container node in {@code SqlflowCatalog} ({@code 02 §5.1}, {@code 03 §1.5.2}).
 *
 * <p>When {@code supportsSchemas == true}, objects live inside {@link #getSchemas()}; when
 * {@code supportsSchemas == false} (e.g., MySQL, Hive, Impala, Teradata), objects populate directly on {@code DatabaseObject}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({
    "name", "schemas", "tables", "views", "packages", "procedures",
    "functions", "triggers", "synonyms", "sequences", "others", "processes"
})
public class DatabaseObject {

    private String name;
    private List<SchemaObject> schemas;
    private List<DbObject> tables;
    private List<DbObject> views;
    private List<PackageObject> packages;
    private List<ProcedureObject> procedures;
    private List<ProcedureObject> functions;
    private List<ProcedureObject> triggers;
    private List<SynonymObject> synonyms;
    private List<SequenceObject> sequences;
    private List<DbObject> others;
    private List<ProcessNode> processes;

    public DatabaseObject() {
    }

    public DatabaseObject(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<SchemaObject> getSchemas() {
        return schemas;
    }

    public void setSchemas(List<SchemaObject> schemas) {
        this.schemas = schemas;
    }

    public List<DbObject> getTables() {
        return tables;
    }

    public void setTables(List<DbObject> tables) {
        this.tables = tables;
    }

    public List<DbObject> getViews() {
        return views;
    }

    public void setViews(List<DbObject> views) {
        this.views = views;
    }

    public List<PackageObject> getPackages() {
        return packages;
    }

    public void setPackages(List<PackageObject> packages) {
        this.packages = packages;
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

    public List<SynonymObject> getSynonyms() {
        return synonyms;
    }

    public void setSynonyms(List<SynonymObject> synonyms) {
        this.synonyms = synonyms;
    }

    public List<SequenceObject> getSequences() {
        return sequences;
    }

    public void setSequences(List<SequenceObject> sequences) {
        this.sequences = sequences;
    }

    public List<DbObject> getOthers() {
        return others;
    }

    public void setOthers(List<DbObject> others) {
        this.others = others;
    }

    public List<ProcessNode> getProcesses() {
        return processes;
    }

    public void setProcesses(List<ProcessNode> processes) {
        this.processes = processes;
    }
}
