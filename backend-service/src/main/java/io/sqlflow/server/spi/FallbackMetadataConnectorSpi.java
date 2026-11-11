package io.sqlflow.server.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.contracts.domain.DataSource;
import io.sqlflow.contracts.lineage.Column;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.SqlInfo;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.contracts.spi.MetadataConnectorSpi;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Built-in JDBC live connector & harvester fallback supporting standard JDBC URLs (including
 * SQLite, DuckDB, PostgreSQL, MySQL, Oracle, SQL Server, etc.).
 */
public class FallbackMetadataConnectorSpi implements MetadataConnectorSpi {

    private final ObjectMapper objectMapper;

    public FallbackMetadataConnectorSpi(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<EDbVendor> supportedVendors() {
        EnumSet<EDbVendor> set = EnumSet.copyOf(SUPPORTED_14_JDBC_VENDORS);
        set.add(EDbVendor.dbvsqlite);
        set.add(EDbVendor.dbvduckdb);
        return set;
    }

    @Override
    public ConnectionTestResult testConnection(EDbVendor vendor, DataSource dataSource) {
        if (dataSource == null) {
            return ConnectionTestResult.fail("DataSource configuration is required");
        }
        String jdbcUrl = buildJdbcUrl(vendor, dataSource);
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return ConnectionTestResult.fail("Unable to construct JDBC URL for vendor " + vendor);
        }
        long start = System.currentTimeMillis();
        Properties props = new Properties();
        if (dataSource.getAccount() != null && !dataSource.getAccount().isBlank()) {
            props.setProperty("user", dataSource.getAccount());
        }
        if (dataSource.getPassword() != null) {
            props.setProperty("password", dataSource.getPassword());
        }
        DriverManager.setLoginTimeout(5);
        try (Connection conn = props.isEmpty()
                ? DriverManager.getConnection(jdbcUrl)
                : DriverManager.getConnection(jdbcUrl, props)) {
            DatabaseMetaData meta = conn.getMetaData();
            long elapsed = System.currentTimeMillis() - start;
            return ConnectionTestResult.ok(
                    meta.getDatabaseProductName(), meta.getDatabaseProductVersion(), elapsed);
        } catch (Exception e) {
            return ConnectionTestResult.fail(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    @Override
    public MetadataHarvestResult harvestMetadata(EDbVendor vendor, DataSource dataSource) {
        MetadataHarvestResult out = new MetadataHarvestResult();
        EDbVendor resolvedVendor = vendor != null ? vendor : EDbVendor.dbvpostgresql;
        out.setVendor(resolvedVendor);
        List<String> warnings = new ArrayList<>();
        List<DbObject> harvestedObjects = new ArrayList<>();
        List<SqlInfo> sqlUnits = new ArrayList<>();
        AtomicLong idSeq = new AtomicLong(1L);

        String jdbcUrl = buildJdbcUrl(resolvedVendor, dataSource);
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            warnings.add("Missing JDBC URL or hostname");
            out.setWarnings(warnings);
            out.setCatalog(new SqlflowCatalog());
            out.setExtractedSqlUnits(List.of());
            return out;
        }

        Properties props = new Properties();
        if (dataSource.getAccount() != null && !dataSource.getAccount().isBlank()) {
            props.setProperty("user", dataSource.getAccount());
        }
        if (dataSource.getPassword() != null) {
            props.setProperty("password", dataSource.getPassword());
        }

        try (Connection conn = props.isEmpty()
                ? DriverManager.getConnection(jdbcUrl)
                : DriverManager.getConnection(jdbcUrl, props)) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet tablesRs = meta.getTables(null, null, "%", new String[] {"TABLE", "VIEW"})) {
                while (tablesRs.next()) {
                    String cat = tablesRs.getString("TABLE_CAT");
                    String schem = tablesRs.getString("TABLE_SCHEM");
                    String tableName = tablesRs.getString("TABLE_NAME");
                    String tableType = tablesRs.getString("TABLE_TYPE");
                    if (tableName == null || tableName.startsWith("sqlite_")) {
                        continue;
                    }
                    String objType = (tableType != null && tableType.toUpperCase().contains("VIEW")) ? "view" : "table";
                    DbObject dbo = new DbObject(String.valueOf(idSeq.getAndIncrement()), tableName, objType);
                    dbo.setServer(dataSource.getHostname() != null ? dataSource.getHostname() : "localhost");
                    dbo.setDatabase(cat != null ? cat : (dataSource.getDatabase() != null ? dataSource.getDatabase() : "DEFAULT"));
                    dbo.setSchema(schem != null ? schem : "DEFAULT");
                    dbo.setFromDDL(Boolean.TRUE);

                    List<Column> cols = new ArrayList<>();
                    try (ResultSet colsRs = meta.getColumns(cat, schem, tableName, "%")) {
                        while (colsRs.next()) {
                            String colName = colsRs.getString("COLUMN_NAME");
                            String typeName = colsRs.getString("TYPE_NAME");
                            Column c = new Column(String.valueOf(idSeq.getAndIncrement()), colName, typeName);
                            cols.add(c);
                        }
                    }
                    dbo.setColumns(cols);
                    harvestedObjects.add(dbo);
                }
            }
        } catch (Exception e) {
            warnings.add("JDBC harvest warning: " + e.getMessage());
        }

        SqlflowCatalog catalog = CatalogUtils.buildCatalog(
                dataSource != null ? dataSource.getHostname() : "localhost",
                resolvedVendor.name(),
                harvestedObjects);
        out.setCatalog(catalog);
        out.setExtractedSqlUnits(sqlUnits);
        out.setWarnings(warnings);
        try {
            out.setExporterMetadataJson(objectMapper.writeValueAsString(catalog));
        } catch (Exception ignored) {
            out.setExporterMetadataJson("{}");
        }
        return out;
    }

    private static String buildJdbcUrl(EDbVendor vendor, DataSource ds) {
        if (ds.getUrl() != null && !ds.getUrl().isBlank()) {
            return ds.getUrl().trim();
        }
        String host = ds.getHostname();
        if (host != null && host.startsWith("jdbc:")) {
            return host.trim();
        }
        String db = ds.getDatabase() != null ? ds.getDatabase() : "";
        if (vendor == EDbVendor.dbvsqlite) {
            return "jdbc:sqlite:" + (!db.isEmpty() ? db : ":memory:");
        }
        if (vendor == EDbVendor.dbvduckdb) {
            return "jdbc:duckdb:" + db;
        }
        if (host == null || host.isBlank()) {
            return null;
        }
        String port = ds.getPort();
        return switch (vendor != null ? vendor : EDbVendor.dbvpostgresql) {
            case dbvmysql -> "jdbc:mysql://" + host + ":" + (port != null ? port : "3306") + "/" + db;
            case dbvoracle -> "jdbc:oracle:thin:@" + host + ":" + (port != null ? port : "1521") + ":" + db;
            case dbvmssql, dbvazuresql -> "jdbc:sqlserver://" + host + ":" + (port != null ? port : "1433") + ";databaseName=" + db;
            case dbvsnowflake -> "jdbc:snowflake://" + host + "/?db=" + db;
            case dbvredshift -> "jdbc:redshift://" + host + ":" + (port != null ? port : "5439") + "/" + db;
            default -> "jdbc:postgresql://" + host + ":" + (port != null ? port : "5432") + "/" + db;
        };
    }
}
