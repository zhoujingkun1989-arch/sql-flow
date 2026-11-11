package io.sqlflow.server.controller;

import io.sqlflow.contracts.domain.R;
import io.sqlflow.contracts.domain.UserInfo;
import io.sqlflow.contracts.domain.VersionEnvelope;
import io.sqlflow.contracts.graph.SqlflowJSON;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.spi.DemoAstServiceSpi;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.server.service.LineageGenerationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
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
 * Unauthenticated Public Anonymous API ({@code /api/anonymous/**} and {@code /api/sqlflow/anonymous/**})
 * guarded by {@code AnonymousRateLimitInterceptor} ({@code 50 req/UTC-day/IP} -> HTTP {@code 429},
 * {@code OBL-024}, Wire Invariant #1e).
 */
@RestController
public class AnonymousApiController {

    private final LineageGenerationService lineageService;
    private final DemoAstServiceSpi demoAstServiceSpi;
    private final SystemAndWebController systemController;
    private final ControllerParamHelper paramHelper;

    public AnonymousApiController(
            LineageGenerationService lineageService,
            DemoAstServiceSpi demoAstServiceSpi,
            SystemAndWebController systemController,
            ControllerParamHelper paramHelper) {
        this.lineageService = lineageService;
        this.demoAstServiceSpi = demoAstServiceSpi;
        this.systemController = systemController;
        this.paramHelper = paramHelper;
    }

    @PostMapping(
            value = {"/api/anonymous/lineage", "/api/sqlflow/anonymous/lineage"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<R<DataFlow>> anonymousLineage(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String sql = lineageService.extractSqlFromRequest(params, sqlfiles);
        if (sql == null || sql.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(R.failWithError(400, "sqltext is required", null));
        }
        String[] sid = new String[1];
        DataFlow df = lineageService.resolveOrAnalyzeDataFlow(UserInfo.GUEST_USER_ID, params, sqlfiles, sid);
        return ResponseEntity.ok(R.data(df));
    }

    @PostMapping(
            value = {
                "/api/anonymous/sqlformatter/format",
                "/api/sqlflow/anonymous/sqlformatter/format"
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<R<Object>> anonymousSqlFormat(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String sql = LineageGenerationService.firstNonBlank(params, "input_sql", "sqltext", "sql");
        if (sql == null || sql.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(R.failWithError(400, "input_sql is required", null));
        }
        String vendorStr = LineageGenerationService.firstNonBlank(params, "dbvendor", "dbVendor");
        if (vendorStr == null && jsonBody != null && jsonBody.get("format_option") instanceof Map<?, ?> fo) {
            Object dv = fo.get("dbVendor") != null ? fo.get("dbVendor") : fo.get("db_vendor");
            if (dv != null) {
                vendorStr = String.valueOf(dv);
            }
        }
        EDbVendor vendor = lineageService.resolveVendor(vendorStr);
        DemoAstServiceSpi.FormatRequest fmtReq =
                new DemoAstServiceSpi.FormatRequest(sql, vendor, new DemoAstServiceSpi.GFmtOpt());
        return ResponseEntity.ok(R.data(demoAstServiceSpi.sqlformat(fmtReq)));
    }

    @PostMapping(
            value = {"/api/anonymous/sqlflow/graph", "/api/sqlflow/anonymous/sqlflow/graph"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<R<SqlflowJSON>> anonymousGraph(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String sql = lineageService.extractSqlFromRequest(params, sqlfiles);
        if (sql == null || sql.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(R.failWithError(400, "sqltext is required", null));
        }
        SqlflowJSON graph = lineageService.generateGraph(UserInfo.GUEST_USER_ID, params, sqlfiles, false, false).getData();
        return ResponseEntity.ok(R.data(graph));
    }

    @RequestMapping(
            value = {"/api/anonymous/version", "/api/sqlflow/anonymous/version"},
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public VersionEnvelope anonymousVersion() {
        return systemController.getVersion();
    }
}
