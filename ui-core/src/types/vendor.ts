/**
 * Complete 48-vendor EDbVendor catalog in exact enum ordinal order (0..47)
 * per docs/re-spec/03-lineage-engine-and-graph-model.md §2.1.
 */

export const EDB_VENDOR_IDS = [
  "dbvaccess",
  "dbvansi",
  "dbvathena",
  "dbvazuresql",
  "dbvbigquery",
  "dbvclickhouse",
  "dbvcouchbase",
  "dbvdax",
  "dbvdb2",
  "dbvdoris",
  "dbvduckdb",
  "dbvexasol",
  "dbvfirebird",
  "dbvflink",
  "dbvgeneric",
  "dbvgreenplum",
  "dbvhana",
  "dbvhive",
  "dbvimpala",
  "dbvinformix",
  "dbvmdx",
  "dbvmysql",
  "dbvmssql",
  "dbvnetezza",
  "dbvodbc",
  "dbvopenedge",
  "dbvoracle",
  "dbvpostgresql",
  "dbvpowerquery",
  "dbvpresto",
  "dbvredshift",
  "dbvsnowflake",
  "dbvsoql",
  "dbvsparksql",
  "dbvsqlite",
  "dbvstarrocks",
  "dbvsybase",
  "dbvteradata",
  "dbvtrino",
  "dbvvertica",
  "dbvdatabricks",
  "dbvgaussdb",
  "dbvedb",
  "dbvdameng",
  "dbvoceanbase",
  "dbvsybasease",
  "dbvsqlanywhere",
  "dbvsybaseiq"
] as const;

export type EDbVendorId = (typeof EDB_VENDOR_IDS)[number];

export interface EDbVendorDescriptor {
  readonly ordinal: number;
  readonly id: EDbVendorId;
  readonly alias: string;
  readonly description: string;
  readonly implemented: boolean;
  readonly supportsCatalogs: boolean;
  readonly supportsSchemas: boolean;
  readonly quoteDelimiter: string;
}

export const EDB_VENDORS: readonly EDbVendorDescriptor[] = [
  { ordinal: 0, id: "dbvaccess", alias: "access", description: "Microsoft Access", implemented: false, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 1, id: "dbvansi", alias: "ansi", description: "ANSI SQL", implemented: false, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 2, id: "dbvathena", alias: "athena", description: "AWS Athena", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "`" },
  { ordinal: 3, id: "dbvazuresql", alias: "azuresql", description: "Azure SQL", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "[" },
  { ordinal: 4, id: "dbvbigquery", alias: "bigquery", description: "Google BigQuery", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "`" },
  { ordinal: 5, id: "dbvclickhouse", alias: "clickhouse", description: "ClickHouse", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 6, id: "dbvcouchbase", alias: "couchbase", description: "Couchbase", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "`" },
  { ordinal: 7, id: "dbvdax", alias: "dax", description: "Microsoft DAX", implemented: false, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "'" },
  { ordinal: 8, id: "dbvdb2", alias: "db2", description: "IBM DB2", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 9, id: "dbvdoris", alias: "doris", description: "Apache Doris", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "`" },
  { ordinal: 10, id: "dbvduckdb", alias: "duckdb", description: "DuckDB", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 11, id: "dbvexasol", alias: "exasol", description: "Exasol", implemented: false, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 12, id: "dbvfirebird", alias: "firebird", description: "Firebird", implemented: false, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 13, id: "dbvflink", alias: "flink", description: "Apache Flink SQL", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "`" },
  { ordinal: 14, id: "dbvgeneric", alias: "generic", description: "Generic SQL", implemented: false, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 15, id: "dbvgreenplum", alias: "greenplum", description: "Greenplum", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 16, id: "dbvhana", alias: "hana", description: "SAP HANA", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 17, id: "dbvhive", alias: "hive", description: "Apache Hive", implemented: true, supportsCatalogs: true, supportsSchemas: false, quoteDelimiter: "`" },
  { ordinal: 18, id: "dbvimpala", alias: "impala", description: "Apache Impala", implemented: true, supportsCatalogs: true, supportsSchemas: false, quoteDelimiter: "`" },
  { ordinal: 19, id: "dbvinformix", alias: "informix", description: "IBM Informix", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 20, id: "dbvmdx", alias: "mdx", description: "MDX", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "[" },
  { ordinal: 21, id: "dbvmysql", alias: "mysql", description: "MySQL", implemented: true, supportsCatalogs: true, supportsSchemas: false, quoteDelimiter: "`" },
  { ordinal: 22, id: "dbvmssql", alias: "mssql", description: "Microsoft SQL Server", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "[" },
  { ordinal: 23, id: "dbvnetezza", alias: "netezza", description: "IBM Netezza", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 24, id: "dbvodbc", alias: "odbc", description: "ODBC", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 25, id: "dbvopenedge", alias: "openedge", description: "Progress OpenEdge", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 26, id: "dbvoracle", alias: "oracle", description: "Oracle", implemented: true, supportsCatalogs: false, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 27, id: "dbvpostgresql", alias: "postgresql", description: "PostgreSQL", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 28, id: "dbvpowerquery", alias: "powerquery", description: "Power Query M Language", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 29, id: "dbvpresto", alias: "presto", description: "Presto", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 30, id: "dbvredshift", alias: "redshift", description: "Amazon Redshift", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 31, id: "dbvsnowflake", alias: "snowflake", description: "Snowflake", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 32, id: "dbvsoql", alias: "soql", description: "Salesforce SOQL", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 33, id: "dbvsparksql", alias: "sparksql", description: "Apache Spark SQL", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "`" },
  { ordinal: 34, id: "dbvsqlite", alias: "sqlite", description: "SQLite", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 35, id: "dbvstarrocks", alias: "starrocks", description: "StarRocks", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "`" },
  { ordinal: 36, id: "dbvsybase", alias: "sybase", description: "Sybase", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "[" },
  { ordinal: 37, id: "dbvteradata", alias: "teradata", description: "Teradata", implemented: true, supportsCatalogs: true, supportsSchemas: false, quoteDelimiter: "\"" },
  { ordinal: 38, id: "dbvtrino", alias: "trino", description: "Trino", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 39, id: "dbvvertica", alias: "vertica", description: "Vertica", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 40, id: "dbvdatabricks", alias: "databricks", description: "Databricks", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "`" },
  { ordinal: 41, id: "dbvgaussdb", alias: "gaussdb", description: "GaussDB", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 42, id: "dbvedb", alias: "edb", description: "EDB Postgres Advanced Server", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 43, id: "dbvdameng", alias: "dameng", description: "Dameng Database", implemented: true, supportsCatalogs: false, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 44, id: "dbvoceanbase", alias: "oceanbase", description: "OceanBase", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "`" },
  { ordinal: 45, id: "dbvsybasease", alias: "sybasease", description: "Sybase ASE", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "[" },
  { ordinal: 46, id: "dbvsqlanywhere", alias: "sqlanywhere", description: "SAP SQL Anywhere", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "\"" },
  { ordinal: 47, id: "dbvsybaseiq", alias: "sybaseiq", description: "Sybase IQ", implemented: true, supportsCatalogs: true, supportsSchemas: true, quoteDelimiter: "[" }
] as const;

const SECONDARY_VENDOR_ALIASES: Record<string, EDbVendorId> = {
  sqlserver: "dbvmssql",
  "sql server": "dbvmssql",
  tsql: "dbvmssql",
  azure: "dbvazuresql",
  "azure sql": "dbvazuresql",
  postgres: "dbvpostgresql",
  pgsql: "dbvpostgresql",
  "sap hana": "dbvhana",
  saphana: "dbvhana",
  spark: "dbvsparksql",
  "spark sql": "dbvsparksql"
};

const VENDOR_BY_ID = new Map<string, EDbVendorDescriptor>(
  EDB_VENDORS.flatMap((v) => [
    [v.id, v],
    [v.alias, v]
  ])
);

/**
 * Resolves a known short key ("oracle"), secondary alias ("SQL Server"),
 * or prefixed key ("dbvoracle") to a canonical EDbVendorId, returning `null`
 * if the input does not match any registered dialect.
 */
export function resolveKnownDbVendor(vendor?: string | null): EDbVendorId | null {
  if (!vendor) {
    return null;
  }
  const cleaned = vendor.trim().toLowerCase();
  if (!cleaned) {
    return null;
  }
  const direct = VENDOR_BY_ID.get(cleaned);
  if (direct) {
    return direct.id;
  }
  const secondary = SECONDARY_VENDOR_ALIASES[cleaned];
  if (secondary) {
    return secondary;
  }
  const withPrefix = cleaned.startsWith("dbv") ? cleaned : `dbv${cleaned}`;
  const matched = VENDOR_BY_ID.get(withPrefix);
  return matched ? matched.id : null;
}

/**
 * Normalizes any short key ("oracle"), secondary alias ("sqlserver"),
 * or prefixed key ("dbvoracle") to a canonical EDbVendorId (defaulting to "dbvoracle").
 */
export function normalizeDbVendor(vendor?: string | null): EDbVendorId {
  return resolveKnownDbVendor(vendor) ?? "dbvoracle";
}

/**
 * Strips the leading "dbv" prefix if present (e.g. "dbvoracle" -> "oracle").
 */
export function toShortVendorKey(vendor?: string | null): string {
  const id = normalizeDbVendor(vendor);
  return id.slice(3);
}

/**
 * Formats a vendor identifier with the canonical "dbv" prefix (e.g. "oracle" -> "dbvoracle").
 */
export function toApiDbVendor(vendor?: string | null): EDbVendorId {
  return normalizeDbVendor(vendor);
}
