package io.sqlflow.graph;

import static org.assertj.core.api.Assertions.assertThat;

import io.sqlflow.contracts.spi.GraphLayoutSpi;
import io.sqlflow.contracts.spi.JoinAnalysisSpi;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

class SpiServiceLoaderTest {

    @Test
    void serviceLoaderDiscoversGraphLayoutAndJoinAnalysisImplementations() {
        List<GraphLayoutSpi> graphProviders = ServiceLoader.load(GraphLayoutSpi.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        assertThat(graphProviders)
                .isNotEmpty()
                .anySatisfy(p -> assertThat(p).isInstanceOf(GraphLayoutService.class));

        List<JoinAnalysisSpi> joinProviders = ServiceLoader.load(JoinAnalysisSpi.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        assertThat(joinProviders)
                .isNotEmpty()
                .anySatisfy(p -> assertThat(p).isInstanceOf(JoinAnalysisService.class));
    }
}
