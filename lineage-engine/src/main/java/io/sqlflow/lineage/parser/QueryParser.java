package io.sqlflow.lineage.parser;

import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.lineage.ast.Expr;
import io.sqlflow.lineage.ast.QualifiedName;
import io.sqlflow.lineage.ast.QueryAst.ConnectByClause;
import io.sqlflow.lineage.ast.QueryAst.CteDef;
import io.sqlflow.lineage.ast.QueryAst.IntoClause;
import io.sqlflow.lineage.ast.QueryAst.JoinClause;
import io.sqlflow.lineage.ast.QueryAst.LateralViewClause;
import io.sqlflow.lineage.ast.QueryAst.NamedTableSource;
import io.sqlflow.lineage.ast.QueryAst.NamedWindowDef;
import io.sqlflow.lineage.ast.QueryAst.OrderByItem;
import io.sqlflow.lineage.ast.QueryAst.OutputClause;
import io.sqlflow.lineage.ast.QueryAst.ParenthesizedJoinSource;
import io.sqlflow.lineage.ast.QueryAst.PivotAggItem;
import io.sqlflow.lineage.ast.QueryAst.PivotSpec;
import io.sqlflow.lineage.ast.QueryAst.PivotValueItem;
import io.sqlflow.lineage.ast.QueryAst.QueryExpr;
import io.sqlflow.lineage.ast.QueryAst.SelectItem;
import io.sqlflow.lineage.ast.QueryAst.SelectQuery;
import io.sqlflow.lineage.ast.QueryAst.SetOpQuery;
import io.sqlflow.lineage.ast.QueryAst.SubqueryTableSource;
import io.sqlflow.lineage.ast.QueryAst.TableFunctionSource;
import io.sqlflow.lineage.ast.QueryAst.TableSource;
import io.sqlflow.lineage.ast.QueryAst.UnpivotSpec;
import io.sqlflow.lineage.ast.QueryAst.ValuesQuery;
import io.sqlflow.lineage.ast.QueryAst.WindowSpec;
import io.sqlflow.lineage.ast.SqlStmt.ColumnDef;
import io.sqlflow.lineage.lexer.SourceSpan;
import io.sqlflow.lineage.lexer.SqlDialectRules;
import io.sqlflow.lineage.lexer.SqlToken;
import io.sqlflow.lineage.lexer.TokenType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Multi-dialect SQL query parser supporting CTEs, set operations, complex joins, {@code UNNEST},
 * {@code FLATTEN}, {@code LATERAL VIEW EXPLODE}, {@code PIVOT}/{@code UNPIVOT}, {@code CONNECT BY},
 * {@code QUALIFY}, {@code RETURNING}/{@code OUTPUT}, SOQL nested subqueries, and {@code VALUES}.
 */
public final class QueryParser {

    private static final Set<String> CLAUSE_BOUNDARY_KEYWORDS = Set.of(
            "FROM", "WHERE", "GROUP", "HAVING", "QUALIFY", "WINDOW", "ORDER", "LIMIT", "OFFSET",
            "FETCH", "UNION", "INTERSECT", "EXCEPT", "MINUS", "INTO", "INNER", "LEFT", "RIGHT",
            "FULL", "CROSS", "NATURAL", "JOIN", "OUTER", "SEMI", "ANTI", "LATERAL", "PIVOT",
            "UNPIVOT", "ON", "USING", "WHEN", "THEN", "ELSE", "END", "RETURNING", "OUTPUT",
            "CONNECT", "START", "FOR", "CLUSTER", "DISTRIBUTE", "SORT", "SETTINGS", "FORMAT", "PREWHERE"
    );

    private final ParserCursor cur;
    private final ExpressionParser exprParser;

    public QueryParser(ParserCursor cur, ExpressionParser exprParser) {
        this.cur = cur;
        this.exprParser = exprParser;
    }

    public QueryExpr parseQuery() {
        List<CteDef> ctes = List.of();
        SourceSpan startSpan = cur.peek().span();
        if (cur.checkWord("WITH")) {
            ctes = parseWithCtes();
        }
        return parseSetOpChain(ctes, startSpan);
    }

    public List<CteDef> parseWithCtes() {
        cur.expectWord("WITH");
        boolean recursive = cur.matchWord("RECURSIVE");
        List<CteDef> ctes = new ArrayList<>();
        do {
            // ClickHouse / BigQuery allow WITH expr AS alias scalar CTEs alongside table CTEs
            if (!isStandardCteDefinitionAhead()) {
                // Skip or treat scalar WITH alias expression: expr AS name
                Expr scalarExpr = exprParser.parseExpression();
                cur.expectWord("AS");
                SqlToken nameTok = cur.expectIdentifier("CTE alias");
                SelectItem item = new SelectItem(scalarExpr, nameTok.value(), nameTok.quoted(),
                        nameTok.span(), cur.extractText(scalarExpr.span(), scalarExpr.span()), scalarExpr.span());
                SelectQuery sq = new SelectQuery(
                        List.of(), false, List.of(item), null, List.of(), List.of(), List.of(),
                        null, null, List.of(), null, null, List.of(), List.of(), null, null, null,
                        SourceSpan.between(scalarExpr.span(), nameTok.span()));
                ctes.add(new CteDef(nameTok.value(), nameTok.quoted(), List.of(), recursive, sq,
                        nameTok.span(), SourceSpan.between(scalarExpr.span(), nameTok.span())));
                continue;
            }

            SqlToken nameTok = cur.expectIdentifier("CTE name");
            List<String> colAliases = List.of();
            if (cur.checkSymbol("(") && !cur.peek(1).isAnyWord("SELECT", "WITH", "VALUES")) {
                colAliases = parseIdentifierListInParens();
            }
            cur.expectWord("AS");
            if (cur.matchWord("NOT")) {
                cur.matchWord("MATERIALIZED");
            } else {
                cur.matchWord("MATERIALIZED");
            }
            cur.expectSymbol("(");
            QueryExpr cteQuery = parseQuery();
            SqlToken close = cur.expectSymbol(")");
            // Optional SEARCH / CYCLE clauses in PostgreSQL / Oracle recursive CTEs
            skipOptionalCteSearchCycle();
            ctes.add(new CteDef(
                    nameTok.value(),
                    nameTok.quoted(),
                    colAliases,
                    recursive,
                    cteQuery,
                    nameTok.span(),
                    SourceSpan.between(nameTok.span(), close.span())));
        } while (cur.matchSymbol(","));
        return ctes;
    }

    private boolean isStandardCteDefinitionAhead() {
        if (!cur.peek().isIdentifierLike()) {
            return false;
        }
        if (cur.peek(1).isWord("AS")) {
            return cur.peek(2).isSymbol("(")
                    || cur.peek(2).isAnyWord("MATERIALIZED", "NOT");
        }
        if (cur.peek(1).isSymbol("(") && !cur.peek(2).isAnyWord("SELECT", "WITH", "VALUES")) {
            // Scan past column list (...) to check for AS (
            int idx = 2;
            int depth = 1;
            while (depth > 0 && !cur.peek(idx).isEof()) {
                if (cur.peek(idx).isSymbol("(")) {
                    depth++;
                } else if (cur.peek(idx).isSymbol(")")) {
                    depth--;
                }
                idx++;
            }
            return cur.peek(idx).isWord("AS");
        }
        return false;
    }

    private void skipOptionalCteSearchCycle() {
        while (cur.checkAnyWord("SEARCH", "CYCLE")) {
            while (!cur.isAtEnd() && !cur.checkSymbol(",") && !cur.checkAnyWord("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "FROM", "VALUES")) {
                if (cur.checkSymbol("(")) {
                    cur.skipParenthesizedGroup();
                } else {
                    cur.advance();
                }
            }
        }
    }

    public QueryExpr parseSetOpChain(List<CteDef> leadingCtes, SourceSpan startSpan) {
        QueryExpr left = parseQueryTerm(leadingCtes, startSpan);

        while (!cur.isAtEnd()) {
            String setOp = matchSetOperator();
            if (setOp == null) {
                break;
            }
            QueryExpr right = parseQueryTerm(List.of(), cur.peek().span());
            List<OrderByItem> trailingOrder = List.of();
            Expr trailingLimit = null;
            if (cur.matchWords("ORDER", "BY")) {
                trailingOrder = parseOrderByList();
            }
            trailingLimit = parseOptionalLimitFetch();
            SourceSpan endSpan = trailingLimit != null
                    ? trailingLimit.span()
                    : (!trailingOrder.isEmpty() ? trailingOrder.get(trailingOrder.size() - 1).span() : right.span());
            left = new SetOpQuery(
                    List.of(),
                    setOp,
                    left,
                    right,
                    trailingOrder,
                    trailingLimit,
                    SourceSpan.between(left.span(), endSpan));
        }
        return left;
    }

    private String matchSetOperator() {
        if (cur.matchWord("UNION")) {
            if (cur.matchWord("ALL")) {
                cur.matchWords("BY", "NAME");
                return "UNION ALL";
            }
            cur.matchWord("DISTINCT");
            cur.matchWords("BY", "NAME");
            return "UNION";
        }
        if (cur.matchWord("INTERSECT")) {
            cur.matchAnyWord("ALL", "DISTINCT");
            return "INTERSECT";
        }
        if (cur.checkAnyWord("EXCEPT", "MINUS") && !isStarExceptModifier()) {
            String op = cur.advance().upperValue();
            cur.matchAnyWord("ALL", "DISTINCT");
            return op;
        }
        return null;
    }

    private boolean isStarExceptModifier() {
        return cur.peek(1).isSymbol("(") && !cur.peek(2).isAnyWord("SELECT", "WITH", "VALUES");
    }

    private QueryExpr parseQueryTerm(List<CteDef> ctes, SourceSpan startSpan) {
        if (cur.checkSymbol("(")) {
            cur.expectSymbol("(");
            QueryExpr inner = parseQuery();
            SqlToken close = cur.expectSymbol(")");
            List<OrderByItem> outerOrder = List.of();
            if (cur.matchWords("ORDER", "BY")) {
                outerOrder = parseOrderByList();
            }
            Expr outerLimit = parseOptionalLimitFetch();
            if (!ctes.isEmpty() && inner instanceof SelectQuery sq) {
                List<CteDef> mergedCtes = new ArrayList<>(ctes);
                mergedCtes.addAll(sq.ctes());
                return new SelectQuery(
                        mergedCtes, sq.distinct(), sq.selectItems(), sq.intoClause(), sq.fromSources(),
                        sq.joins(), sq.lateralViews(), sq.whereExpr(), sq.connectBy(), sq.groupByExprs(),
                        sq.havingExpr(), sq.qualifyExpr(), sq.windowDefs(),
                        !outerOrder.isEmpty() ? outerOrder : sq.orderBy(),
                        outerLimit != null ? outerLimit : sq.limitExpr(),
                        sq.offsetExpr(), sq.outputClause(), SourceSpan.between(startSpan, close.span()));
            }
            return inner;
        }
        if (cur.checkWord("VALUES")) {
            return parseValuesQuery();
        }
        if (cur.checkWord("TABLE") && cur.peek(1).isIdentifierLike()) {
            // PostgreSQL / DuckDB / Spark shorthand: TABLE schema.tbl == SELECT * FROM schema.tbl
            SqlToken tKw = cur.advance();
            QualifiedName tblName = cur.parseQualifiedName();
            SelectItem star = new SelectItem(
                    new Expr.StarExpr(null, List.of(), java.util.Map.of(), java.util.Map.of(), tblName.span()),
                    null, false, null, "*", tblName.span());
            NamedTableSource src = new NamedTableSource(tblName, null, false, null, List.of(), null, null, tblName.span());
            return new SelectQuery(
                    ctes, false, List.of(star), null, List.of(src), List.of(), List.of(),
                    null, null, List.of(), null, null, List.of(), List.of(), null, null, null,
                    SourceSpan.between(startSpan, tblName.span()));
        }
        if (cur.checkWord("FROM")) {
            // DuckDB / Spark / Hive FROM-first query syntax: FROM src SELECT a, b WHERE ...
            return parseFromFirstSelectQuery(ctes, startSpan);
        }
        return parseSelectQuery(ctes, startSpan);
    }

    public ValuesQuery parseValuesQuery() {
        SqlToken startTok = cur.expectWord("VALUES");
        List<List<Expr>> rows = new ArrayList<>();
        SourceSpan endSpan = startTok.span();
        do {
            if (cur.matchSymbol("(")) {
                List<Expr> row = new ArrayList<>();
                if (!cur.checkSymbol(")")) {
                    do {
                        row.add(exprParser.parseExpression());
                    } while (cur.matchSymbol(","));
                }
                endSpan = cur.expectSymbol(")").span();
                rows.add(row);
            } else {
                Expr single = exprParser.parseExpression();
                endSpan = single.span();
                rows.add(List.of(single));
            }
        } while (cur.matchSymbol(","));
        return new ValuesQuery(rows, SourceSpan.between(startTok.span(), endSpan));
    }

    private SelectQuery parseFromFirstSelectQuery(List<CteDef> ctes, SourceSpan startSpan) {
        cur.expectWord("FROM");
        List<TableSource> fromSources = new ArrayList<>();
        List<JoinClause> joins = new ArrayList<>();
        parseFromAndJoins(fromSources, joins);
        List<LateralViewClause> lateralViews = parseLateralViews();
        Expr whereExpr = null;
        if (cur.matchWord("WHERE")) {
            whereExpr = exprParser.parseExpression();
        }
        List<SelectItem> items = List.of();
        boolean distinct = false;
        if (cur.matchWord("SELECT")) {
            distinct = parseSelectModifiers();
            items = parseSelectItemList();
        } else {
            // DuckDB `FROM tbl` implies `SELECT * FROM tbl`
            SourceSpan sp = fromSources.isEmpty() ? startSpan : fromSources.get(0).span();
            items = List.of(new SelectItem(
                    new Expr.StarExpr(null, List.of(), java.util.Map.of(), java.util.Map.of(), sp),
                    null, false, null, "*", sp));
        }
        if (whereExpr == null && cur.matchWord("WHERE")) {
            whereExpr = exprParser.parseExpression();
        }
        List<Expr> groupBy = List.of();
        if (cur.matchWords("GROUP", "BY")) {
            groupBy = parseGroupByList();
        }
        Expr having = cur.matchWord("HAVING") ? exprParser.parseExpression() : null;
        Expr qualify = cur.matchWord("QUALIFY") ? exprParser.parseExpression() : null;
        List<OrderByItem> orderBy = cur.matchWords("ORDER", "BY") ? parseOrderByList() : List.of();
        Expr limit = parseOptionalLimitFetch();
        return new SelectQuery(
                ctes, distinct, items, null, fromSources, joins, lateralViews,
                whereExpr, null, groupBy, having, qualify, List.of(), orderBy, limit, null, null,
                SourceSpan.between(startSpan, cur.previous().span()));
    }

    public SelectQuery parseSelectQuery(List<CteDef> ctes, SourceSpan startSpan) {
        SqlToken selectTok = cur.expectWord("SELECT");
        if (startSpan == null) {
            startSpan = selectTok.span();
        }
        boolean distinct = parseSelectModifiers();

        // BigQuery SELECT AS STRUCT / SELECT AS VALUE
        if (cur.matchWords("AS", "STRUCT") || cur.matchWords("AS", "VALUE")) {
            // handled
        }

        OutputClause midOutput = null;
        List<SelectItem> selectItems = parseSelectItemList();

        // INTO clause before FROM (T-SQL SELECT ... INTO #tmp FROM ..., PL/SQL SELECT ... [BULK COLLECT] INTO v1, v2 FROM ...)
        IntoClause intoClause = parseOptionalIntoClause();

        // FROM & JOINs
        List<TableSource> fromSources = new ArrayList<>();
        List<JoinClause> joins = new ArrayList<>();
        if (cur.matchWord("FROM")) {
            parseFromAndJoins(fromSources, joins);
        }

        // INTO clause after FROM (MySQL SELECT ... FROM ... INTO OUTFILE / @var)
        if (intoClause == null) {
            intoClause = parseOptionalIntoClause();
        }

        // Hive / Spark / Databricks LATERAL VIEW [OUTER] EXPLODE(...)
        List<LateralViewClause> lateralViews = parseLateralViews();

        // ClickHouse PREWHERE
        Expr prewhere = null;
        if (cur.matchWord("PREWHERE")) {
            prewhere = exprParser.parseExpression();
        }

        // WHERE
        Expr whereExpr = null;
        if (cur.matchWord("WHERE")) {
            whereExpr = exprParser.parseExpression();
        }
        if (prewhere != null) {
            whereExpr = whereExpr != null
                    ? new Expr.BinaryExpr("AND", prewhere, whereExpr, SourceSpan.between(prewhere.span(), whereExpr.span()))
                    : prewhere;
        }

        // Oracle / Dameng / GaussDB / Snowflake hierarchical START WITH / CONNECT BY
        ConnectByClause connectBy = parseOptionalConnectBy();

        // GROUP BY / HAVING / QUALIFY / WINDOW (flexible clause ordering across Snowflake/BigQuery/Teradata/DuckDB)
        List<Expr> groupByExprs = List.of();
        Expr havingExpr = null;
        Expr qualifyExpr = null;
        List<NamedWindowDef> windowDefs = new ArrayList<>();

        boolean progress = true;
        while (progress && !cur.isAtEnd()) {
            progress = false;
            if (groupByExprs.isEmpty() && cur.matchWords("GROUP", "BY")) {
                groupByExprs = parseGroupByList();
                progress = true;
            }
            if (havingExpr == null && cur.matchWord("HAVING")) {
                havingExpr = exprParser.parseExpression();
                progress = true;
            }
            if (qualifyExpr == null && cur.matchWord("QUALIFY")) {
                qualifyExpr = exprParser.parseExpression();
                progress = true;
            }
            if (cur.matchWord("WINDOW")) {
                do {
                    SqlToken wName = cur.expectIdentifier("window name");
                    cur.expectWord("AS");
                    WindowSpec spec = exprParser.parseWindowSpecParens(wName.span());
                    windowDefs.add(new NamedWindowDef(wName.value(), spec, SourceSpan.between(wName.span(), spec.span())));
                } while (cur.matchSymbol(","));
                progress = true;
            }
            if (connectBy == null && (checkWordsStart("START", "WITH") || checkWordsStart("CONNECT", "BY"))) {
                connectBy = parseOptionalConnectBy();
                progress = true;
            }
        }

        // ORDER BY / SORT BY / CLUSTER BY / DISTRIBUTE BY
        List<OrderByItem> orderBy = new ArrayList<>();
        while (cur.matchWords("ORDER", "BY")
                || cur.matchWords("SORT", "BY")
                || cur.matchWords("CLUSTER", "BY")
                || cur.matchWords("DISTRIBUTE", "BY")) {
            cur.matchWord("SIBLINGS");
            if (cur.peek().isWord("BY")) {
                cur.advance();
            }
            orderBy.addAll(parseOrderByList());
        }

        // LIMIT / OFFSET / FETCH FIRST
        Expr limitExpr = null;
        Expr offsetExpr = null;
        for (int i = 0; i < 2; i++) {
            if (limitExpr == null) {
                limitExpr = parseOptionalLimitFetch();
            }
            if (offsetExpr == null && cur.matchWord("OFFSET")) {
                offsetExpr = exprParser.parseExpression();
                cur.matchAnyWord("ROW", "ROWS");
            }
        }

        // Trailing INTO (Snowflake / Teradata / Informix) or FOR UPDATE / SETTINGS / OPTION
        if (intoClause == null) {
            intoClause = parseOptionalIntoClause();
        }
        skipTrailingQueryModifiers();

        SourceSpan endSpan = cur.previous().span();
        return new SelectQuery(
                ctes,
                distinct,
                selectItems,
                intoClause,
                fromSources,
                joins,
                lateralViews,
                whereExpr,
                connectBy,
                groupByExprs,
                havingExpr,
                qualifyExpr,
                windowDefs,
                orderBy,
                limitExpr,
                offsetExpr,
                midOutput,
                SourceSpan.between(startSpan, endSpan));
    }

    private boolean parseSelectModifiers() {
        boolean distinct = false;
        while (!cur.isAtEnd()) {
            if (cur.matchAnyWord("DISTINCT", "DISTINCTROW", "UNIQUE")) {
                distinct = true;
                if (cur.matchWord("ON") && cur.checkSymbol("(")) {
                    cur.skipParenthesizedGroup();
                }
            } else if (cur.matchAnyWord("ALL", "SQL_CALC_FOUND_ROWS", "SQL_SMALL_RESULT", "SQL_BIG_RESULT", "STRAIGHT_JOIN")) {
                // MySQL / ANSI modifiers
            } else if (cur.matchWord("TOP")) {
                // T-SQL / Sybase / Vertica / Access: TOP n [PERCENT] [WITH TIES] or TOP (n)
                if (cur.checkSymbol("(")) {
                    cur.skipParenthesizedGroup();
                } else if (!cur.isAtEnd()) {
                    cur.advance();
                }
                cur.matchWord("PERCENT");
                if (cur.matchWords("WITH", "TIES")) {
                    // consumed
                }
            } else if (cur.matchWord("FIRST") || cur.matchWord("SKIP")) {
                // Informix: SELECT SKIP 5 FIRST 10 col FROM tbl
                if (cur.peek().type() == TokenType.NUMBER_LITERAL) {
                    cur.advance();
                }
            } else {
                break;
            }
        }
        return distinct;
    }

    public List<SelectItem> parseSelectItemList() {
        List<SelectItem> items = new ArrayList<>();
        if (cur.isAtEnd() || cur.checkAnyWord("FROM", "INTO", "WHERE", "GROUP", "UNION")) {
            return items;
        }
        do {
            if (cur.isAtEnd() || cur.checkAnyWord("FROM", "WHERE")) {
                // Tolerate BigQuery / DuckDB trailing comma before FROM: SELECT a, b, FROM tbl
                break;
            }
            items.add(parseSelectItem());
        } while (cur.matchSymbol(","));
        return items;
    }

    public SelectItem parseSelectItem() {
        SourceSpan itemStart = cur.peek().span();

        // SOQL child relationship subquery in SELECT list: (SELECT Id, Name FROM Contacts)
        // Handled naturally by ExpressionParser.parseParenthesizedExpr() -> SubqueryExpr("SCALAR")!

        // Check T-SQL prefix alias or variable assignment: SELECT alias = expr OR SELECT @var = expr
        if ((cur.peek().isIdentifierLike() || cur.peek().type() == TokenType.BIND_VARIABLE)
                && (cur.peek(1).isSymbol("=") || cur.peek(1).isSymbol(":="))
                && (cur.vendor() == EDbVendor.dbvmssql
                || cur.vendor() == EDbVendor.dbvazuresql
                || cur.vendor() == EDbVendor.dbvsybase
                || cur.vendor() == EDbVendor.dbvsybasease
                || cur.vendor() == EDbVendor.dbvsybaseiq
                || cur.peek().type() == TokenType.BIND_VARIABLE)) {
            SqlToken lhsTok = cur.advance();
            cur.advance(); // = or :=
            Expr rhs = exprParser.parseExpression();
            SourceSpan span = SourceSpan.between(itemStart, rhs.span());
            return new SelectItem(
                    rhs,
                    lhsTok.value(),
                    lhsTok.quoted(),
                    lhsTok.span(),
                    cur.extractText(itemStart, rhs.span()),
                    span);
        }

        Expr expr = exprParser.parseExpression();
        String alias = null;
        boolean aliasQuoted = false;
        SourceSpan aliasSpan = null;

        if (cur.matchWord("AS")) {
            if (cur.peek().isIdentifierLike() || cur.peek().type() == TokenType.STRING_LITERAL) {
                SqlToken aTok = cur.advance();
                alias = aTok.value();
                aliasQuoted = aTok.quoted() || aTok.type() == TokenType.STRING_LITERAL;
                aliasSpan = aTok.span();
            }
        } else if (canBeImplicitSelectAlias(cur.peek())) {
            SqlToken aTok = cur.advance();
            alias = aTok.value();
            aliasQuoted = aTok.quoted() || aTok.type() == TokenType.STRING_LITERAL;
            aliasSpan = aTok.span();
        }

        SourceSpan endSpan = aliasSpan != null ? aliasSpan : expr.span();
        String rawCode = cur.extractText(expr.span(), expr.span());
        return new SelectItem(expr, alias, aliasQuoted, aliasSpan, rawCode, SourceSpan.between(itemStart, endSpan));
    }

    private boolean canBeImplicitSelectAlias(SqlToken tok) {
        if (tok.isEof()) {
            return false;
        }
        if (tok.type() == TokenType.QUOTED_IDENTIFIER || tok.type() == TokenType.STRING_LITERAL) {
            return true;
        }
        if (tok.type() != TokenType.IDENTIFIER && tok.type() != TokenType.KEYWORD) {
            return false;
        }
        return !CLAUSE_BOUNDARY_KEYWORDS.contains(tok.upperValue())
                && !SqlDialectRules.isReservedKeyword(tok.upperValue(), cur.vendor());
    }

    private IntoClause parseOptionalIntoClause() {
        boolean bulkCollect = false;
        SourceSpan startSpan = cur.peek().span();
        if (cur.checkWord("BULK") && cur.peek(1).isWord("COLLECT") && cur.peek(2).isWord("INTO")) {
            cur.advance();
            cur.advance();
            bulkCollect = true;
        }
        if (!cur.matchWord("INTO")) {
            return null;
        }

        boolean temp = cur.matchAnyWord("TEMPORARY", "TEMP");
        boolean explicitTableKw = cur.matchWord("TABLE");

        if (cur.matchAnyWord("OUTFILE", "DUMPFILE")) {
            SqlToken uriTok = cur.advance();
            return new IntoClause(List.of(), List.of(), false, true, uriTok.value(),
                    SourceSpan.between(startSpan, uriTok.span()));
        }

        List<QualifiedName> intoTables = new ArrayList<>();
        List<Expr.VariableExpr> intoVars = new ArrayList<>();
        SourceSpan endSpan = cur.previous().span();

        do {
            SqlToken t = cur.peek();
            if (t.type() == TokenType.BIND_VARIABLE || bulkCollect) {
                if (t.type() == TokenType.BIND_VARIABLE) {
                    SqlToken vTok = cur.advance();
                    endSpan = vTok.span();
                    intoVars.add(new Expr.VariableExpr(vTok.rawText(), vTok.value(), "INTO_VAR", null, vTok.span()));
                } else {
                    QualifiedName qn = cur.parseQualifiedName();
                    endSpan = qn.span();
                    intoVars.add(new Expr.VariableExpr(
                            qn.rawDisplayName(), qn.fullDisplayName(), "INTO_VAR", null, qn.span()));
                }
            } else if (!explicitTableKw && !temp && intoTables.size() >= 1) {
                // Multiple comma-separated INTO targets without TABLE keyword in PL/SQL / PL/pgSQL: SELECT a, b INTO v1, v2 FROM t
                if (!intoVars.isEmpty()) {
                    QualifiedName qn = cur.parseQualifiedName();
                    endSpan = qn.span();
                    intoVars.add(new Expr.VariableExpr(
                            qn.rawDisplayName(), qn.fullDisplayName(), "INTO_VAR", null, qn.span()));
                } else {
                    // Convert first target to variable as well since SQL SELECT INTO table only allows 1 table!
                    QualifiedName first = intoTables.remove(0);
                    intoVars.add(new Expr.VariableExpr(
                            first.rawDisplayName(), first.fullDisplayName(), "INTO_VAR", null, first.span()));
                    QualifiedName second = cur.parseQualifiedName();
                    endSpan = second.span();
                    intoVars.add(new Expr.VariableExpr(
                            second.rawDisplayName(), second.fullDisplayName(), "INTO_VAR", null, second.span()));
                }
            } else {
                QualifiedName qn = cur.parseQualifiedName();
                endSpan = qn.span();
                intoTables.add(qn);
            }
        } while (cur.matchSymbol(","));

        return new IntoClause(intoTables, intoVars, temp, false, null, SourceSpan.between(startSpan, endSpan));
    }

    public void parseFromAndJoins(List<TableSource> fromSources, List<JoinClause> joins) {
        if (cur.isAtEnd()) {
            return;
        }
        fromSources.add(parseTableSource());

        while (!cur.isAtEnd()) {
            // Implicit comma join: FROM a, b
            if (cur.matchSymbol(",")) {
                SourceSpan jStart = cur.previous().span();
                TableSource right = parseTableSource();
                joins.add(new JoinClause("IMPLICIT", right, null, List.of(), null,
                        SourceSpan.between(jStart, right.span())));
                continue;
            }

            // Couchbase N1QL / BigQuery comma-less UNNEST in FROM: FROM bucket b UNNEST b.items AS i
            if (cur.checkWord("UNNEST") || (cur.checkWord("LEFT") && cur.peek(1).isWord("UNNEST"))) {
                SourceSpan jStart = cur.peek().span();
                String jType = cur.matchWord("LEFT") ? "LEFT" : "CROSS";
                TableSource unnestSrc = parseTableSource();
                joins.add(new JoinClause(jType, unnestSrc, null, List.of(), null,
                        SourceSpan.between(jStart, unnestSrc.span())));
                continue;
            }

            String joinType = matchJoinKeywords();
            if (joinType == null) {
                break;
            }
            SourceSpan jStart = cur.previous().span();
            TableSource right = parseTableSource();
            Expr onCond = null;
            String rawCond = null;
            List<String> usingCols = List.of();
            SourceSpan endSpan = right.span();

            if (cur.matchWord("ON")) {
                onCond = exprParser.parseExpression();
                rawCond = cur.extractText(onCond.span(), onCond.span());
                endSpan = onCond.span();
            } else if (cur.matchWord("USING")) {
                usingCols = parseIdentifierListInParens();
                endSpan = cur.previous().span();
            }
            joins.add(new JoinClause(joinType, right, onCond, usingCols, rawCond, SourceSpan.between(jStart, endSpan)));
        }
    }

    private String matchJoinKeywords() {
        if (cur.checkWord("LATERAL") && cur.peek(1).isWord("VIEW")) {
            return null; // Handled by parseLateralViews()
        }
        if (cur.matchWord("JOIN")) {
            return "INNER";
        }
        if (cur.matchWord("STRAIGHT_JOIN")) {
            return "INNER";
        }
        if (cur.matchWords("CROSS", "APPLY")) {
            return "CROSS APPLY";
        }
        if (cur.matchWords("OUTER", "APPLY")) {
            return "OUTER APPLY";
        }
        if (cur.matchWords("CROSS", "JOIN")) {
            return "CROSS";
        }
        boolean natural = cur.matchWord("NATURAL");
        // ClickHouse GLOBAL / ANY / ALL / ASOF modifiers
        cur.matchWord("GLOBAL");
        cur.matchAnyWord("ANY", "ALL", "ASOF");

        if (cur.matchWord("INNER")) {
            cur.matchWord("JOIN");
            return natural ? "NATURAL INNER" : "INNER";
        }
        if (cur.matchWord("LEFT")) {
            if (cur.matchWord("SEMI")) {
                cur.matchWord("JOIN");
                return "LEFT SEMI";
            }
            if (cur.matchWord("ANTI")) {
                cur.matchWord("JOIN");
                return "LEFT ANTI";
            }
            cur.matchWord("OUTER");
            cur.expectWord("JOIN");
            return natural ? "NATURAL LEFT" : "LEFT";
        }
        if (cur.matchWord("RIGHT")) {
            if (cur.matchWord("SEMI")) {
                cur.matchWord("JOIN");
                return "RIGHT SEMI";
            }
            if (cur.matchWord("ANTI")) {
                cur.matchWord("JOIN");
                return "RIGHT ANTI";
            }
            cur.matchWord("OUTER");
            cur.expectWord("JOIN");
            return natural ? "NATURAL RIGHT" : "RIGHT";
        }
        if (cur.matchWord("FULL")) {
            cur.matchWord("OUTER");
            cur.expectWord("JOIN");
            return natural ? "NATURAL FULL" : "FULL";
        }
        if (cur.matchWord("SEMI")) {
            cur.matchWord("JOIN");
            return "LEFT SEMI";
        }
        if (cur.matchWord("ANTI")) {
            cur.matchWord("JOIN");
            return "LEFT ANTI";
        }
        if (natural && cur.matchWord("JOIN")) {
            return "NATURAL";
        }
        return null;
    }

    public TableSource parseTableSource() {
        SourceSpan startSpan = cur.peek().span();
        boolean lateral = cur.matchWord("LATERAL");

        // 1. Subquery or parenthesized join: [LATERAL] (SELECT ... | t1 JOIN t2)
        if (cur.checkSymbol("(")) {
            cur.expectSymbol("(");
            if (cur.checkAnyWord("SELECT", "WITH", "VALUES", "FROM")) {
                QueryExpr sub = parseQuery();
                SqlToken close = cur.expectSymbol(")");
                AliasInfo alias = parseOptionalTableAlias();
                PivotSpec pivot = parseOptionalPivot();
                UnpivotSpec unpivot = pivot == null ? parseOptionalUnpivot() : null;
                if (alias.name == null) {
                    AliasInfo postAlias = parseOptionalTableAlias();
                    if (postAlias.name != null) {
                        alias = postAlias;
                    }
                }
                SourceSpan endSpan = unpivot != null ? unpivot.span()
                        : (pivot != null ? pivot.span() : (alias.span != null ? alias.span : close.span()));
                return new SubqueryTableSource(
                        sub, lateral, alias.name, alias.quoted, alias.span, alias.colAliases,
                        pivot, unpivot, SourceSpan.between(startSpan, endSpan));
            } else {
                TableSource innerLeft = parseTableSource();
                List<JoinClause> innerJoins = new ArrayList<>();
                while (!cur.checkSymbol(")") && !cur.isAtEnd()) {
                    if (cur.matchSymbol(",")) {
                        TableSource r = parseTableSource();
                        innerJoins.add(new JoinClause("IMPLICIT", r, null, List.of(), null, r.span()));
                        continue;
                    }
                    String jt = matchJoinKeywords();
                    if (jt == null) {
                        break;
                    }
                    TableSource r = parseTableSource();
                    Expr on = cur.matchWord("ON") ? exprParser.parseExpression() : null;
                    List<String> using = (on == null && cur.matchWord("USING")) ? parseIdentifierListInParens() : List.of();
                    innerJoins.add(new JoinClause(jt, r, on, using,
                            on != null ? cur.extractText(on.span(), on.span()) : null, r.span()));
                }
                SqlToken close = cur.expectSymbol(")");
                AliasInfo alias = parseOptionalTableAlias();
                return new ParenthesizedJoinSource(innerLeft, innerJoins, alias.name,
                        SourceSpan.between(startSpan, alias.span != null ? alias.span : close.span()));
            }
        }

        // 2. UNNEST(...) [AS alias] [WITH OFFSET [AS off_alias]]
        if (cur.checkWord("UNNEST")) {
            SqlToken kw = cur.advance();
            List<Expr> args = new ArrayList<>();
            if (cur.matchSymbol("(")) {
                if (!cur.checkSymbol(")")) {
                    args = exprParser.parseExpressionList();
                }
                cur.expectSymbol(")");
            } else {
                args.add(exprParser.parseExpression());
            }
            AliasInfo alias = parseOptionalTableAlias();
            boolean withOffset = false;
            String offsetAlias = null;
            if (cur.matchWords("WITH", "OFFSET") || cur.matchWords("WITH", "ORDINALITY")) {
                withOffset = true;
                cur.matchWord("AS");
                if (canBeImplicitTableAlias(cur.peek())) {
                    offsetAlias = cur.advance().value();
                }
            }
            if (alias.name == null) {
                AliasInfo postAlias = parseOptionalTableAlias();
                if (postAlias.name != null) {
                    alias = postAlias;
                }
            }
            QualifiedName fnName = QualifiedName.single(kw.rawText(), kw.value(), false, false, kw.span());
            return new TableFunctionSource(
                    "UNNEST", fnName, args, args.isEmpty() ? null : args.get(0), lateral,
                    withOffset, offsetAlias, alias.name, alias.colAliases, List.of(),
                    SourceSpan.between(startSpan, cur.previous().span()));
        }

        // 3. Snowflake [LATERAL] FLATTEN(INPUT => expr, ...) [alias]
        if (cur.checkWord("FLATTEN") && cur.peek(1).isSymbol("(")) {
            SqlToken kw = cur.advance();
            QualifiedName fnName = QualifiedName.single(kw.rawText(), kw.value(), false, false, kw.span());
            Expr.FuncCallExpr call = exprParser.parseFunctionCall(fnName);
            Expr inputExpr = extractNamedOrFirstArg(call.args(), "INPUT");
            AliasInfo alias = parseOptionalTableAlias();
            return new TableFunctionSource(
                    "FLATTEN", fnName, call.args(), inputExpr, lateral,
                    false, null, alias.name, alias.colAliases, List.of(),
                    SourceSpan.between(startSpan, alias.span != null ? alias.span : call.span()));
        }

        // 4. Oracle / Snowflake / DB2 TABLE(fn(...)) or TABLE(FLATTEN(...)) or TABLE(@stage)
        if (cur.checkWord("TABLE") && cur.peek(1).isSymbol("(")) {
            cur.advance(); // TABLE
            cur.expectSymbol("(");
            if (cur.checkWord("FLATTEN") && cur.peek(1).isSymbol("(")) {
                SqlToken kw = cur.advance();
                QualifiedName fnName = QualifiedName.single(kw.rawText(), kw.value(), false, false, kw.span());
                Expr.FuncCallExpr call = exprParser.parseFunctionCall(fnName);
                cur.expectSymbol(")");
                AliasInfo alias = parseOptionalTableAlias();
                return new TableFunctionSource(
                        "FLATTEN", fnName, call.args(), extractNamedOrFirstArg(call.args(), "INPUT"),
                        lateral, false, null, alias.name, alias.colAliases, List.of(),
                        SourceSpan.between(startSpan, alias.span != null ? alias.span : cur.previous().span()));
            }
            Expr innerExpr = exprParser.parseExpression();
            SqlToken close = cur.expectSymbol(")");
            AliasInfo alias = parseOptionalTableAlias();
            if (innerExpr instanceof Expr.FuncCallExpr fnCall) {
                return new TableFunctionSource(
                        "FUNCTION", fnCall.name(), fnCall.args(),
                        fnCall.args().isEmpty() ? null : fnCall.args().get(0),
                        lateral, false, null, alias.name, alias.colAliases, List.of(),
                        SourceSpan.between(startSpan, alias.span != null ? alias.span : close.span()));
            }
            QualifiedName wrapperName = QualifiedName.single("TABLE", "TABLE", false, false, startSpan);
            return new TableFunctionSource(
                    "FUNCTION", wrapperName, List.of(innerExpr), innerExpr,
                    lateral, false, null, alias.name, alias.colAliases, List.of(),
                    SourceSpan.between(startSpan, alias.span != null ? alias.span : close.span()));
        }

        // 5. External URI or string path directly in FROM: FROM 's3://bucket/data.parquet'
        if (cur.peek().type() == TokenType.STRING_LITERAL) {
            SqlToken uriTok = cur.advance();
            QualifiedName uriName = QualifiedName.ofUri(uriTok.value(), uriTok.span());
            AliasInfo alias = parseOptionalTableAlias();
            return new NamedTableSource(
                    uriName, alias.name, alias.quoted, alias.span, alias.colAliases,
                    null, null, SourceSpan.between(startSpan, alias.span != null ? alias.span : uriTok.span()));
        }

        // 6. Standard Qualified Table / View / CTE / Stage OR Table-Valued Function fn(...)
        QualifiedName qName = cur.parseQualifiedName();
        if (cur.checkSymbol("(") && !isTablePartitionOrSampleClause()) {
            Expr.FuncCallExpr fnCall = exprParser.parseFunctionCall(qName);
            if (cur.matchWords("WITH", "ORDINALITY")) {
                // Postgres TVF WITH ORDINALITY
            }
            AliasInfo alias = parseOptionalTableAlias();
            List<ColumnDef> explicitCols = parseOptionalOpenJsonWithSchema();
            return new TableFunctionSource(
                    "FUNCTION", qName, fnCall.args(),
                    fnCall.args().isEmpty() ? null : fnCall.args().get(0),
                    lateral, false, null, alias.name, alias.colAliases, explicitCols,
                    SourceSpan.between(startSpan, cur.previous().span()));
        }

        skipOptionalPreAliasTableModifiers();
        AliasInfo alias = parseOptionalTableAlias();
        skipOptionalPostAliasTableModifiers();
        PivotSpec pivot = parseOptionalPivot();
        UnpivotSpec unpivot = pivot == null ? parseOptionalUnpivot() : null;
        if (alias.name == null && (pivot != null || unpivot != null)) {
            AliasInfo postPivotAlias = parseOptionalTableAlias();
            if (postPivotAlias.name != null) {
                alias = postPivotAlias;
            }
        }

        SourceSpan endSpan = unpivot != null ? unpivot.span()
                : (pivot != null ? pivot.span() : (alias.span != null ? alias.span : qName.span()));
        return new NamedTableSource(
                qName, alias.name, alias.quoted, alias.span, alias.colAliases,
                pivot, unpivot, SourceSpan.between(startSpan, endSpan));
    }

    private boolean isTablePartitionOrSampleClause() {
        return false; // Bare parens immediately after table name without PARTITION/TABLESAMPLE keyword is a TVF call
    }

    private void skipOptionalPreAliasTableModifiers() {
        while (!cur.isAtEnd()) {
            if (cur.matchWord("PARTITION") && cur.checkSymbol("(")) {
                cur.skipParenthesizedGroup();
            } else if (cur.matchAnyWord("TABLESAMPLE", "SAMPLE")) {
                cur.matchAnyWord("BERNOULLI", "SYSTEM", "BLOCK", "ROW");
                if (cur.checkSymbol("(")) {
                    cur.skipParenthesizedGroup();
                }
                if (cur.matchAnyWord("SEED", "REPEATABLE") && cur.checkSymbol("(")) {
                    cur.skipParenthesizedGroup();
                }
            } else if (cur.matchWords("FOR", "SYSTEM_TIME") || cur.matchWords("FOR", "BUSINESS_TIME")) {
                // Temporal table: FOR SYSTEM_TIME AS OF ...
                if (cur.matchWords("AS", "OF")) {
                    exprParser.parseExpression();
                } else if (cur.matchWord("ALL")) {
                    // FOR SYSTEM_TIME ALL
                }
            } else if (cur.checkWord("AT") && cur.peek(1).isSymbol("(")) {
                // Snowflake time travel AT (TIMESTAMP => ...)
                cur.advance();
                cur.skipParenthesizedGroup();
            } else if (cur.checkWord("BEFORE") && cur.peek(1).isSymbol("(")) {
                cur.advance();
                cur.skipParenthesizedGroup();
            } else if (cur.checkWord("CHANGES") && cur.peek(1).isSymbol("(")) {
                cur.advance();
                cur.skipParenthesizedGroup();
            } else if (cur.matchWord("FINAL")) {
                // ClickHouse FINAL
            } else {
                break;
            }
        }
    }

    private void skipOptionalPostAliasTableModifiers() {
        while (!cur.isAtEnd()) {
            // T-SQL table hints: WITH (NOLOCK, INDEX(...)) — note: do NOT consume CTE WITH!
            if (cur.checkWord("WITH") && cur.peek(1).isSymbol("(")) {
                cur.advance();
                cur.skipParenthesizedGroup();
            } else if (cur.matchAnyWord("USE", "FORCE", "IGNORE") && cur.matchAnyWord("INDEX", "KEY", "KEYS")) {
                // MySQL USE INDEX (...) or Couchbase USE KEYS [...]
                if (cur.matchWords("FOR", "JOIN") || cur.matchWords("FOR", "ORDER") || cur.matchWords("FOR", "GROUP")) {
                    cur.matchWord("BY");
                }
                if (cur.checkSymbol("(")) {
                    cur.skipParenthesizedGroup();
                } else if (!cur.isAtEnd()) {
                    exprParser.parseExpression();
                }
            } else if (cur.matchAnyWord("TABLESAMPLE", "SAMPLE")) {
                cur.matchAnyWord("BERNOULLI", "SYSTEM", "BLOCK");
                if (cur.checkSymbol("(")) {
                    cur.skipParenthesizedGroup();
                }
            } else {
                break;
            }
        }
    }

    private List<ColumnDef> parseOptionalOpenJsonWithSchema() {
        // T-SQL OPENJSON(...) WITH (id INT '$.id', name NVARCHAR(100) '$.name')
        if (!cur.checkWord("WITH") || !cur.peek(1).isSymbol("(")) {
            return List.of();
        }
        cur.advance(); // WITH
        cur.expectSymbol("(");
        List<ColumnDef> cols = new ArrayList<>();
        if (!cur.checkSymbol(")")) {
            do {
                SqlToken cName = cur.expectIdentifier("column");
                String dtype = cur.parseDataType();
                if (cur.peek().type() == TokenType.STRING_LITERAL) {
                    cur.advance(); // JSON path
                }
                if (cur.matchWords("AS", "JSON")) {
                    // OPENJSON AS JSON
                }
                cols.add(new ColumnDef(
                        cName.value(), cName.quoted(), dtype, true, false, false, false,
                        false, false, false, null, null, null, null, null, null, cName.span()));
            } while (cur.matchSymbol(","));
        }
        cur.expectSymbol(")");
        return cols;
    }

    private record AliasInfo(String name, boolean quoted, SourceSpan span, List<String> colAliases) {
    }

    private AliasInfo parseOptionalTableAlias() {
        String alias = null;
        boolean quoted = false;
        SourceSpan aliasSpan = null;
        List<String> colAliases = List.of();

        if (cur.matchWord("AS")) {
            if (cur.peek().isIdentifierLike()) {
                SqlToken aTok = cur.advance();
                alias = aTok.value();
                quoted = aTok.quoted();
                aliasSpan = aTok.span();
            }
        } else if (canBeImplicitTableAlias(cur.peek())) {
            SqlToken aTok = cur.advance();
            alias = aTok.value();
            quoted = aTok.quoted();
            aliasSpan = aTok.span();
        }

        if (alias != null && cur.checkSymbol("(")) {
            colAliases = parseColumnAliasListInParens();
            aliasSpan = cur.previous().span();
        }
        return new AliasInfo(alias, quoted, aliasSpan, colAliases);
    }

    private List<String> parseColumnAliasListInParens() {
        cur.expectSymbol("(");
        List<String> names = new ArrayList<>();
        if (!cur.checkSymbol(")")) {
            do {
                SqlToken t = cur.expectIdentifier("column alias");
                names.add(t.value());
                // Postgres TVF alias can include column types: AS t(a INT, b TEXT)
                if (!cur.checkSymbol(",") && !cur.checkSymbol(")")) {
                    cur.parseDataType();
                }
            } while (cur.matchSymbol(","));
        }
        cur.expectSymbol(")");
        return names;
    }

    private boolean canBeImplicitTableAlias(SqlToken tok) {
        if (tok.isEof()) {
            return false;
        }
        if (tok.type() == TokenType.QUOTED_IDENTIFIER) {
            return true;
        }
        if (tok.type() != TokenType.IDENTIFIER && tok.type() != TokenType.KEYWORD) {
            return false;
        }
        String u = tok.upperValue();
        if (CLAUSE_BOUNDARY_KEYWORDS.contains(u) || SqlDialectRules.isReservedKeyword(u, cur.vendor())) {
            return false;
        }
        return !Set.of("UNNEST", "APPLY", "OUTER", "CROSS", "SAMPLE", "TABLESAMPLE", "FINAL",
                "MATCHED", "SET", "VALUES", "PARTITION", "WITH", "USE", "AT", "BEFORE", "CHANGES").contains(u);
    }

    private PivotSpec parseOptionalPivot() {
        if (!cur.matchWord("PIVOT")) {
            return null;
        }
        SourceSpan startSpan = cur.previous().span();
        cur.matchWord("XML");
        cur.expectSymbol("(");

        List<PivotAggItem> aggs = new ArrayList<>();
        do {
            Expr aggExpr = exprParser.parseExpression();
            String alias = null;
            if (cur.matchWord("AS") || canBeImplicitSelectAlias(cur.peek())) {
                alias = cur.advance().value();
            }
            aggs.add(new PivotAggItem(aggExpr, alias, aggExpr.span()));
        } while (cur.matchSymbol(",") && !cur.checkWord("FOR"));

        List<String> forCols = new ArrayList<>();
        if (cur.matchWord("FOR")) {
            if (cur.checkSymbol("(")) {
                forCols = parseIdentifierListInParens();
            } else {
                forCols.add(cur.expectIdentifier("PIVOT FOR column").value());
            }
        }

        List<PivotValueItem> inVals = new ArrayList<>();
        if (cur.matchWord("IN") && cur.matchSymbol("(")) {
            if (cur.checkAnyWord("SELECT", "ANY")) {
                // Dynamic PIVOT IN (SELECT ... | ANY)
                while (!cur.isAtEnd() && !cur.checkSymbol(")")) {
                    if (cur.checkSymbol("(")) {
                        cur.skipParenthesizedGroup();
                    } else {
                        cur.advance();
                    }
                }
            } else if (!cur.checkSymbol(")")) {
                do {
                    Expr vExpr = exprParser.parseExpression();
                    String vAlias = null;
                    if (cur.matchWord("AS") || canBeImplicitSelectAlias(cur.peek())) {
                        vAlias = cur.advance().value();
                    }
                    inVals.add(new PivotValueItem(vExpr, vAlias, vExpr.span()));
                } while (cur.matchSymbol(","));
            }
            cur.expectSymbol(")");
        }
        SqlToken close = cur.expectSymbol(")");
        AliasInfo alias = parseOptionalTableAlias();
        return new PivotSpec(aggs, forCols, inVals, alias.name,
                SourceSpan.between(startSpan, alias.span != null ? alias.span : close.span()));
    }

    private UnpivotSpec parseOptionalUnpivot() {
        if (!cur.matchWord("UNPIVOT")) {
            return null;
        }
        SourceSpan startSpan = cur.previous().span();
        if (cur.matchAnyWord("INCLUDE", "EXCLUDE")) {
            cur.matchWord("NULLS");
        }
        cur.expectSymbol("(");
        List<String> valCols = cur.checkSymbol("(")
                ? parseIdentifierListInParens()
                : List.of(cur.expectIdentifier("UNPIVOT value column").value());

        List<String> nameCols = List.of();
        if (cur.matchWord("FOR")) {
            nameCols = cur.checkSymbol("(")
                    ? parseIdentifierListInParens()
                    : List.of(cur.expectIdentifier("UNPIVOT name column").value());
        }

        List<String> inCols = new ArrayList<>();
        if (cur.matchWord("IN") && cur.matchSymbol("(")) {
            if (!cur.checkSymbol(")")) {
                do {
                    if (cur.checkSymbol("(")) {
                        inCols.addAll(parseIdentifierListInParens());
                    } else {
                        inCols.add(cur.expectIdentifier("UNPIVOT source column").value());
                    }
                    if (cur.matchWord("AS")) {
                        exprParser.parseExpression();
                    }
                } while (cur.matchSymbol(","));
            }
            cur.expectSymbol(")");
        }
        SqlToken close = cur.expectSymbol(")");
        AliasInfo alias = parseOptionalTableAlias();
        return new UnpivotSpec(valCols, nameCols, inCols, alias.name,
                SourceSpan.between(startSpan, alias.span != null ? alias.span : close.span()));
    }

    private List<LateralViewClause> parseLateralViews() {
        List<LateralViewClause> list = new ArrayList<>();
        while (cur.checkWord("LATERAL") && cur.peek(1).isWord("VIEW")) {
            SourceSpan start = cur.advance().span(); // LATERAL
            cur.advance(); // VIEW
            boolean outer = cur.matchWord("OUTER");
            QualifiedName fnName = cur.parseQualifiedName();
            List<Expr> args = new ArrayList<>();
            if (cur.matchSymbol("(")) {
                if (!cur.checkSymbol(")")) {
                    args = exprParser.parseExpressionList();
                }
                cur.expectSymbol(")");
            }
            String tableAlias = null;
            if (!cur.checkWord("AS") && cur.peek().isIdentifierLike()) {
                tableAlias = cur.advance().value();
            }
            List<String> colAliases = new ArrayList<>();
            if (cur.matchWord("AS")) {
                do {
                    colAliases.add(cur.expectIdentifier("lateral view column alias").value());
                } while (cur.matchSymbol(","));
            }
            list.add(new LateralViewClause(outer, fnName, args, tableAlias, colAliases,
                    SourceSpan.between(start, cur.previous().span())));
        }
        return list;
    }

    private ConnectByClause parseOptionalConnectBy() {
        Expr startWith = null;
        Expr connectBy = null;
        boolean nocycle = false;
        SourceSpan firstSpan = null;

        for (int i = 0; i < 2; i++) {
            if (startWith == null && cur.matchWords("START", "WITH")) {
                if (firstSpan == null) {
                    firstSpan = cur.previous().span();
                }
                startWith = exprParser.parseExpression();
            }
            if (connectBy == null && cur.matchWords("CONNECT", "BY")) {
                if (firstSpan == null) {
                    firstSpan = cur.previous().span();
                }
                nocycle = cur.matchWord("NOCYCLE");
                connectBy = exprParser.parseExpression();
            }
        }
        if (startWith == null && connectBy == null) {
            return null;
        }
        return new ConnectByClause(startWith, connectBy, nocycle,
                SourceSpan.between(firstSpan, cur.previous().span()));
    }

    private List<Expr> parseGroupByList() {
        List<Expr> list = new ArrayList<>();
        cur.matchAnyWord("ALL", "DISTINCT");
        if (cur.isAtEnd() || cur.checkAnyWord("HAVING", "ORDER", "LIMIT", "WINDOW", "QUALIFY", "UNION")) {
            return list;
        }
        do {
            if (cur.matchAnyWord("ROLLUP", "CUBE") && cur.checkSymbol("(")) {
                cur.expectSymbol("(");
                if (!cur.checkSymbol(")")) {
                    do {
                        if (cur.matchSymbol("(")) {
                            if (!cur.checkSymbol(")")) {
                                list.addAll(exprParser.parseExpressionList());
                            }
                            cur.expectSymbol(")");
                        } else {
                            list.add(exprParser.parseExpression());
                        }
                    } while (cur.matchSymbol(","));
                }
                cur.expectSymbol(")");
            } else if (cur.matchWords("GROUPING", "SETS") && cur.checkSymbol("(")) {
                cur.expectSymbol("(");
                if (!cur.checkSymbol(")")) {
                    do {
                        if (cur.matchSymbol("(")) {
                            if (!cur.checkSymbol(")")) {
                                list.addAll(exprParser.parseExpressionList());
                            }
                            cur.expectSymbol(")");
                        } else {
                            list.add(exprParser.parseExpression());
                        }
                    } while (cur.matchSymbol(","));
                }
                cur.expectSymbol(")");
            } else {
                list.add(exprParser.parseExpression());
            }
        } while (cur.matchSymbol(","));

        if (cur.matchWords("WITH", "ROLLUP") || cur.matchWords("WITH", "CUBE") || cur.matchWords("WITH", "TOTALS")) {
            // MySQL / T-SQL / ClickHouse modifier
        }
        return list;
    }

    public List<OrderByItem> parseOrderByList() {
        List<OrderByItem> list = new ArrayList<>();
        do {
            Expr e = exprParser.parseExpression();
            String dir = null;
            if (cur.matchAnyWord("ASC", "DESC")) {
                dir = cur.previous().upperValue();
            } else if (cur.matchWord("USING") && !cur.isAtEnd()) {
                dir = "USING " + cur.advance().rawText();
            }
            String nulls = null;
            if (cur.matchWords("NULLS", "FIRST")) {
                nulls = "NULLS FIRST";
            } else if (cur.matchWords("NULLS", "LAST")) {
                nulls = "NULLS LAST";
            }
            list.add(new OrderByItem(e, dir, nulls, SourceSpan.between(e.span(), cur.previous().span())));
        } while (cur.matchSymbol(","));
        return list;
    }

    private Expr parseOptionalLimitFetch() {
        if (cur.matchWord("LIMIT")) {
            if (cur.matchWord("ALL")) {
                return null;
            }
            Expr first = exprParser.parseExpression();
            if (cur.matchSymbol(",")) {
                // MySQL LIMIT offset, count
                return exprParser.parseExpression();
            }
            if (cur.matchWord("BY")) {
                // ClickHouse LIMIT n BY expr
                exprParser.parseExpressionList();
            }
            return first;
        }
        if (cur.matchWord("FETCH") && cur.matchAnyWord("FIRST", "NEXT")) {
            Expr count = null;
            if (!cur.checkAnyWord("ROW", "ROWS")) {
                count = exprParser.parseExpression();
                cur.matchWord("PERCENT");
            }
            cur.matchAnyWord("ROW", "ROWS");
            if (cur.matchWord("ONLY")) {
                // ANSI FETCH FIRST n ROWS ONLY
            } else if (cur.matchWords("WITH", "TIES")) {
                // ANSI WITH TIES
            }
            return count;
        }
        return null;
    }

    public OutputClause parseOptionalOutputOrReturning() {
        if (cur.matchWord("RETURNING")) {
            SourceSpan start = cur.previous().span();
            List<SelectItem> items = parseSelectItemList();
            QualifiedName intoTarget = null;
            if (cur.matchWord("INTO")) {
                intoTarget = cur.parseQualifiedName();
            }
            return new OutputClause("RETURNING", items, intoTarget, List.of(),
                    SourceSpan.between(start, cur.previous().span()));
        }
        if (cur.matchWord("OUTPUT")) {
            SourceSpan start = cur.previous().span();
            List<SelectItem> items = parseSelectItemList();
            QualifiedName intoTarget = null;
            List<String> intoCols = List.of();
            if (cur.matchWord("INTO")) {
                if (cur.peek().type() == TokenType.BIND_VARIABLE) {
                    SqlToken v = cur.advance();
                    intoTarget = QualifiedName.single(v.rawText(), v.value(), false, false, v.span());
                } else {
                    intoTarget = cur.parseQualifiedName();
                }
                if (cur.checkSymbol("(")) {
                    intoCols = parseIdentifierListInParens();
                }
            }
            return new OutputClause("OUTPUT", items, intoTarget, intoCols,
                    SourceSpan.between(start, cur.previous().span()));
        }
        return null;
    }

    public List<String> parseIdentifierListInParens() {
        cur.expectSymbol("(");
        List<String> names = new ArrayList<>();
        if (!cur.checkSymbol(")")) {
            do {
                names.add(cur.expectIdentifier("column").value());
            } while (cur.matchSymbol(","));
        }
        cur.expectSymbol(")");
        return names;
    }

    private void skipTrailingQueryModifiers() {
        while (!cur.isAtEnd()) {
            if (cur.matchWord("FOR") && cur.matchAnyWord("UPDATE", "SHARE", "KEY", "NO", "XML", "JSON")) {
                while (!cur.isAtEnd()
                        && !cur.checkSymbol(")")
                        && !cur.checkSymbol(";")
                        && !cur.checkAnyWord("UNION", "INTERSECT", "EXCEPT", "MINUS", "RETURNING")) {
                    if (cur.checkSymbol("(")) {
                        cur.skipParenthesizedGroup();
                    } else {
                        cur.advance();
                    }
                }
            } else if (cur.matchWords("LOCK", "IN") || cur.matchWord("SETTINGS") || cur.matchWord("OPTION")) {
                while (!cur.isAtEnd()
                        && !cur.checkSymbol(")")
                        && !cur.checkSymbol(";")
                        && !cur.checkAnyWord("UNION", "INTERSECT", "EXCEPT", "MINUS")) {
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
    }

    private static Expr extractNamedOrFirstArg(List<Expr> args, String paramName) {
        if (args == null || args.isEmpty()) {
            return null;
        }
        for (Expr arg : args) {
            if (arg instanceof Expr.BinaryExpr bin
                    && "=>".equals(bin.operator())
                    && bin.left() instanceof Expr.LiteralExpr lit
                    && paramName.equalsIgnoreCase(lit.value())) {
                return bin.right();
            }
        }
        return args.get(0);
    }

    private boolean checkWordsStart(String w1, String w2) {
        return cur.peek().isWord(w1) && cur.peek(1).isWord(w2);
    }
}
