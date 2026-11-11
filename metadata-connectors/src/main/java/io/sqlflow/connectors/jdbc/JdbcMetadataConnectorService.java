package io.sqlflow.connectors.jdbc;

import io.sqlflow.connectors.jdbc.harvester.BigQueryAthenaMetadataHarvester;
import io.sqlflow.connectors.jdbc.harvester.ClickHouseMetadataHarvester;
import io.sqlflow.connectors.jdbc.harvester.Db2MetadataHarvester;
import io.sqlflow.connectors.jdbc.harvester.EmbeddedJdbcMetadataHarvester;
import io.sqlflow.connectors.jdbc.harvester.HiveImpalaMetadataHarvester;
import io.sqlflow.connectors.jdbc.harvester.MySqlMetadataHarvester;
import io.sqlflow.connectors.jdbc.harvester.NetezzaMetadataHarvester;
import io.sqlflow.connectors.jdbc.harvester.OracleMetadataHarvester;
import io.sqlflow.connectors.jdbc.harvester.PostgresGreenplumMetadataHarvester;
import io.sqlflow.connectors.jdbc.harvester.RedshiftMetadataHarvester;
import io.sqlflow.connectors.jdbc.harvester.SnowflakeMetadataHarvester;
import io.sqlflow.connectors.jdbc.harvester.SqlServerMetadataHarvester;
import io.sqlflow.connectors.jdbc.harvester.SybaseInformixMetadataHarvester;
import io.sqlflow.connectors.jdbc.harvester.TeradataMetadataHarvester;
import io.sqlflow.connectors.jdbc.harvester.VendorMetadataHarvester;
import io.sqlflow.connectors.util.ConnectorsJson;
import io.sqlflow.contracts.domain.DataSource;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.contracts.spi.MetadataConnectorSpi;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Production implementation of {@link MetadataConnectorSpi} ({@code OBL-074..OBL-076}) supporting live
 * JDBC connectivity testing and vendor-accurate catalog metadata harvesting across all 14 canonical
 * SQLFlow JDBC dialects plus ClickHouse, BigQuery, Athena, Informix, SQLite, DuckDB, and H2/ODBC.
 */
public class JdbcMetadataConnectorService implements MetadataConnectorSpi {

    private static final Set<EDbVendor> ALL_SUPPORTED_JDBC_VENDORS = Collections.unmodifiableSet(EnumSet.of(
            // Canonical 14 live JDBC vendors from MetadataConnectorSpi.SUPPORTED_14_JDBC_VENDORS
            EDbVendor.dbvmysql,
            EDbVendor.dbvoracle,
            EDbVendor.dbvpostgresql,
            EDbVendor.dbvmssql,
            EDbVendor.dbvazuresql,
            EDbVendor.dbvsnowflake,
            EDbVendor.dbvredshift,
            EDbVendor.dbvgreenplum,
            EDbVendor.dbvteradata,
            EDbVendor.dbvdb2,
            EDbVendor.dbvnetezza,
            EDbVendor.dbvhive,
            EDbVendor.dbvimpala,
            EDbVendor.dbvsybase,
            // Extended cloud, analytical & embedded JDBC dialects
            EDbVendor.dbvclickhouse,
            EDbVendor.dbvbigquery,
            EDbVendor.dbvathena,
            EDbVendor.dbvinformix,
            EDbVendor.dbvsqlite,
            EDbVendor.dbvduckdb,
            EDbVendor.dbvodbc,
            EDbVendor.dbvdoris,
            EDbVendor.dbvstarrocks,
            EDbVendor.dbvoceanbase,
            EDbVendor.dbvgaussdb,
            EDbVendor.dbvedb,
            EDbVendor.dbvvertica,
            EDbVendor.dbvdameng,
            EDbVendor.dbvsybasease,
            EDbVendor.dbvsybaseiq,
            EDbVendor.dbvsqlanywhere));

    private static final ExecutorService CONNECT_TIMEOUT_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sqlflow-jdbc-connector");
        t.setDaemon(true);
        return t;
    });

    private final ExternalDriverManager driverManager;
    private final CredentialDecryptor credentialDecryptor;
    private final int defaultConnectTimeoutSeconds;
    private final int defaultSocketTimeoutSeconds;
    private final Map<EDbVendor, VendorMetadataHarvester> harvesters;

    /**
     * Public no-arg constructor required for {@link java.util.ServiceLoader}.
     */
    public JdbcMetadataConnectorService() {
        this(
                new ExternalDriverManager(),
                new CredentialDecryptor(),
                JdbcUrlBuilder.DEFAULT_CONNECT_TIMEOUT_SECONDS,
                JdbcUrlBuilder.DEFAULT_SOCKET_TIMEOUT_SECONDS);
    }

    public JdbcMetadataConnectorService(
            ExternalDriverManager driverManager,
            CredentialDecryptor credentialDecryptor,
            int defaultConnectTimeoutSeconds,
            int defaultSocketTimeoutSeconds) {
        this.driverManager = driverManager != null ? driverManager : new ExternalDriverManager();
        this.credentialDecryptor = credentialDecryptor != null ? credentialDecryptor : new CredentialDecryptor();
        this.defaultConnectTimeoutSeconds = defaultConnectTimeoutSeconds > 0
                ? defaultConnectTimeoutSeconds
                : JdbcUrlBuilder.DEFAULT_CONNECT_TIMEOUT_SECONDS;
        this.defaultSocketTimeoutSeconds = defaultSocketTimeoutSeconds > 0
                ? defaultSocketTimeoutSeconds
                : JdbcUrlBuilder.DEFAULT_SOCKET_TIMEOUT_SECONDS;
        this.harvesters = registerHarvesters();
    }

    @Override
    public Set<EDbVendor> supportedVendors() {
        return ALL_SUPPORTED_JDBC_VENDORS;
    }

    public ExternalDriverManager getDriverManager() {
        return driverManager;
    }

    public CredentialDecryptor getCredentialDecryptor() {
        return credentialDecryptor;
    }

    @Override
    public ConnectionTestResult testConnection(EDbVendor vendor, DataSource dataSource) {
        if (vendor == null) {
            return ConnectionTestResult.fail("Database vendor (EDbVendor) must not be null");
        }
        if (dataSource == null) {
            return ConnectionTestResult.fail("DataSource configuration must not be null");
        }
        if ((dataSource.getUrl() == null || dataSource.getUrl().isBlank())
                && (dataSource.getHostname() == null || dataSource.getHostname().isBlank())
                && vendor != EDbVendor.dbvsqlite
                && vendor != EDbVendor.dbvduckdb) {
            return ConnectionTestResult.fail(
                    "Missing hostname or custom JDBC URL for vendor '" + vendor.shortName() + "'");
        }

        JdbcUrlBuilder.ResolvedJdbcConfig resolved;
        try {
            resolved = JdbcUrlBuilder.build(
                    vendor,
                    dataSource,
                    credentialDecryptor,
                    defaultConnectTimeoutSeconds,
                    defaultSocketTimeoutSeconds);
        } catch (RuntimeException e) {
            return ConnectionTestResult.fail("Invalid JDBC connection parameters: " + e.getMessage());
        }

        long startNanos = System.nanoTime();
        int hardTimeoutSec = Math.max(1, resolved.connectTimeoutSeconds());

        Future<ConnectionTestResult> future = CONNECT_TIMEOUT_EXECUTOR.submit(() -> {
            try (Connection conn = driverManager.openConnection(
                    resolved.jdbcUrl(),
                    resolved.properties(),
                    resolved.candidateDriverClasses(),
                    hardTimeoutSec)) {
                DatabaseMetaData meta = conn.getMetaData();
                String productName = meta.getDatabaseProductName();
                String productVersion = meta.getDatabaseProductVersion();

                probeValidationQuery(conn, resolved.validationQuery(), hardTimeoutSec);

                long latencyMs = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
                return ConnectionTestResult.ok(
                        productName != null ? productName : vendor.description(),
                        productVersion != null ? productVersion : "unknown",
                        latencyMs);
            } catch (SQLException e) {
                return ConnectionTestResult.fail(formatDiagnosticError(vendor, resolved.jdbcUrl(), e));
            }
        });

        try {
            return future.get(hardTimeoutSec + 2L, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return ConnectionTestResult.fail(
                    "Connection timed out after " + hardTimeoutSec + "s connecting to "
                            + ExternalDriverManager.maskUrlCredentials(resolved.jdbcUrl()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ConnectionTestResult.fail("Connection test interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return ConnectionTestResult.fail(
                    "Connection test failed for " + vendor.shortName() + ": " + cause.getMessage());
        }
    }

    @Override
    public MetadataHarvestResult harvestMetadata(EDbVendor vendor, DataSource dataSource) {
        if (vendor == null) {
            throw new IllegalArgumentException("EDbVendor must not be null");
        }
        if (dataSource == null) {
            throw new IllegalArgumentException("DataSource must not be null");
        }

        JdbcUrlBuilder.ResolvedJdbcConfig resolved = JdbcUrlBuilder.build(
                vendor,
                dataSource,
                credentialDecryptor,
                defaultConnectTimeoutSeconds,
                defaultSocketTimeoutSeconds);

        String serverName = resolveServerIdentifier(vendor, dataSource, resolved.jdbcUrl());
        CatalogBuilder builder = new CatalogBuilder(vendor, serverName);
        SchemaFilterMatcher filter = new SchemaFilterMatcher(vendor, dataSource);
        VendorMetadataHarvester harvester = resolveHarvester(vendor);

        try (Connection conn = driverManager.openConnection(
                resolved.jdbcUrl(),
                resolved.properties(),
                resolved.candidateDriverClasses(),
                resolved.connectTimeoutSeconds())) {
            harvester.harvest(conn, vendor, dataSource, filter, builder, resolved.socketTimeoutSeconds());
        } catch (SQLException e) {
            builder.addWarning("JDBC harvest failed: " + formatDiagnosticError(vendor, resolved.jdbcUrl(), e));
        }

        SqlflowCatalog catalog = builder.buildCatalog();
        MetadataHarvestResult result = new MetadataHarvestResult();
        result.setVendor(vendor);
        result.setCatalog(catalog);
        result.setExtractedSqlUnits(builder.buildSqlUnits());
        result.setExporterMetadataJson(ConnectorsJson.toJson(catalog));
        List<String> warnings = builder.buildWarnings();
        result.setWarnings(warnings);
        return result;
    }

    private VendorMetadataHarvester resolveHarvester(EDbVendor vendor) {
        VendorMetadataHarvester harvester = harvesters.get(vendor);
        return harvester != null ? harvester : new EmbeddedJdbcMetadataHarvester();
    }

    private static Map<EDbVendor, VendorMetadataHarvester> registerHarvesters() {
        Map<EDbVendor, VendorMetadataHarvester> m = new EnumMap<>(EDbVendor.class);

        OracleMetadataHarvester oracle = new OracleMetadataHarvester();
        m.put(EDbVendor.dbvoracle, oracle);
        m.put(EDbVendor.dbvdameng, oracle);

        SqlServerMetadataHarvester mssql = new SqlServerMetadataHarvester();
        m.put(EDbVendor.dbvmssql, mssql);
        m.put(EDbVendor.dbvazuresql, mssql);

        PostgresGreenplumMetadataHarvester pg = new PostgresGreenplumMetadataHarvester();
        m.put(EDbVendor.dbvpostgresql, pg);
        m.put(EDbVendor.dbvgreenplum, pg);
        m.put(EDbVendor.dbvgaussdb, pg);
        m.put(EDbVendor.dbvedb, pg);
        m.put(EDbVendor.dbvvertica, pg);

        MySqlMetadataHarvester mysql = new MySqlMetadataHarvester();
        m.put(EDbVendor.dbvmysql, mysql);
        m.put(EDbVendor.dbvdoris, mysql);
        m.put(EDbVendor.dbvstarrocks, mysql);
        m.put(EDbVendor.dbvoceanbase, mysql);

        m.put(EDbVendor.dbvdb2, new Db2MetadataHarvester());
        m.put(EDbVendor.dbvteradata, new TeradataMetadataHarvester());
        m.put(EDbVendor.dbvnetezza, new NetezzaMetadataHarvester());
        m.put(EDbVendor.dbvsnowflake, new SnowflakeMetadataHarvester());
        m.put(EDbVendor.dbvredshift, new RedshiftMetadataHarvester());

        HiveImpalaMetadataHarvester hiveImpala = new HiveImpalaMetadataHarvester();
        m.put(EDbVendor.dbvhive, hiveImpala);
        m.put(EDbVendor.dbvimpala, hiveImpala);

        m.put(EDbVendor.dbvclickhouse, new ClickHouseMetadataHarvester());

        BigQueryAthenaMetadataHarvester bqAthena = new BigQueryAthenaMetadataHarvester();
        m.put(EDbVendor.dbvbigquery, bqAthena);
        m.put(EDbVendor.dbvathena, bqAthena);

        SybaseInformixMetadataHarvester sybIfx = new SybaseInformixMetadataHarvester();
        m.put(EDbVendor.dbvsybase, sybIfx);
        m.put(EDbVendor.dbvsybasease, sybIfx);
        m.put(EDbVendor.dbvsybaseiq, sybIfx);
        m.put(EDbVendor.dbvsqlanywhere, sybIfx);
        m.put(EDbVendor.dbvinformix, sybIfx);

        EmbeddedJdbcMetadataHarvester embedded = new EmbeddedJdbcMetadataHarvester();
        m.put(EDbVendor.dbvsqlite, embedded);
        m.put(EDbVendor.dbvduckdb, embedded);
        m.put(EDbVendor.dbvodbc, embedded);

        return Collections.unmodifiableMap(m);
    }

    private static void probeValidationQuery(Connection conn, String validationQuery, int timeoutSec) throws SQLException {
        try {
            if (conn.isValid(Math.max(1, timeoutSec))) {
                return;
            }
        } catch (AbstractMethodError | SQLException ignored) {
            // Fall through to explicit SQL validation query
        }
        if (validationQuery != null && !validationQuery.isBlank()) {
            try (Statement stmt = conn.createStatement()) {
                try {
                    stmt.setQueryTimeout(Math.max(1, timeoutSec));
                } catch (SQLException | AbstractMethodError ignored) {
                }
                try (ResultSet rs = stmt.executeQuery(validationQuery)) {
                    rs.next();
                }
            }
        }
    }

    private static String resolveServerIdentifier(EDbVendor vendor, DataSource ds, String jdbcUrl) {
        if (ds.getHostname() != null && !ds.getHostname().isBlank()) {
            String host = ds.getHostname().trim();
            int q = host.indexOf('?');
            if (q > 0) {
                host = host.substring(0, q);
            }
            if (ds.getPort() != null && !ds.getPort().isBlank()) {
                return host + ":" + ds.getPort().trim();
            }
            return host;
        }
        return vendor.shortName() + "_instance";
    }

    private static String formatDiagnosticError(EDbVendor vendor, String url, SQLException e) {
        String state = e.getSQLState() != null ? " [SQLState=" + e.getSQLState() + ", code=" + e.getErrorCode() + "]" : "";
        String msg = e.getMessage() != null ? e.getMessage().trim() : e.getClass().getSimpleName();
        return "[" + vendor.shortName() + "] " + msg + state
                + " (url=" + ExternalDriverManager.maskUrlCredentials(url) + ")";
    }
}
