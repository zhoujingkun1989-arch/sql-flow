package io.sqlflow.lineage.demo;

import io.sqlflow.contracts.lineage.Coordinate;
import io.sqlflow.contracts.lineage.ErrorItem;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.AstSqlRequest;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.KeywordCheckResult;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.SyntaxCheckResult;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.TokenItem;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.TokenizeResult;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.lineage.ast.QueryAst.QueryExpr;
import io.sqlflow.lineage.ast.QueryAst.SelectItem;
import io.sqlflow.lineage.ast.QueryAst.SelectQuery;
import io.sqlflow.lineage.ast.QueryAst.SetOpQuery;
import io.sqlflow.lineage.ast.SqlStmt;
import io.sqlflow.lineage.lexer.SqlDialectRules;
import io.sqlflow.lineage.lexer.SqlLexer;
import io.sqlflow.lineage.lexer.SqlToken;
import io.sqlflow.lineage.lexer.TokenType;
import io.sqlflow.lineage.parser.DdlParser;
import io.sqlflow.lineage.parser.ExpressionParser;
import io.sqlflow.lineage.parser.ParserCursor;
import io.sqlflow.lineage.parser.ProceduralAndSpecialParser;
import io.sqlflow.lineage.parser.QueryParser;
import io.sqlflow.lineage.parser.SqlParseException;
import io.sqlflow.lineage.parser.StatementParser;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Multi-dialect lexical tokenizer, 1-based syntax validator, and per-dialect reserved keyword
 * checker ({@code 02 §4.9 #5, #11}, {@code OBL-063}).
 */
public final class SyntaxAndKeywordService {

    public static final String ENGINE_VERSION = "2.8.5.8";

    private static final Set<EDbVendor> TRAILING_COMMA_SELECT_VENDORS = Set.of(
            EDbVendor.dbvbigquery,
            EDbVendor.dbvduckdb,
            EDbVendor.dbvsnowflake,
            EDbVendor.dbvsparksql,
            EDbVendor.dbvdatabricks,
            EDbVendor.dbvclickhouse);

    public SyntaxCheckResult syntaxCheck(AstSqlRequest request) {
        long startNanos = System.nanoTime();
        String sql = request != null ? request.getSql() : null;
        EDbVendor vendor = request != null && request.getDbvendor() != null
                ? request.getDbvendor()
                : EDbVendor.dbvoracle;

        List<ErrorItem> errors = collectSyntaxErrors(sql, vendor);
        double elapsedSec = Math.max(0.001, (System.nanoTime() - startNanos) / 1_000_000_000.0);

        SyntaxCheckResult res = new SyntaxCheckResult();
        res.setGspVersion(ENGINE_VERSION);
        res.setDbVendor(vendor.name());
        res.setUsedTime(String.format(Locale.ROOT, "%.3fs", elapsedSec));
        if (errors.isEmpty()) {
            res.setValid(Boolean.TRUE);
            res.setErrorInfos(List.of());
            res.setErrors(null);
        } else {
            res.setValid(Boolean.FALSE);
            res.setErrors(errors);
            res.setErrorInfos(errors.stream().map(ErrorItem::getErrorMessage).toList());
        }
        return res;
    }

    public TokenizeResult tokenize(AstSqlRequest request) {
        String sql = request != null && request.getSql() != null ? request.getSql() : "";
        EDbVendor vendor = request != null && request.getDbvendor() != null
                ? request.getDbvendor()
                : EDbVendor.dbvoracle;
        SqlLexer.LexResult lexed = SqlLexer.tokenize(sql, vendor);
        List<TokenItem> out = new ArrayList<>(lexed.allTokens().size());
        for (SqlToken tok : lexed.allTokens()) {
            if (!tok.isEof()) {
                out.add(tok.toDemoTokenItem());
            }
        }
        return new TokenizeResult(out);
    }

    public KeywordCheckResult keywordCheck(AstSqlRequest request) {
        String raw = request != null && request.getSql() != null ? request.getSql() : "";
        EDbVendor vendor = request != null && request.getDbvendor() != null
                ? request.getDbvendor()
                : EDbVendor.dbvoracle;

        SqlLexer.LexResult lexed = SqlLexer.tokenize(raw, vendor);
        List<SqlToken> tokens = lexed.tokens();
        List<TokenItem> bad = new ArrayList<>();

        if (!looksLikeFullSqlStatement(tokens)) {
            for (SqlToken tok : tokens) {
                if (tok.isEof() || tok.quoted() || tok.isSymbol(",") || tok.isSymbol(";")) {
                    continue;
                }
                if (SqlDialectRules.isReservedKeyword(tok.upperValue(), vendor)) {
                    bad.add(tok.toDemoTokenItem());
                }
            }
        } else {
            collectReservedUsedAsIdentifiersInSql(tokens, vendor, bad);
        }

        KeywordCheckResult res = new KeywordCheckResult();
        res.setValid(bad.isEmpty());
        res.setReservedKeywordsUsedAsIdentifiers(bad);
        return res;
    }

    public List<ErrorItem> collectSyntaxErrors(String sql, EDbVendor vendor) {
        List<ErrorItem> errors = new ArrayList<>();
        if (sql == null || sql.isBlank()) {
            errors.add(syntaxError(1L, 1L, "EOF", "SQL text is empty"));
            return errors;
        }

        SqlLexer.LexResult lexed = SqlLexer.tokenize(sql, vendor);
        checkLexicalAndDelimiterIntegrity(lexed.allTokens(), vendor, errors);
        if (!errors.isEmpty()) {
            return errors;
        }

        List<SqlToken> codeTokens = lexed.tokens();
        boolean hasExecutableTokens = codeTokens.stream().anyMatch(t -> !t.isEof() && !t.isSymbol(";"));
        if (!hasExecutableTokens) {
            errors.add(syntaxError(1L, 1L, "EOF", "No executable SQL statement found"));
            return errors;
        }

        checkStructuralTokenPatterns(codeTokens, vendor, errors);
        if (!errors.isEmpty()) {
            return errors;
        }

        StatementParser.ParsedScript parsed = parseScriptPackageAware(sql, vendor);
        for (SqlStmt stmt : parsed.statements()) {
            collectAstErrors(stmt, vendor, errors);
        }
        return errors;
    }

    public static StatementParser.ParsedScript parseScriptPackageAware(String sql, EDbVendor vendor) {
        if (sql == null || sql.isBlank()) {
            return new StatementParser.ParsedScript(List.of(), List.of(), List.of());
        }
        EDbVendor resolved = vendor != null ? vendor : EDbVendor.dbvoracle;
        SqlLexer.LexResult lexed = SqlLexer.tokenize(sql, resolved);
        List<SqlToken> tokens = lexed.tokens();
        if (!containsCreatePackage(tokens)) {
            return StatementParser.parseScript(sql, resolved);
        }
        ParserCursor cur = new ParserCursor(sql, tokens, resolved);
        ExpressionParser exprParser = new ExpressionParser(cur);
        QueryParser queryParser = new QueryParser(cur, exprParser);
        exprParser.setQueryParser(queryParser);
        DdlParser ddlParser = new DdlParser(cur, exprParser, queryParser);
        ProceduralAndSpecialParser procParser = new ProceduralAndSpecialParser(cur, exprParser, queryParser);
        StatementParser stmtParser = new StatementParser(cur, exprParser, queryParser, ddlParser, procParser);
        ddlParser.setProceduralParser(procParser);
        procParser.wire(stmtParser, ddlParser);

        List<SqlStmt> statements = new ArrayList<>();
        while (!cur.isAtEnd()) {
            while (cur.matchSymbol(";") || ("/".equals(cur.peek().rawText()) && cur.peek().column() == 1)) {
                if ("/".equals(cur.peek().rawText()) && cur.peek().column() == 1) {
                    cur.advance();
                }
            }
            if (cur.isAtEnd()) {
                break;
            }
            int before = cur.position();
            try {
                statements.add(stmtParser.parseSingleStatementFromCursor());
            } catch (SqlParseException ex) {
                statements.add(new SqlStmt.ErrorStmt("SyntaxError", ex.formattedSyntaxError(), ex.span()));
                break;
            } catch (RuntimeException ex) {
                SqlToken pk = cur.peek();
                String msg = "Syntax error(" + Math.max(1L, pk.line()) + "," + Math.max(1L, pk.column())
                        + ") near: " + pk.rawText();
                statements.add(new SqlStmt.ErrorStmt("SyntaxError", msg, pk.span()));
                break;
            }
            while (cur.matchSymbol(";")) {
                // consume statement terminator
            }
            if (cur.position() == before && !cur.isAtEnd()) {
                cur.advance();
            }
        }
        return new StatementParser.ParsedScript(statements, lexed.comments(), lexed.allTokens());
    }

    private static boolean containsCreatePackage(List<SqlToken> tokens) {
        int n = tokens.size();
        for (int i = 0; i < n; i++) {
            if (tokens.get(i).isWord("CREATE")) {
                for (int j = i + 1; j < Math.min(n, i + 6); j++) {
                    if (tokens.get(j).isWord("PACKAGE")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void checkLexicalAndDelimiterIntegrity(
            List<SqlToken> allTokens, EDbVendor vendor, List<ErrorItem> errors) {
        Deque<SqlToken> openParens = new ArrayDeque<>();
        for (SqlToken tok : allTokens) {
            String raw = tok.rawText();
            if (tok.type() == TokenType.BLOCK_COMMENT && raw.startsWith("/*") && !raw.endsWith("*/")) {
                errors.add(syntaxError(tok.line(), tok.column(), "/*", "Unterminated block comment"));
                return;
            }
            if (tok.type() == TokenType.STRING_LITERAL && isUnterminatedStringLiteral(raw)) {
                errors.add(syntaxError(tok.line(), tok.column(), raw, "Unterminated string literal"));
                return;
            }
            if (tok.type() == TokenType.QUOTED_IDENTIFIER && isUnterminatedQuotedIdentifier(raw)) {
                errors.add(syntaxError(tok.line(), tok.column(), raw, "Unterminated quoted identifier"));
                return;
            }
            if (tok.isComment() || tok.isEof()) {
                continue;
            }
            if (tok.isSymbol("(")) {
                openParens.push(tok);
            } else if (tok.isSymbol(")")) {
                if (openParens.isEmpty()) {
                    errors.add(syntaxError(tok.line(), tok.column(), ")", "Unmatched closing parenthesis ')'"));
                    return;
                }
                openParens.pop();
            }
        }
        if (!openParens.isEmpty()) {
            SqlToken unclosed = openParens.peek();
            errors.add(syntaxError(unclosed.line(), unclosed.column(), "(", "Unclosed opening parenthesis '('"));
        }
    }

    private static void checkStructuralTokenPatterns(
            List<SqlToken> codeTokens, EDbVendor vendor, List<ErrorItem> errors) {
        int n = codeTokens.size();
        for (int i = 0; i < n; i++) {
            SqlToken cur = codeTokens.get(i);
            if (cur.isEof()) {
                break;
            }
            SqlToken next = i + 1 < n ? codeTokens.get(i + 1) : null;
            if (cur.isSymbol(",") && next != null
                    && (next.isEof() || next.isSymbol(";") || next.isSymbol(")")
                    || (!TRAILING_COMMA_SELECT_VENDORS.contains(vendor) && next.isAnyWord("FROM", "WHERE")))) {
                errors.add(syntaxError(cur.line(), cur.column(), ",", "Unexpected trailing comma"));
                return;
            }
            if (cur.isWord("FROM") && next != null
                    && (next.isEof() || next.isSymbol(";") || next.isAnyWord("WHERE", "GROUP", "ORDER", "HAVING", "LIMIT"))) {
                SqlToken prev = i > 0 ? codeTokens.get(i - 1) : null;
                if (prev == null || !prev.isAnyWord("DISTINCT", "EXTRACT", "TRIM")) {
                    errors.add(syntaxError(next.line(), next.column(),
                            next.isEof() ? "EOF" : next.rawText(), "Missing table reference after FROM"));
                    return;
                }
            }
            if (cur.isAnyWord("WHERE", "HAVING", "ON") && next != null
                    && (next.isEof() || next.isSymbol(";") || next.isSymbol(")")
                    || next.isAnyWord("GROUP", "ORDER", "LIMIT", "UNION"))) {
                errors.add(syntaxError(cur.line(), cur.column(), cur.rawText(),
                        "Missing predicate expression after " + cur.upperValue()));
                return;
            }
            if (cur.isWord("JOIN") && next != null
                    && (next.isEof() || next.isSymbol(";") || next.isAnyWord("ON", "WHERE"))) {
                errors.add(syntaxError(cur.line(), cur.column(), cur.rawText(), "Missing table source after JOIN"));
                return;
            }
        }
    }

    private static void collectAstErrors(SqlStmt stmt, EDbVendor vendor, List<ErrorItem> errors) {
        if (stmt == null) {
            return;
        }
        if (stmt instanceof SqlStmt.ErrorStmt err) {
            long line = Math.max(1L, err.span().startLine());
            long col = Math.max(1L, err.span().startCol());
            errors.add(new ErrorItem(err.errorType(), err.message(), List.of(Coordinate.of(line, col))));
            return;
        }
        if (stmt instanceof SqlStmt.QueryStmt qs) {
            validateQueryExpr(qs.query(), vendor, errors);
        } else if (stmt instanceof SqlStmt.CreateViewStmt cv) {
            validateQueryExpr(cv.query(), vendor, errors);
        } else if (stmt instanceof SqlStmt.InsertStmt ins && ins.sourceQuery() != null) {
            validateQueryExpr(ins.sourceQuery(), vendor, errors);
        } else if (stmt instanceof SqlStmt.CreateRoutineStmt cr) {
            for (SqlStmt b : cr.bodyStatements()) {
                collectAstErrors(b, vendor, errors);
            }
        } else if (stmt instanceof SqlStmt.CreatePackageStmt cp) {
            for (SqlStmt.CreateRoutineStmt r : cp.routines()) {
                collectAstErrors(r, vendor, errors);
            }
            for (SqlStmt init : cp.initStatements()) {
                collectAstErrors(init, vendor, errors);
            }
        } else if (stmt instanceof SqlStmt.AnonymousBlockStmt ab) {
            for (SqlStmt b : ab.bodyStatements()) {
                collectAstErrors(b, vendor, errors);
            }
        } else if (stmt instanceof SqlStmt.IfStmt ifs) {
            for (SqlStmt.ConditionalBranch br : ifs.branches()) {
                for (SqlStmt s : br.statements()) {
                    collectAstErrors(s, vendor, errors);
                }
            }
            for (SqlStmt s : ifs.elseStatements()) {
                collectAstErrors(s, vendor, errors);
            }
        }
    }

    private static void validateQueryExpr(QueryExpr q, EDbVendor vendor, List<ErrorItem> errors) {
        if (q instanceof SelectQuery sq) {
            if (sq.selectItems().isEmpty() && vendor != EDbVendor.dbvpostgresql) {
                long line = Math.max(1L, sq.span().startLine());
                long col = Math.max(1L, sq.span().startCol());
                errors.add(syntaxError(line, col, "SELECT", "Empty SELECT projection list"));
            }
            for (SelectItem item : sq.selectItems()) {
                if (item.expr() == null) {
                    long line = Math.max(1L, item.span().startLine());
                    long col = Math.max(1L, item.span().startCol());
                    errors.add(syntaxError(line, col, "SELECT", "Invalid SELECT expression"));
                }
            }
        } else if (q instanceof SetOpQuery setOp) {
            validateQueryExpr(setOp.left(), vendor, errors);
            validateQueryExpr(setOp.right(), vendor, errors);
        }
    }

    private static boolean isUnterminatedStringLiteral(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        if (raw.startsWith("$")) {
            int secondDollar = raw.indexOf('$', 1);
            if (secondDollar > 0) {
                String tag = raw.substring(0, secondDollar + 1);
                return raw.length() < tag.length() * 2 || !raw.endsWith(tag);
            }
        }
        if ((raw.startsWith("q'") || raw.startsWith("Q'")) && raw.length() >= 3) {
            return raw.length() < 4 || !raw.endsWith("'");
        }
        char quote = raw.charAt(0);
        if ("NnEeXxBbRr".indexOf(quote) >= 0 && raw.length() >= 2) {
            quote = raw.charAt(1);
            raw = raw.substring(1);
        }
        if (raw.startsWith("\"\"\"") || raw.startsWith("'''")) {
            String triple = raw.substring(0, 3);
            return raw.length() < 6 || !raw.endsWith(triple);
        }
        return (quote == '\'' || quote == '"') && (raw.length() < 2 || raw.charAt(raw.length() - 1) != quote);
    }

    private static boolean isUnterminatedQuotedIdentifier(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        if (raw.startsWith("#\"")) {
            return raw.length() < 3 || !raw.endsWith("\"");
        }
        char open = raw.charAt(0);
        char expectedClose = open == '[' ? ']' : open;
        return raw.length() < 2 || raw.charAt(raw.length() - 1) != expectedClose;
    }

    private static boolean looksLikeFullSqlStatement(List<SqlToken> tokens) {
        if (tokens.size() < 3) {
            return false;
        }
        SqlToken first = tokens.get(0);
        if (first.quoted()) {
            return false;
        }
        for (SqlToken t : tokens) {
            if (t.isSymbol(",")) {
                // Comma-separated identifier list check: no whitespace-separated clause keywords like FROM/INTO/SET/TABLE
                boolean hasClauseStructure = tokens.stream().anyMatch(
                        k -> !k.quoted() && k.isAnyWord("FROM", "INTO", "SET", "VALUES", "WHERE", "JOIN"));
                if (!hasClauseStructure && !(first.isWord("CREATE") || first.isWord("ALTER") || first.isWord("DROP"))) {
                    return false;
                }
                break;
            }
        }
        return first.isAnyWord("SELECT", "WITH", "INSERT", "UPDATE", "DELETE", "MERGE", "CREATE", "ALTER", "DROP");
    }

    private static void collectReservedUsedAsIdentifiersInSql(
            List<SqlToken> tokens, EDbVendor vendor, List<TokenItem> bad) {
        for (int i = 1; i < tokens.size(); i++) {
            SqlToken prev = tokens.get(i - 1);
            SqlToken cur = tokens.get(i);
            if (cur.isEof() || cur.quoted()) {
                continue;
            }
            if ((prev.isWord("AS") || prev.isSymbol(".")
                    || (prev.isAnyWord("TABLE", "VIEW", "FROM", "JOIN", "INTO", "UPDATE")
                    && !cur.isAnyWord("IF", "ONLY", "LATERAL", "OUTER", "TABLE")))
                    && SqlDialectRules.isReservedKeyword(cur.upperValue(), vendor)) {
                bad.add(cur.toDemoTokenItem());
            }
        }
    }

    private static ErrorItem syntaxError(long line, long col, String near, String detail) {
        long safeLine = Math.max(1L, line);
        long safeCol = Math.max(1L, col);
        String msg = "Syntax error(" + safeLine + "," + safeCol + ") near: " + near + " - " + detail;
        return new ErrorItem("SyntaxError", msg, List.of(Coordinate.of(safeLine, safeCol)));
    }
}
