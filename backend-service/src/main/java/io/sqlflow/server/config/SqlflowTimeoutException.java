package io.sqlflow.server.config;

/**
 * Thrown when a lineage or graph layout computation exceeds {@code dataflow_graph_timeout}.
 */
public class SqlflowTimeoutException extends RuntimeException {

    public SqlflowTimeoutException(String message) {
        super(message);
    }

    public SqlflowTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
