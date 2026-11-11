package io.sqlflow.contracts.lineage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Complete 14-constant {@code SubType} enum matching SQLFlow 8.2.2.15 ({@code 03 §3.3.2}).
 */
public enum SubType {
    scalar,
    cursor,
    record,
    record_type,
    function,
    unnest,
    values_table,
    alias_table,
    dblink,
    synonym,
    in,
    out,
    argument,
    temp_table;

    @JsonValue
    public String wireName() {
        return name();
    }

    @JsonCreator
    public static SubType fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String norm = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if ("temp".equals(norm)) {
            return temp_table;
        }
        if ("values".equals(norm)) {
            return values_table;
        }
        return SubType.valueOf(norm);
    }
}
