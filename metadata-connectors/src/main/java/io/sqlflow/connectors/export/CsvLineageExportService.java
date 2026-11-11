package io.sqlflow.connectors.export;

import com.fasterxml.jackson.databind.JsonNode;
import io.sqlflow.connectors.util.ConnectorsJson;
import io.sqlflow.contracts.graph.SqlflowJSON;
import io.sqlflow.contracts.lineage.Column;
import io.sqlflow.contracts.lineage.Coordinate;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.DatabaseObject;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.PackageObject;
import io.sqlflow.contracts.lineage.ProcedureObject;
import io.sqlflow.contracts.lineage.ProcessNode;
import io.sqlflow.contracts.lineage.Relationship;
import io.sqlflow.contracts.lineage.RelationshipElement;
import io.sqlflow.contracts.lineage.SchemaObject;
import io.sqlflow.contracts.lineage.ServerObject;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.contracts.lineage.Transform;
import io.sqlflow.contracts.spi.CsvLineageExportSpi;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Production implementation of {@link CsvLineageExportSpi} ({@code OBL-087}, {@code 00 §3.3 #15},
 * {@code 01 §3.3.8}, {@code 02 §6.5}, {@code 03 §1.5.1}):
 * <ul>
 *   <li>15/16-column {@code WEB_JOB_SNAKE_CASE} ({@code source_db..procedure[,transform]})</li>
 *   <li>15/16-column {@code GENERATION_PASCAL_CASE} ({@code SourceServer..OccurrencesNumber[,TargetDataType]})</li>
 *   <li>15/16-column {@code COORDINATE_15_COLUMN_HEADER} / {@code COORDINATE_16_COLUMN_HEADER}
 *       ({@code SourceServer..Coordinate,SqlFile,Transforms[,TargetDataType]})</li>
 *   <li>9-column {@code metadata.csv} ({@code db_obj_id..comment}, excluding synthetic {@code RelationRows} / {@code $constant})</li>
 *   <li>{@code metadata_lineage.zip} archive bundler ({@code metadata.csv} + {@code lineage.csv}) and
 *       {@link #parseJsonAsMetadataLineage} filterable JSON-to-ZIP converter</li>
 * </ul>
 */
public class CsvLineageExportService implements CsvLineageExportSpi {

    /**
     * 15-column Coordinate/SqlFile PascalCase header ({@code OBL-087}).
     */
    public static final List<String> COORDINATE_15_COLUMN_HEADER = List.of(
            "SourceServer",
            "SourceDatabase",
            "SourceSchema",
            "SourceTable",
            "SourceColumn",
            "TargetServer",
            "TargetDatabase",
            "TargetSchema",
            "TargetTable",
            "TargetColumn",
            "RelationType",
            "EffectType",
            "Coordinate",
            "SqlFile",
            "Transforms");

    /**
     * 16-column Coordinate/SqlFile PascalCase header with {@code TargetDataType} ({@code OBL-087}).
     */
    public static final List<String> COORDINATE_16_COLUMN_HEADER = List.of(
            "SourceServer",
            "SourceDatabase",
            "SourceSchema",
            "SourceTable",
            "SourceColumn",
            "TargetServer",
            "TargetDatabase",
            "TargetSchema",
            "TargetTable",
            "TargetColumn",
            "RelationType",
            "EffectType",
            "Coordinate",
            "SqlFile",
            "Transforms",
            "TargetDataType");

    /**
     * Public no-arg constructor required for {@link java.util.ServiceLoader}.
     */
    public CsvLineageExportService() {
    }

    @Override
    public String exportLineageCsv(DataFlow dataFlow, CsvExportOptions options) {
        CsvExportOptions opts = options != null ? options : CsvExportOptions.webJob16Col();
        Rfc4180CsvWriter writer = new Rfc4180CsvWriter(opts.getDelimiter());

        List<String> header = resolveHeader(opts);
        writer.writeRow(header);

        if (dataFlow == null || dataFlow.getRelationships() == null || dataFlow.getRelationships().isEmpty()) {
            return writer.toString();
        }

        CatalogLookupIndex index = buildCatalogIndex(dataFlow);
        for (Relationship rel : dataFlow.getRelationships()) {
            if (rel == null || !acceptsRelationshipType(rel.getType(), opts)) {
                continue;
            }
            RelationshipElement target = rel.getTarget();
            if (target == null || rel.getSources() == null || rel.getSources().isEmpty()) {
                continue;
            }
            ResolvedEndpoint tgt = resolveEndpoint(target, index, true);
            String relId = emptyIfNull(rel.getId());
            String relType = nonBlankOr(rel.getType(), "fdd");
            String effectType = emptyIfNull(rel.getEffectType());
            String procName = index.resolveProcedureOrProcessName(rel);

            for (RelationshipElement source : rel.getSources()) {
                if (source == null) {
                    continue;
                }
                ResolvedEndpoint src = resolveEndpoint(source, index, false);
                String transforms = resolveTransforms(source, target, rel, index);
                String occurrences = Integer.toString(resolveOccurrencesCount(source, target));

                if (opts.getHeaderStyle() == CsvHeaderStyle.GENERATION_PASCAL_CASE) {
                    List<String> row = new ArrayList<>(16);
                    row.add(src.server);
                    row.add(src.database);
                    row.add(src.schema);
                    row.add(src.table);
                    row.add(src.column);
                    row.add(tgt.server);
                    row.add(tgt.database);
                    row.add(tgt.schema);
                    row.add(tgt.table);
                    row.add(tgt.column);
                    row.add(relId);
                    row.add(relType);
                    row.add(effectType);
                    row.add(transforms);
                    row.add(occurrences);
                    if (opts.isIncludeSixteenthColumn()) {
                        row.add(tgt.dataType);
                    }
                    writer.writeRow(row);
                } else {
                    List<String> row = new ArrayList<>(16);
                    row.add(src.database);
                    row.add(src.schema);
                    row.add(src.tableId);
                    row.add(src.table);
                    row.add(src.columnId);
                    row.add(src.column);
                    row.add(tgt.database);
                    row.add(tgt.schema);
                    row.add(tgt.tableId);
                    row.add(tgt.table);
                    row.add(tgt.columnId);
                    row.add(tgt.column);
                    row.add(relType);
                    row.add(effectType);
                    row.add(procName);
                    if (opts.isIncludeSixteenthColumn()) {
                        row.add(transforms);
                    }
                    writer.writeRow(row);
                }
            }
        }
        return writer.toString();
    }

    /**
     * Exports 15/16-column Coordinate/SqlFile PascalCase lineage CSV
     * ({@code SourceServer,SourceDatabase,SourceSchema,SourceTable,SourceColumn,TargetServer,TargetDatabase,TargetSchema,TargetTable,TargetColumn,RelationType,EffectType,Coordinate,SqlFile,Transforms[,TargetDataType]}).
     */
    public String exportCoordinateLineageCsv(
            DataFlow dataFlow,
            boolean includeTargetDataType,
            char delimiter,
            boolean includeDirectDataflow,
            boolean includeIndirectDataflow) {
        Rfc4180CsvWriter writer = new Rfc4180CsvWriter(delimiter);
        writer.writeRow(includeTargetDataType ? COORDINATE_16_COLUMN_HEADER : COORDINATE_15_COLUMN_HEADER);

        if (dataFlow == null || dataFlow.getRelationships() == null || dataFlow.getRelationships().isEmpty()) {
            return writer.toString();
        }
        CsvExportOptions filterOpts = new CsvExportOptions();
        filterOpts.setIncludeDirectDataflow(includeDirectDataflow);
        filterOpts.setIncludeIndirectDataflow(includeIndirectDataflow);

        CatalogLookupIndex index = buildCatalogIndex(dataFlow);
        for (Relationship rel : dataFlow.getRelationships()) {
            if (rel == null || !acceptsRelationshipType(rel.getType(), filterOpts)) {
                continue;
            }
            RelationshipElement target = rel.getTarget();
            if (target == null || rel.getSources() == null || rel.getSources().isEmpty()) {
                continue;
            }
            ResolvedEndpoint tgt = resolveEndpoint(target, index, true);
            String relType = nonBlankOr(rel.getType(), "fdd");
            String effectType = emptyIfNull(rel.getEffectType());

            for (RelationshipElement source : rel.getSources()) {
                if (source == null) {
                    continue;
                }
                ResolvedEndpoint src = resolveEndpoint(source, index, false);
                String coordStr = resolveCoordinateString(source, target);
                String sqlFile = resolveSqlFile(source, target, rel, index);
                String transforms = resolveTransforms(source, target, rel, index);

                List<String> row = new ArrayList<>(16);
                row.add(src.server);
                row.add(src.database);
                row.add(src.schema);
                row.add(src.table);
                row.add(src.column);
                row.add(tgt.server);
                row.add(tgt.database);
                row.add(tgt.schema);
                row.add(tgt.table);
                row.add(tgt.column);
                row.add(relType);
                row.add(effectType);
                row.add(coordStr);
                row.add(sqlFile);
                row.add(transforms);
                if (includeTargetDataType) {
                    row.add(tgt.dataType);
                }
                writer.writeRow(row);
            }
        }
        return writer.toString();
    }

    /**
     * Convenience overload exporting lineage CSV directly from a {@link SqlflowJSON} layout payload.
     */
    public String exportLineageCsv(SqlflowJSON sqlflowJson, CsvExportOptions options) {
        return exportLineageCsv(extractDataFlowFromSqlflowJson(sqlflowJson), options);
    }

    /**
     * Convenience overload exporting Coordinate/SqlFile 15/16-column CSV from {@link SqlflowJSON}.
     */
    public String exportCoordinateLineageCsv(SqlflowJSON sqlflowJson, boolean includeTargetDataType) {
        return exportCoordinateLineageCsv(extractDataFlowFromSqlflowJson(sqlflowJson), includeTargetDataType, ',', true, false);
    }

    @Override
    public String exportMetadataCsv(DataFlow dataFlow) {
        return exportMetadataCsv(dataFlow, ',');
    }

    public String exportMetadataCsv(DataFlow dataFlow, char delimiter) {
        Rfc4180CsvWriter writer = new Rfc4180CsvWriter(delimiter);
        writer.writeRow(METADATA_CSV_9_COLUMN_HEADER);

        if (dataFlow == null || dataFlow.getDbobjs() == null) {
            return writer.toString();
        }
        CatalogLookupIndex index = buildCatalogIndex(dataFlow);
        Set<String> emittedKeys = new LinkedHashSet<>();

        for (IndexedDbObject entry : index.orderedObjects) {
            DbObject dbo = entry.dbObject;
            if (isSyntheticObject(dbo)) {
                continue;
            }
            String objId = emptyIfNull(dbo.getId());
            String server = emptyIfNull(entry.server);
            String database = emptyIfNull(entry.database);
            String schema = emptyIfNull(entry.schema);
            String tableName = emptyIfNull(dbo.getName());

            List<Column> cols = dbo.getColumns();
            if (cols == null || cols.isEmpty()) {
                String dedupKey = objId + "|" + database + "|" + schema + "|" + tableName + "|";
                if (emittedKeys.add(dedupKey)) {
                    writer.writeRow(List.of(objId, server, database, schema, tableName, "", "", "", ""));
                }
                continue;
            }
            for (Column col : cols) {
                if (col == null || isSyntheticColumn(col)) {
                    continue;
                }
                String colId = emptyIfNull(col.getId());
                String colName = emptyIfNull(col.getName());
                String dedupKey = objId + "|" + database + "|" + schema + "|" + tableName + "|" + colName.toLowerCase(Locale.ROOT);
                if (!emittedKeys.add(dedupKey)) {
                    continue;
                }
                writer.writeRow(List.of(
                        objId,
                        server,
                        database,
                        schema,
                        tableName,
                        colId,
                        colName,
                        emptyIfNull(col.getDataType()),
                        emptyIfNull(col.getComment())));
            }
        }
        return writer.toString();
    }

    @Override
    public byte[] exportMetadataLineageZip(DataFlow dataFlow, CsvExportOptions options) {
        CsvExportOptions opts = options != null ? options : CsvExportOptions.webJob16Col();
        String metadataCsv = exportMetadataCsv(dataFlow, opts.getDelimiter());
        String lineageCsv = exportLineageCsv(dataFlow, opts);

        ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            ZipEntry metaEntry = new ZipEntry(METADATA_CSV_ENTRY_NAME);
            zos.putNextEntry(metaEntry);
            zos.write(metadataCsv.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            ZipEntry lineageEntry = new ZipEntry(LINEAGE_CSV_ENTRY_NAME);
            zos.putNextEntry(lineageEntry);
            zos.write(lineageCsv.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to package metadata_lineage.zip: " + e.getMessage(), e);
        }
        return baos.toByteArray();
    }

    public byte[] exportMetadataLineageZip(SqlflowJSON sqlflowJson, CsvExportOptions options) {
        return exportMetadataLineageZip(extractDataFlowFromSqlflowJson(sqlflowJson), options);
    }

    /**
     * Parses a serialized {@link DataFlow} or {@link SqlflowJSON} JSON payload, applies optional comma-separated
     * {@code filterDbs}, {@code filterSchemas}, and {@code filterTables} filters ({@code 01 §3.3.8}), and
     * packages {@code metadata.csv} + {@code lineage.csv} into {@code metadata_lineage.zip}.
     */
    public byte[] parseJsonAsMetadataLineage(
            String jsonPayload,
            String filterDbs,
            String filterSchemas,
            String filterTables,
            CsvExportOptions options) {
        if (jsonPayload == null || jsonPayload.isBlank()) {
            return exportMetadataLineageZip(new DataFlow(), options);
        }
        DataFlow df;
        try {
            JsonNode root = ConnectorsJson.mapper().readTree(jsonPayload);
            if (root.has("data") && root.get("data").isObject()) {
                root = root.get("data");
            }
            if (root.has("sqlflow") || root.has("graph") || root.has("elements")) {
                SqlflowJSON wrapper = ConnectorsJson.mapper().treeToValue(root, SqlflowJSON.class);
                df = extractDataFlowFromSqlflowJson(wrapper);
            } else {
                df = ConnectorsJson.mapper().treeToValue(root, DataFlow.class);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid DataFlow/SqlflowJSON payload for parseJsonAsMetadataLineage: " + e.getMessage(), e);
        }

        DataFlow filtered = filterDataFlow(
                df,
                parseFilterSet(filterDbs),
                parseFilterSet(filterSchemas),
                parseFilterSet(filterTables));
        return exportMetadataLineageZip(filtered, options);
    }

    private static DataFlow filterDataFlow(
            DataFlow source,
            Set<String> dbs,
            Set<String> schemas,
            Set<String> tables) {
        if (source == null || (dbs.isEmpty() && schemas.isEmpty() && tables.isEmpty())) {
            return source;
        }
        CatalogLookupIndex index = buildCatalogIndex(source);
        Set<String> allowedObjIds = new LinkedHashSet<>();

        SqlflowCatalog filteredCat = new SqlflowCatalog();
        List<DatabaseObject> keptDbs = new ArrayList<>();
        for (IndexedDbObject item : index.orderedObjects) {
            if (!matchesFilter(dbs, item.database)
                    || !matchesFilter(schemas, item.schema)
                    || !matchesFilter(tables, item.dbObject.getName())) {
                continue;
            }
            if (item.dbObject.getId() != null) {
                allowedObjIds.add(item.dbObject.getId());
            }
            DatabaseObject dbNode = new DatabaseObject(nonBlankOr(item.database, "default"));
            SchemaObject schemaNode = new SchemaObject(nonBlankOr(item.schema, "public"));
            if ("view".equalsIgnoreCase(item.dbObject.getType())) {
                schemaNode.setViews(List.of(item.dbObject));
            } else {
                schemaNode.setTables(List.of(item.dbObject));
            }
            dbNode.setSchemas(List.of(schemaNode));
            keptDbs.add(dbNode);
        }
        filteredCat.setDatabases(keptDbs);

        List<Relationship> keptRels = new ArrayList<>();
        if (source.getRelationships() != null) {
            for (Relationship rel : source.getRelationships()) {
                if (rel == null || rel.getTarget() == null) {
                    continue;
                }
                ResolvedEndpoint tgt = resolveEndpoint(rel.getTarget(), index, true);
                boolean tgtMatch = endpointMatchesFilter(tgt, allowedObjIds, dbs, schemas, tables);
                boolean anySrcMatch = false;
                if (rel.getSources() != null) {
                    for (RelationshipElement s : rel.getSources()) {
                        ResolvedEndpoint src = resolveEndpoint(s, index, false);
                        if (endpointMatchesFilter(src, allowedObjIds, dbs, schemas, tables)) {
                            anySrcMatch = true;
                            break;
                        }
                    }
                }
                if (tgtMatch || anySrcMatch) {
                    keptRels.add(rel);
                }
            }
        }

        DataFlow out = new DataFlow();
        out.setDbvendor(source.getDbvendor());
        out.setDbobjs(filteredCat);
        out.setRelationships(keptRels);
        out.setProcesses(source.getProcesses());
        return out;
    }

    private static boolean endpointMatchesFilter(
            ResolvedEndpoint ep,
            Set<String> allowedIds,
            Set<String> dbs,
            Set<String> schemas,
            Set<String> tables) {
        if (!ep.tableId.isEmpty() && allowedIds.contains(ep.tableId)) {
            return true;
        }
        return matchesFilter(dbs, ep.database)
                && matchesFilter(schemas, ep.schema)
                && matchesFilter(tables, ep.table);
    }

    private static boolean matchesFilter(Set<String> allowedLower, String candidate) {
        if (allowedLower.isEmpty()) {
            return true;
        }
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        return allowedLower.contains(candidate.trim().toLowerCase(Locale.ROOT));
    }

    private static Set<String> parseFilterSet(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> set = new LinkedHashSet<>();
        for (String token : raw.split("[,;]+")) {
            String t = token.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) {
                set.add(t);
            }
        }
        return set;
    }

    private static DataFlow extractDataFlowFromSqlflowJson(SqlflowJSON json) {
        if (json == null) {
            return new DataFlow();
        }
        if (json.getSqlflow() != null) {
            return json.getSqlflow();
        }
        // Fallback: reconstruct minimal DataFlow from visual GraphElements when sqlflow semantic payload was omitted
        DataFlow df = new DataFlow();
        SqlflowJSON.GraphElements elements = json.resolveElements();
        if (elements == null) {
            return df;
        }
        SqlflowCatalog cat = new SqlflowCatalog();
        DatabaseObject dbObj = new DatabaseObject(nonBlankOr(json.getDatabase(), "default"));
        SchemaObject schemaObj = new SchemaObject(nonBlankOr(json.getSchema(), "public"));
        List<DbObject> tables = new ArrayList<>();
        if (elements.getTables() != null) {
            for (SqlflowJSON.GraphTable gt : elements.getTables()) {
                DbObject t = new DbObject(gt.getId(), gt.getName(), nonBlankOr(gt.getType(), "table"));
                t.setServer(gt.getServer());
                t.setDatabase(gt.getDatabase());
                t.setSchema(gt.getSchema());
                if (gt.getColumns() != null) {
                    List<Column> cols = new ArrayList<>();
                    for (SqlflowJSON.GraphColumn gc : gt.getColumns()) {
                        cols.add(new Column(gc.getId(), gc.getName(), gc.getDataType()));
                    }
                    t.setColumns(cols);
                }
                tables.add(t);
            }
        }
        schemaObj.setTables(tables);
        dbObj.setSchemas(List.of(schemaObj));
        cat.setDatabases(List.of(dbObj));
        df.setDbobjs(cat);
        return df;
    }

    private static List<String> resolveHeader(CsvExportOptions opts) {
        if (opts.getHeaderStyle() == CsvHeaderStyle.GENERATION_PASCAL_CASE) {
            return opts.isIncludeSixteenthColumn() ? GENERATION_16_COLUMN_HEADER : GENERATION_15_COLUMN_HEADER;
        }
        return opts.isIncludeSixteenthColumn() ? WEB_JOB_16_COLUMN_HEADER : WEB_JOB_15_COLUMN_HEADER;
    }

    private static boolean acceptsRelationshipType(String rawType, CsvExportOptions opts) {
        String type = (rawType == null || rawType.isBlank()) ? "fdd" : rawType.trim().toLowerCase(Locale.ROOT);
        if ("fdd".equals(type)) {
            return opts.isIncludeDirectDataflow();
        }
        if ("fdr".equals(type) || "fddi".equals(type) || "frd".equals(type)) {
            return opts.isIncludeIndirectDataflow();
        }
        return false;
    }

    private static ResolvedEndpoint resolveEndpoint(
            RelationshipElement elem,
            CatalogLookupIndex index,
            boolean isTarget) {
        IndexedDbObject matchedObj = null;
        if (elem.getParentId() != null && !elem.getParentId().isBlank()) {
            matchedObj = index.byId.get(elem.getParentId().trim());
        }
        if (matchedObj == null && elem.getParentName() != null && !elem.getParentName().isBlank()) {
            matchedObj = index.findByQualifiedName(elem.getParentName());
        }

        String server = "";
        String database = "";
        String schema = "";
        String tableId = emptyIfNull(elem.getParentId());
        String tableName = "";

        if (matchedObj != null) {
            server = emptyIfNull(matchedObj.server);
            database = emptyIfNull(matchedObj.database);
            schema = emptyIfNull(matchedObj.schema);
            if (tableId.isEmpty() && matchedObj.dbObject.getId() != null) {
                tableId = matchedObj.dbObject.getId();
            }
            tableName = emptyIfNull(matchedObj.dbObject.getName());
        } else if (elem.getParentName() != null && !elem.getParentName().isBlank()) {
            ParsedName parsed = parseQualifiedName(elem.getParentName());
            server = parsed.server;
            database = parsed.database;
            schema = parsed.schema;
            tableName = parsed.object;
        }

        String colId = emptyIfNull(elem.getId());
        String colName = firstNonBlank(elem.getColumn(), elem.getName(), elem.getValue());
        String dataType = emptyIfNull(elem.getColumnType());

        if (matchedObj != null) {
            Column matchedCol = null;
            if (!colId.isEmpty()) {
                matchedCol = matchedObj.columnsById.get(colId);
            }
            if (matchedCol == null && !colName.isEmpty()) {
                matchedCol = matchedObj.columnsByNameLower.get(colName.toLowerCase(Locale.ROOT));
            }
            if (matchedCol != null) {
                if (colId.isEmpty() && matchedCol.getId() != null) {
                    colId = matchedCol.getId();
                }
                if (colName.isEmpty() && matchedCol.getName() != null) {
                    colName = matchedCol.getName();
                }
                if (dataType.isEmpty() && matchedCol.getDataType() != null) {
                    dataType = matchedCol.getDataType();
                }
            }
        }

        return new ResolvedEndpoint(server, database, schema, tableId, tableName, colId, colName, dataType);
    }

    private static String resolveTransforms(
            RelationshipElement source,
            RelationshipElement target,
            Relationship rel,
            CatalogLookupIndex index) {
        List<String> codes = new ArrayList<>();
        collectTransformCodes(source.getTransforms(), codes);
        collectTransformCodes(target.getTransforms(), codes);
        if (codes.isEmpty() && rel.getProcessId() != null) {
            ProcessNode procNode = index.processesById.get(rel.getProcessId());
            if (procNode != null) {
                collectTransformCodes(procNode.getTransforms(), codes);
            }
        }
        if (codes.isEmpty()) {
            if (rel.getCondition() != null && !rel.getCondition().isBlank()) {
                return rel.getCondition().trim();
            }
            if (rel.getFunction() != null && !rel.getFunction().isBlank()) {
                return rel.getFunction().trim();
            }
            return "";
        }
        return String.join("; ", new LinkedHashSet<>(codes));
    }

    private static void collectTransformCodes(List<Transform> transforms, List<String> out) {
        if (transforms == null) {
            return;
        }
        for (Transform t : transforms) {
            if (t != null && t.getCode() != null && !t.getCode().isBlank()) {
                out.add(t.getCode().trim());
            }
        }
    }

    private static int resolveOccurrencesCount(RelationshipElement source, RelationshipElement target) {
        List<Coordinate> coords = (source.getCoordinates() != null && !source.getCoordinates().isEmpty())
                ? source.getCoordinates()
                : target.getCoordinates();
        if (coords == null || coords.isEmpty()) {
            return 1;
        }
        return coords.size() >= 2 && coords.size() % 2 == 0 ? coords.size() / 2 : coords.size();
    }

    private static String resolveCoordinateString(RelationshipElement source, RelationshipElement target) {
        List<Coordinate> coords = (source.getCoordinates() != null && !source.getCoordinates().isEmpty())
                ? source.getCoordinates()
                : target.getCoordinates();
        String formatted = Coordinate.formatXmlCoordinate(coords);
        return formatted != null ? formatted : "";
    }

    private static String resolveSqlFile(
            RelationshipElement source,
            RelationshipElement target,
            Relationship rel,
            CatalogLookupIndex index) {
        if (rel.getSqlHash() != null && !rel.getSqlHash().isBlank()) {
            return rel.getSqlHash().trim();
        }
        List<Coordinate> coords = (source.getCoordinates() != null && !source.getCoordinates().isEmpty())
                ? source.getCoordinates()
                : target.getCoordinates();
        if (coords != null) {
            for (Coordinate c : coords) {
                if (c != null && c.getHashCode() != null && !c.getHashCode().isBlank() && !"0".equals(c.getHashCode())) {
                    return c.getHashCode().trim();
                }
            }
        }
        return index.resolveProcedureOrProcessName(rel);
    }

    private static boolean isSyntheticObject(DbObject dbo) {
        if (dbo == null || dbo.getName() == null || dbo.getName().isBlank()) {
            return true;
        }
        String name = dbo.getName().trim();
        if ("$constant".equalsIgnoreCase(name) || "constant".equalsIgnoreCase(dbo.getType())) {
            return true;
        }
        String type = dbo.getType() != null ? dbo.getType().trim().toLowerCase(Locale.ROOT) : "";
        return "resultset".equals(type) || "select_list".equals(type) || "cte".equals(type)
                || "subquery".equals(type) || "variable".equals(type);
    }

    private static boolean isSyntheticColumn(Column col) {
        if ("system".equalsIgnoreCase(col.getSource())) {
            return true;
        }
        String name = col.getName();
        return name == null || name.isBlank() || "RelationRows".equalsIgnoreCase(name.trim());
    }

    private static CatalogLookupIndex buildCatalogIndex(DataFlow dataFlow) {
        CatalogLookupIndex index = new CatalogLookupIndex();
        if (dataFlow == null) {
            return index;
        }
        if (dataFlow.getProcesses() != null) {
            for (ProcessNode p : dataFlow.getProcesses()) {
                if (p != null && p.getId() != null) {
                    index.processesById.put(p.getId(), p);
                }
            }
        }
        SqlflowCatalog cat = dataFlow.getDbobjs();
        if (cat == null) {
            return index;
        }
        String fallbackServer = nonBlankOr(cat.getPhysicalInstance(), "");

        if (cat.getServers() != null) {
            for (ServerObject srv : cat.getServers()) {
                if (srv == null) {
                    continue;
                }
                String srvName = nonBlankOr(srv.getName(), fallbackServer);
                if (srv.getDatabases() != null) {
                    for (DatabaseObject db : srv.getDatabases()) {
                        indexDatabase(db, srvName, index);
                    }
                }
                if (srv.getSchemas() != null) {
                    for (SchemaObject sch : srv.getSchemas()) {
                        indexSchema(sch, srvName, "", index);
                    }
                }
            }
        }
        if (cat.getDatabases() != null) {
            for (DatabaseObject db : cat.getDatabases()) {
                indexDatabase(db, fallbackServer, index);
            }
        }
        return index;
    }

    private static void indexDatabase(DatabaseObject db, String server, CatalogLookupIndex index) {
        if (db == null) {
            return;
        }
        String dbName = emptyIfNull(db.getName());
        indexContainerLists(server, dbName, "", db.getTables(), db.getViews(), db.getOthers(),
                db.getProcedures(), db.getFunctions(), db.getTriggers(), db.getPackages(), db.getProcesses(), index);

        if (db.getSchemas() != null) {
            for (SchemaObject sch : db.getSchemas()) {
                indexSchema(sch, server, dbName, index);
            }
        }
    }

    private static void indexSchema(SchemaObject sch, String server, String dbName, CatalogLookupIndex index) {
        if (sch == null) {
            return;
        }
        String schemaName = emptyIfNull(sch.getName());
        indexContainerLists(server, dbName, schemaName, sch.getTables(), sch.getViews(), sch.getOthers(),
                sch.getProcedures(), sch.getFunctions(), sch.getTriggers(), sch.getPackages(), sch.getProcesses(), index);
    }

    private static void indexContainerLists(
            String server,
            String dbName,
            String schemaName,
            List<DbObject> tables,
            List<DbObject> views,
            List<DbObject> others,
            List<ProcedureObject> procedures,
            List<ProcedureObject> functions,
            List<ProcedureObject> triggers,
            List<PackageObject> packages,
            List<ProcessNode> processes,
            CatalogLookupIndex index) {
        registerDbObjects(tables, server, dbName, schemaName, index);
        registerDbObjects(views, server, dbName, schemaName, index);
        registerDbObjects(others, server, dbName, schemaName, index);
        registerRoutines(procedures, index);
        registerRoutines(functions, index);
        registerRoutines(triggers, index);
        if (packages != null) {
            for (PackageObject pkg : packages) {
                if (pkg == null) {
                    continue;
                }
                if (pkg.getId() != null && pkg.getName() != null) {
                    index.routineNamesById.put(pkg.getId(), pkg.getName());
                }
                registerRoutines(pkg.getProcedures(), index);
                registerRoutines(pkg.getFunctions(), index);
                registerRoutines(pkg.getTriggers(), index);
            }
        }
        if (processes != null) {
            for (ProcessNode p : processes) {
                if (p != null && p.getId() != null) {
                    index.processesById.putIfAbsent(p.getId(), p);
                }
            }
        }
    }

    private static void registerDbObjects(
            List<DbObject> list,
            String server,
            String dbName,
            String schemaName,
            CatalogLookupIndex index) {
        if (list == null) {
            return;
        }
        for (DbObject dbo : list) {
            if (dbo == null) {
                continue;
            }
            String effServer = nonBlankOr(dbo.getServer(), server);
            String effDb = nonBlankOr(dbo.getDatabase(), dbName);
            String effSchema = nonBlankOr(dbo.getSchema(), schemaName);
            IndexedDbObject entry = new IndexedDbObject(dbo, effServer, effDb, effSchema);
            if (dbo.getId() != null && !dbo.getId().isBlank()) {
                if (index.byId.putIfAbsent(dbo.getId().trim(), entry) == null) {
                    index.orderedObjects.add(entry);
                }
            } else {
                index.orderedObjects.add(entry);
            }
            if (dbo.getName() != null && !dbo.getName().isBlank()) {
                String n = dbo.getName().trim().toLowerCase(Locale.ROOT);
                index.byNameFallback.putIfAbsent(n, entry);
                if (!effSchema.isEmpty()) {
                    index.byNameFallback.putIfAbsent(effSchema.toLowerCase(Locale.ROOT) + "." + n, entry);
                }
                if (!effDb.isEmpty() && !effSchema.isEmpty()) {
                    index.byNameFallback.putIfAbsent(
                            effDb.toLowerCase(Locale.ROOT) + "." + effSchema.toLowerCase(Locale.ROOT) + "." + n, entry);
                }
            }
        }
    }

    private static void registerRoutines(List<ProcedureObject> routines, CatalogLookupIndex index) {
        if (routines == null) {
            return;
        }
        for (ProcedureObject r : routines) {
            if (r != null && r.getId() != null && r.getName() != null) {
                index.routineNamesById.put(r.getId(), r.getName());
            }
        }
    }

    private static ParsedName parseQualifiedName(String raw) {
        String cleaned = raw.trim().replace("\"", "").replace("`", "").replace("[", "").replace("]", "");
        String[] parts = cleaned.split("\\.");
        return switch (parts.length) {
            case 1 -> new ParsedName("", "", "", parts[0]);
            case 2 -> new ParsedName("", "", parts[0], parts[1]);
            case 3 -> new ParsedName("", parts[0], parts[1], parts[2]);
            default -> new ParsedName(parts[0], parts[1], parts[2], parts[parts.length - 1]);
        };
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    private static String nonBlankOr(String candidate, String fallback) {
        return (candidate != null && !candidate.isBlank()) ? candidate.trim() : fallback;
    }

    private static String emptyIfNull(String s) {
        return s == null ? "" : s.trim();
    }

    private record ParsedName(String server, String database, String schema, String object) {
    }

    private record ResolvedEndpoint(
            String server,
            String database,
            String schema,
            String tableId,
            String table,
            String columnId,
            String column,
            String dataType) {
    }

    private static final class IndexedDbObject {
        final DbObject dbObject;
        final String server;
        final String database;
        final String schema;
        final Map<String, Column> columnsById = new HashMap<>();
        final Map<String, Column> columnsByNameLower = new HashMap<>();

        IndexedDbObject(DbObject dbObject, String server, String database, String schema) {
            this.dbObject = dbObject;
            this.server = server;
            this.database = database;
            this.schema = schema;
            if (dbObject.getColumns() != null) {
                for (Column c : dbObject.getColumns()) {
                    if (c == null) {
                        continue;
                    }
                    if (c.getId() != null && !c.getId().isBlank()) {
                        columnsById.put(c.getId().trim(), c);
                    }
                    if (c.getName() != null && !c.getName().isBlank()) {
                        columnsByNameLower.putIfAbsent(c.getName().trim().toLowerCase(Locale.ROOT), c);
                    }
                }
            }
        }
    }

    private static final class CatalogLookupIndex {
        final List<IndexedDbObject> orderedObjects = new ArrayList<>();
        final Map<String, IndexedDbObject> byId = new LinkedHashMap<>();
        final Map<String, IndexedDbObject> byNameFallback = new HashMap<>();
        final Map<String, String> routineNamesById = new HashMap<>();
        final Map<String, ProcessNode> processesById = new HashMap<>();

        IndexedDbObject findByQualifiedName(String rawName) {
            String key = rawName.trim().replace("\"", "").replace("`", "").replace("[", "").replace("]", "")
                    .toLowerCase(Locale.ROOT);
            IndexedDbObject direct = byNameFallback.get(key);
            if (direct != null) {
                return direct;
            }
            int dot = key.lastIndexOf('.');
            return dot >= 0 ? byNameFallback.get(key.substring(dot + 1)) : null;
        }

        String resolveProcedureOrProcessName(Relationship rel) {
            if (rel.getProcedureId() != null) {
                String procName = routineNamesById.get(rel.getProcedureId());
                if (procName != null && !procName.isBlank()) {
                    return procName;
                }
            }
            if (rel.getProcessId() != null) {
                ProcessNode pn = processesById.get(rel.getProcessId());
                if (pn != null) {
                    if (pn.getProcedureName() != null && !pn.getProcedureName().isBlank()) {
                        return pn.getProcedureName();
                    }
                    if (pn.getName() != null && !pn.getName().isBlank()) {
                        return pn.getName();
                    }
                }
            }
            return "";
        }
    }
}
