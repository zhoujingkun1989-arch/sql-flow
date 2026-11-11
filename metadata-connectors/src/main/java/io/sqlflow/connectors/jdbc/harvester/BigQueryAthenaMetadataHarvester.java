package io.sqlflow.connectors.jdbc.harvester;

import io.sqlflow.connectors.jdbc.CatalogBuilder;
import io.sqlflow.connectors.jdbc.SchemaFilterMatcher;
import io.sqlflow.contracts.domain.DataSource;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Google BigQuery ({@code dbvbigquery}) &amp; AWS Athena ({@code dbvathena}) live catalog harvester
 * ({@code 02 §6.3.3 BigQuery}):
 * <ul>
 *   <li>{@code INFORMATION_SCHEMA.COLUMNS} + {@code INFORMATION_SCHEMA.TABLES} ({@code project -> dataset -> table})</li>
 *   <li>{@code INFORMATION_SCHEMA.TABLE_CONSTRAINTS} + {@code KEY_COLUMN_USAGE}</li>
 *   <li>{@code INFORMATION_SCHEMA.VIEWS} &amp; {@code INFORMATION_SCHEMA.ROUTINES}</li>
 *   <li>{@code INFORMATION_SCHEMA.JOBS_BY_PROJECT} query history when accessible</li>
 * </ul>
 */
public final class BigQueryAthenaMetadataHarvester extends AbstractVendorHarvester {

    private static final String SQL_COLUMNS = """
            SELECT c.table_catalog,
                   c.table_schema,
                   c.table_name,
                   t.table_type,
                   c.column_name,
                   c.data_type,
                   c.is_nullable,
                   c.column_default,
                   c.ordinal_position
              FROM INFORMATION_SCHEMA.COLUMNS c
              LEFT JOIN INFORMATION_SCHEMA.TABLES t
                ON t.table_catalog = c.table_catalog
               AND t.table_schema = c.table_schema
               AND t.table_name = c.table_name
             ORDER BY c.table_schema, c.table_name, c.ordinal_position
            """;

    private static final String SQL_CONSTRAINTS = """
            SELECT tc.table_catalog,
                   tc.table_schema,
                   tc.table_name,
                   kcu.column_name,
                   tc.constraint_type
              FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
              JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
                ON tc.constraint_name = kcu.constraint_name
               AND tc.table_schema = kcu.table_schema
               AND tc.table_name = kcu.table_name
            """;

    private static final String SQL_VIEWS = """
            SELECT table_catalog,
                   table_schema,
                   table_name,
                   view_definition
              FROM INFORMATION_SCHEMA.VIEWS
             ORDER BY table_schema, table_name
            """;

    private static final String SQL_ROUTINES = """
            SELECT routine_catalog,
                   routine_schema,
                   routine_name,
                   routine_type,
                   routine_definition,
                   ddl
              FROM INFORMATION_SCHEMA.ROUTINES
             ORDER BY routine_schema, routine_name
            """;

    private static final String SQL_ROUTINES_NO_DDL = """
            SELECT routine_catalog,
                   routine_schema,
                   routine_name,
                   routine_type,
                   routine_definition
              FROM INFORMATION_SCHEMA.ROUTINES
             ORDER BY routine_schema, routine_name
            """;

    private static final String SQL_JOBS = """
            SELECT job_id,
                   project_id,
                   statement_type,
                   query
              FROM INFORMATION_SCHEMA.JOBS_BY_PROJECT
             WHERE state = 'DONE' AND error_result IS NULL
             ORDER BY creation_time DESC
             LIMIT 1000
            """;

    @Override
    public void harvest(
            Connection conn,
            EDbVendor vendor,
            DataSource dataSource,
            SchemaFilterMatcher filter,
            CatalogBuilder builder,
            int timeoutSeconds) throws SQLException {

        String defaultProject = resolveCurrentDatabase(
                conn,
                dataSource != null ? dataSource.getDatabase() : null,
                vendor == EDbVendor.dbvathena ? "AwsDataCatalog" : "default_project");

        runQuery(conn, SQL_COLUMNS, timeoutSeconds, builder, "INFORMATION_SCHEMA.COLUMNS", true, (rs, row) -> {
            String catalog = firstNonBlank(row.strTrim("table_catalog"), defaultProject);
            String dataset = row.strTrim("table_schema");
            String table = row.strTrim("table_name");
            String colName = row.strTrim("column_name");
            if (dataset == null || table == null || colName == null || !filter.acceptsSchema(catalog, dataset)) {
                return;
            }
            String tableType = row.strTrim("table_type");
            boolean isView = tableType != null && tableType.toUpperCase(Locale.ROOT).contains("VIEW");
            if (isView && !filter.acceptsView(catalog, dataset, table)) {
                return;
            }
            DbObject parent = isView
                    ? builder.getOrCreateView(catalog, dataset, table)
                    : builder.getOrCreateTable(catalog, dataset, table);
            builder.addOrUpdateColumn(
                    parent,
                    colName,
                    row.strTrim("data_type"),
                    parseNullableFlag(row.strTrim("is_nullable")),
                    row.strTrim("column_default"),
                    null);
        });

        runQuery(conn, SQL_CONSTRAINTS, timeoutSeconds, builder, "INFORMATION_SCHEMA.TABLE_CONSTRAINTS", false, (rs, row) -> {
            String catalog = firstNonBlank(row.strTrim("table_catalog"), defaultProject);
            String dataset = row.strTrim("table_schema");
            if (dataset != null && filter.acceptsSchema(catalog, dataset)) {
                builder.markConstraintColumn(catalog, dataset, row.strTrim("table_name"), row.strTrim("column_name"), row.strTrim("constraint_type"));
            }
        });

        if (filter.shouldHarvestViews()) {
            runQuery(conn, SQL_VIEWS, timeoutSeconds, builder, "INFORMATION_SCHEMA.VIEWS", false, (rs, row) -> {
                String catalog = firstNonBlank(row.strTrim("table_catalog"), defaultProject);
                String dataset = row.strTrim("table_schema");
                String viewName = row.strTrim("table_name");
                if (dataset != null && viewName != null && filter.acceptsView(catalog, dataset, viewName)) {
                    builder.addViewDefinition(catalog, dataset, viewName, row.str("view_definition"), false);
                }
            });
        }

        if (filter.shouldHarvestRoutines()) {
            boolean okWithDdl = runQuery(conn, SQL_ROUTINES, timeoutSeconds, builder, "INFORMATION_SCHEMA.ROUTINES", false,
                    (rs, row) -> processRoutineRow(row, defaultProject, filter, builder));
            if (!okWithDdl) {
                runQuery(conn, SQL_ROUTINES_NO_DDL, timeoutSeconds, builder, "INFORMATION_SCHEMA.ROUTINES(fallback)", false,
                        (rs, row) -> processRoutineRow(row, defaultProject, filter, builder));
            }
        }

        if (vendor == EDbVendor.dbvbigquery) {
            runQuery(conn, SQL_JOBS, timeoutSeconds, builder, "INFORMATION_SCHEMA.JOBS_BY_PROJECT", false, (rs, row) -> {
                String query = row.str("query");
                if (query != null && !query.isBlank()) {
                    String proj = firstNonBlank(row.strTrim("project_id"), defaultProject);
                    String jobId = firstNonBlank(row.strTrim("job_id"), "bq_job");
                    builder.addQuerySource(proj, "default", jobId, "query_history", query, "jobs/" + jobId + ".sql");
                }
            });
        }
    }

    private static void processRoutineRow(
            RowAccessor row,
            String defaultProject,
            SchemaFilterMatcher filter,
            CatalogBuilder builder) throws SQLException {
        String catalog = firstNonBlank(row.strTrim("routine_catalog"), defaultProject);
        String dataset = row.strTrim("routine_schema");
        String name = row.strTrim("routine_name");
        if (dataset == null || name == null || !filter.acceptsRoutine(catalog, dataset, name)) {
            return;
        }
        String rtype = row.strTrim("routine_type");
        String ddl = row.str("ddl");
        if (ddl == null || ddl.isBlank()) {
            String body = row.str("routine_definition");
            if (body != null && !body.isBlank()) {
                boolean isFunc = rtype != null && rtype.toUpperCase(Locale.ROOT).contains("FUNC");
                ddl = isFunc
                        ? "CREATE OR REPLACE FUNCTION `" + catalog + "." + dataset + "." + name + "`() AS (" + body + ");"
                        : "CREATE OR REPLACE PROCEDURE `" + catalog + "." + dataset + "." + name + "`()\nBEGIN\n" + body + "\nEND;";
            }
        }
        builder.addRoutine(catalog, dataset, null, name, rtype, ddl);
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }
}
