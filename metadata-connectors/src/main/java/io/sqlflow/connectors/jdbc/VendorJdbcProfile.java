package io.sqlflow.connectors.jdbc;

import io.sqlflow.contracts.vendor.EDbVendor;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Vendor-specific JDBC connection profile: default TCP port, candidate JDBC driver class names,
 * validation SQL query, and built-in system catalog/schema exclusions ({@code 02 §6.3}, {@code 04 §9.3}).
 */
public final class VendorJdbcProfile {

    private static final Map<EDbVendor, VendorJdbcProfile> PROFILES = buildProfiles();

    private final EDbVendor vendor;
    private final int defaultPort;
    private final List<String> driverClasses;
    private final String validationQuery;
    private final Set<String> systemDatabases;
    private final Set<String> systemSchemas;

    public VendorJdbcProfile(
            EDbVendor vendor,
            int defaultPort,
            List<String> driverClasses,
            String validationQuery,
            Set<String> systemDatabases,
            Set<String> systemSchemas) {
        this.vendor = vendor;
        this.defaultPort = defaultPort;
        this.driverClasses = driverClasses == null ? List.of() : List.copyOf(driverClasses);
        this.validationQuery = validationQuery == null ? "SELECT 1" : validationQuery;
        this.systemDatabases = toLowerSet(systemDatabases);
        this.systemSchemas = toLowerSet(systemSchemas);
    }

    public static VendorJdbcProfile forVendor(EDbVendor vendor) {
        if (vendor == null) {
            return PROFILES.get(EDbVendor.dbvpostgresql);
        }
        VendorJdbcProfile profile = PROFILES.get(vendor);
        if (profile != null) {
            return profile;
        }
        return new VendorJdbcProfile(
                vendor,
                0,
                List.of(),
                "SELECT 1",
                Set.of("information_schema", "sys", "system"),
                Set.of("information_schema", "sys", "pg_catalog"));
    }

    public EDbVendor vendor() {
        return vendor;
    }

    public int defaultPort() {
        return defaultPort;
    }

    public List<String> driverClasses() {
        return driverClasses;
    }

    public String validationQuery() {
        return validationQuery;
    }

    public Set<String> systemDatabases() {
        return systemDatabases;
    }

    public Set<String> systemSchemas() {
        return systemSchemas;
    }

    public boolean isSystemDatabase(String dbName) {
        if (dbName == null || dbName.isBlank()) {
            return false;
        }
        String norm = dbName.trim().toLowerCase(Locale.ROOT);
        return systemDatabases.contains(norm);
    }

    public boolean isSystemSchema(String schemaName) {
        if (schemaName == null || schemaName.isBlank()) {
            return false;
        }
        String norm = schemaName.trim().toLowerCase(Locale.ROOT);
        if (systemSchemas.contains(norm)) {
            return true;
        }
        if (vendor == EDbVendor.dbvoracle && (norm.startsWith("apex_") || norm.startsWith("flows_"))) {
            return true;
        }
        if ((vendor == EDbVendor.dbvpostgresql || vendor == EDbVendor.dbvgreenplum || vendor == EDbVendor.dbvredshift)
                && (norm.startsWith("pg_toast") || norm.startsWith("pg_temp"))) {
            return true;
        }
        return false;
    }

    private static Set<String> toLowerSet(Set<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        return raw.stream()
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Map<EDbVendor, VendorJdbcProfile> buildProfiles() {
        Map<EDbVendor, VendorJdbcProfile> m = new EnumMap<>(EDbVendor.class);

        m.put(EDbVendor.dbvoracle, new VendorJdbcProfile(
                EDbVendor.dbvoracle,
                1521,
                List.of("oracle.jdbc.OracleDriver", "oracle.jdbc.driver.OracleDriver"),
                "SELECT 1 FROM DUAL",
                Set.of(),
                Set.of(
                        "sys", "system", "xs$null", "outln", "dip", "oracle_ocm", "dbsnmp", "appqossys",
                        "wmsys", "exfsys", "ctxsys", "xdb", "anonymous", "ordsys", "orddata", "ordplugins",
                        "si_informtn_schema", "mdsys", "olapsys", " mddat", "spatial_wfs_admin_usr",
                        "spatial_csw_admin_usr", "sysbackup", "sysdg", "syskm", "sysrac", "auaud_sys",
                        "audsys", "gsmadmin_internal", "ggsys", "lbacsys", "dvsys", "dvf", "remote_scheduler_agent")));

        Set<String> mssqlSysDbs = Set.of("master", "tempdb", "model", "msdb", "rdsadmin", " distribution");
        Set<String> mssqlSysSchemas = Set.of(
                "sys", "information_schema", "guest", "db_owner", "db_accessadmin", "db_securityadmin",
                "db_ddladmin", "db_backupoperator", "db_datareader", "db_datawriter", "db_denydatareader",
                "db_denydatawriter");
        m.put(EDbVendor.dbvmssql, new VendorJdbcProfile(
                EDbVendor.dbvmssql,
                1433,
                List.of("com.microsoft.sqlserver.jdbc.SQLServerDriver", "net.sourceforge.jtds.jdbc.Driver"),
                "SELECT 1",
                mssqlSysDbs,
                mssqlSysSchemas));
        m.put(EDbVendor.dbvazuresql, new VendorJdbcProfile(
                EDbVendor.dbvazuresql,
                1433,
                List.of("com.microsoft.sqlserver.jdbc.SQLServerDriver"),
                "SELECT 1",
                mssqlSysDbs,
                mssqlSysSchemas));

        Set<String> pgSysDbs = Set.of("template0", "template1", "rdsadmin", "azure_maintenance", "cloudsqladmin");
        Set<String> pgSysSchemas = Set.of("pg_catalog", "information_schema", "pg_toast", "gp_toolkit");
        m.put(EDbVendor.dbvpostgresql, new VendorJdbcProfile(
                EDbVendor.dbvpostgresql,
                5432,
                List.of("org.postgresql.Driver"),
                "SELECT 1",
                pgSysDbs,
                pgSysSchemas));
        m.put(EDbVendor.dbvgreenplum, new VendorJdbcProfile(
                EDbVendor.dbvgreenplum,
                5432,
                List.of("com.pivotal.jdbc.GreenplumDriver", "org.postgresql.Driver"),
                "SELECT 1",
                pgSysDbs,
                pgSysSchemas));
        m.put(EDbVendor.dbvgaussdb, new VendorJdbcProfile(
                EDbVendor.dbvgaussdb,
                5432,
                List.of("org.opengauss.Driver", "com.huawei.gauss200.jdbc.Driver", "org.postgresql.Driver"),
                "SELECT 1",
                pgSysDbs,
                pgSysSchemas));
        m.put(EDbVendor.dbvedb, new VendorJdbcProfile(
                EDbVendor.dbvedb,
                5444,
                List.of("com.edb.Driver", "org.postgresql.Driver"),
                "SELECT 1",
                pgSysDbs,
                pgSysSchemas));

        Set<String> mysqlSysDbs = Set.of("information_schema", "mysql", "performance_schema", "sys", "__recycle_bin__");
        m.put(EDbVendor.dbvmysql, new VendorJdbcProfile(
                EDbVendor.dbvmysql,
                3306,
                List.of("com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver", "org.mariadb.jdbc.Driver"),
                "SELECT 1",
                mysqlSysDbs,
                mysqlSysDbs));
        m.put(EDbVendor.dbvdoris, new VendorJdbcProfile(
                EDbVendor.dbvdoris,
                9030,
                List.of("com.mysql.cj.jdbc.Driver", "org.mariadb.jdbc.Driver"),
                "SELECT 1",
                Set.of("information_schema", "__internal_schema", "mysql"),
                Set.of("information_schema", "__internal_schema")));
        m.put(EDbVendor.dbvstarrocks, new VendorJdbcProfile(
                EDbVendor.dbvstarrocks,
                9030,
                List.of("com.mysql.cj.jdbc.Driver", "org.mariadb.jdbc.Driver"),
                "SELECT 1",
                Set.of("information_schema", "_statistics_", "sys"),
                Set.of("information_schema", "_statistics_", "sys")));
        m.put(EDbVendor.dbvoceanbase, new VendorJdbcProfile(
                EDbVendor.dbvoceanbase,
                2881,
                List.of("com.oceanbase.jdbc.Driver", "com.mysql.cj.jdbc.Driver"),
                "SELECT 1",
                Set.of("information_schema", "mysql", "oceanbase", "ocs", "sys"),
                Set.of("information_schema", "mysql", "oceanbase", "sys")));

        m.put(EDbVendor.dbvdb2, new VendorJdbcProfile(
                EDbVendor.dbvdb2,
                50000,
                List.of("com.ibm.db2.jcc.DB2Driver"),
                "SELECT 1 FROM SYSIBM.SYSDUMMY1",
                Set.of(),
                Set.of("syscat", "sysibm", "sysibmadm", "sysibminternal", "sysibmts", "sysproc", "syspublic", "sysstat", "systools", "nullid", "sqlj")));

        m.put(EDbVendor.dbvteradata, new VendorJdbcProfile(
                EDbVendor.dbvteradata,
                1025,
                List.of("com.teradata.jdbc.TeraDriver"),
                "SELECT 1",
                Set.of(
                        "dbc", "syslib", "sysudtlib", "sysbar", "sysspatial", "sysadmin", "td_sysfnlib",
                        "td_sysxml", "td_sysgpl", "tdstats", "tdwm", "tdqcd", "sqlj", "dbcmngr", "locklogshredder",
                        "systemfe", "crashdumps", "all", "default", "public", "extuser"),
                Set.of("dbc")));

        m.put(EDbVendor.dbvnetezza, new VendorJdbcProfile(
                EDbVendor.dbvnetezza,
                5480,
                List.of("org.netezza.Driver"),
                "SELECT 1",
                Set.of("system", "master_db"),
                Set.of("definition_schema", "information_schema")));

        m.put(EDbVendor.dbvsnowflake, new VendorJdbcProfile(
                EDbVendor.dbvsnowflake,
                443,
                List.of("net.snowflake.client.jdbc.SnowflakeDriver", "com.snowflake.client.jdbc.SnowflakeDriver"),
                "SELECT 1",
                Set.of("snowflake", "snowflake_sample_data"),
                Set.of("information_schema")));

        m.put(EDbVendor.dbvredshift, new VendorJdbcProfile(
                EDbVendor.dbvredshift,
                5439,
                List.of("com.amazon.redshift.jdbc42.Driver", "com.amazon.redshift.jdbc.Driver", "org.postgresql.Driver"),
                "SELECT 1",
                Set.of("padb_harvest", "template0", "template1", "awsdatacatalog"),
                Set.of("pg_catalog", "information_schema", "pg_internal", "pg_automv")));

        m.put(EDbVendor.dbvhive, new VendorJdbcProfile(
                EDbVendor.dbvhive,
                10000,
                List.of("org.apache.hive.jdbc.HiveDriver", "com.cloudera.hive.jdbc.HS2Driver"),
                "SELECT 1",
                Set.of("information_schema", "sys"),
                Set.of("information_schema", "sys")));

        m.put(EDbVendor.dbvimpala, new VendorJdbcProfile(
                EDbVendor.dbvimpala,
                21050,
                List.of("com.cloudera.impala.jdbc.Driver", "com.cloudera.impala.jdbc41.Driver", "org.apache.hive.jdbc.HiveDriver"),
                "SELECT 1",
                Set.of("_impala_builtins", "information_schema", "sys"),
                Set.of("_impala_builtins", "information_schema", "sys")));

        Set<String> sybaseSysDbs = Set.of("master", "model", "tempdb", "sybsystemdb", "sybsystemprocs", "sybmgmtdb", " sybsecurity");
        m.put(EDbVendor.dbvsybase, new VendorJdbcProfile(
                EDbVendor.dbvsybase,
                5000,
                List.of("com.sybase.jdbc4.jdbc.SybDriver", "com.sybase.jdbc3.jdbc.SybDriver", "net.sourceforge.jtds.jdbc.Driver"),
                "SELECT 1",
                sybaseSysDbs,
                Set.of("sys")));
        m.put(EDbVendor.dbvsybasease, m.get(EDbVendor.dbvsybase));
        m.put(EDbVendor.dbvsybaseiq, new VendorJdbcProfile(
                EDbVendor.dbvsybaseiq,
                2638,
                List.of("com.sybase.jdbc4.jdbc.SybDriver", "sap.jdbc4.sqlanywhere.IDriver"),
                "SELECT 1",
                sybaseSysDbs,
                Set.of("sys", "dbo")));
        m.put(EDbVendor.dbvsqlanywhere, new VendorJdbcProfile(
                EDbVendor.dbvsqlanywhere,
                2638,
                List.of("sap.jdbc4.sqlanywhere.IDriver", "com.sybase.jdbc4.jdbc.SybDriver"),
                "SELECT 1",
                Set.of(),
                Set.of("sys", "rs_systabgroup")));

        m.put(EDbVendor.dbvinformix, new VendorJdbcProfile(
                EDbVendor.dbvinformix,
                9088,
                List.of("com.informix.jdbc.IfxDriver"),
                "SELECT 1 FROM systables WHERE tabid = 1",
                Set.of("sysmaster", "sysutils", "sysuser", "sysadmin", "syscdr"),
                Set.of("informix")));

        m.put(EDbVendor.dbvclickhouse, new VendorJdbcProfile(
                EDbVendor.dbvclickhouse,
                8123,
                List.of("com.clickhouse.jdbc.ClickHouseDriver", "ru.yandex.clickhouse.ClickHouseDriver"),
                "SELECT 1",
                Set.of("system", "information_schema"),
                Set.of("system", "information_schema")));

        m.put(EDbVendor.dbvbigquery, new VendorJdbcProfile(
                EDbVendor.dbvbigquery,
                443,
                List.of("com.simba.googlebigquery.jdbc.Driver", "com.simba.googlebigquery.jdbc42.Driver"),
                "SELECT 1",
                Set.of("information_schema"),
                Set.of("information_schema")));

        m.put(EDbVendor.dbvathena, new VendorJdbcProfile(
                EDbVendor.dbvathena,
                443,
                List.of("com.simba.athena.jdbc.Driver", "com.amazon.athena.jdbc.AthenaDriver"),
                "SELECT 1",
                Set.of("information_schema"),
                Set.of("information_schema")));

        m.put(EDbVendor.dbvvertica, new VendorJdbcProfile(
                EDbVendor.dbvvertica,
                5433,
                List.of("com.vertica.jdbc.Driver"),
                "SELECT 1",
                Set.of(),
                Set.of("v_catalog", "v_monitor", "v_internal", "v_txtindex")));

        m.put(EDbVendor.dbvdameng, new VendorJdbcProfile(
                EDbVendor.dbvdameng,
                5236,
                List.of("dm.jdbc.driver.DmDriver"),
                "SELECT 1 FROM DUAL",
                Set.of(),
                Set.of("sys", "sysdba", "syssso", "sysauditor", "ctisys")));

        m.put(EDbVendor.dbvsqlite, new VendorJdbcProfile(
                EDbVendor.dbvsqlite,
                0,
                List.of("org.sqlite.JDBC"),
                "SELECT 1",
                Set.of(),
                Set.of()));

        m.put(EDbVendor.dbvduckdb, new VendorJdbcProfile(
                EDbVendor.dbvduckdb,
                0,
                List.of("org.duckdb.DuckDBDriver"),
                "SELECT 1",
                Set.of("system", "temp"),
                Set.of("information_schema", "pg_catalog")));

        m.put(EDbVendor.dbvodbc, new VendorJdbcProfile(
                EDbVendor.dbvodbc,
                0,
                List.of("org.h2.Driver"),
                "SELECT 1",
                Set.of("information_schema"),
                Set.of("information_schema")));

        return Collections.unmodifiableMap(m);
    }
}
