package io.sqlflow.server.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.contracts.domain.ApiResponse;
import io.sqlflow.contracts.domain.JobDetail;
import io.sqlflow.contracts.domain.LatestJobGraphEnvelope;
import io.sqlflow.contracts.domain.UserJobListSummary;
import io.sqlflow.contracts.graph.DbObjectPosition;
import io.sqlflow.contracts.graph.LeftMostSourceTable;
import io.sqlflow.contracts.graph.SqlflowJSON;
import io.sqlflow.server.security.RequestAuthResolver;
import io.sqlflow.server.service.JobCenterService;
import io.sqlflow.server.service.LineageGenerationService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Job Center ({@code /sqlflow/job/**}), Persistent Analytical Store ({@code /sqlflow/persist/**}),
 * Live Database Connector ({@code /database/**}), {@code 5 MiB} SQL File Splitter
 * ({@code /sqlflow/file/split}), and Non-JDBC File Extractor ({@code /sqlflow/fileextractor/**})
 * controller ({@code 01 §3.4}, {@code 02 §4.2–§4.4}, Wire Invariants #7 & #10).
 */
@RestController
public class SqlflowJobAndPersistController {

    private final JobCenterService jobCenterService;
    private final LineageGenerationService lineageService;
    private final RequestAuthResolver authResolver;
    private final ControllerParamHelper paramHelper;
    private final ObjectMapper objectMapper;

    public SqlflowJobAndPersistController(
            JobCenterService jobCenterService,
            LineageGenerationService lineageService,
            RequestAuthResolver authResolver,
            ControllerParamHelper paramHelper,
            ObjectMapper objectMapper) {
        this.jobCenterService = jobCenterService;
        this.lineageService = lineageService;
        this.authResolver = authResolver;
        this.paramHelper = paramHelper;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // 1. Live Database Connection & Metadata Harvest (`02 §4.3`)
    // -------------------------------------------------------------------------

    @PostMapping(
            value = {"/database/testConnection", "/sqlflow/job/testConnection", "/sqlflow/database/testConnection"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> testConnection(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return jobCenterService.testConnection(params);
    }

    @PostMapping(
            value = {"/database/exportMetadata", "/sqlflow/database/exportMetadata"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> exportLiveMetadata(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return jobCenterService.exportDatabaseMetadataLive(params);
    }

    // -------------------------------------------------------------------------
    // 2. 5 MiB SQL Splitter & Non-JDBC File Extractor (`OBL-071`, `OBL-073`)
    // -------------------------------------------------------------------------

    @PostMapping(
            value = {"/sqlflow/file/split", "/file/split"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> splitSqlFile(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @OptionalJsonBody Map<String, Object> jsonBody) throws IOException {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        StringBuilder sqlBuilder = new StringBuilder();
        MultipartFile[] files = resolveFiles(sqlfiles, sqlfile, file);
        if (files != null) {
            for (MultipartFile mf : files) {
                if (mf != null && !mf.isEmpty()) {
                    sqlBuilder.append(new String(mf.getBytes(), StandardCharsets.UTF_8)).append("\n");
                }
            }
        }
        String inline = LineageGenerationService.firstNonBlank(params, "sqltext", "sql", "content");
        if (inline != null && !inline.isBlank()) {
            sqlBuilder.append(inline);
        }
        Integer maxBytes = params.get("maxBytes") != null
                ? LineageGenerationService.parseInt(params.get("maxBytes"), 0)
                : (params.get("splitSizeMb") != null
                        ? LineageGenerationService.parseInt(params.get("splitSizeMb"), 5) * 1024 * 1024
                        : null);
        String vendor = LineageGenerationService.firstNonBlank(params, "dbvendor", "dbVendor", "vendor");
        List<String> chunks = jobCenterService.splitSqlScript(sqlBuilder.toString(), vendor, maxBytes);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chunkCount", chunks.size());
        out.put("chunks", chunks);
        return ApiResponse.ok(out);
    }

    @PostMapping(
            value = {
                "/sqlflow/fileextractor/getFileMetadataOrSQL",
                "/fileextractor/getFileMetadataOrSQL"
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> extractFileMetadataOrSql(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "files", required = false) MultipartFile[] files,
            @RequestParam(value = "file", required = false) MultipartFile singleFile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        MultipartFile[] mergedFiles = sqlfiles != null && sqlfiles.length > 0
                ? sqlfiles
                : resolveFiles(files, singleFile, null);
        return jobCenterService.extractFileMetadataOrSql(params, mergedFiles);
    }

    // -------------------------------------------------------------------------
    // 3. Job Lifecycle & Management (`01 §3.3.7`, `02 §4.3`)
    // -------------------------------------------------------------------------

    @PostMapping(value = "/sqlflow/job/submitUserJob", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<JobDetail> submitUserJob(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        JobDetail job = jobCenterService.submitJob(userId, params, resolveFiles(sqlfiles, sqlfile, null), false);
        return ApiResponse.ok(job);
    }

    @PostMapping(value = "/sqlflow/job/submitPersistJob", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<JobDetail> submitPersistJob(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        JobDetail job = jobCenterService.submitJob(userId, params, resolveFiles(sqlfiles, sqlfile, null), true);
        return ApiResponse.ok(job);
    }

    @RequestMapping(
            value = {"/sqlflow/job/queryJobStatus", "/sqlflow/job/displayUserJobStatus"},
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> queryJobStatus(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        return ApiResponse.ok(jobCenterService.queryJobStatus(jobId));
    }

    @RequestMapping(
            value = {
                "/sqlflow/job/queryJobDetail",
                "/sqlflow/job/displayUserJobDetail",
                "/sqlflow/job/displayUserJobSummary",
                "/sqlflow/job/detail"
            },
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<JobDetail> queryJobDetail(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id", "jobName");
        return ApiResponse.ok(jobCenterService.getJobDetail(jobId));
    }

    @RequestMapping(
            value = {"/sqlflow/job/displayUserJobsSummary", "/sqlflow/job/list"},
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<UserJobListSummary> displayUserJobsSummary(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        int pageNo = LineageGenerationService.parseInt(
                LineageGenerationService.firstNonBlank(params, "pageNo", "current", "page"), 1);
        int pageSize = LineageGenerationService.parseInt(
                LineageGenerationService.firstNonBlank(params, "pageSize", "size", "limit"), 100);
        String keyword = LineageGenerationService.firstNonBlank(params, "keyword", "jobName", "search");
        String status = params.get("status");
        return ApiResponse.ok(jobCenterService.listJobsSummary(userId, false, pageNo, pageSize, keyword, status));
    }

    @RequestMapping(
            value = {"/sqlflow/job/displayAllJobsSummary", "/sqlflow/job/displayGroupJobsSummary"},
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<UserJobListSummary> displayAllJobsSummary(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        int pageNo = LineageGenerationService.parseInt(
                LineageGenerationService.firstNonBlank(params, "pageNo", "current", "page"), 1);
        int pageSize = LineageGenerationService.parseInt(
                LineageGenerationService.firstNonBlank(params, "pageSize", "size", "limit"), 100);
        return ApiResponse.ok(jobCenterService.listJobsSummary(
                null, true, pageNo, pageSize, params.get("keyword"), params.get("status")));
    }

    /**
     * Wire Invariant #10: {@code POST /sqlflow/job/displayUserLatestJobGraph} and
     * {@code /sqlflow/job/latestJob/sqlflow/graph} return top-level {@code job}, {@code jobId},
     * and {@code sessionId}.
     */
    @RequestMapping(
            value = {
                "/sqlflow/job/displayUserLatestJobGraph",
                "/sqlflow/job/latestJob/sqlflow/graph"
            },
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public LatestJobGraphEnvelope displayUserLatestJobGraph(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return jobCenterService.displayUserLatestJobGraph(userId, params.get("jobId"));
    }

    @PostMapping(value = "/sqlflow/job/updateUserJobGraphCache", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> updateUserJobGraphCache(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        return jobCenterService.updateUserJobGraphCache(jobId);
    }

    @RequestMapping(
            value = {"/sqlflow/job/getJobFiles", "/sqlflow/job/displayUserJobFiles"},
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> getJobFiles(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        String fileName = LineageGenerationService.firstNonBlank(params, "fileName", "file");
        return ApiResponse.ok(jobCenterService.getJobFiles(jobId, fileName));
    }

    @RequestMapping(
            value = {"/sqlflow/job/deleteUserJob", "/sqlflow/job/deleteJob"},
            method = {RequestMethod.POST, RequestMethod.DELETE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Boolean> deleteUserJob(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        return ApiResponse.ok(jobCenterService.deleteJob(jobId));
    }

    @PostMapping(
            value = {"/sqlflow/job/renameJob", "/sqlflow/job/updateJobName"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<JobDetail> renameJob(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        String jobName = LineageGenerationService.firstNonBlank(params, "jobName", "newJobName", "name");
        return ApiResponse.ok(jobCenterService.renameJob(jobId, jobName));
    }

    @PostMapping(
            value = {"/sqlflow/job/topJob", "/sqlflow/job/setJobOnTop"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<JobDetail> topJob(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        boolean onTop = LineageGenerationService.parseBool(
                LineageGenerationService.firstNonBlank(params, "onTop", "topLevel"), true);
        return ApiResponse.ok(jobCenterService.setJobOnTop(jobId, onTop));
    }

    @PostMapping(
            value = {"/sqlflow/job/resubmitJob", "/sqlflow/job/rerunJob"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<JobDetail> resubmitJob(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        return ApiResponse.ok(jobCenterService.resubmitJob(jobId));
    }

    @PostMapping(
            value = {"/sqlflow/job/scheduleJob", "/sqlflow/job/rescheduleJob", "/sqlflow/job/updateScheduleJob"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<JobDetail> scheduleJob(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        String cron = LineageGenerationService.firstNonBlank(params, "cronExpression", "cron");
        return ApiResponse.ok(jobCenterService.scheduleJob(jobId, cron));
    }

    @PostMapping(
            value = {"/sqlflow/job/cancelScheduleJob", "/sqlflow/job/unscheduleJob", "/sqlflow/job/pauseScheduleJob"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<JobDetail> cancelScheduleJob(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        return ApiResponse.ok(jobCenterService.cancelScheduleJob(jobId));
    }

    @PostMapping(value = "/sqlflow/job/resumeScheduleJob", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<JobDetail> resumeScheduleJob(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        JobDetail detail = jobCenterService.getJobDetail(jobId);
        String cron = detail != null && detail.getCronExpression() != null && !detail.getCronExpression().isBlank()
                ? detail.getCronExpression()
                : "0 0 * * * *";
        return ApiResponse.ok(jobCenterService.scheduleJob(jobId, cron));
    }

    @RequestMapping(
            value = "/sqlflow/job/listScheduleJobs",
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<JobDetail>> listScheduleJobs(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        UserJobListSummary all = jobCenterService.listJobsSummary(null, true, 1, 500, null, null);
        List<JobDetail> scheduled = new ArrayList<>();
        if (all != null && all.getJobDetails() != null) {
            for (JobDetail jd : all.getJobDetails()) {
                if (Boolean.TRUE.equals(jd.getSchedulable())) {
                    scheduled.add(jd);
                }
            }
        }
        return ApiResponse.ok(scheduled);
    }

    // -------------------------------------------------------------------------
    // 4. Job-Scoped Graph & Slicing Routes (`/sqlflow/job/sqlflow/*`)
    // -------------------------------------------------------------------------

    @PostMapping(
            value = {
                "/sqlflow/job/sqlflow/graph",
                "/sqlflow/job/sqlflow/selectedgraph",
                "/sqlflow/job/displayUserJobGraph",
                "/sqlflow/job/displayProcedureGraph",
                "/sqlflow/job/displayMetadataQueryGraph"
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SqlflowJSON> jobGraph(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return lineageService.generateGraph(userId, params, null, false, false);
    }

    @PostMapping(value = "/sqlflow/job/displayUserLatestJobTableLevelLineage", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SqlflowJSON> displayUserLatestJobTableLevelLineage(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        LatestJobGraphEnvelope latest = jobCenterService.displayUserLatestJobGraph(userId, params.get("jobId"));
        if (latest.getJobId() != null) {
            params.put("jobId", latest.getJobId());
        }
        return lineageService.generateGraph(userId, params, null, true, false);
    }

    @PostMapping(value = "/sqlflow/job/displayUserLatestJobLeftMostTable", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SqlflowJSON> displayUserLatestJobLeftMostTable(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        LatestJobGraphEnvelope latest = jobCenterService.displayUserLatestJobGraph(userId, params.get("jobId"));
        if (latest.getJobId() != null) {
            params.put("jobId", latest.getJobId());
        }
        return lineageService.generateLeftMostSourceTableGraph(userId, params, null);
    }

    @PostMapping(value = "/sqlflow/job/graph/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<io.sqlflow.contracts.graph.SummaryInfo> jobGraphSummary(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return lineageService.generateSummary(userId, params, null);
    }

    @PostMapping(value = "/sqlflow/job/sqlflow/upstreamGraph", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SqlflowJSON> jobUpstreamGraph(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return lineageService.sliceGraph(userId, params, null, true);
    }

    @PostMapping(value = "/sqlflow/job/sqlflow/downstreamGraph", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SqlflowJSON> jobDownstreamGraph(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return lineageService.sliceGraph(userId, params, null, false);
    }

    @PostMapping(value = "/sqlflow/job/sqlflow/erdiagramSelectGraph", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SqlflowJSON> jobErGraph(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return lineageService.generateGraph(userId, params, null, false, true);
    }

    @PostMapping(
            value = {"/sqlflow/job/sqlflow/leftMostSourceTable", "/sqlflow/job/leftMostSourceTable"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<LeftMostSourceTable> jobLeftMost(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return lineageService.generateLeftMostSourceTable(userId, params, null);
    }

    @PostMapping(value = "/sqlflow/job/sqlflow/leftMostSourceTableGraph", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SqlflowJSON> jobLeftMostGraph(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return lineageService.generateLeftMostSourceTableGraph(userId, params, null);
    }

    @PostMapping(value = "/sqlflow/job/sqlflow/listTableColumns", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<Map<String, Object>>> jobListTableColumns(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return lineageService.listTableColumns(userId, params, null);
    }

    @PostMapping(value = "/sqlflow/job/sqlflow/getSelectedDbObjectInfo", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<DbObjectPosition>> jobSelectedDbObjectInfo(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return lineageService.getSelectedDbObjectInfo(userId, params);
    }

    @PostMapping(
            value = {"/sqlflow/job/displayProcedureSource", "/sqlflow/job/displayMetadataQuerySource"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> displayProcedureOrQuerySource(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        String fileName = LineageGenerationService.firstNonBlank(params, "fileName", "name");
        Map<String, Object> files = jobCenterService.getJobFiles(jobId, fileName);
        if (files.get("content") instanceof String content) {
            return ApiResponse.ok(content);
        }
        return ApiResponse.ok(files);
    }

    // -------------------------------------------------------------------------
    // 5. Job Exports (JSON, Metadata, CSV, LeftMost CSV, GraphML, XML, Parquet)
    // -------------------------------------------------------------------------

    @RequestMapping(
            value = {"/sqlflow/job/exportJobFile", "/sqlflow/job/download/subJobFile"},
            method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<byte[]> exportJobFile(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        String fileName = LineageGenerationService.firstNonBlank(params, "fileName", "name");
        Map<String, Object> res = jobCenterService.getJobFiles(jobId, fileName);
        String content = res.get("content") instanceof String s ? s : "";
        String downloadName = fileName != null && !fileName.isBlank() ? fileName : (jobId + ".sql");
        return DownloadHelper.textAttachment(content, downloadName, MediaType.TEXT_PLAIN);
    }

    @RequestMapping(
            value = {"/sqlflow/job/exportLineageAsJson", "/sqlflow/job/exportFullLineageAsJson"},
            method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<byte[]> exportJobJson(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        String json = jobCenterService.exportJobLineageAsJson(jobId);
        return DownloadHelper.textAttachment(json, jobId + "_lineage.json", MediaType.APPLICATION_JSON);
    }

    @RequestMapping(
            value = {"/sqlflow/job/exportDatabaseMetadata", "/sqlflow/job/exportMetadata"},
            method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<byte[]> exportJobMetadata(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        String json = jobCenterService.exportJobDatabaseMetadata(jobId);
        return DownloadHelper.textAttachment(json, jobId + "_metadata.json", MediaType.APPLICATION_JSON);
    }

    @RequestMapping(
            value = {"/sqlflow/job/exportLineageAsCsv", "/sqlflow/job/exportFullLineageAsCsv"},
            method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<byte[]> exportJobCsv(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        boolean showTransform = LineageGenerationService.parseBool(params.get("showTransform"), false);
        char delimiter = params.get("delimiter") != null && !params.get("delimiter").isEmpty()
                ? params.get("delimiter").charAt(0)
                : ',';
        String csv = jobCenterService.exportJobLineageAsCsv(jobId, showTransform, delimiter);
        return DownloadHelper.textAttachment(csv, jobId + "_lineage.csv", MediaType.valueOf("text/csv"));
    }

    @RequestMapping(
            value = "/sqlflow/job/leftMostSourceTableAsCsv",
            method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<byte[]> exportJobLeftMostCsv(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        String csv = jobCenterService.exportLeftMostSourceTableAsCsv(jobId, params.get("tableNamePattern"));
        return DownloadHelper.textAttachment(csv, jobId + "_leftmost.csv", MediaType.valueOf("text/csv"));
    }

    @RequestMapping(
            value = "/sqlflow/job/exportLineageAsGraphml",
            method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<byte[]> exportJobGraphml(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        String gml = jobCenterService.exportJobLineageAsGraphml(jobId);
        return DownloadHelper.textAttachment(gml, jobId + ".graphml", MediaType.APPLICATION_XML);
    }

    @RequestMapping(
            value = "/sqlflow/job/exportLineageAsXml",
            method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<byte[]> exportJobXml(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        String xml = jobCenterService.exportJobLineageAsXml(jobId);
        return DownloadHelper.textAttachment(xml, jobId + "_lineage.xml", MediaType.APPLICATION_XML);
    }

    @PostMapping(value = "/sqlflow/job/generate/parquet", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> generateParquet(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        String jobName = params.get("jobName");
        String batchId = params.get("batchId");
        Path file = jobCenterService.generateParquet(jobId, jobName, batchId);
        return ApiResponse.ok(Map.of(
                "jobId", jobId,
                "batchId", batchId != null ? batchId : "batch_1",
                "fileName", file.getFileName().toString()));
    }

    @RequestMapping(
            value = "/sqlflow/job/download/parquet",
            method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<byte[]> downloadParquet(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String jobId = LineageGenerationService.firstNonBlank(params, "jobId", "id");
        String jobName = params.get("jobName");
        String batchId = params.get("batchId");
        Path file = jobCenterService.resolveOrGenerateParquetForDownload(jobId, jobName, batchId);
        String downloadName = (jobName != null && !jobName.isBlank() ? jobName : jobId) + ".parquet";
        return DownloadHelper.fileAttachment(file, downloadName, MediaType.APPLICATION_OCTET_STREAM);
    }

    // -------------------------------------------------------------------------
    // 6. PersistSqlflow Analytical Routes (`/sqlflow/persist/**`, `02 §4.4`)
    // -------------------------------------------------------------------------

    @PostMapping(
            value = {
                "/sqlflow/persist/fetchRegularJobTableRelationSqls",
                "/sqlflow/job/fetchRegularJobTableRelationSqls"
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<List<String>>> fetchRegularJobTableRelationSqls(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return ApiResponse.ok(jobCenterService.fetchRegularJobTableRelationSqls(
                params.get("jobId"),
                params.get("sourceTable"),
                params.get("sourceColumn"),
                params.get("targetTable"),
                params.get("targetColumn")));
    }

    @PostMapping(
            value = {
                "/sqlflow/persist/fetchTableRelationSqlBySqlHash",
                "/sqlflow/job/fetchTableRelationSqlBySqlHash"
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<Map<String, Object>>> fetchTableRelationSqlBySqlHash(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return ApiResponse.ok(jobCenterService.fetchTableRelationSqlBySqlHash(
                params.get("jobId"),
                LineageGenerationService.firstNonBlank(params, "sqlHash", "hash")));
    }

    @PostMapping(
            value = {
                "/sqlflow/persist/fetchLeftMostTableLineageSQL",
                "/sqlflow/job/fetchLeftMostTableLineageSQL"
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<Map<String, Object>>> fetchLeftMostTableLineageSQL(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return ApiResponse.ok(jobCenterService.fetchLeftMostTableLineageSQL(
                params.get("jobId"),
                params.get("targetTable"),
                params.get("targetColumn"),
                params.get("sourceTable"),
                params.get("sourceColumn")));
    }

    @PostMapping(
            value = {
                "/sqlflow/persist/submitTableAffectSQLJob",
                "/sqlflow/job/submitTableAffectSQLJob"
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> submitTableAffectSQLJob(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        List<String> sqlList = new ArrayList<>();
        if (params.get("sqltext") != null) {
            sqlList.add(params.get("sqltext"));
        }
        if (params.get("sqls") != null) {
            try {
                sqlList.addAll(objectMapper.readValue(params.get("sqls"), new TypeReference<List<String>>() {}));
            } catch (Exception ignored) {
                sqlList.add(params.get("sqls"));
            }
        }
        boolean columnLevel = LineageGenerationService.parseBool(params.get("columnLevel"), true);
        return ApiResponse.ok(jobCenterService.submitTableAffectSQLJob(
                params.get("jobId"), sqlList, sqlfiles, columnLevel));
    }

    @RequestMapping(
            value = "/sqlflow/persist/status",
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> persistStatus() {
        return ApiResponse.ok(Map.of("regularJobSupported", jobCenterService.isRegularJobSupported()));
    }

    private static MultipartFile[] resolveFiles(
            MultipartFile[] primary, MultipartFile single1, MultipartFile single2) {
        if (primary != null && primary.length > 0) {
            return primary;
        }
        if (single1 != null && !single1.isEmpty()) {
            return new MultipartFile[] {single1};
        }
        if (single2 != null && !single2.isEmpty()) {
            return new MultipartFile[] {single2};
        }
        return null;
    }
}
