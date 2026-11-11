package io.sqlflow.lineage.demo;

import io.sqlflow.contracts.spi.DemoAstServiceSpi.JoinConvertRequest;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.RemoveSchemaRequest;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.RenameColumnRequest;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.RenameTableRequest;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.RewriteSqlResult;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.lineage.ast.Expr;
import io.sqlflow.lineage.ast.QueryAst.JoinClause;
import io.sqlflow.lineage.ast.QueryAst.NamedTableSource;
import io.sqlflow.lineage.ast.QueryAst.SelectQuery;
import io.sqlflow.lineage.ast.QueryAst.TableSource;
import io.sqlflow.lineage.ast.SqlStmt;
import io.sqlflow.lineage.demo.AstXmlAndOpsHelper.SpanReplacement;
import io.sqlflow.lineage.lexer.SourceSpan;
import io.sqlflow.lineage.lexer.SqlLexer;
import io.sqlflow.lineage.lexer.SqlToken;
import io.sqlflow.lineage.parser.StatementParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AST & lexical-token-aware SQL refactoring operations: {@code joinConvert}, {@code renameTable},
 * {@code renameColumn}, and {@code removeSchema} ({@code 02 §4.9 #4, #6, #7, #8}, {@code OBL-066}).
 */
final class SqlRefactorHelper {

    private SqlRefactorHelper() {
    }

    static RewriteSqlResult joinConvert(JoinConvertRequest request) {
        String sql = request != null && request.getSql() != null ? request.getSql() : "";
        if (sql.isBlank()) {
            return new RewriteSqlResult(sql, 0, null);
        }
        EDbVendor vendor = request.getDbvendor() != null ? request.getDbvendor() : EDbVendor.dbvoracle;
        String style = request.getTargetStyle() != null ? request.getTargetStyle().trim().toUpperCase(Locale.ROOT) : "ANSI";

        StatementParser.ParsedScript parsed = StatementParser.parseScript(sql, vendor);
        List<SpanReplacement> edits = new ArrayList<>();

        for (SqlStmt stmt : parsed.statements()) {
            if (stmt instanceof SqlStmt.QueryStmt qs && qs.query() instanceof SelectQuery sq) {
                if ("IMPLICIT".equals(style) || "ORACLE".equals(style)) {
                    convertAnsiToImplicit(sq, sql, "ORACLE".equals(style), edits);
                } else {
                    convertImplicitToAnsi(sq, sql, edits);
                }
            }
        }
        return AstXmlAndOpsHelper.applyEdits(sql, edits);
    }

    private static void convertImplicitToAnsi(SelectQuery sq, String sql, List<SpanReplacement> edits) {
        boolean hasImplicit = sq.joins().stream().anyMatch(j -> "IMPLICIT".equalsIgnoreCase(j.joinType()));
        if (sq.fromSources().isEmpty() || (!hasImplicit && sq.fromSources().size() < 2)) {
            return;
        }
        List<TableSource> allImplicitRhs = new ArrayList<>();
        for (int i = 1; i < sq.fromSources().size(); i++) {
            allImplicitRhs.add(sq.fromSources().get(i));
        }
        SourceSpan lastFromSpan = sq.fromSources().get(sq.fromSources().size() - 1).span();
        for (JoinClause jc : sq.joins()) {
            if ("IMPLICIT".equalsIgnoreCase(jc.joinType())) {
                allImplicitRhs.add(jc.right());
                lastFromSpan = jc.span();
            } else {
                return;
            }
        }
        List<Expr> conjuncts = new ArrayList<>();
        flattenAndConjuncts(sq.whereExpr(), conjuncts);

        TableSource first = sq.fromSources().get(0);
        Set<String> seenQualifiers = qualifiersForSource(first);
        StringBuilder fromBuilder = new StringBuilder(first.span().extractText(sql));
        Set<Expr> consumedPredicates = new LinkedHashSet<>();

        for (TableSource rhs : allImplicitRhs) {
            Set<String> rhsQuals = qualifiersForSource(rhs);
            List<String> onParts = new ArrayList<>();
            String joinKw = null;

            for (Expr cond : conjuncts) {
                if (consumedPredicates.contains(cond)) {
                    continue;
                }
                String rawCond = cond.span().extractText(sql).trim();
                if (referencesBothSides(cond, seenQualifiers, rhsQuals)) {
                    consumedPredicates.add(cond);
                    if (joinKw == null) {
                        joinKw = determineOuterJoinFromPlus(cond, rhsQuals);
                    }
                    onParts.add(rawCond.replace("(+)", "").replaceAll("\\s+", " ").trim());
                }
            }
            seenQualifiers.addAll(rhsQuals);
            String rhsText = rhs.span().extractText(sql);
            if (onParts.isEmpty()) {
                fromBuilder.append(" CROSS JOIN ").append(rhsText);
            } else {
                fromBuilder.append(' ').append(joinKw != null ? joinKw : "INNER JOIN").append(' ')
                        .append(rhsText).append(" ON ").append(String.join(" AND ", onParts));
            }
        }

        int fromStart = first.span().startOffset();
        int replaceEnd = lastFromSpan.endOffset();
        if (sq.whereExpr() != null) {
            replaceEnd = sq.whereExpr().span().endOffset();
            List<String> remainingWhere = new ArrayList<>();
            for (Expr cond : conjuncts) {
                if (!consumedPredicates.contains(cond)) {
                    remainingWhere.add(cond.span().extractText(sql).trim());
                }
            }
            if (!remainingWhere.isEmpty()) {
                fromBuilder.append(" WHERE ").append(String.join(" AND ", remainingWhere));
            }
        }
        edits.add(new SpanReplacement(fromStart, replaceEnd, fromBuilder.toString(), allImplicitRhs.size()));
    }

    private static void convertAnsiToImplicit(
            SelectQuery sq, String sql, boolean oraclePlus, List<SpanReplacement> edits) {
        if (sq.fromSources().size() != 1 || sq.joins().isEmpty()) {
            return;
        }
        TableSource first = sq.fromSources().get(0);
        StringBuilder tables = new StringBuilder(first.span().extractText(sql));
        List<String> predicates = new ArrayList<>();
        for (JoinClause jc : sq.joins()) {
            tables.append(", ").append(jc.right().span().extractText(sql));
            if (jc.onCondition() != null) {
                String cond = jc.onCondition().span().extractText(sql).trim();
                if (oraclePlus && jc.joinType().contains("LEFT") && cond.contains("=")) {
                    cond = cond + "(+)";
                }
                predicates.add(cond);
            }
        }
        if (sq.whereExpr() != null) {
            predicates.add(sq.whereExpr().span().extractText(sql).trim());
        }
        int endOffset = sq.whereExpr() != null
                ? sq.whereExpr().span().endOffset()
                : sq.joins().get(sq.joins().size() - 1).span().endOffset();
        String rep = predicates.isEmpty() ? tables.toString() : tables + " WHERE " + String.join(" AND ", predicates);
        edits.add(new SpanReplacement(first.span().startOffset(), endOffset, rep, sq.joins().size()));
    }

    static RewriteSqlResult renameTable(RenameTableRequest request) {
        String sql = request != null && request.getSql() != null ? request.getSql() : "";
        String src = request != null ? request.getSourceTable() : null;
        String tgt = request != null ? request.getTargetTable() : null;
        if (sql.isBlank() || src == null || src.isBlank() || tgt == null || tgt.isBlank()) {
            return new RewriteSqlResult(sql, 0, null);
        }
        EDbVendor vendor = request.getDbvendor() != null ? request.getDbvendor() : EDbVendor.dbvoracle;
        List<SqlToken> tokens = SqlLexer.tokenize(sql, vendor).tokens();
        String[] srcParts = src.trim().split("\\.");
        String tgtShort = tgt.contains(".") ? tgt.substring(tgt.lastIndexOf('.') + 1) : tgt.trim();

        List<SpanReplacement> edits = new ArrayList<>();
        int step = srcParts.length * 2 - 1;
        for (int i = 0; i < tokens.size(); i++) {
            SqlToken t = tokens.get(i);
            if (t.isEof()) {
                break;
            }
            if (matchesMultipart(tokens, i, srcParts)) {
                SqlToken endTok = tokens.get(i + step - 1);
                boolean followedByDot = i + step < tokens.size() && tokens.get(i + step).isSymbol(".");
                boolean precededByDot = i > 0 && tokens.get(i - 1).isSymbol(".");
                if (!precededByDot) {
                    String replacement = followedByDot ? tgtShort : tgt.trim();
                    edits.add(new SpanReplacement(t.offset(), endTok.endOffset(), replacement, 1));
                    i += step - 1;
                }
            }
        }
        return AstXmlAndOpsHelper.applyEdits(sql, edits);
    }

    static RewriteSqlResult renameColumn(RenameColumnRequest request) {
        String sql = request != null && request.getSql() != null ? request.getSql() : "";
        String srcSpec = request != null ? request.getSourceColumn() : null;
        String tgtCol = request != null ? request.getTargetColumn() : null;
        if (sql.isBlank() || srcSpec == null || srcSpec.isBlank() || tgtCol == null || tgtCol.isBlank()) {
            return new RewriteSqlResult(sql, 0, null);
        }
        String cleanTarget = tgtCol.contains(".") ? tgtCol.substring(tgtCol.lastIndexOf('.') + 1) : tgtCol.trim();
        EDbVendor vendor = request.getDbvendor() != null ? request.getDbvendor() : EDbVendor.dbvoracle;

        String expectedTable = null;
        String oldCol = srcSpec.trim();
        int lastDot = oldCol.lastIndexOf('.');
        if (lastDot > 0) {
            expectedTable = oldCol.substring(0, lastDot);
            oldCol = oldCol.substring(lastDot + 1);
        }

        Map<String, String> aliasMap = extractSimpleAliasMap(sql, vendor);
        List<SqlToken> tokens = SqlLexer.tokenize(sql, vendor).tokens();
        List<SpanReplacement> edits = new ArrayList<>();

        for (int i = 0; i < tokens.size(); i++) {
            SqlToken t = tokens.get(i);
            if (t.isEof()) {
                break;
            }
            if (!t.isIdentifierLike() || !t.value().equalsIgnoreCase(oldCol)) {
                continue;
            }
            SqlToken next = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
            if (next != null && next.isSymbol(".")) {
                continue; // table or schema prefix, not column leaf
            }
            SqlToken prev = i > 0 ? tokens.get(i - 1) : null;
            if (prev != null && prev.isAnyWord("FROM", "JOIN", "INTO", "UPDATE", "TABLE")) {
                continue; // table identifier
            }
            if (expectedTable != null && prev != null && prev.isSymbol(".") && i >= 2) {
                String qual = tokens.get(i - 2).value().toLowerCase(Locale.ROOT);
                String resolved = aliasMap.getOrDefault(qual, qual);
                if (!matchesTableSpec(resolved, expectedTable)) {
                    continue;
                }
            }
            edits.add(new SpanReplacement(t.offset(), t.endOffset(), preserveQuote(t, cleanTarget), 1));
        }
        return AstXmlAndOpsHelper.applyEdits(sql, edits);
    }

    static RewriteSqlResult removeSchema(RemoveSchemaRequest request) {
        String sql = request != null && request.getSql() != null ? request.getSql() : "";
        String schema = request != null ? request.getSchemaName() : null;
        if (sql.isBlank() || schema == null || schema.isBlank()) {
            return new RewriteSqlResult(sql, 0, null);
        }
        String cleanSchema = stripQuotes(schema.trim());
        EDbVendor vendor = request.getDbvendor() != null ? request.getDbvendor() : EDbVendor.dbvoracle;
        List<SqlToken> tokens = SqlLexer.tokenize(sql, vendor).tokens();
        List<SpanReplacement> edits = new ArrayList<>();

        for (int i = 0; i + 2 < tokens.size(); i++) {
            SqlToken t = tokens.get(i);
            SqlToken dot = tokens.get(i + 1);
            SqlToken obj = tokens.get(i + 2);
            if (t.isIdentifierLike() && t.value().equalsIgnoreCase(cleanSchema)
                    && dot.isSymbol(".") && (obj.isIdentifierLike() || obj.isSymbol("*"))) {
                edits.add(new SpanReplacement(t.offset(), dot.endOffset(), "", 1));
                i++;
            }
        }
        return AstXmlAndOpsHelper.applyEdits(sql, edits);
    }

    private static Map<String, String> extractSimpleAliasMap(String sql, EDbVendor vendor) {
        Map<String, String> map = new LinkedHashMap<>();
        for (SqlStmt stmt : StatementParser.parseScript(sql, vendor).statements()) {
            if (stmt instanceof SqlStmt.QueryStmt qs && qs.query() instanceof SelectQuery sq) {
                for (TableSource ts : sq.fromSources()) {
                    if (ts instanceof NamedTableSource nts && nts.alias() != null) {
                        map.put(nts.alias().toLowerCase(Locale.ROOT), nts.tableName().fullDisplayName());
                    }
                }
                for (JoinClause jc : sq.joins()) {
                    if (jc.right() instanceof NamedTableSource nts && nts.alias() != null) {
                        map.put(nts.alias().toLowerCase(Locale.ROOT), nts.tableName().fullDisplayName());
                    }
                }
            }
        }
        return map;
    }

    private static boolean matchesTableSpec(String resolved, String expected) {
        String r = resolved.toLowerCase(Locale.ROOT);
        String e = expected.toLowerCase(Locale.ROOT);
        return r.equals(e) || r.endsWith("." + e) || e.endsWith("." + r);
    }

    private static boolean matchesMultipart(List<SqlToken> tokens, int start, String[] parts) {
        for (int p = 0; p < parts.length; p++) {
            int tokIdx = start + p * 2;
            if (tokIdx >= tokens.size()) {
                return false;
            }
            SqlToken tok = tokens.get(tokIdx);
            if (!tok.isIdentifierLike() || !tok.value().equalsIgnoreCase(stripQuotes(parts[p]))) {
                return false;
            }
            if (p + 1 < parts.length) {
                if (tokIdx + 1 >= tokens.size() || !tokens.get(tokIdx + 1).isSymbol(".")) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String preserveQuote(SqlToken orig, String newIdent) {
        if (!orig.quoted() || orig.rawText().length() < 2) {
            return newIdent;
        }
        char open = orig.rawText().charAt(0);
        char close = orig.rawText().charAt(orig.rawText().length() - 1);
        return open + newIdent + close;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("`") && s.endsWith("`"))
                || (s.startsWith("[") && s.endsWith("]")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static void flattenAndConjuncts(Expr e, List<Expr> out) {
        if (e == null) {
            return;
        }
        if (e instanceof Expr.BinaryExpr b && "AND".equalsIgnoreCase(b.operator())) {
            flattenAndConjuncts(b.left(), out);
            flattenAndConjuncts(b.right(), out);
        } else {
            out.add(e);
        }
    }

    private static Set<String> qualifiersForSource(TableSource ts) {
        Set<String> q = new LinkedHashSet<>();
        if (ts.alias() != null && !ts.alias().isBlank()) {
            q.add(ts.alias().toLowerCase(Locale.ROOT));
        }
        if (ts instanceof NamedTableSource nts) {
            q.add(nts.tableName().lastName().toLowerCase(Locale.ROOT));
            q.add(nts.tableName().fullDisplayName().toLowerCase(Locale.ROOT));
        }
        return q;
    }

    private static boolean referencesBothSides(Expr cond, Set<String> leftQuals, Set<String> rightQuals) {
        Set<String> cols = new LinkedHashSet<>();
        collectColQualifiers(cond, cols);
        boolean hitLeft = cols.stream().anyMatch(leftQuals::contains);
        boolean hitRight = cols.stream().anyMatch(rightQuals::contains);
        return hitLeft && hitRight;
    }

    private static String determineOuterJoinFromPlus(Expr cond, Set<String> rhsQuals) {
        if (cond instanceof Expr.BinaryExpr b) {
            if (b.right() instanceof Expr.ColumnRefExpr rc && rc.oracleOuterJoinPlus()) {
                String q = rc.name().size() > 1 ? rc.name().parts().get(0).value().toLowerCase(Locale.ROOT) : "";
                return rhsQuals.contains(q) ? "LEFT JOIN" : "RIGHT JOIN";
            }
            if (b.left() instanceof Expr.ColumnRefExpr lc && lc.oracleOuterJoinPlus()) {
                String q = lc.name().size() > 1 ? lc.name().parts().get(0).value().toLowerCase(Locale.ROOT) : "";
                return rhsQuals.contains(q) ? "LEFT JOIN" : "RIGHT JOIN";
            }
        }
        return "INNER JOIN";
    }

    private static void collectColQualifiers(Expr e, Set<String> out) {
        if (e instanceof Expr.ColumnRefExpr c && c.name().size() >= 2) {
            out.add(c.name().parts().get(c.name().size() - 2).value().toLowerCase(Locale.ROOT));
        } else if (e instanceof Expr.BinaryExpr b) {
            collectColQualifiers(b.left(), out);
            collectColQualifiers(b.right(), out);
        } else if (e instanceof Expr.FuncCallExpr fn) {
            for (Expr a : fn.args()) {
                collectColQualifiers(a, out);
            }
        }
    }
}
