package io.sqlflow.server.config;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Cancellable timeout guard ({@code OBL-012}) replacing upstream {@code System.exit(1)} watchdog with
 * bounded worker threads and clean {@link Future#cancel(boolean)} interruption.
 */
@Component
public class ExecutionTimeoutGuard {

    private final SqlflowRuntimeConfig config;
    private final ExecutorService workerPool;

    public ExecutionTimeoutGuard(SqlflowRuntimeConfig config) {
        this.config = config;
        AtomicInteger counter = new AtomicInteger(1);
        int poolSize = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        this.workerPool = Executors.newFixedThreadPool(poolSize, runnable -> {
            Thread t = new Thread(runnable, "sqlflow-timeout-guard-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    public <T> T executeWithTimeout(String operationName, Callable<T> task) {
        return executeWithTimeout(operationName, config.getDataflowGraphTimeoutMs(), task);
    }

    public <T> T executeWithTimeout(String operationName, long timeoutMs, Callable<T> task) {
        Future<T> future = workerPool.submit(task);
        try {
            return future.get(Math.max(10L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new SqlflowTimeoutException(
                    "Operation '" + operationName + "' exceeded timeout of " + timeoutMs + " ms", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new SqlflowTimeoutException("Operation '" + operationName + "' was interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(
                    cause != null && cause.getMessage() != null ? cause.getMessage() : "Execution failed",
                    cause);
        }
    }

    @PreDestroy
    public void shutdown() {
        workerPool.shutdownNow();
    }
}
