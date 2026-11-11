package io.sqlflow.contracts.lineage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * Directed lineage edge ({@code fdd}, {@code fddi}, {@code frd}, {@code fdr}, {@code join}, {@code call},
 * {@code er}, {@code crud}) in {@code DataFlow.relationships} ({@code 02 §5.1}, {@code 03 §1.5.2, §3.1}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type",
        defaultImpl = Relationship.class,
        visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = JoinRelationship.class, name = "join")
})
@JsonPropertyOrder({
    "id", "type", "joinType", "clause", "condition", "effectType",
    "target", "sources", "processId", "processType", "procedureId",
    "function", "partition", "sqlHash", "sqlComment",
    "timestampMin", "timestampMax", "caller", "callees",
    "distance", "builtIn", "callStmt", "callCoordinate"
})
public class Relationship {

    private String id;
    private String type;
    private String joinType;
    private String clause;
    private String condition;
    private String effectType;
    private RelationshipElement target;
    private List<RelationshipElement> sources;
    private String processId;
    private String processType;
    private String procedureId;
    private String function;
    private String partition;
    private String sqlHash;
    private String sqlComment;
    private String timestampMin;
    private String timestampMax;
    private RelationshipElement caller;
    private List<RelationshipElement> callees;
    private Integer distance;
    private Boolean builtIn;
    private String callStmt;
    private List<Coordinate> callCoordinate;

    public Relationship() {
    }

    public Relationship(String id, RelationshipType type) {
        this.id = id;
        this.type = type != null ? type.wireName() : null;
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

    public String getJoinType() {
        return joinType;
    }

    public void setJoinType(String joinType) {
        this.joinType = joinType;
    }

    public String getClause() {
        return clause;
    }

    public void setClause(String clause) {
        this.clause = clause;
    }

    public String getCondition() {
        return condition;
    }

    public void setCondition(String condition) {
        this.condition = condition;
    }

    public String getEffectType() {
        return effectType;
    }

    public void setEffectType(String effectType) {
        this.effectType = effectType;
    }

    public RelationshipElement getTarget() {
        return target;
    }

    public void setTarget(RelationshipElement target) {
        this.target = target;
    }

    public List<RelationshipElement> getSources() {
        return sources;
    }

    public void setSources(List<RelationshipElement> sources) {
        this.sources = sources;
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

    public RelationshipElement getCaller() {
        return caller;
    }

    public void setCaller(RelationshipElement caller) {
        this.caller = caller;
    }

    public List<RelationshipElement> getCallees() {
        return callees;
    }

    public void setCallees(List<RelationshipElement> callees) {
        this.callees = callees;
    }

    public Integer getDistance() {
        return distance;
    }

    public void setDistance(Integer distance) {
        this.distance = distance;
    }

    public Boolean getBuiltIn() {
        return builtIn;
    }

    public void setBuiltIn(Boolean builtIn) {
        this.builtIn = builtIn;
    }

    public String getCallStmt() {
        return callStmt;
    }

    public void setCallStmt(String callStmt) {
        this.callStmt = callStmt;
    }

    public List<Coordinate> getCallCoordinate() {
        return callCoordinate;
    }

    public void setCallCoordinate(List<Coordinate> callCoordinate) {
        this.callCoordinate = callCoordinate;
    }
}
