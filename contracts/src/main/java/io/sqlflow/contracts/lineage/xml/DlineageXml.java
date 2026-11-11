package io.sqlflow.contracts.lineage.xml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import io.sqlflow.contracts.lineage.CommentBindingBundle;
import io.sqlflow.contracts.lineage.Orientation;
import java.util.List;

/**
 * Root {@code <dlineage>} XML model with exact 18 child element order ({@code 03 §1.3}).
 */
@JacksonXmlRootElement(localName = "dlineage")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({
    "packages",
    "procedures",
    "databases",
    "schemas",
    "stages",
    "sequences",
    "datasources",
    "streams",
    "paths",
    "variables",
    "processes",
    "tables",
    "views",
    "resultsets",
    "relationships",
    "errors",
    "orientation",
    "commentBindings"
})
public class DlineageXml {

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "package")
    private List<XmlPackage> packages;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "procedure")
    private List<XmlProcedure> procedures;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "database")
    private List<XmlTable> databases;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "schema")
    private List<XmlTable> schemas;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "stage")
    private List<XmlTable> stages;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "sequence")
    private List<XmlTable> sequences;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "datasource")
    private List<XmlTable> datasources;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "stream")
    private List<XmlTable> streams;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "path")
    private List<XmlTable> paths;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "variable")
    private List<XmlTable> variables;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "process")
    private List<XmlProcess> processes;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "table")
    private List<XmlTable> tables;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "view")
    private List<XmlTable> views;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "resultset")
    private List<XmlTable> resultsets;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "relationship")
    private List<XmlRelationship> relationships;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "error")
    private List<XmlError> errors;

    @JacksonXmlProperty(localName = "orientation")
    private Orientation orientation;

    @JacksonXmlProperty(localName = "commentBindings")
    private CommentBindingBundle commentBindings;

    public DlineageXml() {
    }

    public List<XmlPackage> getPackages() {
        return packages;
    }

    public void setPackages(List<XmlPackage> packages) {
        this.packages = packages;
    }

    public List<XmlProcedure> getProcedures() {
        return procedures;
    }

    public void setProcedures(List<XmlProcedure> procedures) {
        this.procedures = procedures;
    }

    public List<XmlTable> getDatabases() {
        return databases;
    }

    public void setDatabases(List<XmlTable> databases) {
        this.databases = databases;
    }

    public List<XmlTable> getSchemas() {
        return schemas;
    }

    public void setSchemas(List<XmlTable> schemas) {
        this.schemas = schemas;
    }

    public List<XmlTable> getStages() {
        return stages;
    }

    public void setStages(List<XmlTable> stages) {
        this.stages = stages;
    }

    public List<XmlTable> getSequences() {
        return sequences;
    }

    public void setSequences(List<XmlTable> sequences) {
        this.sequences = sequences;
    }

    public List<XmlTable> getDatasources() {
        return datasources;
    }

    public void setDatasources(List<XmlTable> datasources) {
        this.datasources = datasources;
    }

    public List<XmlTable> getStreams() {
        return streams;
    }

    public void setStreams(List<XmlTable> streams) {
        this.streams = streams;
    }

    public List<XmlTable> getPaths() {
        return paths;
    }

    public void setPaths(List<XmlTable> paths) {
        this.paths = paths;
    }

    public List<XmlTable> getVariables() {
        return variables;
    }

    public void setVariables(List<XmlTable> variables) {
        this.variables = variables;
    }

    public List<XmlProcess> getProcesses() {
        return processes;
    }

    public void setProcesses(List<XmlProcess> processes) {
        this.processes = processes;
    }

    public List<XmlTable> getTables() {
        return tables;
    }

    public void setTables(List<XmlTable> tables) {
        this.tables = tables;
    }

    public List<XmlTable> getViews() {
        return views;
    }

    public void setViews(List<XmlTable> views) {
        this.views = views;
    }

    public List<XmlTable> getResultsets() {
        return resultsets;
    }

    public void setResultsets(List<XmlTable> resultsets) {
        this.resultsets = resultsets;
    }

    public List<XmlRelationship> getRelationships() {
        return relationships;
    }

    public void setRelationships(List<XmlRelationship> relationships) {
        this.relationships = relationships;
    }

    public List<XmlError> getErrors() {
        return errors;
    }

    public void setErrors(List<XmlError> errors) {
        this.errors = errors;
    }

    public Orientation getOrientation() {
        return orientation;
    }

    public void setOrientation(Orientation orientation) {
        this.orientation = orientation;
    }

    public CommentBindingBundle getCommentBindings() {
        return commentBindings;
    }

    public void setCommentBindings(CommentBindingBundle commentBindings) {
        this.commentBindings = commentBindings;
    }
}
