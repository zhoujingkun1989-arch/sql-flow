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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Amazon Redshift Audit &amp; Query Log Extractor ({@code OBL-078}, {@code 02 §6.4.2}):
 * <ul>
 *   <li>Parses Redshift {@code STL_QUERYTEXT} / {@code SVL_STATEMENTTEXT} CSV, pipe-delimited, and TSV exports,
 *       grouping multi-part 200-char chunks by {@code query_id} and sorting strictly by {@code sequence ASC}</li>
 *   <li>Parses Redshift JSON / NDJSON query log exports (reassembling fragments by {@code query} &amp; {@code sequence})</li>
 *   <li>Parses Redshift S3 User Activity Audit Logs ({@code 'timestamp UTC [ db=... user=... pid=... userid=... xid=... ]' LOG: ...}
 *       and {@code gu@du}-delimited blocks)</li>
 *   <li>Filters cursor/session noise ({@code fetch}, {@code close}, {@code commit}, {@code set}) and emits structured
 *       SQL batches prefixed with canonical {@code -- time=..., db=..., user=..., userid=..., pid=..., xid=...} metadata headers</li>
 * </ul>
 */
public final class RedshiftLogExtractor {

    private static final Pattern AUDIT_LOG_HEADER = Pattern.compile(
            "^'?(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z?(?:\\s*UTC)?)'?\\s*\\[([^\\]]+)]'?\\s*LOG:\\s*(.*)$");

    private static final Pattern KV_PAIR = Pattern.compile("([a-zA-Z0-9_-]+)=([^\\s]+)");

    public ExtractionResult extract(ExtractionRequest request, Map<String, byte[]> unpackedFiles) {
        EDbVendor resolvedVendor = request.getTargetVendor() != null ? request.getTargetVendor() : EDbVendor.dbvredshift;
        String defaultServer = nonBlankOr(request.getDefaultServer(), "redshift_cluster");
        String defaultDb = nonBlankOr(request.getDefaultDatabase(), "dev");
        String defaultSchema = nonBlankOr(request.getDefaultSchema(), "public");

        CatalogBuilder builder = new CatalogBuilder(resolvedVendor, defaultServer);

        for (Map.Entry<String, byte[]> entry : unpackedFiles.entrySet()) {
            String path = entry.getKey();
            if (path.startsWith("__")) {
                continue;
            }
            String content = ArchiveUnpacker.decodeText(entry.getValue());
            if (content.isBlank()) {
                continue;
            }
            parseSingleLogFile(path, content, defaultDb, defaultSchema, builder);
        }

        if (builder.buildSqlUnits().isEmpty()) {
            builder.addWarning("No executable Redshift SQL statements were found in uploaded log files");
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

    private void parseSingleLogFile(
            String filePath,
            String content,
            String defaultDb,
            String defaultSchema,
            CatalogBuilder builder) {
        String trimmed = content.trim();
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            if (tryParseJsonLog(filePath, trimmed, defaultDb, defaultSchema, builder)) {
                return;
            }
        }
        if (looksLikeAuditTextLog(trimmed)) {
            parseAuditTextLog(filePath, content, defaultDb, defaultSchema, builder);
            return;
        }
        parseTabularStlQueryText(filePath, content, defaultDb, defaultSchema, builder);
    }

    private boolean tryParseJsonLog(
            String filePath,
            String trimmed,
            String defaultDb,
            String defaultSchema,
            CatalogBuilder builder) {
        List<LogFragment> fragments = new ArrayList<>();
        try {
            if (trimmed.startsWith("[")) {
                JsonNode arr = ConnectorsJson.mapper().readTree(trimmed);
                if (!arr.isArray()) {
                    return false;
                }
                int idx = 0;
                for (JsonNode item : arr) {
                    extractFragmentFromJsonNode(item, idx++, defaultDb, fragments);
                }
            } else {
                // NDJSON (one JSON object per line)
                String[] lines = trimmed.split("\\r?\\n");
                int idx = 0;
                for (String line : lines) {
                    String ln = line.trim();
                    if (ln.isEmpty()) {
                        continue;
                    }
                    JsonNode obj = ConnectorsJson.mapper().readTree(ln);
                    extractFragmentFromJsonNode(obj, idx++, defaultDb, fragments);
                }
            }
        } catch (IOException e) {
            return false;
        }

        if (fragments.isEmpty()) {
            return false;
        }
        emitReassembledFragments(filePath, fragments, defaultSchema, builder);
        return true;
    }

    private static void extractFragmentFromJsonNode(
            JsonNode obj,
            int rowIdx,
            String defaultDb,
            List<LogFragment> out) {
        if (obj == null || !obj.isObject()) {
            return;
        }
        String text = firstJsonText(obj, "text", "query_text", "querytxt", "sql", "statement");
        if (text == null) {
            return;
        }
        String queryId = firstJsonText(obj, "query", "query_id", "queryid", "xid");
        if (queryId == null || queryId.isBlank()) {
            queryId = "json_row_" + rowIdx;
        }
        int seq = parseSequence(firstJsonText(obj, "sequence", "seq"), 0);
        String db = nonBlankOr(firstJsonText(obj, "database", "db", "database_name"), defaultDb);
        String user = firstJsonText(obj, "user", "username", "usename");
        String userId = firstJsonText(obj, "userid", "user_id");
        String pid = firstJsonText(obj, "pid", "process_id");
        String xid = firstJsonText(obj, "xid", "transaction_id");
        String startTime = firstJsonText(obj, "starttime", "recordtime", "time", "timestamp");

        out.add(new LogFragment(queryId, seq, unescapeStlText(text), db, user, userId, pid, xid, startTime));
    }

    private void parseAuditTextLog(
            String filePath,
            String content,
            String defaultDb,
            String defaultSchema,
            CatalogBuilder builder) {
        if (content.contains("gu@du")) {
            String[] segments = content.split("gu@du");
            int idx = 0;
            for (String seg : segments) {
                processSingleAuditEntry(filePath, seg.trim(), idx++, defaultDb, defaultSchema, builder);
            }
            return;
        }

        String[] lines = content.split("\\r?\\n");
        StringBuilder currentEntry = new StringBuilder();
        int idx = 0;
        for (String line : lines) {
            if (AUDIT_LOG_HEADER.matcher(line).matches()) {
                if (!currentEntry.isEmpty()) {
                    processSingleAuditEntry(filePath, currentEntry.toString(), idx++, defaultDb, defaultSchema, builder);
                    currentEntry.setLength(0);
                }
            }
            if (!currentEntry.isEmpty()) {
                currentEntry.append('\n');
            }
            currentEntry.append(line);
        }
        if (!currentEntry.isEmpty()) {
            processSingleAuditEntry(filePath, currentEntry.toString(), idx, defaultDb, defaultSchema, builder);
        }
    }

    private void processSingleAuditEntry(
            String filePath,
            String rawEntry,
            int entryIdx,
            String defaultDb,
            String defaultSchema,
            CatalogBuilder builder) {
        if (rawEntry.isBlank()) {
            return;
        }
        int firstNewline = rawEntry.indexOf('\n');
        String firstLine = firstNewline >= 0 ? rawEntry.substring(0, firstNewline) : rawEntry;
        String remainder = firstNewline >= 0 ? rawEntry.substring(firstNewline + 1) : "";

        Matcher m = AUDIT_LOG_HEADER.matcher(firstLine);
        if (!m.matches()) {
            String cleaned = cleanSqlStatement(rawEntry);
            if (!cleaned.isEmpty() && !isNoiseStatement(cleaned)) {
                builder.addQuerySource(defaultDb, defaultSchema, "log_" + entryIdx, "query_history", cleaned, filePath);
            }
            return;
        }

        String timestamp = m.group(1).trim();
        String bracketMeta = m.group(2).trim();
        String firstSqlLine = m.group(3);
        String fullSqlBody = remainder.isEmpty() ? firstSqlLine : (firstSqlLine + "\n" + remainder);
        fullSqlBody = stripAuditStatementPrefix(fullSqlBody);
        String cleanedSql = cleanSqlStatement(fullSqlBody);
        if (cleanedSql.isEmpty() || isNoiseStatement(cleanedSql)) {
            return;
        }

        Map<String, String> kv = new LinkedHashMap<>();
        Matcher kvMatcher = KV_PAIR.matcher(bracketMeta);
        while (kvMatcher.find()) {
            kv.put(kvMatcher.group(1).toLowerCase(Locale.ROOT), kvMatcher.group(2));
        }
        String db = nonBlankOr(kv.get("db"), defaultDb);
        String header = formatMetadataHeader(
                timestamp,
                kv.get("db-instance"),
                kv.get("pid"),
                db,
                kv.get("user"),
                kv.get("userid"),
                kv.get("xid"),
                null);
        String decorated = header + "\n" + ensureSemicolon(cleanedSql);
        builder.addQuerySource(db, defaultSchema, "audit_" + entryIdx, "query_history", decorated, filePath);
    }

    private void parseTabularStlQueryText(
            String filePath,
            String content,
            String defaultDb,
            String defaultSchema,
            CatalogBuilder builder) {
        char delimiter = detectDelimiter(content);
        List<List<String>> rows = CsvMetadataExtractor.parseDelimitedRecords(content, delimiter, '"', '"');
        if (rows.isEmpty()) {
            return;
        }

        List<String> firstRow = rows.get(0);
        ColumnMapping mapping = detectStlColumnMapping(firstRow);
        int startIdx = mapping.hasHeader ? 1 : 0;

        List<LogFragment> fragments = new ArrayList<>();
        for (int r = startIdx; r < rows.size(); r++) {
            List<String> cols = rows.get(r);
            if (cols.isEmpty() || cols.stream().allMatch(String::isBlank)) {
                continue;
            }
            String text = getCol(cols, mapping.textIdx);
            if (text == null || text.isEmpty()) {
                continue;
            }
            String queryId = getColTrim(cols, mapping.queryIdx);
            String xid = getColTrim(cols, mapping.xidIdx);
            String pid = getColTrim(cols, mapping.pidIdx);
            if (queryId == null || queryId.isEmpty()) {
                queryId = (xid != null && !xid.isEmpty()) ? ("xid_" + xid) : ("row_" + r);
            }
            int seq = parseSequence(getColTrim(cols, mapping.sequenceIdx), 0);
            String db = nonBlankOr(getColTrim(cols, mapping.dbIdx), defaultDb);
            String user = getColTrim(cols, mapping.userIdx);
            String userId = getColTrim(cols, mapping.userIdIdx);
            String startTime = getColTrim(cols, mapping.timeIdx);

            if ("1".equals(userId)) {
                // Skip Redshift internal rdsdb superuser background queries (userid = 1)
                continue;
            }
            fragments.add(new LogFragment(queryId, seq, unescapeStlText(text), db, user, userId, pid, xid, startTime));
        }

        emitReassembledFragments(filePath, fragments, defaultSchema, builder);
    }

    private static void emitReassembledFragments(
            String filePath,
            List<LogFragment> fragments,
            String defaultSchema,
            CatalogBuilder builder) {
        Map<String, List<LogFragment>> byQueryId = new LinkedHashMap<>();
        for (LogFragment frag : fragments) {
            byQueryId.computeIfAbsent(frag.queryId, k -> new ArrayList<>()).add(frag);
        }

        for (Map.Entry<String, List<LogFragment>> entry : byQueryId.entrySet()) {
            String queryId = entry.getKey();
            List<LogFragment> parts = entry.getValue();
            parts.sort(Comparator.comparingInt(f -> f.sequence));

            StringBuilder sqlBuf = new StringBuilder();
            for (LogFragment part : parts) {
                sqlBuf.append(part.text);
            }
            String assembled = cleanSqlStatement(sqlBuf.toString());
            if (assembled.isEmpty() || isNoiseStatement(assembled)) {
                continue;
            }
            LogFragment meta = parts.get(0);
            String header = formatMetadataHeader(
                    meta.startTime,
                    null,
                    meta.pid,
                    meta.database,
                    meta.user,
                    meta.userId,
                    meta.xid,
                    queryId);
            String finalSql = header.isEmpty() ? ensureSemicolon(assembled) : (header + "\n" + ensureSemicolon(assembled));
            builder.addQuerySource(meta.database, defaultSchema, "query_" + queryId, "query_history", finalSql, filePath);
        }
    }

    private static ColumnMapping detectStlColumnMapping(List<String> headerCandidate) {
        int queryIdx = -1;
        int seqIdx = -1;
        int textIdx = -1;
        int dbIdx = -1;
        int userIdx = -1;
        int userIdIdx = -1;
        int pidIdx = -1;
        int xidIdx = -1;
        int timeIdx = -1;

        for (int i = 0; i < headerCandidate.size(); i++) {
            String h = headerCandidate.get(i).trim().toLowerCase(Locale.ROOT);
            switch (h) {
                case "query", "query_id", "queryid" -> queryIdx = i;
                case "sequence", "seq" -> seqIdx = i;
                case "text", "querytxt", "query_text", "sql", "statement" -> textIdx = i;
                case "database", "db", "database_name", "dbname" -> dbIdx = i;
                case "user", "username", "usename" -> userIdx = i;
                case "userid", "user_id" -> userIdIdx = i;
                case "pid", "process", "process_id" -> pidIdx = i;
                case "xid", "transaction_id" -> xidIdx = i;
                case "starttime", "start_time", "recordtime", "time", "endtime" -> timeIdx = i;
                default -> {
                }
            }
        }
        if (textIdx >= 0) {
            return new ColumnMapping(true, queryIdx, seqIdx, textIdx, dbIdx, userIdx, userIdIdx, pidIdx, xidIdx, timeIdx);
        }
        // Standard headerless STL_QUERYTEXT layout: userid(0), xid(1), pid(2), query(3), starttime(4), sequence(5), text(6)
        if (headerCandidate.size() >= 7) {
            return new ColumnMapping(false, 3, 5, 6, -1, -1, 0, 2, 1, 4);
        }
        // Compact headerless layout: query(0), sequence(1), text(2)
        if (headerCandidate.size() >= 3 && headerCandidate.get(0).trim().matches("\\d+")
                && headerCandidate.get(1).trim().matches("\\d+")) {
            return new ColumnMapping(false, 0, 1, 2, -1, -1, -1, -1, -1, -1);
        }
        // Single-column SQL log
        return new ColumnMapping(false, -1, -1, 0, -1, -1, -1, -1, -1, -1);
    }

    private static char detectDelimiter(String sample) {
        int nl = sample.indexOf('\n');
        String firstLine = nl >= 0 ? sample.substring(0, nl) : sample;
        int pipes = countChar(firstLine, '|');
        int tabs = countChar(firstLine, '\t');
        int commas = countChar(firstLine, ',');
        if (pipes >= commas && pipes >= tabs && pipes > 0) {
            return '|';
        }
        if (tabs > commas && tabs > 0) {
            return '\t';
        }
        return ',';
    }

    private static int countChar(String s, char target) {
        int c = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == target) {
                c++;
            }
        }
        return c;
    }

    private static boolean looksLikeAuditTextLog(String sample) {
        if (sample.contains("gu@du")) {
            return true;
        }
        String firstLine = sample.lines().findFirst().orElse("");
        return AUDIT_LOG_HEADER.matcher(firstLine.trim()).matches();
    }

    private static String stripAuditStatementPrefix(String raw) {
        String s = raw.stripLeading();
        if (s.startsWith("statement:")) {
            return s.substring("statement:".length()).stripLeading();
        }
        if (s.startsWith("duration:")) {
            int stmtIdx = s.indexOf("statement:");
            if (stmtIdx >= 0) {
                return s.substring(stmtIdx + "statement:".length()).stripLeading();
            }
        }
        return s;
    }

    private static String unescapeStlText(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }

    private static String cleanSqlStatement(String sql) {
        if (sql == null) {
            return "";
        }
        String t = sql.trim();
        if (t.endsWith("'") && t.startsWith("'")) {
            t = t.substring(1, t.length() - 1).replace("''", "'").trim();
        }
        return t;
    }

    private static boolean isNoiseStatement(String sql) {
        String u = sql.stripLeading().toUpperCase(Locale.ROOT);
        return u.isEmpty()
                || u.startsWith("FETCH ")
                || u.startsWith("CLOSE ")
                || u.startsWith("DECLARE ") && u.contains(" CURSOR ")
                || u.equals("BEGIN") || u.equals("BEGIN;")
                || u.equals("COMMIT") || u.equals("COMMIT;") || u.equals("END") || u.equals("END;")
                || u.equals("ROLLBACK") || u.equals("ROLLBACK;")
                || u.startsWith("SET ")
                || u.startsWith("SHOW ")
                || u.startsWith("PADB_FETCH_SAMPLE")
                || u.equals("SELECT 1") || u.equals("SELECT 1;");
    }

    private static String formatMetadataHeader(
            String time,
            String dbInstance,
            String pid,
            String db,
            String user,
            String userId,
            String xid,
            String queryId) {
        List<String> attrs = new ArrayList<>();
        if (notBlank(time)) {
            attrs.add("time=" + time);
        }
        if (notBlank(dbInstance)) {
            attrs.add("db-instance=" + dbInstance);
        }
        if (notBlank(pid)) {
            attrs.add("pid=" + pid);
        }
        if (notBlank(db)) {
            attrs.add("db=" + db);
        }
        if (notBlank(user)) {
            attrs.add("user=" + user);
        }
        if (notBlank(userId)) {
            attrs.add("userid=" + userId);
        }
        if (notBlank(xid)) {
            attrs.add("xid=" + xid);
        }
        if (notBlank(queryId)) {
            attrs.add("query=" + queryId);
        }
        return attrs.isEmpty() ? "" : "-- " + String.join(", ", attrs);
    }

    private static String ensureSemicolon(String sql) {
        String trimmed = sql.stripTrailing();
        return trimmed.endsWith(";") ? trimmed : trimmed + ";";
    }

    private static int parseSequence(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String getCol(List<String> row, int idx) {
        return (idx >= 0 && idx < row.size()) ? row.get(idx) : null;
    }

    private static String getColTrim(List<String> row, int idx) {
        String v = getCol(row, idx);
        return v != null ? v.trim() : null;
    }

    private static String firstJsonText(JsonNode obj, String... keys) {
        for (String key : keys) {
            JsonNode n = obj.get(key);
            if (n != null && !n.isNull() && n.isValueNode()) {
                String s = n.asText();
                if (s != null && !s.isBlank()) {
                    return s;
                }
            }
        }
        return null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nonBlankOr(String candidate, String fallback) {
        return notBlank(candidate) ? candidate.trim() : fallback;
    }

    private record LogFragment(
            String queryId,
            int sequence,
            String text,
            String database,
            String user,
            String userId,
            String pid,
            String xid,
            String startTime) {
    }

    private record ColumnMapping(
            boolean hasHeader,
            int queryIdx,
            int sequenceIdx,
            int textIdx,
            int dbIdx,
            int userIdx,
            int userIdIdx,
            int pidIdx,
            int xidIdx,
            int timeIdx) {
    }
}
