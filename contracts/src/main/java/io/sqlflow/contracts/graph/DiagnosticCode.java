package io.sqlflow.contracts.graph;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * All 10 frozen {@code join-analysis.v1} diagnostic rule codes ({@code 00 §3.3 #8}, {@code 02 §5.3}, {@code 03 §4}).
 */
public enum DiagnosticCode {
    MISSING_JOIN_CONDITION("missing_join_condition", JoinAnalysisGraph.Severity.BLOCK),
    CARTESIAN_JOIN("cartesian_join", JoinAnalysisGraph.Severity.BLOCK),
    MANY_TO_MANY_RISK("many_to_many_risk", JoinAnalysisGraph.Severity.WARN),
    OUTER_JOIN_FILTER_RISK("outer_join_filter_risk", JoinAnalysisGraph.Severity.WARN),
    ANTI_JOIN_DETECTED("anti_join_detected", JoinAnalysisGraph.Severity.INFO),
    SELF_JOIN_DETECTED("self_join_detected", JoinAnalysisGraph.Severity.INFO),
    SEMI_JOIN_DETECTED("semi_join_detected", JoinAnalysisGraph.Severity.INFO),
    NON_INDEXED_JOIN_KEY("non_indexed_join_key", JoinAnalysisGraph.Severity.WARN),
    COMPLEX_PREDICATE("complex_predicate", JoinAnalysisGraph.Severity.WARN),
    UNPARSED_CONDITION("unparsed_condition", JoinAnalysisGraph.Severity.WARN);

    private final String wireValue;
    private final JoinAnalysisGraph.Severity defaultSeverity;

    DiagnosticCode(String wireValue, JoinAnalysisGraph.Severity defaultSeverity) {
        this.wireValue = wireValue;
        this.defaultSeverity = defaultSeverity;
    }

    @JsonValue
    public String getWireValue() {
        return wireValue;
    }

    public JoinAnalysisGraph.Severity getDefaultSeverity() {
        return defaultSeverity;
    }

    @JsonCreator
    public static DiagnosticCode fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (DiagnosticCode code : values()) {
            if (code.wireValue.equals(normalized) || code.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return code;
            }
        }
        throw new IllegalArgumentException("Unknown DiagnosticCode: " + raw);
    }
}
