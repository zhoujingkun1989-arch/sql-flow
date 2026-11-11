package io.sqlflow.contracts.vendor;

/**
 * Scoped catalog object categories corresponding to {@code ESQLDataObjectType} in {@code IdentifierProfile}.
 */
public enum DataObjectType {
    CATALOG,
    SCHEMA,
    TABLE,
    COLUMN,
    PROCEDURE
}
