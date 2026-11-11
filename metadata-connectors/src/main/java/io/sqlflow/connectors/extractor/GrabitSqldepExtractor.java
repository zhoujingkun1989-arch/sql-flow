package io.sqlflow.connectors.extractor;

import com.fasterxml.jackson.databind.JsonNode;
import io.sqlflow.connectors.jdbc.CatalogBuilder;
import io.sqlflow.connectors.util.ConnectorsJson;
import io.sqlflow.contracts.lineage.DatabaseObject;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.QueryObject;
import io.sqlflow.contracts.lineage.SchemaObject;
import io.sqlflow.contracts.lineage.ServerObject;
import io.sqlflow.contracts.lineage.SqlInfo;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.contracts.spi.FileExtractorSpi.ExtractionRequest;
import io.sqlflow.contracts.spi.FileExtractorSpi.ExtractionResult;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Grabit ({@code grabit}) &amp; SQLDep ({@code sqldep}) JSON metadata bundle extractor ({@code 02 §6.1.1, §6.4}).
 *
 * <p>Supports modern {@code servers[]}-rooted Grabit/Exporter JSON, legacy pre-2.5.4.5 flat {@code databases[]}
 * JSON, and SQLDep {@code queries[]}/{@code tables[]} export archives.
 */
public final class GrabitSqldepExtractor {

    public ExtractionResult extract(ExtractionRequest request, Map<String, byte[]> unpackedFiles) {
        EDbVendor resolvedVendor = request.getTargetVendor();
        String defaultServer = nonBlankOr(request.getDefaultServer(), "default_server");
        String defaultDb = nonBlankOr(request.getDefaultDatabase(), "default_db");
        String defaultSchema = nonBlankOr(request.getDefaultSchema(), "public");

        List<String> warnings = new ArrayList<>();
        SqlflowCatalog mergedCatalog = null;
        List<SqlInfo> sqlUnits = new ArrayList<>();
        Set<String> seenHashes = new LinkedHashSet<>();

        for (Map.Entry<String, byte[]> entry : unpackedFiles.entrySet()) {
            String path = entry.getKey();
            if (path.startsWith("__")) {
                continue;
            }
            String lower = path.toLowerCase(Locale.ROOT);
            String text = ArchiveUnpacker.decodeText(entry.getValue());
            if (text.isBlank()) {
                continue;
            }
            if (lower.endsWith(".sql")) {
                addSqlUnit(sqlUnits, seenHashes, text, path, resolvedVendor);
                continue;
            }
            if (!lower.endsWith(".json") && !text.trim().startsWith("{")) {
                continue;
            }

            try {
                JsonNode root = ConnectorsJson.mapper().readTree(text);
                if (resolvedVendor == null) {
                    String dialect = firstText(root, "dialect", "dbVendor", "dbvendor");
                    if (dialect != null) {
                        resolvedVendor = EDbVendor.fromStringOrDefault(dialect, null);
                    }
                }
                EDbVendor effectiveVendor = resolvedVendor != null ? resolvedVendor : EDbVendor.dbvpostgresql;

                if (root.has("servers") || root.has("databases")) {
                    SqlflowCatalog parsed = ConnectorsJson.mapper().treeToValue(root, SqlflowCatalog.class);
                    ensureServersPopulatedFromLegacyDatabases(parsed, effectiveVendor, defaultServer);
                    harvestQueriesFromCatalog(parsed, effectiveVendor, path, sqlUnits, seenHashes);
                    mergedCatalog = mergedCatalog == null ? parsed : mergeCatalogs(mergedCatalog, parsed);
                } else {
                    // SQLDep style JSON: { "dialect": "...", "tables": [...], "queries": [...] / "sqls": [...] }
                    CatalogBuilder builder = new CatalogBuilder(effectiveVendor, defaultServer);
                    parseSqldepJson(root, path, defaultDb, defaultSchema, builder);
                    SqlflowCatalog built = builder.buildCatalog();
                    for (SqlInfo u : builder.buildSqlUnits()) {
                        if (seenHashes.add(u.getHashCode())) {
                            u.setOriginIndex(sqlUnits.size());
                            sqlUnits.add(u);
                        }
                    }
                    mergedCatalog = mergedCatalog == null ? built : mergeCatalogs(mergedCatalog, built);
                }
            } catch (IOException e) {
                warnings.add("Failed to parse JSON metadata file '" + path + "': " + e.getMessage());
            }
        }

        if (resolvedVendor == null) {
            resolvedVendor = EDbVendor.dbvpostgresql;
        }
        if (mergedCatalog == null) {
            mergedCatalog = new CatalogBuilder(resolvedVendor, defaultServer).buildCatalog();
        }
        if (mergedCatalog.getDialect() == null) {
            mergedCatalog.setDialect(resolvedVendor.wireName());
        }
        // Stamp vendor onto any SQL units collected before dialect discovery
        for (SqlInfo unit : sqlUnits) {
            if (unit.getDbVendor() == null) {
                unit.setDbVendor(resolvedVendor.wireName());
            }
        }

        ExtractionResult result = new ExtractionResult();
        result.setResolvedVendor(resolvedVendor);
        result.setCatalog(mergedCatalog);
        result.setSqlUnits(sqlUnits);
        result.setExporterMetadataJson(ConnectorsJson.toJson(mergedCatalog));
        result.setWarnings(warnings);
        return result;
    }

    private static void parseSqldepJson(
            JsonNode root,
            String filePath,
            String defaultDb,
            String defaultSchema,
            CatalogBuilder builder) {
        JsonNode tables = root.path("tables");
        if (tables.isArray()) {
            for (JsonNode t : tables) {
                String db = nonBlankOr(firstText(t, "database", "catalog"), defaultDb);
                String schema = nonBlankOr(firstText(t, "schema", "owner"), defaultSchema);
                String name = firstText(t, "name", "table", "tableName");
                if (name == null) {
                    continue;
                }
                boolean isView = "view".equalsIgnoreCase(firstText(t, "type", "tableType"));
                DbObject obj = isView ? builder.getOrCreateView(db, schema, name) : builder.getOrCreateTable(db, schema, name);
                JsonNode cols = t.path("columns");
                if (cols.isArray()) {
                    for (JsonNode c : cols) {
                        String colName = firstText(c, "name", "column");
                        String dataType = firstText(c, "dataType", "type");
                        String comment = firstText(c, "comment", "description");
                        builder.addOrUpdateColumn(obj, colName, dataType, null, null, comment);
                    }
                }
            }
        }

        JsonNode queries = root.has("queries") ? root.path("queries") : root.path("sqls");
        if (queries.isArray()) {
            int qIdx = 0;
            for (JsonNode q : queries) {
                if (q.isTextual()) {
                    builder.addQuerySource(defaultDb, defaultSchema, "sqldep_" + (qIdx++), "query", q.asText(), filePath);
                } else if (q.isObject()) {
                    String sql = firstText(q, "sourceCode", "sql", "sqlText", "command", "query");
                    String db = nonBlankOr(firstText(q, "database", "defaultDatabase"), defaultDb);
                    String schema = nonBlankOr(firstText(q, "schema", "defaultSchema"), defaultSchema);
                    String name = nonBlankOr(firstText(q, "name", "id", "groupName"), "sqldep_" + (qIdx++));
                    String type = nonBlankOr(firstText(q, "type"), "query");
                    if (sql != null) {
                        builder.addQuerySource(db, schema, name, type, sql, filePath + "#" + name);
                    }
                }
            }
        }
    }

    private static void ensureServersPopulatedFromLegacyDatabases(
            SqlflowCatalog catalog,
            EDbVendor vendor,
            String defaultServer) {
        if ((catalog.getServers() == null || catalog.getServers().isEmpty())
                && catalog.getDatabases() != null && !catalog.getDatabases().isEmpty()) {
            var profile = vendor.identifierProfile();
            ServerObject srv = new ServerObject(
                    defaultServer,
                    vendor.wireName(),
                    profile.metadataSupportsCatalogs(),
                    profile.metadataSupportsSchemas());
            srv.setDatabases(catalog.getDatabases());
            srv.setQueries(catalog.getQueries());
            srv.setDbLinks(catalog.getDbLinks());
            catalog.setServers(List.of(srv));
        }
        if ((catalog.getDatabases() == null || catalog.getDatabases().isEmpty())
                && catalog.getServers() != null) {
            List<DatabaseObject> dbs = new ArrayList<>();
            for (ServerObject s : catalog.getServers()) {
                if (s.getDatabases() != null) {
                    dbs.addAll(s.getDatabases());
                } else if (s.getSchemas() != null && !s.getSchemas().isEmpty()) {
                    DatabaseObject mirror = new DatabaseObject(nonBlankOr(s.getName(), defaultServer));
                    mirror.setSchemas(s.getSchemas());
                    dbs.add(mirror);
                }
            }
            if (!dbs.isEmpty()) {
                catalog.setDatabases(dbs);
            }
        }
    }

    private static void harvestQueriesFromCatalog(
            SqlflowCatalog catalog,
            EDbVendor vendor,
            String baseFile,
            List<SqlInfo> outUnits,
            Set<String> seenHashes) {
        if (catalog.getQueries() != null) {
            for (QueryObject q : catalog.getQueries()) {
                if (q != null && q.getSourceCode() != null && !q.getSourceCode().isBlank()) {
                    String name = q.getName() != null ? q.getName() + ".sql" : baseFile;
                    addSqlUnit(outUnits, seenHashes, q.getSourceCode(), name, vendor);
                }
            }
        }
        if (catalog.getServers() != null) {
            for (ServerObject srv : catalog.getServers()) {
                if (srv.getQueries() != null) {
                    for (QueryObject q : srv.getQueries()) {
                        if (q != null && q.getSourceCode() != null && !q.getSourceCode().isBlank()) {
                            String name = q.getName() != null ? q.getName() + ".sql" : baseFile;
                            addSqlUnit(outUnits, seenHashes, q.getSourceCode(), name, vendor);
                        }
                    }
                }
            }
        }
    }

    private static SqlflowCatalog mergeCatalogs(SqlflowCatalog base, SqlflowCatalog addition) {
        if (addition.getDatabases() != null) {
            List<DatabaseObject> dbs = new ArrayList<>(base.getDatabases() != null ? base.getDatabases() : List.of());
            dbs.addAll(addition.getDatabases());
            base.setDatabases(dbs);
        }
        if (addition.getServers() != null) {
            List<ServerObject> srvs = new ArrayList<>(base.getServers() != null ? base.getServers() : List.of());
            srvs.addAll(addition.getServers());
            base.setServers(srvs);
        }
        if (addition.getQueries() != null) {
            List<QueryObject> qs = new ArrayList<>(base.getQueries() != null ? base.getQueries() : List.of());
            qs.addAll(addition.getQueries());
            base.setQueries(qs);
        }
        return base;
    }

    private static void addSqlUnit(
            List<SqlInfo> units,
            Set<String> seenHashes,
            String sql,
            String filePath,
            EDbVendor vendor) {
        String trimmed = sql.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        String hash = ConnectorsJson.md5Hex(trimmed);
        if (!seenHashes.add(hash)) {
            return;
        }
        int slash = filePath.lastIndexOf('/');
        String fileName = slash >= 0 ? filePath.substring(slash + 1) : filePath;
        SqlInfo info = new SqlInfo(trimmed, fileName, filePath, vendor != null ? vendor.wireName() : null);
        info.setOriginIndex(units.size());
        info.setHashCode(hash);
        units.add(info);
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v != null && !v.isNull() && v.isValueNode()) {
                String t = v.asText();
                if (t != null && !t.isBlank()) {
                    return t.trim();
                }
            }
        }
        return null;
    }

    private static String nonBlankOr(String candidate, String fallback) {
        return (candidate != null && !candidate.isBlank()) ? candidate.trim() : fallback;
    }
}
