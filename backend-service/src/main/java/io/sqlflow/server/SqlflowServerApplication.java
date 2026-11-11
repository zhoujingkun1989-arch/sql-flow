package io.sqlflow.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Unified SQLFlow 8.2.2.15 HTTP Server ({@code :8165}) consolidating gateway, auth, persistence,
 * async/persistent Job Center, Share/Schema, V1/Anonymous APIs, and AI Lineage SSE.
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
@EnableScheduling
public class SqlflowServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SqlflowServerApplication.class, args);
    }
}
