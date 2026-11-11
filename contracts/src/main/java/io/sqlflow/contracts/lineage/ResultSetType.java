package io.sqlflow.contracts.lineage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Complete 18-constant {@code ResultSetType} enum with exact wire {@code type} strings ({@code 03 §3.3.1}).
 */
public enum ResultSetType {
    /* 01 */ select_list("select_list", "RS-"),
    /* 02 */ array("array", "ARRAY-"),
    /* 03 */ struct("struct", "STRUCT-"),
    /* 04 */ result_of("result_of", "RESULT_OF_"),
    /* 05 */ cte("with_cte", "CTE-"),
    /* 06 */ insert_select("insert-select", "INSERT-SELECT"),
    /* 07 */ update_select("update-select", "UPDATE-SELECT"),
    /* 08 */ merge_update("merge-update", "MERGE-UPDATE"),
    /* 09 */ merge_insert("merge-insert", "MERGE-INSERT"),
    /* 10 */ output("output", "OUTPUT"),
    /* 11 */ update_set("update-set", "UPDATE-SET"),
    /* 12 */ pivot_table("pivot-table", "PIVOT-TABLE"),
    /* 13 */ unpivot_table("unpivot-table", "UNPIVOT-TABLE"),
    /* 14 */ alias("alias", ""),
    /* 15 */ function("function", "RESULT_OF_"),
    /* 16 */ case_when("case-when", "CASE-WHEN"),
    /* 17 */ cursor("cursor", ""),
    /* 18 */ variable("variable", "");

    private final String wireType;
    private final String defaultDisplayPrefix;

    ResultSetType(String wireType, String defaultDisplayPrefix) {
        this.wireType = wireType;
        this.defaultDisplayPrefix = defaultDisplayPrefix;
    }

    @JsonValue
    public String wireType() {
        return wireType;
    }

    public String defaultDisplayPrefix() {
        return defaultDisplayPrefix;
    }

    /**
     * Resolves a {@link ResultSetType} from either its enum identifier or wire {@code type} string
     * (normalizing hyphens/underscores and handling {@code "with_cte"} / {@code "cte"}).
     */
    @JsonCreator
    public static ResultSetType of(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        for (ResultSetType candidate : values()) {
            if (candidate.wireType.equals(trimmed) || candidate.name().equals(trimmed)) {
                return candidate;
            }
        }
        String underscored = trimmed.replace('-', '_');
        if ("with_cte".equals(underscored)) {
            return cte;
        }
        for (ResultSetType candidate : values()) {
            if (candidate.name().equals(underscored)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown ResultSetType: '" + raw + "'");
    }
}
