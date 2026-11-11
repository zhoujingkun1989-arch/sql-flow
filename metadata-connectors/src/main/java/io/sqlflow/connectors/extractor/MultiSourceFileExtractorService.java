package io.sqlflow.connectors.extractor;

import io.sqlflow.contracts.spi.FileExtractorSpi;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Production implementation of {@link FileExtractorSpi} ({@code OBL-077, OBL-078}) supporting
 * {@code dbt}, Redshift query/audit logs, PySpark ({@code .py} &amp; {@code .ipynb}), CSV/TSV
 * (with 6-digit {@code csvFormat} &amp; custom enclose/escape chars), Grabit JSON, and SQLDep JSON.
 */
public class MultiSourceFileExtractorService implements FileExtractorSpi {

    private final DbtProjectExtractor dbtExtractor = new DbtProjectExtractor();
    private final RedshiftLogExtractor redshiftLogExtractor = new RedshiftLogExtractor();
    private final PySparkSqlExtractor pySparkExtractor = new PySparkSqlExtractor();
    private final CsvMetadataExtractor csvExtractor = new CsvMetadataExtractor();
    private final GrabitSqldepExtractor grabitSqldepExtractor = new GrabitSqldepExtractor();

    /**
     * Public no-arg constructor required for {@link java.util.ServiceLoader}.
     */
    public MultiSourceFileExtractorService() {
    }

    @Override
    public Set<ExtractorFormat> supportedFormats() {
        return EnumSet.allOf(ExtractorFormat.class);
    }

    @Override
    public ExtractionResult extract(ExtractionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("ExtractionRequest must not be null");
        }
        ExtractorFormat format = request.getFormat();
        if (format == null) {
            throw new IllegalArgumentException("ExtractionRequest.format must not be null");
        }
        Map<String, byte[]> unpacked = ArchiveUnpacker.unpackAll(request.getFiles());
        return switch (format) {
            case DBT -> dbtExtractor.extract(request, unpacked);
            case REDSHIFT_LOG -> redshiftLogExtractor.extract(request, unpacked);
            case PYSPARK -> pySparkExtractor.extract(request, unpacked);
            case CSV -> csvExtractor.extract(request, unpacked);
            case GRABIT_JSON, SQLDEP_JSON -> grabitSqldepExtractor.extract(request, unpacked);
        };
    }

    /**
     * Convenience overload for {@code FileExtractorController} CSV endpoints passing explicit 6-digit {@code csvFormat},
     * {@code objectCodeEncloseChar}, and {@code objectCodeEscapeChar} ({@code 02 §4.7, §6.4.4}).
     */
    public ExtractionResult extractCsv(
            ExtractionRequest request,
            String csvFormat,
            char objectCodeEncloseChar,
            char objectCodeEscapeChar) {
        if (request == null) {
            throw new IllegalArgumentException("ExtractionRequest must not be null");
        }
        Map<String, byte[]> unpacked = ArchiveUnpacker.unpackAll(request.getFiles());
        return csvExtractor.extractWithOptions(request, unpacked, csvFormat, objectCodeEncloseChar, objectCodeEscapeChar, null);
    }
}
