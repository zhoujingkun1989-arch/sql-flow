package io.sqlflow.connectors.jdbc.harvester;

import io.sqlflow.connectors.jdbc.CatalogBuilder;
import io.sqlflow.connectors.jdbc.SchemaFilterMatcher;
import io.sqlflow.contracts.domain.DataSource;
import io.sqlflow.contracts.lineage.Column;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Local &amp; Embedded JDBC Harvester supporting SQLite ({@code dbvsqlite}), DuckDB ({@code dbvduckdb}),
 * H2, and generic JDBC/ODBC ({@code dbvodbc}) data sources via {@code sqlite_master}/{@code PRAGMA}s
 * and standard {@link DatabaseMetaData} + {@code information_schema}.
 */
public final class EmbeddedJdbcMetadataHarvester extends AbstractVendorHarvester {

    @Override
    public void harvest(
            Connection conn,
            EDbVendor vendor,
            DataSource dataSource,
            SchemaFilterMatcher filter,
            CatalogBuilder builder,
            int timeoutSeconds) throws SQLException {

        String url = conn.getMetaData().getURL();
        boolean isSqlite = vendor == EDbVendor.dbvsqlite
                || (url != null && url.toLowerCase(Locale.ROOT).startsWith("jdbc:sqlite:"));
        boolean isDuckDb = vendor == EDbVendor.dbvduckdb
                || (url != null && url.toLowerCase(Locale.ROOT).startsWith("jdbc:duckdb:"));

        if (isSqlite) {
            harvestSqlite(conn, dataSource, filter, builder, timeoutSeconds);
        } else {
            harvestGenericJdbcMetaData(conn, isDuckDb, dataSource, filter, builder, timeoutSeconds);
        }
    }

    private void harvestSqlite(
            Connection conn,
            DataSource dataSource,
            SchemaFilterMatcher filter,
            CatalogBuilder builder,
            int timeoutSeconds) {
        String db = resolveCurrentDatabase(conn, dataSource != null ? dataSource.getDatabase() : null, "main");
        String schema = "main";
        if (!filter.acceptsSchema(db, schema)) {
            return;
        }

        record MasterEntry(String type, String name, String sql) {
        }
        List<MasterEntry> entries = new ArrayList<>();
        runQuery(conn, """
                SELECT type, name, sql
                  FROM sqlite_master
                 WHERE type IN ('table', 'view', 'trigger')
                   AND name NOT LIKE 'sqlite_%'
                 ORDER BY type, name
                """, timeoutSeconds, builder, "sqlite_master", true, (rs, row) -> {
            String t = row.strTrim("type");
            String n = row.strTrim("name");
            if (t != null && n != null) {
                entries.add(new MasterEntry(t.toLowerCase(Locale.ROOT), n, row.str("sql")));
            }
        });

        for (MasterEntry entry : entries) {
            if ("trigger".equals(entry.type)) {
                if (filter.acceptsRoutine(db, schema, entry.name)) {
                    builder.addRoutine(db, schema, null, entry.name, "trigger", entry.sql);
                }
                continue;
            }
            boolean isView = "view".equals(entry.type);
            if (isView && !filter.acceptsView(db, schema, entry.name)) {
                continue;
            }
            DbObject parent = isView
                    ? builder.getOrCreateView(db, schema, entry.name)
                    : builder.getOrCreateTable(db, schema, entry.name);

            String escapedName = entry.name.replace("\"", "\"\"");
            runQuery(conn, "PRAGMA table_info(\"" + escapedName + "\")", timeoutSeconds, builder,
                    "PRAGMA table_info(" + entry.name + ")", false, (rs, row) -> {
                        String colName = row.strTrim("name");
                        String colType = row.strTrim("type");
                        Integer notNull = row.intObj("notnull");
                        Integer pk = row.intObj("pk");
                        String dflt = row.strTrim("dflt_value");
                        Column col = builder.addOrUpdateColumn(
                                parent,
                                colName,
                                (colType == null || colType.isBlank()) ? "TEXT" : colType,
                                notNull != null ? notNull == 0 : true,
                                dflt,
                                null);
                        if (col != null && pk != null && pk > 0) {
                            col.setPrimaryKey(true);
                        }
                    });

            if (!isView) {
                runQuery(conn, "PRAGMA foreign_key_list(\"" + escapedName + "\")", timeoutSeconds, builder,
                        "PRAGMA foreign_key_list", false, (rs, row) ->
                                builder.markConstraintColumn(db, schema, entry.name, row.strTrim("from"), "F"));

                List<String> uniqueIdxNames = new ArrayList<>();
                runQuery(conn, "PRAGMA index_list(\"" + escapedName + "\")", timeoutSeconds, builder,
                        "PRAGMA index_list", false, (rs, row) -> {
                            String idxName = row.strTrim("name");
                            Integer unique = row.intObj("unique");
                            String origin = row.strTrim("origin");
                            if (idxName != null && unique != null && unique == 1 && !"pk".equalsIgnoreCase(origin)) {
                                uniqueIdxNames.add(idxName);
                            }
                        });
                for (String idxName : uniqueIdxNames) {
                    runQuery(conn, "PRAGMA index_info(\"" + idxName.replace("\"", "\"\"") + "\")", timeoutSeconds, builder,
                            "PRAGMA index_info", false, (rs, row) ->
                                    builder.markConstraintColumn(db, schema, entry.name, row.strTrim("name"), "U"));
                }
            } else if (filter.shouldHarvestViews() && entry.sql != null && !entry.sql.isBlank()) {
                builder.addViewDefinition(db, schema, entry.name, entry.sql, false);
            }
        }
    }

    private void harvestGenericJdbcMetaData(
            Connection conn,
            boolean isDuckDb,
            DataSource dataSource,
            SchemaFilterMatcher filter,
            CatalogBuilder builder,
            int timeoutSeconds) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        String defaultDb = resolveCurrentDatabase(conn, dataSource != null ? dataSource.getDatabase() : null, "main");

        try (ResultSet tablesRs = meta.getTables(null, null, "%", new String[]{"TABLE", "BASE TABLE", "VIEW", "MATERIALIZED VIEW"})) {
            while (tablesRs.next()) {
                String cat = firstNonBlank(tablesRs.getString("TABLE_CAT"), defaultDb);
                String sch = firstNonBlank(tablesRs.getString("TABLE_SCHEM"), "main");
                String tblName = tablesRs.getString("TABLE_NAME");
                String tblType = tablesRs.getString("TABLE_TYPE");
                if (tblName == null || !filter.acceptsSchema(cat, sch) || (isDuckDb && isDuckDbSystemView(tblName))) {
                    continue;
                }
                boolean isView = tblType != null && tblType.toUpperCase(Locale.ROOT).contains("VIEW");
                if (isView && !filter.acceptsView(cat, sch, tblName)) {
                    continue;
                }
                DbObject obj = isView
                        ? builder.getOrCreateView(cat, sch, tblName)
                        : builder.getOrCreateTable(cat, sch, tblName);

                try (ResultSet colRs = meta.getColumns(tablesRs.getString("TABLE_CAT"), tablesRs.getString("TABLE_SCHEM"), tblName, "%")) {
                    while (colRs.next()) {
                        String colName = colRs.getString("COLUMN_NAME");
                        String typeName = colRs.getString("TYPE_NAME");
                        int size = colRs.getInt("COLUMN_SIZE");
                        int scale = colRs.getInt("DECIMAL_DIGITS");
                        String isNullable = colRs.getString("IS_NULLABLE");
                        String defVal = colRs.getString("COLUMN_DEF");
                        String remarks = colRs.getString("REMARKS");
                        String formatted = formatPrecisionScale(typeName, size > 0 ? size : null, size > 0 ? size : null, scale > 0 ? scale : null);
                        Column col = builder.addOrUpdateColumn(
                                obj,
                                colName,
                                formatted,
                                parseNullableFlag(isNullable),
                                defVal,
                                remarks);
                        String isAuto = safeGetColumn(colRs, "IS_AUTOINCREMENT");
                        if (col != null && "YES".equalsIgnoreCase(isAuto)) {
                            col.setIdentity(true);
                        }
                    }
                }

                if (!isView) {
                    try (ResultSet pkRs = meta.getPrimaryKeys(tablesRs.getString("TABLE_CAT"), tablesRs.getString("TABLE_SCHEM"), tblName)) {
                        while (pkRs.next()) {
                            builder.markConstraintColumn(cat, sch, tblName, pkRs.getString("COLUMN_NAME"), "P");
                        }
                    } catch (SQLException ignored) {
                    }
                    try (ResultSet fkRs = meta.getImportedKeys(tablesRs.getString("TABLE_CAT"), tablesRs.getString("TABLE_SCHEM"), tblName)) {
                        while (fkRs.next()) {
                            builder.markConstraintColumn(cat, sch, tblName, fkRs.getString("FKCOLUMN_NAME"), "F");
                        }
                    } catch (SQLException ignored) {
                    }
                }
            }
        }

        if (filter.shouldHarvestViews()) {
            boolean duckViewsOk = false;
            if (isDuckDb) {
                duckViewsOk = runQuery(conn,
                        "SELECT database_name AS table_catalog, schema_name AS table_schema, view_name AS table_name, sql AS view_definition FROM duckdb_views() WHERE internal = false",
                        timeoutSeconds, builder, "duckdb_views()", false, (rs, row) -> {
                            String cat = firstNonBlank(row.strTrim("table_catalog"), defaultDb);
                            String sch = firstNonBlank(row.strTrim("table_schema"), "main");
                            String vName = row.strTrim("table_name");
                            if (vName != null && !isDuckDbSystemView(vName) && filter.acceptsView(cat, sch, vName)) {
                                builder.addViewDefinition(cat, sch, vName, row.str("view_definition"), false);
                            }
                        });
            }
            if (!duckViewsOk) {
                runQuery(conn, "SELECT table_catalog, table_schema, table_name, view_definition FROM information_schema.views",
                        timeoutSeconds, builder, "information_schema.views", false, (rs, row) -> {
                            String cat = firstNonBlank(row.strTrim("table_catalog"), defaultDb);
                            String sch = firstNonBlank(row.strTrim("table_schema"), "main");
                            String vName = row.strTrim("table_name");
                            if (vName != null && !(isDuckDb && isDuckDbSystemView(vName)) && filter.acceptsView(cat, sch, vName)) {
                                builder.addViewDefinition(cat, sch, vName, row.str("view_definition"), false);
                            }
                        });
            }
        }
    }

    private static boolean isDuckDbSystemView(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("duckdb_") || lower.startsWith("pragma_") || lower.startsWith("sqlite_");
    }

    private static String safeGetColumn(ResultSet rs, String colLabel) {
        try {
            return rs.getString(colLabel);
        } catch (SQLException e) {
            return null;
        }
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }
}
