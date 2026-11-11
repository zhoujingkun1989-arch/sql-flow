/**
 * Complete 39-Dialect UI Catalog, Popular vs All Dialects Groupings,
 * Vendor Badges, 12 Live JDBC Connection Configs, CSV Format Encoder,
 * and Snowflake Query History SQL Type Catalog.
 * Reference: docs/re-spec/04-frontend-ui-and-widget.md §5.2, §9.3.
 */

import { type EDbVendorId, normalizeDbVendor, toShortVendorKey } from "../types/vendor.js";

export type DialectSelectGroup = "Popular" | "All dialects";

export type ExampleFeature = "lineage" | "join" | "er" | "callgraph";

export const UI_DIALECT_KEYS = [
  // Popular (6)
  "oracle",
  "mysql",
  "mssql",
  "postgresql",
  "snowflake",
  "bigquery",
  // All dialects (33)
  "athena",
  "azuresql",
  "clickhouse",
  "couchbase",
  "dameng",
  "databricks",
  "db2",
  "doris",
  "duckdb",
  "edb",
  "flink",
  "gaussdb",
  "greenplum",
  "hana",
  "hive",
  "impala",
  "informix",
  "mdx",
  "netezza",
  "oceanbase",
  "odbc",
  "openedge",
  "powerquery",
  "presto",
  "redshift",
  "soql",
  "sparksql",
  "sqlite",
  "starrocks",
  "sybase",
  "teradata",
  "trino",
  "vertica"
] as const;

export type UiDialectKey = (typeof UI_DIALECT_KEYS)[number];

export const DEFAULT_UI_DIALECT: UiDialectKey = "oracle";

export interface VendorBadgeMeta {
  readonly code: string;
  readonly color: string;
  readonly bgColor: string;
}

export interface UiDialectDescriptor {
  readonly index: number;
  readonly shortKey: UiDialectKey;
  readonly dbvendor: EDbVendorId;
  readonly group: DialectSelectGroup;
  readonly label: string;
  readonly badge: VendorBadgeMeta;
  readonly exampleFeatures: readonly ExampleFeature[];
  readonly usesSampleFallbackForExamples: boolean;
}

export const UI_DIALECTS: readonly UiDialectDescriptor[] = [
  // Popular (1..6)
  {
    index: 1,
    shortKey: "oracle",
    dbvendor: "dbvoracle",
    group: "Popular",
    label: "Oracle",
    badge: { code: "ORA", color: "#c74634", bgColor: "#fdecea" },
    exampleFeatures: ["lineage", "join", "er", "callgraph"],
    usesSampleFallbackForExamples: false
  },
  {
    index: 2,
    shortKey: "mysql",
    dbvendor: "dbvmysql",
    group: "Popular",
    label: "MySQL",
    badge: { code: "MY", color: "#00758f", bgColor: "#e6f4f7" },
    exampleFeatures: ["lineage", "join", "er", "callgraph"],
    usesSampleFallbackForExamples: false
  },
  {
    index: 3,
    shortKey: "mssql",
    dbvendor: "dbvmssql",
    group: "Popular",
    label: "SQL Server",
    badge: { code: "MS", color: "#a91d22", bgColor: "#fbe9ea" },
    exampleFeatures: ["lineage", "join", "er", "callgraph"],
    usesSampleFallbackForExamples: false
  },
  {
    index: 4,
    shortKey: "postgresql",
    dbvendor: "dbvpostgresql",
    group: "Popular",
    label: "PostgreSQL",
    badge: { code: "PG", color: "#336791", bgColor: "#eaf0f6" },
    exampleFeatures: ["lineage", "join", "er", "callgraph"],
    usesSampleFallbackForExamples: false
  },
  {
    index: 5,
    shortKey: "snowflake",
    dbvendor: "dbvsnowflake",
    group: "Popular",
    label: "Snowflake",
    badge: { code: "SF", color: "#29b5e8", bgColor: "#e8f7fd" },
    exampleFeatures: ["lineage", "join", "er", "callgraph"],
    usesSampleFallbackForExamples: false
  },
  {
    index: 6,
    shortKey: "bigquery",
    dbvendor: "dbvbigquery",
    group: "Popular",
    label: "BigQuery",
    badge: { code: "BQ", color: "#4285f4", bgColor: "#ecf3fe" },
    exampleFeatures: ["lineage", "join", "er", "callgraph"],
    usesSampleFallbackForExamples: false
  },

  // All dialects (7..39)
  {
    index: 7,
    shortKey: "athena",
    dbvendor: "dbvathena",
    group: "All dialects",
    label: "athena",
    badge: { code: "ATH", color: "#ff9900", bgColor: "#fff5e6" },
    exampleFeatures: ["lineage", "join"],
    usesSampleFallbackForExamples: false
  },
  {
    index: 8,
    shortKey: "azuresql",
    dbvendor: "dbvazuresql",
    group: "All dialects",
    label: "Azure SQL",
    badge: { code: "AZ", color: "#0078d4", bgColor: "#e5f1fb" },
    exampleFeatures: ["lineage", "join", "er", "callgraph"],
    usesSampleFallbackForExamples: false
  },
  {
    index: 9,
    shortKey: "clickhouse",
    dbvendor: "dbvclickhouse",
    group: "All dialects",
    label: "ClickHouse",
    badge: { code: "CH", color: "#b38600", bgColor: "#fffbe6" },
    exampleFeatures: ["lineage"],
    usesSampleFallbackForExamples: true
  },
  {
    index: 10,
    shortKey: "couchbase",
    dbvendor: "dbvcouchbase",
    group: "All dialects",
    label: "couchbase",
    badge: { code: "CB", color: "#ea2328", bgColor: "#fde9ea" },
    exampleFeatures: ["lineage", "join"],
    usesSampleFallbackForExamples: false
  },
  {
    index: 11,
    shortKey: "dameng",
    dbvendor: "dbvdameng",
    group: "All dialects",
    label: "dameng",
    badge: { code: "DM", color: "#c0392b", bgColor: "#f9ebea" },
    exampleFeatures: ["lineage"],
    usesSampleFallbackForExamples: true
  },
  {
    index: 12,
    shortKey: "databricks",
    dbvendor: "dbvdatabricks",
    group: "All dialects",
    label: "Databricks",
    badge: { code: "DBX", color: "#ff3621", bgColor: "#ffebe9" },
    exampleFeatures: ["lineage", "join", "er"],
    usesSampleFallbackForExamples: false
  },
  {
    index: 13,
    shortKey: "db2",
    dbvendor: "dbvdb2",
    group: "All dialects",
    label: "Db2",
    badge: { code: "DB2", color: "#0f62fe", bgColor: "#e8f0ff" },
    exampleFeatures: ["lineage", "join", "er", "callgraph"],
    usesSampleFallbackForExamples: false
  },
  {
    index: 14,
    shortKey: "doris",
    dbvendor: "dbvdoris",
    group: "All dialects",
    label: "doris",
    badge: { code: "DOR", color: "#2563eb", bgColor: "#eff6ff" },
    exampleFeatures: ["lineage"],
    usesSampleFallbackForExamples: true
  },
  {
    index: 15,
    shortKey: "duckdb",
    dbvendor: "dbvduckdb",
    group: "All dialects",
    label: "duckdb",
    badge: { code: "DCK", color: "#8a6d00", bgColor: "#fefce8" },
    exampleFeatures: ["lineage"],
    usesSampleFallbackForExamples: true
  },
  {
    index: 16,
    shortKey: "edb",
    dbvendor: "dbvedb",
    group: "All dialects",
    label: "edb",
    badge: { code: "EDB", color: "#d9480f", bgColor: "#fff4e6" },
    exampleFeatures: ["lineage"],
    usesSampleFallbackForExamples: true
  },
  {
    index: 17,
    shortKey: "flink",
    dbvendor: "dbvflink",
    group: "All dialects",
    label: "flink",
    badge: { code: "FLK", color: "#e6526f", bgColor: "#fdf2f4" },
    exampleFeatures: ["lineage"],
    usesSampleFallbackForExamples: true
  },
  {
    index: 18,
    shortKey: "gaussdb",
    dbvendor: "dbvgaussdb",
    group: "All dialects",
    label: "gaussdb",
    badge: { code: "GS", color: "#cf1322", bgColor: "#fff1f0" },
    exampleFeatures: ["lineage", "join", "er", "callgraph"],
    usesSampleFallbackForExamples: false
  },
  {
    index: 19,
    shortKey: "greenplum",
    dbvendor: "dbvgreenplum",
    group: "All dialects",
    label: "Greenplum",
    badge: { code: "GP", color: "#00875a", bgColor: "#e6f6f0" },
    exampleFeatures: ["lineage", "join", "er"],
    usesSampleFallbackForExamples: false
  },
  {
    index: 20,
    shortKey: "hana",
    dbvendor: "dbvhana",
    group: "All dialects",
    label: "SAP HANA",
    badge: { code: "HNA", color: "#0070f2", bgColor: "#e6f1fe" },
    exampleFeatures: ["lineage", "join", "er", "callgraph"],
    usesSampleFallbackForExamples: false
  },
  {
    index: 21,
    shortKey: "hive",
    dbvendor: "dbvhive",
    group: "All dialects",
    label: "hive",
    badge: { code: "HIV", color: "#ad7a00", bgColor: "#fff9db" },
    exampleFeatures: ["lineage", "join", "er"],
    usesSampleFallbackForExamples: false
  },
  {
    index: 22,
    shortKey: "impala",
    dbvendor: "dbvimpala",
    group: "All dialects",
    label: "impala",
    badge: { code: "IMP", color: "#1d4ed8", bgColor: "#eff6ff" },
    exampleFeatures: ["lineage", "join", "er"],
    usesSampleFallbackForExamples: false
  },
  {
    index: 23,
    shortKey: "informix",
    dbvendor: "dbvinformix",
    group: "All dialects",
    label: "informix",
    badge: { code: "IFX", color: "#1e40af", bgColor: "#eef2ff" },
    exampleFeatures: ["lineage", "join", "er"],
    usesSampleFallbackForExamples: false
  },
  {
    index: 24,
    shortKey: "mdx",
    dbvendor: "dbvmdx",
    group: "All dialects",
    label: "mdx",
    badge: { code: "MDX", color: "#6d28d9", bgColor: "#f5f3ff" },
    exampleFeatures: ["lineage"],
    usesSampleFallbackForExamples: true
  },
  {
    index: 25,
    shortKey: "netezza",
    dbvendor: "dbvnetezza",
    group: "All dialects",
    label: "netezza",
    badge: { code: "NZ", color: "#0f62fe", bgColor: "#edf5ff" },
    exampleFeatures: ["lineage", "join", "er", "callgraph"],
    usesSampleFallbackForExamples: false
  },
  {
    index: 26,
    shortKey: "oceanbase",
    dbvendor: "dbvoceanbase",
    group: "All dialects",
    label: "oceanbase",
    badge: { code: "OB", color: "#0284c7", bgColor: "#e0f2fe" },
    exampleFeatures: ["lineage"],
    usesSampleFallbackForExamples: true
  },
  {
    index: 27,
    shortKey: "odbc",
    dbvendor: "dbvodbc",
    group: "All dialects",
    label: "odbc",
    badge: { code: "ODB", color: "#475569", bgColor: "#f1f5f9" },
    exampleFeatures: ["lineage"],
    usesSampleFallbackForExamples: true
  },
  {
    index: 28,
    shortKey: "openedge",
    dbvendor: "dbvopenedge",
    group: "All dialects",
    label: "openedge",
    badge: { code: "OE", color: "#43a047", bgColor: "#e8f5e9" },
    exampleFeatures: ["lineage", "join", "er"],
    usesSampleFallbackForExamples: false
  },
  {
    index: 29,
    shortKey: "powerquery",
    dbvendor: "dbvpowerquery",
    group: "All dialects",
    label: "powerquery",
    badge: { code: "PQ", color: "#107c41", bgColor: "#e7f4ed" },
    exampleFeatures: ["lineage"],
    usesSampleFallbackForExamples: true
  },
  {
    index: 30,
    shortKey: "presto",
    dbvendor: "dbvpresto",
    group: "All dialects",
    label: "presto",
    badge: { code: "PRS", color: "#5890ff", bgColor: "#eef4ff" },
    exampleFeatures: ["lineage", "join"],
    usesSampleFallbackForExamples: false
  },
  {
    index: 31,
    shortKey: "redshift",
    dbvendor: "dbvredshift",
    group: "All dialects",
    label: "Redshift",
    badge: { code: "RS", color: "#8c4fff", bgColor: "#f3ecff" },
    exampleFeatures: ["lineage", "join", "er", "callgraph"],
    usesSampleFallbackForExamples: false
  },
  {
    index: 32,
    shortKey: "soql",
    dbvendor: "dbvsoql",
    group: "All dialects",
    label: "soql",
    badge: { code: "SOQ", color: "#00a1e0", bgColor: "#e6f6fc" },
    exampleFeatures: ["lineage"],
    usesSampleFallbackForExamples: true
  },
  {
    index: 33,
    shortKey: "sparksql",
    dbvendor: "dbvsparksql",
    group: "All dialects",
    label: "Spark SQL",
    badge: { code: "SPK", color: "#e25a1c", bgColor: "#fdf0e9" },
    exampleFeatures: ["lineage", "join", "er"],
    usesSampleFallbackForExamples: false
  },
  {
    index: 34,
    shortKey: "sqlite",
    dbvendor: "dbvsqlite",
    group: "All dialects",
    label: "sqlite",
    badge: { code: "SQL", color: "#003b57", bgColor: "#e6eef2" },
    exampleFeatures: ["lineage"],
    usesSampleFallbackForExamples: true
  },
  {
    index: 35,
    shortKey: "starrocks",
    dbvendor: "dbvstarrocks",
    group: "All dialects",
    label: "starrocks",
    badge: { code: "SR", color: "#3b5bdb", bgColor: "#edf2ff" },
    exampleFeatures: ["lineage"],
    usesSampleFallbackForExamples: true
  },
  {
    index: 36,
    shortKey: "sybase",
    dbvendor: "dbvsybase",
    group: "All dialects",
    label: "Sybase",
    badge: { code: "SYB", color: "#0b5cab", bgColor: "#e8f1fa" },
    exampleFeatures: ["lineage", "join", "er", "callgraph"],
    usesSampleFallbackForExamples: false
  },
  {
    index: 37,
    shortKey: "teradata",
    dbvendor: "dbvteradata",
    group: "All dialects",
    label: "Teradata",
    badge: { code: "TD", color: "#f37440", bgColor: "#fef1ec" },
    exampleFeatures: ["lineage", "join", "er", "callgraph"],
    usesSampleFallbackForExamples: false
  },
  {
    index: 38,
    shortKey: "trino",
    dbvendor: "dbvtrino",
    group: "All dialects",
    label: "trino",
    badge: { code: "TRN", color: "#dd00a1", bgColor: "#fce8f6" },
    exampleFeatures: ["lineage"],
    usesSampleFallbackForExamples: true
  },
  {
    index: 39,
    shortKey: "vertica",
    dbvendor: "dbvvertica",
    group: "All dialects",
    label: "vertica",
    badge: { code: "VRT", color: "#01a982", bgColor: "#e6f7f3" },
    exampleFeatures: ["lineage", "join", "er", "callgraph"],
    usesSampleFallbackForExamples: false
  }
] as const;

export const POPULAR_DIALECTS: readonly UiDialectDescriptor[] = UI_DIALECTS.filter(
  (d) => d.group === "Popular"
);

export const ALL_OTHER_DIALECTS: readonly UiDialectDescriptor[] = UI_DIALECTS.filter(
  (d) => d.group === "All dialects"
);

const DIALECT_BY_KEY = new Map<string, UiDialectDescriptor>(
  UI_DIALECTS.flatMap((d) => [
    [d.shortKey, d],
    [d.dbvendor, d],
    [d.label.toLowerCase(), d]
  ])
);

export function isUiDialect(vendor?: string | null): vendor is UiDialectKey {
  if (!vendor) {
    return false;
  }
  const short = toShortVendorKey(vendor);
  return DIALECT_BY_KEY.has(short);
}

export function getUiDialect(vendor?: string | null): UiDialectDescriptor {
  if (!vendor) {
    return DIALECT_BY_KEY.get(DEFAULT_UI_DIALECT)!;
  }
  const cleaned = vendor.trim().toLowerCase();
  const direct = DIALECT_BY_KEY.get(cleaned);
  if (direct) {
    return direct;
  }
  const normalized = normalizeDbVendor(cleaned);
  return DIALECT_BY_KEY.get(normalized) ?? DIALECT_BY_KEY.get(DEFAULT_UI_DIALECT)!;
}

export function getDialectDisplayLabel(vendor?: string | null): string {
  return getUiDialect(vendor).label;
}

export function groupDialectsForSelect(
  supportedVendors?: readonly string[] | null
): Array<{ label: DialectSelectGroup; options: UiDialectDescriptor[] }> {
  const allowedSet =
    supportedVendors && supportedVendors.length > 0
      ? new Set(supportedVendors.map((v) => toShortVendorKey(v)))
      : null;

  const filterFn = (d: UiDialectDescriptor) =>
    allowedSet === null || allowedSet.has(d.shortKey);

  const groups: Array<{ label: DialectSelectGroup; options: UiDialectDescriptor[] }> = [
    {
      label: "Popular",
      options: POPULAR_DIALECTS.filter(filterFn)
    },
    {
      label: "All dialects",
      options: ALL_OTHER_DIALECTS.filter(filterFn)
    }
  ];
  return groups.filter((group) => group.options.length > 0);
}

/**
 * Exact 12 Supported Live-Database (JDBC) Vendors, Default Ports & Required-Database Rules (04 §9.3).
 */
export interface JdbcDialectConfig {
  readonly shortKey: UiDialectKey;
  readonly dbvendor: EDbVendorId;
  readonly label: string;
  readonly defaultPort?: number;
  readonly databaseRequired: boolean;
  /** Oracle relabels `database` input to `"SID"` */
  readonly databaseFieldLabel: "database" | "SID";
}

export const JDBC_DIALECTS: readonly JdbcDialectConfig[] = [
  {
    shortKey: "azuresql",
    dbvendor: "dbvazuresql",
    label: "Azure SQL",
    defaultPort: 1433,
    databaseRequired: true,
    databaseFieldLabel: "database"
  },
  {
    shortKey: "oracle",
    dbvendor: "dbvoracle",
    label: "Oracle",
    defaultPort: 1521,
    databaseRequired: true,
    databaseFieldLabel: "SID"
  },
  {
    shortKey: "mssql",
    dbvendor: "dbvmssql",
    label: "SQL Server",
    defaultPort: 1433,
    databaseRequired: false,
    databaseFieldLabel: "database"
  },
  {
    shortKey: "mysql",
    dbvendor: "dbvmysql",
    label: "MySQL",
    defaultPort: 3306,
    databaseRequired: false,
    databaseFieldLabel: "database"
  },
  {
    shortKey: "postgresql",
    dbvendor: "dbvpostgresql",
    label: "PostgreSQL",
    defaultPort: 5432,
    databaseRequired: true,
    databaseFieldLabel: "database"
  },
  {
    shortKey: "greenplum",
    dbvendor: "dbvgreenplum",
    label: "Greenplum",
    defaultPort: 5432,
    databaseRequired: false,
    databaseFieldLabel: "database"
  },
  {
    shortKey: "netezza",
    dbvendor: "dbvnetezza",
    label: "netezza",
    defaultPort: 5480,
    databaseRequired: true,
    databaseFieldLabel: "database"
  },
  {
    shortKey: "snowflake",
    dbvendor: "dbvsnowflake",
    label: "Snowflake",
    defaultPort: 443,
    databaseRequired: false,
    databaseFieldLabel: "database"
  },
  {
    shortKey: "redshift",
    dbvendor: "dbvredshift",
    label: "Redshift",
    defaultPort: 5439,
    databaseRequired: true,
    databaseFieldLabel: "database"
  },
  {
    shortKey: "teradata",
    dbvendor: "dbvteradata",
    label: "Teradata",
    defaultPort: undefined,
    databaseRequired: true,
    databaseFieldLabel: "database"
  },
  {
    shortKey: "db2",
    dbvendor: "dbvdb2",
    label: "Db2",
    defaultPort: 50000,
    databaseRequired: false,
    databaseFieldLabel: "database"
  },
  {
    shortKey: "hive",
    dbvendor: "dbvhive",
    label: "hive",
    defaultPort: 10000,
    databaseRequired: false,
    databaseFieldLabel: "database"
  }
] as const;

const JDBC_BY_KEY = new Map<string, JdbcDialectConfig>(
  JDBC_DIALECTS.flatMap((j) => [
    [j.shortKey, j],
    [j.dbvendor, j]
  ])
);

export function getJdbcDialectConfig(vendor?: string | null): JdbcDialectConfig | undefined {
  if (!vendor) {
    return undefined;
  }
  const normalized = normalizeDbVendor(vendor);
  return JDBC_BY_KEY.get(normalized);
}

/**
 * Encodes the 6 CSV column positions (`1..6` or empty/`0`) into the exact 6-digit `csvFormat` wire string (04 §9.3).
 */
export interface CsvFormatPositions {
  csvDatabase?: number | string | null;
  csvSchema?: number | string | null;
  csvObjectType?: number | string | null;
  csvObjectName?: number | string | null;
  csvObjectCode?: number | string | null;
  csvComments?: number | string | null;
}

function toCsvDigit(val?: number | string | null): string {
  if (val === undefined || val === null || val === "") {
    return "0";
  }
  const n = typeof val === "number" ? val : Number.parseInt(String(val), 10);
  if (!Number.isFinite(n) || n < 1 || n > 6) {
    return "0";
  }
  return String(n);
}

export function encodeCsvFormat(positions: CsvFormatPositions): string {
  return [
    toCsvDigit(positions.csvDatabase),
    toCsvDigit(positions.csvSchema),
    toCsvDigit(positions.csvObjectType),
    toCsvDigit(positions.csvObjectName),
    toCsvDigit(positions.csvObjectCode),
    toCsvDigit(positions.csvComments)
  ].join("");
}

/**
 * Snowflake Query History (`snowflakequeryhistory`) QUERY_TYPE catalog (04 §9.3).
 */
export const SNOWFLAKE_QUERY_TYPES = [
  "ALTER",
  "ALTER_NETWORK_POLICY",
  "ALTER_SESSION",
  "ALTER_TABLE",
  "ALTER_TABLE_ADD_COLUMN",
  "ALTER_TABLE_DROP_CLUSTERING_KEY",
  "ALTER_TABLE_MODIFY_COLUMN",
  "ALTER_USER",
  "ALTER_WAREHOUSE",
  "BEGIN_TRANSACTION",
  "CALL",
  "COMMIT",
  "COPY",
  "CREATE",
  "CREATE_CONSTRAINT",
  "CREATE_EXTERNAL_TABLE",
  "CREATE_FUNCTION",
  "CREATE_MASKING_POLICY",
  "CREATE_NETWORK_POLICY",
  "CREATE_PIPE",
  "CREATE_PROCEDURE",
  "CREATE_ROLE",
  "CREATE_ROW_ACCESS_POLICY",
  "CREATE_SCHEMA",
  "CREATE_SEQUENCE",
  "CREATE_STAGE",
  "CREATE_STREAM",
  "CREATE_TABLE",
  "CREATE_TABLE_AS_SELECT",
  "CREATE_TASK",
  "CREATE_TEMP_TABLE",
  "CREATE_USER",
  "CREATE_VIEW",
  "DELETE",
  "DESCRIBE",
  "DESCRIBE_QUERY",
  "DROP",
  "DROP_CONSTRAINT",
  "DROP_FUNCTION",
  "DROP_MASKING_POLICY",
  "DROP_NETWORK_POLICY",
  "DROP_PIPE",
  "DROP_PROCEDURE",
  "DROP_ROLE",
  "DROP_ROW_ACCESS_POLICY",
  "DROP_STAGE",
  "DROP_STREAM",
  "DROP_TASK",
  "DROP_USER",
  "EXECUTE_IMMEDIATE",
  "EXECUTE_TASK",
  "EXPLAIN",
  "GET_FILES",
  "GRANT",
  "INSERT",
  "LIST_FILES",
  "MERGE",
  "MULTI_STATEMENT",
  "PUT_FILES",
  "REMOVE_FILES",
  "RENAME_COLUMN",
  "RENAME_TABLE",
  "RENAME_VIEW",
  "RESTORE",
  "REVOKE",
  "ROLLBACK",
  "SELECT",
  "SET",
  "SHOW",
  "TRUNCATE_TABLE",
  "UNLOAD",
  "UNSET",
  "UPDATE",
  "USE"
] as const;

export type SnowflakeQueryType = (typeof SNOWFLAKE_QUERY_TYPES)[number];
