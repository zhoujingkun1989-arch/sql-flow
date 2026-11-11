package io.sqlflow.lineage.demo;

import io.sqlflow.contracts.lineage.Coordinate;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.AstSqlRequest;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.SqlInjectionFinding;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.SqlInjectionResult;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.lineage.ast.Expr;
import io.sqlflow.lineage.ast.QueryAst.JoinClause;
import io.sqlflow.lineage.ast.QueryAst.NamedTableSource;
import io.sqlflow.lineage.ast.QueryAst.QueryExpr;
import io.sqlflow.lineage.ast.QueryAst.SelectItem;
import io.sqlflow.lineage.ast.QueryAst.SelectQuery;
import io.sqlflow.lineage.ast.QueryAst.SetOpQuery;
import io.sqlflow.lineage.ast.QueryAst.SubqueryTableSource;
import io.sqlflow.lineage.ast.QueryAst.TableSource;
import io.sqlflow.lineage.ast.SqlStmt;
import io.sqlflow.lineage.lexer.SourceSpan;
import io.sqlflow.lineage.lexer.SqlLexer;
import io.sqlflow.lineage.lexer.SqlToken;
import io.sqlflow.lineage.lexer.TokenType;
import io.sqlflow.lineage.parser.StatementParser;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Multi-dialect AST + lexical token SQL injection detector ({@code 02 §4.9 #5}, {@code OBL-064}).
 *
 * <p>Detects tautologies ({@code OR 1=1}, {@code 'a'='a'}), {@code UNION} piggybacking, stacked
 * destructive queries, comment truncation ({@code --}, {@code #}, {@code /*}), and blind
 * time-delay / out-of-band primitives ({@code SLEEP}, {@code WAITFOR DELAY}, {@code PG_SLEEP},
 * {@code DBMS_PIPE}).
 */
public final class AntiSqlInjectionDetector {

    private static final Set<String> TIME_DELAY_AND_DANGEROUS_FUNCS = Set.of(
            "SLEEP", "BENCHMARK", "PG_SLEEP", "PG_SLEEP_FOR", "PG_SLEEP_UNTIL",
            "RECEIVE_MESSAGE", "DBMS_PIPE.RECEIVE_MESSAGE", "DBMS_LOCK.SLEEP",
            "XP_CMDSHELL", "SP_OACREATE", "LOAD_FILE", "UTL_HTTP.REQUEST", "UTL_INADDR.GET_HOST_ADDRESS");

    private static final Set<String> SENSITIVE_CATALOGS = Set.of(
            "information_schema", "pg_catalog", "pg_shadow", "pg_authid", "pg_user",
            "mysql.user", "sys.sql_logins", "sys.tables", "sysobjects", "syscolumns",
            "all_users", "dba_users", "v$version", "v$instance", "sqlite_master");

    private static final Set<String> FINGERPRINT_FUNCS = Set.of(
            "VERSION", "DATABASE", "SCHEMA", "USER", "CURRENT_USER", "SYSTEM_USER", "SESSION_USER");

    public SqlInjectionResult detect(AstSqlRequest request) {
        String sql = request != null && request.getSql() != null ? request.getSql() : "";
        EDbVendor vendor = request != null && request.getDbvendor() != null
                ? request.getDbvendor()
                : EDbVendor.dbvoracle;
        if (sql.isBlank()) {
            return new SqlInjectionResult(false, "SQL injected not found.", List.of());
        }

        SqlLexer.LexResult lexed = SqlLexer.tokenize(sql, vendor);
        StatementParser.ParsedScript parsed = StatementParser.parseScript(sql, vendor);

        List<SqlInjectionFinding> findings = new ArrayList<>();
        Set<String> dedup = new LinkedHashSet<>();

        // 1. Stacked destructive / administrative queries
        checkStackedQueries(parsed.statements(), findings, dedup);

        // 2. AST analysis: tautologies, UNION piggybacking, and dangerous function calls
        boolean hasTrailingComment = !lexed.comments().isEmpty();
        for (SqlStmt stmt : parsed.statements()) {
            inspectStmtAst(stmt, hasTrailingComment, findings, dedup);
        }

        // 3. Lexical & comment-truncation / WAITFOR DELAY / INTO OUTFILE / fallback patterns
        checkTokenAndCommentPatterns(lexed.allTokens(), findings, dedup);

        if (findings.isEmpty()) {
            return new SqlInjectionResult(false, "SQL injected not found.", List.of());
        }
        return new SqlInjectionResult(true, "SQL injected found.", findings);
    }

    private static void checkStackedQueries(
            List<SqlStmt> stmts, List<SqlInjectionFinding> out, Set<String> dedup) {
        if (stmts.size() < 2) {
            return;
        }
        for (int i = 1; i < stmts.size(); i++) {
            SqlStmt s = stmts.get(i);
            if (s instanceof SqlStmt.DropStmt
                    || s instanceof SqlStmt.TruncateStmt
                    || s instanceof SqlStmt.DeleteStmt
                    || s instanceof SqlStmt.UpdateStmt
                    || s instanceof SqlStmt.InsertStmt
                    || s instanceof SqlStmt.AlterTableStmt
                    || s instanceof SqlStmt.CallStmt
                    || s instanceof SqlStmt.DynamicSqlStmt
                    || (s instanceof SqlStmt.NoOpStmt noop && Set.of("GRANT", "REVOKE").contains(noop.kind()))) {
                addFinding(out, dedup, "stacked_query",
                        "Stacked destructive or administrative SQL statement detected: " + s.getClass().getSimpleName(),
                        s.span());
            }
        }
    }

    private static void inspectStmtAst(
            SqlStmt stmt, boolean hasComments, List<SqlInjectionFinding> out, Set<String> dedup) {
        if (stmt instanceof SqlStmt.QueryStmt qs) {
            inspectQueryAst(qs.query(), hasComments, out, dedup);
        } else if (stmt instanceof SqlStmt.DeleteStmt del) {
            checkPredicateExpr(del.whereExpr(), true, hasComments, out, dedup);
        } else if (stmt instanceof SqlStmt.UpdateStmt upd) {
            checkPredicateExpr(upd.whereExpr(), true, hasComments, out, dedup);
        } else if (stmt instanceof SqlStmt.InsertStmt ins && ins.sourceQuery() != null) {
            inspectQueryAst(ins.sourceQuery(), hasComments, out, dedup);
        } else if (stmt instanceof SqlStmt.CallStmt call) {
            checkRoutineName(call.routineName().fullDisplayName(), call.span(), out, dedup);
        }
    }

    private static void inspectQueryAst(
            QueryExpr query, boolean hasComments, List<SqlInjectionFinding> out, Set<String> dedup) {
        if (query == null) {
            return;
        }
        if (query instanceof SetOpQuery setOp) {
            inspectQueryAst(setOp.left(), hasComments, out, dedup);
            inspectQueryAst(setOp.right(), hasComments, out, dedup);
            if (setOp.operator().startsWith("UNION") && isSuspiciousUnionBranch(setOp.right(), hasComments)) {
                addFinding(out, dedup, "union_piggybacking",
                        "Suspicious " + setOp.operator() + " piggybacking query detected",
                        setOp.right().span());
            }
            return;
        }
        if (query instanceof SelectQuery sq) {
            for (SelectItem item : sq.selectItems()) {
                scanFunctionsInExpr(item.expr(), out, dedup);
            }
            for (TableSource ts : sq.fromSources()) {
                if (ts instanceof SubqueryTableSource sub) {
                    inspectQueryAst(sub.query(), hasComments, out, dedup);
                }
            }
            for (JoinClause jc : sq.joins()) {
                checkPredicateExpr(jc.onCondition(), true, hasComments, out, dedup);
            }
            checkPredicateExpr(sq.whereExpr(), true, hasComments, out, dedup);
            checkPredicateExpr(sq.havingExpr(), true, hasComments, out, dedup);
        }
    }

    private static boolean isSuspiciousUnionBranch(QueryExpr right, boolean hasComments) {
        if (!(right instanceof SelectQuery sq)) {
            return false;
        }
        if (hasComments) {
            return true;
        }
        for (TableSource ts : sq.fromSources()) {
            if (ts instanceof NamedTableSource nts) {
                String tblLower = nts.tableName().fullDisplayName().toLowerCase(Locale.ROOT);
                for (String cat : SENSITIVE_CATALOGS) {
                    if (tblLower.contains(cat)) {
                        return true;
                    }
                }
            }
        }
        if (!sq.selectItems().isEmpty()) {
            boolean allConstantsOrSys = true;
            for (SelectItem si : sq.selectItems()) {
                Expr e = si.expr();
                if (e instanceof Expr.LiteralExpr) {
                    continue;
                }
                if (e instanceof Expr.VariableExpr ve && ve.rawName().startsWith("@@")) {
                    return true;
                }
                if (e instanceof Expr.FuncCallExpr fn
                        && FINGERPRINT_FUNCS.contains(fn.name().lastName().toUpperCase(Locale.ROOT))) {
                    return true;
                }
                if (e instanceof Expr.ColumnRefExpr cr
                        && FINGERPRINT_FUNCS.contains(cr.name().lastName().toUpperCase(Locale.ROOT))) {
                    return true;
                }
                allConstantsOrSys = false;
            }
            if (allConstantsOrSys && (sq.fromSources().isEmpty()
                    || (sq.fromSources().size() == 1 && sq.fromSources().get(0) instanceof NamedTableSource n
                    && "DUAL".equalsIgnoreCase(n.tableName().lastName())))) {
                return true;
            }
        }
        return false;
    }

    private static void checkPredicateExpr(
            Expr expr, boolean topWhereRoot, boolean hasComments, List<SqlInjectionFinding> out, Set<String> dedup) {
        if (expr == null) {
            return;
        }
        scanFunctionsInExpr(expr, out, dedup);
        if (expr instanceof Expr.BinaryExpr bin) {
            String op = bin.operator().toUpperCase(Locale.ROOT);
            if ("OR".equals(op)) {
                if (isAlwaysTruePredicate(bin.right(), true)) {
                    addFinding(out, dedup, "always_true_condition",
                            "Always-true tautology detected in OR branch", bin.right().span());
                }
                if (isAlwaysTruePredicate(bin.left(), true)) {
                    addFinding(out, dedup, "always_true_condition",
                            "Always-true tautology detected in OR branch", bin.left().span());
                }
                checkPredicateExpr(bin.left(), false, hasComments, out, dedup);
                checkPredicateExpr(bin.right(), false, hasComments, out, dedup);
                return;
            }
            if ("AND".equals(op)) {
                checkPredicateExpr(bin.left(), false, hasComments, out, dedup);
                checkPredicateExpr(bin.right(), false, hasComments, out, dedup);
                return;
            }
        }
        if (topWhereRoot && isStandaloneSuspiciousTautology(expr, hasComments)) {
            addFinding(out, dedup, "always_true_condition",
                    "Standalone tautology condition detected in WHERE clause", expr.span());
        }
    }

    private static boolean isStandaloneSuspiciousTautology(Expr expr, boolean hasComments) {
        if (expr instanceof Expr.BinaryExpr bin
                && bin.left() instanceof Expr.LiteralExpr l
                && bin.right() instanceof Expr.LiteralExpr r) {
            if ("STRING".equals(l.literalType()) || hasComments) {
                return isAlwaysTruePredicate(expr, false);
            }
        }
        return false;
    }

    private static boolean isAlwaysTruePredicate(Expr expr, boolean inOrBranch) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof Expr.LiteralExpr lit) {
            return "BOOLEAN".equals(lit.literalType()) && "TRUE".equalsIgnoreCase(lit.value())
                    || ("NUMBER".equals(lit.literalType()) && inOrBranch && !"0".equals(lit.value()));
        }
        if (expr instanceof Expr.UnaryExpr un && "NOT".equalsIgnoreCase(un.operator())
                && un.operand() instanceof Expr.LiteralExpr lit) {
            return "BOOLEAN".equals(lit.literalType()) && "FALSE".equalsIgnoreCase(lit.value());
        }
        if (expr instanceof Expr.BinaryExpr bin) {
            String op = bin.operator().toUpperCase(Locale.ROOT);
            if (inOrBranch && "=".equals(op)
                    && bin.left() instanceof Expr.ColumnRefExpr c1
                    && bin.right() instanceof Expr.ColumnRefExpr c2
                    && c1.name().fullDisplayName().equalsIgnoreCase(c2.name().fullDisplayName())) {
                return true;
            }
            Double leftNum = evalConstantNumber(bin.left());
            Double rightNum = evalConstantNumber(bin.right());
            if (leftNum != null && rightNum != null) {
                return switch (op) {
                    case "=", "==" -> Double.compare(leftNum, rightNum) == 0;
                    case "!=", "<>" -> Double.compare(leftNum, rightNum) != 0;
                    case ">" -> leftNum > rightNum;
                    case ">=" -> leftNum >= rightNum;
                    case "<" -> leftNum < rightNum;
                    case "<=" -> leftNum <= rightNum;
                    default -> false;
                };
            }
            if (bin.left() instanceof Expr.LiteralExpr l && bin.right() instanceof Expr.LiteralExpr r) {
                if ("=".equals(op) || "==".equals(op)) {
                    return l.value().equals(r.value());
                }
                if ("!=".equals(op) || "<>".equals(op)) {
                    return !l.value().equals(r.value());
                }
                if ("LIKE".equals(op)) {
                    return l.value().equals(r.value()) || "%".equals(r.value());
                }
            }
            if ("IS NOT".equals(op) && bin.left() instanceof Expr.LiteralExpr l && !"NULL".equals(l.literalType())
                    && bin.right() instanceof Expr.LiteralExpr r && "NULL".equals(r.literalType())) {
                return true;
            }
        }
        return false;
    }

    private static Double evalConstantNumber(Expr expr) {
        if (expr instanceof Expr.LiteralExpr lit && "NUMBER".equals(lit.literalType())) {
            try {
                return Double.parseDouble(lit.value());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (expr instanceof Expr.BinaryExpr bin) {
            Double l = evalConstantNumber(bin.left());
            Double r = evalConstantNumber(bin.right());
            if (l != null && r != null) {
                return switch (bin.operator()) {
                    case "+" -> l + r;
                    case "-" -> l - r;
                    case "*" -> l * r;
                    case "/" -> r != 0 ? l / r : null;
                    default -> null;
                };
            }
        }
        return null;
    }

    private static void scanFunctionsInExpr(Expr expr, List<SqlInjectionFinding> out, Set<String> dedup) {
        if (expr == null) {
            return;
        }
        if (expr instanceof Expr.FuncCallExpr fn) {
            checkRoutineName(fn.name().fullDisplayName(), fn.span(), out, dedup);
            for (Expr arg : fn.args()) {
                scanFunctionsInExpr(arg, out, dedup);
            }
        } else if (expr instanceof Expr.BinaryExpr bin) {
            scanFunctionsInExpr(bin.left(), out, dedup);
            scanFunctionsInExpr(bin.right(), out, dedup);
        } else if (expr instanceof Expr.UnaryExpr un) {
            scanFunctionsInExpr(un.operand(), out, dedup);
        } else if (expr instanceof Expr.CaseExpr ce) {
            scanFunctionsInExpr(ce.operand(), out, dedup);
            for (Expr.WhenClause wc : ce.whenClauses()) {
                scanFunctionsInExpr(wc.whenExpr(), out, dedup);
                scanFunctionsInExpr(wc.thenExpr(), out, dedup);
            }
            scanFunctionsInExpr(ce.elseExpr(), out, dedup);
        } else if (expr instanceof Expr.SubqueryExpr sub) {
            scanFunctionsInExpr(sub.testExpr(), out, dedup);
            inspectQueryAst(sub.query(), false, out, dedup);
        }
    }

    private static void checkRoutineName(
            String fullName, SourceSpan span, List<SqlInjectionFinding> out, Set<String> dedup) {
        String upper = fullName.toUpperCase(Locale.ROOT);
        int dot = upper.lastIndexOf('.');
        String shortName = dot >= 0 ? upper.substring(dot + 1) : upper;
        if (TIME_DELAY_AND_DANGEROUS_FUNCS.contains(upper)
                || TIME_DELAY_AND_DANGEROUS_FUNCS.contains(shortName)
                || upper.startsWith("DBMS_PIPE") || upper.startsWith("DBMS_LOCK")
                || upper.startsWith("UTL_HTTP") || upper.startsWith("UTL_INADDR")) {
            addFinding(out, dedup, "blind_time_delay",
                    "Time-delay or dangerous routine invocation detected: " + fullName, span);
        }
    }

    private static void checkTokenAndCommentPatterns(
            List<SqlToken> allTokens, List<SqlInjectionFinding> out, Set<String> dedup) {
        boolean inPredicateContext = false;
        int n = allTokens.size();
        for (int i = 0; i < n; i++) {
            SqlToken t = allTokens.get(i);
            if (t.isEof()) {
                break;
            }
            if (!t.isComment() && t.isAnyWord("WHERE", "HAVING", "ON", "VALUES", "UNION")) {
                inPredicateContext = true;
            }
            // T-SQL WAITFOR DELAY / WAITFOR TIME
            if (t.isWord("WAITFOR") && i + 1 < n && allTokens.get(i + 1).isAnyWord("DELAY", "TIME")) {
                addFinding(out, dedup, "blind_time_delay",
                        "T-SQL WAITFOR " + allTokens.get(i + 1).upperValue() + " blind time-delay detected", t.span());
            }
            // MySQL INTO OUTFILE / INTO DUMPFILE
            if (t.isWord("INTO") && i + 1 < n && allTokens.get(i + 1).isAnyWord("OUTFILE", "DUMPFILE")) {
                addFinding(out, dedup, "blind_time_delay",
                        "File exfiltration clause INTO " + allTokens.get(i + 1).upperValue() + " detected", t.span());
            }
            // Comment truncation & obfuscation
            if (t.type() == TokenType.LINE_COMMENT) {
                SqlToken prev = i > 0 ? allTokens.get(i - 1) : null;
                if (prev != null && !prev.isComment() && prev.endLine() == t.line() && inPredicateContext) {
                    addFinding(out, dedup, "comment_truncation",
                            "Trailing inline comment truncation (" + t.rawText().substring(0, Math.min(2, t.rawText().length())) + ") in query predicate",
                            t.span());
                }
            } else if (t.type() == TokenType.BLOCK_COMMENT) {
                if (t.rawText().startsWith("/*!")) {
                    addFinding(out, dedup, "comment_truncation",
                            "MySQL conditional execution comment (/*! ... */) detected", t.span());
                } else if (i > 0 && i + 1 < n) {
                    SqlToken prev = allTokens.get(i - 1);
                    SqlToken next = allTokens.get(i + 1);
                    if (!prev.isComment() && !next.isComment() && !next.isEof()
                            && prev.endOffset() == t.offset() && t.endOffset() == next.offset()) {
                        addFinding(out, dedup, "comment_truncation",
                                "Inline block comment keyword/token obfuscation detected", t.span());
                    }
                }
            }
        }
    }

    private static void addFinding(
            List<SqlInjectionFinding> out, Set<String> dedup, String type, String message, SourceSpan span) {
        long line = span != null && span.startLine() >= 1 ? span.startLine() : 1L;
        long col = span != null && span.startCol() >= 1 ? span.startCol() : 1L;
        String key = type + "@" + line + ":" + col;
        if (dedup.add(key)) {
            out.add(new SqlInjectionFinding(type, message, Coordinate.of(line, col)));
        }
    }
}
