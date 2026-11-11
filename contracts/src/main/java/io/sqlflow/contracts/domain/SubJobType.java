package io.sqlflow.contracts.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Sub-job execution stage type ({@code 02 §5.5}).
 */
public enum SubJobType {
    DATAFLOW("dataflow"),
    TABLE_AFFECT_SQL("table_affect_sql");

    private final String wireValue;

    SubJobType(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String getWireValue() {
        return wireValue;
    }

    @JsonCreator
    public static SubJobType fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (SubJobType type : values()) {
            if (type.wireValue.equals(normalized) || type.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown SubJobType: " + raw);
    }
}
