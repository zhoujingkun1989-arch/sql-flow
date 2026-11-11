package io.sqlflow.lineage.lexer;

import io.sqlflow.contracts.lineage.Coordinate;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.TokenItem;
import java.util.List;
import java.util.Locale;

/**
 * Coordinate-preserving lexical token with 1-based {@code [line, col]} span and 0-based character offsets.
 */
public record SqlToken(
        TokenType type,
        String rawText,
        String value,
        String upperValue,
        boolean quoted,
        boolean templated,
        SourceSpan span
) {
    public SqlToken(
            TokenType type,
            String rawText,
            String value,
            boolean quoted,
            boolean templated,
            SourceSpan span) {
        this(
                type,
                rawText,
                value,
                value != null ? value.toUpperCase(Locale.ROOT) : "",
                quoted,
                templated,
                span);
    }

    public long line() {
        return span.startLine();
    }

    public long column() {
        return span.startCol();
    }

    public long endLine() {
        return span.endLine();
    }

    public long endColumn() {
        return span.endCol();
    }

    public int offset() {
        return span.startOffset();
    }

    public int endOffset() {
        return span.endOffset();
    }

    public boolean isEof() {
        return type == TokenType.EOF;
    }

    public boolean isComment() {
        return type == TokenType.LINE_COMMENT || type == TokenType.BLOCK_COMMENT;
    }

    public boolean isWord(String expectedUpper) {
        return !quoted
                && (type == TokenType.KEYWORD || type == TokenType.IDENTIFIER)
                && upperValue.equals(expectedUpper);
    }

    public boolean isAnyWord(String... expectedUppers) {
        if (quoted || (type != TokenType.KEYWORD && type != TokenType.IDENTIFIER)) {
            return false;
        }
        for (String s : expectedUppers) {
            if (upperValue.equals(s)) {
                return true;
            }
        }
        return false;
    }

    public boolean isIdentifierLike() {
        return type == TokenType.IDENTIFIER
                || type == TokenType.QUOTED_IDENTIFIER
                || type == TokenType.KEYWORD
                || type == TokenType.TEMPLATE_TOKEN
                || type == TokenType.BIND_VARIABLE;
    }

    public boolean isSymbol(String sym) {
        return (type == TokenType.PUNCTUATION || type == TokenType.OPERATOR) && rawText.equals(sym);
    }

    public List<Coordinate> toCoordinates(String hashCode) {
        return span.toCoordinates(hashCode);
    }

    public TokenItem toDemoTokenItem() {
        return new TokenItem(rawText, type.name(), span.startLine(), span.startCol(), span.startOffset());
    }
}
