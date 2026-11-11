package io.sqlflow.graph.join;

import io.sqlflow.contracts.graph.JoinAnalysisGraph.SourceRange;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.SourceRangeQuality;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Clean-room multi-dialect SQL tokenizer with exact 1-based line/column {@link SourceRange}
 * tracking and character-offset slicing for {@code join-analysis.v1}.
 */
public final class SqlJoinLexer {

    public enum TokenKind {
        IDENT,
        QUOTED_IDENT,
        STRING,
        NUMBER,
        OPERATOR,
        LPAREN,
        RPAREN,
        COMMA,
        DOT,
        SEMICOLON,
        STAR,
        TEMPLATE,
        UNKNOWN,
        EOF
    }

    public record Token(
            TokenKind kind,
            String text,
            String upper,
            int startOffset,
            int endOffset,
            int startLine,
            int startCol,
            int endLine,
            int endCol) {

        public boolean isWord(String kw) {
            return kind == TokenKind.IDENT && upper.equals(kw);
        }

        public boolean isIdentifierLike() {
            return kind == TokenKind.IDENT || kind == TokenKind.QUOTED_IDENT;
        }

        public String cleanIdentifier() {
            if (kind == TokenKind.QUOTED_IDENT && text.length() >= 2) {
                return text.substring(1, text.length() - 1);
            }
            return text;
        }

        public SourceRange toSourceRange() {
            return new SourceRange(startLine, startCol, endLine, endCol, SourceRangeQuality.EXACT);
        }
    }

    private SqlJoinLexer() {
    }

    public static List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        if (sql == null || sql.isEmpty()) {
            tokens.add(new Token(TokenKind.EOF, "", "", 0, 0, 1, 1, 1, 1));
            return tokens;
        }

        int len = sql.length();
        int pos = 0;
        int line = 1;
        int col = 1;

        while (pos < len) {
            char c = sql.charAt(pos);

            // Whitespace
            if (Character.isWhitespace(c)) {
                if (c == '\r') {
                    pos++;
                    if (pos < len && sql.charAt(pos) == '\n') {
                        pos++;
                    }
                    line++;
                    col = 1;
                } else if (c == '\n') {
                    pos++;
                    line++;
                    col = 1;
                } else {
                    pos++;
                    col++;
                }
                continue;
            }

            // Single-line comment -- ...
            if (c == '-' && pos + 1 < len && sql.charAt(pos + 1) == '-') {
                while (pos < len && sql.charAt(pos) != '\n' && sql.charAt(pos) != '\r') {
                    pos++;
                    col++;
                }
                continue;
            }

            // Block comment /* ... */
            if (c == '/' && pos + 1 < len && sql.charAt(pos + 1) == '*') {
                pos += 2;
                col += 2;
                while (pos < len) {
                    char bc = sql.charAt(pos);
                    if (bc == '*' && pos + 1 < len && sql.charAt(pos + 1) == '/') {
                        pos += 2;
                        col += 2;
                        break;
                    }
                    if (bc == '\r') {
                        pos++;
                        if (pos < len && sql.charAt(pos) == '\n') {
                            pos++;
                        }
                        line++;
                        col = 1;
                    } else if (bc == '\n') {
                        pos++;
                        line++;
                        col = 1;
                    } else {
                        pos++;
                        col++;
                    }
                }
                continue;
            }

            int startOffset = pos;
            int startLine = line;
            int startCol = col;

            // Jinja / dbt template {{ ... }} or {% ... %} or ${...}
            if ((c == '{' && pos + 1 < len && (sql.charAt(pos + 1) == '{' || sql.charAt(pos + 1) == '%'))
                    || (c == '$' && pos + 1 < len && sql.charAt(pos + 1) == '{')) {
                char closeChar = (c == '$') ? '}' : (sql.charAt(pos + 1) == '{' ? '}' : '%');
                pos += 2;
                col += 2;
                while (pos < len) {
                    char tc = sql.charAt(pos);
                    if (c == '$' && tc == '}') {
                        pos++;
                        col++;
                        break;
                    }
                    if (c == '{' && tc == closeChar && pos + 1 < len && sql.charAt(pos + 1) == '}') {
                        pos += 2;
                        col += 2;
                        break;
                    }
                    if (tc == '\n') {
                        pos++;
                        line++;
                        col = 1;
                    } else {
                        pos++;
                        col++;
                    }
                }
                String raw = sql.substring(startOffset, pos);
                tokens.add(new Token(
                        TokenKind.TEMPLATE, raw, raw.toUpperCase(Locale.ROOT),
                        startOffset, pos, startLine, startCol, line, Math.max(startCol, col - 1)));
                continue;
            }

            // String literal '...' or N'...'
            if (c == '\'' || ((c == 'N' || c == 'n') && pos + 1 < len && sql.charAt(pos + 1) == '\'')) {
                if (c != '\'') {
                    pos++;
                    col++;
                }
                pos++;
                col++;
                while (pos < len) {
                    char sc = sql.charAt(pos);
                    if (sc == '\\' && pos + 1 < len) {
                        pos += 2;
                        col += 2;
                        continue;
                    }
                    if (sc == '\'') {
                        pos++;
                        col++;
                        if (pos < len && sql.charAt(pos) == '\'') {
                            pos++;
                            col++;
                            continue;
                        }
                        break;
                    }
                    if (sc == '\n') {
                        pos++;
                        line++;
                        col = 1;
                    } else {
                        pos++;
                        col++;
                    }
                }
                String raw = sql.substring(startOffset, pos);
                tokens.add(new Token(
                        TokenKind.STRING, raw, raw,
                        startOffset, pos, startLine, startCol, line, Math.max(startCol, col - 1)));
                continue;
            }

            // Quoted identifiers "...", `...`, [...]
            if (c == '"' || c == '`' || c == '[') {
                char endQuote = (c == '[') ? ']' : c;
                pos++;
                col++;
                while (pos < len) {
                    char qc = sql.charAt(pos);
                    if (qc == endQuote) {
                        pos++;
                        col++;
                        if (qc == '"' && pos < len && sql.charAt(pos) == '"') {
                            pos++;
                            col++;
                            continue;
                        }
                        break;
                    }
                    if (qc == '\n') {
                        pos++;
                        line++;
                        col = 1;
                    } else {
                        pos++;
                        col++;
                    }
                }
                String raw = sql.substring(startOffset, pos);
                tokens.add(new Token(
                        TokenKind.QUOTED_IDENT, raw, raw.toUpperCase(Locale.ROOT),
                        startOffset, pos, startLine, startCol, line, Math.max(startCol, col - 1)));
                continue;
            }

            // Numbers
            if (Character.isDigit(c)) {
                while (pos < len && (Character.isDigit(sql.charAt(pos)) || sql.charAt(pos) == '.')) {
                    pos++;
                    col++;
                }
                String raw = sql.substring(startOffset, pos);
                tokens.add(new Token(
                        TokenKind.NUMBER, raw, raw,
                        startOffset, pos, startLine, startCol, line, col - 1));
                continue;
            }

            // Identifiers / Keywords (supporting @, #, $ prefixes in T-SQL/Oracle/Snowflake)
            if (Character.isLetter(c) || c == '_' || c == '@' || c == '#' || c == '$') {
                pos++;
                col++;
                while (pos < len) {
                    char ic = sql.charAt(pos);
                    if (Character.isLetterOrDigit(ic) || ic == '_' || ic == '$' || ic == '#') {
                        pos++;
                        col++;
                    } else {
                        break;
                    }
                }
                String raw = sql.substring(startOffset, pos);
                tokens.add(new Token(
                        TokenKind.IDENT, raw, raw.toUpperCase(Locale.ROOT),
                        startOffset, pos, startLine, startCol, line, col - 1));
                continue;
            }

            // Single-char punctuation
            switch (c) {
                case '(' -> {
                    tokens.add(new Token(TokenKind.LPAREN, "(", "(", pos, pos + 1, line, col, line, col));
                    pos++;
                    col++;
                    continue;
                }
                case ')' -> {
                    tokens.add(new Token(TokenKind.RPAREN, ")", ")", pos, pos + 1, line, col, line, col));
                    pos++;
                    col++;
                    continue;
                }
                case ',' -> {
                    tokens.add(new Token(TokenKind.COMMA, ",", ",", pos, pos + 1, line, col, line, col));
                    pos++;
                    col++;
                    continue;
                }
                case '.' -> {
                    tokens.add(new Token(TokenKind.DOT, ".", ".", pos, pos + 1, line, col, line, col));
                    pos++;
                    col++;
                    continue;
                }
                case ';' -> {
                    tokens.add(new Token(TokenKind.SEMICOLON, ";", ";", pos, pos + 1, line, col, line, col));
                    pos++;
                    col++;
                    continue;
                }
                case '*' -> {
                    tokens.add(new Token(TokenKind.STAR, "*", "*", pos, pos + 1, line, col, line, col));
                    pos++;
                    col++;
                    continue;
                }
                default -> {
                }
            }

            // Multi-char & single-char operators
            if (pos + 2 < len && sql.startsWith("<=>", pos)) {
                tokens.add(new Token(TokenKind.OPERATOR, "<=>", "<=>", pos, pos + 3, line, col, line, col + 2));
                pos += 3;
                col += 3;
                continue;
            }
            if (pos + 1 < len) {
                String two = sql.substring(pos, pos + 2);
                if (two.equals("<=") || two.equals(">=") || two.equals("<>") || two.equals("!=")
                        || two.equals("||") || two.equals("::") || two.equals("->")) {
                    tokens.add(new Token(TokenKind.OPERATOR, two, two, pos, pos + 2, line, col, line, col + 1));
                    pos += 2;
                    col += 2;
                    continue;
                }
            }
            if ("=<>+-/%|~^:&!".indexOf(c) >= 0) {
                String one = String.valueOf(c);
                tokens.add(new Token(TokenKind.OPERATOR, one, one, pos, pos + 1, line, col, line, col));
                pos++;
                col++;
                continue;
            }

            // Unrecognized symbol (e.g. '?', '{', '}', '\')
            String unk = String.valueOf(c);
            tokens.add(new Token(TokenKind.UNKNOWN, unk, unk, pos, pos + 1, line, col, line, col));
            pos++;
            col++;
        }

        tokens.add(new Token(TokenKind.EOF, "", "", len, len, line, col, line, col));
        return tokens;
    }

    public static SourceRange spanRange(Token start, Token end) {
        if (start == null && end == null) {
            return new SourceRange(1, 1, 1, 1, SourceRangeQuality.MISSING);
        }
        Token s = start != null ? start : end;
        Token e = end != null ? end : start;
        return new SourceRange(s.startLine(), s.startCol(), e.endLine(), e.endCol(), SourceRangeQuality.EXACT);
    }
}
