package io.sqlflow.lineage.lexer;

import io.sqlflow.contracts.vendor.EDbVendor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits multi-statement SQL scripts and large warehouse batches along statement and procedural
 * block boundaries without breaking mid-PL/SQL, mid-T-SQL, or mid-dollar-quoted routines.
 */
public final class SqlStatementSplitter {

    private static final Pattern DELIMITER_DIRECTIVE = Pattern.compile(
            "(?im)^\\s*(?:DELIMITER|--\\s*sqlflow-delimiter\\s*:?)\\s+(\\S+)\\s*$");

    public record StatementSlice(
            List<SqlToken> tokens,
            SourceSpan span,
            String rawSql
    ) {
    }

    private SqlStatementSplitter() {
    }

    /**
     * Splits a token list into top-level statement slices while honoring compound procedural blocks
     * ({@code CREATE PACKAGE/PROCEDURE/FUNCTION/TRIGGER}, {@code DECLARE/BEGIN ... END}, {@code CASE ... END}).
     */
    public static List<StatementSlice> splitTokens(
            String sourceSql,
            List<SqlToken> tokens,
            EDbVendor vendor,
            String explicitDelimiter) {
        List<StatementSlice> slices = new ArrayList<>();
        if (tokens == null || tokens.isEmpty()) {
            return slices;
        }

        int n = tokens.size();
        int idx = 0;
        while (idx < n && !tokens.get(idx).isEof()) {
            // Skip stray statement separators
            while (idx < n && !tokens.get(idx).isEof() && isStandaloneSeparator(tokens, idx, vendor, explicitDelimiter)) {
                idx++;
            }
            if (idx >= n || tokens.get(idx).isEof()) {
                break;
            }

            // Skip client-only MySQL DELIMITER directives
            if (tokens.get(idx).isWord("DELIMITER") && idx + 1 < n && !tokens.get(idx + 1).isEof()) {
                long dirLine = tokens.get(idx).line();
                while (idx < n && !tokens.get(idx).isEof() && tokens.get(idx).line() == dirLine) {
                    idx++;
                }
                continue;
            }

            int stmtStartIdx = idx;
            int parenDepth = 0;
            int beginDepth = 0;
            int caseDepth = 0;
            boolean inCreateCompoundHeader = isCompoundRoutineCreate(tokens, stmtStartIdx);
            boolean isPackageStmt = isPackageCreate(tokens, stmtStartIdx);
            boolean inPackageRoutineHeader = false;
            boolean compoundBodySeen = false;

            while (idx < n && !tokens.get(idx).isEof()) {
                SqlToken tok = tokens.get(idx);

                // MSSQL / Sybase standalone GO on its own line terminates the current statement/batch
                if (parenDepth == 0
                        && isStandaloneGoBatchSeparator(tokens, idx, vendor)
                        && idx > stmtStartIdx) {
                    break;
                }

                // Standalone Oracle/DB2/Postgres slash '/' or '@' line separator after END;
                if (parenDepth == 0
                        && beginDepth == 0
                        && !inCreateCompoundHeader
                        && idx > stmtStartIdx
                        && isStandaloneSlashOrAtLine(tokens, idx, vendor)) {
                    break;
                }

                if (tok.isSymbol("(")) {
                    parenDepth++;
                } else if (tok.isSymbol(")") && parenDepth > 0) {
                    parenDepth--;
                } else if (parenDepth > 0 && beginDepth == 0 && tok.isSymbol(";")) {
                    SqlToken afterSemi = idx + 1 < n ? tokens.get(idx + 1) : null;
                    if (afterSemi == null
                            || afterSemi.isEof()
                            || (afterSemi.line() > tok.line()
                                    && afterSemi.isAnyWord("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "CREATE", "ALTER", "DROP", "WITH", "TRUNCATE"))) {
                        parenDepth = 0;
                        idx++;
                        break;
                    }
                } else if (parenDepth == 0) {
                    if (isPackageStmt && beginDepth == 0 && tok.isAnyWord("AS", "IS")) {
                        beginDepth = 1;
                        inCreateCompoundHeader = false;
                        compoundBodySeen = true;
                    } else if (isPackageStmt && beginDepth == 1 && tok.isAnyWord("PROCEDURE", "FUNCTION")) {
                        inPackageRoutineHeader = true;
                    } else if (isPackageStmt && inPackageRoutineHeader && tok.isSymbol(";")) {
                        if (!isPackageOrProcedureWithBodyAhead(tokens, idx - 1, idx)) {
                            inPackageRoutineHeader = false;
                        }
                    } else if (tok.isWord("CASE")) {
                        // In PL/SQL, END CASE closes CASE; in SQL expressions, END closes CASE.
                        SqlToken prev = idx > stmtStartIdx ? tokens.get(idx - 1) : null;
                        if (prev == null || !prev.isWord("END")) {
                            caseDepth++;
                        }
                    } else if (tok.isWord("BEGIN")) {
                        if (!isTransactionBegin(tokens, idx)) {
                            if (isPackageStmt && beginDepth == 1 && !inPackageRoutineHeader) {
                                // Package initialization BEGIN block shares the closing END <pkg_name>;
                            } else {
                                beginDepth++;
                                inPackageRoutineHeader = false;
                            }
                            if (inCreateCompoundHeader) {
                                compoundBodySeen = true;
                                inCreateCompoundHeader = false;
                            }
                        }
                    } else if (inCreateCompoundHeader && tok.type() == TokenType.STRING_LITERAL
                            && tok.rawText().startsWith("$")) {
                        // PostgreSQL / Snowflake CREATE FUNCTION ... AS $$ ... $$ LANGUAGE plpgsql;
                        inCreateCompoundHeader = false;
                        compoundBodySeen = true;
                    } else if (inCreateCompoundHeader && tok.isWord("RETURN")
                            && !isFunctionReturnTypeClause(tokens, idx)) {
                        // Single-statement SQL function: CREATE FUNCTION f() RETURNS INT RETURN 1;
                        inCreateCompoundHeader = false;
                    } else if (tok.isWord("END")) {
                        SqlToken next = idx + 1 < n ? tokens.get(idx + 1) : null;
                        if (next != null && next.isAnyWord("IF", "LOOP", "WHILE", "REPEAT", "FOR")) {
                            // Closes IF / LOOP / WHILE inside a BEGIN...END block, not BEGIN itself
                            idx++; // advance over IF/LOOP so we don't re-trigger
                        } else if (next != null && next.isWord("CASE")) {
                            if (caseDepth > 0) {
                                caseDepth--;
                            }
                            idx++;
                        } else if (caseDepth > 0) {
                            caseDepth--;
                        } else if (beginDepth > 0) {
                            beginDepth--;
                        }
                    } else if (beginDepth == 0 && caseDepth == 0 && !inCreateCompoundHeader) {
                        if (isStatementTerminator(tokens, idx, vendor, explicitDelimiter)) {
                            idx++; // consume ';'
                            // Consume optional trailing '/' on next line for PL/SQL
                            if (idx < n && !tokens.get(idx).isEof() && isStandaloneSlashOrAtLine(tokens, idx, vendor)) {
                                idx++;
                            }
                            break;
                        }
                    } else if (inCreateCompoundHeader && !compoundBodySeen && tok.isSymbol(";")) {
                        // Could be forward declaration inside script or single-expression CREATE FUNCTION/MACRO
                        if (!isPackageOrProcedureWithBodyAhead(tokens, stmtStartIdx, idx)) {
                            inCreateCompoundHeader = false;
                            idx++;
                            break;
                        }
                    }
                }
                idx++;
            }

            List<SqlToken> sliceTokens = trimTrailingSeparators(tokens.subList(stmtStartIdx, idx), vendor, explicitDelimiter);
            if (!sliceTokens.isEmpty()) {
                SourceSpan startSpan = sliceTokens.get(0).span();
                SourceSpan endSpan = sliceTokens.get(sliceTokens.size() - 1).span();
                SourceSpan combined = SourceSpan.between(startSpan, endSpan);
                List<SqlToken> withEof = new ArrayList<>(sliceTokens.size() + 1);
                withEof.addAll(sliceTokens);
                withEof.add(new SqlToken(
                        TokenType.EOF,
                        "",
                        "",
                        false,
                        false,
                        new SourceSpan(endSpan.endLine(), endSpan.endCol(), endSpan.endLine(), endSpan.endCol(),
                                endSpan.endOffset(), endSpan.endOffset())));
                slices.add(new StatementSlice(withEof, combined, combined.extractText(sourceSql)));
            }
        }
        return slices;
    }

    /**
     * Splits a large SQL script into UTF-8 chunks of at most {@code maxBytesPerChunk} bytes
     * along statement boundaries without splitting inside a statement or routine body.
     */
    public static List<String> splitLargeSql(String sqlText, EDbVendor vendor, int maxBytesPerChunk) {
        if (sqlText == null || sqlText.isBlank()) {
            return List.of();
        }
        int effectiveMax = maxBytesPerChunk > 0 ? maxBytesPerChunk : 5 * 1024 * 1024;
        byte[] allBytes = sqlText.getBytes(StandardCharsets.UTF_8);
        if (allBytes.length <= effectiveMax) {
            return List.of(sqlText);
        }

        String customDelim = detectCustomDelimiter(sqlText);
        SqlLexer.LexResult lexed = SqlLexer.tokenize(sqlText, vendor);
        List<StatementSlice> slices = splitTokens(sqlText, lexed.tokens(), vendor, customDelim);
        if (slices.isEmpty()) {
            return List.of(sqlText);
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentBytes = 0;

        for (StatementSlice slice : slices) {
            String stmt = slice.rawSql().trim();
            if (stmt.isEmpty()) {
                continue;
            }
            String stmtWithSep = stmt.endsWith(";") ? stmt + "\n" : stmt + ";\n";
            int stmtBytes = stmtWithSep.getBytes(StandardCharsets.UTF_8).length;
            if (currentBytes > 0 && currentBytes + stmtBytes > effectiveMax) {
                chunks.add(current.toString().trim());
                current.setLength(0);
                currentBytes = 0;
            }
            current.append(stmtWithSep);
            currentBytes += stmtBytes;
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    public static String detectCustomDelimiter(String sqlText) {
        if (sqlText == null || sqlText.isEmpty()) {
            return null;
        }
        Matcher m = DELIMITER_DIRECTIVE.matcher(sqlText);
        if (m.find()) {
            String delim = m.group(1);
            if (!";".equals(delim)) {
                return delim;
            }
        }
        return null;
    }

    private static boolean isPackageCreate(List<SqlToken> tokens, int start) {
        if (!tokens.get(start).isWord("CREATE") && !tokens.get(start).isWord("ALTER")) {
            return false;
        }
        int limit = Math.min(tokens.size() - 1, start + 6);
        for (int i = start + 1; i <= limit; i++) {
            if (tokens.get(i).isWord("PACKAGE")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCompoundRoutineCreate(List<SqlToken> tokens, int start) {
        SqlToken first = tokens.get(start);
        if (first.isWord("DECLARE")) {
            return true;
        }
        if (!first.isWord("CREATE") && !first.isWord("ALTER")) {
            return false;
        }
        int limit = Math.min(tokens.size() - 1, start + 8);
        for (int i = start + 1; i <= limit; i++) {
            SqlToken t = tokens.get(i);
            if (t.isAnyWord("PROCEDURE", "PROC", "FUNCTION", "PACKAGE", "TRIGGER")) {
                return true;
            }
            if (t.isAnyWord("TABLE", "VIEW", "INDEX", "DATABASE", "SCHEMA", "SYNONYM", "SEQUENCE", "STAGE", "TYPE")) {
                return false;
            }
        }
        return false;
    }

    private static boolean isPackageOrProcedureWithBodyAhead(List<SqlToken> tokens, int stmtStart, int semiIdx) {
        // Inside CREATE PACKAGE [BODY], declarations end with ';', followed eventually by END [pkg_name];
        for (int i = stmtStart + 1; i < Math.min(tokens.size() - 1, stmtStart + 6); i++) {
            if (tokens.get(i).isWord("PACKAGE")) {
                return true;
            }
        }
        // Check if AS/IS BEGIN appears within the next few tokens or if AS/IS already appeared with local decls before BEGIN
        boolean sawAsOrIs = false;
        for (int i = stmtStart + 1; i < semiIdx; i++) {
            if (tokens.get(i).isAnyWord("AS", "IS") && tokens.get(i - 1).type() != TokenType.OPERATOR) {
                sawAsOrIs = true;
                break;
            }
        }
        if (sawAsOrIs) {
            // Local variable/cursor declaration between IS/AS and BEGIN in PL/SQL!
            for (int j = semiIdx + 1; j < Math.min(tokens.size() - 1, semiIdx + 80); j++) {
                if (tokens.get(j).isWord("BEGIN")) {
                    return true;
                }
                if (tokens.get(j).isAnyWord("CREATE", "DROP", "SELECT", "INSERT", "UPDATE", "DELETE")) {
                    // Unless SELECT is inside CURSOR c IS SELECT ...
                    if (tokens.get(j).isWord("SELECT") && j > semiIdx + 2 && tokens.get(j - 2).isWord("CURSOR")) {
                        continue;
                    }
                    if (tokens.get(j).isAnyWord("CREATE", "DROP")) {
                        return false;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isFunctionReturnTypeClause(List<SqlToken> tokens, int returnIdx) {
        // In Oracle/DB2: CREATE FUNCTION f(...) RETURN NUMBER IS/AS ...
        for (int j = returnIdx + 1; j < Math.min(tokens.size() - 1, returnIdx + 8); j++) {
            if (tokens.get(j).isAnyWord("IS", "AS", "PIPELINED", "DETERMINISTIC", "BEGIN", "LANGUAGE")) {
                return true;
            }
            if (tokens.get(j).isSymbol(";")) {
                return false;
            }
        }
        return false;
    }

    private static boolean isTransactionBegin(List<SqlToken> tokens, int beginIdx) {
        SqlToken next = beginIdx + 1 < tokens.size() ? tokens.get(beginIdx + 1) : null;
        if (next == null || next.isEof() || next.isSymbol(";")) {
            return true;
        }
        return next.isAnyWord("TRANSACTION", "TRAN", "WORK", "DEFERRED", "IMMEDIATE", "EXCLUSIVE");
    }

    private static boolean isStandaloneGoBatchSeparator(List<SqlToken> tokens, int idx, EDbVendor vendor) {
        if (vendor != EDbVendor.dbvmssql
                && vendor != EDbVendor.dbvazuresql
                && vendor != EDbVendor.dbvsybase
                && vendor != EDbVendor.dbvsybasease
                && vendor != EDbVendor.dbvsybaseiq) {
            return false;
        }
        SqlToken tok = tokens.get(idx);
        if (!tok.isWord("GO")) {
            return false;
        }
        boolean firstOnLine = idx == 0 || tokens.get(idx - 1).endLine() < tok.line();
        boolean lastOnLine = idx + 1 >= tokens.size()
                || tokens.get(idx + 1).isEof()
                || tokens.get(idx + 1).line() > tok.line();
        return firstOnLine && lastOnLine;
    }

    private static boolean isStandaloneSlashOrAtLine(List<SqlToken> tokens, int idx, EDbVendor vendor) {
        SqlToken tok = tokens.get(idx);
        if (!tok.isSymbol("/") && !(vendor == EDbVendor.dbvdb2 && tok.isSymbol("@"))) {
            return false;
        }
        boolean firstOnLine = idx == 0 || tokens.get(idx - 1).endLine() < tok.line();
        boolean lastOnLine = idx + 1 >= tokens.size()
                || tokens.get(idx + 1).isEof()
                || tokens.get(idx + 1).line() > tok.line();
        return firstOnLine && lastOnLine;
    }

    private static boolean isStatementTerminator(
            List<SqlToken> tokens,
            int idx,
            EDbVendor vendor,
            String explicitDelimiter) {
        SqlToken tok = tokens.get(idx);
        if (explicitDelimiter != null && !explicitDelimiter.isBlank() && !";".equals(explicitDelimiter)) {
            if (tok.rawText().equalsIgnoreCase(explicitDelimiter)) {
                return true;
            }
            if (explicitDelimiter.length() == 2
                    && idx + 1 < tokens.size()
                    && (tok.rawText() + tokens.get(idx + 1).rawText()).equals(explicitDelimiter)) {
                return true;
            }
        }
        if (tok.isSymbol(";")) {
            return true;
        }
        return isStandaloneGoBatchSeparator(tokens, idx, vendor) || isStandaloneSlashOrAtLine(tokens, idx, vendor);
    }

    private static boolean isStandaloneSeparator(
            List<SqlToken> tokens,
            int idx,
            EDbVendor vendor,
            String explicitDelimiter) {
        SqlToken tok = tokens.get(idx);
        if (tok.isSymbol(";")) {
            return true;
        }
        if (explicitDelimiter != null && tok.rawText().equalsIgnoreCase(explicitDelimiter)) {
            return true;
        }
        return isStandaloneGoBatchSeparator(tokens, idx, vendor) || isStandaloneSlashOrAtLine(tokens, idx, vendor);
    }

    private static List<SqlToken> trimTrailingSeparators(
            List<SqlToken> sub,
            EDbVendor vendor,
            String explicitDelimiter) {
        int end = sub.size();
        while (end > 0 && isStandaloneSeparator(sub, end - 1, vendor, explicitDelimiter)) {
            end--;
        }
        return sub.subList(0, end);
    }
}
