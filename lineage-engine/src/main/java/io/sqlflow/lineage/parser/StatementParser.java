package io.sqlflow.lineage.parser;

import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.lineage.ast.Expr;
import io.sqlflow.lineage.ast.QualifiedName;
import io.sqlflow.lineage.ast.QueryAst.CteDef;
import io.sqlflow.lineage.ast.QueryAst.JoinClause;
import io.sqlflow.lineage.ast.QueryAst.OutputClause;
import io.sqlflow.lineage.ast.QueryAst.QueryExpr;
import io.sqlflow.lineage.ast.QueryAst.SelectQuery;
import io.sqlflow.lineage.ast.QueryAst.TableSource;
import io.sqlflow.lineage.ast.SqlStmt;
import io.sqlflow.lineage.ast.SqlStmt.AssignmentItem;
import io.sqlflow.lineage.ast.SqlStmt.CopyIoStmt;
import io.sqlflow.lineage.ast.SqlStmt.DeleteStmt;
import io.sqlflow.lineage.ast.SqlStmt.InsertBranch;
import io.sqlflow.lineage.ast.SqlStmt.InsertStmt;
import io.sqlflow.lineage.ast.SqlStmt.MergeStmt;
import io.sqlflow.lineage.ast.SqlStmt.MergeWhenClause;
import io.sqlflow.lineage.ast.SqlStmt.MultiInsertStmt;
import io.sqlflow.lineage.ast.SqlStmt.UpdateStmt;
import io.sqlflow.lineage.lexer.SourceSpan;
import io.sqlflow.lineage.lexer.SqlLexer;
import io.sqlflow.lineage.lexer.SqlStatementSplitter;
import io.sqlflow.lineage.lexer.SqlStatementSplitter.StatementSlice;
import io.sqlflow.lineage.lexer.SqlToken;
import io.sqlflow.lineage.lexer.TokenType;
import java.util.ArrayList;
import java.util.List;

/**
 * Top-level multi-dialect SQL & procedural statement parser with statement-level error recovery
 * ({@code OBL-029}..{@code OBL-035}, {@code OBL-045}).
 */
public final class StatementParser {

    public record ParsedScript(
            List<SqlStmt> statements,
            List<SqlToken> comments,
            List<SqlToken> allTokens
    ) {
    }

    private final ParserCursor cur;
    private final ExpressionParser exprParser;
    private final QueryParser queryParser;
    private final DdlParser ddlParser;
    private final ProceduralAndSpecialParser procParser;

    public StatementParser(
            ParserCursor cur,
            ExpressionParser exprParser,
            QueryParser queryParser,
            DdlParser ddlParser,
            ProceduralAndSpecialParser procParser) {
        this.cur = cur;
        this.exprParser = exprParser;
        this.queryParser = queryParser;
        this.ddlParser = ddlParser;
        this.procParser = procParser;
    }

    public static ParsedScript parseScript(String sql, EDbVendor vendor) {
        return parseScript(sql, vendor, null, false);
    }

    public static ParsedScript parseScript(
            String sql,
            EDbVendor vendor,
            String explicitDelimiter,
            boolean mssqlColonBind) {
        if (sql == null || sql.isBlank()) {
            return new ParsedScript(List.of(), List.of(), List.of());
        }
        EDbVendor resolvedVendor = vendor != null ? vendor : EDbVendor.dbvoracle;
        SqlLexer.LexResult lexed = SqlLexer.tokenize(sql, resolvedVendor, mssqlColonBind);

        // Power Query M script starts with `let` and has commas across steps rather than `;`
        if (resolvedVendor == EDbVendor.dbvpowerquery
                || (!lexed.tokens().isEmpty() && lexed.tokens().get(0).isWord("LET"))) {
            SqlStmt pqStmt = parseSlice(sql, lexed.tokens(), resolvedVendor);
            return new ParsedScript(List.of(pqStmt), lexed.comments(), lexed.allTokens());
        }

        String delim = explicitDelimiter != null
                ? explicitDelimiter
                : SqlStatementSplitter.detectCustomDelimiter(sql);
        List<StatementSlice> slices = SqlStatementSplitter.splitTokens(sql, lexed.tokens(), resolvedVendor, delim);

        List<SqlStmt> statements = new ArrayList<>(slices.size());
        for (StatementSlice slice : slices) {
            statements.add(parseSlice(sql, slice.tokens(), resolvedVendor));
        }
        return new ParsedScript(statements, lexed.comments(), lexed.allTokens());
    }

    private static SqlStmt parseSlice(String sourceSql, List<SqlToken> sliceTokens, EDbVendor vendor) {
        ParserCursor cur = new ParserCursor(sourceSql, sliceTokens, vendor);
        ExpressionParser exprParser = new ExpressionParser(cur);
        QueryParser queryParser = new QueryParser(cur, exprParser);
        exprParser.setQueryParser(queryParser);
        DdlParser ddlParser = new DdlParser(cur, exprParser, queryParser);
        ProceduralAndSpecialParser procParser = new ProceduralAndSpecialParser(cur, exprParser, queryParser);
        StatementParser stmtParser = new StatementParser(cur, exprParser, queryParser, ddlParser, procParser);
        ddlParser.setProceduralParser(procParser);
        procParser.wire(stmtParser, ddlParser);

        try {
            SqlStmt stmt = stmtParser.parseSingleStatementFromCursor();
            // Validate no unexpected garbage tokens remain on strict top-level queries
            while (cur.matchSymbol(";")) {
                // trailing semicolon
            }
            if (!cur.isAtEnd() && isStrictStatementKind(stmt)) {
                throw cur.error("Unexpected trailing token", cur.peek());
            }
            return stmt;
        } catch (SqlParseException ex) {
            return new SqlStmt.ErrorStmt("SyntaxError", ex.formattedSyntaxError(), ex.span());
        } catch (RuntimeException ex) {
            SourceSpan sp = cur.peek().span();
            String msg = "Syntax error(" + Math.max(1L, sp.startLine()) + "," + Math.max(1L, sp.startCol())
                    + ") near: " + cur.peek().rawText() + " - " + ex.getMessage();
            return new SqlStmt.ErrorStmt("SyntaxError", msg, sp);
        }
    }

    private static boolean isStrictStatementKind(SqlStmt stmt) {
        return stmt instanceof SqlStmt.QueryStmt
                || stmt instanceof SqlStmt.InsertStmt
                || stmt instanceof SqlStmt.UpdateStmt
                || stmt instanceof SqlStmt.DeleteStmt;
    }

    public SqlStmt parseSingleStatementFromCursor() {
        SqlToken first = cur.peek();
        if (first.isEof()) {
            return new SqlStmt.NoOpStmt("EMPTY", first.span());
        }

        // Power Query M `let ... in ...`
        if (first.isWord("LET") && (cur.vendor() == EDbVendor.dbvpowerquery || cur.peek(2).isSymbol("="))) {
            return procParser.parsePowerQueryLetStmt();
        }

        // MDX `SELECT ... ON COLUMNS FROM [Cube]` or `WITH MEMBER ... SELECT ...`
        if (cur.vendor() == EDbVendor.dbvmdx
                || (first.isWord("WITH") && cur.peek(1).isAnyWord("MEMBER", "SET"))) {
            return procParser.parseMdxSelectStmt();
        }

        // CTE-prefixed Query or CTE-prefixed DML (`WITH cte AS (...) SELECT/INSERT/UPDATE/DELETE/MERGE/FROM ...`)
        if (first.isWord("WITH")) {
            SourceSpan startSpan = first.span();
            List<CteDef> ctes = queryParser.parseWithCtes();
            if (cur.checkAnyWord("INSERT", "REPLACE")) {
                return attachCtesToDml(ctes, parseInsertOrReplaceStmt(startSpan));
            }
            if (cur.checkWord("UPDATE")) {
                return attachCtesToUpdate(ctes, parseUpdateStmt(startSpan));
            }
            if (cur.checkWord("DELETE")) {
                return attachCtesToDelete(ctes, parseDeleteStmt(startSpan));
            }
            if (cur.checkWord("MERGE")) {
                return attachCtesToMerge(ctes, parseMergeStmt(startSpan));
            }
            if (cur.checkWord("FROM") && isMultiInsertFromAhead()) {
                return parseHiveFromMultiInsert(startSpan, ctes);
            }
            QueryExpr query = queryParser.parseSetOpChain(ctes, startSpan);
            return new SqlStmt.QueryStmt(query, query.span());
        }

        // Hive / Spark / Databricks Multi-Insert: FROM src [JOIN ...] INSERT INTO t1 SELECT ... INSERT OVERWRITE t2 SELECT ...
        if (first.isWord("FROM") && isMultiInsertFromAhead()) {
            return parseHiveFromMultiInsert(first.span(), List.of());
        }

        // Standard Query (`SELECT`, `(SELECT ...)`, `VALUES`, DuckDB `FROM tbl SELECT ...`, `TABLE tbl`)
        if (first.isAnyWord("SELECT", "VALUES", "FROM")
                || (first.isWord("TABLE") && cur.peek(1).isIdentifierLike())
                || (first.isSymbol("(") && cur.peek(1).isAnyWord("SELECT", "WITH", "VALUES"))) {
            QueryExpr q = queryParser.parseQuery();
            return new SqlStmt.QueryStmt(q, q.span());
        }

        // DML
        if (first.isAnyWord("INSERT", "REPLACE")) {
            return parseInsertOrReplaceStmt(first.span());
        }
        if (first.isWord("UPDATE")) {
            return parseUpdateStmt(first.span());
        }
        if (first.isWord("DELETE")) {
            return parseDeleteStmt(first.span());
        }
        if (first.isWord("MERGE") || first.isWord("UPSERT")) {
            if (first.isWord("UPSERT") && cur.peek(1).isWord("INTO")) {
                // ClickHouse / Phoenix / CockroachDB UPSERT INTO tbl ...
                cur.advance();
                return parseInsertAfterKeyword(first.span(), true, false);
            }
            return parseMergeStmt(first.span());
        }

        // Warehouse Data Movement: COPY, UNLOAD, LOAD DATA, BULK INSERT, EXPORT, PUT, GET
        if (first.isWord("COPY")) {
            return parseCopyStmt();
        }
        if (first.isWord("UNLOAD")) {
            return parseUnloadStmt();
        }
        if (first.isWord("LOAD") && cur.peek(1).isWord("DATA")) {
            return parseLoadDataStmt();
        }
        if (first.isWord("BULK") && cur.peek(1).isWord("INSERT")) {
            return parseBulkInsertStmt();
        }

        // DDL & Session Context
        if (first.isWord("CREATE")) {
            return ddlParser.parseCreateStmt();
        }
        if (first.isWord("ALTER")) {
            return ddlParser.parseAlterDispatch();
        }
        if (first.isWord("DROP")) {
            return ddlParser.parseDropStmt();
        }
        if (first.isWord("TRUNCATE")) {
            return ddlParser.parseTruncateStmt();
        }
        if (first.isWord("RENAME")) {
            return ddlParser.parseStandaloneRenameStmt();
        }
        if (first.isWord("USE")) {
            return ddlParser.parseUseStmt();
        }
        if (first.isWord("COMMENT") && cur.peek(1).isWord("ON")) {
            return ddlParser.parseCommentOnStmt();
        }

        // Procedural & Dynamic SQL
        if (first.isWord("DECLARE") || first.isWord("BEGIN")) {
            return procParser.parseAnonymousBlockOrDeclareStmt();
        }
        if (first.isWord("SET")) {
            return procParser.parseSetStmt();
        }
        if (first.isWord("CALL")) {
            return procParser.parseCallStmt();
        }
        if (first.isAnyWord("EXEC", "EXECUTE")) {
            return procParser.parseExecOrExecuteStmt();
        }
        if (first.isAnyWord("IF", "FOR", "WHILE", "LOOP", "OPEN", "FETCH", "CLOSE", "RETURN")) {
            return procParser.parseProceduralOrSqlStatement();
        }

        // Non-lineage administrative / utility statements (GRANT, REVOKE, ANALYZE, EXPLAIN, SHOW, DESCRIBE, COMMIT, ROLLBACK, VACUUM, PRAGMA)
        if (first.isWord("EXPLAIN")) {
            cur.advance();
            while (cur.matchAnyWord("ANALYZE", "VERBOSE", "EXTENDED", "FORMATTED", "PLAN", "FOR", "QUERY")) {
                if (cur.checkSymbol("(")) {
                    cur.skipParenthesizedGroup();
                }
            }
            if (!cur.isAtEnd()) {
                return parseSingleStatementFromCursor();
            }
        }
        if (first.isAnyWord("GRANT", "REVOKE", "ANALYZE", "OPTIMIZE", "VACUUM", "SHOW", "DESCRIBE", "DESC",
                "COMMIT", "ROLLBACK", "SAVEPOINT", "PRAGMA", "LOCK", "UNLOCK", "FLUSH", "CACHE", "UNCACHE",
                "MSCK", "REFRESH", "RESET", "PUT", "GET", "REMOVE", "LIST")) {
            SourceSpan start = cur.advance().span();
            while (!cur.isAtEnd() && !cur.checkSymbol(";")) {
                cur.advance();
            }
            return new SqlStmt.NoOpStmt(first.upperValue(), SourceSpan.between(start, cur.previous().span()));
        }

        throw cur.error("Unrecognized SQL statement starting with '" + first.rawText() + "'", first);
    }

    // -------------------------------------------------------------------------
    // DML Parsing: INSERT / MULTI-INSERT / REPLACE / UPDATE / DELETE / MERGE / COPY
    // -------------------------------------------------------------------------

    private SqlStmt parseInsertOrReplaceStmt(SourceSpan startSpan) {
        boolean replaceInto = cur.matchWord("REPLACE");
        if (!replaceInto) {
            cur.expectWord("INSERT");
        }
        // MySQL / SQLite conflict modifiers: INSERT IGNORE / INSERT OR REPLACE / Low_priority
        if (cur.matchWord("OR")) {
            if (cur.matchAnyWord("REPLACE", "IGNORE", "ABORT", "FAIL", "ROLLBACK")) {
                if ("REPLACE".equals(cur.previous().upperValue())) {
                    replaceInto = true;
                }
            }
        }
        cur.matchAnyWord("IGNORE", "LOW_PRIORITY", "HIGH_PRIORITY", "DELAYED");

        // Oracle / Snowflake Multi-Table INSERT ALL / INSERT FIRST
        if (cur.checkAnyWord("ALL", "FIRST") && (cur.peek(1).isAnyWord("WHEN", "INTO"))) {
            String style = cur.advance().isWord("ALL") ? "INSERT_ALL" : "INSERT_FIRST";
            return parseOracleSnowflakeMultiInsert(startSpan, style);
        }

        boolean overwrite = cur.matchWord("OVERWRITE");
        return parseInsertAfterKeyword(startSpan, replaceInto, overwrite);
    }

    private SqlStmt parseInsertAfterKeyword(SourceSpan startSpan, boolean replaceInto, boolean overwrite) {
        boolean directoryTarget = false;
        if (overwrite) {
            cur.matchWord("LOCAL");
            if (cur.matchWord("DIRECTORY")) {
                directoryTarget = true;
            } else {
                cur.matchWord("TABLE");
            }
        } else {
            cur.matchWord("INTO");
            cur.matchWord("TABLE");
        }

        QualifiedName targetTable;
        if (directoryTarget && cur.peek().type() == TokenType.STRING_LITERAL) {
            SqlToken dirTok = cur.advance();
            targetTable = QualifiedName.ofUri(dirTok.value(), dirTok.span());
            // Skip optional Hive ROW FORMAT / STORED AS before SELECT
            while (!cur.isAtEnd() && !cur.checkAnyWord("SELECT", "WITH", "VALUES", "FROM")) {
                cur.advance();
            }
        } else {
            targetTable = cur.parseQualifiedName();
        }

        // Skip optional alias (`INSERT INTO tbl AS t`) or Hive `PARTITION (dt = '2026-01-01', hr)`
        if (cur.matchWord("AS") && cur.peek().isIdentifierLike()) {
            cur.advance();
        }
        if (cur.matchWord("PARTITION") && cur.checkSymbol("(")) {
            cur.skipParenthesizedGroup();
        }

        // Target column list: (c1, c2, ...) — distinguish from parenthesized (SELECT ...)
        List<String> targetCols = new ArrayList<>();
        List<SourceSpan> targetColSpans = new ArrayList<>();
        if (cur.checkSymbol("(") && !cur.peek(1).isAnyWord("SELECT", "WITH", "VALUES")) {
            cur.expectSymbol("(");
            if (!cur.checkSymbol(")")) {
                do {
                    QualifiedName colQName = cur.parseQualifiedName();
                    targetCols.add(colQName.lastName());
                    targetColSpans.add(colQName.span());
                } while (cur.matchSymbol(","));
            }
            cur.expectSymbol(")");
        }

        // Optional Hive PARTITION after column list or ClickHouse SETTINGS
        if (cur.matchWord("PARTITION") && cur.checkSymbol("(")) {
            cur.skipParenthesizedGroup();
        }
        if (cur.matchWords("BY", "NAME")) {
            // DuckDB / Spark INSERT INTO tbl BY NAME SELECT ...
        }

        // T-SQL OUTPUT clause before VALUES/SELECT: INSERT INTO t (a) OUTPUT INSERTED.a INTO @log VALUES (1)
        OutputClause midOutput = null;
        if (cur.checkWord("OUTPUT")) {
            midOutput = queryParser.parseOptionalOutputOrReturning();
        }

        QueryExpr sourceQuery = null;
        if (cur.matchWord("SET")) {
            // MySQL `INSERT INTO t SET c1 = expr1, c2 = expr2`
            List<Expr> rowVals = new ArrayList<>();
            do {
                QualifiedName lhs = cur.parseQualifiedName();
                targetCols.add(lhs.lastName());
                targetColSpans.add(lhs.span());
                cur.expectSymbol("=");
                rowVals.add(exprParser.parseExpression());
            } while (cur.matchSymbol(","));
            sourceQuery = new io.sqlflow.lineage.ast.QueryAst.ValuesQuery(
                    List.of(rowVals), SourceSpan.between(startSpan, cur.previous().span()));
        } else if (cur.matchWords("DEFAULT", "VALUES")) {
            sourceQuery = new io.sqlflow.lineage.ast.QueryAst.ValuesQuery(List.of(), cur.previous().span());
        } else if (cur.checkWord("EXECUTE") || cur.checkWord("EXEC")) {
            // T-SQL `INSERT INTO #tmp EXEC dbo.get_rows @id = 1`
            SqlStmt execStmt = procParser.parseExecOrExecuteStmt();
            if (execStmt instanceof SqlStmt.CallStmt call) {
                Expr fnExpr = new Expr.FuncCallExpr(call.routineName(), false, call.arguments(),
                        List.of(), null, null, call.span());
                io.sqlflow.lineage.ast.QueryAst.SelectItem si = new io.sqlflow.lineage.ast.QueryAst.SelectItem(
                        fnExpr, null, false, null, call.routineName().fullDisplayName(), call.span());
                sourceQuery = new SelectQuery(
                        List.of(), false, List.of(si), null, List.of(), List.of(), List.of(),
                        null, null, List.of(), null, null, List.of(), List.of(), null, null, null, call.span());
            }
        } else if (!cur.isAtEnd() && !cur.checkSymbol(";")) {
            sourceQuery = queryParser.parseQuery();
        }

        // Skip optional ON CONFLICT DO UPDATE / ON DUPLICATE KEY UPDATE while preserving RETURNING
        OutputClause endOutput = skipConflictUpsertAndParseReturning();
        OutputClause effectiveOutput = endOutput != null ? endOutput : midOutput;

        return new InsertStmt(
                replaceInto,
                overwrite,
                directoryTarget,
                targetTable,
                targetCols,
                targetColSpans,
                sourceQuery,
                effectiveOutput,
                SourceSpan.between(startSpan, cur.previous().span()));
    }

    private OutputClause skipConflictUpsertAndParseReturning() {
        while (!cur.isAtEnd() && !cur.checkSymbol(";")) {
            if (cur.checkAnyWord("RETURNING", "OUTPUT")) {
                return queryParser.parseOptionalOutputOrReturning();
            }
            if (cur.matchWords("ON", "CONFLICT") || cur.matchWords("ON", "DUPLICATE")) {
                while (!cur.isAtEnd() && !cur.checkSymbol(";") && !cur.checkWord("RETURNING")) {
                    if (cur.checkSymbol("(")) {
                        cur.skipParenthesizedGroup();
                    } else {
                        cur.advance();
                    }
                }
            } else {
                break;
            }
        }
        return null;
    }

    private MultiInsertStmt parseOracleSnowflakeMultiInsert(SourceSpan startSpan, String style) {
        List<InsertBranch> branches = new ArrayList<>();
        Expr currentCond = null;

        while (!cur.isAtEnd() && !cur.checkAnyWord("SELECT", "WITH")) {
            if (cur.matchWord("WHEN")) {
                currentCond = exprParser.parseExpression();
                cur.expectWord("THEN");
            } else if (cur.matchWord("ELSE")) {
                currentCond = null;
            }
            while (cur.matchWord("INTO")) {
                SourceSpan bStart = cur.previous().span();
                QualifiedName tbl = cur.parseQualifiedName();
                List<String> cols = new ArrayList<>();
                List<SourceSpan> colSpans = new ArrayList<>();
                if (cur.checkSymbol("(")) {
                    cur.expectSymbol("(");
                    if (!cur.checkSymbol(")")) {
                        do {
                            SqlToken c = cur.expectIdentifier("column");
                            cols.add(c.value());
                            colSpans.add(c.span());
                        } while (cur.matchSymbol(","));
                    }
                    cur.expectSymbol(")");
                }
                List<Expr> vals = new ArrayList<>();
                if (cur.matchWord("VALUES") && cur.matchSymbol("(")) {
                    if (!cur.checkSymbol(")")) {
                        vals = exprParser.parseExpressionList();
                    }
                    cur.expectSymbol(")");
                }
                branches.add(new InsertBranch(
                        currentCond, tbl, false, false, cols, colSpans, vals, null,
                        SourceSpan.between(bStart, cur.previous().span())));
            }
            if (!cur.checkAnyWord("WHEN", "ELSE", "INTO", "SELECT", "WITH") && !cur.isAtEnd()) {
                break;
            }
        }

        QueryExpr srcQuery = (!cur.isAtEnd() && cur.checkAnyWord("SELECT", "WITH"))
                ? queryParser.parseQuery()
                : null;
        return new MultiInsertStmt(
                style, List.of(), List.of(), null, srcQuery, branches,
                SourceSpan.between(startSpan, cur.previous().span()));
    }

    private boolean isMultiInsertFromAhead() {
        // Scan ahead after FROM ... to see if INSERT appears before any top-level SELECT!
        int idx = 1;
        int depth = 0;
        while (!cur.peek(idx).isEof()) {
            SqlToken t = cur.peek(idx);
            if (t.isSymbol("(")) {
                depth++;
            } else if (t.isSymbol(")")) {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0) {
                if (t.isWord("INSERT")) {
                    return true;
                }
                if (t.isWord("SELECT")) {
                    return false;
                }
            }
            idx++;
        }
        return false;
    }

    private MultiInsertStmt parseHiveFromMultiInsert(SourceSpan startSpan, List<CteDef> ctes) {
        cur.expectWord("FROM");
        List<TableSource> sharedFrom = new ArrayList<>();
        List<JoinClause> sharedJoins = new ArrayList<>();
        queryParser.parseFromAndJoins(sharedFrom, sharedJoins);
        Expr sharedWhere = cur.matchWord("WHERE") ? exprParser.parseExpression() : null;

        List<InsertBranch> branches = new ArrayList<>();
        while (cur.matchWord("INSERT")) {
            SourceSpan bStart = cur.previous().span();
            boolean overwrite = cur.matchWord("OVERWRITE");
            boolean dir = false;
            if (overwrite) {
                cur.matchWord("LOCAL");
                if (cur.matchWord("DIRECTORY")) {
                    dir = true;
                } else {
                    cur.matchWord("TABLE");
                }
            } else {
                cur.matchWord("INTO");
                cur.matchWord("TABLE");
            }
            QualifiedName tbl = dir && cur.peek().type() == TokenType.STRING_LITERAL
                    ? QualifiedName.ofUri(cur.advance().value(), cur.previous().span())
                    : cur.parseQualifiedName();
            if (cur.matchWord("PARTITION") && cur.checkSymbol("(")) {
                cur.skipParenthesizedGroup();
            }
            List<String> cols = cur.checkSymbol("(") && !cur.peek(1).isWord("SELECT")
                    ? queryParser.parseIdentifierListInParens()
                    : List.of();
            QueryExpr bQuery = queryParser.parseSelectQuery(ctes, cur.peek().span());
            branches.add(new InsertBranch(
                    null, tbl, overwrite, dir, cols, List.of(), List.of(), bQuery,
                    SourceSpan.between(bStart, bQuery.span())));
            ctes = List.of();
        }
        return new MultiInsertStmt(
                "FROM_MULTI_INSERT", sharedFrom, sharedJoins, sharedWhere, null, branches,
                SourceSpan.between(startSpan, cur.previous().span()));
    }

    private UpdateStmt parseUpdateStmt(SourceSpan startSpan) {
        cur.expectWord("UPDATE");
        cur.matchAnyWord("LOW_PRIORITY", "IGNORE", "ONLY");
        if (cur.matchWord("TOP")) {
            if (cur.checkSymbol("(")) {
                cur.skipParenthesizedGroup();
            } else {
                cur.advance();
            }
            cur.matchWord("PERCENT");
        }

        List<TableSource> targetTables = new ArrayList<>();
        List<JoinClause> targetJoins = new ArrayList<>();
        queryParser.parseFromAndJoins(targetTables, targetJoins);

        cur.expectWord("SET");
        List<AssignmentItem> assignments = parseAssignmentList();

        OutputClause midOutput = cur.checkWord("OUTPUT") ? queryParser.parseOptionalOutputOrReturning() : null;

        List<TableSource> fromSources = new ArrayList<>();
        List<JoinClause> fromJoins = new ArrayList<>();
        if (cur.matchWord("FROM")) {
            queryParser.parseFromAndJoins(fromSources, fromJoins);
        }

        Expr whereExpr = cur.matchWord("WHERE") ? exprParser.parseExpression() : null;
        if (cur.matchWords("ORDER", "BY")) {
            queryParser.parseOrderByList();
        }
        if (cur.matchWord("LIMIT")) {
            exprParser.parseExpression();
        }
        OutputClause endOutput = cur.checkAnyWord("RETURNING", "OUTPUT")
                ? queryParser.parseOptionalOutputOrReturning()
                : midOutput;

        return new UpdateStmt(
                targetTables, targetJoins, assignments, fromSources, fromJoins, whereExpr, endOutput,
                SourceSpan.between(startSpan, cur.previous().span()));
    }

    private List<AssignmentItem> parseAssignmentList() {
        List<AssignmentItem> list = new ArrayList<>();
        do {
            SourceSpan aStart = cur.peek().span();
            if (cur.matchSymbol("(")) {
                // Tuple assignment: SET (c1, c2) = (SELECT x, y FROM ...)
                List<QualifiedName> lhsCols = new ArrayList<>();
                do {
                    lhsCols.add(cur.parseQualifiedName());
                } while (cur.matchSymbol(","));
                cur.expectSymbol(")");
                cur.expectSymbol("=");
                if (cur.matchSymbol("(") && cur.checkAnyWord("SELECT", "WITH")) {
                    QueryExpr sub = queryParser.parseQuery();
                    SqlToken close = cur.expectSymbol(")");
                    list.add(new AssignmentItem(lhsCols, null, sub,
                            cur.extractText(sub.span(), sub.span()),
                            SourceSpan.between(aStart, close.span())));
                } else {
                    Expr val = exprParser.parseExpression();
                    list.add(new AssignmentItem(lhsCols, val, null,
                            cur.extractText(val.span(), val.span()),
                            SourceSpan.between(aStart, val.span())));
                }
            } else {
                QualifiedName col = cur.parseQualifiedName();
                String op = cur.advance().rawText(); // =, +=, -=, *=, :=
                Expr val = exprParser.parseExpression();
                if (op.length() == 2 && op.endsWith("=") && !":=".equals(op)) {
                    // Compound assignment col += expr -> col + expr!
                    String mathOp = op.substring(0, 1);
                    Expr colRef = new Expr.ColumnRefExpr(col, List.of(), false, col.span());
                    val = new Expr.BinaryExpr(mathOp, colRef, val, SourceSpan.between(col.span(), val.span()));
                }
                list.add(new AssignmentItem(List.of(col), val, null,
                        cur.extractText(val.span(), val.span()),
                        SourceSpan.between(aStart, val.span())));
            }
        } while (cur.matchSymbol(","));
        return list;
    }

    private DeleteStmt parseDeleteStmt(SourceSpan startSpan) {
        cur.expectWord("DELETE");
        cur.matchAnyWord("LOW_PRIORITY", "QUICK", "IGNORE");
        if (cur.matchWord("TOP")) {
            if (cur.checkSymbol("(")) {
                cur.skipParenthesizedGroup();
            } else {
                cur.advance();
            }
            cur.matchWord("PERCENT");
        }

        // MySQL `DELETE t1 FROM t1 JOIN t2 ...` vs standard `DELETE [FROM] tbl [AS alias] [USING ...]`
        QualifiedName targetTable = null;
        String targetAlias = null;
        if (!cur.checkWord("FROM") && cur.peek().isIdentifierLike()) {
            targetTable = cur.parseQualifiedName();
            if (!cur.checkAnyWord("FROM", "USING", "WHERE", "OUTPUT", "RETURNING") && cur.peek().isIdentifierLike()) {
                if (cur.matchWord("AS") || !cur.checkSymbol(";")) {
                    if (cur.peek().isIdentifierLike() && !cur.checkAnyWord("SET", "JOIN", "INNER", "LEFT")) {
                        targetAlias = cur.advance().value();
                    }
                }
            }
        }

        OutputClause midOutput = cur.checkWord("OUTPUT") ? queryParser.parseOptionalOutputOrReturning() : null;

        List<TableSource> usingSources = new ArrayList<>();
        List<JoinClause> joins = new ArrayList<>();

        if (cur.matchWord("FROM")) {
            if (targetTable == null) {
                TableSource first = queryParser.parseTableSource();
                if (first instanceof io.sqlflow.lineage.ast.QueryAst.NamedTableSource nts) {
                    targetTable = nts.tableName();
                    targetAlias = nts.alias();
                }
                while (cur.matchSymbol(",") || !cur.isAtEnd()) {
                    int posBefore = cur.position();
                    if (cur.previous().isSymbol(",")) {
                        usingSources.add(queryParser.parseTableSource());
                    } else {
                        break;
                    }
                    if (cur.position() == posBefore) {
                        break;
                    }
                }
            } else {
                queryParser.parseFromAndJoins(usingSources, joins);
            }
        }

        if (cur.matchWord("USING")) {
            queryParser.parseFromAndJoins(usingSources, joins);
        }

        if (midOutput == null && cur.checkWord("OUTPUT")) {
            midOutput = queryParser.parseOptionalOutputOrReturning();
        }

        Expr whereExpr = cur.matchWord("WHERE") ? exprParser.parseExpression() : null;
        if (cur.matchWords("ORDER", "BY")) {
            queryParser.parseOrderByList();
        }
        if (cur.matchWord("LIMIT")) {
            exprParser.parseExpression();
        }
        OutputClause endOutput = cur.checkAnyWord("RETURNING", "OUTPUT")
                ? queryParser.parseOptionalOutputOrReturning()
                : midOutput;

        return new DeleteStmt(
                targetTable, targetAlias, usingSources, joins, whereExpr, endOutput,
                SourceSpan.between(startSpan, cur.previous().span()));
    }

    private MergeStmt parseMergeStmt(SourceSpan startSpan) {
        cur.expectWord("MERGE");
        if (cur.matchWord("TOP")) {
            if (cur.checkSymbol("(")) {
                cur.skipParenthesizedGroup();
            } else {
                cur.advance();
            }
            cur.matchWord("PERCENT");
        }
        cur.matchWord("INTO");
        QualifiedName targetTable = cur.parseQualifiedName();
        String targetAlias = null;
        if (cur.matchWord("AS") && cur.peek().isIdentifierLike()) {
            targetAlias = cur.advance().value();
        } else if (cur.peek().isIdentifierLike() && !cur.checkWord("USING")) {
            targetAlias = cur.advance().value();
        }

        cur.expectWord("USING");
        TableSource usingSource = queryParser.parseTableSource();

        cur.expectWord("ON");
        Expr onCond = exprParser.parseExpression();
        String rawOn = cur.extractText(onCond.span(), onCond.span());

        List<MergeWhenClause> whenClauses = new ArrayList<>();
        while (cur.matchWord("WHEN")) {
            SourceSpan wStart = cur.previous().span();
            boolean matched = true;
            boolean bySource = false;
            if (cur.matchWord("NOT")) {
                matched = false;
            }
            cur.expectWord("MATCHED");
            if (cur.matchWord("BY")) {
                if (cur.matchWord("SOURCE")) {
                    bySource = true;
                } else {
                    cur.matchWord("TARGET");
                }
            }
            Expr andCond = cur.matchWord("AND") ? exprParser.parseExpression() : null;
            cur.expectWord("THEN");

            if (cur.matchWord("UPDATE")) {
                cur.expectWord("SET");
                List<AssignmentItem> assigns = parseAssignmentList();
                Expr delWhere = null;
                // Oracle MERGE UPDATE SET ... WHERE ... DELETE WHERE ...
                if (cur.matchWord("WHERE")) {
                    Expr updWhere = exprParser.parseExpression();
                    andCond = andCond != null
                            ? new Expr.BinaryExpr("AND", andCond, updWhere, SourceSpan.between(andCond.span(), updWhere.span()))
                            : updWhere;
                }
                if (cur.matchWord("DELETE") && cur.matchWord("WHERE")) {
                    delWhere = exprParser.parseExpression();
                }
                whenClauses.add(new MergeWhenClause(
                        matched, bySource, andCond, "UPDATE", assigns, List.of(), List.of(), delWhere,
                        SourceSpan.between(wStart, cur.previous().span())));
            } else if (cur.matchWord("DELETE")) {
                Expr delWhere = cur.matchWord("WHERE") ? exprParser.parseExpression() : null;
                whenClauses.add(new MergeWhenClause(
                        matched, bySource, andCond, "DELETE", List.of(), List.of(), List.of(), delWhere,
                        SourceSpan.between(wStart, cur.previous().span())));
            } else if (cur.matchWord("INSERT")) {
                List<String> insCols = cur.checkSymbol("(") && !cur.peek(1).isWord("VALUES")
                        ? queryParser.parseIdentifierListInParens()
                        : List.of();
                List<Expr> insVals = new ArrayList<>();
                if (cur.matchWord("VALUES")) {
                    if (cur.matchSymbol("(")) {
                        if (!cur.checkSymbol(")")) {
                            insVals = exprParser.parseExpressionList();
                        }
                        cur.expectSymbol(")");
                    } else if (cur.peek().isWord("ROW")) {
                        cur.advance();
                    }
                } else if (cur.matchWord("ROW")) {
                    // BigQuery MERGE ... THEN INSERT ROW
                }
                if (cur.matchWord("WHERE")) {
                    Expr insWhere = exprParser.parseExpression();
                    andCond = andCond != null
                            ? new Expr.BinaryExpr("AND", andCond, insWhere, SourceSpan.between(andCond.span(), insWhere.span()))
                            : insWhere;
                }
                whenClauses.add(new MergeWhenClause(
                        matched, bySource, andCond, "INSERT", List.of(), insCols, insVals, null,
                        SourceSpan.between(wStart, cur.previous().span())));
            } else if (cur.matchWords("DO", "NOTHING")) {
                // Postgres MERGE ... THEN DO NOTHING
            }
        }

        OutputClause outputClause = cur.checkAnyWord("OUTPUT", "RETURNING")
                ? queryParser.parseOptionalOutputOrReturning()
                : null;
        while (!cur.isAtEnd() && !cur.checkSymbol(";")) {
            cur.advance();
        }
        return new MergeStmt(
                targetTable, targetAlias, usingSource, onCond, rawOn, whenClauses, outputClause,
                SourceSpan.between(startSpan, cur.previous().span()));
    }

    // -------------------------------------------------------------------------
    // Warehouse Data Movement: COPY / UNLOAD / LOAD DATA / BULK INSERT
    // -------------------------------------------------------------------------

    private CopyIoStmt parseCopyStmt() {
        SourceSpan start = cur.expectWord("COPY").span();
        boolean into = cur.matchWord("INTO");

        QualifiedName firstObj;
        QueryExpr firstSubquery = null;
        List<String> cols = List.of();

        if (cur.checkSymbol("(") && cur.peek(1).isAnyWord("SELECT", "WITH")) {
            // Postgres / DuckDB COPY (SELECT ...) TO 's3://...'
            cur.expectSymbol("(");
            firstSubquery = queryParser.parseQuery();
            cur.expectSymbol(")");
            firstObj = null;
        } else {
            firstObj = parseCopyEndpoint();
            if (cur.checkSymbol("(")) {
                cols = queryParser.parseIdentifierListInParens();
            }
        }

        if (cur.matchWord("TO")) {
            // Postgres / DuckDB COPY tbl_or_query TO 'path.parquet'
            QualifiedName dest = parseCopyEndpoint();
            String fmt = scanFileFormat();
            return new CopyIoStmt("UNLOAD", dest, List.of(), firstObj, firstSubquery, fmt,
                    SourceSpan.between(start, cur.previous().span()));
        }

        // Snowflake `COPY INTO target_or_stage FROM source_or_select` OR Redshift/Postgres `COPY target FROM 's3://...'`
        QualifiedName srcObj = null;
        QueryExpr srcQuery = null;
        if (cur.matchWord("FROM")) {
            if (cur.checkSymbol("(")) {
                cur.expectSymbol("(");
                if (cur.checkAnyWord("SELECT", "WITH")) {
                    srcQuery = queryParser.parseQuery();
                } else {
                    srcObj = parseCopyEndpoint();
                }
                cur.expectSymbol(")");
            } else {
                srcObj = parseCopyEndpoint();
            }
        }
        String fmt = scanFileFormat();
        boolean unloadingToStageOrUri = into && firstObj != null && (firstObj.stageRef() || firstObj.uriRef());
        return new CopyIoStmt(
                unloadingToStageOrUri ? "UNLOAD" : "COPY",
                firstObj,
                cols,
                srcObj,
                srcQuery,
                fmt,
                SourceSpan.between(start, cur.previous().span()));
    }

    private CopyIoStmt parseUnloadStmt() {
        SourceSpan start = cur.expectWord("UNLOAD").span();
        QueryExpr srcQuery = null;
        if (cur.matchSymbol("(")) {
            if (cur.peek().type() == TokenType.STRING_LITERAL) {
                SqlToken strTok = cur.advance();
                ParsedScript inner = parseScript(strTok.value(), cur.vendor(), null, false);
                if (!inner.statements().isEmpty() && inner.statements().get(0) instanceof SqlStmt.QueryStmt qs) {
                    srcQuery = qs.query();
                }
            } else if (cur.checkAnyWord("SELECT", "WITH")) {
                srcQuery = queryParser.parseQuery();
            }
            cur.expectSymbol(")");
        }
        cur.matchWord("TO");
        QualifiedName dest = parseCopyEndpoint();
        String fmt = scanFileFormat();
        return new CopyIoStmt("UNLOAD", dest, List.of(), null, srcQuery, fmt,
                SourceSpan.between(start, cur.previous().span()));
    }

    private CopyIoStmt parseLoadDataStmt() {
        SourceSpan start = cur.expectWord("LOAD").span();
        cur.expectWord("DATA");
        cur.matchAnyWord("LOCAL", "LOW_PRIORITY", "CONCURRENT");
        cur.matchAnyWord("INPATH", "INFILE");
        QualifiedName srcPath = parseCopyEndpoint();
        cur.matchWord("OVERWRITE");
        cur.matchWord("INTO");
        cur.matchWord("TABLE");
        QualifiedName targetTbl = cur.parseQualifiedName();
        String fmt = scanFileFormat();
        return new CopyIoStmt("LOAD_DATA", targetTbl, List.of(), srcPath, null, fmt,
                SourceSpan.between(start, cur.previous().span()));
    }

    private CopyIoStmt parseBulkInsertStmt() {
        SourceSpan start = cur.expectWord("BULK").span();
        cur.expectWord("INSERT");
        QualifiedName targetTbl = cur.parseQualifiedName();
        cur.expectWord("FROM");
        QualifiedName srcPath = parseCopyEndpoint();
        String fmt = scanFileFormat();
        return new CopyIoStmt("BULK_INSERT", targetTbl, List.of(), srcPath, null, fmt,
                SourceSpan.between(start, cur.previous().span()));
    }

    private QualifiedName parseCopyEndpoint() {
        if (cur.peek().type() == TokenType.STRING_LITERAL) {
            SqlToken s = cur.advance();
            return QualifiedName.ofUri(s.value(), s.span());
        }
        if (cur.peek().type() == TokenType.STAGE_REFERENCE) {
            SqlToken st = cur.advance();
            return QualifiedName.ofStage(st.value(), st.span());
        }
        return cur.parseQualifiedName();
    }

    private String scanFileFormat() {
        String fmt = null;
        while (!cur.isAtEnd() && !cur.checkSymbol(";")) {
            if (cur.matchAnyWord("PARQUET", "CSV", "JSON", "ORC", "AVRO", "TEXTFILE", "DELIMITED")) {
                fmt = cur.previous().upperValue();
            } else if (cur.matchWords("FORMAT", "AS") && !cur.isAtEnd()) {
                fmt = cur.advance().upperValue();
            } else if (cur.matchWord("TYPE") && cur.matchSymbol("=") && !cur.isAtEnd()) {
                fmt = cur.advance().value().toUpperCase();
            } else {
                cur.advance();
            }
        }
        return fmt;
    }

    // -------------------------------------------------------------------------
    // CTE Attachment Helpers for CTE-Prefixed DML
    // -------------------------------------------------------------------------

    private SqlStmt attachCtesToDml(List<CteDef> ctes, SqlStmt dml) {
        if (dml instanceof InsertStmt ins && ins.sourceQuery() instanceof SelectQuery sq) {
            List<CteDef> merged = new ArrayList<>(ctes);
            merged.addAll(sq.ctes());
            SelectQuery newSq = new SelectQuery(
                    merged, sq.distinct(), sq.selectItems(), sq.intoClause(), sq.fromSources(),
                    sq.joins(), sq.lateralViews(), sq.whereExpr(), sq.connectBy(), sq.groupByExprs(),
                    sq.havingExpr(), sq.qualifyExpr(), sq.windowDefs(), sq.orderBy(), sq.limitExpr(),
                    sq.offsetExpr(), sq.outputClause(), sq.span());
            return new InsertStmt(
                    ins.replaceInto(), ins.overwrite(), ins.directoryTarget(), ins.targetTable(),
                    ins.targetColumns(), ins.targetColumnSpans(), newSq, ins.outputClause(),
                    SourceSpan.between(ctes.get(0).span(), ins.span()));
        }
        return dml;
    }

    private SqlStmt attachCtesToUpdate(List<CteDef> ctes, UpdateStmt upd) {
        List<TableSource> fromWithCtes = new ArrayList<>();
        for (CteDef cte : ctes) {
            fromWithCtes.add(new io.sqlflow.lineage.ast.QueryAst.SubqueryTableSource(
                    cte.query(), false, cte.name(), cte.nameQuoted(), cte.nameSpan(),
                    cte.columnAliases(), null, null, cte.span()));
        }
        fromWithCtes.addAll(upd.fromSources());
        return new UpdateStmt(
                upd.targetTables(), upd.targetJoins(), upd.assignments(),
                fromWithCtes, upd.fromJoins(), upd.whereExpr(), upd.outputClause(),
                SourceSpan.between(ctes.get(0).span(), upd.span()));
    }

    private SqlStmt attachCtesToDelete(List<CteDef> ctes, DeleteStmt del) {
        List<TableSource> usingWithCtes = new ArrayList<>();
        for (CteDef cte : ctes) {
            usingWithCtes.add(new io.sqlflow.lineage.ast.QueryAst.SubqueryTableSource(
                    cte.query(), false, cte.name(), cte.nameQuoted(), cte.nameSpan(),
                    cte.columnAliases(), null, null, cte.span()));
        }
        usingWithCtes.addAll(del.usingSources());
        return new DeleteStmt(
                del.targetTable(), del.targetAlias(), usingWithCtes, del.joins(),
                del.whereExpr(), del.outputClause(),
                SourceSpan.between(ctes.get(0).span(), del.span()));
    }

    private SqlStmt attachCtesToMerge(List<CteDef> ctes, MergeStmt mrg) {
        TableSource using = mrg.usingSource();
        if (using instanceof io.sqlflow.lineage.ast.QueryAst.NamedTableSource nts) {
            for (CteDef cte : ctes) {
                if (cte.name().equalsIgnoreCase(nts.tableName().lastName())) {
                    String effAlias = nts.alias() != null ? nts.alias() : cte.name();
                    using = new io.sqlflow.lineage.ast.QueryAst.SubqueryTableSource(
                            cte.query(), false, effAlias, cte.nameQuoted(), cte.nameSpan(),
                            cte.columnAliases(), null, null, cte.span());
                    break;
                }
            }
        }
        return new MergeStmt(
                mrg.targetTable(), mrg.targetAlias(), using, mrg.onCondition(), mrg.rawOnCondition(),
                mrg.whenClauses(), mrg.outputClause(),
                SourceSpan.between(ctes.get(0).span(), mrg.span()));
    }
}
