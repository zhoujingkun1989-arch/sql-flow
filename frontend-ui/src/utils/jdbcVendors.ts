import {
  JDBC_DIALECTS,
  type JdbcDialectConfig,
  SNOWFLAKE_QUERY_TYPES,
  encodeCsvFormat,
  type CsvFormatPositions
} from "@sqlflow/ui-core";

export interface ExtendedJdbcVendorConfig {
  readonly shortKey: string;
  readonly dbvendor: string;
  readonly label: string;
  readonly defaultPort?: number;
  readonly databaseRequired: boolean;
  readonly databaseFieldLabel: string;
}

/**
 * Extended 16 JDBC Vendor Configurations covering all 14+ live database harvesters
 * (`OBL-075..076`, `OBL-106`, `04 §9.3`):
 * Includes the 12 core `JDBC_DIALECTS` from `@sqlflow/ui-core` plus
 * `impala`, `clickhouse`, `bigquery`, and `athena`.
 */
export const EXTENDED_JDBC_VENDORS: readonly ExtendedJdbcVendorConfig[] = [
  ...JDBC_DIALECTS,
  {
    shortKey: "impala",
    dbvendor: "dbvimpala",
    label: "Impala",
    defaultPort: 21050,
    databaseRequired: false,
    databaseFieldLabel: "database"
  },
  {
    shortKey: "clickhouse",
    dbvendor: "dbvclickhouse",
    label: "ClickHouse",
    defaultPort: 8123,
    databaseRequired: false,
    databaseFieldLabel: "database"
  },
  {
    shortKey: "bigquery",
    dbvendor: "dbvbigquery",
    label: "BigQuery",
    defaultPort: 443,
    databaseRequired: true,
    databaseFieldLabel: "projectId"
  },
  {
    shortKey: "athena",
    dbvendor: "dbvathena",
    label: "Athena",
    defaultPort: 443,
    databaseRequired: false,
    databaseFieldLabel: "catalog"
  }
];

export function findExtendedJdbcVendor(vendorKey: string): ExtendedJdbcVendorConfig {
  const clean = vendorKey.trim().toLowerCase();
  return (
    EXTENDED_JDBC_VENDORS.find(
      (v) => v.shortKey === clean || v.dbvendor === clean
    ) ?? EXTENDED_JDBC_VENDORS[1]! // defaults to Oracle (index 1)
  );
}

export const CRON_SCHEDULE_PRESETS: ReadonlyArray<{ label: string; cron: string }> = [
  { label: "Every night at 02:00", cron: "0 0 2 * * ?" },
  { label: "Every 6 hours", cron: "0 0 */6 * * ?" },
  { label: "Every Monday at 03:00", cron: "0 0 3 ? * MON" },
  { label: "1st of every month at 01:00", cron: "0 0 1 1 * ?" }
];

export { SNOWFLAKE_QUERY_TYPES, encodeCsvFormat, type CsvFormatPositions, type JdbcDialectConfig };
