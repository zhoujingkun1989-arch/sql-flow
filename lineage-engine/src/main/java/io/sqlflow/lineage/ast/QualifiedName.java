package io.sqlflow.lineage.ast;

import io.sqlflow.lineage.lexer.SourceSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Multi-part SQL object or column name preserving per-segment quotedness, BigQuery multi-part
 * backtick expansion, {@code @dblink}, Snowflake {@code @stage}, external storage URIs, and 1-based spans.
 */
public record QualifiedName(
        List<NamePart> parts,
        String dbLink,
        boolean stageRef,
        boolean uriRef,
        boolean templated,
        SourceSpan span
) {
    public record NamePart(
            String raw,
            String value,
            boolean quoted,
            boolean templated,
            SourceSpan span
    ) {
    }

    public static QualifiedName single(String raw, String value, boolean quoted, boolean templated, SourceSpan span) {
        return new QualifiedName(
                List.of(new NamePart(raw, value, quoted, templated, span)),
                null,
                false,
                isExternalUri(value),
                templated,
                span);
    }

    public static QualifiedName ofUri(String uri, SourceSpan span) {
        return new QualifiedName(
                List.of(new NamePart(uri, uri, true, false, span)),
                null,
                false,
                true,
                false,
                span);
    }

    public static QualifiedName ofStage(String stageText, SourceSpan span) {
        return new QualifiedName(
                List.of(new NamePart(stageText, stageText, false, false, span)),
                null,
                true,
                false,
                false,
                span);
    }

    public static QualifiedName ofParts(List<String> rawParts, SourceSpan span) {
        if (rawParts == null || rawParts.isEmpty()) {
            return new QualifiedName(List.of(), null, false, false, false, span);
        }
        List<NamePart> out = new ArrayList<>(rawParts.size());
        for (String p : rawParts) {
            if (p != null && !p.isBlank()) {
                out.add(new NamePart(p, p, false, false, span));
            }
        }
        return new QualifiedName(out, null, false, false, false, span);
    }

    public boolean isEmpty() {
        return parts == null || parts.isEmpty();
    }

    /**
     * Expands BigQuery backtick-quoted multi-part identifiers such as {@code `my-proj.analytics.orders`}
     * into individual {@link NamePart}s when unescaped dots appear inside backticks.
     */
    public static List<NamePart> expandBigQueryBacktickParts(String rawToken, String unquoted, SourceSpan span) {
        if (unquoted == null || !unquoted.contains(".")) {
            return List.of(new NamePart(rawToken, unquoted != null ? unquoted : "", true, false, span));
        }
        String[] segments = unquoted.split("\\.", -1);
        List<NamePart> out = new ArrayList<>(segments.length);
        for (String seg : segments) {
            if (!seg.isEmpty()) {
                out.add(new NamePart("`" + seg + "`", seg, true, false, span));
            }
        }
        return out.isEmpty()
                ? List.of(new NamePart(rawToken, unquoted, true, false, span))
                : out;
    }

    public int size() {
        return parts.size();
    }

    public NamePart lastPart() {
        return parts.get(parts.size() - 1);
    }

    public String lastName() {
        return parts.isEmpty() ? "" : parts.get(parts.size() - 1).value();
    }

    public boolean lastQuoted() {
        return !parts.isEmpty() && parts.get(parts.size() - 1).quoted();
    }

    public NamePart partFromEnd(int indexFromEnd) {
        int idx = parts.size() - 1 - indexFromEnd;
        return idx >= 0 && idx < parts.size() ? parts.get(idx) : null;
    }

    public String fullDisplayName() {
        if (parts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(parts.get(i).value());
        }
        if (dbLink != null && !dbLink.isEmpty()) {
            sb.append('@').append(dbLink);
        }
        return sb.toString();
    }

    public String rawDisplayName() {
        if (parts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(parts.get(i).raw());
        }
        if (dbLink != null && !dbLink.isEmpty()) {
            sb.append('@').append(dbLink);
        }
        return sb.toString();
    }

    public static boolean isExternalUri(String text) {
        if (text == null || text.length() < 5) {
            return false;
        }
        String lower = text.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("s3://")
                || lower.startsWith("s3a://")
                || lower.startsWith("s3n://")
                || lower.startsWith("gs://")
                || lower.startsWith("gcs://")
                || lower.startsWith("hdfs://")
                || lower.startsWith("abfss://")
                || lower.startsWith("abfs://")
                || lower.startsWith("wasbs://")
                || lower.startsWith("wasb://")
                || lower.startsWith("azure://")
                || lower.startsWith("adl://")
                || lower.startsWith("file://")
                || lower.startsWith("oss://")
                || lower.startsWith("cos://");
    }

    @Override
    public String toString() {
        return fullDisplayName();
    }
}
