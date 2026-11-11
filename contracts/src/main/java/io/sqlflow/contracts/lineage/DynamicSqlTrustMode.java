package io.sqlflow.contracts.lineage;

/**
 * Trust mode for dynamic SQL string abstract interpretation ({@code 03 §2.5.11, §3.5.1}).
 */
public enum DynamicSqlTrustMode {
    LEGACY,
    SHADOW
}
