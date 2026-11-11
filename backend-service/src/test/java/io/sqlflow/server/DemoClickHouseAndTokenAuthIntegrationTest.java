package io.sqlflow.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.contracts.domain.JobDetail;
import io.sqlflow.contracts.domain.JobStatus;
import io.sqlflow.contracts.graph.LeftMostSourceTable;
import io.sqlflow.contracts.lineage.Column;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.DatabaseObject;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.Relationship;
import io.sqlflow.contracts.lineage.RelationshipElement;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.contracts.lineage.Transform;
import io.sqlflow.server.config.SqlflowRuntimeConfig;
import io.sqlflow.server.persistence.AnalyticalStore;
import io.sqlflow.server.persistence.ClickHouseAnalyticalStore;
import io.sqlflow.server.persistence.DuckDbAnalyticalStore;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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

/**
 * Verifies all 6 Stage 2B audit requirements (`#59`):
 * <ol>
 *   <li>{@code /demo/sqlformatter/format}, {@code /sqlflow/demo/sqlformatter/format}, &amp;
 *       {@code /sqlflow/generation/sql/format} with {@code formatConfig} JSON-string &amp; JSON-body
 *       parsing ({@code case_keywords: "upper"/"lower"}, missing {@code input_sql}/{@code db_vendor} -&gt; 500).</li>
 *   <li>{@code /syntax/check}, {@code /sqlflow/generation/checkSyntax}, &amp; {@code /liveDemo} returning
 *       {@code code: 500} on invalid SQL ({@code "SELECT FROM WHERE (((;"}).</li>
 *   <li>{@code /demo/keyword/check} &amp; {@code /sqlflow/demo/keyword/check} with {@code word} identifier list.</li>
 *   <li>{@code /demo/tableColumns/getTableColumns} with {@code sqlfile}, JSON-array {@code sqltext}, &amp;
 *       {@code format=csv}.</li>
 *   <li>{@code /liveDemo} {@code sqlfile}, {@code appParams}, &amp; empty-input {@code code: 500} validation.</li>
 *   <li>{@link ClickHouseAnalyticalStore} all 14 {@code sqlflow.*} {@code MergeTree} tables, TTL,
 *       {@code ngrambf_v1} indexes, &amp; JDBC mutations/queries + {@code Authorization: Token <secretKey>}
 *       authentication.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class DemoClickHouseAndTokenAuthIntegrationTest {

    private static Path tempHome;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) throws Exception {
        tempHome = Files.createTempDirectory("sqlflow-demo-ch-test-");
        Files.createDirectories(tempHome.resolve("conf"));
        Files.writeString(
                tempHome.resolve("conf/gudu_sqlflow.conf"),
                String.join(
                        "\n",
                        "deployment=premise",
                        "ignore_user_token=false",
                        "anonymous_sql_Execute_limit=50"),
                StandardCharsets.UTF_8);
        registry.add("sqlflow.home", () -> tempHome.toAbsolutePath().toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SqlflowRuntimeConfig runtimeConfig;

    @Test
    void sqlFormatterFormatConfigAndCaseKeywordsAndValidation() throws Exception {
        String upperCfg = objectMapper.writeValueAsString(Map.of(
                "input_sql", "select u.id, u.name from users u where u.active = 1",
                "format_option", Map.of(
                        "db_vendor", "dbvoracle",
                        "format_options", Map.of(
                                "capitalization", Map.of("case_keywords", "upper")))));

        JsonNode upperRes = objectMapper.readTree(mockMvc.perform(post("/demo/sqlformatter/format")
                        .param("formatConfig", upperCfg))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(upperRes.path("code").asText()).isEqualTo("200");
        String upperSql = upperRes.path("data").path("formatted_sql").asText();
        assertThat(upperSql).contains("SELECT").contains("FROM").contains("WHERE");

        String lowerCfg = objectMapper.writeValueAsString(Map.of(
                "input_sql", "SELECT U.ID FROM USERS U WHERE U.ACTIVE = 1",
                "format_option", Map.of(
                        "db_vendor", "dbvpostgresql",
                        "format_options", Map.of(
                                "capitalization", Map.of("case_keywords", "lower")))));

        JsonNode lowerRes = objectMapper.readTree(mockMvc.perform(post("/sqlflow/demo/sqlformatter/format")
                        .param("formatConfig", lowerCfg))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(lowerRes.path("code").asInt()).isEqualTo(200);
        String lowerSql = lowerRes.path("data").path("formatted_sql").asText();
        assertThat(lowerSql).contains("select").contains("from").contains("where");

        // Missing db_vendor in formatConfig -> code 500 (`01:967`)
        String badCfg = "{\"input_sql\":\"select 1\",\"format_option\":{}}";
        JsonNode badRes = objectMapper.readTree(mockMvc.perform(post("/demo/sqlformatter/format")
                        .param("formatConfig", badCfg))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(badRes.path("code").asInt()).isEqualTo(500);

        // Also verify `/sqlflow/generation/sql/format` with JSON body format_option
        JsonNode genFmtRes = objectMapper.readTree(mockMvc.perform(post("/sqlflow/generation/sql/format")
                        .param("userId", "gudu|0123456789")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upperCfg))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(genFmtRes.path("code").asInt()).isEqualTo(200);
        assertThat(genFmtRes.path("data").path("formatted_sql").asText()).contains("SELECT");
    }

    @Test
    void syntaxCheckReturnsCode500OnInvalidSqlAnd200OnValidSql() throws Exception {
        JsonNode badSyntax = objectMapper.readTree(mockMvc.perform(post("/syntax/check")
                        .param("dbvendor", "dbvoracle")
                        .param("sql", "SELECT FROM WHERE (((;"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(badSyntax.path("code").asInt()).isEqualTo(500);
        assertThat(badSyntax.path("error").asText()).isNotBlank();
        assertThat(badSyntax.path("data").path("errors")).isNotEmpty();

        JsonNode badGenSyntax = objectMapper.readTree(mockMvc.perform(post("/sqlflow/generation/checkSyntax")
                        .param("userId", "gudu|0123456789")
                        .param("dbvendor", "dbvoracle")
                        .param("sqltext", "SELECT FROM WHERE (((;"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(badGenSyntax.path("code").asInt()).isEqualTo(500);

        JsonNode goodSyntax = objectMapper.readTree(mockMvc.perform(post("/syntax/check")
                        .param("dbvendor", "dbvoracle")
                        .param("sql", "SELECT id, name FROM employees"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(goodSyntax.path("code").asInt()).isEqualTo(200);
        assertThat(goodSyntax.path("data").path("errorInfos")).isEmpty();
        assertThat(goodSyntax.path("data").has("errors")).isFalse();
    }

    @Test
    void keywordCheckTableColumnsCsvAndLiveDemoValidation() throws Exception {
        // 3. `/demo/keyword/check` with `word="SELECT,emp_custom_col"`
        JsonNode kwBad = objectMapper.readTree(mockMvc.perform(post("/demo/keyword/check")
                        .param("dbvendor", "dbvoracle")
                        .param("word", "SELECT,emp_custom_col"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(kwBad.path("code").asText()).isEqualTo("200");
        assertThat(kwBad.path("data").path("valid").asBoolean()).isFalse();
        assertThat(kwBad.path("data").path("reservedKeywordsUsedAsIdentifiers")).hasSize(1);
        assertThat(kwBad.path("data").path("reservedKeywordsUsedAsIdentifiers").get(0).path("text").asText())
                .isEqualToIgnoringCase("SELECT");

        JsonNode kwGood = objectMapper.readTree(mockMvc.perform(post("/sqlflow/demo/keyword/check")
                        .param("dbvendor", "dbvoracle")
                        .param("word", "emp_id,emp_custom_col"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(kwGood.path("code").asInt()).isEqualTo(200);
        assertThat(kwGood.path("data").path("valid").asBoolean()).isTrue();

        // 4. `/demo/tableColumns/getTableColumns` with alias resolution, `sqlfile`, JSON-array `sqltext`, & `format=csv`
        MockMultipartFile sqlFile = new MockMultipartFile(
                "sqlfile",
                "users.sql",
                "text/plain",
                "SELECT u.id, u.email FROM users u".getBytes(StandardCharsets.UTF_8));
        JsonNode tcCsv = objectMapper.readTree(mockMvc.perform(multipart("/demo/tableColumns/getTableColumns")
                        .file(sqlFile)
                        .param("sqltext", "[\"SELECT o.order_id FROM orders o\"]")
                        .param("dbvendor", "dbvpostgresql")
                        .param("mode", "summary")
                        .param("format", "csv"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(tcCsv.path("code").asText()).isEqualTo("200");
        assertThat(tcCsv.path("data").path("format").asText()).isEqualTo("csv");
        String csvText = tcCsv.path("data").path("csv").asText();
        assertThat(csvText).contains("table,column").contains("users,id").contains("users,email").contains("orders,order_id");

        // 5. `/liveDemo` empty input -> code 500 "Both of sqltext and sqlfile are empty"
        JsonNode emptyLiveDemo = objectMapper.readTree(mockMvc.perform(post("/liveDemo")
                        .param("userId", "gudu|0123456789")
                        .param("appId", "columnImpact"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(emptyLiveDemo.path("code").asInt()).isEqualTo(500);
        assertThat(emptyLiveDemo.path("error").asText()).isEqualTo("Both of sqltext and sqlfile are empty");

        // `/liveDemo` with `sqlfile` + `appParams={"options":["/xml"]}` -> code 200
        MockMultipartFile liveDemoFile = new MockMultipartFile(
                "sqlfile",
                "impact.sql",
                "text/plain",
                "CREATE VIEW v_users AS SELECT u.id FROM users u;".getBytes(StandardCharsets.UTF_8));
        JsonNode okLiveDemo = objectMapper.readTree(mockMvc.perform(multipart("/liveDemo")
                        .file(liveDemoFile)
                        .param("userId", "gudu|0123456789")
                        .param("appId", "columnImpact")
                        .param("dbvendor", "dbvpostgresql")
                        .param("appParams", "{\"options\":[\"/xml\"]}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(okLiveDemo.path("code").asInt()).isEqualTo(200);
        assertThat(okLiveDemo.path("data").path("xml").asText()).contains("<dlineage");
    }

    @Test
    void authorizationTokenSecretKeyAuthenticatesWhileStaticPlaceholderIgnored() throws Exception {
        JsonNode reg = objectMapper.readTree(mockMvc.perform(post("/user/register")
                        .param("userEmail", "tokentest@sqlflow.test")
                        .param("userName", "TokenTester")
                        .param("password", "Pass#1234"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(reg.path("code").asInt()).isEqualTo(200);
        String uid = reg.path("userId").asText();
        String jwt = reg.path("token").asText();

        JsonNode skNode = objectMapper.readTree(mockMvc.perform(post("/user/generateSecretKey")
                        .header(HttpHeaders.AUTHORIZATION, "Token " + jwt)
                        .param("userId", uid))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        String secretKey = skNode.path("secretKey").asText();
        assertThat(secretKey).isNotBlank();

        // Authenticate endpoint using ONLY `Authorization: Token <secretKey>` (no `token` or `userId` param):
        // resolves caller identity directly to `uid`!
        JsonNode authedInfo = objectMapper.readTree(mockMvc.perform(post("/user/info")
                        .header(HttpHeaders.AUTHORIZATION, "Token " + secretKey))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(authedInfo.path("code").asInt()).isEqualTo(200);
        assertThat(authedInfo.path("data").path("userId").asText()).isEqualTo(uid);

        // Passing `Authorization: Token <secretKey>` with a different user's userId (`10000`) is caught & rejected with 401
        JsonNode mismatchRes = objectMapper.readTree(mockMvc.perform(post("/sqlflow/job/displayUserJobsSummary")
                        .header(HttpHeaders.AUTHORIZATION, "Token " + secretKey)
                        .param("userId", "10000"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(mismatchRes.path("code").asInt()).isEqualTo(401);

        // Unregistered static placeholder in `Authorization: Token <placeholder>` is ignored (not treated as an invalid JWT):
        // falls back cleanly to built-in guest `gudu|0123456789` with code 200
        JsonNode guestPlaceholder = objectMapper.readTree(mockMvc.perform(post("/user/info")
                        .header(HttpHeaders.AUTHORIZATION, "Token static-spa-placeholder-token"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(guestPlaceholder.path("code").asInt()).isEqualTo(200);
        assertThat(guestPlaceholder.path("data").path("userId").asText()).isEqualTo("gudu|0123456789");
    }

    @Test
    void clickHouseAnalyticalStoreDefinesAll14MergeTreeTablesAndExecutesJdbcOperations() {
        List<String> ddl = ClickHouseAnalyticalStore.buildClickHouseSetupStatements();
        // 1 CREATE DATABASE + 14 MergeTree tables = 15 statements
        assertThat(ddl).hasSize(15);
        assertThat(ddl.get(0)).isEqualTo("CREATE DATABASE IF NOT EXISTS sqlflow");

        String joinedDdl = String.join("\n", ddl);
        for (String table : DuckDbAnalyticalStore.ALL_14_TABLES) {
            assertThat(joinedDdl).contains("CREATE TABLE IF NOT EXISTS sqlflow." + table);
        }
        assertThat(ddl.subList(1, ddl.size())).allSatisfy(stmt -> {
            assertThat(stmt).contains("ENGINE = MergeTree");
            assertThat(stmt).contains("PARTITION BY toDate(create_time)");
            assertThat(stmt).contains("TTL create_time + toIntervalMonth(6)");
            assertThat(stmt).contains("SETTINGS index_granularity = 8192");
        });
        assertThat(joinedDdl)
                .contains("INDEX lineage_index(lineage_hash) TYPE ngrambf_v1(3, 256, 2, 0) GRANULARITY 4")
                .contains("INDEX sql_index(sql_hash) TYPE ngrambf_v1(3, 256, 2, 0) GRANULARITY 4")
                .contains("INDEX batch_index(batch_id) TYPE ngrambf_v1(3, 256, 2, 0) GRANULARITY 4");

        // Exercise full JDBC lifecycle against a recording JDBC Connection proxy
        List<String> executedSqls = new ArrayList<>();
        Connection proxyConn = createRecordingConnection(executedSqls);
        ClickHouseAnalyticalStore store = new ClickHouseAnalyticalStore(runtimeConfig, () -> proxyConn);

        store.initSchema();
        assertThat(executedSqls).hasSize(15);
        executedSqls.clear();

        JobDetail job = new JobDetail();
        job.setJobId("ch-job-1");
        job.setJobName("ClickHouse Nightly Job");
        job.setUserId("gudu|0123456789");
        job.setUserName("guest");
        job.setDbVendor("dbvpostgresql");
        job.setSqlsource("sqlfiles");
        job.setStatus(JobStatus.PARTIAL_SUCCESS);

        DbObject srcObj = new DbObject();
        srcObj.setId("t1");
        srcObj.setName("orders");
        srcObj.setType("table");
        Column srcCol = new Column();
        srcCol.setId("c1");
        srcCol.setName("amount");
        srcObj.setColumns(List.of(srcCol));

        DbObject tgtObj = new DbObject();
        tgtObj.setId("t2");
        tgtObj.setName("sales_summary");
        tgtObj.setType("view");
        Column tgtCol = new Column();
        tgtCol.setId("c2");
        tgtCol.setName("total_amount");
        tgtObj.setColumns(List.of(tgtCol));

        RelationshipElement srcEl = new RelationshipElement();
        srcEl.setId("c1");
        srcEl.setParentId("t1");
        srcEl.setParentName("orders");
        srcEl.setColumn("amount");

        RelationshipElement tgtEl = new RelationshipElement();
        tgtEl.setId("c2");
        tgtEl.setParentId("t2");
        tgtEl.setParentName("sales_summary");
        tgtEl.setColumn("total_amount");
        Transform tr = new Transform();
        tr.setType("expression");
        tr.setCode("SUM(orders.amount)");
        tgtEl.setTransforms(List.of(tr));

        Relationship rel = new Relationship();
        rel.setId("r1");
        rel.setType("fdd");
        rel.setEffectType("select");
        rel.setSources(List.of(srcEl));
        rel.setTarget(tgtEl);
        rel.setSqlHash("hash-1");

        DatabaseObject dbObj = new DatabaseObject();
        dbObj.setName("DEFAULT");
        dbObj.setTables(List.of(srcObj));
        dbObj.setViews(List.of(tgtObj));
        SqlflowCatalog catalog = new SqlflowCatalog();
        catalog.setDatabases(List.of(dbObj));

        DataFlow df = new DataFlow();
        df.setDbobjs(catalog);
        df.setRelationships(List.of(rel));

        LeftMostSourceTable.SourceTableColumn lmSrc = new LeftMostSourceTable.SourceTableColumn();
        lmSrc.setTableName("orders");
        lmSrc.setName("amount");
        LeftMostSourceTable.TargetColumn lmTc = new LeftMostSourceTable.TargetColumn();
        lmTc.setName("total_amount");
        lmTc.setSourceColumns(List.of(lmSrc));
        LeftMostSourceTable.TargetTable lmTt = new LeftMostSourceTable.TargetTable();
        lmTt.setName("sales_summary");
        lmTt.setColumns(List.of(lmTc));
        LeftMostSourceTable lm = new LeftMostSourceTable();
        lm.setElements(List.of(lmTt));

        store.persistJobLineage(
                job,
                df,
                List.of(new AnalyticalStore.SqlEntry(
                        "etl.sql",
                        "CREATE VIEW sales_summary AS SELECT SUM(amount) AS total_amount FROM orders;",
                        "hash-1")),
                lm);

        String persistJoined = String.join("\n", executedSqls);
        assertThat(persistJoined)
                .contains("INSERT INTO sqlflow.job")
                .contains("INSERT INTO sqlflow.sql_table")
                .contains("INSERT INTO sqlflow.dataflow_table_metadata")
                .contains("INSERT INTO sqlflow.dataflow_table")
                .contains("INSERT INTO sqlflow.dataflow_aggregation_table")
                .contains("INSERT INTO sqlflow.dataflow_sql_map_table")
                .contains("INSERT INTO sqlflow.lineage_transform")
                .contains("INSERT INTO sqlflow.sql_transform")
                .contains("INSERT INTO sqlflow.table_level_dataflow")
                .contains("INSERT INTO sqlflow.table_level_aggregation")
                .contains("INSERT INTO sqlflow.left_most_source_table")
                .contains("INSERT INTO sqlflow.left_most_table");

        executedSqls.clear();
        store.updateJobStatus("ch-job-1", "success", "", 250L);
        assertThat(String.join("\n", executedSqls)).contains("ALTER TABLE sqlflow.job");

        List<List<String>> relSqls = store.fetchRegularJobTableRelationSqls(
                "ch-job-1", "orders", "amount", "sales_summary", "total_amount");
        assertThat(relSqls).hasSize(1);
        assertThat(relSqls.get(0)).containsExactly("SELECT SUM(amount) FROM orders", "hash-1", "etl.sql");

        List<Map<String, Object>> byHash = store.fetchTableRelationSqlBySqlHash("ch-job-1", "hash-1");
        assertThat(byHash).hasSize(1);
        assertThat(byHash.get(0).get("sqlHash")).isEqualTo("hash-1");

        List<Map<String, Object>> lmRows = store.fetchLeftMostTableLineageSQL(
                "ch-job-1", "sales_summary", "total_amount", "orders", "amount");
        assertThat(lmRows).hasSize(1);
        assertThat(lmRows.get(0).get("sourceTable")).isEqualTo("orders");

        Map<String, Object> affectRes = store.persistAndFetchSourceTableAffectSQL(
                "ch-job-1",
                "batch-1",
                df,
                List.of(new AnalyticalStore.SqlEntry("downstream.sql", "SELECT * FROM orders", "hash-2")),
                true);
        assertThat(affectRes.get("jobId")).isEqualTo("ch-job-1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> affectedRows = (List<Map<String, Object>>) affectRes.get("affectedSqls");
        assertThat(affectedRows).hasSize(1);
        assertThat(String.join("\n", executedSqls))
                .contains("INSERT INTO sqlflow.column_level_out_going")
                .contains("INSERT INTO sqlflow.column_level_out_going_user");

        executedSqls.clear();
        store.deleteJobData("ch-job-1");
        assertThat(executedSqls).hasSize(14);
        for (String table : DuckDbAnalyticalStore.ALL_14_TABLES) {
            assertThat(executedSqls).contains("ALTER TABLE sqlflow." + table + " DELETE WHERE job_id = ?");
        }
    }

    private static Connection createRecordingConnection(List<String> executedSqls) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("close".equals(name)) {
                        return null;
                    }
                    if ("isClosed".equals(name)) {
                        return false;
                    }
                    if ("createStatement".equals(name)) {
                        return Proxy.newProxyInstance(
                                Statement.class.getClassLoader(),
                                new Class<?>[] {Statement.class},
                                (sProxy, sMethod, sArgs) -> {
                                    if ("execute".equals(sMethod.getName()) || "executeUpdate".equals(sMethod.getName())) {
                                        executedSqls.add(String.valueOf(sArgs[0]));
                                        return "execute".equals(sMethod.getName()) ? Boolean.TRUE : 1;
                                    }
                                    return null;
                                });
                    }
                    if ("prepareStatement".equals(name)) {
                        String sql = String.valueOf(args[0]);
                        return Proxy.newProxyInstance(
                                PreparedStatement.class.getClassLoader(),
                                new Class<?>[] {PreparedStatement.class},
                                (pProxy, pMethod, pArgs) -> {
                                    String pm = pMethod.getName();
                                    if ("executeUpdate".equals(pm)) {
                                        executedSqls.add(sql);
                                        return 1;
                                    }
                                    if ("executeBatch".equals(pm)) {
                                        executedSqls.add(sql);
                                        return new int[] {1};
                                    }
                                    if ("executeQuery".equals(pm)) {
                                        executedSqls.add(sql);
                                        return createMockResultSet(sql);
                                    }
                                    return null;
                                });
                    }
                    return null;
                });
    }

    private static ResultSet createMockResultSet(String sql) {
        Map<String, String> row = new LinkedHashMap<>();
        String lower = sql.toLowerCase(Locale.ROOT);
        if (lower.contains("from sqlflow.dataflow_aggregation_table")) {
            row.put("sql", "SELECT SUM(amount) FROM orders");
            row.put("sql_hash", "hash-1");
            row.put("file_path", "etl.sql");
            row.put("source_table", "orders");
            row.put("target_table", "sales_summary");
        } else if (lower.contains("from sqlflow.dataflow_sql_map_table")) {
            row.put("job_id", "ch-job-1");
            row.put("sql_hash", "hash-1");
            row.put("sql", "SELECT SUM(amount) FROM orders");
            row.put("file_path", "etl.sql");
            row.put("process_type", "select");
        } else if (lower.contains("from sqlflow.left_most_source_table")) {
            row.put("source_table", "orders");
            row.put("source_column", "amount");
            row.put("target_table", "sales_summary");
            row.put("target_column", "total_amount");
            row.put("lineage_paths", "orders.amount -> sales_summary.total_amount");
            row.put("sql", "SELECT SUM(amount) FROM orders");
            row.put("sql_hash", "hash-1");
        } else if (lower.contains("from sqlflow.column_level_out_going")) {
            row.put("source_table", "orders");
            row.put("column", "amount");
            row.put("stmt_consumed", "SELECT * FROM orders");
        }
        AtomicInteger cursor = new AtomicInteger(0);
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                (rProxy, rMethod, rArgs) -> {
                    String rm = rMethod.getName();
                    if ("next".equals(rm)) {
                        return cursor.getAndIncrement() == 0 && !row.isEmpty();
                    }
                    if ("getString".equals(rm)) {
                        return row.get(String.valueOf(rArgs[0]));
                    }
                    return null;
                });
    }
}
