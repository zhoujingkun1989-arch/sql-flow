package io.sqlflow.contracts.spi;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import io.sqlflow.contracts.lineage.SqlInfo;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Frozen Stage 2A SPI for Non-JDBC File & Archive Extractors ({@code metadata-connectors}) ({@code 00 §5.2}).
 *
 * <p>Extracts catalog metadata and executable {@link SqlInfo} lineage units from {@code dbt} ({@code manifest.json}
 * + optional {@code catalog.json}), Redshift query logs ({@code redshiftlog}), PySpark scripts ({@code pyspark}),
 * CSV/TSV schema & procedure dumps ({@code csv}), and Grabit / SQLDep JSON bundles.
 */
public interface FileExtractorSpi {

    default Set<ExtractorFormat> supportedFormats() {
        return EnumSet.allOf(ExtractorFormat.class);
    }

    ExtractionResult extract(ExtractionRequest request);

    enum ExtractorFormat {
        DBT("dbt"),
        REDSHIFT_LOG("redshiftlog"),
        PYSPARK("pyspark"),
        CSV("csv"),
        GRABIT_JSON("grabit"),
        SQLDEP_JSON("sqldep");

        private final String wireValue;

        ExtractorFormat(String wireValue) {
            this.wireValue = wireValue;
        }

        @JsonValue
        public String getWireValue() {
            return wireValue;
        }

        @JsonCreator
        public static ExtractorFormat fromWire(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String norm = raw.trim().toLowerCase(Locale.ROOT);
            for (ExtractorFormat fmt : values()) {
                if (fmt.wireValue.equals(norm) || fmt.name().toLowerCase(Locale.ROOT).equals(norm)) {
                    return fmt;
                }
            }
            throw new IllegalArgumentException("Unknown ExtractorFormat: " + raw);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class ExtractionRequest {
        private ExtractorFormat format;
        private EDbVendor targetVendor;
        private Map<String, byte[]> files;
        private String defaultServer;
        private String defaultDatabase;
        private String defaultSchema;

        public ExtractionRequest() {
        }

        public ExtractorFormat getFormat() {
            return format;
        }

        public void setFormat(ExtractorFormat format) {
            this.format = format;
        }

        public EDbVendor getTargetVendor() {
            return targetVendor;
        }

        public void setTargetVendor(EDbVendor targetVendor) {
            this.targetVendor = targetVendor;
        }

        public Map<String, byte[]> getFiles() {
            return files;
        }

        public void setFiles(Map<String, byte[]> files) {
            this.files = files;
        }

        public String getDefaultServer() {
            return defaultServer;
        }

        public void setDefaultServer(String defaultServer) {
            this.defaultServer = defaultServer;
        }

        public String getDefaultDatabase() {
            return defaultDatabase;
        }

        public void setDefaultDatabase(String defaultDatabase) {
            this.defaultDatabase = defaultDatabase;
        }

        public String getDefaultSchema() {
            return defaultSchema;
        }

        public void setDefaultSchema(String defaultSchema) {
            this.defaultSchema = defaultSchema;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class ExtractionResult {
        private EDbVendor resolvedVendor;
        private SqlflowCatalog catalog;
        private List<SqlInfo> sqlUnits;
        private String exporterMetadataJson;
        private List<String> warnings;

        public ExtractionResult() {
        }

        public EDbVendor getResolvedVendor() {
            return resolvedVendor;
        }

        public void setResolvedVendor(EDbVendor resolvedVendor) {
            this.resolvedVendor = resolvedVendor;
        }

        public SqlflowCatalog getCatalog() {
            return catalog;
        }

        public void setCatalog(SqlflowCatalog catalog) {
            this.catalog = catalog;
        }

        public List<SqlInfo> getSqlUnits() {
            return sqlUnits;
        }

        public void setSqlUnits(List<SqlInfo> sqlUnits) {
            this.sqlUnits = sqlUnits;
        }

        public String getExporterMetadataJson() {
            return exporterMetadataJson;
        }

        public void setExporterMetadataJson(String exporterMetadataJson) {
            this.exporterMetadataJson = exporterMetadataJson;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        public void setWarnings(List<String> warnings) {
            this.warnings = warnings;
        }
    }
}
