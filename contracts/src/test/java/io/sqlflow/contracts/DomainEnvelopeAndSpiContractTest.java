package io.sqlflow.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.contracts.domain.AiQueryResponse;
import io.sqlflow.contracts.domain.AiStatusEnvelope;
import io.sqlflow.contracts.domain.ApiResponse;
import io.sqlflow.contracts.domain.DataSource;
import io.sqlflow.contracts.domain.GroupInfo;
import io.sqlflow.contracts.domain.JobDetail;
import io.sqlflow.contracts.domain.JobStatus;
import io.sqlflow.contracts.domain.JobSummary;
import io.sqlflow.contracts.domain.LatestJobGraphEnvelope;
import io.sqlflow.contracts.domain.LicenseInfo;
import io.sqlflow.contracts.domain.ProductInfo;
import io.sqlflow.contracts.domain.ProductListEnvelope;
import io.sqlflow.contracts.domain.R;
import io.sqlflow.contracts.domain.ResultCode;
import io.sqlflow.contracts.domain.SecretKeyResponse;
import io.sqlflow.contracts.domain.SubJob;
import io.sqlflow.contracts.domain.SubJobType;
import io.sqlflow.contracts.domain.SuggestedQuestion;
import io.sqlflow.contracts.domain.TokenResponse;
import io.sqlflow.contracts.domain.UserInfo;
import io.sqlflow.contracts.domain.VersionEnvelope;
import io.sqlflow.contracts.graph.DbObjectPosition;
import io.sqlflow.contracts.graph.JoinAnalysisGraph;
import io.sqlflow.contracts.graph.LeftMostSourceTable;
import io.sqlflow.contracts.graph.SqlflowJSON;
import io.sqlflow.contracts.graph.SummaryInfo;
import io.sqlflow.contracts.lineage.Coordinate;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.Option;
import io.sqlflow.contracts.spi.CsvLineageExportSpi;
import io.sqlflow.contracts.spi.DemoAstServiceSpi;
import io.sqlflow.contracts.spi.FileExtractorSpi;
import io.sqlflow.contracts.spi.GraphLayoutSpi;
import io.sqlflow.contracts.spi.JoinAnalysisSpi;
import io.sqlflow.contracts.spi.LineageEngineSpi;
import io.sqlflow.contracts.spi.MetadataConnectorSpi;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DomainEnvelopeAndSpiContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void apiResponseAndBytecodeVerifiedResultCodeEnvelopesPreserveWireStructure() throws Exception {
        assertThat(ResultCode.values()).hasSize(14);
        assertThat(ResultCode.SUCCESS.getCode()).isEqualTo(200);
        assertThat(ResultCode.SUCCESS.getMessage()).isEqualTo("Operation successful");
        assertThat(ResultCode.CLIENT_UN_AUTHORIZED.getCode()).isEqualTo(401);
        assertThat(ResultCode.CLIENT_UN_AUTHORIZED.getMessage()).isEqualTo("Client request unauthorized");
        assertThat(ResultCode.PARAM_VALID_ERROR.getCode()).isEqualTo(400);
        assertThat(ResultCode.PARAM_VALID_ERROR.getMessage()).isEqualTo("Parameter validation failed");

        ApiResponse<String> okRes = ApiResponse.okWithMode("ok-payload", "sess-123", "full");
        String okJson = mapper.writeValueAsString(okRes);
        ApiResponse<String> parsedOk = mapper.readValue(okJson, new TypeReference<>() {});
        assertThat(parsedOk.getCode()).isEqualTo(200);
        assertThat(parsedOk.getData()).isEqualTo("ok-payload");
        assertThat(parsedOk.getSessionId()).isEqualTo("sess-123");
        assertThat(parsedOk.getMode()).isEqualTo("full");

        ApiResponse<Void> expiredRes = ApiResponse.expired("Session expired");
        assertThat(expiredRes.getCode()).isEqualTo(600);
        assertThat(expiredRes.getExpired()).isTrue();

        R<String> rOk = R.data("v1-result");
        String rJson = mapper.writeValueAsString(rOk);
        R<String> parsedR = mapper.readValue(rJson, new TypeReference<>() {});
        assertThat(parsedR.getCode()).isEqualTo(200);
        assertThat(parsedR.getSuccess()).isTrue();
        assertThat(parsedR.getData()).isEqualTo("v1-result");
    }

    @Test
    void versionEnvelopeAndLicenseDisplayMapSupportUnlimitedAndIntegerMaxUsers() throws Exception {
        VersionEnvelope env = VersionEnvelope.defaultCleanRoom("8.2.2.15", "8.2.2.15", "2026-11-11");
        env.getVersion().setLicenseExpired("2099-12-31");
        env.getVersion().setLicenseMaxUsers("unlimited");

        String json = mapper.writeValueAsString(env);
        JsonNode root = mapper.readTree(json);

        // Wire invariant 00 §3.3 #4: unwrapped root {"version": {...}} without code or data
        assertThat(root.has("version")).isTrue();
        assertThat(root.has("code")).isFalse();
        assertThat(root.has("data")).isFalse();
        assertThat(root.get("version").get("gsp").get("version").asText()).isEqualTo("8.2.2.15");
        assertThat(root.get("version").get("gsp").get("release.date").asText()).isEqualTo("2026-11-11");
        assertThat(root.get("version").get("support").get("regular.job").asBoolean()).isTrue();
        assertThat(root.get("version").get("license.expired").asText()).isEqualTo("2099-12-31");
        assertThat(root.get("version").get("license.max.users").asText()).isEqualTo("unlimited");

        VersionEnvelope parsedUnlimited = mapper.readValue(json, VersionEnvelope.class);
        assertThat(parsedUnlimited.getVersion().getLicenseMaxUsers()).isEqualTo("unlimited");

        env.getVersion().setLicenseMaxUsers(9999);
        VersionEnvelope parsedNumeric =
                mapper.readValue(mapper.writeValueAsString(env), VersionEnvelope.class);
        assertThat(parsedNumeric.getVersion().getLicenseMaxUsers()).isEqualTo(9999);

        LicenseInfo license = LicenseInfo.openCleanRoomDefault();
        Map<String, Object> displayMap = license.toDisplayMap();
        assertThat(displayMap).containsKeys(
                "Supported databases",
                "Customer company name",
                "Customer contact name",
                "Customer contact email",
                "Product name",
                "Product version",
                "Purchase date",
                "Product license");

        String displayJson = mapper.writeValueAsString(displayMap);
        LicenseInfo parsedFromDisplay = mapper.readValue(displayJson, LicenseInfo.class);
        assertThat(parsedFromDisplay.getProductName()).isEqualTo("Gudu SQLFlow");
        assertThat(parsedFromDisplay.getProductVersion()).isEqualTo("8.2.2.15");
        assertThat(parsedFromDisplay.getCompanyName()).isEqualTo("Open Clean-Room Deployment");
    }

    @Test
    void tokenSecretKeyUserVoAndGroupVoMatchExactWireKeys() throws Exception {
        TokenResponse tokenRes = TokenResponse.ok(UserInfo.GUEST_USER_ID, "jwt-token-xyz", "premise");
        String tokenJson = mapper.writeValueAsString(tokenRes);
        JsonNode tokenNode = mapper.readTree(tokenJson);

        // Wire invariant 00 §3.3 #10: code MUST be JSON string "200", userSource is String
        assertThat(tokenNode.get("code").isTextual()).isTrue();
        assertThat(tokenNode.get("code").textValue()).isEqualTo("200");
        assertThat(tokenNode.get("userId").textValue()).isEqualTo("gudu|0123456789");
        assertThat(tokenNode.get("userSource").textValue()).isEqualTo("premise");

        SecretKeyResponse secretRes = SecretKeyResponse.ok(UserInfo.DEFAULT_ADMIN_USER_ID, "sk-12345");
        JsonNode secretNode = mapper.readTree(mapper.writeValueAsString(secretRes));
        assertThat(secretNode.get("code").isTextual()).isTrue();
        assertThat(secretNode.get("code").textValue()).isEqualTo("200");
        assertThat(secretNode.get("secretKey").textValue()).isEqualTo("sk-12345");

        UserInfo user = UserInfo.guest();
        user.setUserEmail("guest@sqlflow.local");
        user.setUserGroup("engineering");
        user.setLatestLoginDate("2026-11-11 08:00:00");
        user.setRemainingSqls("unlimited");
        JsonNode userNode = mapper.readTree(mapper.writeValueAsString(user));
        assertThat(userNode.get("userId").asText()).isEqualTo("gudu|0123456789");
        assertThat(userNode.get("userEmail").asText()).isEqualTo("guest@sqlflow.local");
        assertThat(userNode.get("userSource").asText()).isEqualTo("default");
        assertThat(userNode.get("userRole").asText()).isEqualTo("3");
        assertThat(userNode.get("latestLoginDate").asText()).isEqualTo("2026-11-11 08:00:00");

        UserInfo aliasUser = mapper.readValue(
                "{\"id\":\"u1\",\"email\":\"u1@corp\",\"role\":\"1\",\"lastLoginTime\":\"2026-01-01\"}",
                UserInfo.class);
        assertThat(aliasUser.getUserId()).isEqualTo("u1");
        assertThat(aliasUser.getUserEmail()).isEqualTo("u1@corp");
        assertThat(aliasUser.getUserRole()).isEqualTo("1");
        assertThat(aliasUser.getLatestLoginDate()).isEqualTo("2026-01-01");

        GroupInfo group = new GroupInfo();
        group.setGroupId("grp-1");
        group.setUserGroup("data-platform");
        group.setGroupName("Data Platform");
        group.setCreatedDate("2026-01-01");
        group.setExpiredDate("2099-12-31");
        group.setQuote("10000");
        group.setGroupUsers(List.of(new GroupInfo.GroupMember("alice@corp", "1")));
        JsonNode grpNode = mapper.readTree(mapper.writeValueAsString(group));
        assertThat(grpNode.get("groupId").asText()).isEqualTo("grp-1");
        assertThat(grpNode.get("createdDate").asText()).isEqualTo("2026-01-01");
        assertThat(grpNode.get("expiredDate").asText()).isEqualTo("2099-12-31");
        assertThat(grpNode.get("groupUsers").get(0).get("userEmail").asText()).isEqualTo("alice@corp");
        assertThat(grpNode.get("groupUsers").get(0).get("userRole").asText()).isEqualTo("1");
    }

    @Test
    void productListLatestJobGraphAndAiContractsMatchWireShape() throws Exception {
        ProductInfo prod = new ProductInfo(
                101,
                "SQLFlow On-Premise Enterprise",
                "1 Year",
                true,
                false,
                "$4999",
                "Full lineage & metadata connectors");
        ProductListEnvelope prodEnv = ProductListEnvelope.ok(List.of(prod));
        JsonNode prodNode = mapper.readTree(mapper.writeValueAsString(prodEnv));
        assertThat(prodNode.get("code").asInt()).isEqualTo(200);
        assertThat(prodNode.has("products")).isTrue();
        assertThat(prodNode.has("data")).isFalse();
        assertThat(prodNode.get("products").get(0).get("productId").asInt()).isEqualTo(101);
        assertThat(prodNode.get("products").get(0).get("productPeriod").asText()).isEqualTo("1 Year");
        assertThat(prodNode.get("products").get(0).get("recommend").asBoolean()).isTrue();
        assertThat(prodNode.get("products").get(0).get("productPrice").asText()).isEqualTo("$4999");

        JobSummary job = new JobSummary();
        job.setJobId("job-001");
        job.setJobName("Nightly Warehouse");
        job.setDbVendor("dbvsnowflake");
        job.setRegularJob(true);
        job.setCronExpression("0 0 2 * * ?");
        job.setOnTop(true);
        job.setStatus(JobStatus.SUCCESS);
        LatestJobGraphEnvelope latestEnv = LatestJobGraphEnvelope.ok(new SqlflowJSON(), job, "job-001", "sess-001");
        JsonNode latestNode = mapper.readTree(mapper.writeValueAsString(latestEnv));
        assertThat(latestNode.get("code").asInt()).isEqualTo(200);
        assertThat(latestNode.has("data")).isTrue();
        assertThat(latestNode.get("job").get("jobId").asText()).isEqualTo("job-001");
        assertThat(latestNode.get("job").get("dbVendor").asText()).isEqualTo("dbvsnowflake");
        assertThat(latestNode.get("job").get("regularJob").asBoolean()).isTrue();
        assertThat(latestNode.get("job").get("cronExpression").asText()).isEqualTo("0 0 2 * * ?");
        assertThat(latestNode.get("job").get("onTop").asBoolean()).isTrue();
        assertThat(latestNode.get("jobId").asText()).isEqualTo("job-001");
        assertThat(latestNode.get("sessionId").asText()).isEqualTo("sess-001");

        AiStatusEnvelope aiStatus = new AiStatusEnvelope(false, "disabled");
        JsonNode aiNode = mapper.readTree(mapper.writeValueAsString(aiStatus));
        assertThat(aiNode.has("code")).isFalse();
        assertThat(aiNode.get("enabled").asBoolean()).isFalse();

        AiQueryResponse aiRes = new AiQueryResponse(
                "orders.amount feeds daily_revenue.total_amount",
                List.of("orders", "daily_revenue"),
                List.of(new AiQueryResponse.ReferencedEdge("orders.amount", "daily_revenue.total_amount")),
                "high",
                List.of("direct fdd lineage"));
        JsonNode aiResNode = mapper.readTree(mapper.writeValueAsString(aiRes));
        assertThat(aiResNode.get("confidence").asText()).isEqualTo("high");
        assertThat(aiResNode.get("referencedNodes").get(0).asText()).isEqualTo("orders");
        assertThat(aiResNode.get("referencedEdges").get(0).get("from").asText()).isEqualTo("orders.amount");
        assertThat(aiResNode.get("basedOn").get(0).asText()).isEqualTo("direct fdd lineage");

        SuggestedQuestion sq = new SuggestedQuestion(
                "Which downstream views depend on orders?", "downstream", "orders");
        SuggestedQuestion parsedSq =
                mapper.readValue(mapper.writeValueAsString(sq), SuggestedQuestion.class);
        assertThat(parsedSq.getCategory()).isEqualTo("downstream");
        assertThat(parsedSq.getTargetNode()).isEqualTo("orders");
    }

    @Test
    void dataSourceMasksPasswordAnd50OrdinalJobDetailRoundTrips() throws Exception {
        DataSource ds = new DataSource();
        ds.setHostname("db.internal");
        ds.setPort("5432");
        ds.setAccount("etl_reader");
        ds.setPassword("super-secret");
        ds.setDatabase("dw");

        DataSource masked = ds.maskedCopy();
        assertThat(masked.getPassword()).isNull();
        assertThat(masked.getHostname()).isEqualTo("db.internal");
        assertThat(mapper.writeValueAsString(masked)).doesNotContain("super-secret");

        SubJob subJob = new SubJob(
                "batch-1",
                List.of("01_fact.sql"),
                "2026-11-11 02:00:00",
                SubJobType.DATAFLOW,
                JobStatus.SUCCESS,
                "1420 ms");

        JobDetail detail = new JobDetail();
        detail.setJobId("job-42");
        detail.setJobName("Snowflake Lineage");
        detail.setDbVendor("dbvsnowflake");
        detail.setStatus(JobStatus.PARTIAL_SUCCESS);
        detail.setDataSource(masked);
        detail.setShowRelationType("fdd,fdr");
        detail.setTreatArgumentsInCountFunctionAsDirectDataflow(false);
        detail.setShowTransform(true);
        detail.setSessionId("sess-job-42");
        detail.setHasMetadata(true);
        detail.setCsvFormat("default");
        detail.setParallel(true);
        detail.setLineageType("table_column");
        detail.setHideLineageFromStoredProcedure(false);
        detail.setSubJobs(List.of(subJob));
        detail.setViewThreshold(200);
        String detailJson = mapper.writeValueAsString(detail);
        JobDetail parsedDetail = mapper.readValue(detailJson, JobDetail.class);
        assertThat(parsedDetail.getJobId()).isEqualTo("job-42");
        assertThat(parsedDetail.getStatus()).isEqualTo(JobStatus.PARTIAL_SUCCESS);
        assertThat(parsedDetail.getShowRelationType()).isEqualTo("fdd,fdr");
        assertThat(parsedDetail.getShowTransform()).isTrue();
        assertThat(parsedDetail.getSubJobs().get(0).getExecuteTime()).isEqualTo("1420 ms");
        assertThat(parsedDetail.getViewThreshold()).isEqualTo(200);
    }

    @Test
    void demoAstWirePayloadsAndAllSevenFrozenSpisCompileCleanly() throws Exception {
        assertThat(MetadataConnectorSpi.SUPPORTED_14_JDBC_VENDORS).hasSize(14);
        assertThat(CsvLineageExportSpi.WEB_JOB_15_COLUMN_HEADER).hasSize(15);
        assertThat(CsvLineageExportSpi.WEB_JOB_16_COLUMN_HEADER).hasSize(16);
        assertThat(CsvLineageExportSpi.GENERATION_15_COLUMN_HEADER).hasSize(15);
        assertThat(CsvLineageExportSpi.GENERATION_16_COLUMN_HEADER).hasSize(16);
        assertThat(CsvLineageExportSpi.METADATA_CSV_9_COLUMN_HEADER).hasSize(9);

        DemoAstServiceSpi.FormatConfiguration fmtCfg =
                new DemoAstServiceSpi.FormatConfiguration("dbvpostgresql");
        fmtCfg.setOutput("sql");
        fmtCfg.setFormatOptions(Map.of("indentLen", 4, "caseKeywords", "Upper"));
        DemoAstServiceSpi.FormatRequest fmtReq =
                new DemoAstServiceSpi.FormatRequest("select a,b from t", fmtCfg);
        JsonNode fmtReqNode = mapper.readTree(mapper.writeValueAsString(fmtReq));
        assertThat(fmtReqNode.get("input_sql").asText()).isEqualTo("select a,b from t");
        assertThat(fmtReqNode.get("format_option").get("db_vendor").asText()).isEqualTo("dbvpostgresql");
        assertThat(fmtReqNode.get("format_option").get("format_options").get("indentLen").asInt())
                .isEqualTo(4);

        DemoAstServiceSpi.FormatResult fmtRes =
                new DemoAstServiceSpi.FormatResult("SELECT\n    a,\n    b\nFROM t", null);
        JsonNode fmtResNode = mapper.readTree(mapper.writeValueAsString(fmtRes));
        assertThat(fmtResNode.get("formatted_sql").asText()).startsWith("SELECT");

        DemoAstServiceSpi.SqlInjectionFinding injFinding = new DemoAstServiceSpi.SqlInjectionFinding(
                "tautology", "Always-true OR 1=1 condition", new Coordinate(1, 20));
        DemoAstServiceSpi.SqlInjectionResult injRes =
                new DemoAstServiceSpi.SqlInjectionResult(true, "SQL injection risk detected", List.of(injFinding));
        JsonNode injNode = mapper.readTree(mapper.writeValueAsString(injRes));
        assertThat(injNode.get("found").asBoolean()).isTrue();
        assertThat(injNode.get("message").asText()).isEqualTo("SQL injection risk detected");
        assertThat(injNode.get("errors").get(0).get("message").asText())
                .isEqualTo("Always-true OR 1=1 condition");

        DemoAstServiceSpi.SyntaxCheckResult synRes = new DemoAstServiceSpi.SyntaxCheckResult();
        synRes.setGspVersion("8.2.2.15");
        synRes.setDbVendor("dbvoracle");
        synRes.setUsedTime("4 ms");
        synRes.setErrorInfos(List.of());
        JsonNode synNode = mapper.readTree(mapper.writeValueAsString(synRes));
        assertThat(synNode.get("gsp.version").asText()).isEqualTo("8.2.2.15");
        assertThat(synNode.get("dbVendor").asText()).isEqualTo("dbvoracle");
        assertThat(synNode.get("errorInfos").isArray()).isTrue();

        // Verify SPI implementations wire cleanly against frozen DTOs
        LineageEngineSpi lineageSpi = new LineageEngineSpi() {
            @Override
            public LineageAnalysisResult analyze(LineageAnalysisRequest request) {
                return new LineageAnalysisResult(new DataFlow(), null, "<dlineage/>", new byte[] {1}, new SummaryInfo());
            }

            @Override
            public DataFlow mergeDataFlows(List<DataFlow> batches, Option option) {
                return new DataFlow();
            }

            @Override
            public SummaryInfo summarize(DataFlow dataFlow) {
                return new SummaryInfo();
            }

            @Override
            public List<String> splitLargeSql(String sqlText, EDbVendor vendor, int maxBytesPerChunk) {
                return List.of(sqlText);
            }
        };
        assertThat(lineageSpi.analyze(LineageEngineSpi.LineageAnalysisRequest.ofSql("SELECT 1", new Option()))
                        .getXmlString())
                .isEqualTo("<dlineage/>");

        JoinAnalysisSpi joinSpi = req -> new JoinAnalysisGraph();
        assertThat(joinSpi.analyzeJoins(JoinAnalysisSpi.JoinAnalysisRequest.of("SELECT 1", EDbVendor.dbvoracle))
                        .getSchemaVersion())
                .isEqualTo("join-analysis.v1");

        MetadataConnectorSpi connectorSpi = new MetadataConnectorSpi() {
            @Override
            public ConnectionTestResult testConnection(EDbVendor vendor, DataSource dataSource) {
                return ConnectionTestResult.ok("PostgreSQL", "16.2", 5L);
            }

            @Override
            public MetadataHarvestResult harvestMetadata(EDbVendor vendor, DataSource dataSource) {
                return new MetadataHarvestResult();
            }
        };
        assertThat(connectorSpi.supportedVendors()).contains(EDbVendor.dbvpostgresql);
        assertThat(connectorSpi.testConnection(EDbVendor.dbvpostgresql, new DataSource()).isConnected()).isTrue();

        FileExtractorSpi extractorSpi = req -> new FileExtractorSpi.ExtractionResult();
        assertThat(extractorSpi.supportedFormats()).contains(FileExtractorSpi.ExtractorFormat.DBT);

        CsvLineageExportSpi csvSpi = new CsvLineageExportSpi() {
            @Override
            public String exportLineageCsv(DataFlow dataFlow, CsvExportOptions options) {
                return String.join(",", WEB_JOB_16_COLUMN_HEADER);
            }

            @Override
            public String exportMetadataCsv(DataFlow dataFlow) {
                return String.join(",", METADATA_CSV_9_COLUMN_HEADER);
            }

            @Override
            public byte[] exportMetadataLineageZip(DataFlow dataFlow, CsvExportOptions options) {
                return "PK".getBytes(StandardCharsets.UTF_8);
            }
        };
        assertThat(csvSpi.exportLineageCsv(new DataFlow(), CsvLineageExportSpi.CsvExportOptions.webJob16Col()))
                .startsWith("source_db,source_schema")
                .endsWith(",procedure,transform");

        GraphLayoutSpi layoutSpi = new GraphLayoutSpi() {
            @Override
            public SqlflowJSON layoutGraph(GraphLayoutRequest request) {
                return new SqlflowJSON();
            }

            @Override
            public SlicingResult sliceUpstream(GraphSliceRequest request) {
                return new SlicingResult();
            }

            @Override
            public SlicingResult sliceDownstream(GraphSliceRequest request) {
                return new SlicingResult();
            }

            @Override
            public List<DbObjectPosition> getSelectedDbObjectInfo(SelectedObjectRequest request) {
                return List.of();
            }

            @Override
            public LeftMostSourceTable computeLeftMostSourceTable(
                    DataFlow dataFlow, String targetTableId, boolean ignoreRecordSet) {
                return new LeftMostSourceTable();
            }

            @Override
            public DataFlow computeOutgoingClosure(DataFlow dataFlow, List<String> rootObjectIds, int maxDepth) {
                return dataFlow;
            }

            @Override
            public String exportGraphML(SqlflowJSON graph) {
                return "<graphml/>";
            }

            @Override
            public byte[] renderPng(SqlflowJSON graph, PngRenderOptions options) {
                return new byte[0];
            }
        };
        assertThat(layoutSpi.exportGraphML(new SqlflowJSON())).isEqualTo("<graphml/>");
    }
}
