package io.sqlflow.lineage.demo;

import io.sqlflow.contracts.lineage.ErrorItem;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.FormatRequest;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.FormatResult;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.GFmtOpt;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.lineage.lexer.SqlDialectRules;
import io.sqlflow.lineage.lexer.SqlLexer;
import io.sqlflow.lineage.lexer.SqlToken;
import io.sqlflow.lineage.lexer.TokenType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * Multi-dialect SQL pretty-printer honoring all 19 {@link GFmtOpt} option groups with
 * token-stream-preserving SQLion fallback ({@code 02 §4.9 #6}, {@code OBL-061}).
 */
public final class SqlFormatterEngine {

    private enum Clause {
        NONE, SELECT, FROM, JOIN, WHERE, GROUP_BY, HAVING, ORDER_BY,
        UPDATE_SET, INSERT_HEAD, INSERT_COLS, INSERT_VALUES, CREATE_TABLE, CREATE_TABLE_ITEMS
    }

    private enum ParenKind {
        SUBQUERY, FUNCTION, TYPENAME, CREATE_TABLE, INSERT_COLS, INSERT_VALUES, EXPR
    }

    private static final Set<String> NON_FUNC_WORDS_BEFORE_PAREN = Set.of(
            "SELECT", "FROM", "WHERE", "VALUES", "IN", "EXISTS", "OVER", "PARTITION", "USING",
            "INTO", "TABLE", "PRIMARY", "FOREIGN", "UNIQUE", "CHECK", "REFERENCES", "WHEN",
            "AND", "OR", "NOT", "AS", "ON", "SET", "BY", "CASE", "WITH", "UNION", "ALL");

    private static final Set<String> JOIN_MODIFIERS = Set.of(
            "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS", "NATURAL", "SEMI", "ANTI", "STRAIGHT_JOIN");

    public FormatResult format(FormatRequest request) {
        String sql = request != null ? request.getSql() : null;
        if (sql == null || sql.isBlank()) {
            return new FormatResult("", List.of());
        }
        EDbVendor vendor = request.getDbvendor();
        GFmtOpt opt = SqlFormatterOptionsResolver.resolve(request);

        SqlLexer.LexResult lexed = SqlLexer.tokenize(sql, vendor);
        List<ErrorItem> syntaxErrors = new SyntaxAndKeywordService().collectSyntaxErrors(sql, vendor);

        String rawFormatted = formatTokens(lexed.allTokens(), vendor, opt);
        String finalSql = SqlFormatterPostProcessor.postProcess(rawFormatted, opt);

        FormatResult res = new FormatResult();
        res.setFormattedSql(finalSql);
        if (!syntaxErrors.isEmpty()) {
            String firstMsg = syntaxErrors.get(0).getErrorMessage();
            res.setMessage("Parse sql error, use SQLion format result. Error message: " + firstMsg);
            res.setErrors(syntaxErrors);
        }
        return res;
    }

    private String formatTokens(List<SqlToken> tokens, EDbVendor vendor, GFmtOpt opt) {
        StringBuilder out = new StringBuilder(tokens.size() * 8);
        String unit = SqlFormatterPostProcessor.indentUnit(opt);
        Deque<Clause> clauseStack = new ArrayDeque<>();
        Deque<ParenKind> parenStack = new ArrayDeque<>();
        clauseStack.push(Clause.NONE);

        int blockIndent = 0;
        boolean atLineStart = true;
        boolean needSpace = false;
        boolean inBetween = false;
        boolean inCastParens = false;

        int n = tokens.size();
        for (int i = 0; i < n; i++) {
            SqlToken tok = tokens.get(i);
            if (tok.isEof()) {
                break;
            }
            SqlToken prev = findPrevCodeToken(tokens, i);
            SqlToken next = findNextCodeToken(tokens, i);

            if (tok.isComment()) {
                if (!atLineStart) {
                    out.append(' ');
                } else {
                    appendIndent(out, unit, blockIndent + subQueryDepth(parenStack));
                }
                out.append(tok.rawText());
                if (tok.type() == TokenType.LINE_COMMENT) {
                    out.append('\n');
                    atLineStart = true;
                    needSpace = false;
                } else {
                    atLineStart = false;
                    needSpace = true;
                }
                continue;
            }

            Clause clause = clauseStack.peek();
            String upper = tok.upperValue();

            // Major clause transitions at current parenthesis depth
            if (!tok.quoted() && (tok.type() == TokenType.KEYWORD || tok.type() == TokenType.IDENTIFIER)) {
                if (upper.equals("SELECT")) {
                    if (!atLineStart && !(prev != null && prev.isSymbol("("))) {
                        newline(out);
                        atLineStart = true;
                    }
                    clause = replaceTop(clauseStack, Clause.SELECT);
                } else if (upper.equals("FROM") && clause != Clause.CREATE_TABLE_ITEMS && !inCastParens) {
                    if (prev == null || !prev.isAnyWord("DISTINCT", "EXTRACT", "TRIM")) {
                        if (!atLineStart) {
                            newline(out);
                            atLineStart = true;
                        }
                        clause = replaceTop(clauseStack, Clause.FROM);
                    }
                } else if (isJoinSequenceStart(tokens, i, prev)) {
                    if (opt.isFromClauseJoinInNewline() && !atLineStart) {
                        newline(out);
                        atLineStart = true;
                    }
                    clause = replaceTop(clauseStack, Clause.JOIN);
                } else if (upper.equals("WHERE")) {
                    if (!atLineStart) {
                        newline(out);
                        atLineStart = true;
                    }
                    clause = replaceTop(clauseStack, Clause.WHERE);
                    inBetween = false;
                } else if (upper.equals("GROUP") && next != null && next.isWord("BY")) {
                    if (!atLineStart) {
                        newline(out);
                        atLineStart = true;
                    }
                    clause = replaceTop(clauseStack, Clause.GROUP_BY);
                } else if (upper.equals("HAVING") || upper.equals("QUALIFY")) {
                    if (!atLineStart) {
                        newline(out);
                        atLineStart = true;
                    }
                    clause = replaceTop(clauseStack, Clause.HAVING);
                } else if (upper.equals("ORDER") && next != null && next.isWord("BY")
                        && (parenStack.isEmpty() || parenStack.peek() == ParenKind.SUBQUERY)) {
                    if (!atLineStart) {
                        newline(out);
                        atLineStart = true;
                    }
                    clause = replaceTop(clauseStack, Clause.ORDER_BY);
                } else if (upper.equals("LIMIT") || upper.equals("FETCH")) {
                    if (!atLineStart) {
                        newline(out);
                        atLineStart = true;
                    }
                    clause = replaceTop(clauseStack, Clause.NONE);
                } else if (upper.equals("UNION") || upper.equals("INTERSECT") || upper.equals("EXCEPT") || upper.equals("MINUS")) {
                    if (!atLineStart) {
                        newline(out);
                        atLineStart = true;
                    }
                    clause = replaceTop(clauseStack, Clause.NONE);
                } else if (upper.equals("INSERT")) {
                    if (!atLineStart) {
                        newline(out);
                        atLineStart = true;
                    }
                    clause = replaceTop(clauseStack, Clause.INSERT_HEAD);
                } else if (upper.equals("VALUES") && (clause == Clause.INSERT_HEAD || clause == Clause.INSERT_COLS)) {
                    if (!atLineStart) {
                        newline(out);
                        atLineStart = true;
                    }
                    clause = replaceTop(clauseStack, Clause.INSERT_VALUES);
                } else if (upper.equals("UPDATE") && (prev == null || prev.isSymbol(";"))) {
                    if (!atLineStart) {
                        newline(out);
                        atLineStart = true;
                    }
                    clause = replaceTop(clauseStack, Clause.NONE);
                } else if (upper.equals("SET") && clause != Clause.CREATE_TABLE_ITEMS) {
                    if (!atLineStart) {
                        newline(out);
                        atLineStart = true;
                    }
                    clause = replaceTop(clauseStack, Clause.UPDATE_SET);
                } else if (upper.equals("CREATE")) {
                    if (!atLineStart) {
                        newline(out);
                        atLineStart = true;
                    }
                    clause = replaceTop(clauseStack, Clause.NONE);
                } else if (upper.equals("TABLE") && prev != null && prev.isAnyWord("CREATE", "TEMPORARY", "TEMP", "EXTERNAL", "REPLACE")) {
                    clause = replaceTop(clauseStack, Clause.CREATE_TABLE);
                } else if (upper.equals("BEGIN")) {
                    if (opt.isBeStyleBlockLeftBEOnNewline() && !atLineStart) {
                        newline(out);
                        atLineStart = true;
                    }
                } else if (upper.equals("END")) {
                    boolean exprCaseEnd = prev != null && clause != Clause.NONE && !isProceduralEnd(next);
                    if (!exprCaseEnd && blockIndent > 0) {
                        blockIndent--;
                        if (opt.isBeStyleBlockRightBEOnNewline() && !atLineStart) {
                            newline(out);
                            atLineStart = true;
                        }
                    }
                } else if (upper.equals("BETWEEN")) {
                    inBetween = true;
                } else if (upper.equals("AND") || upper.equals("OR")) {
                    if (upper.equals("AND") && inBetween) {
                        inBetween = false;
                    } else if ((clause == Clause.WHERE || clause == Clause.HAVING)
                            && (parenStack.isEmpty() || parenStack.peek() == ParenKind.SUBQUERY)
                            && opt.isAndOrUnderWhere() && !atLineStart) {
                        newline(out);
                        atLineStart = true;
                    }
                }
            }

            // Leading comma handling before emitting ','
            if (tok.isSymbol(",")) {
                boolean stacked = isCurrentListStacked(clause, parenStack.peek(), opt);
                boolean leading = SqlFormatterPostProcessor.isLeadingComma(
                        clause == Clause.SELECT ? opt.getSelectColumnlistComma() : null,
                        opt.getDefaultCommaOption());
                if (stacked && leading) {
                    newline(out);
                    appendIndent(out, unit, blockIndent + subQueryDepth(parenStack) + 1);
                    out.append(", ");
                    atLineStart = false;
                    needSpace = false;
                    continue;
                }
                out.append(',');
                if (stacked) {
                    newline(out);
                    atLineStart = true;
                    needSpace = false;
                } else {
                    atLineStart = false;
                    needSpace = true;
                }
                continue;
            }

            if (tok.isSymbol(";")) {
                out.append(';');
                if (next != null) {
                    newline(out);
                    newline(out);
                    atLineStart = true;
                }
                needSpace = false;
                replaceTop(clauseStack, Clause.NONE);
                continue;
            }

            if (tok.isSymbol("(")) {
                ParenKind pk = classifyOpenParen(prev, next, clause, vendor);
                if (prev != null && prev.isAnyWord("CAST", "TRY_CAST", "SAFE_CAST")) {
                    inCastParens = true;
                }
                boolean spaceBeforeParen = prev != null
                        && !prev.isSymbol("(") && !prev.isSymbol(".")
                        && (pk != ParenKind.FUNCTION && pk != ParenKind.TYPENAME
                        || opt.isWsPaddingParenthesesInFunction());
                if (atLineStart) {
                    appendIndent(out, unit, effectiveIndent(blockIndent, parenStack, clause, upper, opt));
                } else if (spaceBeforeParen && needSpace) {
                    out.append(' ');
                }
                out.append('(');
                parenStack.push(pk);
                clauseStack.push(pk == ParenKind.CREATE_TABLE ? Clause.CREATE_TABLE_ITEMS
                        : (pk == ParenKind.INSERT_COLS ? Clause.INSERT_COLS
                        : (pk == ParenKind.INSERT_VALUES ? Clause.INSERT_VALUES : Clause.NONE)));
                if (pk == ParenKind.CREATE_TABLE && SqlFormatterPostProcessor.isStacked(opt.getCreateTableItemlistStyle(), false)) {
                    newline(out);
                    atLineStart = true;
                    needSpace = false;
                } else {
                    atLineStart = false;
                    needSpace = innerParenPadding(pk, opt);
                }
                continue;
            }

            if (tok.isSymbol(")")) {
                ParenKind closed = parenStack.isEmpty() ? ParenKind.EXPR : parenStack.pop();
                if (clauseStack.size() > 1) {
                    clauseStack.pop();
                }
                if (closed == ParenKind.EXPR) {
                    inCastParens = false;
                }
                if (closed == ParenKind.CREATE_TABLE && SqlFormatterPostProcessor.isStacked(opt.getCreateTableItemlistStyle(), false) && !atLineStart) {
                    newline(out);
                    appendIndent(out, unit, blockIndent + subQueryDepth(parenStack));
                } else if (!atLineStart && innerParenPadding(closed, opt)) {
                    out.append(' ');
                }
                out.append(')');
                if (closed == ParenKind.INSERT_COLS && clauseStack.peek() == Clause.INSERT_HEAD) {
                    replaceTop(clauseStack, Clause.INSERT_COLS);
                }
                atLineStart = false;
                needSpace = true;
                continue;
            }

            // Emit indentation or intra-line spacing
            if (atLineStart) {
                appendIndent(out, unit, effectiveIndent(blockIndent, parenStack, clause, upper, opt));
                atLineStart = false;
            } else if (shouldSpaceBefore(tok, prev, opt)) {
                if (needSpace || isBinaryOperator(tok, prev)) {
                    out.append(' ');
                }
            }

            String rendered = renderTokenText(tok, prev, next, clause, inCastParens, vendor, opt);
            out.append(rendered);
            needSpace = shouldSpaceAfter(tok, prev, opt);

            // Post-token newline triggers
            if (!tok.quoted()) {
                if (upper.equals("SELECT") && !(next != null && next.isAnyWord("DISTINCT", "ALL", "TOP"))) {
                    if (SqlFormatterPostProcessor.isStacked(opt.getSelectColumnlistStyle(), opt.isSelectItemInNewLine())) {
                        newline(out);
                        atLineStart = true;
                        needSpace = false;
                    }
                } else if (upper.equals("DISTINCT") && clause == Clause.SELECT
                        && (opt.isTreatDistinctAsVirtualColumn()
                        || SqlFormatterPostProcessor.isStacked(opt.getSelectColumnlistStyle(), opt.isSelectItemInNewLine()))) {
                    newline(out);
                    atLineStart = true;
                    needSpace = false;
                } else if (upper.equals("FROM") && clause == Clause.FROM && opt.isFromClauseInNewline()) {
                    newline(out);
                    atLineStart = true;
                    needSpace = false;
                } else if (upper.equals("WHERE") && clause == Clause.WHERE && opt.isWhereClauseInNewline()) {
                    newline(out);
                    atLineStart = true;
                    needSpace = false;
                } else if (upper.equals("SET") && clause == Clause.UPDATE_SET
                        && SqlFormatterPostProcessor.isStacked(opt.getUpdateSetlistStyle(), false)) {
                    newline(out);
                    atLineStart = true;
                    needSpace = false;
                } else if (upper.equals("BY") && (clause == Clause.GROUP_BY || clause == Clause.ORDER_BY)) {
                    String listStyle = clause == Clause.GROUP_BY ? opt.getGroupbyColumnlistStyle() : opt.getOrderbyColumnlistStyle();
                    if (SqlFormatterPostProcessor.isStacked(listStyle, false)) {
                        newline(out);
                        atLineStart = true;
                        needSpace = false;
                    }
                } else if (upper.equals("BEGIN") && !(next != null && next.isAnyWord("TRANSACTION", "TRAN", "WORK", ";"))) {
                    blockIndent++;
                    newline(out);
                    atLineStart = true;
                    needSpace = false;
                }
            }
        }
        return out.toString().trim();
    }

    private static String renderTokenText(
            SqlToken tok, SqlToken prev, SqlToken next, Clause clause,
            boolean inCastParens, EDbVendor vendor, GFmtOpt opt) {
        if (tok.type() == TokenType.QUOTED_IDENTIFIER) {
            return SqlFormatterOptionsResolver.formatQuotedIdentifier(tok.rawText(), opt.getCaseQuotedIdentifier());
        }
        if (tok.type() != TokenType.KEYWORD && tok.type() != TokenType.IDENTIFIER) {
            return tok.rawText();
        }
        String raw = tok.rawText();
        String upper = tok.upperValue();
        if (prev != null && prev.isSymbol(".")) {
            return SqlFormatterOptionsResolver.formatWordCase(raw, opt.getCaseIdentifier());
        }
        if (next != null && next.isSymbol("(") && !NON_FUNC_WORDS_BEFORE_PAREN.contains(upper)
                && clause != Clause.CREATE_TABLE && clause != Clause.INSERT_HEAD) {
            if (SqlDialectRules.isBuiltInDataType(upper) && (clause == Clause.CREATE_TABLE_ITEMS || inCastParens)) {
                return SqlFormatterOptionsResolver.formatWordCase(raw, opt.getCaseDatatype());
            }
            return SqlFormatterOptionsResolver.formatWordCase(raw, opt.getCaseFuncname());
        }
        if (SqlDialectRules.isBuiltInDataType(upper)
                && (clause == Clause.CREATE_TABLE_ITEMS
                || (inCastParens && prev != null && prev.isWord("AS"))
                || (prev != null && prev.isAnyWord("RETURNS", "RETURN")))) {
            return SqlFormatterOptionsResolver.formatWordCase(raw, opt.getCaseDatatype());
        }
        if (prev != null && prev.isWord("AS") && !inCastParens
                && (next == null || !next.isAnyWord("SELECT", "BEGIN", "$$"))) {
            return SqlFormatterOptionsResolver.formatWordCase(raw, opt.getCaseIdentifier());
        }
        if (SqlDialectRules.isAnyKeyword(upper, vendor)) {
            return SqlFormatterOptionsResolver.formatWordCase(raw, opt.getCaseKeywords());
        }
        return SqlFormatterOptionsResolver.formatWordCase(raw, opt.getCaseIdentifier());
    }

    private static ParenKind classifyOpenParen(SqlToken prev, SqlToken next, Clause clause, EDbVendor vendor) {
        if (next != null && next.isAnyWord("SELECT", "WITH")) {
            return ParenKind.SUBQUERY;
        }
        if (clause == Clause.CREATE_TABLE) {
            return ParenKind.CREATE_TABLE;
        }
        if (clause == Clause.INSERT_HEAD) {
            return ParenKind.INSERT_COLS;
        }
        if (clause == Clause.INSERT_VALUES) {
            return ParenKind.INSERT_VALUES;
        }
        if (prev != null && !prev.quoted() && (prev.type() == TokenType.KEYWORD || prev.type() == TokenType.IDENTIFIER)) {
            if (SqlDialectRules.isBuiltInDataType(prev.upperValue()) && clause == Clause.CREATE_TABLE_ITEMS) {
                return ParenKind.TYPENAME;
            }
            if (!NON_FUNC_WORDS_BEFORE_PAREN.contains(prev.upperValue())) {
                return ParenKind.FUNCTION;
            }
        }
        return ParenKind.EXPR;
    }

    private static boolean innerParenPadding(ParenKind pk, GFmtOpt opt) {
        return switch (pk) {
            case SUBQUERY -> opt.isWsPaddingParenthesesOfSubQuery();
            case FUNCTION -> opt.isWsPaddingParenthesesInFunctionCall();
            case TYPENAME -> opt.isWsPaddingParenthesesOfTypename();
            default -> false;
        };
    }

    private static boolean isCurrentListStacked(Clause clause, ParenKind topParen, GFmtOpt opt) {
        if (topParen == ParenKind.FUNCTION || topParen == ParenKind.TYPENAME || topParen == ParenKind.EXPR) {
            return false;
        }
        return switch (clause) {
            case SELECT -> SqlFormatterPostProcessor.isStacked(opt.getSelectColumnlistStyle(), opt.isSelectItemInNewLine());
            case GROUP_BY -> SqlFormatterPostProcessor.isStacked(opt.getGroupbyColumnlistStyle(), false);
            case ORDER_BY -> SqlFormatterPostProcessor.isStacked(opt.getOrderbyColumnlistStyle(), false);
            case UPDATE_SET -> SqlFormatterPostProcessor.isStacked(opt.getUpdateSetlistStyle(), false);
            case INSERT_COLS -> SqlFormatterPostProcessor.isStacked(opt.getInsertColumnlistStyle(), false);
            case INSERT_VALUES -> SqlFormatterPostProcessor.isStacked(opt.getInsertValuelistStyle(), false);
            case CREATE_TABLE_ITEMS -> SqlFormatterPostProcessor.isStacked(opt.getCreateTableItemlistStyle(), false);
            default -> false;
        };
    }

    private static int effectiveIndent(
            int blockIndent, Deque<ParenKind> parenStack, Clause clause, String upper, GFmtOpt opt) {
        int base = blockIndent + subQueryDepth(parenStack);
        if (clause == Clause.CREATE_TABLE_ITEMS) {
            return base + 1;
        }
        if (clause == Clause.SELECT && !upper.equals("SELECT")) {
            return base + 1;
        }
        if (clause == Clause.FROM && !upper.equals("FROM") && opt.isFromClauseInNewline()) {
            return base + 1;
        }
        if (clause == Clause.JOIN && opt.isAlignJoinWithFromKeyword()) {
            return base + 1;
        }
        if ((clause == Clause.WHERE || clause == Clause.HAVING) && !upper.equals("WHERE") && !upper.equals("HAVING") && !upper.equals("QUALIFY")) {
            return base + 1;
        }
        if (clause == Clause.UPDATE_SET && !upper.equals("SET")) {
            return base + 1;
        }
        if ((clause == Clause.GROUP_BY && !upper.equals("GROUP")) || (clause == Clause.ORDER_BY && !upper.equals("ORDER"))) {
            return base + 1;
        }
        return base;
    }

    private static int subQueryDepth(Deque<ParenKind> stack) {
        int d = 0;
        for (ParenKind pk : stack) {
            if (pk == ParenKind.SUBQUERY) {
                d++;
            }
        }
        return d;
    }

    private static boolean shouldSpaceBefore(SqlToken tok, SqlToken prev, GFmtOpt opt) {
        if (prev == null || tok.isSymbol(".") || tok.isSymbol(",") || tok.isSymbol(";") || tok.isSymbol("::")) {
            return false;
        }
        if (prev.isSymbol(".") || prev.isSymbol("::")) {
            return false;
        }
        if (isBinaryOperator(tok, prev)) {
            return opt.isWsPaddingOperatorArithmetic();
        }
        return true;
    }

    private static boolean shouldSpaceAfter(SqlToken tok, SqlToken prev, GFmtOpt opt) {
        if (tok.isSymbol(".") || tok.isSymbol("::")) {
            return false;
        }
        if ((tok.isSymbol("+") || tok.isSymbol("-"))
                && (prev == null || prev.isSymbol("(") || prev.isSymbol(",") || prev.type() == TokenType.OPERATOR
                || prev.isAnyWord("SELECT", "WHERE", "AND", "OR", "WHEN", "THEN", "ELSE", "SET", "RETURN"))) {
            return false;
        }
        if (isBinaryOperator(tok, prev)) {
            return opt.isWsPaddingOperatorArithmetic();
        }
        return true;
    }

    private static boolean isBinaryOperator(SqlToken tok, SqlToken prev) {
        if (tok.type() != TokenType.OPERATOR) {
            return false;
        }
        if (tok.isSymbol("*") && (prev == null || prev.isSymbol(".") || prev.isSymbol("(") || prev.isSymbol(",")
                || prev.isAnyWord("SELECT", "DISTINCT"))) {
            return false;
        }
        return !tok.isSymbol("::") && !tok.isSymbol("@") && !tok.isSymbol("(+)")
                && !((tok.isSymbol("+") || tok.isSymbol("-"))
                && (prev == null || prev.isSymbol("(") || prev.isSymbol(",") || prev.type() == TokenType.OPERATOR));
    }

    private static boolean isJoinSequenceStart(List<SqlToken> tokens, int idx, SqlToken prev) {
        SqlToken cur = tokens.get(idx);
        if (cur.quoted()) {
            return false;
        }
        String u = cur.upperValue();
        if (prev != null && !prev.quoted() && JOIN_MODIFIERS.contains(prev.upperValue())) {
            return false;
        }
        if (u.equals("JOIN") || u.equals("STRAIGHT_JOIN")) {
            return true;
        }
        if (!JOIN_MODIFIERS.contains(u)) {
            return false;
        }
        for (int j = idx + 1; j < Math.min(tokens.size(), idx + 5); j++) {
            SqlToken t = tokens.get(j);
            if (t.isComment()) {
                continue;
            }
            if (t.isWord("JOIN") || t.isWord("APPLY")) {
                return true;
            }
            if (!JOIN_MODIFIERS.contains(t.upperValue())) {
                return false;
            }
        }
        return false;
    }

    private static boolean isProceduralEnd(SqlToken next) {
        return next == null || next.isSymbol(";") || next.isAnyWord("IF", "LOOP", "WHILE", "REPEAT", "FOR");
    }

    private static Clause replaceTop(Deque<Clause> stack, Clause next) {
        if (!stack.isEmpty()) {
            stack.pop();
        }
        stack.push(next);
        return next;
    }

    private static SqlToken findPrevCodeToken(List<SqlToken> tokens, int idx) {
        for (int j = idx - 1; j >= 0; j--) {
            if (!tokens.get(j).isComment() && !tokens.get(j).isEof()) {
                return tokens.get(j);
            }
        }
        return null;
    }

    private static SqlToken findNextCodeToken(List<SqlToken> tokens, int idx) {
        for (int j = idx + 1; j < tokens.size(); j++) {
            if (!tokens.get(j).isComment() && !tokens.get(j).isEof()) {
                return tokens.get(j);
            }
        }
        return null;
    }

    private static void newline(StringBuilder sb) {
        int len = sb.length();
        while (len > 0 && (sb.charAt(len - 1) == ' ' || sb.charAt(len - 1) == '\t')) {
            sb.setLength(--len);
        }
        if (len == 0 || sb.charAt(len - 1) != '\n') {
            sb.append('\n');
        }
    }

    private static void appendIndent(StringBuilder sb, String unit, int count) {
        for (int k = 0; k < count; k++) {
            sb.append(unit);
        }
    }
}
