package io.sqlflow.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import io.sqlflow.connectors.export.CsvLineageExportService;
import io.sqlflow.connectors.extractor.MultiSourceFileExtractorService;
import io.sqlflow.connectors.jdbc.JdbcMetadataConnectorService;
import io.sqlflow.contracts.spi.CsvLineageExportSpi;
import io.sqlflow.contracts.spi.FileExtractorSpi;
import io.sqlflow.contracts.spi.MetadataConnectorSpi;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

class SpiRegistrationAndContractTest {

    @Test
    void serviceLoaderDiscoversAllThreeFrozenSpisViaPublicNoArgConstructors() {
        List<MetadataConnectorSpi> connectors = ServiceLoader.load(MetadataConnectorSpi.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        assertThat(connectors).hasSize(1);
        assertThat(connectors.get(0)).isInstanceOf(JdbcMetadataConnectorService.class);

        List<FileExtractorSpi> extractors = ServiceLoader.load(FileExtractorSpi.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        assertThat(extractors).hasSize(1);
        assertThat(extractors.get(0)).isInstanceOf(MultiSourceFileExtractorService.class);

        List<CsvLineageExportSpi> exporters = ServiceLoader.load(CsvLineageExportSpi.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        assertThat(exporters).hasSize(1);
        assertThat(exporters.get(0)).isInstanceOf(CsvLineageExportService.class);
    }

    @Test
    void supportedVendorsAndExtractorFormatsCoverAllCanonicalAndExtendedTargets() {
        MetadataConnectorSpi connector = new JdbcMetadataConnectorService();
        assertThat(connector.supportedVendors())
                .containsAll(MetadataConnectorSpi.SUPPORTED_14_JDBC_VENDORS)
                .contains(
                        EDbVendor.dbvclickhouse,
                        EDbVendor.dbvbigquery,
                        EDbVendor.dbvathena,
                        EDbVendor.dbvinformix,
                        EDbVendor.dbvsqlite,
                        EDbVendor.dbvduckdb,
                        EDbVendor.dbvodbc);

        FileExtractorSpi extractor = new MultiSourceFileExtractorService();
        assertThat(extractor.supportedFormats())
                .containsExactlyInAnyOrder(FileExtractorSpi.ExtractorFormat.values());
    }
}
