package io.sqlflow.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the `simple` Spring profile (`OBL-009`): `/version` reports `"regular.job": false`,
 * `NoopAnalyticalStore` is active without creating `conf/sqlflow.duckdb`, and `submitPersistJob`
 * rejects persistent job submission cleanly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("simple")
class SimpleProfileIntegrationTest {

    private static Path tempHome;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) throws Exception {
        tempHome = Files.createTempDirectory("sqlflow-simple-profile-test-");
        Files.createDirectories(tempHome.resolve("conf"));
        registry.add("sqlflow.home", () -> tempHome.toAbsolutePath().toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void simpleProfileDisablesRegularJobAndSkipsDuckDbCreation() throws Exception {
        JsonNode ver = objectMapper.readTree(mockMvc.perform(get("/version"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(ver.path("version").path("support").path("regular.job").asBoolean()).isFalse();
        assertThat(Files.exists(tempHome.resolve("conf/sqlflow.duckdb"))).isFalse();

        JsonNode persistAttempt = objectMapper.readTree(mockMvc.perform(post("/sqlflow/job/submitPersistJob")
                        .param("userId", "gudu|0123456789")
                        .param("jobName", "should_reject")
                        .param("dbvendor", "dbvoracle")
                        .param("sqltext", "SELECT 1 FROM dual"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(persistAttempt.path("code").asInt()).isNotEqualTo(200);
    }
}
