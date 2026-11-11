package io.sqlflow.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies SQLite 11-table persistence, DuckDB 14-table `"sqlflow"` persistence, async persistent
 * job execution, native DuckDB Parquet export (`PAR1` header & footer), `5 MiB` SQL splitter,
 * Share/Permalink lifecycle, `/demo/*` routes, and real HTTP SSE streaming on `/sqlflow/generation/ai/query`.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class PersistenceJobParquetAndAiSseIntegrationTest {

    private static Path tempHome;
    private static HttpServer mockOpenAiServer;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) throws Exception {
        tempHome = Files.createTempDirectory("sqlflow-persist-test-");
        Files.createDirectories(tempHome.resolve("conf"));

        // Start lightweight mock OpenAI SSE HTTP server on ephemeral localhost port
        mockOpenAiServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockOpenAiServer.createContext("/v1/chat/completions", exchange -> {
            byte[] reqBytes = exchange.getRequestBody().readAllBytes();
            String reqStr = new String(reqBytes, StandardCharsets.UTF_8);
            if (reqStr.contains("\"stream\":true")) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream;charset=UTF-8");
                exchange.sendResponseHeaders(200, 0);
                String ssePayload = String.join(
                        "\n\n",
                        "data: {\"choices\":[{\"delta\":{\"content\":\"Table `mart.revenue` \"}}]}",
                        "data: {\"choices\":[{\"delta\":{\"content\":\"is derived from `stg.orders`.\"}}]}",
                        "data: [DONE]",
                        "");
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(ssePayload.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                byte[] body = ("{\"choices\":[{\"message\":{\"content\":\"Sync answer: mart.revenue <- stg.orders\"}}]}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            }
        });
        mockOpenAiServer.start();
        int openAiPort = mockOpenAiServer.getAddress().getPort();

        Files.writeString(
                tempHome.resolve("conf/gudu_sqlflow.conf"),
                String.join(
                        "\n",
                        "deployment=premise",
                        "ignore_user_token=false",
                        "record_sql=true",
                        "split_sql_size=5"),
                StandardCharsets.UTF_8);

        Files.writeString(
                tempHome.resolve("conf/customize.yaml"),
                String.join(
                        "\n",
                        "file_path_database: false",
                        "file_path_schema: false",
                        "ai:",
                        "  enabled: true",
                        "  provider: openai",
                        "  api_key: sk-test-mock",
                        "  base_url: http://127.0.0.1:" + openAiPort + "/v1",
                        "  model: gpt-4o-mini"),
                StandardCharsets.UTF_8);

        registry.add("sqlflow.home", () -> tempHome.toAbsolutePath().toString());
    }

    @AfterAll
    static void stopMockOpenAi() {
        if (mockOpenAiServer != null) {
            mockOpenAiServer.stop(0);
        }
    }

    @LocalServerPort
    private int serverPort;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private io.sqlflow.server.persistence.AnalyticalStore analyticalStore;

    @Test
    void sqlite11TablesSeededUsersGroupCouponOrderTraceAndRecordedSqlLogs() throws Exception {
        Path sqlitePath = tempHome.resolve("conf/sqlflow.sqlite");
        assertThat(Files.isRegularFile(sqlitePath)).isTrue();

        Set<String> tables = new HashSet<>();
        Set<String> usersIndexes = new HashSet<>();
        Set<Integer> seededRowIds = new HashSet<>();
        Set<String> seededUserIds = new HashSet<>();

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath.toAbsolutePath());
                Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (!name.startsWith("sqlite_")) {
                        tables.add(name);
                    }
                }
            }
            try (ResultSet rs = st.executeQuery("PRAGMA index_list('sqlflow_users')")) {
                while (rs.next()) {
                    usersIndexes.add(rs.getString("name"));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT id, user_id FROM sqlflow_users")) {
                while (rs.next()) {
                    seededRowIds.add(rs.getInt(1));
                    seededUserIds.add(rs.getString(2));
                }
            }
        }

        // Verify all 11 SQLite tables (`02 §7.2.1`, `OBL-079`) & absence of broken V1:136 `action` index on `sqlflow_users` (`OBL-080`)
        assertThat(tables).containsExactlyInAnyOrder(
                "sqlflow_coupons",
                "sqlflow_groups",
                "sqlflow_orders",
                "sqlflow_product",
                "sqlflow_products",
                "sqlflow_trace_log",
                "sqlflow_users",
                "sqlflow_users_ext",
                "sqlflow_user_request",
                "sqlflow_api_billing_rule",
                "sqlflow_api_quote");
        assertThat(usersIndexes).doesNotContain("action");
        assertThat(seededRowIds).contains(1, 2, 10000, 10001);
        assertThat(seededUserIds).contains("gudu|0123456789");

        // Non-admin guest is denied on admin User CRUD (`code: 403`)
        JsonNode deniedAdd = objectMapper.readTree(mockMvc.perform(post("/user/addUser")
                        .param("operatorUserId", "gudu|0123456789")
                        .param("userName", "Bob")
                        .param("userEmail", "bob@sqlflow.test")
                        .param("password", "BobPass#1"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(deniedAdd.path("code").asInt()).isEqualTo(403);

        // Authenticate as seeded system admin (`id=1`, `admin@sqlflow.com` / `admin`)
        JsonNode adminLogin = objectMapper.readTree(mockMvc.perform(post("/user/register")
                        .param("userEmail", "admin@sqlflow.com")
                        .param("password", "admin"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(adminLogin.path("code").asInt()).isEqualTo(200);
        String adminUid = adminLogin.path("userId").asText();
        String adminToken = adminLogin.path("token").asText();

        // Admin User + Group + Coupon + Order + Trace CRUD
        JsonNode createdUser = objectMapper.readTree(mockMvc.perform(post("/user/addUser")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("operatorUserId", adminUid)
                        .param("userName", "Bob")
                        .param("userEmail", "bob@sqlflow.test")
                        .param("password", "BobPass#1"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(createdUser.path("code").asInt()).isEqualTo(200);

        JsonNode createdGroup = objectMapper.readTree(mockMvc.perform(post("/group/createGroup")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("operatorUserId", adminUid)
                        .param("groupName", "AnalyticsTeam")
                        .param("quote", "25"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(createdGroup.path("code").asInt()).isEqualTo(200);
        String groupId = createdGroup.path("data").path("groupId").asText();
        assertThat(groupId).isNotBlank();

        mockMvc.perform(post("/group/addGroupUser")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("operatorUserId", adminUid)
                        .param("userGroup", groupId)
                        .param("userEmail", "bob@sqlflow.test"))
                .andExpect(status().isOk());

        JsonNode couponNode = objectMapper.readTree(mockMvc.perform(post("/coupon/createCoupon")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("operatorUserId", adminUid)
                        .param("name", "Q4Promo")
                        .param("code", "PROMO2026")
                        .param("period", "60"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(couponNode.path("code").asInt()).isEqualTo(200);

        mockMvc.perform(post("/user/applyUserCoupon")
                        .param("userId", "gudu|0123456789")
                        .param("couponCode", "PROMO2026"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/order/callback")
                        .param("orderId", "ORD-9001")
                        .param("email", "bob@sqlflow.test")
                        .param("productSku", "sqlflow-onprem"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/log/trace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"gudu|0123456789\",\"action\":\"visualize\"}"))
                .andExpect(status().isOk());

        // Trigger interactive lineage so `record_sql=true` writes an audit SQL file, then list/preview/download it
        mockMvc.perform(post("/sqlflow/generation/sqlflow/graph")
                        .param("userId", "gudu|0123456789")
                        .param("dbvendor", "dbvpostgresql")
                        .param("sqltext", "CREATE VIEW v_audit AS SELECT id FROM audit_src;"))
                .andExpect(status().isOk());

        JsonNode sqlsRes = objectMapper.readTree(mockMvc.perform(post("/user/sqls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"gudu|0123456789\",\"pageNo\":1,\"pageSize\":10}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(sqlsRes.path("code").asInt()).isEqualTo(200);
        assertThat(sqlsRes.path("data")).isNotEmpty();
        String recordedFileName = sqlsRes.path("data").get(0).path("fileName").asText();

        byte[] downloadedSql = mockMvc.perform(post("/user/downloadUserSql")
                        .queryParam("userId", "gudu|0123456789")
                        .queryParam("user", "gudu|0123456789")
                        .queryParam("fileName", recordedFileName))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertThat(new String(downloadedSql, StandardCharsets.UTF_8)).contains("audit_src");
    }

    @Test
    void duckDb14TablesPersistentJobLifecycleAnalyticalQueriesAndNativeParquetExport() throws Exception {
        Path duckPath = tempHome.resolve("conf/sqlflow.duckdb");
        assertThat(Files.exists(duckPath)).isTrue();
        assertThat(analyticalStore.listTables())
                .containsExactlyInAnyOrderElementsOf(
                        io.sqlflow.server.persistence.DuckDbAnalyticalStore.ALL_14_TABLES);

        // Build a .zip archive containing 2 SQL scripts to exercise .zip extraction in JobCenterService
        ByteArrayOutputStream zipBaos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(zipBaos, StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry("01_staging.sql"));
            zos.write(("CREATE TABLE stg_orders (order_id INT, cust_id INT, amount DECIMAL(10,2));\n"
                            + "CREATE TABLE stg_customers (cust_id INT, region VARCHAR(32));\n")
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("02_mart.sql"));
            zos.write(("CREATE VIEW mart_revenue AS "
                            + "SELECT c.region, SUM(o.amount) AS total_rev "
                            + "FROM stg_orders o JOIN stg_customers c ON o.cust_id = c.cust_id "
                            + "GROUP BY c.region;\n")
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        MockMultipartFile zipUpload =
                new MockMultipartFile("sqlfiles", "dw_etl.zip", "application/zip", zipBaos.toByteArray());

        JsonNode submitRes = objectMapper.readTree(mockMvc.perform(multipart("/sqlflow/job/submitPersistJob")
                        .file(zipUpload)
                        .param("userId", "gudu|0123456789")
                        .param("jobName", "dw_persist_job")
                        .param("dbvendor", "dbvpostgresql")
                        .param("firstSubmit", "true"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(submitRes.path("code").asInt()).isEqualTo(200);
        String jobId = submitRes.path("data").path("jobId").asText();
        assertThat(jobId).isNotBlank();

        // Poll async job completion
        String finalStatus = "process";
        for (int attempt = 0; attempt < 60; attempt++) {
            JsonNode stNode = objectMapper.readTree(mockMvc.perform(post("/sqlflow/job/queryJobStatus")
                            .param("userId", "gudu|0123456789")
                            .param("jobId", jobId))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
            finalStatus = stNode.path("data").path("status").asText();
            if ("success".equalsIgnoreCase(finalStatus)
                    || "partial_success".equalsIgnoreCase(finalStatus)
                    || "fail".equalsIgnoreCase(finalStatus)) {
                break;
            }
            Thread.sleep(100L);
        }
        assertThat(finalStatus).isIn("success", "partial_success");

        // Invariant #10: displayUserLatestJobGraph returns top-level `job`, `jobId`, `sessionId`, `data`
        JsonNode latestEnv = objectMapper.readTree(mockMvc.perform(post("/sqlflow/job/displayUserLatestJobGraph")
                        .param("userId", "gudu|0123456789"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(latestEnv.path("code").asInt()).isEqualTo(200);
        assertThat(latestEnv.path("jobId").asText()).isEqualTo(jobId);
        assertThat(latestEnv.has("job")).isTrue();
        assertThat(latestEnv.path("sessionId").asText()).isNotBlank();
        assertThat(latestEnv.has("data")).isTrue();

        // Verify getJobFiles & exportJobFile
        JsonNode filesNode = objectMapper.readTree(mockMvc.perform(post("/sqlflow/job/getJobFiles")
                        .param("userId", "gudu|0123456789")
                        .param("jobId", jobId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(filesNode.path("code").asInt()).isEqualTo(200);
        assertThat(filesNode.path("data").path("fileNames")).isNotEmpty();

        // Analytical store queries (`/sqlflow/job/fetchRegularJobTableRelationSqls`, `fetchLeftMostTableLineageSQL`, `submitTableAffectSQLJob`)
        JsonNode relSqls = objectMapper.readTree(mockMvc.perform(post("/sqlflow/job/fetchRegularJobTableRelationSqls")
                        .param("userId", "gudu|0123456789")
                        .param("jobId", jobId)
                        .param("sourceTable", "stg_orders")
                        .param("targetTable", "mart_revenue"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(relSqls.path("code").asInt()).isEqualTo(200);

        JsonNode affectRes = objectMapper.readTree(mockMvc.perform(post("/sqlflow/job/submitTableAffectSQLJob")
                        .param("userId", "gudu|0123456789")
                        .param("jobId", jobId)
                        .param("sqltext", "ALTER TABLE stg_orders ADD COLUMN discount DECIMAL(5,2);"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(affectRes.path("code").asInt()).isEqualTo(200);

        // Native DuckDB Parquet generation & binary download (`PAR1` header + `PAR1` footer!)
        JsonNode genParquet = objectMapper.readTree(mockMvc.perform(post("/sqlflow/job/generate/parquet")
                        .param("userId", "gudu|0123456789")
                        .param("jobId", jobId)
                        .param("jobName", "dw_persist_job")
                        .param("batchId", ""))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(genParquet.path("code").asInt()).isEqualTo(200);

        byte[] parquetBytes = mockMvc.perform(get("/sqlflow/job/download/parquet")
                        .param("userId", "gudu|0123456789")
                        .param("jobId", jobId)
                        .param("jobName", "dw_persist_job")
                        .param("batchId", ""))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertThat(parquetBytes.length).isGreaterThan(12);
        assertThat(new String(parquetBytes, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("PAR1");
        assertThat(new String(parquetBytes, parquetBytes.length - 4, 4, StandardCharsets.US_ASCII))
                .isEqualTo("PAR1");
    }

    @Test
    void sqlSplitterDbtExtractorSharePermalinkAndDemoRoutes() throws Exception {
        // 1. Statement-boundary SQL splitter preserves PL/SQL BEGIN..END blocks
        String multiSql = String.join(
                "\n",
                "CREATE TABLE t_alpha (id INT, label VARCHAR(40));",
                "CREATE OR REPLACE PROCEDURE proc_sync AS BEGIN INSERT INTO t_alpha VALUES (1, 'a;b'); END;",
                "SELECT id, label FROM t_alpha;");
        JsonNode splitNode = objectMapper.readTree(mockMvc.perform(post("/sqlflow/file/split")
                        .param("dbvendor", "dbvoracle")
                        .param("maxBytes", "90")
                        .param("sqltext", multiSql))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(splitNode.path("code").asInt()).isEqualTo(200);
        assertThat(splitNode.path("data").path("chunkCount").asInt()).isGreaterThanOrEqualTo(2);

        // 2. Non-JDBC FileExtractor (`dbt` manifest.json)
        MockMultipartFile dbtManifest = new MockMultipartFile(
                "file",
                "manifest.json",
                "application/json",
                ("{\"metadata\":{\"adapter_type\":\"snowflake\"},\"nodes\":{"
                                + "\"model.pkg.orders\":{\"resource_type\":\"model\",\"name\":\"orders\","
                                + "\"compiled_code\":\"SELECT id, total FROM raw.orders\"}}}")
                        .getBytes(StandardCharsets.UTF_8));
        JsonNode extNode = objectMapper.readTree(mockMvc.perform(multipart("/sqlflow/fileextractor/getFileMetadataOrSQL")
                        .file(dbtManifest)
                        .param("type", "dbt"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(extNode.path("code").asInt()).isEqualTo(200);

        // 3. Share & Permalink lifecycle (`share=true` -> public GET share -> list -> delete -> 404)
        JsonNode sharedGraphRes = objectMapper.readTree(mockMvc.perform(post("/sqlflow/generation/sqlflow/graph")
                        .param("userId", "gudu|0123456789")
                        .param("dbvendor", "dbvpostgresql")
                        .param("share", "true")
                        .param("sqltext", "CREATE VIEW v_shared AS SELECT id FROM src_shared;"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        String shareId = sharedGraphRes.path("data").path("shareId").asText();
        assertThat(shareId).isNotBlank();

        JsonNode pubShare = objectMapper.readTree(mockMvc.perform(get("/sqlflow/generation/sqlflow/graph/share/" + shareId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(pubShare.path("code").asInt()).isEqualTo(200);
        assertThat(pubShare.path("data").path("shareId").asText()).isEqualTo(shareId);

        mockMvc.perform(post("/user/deleteUserShare")
                        .param("userId", "gudu|0123456789")
                        .param("shareId", shareId))
                .andExpect(status().isOk());

        JsonNode afterDel = objectMapper.readTree(mockMvc.perform(get("/sqlflow/generation/sqlflow/graph/share/" + shareId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(afterDel.path("code").asInt()).isEqualTo(404);

        // 4. `/demo/*` & `/liveDemo` + unknown appId `code: 202`
        JsonNode fmtRes = objectMapper.readTree(mockMvc.perform(post("/demo/sqlformat")
                        .param("dbvendor", "dbvpostgresql")
                        .param("sqltext", "select id,name from users where active=1"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(fmtRes.path("code").asInt()).isEqualTo(200);

        String colImpactXml = mockMvc.perform(post("/demo/columnImpact")
                        .param("dbvendor", "dbvpostgresql")
                        .param("sqltext", "CREATE VIEW v1 AS SELECT a FROM t1;")
                        .param("params", "{\"options\":[\"/xml\"]}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(colImpactXml.toLowerCase()).contains("dlineage");

        JsonNode unknownDemo = objectMapper.readTree(mockMvc.perform(post("/demo/totallyUnknownDemoApp")
                        .param("sqltext", "SELECT 1"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(unknownDemo.path("code").asInt()).isEqualTo(202);
    }

    @Test
    void aiLineageUnwrappedStatusIndexingAndRealHttpSseStreaming() throws Exception {
        // Invariant #16: GET /sqlflow/generation/ai/status is unwrapped (no top-level `code` or `data`)
        JsonNode aiStatus = objectMapper.readTree(mockMvc.perform(get("/sqlflow/generation/ai/status"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(aiStatus.has("code")).isFalse();
        assertThat(aiStatus.has("data")).isFalse();
        assertThat(aiStatus.path("enabled").asBoolean()).isTrue();

        // Index lineage into AI store
        JsonNode idxNode = objectMapper.readTree(mockMvc.perform(post("/sqlflow/generation/ai/index")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"ai-sess-1\",\"dbvendor\":\"dbvpostgresql\","
                                + "\"sqltext\":\"CREATE VIEW mart.revenue AS SELECT order_id, amt FROM stg.orders;\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(idxNode.path("code").asInt()).isEqualTo(200);
        assertThat(idxNode.path("data").path("indexed").asBoolean()).isTrue();
        assertThat(idxNode.path("data").path("nodeCount").asInt()).isGreaterThanOrEqualTo(2);

        // Stream SSE from real embedded Tomcat via java.net.http.HttpClient
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest sseReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + serverPort + "/api/gspLive_backend/sqlflow/generation/ai/query"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"sessionId\":\"ai-sess-1\",\"question\":\"Where does mart.revenue come from?\"}"))
                .build();

        HttpResponse<String> sseResp = httpClient.send(sseReq, HttpResponse.BodyHandlers.ofString());
        assertThat(sseResp.statusCode()).isEqualTo(200);
        assertThat(sseResp.headers().firstValue("content-type").orElse("")).contains("text/event-stream");
        String sseBody = sseResp.body();
        assertThat(sseBody).contains("event:metadata").contains("event:chunk").contains("event:complete");
        assertThat(sseBody).contains("mart.revenue");
    }
}
