package io.sqlflow.contracts.lineage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Complete 36-constant {@code EffectType} enum matching SQLFlow 8.2.2.15 ({@code 03 §3.2.1}).
 */
public enum EffectType {
    /* 01 */ select,
    /* 02 */ insert,
    /* 03 */ replace,
    /* 04 */ update,
    /* 05 */ join,
    /* 06 */ merge_update,
    /* 07 */ merge_insert,
    /* 08 */ merge_when,
    /* 09 */ create_view,
    /* 10 */ create_table,
    /* 11 */ merge,
    /* 12 */ delete,
    /* 13 */ function,
    /* 14 */ rename_table,
    /* 15 */ swap_table,
    /* 16 */ append_from,
    /* 17 */ like_table,
    /* 18 */ cursor,
    /* 19 */ output,
    /* 20 */ trigger,
    /* 21 */ clone_database,
    /* 22 */ clone_schema,
    /* 23 */ clone_table,
    /* 24 */ rowtype,
    /* 25 */ unload,
    /* 26 */ copy,
    /* 27 */ foreign_key,
    /* 28 */ create_synonym,
    /* 29 */ exchange_partition,
    /* 30 */ synonym,
    /* 31 */ drop_table,
    /* 32 */ drop_table_column,
    /* 33 */ add_table_column,
    /* 34 */ truncate_table,
    /* 35 */ external_script_passthrough,
    /* 36 */ switch_partition;

    @JsonValue
    public String wireName() {
        return name();
    }

    @JsonCreator
    public static EffectType fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return EffectType.valueOf(normalized);
    }
}
