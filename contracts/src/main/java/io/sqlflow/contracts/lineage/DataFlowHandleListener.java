package io.sqlflow.contracts.lineage;

/**
 * Progress and cancellation callback listener invoked during {@code DataFlowAnalyzer} execution ({@code 03 §3.5.1}).
 */
public interface DataFlowHandleListener {

    default void startAnalyze(int totalUnits) {
    }

    default void onStatementAnalyzed(int index, int totalUnits) {
    }

    default void endAnalyze(boolean success) {
    }

    default boolean isCanceled() {
        return false;
    }
}
