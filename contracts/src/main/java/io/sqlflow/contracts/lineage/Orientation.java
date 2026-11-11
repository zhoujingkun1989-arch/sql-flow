package io.sqlflow.contracts.lineage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Graph layout orientation direction ({@code 03 §1.5.2, §4.2.3}).
 */
public enum Orientation {
    LEFT_TO_RIGHT((byte) 0, "LR"),
    TOP_TO_BOTTOM((byte) 1, "TB"),
    RIGHT_TO_LEFT((byte) 2, "RL"),
    BOTTOM_TO_TOP((byte) 3, "BT");

    private final byte layoutCode;
    private final String shortCode;

    Orientation(byte layoutCode, String shortCode) {
        this.layoutCode = layoutCode;
        this.shortCode = shortCode;
    }

    public byte layoutCode() {
        return layoutCode;
    }

    public String shortCode() {
        return shortCode;
    }

    @JsonValue
    public String wireName() {
        return name();
    }

    @JsonCreator
    public static Orientation fromString(String value) {
        if (value == null || value.isBlank()) {
            return LEFT_TO_RIGHT;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "0", "LR", "LEFT_TO_RIGHT" -> LEFT_TO_RIGHT;
            case "1", "TB", "TD", "TOP_TO_BOTTOM" -> TOP_TO_BOTTOM;
            case "2", "RL", "RIGHT_TO_LEFT" -> RIGHT_TO_LEFT;
            case "3", "BT", "BOTTOM_TO_TOP" -> BOTTOM_TO_TOP;
            default -> Orientation.valueOf(normalized);
        };
    }

    public static Orientation fromLayoutCode(Integer code) {
        if (code == null) {
            return LEFT_TO_RIGHT;
        }
        return switch (code) {
            case 1 -> TOP_TO_BOTTOM;
            case 2 -> RIGHT_TO_LEFT;
            case 3 -> BOTTOM_TO_TOP;
            default -> LEFT_TO_RIGHT;
        };
    }
}
