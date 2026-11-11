package io.sqlflow.lineage.parser;

import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.lineage.ast.QualifiedName;
import io.sqlflow.lineage.ast.QualifiedName.NamePart;
import io.sqlflow.lineage.lexer.SourceSpan;
import io.sqlflow.lineage.lexer.SqlLexer;
import io.sqlflow.lineage.lexer.SqlToken;
import io.sqlflow.lineage.lexer.TokenType;
import java.util.ArrayList;
import java.util.List;

/**
 * Lookahead token cursor with multi-part dialect identifier helpers and dollar-quoted span relocation.
 */
public final class ParserCursor {

    private final String sourceSql;
    private final List<SqlToken> tokens;
    private final EDbVendor vendor;
    private int pos = 0;

    public ParserCursor(String sourceSql, List<SqlToken> tokens, EDbVendor vendor) {
        this.sourceSql = sourceSql != null ? sourceSql : "";
        this.tokens = tokens;
        this.vendor = vendor != null ? vendor : EDbVendor.dbvoracle;
    }

    public String sourceSql() {
        return sourceSql;
    }

    public EDbVendor vendor() {
        return vendor;
    }

    public int position() {
        return pos;
    }

    public void seek(int newPos) {
        this.pos = Math.max(0, Math.min(newPos, tokens.size() - 1));
    }

    public boolean isAtEnd() {
        return peek().isEof();
    }

    public SqlToken peek() {
        return tokens.get(pos);
    }

    public SqlToken peek(int offset) {
        int idx = pos + offset;
        if (idx < 0) {
            return tokens.get(0);
        }
        if (idx >= tokens.size()) {
            return tokens.get(tokens.size() - 1);
        }
        return tokens.get(idx);
    }

    public SqlToken previous() {
        return tokens.get(Math.max(0, pos - 1));
    }

    public SqlToken advance() {
        SqlToken current = tokens.get(pos);
        if (!current.isEof()) {
            pos++;
        }
        return current;
    }

    public boolean checkWord(String upper) {
        return peek().isWord(upper);
    }

    public boolean checkAnyWord(String... uppers) {
        return peek().isAnyWord(uppers);
    }

    public boolean checkSymbol(String symbol) {
        return peek().isSymbol(symbol);
    }

    public boolean matchWord(String upper) {
        if (peek().isWord(upper)) {
            advance();
            return true;
        }
        return false;
    }

    public boolean matchAnyWord(String... uppers) {
        if (peek().isAnyWord(uppers)) {
            advance();
            return true;
        }
        return false;
    }

    public boolean matchWords(String upper1, String upper2) {
        if (peek().isWord(upper1) && peek(1).isWord(upper2)) {
            advance();
            advance();
            return true;
        }
        return false;
    }

    public boolean matchSymbol(String symbol) {
        if (peek().isSymbol(symbol)) {
            advance();
            return true;
        }
        return false;
    }

    public SqlToken expectWord(String upper) {
        SqlToken tok = peek();
        if (!tok.isWord(upper)) {
            throw error("Expected keyword '" + upper + "'", tok);
        }
        return advance();
    }

    public SqlToken expectSymbol(String symbol) {
        SqlToken tok = peek();
        if (!tok.isSymbol(symbol)) {
            throw error("Expected '" + symbol + "'", tok);
        }
        return advance();
    }

    public SqlToken expectIdentifier(String role) {
        SqlToken tok = peek();
        if (tok.isIdentifierLike()) {
            return advance();
        }
        throw error("Expected " + role + " identifier", tok);
    }

    public SqlParseException error(String message) {
        return error(message, peek());
    }

    public SqlParseException error(String message, SqlToken tok) {
        return new SqlParseException(message, tok.span(), tok.rawText());
    }

    /**
     * Parses a 1..4 part qualified object or column name, handling BigQuery multi-part backticks
     * ({@code `proj.dataset.table`}), T-SQL default schema omission ({@code db..tbl}), Snowflake
     * {@code @stage}, external storage URIs, and Oracle {@code @dblink}.
     */
    public QualifiedName parseQualifiedName() {
        SqlToken first = peek();
        if (first.type() == TokenType.STAGE_REFERENCE) {
            advance();
            return QualifiedName.ofStage(first.value(), first.span());
        }
        if (first.type() == TokenType.STRING_LITERAL && QualifiedName.isExternalUri(first.value())) {
            advance();
            return QualifiedName.ofUri(first.value(), first.span());
        }

        List<NamePart> parts = new ArrayList<>(4);
        boolean anyTemplated = false;
        SqlToken startTok = expectIdentifier("object");
        appendTokenAsParts(parts, startTok);
        if (startTok.templated()) {
            anyTemplated = true;
        }
        SourceSpan endSpan = startTok.span();

        while (true) {
            if (peek().isSymbol("..")) {
                // T-SQL db..table -> empty default schema segment (dbo)
                SqlToken dots = advance();
                String defaultSchema = (vendor == EDbVendor.dbvmssql || vendor == EDbVendor.dbvazuresql) ? "dbo" : "";
                parts.add(new NamePart("", defaultSchema, false, false, dots.span()));
                if (peek().isIdentifierLike()) {
                    SqlToken seg = advance();
                    appendTokenAsParts(parts, seg);
                    anyTemplated |= seg.templated();
                    endSpan = seg.span();
                }
                continue;
            }
            if (peek().isSymbol(".") && (peek(1).isIdentifierLike() || peek(1).isSymbol("*"))) {
                if (peek(1).isSymbol("*")) {
                    break; // Leave .* for StarExpr caller
                }
                advance(); // consume '.'
                SqlToken seg = advance();
                appendTokenAsParts(parts, seg);
                anyTemplated |= seg.templated();
                endSpan = seg.span();
                continue;
            }
            break;
        }

        String dbLink = null;
        if (peek().isSymbol("@") && peek(1).isIdentifierLike()) {
            advance(); // '@'
            StringBuilder linkSb = new StringBuilder();
            SqlToken linkSeg = advance();
            linkSb.append(linkSeg.value());
            endSpan = linkSeg.span();
            while (peek().isSymbol(".") && peek(1).isIdentifierLike()) {
                advance();
                SqlToken nextLink = advance();
                linkSb.append('.').append(nextLink.value());
                endSpan = nextLink.span();
            }
            dbLink = linkSb.toString();
        }

        return new QualifiedName(
                parts,
                dbLink,
                false,
                false,
                anyTemplated,
                SourceSpan.between(startTok.span(), endSpan));
    }

    private void appendTokenAsParts(List<NamePart> parts, SqlToken tok) {
        if (vendor == EDbVendor.dbvbigquery
                && tok.quoted()
                && tok.rawText().startsWith("`")
                && tok.value().contains(".")) {
            parts.addAll(QualifiedName.expandBigQueryBacktickParts(tok.rawText(), tok.value(), tok.span()));
        } else {
            parts.add(new NamePart(tok.rawText(), tok.value(), tok.quoted(), tok.templated(), tok.span()));
        }
    }

    /**
     * Parses SQL data types including parameterized lengths {@code VARCHAR(100 CHAR)},
     * {@code NUMERIC(18, 2)}, {@code TIMESTAMP WITH TIME ZONE}, {@code ARRAY<STRUCT<a INT64, b STRING>>},
     * and {@code emp.col%TYPE} / {@code emp%ROWTYPE}.
     */
    public String parseDataType() {
        if (isAtEnd()) {
            return "UNKNOWN";
        }
        StringBuilder sb = new StringBuilder();
        SqlToken first = advance();
        sb.append(first.rawText());

        // Handle schema.table.col%TYPE or table%ROWTYPE
        while (peek().isSymbol(".") && peek(1).isIdentifierLike()) {
            advance();
            sb.append('.').append(advance().rawText());
        }
        if (peek().isSymbol("%") && peek(1).isAnyWord("TYPE", "ROWTYPE")) {
            advance();
            sb.append('%').append(advance().upperValue());
            return sb.toString();
        }

        // Multi-word ANSI/dialect types: DOUBLE PRECISION, CHARACTER VARYING, TIMESTAMP WITH LOCAL TIME ZONE, UNSIGNED
        while (peek().isAnyWord("PRECISION", "VARYING", "WITHOUT", "WITH", "LOCAL", "TIME", "ZONE", "UNSIGNED", "SIGNED", "ZEROFILL", "LARGE", "OBJECT")) {
            // Do not consume WITH if followed by AS or SELECT (e.g. CTE or WITH DATA)
            if (peek().isWord("WITH") && !peek(1).isAnyWord("TIME", "LOCAL", " TIMEZONE")) {
                break;
            }
            sb.append(' ').append(advance().upperValue());
        }

        // Generic angle-bracket type: ARRAY<...>, STRUCT<...>, MAP<...>, Tuple(...)
        if (peek().isSymbol("<")) {
            int angleDepth = 0;
            do {
                SqlToken t = advance();
                if (t.isSymbol("<")) {
                    angleDepth++;
                } else if (t.isSymbol(">")) {
                    angleDepth--;
                } else if (t.isSymbol(">>")) {
                    angleDepth -= 2;
                }
                sb.append(t.rawText());
                if (t.isSymbol(",")) {
                    sb.append(' ');
                }
            } while (!isAtEnd() && angleDepth > 0);
        }

        // Parenthesized type precision/scale: (255), (18, 4), (100 CHAR)
        if (peek().isSymbol("(")) {
            int parenDepth = 0;
            do {
                SqlToken t = advance();
                if (t.isSymbol("(")) {
                    parenDepth++;
                } else if (t.isSymbol(")")) {
                    parenDepth--;
                }
                sb.append(t.rawText());
            } while (!isAtEnd() && parenDepth > 0);
        }

        // Trailing array brackets: INT[], TEXT[][]
        while (peek().isSymbol("[") && peek(1).isSymbol("]")) {
            advance();
            advance();
            sb.append("[]");
        }
        if (peek().isAnyWord("UNSIGNED", "SIGNED")) {
            sb.append(' ').append(advance().upperValue());
        }
        return sb.toString();
    }

    public String extractText(SourceSpan start, SourceSpan end) {
        return SourceSpan.between(start, end).extractText(sourceSql);
    }

    public void skipParenthesizedGroup() {
        if (!peek().isSymbol("(")) {
            return;
        }
        int depth = 0;
        while (!isAtEnd()) {
            SqlToken t = advance();
            if (t.isSymbol("(")) {
                depth++;
            } else if (t.isSymbol(")")) {
                depth--;
                if (depth <= 0) {
                    break;
                }
            }
        }
    }

    /**
     * Re-lexes an inner string or dollar-quoted routine body and shifts all token spans so their
     * 1-based line and column coordinates map accurately to the outer SQL document.
     */
    public static List<SqlToken> relexEmbeddedBody(SqlToken stringToken, EDbVendor vendor) {
        String raw = stringToken.rawText();
        String body = stringToken.value();
        int prefixLen = 1;
        if (raw.startsWith("$")) {
            int secondDollar = raw.indexOf('$', 1);
            if (secondDollar > 0) {
                prefixLen = secondDollar + 1;
            }
        } else if (raw.startsWith("'''") || raw.startsWith("\"\"\"")) {
            prefixLen = 3;
        } else if (raw.length() >= 2 && Character.isLetter(raw.charAt(0)) && (raw.charAt(1) == '\'' || raw.charAt(1) == '"')) {
            prefixLen = raw.charAt(0) == 'q' || raw.charAt(0) == 'Q' ? 3 : 2;
        }

        long baseLine = stringToken.span().startLine();
        long baseCol = stringToken.span().startCol() + prefixLen;
        int baseOffset = stringToken.span().startOffset() + prefixLen;

        SqlLexer.LexResult innerLex = SqlLexer.tokenize(body, vendor);
        List<SqlToken> shifted = new ArrayList<>(innerLex.tokens().size());
        for (SqlToken t : innerLex.tokens()) {
            SourceSpan s = t.span();
            long sLine = baseLine + (s.startLine() - 1L);
            long sCol = s.startLine() == 1L ? baseCol + (s.startCol() - 1L) : s.startCol();
            long eLine = baseLine + (s.endLine() - 1L);
            long eCol = s.endLine() == 1L ? baseCol + (s.endCol() - 1L) : s.endCol();
            SourceSpan shiftedSpan = new SourceSpan(
                    sLine,
                    sCol,
                    eLine,
                    eCol,
                    baseOffset + s.startOffset(),
                    baseOffset + s.endOffset());
            shifted.add(new SqlToken(t.type(), t.rawText(), t.value(), t.quoted(), t.templated(), shiftedSpan));
        }
        return shifted;
    }
}
