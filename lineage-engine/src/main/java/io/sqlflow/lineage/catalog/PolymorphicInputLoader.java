package io.sqlflow.lineage.catalog;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.contracts.lineage.ErrorItem;
import io.sqlflow.contracts.lineage.Option;
import io.sqlflow.contracts.lineage.QueryObject;
import io.sqlflow.contracts.lineage.ServerObject;
import io.sqlflow.contracts.lineage.SqlInfo;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.contracts.spi.LineageEngineSpi.LineageAnalysisRequest;
import io.sqlflow.contracts.spi.LineageEngineSpi.LineageInputType;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.lineage.catalog.TSQLEnv.CatalogColumn;
import io.sqlflow.lineage.catalog.TSQLEnv.CatalogForeignKey;
import io.sqlflow.lineage.catalog.TSQLEnv.CatalogTable;
import io.sqlflow.lineage.lexer.SqlContentMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Polymorphic input loader ({@code OBL-035}, {@code 03 §1.1, §2.2}).
 *
 * <p>Accepts raw SQL text, multi-file {@link SqlInfo} units, SQLFlow/Grabit/Exporter {@link SqlflowCatalog} JSON,
 * SQLDep JSON, and {@code sqlenvSnapshot} bytes, populating {@link TSQLEnv} and returning normalized executable
 * {@link ExecutableSqlUnit}s.
 */
public final class PolymorphicInputLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY);

    private PolymorphicInputLoader() {
    }

    public record ExecutableSqlUnit(
            int originIndex,
            String sql,
            String sqlHash,
            String fileName,
            String filePath,
            String database,
            String schema,
            String viewOrRoutineName,
            String objectType,
            EDbVendor unitVendor
    ) {
    }

    public record LoadedInput(
            EDbVendor effectiveVendor,
            TSQLEnv sqlEnv,
            List<ExecutableSqlUnit> units,
            List<ErrorItem> errors
    ) {
    }

    public static LoadedInput load(LineageAnalysisRequest request) {
        Option option = request != null && request.getOption() != null ? request.getOption() : new Option();
        EDbVendor baseVendor = option.getVendor() != null ? option.getVendor() : EDbVendor.dbvoracle;

        List<ErrorItem> errors = new ArrayList<>();
        List<ExecutableSqlUnit> units = new ArrayList<>();

        // Detect dialect override from JSON payload if option.vendor was unset
        EDbVendor detectedVendor = detectVendorFromJsonIfNeeded(request, option.getVendor());
        EDbVendor effectiveVendor = detectedVendor != null ? detectedVendor : baseVendor;

        TSQLEnv env = new TSQLEnv(effectiveVendor, option.getIdentifierProfile(), option);

        if (request == null) {
            return new LoadedInput(effectiveVendor, env, units, errors);
        }

        // 1. Restore incremental sqlenv.bin snapshot if provided
        if (request.getSqlenvSnapshot() != null && request.getSqlenvSnapshot().length > 0) {
            SqlenvSnapshotCodec.loadSnapshot(env, request.getSqlenvSnapshot());
        }

        // 2. Import structured initialCatalog if provided
        if (request.getInitialCatalog() != null) {
            env.populateFromSqlflowCatalog(request.getInitialCatalog());
            extractCatalogQueries(request.getInitialCatalog(), effectiveVendor, units);
        }

        // 3. Import metadataJson if provided
        if (request.getMetadataJson() != null && !request.getMetadataJson().isBlank()) {
            parseJsonPayload(request.getMetadataJson().trim(), effectiveVendor, option, env, units, errors, true);
        }

        // 4. Process sqlUnits if provided
        SqlContentMapper mapper = SqlContentMapper.defaultMapper();
        if (request.getSqlUnits() != null && !request.getSqlUnits().isEmpty()) {
            boolean multiUnit = request.getSqlUnits().size() > 1;
            int idx = 0;
            for (SqlInfo info : request.getSqlUnits()) {
                if (info == null || info.getSql() == null || info.getSql().isBlank()) {
                    idx++;
                    continue;
                }
                String rawSql = info.getSql();
                String trimmed = rawSql.trim();
                if (looksLikeJsonPayload(trimmed)) {
                    parseJsonPayload(trimmed, effectiveVendor, option, env, units, errors, false);
                } else {
                    String mapped = mapper.apply(rawSql);
                    EDbVendor unitVendor = info.getDbVendor() != null && !info.getDbVendor().isBlank()
                            ? EDbVendor.fromStringOrDefault(info.getDbVendor(), effectiveVendor)
                            : effectiveVendor;
                    String hash = info.getHashCode();
                    if ((hash == null || hash.isBlank()) && multiUnit) {
                        hash = computeMd5Hex(rawSql);
                    }
                    String[] pathDbSch = extractDatabaseAndSchemaFromPath(info.getFilePath(), option);
                    units.add(new ExecutableSqlUnit(
                            info.getOriginIndex() != null ? info.getOriginIndex() : idx,
                            mapped,
                            hash,
                            info.getFileName(),
                            info.getFilePath(),
                            pathDbSch[0],
                            pathDbSch[1],
                            null,
                            null,
                            unitVendor));
                }
                idx++;
            }
        }

        // 5. Process sqlText (either raw SQL or polymorphic JSON payload)
        if (request.getSqlText() != null && !request.getSqlText().isBlank()) {
            String rawText = request.getSqlText();
            String trimmed = rawText.trim();
            LineageInputType inputType = request.getInputType();
            boolean forceJson = inputType == LineageInputType.SQLFLOW_METADATA_JSON
                    || inputType == LineageInputType.GRABIT_JSON
                    || inputType == LineageInputType.SQLDEP_JSON
                    || inputType == LineageInputType.EXPORTER_METADATA_JSON;

            if (forceJson || looksLikeJsonPayload(trimmed)) {
                parseJsonPayload(trimmed, effectiveVendor, option, env, units, errors, false);
            } else {
                String mapped = mapper.apply(rawText);
                units.add(new ExecutableSqlUnit(
                        units.size(),
                        mapped,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        effectiveVendor));
            }
        }

        return new LoadedInput(effectiveVendor, env, units, errors);
    }

    private static EDbVendor detectVendorFromJsonIfNeeded(LineageAnalysisRequest request, EDbVendor explicitVendor) {
        if (explicitVendor != null || request == null) {
            return explicitVendor;
        }
        if (request.getInitialCatalog() != null && request.getInitialCatalog().getDialect() != null) {
            return EDbVendor.fromStringOrDefault(request.getInitialCatalog().getDialect(), EDbVendor.dbvoracle);
        }
        String candidateJson = request.getMetadataJson();
        if ((candidateJson == null || candidateJson.isBlank())
                && request.getSqlText() != null
                && looksLikeJsonPayload(request.getSqlText().trim())) {
            candidateJson = request.getSqlText().trim();
        }
        if (candidateJson != null && !candidateJson.isBlank()) {
            try {
                JsonNode root = MAPPER.readTree(candidateJson);
                if (root.isObject()) {
                    if (root.hasNonNull("dialect")) {
                        return EDbVendor.fromStringOrDefault(root.get("dialect").asText(), EDbVendor.dbvoracle);
                    }
                    if (root.hasNonNull("dbvendor")) {
                        return EDbVendor.fromStringOrDefault(root.get("dbvendor").asText(), EDbVendor.dbvoracle);
                    }
                    if (root.has("servers") && root.get("servers").isArray() && !root.get("servers").isEmpty()) {
                        JsonNode srv0 = root.get("servers").get(0);
                        if (srv0.hasNonNull("dbVendor")) {
                            return EDbVendor.fromStringOrDefault(srv0.get("dbVendor").asText(), EDbVendor.dbvoracle);
                        }
                    }
                } else if (root.isArray() && !root.isEmpty()) {
                    JsonNode first = root.get(0);
                    if (first.hasNonNull("dbVendor")) {
                        return EDbVendor.fromStringOrDefault(first.get("dbVendor").asText(), EDbVendor.dbvoracle);
                    }
                }
            } catch (Exception ignored) {
                // Handled during parseJsonPayload
            }
        }
        return null;
    }

    private static boolean looksLikeJsonPayload(String trimmed) {
        if (trimmed == null || trimmed.length() < 2) {
            return false;
        }
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            // Avoid mistaking Jinja `{{ ... }}` for a JSON document
            return !trimmed.startsWith("{{") && !trimmed.startsWith("{%") && !trimmed.startsWith("{#");
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            // Ensure it looks like a JSON array of objects (`[{"...`) rather than MSSQL `[dbo].[tbl]`
            int idx = 1;
            while (idx < trimmed.length() && Character.isWhitespace(trimmed.charAt(idx))) {
                idx++;
            }
            return idx < trimmed.length() && (trimmed.charAt(idx) == '{' || trimmed.charAt(idx) == ']');
        }
        return false;
    }

    private static void parseJsonPayload(
            String jsonText,
            EDbVendor effectiveVendor,
            Option option,
            TSQLEnv env,
            List<ExecutableSqlUnit> units,
            List<ErrorItem> errors,
            boolean metadataOnlyUnlessQueriesPresent) {
        try {
            JsonNode root = MAPPER.readTree(jsonText);
            SqlContentMapper mapper = SqlContentMapper.defaultMapper();

            if (root.isArray()) {
                // Array of SqlInfo or SQLDep query items
                boolean multi = root.size() > 1;
                int idx = 0;
                for (JsonNode item : root) {
                    String sql = firstText(item, "sql", "sourceCode", "query", "sqlText");
                    if (sql != null && !sql.isBlank()) {
                        String hash = firstText(item, "hashCode", "sqlHash", "id");
                        if (hash == null && multi) {
                            hash = computeMd5Hex(sql);
                        }
                        String vendorStr = firstText(item, "dbVendor", "dialect");
                        EDbVendor v = vendorStr != null
                                ? EDbVendor.fromStringOrDefault(vendorStr, effectiveVendor)
                                : effectiveVendor;
                        String filePath = firstText(item, "filePath", "path");
                        String[] pathDbSch = extractDatabaseAndSchemaFromPath(filePath, option);
                        units.add(new ExecutableSqlUnit(
                                units.size() + idx,
                                mapper.apply(sql),
                                hash,
                                firstText(item, "fileName", "name"),
                                filePath,
                                firstNonNull(firstText(item, "database"), pathDbSch[0]),
                                firstNonNull(firstText(item, "schema"), pathDbSch[1]),
                                null,
                                firstText(item, "type"),
                                v));
                    }
                    idx++;
                }
                return;
            }

            if (!root.isObject()) {
                return;
            }

            // 1. Standard SqlflowCatalog / Grabit / JDBC Exporter JSON (`servers`, `databases`, `queries`)
            if (root.has("servers") || root.has("databases") || root.has("dbLinks")) {
                SqlflowCatalog catalog = MAPPER.treeToValue(root, SqlflowCatalog.class);
                env.populateFromSqlflowCatalog(catalog);
                importExporterForeignKeysFromRawJson(root, env);
                extractCatalogQueries(catalog, effectiveVendor, units);
            }

            // 2. SQLDep / Grabit top-level `tables` array (flat table metadata list)
            if (root.has("tables") && root.get("tables").isArray()) {
                for (JsonNode tblNode : root.get("tables")) {
                    String db = firstText(tblNode, "database", "catalog");
                    String sch = firstText(tblNode, "schema");
                    String name = firstText(tblNode, "name", "table");
                    String type = firstText(tblNode, "type");
                    if (name != null && !name.isBlank()) {
                        CatalogTable ct = env.registerTable(
                                null,
                                db,
                                sch,
                                name,
                                false,
                                type != null ? type : "table",
                                true);
                        if (ct != null && tblNode.has("columns") && tblNode.get("columns").isArray()) {
                            for (JsonNode colNode : tblNode.get("columns")) {
                                String colName = colNode.isTextual()
                                        ? colNode.asText()
                                        : firstText(colNode, "name", "column");
                                String dataType = colNode.isObject() ? firstText(colNode, "dataType", "type") : null;
                                if (colName != null && !colName.isBlank()) {
                                    CatalogColumn cc = new CatalogColumn(colName, dataType);
                                    if (colNode.isObject()) {
                                        if (colNode.path("primaryKey").asBoolean(false)) {
                                            cc.setPrimaryKey(Boolean.TRUE);
                                        }
                                        if (colNode.path("unqiueKey").asBoolean(false)
                                                || colNode.path("uniqueKey").asBoolean(false)) {
                                            cc.setUnqiueKey(Boolean.TRUE);
                                        }
                                        if (colNode.path("foreignKey").asBoolean(false)) {
                                            cc.setForeignKey(Boolean.TRUE);
                                        }
                                    }
                                    env.addOrUpdateColumn(ct, cc, false);
                                }
                            }
                        }
                    }
                }
            }

            // 3. SQLDep `queries` array when not already extracted via SqlflowCatalog
            if (!root.has("servers") && !root.has("databases") && root.has("queries") && root.get("queries").isArray()) {
                JsonNode queriesNode = root.get("queries");
                boolean multi = queriesNode.size() > 1;
                for (JsonNode qNode : queriesNode) {
                    String code = firstText(qNode, "sourceCode", "sql", "query");
                    if (code != null && !code.isBlank()) {
                        String name = firstText(qNode, "name", "groupName");
                        String db = firstText(qNode, "database");
                        String sch = firstText(qNode, "schema");
                        String type = firstText(qNode, "type");
                        String wrapped = wrapQueryObjectSourceIfNeeded(code, db, sch, name, type);
                        units.add(new ExecutableSqlUnit(
                                units.size(),
                                mapper.apply(wrapped),
                                multi ? computeMd5Hex(wrapped) : null,
                                name,
                                null,
                                db,
                                sch,
                                name,
                                type,
                                effectiveVendor));
                    }
                }
            }
        } catch (Exception ex) {
            if (!metadataOnlyUnlessQueriesPresent) {
                errors.add(new ErrorItem("MetadataJsonParseError", "Failed to parse JSON input: " + ex.getMessage()));
            }
        }
    }

    private static void importExporterForeignKeysFromRawJson(JsonNode root, TSQLEnv env) {
        if (root.has("servers") && root.get("servers").isArray()) {
            for (JsonNode srv : root.get("servers")) {
                scanContainersForForeignKeys(srv, null, env);
            }
        }
        if (root.has("databases") && root.get("databases").isArray()) {
            for (JsonNode db : root.get("databases")) {
                String dbName = firstText(db, "name");
                scanSchemaOrDbForForeignKeys(db, dbName, null, env);
                if (db.has("schemas") && db.get("schemas").isArray()) {
                    for (JsonNode sch : db.get("schemas")) {
                        scanSchemaOrDbForForeignKeys(sch, dbName, firstText(sch, "name"), env);
                    }
                }
            }
        }
    }

    private static void scanContainersForForeignKeys(JsonNode srv, String defaultDb, TSQLEnv env) {
        if (srv.has("databases") && srv.get("databases").isArray()) {
            for (JsonNode db : srv.get("databases")) {
                String dbName = firstText(db, "name");
                scanSchemaOrDbForForeignKeys(db, dbName, null, env);
                if (db.has("schemas") && db.get("schemas").isArray()) {
                    for (JsonNode sch : db.get("schemas")) {
                        scanSchemaOrDbForForeignKeys(sch, dbName, firstText(sch, "name"), env);
                    }
                }
            }
        }
        if (srv.has("schemas") && srv.get("schemas").isArray()) {
            for (JsonNode sch : srv.get("schemas")) {
                scanSchemaOrDbForForeignKeys(sch, defaultDb, firstText(sch, "name"), env);
            }
        }
    }

    private static void scanSchemaOrDbForForeignKeys(JsonNode container, String dbName, String schName, TSQLEnv env) {
        if (container.has("tables") && container.get("tables").isArray()) {
            for (JsonNode tblNode : container.get("tables")) {
                String tblName = firstText(tblNode, "name");
                if (tblName == null || !tblNode.has("foreignKeys") || !tblNode.get("foreignKeys").isArray()) {
                    continue;
                }
                CatalogTable ct = env.registerTable(null, dbName, schName, tblName, false, "table", true);
                if (ct == null) {
                    continue;
                }
                for (JsonNode fkNode : tblNode.get("foreignKeys")) {
                    String fkName = firstText(fkNode, "name", "constraintName");
                    List<String> srcCols = readStringArray(fkNode, "columns", "sourceColumns");
                    String refDb = firstText(fkNode, "refDatabase", "referencedDatabase");
                    String refSch = firstText(fkNode, "refSchema", "referencedSchema");
                    String refTbl = firstText(fkNode, "refTable", "referencedTable");
                    List<String> refCols = readStringArray(fkNode, "refColumns", "referencedColumns");
                    if (refTbl != null && !srcCols.isEmpty()) {
                        ct.addForeignKey(new CatalogForeignKey(
                                fkName,
                                srcCols,
                                refDb != null ? refDb : dbName,
                                refSch != null ? refSch : schName,
                                refTbl,
                                refCols));
                    }
                }
            }
        }
    }

    private static void extractCatalogQueries(
            SqlflowCatalog catalog,
            EDbVendor effectiveVendor,
            List<ExecutableSqlUnit> units) {
        if (catalog == null) {
            return;
        }
        SqlContentMapper mapper = SqlContentMapper.defaultMapper();
        List<QueryObject> allQueries = new ArrayList<>();
        if (catalog.getQueries() != null) {
            allQueries.addAll(catalog.getQueries());
        }
        if (catalog.getServers() != null) {
            for (ServerObject srv : catalog.getServers()) {
                if (srv != null && srv.getQueries() != null) {
                    allQueries.addAll(srv.getQueries());
                }
            }
        }
        boolean multi = allQueries.size() > 1;
        for (QueryObject q : allQueries) {
            if (q == null || q.getSourceCode() == null || q.getSourceCode().isBlank()) {
                continue;
            }
            String wrapped = wrapQueryObjectSourceIfNeeded(
                    q.getSourceCode(),
                    q.getDatabase(),
                    q.getSchema(),
                    q.getName(),
                    q.getType());
            units.add(new ExecutableSqlUnit(
                    units.size(),
                    mapper.apply(wrapped),
                    multi ? computeMd5Hex(wrapped) : null,
                    q.getName(),
                    null,
                    q.getDatabase(),
                    q.getSchema(),
                    q.getName(),
                    q.getType(),
                    effectiveVendor));
        }
    }

    private static String wrapQueryObjectSourceIfNeeded(
            String sourceCode,
            String db,
            String sch,
            String name,
            String type) {
        String trimmed = sourceCode.trim();
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (name != null && !name.isBlank() && type != null) {
            String lowerType = type.trim().toLowerCase(Locale.ROOT);
            if ((lowerType.contains("view")) && !upper.startsWith("CREATE")) {
                StringBuilder qn = new StringBuilder();
                if (db != null && !db.isBlank()) {
                    qn.append(db).append('.');
                }
                if (sch != null && !sch.isBlank()) {
                    qn.append(sch).append('.');
                }
                qn.append(name);
                String viewKw = lowerType.contains("materialized") ? "CREATE MATERIALIZED VIEW " : "CREATE VIEW ";
                return viewKw + qn + " AS\n" + sourceCode;
            }
        }
        return sourceCode;
    }

    private static String[] extractDatabaseAndSchemaFromPath(String filePath, Option option) {
        String db = null;
        String sch = null;
        if (filePath == null || filePath.isBlank() || option == null) {
            return new String[]{null, null};
        }
        if (option.getFilePathDatabase() != null && !option.getFilePathDatabase().isBlank()) {
            db = extractRegexCapture(filePath, option.getFilePathDatabase());
        }
        if (option.getFilePathSchema() != null && !option.getFilePathSchema().isBlank()) {
            sch = extractRegexCapture(filePath, option.getFilePathSchema());
        }
        return new String[]{db, sch};
    }

    private static String extractRegexCapture(String input, String patternText) {
        try {
            Matcher m = Pattern.compile(patternText).matcher(input);
            if (m.find()) {
                return m.groupCount() >= 1 ? m.group(1) : m.group(0);
            }
        } catch (Exception ignored) {
            // Invalid user regex pattern falls back to null
        }
        return null;
    }

    public static String computeMd5Hex(String text) {
        if (text == null) {
            return "0";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v != null && !v.isNull() && v.isValueNode()) {
                String txt = v.asText();
                if (txt != null && !txt.isBlank()) {
                    return txt.trim();
                }
            }
        }
        return null;
    }

    private static List<String> readStringArray(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) {
            return List.of();
        }
        for (String f : fields) {
            JsonNode arr = node.get(f);
            if (arr != null && arr.isArray()) {
                List<String> out = new ArrayList<>(arr.size());
                for (JsonNode el : arr) {
                    if (el != null && !el.isNull()) {
                        out.add(el.isTextual() ? el.asText() : firstText(el, "name", "column"));
                    }
                }
                return out;
            }
        }
        return List.of();
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
