package io.sqlflow.server.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.contracts.spi.CsvLineageExportSpi;
import io.sqlflow.contracts.spi.DemoAstServiceSpi;
import io.sqlflow.contracts.spi.FileExtractorSpi;
import io.sqlflow.contracts.spi.GraphLayoutSpi;
import io.sqlflow.contracts.spi.JoinAnalysisSpi;
import io.sqlflow.contracts.spi.LineageEngineSpi;
import io.sqlflow.contracts.spi.MetadataConnectorSpi;
import java.util.ServiceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Discovers Stage 2B/2C {@link ServiceLoader} SPI implementations on the classpath and falls back to
 * clean deterministic built-in implementations when running standalone.
 */
@Configuration
public class SpiConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SpiConfiguration.class);

    @Bean
    public LineageEngineSpi lineageEngineSpi(ObjectMapper objectMapper) {
        return ServiceLoader.load(LineageEngineSpi.class)
                .findFirst()
                .map(provider -> {
                    log.info("Loaded external LineageEngineSpi provider: {}", provider.getClass().getName());
                    return provider;
                })
                .orElseGet(() -> {
                    log.info("Using built-in FallbackLineageEngineSpi");
                    return new FallbackLineageEngineSpi(objectMapper);
                });
    }

    @Bean
    public GraphLayoutSpi graphLayoutSpi() {
        return ServiceLoader.load(GraphLayoutSpi.class)
                .findFirst()
                .map(provider -> {
                    log.info("Loaded external GraphLayoutSpi provider: {}", provider.getClass().getName());
                    return provider;
                })
                .orElseGet(() -> {
                    log.info("Using built-in FallbackGraphLayoutSpi");
                    return new FallbackGraphLayoutSpi();
                });
    }

    @Bean
    public JoinAnalysisSpi joinAnalysisSpi() {
        return ServiceLoader.load(JoinAnalysisSpi.class)
                .findFirst()
                .map(provider -> {
                    log.info("Loaded external JoinAnalysisSpi provider: {}", provider.getClass().getName());
                    return provider;
                })
                .orElseGet(() -> {
                    log.info("Using built-in FallbackJoinAnalysisSpi");
                    return new FallbackJoinAnalysisSpi();
                });
    }

    @Bean
    public MetadataConnectorSpi metadataConnectorSpi(ObjectMapper objectMapper) {
        return ServiceLoader.load(MetadataConnectorSpi.class)
                .findFirst()
                .map(provider -> {
                    log.info("Loaded external MetadataConnectorSpi provider: {}", provider.getClass().getName());
                    return provider;
                })
                .orElseGet(() -> {
                    log.info("Using built-in FallbackMetadataConnectorSpi");
                    return new FallbackMetadataConnectorSpi(objectMapper);
                });
    }

    @Bean
    public FileExtractorSpi fileExtractorSpi(ObjectMapper objectMapper) {
        return ServiceLoader.load(FileExtractorSpi.class)
                .findFirst()
                .map(provider -> {
                    log.info("Loaded external FileExtractorSpi provider: {}", provider.getClass().getName());
                    return provider;
                })
                .orElseGet(() -> {
                    log.info("Using built-in FallbackFileExtractorSpi");
                    return new FallbackFileExtractorSpi(objectMapper);
                });
    }

    @Bean
    public CsvLineageExportSpi csvLineageExportSpi() {
        return ServiceLoader.load(CsvLineageExportSpi.class)
                .findFirst()
                .map(provider -> {
                    log.info("Loaded external CsvLineageExportSpi provider: {}", provider.getClass().getName());
                    return provider;
                })
                .orElseGet(() -> {
                    log.info("Using built-in FallbackCsvLineageExportSpi");
                    return new FallbackCsvLineageExportSpi();
                });
    }

    @Bean
    public DemoAstServiceSpi demoAstServiceSpi() {
        return ServiceLoader.load(DemoAstServiceSpi.class)
                .findFirst()
                .map(provider -> {
                    log.info("Loaded external DemoAstServiceSpi provider: {}", provider.getClass().getName());
                    return provider;
                })
                .orElseGet(() -> {
                    log.info("Using built-in FallbackDemoAstServiceSpi");
                    return new FallbackDemoAstServiceSpi();
                });
    }
}
