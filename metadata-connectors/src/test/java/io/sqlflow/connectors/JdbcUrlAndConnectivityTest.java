package io.sqlflow.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import io.sqlflow.connectors.jdbc.CredentialDecryptor;
import io.sqlflow.connectors.jdbc.ExternalDriverManager;
import io.sqlflow.connectors.jdbc.JdbcMetadataConnectorService;
import io.sqlflow.connectors.jdbc.JdbcUrlBuilder;
import io.sqlflow.contracts.domain.DataSource;
import io.sqlflow.contracts.spi.MetadataConnectorSpi.ConnectionTestResult;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Base64;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcUrlAndConnectivityTest {

    @Test
    void buildsAccurateJdbcUrlsAndTimeoutPropertiesAcrossAllSupportedVendors() {
        CredentialDecryptor decryptor = new CredentialDecryptor();

        // 1. Oracle Service Name & SID
        DataSource oracleSvc = ds("ora.corp.internal", "1521", "system", "secret", "ORCLPDB1");
        var oraCfg = JdbcUrlBuilder.build(EDbVendor.dbvoracle, oracleSvc, decryptor);
        assertThat(oraCfg.jdbcUrl()).isEqualTo("jdbc:oracle:thin:@//ora.corp.internal:1521/ORCLPDB1");
        assertThat(oraCfg.properties().getProperty("oracle.net.CONNECT_TIMEOUT")).isEqualTo("10000");

        DataSource oracleSid = ds("ora.corp.internal", "1521", "system", "secret", ":XE");
        assertThat(JdbcUrlBuilder.build(EDbVendor.dbvoracle, oracleSid, decryptor).jdbcUrl())
                .isEqualTo("jdbc:oracle:thin:@ora.corp.internal:1521:XE");

        // 2. SQL Server & Azure SQL
        DataSource mssql = ds("sqlsrv.corp", "1433", "sa", "Pass!", "AdventureWorks");
        assertThat(JdbcUrlBuilder.build(EDbVendor.dbvmssql, mssql, decryptor).jdbcUrl())
                .contains("jdbc:sqlserver://sqlsrv.corp:1433;databaseName=AdventureWorks");

        DataSource azure = ds("myserver.database.windows.net", "1433", "admin", "Pass!", "analytics");
        assertThat(JdbcUrlBuilder.build(EDbVendor.dbvazuresql, azure, decryptor).jdbcUrl())
                .contains("encrypt=true")
                .contains("trustServerCertificate=false");

        // 3. PostgreSQL & Greenplum
        DataSource pg = ds("pg.corp", "5432", "postgres", "pw", "warehouse?ssl=true&currentSchema=mart");
        var pgCfg = JdbcUrlBuilder.build(EDbVendor.dbvpostgresql, pg, decryptor);
        assertThat(pgCfg.jdbcUrl()).isEqualTo("jdbc:postgresql://pg.corp:5432/warehouse?ssl=true&currentSchema=mart");
        assertThat(pgCfg.properties().getProperty("connectTimeout")).isEqualTo("10");

        assertThat(JdbcUrlBuilder.build(EDbVendor.dbvgreenplum, ds("gp.corp", null, "gpadmin", "pw", "gpdb"), decryptor).jdbcUrl())
                .isEqualTo("jdbc:postgresql://gp.corp:5432/gpdb");

        // 4. MySQL
        DataSource mysql = ds("mysql.corp", null, "root", "pw", "ecommerce");
        assertThat(JdbcUrlBuilder.build(EDbVendor.dbvmysql, mysql, decryptor).jdbcUrl())
                .startsWith("jdbc:mysql://mysql.corp:3306/ecommerce?");

        // 5. DB2 with SSL
        DataSource db2 = ds("db2.corp", "50000", "db2inst1", "pw", "BLUDB?ssl=true");
        assertThat(JdbcUrlBuilder.build(EDbVendor.dbvdb2, db2, decryptor).jdbcUrl())
                .isEqualTo("jdbc:db2://db2.corp:50000/BLUDB:sslConnection=true;");

        // 6. Teradata
        DataSource td = ds("td.corp", "1025", "dbc", "dbc", "FINANCE");
        assertThat(JdbcUrlBuilder.build(EDbVendor.dbvteradata, td, decryptor).jdbcUrl())
                .isEqualTo("jdbc:teradata://td.corp/DATABASE=FINANCE,DBS_PORT=1025,CHARSET=UTF8");

        // 7. Netezza
        assertThat(JdbcUrlBuilder.build(EDbVendor.dbvnetezza, ds("nz.corp", null, "admin", "pw", "PROD"), decryptor).jdbcUrl())
                .isEqualTo("jdbc:netezza://nz.corp:5480/PROD");

        // 8. Snowflake with warehouse & role
        DataSource sf = ds("xy12345.us-east-1", "443", "etl_svc", "pw", "RAW_DB?warehouse=COMPUTE_WH&role=ANALYST");
        var sfCfg = JdbcUrlBuilder.build(EDbVendor.dbvsnowflake, sf, decryptor);
        assertThat(sfCfg.jdbcUrl())
                .isEqualTo("jdbc:snowflake://xy12345.us-east-1.snowflakecomputing.com/?db=RAW_DB&warehouse=COMPUTE_WH&role=ANALYST");
        assertThat(sfCfg.properties().getProperty("warehouse")).isEqualTo("COMPUTE_WH");
        assertThat(sfCfg.properties().getProperty("role")).isEqualTo("ANALYST");

        // 9. Redshift, Hive, Impala, Sybase, Informix, ClickHouse, BigQuery, Athena
        assertThat(JdbcUrlBuilder.build(EDbVendor.dbvredshift, ds("rs.corp", null, "awsuser", "pw", "dev"), decryptor).jdbcUrl())
                .isEqualTo("jdbc:redshift://rs.corp:5439/dev");
        assertThat(JdbcUrlBuilder.build(EDbVendor.dbvhive, ds("hive.corp", null, "hive", "", "ods?principal=hive/_HOST@CORP"), decryptor).jdbcUrl())
                .isEqualTo("jdbc:hive2://hive.corp:10000/ods;principal=hive/_HOST@CORP");
        assertThat(JdbcUrlBuilder.build(EDbVendor.dbvimpala, ds("impala.corp", null, "impala", "", "dwh"), decryptor).jdbcUrl())
                .isEqualTo("jdbc:impala://impala.corp:21050/dwh");
        assertThat(JdbcUrlBuilder.build(EDbVendor.dbvsybase, ds("syb.corp", null, "sa", "pw", "pubs2"), decryptor).jdbcUrl())
                .isEqualTo("jdbc:sybase:Tds:syb.corp:5000/pubs2");
        assertThat(JdbcUrlBuilder.build(EDbVendor.dbvinformix, ds("ifx.corp", null, "informix", "pw", "stores"), decryptor).jdbcUrl())
                .startsWith("jdbc:informix-sqli://ifx.corp:9088/stores:INFORMIXSERVER=");
        assertThat(JdbcUrlBuilder.build(EDbVendor.dbvclickhouse, ds("ch.corp", null, "default", "", "analytics"), decryptor).jdbcUrl())
                .isEqualTo("jdbc:clickhouse://ch.corp:8123/analytics");
        assertThat(JdbcUrlBuilder.build(EDbVendor.dbvbigquery, ds("bigquery.googleapis.com", "443", "", "", "my-gcp-proj"), decryptor).jdbcUrl())
                .contains("jdbc:bigquery://").contains("ProjectId=my-gcp-proj");
        assertThat(JdbcUrlBuilder.build(EDbVendor.dbvathena, ds("us-west-2", "443", "", "", "AwsDataCatalog?S3OutputLocation=s3://bucket/out"), decryptor).jdbcUrl())
                .contains("jdbc:awsathena://AwsRegion=us-west-2;S3OutputLocation=s3://bucket/out;");

        // 10. Custom URL Override
        DataSource custom = ds("ignored", "9999", "sa", "", "ignored");
        custom.setUrl("jdbc:h2:mem:override_check;DB_CLOSE_DELAY=-1");
        assertThat(JdbcUrlBuilder.build(EDbVendor.dbvoracle, custom, decryptor).jdbcUrl())
                .isEqualTo("jdbc:h2:mem:override_check;DB_CLOSE_DELAY=-1");
    }

    @Test
    void rsaPkcs1AndGbteptedDesPasswordDecryptionAndPlaintextFallbackWorkCleanly() {
        KeyPair kp = CredentialDecryptor.generateRsaKeyPair();
        String pkcs8Base64 = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        CredentialDecryptor decryptor = new CredentialDecryptor(CredentialDecryptor.parsePkcs8PrivateKey(pkcs8Base64));

        String rsaCipher = CredentialDecryptor.encryptRsaBase64("S3cr3t#Snowflake!2026", kp.getPublic());
        assertThat(decryptor.decryptPassword(rsaCipher)).isEqualTo("S3cr3t#Snowflake!2026");

        String desCipher = decryptor.encryptGbtepted("GrabitCliPass$99");
        assertThat(desCipher).startsWith(CredentialDecryptor.GBTEPTED_PREFIX);
        assertThat(decryptor.decryptPassword(desCipher)).isEqualTo("GrabitCliPass$99");

        assertThat(decryptor.decryptPassword("plain_password_123")).isEqualTo("plain_password_123");
    }

    @Test
    void testConnectionSucceedsAgainstLiveH2AndSqliteWithEncryptedCredentialsAndFailsCleanlyOnErrors() throws IOException {
        KeyPair kp = CredentialDecryptor.generateRsaKeyPair();
        CredentialDecryptor decryptor = new CredentialDecryptor(kp.getPrivate());
        JdbcMetadataConnectorService service = new JdbcMetadataConnectorService(
                new ExternalDriverManager(), decryptor, 2, 5);

        // Live H2 with RSA-encrypted password
        DataSource h2Ds = new DataSource();
        h2Ds.setUrl("jdbc:h2:mem:conn_test_db");
        h2Ds.setAccount("sa");
        h2Ds.setPassword(CredentialDecryptor.encryptRsaBase64("", kp.getPublic()));
        ConnectionTestResult h2Ok = service.testConnection(EDbVendor.dbvpostgresql, h2Ds);
        assertThat(h2Ok.isConnected()).isTrue();
        assertThat(h2Ok.getProductName()).containsIgnoringCase("H2");
        assertThat(h2Ok.getErrorMessage()).isNull();

        // Live SQLite in-memory
        DataSource sqliteDs = new DataSource();
        sqliteDs.setDatabase(":memory:");
        ConnectionTestResult sqliteOk = service.testConnection(EDbVendor.dbvsqlite, sqliteDs);
        assertThat(sqliteOk.isConnected()).isTrue();
        assertThat(sqliteOk.getProductName()).containsIgnoringCase("SQLite");

        // Missing proprietary JDBC driver diagnostic message (Teradata has no driver on Maven Central)
        DataSource tdDs = ds("127.0.0.1", "1025", "dbc", "dbc", "DBC");
        ConnectionTestResult tdFail = service.testConnection(EDbVendor.dbvteradata, tdDs);
        assertThat(tdFail.isConnected()).isFalse();
        assertThat(tdFail.getErrorMessage())
                .contains("No suitable JDBC driver")
                .contains("com.teradata.jdbc.TeraDriver");

        // Unreachable TCP port with PostgreSQL driver returns structured connection error promptly
        int closedPort;
        try (ServerSocket ss = new ServerSocket(0)) {
            closedPort = ss.getLocalPort();
        }
        DataSource unreachablePg = ds("127.0.0.1", Integer.toString(closedPort), "postgres", "secretPass123", "postgres?connectTimeout=1");
        ConnectionTestResult pgFail = service.testConnection(EDbVendor.dbvpostgresql, unreachablePg);
        assertThat(pgFail.isConnected()).isFalse();
        assertThat(pgFail.getErrorMessage()).contains("[postgresql]").doesNotContain("secretPass123");
    }

    @Test
    void externalDriverManagerLoadsProprietaryVendorJarDynamicallyFromDriversDirectory(@TempDir Path tempDir) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).isNotNull();

        Path srcDir = tempDir.resolve("src");
        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(srcDir.resolve("mock/vendor"));
        Files.createDirectories(classesDir);

        Path driverSource = srcDir.resolve("mock/vendor/MockExternalNetezzaDriver.java");
        Files.writeString(driverSource, """
                package mock.vendor;
                import java.sql.*;
                import java.util.Properties;
                import java.util.logging.Logger;

                public class MockExternalNetezzaDriver implements Driver {
                    @Override
                    public Connection connect(String url, Properties info) throws SQLException {
                        if (!acceptsURL(url)) return null;
                        return DriverManager.getConnection("jdbc:h2:mem:ext_netezza_shim", "sa", "");
                    }
                    @Override
                    public boolean acceptsURL(String url) {
                        return url != null && url.startsWith("jdbc:netezza://");
                    }
                    @Override
                    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
                    @Override
                    public int getMajorVersion() { return 8; }
                    @Override
                    public int getMinorVersion() { return 2; }
                    @Override
                    public boolean jdbcCompliant() { return true; }
                    @Override
                    public Logger getParentLogger() { return Logger.getGlobal(); }
                }
                """, StandardCharsets.UTF_8);

        ByteArrayOutputStream errOut = new ByteArrayOutputStream();
        int rc = compiler.run(null, null, errOut, "-d", classesDir.toString(), "--release", "17", driverSource.toString());
        assertThat(rc).as(errOut.toString(StandardCharsets.UTF_8)).isZero();

        Path driversDir = tempDir.resolve("drivers");
        Files.createDirectories(driversDir);
        Path jarFile = driversDir.resolve("mock-netezza-driver.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarFile))) {
            jos.putNextEntry(new JarEntry("mock/vendor/MockExternalNetezzaDriver.class"));
            jos.write(Files.readAllBytes(classesDir.resolve("mock/vendor/MockExternalNetezzaDriver.class")));
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("META-INF/services/java.sql.Driver"));
            jos.write("mock.vendor.MockExternalNetezzaDriver\n".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        ExternalDriverManager extMgr = new ExternalDriverManager(List.of(driversDir));
        JdbcMetadataConnectorService service = new JdbcMetadataConnectorService(
                extMgr, new CredentialDecryptor(), 5, 5);

        DataSource nzDs = ds("nz-appliance.local", "5480", "admin", "pw", "ANALYTICS");
        ConnectionTestResult res = service.testConnection(EDbVendor.dbvnetezza, nzDs);
        assertThat(res.isConnected()).isTrue();
        assertThat(extMgr.getExternalDrivers()).isNotEmpty();
    }

    private static DataSource ds(String host, String port, String user, String pass, String db) {
        DataSource ds = new DataSource();
        ds.setHostname(host);
        ds.setPort(port);
        ds.setAccount(user);
        ds.setPassword(pass);
        ds.setDatabase(db);
        return ds;
    }
}
