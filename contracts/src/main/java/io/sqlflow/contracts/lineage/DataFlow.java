package io.sqlflow.contracts.lineage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.sqlflow.contracts.graph.SummaryInfo;
import java.util.List;

/**
 * Root semantic lineage model ({@code DataFlow}) shared across REST APIs, session caches, upstream/downstream
 * slicing, CSV export, and graph layout ({@code 02 §5.1}, {@code 03 §1.4, §1.5.2}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({
    "dbvendor",
    "dbobjs",
    "relationships",
    "errors",
    "orientation",
    "processes",
    "summary",
    "commentBindings"
})
public class DataFlow {

    private String dbvendor;
    private SqlflowCatalog dbobjs;
    private List<Relationship> relationships;
    private List<ErrorItem> errors;
    private Orientation orientation;
    private List<ProcessNode> processes;
    private SummaryInfo summary;
    private CommentBindingBundle commentBindings;

    public DataFlow() {
    }

    public String getDbvendor() {
        return dbvendor;
    }

    public void setDbvendor(String dbvendor) {
        this.dbvendor = dbvendor;
    }

    public SqlflowCatalog getDbobjs() {
        return dbobjs;
    }

    public void setDbobjs(SqlflowCatalog dbobjs) {
        this.dbobjs = dbobjs;
    }

    public List<Relationship> getRelationships() {
        return relationships;
    }

    public void setRelationships(List<Relationship> relationships) {
        this.relationships = relationships;
    }

    public List<ErrorItem> getErrors() {
        return errors;
    }

    public void setErrors(List<ErrorItem> errors) {
        this.errors = errors;
    }

    public Orientation getOrientation() {
        return orientation;
    }

    public void setOrientation(Orientation orientation) {
        this.orientation = orientation;
    }

    public List<ProcessNode> getProcesses() {
        return processes;
    }

    public void setProcesses(List<ProcessNode> processes) {
        this.processes = processes;
    }

    public SummaryInfo getSummary() {
        return summary;
    }

    public void setSummary(SummaryInfo summary) {
        this.summary = summary;
    }

    public CommentBindingBundle getCommentBindings() {
        return commentBindings;
    }

    public void setCommentBindings(CommentBindingBundle commentBindings) {
        this.commentBindings = commentBindings;
    }
}
