package io.sqlflow.server.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.contracts.domain.ApiResponse;
import io.sqlflow.contracts.domain.JobDetail;
import io.sqlflow.contracts.domain.LatestJobGraphEnvelope;
import io.sqlflow.contracts.domain.R;
import io.sqlflow.contracts.domain.UserJobListSummary;
import io.sqlflow.contracts.graph.DbObjectPosition;
import io.sqlflow.contracts.graph.LeftMostSourceTable;
import io.sqlflow.contracts.graph.SqlflowJSON;
import io.sqlflow.contracts.graph.SummaryInfo;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.SqlInfo;
import io.sqlflow.contracts.spi.JoinAnalysisSpi;
import io.sqlflow.server.config.SqlflowRuntimeConfig;
import io.sqlflow.server.security.RequestAuthResolver;
import io.sqlflow.server.service.JobCenterService;
import io.sqlflow.server.service.LineageGenerationService;
import io.sqlflow.server.service.ShareService;
import io.sqlflow.server.service.UserSchemaService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Public REST API v1 controller implementing all 60 {@code /v1/sqlflow/**} endpoints
 * ({@code SqlflowV1Controller} 25 routes + {@code SqlflowJobV1Controller} 35 routes, plus
 * {@code /api/sqlflow/v1/**} aliases) ({@code 01 §3.4.17–§3.4.18}, {@code OBL-019, OBL-027}).
 *
 * <p>Bearer {@code secretKey} authentication is enforced by {@link io.sqlflow.server.security.ApiV1AuthInterceptor}
 * (returning HTTP 401 {@code R<Void>} on missing/invalid keys), except on the sole public endpoint
 * {@code GET /v1/sqlflow/sqlflow/graph/share/{shareId}}.
 */
@RestController
@RequestMapping({"/v1/sqlflow", "/api/sqlflow/v1"})
public class ApiV1Controller {

    private final LineageGenerationService lineageService;
    private final UserSchemaService userSchemaService;
    private final ShareService shareService;
    private final JobCenterService jobCenterService;
    private final JoinAnalysisSpi joinAnalysisSpi;
    private final SqlflowRuntimeConfig config;
    private final ControllerParamHelper paramHelper;
    private final ObjectMapper objectMapper;

    public ApiV1Controller(
            LineageGenerationService lineageService,
            UserSchemaService userSchemaService,
            ShareService shareService,
            JobCenterService jobCenterService,
            JoinAnalysisSpi joinAnalysisSpi,
            SqlflowRuntimeConfig config,
            ControllerParamHelper paramHelper,
            ObjectMapper objectMapper) {
        this.lineageService = lineageService;
        this.userSchemaService = userSchemaService;
        this.shareService = shareService;
        this.jobCenterService = jobCenterService;
        this.joinAnalysisSpi = joinAnalysisSpi;
        this.config = config;
        this.paramHelper = paramHelper;
        this.objectMapper = objectMapper;
    }

    private String v1UserId(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestAuthResolver.ATTR_V1_USER_ID);
        return attr != null ? attr.toString() : SqlflowRuntimeConfig.DEFAULT_GUEST_USER_ID;
    }

    private static <T> R<T> wrapApi(ApiResponse<T> apiRes) {
        if (apiRes == null) {
            return R.fail(500, "Empty response");
        }
        int code = 200;
        if (apiRes.getCode() != null) {
            try {
                code = Integer.parseInt(apiRes.getCode().toString());
            } catch (NumberFormatException ignored) {
                code = 500;
            }
        }
        if (code != 200) {
            return R.fail(code, apiRes.getError() != null ? apiRes.getError() : "Request failed");
        }
        return R.data(apiRes.getData());
    }

    // =========================================================================
    // Part A: SqlflowV1Controller — All 25 Interactive / Schema / Share Routes
    // =========================================================================

    /** 1. POST /v1/sqlflow/sqlflow/upstreamGraph */
    @PostMapping(value = "/sqlflow/upstreamGraph", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<SqlflowJSON> upstreamGraphV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        return wrapApi(lineageService.sliceGraph(v1UserId(request), params, null, true));
    }

    /** 2. POST /v1/sqlflow/sqlflow/downstreamGraph */
    @PostMapping(value = "/sqlflow/downstreamGraph", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<SqlflowJSON> downstreamGraphV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        return wrapApi(lineageService.sliceGraph(v1UserId(request), params, null, false));
    }

    /** 3. POST /v1/sqlflow/submitUserSchema */
    @PostMapping(value = "/submitUserSchema", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<List<String>> submitUserSchemaV1(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        String vendor = LineageGenerationService.firstNonBlank(params, "dbvendor", "dbVendor", "vendor");
        String inline = LineageGenerationService.firstNonBlank(params, "sqltext", "sql", "content");
        List<String> saved = userSchemaService.submitUserSchema(
                v1UserId(request), vendor, resolveFiles(sqlfiles, sqlfile), inline);
        return R.data(saved);
    }

    /** 4. POST /v1/sqlflow/displayUserSchemaSummary */
    @RequestMapping(
            value = "/displayUserSchemaSummary",
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public R<Map<String, List<List<String>>>> displayUserSchemaSummaryV1(HttpServletRequest request) {
        return R.data(userSchemaService.displayUserSchemaSummary(v1UserId(request)));
    }

    /** 5. POST /v1/sqlflow/displayUserDbvendorSchema */
    @PostMapping(value = "/displayUserDbvendorSchema", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<List<String>> displayUserDbvendorSchemaV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        String vendor = LineageGenerationService.firstNonBlank(params, "dbvendor", "dbVendor", "vendor");
        return R.data(userSchemaService.displayUserDbvendorSchema(v1UserId(request), vendor));
    }

    /** 6. POST /v1/sqlflow/deleteUserSchema */
    @RequestMapping(
            value = "/deleteUserSchema",
            method = {RequestMethod.POST, RequestMethod.DELETE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public R<Boolean> deleteUserSchemaV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        String vendor = LineageGenerationService.firstNonBlank(params, "dbvendor", "dbVendor", "vendor");
        String fileName = LineageGenerationService.firstNonBlank(params, "fileName", "file");
        return R.data(userSchemaService.deleteUserSchema(v1UserId(request), vendor, fileName));
    }

    /** 7. POST /v1/sqlflow/checkSyntax */
    @PostMapping(value = "/checkSyntax", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<Object> checkSyntaxV1(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        params.put("sqltext", lineageService.extractSqlFromRequest(params, resolveFiles(sqlfiles, sqlfile)));
        return wrapApi(lineageService.checkSyntax(params));
    }

    /** 8. POST /v1/sqlflow/sqlflow */
    @PostMapping(value = "/sqlflow", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<DataFlow> generateSqlflowV1(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        String[] sid = new String[1];
        DataFlow df = lineageService.resolveOrAnalyzeDataFlow(
                v1UserId(request), params, resolveFiles(sqlfiles, sqlfile), sid);
        return R.data(df);
    }

    /** 9 & 10. POST /v1/sqlflow/sqlflow/graph & /v1/sqlflow/sqlflow/selectedgraph */
    @PostMapping(
            value = {"/sqlflow/graph", "/sqlflow/selectedgraph"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public R<SqlflowJSON> generateGraphV1(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        boolean tableLevel = LineageGenerationService.parseBool(params.get("tableLevel"), false)
                || "false".equalsIgnoreCase(params.get("columnLevel"));
        return wrapApi(lineageService.generateGraph(
                v1UserId(request), params, resolveFiles(sqlfiles, sqlfile), tableLevel, false));
    }

    /** 11 & 12. POST /v1/sqlflow/sqlflow/graph/table_level_lineage & /selectedgraph/table_level_lineage */
    @PostMapping(
            value = {
                "/sqlflow/graph/table_level_lineage",
                "/sqlflow/selectedgraph/table_level_lineage"
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public R<SqlflowJSON> generateTableLevelLineageV1(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        return wrapApi(lineageService.generateGraph(
                v1UserId(request), params, resolveFiles(sqlfiles, sqlfile), true, false));
    }

    /** 13. POST /v1/sqlflow/sql/format */
    @PostMapping(value = "/sql/format", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<Object> formatSqlV1(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        params.put("sqltext", lineageService.extractSqlFromRequest(params, resolveFiles(sqlfiles, sqlfile)));
        return wrapApi(lineageService.formatSql(params));
    }

    /** 14. POST /v1/sqlflow/sqlflow/listTableColumns */
    @PostMapping(value = "/sqlflow/listTableColumns", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<List<Map<String, Object>>> listTableColumnsV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        return wrapApi(lineageService.listTableColumns(v1UserId(request), params, null));
    }

    /** 15. POST /v1/sqlflow/sqlflow/leftMostSourceTableGraph */
    @PostMapping(value = "/sqlflow/leftMostSourceTableGraph", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<SqlflowJSON> leftMostSourceTableGraphV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        return wrapApi(lineageService.generateLeftMostSourceTableGraph(v1UserId(request), params, null));
    }

    /** 16. POST /v1/sqlflow/sqlflow/getSelectedDbObjectInfo */
    @PostMapping(value = "/sqlflow/getSelectedDbObjectInfo", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<List<DbObjectPosition>> getSelectedDbObjectInfoV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        return wrapApi(lineageService.getSelectedDbObjectInfo(v1UserId(request), params));
    }

    /** 17 & 21. POST /v1/sqlflow/sqlflow/download/metadata_lineage & /selected_dbobject/download/metadata_lineage */
    @PostMapping(value = {
        "/sqlflow/download/metadata_lineage",
        "/sqlflow/selected_dbobject/download/metadata_lineage"
    })
    public ResponseEntity<byte[]> downloadMetadataLineageV1(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        byte[] zip = lineageService.exportMetadataLineageZip(
                v1UserId(request), params, resolveFiles(sqlfiles, sqlfile));
        return DownloadHelper.bytesAttachment(zip, "metadata_lineage.zip", MediaType.APPLICATION_OCTET_STREAM);
    }

    /** 18, 20, & 22. POST /v1/sqlflow/sqlflow/exportFullLineageAsCsv, exportLineageAsCsv, selected_dbobject/exportLineageAsCsv */
    @PostMapping(value = {
        "/sqlflow/exportFullLineageAsCsv",
        "/sqlflow/exportLineageAsCsv",
        "/sqlflow/selected_dbobject/exportLineageAsCsv"
    })
    public ResponseEntity<byte[]> exportLineageAsCsvV1(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        String csv = lineageService.exportAsCsv(
                v1UserId(request), params, resolveFiles(sqlfiles, sqlfile), false);
        return DownloadHelper.textAttachment(csv, "lineage.csv", MediaType.valueOf("text/csv"));
    }

    /** 19. POST /v1/sqlflow/sqlflow/exportFullLineageAsJson */
    @PostMapping(value = {"/sqlflow/exportFullLineageAsJson", "/sqlflow/exportLineageAsJson"})
    public ResponseEntity<byte[]> exportFullLineageAsJsonV1(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        String json = lineageService.exportAsJson(
                v1UserId(request), params, resolveFiles(sqlfiles, sqlfile));
        return DownloadHelper.textAttachment(json, "lineage.json", MediaType.APPLICATION_JSON);
    }

    /** 23. GET /v1/sqlflow/sqlflow/graph/share/{shareId} — PUBLIC (exempt from Bearer key!) */
    @GetMapping(value = "/sqlflow/graph/share/{shareId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<SqlflowJSON> shareSqlflowGraphV1(@PathVariable("shareId") String shareId) {
        return R.data(shareService.getSharedGraph(shareId));
    }

    /** 24. POST /v1/sqlflow/sqlflow/erdiagramSelectGraph */
    @PostMapping(value = "/sqlflow/erdiagramSelectGraph", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<SqlflowJSON> erdiagramSelectGraphV1(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        return wrapApi(lineageService.generateGraph(
                v1UserId(request), params, resolveFiles(sqlfiles, sqlfile), false, true));
    }

    /** 25. POST /v1/sqlflow/sqlflow/graph/call_lineage */
    @PostMapping(value = "/sqlflow/graph/call_lineage", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<SqlflowJSON> callLineageV1(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Object rawBody) {
        Map<String, String> params;
        List<SqlInfo> sqlInfos = null;
        if (rawBody instanceof List<?>) {
            params = paramHelper.extractParams(request, null);
            sqlInfos = objectMapper.convertValue(rawBody, new TypeReference<List<SqlInfo>>() {});
        } else if (rawBody instanceof Map<?, ?> mapBody) {
            @SuppressWarnings("unchecked")
            Map<String, Object> casted = (Map<String, Object>) mapBody;
            params = paramHelper.extractParams(request, casted);
            if (casted.get("sqlInfos") instanceof List<?>) {
                sqlInfos = objectMapper.convertValue(casted.get("sqlInfos"), new TypeReference<List<SqlInfo>>() {});
            }
        } else {
            params = paramHelper.extractParams(request, null);
        }
        return wrapApi(lineageService.generateCallLineage(
                v1UserId(request), sqlInfos, params, resolveFiles(sqlfiles, sqlfile)));
    }

    @PostMapping(value = "/sqlflow/join-analysis", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> joinAnalysisV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        if (config.isDisableVisualizeJoin()) {
            return ResponseEntity.status(403).body(R.fail(403, "Visualize Join is disabled by configuration."));
        }
        Map<String, String> params = paramHelper.extractParams(request, body);
        String sql = LineageGenerationService.firstNonBlank(params, "sqltext", "sql");
        if (sql == null || sql.isBlank()) {
            return ResponseEntity.badRequest().body(R.fail(400, "sqltext is required"));
        }
        JoinAnalysisSpi.JoinAnalysisRequest jr = new JoinAnalysisSpi.JoinAnalysisRequest();
        jr.setSqlText(sql);
        jr.setVendor(lineageService.resolveVendor(params.get("dbvendor")));
        jr.setDefaultServer(params.get("defaultServer"));
        jr.setDefaultDatabase(params.get("defaultDatabase"));
        jr.setDefaultSchema(params.get("defaultSchema"));
        return ResponseEntity.ok(joinAnalysisSpi.analyzeJoins(jr));
    }

    // =========================================================================
    // Part B: SqlflowJobV1Controller — All 35 Job / Persist / Export Routes
    // =========================================================================

    /** 26. POST /v1/sqlflow/job/rerunJob */
    @PostMapping(value = {"/job/rerunJob", "/job/resubmitJob"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public R<JobDetail> rerunJobV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id", "jobName");
        return R.data(jobCenterService.resubmitJob(jobId));
    }

    /** 27. POST /v1/sqlflow/job/submitPersistJob */
    @PostMapping(value = "/job/submitPersistJob", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<JobDetail> submitPersistJobV1(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        return R.data(jobCenterService.submitJob(
                v1UserId(request), params, resolveFiles(sqlfiles, sqlfile), true));
    }

    /** 28. POST /v1/sqlflow/job/fetchRegularJobTableRelationSqls */
    @PostMapping(value = "/job/fetchRegularJobTableRelationSqls", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<List<List<String>>> fetchRegularJobTableRelationSqlsV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        return R.data(jobCenterService.fetchRegularJobTableRelationSqls(
                params.get("jobId"),
                params.get("sourceTable"),
                params.get("sourceColumn"),
                params.get("targetTable"),
                params.get("targetColumn")));
    }

    /** 29. POST /v1/sqlflow/job/fetchTableRelationSqlBySqlHash */
    @PostMapping(value = "/job/fetchTableRelationSqlBySqlHash", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<List<Map<String, Object>>> fetchTableRelationSqlBySqlHashV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        return R.data(jobCenterService.fetchTableRelationSqlBySqlHash(
                params.get("jobId"),
                LineageGenerationService.firstNonBlank(params, "sqlHash", "hash")));
    }

    /** 30. POST /v1/sqlflow/job/submitTableAffectSQLJob */
    @PostMapping(value = "/job/submitTableAffectSQLJob", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<Map<String, Object>> submitTableAffectSQLJobV1(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        List<String> sqlList = new ArrayList<>();
        if (params.get("sqltext") != null) {
            sqlList.add(params.get("sqltext"));
        }
        boolean columnLevel = LineageGenerationService.parseBool(params.get("columnLevel"), true);
        return R.data(jobCenterService.submitTableAffectSQLJob(
                params.get("jobId"), sqlList, sqlfiles, columnLevel));
    }

    /** 31 & 47. POST /v1/sqlflow/job/download/subJobFile & /v1/sqlflow/job/exportJobFile */
    @RequestMapping(
            value = {"/job/download/subJobFile", "/job/exportJobFile"},
            method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<byte[]> exportJobFileV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        String fileName = LineageGenerationService.firstNonBlank(params, "fileName", "name");
        Map<String, Object> res = jobCenterService.getJobFiles(jobId, fileName);
        String content = res.get("content") instanceof String s ? s : "";
        String downloadName = fileName != null && !fileName.isBlank() ? fileName : (jobId + ".sql");
        return DownloadHelper.textAttachment(content, downloadName, MediaType.TEXT_PLAIN);
    }

    /** 32. POST /v1/sqlflow/job/fetchLeftMostTableLineageSQL */
    @PostMapping(value = "/job/fetchLeftMostTableLineageSQL", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<List<Map<String, Object>>> fetchLeftMostTableLineageSQLV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        return R.data(jobCenterService.fetchLeftMostTableLineageSQL(
                params.get("jobId"),
                params.get("targetTable"),
                params.get("targetColumn"),
                params.get("sourceTable"),
                params.get("sourceColumn")));
    }

    /** 33. POST /v1/sqlflow/job/submitUserJob */
    @PostMapping(value = "/job/submitUserJob", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<JobDetail> submitUserJobV1(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        return R.data(jobCenterService.submitJob(
                v1UserId(request), params, resolveFiles(sqlfiles, sqlfile), false));
    }

    /** 34. POST /v1/sqlflow/job/displayUserJobsSummary */
    @RequestMapping(
            value = "/job/displayUserJobsSummary",
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public R<UserJobListSummary> displayUserJobsSummaryV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        int pageNo = LineageGenerationService.parseInt(params.get("pageNo"), 1);
        int pageSize = LineageGenerationService.parseInt(params.get("pageSize"), 100);
        return R.data(jobCenterService.listJobsSummary(
                v1UserId(request), false, pageNo, pageSize, params.get("keyword"), params.get("status")));
    }

    /** 35. POST /v1/sqlflow/job/displayUserJobSummary */
    @RequestMapping(
            value = {"/job/displayUserJobSummary", "/job/queryJobDetail", "/job/queryJobStatus"},
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public R<JobDetail> displayUserJobSummaryV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id", "jobName");
        return R.data(jobCenterService.getJobDetail(jobId));
    }

    /** 36. POST /v1/sqlflow/job/listScheduleJobs */
    @RequestMapping(
            value = "/job/listScheduleJobs",
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public R<List<JobDetail>> listScheduleJobsV1() {
        UserJobListSummary all = jobCenterService.listJobsSummary(null, true, 1, 500, null, null);
        List<JobDetail> scheduled = new ArrayList<>();
        if (all != null && all.getJobDetails() != null) {
            for (JobDetail jd : all.getJobDetails()) {
                if (Boolean.TRUE.equals(jd.getSchedulable())) {
                    scheduled.add(jd);
                }
            }
        }
        return R.data(scheduled);
    }

    /** 37. POST /v1/sqlflow/job/deleteUserJob */
    @RequestMapping(
            value = "/job/deleteUserJob",
            method = {RequestMethod.POST, RequestMethod.DELETE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public R<Boolean> deleteUserJobV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        return R.data(jobCenterService.deleteJob(jobId));
    }

    /** 38. POST /v1/sqlflow/job/pauseScheduleJob */
    @PostMapping(value = {"/job/pauseScheduleJob", "/job/cancelScheduleJob"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public R<JobDetail> pauseScheduleJobV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        return R.data(jobCenterService.cancelScheduleJob(jobId));
    }

    /** 39. POST /v1/sqlflow/job/resumeScheduleJob */
    @PostMapping(value = "/job/resumeScheduleJob", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<JobDetail> resumeScheduleJobV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        JobDetail detail = jobCenterService.getJobDetail(jobId);
        String cron = detail != null && detail.getCronExpression() != null && !detail.getCronExpression().isBlank()
                ? detail.getCronExpression()
                : "0 0 * * * *";
        return R.data(jobCenterService.scheduleJob(jobId, cron));
    }

    /** 40. POST /v1/sqlflow/job/updateScheduleJob */
    @PostMapping(value = "/job/updateScheduleJob", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<JobDetail> updateScheduleJobV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        String cron = LineageGenerationService.firstNonBlank(params, "cronExpression", "cron");
        return R.data(jobCenterService.scheduleJob(jobId, cron));
    }

    /** 41. POST /v1/sqlflow/job/setJobOnTop */
    @PostMapping(value = "/job/setJobOnTop", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<JobDetail> setJobOnTopV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        boolean onTop = LineageGenerationService.parseBool(params.get("onTop"), true);
        return R.data(jobCenterService.setJobOnTop(jobId, onTop));
    }

    /** 42. POST /v1/sqlflow/job/updateUserJobGraphCache */
    @PostMapping(value = "/job/updateUserJobGraphCache", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<Map<String, Object>> updateUserJobGraphCacheV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        return wrapApi(jobCenterService.updateUserJobGraphCache(jobId));
    }

    /** 43. POST /v1/sqlflow/job/exportJobFullLineageAsJson */
    @RequestMapping(
            value = {
                "/job/exportJobFullLineageAsJson",
                "/job/exportFullLineageAsJson",
                "/job/exportLineageAsJson"
            },
            method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<byte[]> exportJobFullLineageAsJsonV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        String json = jobCenterService.exportJobLineageAsJson(jobId);
        return DownloadHelper.textAttachment(json, jobId + "_lineage.json", MediaType.APPLICATION_JSON);
    }

    /** 44. POST /v1/sqlflow/job/exportJobFullLineageAsCsv */
    @RequestMapping(
            value = {
                "/job/exportJobFullLineageAsCsv",
                "/job/exportFullLineageAsCsv",
                "/job/exportLineageAsCsv"
            },
            method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<byte[]> exportJobFullLineageAsCsvV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        boolean showTransform = LineageGenerationService.parseBool(params.get("showTransform"), false);
        char delim = params.get("delimiter") != null && !params.get("delimiter").isEmpty()
                ? params.get("delimiter").charAt(0)
                : ',';
        String csv = jobCenterService.exportJobLineageAsCsv(jobId, showTransform, delim);
        return DownloadHelper.textAttachment(csv, jobId + "_lineage.csv", MediaType.valueOf("text/csv"));
    }

    /** 45. POST /v1/sqlflow/job/exportJobLineageAsGraphML */
    @RequestMapping(
            value = {"/job/exportJobLineageAsGraphML", "/job/exportLineageAsGraphml"},
            method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<byte[]> exportJobLineageAsGraphMLV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        String gml = jobCenterService.exportJobLineageAsGraphml(jobId);
        return DownloadHelper.textAttachment(gml, jobId + ".graphml", MediaType.APPLICATION_XML);
    }

    /** 46 & 48. POST /v1/sqlflow/job/exportJobMetadata & /v1/sqlflow/job/exportJobDatabaseMetadata */
    @RequestMapping(
            value = {
                "/job/exportJobMetadata",
                "/job/exportJobDatabaseMetadata",
                "/job/exportMetadata",
                "/job/exportDatabaseMetadata"
            },
            method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<byte[]> exportJobMetadataV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        String json = jobCenterService.exportJobDatabaseMetadata(jobId);
        return DownloadHelper.textAttachment(json, jobId + "_metadata.json", MediaType.APPLICATION_JSON);
    }

    /** 49 & 51. POST /v1/sqlflow/job/displayProcedureSource & /v1/sqlflow/job/displayMetadataQuerySource */
    @PostMapping(
            value = {"/job/displayProcedureSource", "/job/displayMetadataQuerySource"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public R<Object> displayProcedureOrQuerySourceV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        String fileName = LineageGenerationService.firstNonBlank(params, "fileName", "name");
        Map<String, Object> files = jobCenterService.getJobFiles(jobId, fileName);
        if (files.get("content") instanceof String content) {
            return R.data(content);
        }
        return R.data(files);
    }

    /** 50, 52, & 55. POST /v1/sqlflow/job/displayProcedureGraph, displayMetadataQueryGraph, displayUserJobGraph */
    @PostMapping(
            value = {
                "/job/displayUserJobGraph",
                "/job/displayProcedureGraph",
                "/job/displayMetadataQueryGraph"
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public R<SqlflowJSON> displayUserJobGraphV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        return wrapApi(lineageService.generateGraph(v1UserId(request), params, null, false, false));
    }

    /** 53. POST /v1/sqlflow/job/leftMostSourceTable */
    @PostMapping(value = "/job/leftMostSourceTable", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<LeftMostSourceTable> leftMostSourceTableV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        return wrapApi(lineageService.generateLeftMostSourceTable(v1UserId(request), params, null));
    }

    /** 54. POST /v1/sqlflow/job/leftMostSourceTableAsCsv */
    @PostMapping(value = "/job/leftMostSourceTableAsCsv")
    public ResponseEntity<byte[]> leftMostSourceTableAsCsvV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        String csv = jobCenterService.exportLeftMostSourceTableAsCsv(jobId, params.get("tableNamePattern"));
        return DownloadHelper.textAttachment(csv, jobId + "_leftmost.csv", MediaType.valueOf("text/csv"));
    }

    /** 56. POST /v1/sqlflow/job/displayUserLatestJobGraph */
    @PostMapping(value = "/job/displayUserLatestJobGraph", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> displayUserLatestJobGraphV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        LatestJobGraphEnvelope env =
                jobCenterService.displayUserLatestJobGraph(v1UserId(request), params.get("jobId"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 200);
        out.put("success", true);
        out.put("job", env.getJob());
        out.put("jobId", env.getJobId());
        out.put("sessionId", env.getSessionId());
        out.put("data", env.getData());
        return out;
    }

    /** 57. POST /v1/sqlflow/job/displayUserLatestJobLeftMostTable */
    @PostMapping(value = "/job/displayUserLatestJobLeftMostTable", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<SqlflowJSON> displayUserLatestJobLeftMostTableV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        String uid = v1UserId(request);
        LatestJobGraphEnvelope latest = jobCenterService.displayUserLatestJobGraph(uid, params.get("jobId"));
        if (latest.getJobId() != null) {
            params.put("jobId", latest.getJobId());
        }
        return wrapApi(lineageService.generateLeftMostSourceTableGraph(uid, params, null));
    }

    /** 58. POST /v1/sqlflow/job/displayUserLatestJobTableLevelLineage */
    @PostMapping(value = "/job/displayUserLatestJobTableLevelLineage", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<SqlflowJSON> displayUserLatestJobTableLevelLineageV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        String uid = v1UserId(request);
        LatestJobGraphEnvelope latest = jobCenterService.displayUserLatestJobGraph(uid, params.get("jobId"));
        if (latest.getJobId() != null) {
            params.put("jobId", latest.getJobId());
        }
        return wrapApi(lineageService.generateGraph(uid, params, null, true, false));
    }

    /** 59. POST /v1/sqlflow/job/graph/summary */
    @PostMapping(value = "/job/graph/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<SummaryInfo> jobGraphSummaryV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        return wrapApi(lineageService.generateSummary(v1UserId(request), params, null));
    }

    /** 60. POST /v1/sqlflow/job/getJobFiles */
    @PostMapping(value = "/job/getJobFiles", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<Map<String, Object>> getJobFilesV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        return R.data(jobCenterService.getJobFiles(jobId, params.get("fileName")));
    }

    @PostMapping(value = "/job/generate/parquet", produces = MediaType.APPLICATION_JSON_VALUE)
    public R<Map<String, Object>> generateParquetV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        Path file = jobCenterService.generateParquet(jobId, params.get("jobName"), params.get("batchId"));
        return R.data(Map.of(
                "jobId", jobId,
                "fileName", file.getFileName().toString()));
    }

    @RequestMapping(
            value = "/job/download/parquet",
            method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<byte[]> downloadParquetV1(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> body) {
        Map<String, String> params = paramHelper.extractParams(request, body);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        String jobName = params.get("jobName");
        Path file = jobCenterService.resolveOrGenerateParquetForDownload(jobId, jobName, params.get("batchId"));
        String downloadName = (jobName != null && !jobName.isBlank() ? jobName : jobId) + ".parquet";
        return DownloadHelper.fileAttachment(file, downloadName, MediaType.APPLICATION_OCTET_STREAM);
    }

    private static MultipartFile[] resolveFiles(MultipartFile[] primary, MultipartFile single) {
        if (primary != null && primary.length > 0) {
            return primary;
        }
        if (single != null && !single.isEmpty()) {
            return new MultipartFile[] {single};
        }
        return null;
    }
}
