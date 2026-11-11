package io.sqlflow.connectors.jdbc.harvester;

import io.sqlflow.connectors.jdbc.CatalogBuilder;
import io.sqlflow.connectors.jdbc.SchemaFilterMatcher;
import io.sqlflow.contracts.domain.DataSource;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Amazon Redshift ({@code dbvredshift}) live catalog &amp; query history harvester ({@code 02 §6.3.3 Redshift}):
 * <ul>
 *   <li>{@code SVV_COLUMNS} (including late-binding views) + {@code SVV_EXTERNAL_COLUMNS} (Redshift Spectrum)</li>
 *   <li>{@code SVV_VIEWS} / {@code PG_VIEWS} + {@code information_schema.table_constraints}</li>
 *   <li>{@code PG_PROC} + {@code PG_NAMESPACE} for stored procedures &amp; UDFs</li>
 *   <li>{@code STL_QUERY} + {@code STL_QUERYTEXT} multi-fragment query history reassembly ordered by {@code (query, sequence ASC)}</li>
 * </ul>
 */
public final class RedshiftMetadataHarvester extends AbstractVendorHarvester {

    private static final String SQL_SVV_COLUMNS = """
            SELECT table_catalog,
                   table_schema,
                   table_name,
                   column_name,
                   data_type,
                   character_maximum_length,
                   numeric_precision,
                   numeric_scale,
                   is_nullable,
                   column_default,
                   remarks,
                   ordinal_position
              FROM SVV_COLUMNS
             ORDER BY table_schema, table_name, ordinal_position
            """;

    private static final String SQL_INFO_COLUMNS_FALLBACK = """
            SELECT table_catalog,
                   table_schema,
                   table_name,
                   column_name,
                   data_type,
                   character_maximum_length,
                   numeric_precision,
                   numeric_scale,
                   is_nullable,
                   column_default,
                   ordinal_position
              FROM information_schema.columns
             ORDER BY table_schema, table_name, ordinal_position
            """;

    private static final String SQL_SVV_EXTERNAL_COLUMNS = """
            SELECT schemaname,
                   tablename,
                   columnname,
                   external_type,
                   columnnum
              FROM SVV_EXTERNAL_COLUMNS
             ORDER BY schemaname, tablename, columnnum
            """;

    private static final String SQL_CONSTRAINTS = """
            SELECT tc.table_catalog,
                   tc.table_schema,
                   tc.table_name,
                   kcu.column_name,
                   tc.constraint_type
              FROM information_schema.table_constraints tc
              JOIN information_schema.key_column_usage kcu
                ON tc.constraint_name = kcu.constraint_name
               AND tc.table_schema = kcu.table_schema
               AND tc.table_name = kcu.table_name
             WHERE tc.constraint_type IN ('PRIMARY KEY', 'UNIQUE', 'FOREIGN KEY')
            """;

    private static final String SQL_PG_VIEWS = """
            SELECT schemaname, viewname, definition
              FROM PG_VIEWS
             ORDER BY schemaname, viewname
            """;

    private static final String SQL_INFO_VIEWS_FALLBACK = """
            SELECT table_schema AS schemaname, table_name AS viewname, view_definition AS definition
              FROM information_schema.views
             ORDER BY table_schema, table_name
            """;

    private static final String SQL_ROUTINES = """
            SELECT n.nspname AS routine_schema,
                   p.proname AS routine_name,
                   p.prosrc AS routine_body
              FROM pg_catalog.pg_proc p
              JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
             WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
             ORDER BY n.nspname, p.proname
            """;

    private static final String SQL_STL_QUERYTEXT = """
            SELECT qt.query,
                   qt.sequence,
                   qt.text,
                   q.database
              FROM STL_QUERYTEXT qt
              LEFT JOIN STL_QUERY q ON q.query = qt.query
             WHERE qt.userid > 1
             ORDER BY qt.query, qt.sequence ASC
            """;

    @Override
    public void harvest(
            Connection conn,
            EDbVendor vendor,
            DataSource dataSource,
            SchemaFilterMatcher filter,
            CatalogBuilder builder,
            int timeoutSeconds) throws SQLException {

        String defaultDb = resolveCurrentDatabase(conn, dataSource != null ? dataSource.getDatabase() : null, "dev");

        boolean svvOk = runQuery(conn, SQL_SVV_COLUMNS, timeoutSeconds, builder, "SVV_COLUMNS", false,
                (rs, row) -> processSvvColumnRow(row, defaultDb, filter, builder));
        if (!svvOk) {
            runQuery(conn, SQL_INFO_COLUMNS_FALLBACK, timeoutSeconds, builder, "information_schema.columns", true,
                    (rs, row) -> processSvvColumnRow(row, defaultDb, filter, builder));
        }

        // Redshift Spectrum external tables
        runQuery(conn, SQL_SVV_EXTERNAL_COLUMNS, timeoutSeconds, builder, "SVV_EXTERNAL_COLUMNS", false, (rs, row) -> {
            String schema = row.strTrim("schemaname");
            String table = row.strTrim("tablename");
            String colName = row.strTrim("columnname");
            if (schema != null && table != null && colName != null && filter.acceptsSchema(defaultDb, schema)) {
                DbObject extTable = builder.getOrCreateTable(defaultDb, schema, table);
                extTable.setSubType("external_table");
                builder.addOrUpdateColumn(extTable, colName, row.strTrim("external_type"), true, null, null);
            }
        });

        runQuery(conn, SQL_CONSTRAINTS, timeoutSeconds, builder, "information_schema.table_constraints", false, (rs, row) -> {
            String db = firstNonBlank(row.strTrim("table_catalog"), defaultDb);
            String schema = row.strTrim("table_schema");
            if (schema != null && filter.acceptsSchema(db, schema)) {
                builder.markConstraintColumn(db, schema, row.strTrim("table_name"), row.strTrim("column_name"), row.strTrim("constraint_type"));
            }
        });

        if (filter.shouldHarvestViews()) {
            boolean pgViewsOk = runQuery(conn, SQL_PG_VIEWS, timeoutSeconds, builder, "PG_VIEWS", false, (rs, row) -> {
                String schema = row.strTrim("schemaname");
                String viewName = row.strTrim("viewname");
                if (schema != null && viewName != null && filter.acceptsView(defaultDb, schema, viewName)) {
                    builder.addViewDefinition(defaultDb, schema, viewName, row.str("definition"), false);
                }
            });
            if (!pgViewsOk) {
                runQuery(conn, SQL_INFO_VIEWS_FALLBACK, timeoutSeconds, builder, "information_schema.views", false, (rs, row) -> {
                    String schema = row.strTrim("schemaname");
                    String viewName = row.strTrim("viewname");
                    if (schema != null && viewName != null && filter.acceptsView(defaultDb, schema, viewName)) {
                        builder.addViewDefinition(defaultDb, schema, viewName, row.str("definition"), false);
                    }
                });
            }
        }

        if (filter.shouldHarvestRoutines()) {
            runQuery(conn, SQL_ROUTINES, timeoutSeconds, builder, "PG_PROC", false, (rs, row) -> {
                String schema = row.strTrim("routine_schema");
                String name = row.strTrim("routine_name");
                String body = row.str("routine_body");
                if (schema != null && name != null && filter.acceptsRoutine(defaultDb, schema, name)) {
                    String ddl = body != null && body.stripLeading().toUpperCase(Locale.ROOT).startsWith("CREATE ")
                            ? body
                            : "CREATE OR REPLACE PROCEDURE " + schema + "." + name + "() AS $$\n"
                                    + (body != null ? body : "") + "\n$$ LANGUAGE plpgsql;";
                    builder.addRoutine(defaultDb, schema, null, name, "procedure", ddl);
                }
            });
        }

        // Reassemble multi-part STL_QUERYTEXT 200-char fragments by (query, sequence ASC)
        Map<String, QueryFragmentAccumulator> queryMap = new LinkedHashMap<>();
        runQuery(conn, SQL_STL_QUERYTEXT, timeoutSeconds, builder, "STL_QUERYTEXT", false, (rs, row) -> {
            String queryId = row.strTrim("query");
            String fragment = row.str("text");
            String qDb = firstNonBlank(row.strTrim("database"), defaultDb);
            if (queryId == null || fragment == null) {
                return;
            }
            QueryFragmentAccumulator acc = queryMap.computeIfAbsent(queryId, id -> new QueryFragmentAccumulator(qDb));
            acc.append(unescapeRedshiftTextChunk(fragment));
        });

        for (Map.Entry<String, QueryFragmentAccumulator> e : queryMap.entrySet()) {
            String queryId = e.getKey();
            QueryFragmentAccumulator acc = e.getValue();
            String sql = acc.sql.toString().trim();
            if (!sql.isEmpty() && !isUtilityNoiseQuery(sql) && filter.acceptsDatabase(acc.database)) {
                builder.addQuerySource(acc.database, "public", "query_" + queryId, "query_history", sql,
                        "stl_querytext/query_" + queryId + ".sql");
            }
        }
    }

    private static void processSvvColumnRow(
            RowAccessor row,
            String defaultDb,
            SchemaFilterMatcher filter,
            CatalogBuilder builder) throws SQLException {
        String db = firstNonBlank(row.strTrim("table_catalog"), defaultDb);
        String schema = row.strTrim("table_schema");
        String table = row.strTrim("table_name");
        String colName = row.strTrim("column_name");
        if (schema == null || table == null || colName == null || !filter.acceptsSchema(db, schema)) {
            return;
        }
        DbObject parent = builder.getOrCreateTable(db, schema, table);
        String formattedType = formatPrecisionScale(
                row.strTrim("data_type"),
                row.intObj("character_maximum_length"),
                row.intObj("numeric_precision"),
                row.intObj("numeric_scale"));
        builder.addOrUpdateColumn(
                parent,
                colName,
                formattedType,
                parseNullableFlag(row.strTrim("is_nullable")),
                row.str("column_default"),
                row.str("remarks"));
    }

    static String unescapeRedshiftTextChunk(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
    }

    private static boolean isUtilityNoiseQuery(String sql) {
        String u = sql.stripLeading().toUpperCase(Locale.ROOT);
        return u.startsWith("FETCH ") || u.startsWith("CLOSE ") || u.startsWith("COMMIT")
                || u.startsWith("BEGIN") || u.startsWith("ROLLBACK") || u.startsWith("SET ")
                || u.startsWith("SHOW ") || u.startsWith("SELECT 1");
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private static final class QueryFragmentAccumulator {
        final String database;
        final StringBuilder sql = new StringBuilder();

        QueryFragmentAccumulator(String database) {
            this.database = database;
        }

        void append(String chunk) {
            sql.append(chunk);
        }
    }
}
