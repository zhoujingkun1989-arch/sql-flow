package io.sqlflow.server.spi;

import io.sqlflow.contracts.lineage.Column;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.Relationship;
import io.sqlflow.contracts.lineage.RelationshipElement;
import io.sqlflow.contracts.lineage.Transform;
import io.sqlflow.contracts.spi.CsvLineageExportSpi;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Built-in RFC-4180 CSV & {@code metadata_lineage.zip} exporter implementing {@link CsvLineageExportSpi}.
 */
public class FallbackCsvLineageExportSpi implements CsvLineageExportSpi {

    @Override
    public String exportLineageCsv(DataFlow dataFlow, CsvExportOptions options) {
        CsvExportOptions opts = options != null ? options : CsvExportOptions.webJob16Col();
        char delim = opts.getDelimiter();
        StringBuilder sb = new StringBuilder(1024);

        List<String> header;
        if (opts.getHeaderStyle() == CsvHeaderStyle.GENERATION_PASCAL_CASE) {
            header = opts.isIncludeSixteenthColumn() ? GENERATION_16_COLUMN_HEADER : GENERATION_15_COLUMN_HEADER;
        } else {
            header = opts.isIncludeSixteenthColumn() ? WEB_JOB_16_COLUMN_HEADER : WEB_JOB_15_COLUMN_HEADER;
        }
        appendRow(sb, header, delim);

        if (dataFlow == null || dataFlow.getRelationships() == null) {
            return sb.toString();
        }

        Map<String, DbObject> objById = new LinkedHashMap<>();
        Map<String, Column> colById = new LinkedHashMap<>();
        for (DbObject obj : CatalogUtils.collectAllDbObjects(dataFlow)) {
            if (obj.getId() != null) {
                objById.put(obj.getId(), obj);
            }
            if (obj.getColumns() != null) {
                for (Column c : obj.getColumns()) {
                    if (c != null && c.getId() != null) {
                        colById.put(c.getId(), c);
                    }
                }
            }
        }

        for (Relationship rel : dataFlow.getRelationships()) {
            if (rel == null || rel.getTarget() == null || rel.getSources() == null) {
                continue;
            }
            String relType = rel.getType() != null ? rel.getType() : "fdd";
            if ("fdd".equalsIgnoreCase(relType) && !opts.isIncludeDirectDataflow()) {
                continue;
            }
            if ("fddi".equalsIgnoreCase(relType) && !opts.isIncludeIndirectDataflow()) {
                continue;
            }
            RelationshipElement tgt = rel.getTarget();
            DbObject tgtObj = tgt.getParentId() != null ? objById.get(tgt.getParentId()) : null;
            Column tgtCol = tgt.getId() != null ? colById.get(tgt.getId()) : null;
            String tgtTransforms = formatTransforms(tgt.getTransforms());

            for (RelationshipElement src : rel.getSources()) {
                if (src == null) {
                    continue;
                }
                DbObject srcObj = src.getParentId() != null ? objById.get(src.getParentId()) : null;
                String transformText = !tgtTransforms.isEmpty() ? tgtTransforms : formatTransforms(src.getTransforms());

                List<String> row = new ArrayList<>(16);
                if (opts.getHeaderStyle() == CsvHeaderStyle.GENERATION_PASCAL_CASE) {
                    row.add(val(srcObj != null ? srcObj.getServer() : null));
                    row.add(val(srcObj != null ? srcObj.getDatabase() : null));
                    row.add(val(srcObj != null ? srcObj.getSchema() : null));
                    row.add(val(src.getParentName() != null ? src.getParentName() : (srcObj != null ? srcObj.getName() : null)));
                    row.add(val(src.getColumn() != null ? src.getColumn() : src.getName()));
                    row.add(val(tgtObj != null ? tgtObj.getServer() : null));
                    row.add(val(tgtObj != null ? tgtObj.getDatabase() : null));
                    row.add(val(tgtObj != null ? tgtObj.getSchema() : null));
                    row.add(val(tgt.getParentName() != null ? tgt.getParentName() : (tgtObj != null ? tgtObj.getName() : null)));
                    row.add(val(tgt.getColumn() != null ? tgt.getColumn() : tgt.getName()));
                    row.add(val(rel.getId()));
                    row.add(val(relType));
                    row.add(val(rel.getEffectType()));
                    row.add(transformText);
                    row.add("1");
                    if (opts.isIncludeSixteenthColumn()) {
                        row.add(val(tgtCol != null ? tgtCol.getDataType() : tgt.getColumnType()));
                    }
                } else {
                    row.add(val(srcObj != null ? srcObj.getDatabase() : null));
                    row.add(val(srcObj != null ? srcObj.getSchema() : null));
                    row.add(val(src.getParentId()));
                    row.add(val(src.getParentName() != null ? src.getParentName() : (srcObj != null ? srcObj.getName() : null)));
                    row.add(val(src.getId()));
                    row.add(val(src.getColumn() != null ? src.getColumn() : src.getName()));
                    row.add(val(tgtObj != null ? tgtObj.getDatabase() : null));
                    row.add(val(tgtObj != null ? tgtObj.getSchema() : null));
                    row.add(val(tgt.getParentId()));
                    row.add(val(tgt.getParentName() != null ? tgt.getParentName() : (tgtObj != null ? tgtObj.getName() : null)));
                    row.add(val(tgt.getId()));
                    row.add(val(tgt.getColumn() != null ? tgt.getColumn() : tgt.getName()));
                    row.add(val(relType));
                    row.add(val(rel.getEffectType()));
                    row.add(val(rel.getProcedureId()));
                    if (opts.isIncludeSixteenthColumn()) {
                        row.add(transformText);
                    }
                }
                appendRow(sb, row, delim);
            }
        }
        return sb.toString();
    }

    @Override
    public String exportMetadataCsv(DataFlow dataFlow) {
        StringBuilder sb = new StringBuilder(512);
        appendRow(sb, METADATA_CSV_9_COLUMN_HEADER, ',');
        for (DbObject obj : CatalogUtils.collectAllDbObjects(dataFlow)) {
            if (obj.getColumns() == null || obj.getColumns().isEmpty()) {
                appendRow(
                        sb,
                        List.of(
                                val(obj.getId()),
                                val(obj.getServer()),
                                val(obj.getDatabase()),
                                val(obj.getSchema()),
                                val(obj.getName()),
                                "",
                                "",
                                "",
                                ""),
                        ',');
            } else {
                for (Column col : obj.getColumns()) {
                    if (col == null || "system".equalsIgnoreCase(col.getSource())) {
                        continue;
                    }
                    appendRow(
                            sb,
                            List.of(
                                    val(obj.getId()),
                                    val(obj.getServer()),
                                    val(obj.getDatabase()),
                                    val(obj.getSchema()),
                                    val(obj.getName()),
                                    val(col.getId()),
                                    val(col.getName()),
                                    val(col.getDataType()),
                                    val(col.getComment())),
                            ',');
                }
            }
        }
        return sb.toString();
    }

    @Override
    public byte[] exportMetadataLineageZip(DataFlow dataFlow, CsvExportOptions options) {
        String metadataCsv = exportMetadataCsv(dataFlow);
        String lineageCsv = exportLineageCsv(dataFlow, options);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry(METADATA_CSV_ENTRY_NAME));
            zos.write(metadataCsv.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry(LINEAGE_CSV_ENTRY_NAME));
            zos.write(lineageCsv.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create metadata_lineage.zip", e);
        }
        return baos.toByteArray();
    }

    private static String formatTransforms(List<Transform> transforms) {
        if (transforms == null || transforms.isEmpty()) {
            return "";
        }
        return transforms.stream()
                .filter(t -> t != null && t.getCode() != null)
                .map(Transform::getCode)
                .collect(Collectors.joining("; "));
    }

    private static String val(String s) {
        return s != null ? s : "";
    }

    private static void appendRow(StringBuilder sb, List<String> cells, char delim) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(delim);
            }
            sb.append(escapeRfc4180(cells.get(i), delim));
        }
        sb.append("\r\n");
    }

    private static String escapeRfc4180(String cell, char delim) {
        if (cell == null) {
            return "";
        }
        boolean needsQuote = cell.indexOf(delim) >= 0
                || cell.indexOf('"') >= 0
                || cell.indexOf('\n') >= 0
                || cell.indexOf('\r') >= 0;
        if (!needsQuote) {
            return cell;
        }
        return "\"" + cell.replace("\"", "\"\"") + "\"";
    }
}
