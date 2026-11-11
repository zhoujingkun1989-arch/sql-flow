package io.sqlflow.server.persistence;

import io.sqlflow.contracts.domain.JobDetail;
import io.sqlflow.contracts.graph.LeftMostSourceTable;
import io.sqlflow.contracts.lineage.DataFlow;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Analytical lineage store abstraction implemented by DuckDB ({@code duckdb} default profile),
 * ClickHouse ({@code regular}/{@code scale} profiles), and Noop ({@code simple} profile).
 */
public interface AnalyticalStore {

    String storeType();

    boolean isEnabled();

    void initSchema();

    List<String> listTables();

    void persistJobLineage(
            JobDetail job,
            DataFlow dataFlow,
            List<SqlEntry> sqlEntries,
            LeftMostSourceTable leftMost);

    void updateJobStatus(String jobId, String status, String errorMessage, long executeTimeMillis);

    void deleteJobData(String jobId);

    List<List<String>> fetchRegularJobTableRelationSqls(
            String jobId, String sourceTable, String sourceColumn, String targetTable, String targetColumn);

    List<Map<String, Object>> fetchTableRelationSqlBySqlHash(String jobId, String sqlHash);

    List<Map<String, Object>> fetchLeftMostTableLineageSQL(
            String jobId, String targetTable, String targetColumn, String sourceTable, String sourceColumn);

    Map<String, Object> persistAndFetchSourceTableAffectSQL(
            String jobId, String batchId, DataFlow baseFlow, List<SqlEntry> affectSqls, boolean columnLevel);

    Path exportJobParquet(String jobId, String batchId, Path targetParquetFile);

    record SqlEntry(String fileName, String sqlText, String sqlHash) {
    }
}
