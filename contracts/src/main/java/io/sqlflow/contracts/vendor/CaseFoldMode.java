package io.sqlflow.contracts.vendor;

import java.util.Locale;

/**
 * Identifier case-folding mode applied when canonicalizing SQL identifiers for a dialect.
 */
public enum CaseFoldMode {
    NONE,
    UPPER,
    UPPER_SIMPLE,
    LOWER,
    LOWER_SIMPLE,
    LOWER_ASCII,
    SIMPLE_FOLD;

    /**
     * Applies this case-folding rule to an unquoted or quoted SQL identifier token.
     */
    public String fold(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return identifier;
        }
        return switch (this) {
            case NONE -> identifier;
            case UPPER, UPPER_SIMPLE -> identifier.toUpperCase(Locale.ROOT);
            case LOWER, LOWER_SIMPLE, SIMPLE_FOLD -> identifier.toLowerCase(Locale.ROOT);
            case LOWER_ASCII -> foldAsciiLower(identifier);
        };
    }

    private static String foldAsciiLower(String s) {
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                if (sb == null) {
                    sb = new StringBuilder(s.length());
                    sb.append(s, 0, i);
                }
                sb.append((char) (c + ('a' - 'A')));
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? s : sb.toString();
    }
}
