package io.sqlflow.connectors.extractor;

import com.fasterxml.jackson.databind.JsonNode;
import io.sqlflow.connectors.jdbc.CatalogBuilder;
import io.sqlflow.connectors.util.ConnectorsJson;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.contracts.spi.FileExtractorSpi.ExtractionRequest;
import io.sqlflow.contracts.spi.FileExtractorSpi.ExtractionResult;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.io.IOException;
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
 * PySpark &amp; Jupyter Notebook SQL/DataFrame Lineage Extractor ({@code OBL-078}, {@code 02 §6.4.3}):
 * <ul>
 *   <li>Scans {@code .py} scripts and {@code .ipynb} Jupyter notebooks ({@code cells[].source} where {@code cell_type == "code"}),
 *       plus {@code %%sql} / {@code %sql} notebook magic cells</li>
 *   <li>Extracts {@code spark.sql(...)}, {@code sqlContext.sql(...)}, {@code hiveContext.sql(...)}, and {@code session.sql(...)}
 *       across single/double/triple-quoted literals, f-strings, {@code .format(...)} templates, and variable assignments
 *       (e.g. {@code sql_str = """SELECT ..."""; df = spark.sql(sql_str)})</li>
 *   <li>Extracts DataFrame {@code .read.table(...)} / {@code spark.table(...)} and {@code .write...saveAsTable(...)} /
 *       {@code .insertInto(...)} operations, synthesizing executable SparkSQL lineage statements and catalog table nodes</li>
 * </ul>
 */
public final class PySparkSqlExtractor {

    private static final Pattern PYTHON_STRING_ASSIGNMENT = Pattern.compile(
            "(?m)^\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(?:[fFrRuUbB]{0,2})(\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')");

    private static final Pattern SPARK_SQL_CALL_START = Pattern.compile(
            "\\b(?:spark|sqlContext|hiveContext|session|sc_sql)\\s*\\.\\s*sql\\s*\\(");

    private static final Pattern DF_ASSIGN_READ_TABLE = Pattern.compile(
            "(?m)^\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(?:spark|sqlContext|hiveContext)\\s*(?:\\.\\s*read(?:\\.[a-zA-Z0-9_]+\\([^)]*\\))*)?\\s*\\.\\s*table\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");

    private static final Pattern DF_ASSIGN_SPARK_SQL_CREATE_TEMP_VIEW = Pattern.compile(
            "\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\.\\s*create(?:OrReplace)?(?:Global)?TempView\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");

    private static final Pattern DF_WRITE_TARGET = Pattern.compile(
            "\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\.\\s*write(?:\\s*\\.\\s*[a-zA-Z0-9_]+\\s*\\([^)]*\\))*\\s*\\.\\s*(saveAsTable|insertInto)\\s*\\(\\s*['\"]([^'\"]+)['\"]");

    private static final Pattern INLINE_READ_TO_WRITE = Pattern.compile(
            "\\b(?:spark|sqlContext|hiveContext)\\s*(?:\\.\\s*read(?:\\.[a-zA-Z0-9_]+\\([^)]*\\))*)?\\s*\\.\\s*table\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)[\\s\\S]{0,400}?\\.\\s*(saveAsTable|insertInto)\\s*\\(\\s*['\"]([^'\"]+)['\"]");

    private static final Pattern ALL_READ_TABLES = Pattern.compile(
            "\\b(?:spark|sqlContext|hiveContext)\\s*(?:\\.\\s*read(?:\\.[a-zA-Z0-9_]+\\([^)]*\\))*)?\\s*\\.\\s*table\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");

    private static final Pattern ALL_WRITE_TABLES = Pattern.compile(
            "\\.\\s*(?:saveAsTable|insertInto)\\s*\\(\\s*['\"]([^'\"]+)['\"]");

    private static final Pattern FORMAT_PLACEHOLDER = Pattern.compile("\\$\\{([a-zA-Z0-9_]+)}|\\{([a-zA-Z0-9_:.!]+)}");

    public ExtractionResult extract(ExtractionRequest request, Map<String, byte[]> unpackedFiles) {
        EDbVendor resolvedVendor = request.getTargetVendor() != null ? request.getTargetVendor() : EDbVendor.dbvsparksql;
        String defaultServer = nonBlankOr(request.getDefaultServer(), "spark_cluster");
        String defaultDb = nonBlankOr(request.getDefaultDatabase(), "default");
        String defaultSchema = nonBlankOr(request.getDefaultSchema(), "default");

        CatalogBuilder builder = new CatalogBuilder(resolvedVendor, defaultServer);

        for (Map.Entry<String, byte[]> entry : unpackedFiles.entrySet()) {
            String path = entry.getKey();
            if (path.startsWith("__")) {
                continue;
            }
            String lower = path.toLowerCase(Locale.ROOT);
            String rawText = ArchiveUnpacker.decodeText(entry.getValue());
            if (rawText.isBlank()) {
                continue;
            }
            if (lower.endsWith(".ipynb")) {
                extractNotebook(path, rawText, defaultDb, defaultSchema, builder);
            } else if (lower.endsWith(".py") || !lower.endsWith(".json")) {
                extractPythonSource(path, rawText, defaultDb, defaultSchema, builder);
            }
        }

        if (builder.buildSqlUnits().isEmpty()) {
            builder.addWarning("No spark.sql(...) or DataFrame table operations were found in uploaded PySpark files");
        }

        SqlflowCatalog catalog = builder.buildCatalog();
        ExtractionResult result = new ExtractionResult();
        result.setResolvedVendor(resolvedVendor);
        result.setCatalog(catalog);
        result.setSqlUnits(builder.buildSqlUnits());
        result.setExporterMetadataJson(ConnectorsJson.toJson(catalog));
        result.setWarnings(builder.buildWarnings());
        return result;
    }

    private void extractNotebook(
            String notebookPath,
            String jsonText,
            String defaultDb,
            String defaultSchema,
            CatalogBuilder builder) {
        try {
            JsonNode root = ConnectorsJson.mapper().readTree(jsonText);
            JsonNode cells = root.path("cells");
            if (!cells.isArray()) {
                builder.addWarning("Notebook '" + notebookPath + "' has no cells[] array");
                return;
            }
            StringBuilder combinedPython = new StringBuilder();
            int cellIdx = 0;
            for (JsonNode cell : cells) {
                String cellType = cell.path("cell_type").asText("");
                if ("code".equalsIgnoreCase(cellType)) {
                    String cellCode = readNotebookCellSource(cell.path("source"));
                    String trimmedCell = cellCode.stripLeading();
                    if (trimmedCell.startsWith("%%sql") || trimmedCell.startsWith("%sql")) {
                        String magicSql = stripSqlMagicHeader(trimmedCell);
                        if (!magicSql.isBlank()) {
                            String sanitized = sanitizeInterpolatedSql(magicSql);
                            builder.addQuerySource(
                                    defaultDb,
                                    defaultSchema,
                                    "cell_" + cellIdx,
                                    "notebook_sql",
                                    ensureSemicolon(sanitized),
                                    notebookPath + "#cell_" + cellIdx);
                        }
                    } else {
                        combinedPython.append(cellCode).append("\n\n");
                    }
                }
                cellIdx++;
            }
            if (!combinedPython.isEmpty()) {
                extractPythonSource(notebookPath, combinedPython.toString(), defaultDb, defaultSchema, builder);
            }
        } catch (IOException e) {
            builder.addWarning("Failed to parse Jupyter notebook '" + notebookPath + "': " + e.getMessage());
        }
    }

    private void extractPythonSource(
            String filePath,
            String pythonCode,
            String defaultDb,
            String defaultSchema,
            CatalogBuilder builder) {

        // 1. Collect top-level / local string variable assignments (e.g., query = """SELECT ...""")
        Map<String, String> stringVars = new LinkedHashMap<>();
        Matcher assignMatcher = PYTHON_STRING_ASSIGNMENT.matcher(pythonCode);
        while (assignMatcher.find()) {
            String varName = assignMatcher.group(1);
            String unquoted = unquotePythonStringLiteral(assignMatcher.group(2));
            stringVars.put(varName, unquoted);
        }

        // 2. Track DataFrame variable assignments from spark.sql(...) and spark.read.table(...)
        Map<String, String> dfSourceSqlOrTable = new LinkedHashMap<>();
        Matcher readAssignMatcher = DF_ASSIGN_READ_TABLE.matcher(pythonCode);
        while (readAssignMatcher.find()) {
            String dfVar = readAssignMatcher.group(1);
            String srcTable = sanitizeTableIdentifier(readAssignMatcher.group(2));
            dfSourceSqlOrTable.put(dfVar, "SELECT * FROM " + srcTable);
            registerTableReference(srcTable, defaultDb, defaultSchema, builder);
        }

        // 3. Extract all spark.sql(...) / sqlContext.sql(...) calls
        List<ExtractedSqlCall> sqlCalls = findSparkSqlCalls(pythonCode, stringVars);
        int callIdx = 0;
        for (ExtractedSqlCall call : sqlCalls) {
            String cleaned = sanitizeInterpolatedSql(call.sql);
            if (cleaned.isBlank()) {
                continue;
            }
            if (call.assignedDfVar != null) {
                dfSourceSqlOrTable.put(call.assignedDfVar, stripTrailingSemicolon(cleaned));
            }
            builder.addQuerySource(
                    defaultDb,
                    defaultSchema,
                    "spark_sql_" + callIdx++,
                    "pyspark_sql",
                    ensureSemicolon(cleaned),
                    filePath);
        }

        // 4. Handle df.createOrReplaceTempView("tmp_name") -> wrap assigned DF query as CREATE VIEW tmp_name AS ...
        Matcher tempViewMatcher = DF_ASSIGN_SPARK_SQL_CREATE_TEMP_VIEW.matcher(pythonCode);
        while (tempViewMatcher.find()) {
            String dfVar = tempViewMatcher.group(1);
            String viewName = sanitizeTableIdentifier(tempViewMatcher.group(2));
            String upstreamSelect = dfSourceSqlOrTable.get(dfVar);
            if (upstreamSelect != null && !upstreamSelect.isBlank()) {
                builder.addViewDefinition(defaultDb, defaultSchema, viewName, upstreamSelect, false);
            }
        }

        // 5. Synthesize lineage SQL for DataFrame .write.saveAsTable(...) and .write.insertInto(...)
        Set<String> handledWriteTargets = new LinkedHashSet<>();

        Matcher inlineChain = INLINE_READ_TO_WRITE.matcher(pythonCode);
        while (inlineChain.find()) {
            String srcTable = sanitizeTableIdentifier(inlineChain.group(1));
            String writeOp = inlineChain.group(2);
            String dstTable = sanitizeTableIdentifier(inlineChain.group(3));
            registerTableReference(srcTable, defaultDb, defaultSchema, builder);
            registerTableReference(dstTable, defaultDb, defaultSchema, builder);
            String stmt = buildWriteLineageSql(writeOp, dstTable, "SELECT * FROM " + srcTable);
            builder.addQuerySource(defaultDb, defaultSchema, "df_write_" + dstTable, "pyspark_df", stmt, filePath);
            handledWriteTargets.add(dstTable);
        }

        Matcher dfWriteMatcher = DF_WRITE_TARGET.matcher(pythonCode);
        while (dfWriteMatcher.find()) {
            String dfVar = dfWriteMatcher.group(1);
            String writeOp = dfWriteMatcher.group(2);
            String dstTable = sanitizeTableIdentifier(dfWriteMatcher.group(3));
            registerTableReference(dstTable, defaultDb, defaultSchema, builder);

            String upstreamSelect = dfSourceSqlOrTable.get(dfVar);
            if (upstreamSelect != null && !upstreamSelect.isBlank()) {
                String stmt = buildWriteLineageSql(writeOp, dstTable, upstreamSelect);
                builder.addQuerySource(defaultDb, defaultSchema, "df_write_" + dstTable, "pyspark_df", stmt, filePath);
                handledWriteTargets.add(dstTable);
            }
        }

        // Register any remaining standalone .read.table(...) or .saveAsTable(...) references in catalog
        Matcher allReads = ALL_READ_TABLES.matcher(pythonCode);
        while (allReads.find()) {
            registerTableReference(sanitizeTableIdentifier(allReads.group(1)), defaultDb, defaultSchema, builder);
        }
        Matcher allWrites = ALL_WRITE_TABLES.matcher(pythonCode);
        while (allWrites.find()) {
            String dst = sanitizeTableIdentifier(allWrites.group(1));
            registerTableReference(dst, defaultDb, defaultSchema, builder);
            if (!handledWriteTargets.contains(dst) && builder.buildSqlUnits().isEmpty()) {
                builder.addQuerySource(
                        defaultDb,
                        defaultSchema,
                        "df_target_" + dst,
                        "pyspark_df",
                        "CREATE TABLE " + dst + " AS SELECT 1 AS col;",
                        filePath);
            }
        }
    }

    private static List<ExtractedSqlCall> findSparkSqlCalls(String code, Map<String, String> stringVars) {
        List<ExtractedSqlCall> out = new ArrayList<>();
        Matcher m = SPARK_SQL_CALL_START.matcher(code);
        while (m.find()) {
            int matchStart = m.start();
            int openParenIdx = m.end() - 1;
            int closeParenIdx = findMatchingCloseParen(code, openParenIdx);
            if (closeParenIdx <= openParenIdx) {
                continue;
            }
            String assignedDfVar = detectAssignedVariableBefore(code, matchStart);
            String argExpr = code.substring(openParenIdx + 1, closeParenIdx).trim();
            String resolvedSql = evaluatePythonStringArg(argExpr, stringVars);
            if (resolvedSql != null && !resolvedSql.isBlank()) {
                out.add(new ExtractedSqlCall(resolvedSql, assignedDfVar));
            }
        }
        return out;
    }

    private static String detectAssignedVariableBefore(String code, int callStart) {
        int lineStart = code.lastIndexOf('\n', Math.max(0, callStart - 1)) + 1;
        String prefix = code.substring(lineStart, callStart).trim();
        if (prefix.endsWith("=") && !prefix.endsWith("==")) {
            String lhs = prefix.substring(0, prefix.length() - 1).trim();
            if (lhs.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                return lhs;
            }
        }
        return null;
    }

    private static int findMatchingCloseParen(String code, int openParenIdx) {
        int depth = 0;
        int i = openParenIdx;
        int n = code.length();
        while (i < n) {
            char c = code.charAt(i);
            if (c == '#') {
                int nl = code.indexOf('\n', i);
                i = nl < 0 ? n : nl + 1;
                continue;
            }
            if (c == '"' || c == '\'') {
                i = skipPythonQuotedLiteral(code, i);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    private static int skipPythonQuotedLiteral(String code, int quoteStart) {
        char q = code.charAt(quoteStart);
        int n = code.length();
        if (quoteStart + 2 < n && code.charAt(quoteStart + 1) == q && code.charAt(quoteStart + 2) == q) {
            String delim = "" + q + q + q;
            int end = code.indexOf(delim, quoteStart + 3);
            return end < 0 ? n : end + 3;
        }
        int i = quoteStart + 1;
        while (i < n) {
            char c = code.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == q) {
                return i + 1;
            }
            i++;
        }
        return n;
    }

    private static String evaluatePythonStringArg(String argExpr, Map<String, String> stringVars) {
        String expr = argExpr.trim();
        // Strip trailing .format(...) call inside spark.sql("...".format(...))
        int dotFormat = expr.lastIndexOf(".format(");
        if (dotFormat > 0) {
            expr = expr.substring(0, dotFormat).trim();
        }
        while (expr.startsWith("(") && expr.endsWith(")") && findMatchingCloseParen(expr, 0) == expr.length() - 1) {
            expr = expr.substring(1, expr.length() - 1).trim();
        }
        if (expr.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            return stringVars.get(expr);
        }

        // Extract one or more adjacent/concatenated Python string literals
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int n = expr.length();
        boolean foundLiteral = false;
        while (i < n) {
            char c = expr.charAt(i);
            if (Character.isWhitespace(c) || c == '+' || c == '\\') {
                i++;
                continue;
            }
            // String prefix (f, r, u, b)
            if ("fFrRuUbB".indexOf(c) >= 0 && i + 1 < n
                    && (expr.charAt(i + 1) == '"' || expr.charAt(i + 1) == '\''
                            || ("fFrRuUbB".indexOf(expr.charAt(i + 1)) >= 0 && i + 2 < n
                                    && (expr.charAt(i + 2) == '"' || expr.charAt(i + 2) == '\'')))) {
                i++;
                if ("fFrRuUbB".indexOf(expr.charAt(i)) >= 0) {
                    i++;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                int end = skipPythonQuotedLiteral(expr, i);
                sb.append(unquotePythonStringLiteral(expr.substring(i, end)));
                foundLiteral = true;
                i = end;
            } else {
                break;
            }
        }
        return foundLiteral ? sb.toString() : null;
    }

    private static String unquotePythonStringLiteral(String literal) {
        String s = literal.trim();
        while (!s.isEmpty() && "fFrRuUbB".indexOf(s.charAt(0)) >= 0) {
            s = s.substring(1);
        }
        if ((s.startsWith("\"\"\"") && s.endsWith("\"\"\"")) || (s.startsWith("'''") && s.endsWith("'''"))) {
            s = s.substring(3, s.length() - 3);
        } else if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1);
        }
        return s.replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\'", "'");
    }

    static String sanitizeInterpolatedSql(String rawSql) {
        Matcher m = FORMAT_PLACEHOLDER.matcher(rawSql);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String token = m.group(1) != null ? m.group(1) : m.group(2);
            String ident = token.replaceAll("[^a-zA-Z0-9_]", "_");
            if (ident.isEmpty() || Character.isDigit(ident.charAt(0))) {
                ident = "param_" + ident;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(ident));
        }
        m.appendTail(sb);
        return sb.toString().trim();
    }

    private static String sanitizeTableIdentifier(String raw) {
        return sanitizeInterpolatedSql(raw.trim());
    }

    private static void registerTableReference(String fqn, String defaultDb, String defaultSchema, CatalogBuilder builder) {
        if (fqn == null || fqn.isBlank()) {
            return;
        }
        String[] parts = fqn.split("\\.");
        if (parts.length >= 3) {
            builder.getOrCreateTable(parts[0], parts[1], parts[2]);
        } else if (parts.length == 2) {
            builder.getOrCreateTable(parts[0], parts[0], parts[1]);
        } else {
            builder.getOrCreateTable(defaultDb, defaultSchema, parts[0]);
        }
    }

    private static String buildWriteLineageSql(String writeOp, String targetTable, String selectQuery) {
        String cleanSelect = stripTrailingSemicolon(selectQuery.trim());
        String upper = cleanSelect.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("SELECT ") && !upper.startsWith("WITH ")) {
            cleanSelect = "SELECT * FROM (" + cleanSelect + ") _src";
        }
        if ("insertInto".equalsIgnoreCase(writeOp)) {
            return "INSERT INTO " + targetTable + "\n" + cleanSelect + ";";
        }
        return "CREATE TABLE " + targetTable + " AS\n" + cleanSelect + ";";
    }

    private static String readNotebookCellSource(JsonNode sourceNode) {
        if (sourceNode == null || sourceNode.isNull() || sourceNode.isMissingNode()) {
            return "";
        }
        if (sourceNode.isTextual()) {
            return sourceNode.asText();
        }
        if (sourceNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode line : sourceNode) {
                sb.append(line.asText(""));
            }
            return sb.toString();
        }
        return "";
    }

    private static String stripSqlMagicHeader(String cellText) {
        int nl = cellText.indexOf('\n');
        if (cellText.startsWith("%%sql")) {
            return nl >= 0 ? cellText.substring(nl + 1).trim() : "";
        }
        if (cellText.startsWith("%sql")) {
            return cellText.substring("%sql".length()).trim();
        }
        return cellText;
    }

    private static String stripTrailingSemicolon(String sql) {
        String s = sql;
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    private static String ensureSemicolon(String sql) {
        String t = sql.stripTrailing();
        return t.endsWith(";") ? t : t + ";";
    }

    private static String nonBlankOr(String candidate, String fallback) {
        return (candidate != null && !candidate.isBlank()) ? candidate.trim() : fallback;
    }

    private record ExtractedSqlCall(String sql, String assignedDfVar) {
    }
}
