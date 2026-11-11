package io.sqlflow.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import io.sqlflow.connectors.export.CsvLineageExportService;
import io.sqlflow.connectors.extractor.CsvMetadataExtractor;
import io.sqlflow.connectors.util.ConnectorsJson;
import io.sqlflow.contracts.graph.SqlflowJSON;
import io.sqlflow.contracts.lineage.Column;
import io.sqlflow.contracts.lineage.Coordinate;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.DatabaseObject;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.ProcedureObject;
import io.sqlflow.contracts.lineage.Relationship;
import io.sqlflow.contracts.lineage.RelationshipElement;
import io.sqlflow.contracts.lineage.SchemaObject;
import io.sqlflow.contracts.lineage.ServerObject;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.contracts.lineage.Transform;
import io.sqlflow.contracts.spi.CsvLineageExportSpi;
import io.sqlflow.contracts.spi.CsvLineageExportSpi.CsvExportOptions;
import io.sqlflow.contracts.spi.FileExtractorSpi.ExtractionRequest;
import io.sqlflow.contracts.spi.FileExtractorSpi.ExtractorFormat;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class CsvLineageExportTest {

    private final CsvLineageExportService exportService = new CsvLineageExportService();

    @Test
    void exports15And16ColumnWebJobSnakeCaseGenerationPascalCaseAndCoordinateHeadersWithRfc4180Escaping() {
        DataFlow df = buildSampleDataFlow();

        // 1. Web Job 16-column snake_case
        String web16 = exportService.exportLineageCsv(df, CsvExportOptions.webJob16Col());
        List<List<String>> web16Rows = CsvMetadataExtractor.parseDelimitedRecords(web16, ',', '"', '"');
        assertThat(web16Rows).hasSize(2);
        assertThat(web16Rows.get(0)).containsExactlyElementsOf(CsvLineageExportSpi.WEB_JOB_16_COLUMN_HEADER);
        assertThat(web16Rows.get(1)).containsExactly(
                "sales_db",
                "raw",
                "10",
                "orders",
                "11",
                "gross_amount",
                "mart_db",
                "analytics",
                "20",
                "daily_revenue",
                "21",
                "net_revenue",
                "fdd",
                "insert",
                "sp_load_revenue",
                "ROUND(o.gross_amount * 0.9, 2), \"net\"\n-- adjusted");

        // 2. Web Job 15-column snake_case (no transform column)
        String web15 = exportService.exportLineageCsv(df, CsvExportOptions.webJob15Col());
        List<List<String>> web15Rows = CsvMetadataExtractor.parseDelimitedRecords(web15, ',', '"', '"');
        assertThat(web15Rows.get(0)).containsExactlyElementsOf(CsvLineageExportSpi.WEB_JOB_15_COLUMN_HEADER);
        assertThat(web15Rows.get(1)).hasSize(15);

        // 3. Generation 16-column PascalCase (with TargetDataType & OccurrencesNumber)
        String gen16 = exportService.exportLineageCsv(df, CsvExportOptions.generationPascalCase(true));
        List<List<String>> gen16Rows = CsvMetadataExtractor.parseDelimitedRecords(gen16, ',', '"', '"');
        assertThat(gen16Rows.get(0)).containsExactlyElementsOf(CsvLineageExportSpi.GENERATION_16_COLUMN_HEADER);
        assertThat(gen16Rows.get(1)).containsExactly(
                "prod-snowflake",
                "sales_db",
                "raw",
                "orders",
                "gross_amount",
                "prod-snowflake",
                "mart_db",
                "analytics",
                "daily_revenue",
                "net_revenue",
                "rel-1",
                "fdd",
                "insert",
                "ROUND(o.gross_amount * 0.9, 2), \"net\"\n-- adjusted",
                "2",
                "DECIMAL(18,2)");

        // 4. Generation 15-column PascalCase + includeIndirectDataflow = true includes fdr edge
        CsvExportOptions gen15WithFdr = CsvExportOptions.generationPascalCase(false);
        gen15WithFdr.setIncludeIndirectDataflow(true);
        String gen15 = exportService.exportLineageCsv(df, gen15WithFdr);
        List<List<String>> gen15Rows = CsvMetadataExtractor.parseDelimitedRecords(gen15, ',', '"', '"');
        assertThat(gen15Rows.get(0)).containsExactlyElementsOf(CsvLineageExportSpi.GENERATION_15_COLUMN_HEADER);
        assertThat(gen15Rows).hasSize(3);
        assertThat(gen15Rows.get(2).get(11)).isEqualTo("fdr");

        // 5. Coordinate / SqlFile 15-column & 16-column PascalCase header (OBL-087)
        String coord15 = exportService.exportCoordinateLineageCsv(df, false, ',', true, false);
        List<List<String>> coord15Rows = CsvMetadataExtractor.parseDelimitedRecords(coord15, ',', '"', '"');
        assertThat(coord15Rows.get(0)).containsExactlyElementsOf(CsvLineageExportService.COORDINATE_15_COLUMN_HEADER);
        assertThat(coord15Rows.get(1).get(12)).isEqualTo("[4,8,etl_01.sql],[4,22,etl_01.sql],[9,12,etl_01.sql],[9,26,etl_01.sql]");
        assertThat(coord15Rows.get(1).get(13)).isEqualTo("etl_01.sql");

        String coord16 = exportService.exportCoordinateLineageCsv(df, true, ',', true, false);
        List<List<String>> coord16Rows = CsvMetadataExtractor.parseDelimitedRecords(coord16, ',', '"', '"');
        assertThat(coord16Rows.get(0)).containsExactlyElementsOf(CsvLineageExportService.COORDINATE_16_COLUMN_HEADER);
        assertThat(coord16Rows.get(1).get(15)).isEqualTo("DECIMAL(18,2)");
    }

    @Test
    void exports9ColumnMetadataCsvExcludingSyntheticRelationRowsAndPackagesValidMetadataLineageZip() throws Exception {
        DataFlow df = buildSampleDataFlow();

        String metaCsv = exportService.exportMetadataCsv(df);
        List<List<String>> metaRows = CsvMetadataExtractor.parseDelimitedRecords(metaCsv, ',', '"', '"');
        assertThat(metaRows.get(0)).containsExactlyElementsOf(CsvLineageExportSpi.METADATA_CSV_9_COLUMN_HEADER);
        // 2 physical columns on orders + 1 physical column on daily_revenue (RelationRows & $constant skipped!)
        assertThat(metaRows).hasSize(4);
        assertThat(metaCsv).doesNotContain("RelationRows").doesNotContain("$constant");

        // Package metadata_lineage.zip from SqlflowJSON and verify entries + round-trip via parseJsonAsMetadataLineage
        SqlflowJSON graphPayload = new SqlflowJSON();
        graphPayload.setSqlflow(df);
        String jsonPayload = ConnectorsJson.toJson(graphPayload);

        byte[] zipBytes = exportService.parseJsonAsMetadataLineage(
                jsonPayload, "sales_db,mart_db", null, null, CsvExportOptions.generationPascalCase(true));
        assertThat(zipBytes).isNotEmpty();

        Map<String, String> unzipped = unzipToStrings(zipBytes);
        assertThat(unzipped).containsOnlyKeys(
                CsvLineageExportSpi.METADATA_CSV_ENTRY_NAME,
                CsvLineageExportSpi.LINEAGE_CSV_ENTRY_NAME);
        assertThat(unzipped.get("metadata.csv")).contains("orders").contains("daily_revenue");
        assertThat(unzipped.get("lineage.csv")).startsWith("SourceServer,SourceDatabase,SourceSchema");

        // Round-trip lineage.csv back into CsvMetadataExtractor
        ExtractionRequest req = new ExtractionRequest();
        req.setFormat(ExtractorFormat.CSV);
        req.setTargetVendor(EDbVendor.dbvsnowflake);
        req.setFiles(Map.of("lineage.csv", unzipped.get("lineage.csv").getBytes(StandardCharsets.UTF_8)));

        var extracted = new CsvMetadataExtractor().extract(req, req.getFiles());
        assertThat(extracted.getCatalog().getDatabases()).isNotEmpty();
        assertThat(extracted.getSqlUnits()).isNotEmpty();
        assertThat(extracted.getSqlUnits().get(0).getSql()).contains("INSERT INTO mart_db.analytics.daily_revenue");
    }

    private static DataFlow buildSampleDataFlow() {
        DbObject srcTable = new DbObject("10", "orders", "table");
        srcTable.setServer("prod-snowflake");
        srcTable.setDatabase("sales_db");
        srcTable.setSchema("raw");
        Column srcCol1 = new Column("11", "gross_amount", "DECIMAL(18,2)");
        srcCol1.setComment("Gross order total, USD");
        Column srcCol2 = new Column("12", "status", "VARCHAR(20)");
        Column srcRelRows = Column.relationRows("13");
        srcTable.setColumns(List.of(srcCol1, srcCol2, srcRelRows));

        DbObject tgtTable = new DbObject("20", "daily_revenue", "view");
        tgtTable.setServer("prod-snowflake");
        tgtTable.setDatabase("mart_db");
        tgtTable.setSchema("analytics");
        Column tgtCol = new Column("21", "net_revenue", "DECIMAL(18,2)");
        tgtTable.setColumns(List.of(tgtCol));

        DbObject constantPseudo = new DbObject("99", "$constant", "constant");
        constantPseudo.setColumns(List.of(new Column("100", "0.9", "NUMBER")));

        ProcedureObject proc = new ProcedureObject("30", "sp_load_revenue", "procedure");

        SchemaObject rawSchema = new SchemaObject("raw");
        rawSchema.setTables(List.of(srcTable));
        rawSchema.setOthers(List.of(constantPseudo));
        DatabaseObject salesDb = new DatabaseObject("sales_db");
        salesDb.setSchemas(List.of(rawSchema));

        SchemaObject analyticsSchema = new SchemaObject("analytics");
        analyticsSchema.setViews(List.of(tgtTable));
        analyticsSchema.setProcedures(List.of(proc));
        DatabaseObject martDb = new DatabaseObject("mart_db");
        martDb.setSchemas(List.of(analyticsSchema));

        ServerObject server = new ServerObject("prod-snowflake", "dbvsnowflake", true, true);
        server.setDatabases(List.of(salesDb, martDb));

        SqlflowCatalog catalog = new SqlflowCatalog();
        catalog.setServers(List.of(server));
        catalog.setDatabases(List.of(salesDb, martDb));

        // Direct fdd relationship with multi-line transform & 2 coordinate spans
        Relationship fdd = new Relationship();
        fdd.setId("rel-1");
        fdd.setType("fdd");
        fdd.setEffectType("insert");
        fdd.setProcedureId("30");
        fdd.setSqlHash("etl_01.sql");
        RelationshipElement fddTarget = new RelationshipElement("21", "net_revenue", "20", "mart_db.analytics.daily_revenue");
        RelationshipElement fddSource = new RelationshipElement("11", "gross_amount", "10", "sales_db.raw.orders");
        fddSource.setCoordinates(List.of(
                new Coordinate(4, 8, "etl_01.sql"),
                new Coordinate(4, 22, "etl_01.sql"),
                new Coordinate(9, 12, "etl_01.sql"),
                new Coordinate(9, 26, "etl_01.sql")));
        fddSource.setTransforms(List.of(new Transform("expression", "ROUND(o.gross_amount * 0.9, 2), \"net\"\n-- adjusted")));
        fdd.setTarget(fddTarget);
        fdd.setSources(List.of(fddSource));

        // Indirect fdr relationship (WHERE filter)
        Relationship fdr = new Relationship();
        fdr.setId("rel-2");
        fdr.setType("fdr");
        fdr.setEffectType("where");
        fdr.setTarget(new RelationshipElement("21", "net_revenue", "20", "mart_db.analytics.daily_revenue"));
        RelationshipElement fdrSource = new RelationshipElement("12", "status", "10", "sales_db.raw.orders");
        fdrSource.setTransforms(List.of(new Transform("where", "o.status = 'PAID'")));
        fdr.setSources(List.of(fdrSource));

        DataFlow df = new DataFlow();
        df.setDbvendor("dbvsnowflake");
        df.setDbobjs(catalog);
        df.setRelationships(List.of(fdd, fdr));
        return df;
    }

    private static Map<String, String> unzipToStrings(byte[] zipBytes) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
