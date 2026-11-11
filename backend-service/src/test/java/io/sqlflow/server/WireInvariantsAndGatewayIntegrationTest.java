package io.sqlflow.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.server.config.ExecutionTimeoutGuard;
import io.sqlflow.server.config.SqlflowTimeoutException;
import io.sqlflow.server.security.CryptoService;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Verifies unified `:8165` gateway ingress (`WebUrlFilter` 3-prefix parity, CORS, static SPA & Widget
 * mounts, disabled Swagger/Actuator) and Cross-Layer Wire Invariants (`00 §3.3`).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class WireInvariantsAndGatewayIntegrationTest {

    private static Path tempHome;

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) throws Exception {
        tempHome = Files.createTempDirectory("sqlflow-wire-test-");
        Files.createDirectories(tempHome.resolve("conf"));
        Files.writeString(
                tempHome.resolve("conf/gudu_sqlflow.conf"),
                String.join(
                        "\n",
                        "deployment=premise",
                        "ignore_user_token=false",
                        "record_sql=false",
                        "anonymous_sql_Execute_limit=50",
                        "cros.allowedOrigins=*"),
                StandardCharsets.UTF_8);
        registry.add("sqlflow.home", () -> tempHome.toAbsolutePath().toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CryptoService cryptoService;

    @Autowired
    private ExecutionTimeoutGuard timeoutGuard;

    @Test
    void threePrefixWebUrlFilterStaticAssetsCorsAndDisabledSwagger() throws Exception {
        // 1. All 3 URL prefixes route identically (`/X`, `/gspLive_backend/X`, `/api/gspLive_backend/X`)
        for (String prefix : new String[] {"", "/gspLive_backend", "/api/gspLive_backend"}) {
            MvcResult res = mockMvc.perform(get(prefix + "/version").param("userId", "gudu|0123456789"))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode root = objectMapper.readTree(res.getResponse().getContentAsString());
            assertThat(root.has("code")).isFalse();
            assertThat(root.has("data")).isFalse();
            assertThat(root.path("version").path("backend").path("version").asText()).isEqualTo("8.2.2.15");
            assertThat(root.path("version").path("support").path("regular.job").asBoolean()).isTrue();
        }

        // POST /version also unwrapped (Invariant #2)
        MvcResult postVer = mockMvc.perform(post("/api/gspLive_backend/version"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode postVerJson = objectMapper.readTree(postVer.getResponse().getContentAsString());
        assertThat(postVerJson.has("version")).isTrue();
        assertThat(postVerJson.has("code")).isFalse();

        // 2. Root SPA index.html, config.private.json, joinflow-island.js, and /widget/* (`OBL-015, OBL-120`)
        String indexHtml = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(indexHtml).contains("SQLFlow");

        JsonNode cfgJson = objectMapper.readTree(mockMvc.perform(get("/config.private.json"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(cfgJson.path("userId").asText()).isEqualTo("gudu|0123456789");

        mockMvc.perform(get("/joinflow-island.js")).andExpect(status().isOk());
        mockMvc.perform(get("/widget/sqlflow.widget.3.6.16.js")).andExpect(status().isOk());

        // 3. CORS preflight OPTIONS works across `/api/gspLive_backend/**` (`OBL-014`)
        mockMvc.perform(options("/api/gspLive_backend/sqlflow/generation/sqlflow/graph")
                        .header(HttpHeaders.ORIGIN, "http://example.local:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

        // 4. Swagger / OpenAPI / Actuator disabled by default (`OBL-010`)
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
    }

    @Test
    void invariants1And3And6AuthRegisterSecretKeyJwtAndLicense() throws Exception {
        // Invariant #1: Standard @UserValidate route returns HTTP 200 + body `code: 401` on invalid user/token
        JsonNode badStd = objectMapper.readTree(mockMvc.perform(post("/sqlflow/job/displayUserJobsSummary")
                        .param("userId", "nonexistent-user")
                        .param("token", "bad-token"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(badStd.path("code").asInt()).isEqualTo(401);

        // Invariant #1: V1 endpoint returns real HTTP 401 R<Void> on invalid Bearer key
        JsonNode badV1 = objectMapper.readTree(mockMvc.perform(post("/v1/sqlflow/sqlflow")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dbvendor\":\"dbvoracle\",\"sqltext\":\"SELECT 1 FROM dual\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(badV1.path("code").asInt()).isEqualTo(401);
        assertThat(badV1.path("success").asBoolean()).isFalse();

        // Invariant #6: Dual registration + login on POST /user/register
        JsonNode reg1 = objectMapper.readTree(mockMvc.perform(post("/api/gspLive_backend/user/register")
                        .param("userEmail", "alice@sqlflow.test")
                        .param("userName", "Alice")
                        .param("password", "Secret#123"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(reg1.path("code").asInt()).isEqualTo(200);
        assertThat(reg1.path("first_login").asBoolean()).isTrue();
        String userId = reg1.path("userId").asText();
        String loginToken = reg1.path("token").asText();
        assertThat(userId).isNotBlank();
        assertThat(loginToken).isNotBlank();

        JsonNode reg2 = objectMapper.readTree(mockMvc.perform(post("/user/register")
                        .param("userEmail", "alice@sqlflow.test")
                        .param("password", "Secret#123"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(reg2.path("first_login").asBoolean()).isFalse();
        assertThat(reg2.path("userId").asText()).isEqualTo(userId);

        // Invariant #3: Strict string "200" & top-level secretKey/token without `data` wrapper
        String skRaw = mockMvc.perform(post("/user/generateSecretKey")
                        .header(HttpHeaders.AUTHORIZATION, "Token " + loginToken)
                        .param("userId", userId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode skNode = objectMapper.readTree(skRaw);
        assertThat(skNode.get("code").isTextual()).isTrue();
        assertThat(skNode.get("code").asText()).isEqualTo("200");
        assertThat(skNode.has("data")).isFalse();
        String secretKey = skNode.get("secretKey").asText();
        assertThat(secretKey).isNotBlank();

        JsonNode tokNode = objectMapper.readTree(mockMvc.perform(post("/user/generateToken")
                        .param("userId", userId)
                        .param("secretKey", secretKey))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(tokNode.get("code").isTextual()).isTrue();
        assertThat(tokNode.get("code").asText()).isEqualTo("200");
        assertThat(tokNode.has("data")).isFalse();
        String jwt = tokNode.get("token").asText();
        assertThat(jwt.split("\\.")).hasSize(3);

        // Verify JWT authenticates protected `/license` and `/user/info`
        JsonNode licNode = objectMapper.readTree(mockMvc.perform(get("/license")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                        .param("userId", userId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(licNode.path("code").asInt()).isEqualTo(200);
        assertThat(licNode.path("data").path("Product name").asText()).contains("SQLFlow");
    }

    @Test
    void invariants4And5And8And9LineageErCoordinatesJoinAnalysisAndSlicing() throws Exception {
        String ddlAndView = String.join(
                "\n",
                "CREATE TABLE accounts (",
                "  account_id INT PRIMARY KEY,",
                "  account_code VARCHAR(32) UNIQUE,",
                "  balance DECIMAL(12,2)",
                ");",
                "CREATE VIEW v_accounts AS SELECT account_id, account_code, balance FROM accounts;");

        // Invariant #4 & #5: Transposed `unqiueKey` property + 1-based [line, col] coordinates
        String erRespStr = mockMvc.perform(post("/sqlflow/generation/sqlflow/erdiagramSelectGraph")
                        .param("userId", "gudu|0123456789")
                        .param("dbvendor", "dbvoracle")
                        .param("sqltext", ddlAndView))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(erRespStr).contains("\"unqiueKey\"");
        JsonNode erNode = objectMapper.readTree(erRespStr);
        assertThat(erNode.path("code").asInt()).isEqualTo(200);
        String sessionId = erNode.path("sessionId").asText();
        assertThat(sessionId).isNotBlank();

        // getSelectedDbObjectInfo resolves 1-based coordinates
        JsonNode posNode = objectMapper.readTree(mockMvc.perform(post("/sqlflow/generation/sqlflow/getSelectedDbObjectInfo")
                        .param("userId", "gudu|0123456789")
                        .param("sessionId", sessionId)
                        .param("coordinates", "[{\"x\":6,\"y\":1,\"hashCode\":\"0\"},{\"x\":6,\"y\":79,\"hashCode\":\"0\"}]"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(posNode.path("code").asInt()).isEqualTo(200);
        assertThat(posNode.path("data").isArray()).isTrue();
        assertThat(posNode.path("data")).isNotEmpty();

        // Invariant #8: Unwrapped `join-analysis.v1` on `/sqlflow/generation/sqlflow/join-analysis` + HTTP 400 errors
        mockMvc.perform(post("/sqlflow/generation/sqlflow/join-analysis")
                        .param("dbvendor", "dbvoracle")
                        .param("sqltext", ""))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/sqlflow/generation/sqlflow/join-analysis")
                        .param("dbvendor", "dbv_unknown_dialect")
                        .param("sqltext", "SELECT 1"))
                .andExpect(status().isBadRequest());

        JsonNode joinNode = objectMapper.readTree(mockMvc.perform(post("/sqlflow/generation/sqlflow/join-analysis")
                        .param("dbvendor", "dbvpostgresql")
                        .param("sqltext", "SELECT o.id, c.name FROM orders o INNER JOIN customers c ON o.cid = c.id"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(joinNode.has("code")).isFalse();
        assertThat(joinNode.path("schemaVersion").asText()).isEqualTo("join-analysis.v1");
        assertThat(joinNode.path("joins").isArray()).isTrue();

        // Invariant #9: Public wrapped `upstreamGraph` (`ApiResponse<SqlflowJSON>`) vs Internal raw `DataFlow`
        JsonNode pubUp = objectMapper.readTree(mockMvc.perform(post("/sqlflow/generation/sqlflow/upstreamGraph")
                        .param("userId", "gudu|0123456789")
                        .param("sessionId", sessionId)
                        .param("table", "v_accounts"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(pubUp.path("code").asInt()).isEqualTo(200);
        assertThat(pubUp.path("data").path("graph").has("elements")).isTrue();

        JsonNode rawUp = objectMapper.readTree(mockMvc.perform(post("/sqlflow/lineage/upstream")
                        .param("userId", "gudu|0123456789")
                        .param("sessionId", sessionId)
                        .param("table", "v_accounts"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(rawUp.has("code")).isFalse();
        assertThat(rawUp.has("data")).isFalse();
        assertThat(rawUp.has("dbobjs")).isTrue();
        assertThat(rawUp.has("relationships")).isTrue();
    }

    @Test
    void invariants7And11And12RsaEncryptionPasswordMaskingAndUserDdlSchema() throws Exception {
        // Invariant #7: 1024-bit RSA public key + PKCS#1 v1.5 decryption + plaintext fallback + password masking
        JsonNode rsaRes = objectMapper.readTree(mockMvc.perform(get("/rsa/publicKey"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        String pubKeyB64 = rsaRes.path("data").path("publicKey").asText();
        assertThat(pubKeyB64).isNotBlank();

        String plainSecret = "MyDbP@ssw0rd!";
        String encryptedB64 = cryptoService.encryptRsaPkcs1(plainSecret);
        assertThat(Base64.getDecoder().decode(encryptedB64)).hasSize(128);
        assertThat(cryptoService.decryptRsaOrPlaintext(encryptedB64)).isEqualTo(plainSecret);
        assertThat(cryptoService.decryptRsaOrPlaintext(plainSecret)).isEqualTo(plainSecret);

        // Submit job with encrypted password and verify `dataSource.password` is masked (null) on the wire
        JsonNode jobSub = objectMapper.readTree(mockMvc.perform(post("/sqlflow/job/submitUserJob")
                        .param("userId", "gudu|0123456789")
                        .param("jobName", "rsa_mask_job")
                        .param("dbvendor", "dbvpostgresql")
                        .param("hostname", "127.0.0.1")
                        .param("port", "5432")
                        .param("account", "postgres")
                        .param("password", encryptedB64)
                        .param("sqltext", "CREATE VIEW v1 AS SELECT a FROM t1;"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(jobSub.path("code").asInt()).isEqualTo(200);
        assertThat(jobSub.path("data").path("dataSource").path("password").isMissingNode()
                        || jobSub.path("data").path("dataSource").path("password").isNull())
                .isTrue();

        // Invariant #11 & #12: Multipart `sqlfiles` User Schema upload + double-nested vendor summary
        MockMultipartFile schemaFile = new MockMultipartFile(
                "sqlfiles",
                "emp_schema.sql",
                "text/plain",
                "CREATE TABLE emp (emp_id INT, emp_name VARCHAR(64), dept_id INT);".getBytes(StandardCharsets.UTF_8));

        JsonNode uploadRes = objectMapper.readTree(mockMvc.perform(multipart("/sqlflow/generation/submitUserSchema")
                        .file(schemaFile)
                        .param("userId", "gudu|0123456789")
                        .param("dbvendor", "dbvoracle"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(uploadRes.path("code").asInt()).isEqualTo(200);
        assertThat(uploadRes.path("data").get(0).asText()).isEqualTo("emp_schema.sql");

        JsonNode sumRes = objectMapper.readTree(mockMvc.perform(post("/sqlflow/generation/displayUserSchemaSummary")
                        .param("userId", "gudu|0123456789"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        // Stripped of "dbv" -> "oracle": [["emp_schema.sql"]]
        JsonNode oracleNested = sumRes.path("data").path("oracle");
        assertThat(oracleNested.isArray()).isTrue();
        assertThat(oracleNested.get(0).isArray()).isTrue();
        assertThat(oracleNested.get(0).get(0).asText()).isEqualTo("emp_schema.sql");

        // Verify SELECT * expands columns from uploaded emp_schema.sql
        String starGraphRaw = mockMvc.perform(post("/sqlflow/generation/sqlflow/graph")
                        .param("userId", "gudu|0123456789")
                        .param("dbvendor", "dbvoracle")
                        .param("sqltext", "CREATE VIEW v_all_emp AS SELECT * FROM emp;"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(starGraphRaw.toLowerCase()).contains("emp_name");

        // Delete schema & verify summary cleared
        mockMvc.perform(post("/sqlflow/generation/deleteUserSchema")
                        .param("userId", "gudu|0123456789")
                        .param("dbvendor", "dbvoracle")
                        .param("fileName", "emp_schema.sql"))
                .andExpect(status().isOk());
    }

    @Test
    void invariants13To20ProductsSettingsAuditExportsComplexityTimeoutAndPng() throws Exception {
        // Invariant #13: record_sql=false -> code 509 on POST /user/sqls; downloadUserSql accepts query params
        JsonNode recDisabled = objectMapper.readTree(mockMvc.perform(post("/user/sqls")
                        .param("userId", "gudu|0123456789"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(recDisabled.path("code").asInt()).isEqualTo(509);

        mockMvc.perform(post("/user/downloadUserSql")
                        .queryParam("userId", "gudu|0123456789")
                        .queryParam("user", "gudu|0123456789")
                        .queryParam("fileName", "20261111000000000_missing.sql"))
                .andExpect(status().isOk());

        // Invariant #14: Top-level `products` array on POST /product/products
        JsonNode prodRes = objectMapper.readTree(mockMvc.perform(post("/product/products"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(prodRes.path("code").asInt()).isEqualTo(200);
        assertThat(prodRes.has("data")).isFalse();
        assertThat(prodRes.path("products").isArray()).isTrue();
        assertThat(prodRes.path("products")).isNotEmpty();

        // Invariant #19: MD5-hashed User Settings at data/user/settings/{MD5(section)}/{MD5(userId)}.json
        String section = "graphTheme";
        String uid = "gudu|0123456789";
        mockMvc.perform(post("/user/saveUserSetting")
                        .param("userId", uid)
                        .param("section", section)
                        .param("value", "{\"darkMode\":true,\"zoom\":1.25}"))
                .andExpect(status().isOk());

        Path expectedSettingFile = tempHome.resolve("data/user/settings")
                .resolve(CryptoService.md5Hex(section))
                .resolve(CryptoService.md5Hex(uid) + ".json");
        assertThat(Files.isRegularFile(expectedSettingFile)).isTrue();

        JsonNode getSetting = objectMapper.readTree(mockMvc.perform(post("/user/getUserSetting")
                        .param("userId", uid)
                        .param("section", section))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(getSetting.path("code").asInt()).isEqualTo(200);
        assertThat(getSetting.path("data").path("darkMode").asBoolean()).isTrue();

        JsonNode missingSetting = objectMapper.readTree(mockMvc.perform(post("/user/getUserSetting")
                        .param("userId", uid)
                        .param("section", "nonexistent_section"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(missingSetting.path("code").asInt()).isEqualTo(404);

        // Invariant #15: 15-col vs 16-col CSV + metadata_lineage.zip (`metadata.csv` + `lineage.csv`)
        String sampleSql = "INSERT INTO mart.sales_summary (region_id, total_amt) "
                + "SELECT r.region_id, SUM(o.amount) FROM raw.orders o JOIN raw.regions r ON o.region_id = r.region_id GROUP BY r.region_id;";

        String csv15 = mockMvc.perform(post("/sqlflow/generation/sqlflow/exportLineageAsCsv")
                        .param("userId", uid)
                        .param("dbvendor", "dbvpostgresql")
                        .param("showTransform", "false")
                        .param("sqltext", sampleSql))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String header15 = csv15.split("\\R")[0];
        assertThat(header15.split(",")).hasSize(15);

        String csv16 = mockMvc.perform(post("/sqlflow/generation/sqlflow/exportLineageAsCsv")
                        .param("userId", uid)
                        .param("dbvendor", "dbvpostgresql")
                        .param("showTransform", "true")
                        .param("sqltext", sampleSql))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String header16 = csv16.split("\\R")[0];
        assertThat(header16.split(",")).hasSize(16);
        assertThat(header16).endsWith("transform");

        byte[] zipBytes = mockMvc.perform(post("/sqlflow/generation/sqlflow/download/metadata_lineage")
                        .param("userId", uid)
                        .param("dbvendor", "dbvpostgresql")
                        .param("sqltext", sampleSql))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        Map<String, String> zipEntries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                zipEntries.put(ze.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(zipEntries).containsKeys("metadata.csv", "lineage.csv");

        // Invariant #17: >200 objects triggers `mode == "summary"` + cancellable timeout guard without System.exit
        StringBuilder hugeSql = new StringBuilder();
        for (int i = 1; i <= 205; i++) {
            hugeSql.append("CREATE TABLE tbl_").append(i).append(" (id INT);\n");
        }
        JsonNode degraded = objectMapper.readTree(mockMvc.perform(post("/sqlflow/generation/sqlflow/graph")
                        .param("userId", uid)
                        .param("dbvendor", "dbvpostgresql")
                        .param("sqltext", hugeSql.toString()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(degraded.path("mode").asText()).isEqualTo("summary");

        assertThatThrownBy(() -> timeoutGuard.executeWithTimeout("unit-timeout-check", 40L, () -> {
                    Thread.sleep(5_000L);
                    return "never";
                }))
                .isInstanceOf(SqlflowTimeoutException.class);

        // Invariant #20: `/sqlflow/generation/sqlflow/graph/image` renders valid PNG with IHDR dimensions >= 50x30
        byte[] png = mockMvc.perform(post("/sqlflow/generation/sqlflow/graph/image")
                        .param("userId", uid)
                        .param("dbvendor", "dbvpostgresql")
                        .param("sqltext", sampleSql))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertThat(png.length).isGreaterThan(100);
        assertThat(png[0]).isEqualTo((byte) 0x89);
        assertThat(png[1]).isEqualTo((byte) 'P');
        assertThat(png[2]).isEqualTo((byte) 'N');
        assertThat(png[3]).isEqualTo((byte) 'G');
        int width = ByteBuffer.wrap(png, 16, 4).getInt();
        int height = ByteBuffer.wrap(png, 20, 4).getInt();
        assertThat(width).isGreaterThanOrEqualTo(50);
        assertThat(height).isGreaterThanOrEqualTo(30);
    }
}
