package io.sqlflow.contracts.vendor;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/**
 * Case-folding and case-comparison rules for a specific {@link DataObjectType} within an {@link IdentifierProfile}.
 */
public record IdentifierRule(
        CaseFoldMode unquotedFold,
        CaseCompareMode unquotedCompare,
        CaseFoldMode quotedFold,
        CaseCompareMode quotedCompare,
        CaseFoldMode collapseFold
) {
    public IdentifierRule {
        Objects.requireNonNull(unquotedFold, "unquotedFold must not be null");
        Objects.requireNonNull(unquotedCompare, "unquotedCompare must not be null");
        Objects.requireNonNull(quotedFold, "quotedFold must not be null");
        Objects.requireNonNull(quotedCompare, "quotedCompare must not be null");
        if (collapseFold == null) {
            collapseFold = CaseFoldMode.NONE;
        }
    }

    public static IdentifierRule of(
            CaseFoldMode unquotedFold,
            CaseCompareMode unquotedCompare,
            CaseFoldMode quotedFold,
            CaseCompareMode quotedCompare) {
        return new IdentifierRule(unquotedFold, unquotedCompare, quotedFold, quotedCompare, CaseFoldMode.NONE);
    }

    public boolean isCaseSensitive(boolean quoted) {
        return quoted
                ? quotedCompare.isCaseSensitive(unquotedCompare)
                : unquotedCompare.isCaseSensitive(unquotedCompare);
    }

    /**
     * Normalizes an identifier after quote stripping, applying dialect folding and UTF-8 byte truncation if configured.
     */
    public String normalize(String identifier, boolean quoted, int maxUtf8Bytes) {
        if (identifier == null) {
            return null;
        }
        String folded = (quoted ? quotedFold : unquotedFold).fold(identifier);
        return truncateUtf8Bytes(folded, maxUtf8Bytes);
    }

    /**
     * Computes a lookup key suitable for hash maps respecting comparison sensitivity.
     */
    public String canonicalLookupKey(String identifier, boolean quoted, int maxUtf8Bytes) {
        String normalized = normalize(identifier, quoted, maxUtf8Bytes);
        if (normalized == null) {
            return null;
        }
        if (isCaseSensitive(quoted)) {
            return normalized;
        }
        if (collapseFold != CaseFoldMode.NONE) {
            return collapseFold.fold(normalized);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    /**
     * Compares two identifiers according to their quotedness and this dialect rule.
     */
    public boolean matches(String left, boolean leftQuoted, String right, boolean rightQuoted, int maxUtf8Bytes) {
        if (left == null || right == null) {
            return Objects.equals(left, right);
        }
        String normLeft = normalize(left, leftQuoted, maxUtf8Bytes);
        String normRight = normalize(right, rightQuoted, maxUtf8Bytes);
        if (isCaseSensitive(leftQuoted) || isCaseSensitive(rightQuoted)) {
            return normLeft.equals(normRight);
        }
        return normLeft.equalsIgnoreCase(normRight);
    }

    private static String truncateUtf8Bytes(String input, int maxUtf8Bytes) {
        if (maxUtf8Bytes <= 0 || input.isEmpty()) {
            return input;
        }
        byte[] utf8 = input.getBytes(StandardCharsets.UTF_8);
        if (utf8.length <= maxUtf8Bytes) {
            return input;
        }
        int byteCount = 0;
        int charIndex = 0;
        while (charIndex < input.length()) {
            int codePoint = input.codePointAt(charIndex);
            int cpBytes = utf8Length(codePoint);
            if (byteCount + cpBytes > maxUtf8Bytes) {
                break;
            }
            byteCount += cpBytes;
            charIndex += Character.charCount(codePoint);
        }
        return input.substring(0, charIndex);
    }

    private static int utf8Length(int codePoint) {
        if (codePoint <= 0x7F) {
            return 1;
        } else if (codePoint <= 0x7FF) {
            return 2;
        } else if (codePoint <= 0xFFFF) {
            return 3;
        }
        return 4;
    }
}
