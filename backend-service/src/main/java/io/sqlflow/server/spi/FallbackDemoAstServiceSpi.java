package io.sqlflow.server.spi;

import io.sqlflow.contracts.lineage.Coordinate;
import io.sqlflow.contracts.lineage.ErrorItem;
import io.sqlflow.contracts.spi.DemoAstServiceSpi;
import io.sqlflow.contracts.vendor.EDbVendor;
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
 * Built-in SQL AST & pretty-printer utility fallback implementing all 15 methods of
 * {@link DemoAstServiceSpi}.
 */
public class FallbackDemoAstServiceSpi implements DemoAstServiceSpi {

    private static final String ENGINE_VERSION = "2.8.5.8";
    private static final Set<String> RESERVED_KEYWORDS = Set.of(
            "SELECT", "FROM", "WHERE", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS",
            "ON", "USING", "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET", "INSERT", "INTO",
            "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "ALTER", "DROP", "TABLE", "VIEW",
            "AS", "AND", "OR", "NOT", "IN", "EXISTS", "BETWEEN", "LIKE", "IS", "NULL", "CASE",
            "WHEN", "THEN", "ELSE", "END", "UNION", "ALL", "DISTINCT", "WITH", "MERGE");

    private static final Pattern TABLE_EXTRACT = Pattern.compile(
            "(?is)\\b(?:FROM|JOIN|INTO|UPDATE|TABLE)\\s+([A-Za-z_][\\w.]*)");
    private static final Pattern SQLI_TAUTOLOGY = Pattern.compile(
            "(?is)(?:'\\s*OR\\s+'?1'?\\s*=\\s*'?1|\\bOR\\s+1\\s*=\\s*1\\b|;\\s*DROP\\s+TABLE\\b|\\bUNION\\s+SELECT\\b.*\\bFROM\\s+information_schema\\b)");
    private static final Pattern STRING_CONST = Pattern.compile("'([^']*)'");
    private static final Pattern NUMBER_CONST = Pattern.compile("(?<![A-Za-z0-9_])(\\d+(?:\\.\\d+)?)(?![A-Za-z0-9_])");

    @Override
    public FormatResult sqlformat(FormatRequest request) {
        String sql = request != null && request.getSql() != null ? request.getSql() : "";
        GFmtOpt opt = request != null ? request.resolveOptions() : new GFmtOpt();
        if (sql.isBlank()) {
            return new FormatResult("", List.of());
        }
        String formatted = sql.trim().replaceAll("[ \\t]+", " ");
        String caseKw = opt.getCaseKeywords() != null ? opt.getCaseKeywords() : "Upper";
        boolean lowerKw = "Lower".equalsIgnoreCase(caseKw);
        boolean noChangeKw = "NoChange".equalsIgnoreCase(caseKw);
        for (String kw : List.of(
                "SELECT", "FROM", "WHERE", "LEFT JOIN", "RIGHT JOIN", "INNER JOIN", "FULL JOIN",
                "CROSS JOIN", "JOIN", "ON", "AND", "OR", "GROUP BY", "ORDER BY", "HAVING", "UNION ALL", "UNION")) {
            boolean newlineBefore = !kw.equals("ON") && !kw.equals("AND") && !kw.equals("OR");
            Matcher m = Pattern.compile("(?i)\\b" + kw.replace(" ", "\\s+") + "\\b").matcher(formatted);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String matched = m.group();
                String cased = noChangeKw ? matched : (lowerKw ? kw.toLowerCase(Locale.ROOT) : kw.toUpperCase(Locale.ROOT));
                String rep = newlineBefore ? ("\n" + cased) : cased;
                m.appendReplacement(sb, Matcher.quoteReplacement(rep));
            }
            m.appendTail(sb);
            formatted = sb.toString();
        }
        formatted = formatted.replaceFirst("^\\n+", "");
        return new FormatResult(formatted, null);
    }

    @Override
    public TableColumnResult getTableColumns(AstSqlRequest request) {
        return tableColumn(request);
    }

    @Override
    public TableColumnResult tableColumn(AstSqlRequest request) {
        String sql = request != null && request.getSql() != null ? request.getSql() : "";
        Map<String, List<String>> tables = new LinkedHashMap<>();
        Map<String, String> aliasToTable = new LinkedHashMap<>();
        List<String> orphans = new ArrayList<>();

        Pattern tableWithAlias = Pattern.compile(
                "(?is)\\b(?:FROM|JOIN|INTO|UPDATE|TABLE)\\s+([A-Za-z_][\\w.]*)(?:\\s+(?:AS\\s+)?([A-Za-z_][A-Za-z0-9_]*))?");
        Matcher m = tableWithAlias.matcher(sql);
        while (m.find()) {
            String tbl = m.group(1);
            tables.putIfAbsent(tbl, new ArrayList<>());
            String alias = m.group(2);
            if (alias != null && !RESERVED_KEYWORDS.contains(alias.toUpperCase(Locale.ROOT))) {
                aliasToTable.put(alias.toLowerCase(Locale.ROOT), tbl);
            }
        }

        Pattern qualCol = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_*]*)");
        Matcher cm = qualCol.matcher(sql);
        while (cm.find()) {
            String prefix = cm.group(1);
            String col = cm.group(2);
            // Skip if prefix.col is actually schema.table already captured in tables
            if (tables.containsKey(prefix + "." + col)) {
                continue;
            }
            String resolvedTbl = aliasToTable.getOrDefault(prefix.toLowerCase(Locale.ROOT), prefix);
            List<String> list = tables.computeIfAbsent(resolvedTbl, k -> new ArrayList<>());
            if (!list.contains(col)) {
                list.add(col);
            }
        }

        // Extract simple unqualified SELECT columns when querying a single table
        if (tables.size() == 1) {
            String onlyTable = tables.keySet().iterator().next();
            Matcher selM = Pattern.compile("(?is)\\bSELECT\\s+(.*?)\\s+FROM\\b").matcher(sql);
            if (selM.find()) {
                for (String item : selM.group(1).split(",")) {
                    String trimmed = item.trim();
                    if (!trimmed.contains(".") && trimmed.matches("[A-Za-z_*][A-Za-z0-9_]*")
                            && !RESERVED_KEYWORDS.contains(trimmed.toUpperCase(Locale.ROOT))) {
                        List<String> cols = tables.get(onlyTable);
                        if (!cols.contains(trimmed)) {
                            cols.add(trimmed);
                        }
                    }
                }
            }
        }

        TableColumnResult res = new TableColumnResult();
        res.setTables(tables);
        res.setOrphanColumns(orphans);
        return res;
    }

    @Override
    public SyntaxCheckResult syntaxCheck(AstSqlRequest request) {
        String sql = request != null && request.getSql() != null ? request.getSql().trim() : "";
        EDbVendor vendor = request != null && request.getDbvendor() != null ? request.getDbvendor() : EDbVendor.dbvoracle;
        SyntaxCheckResult res = new SyntaxCheckResult();
        res.setGspVersion(ENGINE_VERSION);
        res.setDbVendor(vendor.name());
        res.setUsedTime("1 ms");

        List<ErrorItem> errors = validateBasicSqlSyntax(sql);
        if (errors.isEmpty()) {
            res.setValid(Boolean.TRUE);
            res.setErrorInfos(List.of());
        } else {
            res.setValid(Boolean.FALSE);
            res.setErrors(errors);
            res.setErrorInfos(errors.stream().map(ErrorItem::getErrorMessage).toList());
        }
        return res;
    }

    @Override
    public TokenizeResult tokenize(AstSqlRequest request) {
        String sql = request != null && request.getSql() != null ? request.getSql() : "";
        List<TokenItem> tokens = new ArrayList<>();
        Pattern p = Pattern.compile("'[^']*'|\"[^\"]*\"|\\d+(?:\\.\\d+)?|[A-Za-z_][A-Za-z0-9_$#]*|[(),.;=+\\-*/<>!]+");
        Matcher m = p.matcher(sql);
        long line = 1;
        int lastIdx = 0;
        long colBase = 1;
        while (m.find()) {
            for (int i = lastIdx; i < m.start(); i++) {
                if (sql.charAt(i) == '\n') {
                    line++;
                    colBase = 1;
                } else {
                    colBase++;
                }
            }
            String text = m.group();
            String type = classifyToken(text);
            tokens.add(new TokenItem(text, type, line, colBase, m.start()));
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                    colBase = 1;
                } else {
                    colBase++;
                }
            }
            lastIdx = m.end();
        }
        return new TokenizeResult(tokens);
    }

    @Override
    public SqlInjectionResult antiSqlInjection(AstSqlRequest request) {
        String sql = request != null && request.getSql() != null ? request.getSql() : "";
        Matcher m = SQLI_TAUTOLOGY.matcher(sql);
        if (m.find()) {
            SqlInjectionFinding finding = new SqlInjectionFinding(
                    "SQL_INJECTION_RISK",
                    "Suspicious SQL injection pattern detected: " + m.group(),
                    Coordinate.of(1, Math.max(1, m.start() + 1)));
            return new SqlInjectionResult(true, "SQL injection risk detected", List.of(finding));
        }
        return new SqlInjectionResult(false, "No SQL injection detected", List.of());
    }

    @Override
    public CallRelationResult callRelation(AstSqlRequest request) {
        String sql = request != null && request.getSql() != null ? request.getSql() : "";
        List<CallEdge> calls = new ArrayList<>();
        Matcher m = Pattern.compile("(?is)\\b(?:CALL|EXEC(?:UTE)?)\\s+([\\w.]+)").matcher(sql);
        while (m.find()) {
            CallEdge edge = new CallEdge();
            edge.setCaller("anonymous");
            edge.setCallee(m.group(1));
            edge.setCallType("procedure");
            edge.setCoordinates(List.of(Coordinate.of(1, Math.max(1, m.start(1) + 1))));
            calls.add(edge);
        }
        CallRelationResult res = new CallRelationResult();
        res.setCalls(calls);
        res.setXml("<callRelation count=\"" + calls.size() + "\"/>");
        return res;
    }

    @Override
    public ColumnImpactResult columnImpact(AstSqlRequest request) {
        TableColumnResult tc = tableColumn(request);
        ColumnImpactResult res = new ColumnImpactResult();
        res.setImpacts(tc.getTables() != null ? tc.getTables() : Map.of());
        res.setXml("<columnImpact/>");
        return res;
    }

    @Override
    public FindConstantsResult findConstants(AstSqlRequest request) {
        String sql = request != null && request.getSql() != null ? request.getSql() : "";
        List<ConstantItem> constants = new ArrayList<>();
        Matcher sm = STRING_CONST.matcher(sql);
        while (sm.find()) {
            constants.add(new ConstantItem(sm.group(0), "string", Coordinate.of(1, Math.max(1, sm.start() + 1))));
        }
        Matcher nm = NUMBER_CONST.matcher(sql);
        while (nm.find()) {
            constants.add(new ConstantItem(nm.group(1), "number", Coordinate.of(1, Math.max(1, nm.start() + 1))));
        }
        return new FindConstantsResult(constants);
    }

    @Override
    public RewriteSqlResult joinConvert(JoinConvertRequest request) {
        String sql = request != null && request.getSql() != null ? request.getSql() : "";
        // Convert simple Oracle (+) or comma join to INNER/LEFT JOIN when applicable
        Pattern commaJoin = Pattern.compile("(?is)\\bFROM\\s+(\\w+)(?:\\s+(\\w+))?\\s*,\\s*(\\w+)(?:\\s+(\\w+))?\\s+WHERE\\s+([^;]+)");
        Matcher m = commaJoin.matcher(sql);
        if (m.find()) {
            String t1 = m.group(1) + (m.group(2) != null ? " " + m.group(2) : "");
            String t2 = m.group(3) + (m.group(4) != null ? " " + m.group(4) : "");
            String cond = m.group(5).replace("(+)", "").trim();
            String joinKw = m.group(5).contains("(+)") ? " LEFT JOIN " : " INNER JOIN ";
            String rewritten = sql.substring(0, m.start()) + "FROM " + t1 + joinKw + t2 + " ON " + cond + sql.substring(m.end());
            return new RewriteSqlResult(rewritten, 1, null);
        }
        return new RewriteSqlResult(sql, 0, null);
    }

    @Override
    public KeywordCheckResult keywordCheck(AstSqlRequest request) {
        String raw = request != null && request.getSql() != null ? request.getSql().trim() : "";
        TokenizeResult tokRes = tokenize(request);
        List<TokenItem> bad = new ArrayList<>();
        List<TokenItem> toks = tokRes.getTokens() != null ? tokRes.getTokens() : List.of();

        boolean looksLikeSqlStatement = raw.matches(
                "(?is)^(?:SELECT\\b.*\\bFROM\\b|INSERT\\b.*\\bINTO\\b|UPDATE\\b.*\\bSET\\b|DELETE\\b.*\\bFROM\\b|CREATE\\b\\s+(?:OR\\b|TABLE\\b|VIEW\\b)|ALTER\\b\\s+TABLE\\b|DROP\\b\\s+(?:TABLE|VIEW)\\b|WITH\\b.*\\bSELECT\\b).*");
        if (!looksLikeSqlStatement) {
            for (TokenItem tok : toks) {
                if (RESERVED_KEYWORDS.contains(tok.getText().toUpperCase(Locale.ROOT))) {
                    bad.add(tok);
                }
            }
        } else {
            for (int i = 1; i < toks.size(); i++) {
                TokenItem prev = toks.get(i - 1);
                TokenItem cur = toks.get(i);
                if ("AS".equalsIgnoreCase(prev.getText())
                        && RESERVED_KEYWORDS.contains(cur.getText().toUpperCase(Locale.ROOT))) {
                    bad.add(cur);
                }
            }
        }
        KeywordCheckResult res = new KeywordCheckResult();
        res.setValid(bad.isEmpty());
        res.setReservedKeywordsUsedAsIdentifiers(bad);
        return res;
    }

    @Override
    public RewriteSqlResult renameTable(RenameTableRequest request) {
        String sql = request != null && request.getSql() != null ? request.getSql() : "";
        if (request == null || request.getSourceTable() == null || request.getTargetTable() == null) {
            return new RewriteSqlResult(sql, 0, null);
        }
        Pattern p = Pattern.compile("(?i)\\b" + Pattern.quote(request.getSourceTable()) + "\\b");
        Matcher m = p.matcher(sql);
        int count = 0;
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            count++;
            m.appendReplacement(sb, Matcher.quoteReplacement(request.getTargetTable()));
        }
        m.appendTail(sb);
        return new RewriteSqlResult(sb.toString(), count, null);
    }

    @Override
    public RewriteSqlResult renameColumn(RenameColumnRequest request) {
        String sql = request != null && request.getSql() != null ? request.getSql() : "";
        if (request == null || request.getSourceColumn() == null || request.getTargetColumn() == null) {
            return new RewriteSqlResult(sql, 0, null);
        }
        Pattern p = Pattern.compile("(?i)\\b" + Pattern.quote(request.getSourceColumn()) + "\\b");
        Matcher m = p.matcher(sql);
        int count = 0;
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            count++;
            m.appendReplacement(sb, Matcher.quoteReplacement(request.getTargetColumn()));
        }
        m.appendTail(sb);
        return new RewriteSqlResult(sb.toString(), count, null);
    }

    @Override
    public RewriteSqlResult removeSchema(RemoveSchemaRequest request) {
        String sql = request != null && request.getSql() != null ? request.getSql() : "";
        String schema = request != null ? request.getSchemaName() : null;
        if (schema == null || schema.isBlank()) {
            return new RewriteSqlResult(sql, 0, null);
        }
        Pattern p = Pattern.compile("(?i)\\b" + Pattern.quote(schema) + "\\.");
        Matcher m = p.matcher(sql);
        int count = 0;
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            count++;
            m.appendReplacement(sb, "");
        }
        m.appendTail(sb);
        return new RewriteSqlResult(sb.toString(), count, null);
    }

    @Override
    public XmlAstResult toXml(AstSqlRequest request) {
        String sql = request != null && request.getSql() != null ? request.getSql() : "";
        EDbVendor vendor = request != null && request.getDbvendor() != null ? request.getDbvendor() : EDbVendor.dbvoracle;
        XmlAstResult res = new XmlAstResult();
        res.setGspVersion(ENGINE_VERSION);
        res.setDbVendor(vendor.name());
        res.setUsedTime("1 ms");
        List<ErrorItem> errs = validateBasicSqlSyntax(sql);
        if (!errs.isEmpty()) {
            res.setErrors(errs);
            res.setErrorInfos(errs.stream().map(ErrorItem::getErrorMessage).toList());
        } else {
            String escaped = sql.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            res.setXml("<sqlscript dbvendor=\"" + vendor.name() + "\"><statement>" + escaped + "</statement></sqlscript>");
        }
        return res;
    }

    private List<ErrorItem> validateBasicSqlSyntax(String sql) {
        List<ErrorItem> errors = new ArrayList<>();
        if (sql == null || sql.isBlank()) {
            errors.add(new ErrorItem("SyntaxError", "SQL text is empty", List.of(Coordinate.of(1, 1))));
            return errors;
        }
        int depth = 0;
        boolean inSingle = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inSingle = !inSingle;
            } else if (!inSingle) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth < 0) {
                        errors.add(new ErrorItem("SyntaxError", "Unmatched closing parenthesis ')'", List.of(Coordinate.of(1, i + 1))));
                        return errors;
                    }
                }
            }
        }
        if (inSingle) {
            errors.add(new ErrorItem("SyntaxError", "Unterminated string literal", List.of(Coordinate.of(1, sql.length()))));
        } else if (depth != 0) {
            errors.add(new ErrorItem("SyntaxError", "Unbalanced parentheses in SQL", List.of(Coordinate.of(1, sql.length()))));
        }
        String upper = sql.trim().toUpperCase(Locale.ROOT);
        Set<String> validStarts = new LinkedHashSet<>(Set.of(
                "SELECT", "WITH", "INSERT", "UPDATE", "DELETE", "MERGE", "CREATE", "ALTER", "DROP",
                "TRUNCATE", "CALL", "EXEC", "EXECUTE", "BEGIN", "DECLARE", "SET", "USE", "SHOW",
                "EXPLAIN", "DESCRIBE", "DESC", "GRANT", "REVOKE", "COMMIT", "ROLLBACK", "COPY", "--", "/*"));
        String firstTok = upper.split("\\s+|\\(", 2)[0];
        if (!validStarts.contains(firstTok) && !upper.startsWith("--") && !upper.startsWith("/*")) {
            errors.add(new ErrorItem(
                    "SyntaxError",
                    "Unexpected token '" + firstTok + "' at start of statement",
                    List.of(Coordinate.of(1, 1))));
        }
        if (upper.contains(" FROM WHERE ") || upper.endsWith(" FROM") || upper.endsWith(" FROM;")) {
            errors.add(new ErrorItem("SyntaxError", "Missing table reference after FROM", List.of(Coordinate.of(1, 1))));
        }
        return errors;
    }

    private String classifyToken(String text) {
        if (text.startsWith("'")) {
            return "STRING_LITERAL";
        }
        if (text.startsWith("\"")) {
            return "QUOTED_IDENTIFIER";
        }
        if (Character.isDigit(text.charAt(0))) {
            return "NUMBER";
        }
        if (RESERVED_KEYWORDS.contains(text.toUpperCase(Locale.ROOT))) {
            return "KEYWORD";
        }
        if (Character.isLetter(text.charAt(0)) || text.charAt(0) == '_') {
            return "IDENTIFIER";
        }
        return "SYMBOL";
    }
}
