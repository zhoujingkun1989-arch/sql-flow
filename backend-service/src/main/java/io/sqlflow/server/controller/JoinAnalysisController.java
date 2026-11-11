package io.sqlflow.server.controller;

import io.sqlflow.contracts.graph.JoinAnalysisGraph;
import io.sqlflow.contracts.spi.JoinAnalysisSpi;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.server.config.ExecutionTimeoutGuard;
import io.sqlflow.server.config.SqlflowRuntimeConfig;
import io.sqlflow.server.service.LineageGenerationService;
import io.sqlflow.server.service.UserSchemaService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dedicated Join Analysis endpoint ({@code POST /sqlflow/generation/sqlflow/join-analysis})
 * enforcing Wire Invariants #1d and #8 ({@code 00 §3.3 #1, #8}, {@code 02 §4.2.1}):
 * <ul>
 *   <li>HTTP {@code 403} when {@code disable_visualize_join=true}</li>
 *   <li>HTTP {@code 400} when {@code sqltext} is missing/blank or {@code dbvendor} is unknown</li>
 *   <li>HTTP {@code 200} returning unwrapped {@link JoinAnalysisGraph} ({@code schemaVersion: "join-analysis.v1"})</li>
 * </ul>
 */
@RestController
public class JoinAnalysisController {

    private final SqlflowRuntimeConfig config;
    private final ExecutionTimeoutGuard timeoutGuard;
    private final JoinAnalysisSpi joinAnalysisSpi;
    private final UserSchemaService userSchemaService;
    private final ControllerParamHelper paramHelper;

    public JoinAnalysisController(
            SqlflowRuntimeConfig config,
            ExecutionTimeoutGuard timeoutGuard,
            JoinAnalysisSpi joinAnalysisSpi,
            UserSchemaService userSchemaService,
            ControllerParamHelper paramHelper) {
        this.config = config;
        this.timeoutGuard = timeoutGuard;
        this.joinAnalysisSpi = joinAnalysisSpi;
        this.userSchemaService = userSchemaService;
        this.paramHelper = paramHelper;
    }

    @PostMapping(
            value = {"/sqlflow/generation/sqlflow/join-analysis", "/sqlflow/join-analysis"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> analyzeJoins(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        if (config.isDisableVisualizeJoin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(errorBody(403, "This service is closed."));
        }

        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String sqlText = LineageGenerationService.firstNonBlank(params, "sqltext", "sql", "sqlText");
        if (sqlText == null || sqlText.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(errorBody(400, "sqltext is required"));
        }

        String rawVendor = LineageGenerationService.firstNonBlank(params, "dbvendor", "dbVendor", "vendor");
        EDbVendor vendor = EDbVendor.dbvoracle;
        if (rawVendor != null && !rawVendor.isBlank()) {
            Optional<EDbVendor> parsed = EDbVendor.find(rawVendor.trim());
            if (parsed.isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(errorBody(400, "Unsupported dbvendor: " + rawVendor));
            }
            vendor = parsed.get();
        }

        String userId = params.get("userId");
        String userDdl = userSchemaService.loadCombinedUserSchemaDdl(userId, vendor.wireName());
        String effectiveSql = (userDdl != null && !userDdl.isBlank())
                ? (userDdl + "\n" + config.preprocessSql(sqlText))
                : config.preprocessSql(sqlText);

        final EDbVendor finalVendor = vendor;
        try {
            JoinAnalysisGraph result = timeoutGuard.executeWithTimeout(
                    "join-analysis",
                    config.getDataflowGraphTimeoutMs(),
                    () -> {
                        JoinAnalysisSpi.JoinAnalysisRequest req = JoinAnalysisSpi.JoinAnalysisRequest.of(effectiveSql, finalVendor);
                        req.setUserId(userId);
                        req.setJobId(params.get("jobId"));
                        req.setDefaultServer(params.get("defaultServer"));
                        req.setDefaultDatabase(params.get("defaultDatabase"));
                        req.setDefaultSchema(params.get("defaultSchema"));
                        String dirRaw = LineageGenerationService.firstNonBlank(params, "layoutDirection", "direction");
                        if (dirRaw != null && "DOWN".equalsIgnoreCase(dirRaw.trim())) {
                            req.setLayoutDirection(JoinAnalysisGraph.LayoutDirection.DOWN);
                        }
                        return joinAnalysisSpi.analyzeJoins(req);
                    });
            if (result == null) {
                result = new JoinAnalysisGraph();
            }
            if (result.getSchemaVersion() == null || result.getSchemaVersion().isBlank()) {
                result.setSchemaVersion("join-analysis.v1");
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorBody(500, "Join analysis failed: " + e.getMessage()));
        }
    }

    private static Map<String, Object> errorBody(int code, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("error", error);
        return m;
    }
}
