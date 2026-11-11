package io.sqlflow.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import io.sqlflow.connectors.extractor.CsvMetadataExtractor;
import io.sqlflow.connectors.extractor.MultiSourceFileExtractorService;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.SchemaObject;
import io.sqlflow.contracts.lineage.SqlInfo;
import io.sqlflow.contracts.spi.FileExtractorSpi.ExtractionRequest;
import io.sqlflow.contracts.spi.FileExtractorSpi.ExtractionResult;
import io.sqlflow.contracts.spi.FileExtractorSpi.ExtractorFormat;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class FileExtractorsTest {

    private final MultiSourceFileExtractorService extractor = new MultiSourceFileExtractorService();

    @Test
    void dbtExtractorParsesManifestV12CatalogJsonAndZippedTargetCompiledSqlFiles() throws Exception {
        String manifestJson = """
                {
                  "metadata": {
                    "dbt_schema_version": "https://schemas.getdbt.com/dbt/manifest/v12.json",
                    "adapter_type": "snowflake"
                  },
                  "sources": {
                    "source.jaffle_shop.raw.orders": {
                      "database": "RAW_DB",
                      "schema": "JAFFLE",
                      "source_name": "raw",
                      "name": "orders",
                      "identifier": "RAW_ORDERS",
                      "columns": {
                        "id": { "name": "ID", "description": "Primary order key" },
                        "amount": { "name": "AMOUNT", "data_type": "NUMBER(18,2)" }
                      }
                    }
                  },
                  "nodes": {
                    "model.jaffle_shop.stg_orders": {
                      "resource_type": "model",
                      "name": "stg_orders",
                      "alias": "STG_ORDERS",
                      "database": "ANALYTICS_DB",
                      "schema": "STAGING",
                      "compiled_path": "target/compiled/jaffle_shop/models/staging/stg_orders.sql",
                      "config": { "materialized": "view" },
                      "columns": {
                        "order_id": { "name": "ORDER_ID" }
                      }
                    },
                    "model.jaffle_shop.fct_revenue": {
                      "resource_type": "model",
                      "name": "fct_revenue",
                      "alias": "FCT_REVENUE",
                      "database": "ANALYTICS_DB",
                      "schema": "MARTS",
                      "config": { "materialized": "incremental" },
                      "compiled_code": "SELECT ORDER_ID, AMOUNT FROM ANALYTICS_DB.STAGING.STG_ORDERS"
                    },
                    "model.jaffle_shop.jinja_fallback_model": {
                      "resource_type": "model",
                      "name": "jinja_fallback_model",
                      "alias": "JINJA_FALLBACK",
                      "database": "ANALYTICS_DB",
                      "schema": "MARTS",
                      "config": { "materialized": "table" },
                      "raw_code": "{{ config(materialized='table') }}\\nSELECT * FROM {{ ref('stg_orders') }} JOIN {{ source('raw', 'orders') }} r ON 1=1"
                    }
                  },
                  "exposures": {
                    "exposure.jaffle_shop.exec_dashboard": {
                      "name": "exec_revenue_dashboard",
                      "type": "dashboard",
                      "url": "https://bi.corp/dash/1"
                    }
                  }
                }
                """;

        String catalogJson = """
                {
                  "sources": {
                    "source.jaffle_shop.raw.orders": {
                      "columns": {
                        "ID": { "name": "ID", "type": "NUMBER(38,0)", "comment": "Catalog PK" }
                      }
                    }
                  },
                  "nodes": {
                    "model.jaffle_shop.stg_orders": {
                      "columns": {
                        "ORDER_ID": { "name": "ORDER_ID", "type": "NUMBER(38,0)" },
                        "AMOUNT": { "name": "AMOUNT", "type": "NUMBER(18,2)" }
                      }
                    }
                  }
                }
                """;

        byte[] dbtZip = buildZip(Map.of(
                "manifest.json", manifestJson.getBytes(StandardCharsets.UTF_8),
                "catalog.json", catalogJson.getBytes(StandardCharsets.UTF_8),
                "target/compiled/jaffle_shop/models/staging/stg_orders.sql",
                "SELECT ID AS ORDER_ID, AMOUNT FROM RAW_DB.JAFFLE.RAW_ORDERS".getBytes(StandardCharsets.UTF_8)));

        ExtractionRequest req = new ExtractionRequest();
        req.setFormat(ExtractorFormat.DBT);
        req.setFiles(Map.of("dbt_artifacts.zip", dbtZip));

        ExtractionResult res = extractor.extract(req);
        assertThat(res.getResolvedVendor()).isEqualTo(EDbVendor.dbvsnowflake);
        assertThat(res.getCatalog().getDatabases()).hasSize(2);
        assertThat(res.getSqlUnits()).hasSize(3);

        assertThat(res.getSqlUnits())
                .extracting(SqlInfo::getSql)
                .anyMatch(s -> s.startsWith("CREATE VIEW ANALYTICS_DB.STAGING.STG_ORDERS AS")
                        && s.contains("RAW_DB.JAFFLE.RAW_ORDERS"))
                .anyMatch(s -> s.startsWith("CREATE TABLE ANALYTICS_DB.MARTS.FCT_REVENUE AS"))
                .anyMatch(s -> s.contains("ANALYTICS_DB.STAGING.STG_ORDERS")
                        && s.contains("RAW_DB.JAFFLE.RAW_ORDERS"));
    }

    @Test
    void redshiftLogExtractorReassemblesGzippedOutOfOrderStlQueryTextChunksAndAuditLogs() throws Exception {
        String stlCsv = """
                userid,xid,pid,query,starttime,sequence,text
                105,9001,3210,7701,2026-11-11 01:00:00,1,"SUM(o.amount) AS rev FROM public.orders o "
                105,9001,3210,7701,2026-11-11 01:00:00,0,"CREATE TABLE public.daily_rev AS SELECT o.order_date, "
                105,9001,3210,7701,2026-11-11 01:00:00,2,"GROUP BY o.order_date"
                105,9002,3210,7702,2026-11-11 01:01:00,0,"FETCH 1000 IN cur_1"
                1,9003,1111,7703,2026-11-11 01:02:00,0,"SELECT * FROM stv_blocklist"
                """;

        String s3AuditLog = """
                '2026-11-11T02:15:00Z UTC [ db=prod_dw user=etl_bot pid=8844 userid=108 xid=99401 ]' LOG: statement: INSERT INTO mart.customer_ltv
                SELECT customer_id, SUM(amount)
                FROM public.orders
                GROUP BY customer_id;
                '2026-11-11T02:15:05Z UTC [ db=prod_dw user=etl_bot pid=8844 userid=108 xid=99402 ]' LOG: COMMIT;
                """;

        String ndjsonLog = """
                {"query":"8801","sequence":1,"database":"prod_dw","user":"analyst","text":"FROM public.orders;"}
                {"query":"8801","sequence":0,"database":"prod_dw","user":"analyst","text":"CREATE VIEW public.v_orders AS SELECT * "}
                """;

        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("stl_querytext.csv.gz", gzipBytes(stlCsv.getBytes(StandardCharsets.UTF_8)));
        files.put("useractivitylog.txt", s3AuditLog.getBytes(StandardCharsets.UTF_8));
        files.put("query_log.ndjson", ndjsonLog.getBytes(StandardCharsets.UTF_8));

        ExtractionRequest req = new ExtractionRequest();
        req.setFormat(ExtractorFormat.REDSHIFT_LOG);
        req.setDefaultDatabase("prod_dw");
        req.setFiles(files);

        ExtractionResult res = extractor.extract(req);
        assertThat(res.getResolvedVendor()).isEqualTo(EDbVendor.dbvredshift);
        // Exactly 3 valid lineage queries (FETCH, COMMIT, and userid=1 internal query excluded)
        assertThat(res.getSqlUnits()).hasSize(3);

        assertThat(res.getSqlUnits().get(0).getSql())
                .contains("-- time=2026-11-11 01:00:00, pid=3210, db=prod_dw, userid=105, xid=9001, query=7701")
                .contains("CREATE TABLE public.daily_rev AS SELECT o.order_date, SUM(o.amount) AS rev FROM public.orders o GROUP BY o.order_date;");

        assertThat(res.getSqlUnits().get(1).getSql())
                .contains("db=prod_dw, user=etl_bot")
                .contains("INSERT INTO mart.customer_ltv");

        assertThat(res.getSqlUnits().get(2).getSql())
                .contains("CREATE VIEW public.v_orders AS SELECT * FROM public.orders;");
    }

    @Test
    void pySparkExtractorExtractsSparkSqlFStringsVariableSqlAndDataFrameReadWriteFromPyAndIpynb() {
        String pyScript = """
                from pyspark.sql import SparkSession
                spark = SparkSession.builder.getOrCreate()

                agg_sql = \"\"\"
                    SELECT c.customer_id, SUM(o.total) AS lifetime_spend
                    FROM raw_db.customers c
                    JOIN raw_db.orders o ON c.customer_id = o.customer_id
                    GROUP BY c.customer_id
                \"\"\"
                ltv_df = spark.sql(agg_sql)
                ltv_df.createOrReplaceTempView("tmp_customer_ltv")

                spark.sql(f"INSERT INTO mart_db.customer_tiers SELECT customer_id, lifetime_spend FROM tmp_customer_ltv WHERE region = '{target_region}'")

                raw_events = spark.read.format("parquet").table("raw_db.click_events")
                raw_events.write.mode("overwrite").saveAsTable("mart_db.clean_click_events")
                """;

        String ipynbJson = """
                {
                  "nbformat": 4,
                  "nbformat_minor": 5,
                  "cells": [
                    {
                      "cell_type": "markdown",
                      "source": ["# Notebook Header (ignored)"]
                    },
                    {
                      "cell_type": "code",
                      "source": [
                        "%%sql\\n",
                        "CREATE VIEW mart_db.v_top_customers AS SELECT customer_id FROM mart_db.customer_tiers"
                      ]
                    },
                    {
                      "cell_type": "code",
                      "source": [
                        "df = sqlContext.sql(\\"SELECT * FROM mart_db.v_top_customers\\")\\n",
                        "df.write.insertInto(\\"mart_db.vip_export\\")"
                      ]
                    }
                  ]
                }
                """;

        ExtractionRequest req = new ExtractionRequest();
        req.setFormat(ExtractorFormat.PYSPARK);
        req.setFiles(Map.of(
                "jobs/etl_pipeline.py", pyScript.getBytes(StandardCharsets.UTF_8),
                "notebooks/vip_analysis.ipynb", ipynbJson.getBytes(StandardCharsets.UTF_8)));

        ExtractionResult res = extractor.extract(req);
        assertThat(res.getResolvedVendor()).isEqualTo(EDbVendor.dbvsparksql);
        assertThat(res.getSqlUnits())
                .extracting(SqlInfo::getSql)
                .anyMatch(s -> s.contains("JOIN raw_db.orders"))
                .anyMatch(s -> s.contains("CREATE VIEW") && s.contains("tmp_customer_ltv"))
                .anyMatch(s -> s.contains("INSERT INTO mart_db.customer_tiers"))
                .anyMatch(s -> s.contains("CREATE TABLE mart_db.clean_click_events AS\nSELECT * FROM raw_db.click_events;"))
                .anyMatch(s -> s.contains("CREATE VIEW mart_db.v_top_customers"))
                .anyMatch(s -> s.contains("INSERT INTO mart_db.vip_export"));
    }

    @Test
    void csvAndGrabitSqldepExtractorsHonor6DigitCsvFormatCustomEncloseEscapeAndJsonBundles() {
        // 1. 6-digit positional csvFormat "124350": col1=db, col2=schema, col3=objName, col4=objType, col5=objCode
        String customCsv = """
                FIN_DW,REPORTING,'v_monthly_pnl','VIEW','CREATE VIEW REPORTING.v_monthly_pnl AS SELECT acct_id, SUM(amt) AS pnl FROM REPORTING.gl_entries WHERE status = \\'POSTED\\' GROUP BY acct_id'
                FIN_DW,REPORTING,'sp_close_period','PROCEDURE','CREATE PROCEDURE REPORTING.sp_close_period() BEGIN INSERT INTO REPORTING.archive SELECT * FROM REPORTING.gl_entries; END'
                """;
        ExtractionRequest csvReq = new ExtractionRequest();
        csvReq.setFormat(ExtractorFormat.CSV);
        csvReq.setTargetVendor(EDbVendor.dbvpostgresql);
        csvReq.setFiles(Map.of("stored_objects.csv", customCsv.getBytes(StandardCharsets.UTF_8)));

        ExtractionResult csvRes = extractor.extractCsv(csvReq, "124350", '\'', '\\');
        SchemaObject reporting = csvRes.getCatalog().getDatabases().get(0).getSchemas().get(0);
        assertThat(reporting.getViews()).extracting(DbObject::getName).containsExactly("v_monthly_pnl");
        assertThat(reporting.getProcedures()).hasSize(1);
        assertThat(csvRes.getSqlUnits()).hasSize(2);
        assertThat(csvRes.getSqlUnits().get(0).getSql()).contains("status = 'POSTED'");

        // 2. 6-digit bitmask csvFormat "111110" passed via virtual __csvFormat__ file entry
        String bitmaskCsv = """
                database,schema,object_type,object_name,object_code
                SALES_DW,PUBLIC,VIEW,v_active_users,"SELECT user_id, email FROM SALES_DW.PUBLIC.users WHERE active = 1"
                """;
        ExtractionRequest bitmaskReq = new ExtractionRequest();
        bitmaskReq.setFormat(ExtractorFormat.CSV);
        bitmaskReq.setTargetVendor(EDbVendor.dbvsnowflake);
        bitmaskReq.setFiles(Map.of(
                CsvMetadataExtractor.CONFIG_CSV_FORMAT, "111110".getBytes(StandardCharsets.UTF_8),
                "views.csv", bitmaskCsv.getBytes(StandardCharsets.UTF_8)));
        ExtractionResult bitmaskRes = extractor.extract(bitmaskReq);
        assertThat(bitmaskRes.getSqlUnits()).hasSize(1);
        assertThat(bitmaskRes.getSqlUnits().get(0).getSql()).startsWith("CREATE VIEW SALES_DW.PUBLIC.v_active_users AS");

        // 3. SQLDep JSON bundle
        String sqldepJson = """
                {
                  "dialect": "oracle",
                  "tables": [
                    {
                      "schema": "CORE",
                      "name": "ACCOUNTS",
                      "columns": [
                        {"name": "ACCT_ID", "dataType": "NUMBER(12)"},
                        {"name": "BALANCE", "dataType": "NUMBER(18,2)"}
                      ]
                    }
                  ],
                  "queries": [
                    {
                      "schema": "CORE",
                      "name": "V_HIGH_BALANCE",
                      "type": "view",
                      "sourceCode": "CREATE VIEW CORE.V_HIGH_BALANCE AS SELECT ACCT_ID, BALANCE FROM CORE.ACCOUNTS WHERE BALANCE > 10000"
                    }
                  ]
                }
                """;
        ExtractionRequest sqldepReq = new ExtractionRequest();
        sqldepReq.setFormat(ExtractorFormat.SQLDEP_JSON);
        sqldepReq.setFiles(Map.of("sqldep_export.json", sqldepJson.getBytes(StandardCharsets.UTF_8)));

        ExtractionResult sqldepRes = extractor.extract(sqldepReq);
        assertThat(sqldepRes.getResolvedVendor()).isEqualTo(EDbVendor.dbvoracle);
        assertThat(sqldepRes.getCatalog().getServers().get(0).getSchemas().get(0).getTables())
                .extracting(DbObject::getName)
                .containsExactly("ACCOUNTS");
        assertThat(sqldepRes.getSqlUnits()).hasSize(1);
    }

    private static byte[] buildZip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    private static byte[] gzipBytes(byte[] raw) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            gos.write(raw);
        }
        return baos.toByteArray();
    }
}
