package io.sqlflow.server.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.contracts.lineage.Column;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.QueryObject;
import io.sqlflow.contracts.lineage.SqlInfo;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.contracts.spi.FileExtractorSpi;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Built-in non-JDBC file/archive extractor fallback supporting {@code dbt}, {@code redshiftlog},
 * {@code pyspark}, {@code csv}, {@code grabit}, and {@code sqldep}.
 */
public class FallbackFileExtractorSpi implements FileExtractorSpi {

    private static final Pattern PYSPARK_SQL_PATTERN = Pattern.compile(
            "(?s)spark\\.sql\\s*\\(\\s*(?:\"\"\"(.*?)\"\"\"|'''(.*?)'''|\"([^\"]+)\"|'([^']+)')\\s*\\)");

    private final ObjectMapper objectMapper;

    public FallbackFileExtractorSpi(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ExtractionResult extract(ExtractionRequest request) {
        ExtractionResult res = new ExtractionResult();
        EDbVendor vendor = request != null && request.getTargetVendor() != null
                ? request.getTargetVendor()
                : EDbVendor.dbvpostgresql;
        res.setResolvedVendor(vendor);

        Map<String, byte[]> files = request != null && request.getFiles() != null ? request.getFiles() : Map.of();
        ExtractorFormat fmt = request != null && request.getFormat() != null
                ? request.getFormat()
                : ExtractorFormat.CSV;

        List<DbObject> objects = new ArrayList<>();
        List<SqlInfo> sqlUnits = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        AtomicLong idSeq = new AtomicLong(1L);

        switch (fmt) {
            case DBT -> extractDbt(files, vendor, objects, sqlUnits, warnings, idSeq, res);
            case PYSPARK -> extractPySpark(files, vendor, sqlUnits);
            case REDSHIFT_LOG -> extractRedshiftLog(files, sqlUnits, res);
            case GRABIT_JSON, SQLDEP_JSON -> extractCatalogJson(files, vendor, objects, sqlUnits, warnings);
            case CSV -> extractCsvMetadata(files, request, objects, sqlUnits, idSeq);
        }

        SqlflowCatalog catalog = CatalogUtils.buildCatalog(
                request != null ? request.getDefaultServer() : "default",
                res.getResolvedVendor().name(),
                objects);
        res.setCatalog(catalog);
        res.setSqlUnits(sqlUnits);
        res.setWarnings(warnings);
        try {
            res.setExporterMetadataJson(objectMapper.writeValueAsString(catalog));
        } catch (Exception ignored) {
            res.setExporterMetadataJson("{}");
        }
        return res;
    }

    private void extractDbt(
            Map<String, byte[]> files,
            EDbVendor fallbackVendor,
            List<DbObject> objects,
            List<SqlInfo> sqlUnits,
            List<String> warnings,
            AtomicLong idSeq,
            ExtractionResult res) {
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            String lower = e.getKey().toLowerCase(Locale.ROOT);
            if (lower.endsWith("manifest.json")) {
                try {
                    JsonNode root = objectMapper.readTree(e.getValue());
                    String adapter = root.path("metadata").path("adapter_type").asText("");
                    if (!adapter.isBlank()) {
                        res.setResolvedVendor(EDbVendor.fromStringOrDefault(adapter, fallbackVendor));
                    }
                    JsonNode nodes = root.path("nodes");
                    if (nodes.isObject()) {
                        int idx = 0;
                        Iterator<Map.Entry<String, JsonNode>> fields = nodes.fields();
                        while (fields.hasNext()) {
                            JsonNode node = fields.next().getValue();
                            String compiled = node.hasNonNull("compiled_code")
                                    ? node.get("compiled_code").asText()
                                    : node.path("compiled_sql").asText(node.path("raw_code").asText(""));
                            String name = node.path("name").asText("model_" + idx);
                            String db = node.path("database").asText("DEFAULT");
                            String sc = node.path("schema").asText("DEFAULT");
                            if (!compiled.isBlank()) {
                                String wrapped = "CREATE VIEW " + sc + "." + name + " AS " + compiled;
                                SqlInfo unit = new SqlInfo(wrapped, name + ".sql", null, res.getResolvedVendor().name());
                                unit.setOriginIndex(idx++);
                                sqlUnits.add(unit);
                            }
                        }
                    }
                    JsonNode sources = root.path("sources");
                    if (sources.isObject()) {
                        Iterator<Map.Entry<String, JsonNode>> sFields = sources.fields();
                        while (sFields.hasNext()) {
                            JsonNode sNode = sFields.next().getValue();
                            DbObject tbl = new DbObject(
                                    String.valueOf(idSeq.getAndIncrement()),
                                    sNode.path("identifier").asText(sNode.path("name").asText("source")),
                                    "table");
                            tbl.setDatabase(sNode.path("database").asText("DEFAULT"));
                            tbl.setSchema(sNode.path("schema").asText("DEFAULT"));
                            tbl.setFromDDL(Boolean.TRUE);
                            objects.add(tbl);
                        }
                    }
                } catch (Exception ex) {
                    warnings.add("Failed to parse dbt manifest.json: " + ex.getMessage());
                }
            }
        }
    }

    private void extractPySpark(Map<String, byte[]> files, EDbVendor vendor, List<SqlInfo> sqlUnits) {
        int idx = 0;
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            String text = new String(e.getValue(), StandardCharsets.UTF_8);
            Matcher m = PYSPARK_SQL_PATTERN.matcher(text);
            while (m.find()) {
                String sql = m.group(1) != null ? m.group(1)
                        : (m.group(2) != null ? m.group(2) : (m.group(3) != null ? m.group(3) : m.group(4)));
                if (sql != null && !sql.isBlank()) {
                    SqlInfo info = new SqlInfo(sql.trim(), e.getKey(), null, EDbVendor.dbvsparksql.name());
                    info.setOriginIndex(idx++);
                    sqlUnits.add(info);
                }
            }
            if (sqlUnits.isEmpty() && e.getKey().toLowerCase(Locale.ROOT).endsWith(".sql")) {
                SqlInfo info = new SqlInfo(text, e.getKey(), null, vendor.name());
                info.setOriginIndex(idx++);
                sqlUnits.add(info);
            }
        }
    }

    private void extractRedshiftLog(Map<String, byte[]> files, List<SqlInfo> sqlUnits, ExtractionResult res) {
        res.setResolvedVendor(EDbVendor.dbvredshift);
        int idx = 0;
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            String text = new String(e.getValue(), StandardCharsets.UTF_8);
            for (String line : text.split("\r?\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.toLowerCase(Locale.ROOT).startsWith("userid,")) {
                    continue;
                }
                SqlInfo info = new SqlInfo(trimmed, e.getKey(), null, EDbVendor.dbvredshift.name());
                info.setOriginIndex(idx++);
                sqlUnits.add(info);
            }
        }
    }

    private void extractCatalogJson(
            Map<String, byte[]> files,
            EDbVendor vendor,
            List<DbObject> objects,
            List<SqlInfo> sqlUnits,
            List<String> warnings) {
        int idx = 0;
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            try {
                SqlflowCatalog cat = objectMapper.readValue(e.getValue(), SqlflowCatalog.class);
                objects.addAll(CatalogUtils.collectAllDbObjects(cat));
                if (cat.getQueries() != null) {
                    for (QueryObject q : cat.getQueries()) {
                        if (q != null && q.getSourceCode() != null) {
                            SqlInfo info = new SqlInfo(q.getSourceCode(), q.getName(), null, vendor.name());
                            info.setOriginIndex(idx++);
                            sqlUnits.add(info);
                        }
                    }
                }
            } catch (Exception ex) {
                warnings.add("Invalid catalog JSON in " + e.getKey() + ": " + ex.getMessage());
            }
        }
    }

    private void extractCsvMetadata(
            Map<String, byte[]> files,
            ExtractionRequest req,
            List<DbObject> objects,
            List<SqlInfo> sqlUnits,
            AtomicLong idSeq) {
        Map<String, DbObject> byKey = new LinkedHashMap<>();
        int idx = 0;
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            String content = new String(e.getValue(), StandardCharsets.UTF_8);
            String[] lines = content.split("\r?\n");
            for (int li = 0; li < lines.length; li++) {
                String line = lines[li].trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (li == 0 && (line.toLowerCase(Locale.ROOT).contains("table") || line.toLowerCase(Locale.ROOT).contains("column"))) {
                    continue;
                }
                String[] cols = line.split(",", -1);
                if (cols.length >= 4) {
                    String db = CatalogUtils.stripQuotes(cols[0]);
                    String sc = CatalogUtils.stripQuotes(cols[1]);
                    String tbl = CatalogUtils.stripQuotes(cols[2]);
                    String col = CatalogUtils.stripQuotes(cols[3]);
                    String dtype = cols.length >= 5 ? CatalogUtils.stripQuotes(cols[4]) : "VARCHAR";
                    String key = db + "." + sc + "." + tbl;
                    DbObject dbo = byKey.computeIfAbsent(key, k -> {
                        DbObject o = new DbObject(String.valueOf(idSeq.getAndIncrement()), tbl, "table");
                        o.setDatabase(db.isEmpty() ? "DEFAULT" : db);
                        o.setSchema(sc.isEmpty() ? "DEFAULT" : sc);
                        o.setFromDDL(Boolean.TRUE);
                        o.setColumns(new ArrayList<>());
                        return o;
                    });
                    dbo.getColumns().add(new Column(String.valueOf(idSeq.getAndIncrement()), col, dtype));
                } else if (line.toUpperCase(Locale.ROOT).contains("SELECT ") || line.toUpperCase(Locale.ROOT).contains("CREATE ")) {
                    SqlInfo unit = new SqlInfo(line, e.getKey(), null, req != null && req.getTargetVendor() != null ? req.getTargetVendor().name() : EDbVendor.dbvoracle.name());
                    unit.setOriginIndex(idx++);
                    sqlUnits.add(unit);
                }
            }
        }
        objects.addAll(byKey.values());
    }
}
