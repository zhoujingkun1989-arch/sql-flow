package io.sqlflow.connectors.jdbc.harvester;

import io.sqlflow.connectors.jdbc.CatalogBuilder;
import io.sqlflow.connectors.jdbc.SchemaFilterMatcher;
import io.sqlflow.contracts.domain.DataSource;
import io.sqlflow.contracts.lineage.Column;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Snowflake ({@code dbvsnowflake}) live catalog &amp; query history harvester ({@code 02 §6.3.3 Snowflake}, {@code 04 §9.3}):
 * <ul>
 *   <li>{@code SNOWFLAKE.ACCOUNT_USAGE.COLUMNS} / {@code TABLES} / {@code VIEWS} / {@code TABLE_CONSTRAINTS} / {@code PROCEDURES} / {@code FUNCTIONS}
 *       with automatic fallback to {@code INFORMATION_SCHEMA} when {@code ACCOUNT_USAGE} privileges are restricted</li>
 *   <li>{@code SNOWFLAKE.ACCOUNT_USAGE.STAGES} / {@code STREAMS} (or {@code INFORMATION_SCHEMA.STAGES})</li>
 *   <li>{@code SNOWFLAKE.ACCOUNT_USAGE.QUERY_HISTORY} across all 71 Snowflake statement types ({@code 04 §9.3})</li>
 * </ul>
 */
public final class SnowflakeMetadataHarvester extends AbstractVendorHarvester {

    /**
     * All 71 Snowflake {@code QUERY_HISTORY.QUERY_TYPE} values supported by SQLFlow 8.2.2.15 ({@code 04 §9.3}).
     */
    public static final Set<String> SUPPORTED_71_QUERY_TYPES = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            "SELECT",
            "INSERT",
            "UPDATE",
            "DELETE",
            "MERGE",
            "MULTI_TABLE_INSERT",
            "COPY",
            "UNLOAD",
            "CREATE",
            "CREATE_TABLE",
            "CREATE_TABLE_AS_SELECT",
            "CREATE_VIEW",
            "CREATE_MATERIALIZED_VIEW",
            "CREATE_DYNAMIC_TABLE",
            "CREATE_EXTERNAL_TABLE",
            "CREATE_EVENT_TABLE",
            "CREATE_ICEBERG_TABLE",
            "CREATE_HYBRID_TABLE",
            "CREATE_STREAM",
            "CREATE_TASK",
            "CREATE_PIPE",
            "CREATE_STAGE",
            "CREATE_FILE_FORMAT",
            "CREATE_SEQUENCE",
            "CREATE_PROCEDURE",
            "CREATE_FUNCTION",
            "CREATE_SCHEMA",
            "CREATE_DATABASE",
            "CREATE_TAG",
            "CREATE_MASKING_POLICY",
            "CREATE_ROW_ACCESS_POLICY",
            "CREATE_ALERT",
            "CREATE_GIT_REPOSITORY",
            "CREATE_STREAMLIT",
            "CREATE_NOTEBOOK",
            "CREATE_CORTEX_SEARCH_SERVICE",
            "ALTER",
            "ALTER_TABLE",
            "ALTER_TABLE_ADD_COLUMN",
            "ALTER_TABLE_DROP_COLUMN",
            "ALTER_TABLE_MODIFY_COLUMN",
            "ALTER_VIEW",
            "ALTER_MATERIALIZED_VIEW",
            "ALTER_DYNAMIC_TABLE",
            "ALTER_STREAM",
            "ALTER_TASK",
            "ALTER_PIPE",
            "ALTER_STAGE",
            "ALTER_PROCEDURE",
            "ALTER_FUNCTION",
            "RENAME_TABLE",
            "RENAME_VIEW",
            "RENAME_COLUMN",
            "RENAME_SCHEMA",
            "RENAME_DATABASE",
            "SWAP_WITH",
            "TRUNCATE_TABLE",
            "DROP",
            "DROP_TABLE",
            "DROP_VIEW",
            "DROP_MATERIALIZED_VIEW",
            "DROP_DYNAMIC_TABLE",
            "DROP_STREAM",
            "DROP_TASK",
            "DROP_PROCEDURE",
            "DROP_FUNCTION",
            "UNDROP_TABLE",
            "UNDROP_SCHEMA",
            "UNDROP_DATABASE",
            "CALL",
            "EXECUTE_IMMEDIATE",
            "EXECUTE_TASK")));

    private static final String SQL_ACCOUNT_USAGE_COLUMNS = """
            SELECT c.TABLE_CATALOG,
                   c.TABLE_SCHEMA,
                   c.TABLE_NAME,
                   t.TABLE_TYPE,
                   c.COLUMN_NAME,
                   c.DATA_TYPE,
                   c.CHARACTER_MAXIMUM_LENGTH,
                   c.NUMERIC_PRECISION,
                   c.NUMERIC_SCALE,
                   c.IS_NULLABLE,
                   c.COLUMN_DEFAULT,
                   c.IS_IDENTITY,
                   c.COMMENT,
                   c.ORDINAL_POSITION
              FROM SNOWFLAKE.ACCOUNT_USAGE.COLUMNS c
              LEFT JOIN SNOWFLAKE.ACCOUNT_USAGE.TABLES t
                ON t.TABLE_CATALOG = c.TABLE_CATALOG
               AND t.TABLE_SCHEMA = c.TABLE_SCHEMA
               AND t.TABLE_NAME = c.TABLE_NAME
               AND t.DELETED IS NULL
             WHERE c.DELETED IS NULL
             ORDER BY c.TABLE_CATALOG, c.TABLE_SCHEMA, c.TABLE_NAME, c.ORDINAL_POSITION
            """;

    private static final String SQL_INFO_SCHEMA_COLUMNS = """
            SELECT c.TABLE_CATALOG,
                   c.TABLE_SCHEMA,
                   c.TABLE_NAME,
                   t.TABLE_TYPE,
                   c.COLUMN_NAME,
                   c.DATA_TYPE,
                   c.CHARACTER_MAXIMUM_LENGTH,
                   c.NUMERIC_PRECISION,
                   c.NUMERIC_SCALE,
                   c.IS_NULLABLE,
                   c.COLUMN_DEFAULT,
                   c.IS_IDENTITY,
                   c.COMMENT,
                   c.ORDINAL_POSITION
              FROM INFORMATION_SCHEMA.COLUMNS c
              LEFT JOIN INFORMATION_SCHEMA.TABLES t
                ON t.TABLE_CATALOG = c.TABLE_CATALOG
               AND t.TABLE_SCHEMA = c.TABLE_SCHEMA
               AND t.TABLE_NAME = c.TABLE_NAME
             ORDER BY c.TABLE_CATALOG, c.TABLE_SCHEMA, c.TABLE_NAME, c.ORDINAL_POSITION
            """;

    private static final String SQL_ANSI_INFO_SCHEMA_COLUMNS = """
            SELECT c.TABLE_CATALOG,
                   c.TABLE_SCHEMA,
                   c.TABLE_NAME,
                   t.TABLE_TYPE,
                   c.COLUMN_NAME,
                   c.DATA_TYPE,
                   c.CHARACTER_MAXIMUM_LENGTH,
                   c.NUMERIC_PRECISION,
                   c.NUMERIC_SCALE,
                   c.IS_NULLABLE,
                   c.COLUMN_DEFAULT,
                   c.IS_IDENTITY,
                   c.REMARKS AS COMMENT,
                   c.ORDINAL_POSITION
              FROM INFORMATION_SCHEMA.COLUMNS c
              LEFT JOIN INFORMATION_SCHEMA.TABLES t
                ON t.TABLE_CATALOG = c.TABLE_CATALOG
               AND t.TABLE_SCHEMA = c.TABLE_SCHEMA
               AND t.TABLE_NAME = c.TABLE_NAME
             ORDER BY c.TABLE_CATALOG, c.TABLE_SCHEMA, c.TABLE_NAME, c.ORDINAL_POSITION
            """;

    private static final String SQL_ACCOUNT_USAGE_VIEWS = """
            SELECT TABLE_CATALOG, TABLE_SCHEMA, TABLE_NAME, VIEW_DEFINITION, IS_MATERIALIZED
              FROM SNOWFLAKE.ACCOUNT_USAGE.VIEWS
             WHERE DELETED IS NULL
             ORDER BY TABLE_CATALOG, TABLE_SCHEMA, TABLE_NAME
            """;

    private static final String SQL_INFO_SCHEMA_VIEWS = """
            SELECT TABLE_CATALOG, TABLE_SCHEMA, TABLE_NAME, VIEW_DEFINITION
              FROM INFORMATION_SCHEMA.VIEWS
             ORDER BY TABLE_CATALOG, TABLE_SCHEMA, TABLE_NAME
            """;

    private static final String SQL_INFO_SCHEMA_CONSTRAINTS = """
            SELECT tc.TABLE_CATALOG,
                   tc.TABLE_SCHEMA,
                   tc.TABLE_NAME,
                   kcu.COLUMN_NAME,
                   tc.CONSTRAINT_TYPE
              FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
              JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
                ON tc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME
               AND tc.TABLE_SCHEMA = kcu.TABLE_SCHEMA
               AND tc.TABLE_NAME = kcu.TABLE_NAME
            """;

    private static final String SQL_ACCOUNT_USAGE_PROCEDURES = """
            SELECT PROCEDURE_CATALOG,
                   PROCEDURE_SCHEMA,
                   PROCEDURE_NAME,
                   ARGUMENT_SIGNATURE,
                   DATA_TYPE,
                   PROCEDURE_DEFINITION
              FROM SNOWFLAKE.ACCOUNT_USAGE.PROCEDURES
             WHERE DELETED IS NULL
             ORDER BY PROCEDURE_CATALOG, PROCEDURE_SCHEMA, PROCEDURE_NAME
            """;

    private static final String SQL_INFO_SCHEMA_PROCEDURES = """
            SELECT PROCEDURE_CATALOG,
                   PROCEDURE_SCHEMA,
                   PROCEDURE_NAME,
                   ARGUMENT_SIGNATURE,
                   DATA_TYPE,
                   PROCEDURE_DEFINITION
              FROM INFORMATION_SCHEMA.PROCEDURES
             ORDER BY PROCEDURE_CATALOG, PROCEDURE_SCHEMA, PROCEDURE_NAME
            """;

    private static final String SQL_ACCOUNT_USAGE_FUNCTIONS = """
            SELECT FUNCTION_CATALOG,
                   FUNCTION_SCHEMA,
                   FUNCTION_NAME,
                   ARGUMENT_SIGNATURE,
                   DATA_TYPE,
                   FUNCTION_DEFINITION
              FROM SNOWFLAKE.ACCOUNT_USAGE.FUNCTIONS
             WHERE DELETED IS NULL
             ORDER BY FUNCTION_CATALOG, FUNCTION_SCHEMA, FUNCTION_NAME
            """;

    private static final String SQL_INFO_SCHEMA_FUNCTIONS = """
            SELECT FUNCTION_CATALOG,
                   FUNCTION_SCHEMA,
                   FUNCTION_NAME,
                   ARGUMENT_SIGNATURE,
                   DATA_TYPE,
                   FUNCTION_DEFINITION
              FROM INFORMATION_SCHEMA.FUNCTIONS
             ORDER BY FUNCTION_CATALOG, FUNCTION_SCHEMA, FUNCTION_NAME
            """;

    private static final String SQL_ACCOUNT_USAGE_STAGES = """
            SELECT STAGE_CATALOG, STAGE_SCHEMA, STAGE_NAME, STAGE_URL, STAGE_TYPE
              FROM SNOWFLAKE.ACCOUNT_USAGE.STAGES
             WHERE DELETED IS NULL
            """;

    private static final String SQL_QUERY_HISTORY = """
            SELECT QUERY_ID,
                   QUERY_TEXT,
                   DATABASE_NAME,
                   SCHEMA_NAME,
                   QUERY_TYPE,
                   EXECUTION_STATUS
              FROM SNOWFLAKE.ACCOUNT_USAGE.QUERY_HISTORY
             WHERE EXECUTION_STATUS = 'SUCCESS'
             ORDER BY START_TIME DESC
             FETCH FIRST 5000 ROWS ONLY
            """;

    @Override
    public void harvest(
            Connection conn,
            EDbVendor vendor,
            DataSource dataSource,
            SchemaFilterMatcher filter,
            CatalogBuilder builder,
            int timeoutSeconds) throws SQLException {

        String defaultDb = resolveCurrentDatabase(conn, dataSource != null ? dataSource.getDatabase() : null, "SNOWFLAKE_DB");

        boolean usedAccountUsage = runQuery(conn, SQL_ACCOUNT_USAGE_COLUMNS, timeoutSeconds, builder,
                "SNOWFLAKE.ACCOUNT_USAGE.COLUMNS", false,
                (rs, row) -> processColumnRow(row, defaultDb, filter, builder));
        if (!usedAccountUsage) {
            boolean infoSchemaOk = runQuery(conn, SQL_INFO_SCHEMA_COLUMNS, timeoutSeconds, builder,
                    "INFORMATION_SCHEMA.COLUMNS", false,
                    (rs, row) -> processColumnRow(row, defaultDb, filter, builder));
            if (!infoSchemaOk) {
                runQuery(conn, SQL_ANSI_INFO_SCHEMA_COLUMNS, timeoutSeconds, builder,
                        "INFORMATION_SCHEMA.COLUMNS", true,
                        (rs, row) -> processColumnRow(row, defaultDb, filter, builder));
            }
        }

        runQuery(conn, SQL_INFO_SCHEMA_CONSTRAINTS, timeoutSeconds, builder, "INFORMATION_SCHEMA.TABLE_CONSTRAINTS", false, (rs, row) -> {
            String db = firstNonBlank(row.strTrim("TABLE_CATALOG"), defaultDb);
            String schema = row.strTrim("TABLE_SCHEMA");
            if (schema != null && filter.acceptsSchema(db, schema)) {
                builder.markConstraintColumn(db, schema, row.strTrim("TABLE_NAME"), row.strTrim("COLUMN_NAME"), row.strTrim("CONSTRAINT_TYPE"));
            }
        });

        if (filter.shouldHarvestViews()) {
            boolean viewsOk = runQuery(conn, SQL_ACCOUNT_USAGE_VIEWS, timeoutSeconds, builder, "ACCOUNT_USAGE.VIEWS", false, (rs, row) -> {
                String db = firstNonBlank(row.strTrim("TABLE_CATALOG"), defaultDb);
                String schema = row.strTrim("TABLE_SCHEMA");
                String viewName = row.strTrim("TABLE_NAME");
                boolean mat = Boolean.TRUE.equals(parseNullableFlag(row.strTrim("IS_MATERIALIZED")));
                if (schema != null && viewName != null && filter.acceptsView(db, schema, viewName)) {
                    builder.addViewDefinition(db, schema, viewName, row.str("VIEW_DEFINITION"), mat);
                }
            });
            if (!viewsOk) {
                runQuery(conn, SQL_INFO_SCHEMA_VIEWS, timeoutSeconds, builder, "INFORMATION_SCHEMA.VIEWS", false, (rs, row) -> {
                    String db = firstNonBlank(row.strTrim("TABLE_CATALOG"), defaultDb);
                    String schema = row.strTrim("TABLE_SCHEMA");
                    String viewName = row.strTrim("TABLE_NAME");
                    if (schema != null && viewName != null && filter.acceptsView(db, schema, viewName)) {
                        builder.addViewDefinition(db, schema, viewName, row.str("VIEW_DEFINITION"), false);
                    }
                });
            }
        }

        if (filter.shouldHarvestRoutines()) {
            boolean procsOk = runQuery(conn, SQL_ACCOUNT_USAGE_PROCEDURES, timeoutSeconds, builder, "ACCOUNT_USAGE.PROCEDURES", false,
                    (rs, row) -> processProcedureRow(row, defaultDb, filter, builder));
            if (!procsOk) {
                runQuery(conn, SQL_INFO_SCHEMA_PROCEDURES, timeoutSeconds, builder, "INFORMATION_SCHEMA.PROCEDURES", false,
                        (rs, row) -> processProcedureRow(row, defaultDb, filter, builder));
            }

            boolean funcsOk = runQuery(conn, SQL_ACCOUNT_USAGE_FUNCTIONS, timeoutSeconds, builder, "ACCOUNT_USAGE.FUNCTIONS", false,
                    (rs, row) -> processFunctionRow(row, defaultDb, filter, builder));
            if (!funcsOk) {
                runQuery(conn, SQL_INFO_SCHEMA_FUNCTIONS, timeoutSeconds, builder, "INFORMATION_SCHEMA.FUNCTIONS", false,
                        (rs, row) -> processFunctionRow(row, defaultDb, filter, builder));
            }
        }

        // Stages as <stage> objects in SchemaObject.others
        runQuery(conn, SQL_ACCOUNT_USAGE_STAGES, timeoutSeconds, builder, "ACCOUNT_USAGE.STAGES", false, (rs, row) -> {
            String db = firstNonBlank(row.strTrim("STAGE_CATALOG"), defaultDb);
            String schema = row.strTrim("STAGE_SCHEMA");
            String stageName = row.strTrim("STAGE_NAME");
            if (schema != null && stageName != null && filter.acceptsSchema(db, schema)) {
                DbObject stage = builder.getOrCreateOtherObject(db, schema, "@" + stageName, "stage");
                stage.setLocation(row.strTrim("STAGE_URL"));
            }
        });

        // Query History across all 71 supported Snowflake statement types
        runQuery(conn, SQL_QUERY_HISTORY, timeoutSeconds, builder, "ACCOUNT_USAGE.QUERY_HISTORY", false, (rs, row) -> {
            String qType = row.strTrim("QUERY_TYPE");
            String qText = row.str("QUERY_TEXT");
            if (qText == null || qText.isBlank()) {
                return;
            }
            if (qType != null && !SUPPORTED_71_QUERY_TYPES.contains(qType.toUpperCase(Locale.ROOT))) {
                return;
            }
            String qDb = firstNonBlank(row.strTrim("DATABASE_NAME"), defaultDb);
            String qSchema = firstNonBlank(row.strTrim("SCHEMA_NAME"), "PUBLIC");
            if (!filter.acceptsSchema(qDb, qSchema)) {
                return;
            }
            String qId = firstNonBlank(row.strTrim("QUERY_ID"), "sf_query");
            builder.addQuerySource(qDb, qSchema, qId, "query_history", qText, "query_history/" + qId + ".sql");
        });
    }

    private static void processColumnRow(
            RowAccessor row,
            String defaultDb,
            SchemaFilterMatcher filter,
            CatalogBuilder builder) throws SQLException {
        String db = firstNonBlank(row.strTrim("TABLE_CATALOG"), defaultDb);
        String schema = row.strTrim("TABLE_SCHEMA");
        String table = row.strTrim("TABLE_NAME");
        String colName = row.strTrim("COLUMN_NAME");
        if (schema == null || table == null || colName == null || !filter.acceptsSchema(db, schema)) {
            return;
        }
        String tableType = row.strTrim("TABLE_TYPE");
        boolean isView = tableType != null && tableType.toUpperCase(Locale.ROOT).contains("VIEW");
        if (isView && !filter.acceptsView(db, schema, table)) {
            return;
        }
        DbObject parent = isView
                ? builder.getOrCreateView(db, schema, table)
                : builder.getOrCreateTable(db, schema, table);
        String formattedType = formatPrecisionScale(
                row.strTrim("DATA_TYPE"),
                row.intObj("CHARACTER_MAXIMUM_LENGTH"),
                row.intObj("NUMERIC_PRECISION"),
                row.intObj("NUMERIC_SCALE"));
        Column col = builder.addOrUpdateColumn(
                parent,
                colName,
                formattedType,
                parseNullableFlag(row.strTrim("IS_NULLABLE")),
                row.str("COLUMN_DEFAULT"),
                row.str("COMMENT"));
        if (col != null && Boolean.TRUE.equals(parseNullableFlag(row.strTrim("IS_IDENTITY")))) {
            col.setIdentity(true);
        }
    }

    private static void processProcedureRow(
            RowAccessor row,
            String defaultDb,
            SchemaFilterMatcher filter,
            CatalogBuilder builder) throws SQLException {
        String db = firstNonBlank(row.strTrim("PROCEDURE_CATALOG"), defaultDb);
        String schema = row.strTrim("PROCEDURE_SCHEMA");
        String name = row.strTrim("PROCEDURE_NAME");
        if (schema == null || name == null || !filter.acceptsRoutine(db, schema, name)) {
            return;
        }
        String sig = firstNonBlank(row.strTrim("ARGUMENT_SIGNATURE"), "()");
        String ret = firstNonBlank(row.strTrim("DATA_TYPE"), "VARIANT");
        String body = row.str("PROCEDURE_DEFINITION");
        String ddl = wrapSnowflakeRoutine("PROCEDURE", db, schema, name, sig, ret, body);
        builder.addRoutine(db, schema, null, name, "procedure", ddl);
    }

    private static void processFunctionRow(
            RowAccessor row,
            String defaultDb,
            SchemaFilterMatcher filter,
            CatalogBuilder builder) throws SQLException {
        String db = firstNonBlank(row.strTrim("FUNCTION_CATALOG"), defaultDb);
        String schema = row.strTrim("FUNCTION_SCHEMA");
        String name = row.strTrim("FUNCTION_NAME");
        if (schema == null || name == null || !filter.acceptsRoutine(db, schema, name)) {
            return;
        }
        String sig = firstNonBlank(row.strTrim("ARGUMENT_SIGNATURE"), "()");
        String ret = firstNonBlank(row.strTrim("DATA_TYPE"), "VARIANT");
        String body = row.str("FUNCTION_DEFINITION");
        String ddl = wrapSnowflakeRoutine("FUNCTION", db, schema, name, sig, ret, body);
        builder.addRoutine(db, schema, null, name, "function", ddl);
    }

    private static String wrapSnowflakeRoutine(
            String kind,
            String db,
            String schema,
            String name,
            String signature,
            String returnType,
            String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        if (body.stripLeading().toUpperCase(Locale.ROOT).startsWith("CREATE ")) {
            return body;
        }
        return "CREATE OR REPLACE " + kind + " " + db + "." + schema + "." + name + signature
                + " RETURNS " + returnType + " AS $$\n" + body + "\n$$;";
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }
}
