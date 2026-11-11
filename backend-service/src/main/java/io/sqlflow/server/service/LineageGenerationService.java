package io.sqlflow.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.contracts.domain.ApiResponse;
import io.sqlflow.contracts.domain.ShareInfo;
import io.sqlflow.contracts.graph.DbObjectPosition;
import io.sqlflow.contracts.graph.LeftMostSourceTable;
import io.sqlflow.contracts.graph.SqlflowJSON;
import io.sqlflow.contracts.graph.SummaryInfo;
import io.sqlflow.contracts.lineage.Coordinate;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.Option;
import io.sqlflow.contracts.lineage.Orientation;
import io.sqlflow.contracts.lineage.RelationshipType;
import io.sqlflow.contracts.lineage.SqlInfo;
import io.sqlflow.contracts.lineage.xml.DlineageXml;
import io.sqlflow.contracts.lineage.xml.DlineageXmlCodec;
import io.sqlflow.contracts.spi.CsvLineageExportSpi;
import io.sqlflow.contracts.spi.DemoAstServiceSpi;
import io.sqlflow.contracts.spi.GraphLayoutSpi;
import io.sqlflow.contracts.spi.LineageEngineSpi;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.server.config.ExecutionTimeoutGuard;
import io.sqlflow.server.config.SqlflowRuntimeConfig;
import io.sqlflow.server.persistence.SessionCacheService;
import io.sqlflow.server.security.CryptoService;
import io.sqlflow.server.security.SqlflowBusinessException;
import io.sqlflow.server.spi.CatalogUtils;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Interactive & stateless SQL lineage generation, graph layout, upstream/downstream slicing,
 * ER diagramming, token coordinate highlighting, complexity degradation, and export service
 * ({@code 01 §3.4}, {@code 02 §4.1}, Wire Invariants #4, #5, #9, #15, #17, #20).
 */
@Service
public class LineageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(LineageGenerationService.class);
    private static final int SUMMARY_OBJECT_GATE = 200;
    private static final int MAX_CALL_LINEAGE_SQL_ITEMS = 100;
    private static final DateTimeFormatter LOG_DAY_FMT =
            DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter LOG_FILE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);

    private final SqlflowRuntimeConfig config;
    private final ExecutionTimeoutGuard timeoutGuard;
    private final LineageEngineSpi lineageEngineSpi;
    private final GraphLayoutSpi graphLayoutSpi;
    private final CsvLineageExportSpi csvLineageExportSpi;
    private final DemoAstServiceSpi demoAstServiceSpi;
    private final SessionCacheService sessionCacheService;
    private final UserSchemaService userSchemaService;
    private final ShareService shareService;
    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper;

    private final Map<String, List<SqlInfo>> sessionSqlUnits = new ConcurrentHashMap<>();
    private final Map<String, String> sessionRawSql = new ConcurrentHashMap<>();
    private volatile Function<String, DataFlow> jobDataFlowResolver = id -> null;

    public LineageGenerationService(
            SqlflowRuntimeConfig config,
            ExecutionTimeoutGuard timeoutGuard,
            LineageEngineSpi lineageEngineSpi,
            GraphLayoutSpi graphLayoutSpi,
            CsvLineageExportSpi csvLineageExportSpi,
            DemoAstServiceSpi demoAstServiceSpi,
            SessionCacheService sessionCacheService,
            UserSchemaService userSchemaService,
            ShareService shareService,
            CryptoService cryptoService,
            ObjectMapper objectMapper) {
        this.config = config;
        this.timeoutGuard = timeoutGuard;
        this.lineageEngineSpi = lineageEngineSpi;
        this.graphLayoutSpi = graphLayoutSpi;
        this.csvLineageExportSpi = csvLineageExportSpi;
        this.demoAstServiceSpi = demoAstServiceSpi;
        this.sessionCacheService = sessionCacheService;
        this.userSchemaService = userSchemaService;
        this.shareService = shareService;
        this.cryptoService = cryptoService;
        this.objectMapper = objectMapper;
    }

    public void registerJobDataFlowResolver(Function<String, DataFlow> resolver) {
        if (resolver != null) {
            this.jobDataFlowResolver = resolver;
        }
    }

    public EDbVendor resolveVendor(String rawVendor) {
        if (rawVendor == null || rawVendor.isBlank()) {
            return EDbVendor.dbvoracle;
        }
        return EDbVendor.find(rawVendor.trim()).orElse(EDbVendor.dbvoracle);
    }

    public Option buildOption(Map<String, String> params, EDbVendor vendor) {
        Option opt = Option.forVendor(vendor != null ? vendor : EDbVendor.dbvoracle);
        opt.setDefaultServer(blankToNull(firstNonBlank(params, "defaultServer", "server")));
        opt.setDefaultDatabase(blankToNull(firstNonBlank(params, "defaultDatabase", "database")));
        opt.setDefaultSchema(blankToNull(firstNonBlank(params, "defaultSchema", "schema")));
        if (config.getFilePathDatabase() != null && !config.getFilePathDatabase().isBlank()) {
            opt.setFilePathDatabase(config.getFilePathDatabase());
        }
        if (config.getFilePathSchema() != null && !config.getFilePathSchema().isBlank()) {
            opt.setFilePathSchema(config.getFilePathSchema());
        }

        opt.setSimpleOutput(parseBool(params.get("simple"), false) || parseBool(params.get("simpleOutput"), false));
        opt.setIgnoreRecordSet(parseBool(params.get("ignoreRecordSet"), false));
        opt.setSqlflowIgnoreFunction(parseBool(firstNonBlank(params, "ignoreFunction", "sqlflowIgnoreFunction"), false));
        opt.setShowConstantTable(parseBool(params.get("showConstantTable"), false));
        opt.setShowCountTableColumn(parseBool(
                firstNonBlank(params, "treatArgumentsInCountFunctionAsDirectDataflow", "showCountTableColumn"), true));
        opt.setTransform(parseBool(firstNonBlank(params, "showTransform", "transform"), true));
        opt.setTransformCoordinate(parseBool(params.get("transformCoordinate"), true));
        opt.setShowJoin(parseBool(params.get("showJoin"), false));
        opt.setShowCallRelation(parseBool(firstNonBlank(params, "showCallRelation", "callRelation"), false));
        opt.setShowERDiagram(parseBool(firstNonBlank(params, "erDiagram", "showERDiagram"), false));
        opt.setLinkOrphanColumnToFirstTable(parseBool(params.get("linkOrphanColumnToFirstTable"), false));
        opt.setShowCandidateTable(parseBool(params.get("showCandidateTable"), true));
        opt.setIgnoreCoordinate(parseBool(params.get("ignoreCoordinate"), false));

        if (parseBool(params.get("onlyDirect"), false)) {
            opt.applyOnlyDirect();
        }
        if (opt.isShowERDiagram()) {
            opt.applyErDiagram();
        }
        String showRelationType = params.get("showRelationType");
        if (showRelationType != null && !showRelationType.isBlank()) {
            Set<String> types = new LinkedHashSet<>();
            for (String part : showRelationType.split(",")) {
                if (!part.isBlank()) {
                    types.add(part.trim().toLowerCase(Locale.ROOT));
                }
            }
            if (!types.isEmpty()) {
                opt.setFilterRelationTypes(types);
            }
        }
        return opt;
    }

    public String extractSqlFromRequest(Map<String, String> params, MultipartFile[] sqlfiles) {
        StringBuilder sb = new StringBuilder();
        if (sqlfiles != null && sqlfiles.length > 0) {
            for (MultipartFile mf : sqlfiles) {
                if (mf != null && !mf.isEmpty()) {
                    try {
                        sb.append(new String(mf.getBytes(), StandardCharsets.UTF_8)).append("\n;\n");
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to read uploaded sqlfile: " + e.getMessage(), e);
                    }
                }
            }
        }
        String inline = firstNonBlank(params, "sqltext", "sql", "sqlText", "input_sql");
        if (inline != null && !inline.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(inline);
        }
        return sb.toString();
    }

    public DataFlow resolveOrAnalyzeDataFlow(
            String userId,
            Map<String, String> params,
            MultipartFile[] sqlfiles,
            String[] outSessionId) {
        String existingSessionId = firstNonBlank(params, "sessionId", "sqlHash");
        String jobId = params.get("jobId");
        String rawSql = extractSqlFromRequest(params, sqlfiles);

        if (rawSql.isBlank()) {
            if (existingSessionId != null && !existingSessionId.isBlank()) {
                DataFlow cached = sessionCacheService.loadDataFlow(existingSessionId);
                if (cached != null) {
                    if (outSessionId != null && outSessionId.length > 0) {
                        outSessionId[0] = existingSessionId;
                    }
                    return cached;
                }
                DataFlow fromJobAsSession = jobDataFlowResolver.apply(existingSessionId);
                if (fromJobAsSession != null) {
                    if (outSessionId != null && outSessionId.length > 0) {
                        outSessionId[0] = existingSessionId;
                    }
                    return fromJobAsSession;
                }
            }
            if (jobId != null && !jobId.isBlank()) {
                DataFlow fromJob = jobDataFlowResolver.apply(jobId);
                if (fromJob != null) {
                    String sid = existingSessionId != null && !existingSessionId.isBlank() ? existingSessionId : jobId;
                    sessionCacheService.saveDataFlow(sid, fromJob);
                    if (outSessionId != null && outSessionId.length > 0) {
                        outSessionId[0] = sid;
                    }
                    return fromJob;
                }
            }
        }

        EDbVendor vendor = resolveVendor(firstNonBlank(params, "dbvendor", "dbVendor", "vendor", "database"));
        Option option = buildOption(params, vendor);

        String mappedSql = config.preprocessSql(rawSql);
        String userSchemaDdl = userSchemaService.loadCombinedUserSchemaDdl(userId, vendor.wireName());
        final String effectiveSql;
        final List<SqlInfo> units = new ArrayList<>();
        if (userSchemaDdl != null && !userSchemaDdl.isBlank()) {
            SqlInfo ddlUnit = new SqlInfo(userSchemaDdl, "user_schema.sql", "user_schema.sql", vendor.wireName());
            units.add(ddlUnit);

            SqlInfo queryUnit = new SqlInfo(mappedSql, "input.sql", "input.sql", vendor.wireName());
            units.add(queryUnit);
            effectiveSql = userSchemaDdl + "\n" + mappedSql;
        } else {
            effectiveSql = mappedSql;
            SqlInfo queryUnit = new SqlInfo(mappedSql, "input.sql", "input.sql", vendor.wireName());
            units.add(queryUnit);
        }

        if (!mappedSql.isBlank()) {
            auditUserSqlIfEnabled(userId, mappedSql, vendor.shortName());
        }

        String sessionId = (existingSessionId != null && !existingSessionId.isBlank() && rawSql.isBlank())
                ? existingSessionId
                : computeSessionId(userId, vendor, option, effectiveSql);
        if (outSessionId != null && outSessionId.length > 0) {
            outSessionId[0] = sessionId;
        }

        DataFlow cachedDf = sessionCacheService.loadDataFlow(sessionId);
        if (cachedDf != null && !parseBool(params.get("forceRefresh"), false)) {
            sessionRawSql.putIfAbsent(sessionId, mappedSql);
            sessionSqlUnits.putIfAbsent(sessionId, units);
            return cachedDf;
        }

        LineageEngineSpi.LineageAnalysisResult result = timeoutGuard.executeWithTimeout(
                "interactive-lineage",
                config.getDataflowGraphTimeoutMs(),
                () -> {
                    LineageEngineSpi.LineageAnalysisRequest req = LineageEngineSpi.LineageAnalysisRequest.ofSql(effectiveSql, option);
                    return lineageEngineSpi.analyze(req);
                });

        DataFlow df = result != null && result.getDataFlow() != null ? result.getDataFlow() : new DataFlow();
        sessionCacheService.saveDataFlow(sessionId, df);
        sessionRawSql.put(sessionId, mappedSql);
        sessionSqlUnits.put(sessionId, units);
        return df;
    }

    public ApiResponse<SqlflowJSON> generateGraph(
            String userId,
            Map<String, String> params,
            MultipartFile[] sqlfiles,
            boolean forceTableLevel,
            boolean forceErDiagram) {
        long startMs = System.currentTimeMillis();
        Map<String, String> effectiveParams = new LinkedHashMap<>(params);
        if (forceErDiagram) {
            effectiveParams.put("erDiagram", "true");
        }
        String[] sidHolder = new String[1];
        DataFlow df = resolveOrAnalyzeDataFlow(userId, effectiveParams, sqlfiles, sidHolder);
        String sessionId = sidHolder[0];

        int objCount = CatalogUtils.collectAllDbObjects(df).size();
        int relCount = df.getRelationships() != null ? df.getRelationships().size() : 0;

        String mode = "global";
        boolean tableLevel = forceTableLevel || parseBool(effectiveParams.get("tableLevel"), false);
        if (!forceErDiagram && (objCount > SUMMARY_OBJECT_GATE || relCount > config.getRelationLimit())) {
            mode = "summary";
            tableLevel = true;
        } else if (!forceErDiagram
                && relCount > config.getRelationTrySimpleLimit()
                && !parseBool(effectiveParams.get("ignoreRecordSet"), false)) {
            mode = "ignoreRecordSet";
        }

        Orientation orientation = Orientation.fromString(effectiveParams.get("orientation"));
        Set<RelationshipType> relTypes = parseRelationTypes(effectiveParams.get("showRelationType"), forceErDiagram);

        final boolean layoutTableLevel = tableLevel;
        SqlflowJSON laidOut = timeoutGuard.executeWithTimeout(
                "graph-layout",
                config.getDataflowGraphTimeoutMs(),
                () -> {
                    GraphLayoutSpi.GraphLayoutRequest layoutReq = GraphLayoutSpi.GraphLayoutRequest.of(df, orientation);
                    layoutReq.setTableLevel(layoutTableLevel);
                    layoutReq.setRelationTypes(relTypes);
                    layoutReq.setIncludeSvg(parseBool(effectiveParams.get("includeSvg"), false));
                    layoutReq.setIncludeGraphML(parseBool(effectiveParams.get("includeGraphML"), false));
                    return graphLayoutSpi.layoutGraph(layoutReq);
                });

        normalizeLaidOutGraph(laidOut, df, mode, tableLevel, orientation, System.currentTimeMillis() - startMs);
        sessionCacheService.saveGraph(sessionId, laidOut);

        if (parseBool(effectiveParams.get("share"), false)) {
            ShareInfo shareInfo = shareService.createShare(
                    userId,
                    laidOut,
                    sessionId,
                    effectiveParams.get("jobId"),
                    tableLevel,
                    effectiveParams.get("title"),
                    sessionRawSql.getOrDefault(sessionId, sessionId));
            laidOut.setShareId(shareInfo.getShareId());
        }

        return ApiResponse.okWithMode(laidOut, sessionId, mode);
    }

    public ApiResponse<SummaryInfo> generateSummary(
            String userId,
            Map<String, String> params,
            MultipartFile[] sqlfiles) {
        String[] sidHolder = new String[1];
        DataFlow df = resolveOrAnalyzeDataFlow(userId, params, sqlfiles, sidHolder);
        SummaryInfo summary = lineageEngineSpi.summarize(df);
        return ApiResponse.okWithSession(summary, sidHolder[0]);
    }

    /**
     * Public {@code upstreamGraph} / {@code downstreamGraph} returning wrapped {@code ApiResponse<SqlflowJSON>}.
     */
    public ApiResponse<SqlflowJSON> sliceGraph(
            String userId,
            Map<String, String> params,
            MultipartFile[] sqlfiles,
            boolean upstream) {
        long startMs = System.currentTimeMillis();
        String[] sidHolder = new String[1];
        DataFlow df = resolveOrAnalyzeDataFlow(userId, params, sqlfiles, sidHolder);
        String sessionId = sidHolder[0];

        GraphLayoutSpi.GraphSliceRequest sliceReq = buildSliceRequest(df, params, true);
        GraphLayoutSpi.SlicingResult result = timeoutGuard.executeWithTimeout(
                upstream ? "slice-upstream-graph" : "slice-downstream-graph",
                config.getDataflowGraphTimeoutMs(),
                () -> upstream ? graphLayoutSpi.sliceUpstream(sliceReq) : graphLayoutSpi.sliceDownstream(sliceReq));

        DataFlow slicedDf = result != null && result.getSlicedDataFlow() != null ? result.getSlicedDataFlow() : df;
        SqlflowJSON graph = result != null && result.getGraph() != null ? result.getGraph() : new SqlflowJSON();
        if (graph.getElements() == null && graph.getGraph() == null) {
            GraphLayoutSpi.GraphLayoutRequest lr = GraphLayoutSpi.GraphLayoutRequest.of(slicedDf, sliceReq.getOrientation());
            lr.setTableLevel(parseBool(params.get("tableLevel"), false));
            graph = graphLayoutSpi.layoutGraph(lr);
        }
        normalizeLaidOutGraph(
                graph,
                slicedDf,
                "global",
                parseBool(params.get("tableLevel"), false),
                sliceReq.getOrientation(),
                System.currentTimeMillis() - startMs);

        if (parseBool(params.get("share"), false)) {
            ShareInfo shareInfo = shareService.createShare(
                    userId, graph, sessionId, params.get("jobId"), Boolean.TRUE.equals(graph.getTableLevel()), null, sessionId);
            graph.setShareId(shareInfo.getShareId());
        }

        return ApiResponse.okWithMode(graph, sessionId, "global");
    }

    /**
     * Internal {@code POST /sqlflow/lineage/{upstream,downstream}} returning raw unwrapped {@link DataFlow}
     * (Wire Invariant #9).
     */
    public DataFlow sliceRawDataFlow(
            String userId,
            Map<String, String> params,
            MultipartFile[] sqlfiles,
            boolean upstream) {
        String[] sidHolder = new String[1];
        DataFlow df = resolveOrAnalyzeDataFlow(userId, params, sqlfiles, sidHolder);
        GraphLayoutSpi.GraphSliceRequest sliceReq = buildSliceRequest(df, params, false);
        GraphLayoutSpi.SlicingResult res = timeoutGuard.executeWithTimeout(
                upstream ? "raw-upstream" : "raw-downstream",
                config.getDataflowGraphTimeoutMs(),
                () -> upstream ? graphLayoutSpi.sliceUpstream(sliceReq) : graphLayoutSpi.sliceDownstream(sliceReq));
        DataFlow out = res != null && res.getSlicedDataFlow() != null ? res.getSlicedDataFlow() : new DataFlow();
        if (out.getDbobjs() == null) {
            out.setDbobjs(new io.sqlflow.contracts.lineage.SqlflowCatalog());
        }
        if (out.getRelationships() == null) {
            out.setRelationships(List.of());
        }
        return out;
    }

    public ApiResponse<SqlflowJSON> generateCallLineage(
            String userId,
            List<SqlInfo> sqlInfos,
            Map<String, String> params,
            MultipartFile[] sqlfiles) {
        if (sqlInfos != null && sqlInfos.size() > MAX_CALL_LINEAGE_SQL_ITEMS) {
            throw SqlflowBusinessException.badRequest(
                    "call_lineage accepts at most " + MAX_CALL_LINEAGE_SQL_ITEMS + " SqlInfo items");
        }
        Map<String, String> merged = new LinkedHashMap<>(params);
        merged.put("showCallRelation", "true");
        if (sqlInfos != null && !sqlInfos.isEmpty() && firstNonBlank(merged, "sqltext", "sql") == null) {
            StringBuilder sb = new StringBuilder();
            for (SqlInfo item : sqlInfos) {
                if (item != null && item.getSql() != null) {
                    sb.append(item.getSql()).append("\n;\n");
                }
            }
            merged.put("sqltext", sb.toString());
        }
        return generateGraph(userId, merged, sqlfiles, false, false);
    }

    public ApiResponse<LeftMostSourceTable> generateLeftMostSourceTable(
            String userId,
            Map<String, String> params,
            MultipartFile[] sqlfiles) {
        String[] sidHolder = new String[1];
        DataFlow df = resolveOrAnalyzeDataFlow(userId, params, sqlfiles, sidHolder);
        String targetTable = firstNonBlank(params, "tableId", "dbObjId", "table", "tableNamePattern");
        boolean ignoreRecordSet = parseBool(params.get("ignoreRecordSet"), true);
        LeftMostSourceTable leftMost = graphLayoutSpi.computeLeftMostSourceTable(df, targetTable, ignoreRecordSet);
        return ApiResponse.okWithSession(leftMost, sidHolder[0]);
    }

    public ApiResponse<SqlflowJSON> generateLeftMostSourceTableGraph(
            String userId,
            Map<String, String> params,
            MultipartFile[] sqlfiles) {
        Map<String, String> upParams = new LinkedHashMap<>(params);
        upParams.putIfAbsent("ignoreRecordSet", "true");
        return sliceGraph(userId, upParams, sqlfiles, true);
    }

    public ApiResponse<List<Map<String, Object>>> listTableColumns(
            String userId,
            Map<String, String> params,
            MultipartFile[] sqlfiles) {
        String[] sidHolder = new String[1];
        DataFlow df = resolveOrAnalyzeDataFlow(userId, params, sqlfiles, sidHolder);
        String filterPattern = firstNonBlank(params, "tableNamePattern", "table", "keyword");
        List<Map<String, Object>> result = new ArrayList<>();
        for (DbObject obj : CatalogUtils.collectAllDbObjects(df)) {
            if (filterPattern != null && !filterPattern.isBlank()) {
                String name = obj.getName() != null ? obj.getName() : "";
                if (!name.toLowerCase(Locale.ROOT).contains(filterPattern.toLowerCase(Locale.ROOT))
                        && !matchesRegexSafe(name, filterPattern)) {
                    continue;
                }
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", obj.getId());
            entry.put("database", obj.getDatabase());
            entry.put("schema", obj.getSchema());
            entry.put("name", obj.getName());
            entry.put("type", obj.getType());
            entry.put("columns", obj.getColumns() != null ? obj.getColumns() : List.of());
            result.add(entry);
        }
        return ApiResponse.okWithSession(result, sidHolder[0]);
    }

    /**
     * Wire Invariant #5: Resolves 1-based {@code [line, col]} SQL coordinates for selected graph elements.
     */
    public ApiResponse<List<DbObjectPosition>> getSelectedDbObjectInfo(
            String userId,
            Map<String, String> params) {
        String sessionId = firstNonBlank(params, "sessionId", "sqlHash", "jobId");
        DataFlow df = sessionId != null ? sessionCacheService.loadDataFlow(sessionId) : null;
        if (df == null && sessionId != null) {
            df = jobDataFlowResolver.apply(sessionId);
        }
        List<SqlInfo> sqlUnits = sessionId != null ? sessionSqlUnits.get(sessionId) : null;
        String inlineSql = firstNonBlank(params, "sqltext", "sql");
        if ((sqlUnits == null || sqlUnits.isEmpty()) && inlineSql != null && !inlineSql.isBlank()) {
            SqlInfo unit = new SqlInfo(inlineSql, "input.sql", "input.sql", "dbvoracle");
            sqlUnits = List.of(unit);
        }

        GraphLayoutSpi.SelectedObjectRequest req = new GraphLayoutSpi.SelectedObjectRequest();
        req.setDataFlow(df != null ? df : new DataFlow());
        req.setSqlUnits(sqlUnits != null ? sqlUnits : List.of());
        req.setDbObjId(firstNonBlank(params, "dbObjId", "tableId", "id"));
        req.setColumnId(params.get("columnId"));
        String relIdsRaw = firstNonBlank(params, "relationIds", "relationshipIds");
        if (relIdsRaw != null && !relIdsRaw.isBlank()) {
            List<String> relIds = new ArrayList<>();
            for (String s : relIdsRaw.replace("[", "").replace("]", "").replace("\"", "").split(",")) {
                if (!s.isBlank()) {
                    relIds.add(s.trim());
                }
            }
            req.setRelationIds(relIds);
        }

        List<DbObjectPosition> positions = new ArrayList<>(graphLayoutSpi.getSelectedDbObjectInfo(req));

        // Also support explicit `coordinates` JSON parameter passed directly from UI/E2E
        String coordsJson = params.get("coordinates");
        if (positions.isEmpty() && coordsJson != null && !coordsJson.isBlank()) {
            String sqlText = sessionId != null
                    ? sessionRawSql.getOrDefault(sessionId, inlineSql != null ? inlineSql : "")
                    : (inlineSql != null ? inlineSql : "");
            positions.addAll(buildPositionsFromCoordinatesJson(coordsJson, sqlText));
        }

        return ApiResponse.okWithSession(positions, sessionId);
    }

    // -------------------------------------------------------------------------
    // Binary & Text Exports (XML, JSON, CSV, metadata_lineage.zip, GraphML, PNG)
    // -------------------------------------------------------------------------

    public String exportAsJson(String userId, Map<String, String> params, MultipartFile[] sqlfiles) {
        String[] sidHolder = new String[1];
        DataFlow df = resolveOrAnalyzeDataFlow(userId, params, sqlfiles, sidHolder);
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(df);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize DataFlow JSON", e);
        }
    }

    public String exportMetadataJson(String userId, Map<String, String> params, MultipartFile[] sqlfiles) {
        String[] sidHolder = new String[1];
        DataFlow df = resolveOrAnalyzeDataFlow(userId, params, sqlfiles, sidHolder);
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("code", 200);
            meta.put("dbobjs", df.getDbobjs());
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(meta);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize metadata JSON", e);
        }
    }

    public String exportAsXml(String userId, Map<String, String> params, MultipartFile[] sqlfiles) {
        EDbVendor vendor = resolveVendor(firstNonBlank(params, "dbvendor", "dbVendor"));
        Option option = buildOption(params, vendor);
        String rawSql = extractSqlFromRequest(params, sqlfiles);
        if (!rawSql.isBlank()) {
            LineageEngineSpi.LineageAnalysisResult res = timeoutGuard.executeWithTimeout(
                    "export-xml",
                    config.getDataflowGraphTimeoutMs(),
                    () -> lineageEngineSpi.analyze(LineageEngineSpi.LineageAnalysisRequest.ofSql(rawSql, option)));
            if (res != null && res.getXmlString() != null && !res.getXmlString().isBlank()) {
                return res.getXmlString();
            }
            if (res != null && res.getDlineageXml() != null) {
                return DlineageXmlCodec.toXml(res.getDlineageXml(), true);
            }
        }
        String[] sidHolder = new String[1];
        DataFlow df = resolveOrAnalyzeDataFlow(userId, params, sqlfiles, sidHolder);
        DlineageXml xml = new DlineageXml();
        return DlineageXmlCodec.toXml(xml, true);
    }

    public String exportAsCsv(
            String userId,
            Map<String, String> params,
            MultipartFile[] sqlfiles,
            boolean generationPascalStyle) {
        String[] sidHolder = new String[1];
        DataFlow df = resolveOrAnalyzeDataFlow(userId, params, sqlfiles, sidHolder);
        CsvLineageExportSpi.CsvExportOptions opts;
        if (generationPascalStyle) {
            boolean showDataType = parseBool(firstNonBlank(params, "showDataType", "includeTargetDataType"), false);
            opts = CsvLineageExportSpi.CsvExportOptions.generationPascalCase(showDataType);
        } else {
            boolean showTransform = parseBool(params.get("showTransform"), false);
            opts = showTransform
                    ? CsvLineageExportSpi.CsvExportOptions.webJob16Col()
                    : CsvLineageExportSpi.CsvExportOptions.webJob15Col();
        }
        String delim = params.get("delimiter");
        if (delim != null && !delim.isEmpty()) {
            opts.setDelimiter(delim.charAt(0));
        }
        opts.setIncludeIndirectDataflow(parseBool(params.get("showIndirect"), true));
        return csvLineageExportSpi.exportLineageCsv(df, opts);
    }

    public byte[] exportMetadataLineageZip(String userId, Map<String, String> params, MultipartFile[] sqlfiles) {
        String[] sidHolder = new String[1];
        DataFlow df = resolveOrAnalyzeDataFlow(userId, params, sqlfiles, sidHolder);
        boolean showTransform = parseBool(params.get("showTransform"), true);
        CsvLineageExportSpi.CsvExportOptions opts = showTransform
                ? CsvLineageExportSpi.CsvExportOptions.webJob16Col()
                : CsvLineageExportSpi.CsvExportOptions.webJob15Col();
        return csvLineageExportSpi.exportMetadataLineageZip(df, opts);
    }

    public String exportAsGraphML(String userId, Map<String, String> params, MultipartFile[] sqlfiles) {
        Map<String, String> p = new LinkedHashMap<>(params);
        p.put("includeGraphML", "true");
        ApiResponse<SqlflowJSON> graphRes = generateGraph(userId, p, sqlfiles, false, false);
        SqlflowJSON graph = graphRes.getData();
        if (graph != null && graph.getGraphML() != null && !graph.getGraphML().isBlank()) {
            return graph.getGraphML();
        }
        return graphLayoutSpi.exportGraphML(graph != null ? graph : new SqlflowJSON());
    }

    public byte[] renderPngImage(
            String userId,
            Map<String, String> params,
            MultipartFile[] sqlfiles,
            String variant) {
        SqlflowJSON graph;
        if ("upstream".equals(variant)) {
            graph = sliceGraph(userId, params, sqlfiles, true).getData();
        } else if ("downstream".equals(variant)) {
            graph = sliceGraph(userId, params, sqlfiles, false).getData();
        } else if ("er".equals(variant)) {
            graph = generateGraph(userId, params, sqlfiles, false, true).getData();
        } else if ("leftmost".equals(variant)) {
            graph = generateLeftMostSourceTableGraph(userId, params, sqlfiles).getData();
        } else {
            graph = generateGraph(userId, params, sqlfiles, false, false).getData();
        }
        GraphLayoutSpi.PngRenderOptions pngOpts = new GraphLayoutSpi.PngRenderOptions();
        String scaleRaw = params.get("scale");
        if (scaleRaw != null && !scaleRaw.isBlank()) {
            try {
                pngOpts.setScale(Math.max(0.25, Math.min(4.0, Double.parseDouble(scaleRaw))));
            } catch (NumberFormatException ignored) {
            }
        }
        return graphLayoutSpi.renderPng(graph != null ? graph : new SqlflowJSON(), pngOpts);
    }

    public ApiResponse<Object> formatSql(Map<String, String> params) {
        return formatSql(params, null);
    }

    public ApiResponse<Object> formatSql(Map<String, String> params, Map<String, Object> jsonBody) {
        String rawFormatConfig = params != null ? params.get("formatConfig") : null;
        if (rawFormatConfig != null) {
            try {
                DemoAstServiceSpi.FormatRequest req =
                        objectMapper.readValue(rawFormatConfig, DemoAstServiceSpi.FormatRequest.class);
                if (req == null
                        || req.getInputSql() == null
                        || req.getFormatOption() == null
                        || req.getFormatOption().getDbVendor() == null
                        || req.getFormatOption().getDbVendor().isBlank()) {
                    return ApiResponse.error(500, "Invalid formatConfig: input_sql and format_option.db_vendor are required.");
                }
                req.setOptions(populateGFmtOpt(req.getFormatOption().getFormatOptions(), req.getOptions()));
                return ApiResponse.ok(demoAstServiceSpi.sqlformat(req));
            } catch (Exception e) {
                return ApiResponse.error(500, "Failed to parse formatConfig: " + e.getMessage());
            }
        }

        if (jsonBody != null
                && (jsonBody.containsKey("input_sql")
                        || jsonBody.containsKey("format_option")
                        || jsonBody.containsKey("formatOption"))) {
            try {
                DemoAstServiceSpi.FormatRequest req =
                        objectMapper.convertValue(jsonBody, DemoAstServiceSpi.FormatRequest.class);
                if (req == null
                        || req.getInputSql() == null
                        || req.getFormatOption() == null
                        || req.getFormatOption().getDbVendor() == null
                        || req.getFormatOption().getDbVendor().isBlank()) {
                    return ApiResponse.error(500, "Invalid format request: input_sql and format_option.db_vendor are required.");
                }
                req.setOptions(populateGFmtOpt(req.getFormatOption().getFormatOptions(), req.getOptions()));
                return ApiResponse.ok(demoAstServiceSpi.sqlformat(req));
            } catch (Exception e) {
                return ApiResponse.error(500, "Failed to parse format request: " + e.getMessage());
            }
        }

        String sql = firstNonBlank(params, "sqltext", "sql", "input_sql", "sqlText");
        EDbVendor vendor = resolveVendor(firstNonBlank(params, "dbvendor", "dbVendor", "database", "vendor"));
        DemoAstServiceSpi.FormatRequest req = new DemoAstServiceSpi.FormatRequest(
                sql != null ? sql : "", vendor, new DemoAstServiceSpi.GFmtOpt());
        DemoAstServiceSpi.FormatResult res = demoAstServiceSpi.sqlformat(req);
        return ApiResponse.ok(res);
    }

    public static DemoAstServiceSpi.GFmtOpt populateGFmtOpt(
            Map<String, Object> formatOptions, DemoAstServiceSpi.GFmtOpt base) {
        DemoAstServiceSpi.GFmtOpt opt = base != null ? base : new DemoAstServiceSpi.GFmtOpt();
        if (formatOptions == null || formatOptions.isEmpty()) {
            return opt;
        }
        if (formatOptions.get("capitalization") instanceof Map<?, ?> cap) {
            Object ck = cap.get("case_keywords") != null ? cap.get("case_keywords") : cap.get("caseKeywords");
            if (ck != null) {
                opt.setCaseKeywords(normalizeCaseStyle(String.valueOf(ck)));
            }
            Object ci = cap.get("case_identifier") != null ? cap.get("case_identifier") : cap.get("caseIdentifier");
            if (ci != null) {
                opt.setCaseIdentifier(normalizeCaseStyle(String.valueOf(ci)));
            }
            Object cf = cap.get("case_funcname") != null ? cap.get("case_funcname") : cap.get("caseFuncname");
            if (cf != null) {
                opt.setCaseFuncname(normalizeCaseStyle(String.valueOf(cf)));
            }
            Object cd = cap.get("case_datatype") != null ? cap.get("case_datatype") : cap.get("caseDatatype");
            if (cd != null) {
                opt.setCaseDatatype(normalizeCaseStyle(String.valueOf(cd)));
            }
        }
        Object topCaseKw = formatOptions.get("case_keywords") != null
                ? formatOptions.get("case_keywords")
                : formatOptions.get("caseKeywords");
        if (topCaseKw != null) {
            opt.setCaseKeywords(normalizeCaseStyle(String.valueOf(topCaseKw)));
        }
        if (formatOptions.get("indentation") instanceof Map<?, ?> ind) {
            Object il = ind.get("indent_length") != null ? ind.get("indent_length") : ind.get("indentLen");
            if (il != null) {
                opt.setIndentLen(parseInt(String.valueOf(il), opt.getIndentLen()));
            }
            Object ut = ind.get("use_tab") != null ? ind.get("use_tab") : ind.get("useTab");
            if (ut != null) {
                opt.setUseTab(parseBool(String.valueOf(ut), opt.isUseTab()));
            }
        }
        if (formatOptions.get("linenumber") instanceof Map<?, ?> ln) {
            Object en = ln.get("enabled") != null ? ln.get("enabled") : ln.get("linenumberEnabled");
            if (en != null) {
                opt.setLinenumberEnabled(parseBool(String.valueOf(en), opt.isLinenumberEnabled()));
            }
        }
        return opt;
    }

    private static String normalizeCaseStyle(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Upper";
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "upper", "uppercase" -> "Upper";
            case "lower", "lowercase" -> "Lower";
            case "initcap", "capitalize" -> "InitCap";
            case "nochange", "unchanged", "preserve" -> "NoChange";
            default -> raw.trim();
        };
    }

    public ApiResponse<Object> checkSyntax(Map<String, String> params) {
        String sql = firstNonBlank(params, "sqltext", "sql", "input_sql", "sqlText");
        EDbVendor vendor = resolveVendor(firstNonBlank(params, "dbvendor", "dbVendor", "database", "vendor"));
        DemoAstServiceSpi.SyntaxCheckResult res =
                demoAstServiceSpi.syntaxCheck(new DemoAstServiceSpi.AstSqlRequest(sql != null ? sql : "", vendor));
        return toSyntaxApiResponse(res);
    }

    public static ApiResponse<Object> toSyntaxApiResponse(DemoAstServiceSpi.SyntaxCheckResult res) {
        if (res == null) {
            return ApiResponse.error(500, "Syntax check failed");
        }
        boolean hasErrors = (res.getErrors() != null && !res.getErrors().isEmpty())
                || (res.getErrorInfos() != null && !res.getErrorInfos().isEmpty());
        if (!res.isValid() || hasErrors) {
            String errMsg = "SQL syntax error";
            if (res.getErrors() != null && !res.getErrors().isEmpty() && res.getErrors().get(0).getErrorMessage() != null) {
                errMsg = res.getErrors().get(0).getErrorMessage();
            } else if (res.getErrorInfos() != null && !res.getErrorInfos().isEmpty() && res.getErrorInfos().get(0) != null) {
                errMsg = res.getErrorInfos().get(0);
            }
            return new ApiResponse<>(500, res, errMsg, null);
        }
        return ApiResponse.ok(res);
    }

    // -------------------------------------------------------------------------
    // Internal Helpers
    // -------------------------------------------------------------------------

    private GraphLayoutSpi.GraphSliceRequest buildSliceRequest(
            DataFlow df,
            Map<String, String> params,
            boolean layout) {
        GraphLayoutSpi.GraphSliceRequest req = new GraphLayoutSpi.GraphSliceRequest();
        req.setDataFlow(df);
        req.setDatabase(blankToNull(params.get("database")));
        req.setSchema(blankToNull(params.get("schema")));
        req.setTable(blankToNull(firstNonBlank(params, "table", "tableName", "tableNamePattern")));
        req.setColumn(blankToNull(firstNonBlank(params, "column", "columnName")));

        String idsRaw = firstNonBlank(params, "dbObjIds", "dbObjId", "id", "selectedNodes");
        if (idsRaw != null && !idsRaw.isBlank()) {
            List<String> ids = new ArrayList<>();
            for (String s : idsRaw.replace("[", "").replace("]", "").replace("\"", "").split(",")) {
                if (!s.isBlank()) {
                    ids.add(s.trim());
                }
            }
            req.setDbObjIds(ids);
        }

        int hop = parseInt(firstNonBlank(params, "distance", "level", "hopDepth", "maxHopDepth"), 20);
        req.setMaxHopDepth(Math.max(1, hop));
        req.setIgnoreRecordSet(parseBool(params.get("ignoreRecordSet"), false));
        req.setShowConstantTable(parseBool(params.get("showConstantTable"), false));
        req.setLayoutResult(layout);
        req.setOrientation(Orientation.fromString(params.get("orientation")));
        req.setRelationTypes(parseRelationTypes(params.get("showRelationType"), false));
        return req;
    }

    private void normalizeLaidOutGraph(
            SqlflowJSON out,
            DataFlow df,
            String mode,
            boolean tableLevel,
            Orientation orientation,
            long spendMs) {
        out.setMode(mode);
        out.setTableLevel(tableLevel);
        out.setOrientation(orientation != null ? orientation : Orientation.LEFT_TO_RIGHT);
        out.setSpendTime(spendMs);
        if (out.getSqlflow() == null) {
            out.setSqlflow(df);
        }
        if (out.getSummary() == null) {
            out.setSummary(df.getSummary() != null ? df.getSummary() : lineageEngineSpi.summarize(df));
        }
        if (out.getElements() == null && out.getGraph() != null && out.getGraph().getElements() != null) {
            out.setElements(out.getGraph().getElements());
        }
        if (out.getElements() == null) {
            out.setElements(new SqlflowJSON.GraphElements(List.of(), List.of(), List.of()));
        }
        if (out.getGraph() == null) {
            SqlflowJSON.Graph nested = new SqlflowJSON.Graph();
            nested.setElements(out.getElements());
            nested.setWidth(out.getWidth() != null ? out.getWidth() : 200.0);
            nested.setHeight(out.getHeight() != null ? out.getHeight() : 120.0);
            nested.setOrientation(out.getOrientation());
            nested.setRelationshipIdMap(out.getRelationshipIdMap());
            nested.setListIdMap(out.getListIdMap());
            nested.setTooltip(out.getTooltip());
            nested.setSvg(out.getSvg());
            nested.setGraphML(out.getGraphML());
            out.setGraph(nested);
        } else if (out.getGraph().getElements() == null) {
            out.getGraph().setElements(out.getElements());
        }
    }

    private List<DbObjectPosition> buildPositionsFromCoordinatesJson(String coordsJson, String sqlText) {
        try {
            List<Coordinate> coords = objectMapper.readValue(coordsJson, new TypeReference<List<Coordinate>>() {});
            DbObjectPosition pos = new DbObjectPosition();
            pos.setSql(sqlText != null ? sqlText : "");
            pos.setIndex(0);
            List<DbObjectPosition.PositionPoint> pts = new ArrayList<>();
            if (coords != null) {
                for (Coordinate c : coords) {
                    if (c != null) {
                        pts.add(new DbObjectPosition.PositionPoint(
                                (int) Math.max(1L, c.getX()),
                                (int) Math.max(1L, c.getY())));
                    }
                }
            }
            pos.setPositions(pts);
            return List.of(pos);
        } catch (Exception e) {
            DbObjectPosition fallback = new DbObjectPosition();
            fallback.setSql(sqlText != null ? sqlText : "");
            fallback.setIndex(0);
            fallback.setPositions(List.of(new DbObjectPosition.PositionPoint(1, 1)));
            return List.of(fallback);
        }
    }

    private void auditUserSqlIfEnabled(String userId, String sql, String shortVendor) {
        if (!config.isRecordSql() || sql == null || sql.isBlank()) {
            return;
        }
        try {
            String effectiveUser = (userId == null || userId.isBlank())
                    ? SqlflowRuntimeConfig.DEFAULT_GUEST_USER_ID
                    : userId.trim();
            Instant now = Instant.now();
            Path logDir = config.getBaseSqlLogDir()
                    .resolve(URLEncoder.encode(effectiveUser, StandardCharsets.UTF_8))
                    .resolve(LOG_DAY_FMT.format(now));
            Files.createDirectories(logDir);
            String fileName = LOG_FILE_FMT.format(now) + "_" + shortVendor + ".sql";
            Files.writeString(logDir.resolve(fileName), sql, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("Failed to write SQL audit log: {}", e.getMessage());
        }
    }

    private String computeSessionId(String userId, EDbVendor vendor, Option option, String sql) {
        String key = (userId != null ? userId : "guest")
                + "|" + vendor.wireName()
                + "|simple=" + option.isSimpleOutput()
                + "|irs=" + option.isIgnoreRecordSet()
                + "|ifn=" + option.isSqlflowIgnoreFunction()
                + "|sct=" + option.isShowConstantTable()
                + "|er=" + option.isShowERDiagram()
                + "|call=" + option.isShowCallRelation()
                + "|rel=" + option.getFilterRelationTypes()
                + "|" + sql;
        return cryptoService.sha256Hex(key).substring(0, 32);
    }

    private static Set<RelationshipType> parseRelationTypes(String raw, boolean forceEr) {
        if (forceEr) {
            return Set.of(RelationshipType.er);
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Set<RelationshipType> set = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            if (!token.isBlank()) {
                try {
                    set.add(RelationshipType.fromString(token.trim()));
                } catch (Exception ignored) {
                }
            }
        }
        return set.isEmpty() ? null : Collections.unmodifiableSet(set);
    }

    public static String firstNonBlank(Map<String, String> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            String val = map.get(key);
            if (val != null && !val.isBlank()) {
                return val;
            }
        }
        return null;
    }

    private static String blankToNull(String val) {
        return (val == null || val.isBlank()) ? null : val.trim();
    }

    public static boolean parseBool(String raw, boolean defaultVal) {
        if (raw == null || raw.isBlank()) {
            return defaultVal;
        }
        String norm = raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(norm) || "1".equals(norm) || "yes".equals(norm)) {
            return true;
        }
        if ("false".equals(norm) || "0".equals(norm) || "no".equals(norm)) {
            return false;
        }
        return defaultVal;
    }

    public static int parseInt(String raw, int defaultVal) {
        if (raw == null || raw.isBlank()) {
            return defaultVal;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private static boolean matchesRegexSafe(String text, String pattern) {
        try {
            return text.matches(pattern);
        } catch (Exception e) {
            return false;
        }
    }
}
