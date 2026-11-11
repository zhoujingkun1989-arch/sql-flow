package io.sqlflow.contracts.lineage.xml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import java.util.List;

/**
 * {@code <relationship>} element in {@code <dlineage>} XML ({@code 03 §1.3}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"caller", "target", "callees", "sources"})
public class XmlRelationship {

    @JacksonXmlProperty(isAttribute = true, localName = "id")
    private String id;

    @JacksonXmlProperty(isAttribute = true, localName = "type")
    private String type;

    @JacksonXmlProperty(isAttribute = true, localName = "effectType")
    private String effectType;

    @JacksonXmlProperty(isAttribute = true, localName = "function")
    private String function;

    @JacksonXmlProperty(isAttribute = true, localName = "partition")
    private String partition;

    @JacksonXmlProperty(isAttribute = true, localName = "clause")
    private String clause;

    @JacksonXmlProperty(isAttribute = true, localName = "joinType")
    private String joinType;

    @JacksonXmlProperty(isAttribute = true, localName = "condition")
    private String condition;

    @JacksonXmlProperty(isAttribute = true, localName = "processId")
    private String processId;

    @JacksonXmlProperty(isAttribute = true, localName = "processType")
    private String processType;

    @JacksonXmlProperty(isAttribute = true, localName = "procedureId")
    private String procedureId;

    @JacksonXmlProperty(isAttribute = true, localName = "sqlHash")
    private String sqlHash;

    @JacksonXmlProperty(isAttribute = true, localName = "sqlComment")
    private String sqlComment;

    @JacksonXmlProperty(isAttribute = true, localName = "timestampMin")
    private String timestampMin;

    @JacksonXmlProperty(isAttribute = true, localName = "timestampMax")
    private String timestampMax;

    @JacksonXmlProperty(isAttribute = true, localName = "builtIn")
    private String builtIn;

    @JacksonXmlProperty(isAttribute = true, localName = "callStmt")
    private String callStmt;

    @JacksonXmlProperty(isAttribute = true, localName = "callCoordinate")
    private String callCoordinate;

    @JacksonXmlProperty(localName = "caller")
    private XmlEndpoint caller;

    @JacksonXmlProperty(localName = "target")
    private XmlEndpoint target;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "callee")
    private List<XmlEndpoint> callees;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "source")
    private List<XmlEndpoint> sources;

    public XmlRelationship() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getEffectType() {
        return effectType;
    }

    public void setEffectType(String effectType) {
        this.effectType = effectType;
    }

    public String getFunction() {
        return function;
    }

    public void setFunction(String function) {
        this.function = function;
    }

    public String getPartition() {
        return partition;
    }

    public void setPartition(String partition) {
        this.partition = partition;
    }

    public String getClause() {
        return clause;
    }

    public void setClause(String clause) {
        this.clause = clause;
    }

    public String getJoinType() {
        return joinType;
    }

    public void setJoinType(String joinType) {
        this.joinType = joinType;
    }

    public String getCondition() {
        return condition;
    }

    public void setCondition(String condition) {
        this.condition = condition;
    }

    public String getProcessId() {
        return processId;
    }

    public void setProcessId(String processId) {
        this.processId = processId;
    }

    public String getProcessType() {
        return processType;
    }

    public void setProcessType(String processType) {
        this.processType = processType;
    }

    public String getProcedureId() {
        return procedureId;
    }

    public void setProcedureId(String procedureId) {
        this.procedureId = procedureId;
    }

    public String getSqlHash() {
        return sqlHash;
    }

    public void setSqlHash(String sqlHash) {
        this.sqlHash = sqlHash;
    }

    public String getSqlComment() {
        return sqlComment;
    }

    public void setSqlComment(String sqlComment) {
        this.sqlComment = sqlComment;
    }

    public String getTimestampMin() {
        return timestampMin;
    }

    public void setTimestampMin(String timestampMin) {
        this.timestampMin = timestampMin;
    }

    public String getTimestampMax() {
        return timestampMax;
    }

    public void setTimestampMax(String timestampMax) {
        this.timestampMax = timestampMax;
    }

    public String getBuiltIn() {
        return builtIn;
    }

    public void setBuiltIn(String builtIn) {
        this.builtIn = builtIn;
    }

    public String getCallStmt() {
        return callStmt;
    }

    public void setCallStmt(String callStmt) {
        this.callStmt = callStmt;
    }

    public String getCallCoordinate() {
        return callCoordinate;
    }

    public void setCallCoordinate(String callCoordinate) {
        this.callCoordinate = callCoordinate;
    }

    public XmlEndpoint getCaller() {
        return caller;
    }

    public void setCaller(XmlEndpoint caller) {
        this.caller = caller;
    }

    public XmlEndpoint getTarget() {
        return target;
    }

    public void setTarget(XmlEndpoint target) {
        this.target = target;
    }

    public List<XmlEndpoint> getCallees() {
        return callees;
    }

    public void setCallees(List<XmlEndpoint> callees) {
        this.callees = callees;
    }

    public List<XmlEndpoint> getSources() {
        return sources;
    }

    public void setSources(List<XmlEndpoint> sources) {
        this.sources = sources;
    }
}
