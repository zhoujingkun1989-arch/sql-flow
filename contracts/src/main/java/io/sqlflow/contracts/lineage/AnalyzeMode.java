package io.sqlflow.contracts.lineage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Lineage analyzer execution mode ({@code 03 §3.5.1}).
 */
public enum AnalyzeMode {
    dataflow,
    crud,
    dynamic;

    @JsonValue
    public String wireName() {
        return name();
    }

    @JsonCreator
    public static AnalyzeMode fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return AnalyzeMode.valueOf(raw.trim().toLowerCase(Locale.ROOT));
    }
}
