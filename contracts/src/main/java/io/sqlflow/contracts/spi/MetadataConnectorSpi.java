package io.sqlflow.contracts.spi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.sqlflow.contracts.domain.DataSource;
import io.sqlflow.contracts.lineage.SqlInfo;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Frozen Stage 2A SPI for Live JDBC Metadata Connectors ({@code metadata-connectors}) ({@code 00 §5.2}, {@code 02 §4.3}).
 *
 * <p>Covers {@code testConnection} and live schema/view/procedure metadata harvesting across the 14 supported
 * JDBC database dialects, emitting canonical Exporter Metadata JSON and structured {@link SqlflowCatalog} objects.
 */
public interface MetadataConnectorSpi {

    /**
     * Canonical 14 live JDBC harvester dialects in SQLFlow 8.2.2.15.
     */
    Set<EDbVendor> SUPPORTED_14_JDBC_VENDORS = Collections.unmodifiableSet(EnumSet.of(
            EDbVendor.dbvmysql,
            EDbVendor.dbvoracle,
            EDbVendor.dbvpostgresql,
            EDbVendor.dbvmssql,
            EDbVendor.dbvazuresql,
            EDbVendor.dbvsnowflake,
            EDbVendor.dbvredshift,
            EDbVendor.dbvgreenplum,
            EDbVendor.dbvteradata,
            EDbVendor.dbvdb2,
            EDbVendor.dbvnetezza,
            EDbVendor.dbvhive,
            EDbVendor.dbvimpala,
            EDbVendor.dbvsybase));

    /**
     * Returns the set of {@link EDbVendor} dialects supported for live JDBC connection and metadata harvesting.
     */
    default Set<EDbVendor> supportedVendors() {
        return SUPPORTED_14_JDBC_VENDORS;
    }

    /**
     * Tests JDBC connectivity and credentials for {@code POST /sqlflow/job/testConnection}.
     */
    ConnectionTestResult testConnection(EDbVendor vendor, DataSource dataSource);

    /**
     * Harvests catalogs, schemas, tables, columns, views, procedures, and functions from a live JDBC data source
     * into Exporter Metadata JSON and structured {@link SqlflowCatalog} + {@link SqlInfo} units.
     */
    MetadataHarvestResult harvestMetadata(EDbVendor vendor, DataSource dataSource);

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class ConnectionTestResult {
        private boolean connected;
        private String productName;
        private String productVersion;
        private long latencyMs;
        private String errorMessage;

        public ConnectionTestResult() {
        }

        public static ConnectionTestResult ok(String productName, String productVersion, long latencyMs) {
            ConnectionTestResult r = new ConnectionTestResult();
            r.setConnected(true);
            r.setProductName(productName);
            r.setProductVersion(productVersion);
            r.setLatencyMs(latencyMs);
            return r;
        }

        public static ConnectionTestResult fail(String errorMessage) {
            ConnectionTestResult r = new ConnectionTestResult();
            r.setConnected(false);
            r.setErrorMessage(errorMessage);
            return r;
        }

        public boolean isConnected() {
            return connected;
        }

        public void setConnected(boolean connected) {
            this.connected = connected;
        }

        public String getProductName() {
            return productName;
        }

        public void setProductName(String productName) {
            this.productName = productName;
        }

        public String getProductVersion() {
            return productVersion;
        }

        public void setProductVersion(String productVersion) {
            this.productVersion = productVersion;
        }

        public long getLatencyMs() {
            return latencyMs;
        }

        public void setLatencyMs(long latencyMs) {
            this.latencyMs = latencyMs;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class MetadataHarvestResult {
        private EDbVendor vendor;
        private SqlflowCatalog catalog;
        private List<SqlInfo> extractedSqlUnits;
        private String exporterMetadataJson;
        private List<String> warnings;

        public MetadataHarvestResult() {
        }

        public EDbVendor getVendor() {
            return vendor;
        }

        public void setVendor(EDbVendor vendor) {
            this.vendor = vendor;
        }

        public SqlflowCatalog getCatalog() {
            return catalog;
        }

        public void setCatalog(SqlflowCatalog catalog) {
            this.catalog = catalog;
        }

        public List<SqlInfo> getExtractedSqlUnits() {
            return extractedSqlUnits;
        }

        public void setExtractedSqlUnits(List<SqlInfo> extractedSqlUnits) {
            this.extractedSqlUnits = extractedSqlUnits;
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
