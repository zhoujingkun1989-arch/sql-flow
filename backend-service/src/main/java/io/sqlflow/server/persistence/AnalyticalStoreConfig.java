package io.sqlflow.server.persistence;

import io.sqlflow.server.config.SqlflowRuntimeConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the active {@link AnalyticalStore} implementation based on Spring profile:
 * <ul>
 *   <li>{@code duckdb} (default): {@link DuckDbAnalyticalStore} ({@code regular.job = true})</li>
 *   <li>{@code regular} / {@code scale}: {@link ClickHouseAnalyticalStore} ({@code regular.job = true})</li>
 *   <li>{@code simple}: {@link NoopAnalyticalStore} ({@code regular.job = false})</li>
 * </ul>
 */
@Configuration
public class AnalyticalStoreConfig {

    @Bean
    public AnalyticalStore analyticalStore(SqlflowRuntimeConfig config) {
        String storeType = config.getAnalyticalStoreType();
        AnalyticalStore store = switch (storeType) {
            case "none" -> new NoopAnalyticalStore();
            case "clickhouse" -> new ClickHouseAnalyticalStore(config);
            default -> new DuckDbAnalyticalStore(config);
        };
        store.initSchema();
        return store;
    }
}
