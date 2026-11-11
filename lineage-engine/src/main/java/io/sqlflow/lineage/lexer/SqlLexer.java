package io.sqlflow.lineage.lexer;

import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.contracts.vendor.IdentifierProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Multi-dialect, 1-based coordinate-preserving SQL lexer supporting all 48 {@link EDbVendor}s,
 * procedural dollar-quoted bodies, dbt/Jinja templates, Snowflake stages, semi-structured paths,
 * and dialect-specific quoting & comments.
 */
public final class SqlLexer {

    private static final Pattern DBT_REF_PATTERN = Pattern.compile(
            "^\\{\\{-?\\s*ref\\s*\\(\\s*['\"]([^'\"]+)['\"](?:\\s*,\\s*['\"]([^'\"]+)['\"])?\\s*\\)\\s*-?}}$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern DBT_SOURCE_PATTERN = Pattern.compile(
            "^\\{\\{-?\\s*source\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*,\\s*['\"]([^'\"]+)['\"]\\s*\\)\\s*-?}}$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern DBT_CONFIG_PATTERN = Pattern.compile(
            "^\\{\\{-?\\s*config\\s*\\(.*\\)\\s*-?}}$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public record LexResult(
            List<SqlToken> tokens,
            List<SqlToken> comments,
            List<SqlToken> allTokens
    ) {
    }

    private final String sql;
    private final int len;
    private final EDbVendor vendor;
    private final IdentifierProfile profile;
    private final boolean doubleQuoteIsIdentifier;
    private final boolean backtickQuote;
    private final boolean bracketQuote;
    private final boolean hashComment;
    private final boolean doubleSlashComment;
    private final boolean mssqlColonBind;

    private int pos = 0;
    private long line = 1L;
    private long col = 1L;

    public SqlLexer(String sql, EDbVendor vendor) {
        this(sql, vendor, false);
    }

    public SqlLexer(String sql, EDbVendor vendor, boolean mssqlColonBind) {
        this.sql = sql != null ? sql : "";
        this.len = this.sql.length();
        this.vendor = vendor != null ? vendor : EDbVendor.dbvoracle;
        this.profile = IdentifierProfile.forVendor(this.vendor);
        this.doubleQuoteIsIdentifier = this.profile.quoteDelimiters().contains("\"");
        this.backtickQuote = SqlDialectRules.supportsBacktickQuote(this.vendor)
                || this.profile.quoteDelimiters().contains("`");
        this.bracketQuote = SqlDialectRules.supportsBracketQuote(this.vendor);
        this.hashComment = SqlDialectRules.supportsHashLineComment(this.vendor);
        this.doubleSlashComment = SqlDialectRules.supportsDoubleSlashComment(this.vendor);
        this.mssqlColonBind = mssqlColonBind;
    }

    public static LexResult tokenize(String sql, EDbVendor vendor) {
        return new SqlLexer(sql, vendor, false).lex();
    }

    public static LexResult tokenize(String sql, EDbVendor vendor, boolean mssqlColonBind) {
        return new SqlLexer(sql, vendor, mssqlColonBind).lex();
    }

    public LexResult lex() {
        List<SqlToken> codeTokens = new ArrayList<>();
        List<SqlToken> comments = new ArrayList<>();
        List<SqlToken> allTokens = new ArrayList<>();

        while (pos < len) {
            char ch = sql.charAt(pos);

            // 1. Whitespace
            if (Character.isWhitespace(ch)) {
                consumeWhitespace();
                continue;
            }

            long startLine = line;
            long startCol = col;
            int startOffset = pos;

            // 2. Line comments: --
            if (ch == '-' && peek(1) == '-') {
                SqlToken tok = readLineComment(startLine, startCol, startOffset);
                comments.add(tok);
                allTokens.add(tok);
                continue;
            }

            // 3. Double-slash line comments: //
            if (ch == '/' && peek(1) == '/' && doubleSlashComment) {
                SqlToken tok = readLineComment(startLine, startCol, startOffset);
                comments.add(tok);
                allTokens.add(tok);
                continue;
            }

            // 4. Block comments: /* ... */
            if (ch == '/' && peek(1) == '*') {
                SqlToken tok = readBlockComment(startLine, startCol, startOffset);
                comments.add(tok);
                allTokens.add(tok);
                continue;
            }

            // 5. Power Query quoted identifier: #"..."
            if (ch == '#' && peek(1) == '"') {
                SqlToken tok = readPowerQueryQuotedIdentifier(startLine, startCol, startOffset);
                codeTokens.add(tok);
                allTokens.add(tok);
                continue;
            }

            // 6. Hash line comments vs #temp_table vs #> operator
            if (ch == '#') {
                char next = peek(1);
                if (next == '>' || next == '=') {
                    SqlToken tok = readOperator(startLine, startCol, startOffset);
                    codeTokens.add(tok);
                    allTokens.add(tok);
                    continue;
                }
                if (hashComment && (next == ' ' || next == '\t' || next == '!' || next == '\r' || next == '\n'
                        || !isIdentifierPart(next))) {
                    SqlToken tok = readLineComment(startLine, startCol, startOffset);
                    comments.add(tok);
                    allTokens.add(tok);
                    continue;
                }
                if (hashComment && vendor == EDbVendor.dbvmysql) {
                    SqlToken tok = readLineComment(startLine, startCol, startOffset);
                    comments.add(tok);
                    allTokens.add(tok);
                    continue;
                }
                if (isIdentifierStart(next) || next == '#') {
                    SqlToken tok = readTempTableHashIdentifier(startLine, startCol, startOffset);
                    codeTokens.add(tok);
                    allTokens.add(tok);
                    continue;
                }
            }

            // 7. Jinja / dbt templates: {{ ... }}, {% ... %}, {# ... #}
            if (ch == '{' && (peek(1) == '{' || peek(1) == '%' || peek(1) == '#')) {
                char second = peek(1);
                if (second == '#' || second == '%') {
                    char closeChar = second == '#' ? '#' : '%';
                    SqlToken tok = readJinjaBlock(closeChar, startLine, startCol, startOffset);
                    comments.add(tok);
                    allTokens.add(tok);
                    continue;
                }
                List<SqlToken> expanded = readJinjaExpression(startLine, startCol, startOffset);
                for (SqlToken t : expanded) {
                    if (t.isComment()) {
                        comments.add(t);
                    } else {
                        codeTokens.add(t);
                    }
                    allTokens.add(t);
                }
                continue;
            }

            // 8. Shell/Hive/Spark template variable: ${var}
            if (ch == '$' && peek(1) == '{') {
                SqlToken tok = readBraceTemplateVar(startLine, startCol, startOffset);
                codeTokens.add(tok);
                allTokens.add(tok);
                continue;
            }

            // 9. Dollar-quoted strings: $$...$$ or $tag$...$tag$
            if (ch == '$') {
                String dollarTag = matchDollarQuoteTag(pos);
                if (dollarTag != null) {
                    SqlToken tok = readDollarQuotedString(dollarTag, startLine, startCol, startOffset);
                    codeTokens.add(tok);
                    allTokens.add(tok);
                    continue;
                }
                if (Character.isDigit(peek(1)) || isIdentifierStart(peek(1))) {
                    SqlToken tok = readDollarBindOrIdentifier(startLine, startCol, startOffset);
                    codeTokens.add(tok);
                    allTokens.add(tok);
                    continue;
                }
            }

            // 10. Oracle alternative quoting q'[...]' / Q'{...}' or prefixed string literals N'...', E'...', X'...', B'...', r'...'
            if ((ch == 'q' || ch == 'Q') && peek(1) == '\'' && pos + 2 < len) {
                SqlToken tok = readOracleQString(startLine, startCol, startOffset);
                codeTokens.add(tok);
                allTokens.add(tok);
                continue;
            }
            if ((ch == 'N' || ch == 'n' || ch == 'E' || ch == 'e' || ch == 'X' || ch == 'x'
                    || ch == 'B' || ch == 'b' || ch == 'R' || ch == 'r')
                    && (peek(1) == '\'' || ((ch == 'R' || ch == 'r') && peek(1) == '"' && !doubleQuoteIsIdentifier))) {
                char quoteCh = peek(1);
                advance(); // prefix char
                SqlToken tok = readQuotedString(quoteCh, startLine, startCol, startOffset, String.valueOf(ch));
                codeTokens.add(tok);
                allTokens.add(tok);
                continue;
            }

            // 11. Single-quoted string (or DAX quoted table identifier)
            if (ch == '\'') {
                if (vendor == EDbVendor.dbvdax) {
                    SqlToken tok = readDelimitedIdentifier('\'', '\'', startLine, startCol, startOffset);
                    codeTokens.add(tok);
                    allTokens.add(tok);
                } else {
                    SqlToken tok = readQuotedString('\'', startLine, startCol, startOffset, "");
                    codeTokens.add(tok);
                    allTokens.add(tok);
                }
                continue;
            }

            // 12. Double-quoted identifier or string literal
            if (ch == '"') {
                if (peek(1) == '"' && peek(2) == '"' && !doubleQuoteIsIdentifier) {
                    SqlToken tok = readQuotedString('"', startLine, startCol, startOffset, "");
                    codeTokens.add(tok);
                    allTokens.add(tok);
                } else if (doubleQuoteIsIdentifier) {
                    SqlToken tok = readDelimitedIdentifier('"', '"', startLine, startCol, startOffset);
                    codeTokens.add(tok);
                    allTokens.add(tok);
                } else {
                    SqlToken tok = readQuotedString('"', startLine, startCol, startOffset, "");
                    codeTokens.add(tok);
                    allTokens.add(tok);
                }
                continue;
            }

            // 13. Backtick-quoted identifier (including BigQuery `proj.ds.tbl`)
            if (ch == '`') {
                SqlToken tok = readDelimitedIdentifier('`', '`', startLine, startCol, startOffset);
                codeTokens.add(tok);
                allTokens.add(tok);
                continue;
            }

            // 14. Bracket-quoted identifier [col] vs array subscript arr[0]
            if (ch == '[' && bracketQuote && !isPrecededBySubscriptableToken(codeTokens)) {
                SqlToken tok = readDelimitedIdentifier('[', ']', startLine, startCol, startOffset);
                codeTokens.add(tok);
                allTokens.add(tok);
                continue;
            }

            // 15. Oracle outer join (+)
            if (ch == '(' && peek(1) == '+' && peek(2) == ')') {
                advance();
                advance();
                advance();
                SourceSpan span = new SourceSpan(startLine, startCol, line, col, startOffset, pos);
                SqlToken tok = new SqlToken(TokenType.OPERATOR, "(+)", "(+)", false, false, span);
                codeTokens.add(tok);
                allTokens.add(tok);
                continue;
            }

            // 16. Snowflake @stage vs @variable vs @dblink
            if (ch == '@') {
                SqlToken prev = codeTokens.isEmpty() ? null : codeTokens.get(codeTokens.size() - 1);
                boolean adjacentAfterIdent = prev != null
                        && prev.endOffset() == startOffset
                        && (prev.type() == TokenType.IDENTIFIER || prev.type() == TokenType.QUOTED_IDENTIFIER);
                if (adjacentAfterIdent) {
                    // e.g. emp@remote_db dblink operator
                    advance();
                    SourceSpan span = new SourceSpan(startLine, startCol, line, col, startOffset, pos);
                    SqlToken tok = new SqlToken(TokenType.OPERATOR, "@", "@", false, false, span);
                    codeTokens.add(tok);
                    allTokens.add(tok);
                    continue;
                }
                if (vendor == EDbVendor.dbvsnowflake && (peek(1) == '~' || peek(1) == '%' || isIdentifierStart(peek(1)))) {
                    SqlToken tok = readSnowflakeStage(startLine, startCol, startOffset);
                    codeTokens.add(tok);
                    allTokens.add(tok);
                    continue;
                }
                if (peek(1) == '@' || isIdentifierStart(peek(1)) || Character.isDigit(peek(1))) {
                    SqlToken tok = readAtVariable(startLine, startCol, startOffset);
                    codeTokens.add(tok);
                    allTokens.add(tok);
                    continue;
                }
            }

            // 17. Colon ::, :=, semi-structured path col:field, or :bind_variable
            if (ch == ':') {
                if (peek(1) == ':' || peek(1) == '=') {
                    advance();
                    advance();
                    String raw = sql.substring(startOffset, pos);
                    SourceSpan span = new SourceSpan(startLine, startCol, line, col, startOffset, pos);
                    SqlToken tok = new SqlToken(TokenType.OPERATOR, raw, raw, false, false, span);
                    codeTokens.add(tok);
                    allTokens.add(tok);
                    continue;
                }
                SqlToken prev = codeTokens.isEmpty() ? null : codeTokens.get(codeTokens.size() - 1);
                boolean isPathAccess = (vendor == EDbVendor.dbvsnowflake
                        || vendor == EDbVendor.dbvdatabricks
                        || vendor == EDbVendor.dbvsparksql
                        || vendor == EDbVendor.dbvcouchbase)
                        && prev != null
                        && prev.endOffset() == startOffset
                        && (prev.isIdentifierLike() || prev.isSymbol("]") || prev.isSymbol(")"));
                if (!isPathAccess
                        && (vendor != EDbVendor.dbvmssql || mssqlColonBind)
                        && (isIdentifierStart(peek(1)) || Character.isDigit(peek(1)))) {
                    SqlToken tok = readColonBindVariable(startLine, startCol, startOffset);
                    codeTokens.add(tok);
                    allTokens.add(tok);
                    continue;
                }
            }

            // 18. Numeric literals (including leading dot .5 when not preceded adjacent to identifier)
            if (Character.isDigit(ch)
                    || (ch == '.' && Character.isDigit(peek(1)) && !isAdjacentAfterIdentifier(codeTokens, startOffset))) {
                SqlToken tok = readNumberLiteral(startLine, startCol, startOffset);
                codeTokens.add(tok);
                allTokens.add(tok);
                continue;
            }

            // 19. Unquoted Identifier or Keyword
            if (isIdentifierStart(ch)) {
                SqlToken tok = readWord(startLine, startCol, startOffset);
                codeTokens.add(tok);
                allTokens.add(tok);
                continue;
            }

            // 20. Punctuation & Operators
            SqlToken tok = readOperatorOrPunctuation(startLine, startCol, startOffset);
            codeTokens.add(tok);
            allTokens.add(tok);
        }

        SourceSpan eofSpan = new SourceSpan(line, col, line, col, pos, pos);
        SqlToken eof = new SqlToken(TokenType.EOF, "", "", false, false, eofSpan);
        codeTokens.add(eof);
        return new LexResult(codeTokens, comments, allTokens);
    }

    private boolean isPrecededBySubscriptableToken(List<SqlToken> codeTokens) {
        if (codeTokens.isEmpty()) {
            return false;
        }
        if (vendor == EDbVendor.dbvmssql
                || vendor == EDbVendor.dbvazuresql
                || vendor == EDbVendor.dbvsybase
                || vendor == EDbVendor.dbvsybasease
                || vendor == EDbVendor.dbvsybaseiq
                || vendor == EDbVendor.dbvaccess
                || vendor == EDbVendor.dbvmdx) {
            return false;
        }
        SqlToken prev = codeTokens.get(codeTokens.size() - 1);
        return prev.endOffset() == pos
                && (prev.type() == TokenType.IDENTIFIER
                || prev.type() == TokenType.QUOTED_IDENTIFIER
                || prev.isSymbol("]")
                || prev.isSymbol(")"));
    }

    private boolean isAdjacentAfterIdentifier(List<SqlToken> codeTokens, int currentOffset) {
        if (codeTokens.isEmpty()) {
            return false;
        }
        SqlToken prev = codeTokens.get(codeTokens.size() - 1);
        return prev.endOffset() == currentOffset && prev.isIdentifierLike();
    }

    private void consumeWhitespace() {
        while (pos < len && Character.isWhitespace(sql.charAt(pos))) {
            advance();
        }
    }

    private SqlToken readLineComment(long startLine, long startCol, int startOffset) {
        while (pos < len && sql.charAt(pos) != '\n' && sql.charAt(pos) != '\r') {
            advance();
        }
        String raw = sql.substring(startOffset, pos);
        SourceSpan span = new SourceSpan(startLine, startCol, line, col, startOffset, pos);
        return new SqlToken(TokenType.LINE_COMMENT, raw, raw, false, false, span);
    }

    private SqlToken readBlockComment(long startLine, long startCol, int startOffset) {
        advance(); // /
        advance(); // *
        int depth = 1;
        while (pos < len && depth > 0) {
            if (sql.charAt(pos) == '/' && peek(1) == '*') {
                advance();
                advance();
                depth++;
            } else if (sql.charAt(pos) == '*' && peek(1) == '/') {
                advance();
                advance();
                depth--;
            } else {
                advance();
            }
        }
        String raw = sql.substring(startOffset, pos);
        SourceSpan span = new SourceSpan(startLine, startCol, line, col, startOffset, pos);
        return new SqlToken(TokenType.BLOCK_COMMENT, raw, raw, false, false, span);
    }

    private SqlToken readJinjaBlock(char closeChar, long startLine, long startCol, int startOffset) {
        advance(); // {
        advance(); // % or #
        while (pos < len) {
            if (sql.charAt(pos) == closeChar && peek(1) == '}') {
                advance();
                advance();
                break;
            }
            advance();
        }
        String raw = sql.substring(startOffset, pos);
        SourceSpan span = new SourceSpan(startLine, startCol, line, col, startOffset, pos);
        return new SqlToken(TokenType.BLOCK_COMMENT, raw, raw, false, true, span);
    }

    private List<SqlToken> readJinjaExpression(long startLine, long startCol, int startOffset) {
        advance(); // {
        advance(); // {
        while (pos < len) {
            if (sql.charAt(pos) == '}' && peek(1) == '}') {
                advance();
                advance();
                break;
            }
            advance();
        }
        String raw = sql.substring(startOffset, pos);
        SourceSpan span = new SourceSpan(startLine, startCol, line, col, startOffset, pos);

        if (DBT_CONFIG_PATTERN.matcher(raw).matches()) {
            return List.of(new SqlToken(TokenType.BLOCK_COMMENT, raw, raw, false, true, span));
        }
        Matcher refMatcher = DBT_REF_PATTERN.matcher(raw);
        if (refMatcher.matches()) {
            String first = refMatcher.group(1);
            String second = refMatcher.group(2);
            if (second != null && !second.isBlank()) {
                return List.of(
                        new SqlToken(TokenType.TEMPLATE_TOKEN, raw, first, false, true, span),
                        new SqlToken(TokenType.PUNCTUATION, ".", ".", false, true, span),
                        new SqlToken(TokenType.TEMPLATE_TOKEN, raw, second, false, true, span));
            }
            return List.of(new SqlToken(TokenType.TEMPLATE_TOKEN, raw, first, false, true, span));
        }
        Matcher srcMatcher = DBT_SOURCE_PATTERN.matcher(raw);
        if (srcMatcher.matches()) {
            String srcSchema = srcMatcher.group(1);
            String srcTable = srcMatcher.group(2);
            return List.of(
                    new SqlToken(TokenType.TEMPLATE_TOKEN, raw, srcSchema, false, true, span),
                    new SqlToken(TokenType.PUNCTUATION, ".", ".", false, true, span),
                    new SqlToken(TokenType.TEMPLATE_TOKEN, raw, srcTable, false, true, span));
        }
        String inner = raw.length() >= 4 ? raw.substring(2, raw.length() - 2).trim() : raw;
        if (inner.startsWith("-")) {
            inner = inner.substring(1).trim();
        }
        if (inner.endsWith("-")) {
            inner = inner.substring(0, inner.length() - 1).trim();
        }
        String cleaned = inner.replaceAll("[^A-Za-z0-9_.]", "_");
        if (cleaned.isBlank()) {
            cleaned = "jinja_expr";
        }
        return List.of(new SqlToken(TokenType.TEMPLATE_TOKEN, raw, cleaned, false, true, span));
    }

    private SqlToken readBraceTemplateVar(long startLine, long startCol, int startOffset) {
        advance(); // $
        advance(); // {
        int innerStart = pos;
        while (pos < len && sql.charAt(pos) != '}') {
            advance();
        }
        String inner = sql.substring(innerStart, pos).trim();
        if (pos < len && sql.charAt(pos) == '}') {
            advance();
        }
        String raw = sql.substring(startOffset, pos);
        SourceSpan span = new SourceSpan(startLine, startCol, line, col, startOffset, pos);
        String val = inner.isEmpty() ? raw : inner;
        return new SqlToken(TokenType.TEMPLATE_TOKEN, raw, val, false, true, span);
    }

    private String matchDollarQuoteTag(int index) {
        if (index >= len || sql.charAt(index) != '$') {
            return null;
        }
        int cursor = index + 1;
        if (cursor < len && sql.charAt(cursor) == '$') {
            return "$$";
        }
        if (cursor < len && isIdentifierStart(sql.charAt(cursor))) {
            while (cursor < len && isIdentifierPart(sql.charAt(cursor))) {
                cursor++;
            }
            if (cursor < len && sql.charAt(cursor) == '$') {
                return sql.substring(index, cursor + 1);
            }
        }
        return null;
    }

    private SqlToken readDollarQuotedString(String tag, long startLine, long startCol, int startOffset) {
        for (int i = 0; i < tag.length(); i++) {
            advance();
        }
        int bodyStart = pos;
        int bodyEnd = len;
        while (pos < len) {
            if (sql.startsWith(tag, pos)) {
                bodyEnd = pos;
                for (int i = 0; i < tag.length(); i++) {
                    advance();
                }
                break;
            }
            advance();
        }
        String raw = sql.substring(startOffset, pos);
        String body = sql.substring(bodyStart, Math.min(bodyEnd, pos));
        SourceSpan span = new SourceSpan(startLine, startCol, line, col, startOffset, pos);
        return new SqlToken(TokenType.STRING_LITERAL, raw, body, false, false, span);
    }

    private SqlToken readDollarBindOrIdentifier(long startLine, long startCol, int startOffset) {
        advance(); // $
        while (pos < len && isIdentifierPart(sql.charAt(pos))) {
            advance();
        }
        String raw = sql.substring(startOffset, pos);
        SourceSpan span = new SourceSpan(startLine, startCol, line, col, startOffset, pos);
        return new SqlToken(TokenType.BIND_VARIABLE, raw, raw, false, false, span);
    }

    private SqlToken readOracleQString(long startLine, long startCol, int startOffset) {
        advance(); // q or Q
        advance(); // '
        char openDelim = pos < len ? sql.charAt(pos) : '\'';
        char closeDelim = switch (openDelim) {
            case '[' -> ']';
            case '{' -> '}';
            case '(' -> ')';
            case '<' -> '>';
            default -> openDelim;
        };
        if (pos < len) {
            advance();
        }
        int bodyStart = pos;
        int bodyEnd = len;
        while (pos < len) {
            if (sql.charAt(pos) == closeDelim && peek(1) == '\'') {
                bodyEnd = pos;
                advance();
                advance();
                break;
            }
            advance();
        }
        String raw = sql.substring(startOffset, pos);
        String body = sql.substring(bodyStart, Math.min(bodyEnd, pos));
        SourceSpan span = new SourceSpan(startLine, startCol, line, col, startOffset, pos);
        return new SqlToken(TokenType.STRING_LITERAL, raw, body, false, false, span);
    }

    private SqlToken readQuotedString(char quote, long startLine, long startCol, int startOffset, String prefix) {
        boolean triple = peek(0) == quote && peek(1) == quote && peek(2) == quote;
        if (triple) {
            advance();
            advance();
            advance();
            StringBuilder sb = new StringBuilder();
            while (pos < len) {
                if (sql.charAt(pos) == quote && peek(1) == quote && peek(2) == quote) {
                    advance();
                    advance();
                    advance();
                    break;
                }
                if (sql.charAt(pos) == '\\' && pos + 1 < len) {
                    advance();
                    sb.append(sql.charAt(pos));
                    advance();
                } else {
                    sb.append(sql.charAt(pos));
                    advance();
                }
            }
            String raw = sql.substring(startOffset, pos);
            SourceSpan span = new SourceSpan(startLine, startCol, line, col, startOffset, pos);
            return new SqlToken(TokenType.STRING_LITERAL, raw, sb.toString(), false, false, span);
        }

        advance(); // opening quote
        StringBuilder sb = new StringBuilder();
        boolean backslashEscape = vendor == EDbVendor.dbvmysql
                || vendor == EDbVendor.dbvhive
                || vendor == EDbVendor.dbvsparksql
                || vendor == EDbVendor.dbvdatabricks
                || vendor == EDbVendor.dbvbigquery
                || vendor == EDbVendor.dbvsnowflake
                || vendor == EDbVendor.dbvclickhouse
                || prefix.equalsIgnoreCase("E");
        while (pos < len) {
            char c = sql.charAt(pos);
            if (backslashEscape && c == '\\' && pos + 1 < len && !prefix.equalsIgnoreCase("R")) {
                advance();
                char esc = sql.charAt(pos);
                sb.append(switch (esc) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> esc;
                });
                advance();
                continue;
            }
            if (c == quote) {
                if (peek(1) == quote) {
                    sb.append(quote);
                    advance();
                    advance();
                    continue;
                }
                advance();
                break;
            }
            sb.append(c);
            advance();
        }
        String raw = sql.substring(startOffset, pos);
        SourceSpan span = new SourceSpan(startLine, startCol, line, col, startOffset, pos);
        return new SqlToken(TokenType.STRING_LITERAL, raw, sb.toString(), false, false, span);
    }

    private SqlToken readPowerQueryQuotedIdentifier(long startLine, long startCol, int startOffset) {
        advance(); // #
        advance(); // "
        StringBuilder sb = new StringBuilder();
        while (pos < len) {
            char c = sql.charAt(pos);
            if (c == '"') {
                if (peek(1) == '"') {
                    sb.append('"');
                    advance();
                    advance();
                    continue;
                }
                advance();
                break;
            }
            sb.append(c);
            advance();
        }
        String raw = sql.substring(startOffset, pos);
        SourceSpan span = new SourceSpan(startLine, startCol, line, col, startOffset, pos);
        return new SqlToken(TokenType.QUOTED_IDENTIFIER, raw, sb.toString(), true, false, span);
    }

    private SqlToken readDelimitedIdentifier(char open, char close, long startLine, long startCol, int startOffset) {
        advance(); // open
        StringBuilder sb = new StringBuilder();
        while (pos < len) {
            char c = sql.charAt(pos);
            if (c == close) {
                if (peek(1) == close) {
                    sb.append(close);
                    advance();
                    advance();
                    continue;
                }
                advance();
                break;
            }
            sb.append(c);
            advance();
        }
        String raw = sql.substring(startOffset, pos);
        SourceSpan span = new SourceSpan(startLine, startCol, line, col, startOffset, pos);
        return new SqlToken(TokenType.QUOTED_IDENTIFIER, raw, sb.toString(), true, false, span);
    }

    private SqlToken readTempTableHashIdentifier(long startLine, long startCol, int startOffset) {
        advance(); // #
        if (pos < len && sql.charAt(pos) == '#') {
            advance(); // ##
        }
        while (pos < len && isIdentifierPart(sql.charAt(pos))) {
            advance();
        }
        String raw = sql.substring(startOffset, pos);
        SourceSpan span = new SourceSpan(startLine, startCol, line, col, startOffset, pos);
        return new SqlToken(TokenType.IDENTIFIER, raw, raw, false, false, span);
    }

    private SqlToken readSnowflakeStage(long startLine, long startCol, int startOffset) {
        advance(); // @
        if (pos < len && (sql.charAt(pos) == '~' || sql.charAt(pos) == '%')) {
            advance();
        }
        while (pos < len) {
            char c = sql.charAt(pos);
            if (isIdentifierPart(c) || c == '.' || c == '/' || c == '-' || c == '%') {
                advance();
            } else {
                break;
            }
        }
        String raw = sql.substring(startOffset, pos);
        SourceSpan span = new SourceSpan(startLine, startCol, line, col, startOffset, pos);
        return new SqlToken(TokenType.STAGE_REFERENCE, raw, raw, false, false, span);
    }

    private SqlToken readAtVariable(long startLine, long startCol, int startOffset) {
        advance(); // @
        if (pos < len && sql.charAt(pos) == '@') {
            advance(); // @@
        }
        while (pos < len && isIdentifierPart(sql.charAt(pos))) {
            advance();
        }
        String raw = sql.substring(startOffset, pos);
        SourceSpan span = new SourceSpan(startLine, startCol, line, col, startOffset, pos);
        return new SqlToken(TokenType.BIND_VARIABLE, raw, raw, false, false, span);
    }

    private SqlToken readColonBindVariable(long startLine, long startCol, int startOffset) {
        advance(); // :
        while (pos < len && isIdentifierPart(sql.charAt(pos))) {
            advance();
        }
        String raw = sql.substring(startOffset, pos);
        SourceSpan span = new SourceSpan(startLine, startCol, line, col, startOffset, pos);
        return new SqlToken(TokenType.BIND_VARIABLE, raw, raw, false, false, span);
    }

    private SqlToken readNumberLiteral(long startLine, long startCol, int startOffset) {
        if (sql.charAt(pos) == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
            advance();
            advance();
            while (pos < len && isHexDigit(sql.charAt(pos))) {
                advance();
            }
        } else {
            while (pos < len && Character.isDigit(sql.charAt(pos))) {
                advance();
            }
            if (pos < len && sql.charAt(pos) == '.' && peek(1) != '.') {
                advance();
                while (pos < len && Character.isDigit(sql.charAt(pos))) {
                    advance();
                }
            }
            if (pos < len && (sql.charAt(pos) == 'e' || sql.charAt(pos) == 'E')) {
                char next = peek(1);
                if (Character.isDigit(next) || ((next == '+' || next == '-') && Character.isDigit(peek(2)))) {
                    advance();
                    if (sql.charAt(pos) == '+' || sql.charAt(pos) == '-') {
                        advance();
                    }
                    while (pos < len && Character.isDigit(sql.charAt(pos))) {
                        advance();
                    }
                }
            }
        }
        String raw = sql.substring(startOffset, pos);
        SourceSpan span = new SourceSpan(startLine, startCol, line, col, startOffset, pos);
        return new SqlToken(TokenType.NUMBER_LITERAL, raw, raw, false, false, span);
    }

    private SqlToken readWord(long startLine, long startCol, int startOffset) {
        while (pos < len) {
            char c = sql.charAt(pos);
            if (isIdentifierPart(c)
                    || (c == '-' && vendor == EDbVendor.dbvbigquery && isIdentifierPart(peek(1)) && peek(-1) != ' ')) {
                advance();
            } else {
                break;
            }
        }
        String raw = sql.substring(startOffset, pos);
        String upper = raw.toUpperCase(Locale.ROOT);
        TokenType type = SqlDialectRules.isAnyKeyword(upper, vendor)
                ? TokenType.KEYWORD
                : TokenType.IDENTIFIER;
        SourceSpan span = new SourceSpan(startLine, startCol, line, col, startOffset, pos);
        return new SqlToken(type, raw, raw, false, false, span);
    }

    private SqlToken readOperator(long startLine, long startCol, int startOffset) {
        return readOperatorOrPunctuation(startLine, startCol, startOffset);
    }

    private SqlToken readOperatorOrPunctuation(long startLine, long startCol, int startOffset) {
        char c = sql.charAt(pos);
        char n1 = peek(1);
        char n2 = peek(2);

        if (c == '<' && n1 == '=' && n2 == '>') {
            advance();
            advance();
            advance();
            return makeOp(startLine, startCol, startOffset);
        }
        if ((c == '-' && n1 == '>' && n2 == '>') || (c == '#' && n1 == '>' && n2 == '>')) {
            advance();
            advance();
            advance();
            return makeOp(startLine, startCol, startOffset);
        }
        if ((c == '<' && (n1 == '=' || n1 == '>'))
                || (c == '>' && n1 == '=')
                || (c == '!' && (n1 == '=' || n1 == '~' || n1 == '<' || n1 == '>'))
                || (c == '=' && (n1 == '>' || n1 == '~' || n1 == '='))
                || (c == '|' && n1 == '|')
                || (c == '&' && n1 == '&')
                || (c == '-' && n1 == '>')
                || (c == '#' && n1 == '>')
                || (c == ':' && (n1 == ':' || n1 == '='))
                || (c == '.' && n1 == '.')
                || (c == '<' && n1 == '<')
                || (c == '>' && n1 == '>')) {
            advance();
            advance();
            return makeOp(startLine, startCol, startOffset);
        }

        advance();
        String raw = sql.substring(startOffset, pos);
        SourceSpan span = new SourceSpan(startLine, startCol, line, col, startOffset, pos);
        TokenType type = switch (c) {
            case '(', ')', ',', ';', '.', '[', ']', '{', '}' -> TokenType.PUNCTUATION;
            case '?' -> TokenType.BIND_VARIABLE;
            default -> TokenType.OPERATOR;
        };
        return new SqlToken(type, raw, raw, false, false, span);
    }

    private SqlToken makeOp(long startLine, long startCol, int startOffset) {
        String raw = sql.substring(startOffset, pos);
        SourceSpan span = new SourceSpan(startLine, startCol, line, col, startOffset, pos);
        return new SqlToken(TokenType.OPERATOR, raw, raw, false, false, span);
    }

    private void advance() {
        if (pos >= len) {
            return;
        }
        char c = sql.charAt(pos);
        if (c == '\r') {
            if (pos + 1 < len && sql.charAt(pos + 1) == '\n') {
                pos += 2;
            } else {
                pos += 1;
            }
            line++;
            col = 1L;
        } else if (c == '\n') {
            pos++;
            line++;
            col = 1L;
        } else {
            pos++;
            col++;
        }
    }

    private char peek(int delta) {
        int idx = pos + delta;
        return (idx >= 0 && idx < len) ? sql.charAt(idx) : '\0';
    }

    private static boolean isIdentifierStart(char c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || c == '_'
                || (c > 127 && Character.isLetter(c));
    }

    private static boolean isIdentifierPart(char c) {
        return isIdentifierStart(c)
                || (c >= '0' && c <= '9')
                || c == '$'
                || (c > 127 && Character.isLetterOrDigit(c));
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f')
                || (c >= 'A' && c <= 'F');
    }
}
