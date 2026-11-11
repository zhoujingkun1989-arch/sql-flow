package io.sqlflow.connectors.jdbc.harvester;

import io.sqlflow.connectors.jdbc.CatalogBuilder;
import io.sqlflow.connectors.jdbc.SchemaFilterMatcher;
import io.sqlflow.contracts.domain.DataSource;
import io.sqlflow.contracts.lineage.Column;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Microsoft SQL Server ({@code dbvmssql}) &amp; Azure SQL ({@code dbvazuresql}) catalog harvester ({@code 02 §6.3.3}):
 * <ul>
 *   <li>Multi-database catalog scan via {@code sys.databases} (or single current DB for Azure SQL)</li>
 *   <li>{@code sys.schemas}, {@code sys.objects}, {@code sys.columns}, {@code sys.types}, {@code sys.default_constraints}, {@code sys.computed_columns}</li>
 *   <li>{@code sys.indexes} + {@code sys.index_columns} + {@code sys.foreign_key_columns}</li>
 *   <li>{@code sys.sql_modules} for views, procedures, functions, triggers + {@code sys.parameters}</li>
 *   <li>{@code sys.synonyms} and linked servers via {@code sys.servers}</li>
 * </ul>
 */
public final class SqlServerMetadataHarvester extends AbstractVendorHarvester {

    private static final String SQL_DATABASES = """
            SELECT name
              FROM sys.databases
             WHERE state = 0
             ORDER BY name
            """;

    private static final String SQL_COLUMNS_TEMPLATE = """
            SELECT s.name AS schema_name,
                   o.name AS table_name,
                   o.type AS object_type,
                   c.name AS column_name,
                   t.name AS data_type,
                   c.max_length,
                   c.precision,
                   c.scale,
                   c.is_nullable,
                   c.is_identity,
                   c.is_computed,
                   dc.definition AS default_value,
                   cc.definition AS computed_definition
              FROM %s.sys.objects o
              JOIN %s.sys.schemas s ON o.schema_id = s.schema_id
              JOIN %s.sys.columns c ON o.object_id = c.object_id
              JOIN %s.sys.types t ON c.user_type_id = t.user_type_id
              LEFT JOIN %s.sys.default_constraints dc ON c.default_object_id = dc.object_id
              LEFT JOIN %s.sys.computed_columns cc ON c.object_id = cc.object_id AND c.column_id = cc.column_id
             WHERE o.type IN ('U', 'V') AND o.is_ms_shipped = 0
             ORDER BY s.name, o.name, c.column_id
            """;

    private static final String SQL_INDEX_KEYS_TEMPLATE = """
            SELECT s.name AS schema_name,
                   o.name AS table_name,
                   c.name AS column_name,
                   i.is_primary_key,
                   i.is_unique_constraint,
                   i.is_unique
              FROM %s.sys.indexes i
              JOIN %s.sys.index_columns ic ON i.object_id = ic.object_id AND i.index_id = ic.index_id
              JOIN %s.sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id
              JOIN %s.sys.objects o ON i.object_id = o.object_id
              JOIN %s.sys.schemas s ON o.schema_id = s.schema_id
             WHERE o.type = 'U' AND o.is_ms_shipped = 0
            """;

    private static final String SQL_FOREIGN_KEYS_TEMPLATE = """
            SELECT s.name AS schema_name,
                   o.name AS table_name,
                   c.name AS column_name
              FROM %s.sys.foreign_key_columns fkc
              JOIN %s.sys.objects o ON fkc.parent_object_id = o.object_id
              JOIN %s.sys.schemas s ON o.schema_id = s.schema_id
              JOIN %s.sys.columns c ON fkc.parent_object_id = c.object_id AND fkc.parent_column_id = c.column_id
            """;

    private static final String SQL_MODULES_TEMPLATE = """
            SELECT s.name AS schema_name,
                   o.name AS object_name,
                   o.type AS object_type,
                   m.definition AS definition
              FROM %s.sys.sql_modules m
              JOIN %s.sys.objects o ON m.object_id = o.object_id
              JOIN %s.sys.schemas s ON o.schema_id = s.schema_id
             WHERE o.is_ms_shipped = 0
               AND o.type IN ('V', 'P', 'FN', 'IF', 'TF', 'TR')
             ORDER BY s.name, o.name
            """;

    private static final String SQL_PARAMETERS_TEMPLATE = """
            SELECT s.name AS schema_name,
                   o.name AS object_name,
                   p.name AS param_name,
                   t.name AS data_type,
                   p.is_output,
                   p.parameter_id
              FROM %s.sys.parameters p
              JOIN %s.sys.objects o ON p.object_id = o.object_id
              JOIN %s.sys.schemas s ON o.schema_id = s.schema_id
              JOIN %s.sys.types t ON p.user_type_id = t.user_type_id
             WHERE o.is_ms_shipped = 0
             ORDER BY s.name, o.name, p.parameter_id
            """;

    private static final String SQL_SYNONYMS_TEMPLATE = """
            SELECT s.name AS schema_name,
                   sn.name AS synonym_name,
                   sn.base_object_name
              FROM %s.sys.synonyms sn
              JOIN %s.sys.schemas s ON sn.schema_id = s.schema_id
             ORDER BY s.name, sn.name
            """;

    private static final String SQL_LINKED_SERVERS = """
            SELECT name, data_source, provider
              FROM sys.servers
             WHERE is_linked = 1
            """;

    @Override
    public void harvest(
            Connection conn,
            EDbVendor vendor,
            DataSource dataSource,
            SchemaFilterMatcher filter,
            CatalogBuilder builder,
            int timeoutSeconds) throws SQLException {

        String defaultDb = resolveCurrentDatabase(conn, dataSource != null ? dataSource.getDatabase() : null, "master");
        List<String> targetDatabases = new ArrayList<>();

        if (vendor == EDbVendor.dbvazuresql) {
            if (filter.acceptsDatabase(defaultDb)) {
                targetDatabases.add(defaultDb);
            }
        } else {
            boolean listed = runQuery(conn, SQL_DATABASES, timeoutSeconds, builder, "sys.databases", false, (rs, row) -> {
                String db = row.strTrim("name");
                if (db != null && filter.acceptsDatabase(db)) {
                    targetDatabases.add(db);
                }
            });
            if (!listed || targetDatabases.isEmpty()) {
                if (filter.acceptsDatabase(defaultDb)) {
                    targetDatabases.add(defaultDb);
                }
            }
        }

        boolean singleCurrentDbOnly = vendor == EDbVendor.dbvazuresql || isEmbeddedCompatibilityUrl(conn);

        for (String db : targetDatabases) {
            String prefix = singleCurrentDbOnly ? "" : "[" + db.replace("]", "]]") + "].";
            harvestSingleDatabase(conn, db, prefix, filter, builder, timeoutSeconds);
            if (singleCurrentDbOnly) {
                break;
            }
        }

        runQuery(conn, SQL_LINKED_SERVERS, timeoutSeconds, builder, "sys.servers", false, (rs, row) ->
                builder.addDbLink("dbo", row.strTrim("name"), null, row.strTrim("data_source")));
    }

    private void harvestSingleDatabase(
            Connection conn,
            String db,
            String dbPrefix,
            SchemaFilterMatcher filter,
            CatalogBuilder builder,
            int timeoutSeconds) {

        String colSql = dbPrefix.isEmpty()
                ? stripLeadingDots(String.format(SQL_COLUMNS_TEMPLATE, "", "", "", "", "", ""))
                : stripTrailingDots(SQL_COLUMNS_TEMPLATE, dbPrefix);

        runQuery(conn, colSql, timeoutSeconds, builder, db + ".sys.columns", true, (rs, row) -> {
            String schema = row.strTrim("schema_name");
            String table = row.strTrim("table_name");
            String objType = row.strTrim("object_type");
            String colName = row.strTrim("column_name");
            if (schema == null || table == null || colName == null || !filter.acceptsSchema(db, schema)) {
                return;
            }
            boolean isView = "V".equalsIgnoreCase(objType);
            if (isView && !filter.acceptsView(db, schema, table)) {
                return;
            }
            DbObject parent = isView
                    ? builder.getOrCreateView(db, schema, table)
                    : builder.getOrCreateTable(db, schema, table);
            String baseType = row.strTrim("data_type");
            Integer maxLen = row.intObj("max_length");
            if (maxLen != null && maxLen > 0 && baseType != null
                    && (baseType.equalsIgnoreCase("nvarchar") || baseType.equalsIgnoreCase("nchar"))) {
                maxLen = maxLen / 2;
            }
            String formattedType = formatPrecisionScale(
                    baseType,
                    maxLen,
                    row.intObj("precision"),
                    row.intObj("scale"));
            Column col = builder.addOrUpdateColumn(
                    parent,
                    colName,
                    formattedType,
                    row.boolObj("is_nullable"),
                    row.strTrim("default_value"),
                    null);
            if (col != null) {
                Boolean identity = row.boolObj("is_identity");
                if (Boolean.TRUE.equals(identity)) {
                    col.setIdentity(true);
                }
                Boolean computed = row.boolObj("is_computed");
                if (Boolean.TRUE.equals(computed)) {
                    col.setComputed(true);
                    col.setComputedDefinition(row.strTrim("computed_definition"));
                }
            }
        });

        String idxSql = formatDbTemplate(SQL_INDEX_KEYS_TEMPLATE, dbPrefix);
        runQuery(conn, idxSql, timeoutSeconds, builder, db + ".sys.indexes", false, (rs, row) -> {
            String schema = row.strTrim("schema_name");
            String table = row.strTrim("table_name");
            String colName = row.strTrim("column_name");
            if (schema == null || !filter.acceptsSchema(db, schema)) {
                return;
            }
            if (Boolean.TRUE.equals(row.boolObj("is_primary_key"))) {
                builder.markConstraintColumn(db, schema, table, colName, "P");
            } else if (Boolean.TRUE.equals(row.boolObj("is_unique_constraint")) || Boolean.TRUE.equals(row.boolObj("is_unique"))) {
                builder.markConstraintColumn(db, schema, table, colName, "U");
            } else {
                builder.markConstraintColumn(db, schema, table, colName, "INDEX");
            }
        });

        String fkSql = formatDbTemplate(SQL_FOREIGN_KEYS_TEMPLATE, dbPrefix);
        runQuery(conn, fkSql, timeoutSeconds, builder, db + ".sys.foreign_key_columns", false, (rs, row) -> {
            String schema = row.strTrim("schema_name");
            if (schema != null && filter.acceptsSchema(db, schema)) {
                builder.markConstraintColumn(db, schema, row.strTrim("table_name"), row.strTrim("column_name"), "F");
            }
        });

        String modSql = formatDbTemplate(SQL_MODULES_TEMPLATE, dbPrefix);
        runQuery(conn, modSql, timeoutSeconds, builder, db + ".sys.sql_modules", false, (rs, row) -> {
            String schema = row.strTrim("schema_name");
            String name = row.strTrim("object_name");
            String type = row.strTrim("object_type");
            String def = row.str("definition");
            if (schema == null || name == null || type == null) {
                return;
            }
            String uType = type.toUpperCase(Locale.ROOT);
            if ("V".equals(uType)) {
                if (filter.acceptsView(db, schema, name)) {
                    builder.addViewDefinition(db, schema, name, def, false);
                }
            } else if (filter.acceptsRoutine(db, schema, name)) {
                builder.addRoutine(db, schema, null, name, uType, def);
            }
        });

        if (filter.shouldHarvestRoutines()) {
            String paramSql = formatDbTemplate(SQL_PARAMETERS_TEMPLATE, dbPrefix);
            runQuery(conn, paramSql, timeoutSeconds, builder, db + ".sys.parameters", false, (rs, row) -> {
                String schema = row.strTrim("schema_name");
                String objName = row.strTrim("object_name");
                if (schema != null && objName != null && filter.acceptsRoutine(db, schema, objName)) {
                    String paramName = row.strTrim("param_name");
                    Integer paramId = row.intObj("parameter_id");
                    boolean isOut = Boolean.TRUE.equals(row.boolObj("is_output"));
                    String inOut = (paramId != null && paramId == 0) ? "RETURN" : (isOut ? "INOUT" : "IN");
                    builder.addRoutineArgument(db, schema, null, objName, paramName, row.strTrim("data_type"), inOut);
                }
            });
        }

        String synSql = formatDbTemplate(SQL_SYNONYMS_TEMPLATE, dbPrefix);
        runQuery(conn, synSql, timeoutSeconds, builder, db + ".sys.synonyms", false, (rs, row) -> {
            String schema = row.strTrim("schema_name");
            String synName = row.strTrim("synonym_name");
            String baseObj = row.strTrim("base_object_name");
            if (schema != null && synName != null && filter.acceptsSchema(db, schema)) {
                ParsedFourPart parsed = parseFourPartName(baseObj, db, schema);
                builder.addSynonym(db, schema, synName, parsed.database, parsed.schema, parsed.object, parsed.server);
            }
        });
    }

    private static String formatDbTemplate(String template, String dbPrefix) {
        if (dbPrefix.isEmpty()) {
            return stripLeadingDots(template.replace("%s.", ""));
        }
        String cleanDb = dbPrefix.endsWith(".") ? dbPrefix.substring(0, dbPrefix.length() - 1) : dbPrefix;
        return template.replace("%s", cleanDb);
    }

    private static String stripTrailingDots(String template, String dbPrefix) {
        String cleanDb = dbPrefix.endsWith(".") ? dbPrefix.substring(0, dbPrefix.length() - 1) : dbPrefix;
        return template.replace("%s", cleanDb);
    }

    private static String stripLeadingDots(String sql) {
        return sql.replace(" .sys.", " sys.").replace("\n.sys.", "\nsys.");
    }

    private static boolean isEmbeddedCompatibilityUrl(Connection conn) {
        try {
            String url = conn.getMetaData().getURL();
            if (url == null) {
                return false;
            }
            String lower = url.toLowerCase(Locale.ROOT);
            return lower.startsWith("jdbc:h2:") || lower.startsWith("jdbc:sqlite:");
        } catch (SQLException e) {
            return false;
        }
    }

    private record ParsedFourPart(String server, String database, String schema, String object) {
    }

    private static ParsedFourPart parseFourPartName(String raw, String fallbackDb, String fallbackSchema) {
        if (raw == null || raw.isBlank()) {
            return new ParsedFourPart(null, fallbackDb, fallbackSchema, null);
        }
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inBracket = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '[') {
                inBracket = true;
            } else if (c == ']') {
                inBracket = false;
            } else if (c == '.' && !inBracket) {
                parts.add(cur.toString().replace("\"", "").trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString().replace("\"", "").trim());
        return switch (parts.size()) {
            case 1 -> new ParsedFourPart(null, fallbackDb, fallbackSchema, parts.get(0));
            case 2 -> new ParsedFourPart(null, fallbackDb, parts.get(0), parts.get(1));
            case 3 -> new ParsedFourPart(null, parts.get(0).isEmpty() ? fallbackDb : parts.get(0),
                    parts.get(1).isEmpty() ? fallbackSchema : parts.get(1), parts.get(2));
            default -> new ParsedFourPart(
                    parts.get(0).isEmpty() ? null : parts.get(0),
                    parts.get(1).isEmpty() ? fallbackDb : parts.get(1),
                    parts.get(2).isEmpty() ? fallbackSchema : parts.get(2),
                    parts.get(3));
        };
    }
}
