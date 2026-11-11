package io.sqlflow.connectors.extractor;

import io.sqlflow.connectors.jdbc.CatalogBuilder;
import io.sqlflow.connectors.util.ConnectorsJson;
import io.sqlflow.contracts.lineage.Column;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.contracts.spi.FileExtractorSpi.ExtractionRequest;
import io.sqlflow.contracts.spi.FileExtractorSpi.ExtractionResult;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CSV &amp; TSV Metadata, Stored-Code, and Lineage Extractor ({@code OBL-078}, {@code 02 §4.7, §6.4.4}, {@code 04 §9.3}):
 * <ul>
 *   <li>Honors the 6-digit {@code csvFormat} specification for stored-code CSVs (supporting both positional {@code "123456"} /
 *       {@code "124350"} from {@code 04 §9.3} and bitmask {@code "111110"} from {@code 02 §6.4.4}), plus custom
 *       {@code objectCodeEncloseChar} and {@code objectCodeEscapeChar}</li>
 *   <li>Parses schema/column catalog CSVs ({@code metadata.csv}, {@code INFORMATION_SCHEMA} dumps, Redshift
 *       {@code [svv_columns]} / {@code [svv_external_columns]} / {@code [pg_views_*]} sectioned TSV/CSV files)</li>
 *   <li>Parses 15/16-column lineage CSVs ({@code SourceServer..TargetColumn} / {@code source_db..target_column})
 *       into structured catalog tables, columns, and synthesized SQL lineage units</li>
 * </ul>
 */
public final class CsvMetadataExtractor {

    /** Optional virtual config entry keys inside {@link ExtractionRequest#getFiles()}. */
    public static final String CONFIG_CSV_FORMAT = "__csvFormat__";
    public static final String CONFIG_ENCLOSE_CHAR = "__objectCodeEncloseChar__";
    public static final String CONFIG_ESCAPE_CHAR = "__objectCodeEscapeChar__";
    public static final String CONFIG_DELIMITER_CHAR = "__delimiter__";

    public ExtractionResult extract(ExtractionRequest request, Map<String, byte[]> unpackedFiles) {
        String csvFormat = readConfigString(request.getFiles(), CONFIG_CSV_FORMAT, "csvFormat");
        String encloseStr = readConfigString(request.getFiles(), CONFIG_ENCLOSE_CHAR, "objectCodeEncloseChar");
        String escapeStr = readConfigString(request.getFiles(), CONFIG_ESCAPE_CHAR, "objectCodeEscapeChar");
        String delimStr = readConfigString(request.getFiles(), CONFIG_DELIMITER_CHAR, "delimiter");

        char encloseChar = (encloseStr != null && !encloseStr.isEmpty()) ? encloseStr.charAt(0) : '"';
        char escapeChar = (escapeStr != null && !escapeStr.isEmpty()) ? escapeStr.charAt(0) : encloseChar;
        Character explicitDelimiter = (delimStr != null && !delimStr.isEmpty()) ? delimStr.charAt(0) : null;

        return extractWithOptions(request, unpackedFiles, csvFormat, encloseChar, escapeChar, explicitDelimiter);
    }

    public ExtractionResult extractWithOptions(
            ExtractionRequest request,
            Map<String, byte[]> unpackedFiles,
            String csvFormat,
            char encloseChar,
            char escapeChar,
            Character explicitDelimiter) {
        EDbVendor resolvedVendor = request.getTargetVendor() != null ? request.getTargetVendor() : EDbVendor.dbvpostgresql;
        String defaultServer = nonBlankOr(request.getDefaultServer(), "csv_server");
        String defaultDb = nonBlankOr(request.getDefaultDatabase(), "default_db");
        String defaultSchema = nonBlankOr(request.getDefaultSchema(), "public");

        CatalogBuilder builder = new CatalogBuilder(resolvedVendor, defaultServer);
        CsvFormatLayout explicitLayout = parseSixDigitCsvFormat(csvFormat);

        for (Map.Entry<String, byte[]> entry : unpackedFiles.entrySet()) {
            String path = entry.getKey();
            if (path.startsWith("__") || "csvFormat".equals(path) || "objectCodeEncloseChar".equals(path)
                    || "objectCodeEscapeChar".equals(path) || "delimiter".equals(path)) {
                continue;
            }
            String content = ArchiveUnpacker.decodeText(entry.getValue());
            if (content.isBlank()) {
                continue;
            }

            if (containsRedshiftSectionMarkers(content)) {
                parseRedshiftSectionedDump(path, content, defaultDb, defaultSchema, builder);
                continue;
            }

            char delimiter = explicitDelimiter != null ? explicitDelimiter : detectCsvDelimiter(content, encloseChar);
            List<List<String>> rows = parseDelimitedRecords(content, delimiter, encloseChar, escapeChar);
            if (rows.isEmpty()) {
                continue;
            }

            if (explicitLayout != null) {
                parseStoredCodeRows(path, rows, explicitLayout, defaultDb, defaultSchema, builder);
            } else {
                dispatchAutoDetectedCsv(path, rows, defaultDb, defaultSchema, builder);
            }
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

    /**
     * Decodes SQLFlow's 6-digit {@code csvFormat} string ({@code database,schema,objectType,objectName,objectCode,comments})
     * supporting both positional 1..6 indices ({@code "123456"}, {@code "124350"}) and bitmasks ({@code "111110"}).
     */
    public static CsvFormatLayout parseSixDigitCsvFormat(String rawFormat) {
        if (rawFormat == null || rawFormat.isBlank()) {
            return null;
        }
        String f = rawFormat.trim();
        if (f.length() != 6 || !f.matches("[0-9]{6}")) {
            throw new IllegalArgumentException(
                    "Invalid 6-digit csvFormat '" + rawFormat + "': expected 6 digits for (database,schema,objectType,objectName,objectCode,comments)");
        }
        boolean allZeroOrOne = f.chars().allMatch(ch -> ch == '0' || ch == '1');
        long onesCount = f.chars().filter(ch -> ch == '1').count();

        int[] zeroBasedCols = new int[]{-1, -1, -1, -1, -1, -1};
        if (allZeroOrOne && onesCount > 1) {
            // Bitmask mode ("111110" -> sequential 0-based columns 0,1,2,3,4,-1)
            int nextCol = 0;
            for (int i = 0; i < 6; i++) {
                if (f.charAt(i) == '1') {
                    zeroBasedCols[i] = nextCol++;
                }
            }
        } else {
            // Positional mode ("123456" / "124350" / "000010" -> 1-based column index; '0' means omitted)
            for (int i = 0; i < 6; i++) {
                int pos = f.charAt(i) - '0';
                if (pos > 0) {
                    zeroBasedCols[i] = pos - 1;
                }
            }
        }
        return new CsvFormatLayout(
                zeroBasedCols[0],
                zeroBasedCols[1],
                zeroBasedCols[2],
                zeroBasedCols[3],
                zeroBasedCols[4],
                zeroBasedCols[5]);
    }

    private static void parseStoredCodeRows(
            String filePath,
            List<List<String>> rows,
            CsvFormatLayout layout,
            String defaultDb,
            String defaultSchema,
            CatalogBuilder builder) {
        if (rows.isEmpty()) {
            return;
        }
        int startRow = isStoredCodeHeaderRow(rows.get(0), layout) ? 1 : 0;
        int rowNum = 0;
        for (int r = startRow; r < rows.size(); r++) {
            List<String> cols = rows.get(r);
            if (cols.isEmpty() || cols.stream().allMatch(String::isBlank)) {
                continue;
            }
            String db = nonBlankOr(getCell(cols, layout.dbCol), defaultDb);
            String schema = nonBlankOr(getCell(cols, layout.schemaCol), defaultSchema);
            String objType = nonBlankOr(getCell(cols, layout.objectTypeCol), "query");
            String objName = nonBlankOr(getCell(cols, layout.objectNameCol), "csv_obj_" + (++rowNum));
            String objCode = getCellRaw(cols, layout.objectCodeCol);

            if (objCode == null || objCode.isBlank()) {
                continue;
            }
            registerStoredCodeRow(filePath, db, schema, objType, objName, objCode.trim(), builder);
        }
    }

    private static void registerStoredCodeRow(
            String filePath,
            String db,
            String schema,
            String objType,
            String objName,
            String objCode,
            CatalogBuilder builder) {
        String lowerType = objType.trim().toLowerCase(Locale.ROOT);
        if (lowerType.contains("view")) {
            boolean mat = lowerType.contains("materialized");
            builder.addViewDefinition(db, schema, objName, objCode, mat);
        } else if (lowerType.contains("proc") || lowerType.equals("p")) {
            builder.addRoutine(db, schema, null, objName, "procedure", objCode);
        } else if (lowerType.contains("func") || lowerType.equals("f") || lowerType.equals("fn")) {
            builder.addRoutine(db, schema, null, objName, "function", objCode);
        } else if (lowerType.contains("trig") || lowerType.equals("tr")) {
            builder.addRoutine(db, schema, null, objName, "trigger", objCode);
        } else {
            builder.addQuerySource(db, schema, objName, objType, objCode, filePath + "#" + objName);
        }
    }

    private static void dispatchAutoDetectedCsv(
            String filePath,
            List<List<String>> rows,
            String defaultDb,
            String defaultSchema,
            CatalogBuilder builder) {
        List<String> header = rows.get(0);
        Map<String, Integer> idxMap = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            idxMap.put(normalizeHeaderName(header.get(i)), i);
        }

        // Case A: Lineage CSV (15/16-column PascalCase or snake_case)
        if ((idxMap.containsKey("sourcetable") && idxMap.containsKey("targettable"))
                || (idxMap.containsKey("source_table") && idxMap.containsKey("target_table"))) {
            parseLineageCsvRows(filePath, rows, idxMap, defaultDb, defaultSchema, builder);
            return;
        }

        // Case B: Stored-code CSV with header (object_code / source_code / sql / ddl / view_definition)
        Integer codeIdx = firstPresent(idxMap, "objectcode", "object_code", "sourcecode", "source_code", "sql", "ddl", "definition", "view_definition", "routine_definition");
        if (codeIdx != null) {
            CsvFormatLayout inferred = new CsvFormatLayout(
                    firstPresentOrMinusOne(idxMap, "database", "db", "catalog", "table_catalog"),
                    firstPresentOrMinusOne(idxMap, "schema", "table_schema", "owner"),
                    firstPresentOrMinusOne(idxMap, "objecttype", "object_type", "type", "routine_type"),
                    firstPresentOrMinusOne(idxMap, "objectname", "object_name", "name", "table_name", "view_name", "routine_name"),
                    codeIdx,
                    firstPresentOrMinusOne(idxMap, "comments", "comment", "remarks", "description"));
            parseStoredCodeRows(filePath, rows, inferred, defaultDb, defaultSchema, builder);
            return;
        }

        // Case C: Schema / Table / Column catalog CSV (metadata.csv, INFORMATION_SCHEMA.COLUMNS, etc.)
        Integer colIdx = firstPresent(idxMap, "column", "column_name", "columnname", "colname", "attname");
        Integer tblIdx = firstPresent(idxMap, "table", "table_name", "tablename", "tabname", "object_name");
        if (colIdx != null && tblIdx != null) {
            parseSchemaCatalogRows(rows, idxMap, tblIdx, colIdx, defaultDb, defaultSchema, builder);
            return;
        }

        // Fallback: Default 6-column stored-code positional layout (123456)
        parseStoredCodeRows(filePath, rows, parseSixDigitCsvFormat("123456"), defaultDb, defaultSchema, builder);
    }

    private static void parseSchemaCatalogRows(
            List<List<String>> rows,
            Map<String, Integer> idxMap,
            int tblIdx,
            int colIdx,
            String defaultDb,
            String defaultSchema,
            CatalogBuilder builder) {
        int dbIdx = firstPresentOrMinusOne(idxMap, "database", "db", "table_catalog", "catalog", "database_name");
        int schemaIdx = firstPresentOrMinusOne(idxMap, "schema", "table_schema", "schemaname", "owner", "tabschema");
        int typeIdx = firstPresentOrMinusOne(idxMap, "data_type", "datatype", "type", "column_type", "external_type", "typename");
        int commentIdx = firstPresentOrMinusOne(idxMap, "comment", "comments", "remarks", "description");
        int nullableIdx = firstPresentOrMinusOne(idxMap, "nullable", "is_nullable", "nulls");
        int pkIdx = firstPresentOrMinusOne(idxMap, "primary_key", "primarykey", "is_pk", "pk");
        int fkIdx = firstPresentOrMinusOne(idxMap, "foreign_key", "foreignkey", "is_fk", "fk");
        int objKindIdx = firstPresentOrMinusOne(idxMap, "table_type", "object_type");

        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String table = getCell(row, tblIdx);
            String column = getCell(row, colIdx);
            if (table == null || column == null) {
                continue;
            }
            String db = nonBlankOr(getCell(row, dbIdx), defaultDb);
            String schema = nonBlankOr(getCell(row, schemaIdx), defaultSchema);
            String objKind = getCell(row, objKindIdx);
            boolean isView = objKind != null && objKind.toLowerCase(Locale.ROOT).contains("view");

            DbObject parent = isView
                    ? builder.getOrCreateView(db, schema, table)
                    : builder.getOrCreateTable(db, schema, table);
            Boolean nullable = parseBoolOrNull(getCell(row, nullableIdx));
            Column col = builder.addOrUpdateColumn(
                    parent,
                    column,
                    getCell(row, typeIdx),
                    nullable,
                    null,
                    getCell(row, commentIdx));
            if (col != null) {
                if (Boolean.TRUE.equals(parseBoolOrNull(getCell(row, pkIdx)))) {
                    col.setPrimaryKey(true);
                }
                if (Boolean.TRUE.equals(parseBoolOrNull(getCell(row, fkIdx)))) {
                    col.setForeignKey(true);
                }
            }
        }
    }

    private static void parseLineageCsvRows(
            String filePath,
            List<List<String>> rows,
            Map<String, Integer> idxMap,
            String defaultDb,
            String defaultSchema,
            CatalogBuilder builder) {
        int srcDbIdx = firstPresentOrMinusOne(idxMap, "sourcedatabase", "source_db");
        int srcSchemaIdx = firstPresentOrMinusOne(idxMap, "sourceschema", "source_schema");
        int srcTableIdx = firstPresentOrMinusOne(idxMap, "sourcetable", "source_table");
        int srcColIdx = firstPresentOrMinusOne(idxMap, "sourcecolumn", "source_column");
        int tgtDbIdx = firstPresentOrMinusOne(idxMap, "targetdatabase", "target_db");
        int tgtSchemaIdx = firstPresentOrMinusOne(idxMap, "targetschema", "target_schema");
        int tgtTableIdx = firstPresentOrMinusOne(idxMap, "targettable", "target_table");
        int tgtColIdx = firstPresentOrMinusOne(idxMap, "targetcolumn", "target_column");
        int tgtTypeIdx = firstPresentOrMinusOne(idxMap, "targetdatatype", "target_data_type");

        Map<String, List<String[]>> targetColumnMappings = new HashMap<>();

        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String srcTable = getCell(row, srcTableIdx);
            String tgtTable = getCell(row, tgtTableIdx);
            if (srcTable == null || tgtTable == null) {
                continue;
            }
            String srcDb = nonBlankOr(getCell(row, srcDbIdx), defaultDb);
            String srcSchema = nonBlankOr(getCell(row, srcSchemaIdx), defaultSchema);
            String srcCol = getCell(row, srcColIdx);

            String tgtDb = nonBlankOr(getCell(row, tgtDbIdx), defaultDb);
            String tgtSchema = nonBlankOr(getCell(row, tgtSchemaIdx), defaultSchema);
            String tgtCol = getCell(row, tgtColIdx);
            String tgtType = getCell(row, tgtTypeIdx);

            DbObject srcObj = builder.getOrCreateTable(srcDb, srcSchema, srcTable);
            if (srcCol != null && !"RelationRows".equalsIgnoreCase(srcCol)) {
                builder.addOrUpdateColumn(srcObj, srcCol, null, null, null, null);
            }
            DbObject tgtObj = builder.getOrCreateTable(tgtDb, tgtSchema, tgtTable);
            if (tgtCol != null && !"RelationRows".equalsIgnoreCase(tgtCol)) {
                builder.addOrUpdateColumn(tgtObj, tgtCol, tgtType, null, null, null);
            }

            if (srcCol != null && tgtCol != null) {
                String key = tgtDb + "." + tgtSchema + "." + tgtTable + "|" + srcDb + "." + srcSchema + "." + srcTable;
                targetColumnMappings.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new String[]{srcCol, tgtCol, tgtDb, tgtSchema, tgtTable});
            }
        }

        for (Map.Entry<String, List<String[]>> e : targetColumnMappings.entrySet()) {
            String[] tables = e.getKey().split("\\|");
            String tgtFqn = tables[0];
            String srcFqn = tables[1];
            List<String> selectExprs = new ArrayList<>();
            List<String> insertCols = new ArrayList<>();
            for (String[] pair : e.getValue()) {
                selectExprs.add(pair[0] + " AS " + pair[1]);
                insertCols.add(pair[1]);
            }
            String[] meta = e.getValue().get(0);
            String sql = "INSERT INTO " + tgtFqn + " (" + String.join(", ", insertCols) + ") SELECT "
                    + String.join(", ", selectExprs) + " FROM " + srcFqn + ";";
            builder.addQuerySource(meta[2], meta[3], meta[4], "lineage_csv", sql, filePath + "#" + meta[4]);
        }
    }

    private static void parseRedshiftSectionedDump(
            String filePath,
            String content,
            String defaultDb,
            String defaultSchema,
            CatalogBuilder builder) {
        String currentSection = "";
        StringBuilder sectionBody = new StringBuilder();
        for (String line : content.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]") && !trimmed.contains(",")) {
                flushRedshiftSection(filePath, currentSection, sectionBody.toString(), defaultDb, defaultSchema, builder);
                currentSection = trimmed.substring(1, trimmed.length() - 1).toLowerCase(Locale.ROOT);
                sectionBody.setLength(0);
            } else {
                sectionBody.append(line).append('\n');
            }
        }
        flushRedshiftSection(filePath, currentSection, sectionBody.toString(), defaultDb, defaultSchema, builder);
    }

    private static void flushRedshiftSection(
            String filePath,
            String section,
            String body,
            String defaultDb,
            String defaultSchema,
            CatalogBuilder builder) {
        if (section.isEmpty() || body.isBlank()) {
            return;
        }
        char delim = detectCsvDelimiter(body, '"');
        List<List<String>> rows = parseDelimitedRecords(body, delim, '"', '"');
        if (rows.isEmpty()) {
            return;
        }
        if (section.startsWith("pg_views")) {
            for (List<String> r : rows) {
                if (r.size() >= 3 && !"viewname".equalsIgnoreCase(r.get(1).trim())) {
                    builder.addViewDefinition(defaultDb, r.get(0).trim(), r.get(1).trim(), r.get(2).trim(), false);
                }
            }
        } else {
            dispatchAutoDetectedCsv(filePath + "[" + section + "]", rows, defaultDb, defaultSchema, builder);
        }
    }

    /**
     * Full multi-line RFC 4180 + custom enclose/escape CSV/TSV record parser.
     */
    public static List<List<String>> parseDelimitedRecords(
            String text,
            char delimiter,
            char encloseChar,
            char escapeChar) {
        List<List<String>> records = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return records;
        }
        List<String> currentRow = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int n = text.length();
        int i = 0;

        while (i < n) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (escapeChar != encloseChar && c == escapeChar && i + 1 < n) {
                    field.append(text.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == encloseChar) {
                    if (i + 1 < n && text.charAt(i + 1) == encloseChar) {
                        field.append(encloseChar);
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                field.append(c);
                i++;
            } else {
                if (c == encloseChar && field.isEmpty()) {
                    inQuotes = true;
                    i++;
                } else if (c == delimiter) {
                    currentRow.add(field.toString());
                    field.setLength(0);
                    i++;
                } else if (c == '\r' || c == '\n') {
                    if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                    currentRow.add(field.toString());
                    field.setLength(0);
                    if (!(currentRow.size() == 1 && currentRow.get(0).isEmpty())) {
                        records.add(currentRow);
                    }
                    currentRow = new ArrayList<>();
                    i++;
                } else {
                    field.append(c);
                    i++;
                }
            }
        }
        if (field.length() > 0 || !currentRow.isEmpty()) {
            currentRow.add(field.toString());
            if (!(currentRow.size() == 1 && currentRow.get(0).isEmpty())) {
                records.add(currentRow);
            }
        }
        return records;
    }

    private static char detectCsvDelimiter(String text, char encloseChar) {
        int nl = text.indexOf('\n');
        String firstLine = nl >= 0 ? text.substring(0, nl) : text;
        int commas = countOutsideQuotes(firstLine, ',', encloseChar);
        int tabs = countOutsideQuotes(firstLine, '\t', encloseChar);
        int pipes = countOutsideQuotes(firstLine, '|', encloseChar);
        if (tabs > commas && tabs >= pipes) {
            return '\t';
        }
        if (pipes > commas && pipes > tabs) {
            return '|';
        }
        return ',';
    }

    private static int countOutsideQuotes(String line, char target, char quote) {
        boolean inQuote = false;
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == quote) {
                inQuote = !inQuote;
            } else if (!inQuote && c == target) {
                count++;
            }
        }
        return count;
    }

    private static boolean containsRedshiftSectionMarkers(String text) {
        return text.contains("[svv_columns]") || text.contains("[svv_external_columns]") || text.contains("[pg_views_");
    }

    private static boolean isStoredCodeHeaderRow(List<String> firstRow, CsvFormatLayout layout) {
        String codeCell = getCell(firstRow, layout.objectCodeCol);
        if (codeCell != null) {
            String norm = normalizeHeaderName(codeCell);
            if ("objectcode".equals(norm) || "object_code".equals(norm) || "sourcecode".equals(norm)
                    || "source_code".equals(norm) || "code".equals(norm) || "sql".equals(norm)
                    || "ddl".equals(norm) || "definition".equals(norm)) {
                return true;
            }
        }
        String nameCell = getCell(firstRow, layout.objectNameCol);
        if (nameCell != null) {
            String norm = normalizeHeaderName(nameCell);
            return "objectname".equals(norm) || "object_name".equals(norm);
        }
        return false;
    }

    private static String normalizeHeaderName(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace(" ", "_");
    }

    private static Integer firstPresent(Map<String, Integer> map, String... keys) {
        for (String k : keys) {
            Integer v = map.get(k);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static int firstPresentOrMinusOne(Map<String, Integer> map, String... keys) {
        Integer v = firstPresent(map, keys);
        return v != null ? v : -1;
    }

    private static String getCell(List<String> row, int colIdx) {
        if (colIdx < 0 || colIdx >= row.size()) {
            return null;
        }
        String val = row.get(colIdx);
        if (val == null) {
            return null;
        }
        String t = val.trim();
        return t.isEmpty() || "NULL".equalsIgnoreCase(t) || "\\N".equals(t) ? null : t;
    }

    private static String getCellRaw(List<String> row, int colIdx) {
        if (colIdx < 0 || colIdx >= row.size()) {
            return null;
        }
        return row.get(colIdx);
    }

    private static Boolean parseBoolOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        if ("TRUE".equals(u) || "YES".equals(u) || "Y".equals(u) || "1".equals(u)) {
            return Boolean.TRUE;
        }
        if ("FALSE".equals(u) || "NO".equals(u) || "N".equals(u) || "0".equals(u)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static String readConfigString(Map<String, byte[]> files, String... keys) {
        if (files == null) {
            return null;
        }
        for (String k : keys) {
            byte[] val = files.get(k);
            if (val != null && val.length > 0) {
                return new String(val, StandardCharsets.UTF_8).trim();
            }
        }
        return null;
    }

    private static String nonBlankOr(String candidate, String fallback) {
        return (candidate != null && !candidate.isBlank()) ? candidate.trim() : fallback;
    }

    public record CsvFormatLayout(
            int dbCol,
            int schemaCol,
            int objectTypeCol,
            int objectNameCol,
            int objectCodeCol,
            int commentsCol) {
    }
}
