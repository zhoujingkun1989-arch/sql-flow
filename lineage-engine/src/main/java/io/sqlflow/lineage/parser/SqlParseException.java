package io.sqlflow.lineage.parser;

import io.sqlflow.lineage.lexer.SourceSpan;

/**
 * Structured syntax error carrying exact 1-based {@link SourceSpan} coordinates.
 */
public final class SqlParseException extends RuntimeException {

    private final SourceSpan span;
    private final String nearToken;

    public SqlParseException(String message, SourceSpan span, String nearToken) {
        super(message);
        this.span = span != null ? span : SourceSpan.SYNTHETIC;
        this.nearToken = nearToken;
    }

    public SourceSpan span() {
        return span;
    }

    public String nearToken() {
        return nearToken;
    }

    public String formattedSyntaxError() {
        long line = Math.max(1L, span.startLine());
        long col = Math.max(1L, span.startCol());
        String near = (nearToken == null || nearToken.isEmpty()) ? "EOF" : nearToken;
        return "Syntax error(" + line + "," + col + ") near: " + near + " - " + getMessage();
    }
}
