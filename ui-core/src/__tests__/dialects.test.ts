import { describe, expect, it } from "vitest";
import {
  ALL_OTHER_DIALECTS,
  JDBC_DIALECTS,
  ONBOARDING_CUSTOMER_REVENUE_SQL,
  POPULAR_DIALECTS,
  SAMPLE_SQL,
  SNOWFLAKE_QUERY_TYPES,
  TRIAL_SQL_LENGTH_ERROR,
  UI_DIALECTS,
  UI_DIALECT_KEYS,
  UNAUTHENTICATED_CUSTOM_SQL_WARNING,
  encodeCsvFormat,
  getDialectDisplayLabel,
  getDialectExamples,
  getJdbcDialectConfig,
  getSampleSql,
  groupDialectsForSelect,
  isBuiltInSampleOrExampleSql,
  validateClientSqlSubmission
} from "../index.js";

describe("Complete 39-Dialect Catalog, Verbatim Sample SQLs & Categorized Examples", () => {
  it("contains exactly 39 UI dialects split into 6 Popular and 33 All dialects", () => {
    expect(UI_DIALECTS).toHaveLength(39);
    expect(UI_DIALECT_KEYS).toHaveLength(39);
    expect(POPULAR_DIALECTS.map((d) => d.shortKey)).toEqual([
      "oracle",
      "mysql",
      "mssql",
      "postgresql",
      "snowflake",
      "bigquery"
    ]);
    expect(ALL_OTHER_DIALECTS).toHaveLength(33);

    expect(getDialectDisplayLabel("oracle")).toBe("Oracle");
    expect(getDialectDisplayLabel("mssql")).toBe("SQL Server");
    expect(getDialectDisplayLabel("azuresql")).toBe("Azure SQL");
    expect(getDialectDisplayLabel("hana")).toBe("SAP HANA");
    expect(getDialectDisplayLabel("sparksql")).toBe("Spark SQL");
    expect(getDialectDisplayLabel("db2")).toBe("Db2");
    expect(getDialectDisplayLabel("athena")).toBe("athena");

    const grouped = groupDialectsForSelect(["dbvoracle", "snowflake", "trino"]);
    expect(grouped).toHaveLength(2);
    expect(grouped[0]?.options.map((o) => o.shortKey)).toEqual(["oracle", "snowflake"]);
    expect(grouped[1]?.options.map((o) => o.shortKey)).toEqual(["trino"]);
  });

  it("provides non-empty verbatim sampleSQL for every one of the 39 dialects plus customer_revenue", () => {
    expect(ONBOARDING_CUSTOMER_REVENUE_SQL).toContain("CREATE VIEW customer_revenue AS");
    for (const key of UI_DIALECT_KEYS) {
      const sample = SAMPLE_SQL[key];
      expect(typeof sample).toBe("string");
      expect(sample.length).toBeGreaterThan(0);
      expect(getSampleSql(`dbv${key}`)).toBe(sample);
    }
    expect(SAMPLE_SQL.mdx).toBe("mdx");
    expect(SAMPLE_SQL.oracle).toContain("CREATE VIEW vsal");
    expect(SAMPLE_SQL.bigquery).toContain("MERGE dataset.DetailedInventory T");
    expect(SAMPLE_SQL.powerquery).toContain('Value.NativeQuery');
  });

  it("provides categorized Examples for all 25 explicit dialects and sample fallback for the 14 others", () => {
    const oracleExamples = getDialectExamples("oracle");
    expect(oracleExamples.map((e) => e.feature)).toEqual([
      "lineage",
      "join",
      "er",
      "callgraph"
    ]);
    expect(oracleExamples[3]?.sql).toContain("PACKAGE BODY order_workflow");

    const databricksExamples = getDialectExamples("databricks");
    expect(databricksExamples.map((e) => e.feature)).toEqual(["lineage", "join", "er"]);

    const clickhouseExamples = getDialectExamples("clickhouse");
    expect(clickhouseExamples).toHaveLength(1);
    expect(clickhouseExamples[0]?.id).toBe("clickhouse-sample");
    expect(clickhouseExamples[0]?.sql).toBe(SAMPLE_SQL.clickhouse);
  });

  it("enforces unauthenticated custom-SQL and trial 10k-character client guards", () => {
    expect(isBuiltInSampleOrExampleSql(ONBOARDING_CUSTOMER_REVENUE_SQL)).toBe(true);
    expect(isBuiltInSampleOrExampleSql(SAMPLE_SQL.snowflake)).toBe(true);
    expect(isBuiltInSampleOrExampleSql("SELECT custom_col FROM secret_table;")).toBe(false);

    const blockedCustom = validateClientSqlSubmission({
      sqltext: "SELECT custom_col FROM secret_table;",
      isLogin: false,
      loginDisable: false
    });
    expect(blockedCustom).toEqual({
      allowed: false,
      reason: "unauthenticated_custom_sql",
      message: UNAUTHENTICATED_CUSTOM_SQL_WARNING
    });

    const allowedSample = validateClientSqlSubmission({
      sqltext: SAMPLE_SQL.postgresql,
      isLogin: false
    });
    expect(allowedSample.allowed).toBe(true);

    const blockedTrial = validateClientSqlSubmission({
      sqltext: "SELECT 1;\n".repeat(1200),
      isLogin: true,
      trialVersion: true
    });
    expect(blockedTrial).toEqual({
      allowed: false,
      reason: "trial_length_exceeded",
      message: TRIAL_SQL_LENGTH_ERROR
    });
  });

  it("exposes exact 12 JDBC live database configs, 6-digit csvFormat encoder, and Snowflake query types", () => {
    expect(JDBC_DIALECTS).toHaveLength(12);
    expect(getJdbcDialectConfig("oracle")?.databaseFieldLabel).toBe("SID");
    expect(getJdbcDialectConfig("oracle")?.defaultPort).toBe(1521);
    expect(getJdbcDialectConfig("teradata")?.defaultPort).toBeUndefined();
    expect(getJdbcDialectConfig("teradata")?.databaseRequired).toBe(true);
    expect(getJdbcDialectConfig("snowflake")?.defaultPort).toBe(443);

    expect(
      encodeCsvFormat({
        csvDatabase: 1,
        csvSchema: 2,
        csvObjectType: 3,
        csvObjectName: 4,
        csvObjectCode: 5,
        csvComments: null
      })
    ).toBe("123450");

    expect(SNOWFLAKE_QUERY_TYPES.length).toBeGreaterThanOrEqual(71);
    expect(SNOWFLAKE_QUERY_TYPES).toContain("CREATE_TABLE_AS_SELECT");
    expect(SNOWFLAKE_QUERY_TYPES).toContain("MERGE");
  });
});
