package io.sqlflow.lineage.demo;

import io.sqlflow.contracts.lineage.Coordinate;
import io.sqlflow.contracts.lineage.ErrorItem;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.AstSqlRequest;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.RewriteSqlResult;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.XmlAstResult;
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
import io.sqlflow.lineage.lexer.SqlDialectRules;
import io.sqlflow.lineage.lexer.SqlLexer;
import io.sqlflow.lineage.lexer.SqlToken;
import io.sqlflow.lineage.lexer.TokenType;
import io.sqlflow.lineage.parser.StatementParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AST XML serializer and extended {@code /demo/*} AST inspection/transformation operations
 * ({@code joins}, {@code wherecondition}, {@code removecondition}, {@code alterscript},
 * {@code sqltranslate}, expression analyzer) ({@code 02 §4.9}, {@code OBL-066}).
 */
public final class AstXmlAndOpsHelper {

    public record JoinInfo(
            String leftTable,
            String rightTable,
            String joinType,
            String condition,
            List<Coordinate> coordinates
    ) {
    }

    public record WhereConditionInfo(
            String clause,
            String condition,
            List<Coordinate> coordinates
    ) {
    }

    public record ExpressionAnalysisItem(
            String context,
            String expression,
            List<String> operators,
            List<String> functions,
            List<String> columns,
            List<Coordinate> coordinates
    ) {
    }

    private AstXmlAndOpsHelper() {
    }

    static XmlAstResult toXml(AstSqlRequest request, SyntaxAndKeywordService syntaxService) {
        long startNanos = System.nanoTime();
        String sql = request != null && request.getSql() != null ? request.getSql() : "";
        EDbVendor vendor = request != null && request.getDbvendor() != null
                ? request.getDbvendor()
                : EDbVendor.dbvoracle;

        List<ErrorItem> errs = syntaxService.collectSyntaxErrors(sql, vendor);
        double elapsedSec = Math.max(0.001, (System.nanoTime() - startNanos) / 1_000_000_000.0);

        XmlAstResult res = new XmlAstResult();
        res.setGspVersion(SyntaxAndKeywordService.ENGINE_VERSION);
        res.setDbVendor(vendor.name());
        res.setUsedTime(String.format(Locale.ROOT, "%.3fs", elapsedSec));
        if (!errs.isEmpty()) {
            res.setErrors(errs);
            res.setErrorInfos(errs.stream().map(ErrorItem::getErrorMessage).toList());
            return res;
        }

        StatementParser.ParsedScript parsed = SyntaxAndKeywordService.parseScriptPackageAware(sql, vendor);
        StringBuilder xml = new StringBuilder();
        xml.append("<sqlscript dbvendor=\"").append(esc(vendor.name())).append("\">");
        for (SqlStmt stmt : parsed.statements()) {
            appendStmtXml(stmt, sql, xml);
        }
        xml.append("</sqlscript>");
        res.setXml(xml.toString());
        return res;
    }

    static List<JoinInfo> extractJoins(AstSqlRequest request) {
        String sql = request != null && request.getSql() != null ? request.getSql() : "";
        EDbVendor vendor = request != null && request.getDbvendor() != null ? request.getDbvendor() : EDbVendor.dbvoracle;
        StatementParser.ParsedScript parsed = SyntaxAndKeywordService.parseScriptPackageAware(sql, vendor);
        List<JoinInfo> out = new ArrayList<>();
        for (SqlStmt stmt : parsed.statements()) {
            collectJoinsFromStmt(stmt, sql, out);
        }
        return out;
    }

    static List<WhereConditionInfo> extractWhereConditions(AstSqlRequest request) {
        String sql = request != null && request.getSql() != null ? request.getSql() : "";
        EDbVendor vendor = request != null && request.getDbvendor() != null ? request.getDbvendor() : EDbVendor.dbvoracle;
        StatementParser.ParsedScript parsed = SyntaxAndKeywordService.parseScriptPackageAware(sql, vendor);
        List<WhereConditionInfo> out = new ArrayList<>();
        for (SqlStmt stmt : parsed.statements()) {
            collectWhereFromStmt(stmt, sql, out);
        }
        return out;
    }

    static RewriteSqlResult removeCondition(AstSqlRequest request, String targetColumnOrParam) {
        String sql = request != null && request.getSql() != null ? request.getSql() : "";
        if (sql.isBlank() || targetColumnOrParam == null || targetColumnOrParam.isBlank()) {
            return new RewriteSqlResult(sql, 0, null);
        }
        EDbVendor vendor = request.getDbvendor() != null ? request.getDbvendor() : EDbVendor.dbvoracle;
        StatementParser.ParsedScript parsed = SyntaxAndKeywordService.parseScriptPackageAware(sql, vendor);
        List<SpanReplacement> edits = new ArrayList<>();
        String needle = targetColumnOrParam.trim().toLowerCase(Locale.ROOT);
        for (SqlStmt stmt : parsed.statements()) {
            Expr where = extractTopWhereExpr(stmt);
            if (where != null) {
                PrunedExpr pruned = prunePredicate(where, sql, needle);
                if (pruned.removedCount > 0) {
                    if (pruned.remainingSql == null || pruned.remainingSql.isBlank()) {
                        int whereKwStart = findKeywordBeforeOffset(sql, where.span().startOffset(), "WHERE");
                        edits.add(new SpanReplacement(whereKwStart, where.span().endOffset(), "", pruned.removedCount));
                    } else {
                        edits.add(new SpanReplacement(where.span().startOffset(), where.span().endOffset(),
                                pruned.remainingSql, pruned.removedCount));
                    }
                }
            }
        }
        return applyEdits(sql, edits);
    }

    static RewriteSqlResult generateAlterScript(AstSqlRequest sourceDdl, AstSqlRequest targetDdl) {
        String srcSql = sourceDdl != null && sourceDdl.getSql() != null ? sourceDdl.getSql() : "";
        String tgtSql = targetDdl != null && targetDdl.getSql() != null ? targetDdl.getSql() : "";
        EDbVendor vendor = targetDdl != null && targetDdl.getDbvendor() != null
                ? targetDdl.getDbvendor()
                : (sourceDdl != null ? sourceDdl.getDbvendor() : EDbVendor.dbvpostgresql);

        Map<String, Map<String, SqlStmt.ColumnDef>> srcTables = parseCreateTables(srcSql, vendor);
        Map<String, Map<String, SqlStmt.ColumnDef>> tgtTables = parseCreateTables(tgtSql, vendor);

        List<String> stmts = new ArrayList<>();
        for (Map.Entry<String, Map<String, SqlStmt.ColumnDef>> entry : tgtTables.entrySet()) {
            String tbl = entry.getKey();
            Map<String, SqlStmt.ColumnDef> tgtCols = entry.getValue();
            Map<String, SqlStmt.ColumnDef> srcCols = srcTables.get(tbl);
            if (srcCols == null) {
                continue;
            }
            for (Map.Entry<String, SqlStmt.ColumnDef> tc : tgtCols.entrySet()) {
                SqlStmt.ColumnDef oldCol = srcCols.get(tc.getKey());
                SqlStmt.ColumnDef newCol = tc.getValue();
                if (oldCol == null) {
                    stmts.add("ALTER TABLE " + tbl + " ADD COLUMN " + newCol.name() + " " + newCol.dataType() + ";");
                } else if (!String.valueOf(oldCol.dataType()).equalsIgnoreCase(String.valueOf(newCol.dataType()))) {
                    String verb = (vendor == EDbVendor.dbvoracle || vendor == EDbVendor.dbvmysql)
                            ? "MODIFY COLUMN "
                            : "ALTER COLUMN ";
                    String typeKw = (vendor == EDbVendor.dbvpostgresql || vendor == EDbVendor.dbvsnowflake) ? " TYPE " : " ";
                    stmts.add("ALTER TABLE " + tbl + " " + verb + newCol.name() + typeKw + newCol.dataType() + ";");
                }
            }
            for (SqlStmt.ColumnDef oldCol : srcCols.values()) {
                if (!tgtCols.containsKey(oldCol.name().toLowerCase(Locale.ROOT))) {
                    stmts.add("ALTER TABLE " + tbl + " DROP COLUMN " + oldCol.name() + ";");
                }
            }
        }
        return new RewriteSqlResult(String.join("\n", stmts), stmts.size(), null);
    }

    static RewriteSqlResult sqlTranslate(AstSqlRequest request, EDbVendor targetVendor) {
        String sql = request != null && request.getSql() != null ? request.getSql() : "";
        if (sql.isBlank() || targetVendor == null) {
            return new RewriteSqlResult(sql, 0, null);
        }
        EDbVendor srcVendor = request.getDbvendor() != null ? request.getDbvendor() : EDbVendor.dbvoracle;
        SqlLexer.LexResult lexed = SqlLexer.tokenize(sql, srcVendor);
        List<SpanReplacement> edits = new ArrayList<>();

        for (int i = 0; i < lexed.tokens().size(); i++) {
            SqlToken t = lexed.tokens().get(i);
            if (t.isEof()) {
                break;
            }
            SqlToken next = i + 1 < lexed.tokens().size() ? lexed.tokens().get(i + 1) : null;
            String u = t.upperValue();
            if (t.type() == TokenType.QUOTED_IDENTIFIER) {
                String requoted = requoteForVendor(t.value(), targetVendor);
                if (!requoted.equals(t.rawText())) {
                    edits.add(new SpanReplacement(t.offset(), t.endOffset(), requoted, 1));
                }
            } else if (!t.quoted()) {
                if ((u.equals("NVL") || u.equals("IFNULL") || u.equals("ISNULL")) && next != null && next.isSymbol("(")) {
                    String fn = mapNullFunc(targetVendor);
                    if (!fn.equalsIgnoreCase(u)) {
                        edits.add(new SpanReplacement(t.offset(), t.endOffset(), fn, 1));
                    }
                } else if (u.equals("SYSDATE") && (next == null || !next.isSymbol("("))) {
                    String ts = mapNowExpr(targetVendor);
                    if (!ts.equalsIgnoreCase(u)) {
                        edits.add(new SpanReplacement(t.offset(), t.endOffset(), ts, 1));
                    }
                } else if (u.equals("GETDATE") && next != null && next.isSymbol("(")
                        && i + 2 < lexed.tokens().size() && lexed.tokens().get(i + 2).isSymbol(")")) {
                    String ts = mapNowExpr(targetVendor);
                    if (!"GETDATE()".equalsIgnoreCase(ts)) {
                        edits.add(new SpanReplacement(t.offset(), lexed.tokens().get(i + 2).endOffset(), ts, 1));
                        i += 2;
                    }
                } else if (u.equals("VARCHAR2") || u.equals("NVARCHAR2")) {
                    if (targetVendor != EDbVendor.dbvoracle && targetVendor != EDbVendor.dbvdameng) {
                        edits.add(new SpanReplacement(t.offset(), t.endOffset(), "VARCHAR", 1));
                    }
                } else if (u.equals("NUMBER") && targetVendor != EDbVendor.dbvoracle && targetVendor != EDbVendor.dbvsnowflake) {
                    edits.add(new SpanReplacement(t.offset(), t.endOffset(), "NUMERIC", 1));
                }
            }
        }
        RewriteSqlResult step1 = applyEdits(sql, edits);
        return rewriteTopOrRownumPagination(step1.getSql(), step1.getReplacements(), targetVendor);
    }

    static List<ExpressionAnalysisItem> analyzeExpressions(AstSqlRequest request) {
        String sql = request != null && request.getSql() != null ? request.getSql() : "";
        EDbVendor vendor = request != null && request.getDbvendor() != null ? request.getDbvendor() : EDbVendor.dbvoracle;
        StatementParser.ParsedScript parsed = SyntaxAndKeywordService.parseScriptPackageAware(sql, vendor);
        List<ExpressionAnalysisItem> out = new ArrayList<>();
        for (SqlStmt stmt : parsed.statements()) {
            if (stmt instanceof SqlStmt.QueryStmt qs) {
                collectExprAnalysisInQuery(qs.query(), sql, out);
            } else if (stmt instanceof SqlStmt.CreateViewStmt cv) {
                collectExprAnalysisInQuery(cv.query(), sql, out);
            }
        }
        return out;
    }

    private static void appendStmtXml(SqlStmt stmt, String sql, StringBuilder xml) {
        SourceSpan sp = stmt.span();
        String type = stmt.getClass().getSimpleName();
        xml.append("<statement type=\"").append(esc(type)).append("\" line=\"")
                .append(Math.max(1L, sp.startLine())).append("\" column=\"")
                .append(Math.max(1L, sp.startCol())).append("\">");
        if (stmt instanceof SqlStmt.QueryStmt qs) {
            appendQueryXml(qs.query(), sql, xml);
        } else if (stmt instanceof SqlStmt.CreateTableStmt ct) {
            xml.append("<table name=\"").append(esc(ct.tableName().fullDisplayName())).append("\">");
            for (SqlStmt.ColumnDef c : ct.columns()) {
                xml.append("<column name=\"").append(esc(c.name())).append("\" datatype=\"")
                        .append(esc(c.dataType())).append("\"/>");
            }
            xml.append("</table>");
        } else if (stmt instanceof SqlStmt.CreateViewStmt cv) {
            xml.append("<view name=\"").append(esc(cv.viewName().fullDisplayName())).append("\"/>");
            appendQueryXml(cv.query(), sql, xml);
        } else {
            xml.append("<text>").append(esc(sp.extractText(sql))).append("</text>");
        }
        xml.append("</statement>");
    }

    private static void appendQueryXml(QueryExpr q, String sql, StringBuilder xml) {
        if (q instanceof SelectQuery sq) {
            xml.append("<select_list>");
            for (SelectItem item : sq.selectItems()) {
                xml.append("<result_column alias=\"").append(esc(item.effectiveOutputName())).append("\">")
                        .append(esc(item.span().extractText(sql))).append("</result_column>");
            }
            xml.append("</select_list>");
            if (!sq.fromSources().isEmpty()) {
                xml.append("<from_clause>");
                for (TableSource ts : sq.fromSources()) {
                    xml.append("<table_source>").append(esc(ts.span().extractText(sql))).append("</table_source>");
                }
                for (JoinClause jc : sq.joins()) {
                    xml.append("<join type=\"").append(esc(jc.joinType())).append("\">")
                            .append(esc(jc.span().extractText(sql))).append("</join>");
                }
                xml.append("</from_clause>");
            }
            if (sq.whereExpr() != null) {
                xml.append("<where_clause>").append(esc(sq.whereExpr().span().extractText(sql))).append("</where_clause>");
            }
        } else if (q instanceof SetOpQuery setOp) {
            xml.append("<set_operation operator=\"").append(esc(setOp.operator())).append("\">");
            appendQueryXml(setOp.left(), sql, xml);
            appendQueryXml(setOp.right(), sql, xml);
            xml.append("</set_operation>");
        }
    }

    private static void collectJoinsFromStmt(SqlStmt stmt, String sql, List<JoinInfo> out) {
        if (stmt instanceof SqlStmt.QueryStmt qs) {
            collectJoinsFromQuery(qs.query(), sql, out);
        } else if (stmt instanceof SqlStmt.CreateViewStmt cv) {
            collectJoinsFromQuery(cv.query(), sql, out);
        } else if (stmt instanceof SqlStmt.InsertStmt ins && ins.sourceQuery() != null) {
            collectJoinsFromQuery(ins.sourceQuery(), sql, out);
        }
    }

    private static void collectJoinsFromQuery(QueryExpr q, String sql, List<JoinInfo> out) {
        if (q instanceof SetOpQuery so) {
            collectJoinsFromQuery(so.left(), sql, out);
            collectJoinsFromQuery(so.right(), sql, out);
        } else if (q instanceof SelectQuery sq) {
            String leftName = sq.fromSources().isEmpty() ? "" : tableSourceLabel(sq.fromSources().get(0), sql);
            for (int i = 1; i < sq.fromSources().size(); i++) {
                TableSource rhs = sq.fromSources().get(i);
                String cond = sq.whereExpr() != null ? sq.whereExpr().span().extractText(sql) : "";
                String jt = cond.contains("(+)") ? "IMPLICIT_OUTER" : "IMPLICIT";
                out.add(new JoinInfo(leftName, tableSourceLabel(rhs, sql), jt, cond, rhs.span().toCoordinates(null)));
            }
            for (JoinClause jc : sq.joins()) {
                String cond = jc.onCondition() != null
                        ? jc.onCondition().span().extractText(sql)
                        : (!jc.usingColumns().isEmpty()
                        ? "USING (" + String.join(", ", jc.usingColumns()) + ")"
                        : ("IMPLICIT".equalsIgnoreCase(jc.joinType()) && sq.whereExpr() != null
                        ? sq.whereExpr().span().extractText(sql) : ""));
                out.add(new JoinInfo(leftName, tableSourceLabel(jc.right(), sql), jc.joinType(), cond,
                        jc.span().toCoordinates(null)));
            }
            for (TableSource ts : sq.fromSources()) {
                if (ts instanceof SubqueryTableSource sub) {
                    collectJoinsFromQuery(sub.query(), sql, out);
                }
            }
        }
    }

    private static void collectWhereFromStmt(SqlStmt stmt, String sql, List<WhereConditionInfo> out) {
        Expr w = extractTopWhereExpr(stmt);
        if (w != null && !(stmt instanceof SqlStmt.QueryStmt)) {
            out.add(new WhereConditionInfo("WHERE", w.span().extractText(sql), w.span().toCoordinates(null)));
        }
        if (stmt instanceof SqlStmt.QueryStmt qs) {
            collectWhereFromQuery(qs.query(), sql, out);
        } else if (stmt instanceof SqlStmt.CreateViewStmt cv) {
            collectWhereFromQuery(cv.query(), sql, out);
        }
    }

    private static void collectWhereFromQuery(QueryExpr q, String sql, List<WhereConditionInfo> out) {
        if (q instanceof SetOpQuery so) {
            collectWhereFromQuery(so.left(), sql, out);
            collectWhereFromQuery(so.right(), sql, out);
        } else if (q instanceof SelectQuery sq) {
            if (sq.whereExpr() != null) {
                out.add(new WhereConditionInfo("WHERE", sq.whereExpr().span().extractText(sql),
                        sq.whereExpr().span().toCoordinates(null)));
            }
            if (sq.havingExpr() != null) {
                out.add(new WhereConditionInfo("HAVING", sq.havingExpr().span().extractText(sql),
                        sq.havingExpr().span().toCoordinates(null)));
            }
            if (sq.qualifyExpr() != null) {
                out.add(new WhereConditionInfo("QUALIFY", sq.qualifyExpr().span().extractText(sql),
                        sq.qualifyExpr().span().toCoordinates(null)));
            }
        }
    }

    private static Expr extractTopWhereExpr(SqlStmt stmt) {
        if (stmt instanceof SqlStmt.QueryStmt qs && qs.query() instanceof SelectQuery sq) {
            return sq.whereExpr();
        }
        if (stmt instanceof SqlStmt.UpdateStmt u) {
            return u.whereExpr();
        }
        if (stmt instanceof SqlStmt.DeleteStmt d) {
            return d.whereExpr();
        }
        return null;
    }

    private record PrunedExpr(String remainingSql, int removedCount) {
    }

    private static PrunedExpr prunePredicate(Expr expr, String sql, String needleLower) {
        if (expr instanceof Expr.BinaryExpr bin
                && ("AND".equalsIgnoreCase(bin.operator()) || "OR".equalsIgnoreCase(bin.operator()))) {
            PrunedExpr l = prunePredicate(bin.left(), sql, needleLower);
            PrunedExpr r = prunePredicate(bin.right(), sql, needleLower);
            int removed = l.removedCount + r.removedCount;
            if (l.remainingSql == null) {
                return new PrunedExpr(r.remainingSql, removed);
            }
            if (r.remainingSql == null) {
                return new PrunedExpr(l.remainingSql, removed);
            }
            return new PrunedExpr(l.remainingSql + " " + bin.operator().toUpperCase(Locale.ROOT) + " " + r.remainingSql, removed);
        }
        String snippet = expr.span().extractText(sql).trim();
        if (exprReferencesTarget(expr, snippet, needleLower)) {
            return new PrunedExpr(null, 1);
        }
        return new PrunedExpr(snippet, 0);
    }

    private static boolean exprReferencesTarget(Expr expr, String snippet, String needleLower) {
        if (snippet.toLowerCase(Locale.ROOT).contains(needleLower)) {
            Set<String> cols = new LinkedHashSet<>();
            Set<String> ops = new LinkedHashSet<>();
            Set<String> fns = new LinkedHashSet<>();
            walkExprTokens(expr, ops, fns, cols);
            for (String c : cols) {
                String cl = c.toLowerCase(Locale.ROOT);
                if (cl.equals(needleLower) || cl.endsWith("." + needleLower)) {
                    return true;
                }
            }
            return needleLower.startsWith(":") || needleLower.startsWith("@") || needleLower.contains("=");
        }
        return false;
    }

    private static void collectExprAnalysisInQuery(QueryExpr q, String sql, List<ExpressionAnalysisItem> out) {
        if (q instanceof SelectQuery sq) {
            for (SelectItem si : sq.selectItems()) {
                recordExprItem("SELECT", si.expr(), sql, out);
            }
            if (sq.whereExpr() != null) {
                recordExprItem("WHERE", sq.whereExpr(), sql, out);
            }
        } else if (q instanceof SetOpQuery so) {
            collectExprAnalysisInQuery(so.left(), sql, out);
            collectExprAnalysisInQuery(so.right(), sql, out);
        }
    }

    private static void recordExprItem(String ctx, Expr e, String sql, List<ExpressionAnalysisItem> out) {
        if (e == null) {
            return;
        }
        Set<String> ops = new LinkedHashSet<>();
        Set<String> fns = new LinkedHashSet<>();
        Set<String> cols = new LinkedHashSet<>();
        walkExprTokens(e, ops, fns, cols);
        out.add(new ExpressionAnalysisItem(ctx, e.span().extractText(sql),
                new ArrayList<>(ops), new ArrayList<>(fns), new ArrayList<>(cols), e.span().toCoordinates(null)));
    }

    private static void walkExprTokens(Expr e, Set<String> ops, Set<String> fns, Set<String> cols) {
        if (e == null) {
            return;
        }
        if (e instanceof Expr.ColumnRefExpr c) {
            cols.add(c.name().fullDisplayName());
        } else if (e instanceof Expr.VariableExpr v) {
            cols.add(v.rawName());
        } else if (e instanceof Expr.FuncCallExpr fn) {
            fns.add(fn.name().fullDisplayName());
            for (Expr a : fn.args()) {
                walkExprTokens(a, ops, fns, cols);
            }
        } else if (e instanceof Expr.BinaryExpr b) {
            ops.add(b.operator());
            walkExprTokens(b.left(), ops, fns, cols);
            walkExprTokens(b.right(), ops, fns, cols);
        } else if (e instanceof Expr.UnaryExpr u) {
            ops.add(u.operator());
            walkExprTokens(u.operand(), ops, fns, cols);
        } else if (e instanceof Expr.CaseExpr ce) {
            walkExprTokens(ce.operand(), ops, fns, cols);
            for (Expr.WhenClause wc : ce.whenClauses()) {
                walkExprTokens(wc.whenExpr(), ops, fns, cols);
                walkExprTokens(wc.thenExpr(), ops, fns, cols);
            }
            walkExprTokens(ce.elseExpr(), ops, fns, cols);
        } else if (e instanceof Expr.CastExpr c) {
            fns.add("CAST");
            walkExprTokens(c.expr(), ops, fns, cols);
        } else if (e instanceof Expr.InListExpr in) {
            ops.add(in.negated() ? "NOT IN" : "IN");
            walkExprTokens(in.expr(), ops, fns, cols);
            for (Expr item : in.items()) {
                walkExprTokens(item, ops, fns, cols);
            }
        } else if (e instanceof Expr.BetweenExpr bw) {
            ops.add(bw.negated() ? "NOT BETWEEN" : "BETWEEN");
            walkExprTokens(bw.expr(), ops, fns, cols);
            walkExprTokens(bw.low(), ops, fns, cols);
            walkExprTokens(bw.high(), ops, fns, cols);
        }
    }

    private static Map<String, Map<String, SqlStmt.ColumnDef>> parseCreateTables(String ddl, EDbVendor vendor) {
        Map<String, Map<String, SqlStmt.ColumnDef>> map = new LinkedHashMap<>();
        for (SqlStmt s : StatementParser.parseScript(ddl, vendor).statements()) {
            if (s instanceof SqlStmt.CreateTableStmt ct) {
                Map<String, SqlStmt.ColumnDef> cols = new LinkedHashMap<>();
                for (SqlStmt.ColumnDef c : ct.columns()) {
                    cols.put(c.name().toLowerCase(Locale.ROOT), c);
                }
                map.put(ct.tableName().fullDisplayName(), cols);
            }
        }
        return map;
    }

    private static String requoteForVendor(String ident, EDbVendor target) {
        if (SqlDialectRules.supportsBacktickQuote(target) && target != EDbVendor.dbvsqlite && target != EDbVendor.dbvduckdb) {
            return "`" + ident + "`";
        }
        if (target == EDbVendor.dbvmssql || target == EDbVendor.dbvazuresql || target == EDbVendor.dbvsybase) {
            return "[" + ident + "]";
        }
        return "\"" + ident + "\"";
    }

    private static String mapNullFunc(EDbVendor target) {
        if (target == EDbVendor.dbvoracle) {
            return "NVL";
        }
        if (target == EDbVendor.dbvmysql) {
            return "IFNULL";
        }
        if (target == EDbVendor.dbvmssql || target == EDbVendor.dbvazuresql) {
            return "ISNULL";
        }
        return "COALESCE";
    }

    private static String mapNowExpr(EDbVendor target) {
        if (target == EDbVendor.dbvoracle) {
            return "SYSDATE";
        }
        if (target == EDbVendor.dbvmssql || target == EDbVendor.dbvazuresql) {
            return "GETDATE()";
        }
        return "CURRENT_TIMESTAMP";
    }

    private static RewriteSqlResult rewriteTopOrRownumPagination(String sql, int baseEdits, EDbVendor target) {
        boolean targetUsesTop = target == EDbVendor.dbvmssql || target == EDbVendor.dbvazuresql;
        if (!targetUsesTop && target != EDbVendor.dbvoracle) {
            Matcher topM = Pattern.compile("(?is)\\bSELECT\\s+TOP\\s+\\(?(\\d+)\\)?\\s+(.*?)(?=;|\\z)").matcher(sql);
            if (topM.find()) {
                String body = topM.group(2).trim();
                String rep = "SELECT " + body + " LIMIT " + topM.group(1);
                String out = sql.substring(0, topM.start()) + rep + sql.substring(topM.end());
                return new RewriteSqlResult(out, baseEdits + 1, null);
            }
            Matcher rnM = Pattern.compile("(?is)\\bWHERE\\s+ROWNUM\\s*<=\\s*(\\d+)\\b").matcher(sql);
            if (rnM.find()) {
                String out = sql.substring(0, rnM.start()) + "LIMIT " + rnM.group(1) + sql.substring(rnM.end());
                return new RewriteSqlResult(out, baseEdits + 1, null);
            }
        }
        return new RewriteSqlResult(sql, baseEdits, null);
    }

    private static int findKeywordBeforeOffset(String sql, int exprStart, String kw) {
        String prefix = sql.substring(0, Math.min(sql.length(), exprStart));
        Matcher m = Pattern.compile("(?i)\\b" + kw + "\\s*$").matcher(prefix);
        if (m.find()) {
            int start = m.start();
            while (start > 0 && (sql.charAt(start - 1) == ' ' || sql.charAt(start - 1) == '\t')) {
                start--;
            }
            return start;
        }
        return exprStart;
    }

    private static String tableSourceLabel(TableSource ts, String sql) {
        if (ts instanceof NamedTableSource nts) {
            return nts.tableName().fullDisplayName();
        }
        return ts.alias() != null ? ts.alias() : ts.span().extractText(sql);
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    record SpanReplacement(int startOffset, int endOffset, String replacement, int count) {
    }

    static RewriteSqlResult applyEdits(String sql, List<SpanReplacement> edits) {
        if (edits.isEmpty()) {
            return new RewriteSqlResult(sql, 0, null);
        }
        List<SpanReplacement> sorted = new ArrayList<>(edits);
        sorted.sort((a, b) -> Integer.compare(b.startOffset, a.startOffset));
        StringBuilder sb = new StringBuilder(sql);
        int total = 0;
        int lastStart = Integer.MAX_VALUE;
        for (SpanReplacement ed : sorted) {
            if (ed.endOffset <= lastStart && ed.startOffset >= 0 && ed.endOffset <= sb.length()) {
                sb.replace(ed.startOffset, ed.endOffset, ed.replacement);
                lastStart = ed.startOffset;
                total += ed.count;
            }
        }
        return new RewriteSqlResult(sb.toString().trim(), total, null);
    }
}
