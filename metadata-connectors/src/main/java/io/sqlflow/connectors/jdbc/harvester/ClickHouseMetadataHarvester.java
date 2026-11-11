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
 * ClickHouse ({@code dbvclickhouse}) live catalog &amp; query log harvester ({@code 02 §6.3.3 ClickHouse}):
 * <ul>
 *   <li>{@code system.columns} joined with {@code system.tables} ({@code engine}, {@code create_table_query}, {@code as_select})</li>
 *   <li>{@code is_in_primary_key} &amp; {@code is_in_sorting_key} flags</li>
 *   <li>{@code system.query_log} ({@code type = 'QueryFinish'}, {@code is_initial_query = 1})</li>
 * </ul>
 */
public final class ClickHouseMetadataHarvester extends AbstractVendorHarvester {

    private static final String SQL_COLUMNS = """
            SELECT c.database,
                   c.table,
                   t.engine,
                   c.name AS column_name,
                   c.type AS data_type,
                   c.default_kind,
                   c.default_expression,
                   c.comment,
                   c.is_in_primary_key,
                   c.is_in_sorting_key,
                   c.position
              FROM system.columns c
              LEFT JOIN system.tables t
                ON t.database = c.database AND t.name = c.table
             WHERE (t.is_temporary IS NULL OR t.is_temporary = 0)
             ORDER BY c.database, c.table, c.position
            """;

    private static final String SQL_COLUMNS_QUOTED = """
            SELECT c.database,
                   c."table" AS "table",
                   t.engine,
                   c.name AS column_name,
                   c.type AS data_type,
                   c.default_kind,
                   c.default_expression,
                   c.comment,
                   c.is_in_primary_key,
                   c.is_in_sorting_key,
                   c.position
              FROM system.columns c
              LEFT JOIN system.tables t
                ON t.database = c.database AND t.name = c."table"
             WHERE (t.is_temporary IS NULL OR t.is_temporary = 0)
             ORDER BY c.database, c."table", c.position
            """;

    private static final String SQL_VIEWS = """
            SELECT database,
                   name AS view_name,
                   engine,
                   create_table_query,
                   as_select
              FROM system.tables
             WHERE engine IN ('View', 'MaterializedView', 'LiveView', 'WindowView')
             ORDER BY database, name
            """;

    private static final String SQL_QUERY_LOG = """
            SELECT query_id,
                   current_database,
                   query_kind,
                   query
              FROM system.query_log
             WHERE type = 'QueryFinish'
               AND (is_initial_query IS NULL OR is_initial_query = 1)
               AND query_kind IN ('Insert', 'Create', 'Select', 'Alter')
             ORDER BY event_time DESC
             LIMIT 2000
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
            String db = row.strTrim("database");
            String table = row.strTrim("table");
            String colName = row.strTrim("column_name");
            if (db == null || table == null || colName == null || table.startsWith(".inner") || !filter.acceptsSchema(db, null)) {
                return;
            }
            String engine = row.strTrim("engine");
            boolean isView = engine != null && engine.toLowerCase(Locale.ROOT).contains("view");
            if (isView && !filter.acceptsView(db, null, table)) {
                return;
            }
            DbObject parent = isView
                    ? builder.getOrCreateView(db, null, table)
                    : builder.getOrCreateTable(db, null, table);

            String rawType = row.strTrim("data_type");
            boolean nullable = rawType != null && rawType.startsWith("Nullable(");
            Column col = builder.addOrUpdateColumn(
                    parent,
                    colName,
                    rawType,
                    nullable,
                    row.strTrim("default_expression"),
                    row.str("comment"));
            if (col != null) {
                if (Boolean.TRUE.equals(row.boolObj("is_in_primary_key"))) {
                    col.setPrimaryKey(true);
                } else if (Boolean.TRUE.equals(row.boolObj("is_in_sorting_key"))) {
                    col.setIndexKey(true);
                }
                String defKind = row.strTrim("default_kind");
                if ("MATERIALIZED".equalsIgnoreCase(defKind) || "ALIAS".equalsIgnoreCase(defKind)) {
                    col.setComputed(true);
                    col.setComputedDefinition(row.strTrim("default_expression"));
                }
            }
        };

        boolean chColsOk = runQuery(conn, SQL_COLUMNS, timeoutSeconds, builder, "system.columns", false, colConsumer);
        if (!chColsOk) {
            runQuery(conn, SQL_COLUMNS_QUOTED, timeoutSeconds, builder, "system.columns", true, colConsumer);
        }

        if (filter.shouldHarvestViews()) {
            runQuery(conn, SQL_VIEWS, timeoutSeconds, builder, "system.tables(views)", false, (rs, row) -> {
                String db = row.strTrim("database");
                String viewName = row.strTrim("view_name");
                String engine = row.strTrim("engine");
                if (db == null || viewName == null || !filter.acceptsView(db, null, viewName)) {
                    return;
                }
                String ddl = row.str("create_table_query");
                if (ddl == null || ddl.isBlank()) {
                    ddl = row.str("as_select");
                }
                boolean mat = "MaterializedView".equalsIgnoreCase(engine);
                builder.addViewDefinition(db, null, viewName, ddl, mat);
            });
        }

        runQuery(conn, SQL_QUERY_LOG, timeoutSeconds, builder, "system.query_log", false, (rs, row) -> {
            String db = row.strTrim("current_database");
            String queryId = row.strTrim("query_id");
            String sql = row.str("query");
            if (sql != null && !sql.isBlank() && (db == null || filter.acceptsSchema(db, null))) {
                String lower = sql.toLowerCase(Locale.ROOT);
                if (!lower.contains("system.columns") && !lower.contains("system.tables") && !lower.contains("system.query_log")) {
                    String effectiveDb = (db == null || db.isBlank()) ? "default" : db;
                    String id = (queryId == null || queryId.isBlank()) ? "ch_query" : queryId;
                    builder.addQuerySource(effectiveDb, null, id, "query_history", sql, "query_log/" + id + ".sql");
                }
            }
        });
    }
}
