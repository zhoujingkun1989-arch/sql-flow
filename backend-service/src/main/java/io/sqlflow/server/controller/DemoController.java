package io.sqlflow.server.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.contracts.domain.ApiResponse;
import io.sqlflow.contracts.lineage.Option;
import io.sqlflow.contracts.spi.DemoAstServiceSpi;
import io.sqlflow.contracts.spi.LineageEngineSpi;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.server.config.SqlflowRuntimeConfig;
import io.sqlflow.server.persistence.SqliteRepository;
import io.sqlflow.server.security.RequestAuthResolver;
import io.sqlflow.server.service.LineageGenerationService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Complete {@code /demo/**}, {@code /sqlflow/demo/**}, {@code /liveDemo}, and {@code /syntax/check}
 * SQL AST & Formatting utility controller ({@code 01 §3.4}, {@code 02 §4.6 & §4.9}, {@code OBL-089}).
 */
@RestController
public class DemoController {

    private final DemoAstServiceSpi demoAstServiceSpi;
    private final LineageEngineSpi lineageEngineSpi;
    private final SqlflowRuntimeConfig config;
    private final SqliteRepository sqliteRepository;
    private final RequestAuthResolver authResolver;
    private final LineageGenerationService lineageService;
    private final ControllerParamHelper paramHelper;
    private final ObjectMapper objectMapper;

    public DemoController(
            DemoAstServiceSpi demoAstServiceSpi,
            LineageEngineSpi lineageEngineSpi,
            SqlflowRuntimeConfig config,
            SqliteRepository sqliteRepository,
            RequestAuthResolver authResolver,
            LineageGenerationService lineageService,
            ControllerParamHelper paramHelper,
            ObjectMapper objectMapper) {
        this.demoAstServiceSpi = demoAstServiceSpi;
        this.lineageEngineSpi = lineageEngineSpi;
        this.config = config;
        this.sqliteRepository = sqliteRepository;
        this.authResolver = authResolver;
        this.lineageService = lineageService;
        this.paramHelper = paramHelper;
        this.objectMapper = objectMapper;
    }

    @PostMapping(
            value = {
                "/demo/sqlformat",
                "/sqlflow/demo/sqlformat",
                "/demo/format",
                "/demo/sqlformatter/format",
                "/sqlflow/demo/sqlformatter/format"
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> sqlformat(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        EDbVendor vendor = resolveVendor(params, jsonBody);
        auditDemo("sqlformat", vendor, resolveSql(params, null));
        return lineageService.formatSql(params, jsonBody);
    }

    @PostMapping(
            value = {
                "/syntax/check",
                "/demo/syntax/check",
                "/demo/syntaxCheck",
                "/sqlflow/demo/syntaxCheck",
                "/sqlflow/demo/syntax/check"
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> syntaxCheck(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String sql = resolveSql(params, null);
        sqliteRepository.checkApiQuota(userId, "/demo/syntax/check", sql.getBytes(StandardCharsets.UTF_8).length);
        EDbVendor vendor = resolveVendor(params, jsonBody);
        auditDemo("syntaxCheck", vendor, sql);
        DemoAstServiceSpi.SyntaxCheckResult res =
                demoAstServiceSpi.syntaxCheck(new DemoAstServiceSpi.AstSqlRequest(sql, vendor));
        return LineageGenerationService.toSyntaxApiResponse(res);
    }

    @PostMapping(
            value = {
                "/demo/gettablecolumns",
                "/sqlflow/demo/gettablecolumns",
                "/demo/getTableColumns",
                "/demo/tableColumns/getTableColumns",
                "/sqlflow/demo/tableColumns/getTableColumns"
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> getTableColumns(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String sql = resolveSql(params, mergeFiles(sqlfiles, sqlfile));
        EDbVendor vendor = resolveVendor(params, jsonBody);
        auditDemo("gettablecolumns", vendor, sql);
        return executeGetTableColumns(sql, vendor, params);
    }

    @PostMapping(
            value = {"/demo/tableColumn", "/sqlflow/demo/tableColumn"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> tableColumn(
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String sql = resolveSql(params, mergeFiles(sqlfiles, sqlfile));
        EDbVendor vendor = resolveVendor(params, jsonBody);
        auditDemo("tableColumn", vendor, sql);
        return executeGetTableColumns(sql, vendor, params);
    }

    @PostMapping(
            value = {"/demo/tokenize", "/sqlflow/demo/tokenize"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> tokenize(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String sql = resolveSql(params, null);
        EDbVendor vendor = resolveVendor(params, jsonBody);
        auditDemo("tokenize", vendor, sql);
        return ApiResponse.ok(demoAstServiceSpi.tokenize(new DemoAstServiceSpi.AstSqlRequest(sql, vendor)));
    }

    @PostMapping(
            value = {
                "/demo/antiSQLInjection",
                "/demo/antiSqlInjection",
                "/demo/sqlInjectionDetector",
                "/sqlflow/demo/antiSQLInjection"
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> antiSqlInjection(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String sql = resolveSql(params, null);
        EDbVendor vendor = resolveVendor(params, jsonBody);
        auditDemo("antiSQLInjection", vendor, sql);
        return ApiResponse.ok(demoAstServiceSpi.antiSqlInjection(new DemoAstServiceSpi.AstSqlRequest(sql, vendor)));
    }

    @PostMapping(
            value = {"/demo/callRelation", "/sqlflow/demo/callRelation"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> callRelation(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String sql = resolveSql(params, null);
        EDbVendor vendor = resolveVendor(params, jsonBody);
        auditDemo("callRelation", vendor, sql);
        return ApiResponse.ok(demoAstServiceSpi.callRelation(new DemoAstServiceSpi.AstSqlRequest(sql, vendor)));
    }

    @PostMapping(
            value = {"/demo/columnImpact", "/sqlflow/demo/columnImpact"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> columnImpact(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String sql = resolveSql(params, null);
        EDbVendor vendor = resolveVendor(params, jsonBody);
        auditDemo("columnImpact", vendor, sql);
        return executeColumnImpact(sql, vendor, params);
    }

    @PostMapping(
            value = {"/demo/findConstants", "/sqlflow/demo/findConstants"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> findConstants(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String sql = resolveSql(params, null);
        EDbVendor vendor = resolveVendor(params, jsonBody);
        auditDemo("findConstants", vendor, sql);
        return ApiResponse.ok(demoAstServiceSpi.findConstants(new DemoAstServiceSpi.AstSqlRequest(sql, vendor)));
    }

    @PostMapping(
            value = {"/demo/joinConvert", "/sqlflow/demo/joinConvert"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> joinConvert(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String sql = resolveSql(params, null);
        EDbVendor vendor = resolveVendor(params, jsonBody);
        auditDemo("joinConvert", vendor, sql);
        DemoAstServiceSpi.JoinConvertRequest req = new DemoAstServiceSpi.JoinConvertRequest();
        req.setSql(sql);
        req.setDbvendor(vendor);
        return ApiResponse.ok(demoAstServiceSpi.joinConvert(req));
    }

    @PostMapping(
            value = {
                "/demo/keywordCheck",
                "/sqlflow/demo/keywordCheck",
                "/demo/keyword/check",
                "/sqlflow/demo/keyword/check"
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> keywordCheck(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String target = LineageGenerationService.firstNonBlank(params, "word", "words");
        if (target == null || target.isBlank()) {
            target = resolveSql(params, null);
        }
        EDbVendor vendor = resolveVendor(params, jsonBody);
        auditDemo("keywordCheck", vendor, target);
        return ApiResponse.ok(demoAstServiceSpi.keywordCheck(new DemoAstServiceSpi.AstSqlRequest(target, vendor)));
    }

    @PostMapping(
            value = {"/demo/renameTable", "/sqlflow/demo/renameTable"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> renameTable(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String sql = resolveSql(params, null);
        EDbVendor vendor = resolveVendor(params, jsonBody);
        DemoAstServiceSpi.RenameTableRequest req = new DemoAstServiceSpi.RenameTableRequest();
        req.setSql(sql);
        req.setDbvendor(vendor);
        req.setSourceTable(LineageGenerationService.firstNonBlank(params, "sourceTable", "oldTable", "from"));
        req.setTargetTable(LineageGenerationService.firstNonBlank(params, "targetTable", "newTable", "to"));
        return ApiResponse.ok(demoAstServiceSpi.renameTable(req));
    }

    @PostMapping(
            value = {"/demo/renameColumn", "/sqlflow/demo/renameColumn"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> renameColumn(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String sql = resolveSql(params, null);
        EDbVendor vendor = resolveVendor(params, jsonBody);
        DemoAstServiceSpi.RenameColumnRequest req = new DemoAstServiceSpi.RenameColumnRequest();
        req.setSql(sql);
        req.setDbvendor(vendor);
        req.setSourceColumn(LineageGenerationService.firstNonBlank(params, "sourceColumn", "oldColumn", "from"));
        req.setTargetColumn(LineageGenerationService.firstNonBlank(params, "targetColumn", "newColumn", "to"));
        return ApiResponse.ok(demoAstServiceSpi.renameColumn(req));
    }

    @PostMapping(
            value = {"/demo/removeSchema", "/sqlflow/demo/removeSchema"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> removeSchema(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String sql = resolveSql(params, null);
        EDbVendor vendor = resolveVendor(params, jsonBody);
        DemoAstServiceSpi.RemoveSchemaRequest req = new DemoAstServiceSpi.RemoveSchemaRequest();
        req.setSql(sql);
        req.setDbvendor(vendor);
        req.setSchemaName(LineageGenerationService.firstNonBlank(params, "schemaName", "schema"));
        return ApiResponse.ok(demoAstServiceSpi.removeSchema(req));
    }

    @PostMapping(
            value = {"/demo/xml/toXML", "/demo/toXML", "/sqlflow/demo/xml/toXML"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> toXml(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String sql = resolveSql(params, null);
        EDbVendor vendor = resolveVendor(params, jsonBody);
        auditDemo("toXML", vendor, sql);
        return ApiResponse.ok(demoAstServiceSpi.toXml(new DemoAstServiceSpi.AstSqlRequest(sql, vendor)));
    }

    @PostMapping(
            value = {"/liveDemo", "/demo/{appId}"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> dispatchDemoAppId(
            @PathVariable(value = "appId", required = false) String pathAppId,
            HttpServletRequest request,
            @RequestParam(value = "sqlfiles", required = false) MultipartFile[] sqlfiles,
            @RequestParam(value = "sqlfile", required = false) MultipartFile sqlfile,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        if (!params.containsKey("params") && params.get("appParams") != null) {
            params.put("params", params.get("appParams"));
        }

        boolean isLiveDemoEndpoint = pathAppId == null;
        if (isLiveDemoEndpoint) {
            authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        }

        MultipartFile[] uploadedFiles = mergeFiles(sqlfiles, sqlfile);
        String sql = resolveSql(params, uploadedFiles);
        if (isLiveDemoEndpoint && sql.isBlank() && params.get("formatConfig") == null) {
            return ApiResponse.error(500, "Both of sqltext and sqlfile are empty");
        }

        String appId = pathAppId != null ? pathAppId : params.get("appId");
        if (appId == null || appId.isBlank()) {
            return ApiResponse.error(202, "Unknown gspLive demo appId null");
        }

        EDbVendor vendor = resolveVendor(params, jsonBody);
        auditDemo(appId, vendor, sql);

        return switch (appId.trim().toLowerCase(Locale.ROOT)) {
            case "sqlinjectiondetector", "antisqlinjection" ->
                    ApiResponse.ok(demoAstServiceSpi.antiSqlInjection(new DemoAstServiceSpi.AstSqlRequest(sql, vendor)));
            case "sqlformatter", "sqlformat" -> {
                if (!sql.isBlank() && !params.containsKey("sqltext")) {
                    params.put("sqltext", sql);
                }
                yield lineageService.formatSql(params, jsonBody);
            }
            case "checksyntax", "syntaxcheck" ->
                    LineageGenerationService.toSyntaxApiResponse(
                            demoAstServiceSpi.syntaxCheck(new DemoAstServiceSpi.AstSqlRequest(sql, vendor)));
            case "gettablecolumns", "tablecolumn" -> executeGetTableColumns(sql, vendor, params);
            case "columnimpact" -> executeColumnImpact(sql, vendor, params);
            case "keywordcheck", "keyword" -> {
                String target = LineageGenerationService.firstNonBlank(params, "word", "words");
                if (target == null || target.isBlank()) {
                    target = sql;
                }
                yield ApiResponse.ok(demoAstServiceSpi.keywordCheck(new DemoAstServiceSpi.AstSqlRequest(target, vendor)));
            }
            case "joinconverter", "joinconvert" -> {
                DemoAstServiceSpi.JoinConvertRequest jr = new DemoAstServiceSpi.JoinConvertRequest();
                jr.setSql(sql);
                jr.setDbvendor(vendor);
                yield ApiResponse.ok(demoAstServiceSpi.joinConvert(jr));
            }
            case "toxml" ->
                    ApiResponse.ok(demoAstServiceSpi.toXml(new DemoAstServiceSpi.AstSqlRequest(sql, vendor)));
            default -> ApiResponse.error(202, "Unknown gspLive demo appId " + appId);
        };
    }

    private ApiResponse<Object> executeGetTableColumns(
            String sql, EDbVendor vendor, Map<String, String> params) {
        DemoAstServiceSpi.TableColumnResult tc =
                demoAstServiceSpi.getTableColumns(new DemoAstServiceSpi.AstSqlRequest(sql, vendor));
        String format = params != null ? params.get("format") : null;
        if ("csv".equalsIgnoreCase(format)) {
            String delim = params.get("delimiter");
            if (delim == null || delim.isEmpty()) {
                delim = ",";
            }
            String csv = renderTableColumnsCsv(tc, delim);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tables", tc != null && tc.getTables() != null ? tc.getTables() : Map.of());
            if (tc != null && tc.getOrphanColumns() != null) {
                out.put("orphanColumns", tc.getOrphanColumns());
            }
            if (tc != null && tc.getErrors() != null) {
                out.put("errors", tc.getErrors());
            }
            out.put("mode", params.getOrDefault("mode", "summary"));
            out.put("format", "csv");
            out.put("csv", csv);
            return ApiResponse.ok(out);
        }
        return ApiResponse.ok(tc);
    }

    private static String renderTableColumnsCsv(DemoAstServiceSpi.TableColumnResult tc, String delim) {
        StringBuilder sb = new StringBuilder();
        sb.append("table").append(delim).append("column\n");
        if (tc != null && tc.getTables() != null) {
            for (Map.Entry<String, List<String>> e : tc.getTables().entrySet()) {
                String tbl = e.getKey() != null ? e.getKey() : "";
                List<String> cols = e.getValue();
                if (cols == null || cols.isEmpty()) {
                    sb.append(tbl).append(delim).append("\n");
                } else {
                    for (String col : cols) {
                        sb.append(tbl).append(delim).append(col != null ? col : "").append("\n");
                    }
                }
            }
        }
        return sb.toString();
    }

    private ApiResponse<Object> executeColumnImpact(
            String sql, EDbVendor vendor, Map<String, String> params) {
        String rawOpts = LineageGenerationService.firstNonBlank(params, "params", "appParams");
        if (rawOpts != null && rawOpts.toLowerCase(Locale.ROOT).contains("/xml")) {
            LineageEngineSpi.LineageAnalysisResult res = lineageEngineSpi.analyze(
                    LineageEngineSpi.LineageAnalysisRequest.ofSql(sql, Option.forVendor(vendor)));
            Map<String, Object> xmlOut = new LinkedHashMap<>();
            xmlOut.put("xml", res != null && res.getXmlString() != null ? res.getXmlString() : "<dlineage/>");
            xmlOut.put("impact", demoAstServiceSpi.columnImpact(new DemoAstServiceSpi.AstSqlRequest(sql, vendor)));
            return ApiResponse.ok(xmlOut);
        }
        return ApiResponse.ok(demoAstServiceSpi.columnImpact(new DemoAstServiceSpi.AstSqlRequest(sql, vendor)));
    }

    private static MultipartFile[] mergeFiles(MultipartFile[] sqlfiles, MultipartFile sqlfile) {
        List<MultipartFile> out = new ArrayList<>();
        if (sqlfiles != null) {
            for (MultipartFile mf : sqlfiles) {
                if (mf != null && !mf.isEmpty()) {
                    out.add(mf);
                }
            }
        }
        if (sqlfile != null && !sqlfile.isEmpty()) {
            out.add(sqlfile);
        }
        return out.isEmpty() ? null : out.toArray(MultipartFile[]::new);
    }

    private String resolveSql(Map<String, String> params, MultipartFile[] files) {
        StringBuilder sb = new StringBuilder();
        if (files != null) {
            for (MultipartFile mf : files) {
                if (mf != null && !mf.isEmpty()) {
                    try {
                        sb.append(new String(mf.getBytes(), StandardCharsets.UTF_8)).append("\n;\n");
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        String s = LineageGenerationService.firstNonBlank(params, "sqltext", "sql", "input_sql", "sqlText");
        if (s != null && !s.isBlank()) {
            String trimmed = s.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                try {
                    List<String> arr = objectMapper.readValue(trimmed, new TypeReference<List<String>>() {});
                    s = String.join("\n;\n", arr);
                } catch (Exception ignored) {
                }
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private EDbVendor resolveVendor(Map<String, String> params, Map<String, Object> jsonBody) {
        String raw = LineageGenerationService.firstNonBlank(params, "dbvendor", "dbVendor", "database", "vendor");
        if (raw == null && jsonBody != null && jsonBody.get("format_option") instanceof Map<?, ?> fo) {
            Object v = fo.get("dbVendor") != null ? fo.get("dbVendor") : fo.get("db_vendor");
            if (v != null) {
                raw = String.valueOf(v);
            }
        }
        return lineageService.resolveVendor(raw);
    }

    private void auditDemo(String demoName, EDbVendor vendor, String sql) {
        try {
            Path logDir = config.getLogDir().resolve("demo");
            Files.createDirectories(logDir);
            Path logFile = logDir.resolve(LocalDate.now(ZoneOffset.UTC) + ".log");
            String entry = Instant.now() + " [" + demoName + "] [" + vendor.wireName() + "] bytes="
                    + (sql != null ? sql.length() : 0) + "\n";
            Files.writeString(
                    logFile,
                    entry,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
}
