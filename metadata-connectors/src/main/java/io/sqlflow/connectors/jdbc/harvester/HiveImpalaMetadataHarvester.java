package io.sqlflow.connectors.jdbc.harvester;

import io.sqlflow.connectors.jdbc.CatalogBuilder;
import io.sqlflow.connectors.jdbc.SchemaFilterMatcher;
import io.sqlflow.contracts.domain.DataSource;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Apache Hive ({@code dbvhive}) &amp; Apache Impala ({@code dbvimpala}) metadata harvester ({@code 02 §6.3.3}):
 * <ul>
 *   <li>Executes {@code SHOW DATABASES}, {@code SHOW TABLES IN `db`}, {@code SHOW VIEWS IN `db`}</li>
 *   <li>Executes {@code DESCRIBE FORMATTED `db`.`table`} (falling back to {@code DESCRIBE `db`.`table`}) to harvest
 *       columns, partition columns, table type ({@code MANAGED_TABLE}, {@code EXTERNAL_TABLE}, {@code VIRTUAL_VIEW}),
 *       HDFS/S3 {@code Location}, and {@code View Expanded Text}</li>
 *   <li>Executes {@code SHOW CREATE TABLE `db`.`table`} for view/table DDL</li>
 *   <li>Includes {@code INFORMATION_SCHEMA} SQL fallback for Hive 3+ HMS SQL catalogs and embedded catalog testing</li>
 * </ul>
 */
public final class HiveImpalaMetadataHarvester extends AbstractVendorHarvester {

    private static final String SQL_INFO_SCHEMA_COLUMNS = """
            SELECT c.TABLE_SCHEMA,
                   c.TABLE_NAME,
                   t.TABLE_TYPE,
                   c.COLUMN_NAME,
                   c.DATA_TYPE,
                   c.IS_NULLABLE,
                   c.COLUMN_DEFAULT,
                   c.ORDINAL_POSITION
              FROM INFORMATION_SCHEMA.COLUMNS c
              LEFT JOIN INFORMATION_SCHEMA.TABLES t
                ON t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME
             ORDER BY c.TABLE_SCHEMA, c.TABLE_NAME, c.ORDINAL_POSITION
            """;

    private static final String SQL_INFO_SCHEMA_VIEWS = """
            SELECT TABLE_SCHEMA, TABLE_NAME, VIEW_DEFINITION
              FROM INFORMATION_SCHEMA.VIEWS
             ORDER BY TABLE_SCHEMA, TABLE_NAME
            """;

    @Override
    public void harvest(
            Connection conn,
            EDbVendor vendor,
            DataSource dataSource,
            SchemaFilterMatcher filter,
            CatalogBuilder builder,
            int timeoutSeconds) throws SQLException {

        List<String> databases = new ArrayList<>();
        boolean showDbsOk = runQuery(conn, "SHOW DATABASES", timeoutSeconds, builder, "SHOW DATABASES", false, (rs, row) -> {
            String db = firstNonBlank(row.strTrim("database_name"), firstNonBlank(row.strTrim("name"), row.strAt(1)));
            if (db != null && !db.isBlank() && filter.acceptsSchema(db.trim(), null)) {
                databases.add(db.trim());
            }
        });

        if (!showDbsOk || isEmbeddedSqlCatalog(conn)) {
            harvestViaInformationSchema(conn, filter, builder, timeoutSeconds);
            return;
        }

        if (databases.isEmpty()) {
            String fallbackDb = resolveCurrentDatabase(conn, dataSource != null ? dataSource.getDatabase() : null, "default");
            if (filter.acceptsSchema(fallbackDb, null)) {
                databases.add(fallbackDb);
            }
        }

        for (String db : databases) {
            String quotedDb = quoteBacktick(db);
            Set<String> knownViews = new LinkedHashSet<>();
            runQuery(conn, "SHOW VIEWS IN " + quotedDb, timeoutSeconds, builder, "SHOW VIEWS IN " + db, false, (rs, row) -> {
                String v = firstNonBlank(row.strTrim("tab_name"), firstNonBlank(row.strTrim("name"), row.strAt(1)));
                if (v != null && !v.isBlank()) {
                    knownViews.add(v.trim().toLowerCase(Locale.ROOT));
                }
            });

            List<String> tables = new ArrayList<>();
            runQuery(conn, "SHOW TABLES IN " + quotedDb, timeoutSeconds, builder, "SHOW TABLES IN " + db, true, (rs, row) -> {
                String t = firstNonBlank(row.strTrim("tab_name"), firstNonBlank(row.strTrim("name"), row.strAt(1)));
                if (t != null && !t.isBlank()) {
                    tables.add(t.trim());
                }
            });

            for (String table : tables) {
                harvestHiveObject(conn, db, table, knownViews.contains(table.toLowerCase(Locale.ROOT)), filter, builder, timeoutSeconds);
            }
        }
    }

    private void harvestHiveObject(
            Connection conn,
            String db,
            String table,
            boolean knownView,
            SchemaFilterMatcher filter,
            CatalogBuilder builder,
            int timeoutSeconds) {
        String qualified = quoteBacktick(db) + "." + quoteBacktick(table);
        List<String[]> rawRows = new ArrayList<>();

        boolean formattedOk = runQuery(conn, "DESCRIBE FORMATTED " + qualified, timeoutSeconds, builder,
                "DESCRIBE FORMATTED " + db + "." + table, false, (rs, row) ->
                        rawRows.add(new String[]{row.strAt(1), row.strAt(2), row.strAt(3)}));
        if (!formattedOk) {
            runQuery(conn, "DESCRIBE " + qualified, timeoutSeconds, builder,
                    "DESCRIBE " + db + "." + table, true, (rs, row) ->
                            rawRows.add(new String[]{row.strAt(1), row.strAt(2), row.strAt(3)}));
        }

        boolean isView = knownView;
        String location = null;
        String viewExpandedText = null;
        for (String[] r : rawRows) {
            String c0 = r[0] != null ? r[0].trim() : "";
            String c1 = r[1] != null ? r[1].trim() : "";
            if (c0.startsWith("Table Type:") && c1.toUpperCase(Locale.ROOT).contains("VIEW")) {
                isView = true;
            } else if (c0.startsWith("Location:") && !c1.isEmpty()) {
                location = c1;
            } else if (c0.startsWith("View Expanded Text:") && !c1.isEmpty()) {
                viewExpandedText = c1;
            }
        }

        if (isView && !filter.acceptsView(db, null, table)) {
            return;
        }
        DbObject targetObj = isView
                ? builder.getOrCreateView(db, null, table)
                : builder.getOrCreateTable(db, null, table);
        if (location != null) {
            targetObj.setLocation(location);
        }

        boolean inMetadataSection = false;
        boolean inPartitionColumns = false;
        for (String[] r : rawRows) {
            String colName = r[0] != null ? r[0].trim() : "";
            String dataType = r[1] != null ? r[1].trim() : "";
            String comment = r[2] != null ? r[2].trim() : null;

            if (colName.isEmpty()) {
                continue;
            }
            if (colName.startsWith("# Partition Information") || colName.startsWith("# Partition Transform")) {
                inPartitionColumns = true;
                inMetadataSection = false;
                continue;
            }
            if (colName.startsWith("# Detailed Table Information") || colName.startsWith("# Storage Information")
                    || colName.startsWith("# Constraints")) {
                inMetadataSection = true;
                inPartitionColumns = false;
                continue;
            }
            if (colName.startsWith("#") || "col_name".equalsIgnoreCase(colName) || "name".equalsIgnoreCase(colName)) {
                continue;
            }
            if (!inMetadataSection && !dataType.isEmpty()) {
                builder.addOrUpdateColumn(
                        targetObj,
                        colName,
                        dataType,
                        true,
                        null,
                        inPartitionColumns && (comment == null || comment.isEmpty()) ? "PARTITION COLUMN" : comment);
            }
        }

        if (isView && filter.shouldHarvestViews()) {
            StringBuilder ddlBuf = new StringBuilder();
            boolean showCreateOk = runQuery(conn, "SHOW CREATE TABLE " + qualified, timeoutSeconds, builder,
                    "SHOW CREATE TABLE " + db + "." + table, false, (rs, row) -> {
                        String line = row.strAt(1);
                        if (line != null) {
                            ddlBuf.append(line).append('\n');
                        }
                    });
            String ddl = showCreateOk && !ddlBuf.isEmpty() ? ddlBuf.toString().trim() : viewExpandedText;
            if (ddl != null && !ddl.isBlank()) {
                builder.addViewDefinition(db, null, table, ddl, false);
            }
        }
    }

    private void harvestViaInformationSchema(
            Connection conn,
            SchemaFilterMatcher filter,
            CatalogBuilder builder,
            int timeoutSeconds) {
        runQuery(conn, SQL_INFO_SCHEMA_COLUMNS, timeoutSeconds, builder, "INFORMATION_SCHEMA.COLUMNS", true, (rs, row) -> {
            String db = row.strTrim("TABLE_SCHEMA");
            String table = row.strTrim("TABLE_NAME");
            String col = row.strTrim("COLUMN_NAME");
            if (db == null || table == null || col == null || !filter.acceptsSchema(db, null)) {
                return;
            }
            String tType = row.strTrim("TABLE_TYPE");
            boolean isView = tType != null && tType.toUpperCase(Locale.ROOT).contains("VIEW");
            if (isView && !filter.acceptsView(db, null, table)) {
                return;
            }
            DbObject parent = isView ? builder.getOrCreateView(db, null, table) : builder.getOrCreateTable(db, null, table);
            builder.addOrUpdateColumn(
                    parent,
                    col,
                    row.strTrim("DATA_TYPE"),
                    parseNullableFlag(row.strTrim("IS_NULLABLE")),
                    row.strTrim("COLUMN_DEFAULT"),
                    null);
        });

        if (filter.shouldHarvestViews()) {
            runQuery(conn, SQL_INFO_SCHEMA_VIEWS, timeoutSeconds, builder, "INFORMATION_SCHEMA.VIEWS", false, (rs, row) -> {
                String db = row.strTrim("TABLE_SCHEMA");
                String view = row.strTrim("TABLE_NAME");
                if (db != null && view != null && filter.acceptsView(db, null, view)) {
                    builder.addViewDefinition(db, null, view, row.str("VIEW_DEFINITION"), false);
                }
            });
        }
    }

    private static boolean isEmbeddedSqlCatalog(Connection conn) {
        try {
            String url = conn.getMetaData().getURL();
            return url != null && url.toLowerCase(Locale.ROOT).startsWith("jdbc:h2:");
        } catch (SQLException e) {
            return false;
        }
    }

    private static String quoteBacktick(String ident) {
        return "`" + ident.replace("`", "``") + "`";
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }
}
