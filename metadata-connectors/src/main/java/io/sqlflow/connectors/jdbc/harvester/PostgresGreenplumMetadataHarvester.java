package io.sqlflow.connectors.jdbc.harvester;

import io.sqlflow.connectors.jdbc.CatalogBuilder;
import io.sqlflow.connectors.jdbc.SchemaFilterMatcher;
import io.sqlflow.contracts.domain.DataSource;
import io.sqlflow.contracts.lineage.Column;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * PostgreSQL ({@code dbvpostgresql}), Greenplum ({@code dbvgreenplum}), GaussDB ({@code dbvgaussdb}),
 * EDB ({@code dbvedb}), and Vertica ({@code dbvvertica}) live catalog harvester ({@code 02 §6.3.3}).
 *
 * <p>Automatically filters Greenplum partition child tables matching {@code _1_prt_*} / {@code _%_prt_%}.
 */
public final class PostgresGreenplumMetadataHarvester extends AbstractVendorHarvester {

    private static final Pattern GREENPLUM_PARTITION_TABLE = Pattern.compile(".*_\\d+_prt_.*", Pattern.CASE_INSENSITIVE);

    private static final String SQL_COLUMNS = """
            SELECT c.table_catalog,
                   c.table_schema,
                   c.table_name,
                   t.table_type,
                   c.column_name,
                   c.udt_name,
                   c.data_type,
                   c.character_maximum_length,
                   c.numeric_precision,
                   c.numeric_scale,
                   c.is_nullable,
                   c.column_default,
                   c.is_identity,
                   c.is_generated,
                   c.generation_expression,
                   c.ordinal_position
              FROM information_schema.columns c
              LEFT JOIN information_schema.tables t
                ON t.table_catalog = c.table_catalog
               AND t.table_schema = c.table_schema
               AND t.table_name = c.table_name
             ORDER BY c.table_schema, c.table_name, c.ordinal_position
            """;

    private static final String SQL_COLUMNS_FALLBACK = """
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
             ORDER BY tc.table_schema, tc.table_name, kcu.ordinal_position
            """;

    private static final String SQL_VIEWS = """
            SELECT table_catalog, table_schema, table_name, view_definition
              FROM information_schema.views
             ORDER BY table_schema, table_name
            """;

    private static final String SQL_MATVIEWS = """
            SELECT schemaname, matviewname, definition
              FROM pg_catalog.pg_matviews
             ORDER BY schemaname, matviewname
            """;

    private static final String SQL_PG_PROC = """
            SELECT n.nspname AS routine_schema,
                   p.proname AS routine_name,
                   CASE p.prokind
                        WHEN 'p' THEN 'PROCEDURE'
                        WHEN 'f' THEN 'FUNCTION'
                        ELSE 'FUNCTION'
                   END AS routine_type,
                   pg_get_functiondef(p.oid) AS routine_definition
              FROM pg_catalog.pg_proc p
              JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
             WHERE p.prokind IN ('f', 'p')
               AND n.nspname NOT IN ('pg_catalog', 'information_schema')
             ORDER BY n.nspname, p.proname
            """;

    private static final String SQL_ROUTINES_FALLBACK = """
            SELECT routine_catalog,
                   routine_schema,
                   routine_name,
                   specific_name,
                   routine_type,
                   data_type,
                   routine_definition
              FROM information_schema.routines
             ORDER BY routine_schema, routine_name
            """;

    private static final String SQL_PARAMETERS = """
            SELECT r.routine_schema,
                   r.routine_name,
                   p.parameter_name,
                   p.data_type,
                   p.parameter_mode,
                   p.ordinal_position
              FROM information_schema.parameters p
              JOIN information_schema.routines r
                ON r.specific_schema = p.specific_schema
               AND r.specific_name = p.specific_name
             ORDER BY r.routine_schema, r.routine_name, p.ordinal_position
            """;

    private static final String SQL_TRIGGERS = """
            SELECT trigger_schema,
                   trigger_name,
                   event_manipulation,
                   event_object_schema,
                   event_object_table,
                   action_timing,
                   action_statement
              FROM information_schema.triggers
             ORDER BY trigger_schema, trigger_name
            """;

    private static final String SQL_SEQUENCES = """
            SELECT sequence_schema,
                   sequence_name,
                   start_value,
                   minimum_value,
                   maximum_value,
                   increment,
                   cycle_option
              FROM information_schema.sequences
             ORDER BY sequence_schema, sequence_name
            """;

    @Override
    public void harvest(
            Connection conn,
            EDbVendor vendor,
            DataSource dataSource,
            SchemaFilterMatcher filter,
            CatalogBuilder builder,
            int timeoutSeconds) throws SQLException {

        String defaultDb = resolveCurrentDatabase(conn, dataSource != null ? dataSource.getDatabase() : null, "postgres");
        boolean isGreenplum = vendor == EDbVendor.dbvgreenplum;

        boolean loadedRich = runQuery(conn, SQL_COLUMNS, timeoutSeconds, builder, "information_schema.columns", false,
                (rs, row) -> processColumnRow(row, defaultDb, isGreenplum, filter, builder));
        if (!loadedRich) {
            runQuery(conn, SQL_COLUMNS_FALLBACK, timeoutSeconds, builder, "information_schema.columns(fallback)", true,
                    (rs, row) -> processColumnRow(row, defaultDb, isGreenplum, filter, builder));
        }

        runQuery(conn, SQL_CONSTRAINTS, timeoutSeconds, builder, "information_schema.table_constraints", false, (rs, row) -> {
            String db = firstNonBlank(row.strTrim("table_catalog"), defaultDb);
            String schema = row.strTrim("table_schema");
            String table = row.strTrim("table_name");
            String col = row.strTrim("column_name");
            String type = row.strTrim("constraint_type");
            if (schema != null && filter.acceptsSchema(db, schema)) {
                builder.markConstraintColumn(db, schema, table, col, type);
            }
        });

        if (filter.shouldHarvestViews()) {
            runQuery(conn, SQL_VIEWS, timeoutSeconds, builder, "information_schema.views", false, (rs, row) -> {
                String db = firstNonBlank(row.strTrim("table_catalog"), defaultDb);
                String schema = row.strTrim("table_schema");
                String viewName = row.strTrim("table_name");
                String def = row.str("view_definition");
                if (schema != null && viewName != null && filter.acceptsView(db, schema, viewName)) {
                    builder.addViewDefinition(db, schema, viewName, def, false);
                }
            });

            runQuery(conn, SQL_MATVIEWS, timeoutSeconds, builder, "pg_matviews", false, (rs, row) -> {
                String schema = row.strTrim("schemaname");
                String matview = row.strTrim("matviewname");
                String def = row.str("definition");
                if (schema != null && matview != null && filter.acceptsView(defaultDb, schema, matview)) {
                    builder.addViewDefinition(defaultDb, schema, matview, def, true);
                }
            });
        }

        if (filter.shouldHarvestRoutines()) {
            boolean usedPgProc = runQuery(conn, SQL_PG_PROC, timeoutSeconds, builder, "pg_proc", false, (rs, row) -> {
                String schema = row.strTrim("routine_schema");
                String name = row.strTrim("routine_name");
                String type = row.strTrim("routine_type");
                String def = row.str("routine_definition");
                if (schema != null && name != null && filter.acceptsRoutine(defaultDb, schema, name)) {
                    builder.addRoutine(defaultDb, schema, null, name, type, def);
                }
            });

            if (!usedPgProc) {
                runQuery(conn, SQL_ROUTINES_FALLBACK, timeoutSeconds, builder, "information_schema.routines", false, (rs, row) -> {
                    String db = firstNonBlank(row.strTrim("routine_catalog"), defaultDb);
                    String schema = row.strTrim("routine_schema");
                    String name = row.strTrim("routine_name");
                    String type = row.strTrim("routine_type");
                    String body = row.str("routine_definition");
                    if (schema != null && name != null && filter.acceptsRoutine(db, schema, name)) {
                        String ddl = wrapRoutineBodyIfNeeded(schema, name, type, row.strTrim("data_type"), body);
                        builder.addRoutine(db, schema, null, name, type, ddl);
                    }
                });
            }

            runQuery(conn, SQL_PARAMETERS, timeoutSeconds, builder, "information_schema.parameters", false, (rs, row) -> {
                String schema = row.strTrim("routine_schema");
                String routineName = row.strTrim("routine_name");
                if (schema != null && routineName != null && filter.acceptsRoutine(defaultDb, schema, routineName)) {
                    builder.addRoutineArgument(
                            defaultDb,
                            schema,
                            null,
                            routineName,
                            row.strTrim("parameter_name"),
                            row.strTrim("data_type"),
                            row.strTrim("parameter_mode"));
                }
            });

            runQuery(conn, SQL_TRIGGERS, timeoutSeconds, builder, "information_schema.triggers", false, (rs, row) -> {
                String schema = row.strTrim("trigger_schema");
                String name = row.strTrim("trigger_name");
                String timing = row.strTrim("action_timing");
                String event = row.strTrim("event_manipulation");
                String tblSchema = row.strTrim("event_object_schema");
                String tbl = row.strTrim("event_object_table");
                String action = row.str("action_statement");
                if (schema != null && name != null && filter.acceptsRoutine(defaultDb, schema, name)) {
                    String ddl = action != null && action.stripLeading().toUpperCase(Locale.ROOT).startsWith("CREATE ")
                            ? action
                            : "CREATE TRIGGER " + schema + "." + name + " "
                                    + (timing != null ? timing : "AFTER") + " "
                                    + (event != null ? event : "INSERT") + " ON "
                                    + (tblSchema != null ? tblSchema + "." : "") + (tbl != null ? tbl : "unknown_table")
                                    + " FOR EACH ROW " + (action != null ? action : "");
                    builder.addRoutine(defaultDb, schema, null, name, "trigger", ddl);
                }
            });
        }

        runQuery(conn, SQL_SEQUENCES, timeoutSeconds, builder, "information_schema.sequences", false, (rs, row) -> {
            String schema = row.strTrim("sequence_schema");
            String seqName = row.strTrim("sequence_name");
            if (schema != null && seqName != null && filter.acceptsSchema(defaultDb, schema)) {
                builder.addSequence(
                        defaultDb,
                        schema,
                        seqName,
                        row.strTrim("start_value"),
                        row.strTrim("increment"),
                        row.strTrim("minimum_value"),
                        row.strTrim("maximum_value"),
                        parseNullableFlag(row.strTrim("cycle_option")),
                        null,
                        null);
            }
        });
    }

    private static void processColumnRow(
            RowAccessor row,
            String defaultDb,
            boolean isGreenplum,
            SchemaFilterMatcher filter,
            CatalogBuilder builder) throws SQLException {
        String db = firstNonBlank(row.strTrim("table_catalog"), defaultDb);
        String schema = row.strTrim("table_schema");
        String table = row.strTrim("table_name");
        String colName = row.strTrim("column_name");
        if (schema == null || table == null || colName == null) {
            return;
        }
        if (isGreenplum && GREENPLUM_PARTITION_TABLE.matcher(table).matches()) {
            return;
        }
        if (!filter.acceptsSchema(db, schema)) {
            return;
        }
        String tableType = row.strTrim("table_type");
        boolean isView = tableType != null && tableType.toUpperCase(Locale.ROOT).contains("VIEW");
        if (isView && !filter.acceptsView(db, schema, table)) {
            return;
        }
        DbObject parent = isView
                ? builder.getOrCreateView(db, schema, table)
                : builder.getOrCreateTable(db, schema, table);
        String rawType = row.strTrim("data_type");
        if ("USER-DEFINED".equalsIgnoreCase(rawType) || "ARRAY".equalsIgnoreCase(rawType)) {
            String udt = row.strTrim("udt_name");
            if (udt != null && !udt.isBlank()) {
                rawType = udt.startsWith("_") ? udt.substring(1) + "[]" : udt;
            }
        }
        String formattedType = formatPrecisionScale(
                rawType,
                row.intObj("character_maximum_length"),
                row.intObj("numeric_precision"),
                row.intObj("numeric_scale"));
        Column col = builder.addOrUpdateColumn(
                parent,
                colName,
                formattedType,
                parseNullableFlag(row.strTrim("is_nullable")),
                row.str("column_default"),
                null);
        if (col != null) {
            if (Boolean.TRUE.equals(parseNullableFlag(row.strTrim("is_identity")))) {
                col.setIdentity(true);
            }
            String gen = row.strTrim("is_generated");
            if (gen != null && !"NEVER".equalsIgnoreCase(gen)) {
                col.setComputed(true);
                col.setComputedDefinition(row.str("generation_expression"));
            }
        }
    }

    private static String wrapRoutineBodyIfNeeded(
            String schema,
            String name,
            String type,
            String returnType,
            String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String upper = body.stripLeading().toUpperCase(Locale.ROOT);
        if (upper.startsWith("CREATE ")) {
            return body;
        }
        boolean isFunc = type != null && type.toUpperCase(Locale.ROOT).contains("FUNC");
        if (isFunc) {
            String ret = (returnType != null && !returnType.isBlank()) ? returnType : "VOID";
            return "CREATE OR REPLACE FUNCTION " + schema + "." + name + "() RETURNS " + ret
                    + " AS $$\n" + body + "\n$$ LANGUAGE sql;";
        }
        return "CREATE OR REPLACE PROCEDURE " + schema + "." + name + "()\nAS $$\n" + body + "\n$$ LANGUAGE plpgsql;";
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }
}
