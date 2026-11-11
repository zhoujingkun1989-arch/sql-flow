package io.sqlflow.connectors.extractor;

import com.fasterxml.jackson.databind.JsonNode;
import io.sqlflow.connectors.jdbc.CatalogBuilder;
import io.sqlflow.connectors.util.ConnectorsJson;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.contracts.spi.FileExtractorSpi.ExtractionRequest;
import io.sqlflow.contracts.spi.FileExtractorSpi.ExtractionResult;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code dbt} project extractor ({@code OBL-077}, {@code 02 §6.4.1}, {@code 03 §2.4.2}):
 * <ul>
 *   <li>Parses {@code manifest.json} (schema versions v4–v12+) including {@code metadata.adapter_type},
 *       {@code sources}, {@code nodes} ({@code model}, {@code seed}, {@code snapshot}), and {@code exposures}</li>
 *   <li>Merges physical column types &amp; comments from optional {@code catalog.json} ({@code nodes} &amp; {@code sources})</li>
 *   <li>Resolves compiled SQL from uploaded/zipped {@code target/compiled/} {@code .sql} files, {@code compiled_code} (dbt v7+),
 *       {@code compiled_sql} (dbt v4–v6), or Jinja-expanded {@code raw_code}/{@code raw_sql}</li>
 *   <li>Wraps model SELECT queries into executable {@code CREATE VIEW} / {@code CREATE MATERIALIZED VIEW} / {@code CREATE TABLE}
 *       lineage units and emits canonical Exporter Metadata JSON</li>
 * </ul>
 */
public final class DbtProjectExtractor {

    private static final Pattern JINJA_CONFIG_BLOCK = Pattern.compile("\\{\\{\\s*config\\s*\\(.*?\\)\\s*\\}\\}", Pattern.DOTALL);
    private static final Pattern JINJA_REF_CALL = Pattern.compile("\\{\\{\\s*ref\\s*\\(\\s*['\"]([^'\"]+)['\"](?:\\s*,\\s*['\"]([^'\"]+)['\"])?\\s*\\)\\s*\\}\\}");
    private static final Pattern JINJA_SOURCE_CALL = Pattern.compile("\\{\\{\\s*source\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*,\\s*['\"]([^'\"]+)['\"]\\s*\\)\\s*\\}\\}");

    public ExtractionResult extract(ExtractionRequest request, Map<String, byte[]> unpackedFiles) {
        JsonNode manifestRoot = findJsonFile(unpackedFiles, "manifest.json");
        JsonNode catalogRoot = findJsonFile(unpackedFiles, "catalog.json");
        Map<String, String> compiledSqlFiles = collectCompiledSqlFiles(unpackedFiles);

        EDbVendor resolvedVendor = resolveDbtVendor(request.getTargetVendor(), manifestRoot);
        String defaultServer = nonBlankOr(request.getDefaultServer(), "dbt_server");
        String defaultDb = nonBlankOr(request.getDefaultDatabase(), "analytics");
        String defaultSchema = nonBlankOr(request.getDefaultSchema(), "public");

        CatalogBuilder builder = new CatalogBuilder(resolvedVendor, defaultServer);

        if (manifestRoot == null) {
            if (compiledSqlFiles.isEmpty()) {
                builder.addWarning("dbt extraction found neither manifest.json nor compiled .sql files");
            } else {
                builder.addWarning("manifest.json not provided; extracted compiled SQL files directly");
                for (Map.Entry<String, String> entry : compiledSqlFiles.entrySet()) {
                    wrapOrRegisterStandaloneCompiledSql(
                            entry.getKey(), entry.getValue(), defaultDb, defaultSchema, builder);
                }
            }
            return finalizeResult(resolvedVendor, builder);
        }

        // Index ref() and source() targets so raw_code fallback can expand Jinja macros accurately
        Map<String, String> refQualifiedMap = new HashMap<>();
        Map<String, String> sourceQualifiedMap = new HashMap<>();

        // 1. Sources (manifest.sources + catalog.sources)
        JsonNode sourcesNode = manifestRoot.path("sources");
        JsonNode catalogSources = catalogRoot != null ? catalogRoot.path("sources") : null;
        if (sourcesNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = sourcesNode.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String uniqueId = entry.getKey();
                JsonNode src = entry.getValue();
                String db = nonBlankOr(textOrNull(src, "database"), defaultDb);
                String schema = nonBlankOr(textOrNull(src, "schema"), defaultSchema);
                String sourceGroup = textOrNull(src, "source_name");
                String srcName = textOrNull(src, "name");
                String tableIdent = nonBlankOr(textOrNull(src, "identifier"), srcName);
                if (tableIdent == null) {
                    continue;
                }
                DbObject tableObj = builder.getOrCreateTable(db, schema, tableIdent);
                mergeColumnsFromManifestAndCatalog(
                        builder,
                        tableObj,
                        src.path("columns"),
                        catalogSources != null ? catalogSources.path(uniqueId).path("columns") : null);

                String fqn = qualify(resolvedVendor, db, schema, tableIdent);
                if (sourceGroup != null && srcName != null) {
                    sourceQualifiedMap.put(sourceGroup + "." + srcName, fqn);
                }
            }
        }

        // Pre-pass over nodes to populate refQualifiedMap and discover primary model database/schema
        String primaryModelDb = null;
        String primaryModelSchema = null;
        JsonNode nodesNode = manifestRoot.path("nodes");
        if (nodesNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> preIt = nodesNode.fields();
            while (preIt.hasNext()) {
                JsonNode node = preIt.next().getValue();
                String resourceType = textOrNull(node, "resource_type");
                if (!isTabularDbtResource(resourceType)) {
                    continue;
                }
                String modelName = textOrNull(node, "name");
                String alias = nonBlankOr(textOrNull(node, "alias"), modelName);
                String db = nonBlankOr(textOrNull(node, "database"), defaultDb);
                String schema = nonBlankOr(textOrNull(node, "schema"), defaultSchema);
                if (primaryModelDb == null && textOrNull(node, "database") != null) {
                    primaryModelDb = db;
                }
                if (primaryModelSchema == null && textOrNull(node, "schema") != null) {
                    primaryModelSchema = schema;
                }
                if (modelName != null && alias != null) {
                    refQualifiedMap.put(modelName, qualify(resolvedVendor, db, schema, alias));
                }
            }
        }

        // 2. Models, Seeds, Snapshots (manifest.nodes + catalog.nodes + target/compiled/*.sql)
        JsonNode catalogNodes = catalogRoot != null ? catalogRoot.path("nodes") : null;
        if (nodesNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = nodesNode.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String uniqueId = entry.getKey();
                JsonNode node = entry.getValue();
                String resourceType = textOrNull(node, "resource_type");
                if (!isTabularDbtResource(resourceType)) {
                    continue;
                }
                String name = textOrNull(node, "name");
                String alias = nonBlankOr(textOrNull(node, "alias"), name);
                if (alias == null) {
                    continue;
                }
                String db = nonBlankOr(textOrNull(node, "database"), defaultDb);
                String schema = nonBlankOr(textOrNull(node, "schema"), defaultSchema);
                String materialized = textOrNull(node.path("config"), "materialized");
                if (materialized == null) {
                    materialized = "seed".equalsIgnoreCase(resourceType) || "snapshot".equalsIgnoreCase(resourceType)
                            ? "table"
                            : "view";
                }

                boolean isView = "view".equalsIgnoreCase(materialized)
                        || "ephemeral".equalsIgnoreCase(materialized)
                        || "materialized_view".equalsIgnoreCase(materialized);
                boolean isMatView = "materialized_view".equalsIgnoreCase(materialized);

                DbObject dbObj = isView
                        ? builder.getOrCreateView(db, schema, alias)
                        : builder.getOrCreateTable(db, schema, alias);
                if (isMatView) {
                    dbObj.setSubType("materialized_view");
                } else if ("ephemeral".equalsIgnoreCase(materialized)) {
                    dbObj.setSubType("ephemeral");
                }

                mergeColumnsFromManifestAndCatalog(
                        builder,
                        dbObj,
                        node.path("columns"),
                        catalogNodes != null ? catalogNodes.path(uniqueId).path("columns") : null);

                if ("model".equalsIgnoreCase(resourceType) || "snapshot".equalsIgnoreCase(resourceType)) {
                    String sql = resolveNodeSql(node, compiledSqlFiles, refQualifiedMap, sourceQualifiedMap);
                    if (sql != null && !sql.isBlank()) {
                        String ddl = wrapDbtModelSql(resolvedVendor, db, schema, alias, materialized, sql);
                        String filePath = nonBlankOr(
                                textOrNull(node, "original_file_path"),
                                nonBlankOr(textOrNull(node, "path"), "models/" + alias + ".sql"));
                        builder.addQuerySource(db, schema, alias, isView ? "view" : "model", ddl, filePath);
                    }
                }
            }
        }

        // 3. Exposures (downstream BI dashboards / notebooks in manifest.exposures)
        JsonNode exposuresNode = manifestRoot.path("exposures");
        if (exposuresNode.isObject()) {
            String exposureFallbackDb = request.getDefaultDatabase() != null && !request.getDefaultDatabase().isBlank()
                    ? defaultDb
                    : nonBlankOr(primaryModelDb, defaultDb);
            String exposureFallbackSchema = request.getDefaultSchema() != null && !request.getDefaultSchema().isBlank()
                    ? defaultSchema
                    : nonBlankOr(primaryModelSchema, defaultSchema);
            Iterator<Map.Entry<String, JsonNode>> expIt = exposuresNode.fields();
            while (expIt.hasNext()) {
                JsonNode exp = expIt.next().getValue();
                String expName = textOrNull(exp, "name");
                String expType = nonBlankOr(textOrNull(exp, "type"), "exposure");
                if (expName != null) {
                    String expDb = nonBlankOr(textOrNull(exp, "database"), exposureFallbackDb);
                    String expSchema = nonBlankOr(textOrNull(exp, "schema"), exposureFallbackSchema);
                    DbObject expObj = builder.getOrCreateOtherObject(expDb, expSchema, expName, "exposure");
                    expObj.setSubType(expType);
                    expObj.setUri(textOrNull(exp, "url"));
                }
            }
        }

        return finalizeResult(resolvedVendor, builder);
    }

    private static ExtractionResult finalizeResult(EDbVendor vendor, CatalogBuilder builder) {
        SqlflowCatalog catalog = builder.buildCatalog();
        ExtractionResult result = new ExtractionResult();
        result.setResolvedVendor(vendor);
        result.setCatalog(catalog);
        result.setSqlUnits(builder.buildSqlUnits());
        result.setExporterMetadataJson(ConnectorsJson.toJson(catalog));
        result.setWarnings(builder.buildWarnings());
        return result;
    }

    private static boolean isTabularDbtResource(String resourceType) {
        if (resourceType == null) {
            return false;
        }
        String lower = resourceType.toLowerCase(Locale.ROOT);
        return "model".equals(lower) || "seed".equals(lower) || "snapshot".equals(lower);
    }

    private static void mergeColumnsFromManifestAndCatalog(
            CatalogBuilder builder,
            DbObject targetObj,
            JsonNode manifestCols,
            JsonNode catalogCols) {
        if (manifestCols != null && manifestCols.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = manifestCols.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode c = e.getValue();
                String colName = nonBlankOr(textOrNull(c, "name"), e.getKey());
                String dataType = textOrNull(c, "data_type");
                String desc = textOrNull(c, "description");
                builder.addOrUpdateColumn(targetObj, colName, dataType, null, null, desc);
            }
        }
        if (catalogCols != null && catalogCols.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = catalogCols.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode c = e.getValue();
                String colName = nonBlankOr(textOrNull(c, "name"), e.getKey());
                String dataType = textOrNull(c, "type");
                String comment = textOrNull(c, "comment");
                builder.addOrUpdateColumn(targetObj, colName, dataType, null, null, comment);
            }
        }
    }

    private static String resolveNodeSql(
            JsonNode node,
            Map<String, String> compiledFiles,
            Map<String, String> refMap,
            Map<String, String> sourceMap) {
        String compiledPath = textOrNull(node, "compiled_path");
        if (compiledPath != null) {
            String norm = ArchiveUnpacker.normalizeEntryPath(compiledPath);
            if (compiledFiles.containsKey(norm)) {
                return compiledFiles.get(norm);
            }
            for (Map.Entry<String, String> e : compiledFiles.entrySet()) {
                if (e.getKey().endsWith(norm) || norm.endsWith(e.getKey())) {
                    return e.getValue();
                }
            }
        }
        // Match by node path under target/compiled/**/models/...
        String relPath = textOrNull(node, "path");
        if (relPath != null) {
            String normRel = ArchiveUnpacker.normalizeEntryPath(relPath);
            for (Map.Entry<String, String> e : compiledFiles.entrySet()) {
                if (e.getKey().contains("compiled/") && e.getKey().endsWith("/" + normRel)) {
                    return e.getValue();
                }
            }
        }

        // dbt v7–v12 compiled_code
        String compiledCode = textOrNull(node, "compiled_code");
        if (compiledCode != null && !compiledCode.isBlank()) {
            return compiledCode;
        }
        // dbt v4–v6 compiled_sql
        String compiledSql = textOrNull(node, "compiled_sql");
        if (compiledSql != null && !compiledSql.isBlank()) {
            return compiledSql;
        }

        // Uncompiled raw_code / raw_sql fallback with Jinja ref()/source() expansion
        String raw = nonBlankOr(textOrNull(node, "raw_code"), textOrNull(node, "raw_sql"));
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return expandBasicJinjaSql(raw, refMap, sourceMap);
    }

    static String expandBasicJinjaSql(String rawSql, Map<String, String> refMap, Map<String, String> sourceMap) {
        String s = JINJA_CONFIG_BLOCK.matcher(rawSql).replaceAll("");
        Matcher refMatcher = JINJA_REF_CALL.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (refMatcher.find()) {
            String arg1 = refMatcher.group(1);
            String arg2 = refMatcher.group(2);
            String model = arg2 != null ? arg2 : arg1;
            String resolved = refMap.getOrDefault(model, model);
            refMatcher.appendReplacement(sb, Matcher.quoteReplacement(resolved));
        }
        refMatcher.appendTail(sb);

        Matcher srcMatcher = JINJA_SOURCE_CALL.matcher(sb.toString());
        StringBuilder sb2 = new StringBuilder();
        while (srcMatcher.find()) {
            String srcGroup = srcMatcher.group(1);
            String srcTable = srcMatcher.group(2);
            String resolved = sourceMap.getOrDefault(srcGroup + "." + srcTable, srcGroup + "." + srcTable);
            srcMatcher.appendReplacement(sb2, Matcher.quoteReplacement(resolved));
        }
        srcMatcher.appendTail(sb2);
        return sb2.toString().trim();
    }

    private static String wrapDbtModelSql(
            EDbVendor vendor,
            String db,
            String schema,
            String alias,
            String materialized,
            String compiledSql) {
        String trimmed = stripTrailingSemicolon(compiledSql.trim());
        String upper = trimmed.stripLeading().toUpperCase(Locale.ROOT);
        if (upper.startsWith("CREATE ") || upper.startsWith("REPLACE ")
                || upper.startsWith("INSERT ") || upper.startsWith("MERGE ")) {
            return trimmed + ";";
        }
        String fqn = qualify(vendor, db, schema, alias);
        if ("materialized_view".equalsIgnoreCase(materialized)) {
            return "CREATE MATERIALIZED VIEW " + fqn + " AS\n" + trimmed + ";";
        }
        if ("table".equalsIgnoreCase(materialized) || "incremental".equalsIgnoreCase(materialized)) {
            return "CREATE TABLE " + fqn + " AS\n" + trimmed + ";";
        }
        return "CREATE VIEW " + fqn + " AS\n" + trimmed + ";";
    }

    private static void wrapOrRegisterStandaloneCompiledSql(
            String path,
            String sql,
            String defaultDb,
            String defaultSchema,
            CatalogBuilder builder) {
        if (sql == null || sql.isBlank()) {
            return;
        }
        int slash = path.lastIndexOf('/');
        String fileName = slash >= 0 ? path.substring(slash + 1) : path;
        String modelName = fileName.endsWith(".sql") ? fileName.substring(0, fileName.length() - 4) : fileName;
        boolean isStaging = modelName.toLowerCase(Locale.ROOT).startsWith("stg_");
        String materialized = isStaging ? "view" : "table";
        if (isStaging) {
            builder.getOrCreateView(defaultDb, defaultSchema, modelName);
        } else {
            builder.getOrCreateTable(defaultDb, defaultSchema, modelName);
        }
        String wrapped = wrapDbtModelSql(builder.vendor(), defaultDb, defaultSchema, modelName, materialized, sql);
        builder.addQuerySource(defaultDb, defaultSchema, modelName, materialized, wrapped, path);
    }

    public static EDbVendor resolveDbtVendor(EDbVendor explicitTarget, JsonNode manifestRoot) {
        if (explicitTarget != null) {
            return explicitTarget;
        }
        if (manifestRoot != null) {
            String adapter = textOrNull(manifestRoot.path("metadata"), "adapter_type");
            if (adapter != null && !adapter.isBlank()) {
                String norm = adapter.trim().toLowerCase(Locale.ROOT);
                return switch (norm) {
                    case "postgres", "postgresql" -> EDbVendor.dbvpostgresql;
                    case "redshift" -> EDbVendor.dbvredshift;
                    case "snowflake" -> EDbVendor.dbvsnowflake;
                    case "bigquery" -> EDbVendor.dbvbigquery;
                    case "databricks" -> EDbVendor.dbvdatabricks;
                    case "spark" -> EDbVendor.dbvsparksql;
                    case "trino", "starburst" -> EDbVendor.dbvtrino;
                    case "presto" -> EDbVendor.dbvpresto;
                    case "duckdb" -> EDbVendor.dbvduckdb;
                    case "clickhouse" -> EDbVendor.dbvclickhouse;
                    case "oracle" -> EDbVendor.dbvoracle;
                    case "sqlserver", "tsql", "fabric" -> EDbVendor.dbvmssql;
                    case "synapse", "azuresql" -> EDbVendor.dbvazuresql;
                    case "mysql", "mariadb", "tidb" -> EDbVendor.dbvmysql;
                    case "teradata" -> EDbVendor.dbvteradata;
                    case "athena" -> EDbVendor.dbvathena;
                    case "hive" -> EDbVendor.dbvhive;
                    case "impala" -> EDbVendor.dbvimpala;
                    case "greenplum" -> EDbVendor.dbvgreenplum;
                    case "sqlite" -> EDbVendor.dbvsqlite;
                    case "db2" -> EDbVendor.dbvdb2;
                    case "vertica" -> EDbVendor.dbvvertica;
                    default -> EDbVendor.fromStringOrDefault(norm, EDbVendor.dbvsnowflake);
                };
            }
        }
        return EDbVendor.dbvsnowflake;
    }

    private static JsonNode findJsonFile(Map<String, byte[]> files, String exactFileName) {
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String path = entry.getKey().toLowerCase(Locale.ROOT);
            if (path.equals(exactFileName) || path.endsWith("/" + exactFileName)) {
                try {
                    return ConnectorsJson.mapper().readTree(ArchiveUnpacker.decodeText(entry.getValue()));
                } catch (IOException ignored) {
                }
            }
        }
        return null;
    }

    private static Map<String, String> collectCompiledSqlFiles(Map<String, byte[]> files) {
        Map<String, String> sqls = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String path = entry.getKey();
            if (path.toLowerCase(Locale.ROOT).endsWith(".sql")) {
                sqls.put(path, ArchiveUnpacker.decodeText(entry.getValue()));
            }
        }
        return sqls;
    }

    private static String qualify(EDbVendor vendor, String db, String schema, String name) {
        var profile = vendor.identifierProfile();
        StringBuilder sb = new StringBuilder();
        if (profile.metadataSupportsCatalogs() && db != null && !db.isBlank()) {
            sb.append(db.trim()).append('.');
        }
        if (profile.metadataSupportsSchemas() && schema != null && !schema.isBlank()) {
            sb.append(schema.trim()).append('.');
        }
        sb.append(name.trim());
        return sb.toString();
    }

    private static String stripTrailingSemicolon(String sql) {
        String s = sql;
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode child = node.get(field);
        if (child == null || child.isNull() || !child.isValueNode()) {
            return null;
        }
        String text = child.asText();
        return (text == null || text.isBlank()) ? null : text.trim();
    }

    private static String nonBlankOr(String candidate, String fallback) {
        return (candidate != null && !candidate.isBlank()) ? candidate.trim() : fallback;
    }
}
