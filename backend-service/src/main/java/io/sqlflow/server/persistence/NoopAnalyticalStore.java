package io.sqlflow.server.persistence;

import io.sqlflow.contracts.domain.JobDetail;
import io.sqlflow.contracts.graph.LeftMostSourceTable;
import io.sqlflow.contracts.lineage.DataFlow;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Disabled analytical store active under the {@code simple} profile ({@code regular.job = false}).
 */
public class NoopAnalyticalStore implements AnalyticalStore {

    @Override
    public String storeType() {
        return "none";
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public void initSchema() {
    }

    @Override
    public List<String> listTables() {
        return Collections.emptyList();
    }

    @Override
    public void persistJobLineage(
            JobDetail job, DataFlow dataFlow, List<SqlEntry> sqlEntries, LeftMostSourceTable leftMost) {
    }

    @Override
    public void updateJobStatus(String jobId, String status, String errorMessage, long executeTimeMillis) {
    }

    @Override
    public void deleteJobData(String jobId) {
    }

    @Override
    public List<List<String>> fetchRegularJobTableRelationSqls(
            String jobId, String sourceTable, String sourceColumn, String targetTable, String targetColumn) {
        return Collections.emptyList();
    }

    @Override
    public List<Map<String, Object>> fetchTableRelationSqlBySqlHash(String jobId, String sqlHash) {
        return Collections.emptyList();
    }

    @Override
    public List<Map<String, Object>> fetchLeftMostTableLineageSQL(
            String jobId, String targetTable, String targetColumn, String sourceTable, String sourceColumn) {
        return Collections.emptyList();
    }

    @Override
    public Map<String, Object> persistAndFetchSourceTableAffectSQL(
            String jobId, String batchId, DataFlow baseFlow, List<SqlEntry> affectSqls, boolean columnLevel) {
        return Map.of("jobId", jobId, "batchId", batchId, "affectedSqls", List.of());
    }

    @Override
    public Path exportJobParquet(String jobId, String batchId, Path targetParquetFile) {
        throw new UnsupportedOperationException("Regular job analytical store is disabled in simple profile.");
    }
}
