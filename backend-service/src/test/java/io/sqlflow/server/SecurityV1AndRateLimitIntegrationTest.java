package io.sqlflow.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Verifies V1 API Bearer `secretKey` security across `/v1/sqlflow/**` (including the public
 * `share/{shareId}` exception), Anonymous API `50/UTC-day/IP` rate limiting (`HTTP 429`),
 * `ignore_user_token=true` bypass, and `disable_visualize_join=true` (`HTTP 403`).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class SecurityV1AndRateLimitIntegrationTest {

    private static Path tempHome;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) throws Exception {
        tempHome = Files.createTempDirectory("sqlflow-sec-test-");
        Files.createDirectories(tempHome.resolve("conf"));
        Files.writeString(
                tempHome.resolve("conf/gudu_sqlflow.conf"),
                String.join(
                        "\n",
                        "deployment=premise",
                        "ignore_user_token=true",
                        "disable_visualize_join=true",
                        "anonymous_sql_Execute_limit=50"),
                StandardCharsets.UTF_8);
        registry.add("sqlflow.home", () -> tempHome.toAbsolutePath().toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void ignoreUserTokenAndDisableVisualizeJoinGates() throws Exception {
        // When ignore_user_token=true, /version includes `login.disable: true` & `login.userId` (`01 §3.3.1`)
        JsonNode ver = objectMapper.readTree(mockMvc.perform(get("/version"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(ver.path("version").path("support").path("login.disable").asBoolean()).isTrue();
        assertThat(ver.path("version").path("support").path("login.userId").asText()).isNotBlank();

        // Arbitrary userId bypasses @UserValidate when ignore_user_token=true
        JsonNode jobs = objectMapper.readTree(mockMvc.perform(post("/sqlflow/job/displayUserJobsSummary")
                        .param("userId", "arbitrary-unregistered-user"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(jobs.path("code").asInt()).isEqualTo(200);

        // When disable_visualize_join=true, join-analysis returns real HTTP 403 (`00 §3.3 #8`)
        mockMvc.perform(post("/sqlflow/generation/sqlflow/join-analysis")
                        .param("dbvendor", "dbvpostgresql")
                        .param("sqltext", "SELECT a.id FROM a JOIN b ON a.id = b.id"))
                .andExpect(status().isForbidden());
    }

    @Test
    void v1ApiBearerAuthAndPublicShareException() throws Exception {
        // 1. Missing Authorization header -> HTTP 401 R<Void>
        mockMvc.perform(post("/v1/sqlflow/sqlflow/graph")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dbvendor\":\"dbvpostgresql\",\"sqltext\":\"SELECT 1\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/v1/sqlflow/job/displayUserJobsSummary"))
                .andExpect(status().isUnauthorized());

        // 2. Generate valid secretKey for guest user and invoke V1 interactive + schema + job endpoints
        JsonNode skNode = objectMapper.readTree(mockMvc.perform(post("/user/generateSecretKey")
                        .param("userId", "gudu|0123456789"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        String secretKey = skNode.path("secretKey").asText();
        assertThat(secretKey).isNotBlank();

        JsonNode v1Graph = objectMapper.readTree(mockMvc.perform(post("/v1/sqlflow/sqlflow/graph")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secretKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dbvendor\":\"dbvpostgresql\",\"share\":true,"
                                + "\"sqltext\":\"CREATE VIEW v_v1 AS SELECT id FROM src_v1;\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(v1Graph.path("code").asInt()).isEqualTo(200);
        assertThat(v1Graph.path("success").asBoolean()).isTrue();
        String shareId = v1Graph.path("data").path("shareId").asText();
        assertThat(shareId).isNotBlank();

        JsonNode v1Jobs = objectMapper.readTree(mockMvc.perform(post("/v1/sqlflow/job/displayUserJobsSummary")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secretKey))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(v1Jobs.path("code").asInt()).isEqualTo(200);
        assertThat(v1Jobs.path("success").asBoolean()).isTrue();

        // 3. Sole unauthenticated V1 route: GET /v1/sqlflow/sqlflow/graph/share/{shareId} succeeds WITHOUT Bearer header!
        JsonNode publicV1Share = objectMapper.readTree(mockMvc.perform(get("/v1/sqlflow/sqlflow/graph/share/" + shareId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(publicV1Share.path("code").asInt()).isEqualTo(200);
        assertThat(publicV1Share.path("data").path("shareId").asText()).isEqualTo(shareId);
    }

    @Test
    void anonymousRateLimit50PerUtcDayPerIpEnforced() throws Exception {
        String ipA = "198.51.100.201";
        String ipB = "198.51.100.202";

        for (int i = 1; i <= 50; i++) {
            MvcResult ok = mockMvc.perform(post("/api/anonymous/sqlformatter/format")
                            .header("X-Forwarded-For", ipA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"input_sql\":\"select id from t\",\"format_option\":{\"dbVendor\":\"dbvoracle\"}}"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Limit", "50"))
                    .andReturn();
            assertThat(ok.getResponse().getHeader("X-RateLimit-Remaining"))
                    .isEqualTo(String.valueOf(50 - i));
        }

        // 51st request from ipA -> HTTP 429 Too Many Requests (`rate_limit_exceeded`)
        JsonNode exceeded = objectMapper.readTree(mockMvc.perform(post("/api/anonymous/lineage")
                        .header("X-Forwarded-For", ipA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dbvendor\":\"dbvoracle\",\"sqltext\":\"SELECT 1 FROM dual\"}"))
                .andExpect(status().isTooManyRequests())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(exceeded.path("code").asInt()).isEqualTo(429);
        assertThat(exceeded.path("error").asText()).isEqualTo("rate_limit_exceeded");

        // Request from distinct IP ipB still succeeds (`200 OK`)
        mockMvc.perform(post("/api/anonymous/lineage")
                        .header("X-Forwarded-For", ipB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dbvendor\":\"dbvoracle\",\"sqltext\":\"CREATE VIEW v AS SELECT 1 AS c FROM dual\"}"))
                .andExpect(status().isOk());
    }
}
