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

/**
 * MySQL ({@code dbvmysql}), MariaDB, TiDB, Doris ({@code dbvdoris}), StarRocks ({@code dbvstarrocks}),
 * and OceanBase ({@code dbvoceanbase}) catalog harvester ({@code 02 §6.3.3 MySQL}).
 *
 * <p>MySQL uses catalog-only topology ({@code supportsCatalogs = true, supportsSchemas = false}) where
 * {@code TABLE_SCHEMA} in {@code INFORMATION_SCHEMA} maps to {@code DatabaseObject.name}.
 */
public final class MySqlMetadataHarvester extends AbstractVendorHarvester {

    private static final String SQL_COLUMNS = """
            SELECT c.TABLE_SCHEMA,
                   c.TABLE_NAME,
                   t.TABLE_TYPE,
                   c.COLUMN_NAME,
                   c.COLUMN_TYPE,
                   c.DATA_TYPE,
                   c.CHARACTER_MAXIMUM_LENGTH,
                   c.NUMERIC_PRECISION,
                   c.NUMERIC_SCALE,
                   c.IS_NULLABLE,
                   c.COLUMN_DEFAULT,
                   c.COLUMN_KEY,
                   c.EXTRA,
                   c.COLUMN_COMMENT,
                   c.ORDINAL_POSITION
              FROM INFORMATION_SCHEMA.COLUMNS c
              LEFT JOIN INFORMATION_SCHEMA.TABLES t
                ON t.TABLE_SCHEMA = c.TABLE_SCHEMA
               AND t.TABLE_NAME = c.TABLE_NAME
             ORDER BY c.TABLE_SCHEMA, c.TABLE_NAME, c.ORDINAL_POSITION
            """;

    private static final String SQL_ANSI_COLUMNS = """
            SELECT c.TABLE_SCHEMA,
                   c.TABLE_NAME,
                   t.TABLE_TYPE,
                   c.COLUMN_NAME,
                   c.DATA_TYPE,
                   c.CHARACTER_MAXIMUM_LENGTH,
                   c.NUMERIC_PRECISION,
                   c.NUMERIC_SCALE,
                   c.IS_NULLABLE,
                   c.COLUMN_DEFAULT,
                   c.REMARKS AS COLUMN_COMMENT,
                   c.ORDINAL_POSITION
              FROM INFORMATION_SCHEMA.COLUMNS c
              LEFT JOIN INFORMATION_SCHEMA.TABLES t
                ON t.TABLE_SCHEMA = c.TABLE_SCHEMA
               AND t.TABLE_NAME = c.TABLE_NAME
             ORDER BY c.TABLE_SCHEMA, c.TABLE_NAME, c.ORDINAL_POSITION
            """;

    private static final String SQL_KEY_COLUMN_USAGE = """
            SELECT TABLE_SCHEMA,
                   TABLE_NAME,
                   COLUMN_NAME,
                   CONSTRAINT_NAME,
                   REFERENCED_TABLE_NAME
              FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
             ORDER BY TABLE_SCHEMA, TABLE_NAME, ORDINAL_POSITION
            """;

    private static final String SQL_ANSI_TABLE_CONSTRAINTS = """
            SELECT tc.TABLE_SCHEMA,
                   tc.TABLE_NAME,
                   kcu.COLUMN_NAME,
                   tc.CONSTRAINT_TYPE
              FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
              JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
                ON tc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME
               AND tc.TABLE_SCHEMA = kcu.TABLE_SCHEMA
               AND tc.TABLE_NAME = kcu.TABLE_NAME
            """;

    private static final String SQL_VIEWS = """
            SELECT TABLE_SCHEMA, TABLE_NAME, VIEW_DEFINITION
              FROM INFORMATION_SCHEMA.VIEWS
             ORDER BY TABLE_SCHEMA, TABLE_NAME
            """;

    private static final String SQL_ROUTINES = """
            SELECT ROUTINE_SCHEMA,
                   ROUTINE_NAME,
                   ROUTINE_TYPE,
                   DTD_IDENTIFIER,
                   ROUTINE_DEFINITION
              FROM INFORMATION_SCHEMA.ROUTINES
             ORDER BY ROUTINE_SCHEMA, ROUTINE_NAME
            """;

    private static final String SQL_PARAMETERS = """
            SELECT SPECIFIC_SCHEMA,
                   SPECIFIC_NAME,
                   PARAMETER_NAME,
                   DTD_IDENTIFIER,
                   DATA_TYPE,
                   PARAMETER_MODE,
                   ORDINAL_POSITION
              FROM INFORMATION_SCHEMA.PARAMETERS
             ORDER BY SPECIFIC_SCHEMA, SPECIFIC_NAME, ORDINAL_POSITION
            """;

    private static final String SQL_TRIGGERS = """
            SELECT TRIGGER_SCHEMA,
                   TRIGGER_NAME,
                   ACTION_TIMING,
                   EVENT_MANIPULATION,
                   EVENT_OBJECT_TABLE,
                   ACTION_STATEMENT
              FROM INFORMATION_SCHEMA.TRIGGERS
             ORDER BY TRIGGER_SCHEMA, TRIGGER_NAME
            """;

    @Override
    public void harvest(
            Connection conn,
            EDbVendor vendor,
            DataSource dataSource,
            SchemaFilterMatcher filter,
            CatalogBuilder builder,
            int timeoutSeconds) throws SQLException {

        RowConsumer colConsumer = (rs, row) -> {
            String db = row.strTrim("TABLE_SCHEMA");
            String table = row.strTrim("TABLE_NAME");
            String colName = row.strTrim("COLUMN_NAME");
            if (db == null || table == null || colName == null || !filter.acceptsSchema(db, null)) {
                return;
            }
            String tableType = row.strTrim("TABLE_TYPE");
            boolean isView = tableType != null && tableType.toUpperCase(Locale.ROOT).contains("VIEW");
            if (isView && !filter.acceptsView(db, null, table)) {
                return;
            }
            DbObject parent = isView
                    ? builder.getOrCreateView(db, null, table)
                    : builder.getOrCreateTable(db, null, table);

            String colType = row.strTrim("COLUMN_TYPE");
            if (colType == null || colType.isBlank()) {
                colType = formatPrecisionScale(
                        row.strTrim("DATA_TYPE"),
                        row.intObj("CHARACTER_MAXIMUM_LENGTH"),
                        row.intObj("NUMERIC_PRECISION"),
                        row.intObj("NUMERIC_SCALE"));
            }
            Column col = builder.addOrUpdateColumn(
                    parent,
                    colName,
                    colType,
                    parseNullableFlag(row.strTrim("IS_NULLABLE")),
                    row.str("COLUMN_DEFAULT"),
                    row.str("COLUMN_COMMENT"));
            if (col != null) {
                String key = row.strTrim("COLUMN_KEY");
                if ("PRI".equalsIgnoreCase(key)) {
                    col.setPrimaryKey(true);
                } else if ("UNI".equalsIgnoreCase(key)) {
                    col.setUnqiueKey(true);
                } else if ("MUL".equalsIgnoreCase(key)) {
                    col.setIndexKey(true);
                }
                String extra = row.strTrim("EXTRA");
                if (extra != null) {
                    String lowerExtra = extra.toLowerCase(Locale.ROOT);
                    if (lowerExtra.contains("auto_increment")) {
                        col.setIdentity(true);
                    }
                    if (lowerExtra.contains("generated")) {
                        col.setComputed(true);
                    }
                }
            }
        };

        boolean mysqlColsOk = runQuery(conn, SQL_COLUMNS, timeoutSeconds, builder, "INFORMATION_SCHEMA.COLUMNS", false, colConsumer);
        if (!mysqlColsOk) {
            runQuery(conn, SQL_ANSI_COLUMNS, timeoutSeconds, builder, "INFORMATION_SCHEMA.COLUMNS", true, colConsumer);
        }

        boolean mysqlKeyUsageOk = runQuery(conn, SQL_KEY_COLUMN_USAGE, timeoutSeconds, builder, "INFORMATION_SCHEMA.KEY_COLUMN_USAGE", false, (rs, row) -> {
            String db = row.strTrim("TABLE_SCHEMA");
            String table = row.strTrim("TABLE_NAME");
            String col = row.strTrim("COLUMN_NAME");
            String constraint = row.strTrim("CONSTRAINT_NAME");
            String refTable = row.strTrim("REFERENCED_TABLE_NAME");
            if (db == null || !filter.acceptsSchema(db, null)) {
                return;
            }
            if ("PRIMARY".equalsIgnoreCase(constraint)) {
                builder.markConstraintColumn(db, null, table, col, "P");
            } else if (refTable != null && !refTable.isBlank()) {
                builder.markConstraintColumn(db, null, table, col, "F");
            } else if (constraint != null) {
                builder.markConstraintColumn(db, null, table, col, "U");
            }
        });
        if (!mysqlKeyUsageOk) {
            runQuery(conn, SQL_ANSI_TABLE_CONSTRAINTS, timeoutSeconds, builder, "INFORMATION_SCHEMA.TABLE_CONSTRAINTS", false, (rs, row) -> {
                String db = row.strTrim("TABLE_SCHEMA");
                if (db != null && filter.acceptsSchema(db, null)) {
                    builder.markConstraintColumn(db, null, row.strTrim("TABLE_NAME"), row.strTrim("COLUMN_NAME"), row.strTrim("CONSTRAINT_TYPE"));
                }
            });
        }

        if (filter.shouldHarvestViews()) {
            runQuery(conn, SQL_VIEWS, timeoutSeconds, builder, "INFORMATION_SCHEMA.VIEWS", false, (rs, row) -> {
                String db = row.strTrim("TABLE_SCHEMA");
                String viewName = row.strTrim("TABLE_NAME");
                String def = row.str("VIEW_DEFINITION");
                if (db != null && viewName != null && filter.acceptsView(db, null, viewName)) {
                    builder.addViewDefinition(db, null, viewName, def, false);
                }
            });
        }

        if (filter.shouldHarvestRoutines()) {
            runQuery(conn, SQL_ROUTINES, timeoutSeconds, builder, "INFORMATION_SCHEMA.ROUTINES", false, (rs, row) -> {
                String db = row.strTrim("ROUTINE_SCHEMA");
                String name = row.strTrim("ROUTINE_NAME");
                String type = row.strTrim("ROUTINE_TYPE");
                String def = row.str("ROUTINE_DEFINITION");
                String retType = row.strTrim("DTD_IDENTIFIER");
                if (db != null && name != null && filter.acceptsRoutine(db, null, name)) {
                    String ddl = wrapMySqlRoutine(db, name, type, retType, def);
                    builder.addRoutine(db, null, null, name, type, ddl);
                }
            });

            runQuery(conn, SQL_PARAMETERS, timeoutSeconds, builder, "INFORMATION_SCHEMA.PARAMETERS", false, (rs, row) -> {
                String db = row.strTrim("SPECIFIC_SCHEMA");
                String name = row.strTrim("SPECIFIC_NAME");
                if (db != null && name != null && filter.acceptsRoutine(db, null, name)) {
                    String dtype = row.strTrim("DTD_IDENTIFIER");
                    if (dtype == null || dtype.isBlank()) {
                        dtype = row.strTrim("DATA_TYPE");
                    }
                    Integer pos = row.intObj("ORDINAL_POSITION");
                    String mode = (pos != null && pos == 0) ? "RETURN" : row.strTrim("PARAMETER_MODE");
                    builder.addRoutineArgument(db, null, null, name, row.strTrim("PARAMETER_NAME"), dtype, mode);
                }
            });

            runQuery(conn, SQL_TRIGGERS, timeoutSeconds, builder, "INFORMATION_SCHEMA.TRIGGERS", false, (rs, row) -> {
                String db = row.strTrim("TRIGGER_SCHEMA");
                String name = row.strTrim("TRIGGER_NAME");
                if (db != null && name != null && filter.acceptsRoutine(db, null, name)) {
                    String stmt = row.str("ACTION_STATEMENT");
                    String ddl = stmt != null && stmt.stripLeading().toUpperCase(Locale.ROOT).startsWith("CREATE ")
                            ? stmt
                            : "CREATE TRIGGER `" + db + "`.`" + name + "` "
                                    + (row.strTrim("ACTION_TIMING") != null ? row.strTrim("ACTION_TIMING") : "AFTER") + " "
                                    + (row.strTrim("EVENT_MANIPULATION") != null ? row.strTrim("EVENT_MANIPULATION") : "INSERT")
                                    + " ON `" + db + "`.`" + row.strTrim("EVENT_OBJECT_TABLE") + "` FOR EACH ROW "
                                    + (stmt != null ? stmt : "");
                    builder.addRoutine(db, null, null, name, "trigger", ddl);
                }
            });
        }
    }

    private static String wrapMySqlRoutine(String db, String name, String type, String returnType, String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String upper = body.stripLeading().toUpperCase(Locale.ROOT);
        if (upper.startsWith("CREATE ")) {
            return body;
        }
        if (type != null && type.equalsIgnoreCase("FUNCTION")) {
            String ret = (returnType != null && !returnType.isBlank()) ? returnType : "INT";
            return "CREATE FUNCTION `" + db + "`.`" + name + "`() RETURNS " + ret + "\n" + body;
        }
        return "CREATE PROCEDURE `" + db + "`.`" + name + "`()\n" + body;
    }
}
