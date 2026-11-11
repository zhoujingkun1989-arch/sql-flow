package io.sqlflow.contracts.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Job execution status values ({@code 02 §5.5}).
 */
public enum JobStatus {
    CREATE("create"),
    PROCESS("process"),
    SUCCESS("success"),
    FAIL("fail"),
    DELETE("delete"),
    PARTIAL_SUCCESS("partial_success");

    private final String wireValue;

    JobStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String getWireValue() {
        return wireValue;
    }

    @JsonCreator
    public static JobStatus fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (JobStatus status : values()) {
            if (status.wireValue.equals(normalized) || status.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown JobStatus: " + raw);
    }
}
