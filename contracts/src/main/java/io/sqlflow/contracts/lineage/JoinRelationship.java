package io.sqlflow.contracts.lineage;

/**
 * Specialized {@link Relationship} subclass for {@code type = "join"} edges ({@code 03 §1.5.2}).
 */
public class JoinRelationship extends Relationship {

    public JoinRelationship() {
        super(null, RelationshipType.join);
    }

    public JoinRelationship(String id, String joinType, String clause, String condition) {
        super(id, RelationshipType.join);
        setJoinType(joinType);
        setClause(clause);
        setCondition(condition);
    }
}
