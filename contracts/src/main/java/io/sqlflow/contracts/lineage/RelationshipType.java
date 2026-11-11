package io.sqlflow.contracts.lineage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * The 8 lineage relationship types in exact {@code ordinal()} declaration order ({@code 0..7})
 * matching SQLFlow 8.2.2.15 ({@code 03 §3.1}).
 */
public enum RelationshipType {
    /* 0 */ fdd,
    /* 1 */ fddi,
    /* 2 */ frd,
    /* 3 */ fdr,
    /* 4 */ join,
    /* 5 */ call,
    /* 6 */ er,
    /* 7 */ crud;

    @JsonValue
    public String wireName() {
        return name();
    }

    @JsonCreator
    public static RelationshipType fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return RelationshipType.valueOf(value.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Transitive path-collapsing dominance rule ({@code 03 §3.1}):
     * {@code fdd + fdd -> fdd}; if either hop is an indirect/recordset dependency ({@code fddi}, {@code frd}, {@code fdr}),
     * the collapsed edge takes {@code max(ord(first), ord(second))} across {@code 0..3} (degrading to {@code fdr}).
     */
    public static RelationshipType collapseTransitive(RelationshipType first, RelationshipType second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        if (first.ordinal() <= fdr.ordinal() && second.ordinal() <= fdr.ordinal()) {
            return values()[Math.max(first.ordinal(), second.ordinal())];
        }
        return first == second ? first : second;
    }
}
