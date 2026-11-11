package io.sqlflow.contracts.vendor;

/**
 * Identifier comparison sensitivity mode for a SQL dialect.
 */
public enum CaseCompareMode {
    /** Exact Unicode code-point comparison. */
    SENSITIVE,
    /** Case-insensitive comparison. */
    INSENSITIVE,
    /** Server-collation dependent; defaults to case-insensitive unless overridden by customize.yaml. */
    COLLATION_BASED,
    /** Quoted identifiers inherit the unquoted comparison sensitivity of the same rule. */
    SAME_AS_UNQUOTED;

    /**
     * Resolves whether comparison is effectively case-sensitive when given the unquoted rule's mode.
     */
    public boolean isCaseSensitive(CaseCompareMode unquotedMode) {
        return switch (this) {
            case SENSITIVE -> true;
            case INSENSITIVE, COLLATION_BASED -> false;
            case SAME_AS_UNQUOTED -> unquotedMode == SENSITIVE;
        };
    }
}
