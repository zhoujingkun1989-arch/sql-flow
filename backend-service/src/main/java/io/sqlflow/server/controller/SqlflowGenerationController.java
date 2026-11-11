package io.sqlflow.server.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.contracts.domain.ApiResponse;
import io.sqlflow.contracts.domain.ShareInfo;
import io.sqlflow.contracts.graph.DbObjectPosition;
import io.sqlflow.contracts.graph.LeftMostSourceTable;
import io.sqlflow.contracts.graph.SqlflowJSON;
import io.sqlflow.contracts.graph.SummaryInfo;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.SqlInfo;
import io.sqlflow.server.security.RequestAuthResolver;
import io.sqlflow.server.service.LineageGenerationService;
import io.sqlflow.server.service.ShareService;
import io.sqlflow.server.service.UserSchemaService;
import jakarta.servlet.http.HttpServletRequest;
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
 * Interactive SQLFlow Lineage Generation, Graph Visualization, Upstream/Downstream Slicing,
 * ER Diagramming, User DDL Schema Management, Permalink/Share Snapshots, and Interactive Exports
 * ({@code 01 §3.4.13}, {@code 02 §4.1}, Wire Invariants #4, #5, #9, #11, #12, #15, #17, #20).
 */
@RestController
public class SqlflowGenerationController {

    private final LineageGenerationService lineageService;
    private final UserSchemaService userSchemaService;
    private final ShareService shareService;
    private final RequestAuthResolver authResolver;
    private final ControllerParamHelper paramHelper;
    private final ObjectMapper objectMapper;

    public SqlflowGenerationController(
            LineageGenerationService lineageService,
            UserSchemaService userSchemaService,
            ShareService shareService,
            RequestAuthResolver authResolver,
            ControllerParamHelper paramHelper,
            ObjectMapper objectMapper) {
        this.lineageService = lineageService;
        this.userSchemaService = userSchemaService;
        this.shareService = shareService;
        this.authResolver = authResolver;
        this.paramHelper = paramHelper;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // 1. Core Interactive DataFlow, Graph, Table-Level, ER, & Call Lineage
    // -------------------------------------------------------------------------

    @PostMapping(
            value = {"/sqlflow/generation/sqlflow", "/sqlflow/generation/dataflow"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<DataFlow> generateSqlflow(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String[] sidHolder = new String[1];
        DataFlow df = lineageService.resolveOrAnalyzeDataFlow(
                userId, params, resolveFiles(sqlfiles, sqlfile), sidHolder);
        return ApiResponse.okWithSession(df, sidHolder[0]);
    }

    @PostMapping(
            value = {
                "/sqlflow/generation/sqlflow/graph",
                "/sqlflow/generation/sqlflow/selectedgraph"
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SqlflowJSON> generateGraph(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        boolean tableLevel = LineageGenerationService.parseBool(params.get("tableLevel"), false)
                || "false".equalsIgnoreCase(params.get("columnLevel"));
        return lineageService.generateGraph(userId, params, resolveFiles(sqlfiles, sqlfile), tableLevel, false);
    }

    @PostMapping(
            value = {
                "/sqlflow/generation/sqlflow/graph/table_level_lineage",
                "/sqlflow/generation/sqlflow/selectedgraph/table_level_lineage"
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SqlflowJSON> generateTableLevelLineage(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return lineageService.generateGraph(userId, params, resolveFiles(sqlfiles, sqlfile), true, false);
    }

    @PostMapping(
            value = {
                "/sqlflow/generation/sqlflow/erdiagramSelectGraph",
                "/sqlflow/generation/sqlflow/er"
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SqlflowJSON> generateErDiagramGraph(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return lineageService.generateGraph(userId, params, resolveFiles(sqlfiles, sqlfile), false, true);
    }

    @PostMapping(
            value = "/sqlflow/generation/sqlflow/graph/call_lineage",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SqlflowJSON> generateCallLineageGraph(
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
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return lineageService.generateCallLineage(userId, sqlInfos, params, resolveFiles(sqlfiles, sqlfile));
    }

    @PostMapping(
            value = {
                "/sqlflow/generation/sqlflow/graph/summary",
                "/sqlflow/generation/sqlflow/summary"
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SummaryInfo> generateSummary(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return lineageService.generateSummary(userId, params, resolveFiles(sqlfiles, sqlfile));
    }

    // -------------------------------------------------------------------------
    // 2. Upstream/Downstream Slicing & Internal Raw DataFlow (`Wire Invariant #9`)
    // -------------------------------------------------------------------------

    @PostMapping(
            value = {
                "/sqlflow/generation/sqlflow/upstreamGraph",
                "/sqlflow/generation/upstream"
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SqlflowJSON> generateUpstreamGraph(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = normalizeStreamSearchParams(paramHelper.extractParams(request, jsonBody));
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return lineageService.sliceGraph(userId, params, resolveFiles(sqlfiles, sqlfile), true);
    }

    @PostMapping(
            value = {
                "/sqlflow/generation/sqlflow/downstreamGraph",
                "/sqlflow/generation/downstream"
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SqlflowJSON> generateDownstreamGraph(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = normalizeStreamSearchParams(paramHelper.extractParams(request, jsonBody));
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return lineageService.sliceGraph(userId, params, resolveFiles(sqlfiles, sqlfile), false);
    }

    /**
     * Wire Invariant #9: Internal {@code POST /sqlflow/lineage/upstream} returns raw unwrapped
     * {@link DataFlow} ({@code dbobjs}, {@code relationships}), NOT {@code ApiResponse<SqlflowJSON>}.
     */
    @PostMapping(value = "/sqlflow/lineage/upstream", produces = MediaType.APPLICATION_JSON_VALUE)
    public DataFlow internalUpstreamRaw(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = normalizeStreamSearchParams(paramHelper.extractParams(request, jsonBody));
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return lineageService.sliceRawDataFlow(userId, params, resolveFiles(sqlfiles, sqlfile), true);
    }

    /**
     * Wire Invariant #9: Internal {@code POST /sqlflow/lineage/downstream} returns raw unwrapped
     * {@link DataFlow}.
     */
    @PostMapping(value = "/sqlflow/lineage/downstream", produces = MediaType.APPLICATION_JSON_VALUE)
    public DataFlow internalDownstreamRaw(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = normalizeStreamSearchParams(paramHelper.extractParams(request, jsonBody));
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return lineageService.sliceRawDataFlow(userId, params, resolveFiles(sqlfiles, sqlfile), false);
    }

    @PostMapping(
            value = "/sqlflow/generation/sqlflow/leftMostSourceTable",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<LeftMostSourceTable> leftMostSourceTable(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return lineageService.generateLeftMostSourceTable(userId, params, resolveFiles(sqlfiles, sqlfile));
    }

    @PostMapping(
            value = "/sqlflow/generation/sqlflow/leftMostSourceTableGraph",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SqlflowJSON> leftMostSourceTableGraph(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return lineageService.generateLeftMostSourceTableGraph(userId, params, resolveFiles(sqlfiles, sqlfile));
    }

    @PostMapping(
            value = "/sqlflow/generation/sqlflow/listTableColumns",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<Map<String, Object>>> listTableColumns(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return lineageService.listTableColumns(userId, params, resolveFiles(sqlfiles, sqlfile));
    }

    @PostMapping(
            value = "/sqlflow/generation/sqlflow/getSelectedDbObjectInfo",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<DbObjectPosition>> getSelectedDbObjectInfo(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return lineageService.getSelectedDbObjectInfo(userId, params);
    }

    @PostMapping(
            value = "/sqlflow/generation/checkSyntax",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> checkSyntax(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String sql = lineageService.extractSqlFromRequest(params, resolveFiles(sqlfiles, sqlfile));
        params.put("sqltext", sql);
        return lineageService.checkSyntax(params);
    }

    @PostMapping(
            value = "/sqlflow/generation/sql/format",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> formatSql(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String sql = lineageService.extractSqlFromRequest(params, resolveFiles(sqlfiles, sqlfile));
        params.put("sqltext", sql);
        return lineageService.formatSql(params, jsonBody);
    }

    // -------------------------------------------------------------------------
    // 3. User DDL Schema Management (`01 §3.3.6`, Wire Invariants #11 & #12)
    // -------------------------------------------------------------------------

    @PostMapping(
            value = {
                "/sqlflow/generation/submitUserSchema",
                "/sqlflow/generation/uploadUserSchema",
                "/sqlflow/schema/submitUserSchema",
                "/sqlflow/schema/uploadUserSchema"
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<String>> submitUserSchema(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String dbvendor = LineageGenerationService.firstNonBlank(params, "dbvendor", "dbVendor", "vendor");
        String inlineSql = LineageGenerationService.firstNonBlank(params, "sqltext", "sql", "content");
        List<String> saved = userSchemaService.submitUserSchema(
                userId, dbvendor, resolveFiles(sqlfiles, sqlfile), inlineSql);
        return ApiResponse.ok(saved);
    }

    @RequestMapping(
            value = {
                "/sqlflow/generation/displayUserSchemaSummary",
                "/sqlflow/schema/displayUserSchemaSummary"
            },
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, List<List<String>>>> displayUserSchemaSummary(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return ApiResponse.ok(userSchemaService.displayUserSchemaSummary(userId));
    }

    @RequestMapping(
            value = {
                "/sqlflow/generation/displayUserDbvendorSchema",
                "/sqlflow/schema/displayUserDbvendorSchema"
            },
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<String>> displayUserDbvendorSchema(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String dbvendor = LineageGenerationService.firstNonBlank(params, "dbvendor", "dbVendor", "vendor");
        return ApiResponse.ok(userSchemaService.displayUserDbvendorSchema(userId, dbvendor));
    }

    @RequestMapping(
            value = {
                "/sqlflow/generation/displayUserDbvendorSchemaContent",
                "/sqlflow/schema/displayUserDbvendorSchemaContent"
            },
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<String> displayUserDbvendorSchemaContent(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String dbvendor = LineageGenerationService.firstNonBlank(params, "dbvendor", "dbVendor", "vendor");
        String fileName = LineageGenerationService.firstNonBlank(params, "fileName", "file");
        return ApiResponse.ok(userSchemaService.displayUserDbvendorSchemaContent(userId, dbvendor, fileName));
    }

    @RequestMapping(
            value = {
                "/sqlflow/generation/deleteUserSchema",
                "/sqlflow/schema/deleteUserSchema"
            },
            method = {RequestMethod.POST, RequestMethod.DELETE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Boolean> deleteUserSchema(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String dbvendor = LineageGenerationService.firstNonBlank(params, "dbvendor", "dbVendor", "vendor");
        String fileName = LineageGenerationService.firstNonBlank(params, "fileName", "file");
        return ApiResponse.ok(userSchemaService.deleteUserSchema(userId, dbvendor, fileName));
    }

    // -------------------------------------------------------------------------
    // 4. Share Snapshots & Permalinks (`01 §3.3.5`, `OBL-025`)
    // -------------------------------------------------------------------------

    @GetMapping(
            value = {
                "/sqlflow/generation/sqlflow/graph/share/{shareId}",
                "/sqlflow/share/{shareId}"
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SqlflowJSON> getSharedGraph(@PathVariable("shareId") String shareId) {
        return ApiResponse.ok(shareService.getSharedGraph(shareId));
    }

    @PostMapping(
            value = {"/sqlflow/share/create", "/sqlflow/generation/share"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ShareInfo> createShare(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        params.put("share", "true");
        ApiResponse<SqlflowJSON> graphRes =
                lineageService.generateGraph(userId, params, resolveFiles(sqlfiles, sqlfile), false, false);
        SqlflowJSON g = graphRes.getData();
        List<ShareInfo> list = shareService.listUserShares(userId);
        ShareInfo match = list.stream()
                .filter(s -> g != null && s.getShareId().equals(g.getShareId()))
                .findFirst()
                .orElseGet(() -> new ShareInfo(g != null ? g.getShareId() : null, null, false, userId));
        return ApiResponse.ok(match);
    }

    @RequestMapping(
            value = "/permalink/checkExistence",
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> checkPermalinkExistence(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String id = LineageGenerationService.firstNonBlank(params, "id", "shareId", "permalinkId");
        boolean exists = shareService.checkPermalinkExistence(id);
        return ApiResponse.ok(Map.of("exists", exists, "id", id != null ? id : ""));
    }

    @PostMapping(
            value = {"/permalink/createPermalink", "/permalink/create"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> createPermalink(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String id = LineageGenerationService.firstNonBlank(params, "id", "shareId", "permalinkId");
        Object payload = jsonBody != null && jsonBody.containsKey("data") ? jsonBody.get("data") : jsonBody;
        return ApiResponse.ok(shareService.createPermalink(id, payload, userId));
    }

    @RequestMapping(
            value = "/permalink/getPermalinkData",
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> getPermalinkData(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String id = LineageGenerationService.firstNonBlank(params, "id", "shareId", "permalinkId");
        return ApiResponse.ok(shareService.getPermalinkData(id));
    }

    @GetMapping(
            value = "/permalink/{id:^(?!checkExistence$|createPermalink$|create$|getPermalinkData$).+}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> getPermalinkByPath(@PathVariable("id") String id) {
        return ApiResponse.ok(shareService.getPermalinkData(id));
    }

    // -------------------------------------------------------------------------
    // 5. Interactive Exports (JSON, XML, CSV, metadata_lineage ZIP, GraphML, PNG)
    // -------------------------------------------------------------------------

    @PostMapping(value = {
        "/sqlflow/generation/sqlflow/exportLineageAsJson",
        "/sqlflow/generation/sqlflow/exportFullLineageAsJson"
    })
    public ResponseEntity<byte[]> exportLineageAsJson(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String json = lineageService.exportAsJson(userId, params, resolveFiles(sqlfiles, sqlfile));
        return DownloadHelper.textAttachment(json, "lineage.json", MediaType.APPLICATION_JSON);
    }

    @PostMapping(value = "/sqlflow/generation/sqlflow/exportDataflowMetadata")
    public ResponseEntity<byte[]> exportDataflowMetadata(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String json = lineageService.exportMetadataJson(userId, params, resolveFiles(sqlfiles, sqlfile));
        return DownloadHelper.textAttachment(json, "metadata.json", MediaType.APPLICATION_JSON);
    }

    @PostMapping(value = "/sqlflow/generation/sqlflow/exportLineageAsXml")
    public ResponseEntity<byte[]> exportLineageAsXml(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String xml = lineageService.exportAsXml(userId, params, resolveFiles(sqlfiles, sqlfile));
        return DownloadHelper.textAttachment(xml, "lineage.xml", MediaType.APPLICATION_XML);
    }

    @PostMapping(value = {
        "/sqlflow/generation/sqlflow/exportLineageAsCsv",
        "/sqlflow/generation/sqlflow/exportFullLineageAsCsv",
        "/sqlflow/generation/sqlflow/selected_dbobject/exportLineageAsCsv"
    })
    public ResponseEntity<byte[]> exportLineageAsCsv(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        boolean pascalStyle = LineageGenerationService.parseBool(params.get("pascalHeader"), false);
        String csv = lineageService.exportAsCsv(userId, params, resolveFiles(sqlfiles, sqlfile), pascalStyle);
        return DownloadHelper.textAttachment(csv, "lineage.csv", MediaType.valueOf("text/csv"));
    }

    @PostMapping(value = {
        "/sqlflow/generation/sqlflow/download/metadata_lineage",
        "/sqlflow/generation/sqlflow/selected_dbobject/download/metadata_lineage"
    })
    public ResponseEntity<byte[]> downloadMetadataLineageZip(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        byte[] zipBytes = lineageService.exportMetadataLineageZip(
                userId, params, resolveFiles(sqlfiles, sqlfile));
        return DownloadHelper.bytesAttachment(
                zipBytes, "metadata_lineage.zip", MediaType.APPLICATION_OCTET_STREAM);
    }

    @PostMapping(value = "/sqlflow/generation/sqlflow/exportLineageAsGraphml")
    public ResponseEntity<byte[]> exportLineageAsGraphml(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String gml = lineageService.exportAsGraphML(userId, params, resolveFiles(sqlfiles, sqlfile));
        return DownloadHelper.textAttachment(gml, "lineage.graphml", MediaType.APPLICATION_XML);
    }

    @PostMapping(
            value = {
                "/sqlflow/generation/sqlflow/graph/image",
                "/sqlflow/generation/sqlflow/selectedgraph/image",
                "/sqlflow/generation/sqlflow/image",
                "/sqlflow/generation/sqlflow/exportLineageAsPng"
            },
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> renderGraphImage(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        byte[] png = lineageService.renderPngImage(userId, params, resolveFiles(sqlfiles, sqlfile), "graph");
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
    }

    @PostMapping(
            value = "/sqlflow/generation/sqlflow/upstreamGraph/image",
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> renderUpstreamGraphImage(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = normalizeStreamSearchParams(paramHelper.extractParams(request, jsonBody));
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        byte[] png = lineageService.renderPngImage(userId, params, resolveFiles(sqlfiles, sqlfile), "upstream");
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
    }

    @PostMapping(
            value = "/sqlflow/generation/sqlflow/downstreamGraph/image",
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> renderDownstreamGraphImage(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = normalizeStreamSearchParams(paramHelper.extractParams(request, jsonBody));
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        byte[] png = lineageService.renderPngImage(userId, params, resolveFiles(sqlfiles, sqlfile), "downstream");
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
    }

    @PostMapping(
            value = "/sqlflow/generation/sqlflow/erdiagramSelectGraph/image",
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> renderErGraphImage(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        byte[] png = lineageService.renderPngImage(userId, params, resolveFiles(sqlfiles, sqlfile), "er");
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
    }

    @PostMapping(
            value = "/sqlflow/generation/sqlflow/leftMostSourceTableGraph/image",
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> renderLeftMostGraphImage(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        byte[] png = lineageService.renderPngImage(userId, params, resolveFiles(sqlfiles, sqlfile), "leftmost");
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
    }

    @PostMapping(value = "/sqlflow/generation/sqlflow/session/lineage")
    public ResponseEntity<byte[]> sessionLineageStream(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String format = params.getOrDefault("format", "json");
        if ("csv".equalsIgnoreCase(format)) {
            String csv = lineageService.exportAsCsv(userId, params, resolveFiles(sqlfiles, sqlfile), false);
            return DownloadHelper.textAttachment(csv, "lineage.csv", MediaType.valueOf("text/csv"));
        }
        String json = lineageService.exportAsJson(userId, params, resolveFiles(sqlfiles, sqlfile));
        return DownloadHelper.textAttachment(json, "lineage.json", MediaType.APPLICATION_JSON);
    }

    private static Map<String, String> normalizeStreamSearchParams(Map<String, String> params) {
        Map<String, String> merged = new LinkedHashMap<>(params);
        if (!merged.containsKey("userId") && merged.containsKey("userid")) {
            merged.put("userId", merged.get("userid"));
        }
        if (!merged.containsKey("jobId") && merged.containsKey("jobid")) {
            merged.put("jobId", merged.get("jobid"));
        }
        if (!merged.containsKey("table") && merged.containsKey("tablename")) {
            merged.put("table", merged.get("tablename"));
        }
        if (!merged.containsKey("column") && merged.containsKey("columnname")) {
            merged.put("column", merged.get("columnname"));
        }
        return merged;
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
