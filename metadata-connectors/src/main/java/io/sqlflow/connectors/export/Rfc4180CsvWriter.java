package io.sqlflow.connectors.export;

import java.util.List;

/**
 * Strict RFC 4180 CSV encoder supporting custom field delimiters, double-quote escaping ({@code ""}),
 * and multi-line cell quoting ({@code OBL-087}, {@code 00 §3.3 #15}).
 */
public final class Rfc4180CsvWriter {

    private final char delimiter;
    private final String lineSeparator;
    private final StringBuilder buffer = new StringBuilder(1024);
    private boolean firstRow = true;

    public Rfc4180CsvWriter() {
        this(',', "\n");
    }

    public Rfc4180CsvWriter(char delimiter) {
        this(delimiter, "\n");
    }

    public Rfc4180CsvWriter(char delimiter, String lineSeparator) {
        this.delimiter = delimiter;
        this.lineSeparator = lineSeparator != null ? lineSeparator : "\n";
    }

    public Rfc4180CsvWriter writeRow(List<String> cells) {
        if (!firstRow) {
            buffer.append(lineSeparator);
        }
        firstRow = false;
        if (cells == null || cells.isEmpty()) {
            return this;
        }
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                buffer.append(delimiter);
            }
            appendEscapedCell(cells.get(i));
        }
        return this;
    }

    private void appendEscapedCell(String raw) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        boolean mustQuote = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == delimiter || c == '"' || c == '\n' || c == '\r') {
                mustQuote = true;
                break;
            }
        }
        if (!mustQuote) {
            buffer.append(raw);
            return;
        }
        buffer.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') {
                buffer.append("\"\"");
            } else {
                buffer.append(c);
            }
        }
        buffer.append('"');
    }

    @Override
    public String toString() {
        return buffer.toString();
    }
}
