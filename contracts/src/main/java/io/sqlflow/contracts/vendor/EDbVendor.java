package io.sqlflow.contracts.vendor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Complete 48-constant SQL dialect enum in exact {@code ordinal()} declaration order ({@code 0..47}),
 * matching SQLFlow 8.2.2.15 ({@code 03 §2.1}, {@code OBL-029}).
 *
 * <p>42 dialects are actively implemented ({@link #isImplemented()} {@code == true}) and 6 entries
 * ({@link #dbvaccess}, {@link #dbvansi}, {@link #dbvdax}, {@link #dbvexasol}, {@link #dbvfirebird},
 * {@link #dbvgeneric}) are retained with {@link #isImplemented()} {@code == false} for 1:1 wire parity.
 */
public enum EDbVendor {
    /* 00 */ dbvaccess("access", "Microsoft Access", false),
    /* 01 */ dbvansi("ansi", "ANSI SQL", false),
    /* 02 */ dbvathena("athena", "AWS Athena", true),
    /* 03 */ dbvazuresql("azuresql", "Azure SQL", true, "azure"),
    /* 04 */ dbvbigquery("bigquery", "Google BigQuery", true, "bq"),
    /* 05 */ dbvclickhouse("clickhouse", "ClickHouse", true),
    /* 06 */ dbvcouchbase("couchbase", "Couchbase", true, "n1ql"),
    /* 07 */ dbvdax("dax", "Microsoft DAX", false),
    /* 08 */ dbvdb2("db2", "IBM DB2", true),
    /* 09 */ dbvdoris("doris", "Apache Doris", true),
    /* 10 */ dbvduckdb("duckdb", "DuckDB", true),
    /* 11 */ dbvexasol("exasol", "Exasol", false),
    /* 12 */ dbvfirebird("firebird", "Firebird", false),
    /* 13 */ dbvflink("flink", "Apache Flink SQL", true, "flinksql"),
    /* 14 */ dbvgeneric("generic", "Generic SQL", false),
    /* 15 */ dbvgreenplum("greenplum", "Greenplum", true, "gpdb"),
    /* 16 */ dbvhana("hana", "SAP HANA", true, "saphana"),
    /* 17 */ dbvhive("hive", "Apache Hive", true),
    /* 18 */ dbvimpala("impala", "Apache Impala", true),
    /* 19 */ dbvinformix("informix", "IBM Informix", true),
    /* 20 */ dbvmdx("mdx", "MDX", true),
    /* 21 */ dbvmysql("mysql", "MySQL", true, "mariadb", "tidb"),
    /* 22 */ dbvmssql("mssql", "Microsoft SQL Server", true, "sqlserver", "tsql"),
    /* 23 */ dbvnetezza("netezza", "IBM Netezza", true),
    /* 24 */ dbvodbc("odbc", "ODBC", true),
    /* 25 */ dbvopenedge("openedge", "Progress OpenEdge", true),
    /* 26 */ dbvoracle("oracle", "Oracle", true, "plsql"),
    /* 27 */ dbvpostgresql("postgresql", "PostgreSQL", true, "postgres", "pgsql"),
    /* 28 */ dbvpowerquery("powerquery", "Power Query M Language", true),
    /* 29 */ dbvpresto("presto", "Presto", true, "prestodb"),
    /* 30 */ dbvredshift("redshift", "Amazon Redshift", true),
    /* 31 */ dbvsnowflake("snowflake", "Snowflake", true),
    /* 32 */ dbvsoql("soql", "Salesforce SOQL", true),
    /* 33 */ dbvsparksql("sparksql", "Apache Spark SQL", true, "spark"),
    /* 34 */ dbvsqlite("sqlite", "SQLite", true, "sqlite3"),
    /* 35 */ dbvstarrocks("starrocks", "StarRocks", true),
    /* 36 */ dbvsybase("sybase", "Sybase", true),
    /* 37 */ dbvteradata("teradata", "Teradata", true),
    /* 38 */ dbvtrino("trino", "Trino", true, "prestosql"),
    /* 39 */ dbvvertica("vertica", "Vertica", true),
    /* 40 */ dbvdatabricks("databricks", "Databricks", true),
    /* 41 */ dbvgaussdb("gaussdb", "GaussDB", true, "opengauss"),
    /* 42 */ dbvedb("edb", "EDB Postgres Advanced Server", true),
    /* 43 */ dbvdameng("dameng", "Dameng Database", true, "dm"),
    /* 44 */ dbvoceanbase("oceanbase", "OceanBase", true),
    /* 45 */ dbvsybasease("sybasease", "Sybase ASE", true),
    /* 46 */ dbvsqlanywhere("sqlanywhere", "SAP SQL Anywhere", true),
    /* 47 */ dbvsybaseiq("sybaseiq", "Sybase IQ", true);

    private static final Map<String, EDbVendor> LOOKUP_BY_KEY;

    static {
        Map<String, EDbVendor> map = new LinkedHashMap<>(160);
        for (EDbVendor vendor : values()) {
            map.put(vendor.name().toLowerCase(Locale.ROOT), vendor);
            map.put(vendor.primaryAlias.toLowerCase(Locale.ROOT), vendor);
            for (String alias : vendor.secondaryAliases) {
                String normalizedAlias = alias.toLowerCase(Locale.ROOT);
                map.putIfAbsent(normalizedAlias, vendor);
                map.putIfAbsent("dbv" + normalizedAlias, vendor);
            }
        }
        LOOKUP_BY_KEY = Collections.unmodifiableMap(map);
    }

    private final String primaryAlias;
    private final String description;
    private final boolean implemented;
    private final List<String> secondaryAliases;

    EDbVendor(String primaryAlias, String description, boolean implemented, String... secondaryAliases) {
        this.primaryAlias = primaryAlias;
        this.description = description;
        this.implemented = implemented;
        this.secondaryAliases = secondaryAliases == null || secondaryAliases.length == 0
                ? List.of()
                : List.of(secondaryAliases);
    }

    /**
     * Wire representation always uses the canonical {@code dbv*} enum identifier (e.g., {@code "dbvoracle"}).
     */
    @JsonValue
    public String wireName() {
        return name();
    }

    /**
     * Short dialect name stripped of the {@code "dbv"} prefix (e.g., {@code "oracle"}, {@code "postgresql"}).
     */
    public String primaryAlias() {
        return primaryAlias;
    }

    /**
     * Alias of {@link #primaryAlias()} used by schema directory paths ({@code data/schema/{user}/{shortName}/}).
     */
    public String shortName() {
        return primaryAlias;
    }

    /**
     * Human-readable dialect display name (e.g., {@code "Microsoft SQL Server"}).
     */
    public String description() {
        return description;
    }

    /**
     * Returns {@code true} for the 42 active SQL dialects and {@code false} for the 6 stubbed entries.
     */
    public boolean isImplemented() {
        return implemented;
    }

    public List<String> secondaryAliases() {
        return secondaryAliases;
    }

    /**
     * Returns the default dialect {@link IdentifierProfile} for this vendor ({@code 03 §2.1–§2.3}).
     */
    public IdentifierProfile identifierProfile() {
        return IdentifierProfile.forVendor(this);
    }

    /**
     * Checks whether {@code candidate} matches this vendor's {@code dbv*} name, primary alias, or secondary aliases.
     */
    public boolean matchesAlias(String candidate) {
        return find(candidate).map(v -> v == this).orElse(false);
    }

    /**
     * Case-insensitive lookup accepting both {@code "dbvoracle"} and {@code "oracle"} forms, plus secondary aliases.
     */
    public static Optional<EDbVendor> find(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        EDbVendor direct = LOOKUP_BY_KEY.get(key);
        if (direct != null) {
            return Optional.of(direct);
        }
        if (key.startsWith("dbv") && key.length() > 3) {
            return Optional.ofNullable(LOOKUP_BY_KEY.get(key.substring(3)));
        }
        return Optional.ofNullable(LOOKUP_BY_KEY.get("dbv" + key));
    }

    public static boolean isSupported(String raw) {
        return find(raw).isPresent();
    }

    public static EDbVendor fromStringOrDefault(String raw, EDbVendor defaultVendor) {
        return find(raw).orElse(defaultVendor);
    }

    /**
     * Jackson {@code @JsonCreator} factory accepting {@code null}/blank as {@code null} and resolving
     * both {@code "dbvoracle"} and {@code "oracle"} case-insensitively.
     *
     * @throws IllegalArgumentException if {@code raw} is non-blank and does not match any known dialect
     */
    @JsonCreator
    public static EDbVendor fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return find(raw).orElseThrow(
                () -> new IllegalArgumentException("Unsupported EDbVendor: '" + raw + "'"));
    }
}
