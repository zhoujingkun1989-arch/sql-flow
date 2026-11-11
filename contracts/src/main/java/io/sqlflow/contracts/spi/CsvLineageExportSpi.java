package io.sqlflow.contracts.spi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.sqlflow.contracts.lineage.DataFlow;
import java.util.List;

/**
 * Frozen Stage 2A SPI for 15/16-column {@code lineage.csv}, 9-column {@code metadata.csv}, and
 * {@code metadata_lineage.zip} archive generation ({@code 03 §1.5.1–§1.5.2}).
 */
public interface CsvLineageExportSpi {

    /** 15-column snake_case header for {@code exportLineageAsCsv} (without {@code transform}). */
    List<String> WEB_JOB_15_COLUMN_HEADER = List.of(
            "source_db",
            "source_schema",
            "source_table_id",
            "source_table",
            "source_column_id",
            "source_column",
            "target_db",
            "target_schema",
            "target_table_id",
            "target_table",
            "target_column_id",
            "target_column",
            "relation_type",
            "effect_type",
            "procedure");

    /** 16-column snake_case header for {@code exportLineageAsCsv} (with {@code transform}). */
    List<String> WEB_JOB_16_COLUMN_HEADER = List.of(
            "source_db",
            "source_schema",
            "source_table_id",
            "source_table",
            "source_column_id",
            "source_column",
            "target_db",
            "target_schema",
            "target_table_id",
            "target_table",
            "target_column_id",
            "target_column",
            "relation_type",
            "effect_type",
            "procedure",
            "transform");

    /** 15-column PascalCase header for {@code /generation/sqlflow/exportCsv} (without {@code TargetDataType}). */
    List<String> GENERATION_15_COLUMN_HEADER = List.of(
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
            "RelationId",
            "RelationType",
            "EffectType",
            "Transforms",
            "OccurrencesNumber");

    /** 16-column PascalCase header for {@code /generation/sqlflow/exportCsv} (with {@code TargetDataType}). */
    List<String> GENERATION_16_COLUMN_HEADER = List.of(
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
            "RelationId",
            "RelationType",
            "EffectType",
            "Transforms",
            "OccurrencesNumber",
            "TargetDataType");

    /** 9-column header for {@code metadata.csv} inside {@code metadata_lineage.zip}. */
    List<String> METADATA_CSV_9_COLUMN_HEADER = List.of(
            "db_obj_id",
            "server",
            "database",
            "schema",
            "table",
            "column_id",
            "column",
            "data_type",
            "comment");

    /** Name of the metadata entry inside {@code metadata_lineage.zip}. */
    String METADATA_CSV_ENTRY_NAME = "metadata.csv";

    /** Name of the lineage entry inside {@code metadata_lineage.zip}. */
    String LINEAGE_CSV_ENTRY_NAME = "lineage.csv";

    /**
     * Exports column/table lineage rows from {@code dataFlow} as RFC-4180 CSV text (15 or 16 columns).
     */
    String exportLineageCsv(DataFlow dataFlow, CsvExportOptions options);

    /**
     * Exports catalog object/column metadata from {@code dataFlow} as 9-column {@code metadata.csv} text.
     */
    String exportMetadataCsv(DataFlow dataFlow);

    /**
     * Packages {@code metadata.csv} and {@code lineage.csv} into a {@code metadata_lineage.zip} byte array.
     */
    byte[] exportMetadataLineageZip(DataFlow dataFlow, CsvExportOptions options);

    enum CsvHeaderStyle {
        /** {@code source_db,...,procedure[,transform]} (15 or 16 columns). */
        WEB_JOB_SNAKE_CASE,
        /** {@code SourceServer,...,OccurrencesNumber[,TargetDataType]} (15 or 16 columns). */
        GENERATION_PASCAL_CASE
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class CsvExportOptions {
        private CsvHeaderStyle headerStyle = CsvHeaderStyle.WEB_JOB_SNAKE_CASE;
        private boolean includeSixteenthColumn = true;
        private char delimiter = ',';
        private boolean includeDirectDataflow = true;
        private boolean includeIndirectDataflow = false;

        public CsvExportOptions() {
        }

        public static CsvExportOptions webJob16Col() {
            CsvExportOptions opts = new CsvExportOptions();
            opts.setHeaderStyle(CsvHeaderStyle.WEB_JOB_SNAKE_CASE);
            opts.setIncludeSixteenthColumn(true);
            return opts;
        }

        public static CsvExportOptions webJob15Col() {
            CsvExportOptions opts = new CsvExportOptions();
            opts.setHeaderStyle(CsvHeaderStyle.WEB_JOB_SNAKE_CASE);
            opts.setIncludeSixteenthColumn(false);
            return opts;
        }

        public static CsvExportOptions generationPascalCase(boolean showDataType) {
            CsvExportOptions opts = new CsvExportOptions();
            opts.setHeaderStyle(CsvHeaderStyle.GENERATION_PASCAL_CASE);
            opts.setIncludeSixteenthColumn(showDataType);
            return opts;
        }

        public CsvHeaderStyle getHeaderStyle() {
            return headerStyle;
        }

        public void setHeaderStyle(CsvHeaderStyle headerStyle) {
            this.headerStyle = headerStyle != null ? headerStyle : CsvHeaderStyle.WEB_JOB_SNAKE_CASE;
        }

        public boolean isIncludeSixteenthColumn() {
            return includeSixteenthColumn;
        }

        public void setIncludeSixteenthColumn(boolean includeSixteenthColumn) {
            this.includeSixteenthColumn = includeSixteenthColumn;
        }

        public char getDelimiter() {
            return delimiter;
        }

        public void setDelimiter(char delimiter) {
            this.delimiter = delimiter;
        }

        public boolean isIncludeDirectDataflow() {
            return includeDirectDataflow;
        }

        public void setIncludeDirectDataflow(boolean includeDirectDataflow) {
            this.includeDirectDataflow = includeDirectDataflow;
        }

        public boolean isIncludeIndirectDataflow() {
            return includeIndirectDataflow;
        }

        public void setIncludeIndirectDataflow(boolean includeIndirectDataflow) {
            this.includeIndirectDataflow = includeIndirectDataflow;
        }
    }
}
