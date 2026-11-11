package io.sqlflow.lineage.parser;

import io.sqlflow.lineage.ast.Expr;
import io.sqlflow.lineage.ast.Expr.WhenClause;
import io.sqlflow.lineage.ast.QualifiedName;
import io.sqlflow.lineage.ast.QueryAst.OrderByItem;
import io.sqlflow.lineage.ast.QueryAst.QueryExpr;
import io.sqlflow.lineage.ast.QueryAst.SelectItem;
import io.sqlflow.lineage.ast.QueryAst.WindowSpec;
import io.sqlflow.lineage.lexer.SourceSpan;
import io.sqlflow.lineage.lexer.SqlDialectRules;
import io.sqlflow.lineage.lexer.SqlToken;
import io.sqlflow.lineage.lexer.TokenType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Multi-dialect precedence-climbing SQL expression parser supporting window functions, semi-structured
 * paths ({@code col:field[0]::TYPE}, {@code col->>'k'}, {@code struct.field}), subqueries, {@code CASE},
 * {@code CAST}/{@code CONVERT}, sequences, Oracle {@code (+)}, and BigQuery/Snowflake {@code * EXCEPT/EXCLUDE/REPLACE}.
 */
public final class ExpressionParser {

    private final ParserCursor cur;
    private QueryParser queryParser;

    public ExpressionParser(ParserCursor cur) {
        this.cur = cur;
    }

    public void setQueryParser(QueryParser queryParser) {
        this.queryParser = queryParser;
    }

    public Expr parseExpression() {
        return parseOr();
    }

    private Expr parseOr() {
        Expr left = parseAnd();
        while (cur.matchAnyWord("OR", "XOR")) {
            String op = cur.previous().upperValue();
            Expr right = parseAnd();
            left = new Expr.BinaryExpr(op, left, right, SourceSpan.between(left.span(), right.span()));
        }
        return left;
    }

    private Expr parseAnd() {
        Expr left = parseNot();
        while (cur.matchWord("AND") || cur.matchSymbol("&&")) {
            String op = cur.previous().isSymbol("&&") ? "AND" : cur.previous().upperValue();
            Expr right = parseNot();
            left = new Expr.BinaryExpr(op, left, right, SourceSpan.between(left.span(), right.span()));
        }
        return left;
    }

    private Expr parseNot() {
        if (cur.checkWord("NOT")) {
            SqlToken notTok = cur.advance();
            if (cur.matchWord("EXISTS") && cur.checkSymbol("(")) {
                cur.expectSymbol("(");
                QueryExpr sub = queryParser.parseQuery();
                SqlToken close = cur.expectSymbol(")");
                return new Expr.SubqueryExpr("NOT_EXISTS", null, sub, SourceSpan.between(notTok.span(), close.span()));
            }
            Expr operand = parseNot();
            return new Expr.UnaryExpr("NOT", operand, SourceSpan.between(notTok.span(), operand.span()));
        }
        return parseComparison();
    }

    private Expr parseComparison() {
        Expr left = parseAdditiveOrConcat();

        while (!cur.isAtEnd()) {
            // IS [NOT] NULL / TRUE / FALSE / UNKNOWN / DISTINCT FROM
            if (cur.matchWord("IS")) {
                boolean not = cur.matchWord("NOT");
                if (cur.matchWords("DISTINCT", "FROM")) {
                    Expr right = parseAdditiveOrConcat();
                    String op = not ? "IS NOT DISTINCT FROM" : "IS DISTINCT FROM";
                    left = new Expr.BinaryExpr(op, left, right, SourceSpan.between(left.span(), right.span()));
                } else if (cur.matchAnyWord("NULL", "TRUE", "FALSE", "UNKNOWN", "NAN", "INFINITE", "EMPTY")) {
                    SqlToken valTok = cur.previous();
                    String op = (not ? "IS NOT " : "IS ") + valTok.upperValue();
                    left = new Expr.UnaryExpr(op, left, SourceSpan.between(left.span(), valTok.span()));
                } else {
                    Expr right = parseAdditiveOrConcat();
                    left = new Expr.BinaryExpr(not ? "IS NOT" : "IS", left, right, SourceSpan.between(left.span(), right.span()));
                }
                continue;
            }

            // [NOT] IN / BETWEEN / LIKE / ILIKE / RLIKE / SIMILAR TO / REGEXP / MEMBER OF
            boolean negated = false;
            int savedPos = cur.position();
            if (cur.matchWord("NOT")) {
                negated = true;
            }

            if (cur.matchWord("IN")) {
                if (cur.matchSymbol("(")) {
                    if (isSubqueryStart()) {
                        QueryExpr sub = queryParser.parseQuery();
                        SqlToken close = cur.expectSymbol(")");
                        left = new Expr.SubqueryExpr(
                                negated ? "NOT_IN" : "IN",
                                left,
                                sub,
                                SourceSpan.between(left.span(), close.span()));
                    } else {
                        List<Expr> items = new ArrayList<>();
                        if (!cur.checkSymbol(")")) {
                            do {
                                items.add(parseExpression());
                            } while (cur.matchSymbol(","));
                        }
                        SqlToken close = cur.expectSymbol(")");
                        left = new Expr.InListExpr(left, negated, items, SourceSpan.between(left.span(), close.span()));
                    }
                } else {
                    // UNNEST / variable after IN: x IN UNNEST(arr)
                    Expr rhs = parseAdditiveOrConcat();
                    left = new Expr.BinaryExpr(negated ? "NOT IN" : "IN", left, rhs, SourceSpan.between(left.span(), rhs.span()));
                }
                continue;
            }

            if (cur.matchWord("BETWEEN")) {
                Expr low = parseAdditiveOrConcat();
                cur.expectWord("AND");
                Expr high = parseAdditiveOrConcat();
                left = new Expr.BetweenExpr(left, negated, low, high, SourceSpan.between(left.span(), high.span()));
                continue;
            }

            if (cur.matchAnyWord("LIKE", "ILIKE", "RLIKE", "REGEXP")) {
                String op = (negated ? "NOT " : "") + cur.previous().upperValue();
                if (cur.matchAnyWord("ANY", "ALL", "SOME") && cur.matchSymbol("(")) {
                    List<Expr> patterns = parseExpressionList();
                    SqlToken close = cur.expectSymbol(")");
                    left = new Expr.InListExpr(left, negated, patterns, SourceSpan.between(left.span(), close.span()));
                } else {
                    Expr pattern = parseAdditiveOrConcat();
                    if (cur.matchWord("ESCAPE")) {
                        Expr esc = parseAdditiveOrConcat();
                        pattern = new Expr.BinaryExpr("ESCAPE", pattern, esc, SourceSpan.between(pattern.span(), esc.span()));
                    }
                    left = new Expr.BinaryExpr(op, left, pattern, SourceSpan.between(left.span(), pattern.span()));
                }
                continue;
            }

            if (cur.matchWords("SIMILAR", "TO")) {
                Expr pattern = parseAdditiveOrConcat();
                left = new Expr.BinaryExpr(negated ? "NOT SIMILAR TO" : "SIMILAR TO", left, pattern,
                        SourceSpan.between(left.span(), pattern.span()));
                continue;
            }

            if (cur.matchWords("MEMBER", "OF")) {
                Expr target = parseAdditiveOrConcat();
                left = new Expr.BinaryExpr(negated ? "NOT MEMBER OF" : "MEMBER OF", left, target,
                        SourceSpan.between(left.span(), target.span()));
                continue;
            }

            if (negated) {
                cur.seek(savedPos);
            }

            if (isComparisonOperator(cur.peek())) {
                String op = cur.advance().rawText();
                if (cur.matchAnyWord("ANY", "SOME", "ALL") && cur.matchSymbol("(")) {
                    if (isSubqueryStart()) {
                        QueryExpr sub = queryParser.parseQuery();
                        SqlToken close = cur.expectSymbol(")");
                        left = new Expr.SubqueryExpr("SCALAR", left, sub, SourceSpan.between(left.span(), close.span()));
                    } else {
                        List<Expr> list = parseExpressionList();
                        SqlToken close = cur.expectSymbol(")");
                        left = new Expr.InListExpr(left, false, list, SourceSpan.between(left.span(), close.span()));
                    }
                } else {
                    Expr right = parseAdditiveOrConcat();
                    left = new Expr.BinaryExpr(op, left, right, SourceSpan.between(left.span(), right.span()));
                }
                continue;
            }
            break;
        }
        return left;
    }

    private Expr parseAdditiveOrConcat() {
        Expr left = parseMultiplicative();
        while (!cur.isAtEnd()) {
            SqlToken tok = cur.peek();
            if (tok.isSymbol("+") || tok.isSymbol("-") || tok.isSymbol("||")
                    || tok.isSymbol("&") || tok.isSymbol("|") || tok.isSymbol("^")
                    || tok.isSymbol("<<") || tok.isSymbol(">>")) {
                String op = cur.advance().rawText();
                Expr right = parseMultiplicative();
                left = new Expr.BinaryExpr(op, left, right, SourceSpan.between(left.span(), right.span()));
            } else {
                break;
            }
        }
        return left;
    }

    private Expr parseMultiplicative() {
        Expr left = parseUnary();
        while (!cur.isAtEnd()) {
            SqlToken tok = cur.peek();
            if (tok.isSymbol("*") || tok.isSymbol("/") || tok.isSymbol("%") || tok.isWord("DIV") || tok.isWord("MOD")) {
                // Note: MOD(a, b) is a function call, whereas a MOD b is binary operator
                if (tok.isWord("MOD") && cur.peek(1).isSymbol("(")) {
                    break;
                }
                String op = cur.advance().rawText().toUpperCase(Locale.ROOT);
                Expr right = parseUnary();
                left = new Expr.BinaryExpr(op, left, right, SourceSpan.between(left.span(), right.span()));
            } else {
                break;
            }
        }
        return left;
    }

    private Expr parseUnary() {
        SqlToken tok = cur.peek();
        if (tok.isSymbol("+") || tok.isSymbol("-") || tok.isSymbol("~") || tok.isSymbol("!")
                || tok.isWord("PRIOR") || tok.isWord("CONNECT_BY_ROOT") || tok.isWord("BINARY")) {
            cur.advance();
            Expr operand = parseUnary();
            return new Expr.UnaryExpr(tok.rawText(), operand, SourceSpan.between(tok.span(), operand.span()));
        }
        return parsePostfix();
    }

    private Expr parsePostfix() {
        Expr expr = parsePrimary();

        while (!cur.isAtEnd()) {
            // 1. PostgreSQL / Snowflake / DuckDB ::TYPE cast
            if (cur.matchSymbol("::")) {
                String targetType = cur.parseDataType();
                SourceSpan endSpan = cur.previous().span();
                expr = new Expr.CastExpr(expr, targetType, false, SourceSpan.between(expr.span(), endSpan));
                continue;
            }

            // 2. Semi-structured / JSON path operators: col:field, col->'k', col->>'k', col#>'p', col#>>'p'
            if (cur.peek().isSymbol(":") && cur.peek().offset() == cur.previous().endOffset()) {
                cur.advance(); // ':'
                List<String> path = new ArrayList<>();
                SourceSpan endSpan = cur.previous().span();
                if (cur.peek().isIdentifierLike() || cur.peek().type() == TokenType.STRING_LITERAL) {
                    SqlToken fieldTok = cur.advance();
                    path.add(fieldTok.value());
                    endSpan = fieldTok.span();
                }
                while (cur.peek().isSymbol(".") && cur.peek(1).isIdentifierLike()) {
                    cur.advance();
                    SqlToken subTok = cur.advance();
                    path.add(subTok.value());
                    endSpan = subTok.span();
                }
                expr = attachPathToExpr(expr, path, endSpan);
                continue;
            }

            if (cur.peek().isSymbol("->") || cur.peek().isSymbol("->>")
                    || cur.peek().isSymbol("#>") || cur.peek().isSymbol("#>>")) {
                SqlToken opTok = cur.advance();
                Expr keyExpr = parsePrimary();
                if (expr instanceof Expr.ColumnRefExpr colRef && keyExpr instanceof Expr.LiteralExpr lit) {
                    List<String> merged = new ArrayList<>(colRef.jsonOrStructPath());
                    merged.add(lit.value());
                    expr = new Expr.ColumnRefExpr(
                            colRef.name(),
                            merged,
                            colRef.oracleOuterJoinPlus(),
                            SourceSpan.between(colRef.span(), keyExpr.span()));
                } else {
                    expr = new Expr.BinaryExpr(opTok.rawText(), expr, keyExpr,
                            SourceSpan.between(expr.span(), keyExpr.span()));
                }
                continue;
            }

            // 3. Array / Map subscript: expr[idx] or BigQuery expr[OFFSET(0)]
            if (cur.matchSymbol("[")) {
                Expr indexExpr = null;
                if (!cur.checkSymbol("]")) {
                    indexExpr = parseExpression();
                    if (cur.matchSymbol(":")) {
                        // Slice arr[1:3]
                        if (!cur.checkSymbol("]")) {
                            Expr sliceEnd = parseExpression();
                            indexExpr = new Expr.BinaryExpr(":", indexExpr, sliceEnd,
                                    SourceSpan.between(indexExpr.span(), sliceEnd.span()));
                        }
                    }
                }
                SqlToken close = cur.expectSymbol("]");
                if (expr instanceof Expr.ColumnRefExpr colRef && indexExpr instanceof Expr.LiteralExpr lit
                        && "STRING".equals(lit.literalType())) {
                    List<String> merged = new ArrayList<>(colRef.jsonOrStructPath());
                    merged.add(lit.value());
                    expr = new Expr.ColumnRefExpr(
                            colRef.name(),
                            merged,
                            colRef.oracleOuterJoinPlus(),
                            SourceSpan.between(colRef.span(), close.span()));
                } else if (indexExpr != null) {
                    expr = new Expr.BinaryExpr("[]", expr, indexExpr, SourceSpan.between(expr.span(), close.span()));
                }
                continue;
            }

            // 4. Struct / JSON dot access after non-column expression or parenthesized/subscripted expression: (fn()).field or arr[0].field
            if (cur.peek().isSymbol(".") && cur.peek(1).isIdentifierLike() && !(expr instanceof Expr.ColumnRefExpr)) {
                cur.advance();
                SqlToken fieldTok = cur.advance();
                if (cur.checkSymbol("(")) {
                    // Method-style call: xmlCol.value('...', 'VARCHAR(20)') in T-SQL or JSON method
                    List<Expr> args = new ArrayList<>();
                    args.add(expr);
                    cur.expectSymbol("(");
                    if (!cur.checkSymbol(")")) {
                        args.addAll(parseFunctionArgList());
                    }
                    SqlToken close = cur.expectSymbol(")");
                    QualifiedName methodQName = QualifiedName.single(
                            fieldTok.rawText(), fieldTok.value(), fieldTok.quoted(), false, fieldTok.span());
                    expr = new Expr.FuncCallExpr(methodQName, false, args, List.of(), null, null,
                            SourceSpan.between(expr.span(), close.span()));
                } else {
                    expr = attachPathToExpr(expr, List.of(fieldTok.value()), fieldTok.span());
                }
                continue;
            }

            // 5. Oracle outer-join (+) marker
            if (cur.matchSymbol("(+)")) {
                SourceSpan span = SourceSpan.between(expr.span(), cur.previous().span());
                if (expr instanceof Expr.ColumnRefExpr colRef) {
                    expr = new Expr.ColumnRefExpr(colRef.name(), colRef.jsonOrStructPath(), true, span);
                } else {
                    expr = new Expr.UnaryExpr("(+)", expr, span);
                }
                continue;
            }

            // 6. ClickHouse parametric function call: quantile(0.9)(col)
            if (expr instanceof Expr.FuncCallExpr fn && cur.checkSymbol("(")) {
                cur.expectSymbol("(");
                List<Expr> combinedArgs = new ArrayList<>(fn.args());
                if (!cur.checkSymbol(")")) {
                    combinedArgs.addAll(parseFunctionArgList());
                }
                SqlToken close = cur.expectSymbol(")");
                WindowSpec over = parseOptionalOverClause();
                expr = new Expr.FuncCallExpr(
                        fn.name(),
                        fn.distinct(),
                        combinedArgs,
                        fn.withinGroupOrder(),
                        fn.filterWhere(),
                        over != null ? over : fn.overWindow(),
                        SourceSpan.between(fn.span(), over != null ? over.span() : close.span()));
                continue;
            }

            // 7. COLLATE clause or AT TIME ZONE
            if (cur.matchWord("COLLATE")) {
                if (cur.peek().isIdentifierLike() || cur.peek().type() == TokenType.STRING_LITERAL) {
                    cur.advance();
                }
                continue;
            }
            if (cur.matchWords("AT", "TIME") && cur.matchWord("ZONE")) {
                Expr tz = parsePrimary();
                expr = new Expr.BinaryExpr("AT TIME ZONE", expr, tz, SourceSpan.between(expr.span(), tz.span()));
                continue;
            }
            break;
        }
        return expr;
    }

    private Expr attachPathToExpr(Expr base, List<String> extraPath, SourceSpan endSpan) {
        if (base instanceof Expr.ColumnRefExpr colRef) {
            List<String> merged = new ArrayList<>(colRef.jsonOrStructPath());
            merged.addAll(extraPath);
            return new Expr.ColumnRefExpr(
                    colRef.name(),
                    merged,
                    colRef.oracleOuterJoinPlus(),
                    SourceSpan.between(colRef.span(), endSpan));
        }
        String joined = String.join(".", extraPath);
        Expr pathLit = new Expr.LiteralExpr(joined, "STRING", endSpan);
        return new Expr.BinaryExpr(":", base, pathLit, SourceSpan.between(base.span(), endSpan));
    }

    private Expr parsePrimary() {
        SqlToken tok = cur.peek();

        // 1. Unqualified * (with optional BigQuery EXCEPT / Snowflake EXCLUDE / REPLACE / RENAME)
        if (tok.isSymbol("*")) {
            cur.advance();
            return parseStarModifiers(null, tok.span());
        }

        // 2. CASE expression
        if (tok.isWord("CASE")) {
            return parseCaseExpr();
        }

        // 3. EXISTS (subquery)
        if (tok.isWord("EXISTS") && cur.peek(1).isSymbol("(")) {
            cur.advance();
            cur.expectSymbol("(");
            QueryExpr sub = queryParser.parseQuery();
            SqlToken close = cur.expectSymbol(")");
            return new Expr.SubqueryExpr("EXISTS", null, sub, SourceSpan.between(tok.span(), close.span()));
        }

        // 4. CAST / TRY_CAST / SAFE_CAST / CONVERT / TRY_CONVERT
        if (tok.isAnyWord("CAST", "TRY_CAST", "SAFE_CAST") && cur.peek(1).isSymbol("(")) {
            return parseCastExpr();
        }
        if (tok.isAnyWord("CONVERT", "TRY_CONVERT") && cur.peek(1).isSymbol("(")) {
            return parseConvertExpr();
        }

        // 5. EXTRACT(field FROM source)
        if (tok.isWord("EXTRACT") && cur.peek(1).isSymbol("(")) {
            return parseExtractExpr();
        }

        // 6. NEXT VALUE FOR seq / PREVIOUS VALUE FOR seq (T-SQL / DB2 / ANSI)
        if (tok.isAnyWord("NEXT", "PREVIOUS") && cur.peek(1).isWord("VALUE") && cur.peek(2).isWord("FOR")) {
            String op = cur.advance().isWord("NEXT") ? "NEXTVAL" : "CURRVAL";
            cur.advance(); // VALUE
            cur.advance(); // FOR
            QualifiedName seqName = cur.parseQualifiedName();
            return new Expr.SequenceExpr(seqName, op, SourceSpan.between(tok.span(), seqName.span()));
        }

        // 7. STRUCT / ARRAY literals: STRUCT(a, b AS y), STRUCT<...>(...), ARRAY[...], ARRAY(SELECT ...)
        if (tok.isWord("STRUCT") && (cur.peek(1).isSymbol("(") || cur.peek(1).isSymbol("<"))) {
            return parseStructLiteral();
        }
        if (tok.isWord("ARRAY") && (cur.peek(1).isSymbol("[") || cur.peek(1).isSymbol("<") || cur.peek(1).isSymbol("("))) {
            return parseArrayLiteral();
        }
        if (tok.isSymbol("[")) {
            return parseBracketArrayLiteral();
        }

        // 8. Parenthesized expression, tuple, scalar subquery, or Teradata (TYPE) cast
        if (tok.isSymbol("(")) {
            return parseParenthesizedExpr();
        }

        // 9. Typed literals: DATE '...', TIMESTAMP '...', TIME '...', INTERVAL '...' DAY
        if (tok.isAnyWord("DATE", "TIME", "TIMESTAMP", "TIMESTAMPTZ", "JSON", "BIGNUMERIC", "NUMERIC")
                && cur.peek(1).type() == TokenType.STRING_LITERAL) {
            cur.advance();
            SqlToken strTok = cur.advance();
            return new Expr.LiteralExpr(strTok.value(), tok.upperValue(), SourceSpan.between(tok.span(), strTok.span()));
        }
        if (tok.isWord("INTERVAL") && (cur.peek(1).type() == TokenType.STRING_LITERAL
                || cur.peek(1).type() == TokenType.NUMBER_LITERAL)) {
            cur.advance();
            SqlToken valTok = cur.advance();
            SourceSpan endSpan = valTok.span();
            while (cur.peek().isAnyWord("YEAR", "YEARS", "MONTH", "MONTHS", "DAY", "DAYS",
                    "HOUR", "HOURS", "MINUTE", "MINUTES", "SECOND", "SECONDS", "TO")) {
                endSpan = cur.advance().span();
            }
            return new Expr.LiteralExpr(valTok.value(), "INTERVAL", SourceSpan.between(tok.span(), endSpan));
        }

        // 10. NULL / TRUE / FALSE / DEFAULT
        if (tok.isWord("NULL")) {
            cur.advance();
            return new Expr.LiteralExpr("NULL", "NULL", tok.span());
        }
        if (tok.isAnyWord("TRUE", "FALSE")) {
            cur.advance();
            return new Expr.LiteralExpr(tok.upperValue(), "BOOLEAN", tok.span());
        }
        if (tok.isWord("DEFAULT") && !cur.peek(1).isSymbol(".")) {
            cur.advance();
            return new Expr.LiteralExpr("DEFAULT", "DEFAULT", tok.span());
        }

        // 11. String & Number literals
        if (tok.type() == TokenType.STRING_LITERAL) {
            cur.advance();
            return new Expr.LiteralExpr(tok.value(), "STRING", tok.span());
        }
        if (tok.type() == TokenType.NUMBER_LITERAL) {
            cur.advance();
            return new Expr.LiteralExpr(tok.value(), "NUMBER", tok.span());
        }

        // 12. Bind variables / @variables / :NEW.col / :OLD.col / ${template}
        if (tok.type() == TokenType.BIND_VARIABLE) {
            return parseBindOrPseudoRecordVar();
        }

        // 13. Trigger pseudo-records INSERTED.col / DELETED.col / NEW.col / OLD.col
        if (tok.isAnyWord("INSERTED", "DELETED") && cur.peek(1).isSymbol(".") && cur.peek(2).isIdentifierLike()) {
            SqlToken recTok = cur.advance();
            cur.advance(); // '.'
            SqlToken colTok = cur.advance();
            return new Expr.VariableExpr(
                    recTok.rawText(),
                    recTok.upperValue(),
                    "PSEUDO_RECORD",
                    colTok.value(),
                    SourceSpan.between(recTok.span(), colTok.span()));
        }

        // 14. Special SQL functions with keyword syntax: TRIM, SUBSTRING, POSITION
        if (tok.isWord("TRIM") && cur.peek(1).isSymbol("(")) {
            return parseTrimCall();
        }
        if (tok.isAnyWord("SUBSTRING", "SUBSTR") && cur.peek(1).isSymbol("(")) {
            return parseSubstringCall();
        }
        if (tok.isWord("POSITION") && cur.peek(1).isSymbol("(")) {
            return parsePositionCall();
        }

        // 15. Identifier / Qualified Name -> Function Call, Qualified Star, Sequence .NEXTVAL, or ColumnRef
        if (tok.isIdentifierLike() || tok.type() == TokenType.STAGE_REFERENCE) {
            QualifiedName qName = cur.parseQualifiedName();

            // Qualified star: table.* or schema.table.*
            if (cur.peek().isSymbol(".") && cur.peek(1).isSymbol("*")) {
                cur.advance(); // '.'
                SqlToken starTok = cur.advance(); // '*'
                return parseStarModifiers(qName, SourceSpan.between(qName.span(), starTok.span()));
            }

            // Function call: fn(...)
            if (cur.checkSymbol("(")) {
                return parseFunctionCall(qName);
            }

            // Oracle / Postgres / Snowflake sequence pseudo-columns: seq.NEXTVAL / seq.CURRVAL
            String upperLast = qName.lastName().toUpperCase(Locale.ROOT);
            if (qName.size() >= 2 && ("NEXTVAL".equals(upperLast) || "CURRVAL".equals(upperLast))) {
                List<QualifiedName.NamePart> seqParts = qName.parts().subList(0, qName.size() - 1);
                QualifiedName seqName = new QualifiedName(
                        seqParts,
                        qName.dbLink(),
                        false,
                        false,
                        qName.templated(),
                        SourceSpan.between(seqParts.get(0).span(), seqParts.get(seqParts.size() - 1).span()));
                return new Expr.SequenceExpr(seqName, upperLast, qName.span());
            }

            // Method call on qualified col (e.g. T-SQL xmlCol.nodes(...) or col.STAsText())
            return new Expr.ColumnRefExpr(qName, List.of(), false, qName.span());
        }

        throw cur.error("Unexpected token in expression", tok);
    }

    private Expr.StarExpr parseStarModifiers(QualifiedName qualifier, SourceSpan initialSpan) {
        List<String> exceptCols = new ArrayList<>();
        Map<String, Expr> replaceCols = new LinkedHashMap<>();
        Map<String, String> renameCols = new LinkedHashMap<>();
        SourceSpan endSpan = initialSpan;

        // Note: Do NOT consume top-level EXCEPT (SELECT ...) set operator!
        while (!cur.isAtEnd()) {
            if (cur.checkAnyWord("EXCEPT", "EXCLUDE") && cur.peek(1).isSymbol("(") && !isSetOperatorExcept()) {
                cur.advance(); // EXCEPT or EXCLUDE
                cur.expectSymbol("(");
                if (!cur.checkSymbol(")")) {
                    do {
                        SqlToken colTok = cur.expectIdentifier("excluded column");
                        exceptCols.add(colTok.value());
                    } while (cur.matchSymbol(","));
                }
                endSpan = cur.expectSymbol(")").span();
                continue;
            }
            if (cur.matchWord("REPLACE") && cur.checkSymbol("(")) {
                cur.expectSymbol("(");
                if (!cur.checkSymbol(")")) {
                    do {
                        Expr replExpr = parseExpression();
                        cur.expectWord("AS");
                        SqlToken colTok = cur.expectIdentifier("replaced column");
                        replaceCols.put(colTok.value(), replExpr);
                    } while (cur.matchSymbol(","));
                }
                endSpan = cur.expectSymbol(")").span();
                continue;
            }
            if (cur.matchWord("RENAME") && cur.checkSymbol("(")) {
                cur.expectSymbol("(");
                if (!cur.checkSymbol(")")) {
                    do {
                        SqlToken oldTok = cur.expectIdentifier("old column");
                        cur.expectWord("AS");
                        SqlToken newTok = cur.expectIdentifier("new column");
                        renameCols.put(oldTok.value(), newTok.value());
                    } while (cur.matchSymbol(","));
                }
                endSpan = cur.expectSymbol(")").span();
                continue;
            }
            break;
        }
        return new Expr.StarExpr(qualifier, exceptCols, replaceCols, renameCols, SourceSpan.between(initialSpan, endSpan));
    }

    private boolean isSetOperatorExcept() {
        // EXCEPT (SELECT ...) or EXCEPT (WITH ...) is a set operator, whereas * EXCEPT (col1, col2) is BigQuery/DuckDB star modifier
        return cur.peek(2).isAnyWord("SELECT", "WITH", "VALUES");
    }

    private Expr parseCaseExpr() {
        SqlToken startTok = cur.expectWord("CASE");
        Expr operand = null;
        if (!cur.checkWord("WHEN") && !cur.checkWord("END")) {
            operand = parseExpression();
        }
        List<WhenClause> whenClauses = new ArrayList<>();
        while (cur.matchWord("WHEN")) {
            SourceSpan wStart = cur.previous().span();
            Expr cond = parseExpression();
            cur.expectWord("THEN");
            Expr thenExpr = parseExpression();
            whenClauses.add(new WhenClause(cond, thenExpr, SourceSpan.between(wStart, thenExpr.span())));
        }
        Expr elseExpr = null;
        if (cur.matchWord("ELSE")) {
            elseExpr = parseExpression();
        }
        SqlToken endTok = cur.expectWord("END");
        return new Expr.CaseExpr(operand, whenClauses, elseExpr, SourceSpan.between(startTok.span(), endTok.span()));
    }

    private Expr parseCastExpr() {
        SqlToken kw = cur.advance();
        boolean safeOrTry = !kw.isWord("CAST");
        cur.expectSymbol("(");
        Expr inner = parseExpression();
        String targetType = "VARCHAR";
        if (cur.matchWord("AS")) {
            targetType = cur.parseDataType();
        }
        if (cur.matchWord("FORMAT")) {
            // BigQuery / Teradata CAST(x AS STRING FORMAT 'YYYY-MM-DD')
            parseExpression();
        }
        SqlToken close = cur.expectSymbol(")");
        return new Expr.CastExpr(inner, targetType, safeOrTry, SourceSpan.between(kw.span(), close.span()));
    }

    private Expr parseConvertExpr() {
        SqlToken kw = cur.advance();
        boolean tryConvert = kw.isWord("TRY_CONVERT");
        cur.expectSymbol("(");
        // Distinguish T-SQL CONVERT(VARCHAR(20), expr [, style]) vs MySQL CONVERT(expr, CHAR) / CONVERT(expr USING utf8)
        int saved = cur.position();
        boolean tsqlStyle = false;
        if (cur.peek().isIdentifierLike() && SqlDialectRules.isBuiltInDataType(cur.peek().upperValue())) {
            String candidateType = cur.parseDataType();
            if (cur.matchSymbol(",")) {
                tsqlStyle = true;
                Expr inner = parseExpression();
                while (cur.matchSymbol(",")) {
                    parseExpression(); // optional style
                }
                SqlToken close = cur.expectSymbol(")");
                return new Expr.CastExpr(inner, candidateType, tryConvert, SourceSpan.between(kw.span(), close.span()));
            }
        }
        if (!tsqlStyle) {
            cur.seek(saved);
        }
        Expr inner = parseExpression();
        String targetType = "VARCHAR";
        if (cur.matchWord("USING")) {
            targetType = cur.expectIdentifier("charset").value();
        } else if (cur.matchSymbol(",")) {
            targetType = cur.parseDataType();
        }
        SqlToken close = cur.expectSymbol(")");
        return new Expr.CastExpr(inner, targetType, tryConvert, SourceSpan.between(kw.span(), close.span()));
    }

    private Expr parseExtractExpr() {
        SqlToken kw = cur.advance();
        cur.expectSymbol("(");
        String field = cur.advance().value();
        Expr source;
        if (cur.matchWord("FROM") || cur.matchSymbol(",")) {
            source = parseExpression();
        } else {
            source = new Expr.LiteralExpr("", "STRING", kw.span());
        }
        SqlToken close = cur.expectSymbol(")");
        return new Expr.ExtractExpr(field, source, SourceSpan.between(kw.span(), close.span()));
    }

    private Expr parseTrimCall() {
        SqlToken kw = cur.advance();
        cur.expectSymbol("(");
        List<Expr> args = new ArrayList<>();
        if (cur.matchAnyWord("LEADING", "TRAILING", "BOTH")) {
            if (!cur.checkWord("FROM") && !cur.checkSymbol(")")) {
                args.add(parseExpression());
            }
            if (cur.matchWord("FROM")) {
                args.add(parseExpression());
            }
        } else if (!cur.checkSymbol(")")) {
            Expr first = parseExpression();
            args.add(first);
            if (cur.matchWord("FROM") || cur.matchSymbol(",")) {
                args.add(parseExpression());
            }
        }
        SqlToken close = cur.expectSymbol(")");
        QualifiedName fnName = QualifiedName.single(kw.rawText(), kw.value(), false, false, kw.span());
        return new Expr.FuncCallExpr(fnName, false, args, List.of(), null, null,
                SourceSpan.between(kw.span(), close.span()));
    }

    private Expr parseSubstringCall() {
        SqlToken kw = cur.advance();
        cur.expectSymbol("(");
        List<Expr> args = new ArrayList<>();
        if (!cur.checkSymbol(")")) {
            args.add(parseExpression());
            while (cur.matchSymbol(",") || cur.matchWord("FROM") || cur.matchWord("FOR")) {
                args.add(parseExpression());
            }
        }
        SqlToken close = cur.expectSymbol(")");
        QualifiedName fnName = QualifiedName.single(kw.rawText(), kw.value(), false, false, kw.span());
        return new Expr.FuncCallExpr(fnName, false, args, List.of(), null, null,
                SourceSpan.between(kw.span(), close.span()));
    }

    private Expr parsePositionCall() {
        SqlToken kw = cur.advance();
        cur.expectSymbol("(");
        List<Expr> args = new ArrayList<>();
        if (!cur.checkSymbol(")")) {
            Expr sub = parseAdditiveOrConcat();
            args.add(sub);
            if (cur.matchWord("IN") || cur.matchSymbol(",")) {
                args.add(parseExpression());
            }
        }
        SqlToken close = cur.expectSymbol(")");
        QualifiedName fnName = QualifiedName.single(kw.rawText(), kw.value(), false, false, kw.span());
        return new Expr.FuncCallExpr(fnName, false, args, List.of(), null, null,
                SourceSpan.between(kw.span(), close.span()));
    }

    private Expr parseStructLiteral() {
        SqlToken kw = cur.advance(); // STRUCT
        if (cur.checkSymbol("<")) {
            // Skip explicit BigQuery STRUCT<a INT64, b STRING> type header
            int depth = 0;
            do {
                SqlToken t = cur.advance();
                if (t.isSymbol("<")) {
                    depth++;
                } else if (t.isSymbol(">")) {
                    depth--;
                } else if (t.isSymbol(">>")) {
                    depth -= 2;
                }
            } while (!cur.isAtEnd() && depth > 0);
        }
        if (!cur.checkSymbol("(")) {
            return new Expr.LiteralExpr("STRUCT", "STRUCT", kw.span());
        }
        cur.expectSymbol("(");
        List<SelectItem> items = new ArrayList<>();
        if (!cur.checkSymbol(")")) {
            do {
                SourceSpan itemStart = cur.peek().span();
                Expr val = parseExpression();
                String alias = null;
                boolean aliasQuoted = false;
                SourceSpan aliasSpan = null;
                if (cur.matchWord("AS") && cur.peek().isIdentifierLike()) {
                    SqlToken aTok = cur.advance();
                    alias = aTok.value();
                    aliasQuoted = aTok.quoted();
                    aliasSpan = aTok.span();
                }
                SourceSpan itemEnd = aliasSpan != null ? aliasSpan : val.span();
                items.add(new SelectItem(val, alias, aliasQuoted, aliasSpan,
                        cur.extractText(itemStart, itemEnd), SourceSpan.between(itemStart, itemEnd)));
            } while (cur.matchSymbol(","));
        }
        SqlToken close = cur.expectSymbol(")");
        return new Expr.StructArrayExpr("STRUCT", items, SourceSpan.between(kw.span(), close.span()));
    }

    private Expr parseArrayLiteral() {
        SqlToken kw = cur.advance(); // ARRAY
        if (cur.checkSymbol("<")) {
            int depth = 0;
            do {
                SqlToken t = cur.advance();
                if (t.isSymbol("<")) {
                    depth++;
                } else if (t.isSymbol(">")) {
                    depth--;
                } else if (t.isSymbol(">>")) {
                    depth -= 2;
                }
            } while (!cur.isAtEnd() && depth > 0);
        }
        if (cur.matchSymbol("(")) {
            if (isSubqueryStart()) {
                QueryExpr sub = queryParser.parseQuery();
                SqlToken close = cur.expectSymbol(")");
                return new Expr.SubqueryExpr("ARRAY", null, sub, SourceSpan.between(kw.span(), close.span()));
            }
            List<SelectItem> elems = parseSelectItemStyleElements(")");
            SqlToken close = cur.expectSymbol(")");
            return new Expr.StructArrayExpr("ARRAY", elems, SourceSpan.between(kw.span(), close.span()));
        }
        if (cur.matchSymbol("[")) {
            List<SelectItem> elems = parseSelectItemStyleElements("]");
            SqlToken close = cur.expectSymbol("]");
            return new Expr.StructArrayExpr("ARRAY", elems, SourceSpan.between(kw.span(), close.span()));
        }
        return new Expr.LiteralExpr("ARRAY", "ARRAY", kw.span());
    }

    private Expr parseBracketArrayLiteral() {
        SqlToken open = cur.expectSymbol("[");
        List<SelectItem> elems = parseSelectItemStyleElements("]");
        SqlToken close = cur.expectSymbol("]");
        return new Expr.StructArrayExpr("ARRAY", elems, SourceSpan.between(open.span(), close.span()));
    }

    private List<SelectItem> parseSelectItemStyleElements(String closeSym) {
        List<SelectItem> items = new ArrayList<>();
        if (!cur.checkSymbol(closeSym)) {
            do {
                Expr e = parseExpression();
                items.add(new SelectItem(e, null, false, null, cur.extractText(e.span(), e.span()), e.span()));
            } while (cur.matchSymbol(","));
        }
        return items;
    }

    private Expr parseParenthesizedExpr() {
        SqlToken open = cur.expectSymbol("(");
        if (isSubqueryStart()) {
            QueryExpr sub = queryParser.parseQuery();
            SqlToken close = cur.expectSymbol(")");
            return new Expr.SubqueryExpr("SCALAR", null, sub, SourceSpan.between(open.span(), close.span()));
        }
        if (cur.matchSymbol(")")) {
            return new Expr.StructArrayExpr("TUPLE", List.of(), SourceSpan.between(open.span(), cur.previous().span()));
        }
        Expr first = parseExpression();
        if (cur.matchSymbol(",")) {
            List<SelectItem> tupleItems = new ArrayList<>();
            tupleItems.add(new SelectItem(first, null, false, null, "", first.span()));
            do {
                Expr next = parseExpression();
                tupleItems.add(new SelectItem(next, null, false, null, "", next.span()));
            } while (cur.matchSymbol(","));
            SqlToken close = cur.expectSymbol(")");
            return new Expr.StructArrayExpr("TUPLE", tupleItems, SourceSpan.between(open.span(), close.span()));
        }
        SqlToken close = cur.expectSymbol(")");
        return first;
    }

    private Expr parseBindOrPseudoRecordVar() {
        SqlToken tok = cur.advance();
        String raw = tok.rawText();
        String clean = raw;
        String kind = "BIND_VAR";
        if (raw.startsWith("@")) {
            kind = "AT_VAR";
            clean = raw;
        } else if (raw.startsWith(":")) {
            String withoutColon = raw.substring(1);
            if ("NEW".equalsIgnoreCase(withoutColon) || "OLD".equalsIgnoreCase(withoutColon)) {
                kind = "PSEUDO_RECORD";
                clean = withoutColon.toUpperCase(Locale.ROOT);
            } else {
                kind = "COLON_BIND";
                clean = raw;
            }
        }
        String field = null;
        SourceSpan endSpan = tok.span();
        if (cur.peek().isSymbol(".") && cur.peek(1).isIdentifierLike()) {
            cur.advance();
            SqlToken fTok = cur.advance();
            field = fTok.value();
            endSpan = fTok.span();
        }
        return new Expr.VariableExpr(raw, clean, kind, field, SourceSpan.between(tok.span(), endSpan));
    }

    public Expr.FuncCallExpr parseFunctionCall(QualifiedName fnName) {
        cur.expectSymbol("(");
        boolean distinct = false;
        if (cur.matchWord("DISTINCT") || cur.matchWord("UNIQUE")) {
            distinct = true;
        } else {
            cur.matchWord("ALL");
        }

        List<Expr> args = new ArrayList<>();
        if (!cur.checkSymbol(")")) {
            args.addAll(parseFunctionArgList());
        }

        // Optional ORDER BY / SEPARATOR / LIMIT inside aggregate call: ARRAY_AGG(x ORDER BY y DESC LIMIT 5)
        if (cur.matchWords("ORDER", "BY")) {
            queryParser.parseOrderByList().forEach(ob -> args.add(ob.expr()));
        }
        if (cur.matchWord("SEPARATOR") && !cur.checkSymbol(")")) {
            parseExpression();
        }
        if (cur.matchWord("LIMIT") && !cur.checkSymbol(")")) {
            parseExpression();
        }
        // Ignore IGNORE NULLS / RESPECT NULLS inside parens (BigQuery / Snowflake)
        if (cur.matchAnyWord("IGNORE", "RESPECT") && cur.matchWord("NULLS")) {
            // consumed
        }

        SqlToken close = cur.expectSymbol(")");
        SourceSpan endSpan = close.span();

        // Trailing IGNORE/RESPECT NULLS outside parens (Oracle / Redshift / Snowflake)
        if (cur.matchAnyWord("IGNORE", "RESPECT") && cur.matchWord("NULLS")) {
            endSpan = cur.previous().span();
        }

        // WITHIN GROUP (ORDER BY ...)
        List<OrderByItem> withinGroup = List.of();
        if (cur.matchWords("WITHIN", "GROUP") && cur.matchSymbol("(")) {
            if (cur.matchWords("ORDER", "BY")) {
                withinGroup = queryParser.parseOrderByList();
            }
            endSpan = cur.expectSymbol(")").span();
        }

        // FILTER (WHERE ...)
        Expr filterWhere = null;
        if (cur.matchWord("FILTER") && cur.matchSymbol("(")) {
            cur.matchWord("WHERE");
            filterWhere = parseExpression();
            endSpan = cur.expectSymbol(")").span();
        }

        // OVER (window_spec) or OVER window_name
        WindowSpec overWindow = parseOptionalOverClause();
        if (overWindow != null) {
            endSpan = overWindow.span();
        }

        return new Expr.FuncCallExpr(
                fnName,
                distinct,
                args,
                withinGroup,
                filterWhere,
                overWindow,
                SourceSpan.between(fnName.span(), endSpan));
    }

    private List<Expr> parseFunctionArgList() {
        List<Expr> args = new ArrayList<>();
        do {
            if (cur.checkSymbol(")")) {
                break;
            }
            // Named argument syntax: param => expr (Oracle / Postgres / Snowflake FLATTEN(INPUT => x))
            if (cur.peek().isIdentifierLike() && (cur.peek(1).isSymbol("=>") || cur.peek(1).isSymbol(":="))) {
                SqlToken pName = cur.advance();
                cur.advance(); // => or :=
                Expr val = parseExpression();
                args.add(new Expr.BinaryExpr(
                        "=>",
                        new Expr.LiteralExpr(pName.value(), "PARAM_NAME", pName.span()),
                        val,
                        SourceSpan.between(pName.span(), val.span())));
            } else {
                Expr arg = parseExpression();
                // Handle JSON_OBJECT(KEY 'a' VALUE x) or AS TYPE inside special functions
                if (cur.matchWord("AS") && !cur.checkSymbol(",") && !cur.checkSymbol(")")) {
                    cur.parseDataType();
                }
                args.add(arg);
            }
        } while (cur.matchSymbol(","));
        return args;
    }

    public WindowSpec parseOptionalOverClause() {
        if (!cur.matchWord("OVER")) {
            return null;
        }
        SqlToken overTok = cur.previous();
        if (!cur.checkSymbol("(")) {
            SqlToken winName = cur.expectIdentifier("window name");
            return new WindowSpec(winName.value(), List.of(), List.of(), null,
                    SourceSpan.between(overTok.span(), winName.span()));
        }
        return parseWindowSpecParens(overTok.span());
    }

    public WindowSpec parseWindowSpecParens(SourceSpan startSpan) {
        cur.expectSymbol("(");
        String refWin = null;
        if (cur.peek().isIdentifierLike()
                && !cur.checkAnyWord("PARTITION", "ORDER", "ROWS", "RANGE", "GROUPS")) {
            refWin = cur.advance().value();
        }
        List<Expr> partitionBy = new ArrayList<>();
        if (cur.matchWords("PARTITION", "BY") || cur.matchWords("DISTRIBUTE", "BY")) {
            do {
                partitionBy.add(parseExpression());
            } while (cur.matchSymbol(","));
        }
        List<OrderByItem> orderBy = List.of();
        if (cur.matchWords("ORDER", "BY") || cur.matchWords("SORT", "BY")) {
            orderBy = queryParser.parseOrderByList();
        }
        String frame = null;
        if (cur.checkAnyWord("ROWS", "RANGE", "GROUPS")) {
            SourceSpan fStart = cur.peek().span();
            int depth = 0;
            while (!cur.isAtEnd()) {
                if (cur.checkSymbol("(")) {
                    depth++;
                } else if (cur.checkSymbol(")")) {
                    if (depth == 0) {
                        break;
                    }
                    depth--;
                }
                cur.advance();
            }
            frame = cur.extractText(fStart, cur.previous().span());
        }
        SqlToken close = cur.expectSymbol(")");
        return new WindowSpec(refWin, partitionBy, orderBy, frame, SourceSpan.between(startSpan, close.span()));
    }

    public List<Expr> parseExpressionList() {
        List<Expr> list = new ArrayList<>();
        do {
            list.add(parseExpression());
        } while (cur.matchSymbol(","));
        return list;
    }

    private boolean isSubqueryStart() {
        return cur.checkAnyWord("SELECT", "WITH", "VALUES", "FROM");
    }

    private static boolean isComparisonOperator(SqlToken tok) {
        if (tok.type() != TokenType.OPERATOR) {
            return false;
        }
        return switch (tok.rawText()) {
            case "=", "==", "!=", "<>", "<", "<=", ">", ">=", "<=>", "=~", "!~", "~", "~*" -> true;
            default -> false;
        };
    }
}
