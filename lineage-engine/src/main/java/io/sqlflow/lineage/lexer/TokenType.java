package io.sqlflow.lineage.lexer;

/**
 * Lexical token classification across all 48 {@code EDbVendor} dialects.
 */
public enum TokenType {
    KEYWORD,
    IDENTIFIER,
    QUOTED_IDENTIFIER,
    STRING_LITERAL,
    NUMBER_LITERAL,
    OPERATOR,
    PUNCTUATION,
    BIND_VARIABLE,
    TEMPLATE_TOKEN,
    STAGE_REFERENCE,
    LINE_COMMENT,
    BLOCK_COMMENT,
    WHITESPACE,
    EOF
}
