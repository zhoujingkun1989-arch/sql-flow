package io.sqlflow.contracts.lineage;

/**
 * Complete 12-constant {@code EndpointKind} enum ({@code 03 §3.3.2}).
 */
public enum EndpointKind {
    CATALOG_OBJECT,
    LOCAL_TEMP_TABLE,
    GLOBAL_TEMP_TABLE,
    TABLE_VARIABLE,
    TEMPDB_OBJECT,
    CTE,
    DERIVED_RESULTSET,
    TVF_RESULT,
    CONSTANT,
    VARIABLE,
    DYNAMIC_TEMPLATE,
    UNKNOWN
}
