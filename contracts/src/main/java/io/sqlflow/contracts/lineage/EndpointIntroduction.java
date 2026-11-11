package io.sqlflow.contracts.lineage;

/**
 * Complete 11-constant {@code EndpointIntroduction} enum ({@code 03 §3.3.2}).
 */
public enum EndpointIntroduction {
    CATALOG_REFERENCE,
    CREATE_TABLE_DDL,
    SELECT_INTO,
    CTAS,
    CLONE_TABLE,
    CTE_DEFINITION,
    DERIVED_SUBQUERY,
    FUNCTION_CALL,
    LITERAL,
    VARIABLE_REFERENCE,
    UNKNOWN
}
