package io.sqlflow.server.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.contracts.graph.SummaryInfo;
import io.sqlflow.contracts.lineage.Column;
import io.sqlflow.contracts.lineage.Coordinate;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.ErrorItem;
import io.sqlflow.contracts.lineage.Option;
import io.sqlflow.contracts.lineage.Orientation;
import io.sqlflow.contracts.lineage.QueryObject;
import io.sqlflow.contracts.lineage.Relationship;
import io.sqlflow.contracts.lineage.RelationshipElement;
import io.sqlflow.contracts.lineage.RelationshipType;
import io.sqlflow.contracts.lineage.SqlInfo;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.contracts.lineage.Transform;
import io.sqlflow.contracts.lineage.xml.DlineageXml;
import io.sqlflow.contracts.lineage.xml.DlineageXmlCodec;
import io.sqlflow.contracts.spi.LineageEngineSpi;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic standalone fallback implementation of {@link LineageEngineSpi} used when
 * {@code lineage-engine} is not yet on the classpath during Stage 2B module testing.
 */
public class FallbackLineageEngineSpi implements LineageEngineSpi {

    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile(
            "(?is)\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:TEMP(?:ORARY)?\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([\\w.\"`\\[\\]]+)\\s*(?:AS\\s+(SELECT\\b.*)|\\((.*)\\))");
    private static final Pattern CREATE_VIEW_PATTERN = Pattern.compile(
            "(?is)\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:MATERIALIZED\\s+)?VIEW\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([\\w.\"`\\[\\]]+)\\s*(?:\\(([^)]*)\\))?\\s+AS\\s+(SELECT\\b.*|WITH\\b.*)");
    private static final Pattern INSERT_SELECT_PATTERN = Pattern.compile(
            "(?is)\\bINSERT\\s+(?:OVERWRITE\\s+TABLE|INTO(?:\\s+TABLE)?)\\s+([\\w.\"`\\[\\]]+)\\s*(?:\\(([^)]*)\\))?\\s*(WITH\\b.*|SELECT\\b.*)");
    private static final Pattern UPDATE_SET_PATTERN = Pattern.compile(
            "(?is)\\bUPDATE\\s+([\\w.\"`\\[\\]]+)(?:\\s+(?:AS\\s+)?(\\w+))?\\s+SET\\s+(.*?)(?:\\bFROM\\s+(.*?))?(?:\\bWHERE\\s+(.*))?$");
    private static final Pattern WITH_CTE_HEADER = Pattern.compile(
            "(?is)^\\s*WITH\\s+(?:RECURSIVE\\s+)?");
    private static final Pattern TABLE_REF_PATTERN = Pattern.compile(
            "(?is)\\b(?:FROM|JOIN)\\s+([\\w.\"`\\[\\]]+)(?:\\s+(?:AS\\s+)?(?!WHERE\\b|ON\\b|INNER\\b|LEFT\\b|RIGHT\\b|FULL\\b|CROSS\\b|JOIN\\b|GROUP\\b|ORDER\\b|HAVING\\b|LIMIT\\b|UNION\\b|SET\\b)([\\w\"`\\[\\]]+))?");
    private static final Pattern CALL_PROC_PATTERN = Pattern.compile(
            "(?is)\\b(?:CALL|EXEC(?:UTE)?)\\s+([\\w.\"`\\[\\]]+)");
    private static final Pattern FK_INLINE_PATTERN = Pattern.compile(
            "(?is)\\bREFERENCES\\s+([\\w.\"`\\[\\]]+)\\s*\\(([^)]+)\\)");
    private static final Pattern FK_CONSTRAINT_PATTERN = Pattern.compile(
            "(?is)\\bFOREIGN\\s+KEY\\s*\\(([^)]+)\\)\\s*REFERENCES\\s+([\\w.\"`\\[\\]]+)\\s*\\(([^)]+)\\)");

    private final ObjectMapper objectMapper;

    public FallbackLineageEngineSpi(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public LineageAnalysisResult analyze(LineageAnalysisRequest request) {
        Option option = request != null && request.getOption() != null ? request.getOption() : new Option();
        EDbVendor vendor = option.getVendor() != null ? option.getVendor() : EDbVendor.dbvoracle;
        AtomicLong idSeq = new AtomicLong(Math.max(1L, option.getStartId() + 1L));

        Map<String, DbObject> objectsByKey = new LinkedHashMap<>();
        List<Relationship> relationships = new ArrayList<>();
        List<ErrorItem> errors = new ArrayList<>();

        // Seed initial catalog if provided
        if (request != null && request.getInitialCatalog() != null) {
            seedCatalog(request.getInitialCatalog(), objectsByKey, idSeq);
        }
        if (request != null && request.getSqlenvSnapshot() != null && request.getSqlenvSnapshot().length > 0) {
            try {
                SqlflowCatalog snap = objectMapper.readValue(request.getSqlenvSnapshot(), SqlflowCatalog.class);
                seedCatalog(snap, objectsByKey, idSeq);
            } catch (Exception ignored) {
                // ignore opaque binary snapshots
            }
        }

        List<SqlInfo> units = new ArrayList<>();
        if (request != null) {
            if (request.getMetadataJson() != null && !request.getMetadataJson().isBlank()) {
                try {
                    SqlflowCatalog metaCat = objectMapper.readValue(request.getMetadataJson(), SqlflowCatalog.class);
                    seedCatalog(metaCat, objectsByKey, idSeq);
                    if (metaCat.getQueries() != null) {
                        int idx = 0;
                        for (QueryObject q : metaCat.getQueries()) {
                            if (q != null && q.getSourceCode() != null && !q.getSourceCode().isBlank()) {
                                SqlInfo info = new SqlInfo(
                                        q.getSourceCode(),
                                        q.getName() != null ? q.getName() : "query_" + idx + ".sql",
                                        null,
                                        vendor.name());
                                info.setOriginIndex(idx++);
                                units.add(info);
                            }
                        }
                    }
                } catch (Exception e) {
                    errors.add(new ErrorItem("MetadataParseError", "Invalid metadata JSON: " + e.getMessage()));
                }
            }
            if (request.getSqlUnits() != null && !request.getSqlUnits().isEmpty()) {
                units.addAll(request.getSqlUnits());
            } else if (request.getSqlText() != null && !request.getSqlText().isBlank()) {
                String trimmed = request.getSqlText().trim();
                if (trimmed.startsWith("{") && trimmed.contains("\"databases\"")) {
                    try {
                        SqlflowCatalog inlineCat = objectMapper.readValue(trimmed, SqlflowCatalog.class);
                        seedCatalog(inlineCat, objectsByKey, idSeq);
                        if (inlineCat.getQueries() != null) {
                            int idx = 0;
                            for (QueryObject q : inlineCat.getQueries()) {
                                if (q != null && q.getSourceCode() != null) {
                                    SqlInfo info = new SqlInfo(q.getSourceCode(), q.getName(), null, vendor.name());
                                    info.setOriginIndex(idx++);
                                    units.add(info);
                                }
                            }
                        }
                    } catch (Exception ignored) {
                        SqlInfo single = new SqlInfo(request.getSqlText(), "input.sql", null, vendor.name());
                        single.setOriginIndex(0);
                        units.add(single);
                    }
                } else {
                    SqlInfo single = new SqlInfo(request.getSqlText(), "input.sql", null, vendor.name());
                    single.setOriginIndex(0);
                    units.add(single);
                }
            }
        }

        int rsCounter = 1;
        for (int uIdx = 0; uIdx < units.size(); uIdx++) {
            SqlInfo unit = units.get(uIdx);
            if (unit == null || unit.getSql() == null || unit.getSql().isBlank()) {
                continue;
            }
            String unitHash = unit.getHashCode() != null
                    ? unit.getHashCode()
                    : String.valueOf(unit.getOriginIndex() != null ? unit.getOriginIndex() : uIdx);
            List<StatementSpan> stmts = splitStatementsWithLines(unit.getSql(), request != null ? request.getDelimiter() : null);
            for (StatementSpan span : stmts) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("Lineage analysis interrupted");
                }
                rsCounter = analyzeStatement(
                        span,
                        unitHash,
                        vendor,
                        option,
                        objectsByKey,
                        relationships,
                        errors,
                        idSeq,
                        rsCounter);
            }
        }

        // Filter relationships if option.filterRelationTypes is non-empty
        if (option.getFilterRelationTypes() != null && !option.getFilterRelationTypes().isEmpty()) {
            Set<String> allowed = new LinkedHashSet<>();
            for (String ft : option.getFilterRelationTypes()) {
                if (ft != null) {
                    allowed.add(ft.toLowerCase(Locale.ROOT));
                }
            }
            relationships.removeIf(r -> r.getType() == null || !allowed.contains(r.getType().toLowerCase(Locale.ROOT)));
        }

        DataFlow df = new DataFlow();
        df.setDbvendor(vendor.name());
        df.setOrientation(Orientation.LEFT_TO_RIGHT);
        df.setDbobjs(CatalogUtils.buildCatalog(option.getDefaultServer(), vendor.name(), new ArrayList<>(objectsByKey.values())));
        df.setRelationships(relationships);
        if (!errors.isEmpty()) {
            df.setErrors(errors);
        }
        SummaryInfo summary = CatalogUtils.computeSummary(df);
        df.setSummary(summary);

        DlineageXml xml = CatalogUtils.toDlineageXml(df);
        String xmlStr = DlineageXmlCodec.toXml(xml, true);
        byte[] snapshotBytes = null;
        try {
            snapshotBytes = objectMapper.writeValueAsBytes(df.getDbobjs());
        } catch (Exception ignored) {
            // optional snapshot
        }

        LineageAnalysisResult res = new LineageAnalysisResult(df, xml, xmlStr, snapshotBytes, summary);
        res.setMode(DegradationMode.FULL);
        if (!errors.isEmpty()) {
            res.setErrors(errors);
        }
        return res;
    }

    @Override
    public DataFlow mergeDataFlows(List<DataFlow> batches, Option option) {
        DataFlow merged = new DataFlow();
        EDbVendor vendor = option != null && option.getVendor() != null ? option.getVendor() : EDbVendor.dbvoracle;
        merged.setDbvendor(vendor.name());
        merged.setOrientation(Orientation.LEFT_TO_RIGHT);

        Map<String, DbObject> objectsByKey = new LinkedHashMap<>();
        List<Relationship> allRels = new ArrayList<>();
        List<ErrorItem> allErrors = new ArrayList<>();
        AtomicLong idSeq = new AtomicLong(1L);

        if (batches != null) {
            for (DataFlow batch : batches) {
                if (batch == null) {
                    continue;
                }
                if (batch.getDbvendor() != null) {
                    merged.setDbvendor(batch.getDbvendor());
                }
                Map<String, String> idRemap = new LinkedHashMap<>();
                for (DbObject obj : CatalogUtils.collectAllDbObjects(batch)) {
                    String canonKey = canonicalObjectKey(obj.getDatabase(), obj.getSchema(), obj.getName(), obj.getType());
                    DbObject target = objectsByKey.get(canonKey);
                    if (target == null) {
                        target = copyDbObject(obj, String.valueOf(idSeq.getAndIncrement()), idSeq);
                        objectsByKey.put(canonKey, target);
                    } else {
                        mergeColumns(target, obj.getColumns(), idSeq, idRemap);
                    }
                    if (obj.getId() != null) {
                        idRemap.put(obj.getId(), target.getId());
                    }
                    if (obj.getColumns() != null && target.getColumns() != null) {
                        for (Column srcCol : obj.getColumns()) {
                            Column dstCol = findColumn(target, srcCol.getName());
                            if (srcCol.getId() != null && dstCol != null) {
                                idRemap.put(srcCol.getId(), dstCol.getId());
                            }
                        }
                    }
                }
                if (batch.getRelationships() != null) {
                    for (Relationship rel : batch.getRelationships()) {
                        Relationship copy = remapRelationship(rel, idRemap, String.valueOf(idSeq.getAndIncrement()));
                        allRels.add(copy);
                    }
                }
                if (batch.getErrors() != null) {
                    allErrors.addAll(batch.getErrors());
                }
            }
        }

        merged.setDbobjs(CatalogUtils.buildCatalog(
                option != null ? option.getDefaultServer() : null,
                merged.getDbvendor(),
                new ArrayList<>(objectsByKey.values())));
        merged.setRelationships(allRels);
        if (!allErrors.isEmpty()) {
            merged.setErrors(allErrors);
        }
        merged.setSummary(CatalogUtils.computeSummary(merged));
        return merged;
    }

    @Override
    public SummaryInfo summarize(DataFlow dataFlow) {
        return CatalogUtils.computeSummary(dataFlow);
    }

    @Override
    public List<String> splitLargeSql(String sqlText, EDbVendor vendor, int maxBytesPerChunk) {
        if (sqlText == null || sqlText.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(64, maxBytesPerChunk);
        byte[] allBytes = sqlText.getBytes(StandardCharsets.UTF_8);
        if (allBytes.length <= limit) {
            return List.of(sqlText);
        }

        List<String> statements = splitRespectingBlocksAndQuotes(sqlText);
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentBytes = 0;

        for (String stmt : statements) {
            int stmtBytes = stmt.getBytes(StandardCharsets.UTF_8).length;
            if (currentBytes > 0 && currentBytes + stmtBytes > limit) {
                chunks.add(current.toString());
                current.setLength(0);
                currentBytes = 0;
            }
            current.append(stmt);
            currentBytes += stmtBytes;
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private List<String> splitRespectingBlocksAndQuotes(String sql) {
        List<String> out = new ArrayList<>();
        int len = sql.length();
        int start = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        int beginDepth = 0;

        for (int i = 0; i < len; i++) {
            char c = sql.charAt(i);
            char next = (i + 1 < len) ? sql.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inSingle) {
                if (c == '\'' && next == '\'') {
                    i++;
                } else if (c == '\'') {
                    inSingle = false;
                }
                continue;
            }
            if (inDouble) {
                if (c == '"') {
                    inDouble = false;
                }
                continue;
            }

            if (c == '-' && next == '-') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                continue;
            }
            if (c == '"') {
                inDouble = true;
                continue;
            }

            if (Character.isLetter(c)) {
                int wStart = i;
                while (i + 1 < len && (Character.isLetterOrDigit(sql.charAt(i + 1)) || sql.charAt(i + 1) == '_')) {
                    i++;
                }
                String word = sql.substring(wStart, i + 1).toUpperCase(Locale.ROOT);
                if ("BEGIN".equals(word)) {
                    beginDepth++;
                } else if ("END".equals(word) && beginDepth > 0) {
                    // Check not END CASE / END IF / END LOOP unless paired; conservatively decrement on END;
                    int look = i + 1;
                    while (look < len && Character.isWhitespace(sql.charAt(look))) {
                        look++;
                    }
                    if (look < len && sql.charAt(look) == ';') {
                        beginDepth--;
                    }
                }
                continue;
            }

            if (c == ';' && beginDepth == 0) {
                int endIdx = i + 1;
                while (endIdx < len && (sql.charAt(endIdx) == ' ' || sql.charAt(endIdx) == '\t' || sql.charAt(endIdx) == '\r')) {
                    endIdx++;
                }
                if (endIdx < len && sql.charAt(endIdx) == '\n') {
                    endIdx++;
                }
                out.add(sql.substring(start, endIdx));
                start = endIdx;
                i = endIdx - 1;
            }
        }
        if (start < len) {
            out.add(sql.substring(start));
        }
        return out;
    }

    private void seedCatalog(SqlflowCatalog catalog, Map<String, DbObject> objectsByKey, AtomicLong idSeq) {
        for (DbObject obj : CatalogUtils.collectAllDbObjects(catalog)) {
            DbObject copy = copyDbObject(
                    obj,
                    obj.getId() != null ? obj.getId() : String.valueOf(idSeq.getAndIncrement()),
                    idSeq);
            if (copy.getFromDDL() == null) {
                copy.setFromDDL(Boolean.TRUE);
            }
            String key = canonicalObjectKey(copy.getDatabase(), copy.getSchema(), copy.getName(), copy.getType());
            objectsByKey.putIfAbsent(key, copy);
        }
    }

    private int analyzeStatement(
            StatementSpan span,
            String unitHash,
            EDbVendor vendor,
            Option option,
            Map<String, DbObject> objectsByKey,
            List<Relationship> relationships,
            List<ErrorItem> errors,
            AtomicLong idSeq,
            int rsCounter) {
        String raw = span.text().trim();
        if (raw.isEmpty()) {
            return rsCounter;
        }
        String cleaned = stripComments(raw).trim();
        if (cleaned.isEmpty()) {
            return rsCounter;
        }
        String sqlHash = md5Hex(cleaned);

        // Basic syntax check for blatantly broken SQL markers
        String upper = cleaned.toUpperCase(Locale.ROOT);
        if (upper.startsWith("SELEC ") || upper.startsWith("FORM ") || upper.contains(" FROM WHERE ")) {
            errors.add(new ErrorItem(
                    "SyntaxError",
                    "Syntax error near '" + cleaned.substring(0, Math.min(24, cleaned.length())) + "'",
                    Coordinate.span(span.startLine(), 1, span.endLine(), Math.max(2, cleaned.length()), unitHash)));
            return rsCounter;
        }

        // Procedure call
        Matcher callMatcher = CALL_PROC_PATTERN.matcher(cleaned);
        if (callMatcher.find() && (upper.startsWith("CALL ") || upper.startsWith("EXEC"))) {
            String procRaw = callMatcher.group(1);
            DbObject caller = getOrCreateObject("ANONYMOUS_BLOCK", "procedure", option, objectsByKey, idSeq, span, unitHash);
            DbObject callee = getOrCreateObject(procRaw, "procedure", option, objectsByKey, idSeq, span, unitHash);
            Relationship callRel = new Relationship(String.valueOf(idSeq.getAndIncrement()), RelationshipType.call);
            callRel.setCaller(new RelationshipElement(caller.getId(), caller.getName(), caller.getId(), caller.getName()));
            callRel.setCallees(List.of(new RelationshipElement(callee.getId(), callee.getName(), callee.getId(), callee.getName())));
            callRel.setSqlHash(sqlHash);
            relationships.add(callRel);
            return rsCounter;
        }

        // CREATE TABLE ...
        Matcher ctMatcher = CREATE_TABLE_PATTERN.matcher(cleaned);
        if (ctMatcher.find() && upper.startsWith("CREATE")) {
            String tableName = ctMatcher.group(1);
            String asSelect = ctMatcher.group(2);
            String bodyCols = ctMatcher.group(3);
            DbObject targetTable = getOrCreateObject(tableName, "table", option, objectsByKey, idSeq, span, unitHash);
            targetTable.setFromDDL(Boolean.TRUE);
            targetTable.setCreatedInSql(Boolean.TRUE);
            if (bodyCols != null) {
                parseDdlColumnsAndConstraints(bodyCols, targetTable, option, objectsByKey, relationships, idSeq, span, unitHash, sqlHash);
            } else if (asSelect != null) {
                targetTable.setIsTarget("true");
                analyzeQueryIntoTarget(asSelect, targetTable, null, "create", option, objectsByKey, relationships, idSeq, span, unitHash, sqlHash);
            }
            return rsCounter;
        }

        // CREATE VIEW ... AS SELECT ...
        Matcher cvMatcher = CREATE_VIEW_PATTERN.matcher(cleaned);
        if (cvMatcher.find() && upper.startsWith("CREATE")) {
            String viewName = cvMatcher.group(1);
            String explicitCols = cvMatcher.group(2);
            String selectSql = cvMatcher.group(3);
            DbObject viewObj = getOrCreateObject(viewName, "view", option, objectsByKey, idSeq, span, unitHash);
            viewObj.setIsView("true");
            viewObj.setIsTarget("true");
            viewObj.setFromDDL(Boolean.TRUE);
            List<String> targetColNames = parseCommaIdentList(explicitCols);
            analyzeQueryIntoTarget(selectSql, viewObj, targetColNames, "create_view", option, objectsByKey, relationships, idSeq, span, unitHash, sqlHash);
            return rsCounter;
        }

        // INSERT INTO target [(cols)] SELECT/WITH ...
        Matcher insMatcher = INSERT_SELECT_PATTERN.matcher(cleaned);
        if (insMatcher.find() && upper.startsWith("INSERT")) {
            String targetName = insMatcher.group(1);
            String explicitCols = insMatcher.group(2);
            String queryPart = insMatcher.group(3);
            DbObject targetObj = getOrCreateObject(targetName, "table", option, objectsByKey, idSeq, span, unitHash);
            targetObj.setIsTarget("true");
            List<String> targetColNames = parseCommaIdentList(explicitCols);
            analyzeQueryIntoTarget(queryPart, targetObj, targetColNames, "insert", option, objectsByKey, relationships, idSeq, span, unitHash, sqlHash);
            return rsCounter;
        }

        // UPDATE target SET col = expr [FROM ...] [WHERE ...]
        Matcher updMatcher = UPDATE_SET_PATTERN.matcher(cleaned);
        if (updMatcher.find() && upper.startsWith("UPDATE")) {
            String targetName = updMatcher.group(1);
            String targetAlias = updMatcher.group(2);
            String setPart = updMatcher.group(3);
            String fromPart = updMatcher.group(4);
            String wherePart = updMatcher.group(5);
            DbObject targetObj = getOrCreateObject(targetName, "table", option, objectsByKey, idSeq, span, unitHash);
            targetObj.setIsTarget("true");
            Map<String, DbObject> aliasMap = new LinkedHashMap<>();
            aliasMap.put(targetObj.getName().toUpperCase(Locale.ROOT), targetObj);
            if (targetAlias != null && !targetAlias.isBlank()) {
                aliasMap.put(CatalogUtils.stripQuotes(targetAlias).toUpperCase(Locale.ROOT), targetObj);
            }
            if (fromPart != null) {
                extractSourceTables(fromPart, option, objectsByKey, aliasMap, new LinkedHashMap<>(), idSeq, span, unitHash);
            }
            for (String assignment : splitTopLevelCommas(setPart)) {
                int eq = assignment.indexOf('=');
                if (eq > 0) {
                    String lhs = CatalogUtils.stripQuotes(assignment.substring(0, eq).trim());
                    if (lhs.contains(".")) {
                        lhs = lhs.substring(lhs.lastIndexOf('.') + 1);
                    }
                    String rhs = assignment.substring(eq + 1).trim();
                    Column targetCol = ensureColumn(targetObj, lhs, null, idSeq, span, unitHash);
                    linkExpressionToTarget(rhs, targetObj, targetCol, aliasMap, "update", option, relationships, idSeq, span, unitHash, sqlHash);
                }
            }
            if (wherePart != null && !option.isSimpleOutput()) {
                addIndirectWhereRelationships(wherePart, targetObj, aliasMap, "update", relationships, idSeq, span, unitHash, sqlHash);
            }
            return rsCounter;
        }

        // WITH ... SELECT ... or standalone SELECT ...
        if (upper.startsWith("SELECT") || upper.startsWith("WITH")) {
            if (option.isIgnoreTopSelect()) {
                return rsCounter;
            }
            String rsName = "RS-" + (rsCounter++);
            DbObject rsObj = new DbObject(String.valueOf(idSeq.getAndIncrement()), rsName, "resultset");
            rsObj.setSubType("select_list");
            rsObj.setServer(option.getDefaultServer());
            rsObj.setDatabase(option.getDefaultDatabase() != null ? option.getDefaultDatabase() : "DEFAULT");
            rsObj.setSchema(option.getDefaultSchema() != null ? option.getDefaultSchema() : "DEFAULT");
            rsObj.setCoordinates(Coordinate.span(span.startLine(), 1, span.endLine(), 10, unitHash));
            if (!option.isIgnoreRecordSet()) {
                objectsByKey.put(canonicalObjectKey(rsObj.getDatabase(), rsObj.getSchema(), rsObj.getName(), rsObj.getType()), rsObj);
            }
            analyzeQueryIntoTarget(cleaned, rsObj, null, "select", option, objectsByKey, relationships, idSeq, span, unitHash, sqlHash);
        }

        return rsCounter;
    }

    private void analyzeQueryIntoTarget(
            String querySql,
            DbObject targetObj,
            List<String> explicitTargetCols,
            String effectType,
            Option option,
            Map<String, DbObject> objectsByKey,
            List<Relationship> relationships,
            AtomicLong idSeq,
            StatementSpan span,
            String unitHash,
            String sqlHash) {
        Map<String, DbObject> cteMap = new LinkedHashMap<>();
        String mainSelect = extractCtes(querySql, option, objectsByKey, cteMap, relationships, idSeq, span, unitHash, sqlHash);

        // Handle UNION / UNION ALL branches
        List<String> unionBranches = splitTopLevelUnion(mainSelect);
        for (String branch : unionBranches) {
            analyzeSingleSelectBlock(branch, targetObj, explicitTargetCols, effectType, option, objectsByKey, cteMap, relationships, idSeq, span, unitHash, sqlHash);
        }
    }

    private String extractCtes(
            String querySql,
            Option option,
            Map<String, DbObject> objectsByKey,
            Map<String, DbObject> cteMap,
            List<Relationship> relationships,
            AtomicLong idSeq,
            StatementSpan span,
            String unitHash,
            String sqlHash) {
        Matcher m = WITH_CTE_HEADER.matcher(querySql);
        if (!m.find()) {
            return querySql;
        }
        int pos = m.end();
        int len = querySql.length();
        while (pos < len) {
            while (pos < len && Character.isWhitespace(querySql.charAt(pos))) {
                pos++;
            }
            int nameStart = pos;
            while (pos < len && (Character.isLetterOrDigit(querySql.charAt(pos)) || "_\"`[]".indexOf(querySql.charAt(pos)) >= 0)) {
                pos++;
            }
            if (pos == nameStart) {
                break;
            }
            String cteName = CatalogUtils.stripQuotes(querySql.substring(nameStart, pos));
            while (pos < len && Character.isWhitespace(querySql.charAt(pos))) {
                pos++;
            }
            List<String> cteColList = null;
            if (pos < len && querySql.charAt(pos) == '(') {
                int closeCols = findMatchingParen(querySql, pos);
                if (closeCols > pos) {
                    cteColList = parseCommaIdentList(querySql.substring(pos + 1, closeCols));
                    pos = closeCols + 1;
                }
            }
            while (pos < len && Character.isWhitespace(querySql.charAt(pos))) {
                pos++;
            }
            if (pos + 2 <= len && querySql.regionMatches(true, pos, "AS", 0, 2)) {
                pos += 2;
            }
            while (pos < len && Character.isWhitespace(querySql.charAt(pos))) {
                pos++;
            }
            if (pos >= len || querySql.charAt(pos) != '(') {
                break;
            }
            int closeBody = findMatchingParen(querySql, pos);
            if (closeBody < 0) {
                break;
            }
            String cteQuery = querySql.substring(pos + 1, closeBody);
            DbObject cteObj = new DbObject(String.valueOf(idSeq.getAndIncrement()), cteName, "resultset");
            cteObj.setSubType("with_cte");
            cteObj.setDatabase(option.getDefaultDatabase() != null ? option.getDefaultDatabase() : "DEFAULT");
            cteObj.setSchema(option.getDefaultSchema() != null ? option.getDefaultSchema() : "DEFAULT");
            cteObj.setCoordinates(Coordinate.span(span.startLine(), 1, span.endLine(), 10, unitHash));
            if (!option.isIgnoreRecordSet()) {
                objectsByKey.put(canonicalObjectKey(cteObj.getDatabase(), cteObj.getSchema(), cteObj.getName(), cteObj.getType()), cteObj);
            }
            cteMap.put(cteName.toUpperCase(Locale.ROOT), cteObj);
            analyzeQueryIntoTarget(cteQuery, cteObj, cteColList, "select", option, objectsByKey, relationships, idSeq, span, unitHash, sqlHash);

            pos = closeBody + 1;
            while (pos < len && Character.isWhitespace(querySql.charAt(pos))) {
                pos++;
            }
            if (pos < len && querySql.charAt(pos) == ',') {
                pos++;
            } else {
                break;
            }
        }
        return pos < len ? querySql.substring(pos).trim() : querySql;
    }

    private void analyzeSingleSelectBlock(
            String selectSql,
            DbObject targetObj,
            List<String> explicitTargetCols,
            String effectType,
            Option option,
            Map<String, DbObject> objectsByKey,
            Map<String, DbObject> cteMap,
            List<Relationship> relationships,
            AtomicLong idSeq,
            StatementSpan span,
            String unitHash,
            String sqlHash) {
        String trimmed = selectSql.trim();
        if (!trimmed.toUpperCase(Locale.ROOT).startsWith("SELECT")) {
            return;
        }
        int fromIdx = findTopLevelKeyword(trimmed, "FROM");
        String selectListRaw;
        String afterFrom = "";
        if (fromIdx > 0) {
            selectListRaw = trimmed.substring(6, fromIdx).trim();
            afterFrom = trimmed.substring(fromIdx + 4).trim();
        } else {
            selectListRaw = trimmed.substring(6).trim();
        }
        selectListRaw = selectListRaw.replaceFirst("(?i)^(?:DISTINCT|ALL)\\s+", "").trim();

        int whereIdx = findTopLevelKeyword(afterFrom, "WHERE");
        int groupIdx = findTopLevelKeyword(afterFrom, "GROUP");
        int orderIdx = findTopLevelKeyword(afterFrom, "ORDER");

        int fromEnd = afterFrom.length();
        if (whereIdx >= 0 && whereIdx < fromEnd) {
            fromEnd = whereIdx;
        }
        if (groupIdx >= 0 && groupIdx < fromEnd) {
            fromEnd = groupIdx;
        }
        if (orderIdx >= 0 && orderIdx < fromEnd) {
            fromEnd = orderIdx;
        }
        String fromClause = afterFrom.substring(0, fromEnd).trim();
        String filterTail = fromEnd < afterFrom.length() ? afterFrom.substring(fromEnd).trim() : "";

        Map<String, DbObject> aliasMap = new LinkedHashMap<>(cteMap);
        List<DbObject> orderedSources = extractSourceTables(fromClause, option, objectsByKey, aliasMap, cteMap, idSeq, span, unitHash);

        List<String> selectItems = splitTopLevelCommas(selectListRaw);
        for (int i = 0; i < selectItems.size(); i++) {
            String item = selectItems.get(i).trim();
            if (item.isEmpty()) {
                continue;
            }
            if ("*".equals(item) || item.endsWith(".*")) {
                List<DbObject> starSources = orderedSources;
                if (item.endsWith(".*")) {
                    String prefix = CatalogUtils.stripQuotes(item.substring(0, item.length() - 2));
                    DbObject resolved = aliasMap.get(prefix.toUpperCase(Locale.ROOT));
                    if (resolved != null) {
                        starSources = List.of(resolved);
                    }
                }
                for (DbObject srcObj : starSources) {
                    if (srcObj.getColumns() != null && !srcObj.getColumns().isEmpty()) {
                        for (Column srcCol : new ArrayList<>(srcObj.getColumns())) {
                            if ("system".equalsIgnoreCase(srcCol.getSource())) {
                                continue;
                            }
                            Column tgtCol = ensureColumn(targetObj, srcCol.getName(), srcCol.getDataType(), idSeq, span, unitHash);
                            addColumnRelationship(srcObj, srcCol, targetObj, tgtCol, RelationshipType.fdd, effectType, "*", option, relationships, idSeq, sqlHash);
                        }
                    } else {
                        Column srcCol = ensureColumn(srcObj, "*", null, idSeq, span, unitHash);
                        srcCol.setStar(Boolean.TRUE);
                        Column tgtCol = ensureColumn(targetObj, "*", null, idSeq, span, unitHash);
                        tgtCol.setStar(Boolean.TRUE);
                        addColumnRelationship(srcObj, srcCol, targetObj, tgtCol, RelationshipType.fdd, effectType, item, option, relationships, idSeq, sqlHash);
                    }
                }
                continue;
            }

            ExprAndAlias parsed = splitSelectAlias(item, i);
            String targetColName = (explicitTargetCols != null && i < explicitTargetCols.size())
                    ? explicitTargetCols.get(i)
                    : parsed.alias();
            Column tgtCol = ensureColumn(targetObj, targetColName, null, idSeq, span, unitHash);
            linkExpressionToTarget(parsed.expr(), targetObj, tgtCol, aliasMap, orderedSources, effectType, option, relationships, idSeq, span, unitHash, sqlHash);
        }

        // JOIN & WHERE indirect relationships (fddi / join)
        if (!filterTail.isEmpty() && !option.isSimpleOutput()) {
            addIndirectWhereRelationships(filterTail, targetObj, aliasMap, effectType, relationships, idSeq, span, unitHash, sqlHash);
        }
        if (option.isShowJoin()) {
            extractJoinRelationships(fromClause, aliasMap, relationships, idSeq, span, unitHash, sqlHash);
        }
    }

    private List<DbObject> extractSourceTables(
            String fromClause,
            Option option,
            Map<String, DbObject> objectsByKey,
            Map<String, DbObject> aliasMap,
            Map<String, DbObject> cteMap,
            AtomicLong idSeq,
            StatementSpan span,
            String unitHash) {
        List<DbObject> sources = new ArrayList<>();
        if (fromClause == null || fromClause.isBlank()) {
            return sources;
        }
        // Check comma-separated FROM items if no JOIN keyword or mixed
        Matcher m = TABLE_REF_PATTERN.matcher("FROM " + fromClause);
        while (m.find()) {
            String rawTable = m.group(1);
            String rawAlias = m.group(2);
            registerSourceTableRef(rawTable, rawAlias, option, objectsByKey, aliasMap, cteMap, sources, idSeq, span, unitHash);
        }
        // Also handle comma-joined tables: FROM a a1, b b1 WHERE ...
        int joinPos = findTopLevelKeyword(fromClause, "JOIN");
        String commaHead = joinPos > 0 ? fromClause.substring(0, joinPos) : fromClause;
        List<String> commaParts = splitTopLevelCommas(commaHead);
        if (commaParts.size() > 1) {
            for (int i = 1; i < commaParts.size(); i++) {
                String part = commaParts.get(i).trim();
                if (!part.isEmpty() && !part.startsWith("(")) {
                    String[] toks = part.split("\\s+");
                    String tbl = toks[0];
                    String al = toks.length >= 2 && !"AS".equalsIgnoreCase(toks[1])
                            ? toks[1]
                            : (toks.length >= 3 && "AS".equalsIgnoreCase(toks[1]) ? toks[2] : null);
                    registerSourceTableRef(tbl, al, option, objectsByKey, aliasMap, cteMap, sources, idSeq, span, unitHash);
                }
            }
        }
        return sources;
    }

    private void registerSourceTableRef(
            String rawTable,
            String rawAlias,
            Option option,
            Map<String, DbObject> objectsByKey,
            Map<String, DbObject> aliasMap,
            Map<String, DbObject> cteMap,
            List<DbObject> sources,
            AtomicLong idSeq,
            StatementSpan span,
            String unitHash) {
        String cleanName = CatalogUtils.stripQuotes(rawTable);
        if (cleanName == null || cleanName.isEmpty() || "LATERAL".equalsIgnoreCase(cleanName) || "UNNEST".equalsIgnoreCase(cleanName)) {
            return;
        }
        DbObject resolved = cteMap.get(cleanName.toUpperCase(Locale.ROOT));
        if (resolved == null) {
            resolved = getOrCreateObject(rawTable, "table", option, objectsByKey, idSeq, span, unitHash);
        }
        if (!sources.contains(resolved)) {
            sources.add(resolved);
        }
        aliasMap.put(resolved.getName().toUpperCase(Locale.ROOT), resolved);
        if (rawAlias != null && !rawAlias.isBlank()) {
            aliasMap.put(CatalogUtils.stripQuotes(rawAlias).toUpperCase(Locale.ROOT), resolved);
        }
    }

    private void linkExpressionToTarget(
            String expr,
            DbObject targetObj,
            Column targetCol,
            Map<String, DbObject> aliasMap,
            String effectType,
            Option option,
            List<Relationship> relationships,
            AtomicLong idSeq,
            StatementSpan span,
            String unitHash,
            String sqlHash) {
        linkExpressionToTarget(
                expr,
                targetObj,
                targetCol,
                aliasMap,
                new ArrayList<>(new LinkedHashSet<>(aliasMap.values())),
                effectType,
                option,
                relationships,
                idSeq,
                span,
                unitHash,
                sqlHash);
    }

    private void linkExpressionToTarget(
            String expr,
            DbObject targetObj,
            Column targetCol,
            Map<String, DbObject> aliasMap,
            List<DbObject> orderedSources,
            String effectType,
            Option option,
            List<Relationship> relationships,
            AtomicLong idSeq,
            StatementSpan span,
            String unitHash,
            String sqlHash) {
        List<ColToken> colRefs = extractColumnRefsFromExpr(expr);
        List<RelationshipElement> sourceElements = new ArrayList<>();
        for (ColToken tok : colRefs) {
            DbObject srcObj = null;
            if (tok.qualifier() != null) {
                srcObj = aliasMap.get(tok.qualifier().toUpperCase(Locale.ROOT));
            } else if (orderedSources.size() == 1) {
                srcObj = orderedSources.get(0);
            } else {
                for (DbObject candidate : orderedSources) {
                    if (findColumn(candidate, tok.column()) != null) {
                        srcObj = candidate;
                        break;
                    }
                }
                if (srcObj == null && !orderedSources.isEmpty()) {
                    srcObj = orderedSources.get(0);
                }
            }
            if (srcObj != null && srcObj != targetObj) {
                Column srcCol = ensureColumn(srcObj, tok.column(), null, idSeq, span, unitHash);
                RelationshipElement srcEl = new RelationshipElement(
                        srcCol.getId(), srcCol.getName(), srcObj.getId(), srcObj.getName());
                srcEl.setCoordinates(srcCol.getCoordinates());
                if (option.isTransform()) {
                    srcEl.setTransforms(List.of(new Transform("expression", expr)));
                }
                sourceElements.add(srcEl);
            }
        }
        if (!sourceElements.isEmpty()) {
            Relationship rel = new Relationship(String.valueOf(idSeq.getAndIncrement()), RelationshipType.fdd);
            rel.setEffectType(effectType);
            rel.setSqlHash(sqlHash);
            RelationshipElement tgtEl = new RelationshipElement(
                    targetCol.getId(), targetCol.getName(), targetObj.getId(), targetObj.getName());
            tgtEl.setCoordinates(targetCol.getCoordinates());
            if (option.isTransform()) {
                tgtEl.setTransforms(List.of(new Transform("expression", expr)));
            }
            rel.setTarget(tgtEl);
            rel.setSources(sourceElements);
            relationships.add(rel);
        }
    }

    private void addIndirectWhereRelationships(
            String filterSql,
            DbObject targetObj,
            Map<String, DbObject> aliasMap,
            String effectType,
            List<Relationship> relationships,
            AtomicLong idSeq,
            StatementSpan span,
            String unitHash,
            String sqlHash) {
        List<ColToken> refs = extractColumnRefsFromExpr(filterSql);
        if (refs.isEmpty()) {
            return;
        }
        Column targetPseudo = ensureRelationRowsColumn(targetObj, idSeq);
        List<RelationshipElement> sources = new ArrayList<>();
        for (ColToken tok : refs) {
            DbObject srcObj = tok.qualifier() != null
                    ? aliasMap.get(tok.qualifier().toUpperCase(Locale.ROOT))
                    : (aliasMap.size() == 1 ? aliasMap.values().iterator().next() : null);
            if (srcObj != null && srcObj != targetObj) {
                Column srcCol = ensureColumn(srcObj, tok.column(), null, idSeq, span, unitHash);
                sources.add(new RelationshipElement(srcCol.getId(), srcCol.getName(), srcObj.getId(), srcObj.getName()));
            }
        }
        if (!sources.isEmpty()) {
            Relationship fddi = new Relationship(String.valueOf(idSeq.getAndIncrement()), RelationshipType.fddi);
            fddi.setEffectType(effectType);
            fddi.setClause("WHERE");
            fddi.setSqlHash(sqlHash);
            fddi.setTarget(new RelationshipElement(
                    targetPseudo.getId(), targetPseudo.getName(), targetObj.getId(), targetObj.getName()));
            fddi.setSources(sources);
            relationships.add(fddi);
        }
    }

    private void extractJoinRelationships(
            String fromClause,
            Map<String, DbObject> aliasMap,
            List<Relationship> relationships,
            AtomicLong idSeq,
            StatementSpan span,
            String unitHash,
            String sqlHash) {
        Pattern onPattern = Pattern.compile("(?is)\\bON\\s+([^\\b]+?)(?=\\b(?:INNER|LEFT|RIGHT|FULL|CROSS|JOIN|WHERE|GROUP|ORDER|$))");
        Matcher m = onPattern.matcher(fromClause);
        while (m.find()) {
            String cond = m.group(1).trim();
            List<ColToken> refs = extractColumnRefsFromExpr(cond);
            if (refs.size() >= 2) {
                ColToken left = refs.get(0);
                ColToken right = refs.get(1);
                DbObject lObj = left.qualifier() != null ? aliasMap.get(left.qualifier().toUpperCase(Locale.ROOT)) : null;
                DbObject rObj = right.qualifier() != null ? aliasMap.get(right.qualifier().toUpperCase(Locale.ROOT)) : null;
                if (lObj != null && rObj != null) {
                    Column lCol = ensureColumn(lObj, left.column(), null, idSeq, span, unitHash);
                    Column rCol = ensureColumn(rObj, right.column(), null, idSeq, span, unitHash);
                    Relationship joinRel = new Relationship(String.valueOf(idSeq.getAndIncrement()), RelationshipType.join);
                    joinRel.setCondition(cond);
                    joinRel.setClause("ON");
                    joinRel.setJoinType("INNER");
                    joinRel.setSqlHash(sqlHash);
                    joinRel.setTarget(new RelationshipElement(rCol.getId(), rCol.getName(), rObj.getId(), rObj.getName()));
                    joinRel.setSources(List.of(new RelationshipElement(lCol.getId(), lCol.getName(), lObj.getId(), lObj.getName())));
                    relationships.add(joinRel);
                }
            }
        }
    }

    private void parseDdlColumnsAndConstraints(
            String body,
            DbObject table,
            Option option,
            Map<String, DbObject> objectsByKey,
            List<Relationship> relationships,
            AtomicLong idSeq,
            StatementSpan span,
            String unitHash,
            String sqlHash) {
        Set<String> tablePkCols = new LinkedHashSet<>();
        List<String> items = splitTopLevelCommas(body);
        for (String rawItem : items) {
            String item = rawItem.trim();
            if (item.isEmpty()) {
                continue;
            }
            String upper = item.toUpperCase(Locale.ROOT);
            Matcher fkConstraint = FK_CONSTRAINT_PATTERN.matcher(item);
            if (fkConstraint.find()) {
                List<String> localCols = parseCommaIdentList(fkConstraint.group(1));
                String refTableRaw = fkConstraint.group(2);
                List<String> refCols = parseCommaIdentList(fkConstraint.group(3));
                DbObject refTable = getOrCreateObject(refTableRaw, "table", option, objectsByKey, idSeq, span, unitHash);
                for (int i = 0; i < localCols.size(); i++) {
                    Column lc = ensureColumn(table, localCols.get(i), null, idSeq, span, unitHash);
                    lc.setForeignKey(Boolean.TRUE);
                    String rcName = i < refCols.size() ? refCols.get(i) : localCols.get(i);
                    Column rc = ensureColumn(refTable, rcName, null, idSeq, span, unitHash);
                    rc.setPrimaryKey(Boolean.TRUE);
                    Relationship erRel = new Relationship(String.valueOf(idSeq.getAndIncrement()), RelationshipType.er);
                    erRel.setSqlHash(sqlHash);
                    erRel.setTarget(new RelationshipElement(lc.getId(), lc.getName(), table.getId(), table.getName()));
                    erRel.setSources(List.of(new RelationshipElement(rc.getId(), rc.getName(), refTable.getId(), refTable.getName())));
                    relationships.add(erRel);
                }
                continue;
            }
            if (upper.startsWith("PRIMARY KEY") || (upper.startsWith("CONSTRAINT ") && upper.contains("PRIMARY KEY"))) {
                int p1 = item.indexOf('(');
                int p2 = item.lastIndexOf(')');
                if (p1 > 0 && p2 > p1) {
                    tablePkCols.addAll(parseCommaIdentList(item.substring(p1 + 1, p2)));
                }
                continue;
            }
            if (upper.startsWith("CONSTRAINT ") || upper.startsWith("UNIQUE ") || upper.startsWith("KEY ") || upper.startsWith("INDEX ") || upper.startsWith("CHECK ")) {
                continue;
            }

            String[] parts = item.split("\\s+", 3);
            if (parts.length >= 2) {
                String colName = CatalogUtils.stripQuotes(parts[0]);
                String dataType = parts[1].replaceAll(",$", "");
                Column col = ensureColumn(table, colName, dataType, idSeq, span, unitHash);
                if (upper.contains("PRIMARY KEY")) {
                    col.setPrimaryKey(Boolean.TRUE);
                }
                if (upper.contains("UNIQUE")) {
                    col.setUnqiueKey(Boolean.TRUE);
                }
                if (upper.contains("NOT NULL")) {
                    col.setNullable(Boolean.FALSE);
                }
                Matcher inlineFk = FK_INLINE_PATTERN.matcher(item);
                if (inlineFk.find()) {
                    col.setForeignKey(Boolean.TRUE);
                    DbObject refTable = getOrCreateObject(inlineFk.group(1), "table", option, objectsByKey, idSeq, span, unitHash);
                    List<String> refCols = parseCommaIdentList(inlineFk.group(2));
                    String rcName = refCols.isEmpty() ? colName : refCols.get(0);
                    Column rc = ensureColumn(refTable, rcName, dataType, idSeq, span, unitHash);
                    rc.setPrimaryKey(Boolean.TRUE);
                    Relationship erRel = new Relationship(String.valueOf(idSeq.getAndIncrement()), RelationshipType.er);
                    erRel.setSqlHash(sqlHash);
                    erRel.setTarget(new RelationshipElement(col.getId(), col.getName(), table.getId(), table.getName()));
                    erRel.setSources(List.of(new RelationshipElement(rc.getId(), rc.getName(), refTable.getId(), refTable.getName())));
                    relationships.add(erRel);
                }
            }
        }
        for (String pkName : tablePkCols) {
            Column col = findColumn(table, pkName);
            if (col != null) {
                col.setPrimaryKey(Boolean.TRUE);
            }
        }
    }

    private void addColumnRelationship(
            DbObject srcObj,
            Column srcCol,
            DbObject tgtObj,
            Column tgtCol,
            RelationshipType type,
            String effectType,
            String exprCode,
            Option option,
            List<Relationship> relationships,
            AtomicLong idSeq,
            String sqlHash) {
        Relationship rel = new Relationship(String.valueOf(idSeq.getAndIncrement()), type);
        rel.setEffectType(effectType);
        rel.setSqlHash(sqlHash);
        RelationshipElement srcEl = new RelationshipElement(srcCol.getId(), srcCol.getName(), srcObj.getId(), srcObj.getName());
        srcEl.setCoordinates(srcCol.getCoordinates());
        RelationshipElement tgtEl = new RelationshipElement(tgtCol.getId(), tgtCol.getName(), tgtObj.getId(), tgtObj.getName());
        tgtEl.setCoordinates(tgtCol.getCoordinates());
        if (option.isTransform() && exprCode != null) {
            tgtEl.setTransforms(List.of(new Transform("expression", exprCode)));
        }
        rel.setSources(List.of(srcEl));
        rel.setTarget(tgtEl);
        relationships.add(rel);
    }

    private DbObject getOrCreateObject(
            String rawQualifiedName,
            String defaultType,
            Option option,
            Map<String, DbObject> objectsByKey,
            AtomicLong idSeq,
            StatementSpan span,
            String unitHash) {
        String[] parts = rawQualifiedName.trim().split("\\.");
        String db = option.getDefaultDatabase() != null ? option.getDefaultDatabase() : "DEFAULT";
        String sc = option.getDefaultSchema() != null ? option.getDefaultSchema() : "DEFAULT";
        String name;
        if (parts.length >= 3) {
            db = CatalogUtils.stripQuotes(parts[parts.length - 3]);
            sc = CatalogUtils.stripQuotes(parts[parts.length - 2]);
            name = CatalogUtils.stripQuotes(parts[parts.length - 1]);
        } else if (parts.length == 2) {
            sc = CatalogUtils.stripQuotes(parts[0]);
            name = CatalogUtils.stripQuotes(parts[1]);
        } else {
            name = CatalogUtils.stripQuotes(parts[0]);
        }
        String key = canonicalObjectKey(db, sc, name, defaultType);
        DbObject existing = objectsByKey.get(key);
        if (existing != null) {
            return existing;
        }
        // Check if registered as view under same db.sc.name
        String viewKey = canonicalObjectKey(db, sc, name, "view");
        if (objectsByKey.containsKey(viewKey)) {
            return objectsByKey.get(viewKey);
        }
        DbObject obj = new DbObject(String.valueOf(idSeq.getAndIncrement()), name, defaultType);
        obj.setServer(option.getDefaultServer());
        obj.setDatabase(db);
        obj.setSchema(sc);
        obj.setDisplayName(
                !"DEFAULT".equalsIgnoreCase(sc) ? sc + "." + name : name);
        if (!option.isIgnoreCoordinate()) {
            obj.setCoordinates(Coordinate.span(span.startLine(), 1, span.endLine(), Math.max(2, name.length() + 1), unitHash));
        }
        obj.setColumns(new ArrayList<>());
        objectsByKey.put(key, obj);
        return obj;
    }

    private Column ensureColumn(
            DbObject table,
            String colName,
            String dataType,
            AtomicLong idSeq,
            StatementSpan span,
            String unitHash) {
        String clean = CatalogUtils.stripQuotes(colName);
        Column existing = findColumn(table, clean);
        if (existing != null) {
            if (existing.getDataType() == null && dataType != null) {
                existing.setDataType(dataType);
            }
            return existing;
        }
        if (table.getColumns() == null) {
            table.setColumns(new ArrayList<>());
        }
        Column col = new Column(String.valueOf(idSeq.getAndIncrement()), clean, dataType);
        col.setCoordinates(Coordinate.span(span.startLine(), 1, span.endLine(), Math.max(2, clean.length() + 1), unitHash));
        table.getColumns().add(col);
        return col;
    }

    private Column ensureRelationRowsColumn(DbObject table, AtomicLong idSeq) {
        Column existing = findColumn(table, "RelationRows");
        if (existing != null) {
            return existing;
        }
        if (table.getColumns() == null) {
            table.setColumns(new ArrayList<>());
        }
        Column rr = Column.relationRows(String.valueOf(idSeq.getAndIncrement()));
        table.getColumns().add(rr);
        return rr;
    }

    private static Column findColumn(DbObject obj, String colName) {
        if (obj == null || obj.getColumns() == null || colName == null) {
            return null;
        }
        for (Column c : obj.getColumns()) {
            if (c != null && c.getName() != null && c.getName().equalsIgnoreCase(colName)) {
                return c;
            }
        }
        return null;
    }

    private static String canonicalObjectKey(String db, String schema, String name, String type) {
        String d = db != null ? db.toUpperCase(Locale.ROOT) : "DEFAULT";
        String s = schema != null ? schema.toUpperCase(Locale.ROOT) : "DEFAULT";
        String n = name != null ? name.toUpperCase(Locale.ROOT) : "UNKNOWN";
        String t = "resultset".equalsIgnoreCase(type) ? "RESULTSET" : "OBJECT";
        return t + ":" + d + "." + s + "." + n;
    }

    private static DbObject copyDbObject(DbObject src, String newId, AtomicLong idSeq) {
        DbObject dst = new DbObject(newId, src.getName(), src.getType());
        dst.setServer(src.getServer());
        dst.setDatabase(src.getDatabase());
        dst.setSchema(src.getSchema());
        dst.setDisplayName(src.getDisplayName());
        dst.setAlias(src.getAlias());
        dst.setSubType(src.getSubType());
        dst.setIsView(src.getIsView());
        dst.setIsTarget(src.getIsTarget());
        dst.setFromDDL(src.getFromDDL());
        dst.setCoordinates(src.getCoordinates());
        List<Column> cols = new ArrayList<>();
        if (src.getColumns() != null) {
            for (Column c : src.getColumns()) {
                Column cc = new Column(
                        c.getId() != null ? c.getId() : String.valueOf(idSeq.getAndIncrement()),
                        c.getName(),
                        c.getDataType());
                cc.setSource(c.getSource());
                cc.setPrimaryKey(c.getPrimaryKey());
                cc.setUnqiueKey(c.getUnqiueKey());
                cc.setForeignKey(c.getForeignKey());
                cc.setIndexKey(c.getIndexKey());
                cc.setNullable(c.getNullable());
                cc.setComment(c.getComment());
                cc.setCoordinates(c.getCoordinates());
                cols.add(cc);
            }
        }
        dst.setColumns(cols);
        return dst;
    }

    private static void mergeColumns(
            DbObject target, List<Column> incoming, AtomicLong idSeq, Map<String, String> idRemap) {
        if (incoming == null) {
            return;
        }
        if (target.getColumns() == null) {
            target.setColumns(new ArrayList<>());
        }
        for (Column c : incoming) {
            Column existing = findColumn(target, c.getName());
            if (existing == null) {
                Column copy = new Column(String.valueOf(idSeq.getAndIncrement()), c.getName(), c.getDataType());
                copy.setSource(c.getSource());
                copy.setPrimaryKey(c.getPrimaryKey());
                copy.setUnqiueKey(c.getUnqiueKey());
                copy.setForeignKey(c.getForeignKey());
                copy.setCoordinates(c.getCoordinates());
                target.getColumns().add(copy);
                if (c.getId() != null) {
                    idRemap.put(c.getId(), copy.getId());
                }
            } else if (c.getId() != null) {
                idRemap.put(c.getId(), existing.getId());
            }
        }
    }

    private static Relationship remapRelationship(Relationship src, Map<String, String> idRemap, String newId) {
        Relationship copy = new Relationship();
        copy.setId(newId);
        copy.setType(src.getType());
        copy.setEffectType(src.getEffectType());
        copy.setClause(src.getClause());
        copy.setJoinType(src.getJoinType());
        copy.setCondition(src.getCondition());
        copy.setSqlHash(src.getSqlHash());
        copy.setSqlComment(src.getSqlComment());
        if (src.getTarget() != null) {
            copy.setTarget(remapElement(src.getTarget(), idRemap));
        }
        if (src.getSources() != null) {
            copy.setSources(src.getSources().stream().map(e -> remapElement(e, idRemap)).toList());
        }
        if (src.getCaller() != null) {
            copy.setCaller(remapElement(src.getCaller(), idRemap));
        }
        if (src.getCallees() != null) {
            copy.setCallees(src.getCallees().stream().map(e -> remapElement(e, idRemap)).toList());
        }
        return copy;
    }

    private static RelationshipElement remapElement(RelationshipElement el, Map<String, String> idRemap) {
        RelationshipElement copy = new RelationshipElement();
        copy.setId(idRemap.getOrDefault(el.getId(), el.getId()));
        copy.setColumn(el.getColumn());
        copy.setName(el.getName());
        copy.setParentId(idRemap.getOrDefault(el.getParentId(), el.getParentId()));
        copy.setParentName(el.getParentName());
        copy.setTransforms(el.getTransforms());
        copy.setCoordinates(el.getCoordinates());
        return copy;
    }

    private static List<StatementSpan> splitStatementsWithLines(String sql, String customDelimiter) {
        List<StatementSpan> result = new ArrayList<>();
        if (customDelimiter != null && !customDelimiter.isBlank() && !";".equals(customDelimiter.trim())) {
            String[] parts = sql.split(Pattern.quote(customDelimiter));
            int line = 1;
            for (String p : parts) {
                int lines = countNewlines(p);
                result.add(new StatementSpan(p, line, line + lines));
                line += lines;
            }
            return result;
        }
        int len = sql.length();
        int start = 0;
        int startLine = 1;
        int curLine = 1;
        boolean inSingle = false;
        boolean inDouble = false;
        int beginDepth = 0;

        for (int i = 0; i < len; i++) {
            char c = sql.charAt(i);
            if (c == '\n') {
                curLine++;
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }
            if (inSingle || inDouble) {
                continue;
            }
            if (Character.isLetter(c)) {
                int ws = i;
                while (i + 1 < len && (Character.isLetterOrDigit(sql.charAt(i + 1)) || sql.charAt(i + 1) == '_')) {
                    i++;
                }
                String word = sql.substring(ws, i + 1).toUpperCase(Locale.ROOT);
                if ("BEGIN".equals(word)) {
                    beginDepth++;
                } else if ("END".equals(word) && beginDepth > 0) {
                    beginDepth--;
                }
                continue;
            }
            if (c == ';' && beginDepth == 0) {
                result.add(new StatementSpan(sql.substring(start, i), startLine, curLine));
                start = i + 1;
                startLine = curLine;
            }
        }
        if (start < len) {
            String tail = sql.substring(start);
            if (!tail.isBlank()) {
                result.add(new StatementSpan(tail, startLine, curLine));
            }
        }
        return result;
    }

    private static int countNewlines(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    private static String stripComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--.*$", " ");
    }

    private static List<String> parseCommaIdentList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String c = CatalogUtils.stripQuotes(part);
            if (c != null && !c.isEmpty()) {
                out.add(c);
            }
        }
        return out;
    }

    private static List<String> splitTopLevelCommas(String text) {
        List<String> items = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return items;
        }
        int depth = 0;
        boolean inQuote = false;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
            } else if (!inQuote) {
                if (c == '(') {
                    depth++;
                } else if (c == ')' && depth > 0) {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    items.add(text.substring(start, i));
                    start = i + 1;
                }
            }
        }
        items.add(text.substring(start));
        return items;
    }

    private static List<String> splitTopLevelUnion(String sql) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inQuote = false;
        int start = 0;
        int len = sql.length();
        for (int i = 0; i < len; i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
            } else if (!inQuote) {
                if (c == '(') {
                    depth++;
                } else if (c == ')' && depth > 0) {
                    depth--;
                } else if (depth == 0 && (i == 0 || !Character.isLetterOrDigit(sql.charAt(i - 1)))) {
                    if (sql.regionMatches(true, i, "UNION", 0, 5)
                            && (i + 5 == len || Character.isWhitespace(sql.charAt(i + 5)))) {
                        parts.add(sql.substring(start, i));
                        int next = i + 5;
                        while (next < len && Character.isWhitespace(sql.charAt(next))) {
                            next++;
                        }
                        if (sql.regionMatches(true, next, "ALL", 0, 3)
                                && (next + 3 == len || Character.isWhitespace(sql.charAt(next + 3)))) {
                            next += 3;
                        }
                        start = next;
                        i = next - 1;
                    }
                }
            }
        }
        parts.add(sql.substring(start));
        return parts;
    }

    private static int findTopLevelKeyword(String sql, String keyword) {
        int depth = 0;
        boolean inQuote = false;
        int kwLen = keyword.length();
        int len = sql.length();
        for (int i = 0; i <= len - kwLen; i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
            } else if (!inQuote) {
                if (c == '(') {
                    depth++;
                } else if (c == ')' && depth > 0) {
                    depth--;
                } else if (depth == 0
                        && (i == 0 || !Character.isLetterOrDigit(sql.charAt(i - 1)) && sql.charAt(i - 1) != '_')
                        && sql.regionMatches(true, i, keyword, 0, kwLen)
                        && (i + kwLen == len || !Character.isLetterOrDigit(sql.charAt(i + kwLen)) && sql.charAt(i + kwLen) != '_')) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int findMatchingParen(String s, int openIdx) {
        int depth = 0;
        boolean inQuote = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
            } else if (!inQuote) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    private static ExprAndAlias splitSelectAlias(String item, int index) {
        String trimmed = item.trim();
        int asIdx = findTopLevelKeyword(trimmed, "AS");
        if (asIdx > 0) {
            String expr = trimmed.substring(0, asIdx).trim();
            String alias = CatalogUtils.stripQuotes(trimmed.substring(asIdx + 2).trim());
            return new ExprAndAlias(expr, alias.isEmpty() ? "COL_" + (index + 1) : alias);
        }
        // Check trailing space alias outside parens
        int depth = 0;
        boolean inQuote = false;
        int lastSpace = -1;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
            } else if (!inQuote) {
                if (c == '(') {
                    depth++;
                } else if (c == ')' && depth > 0) {
                    depth--;
                } else if (Character.isWhitespace(c) && depth == 0) {
                    lastSpace = i;
                }
            }
        }
        if (lastSpace > 0) {
            String lhs = trimmed.substring(0, lastSpace).trim();
            String rhs = trimmed.substring(lastSpace + 1).trim();
            if (!rhs.isEmpty()
                    && rhs.matches("[\\w\"`\\[\\]]+")
                    && !lhs.endsWith("+")
                    && !lhs.endsWith("-")
                    && !lhs.endsWith("*")
                    && !lhs.endsWith("/")
                    && !lhs.toUpperCase(Locale.ROOT).endsWith("END")) {
                return new ExprAndAlias(lhs, CatalogUtils.stripQuotes(rhs));
            }
        }
        // Default alias from column identifier if simple a.b or b
        String simple = trimmed;
        if (simple.contains(".")) {
            simple = simple.substring(simple.lastIndexOf('.') + 1);
        }
        simple = CatalogUtils.stripQuotes(simple);
        if (simple.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return new ExprAndAlias(trimmed, simple);
        }
        return new ExprAndAlias(trimmed, "EXPR_" + (index + 1));
    }

    private static final Set<String> SQL_EXPR_KEYWORDS = Set.of(
            "CASE", "WHEN", "THEN", "ELSE", "END", "AND", "OR", "NOT", "NULL", "IS", "IN",
            "BETWEEN", "LIKE", "AS", "CAST", "DISTINCT", "OVER", "PARTITION", "BY", "ORDER",
            "ASC", "DESC", "ROWS", "RANGE", "UNBOUNDED", "PRECEDING", "CURRENT", "ROW",
            "TRUE", "FALSE", "WHERE", "GROUP", "HAVING", "INTERVAL", "DAY", "MONTH", "YEAR",
            "INT", "INTEGER", "VARCHAR", "NUMERIC", "DECIMAL", "DATE", "TIMESTAMP", "CHAR");

    private static List<ColToken> extractColumnRefsFromExpr(String expr) {
        if (expr == null || expr.isBlank()) {
            return List.of();
        }
        String withoutStrings = expr.replaceAll("'[^']*'", " ");
        List<ColToken> out = new ArrayList<>();
        Pattern tokPattern = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)(?:\\.([A-Za-z_][A-Za-z0-9_]*))?(\\s*\\()?");
        Matcher m = tokPattern.matcher(withoutStrings);
        while (m.find()) {
            if (m.group(3) != null) {
                // Function call name
                continue;
            }
            String first = m.group(1);
            String second = m.group(2);
            if (second != null) {
                out.add(new ColToken(first, second));
            } else if (!SQL_EXPR_KEYWORDS.contains(first.toUpperCase(Locale.ROOT))) {
                out.add(new ColToken(null, first));
            }
        }
        return out;
    }

    private static String md5Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private record StatementSpan(String text, int startLine, int endLine) {
    }

    private record ExprAndAlias(String expr, String alias) {
    }

    private record ColToken(String qualifier, String column) {
    }
}
