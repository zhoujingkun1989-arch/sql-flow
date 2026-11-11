package io.sqlflow.contracts.spi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.sqlflow.contracts.graph.JoinAnalysisGraph;
import io.sqlflow.contracts.lineage.SqlInfo;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.util.List;

/**
 * Frozen Stage 2A SPI for SQL Join Analysis ({@code join-analysis.v1})
 * ({@code 00 §3.3 #8}, {@code 02 §4.2.1 & §5.3}, {@code 03 §4}).
 */
public interface JoinAnalysisSpi {

    /**
     * Analyzes SQL joins, predicates, keys, cardinality hints, and all 10 {@code DiagnosticCode} rules,
     * returning a laid-out {@link JoinAnalysisGraph} with {@code schemaVersion = "join-analysis.v1"}.
     */
    JoinAnalysisGraph analyzeJoins(JoinAnalysisRequest request);

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class JoinAnalysisRequest {
        private String sqlText;
        private List<SqlInfo> sqlUnits;
        private EDbVendor vendor = EDbVendor.dbvoracle;
        private String userId;
        private String jobId;
        private String defaultServer;
        private String defaultDatabase;
        private String defaultSchema;
        private SqlflowCatalog catalog;
        private JoinAnalysisGraph.LayoutDirection layoutDirection = JoinAnalysisGraph.LayoutDirection.RIGHT;

        public JoinAnalysisRequest() {
        }

        public static JoinAnalysisRequest of(String sqlText, EDbVendor vendor) {
            JoinAnalysisRequest req = new JoinAnalysisRequest();
            req.setSqlText(sqlText);
            req.setVendor(vendor != null ? vendor : EDbVendor.dbvoracle);
            return req;
        }

        public String getSqlText() {
            return sqlText;
        }

        public void setSqlText(String sqlText) {
            this.sqlText = sqlText;
        }

        public List<SqlInfo> getSqlUnits() {
            return sqlUnits;
        }

        public void setSqlUnits(List<SqlInfo> sqlUnits) {
            this.sqlUnits = sqlUnits;
        }

        public EDbVendor getVendor() {
            return vendor;
        }

        public void setVendor(EDbVendor vendor) {
            this.vendor = vendor != null ? vendor : EDbVendor.dbvoracle;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getJobId() {
            return jobId;
        }

        public void setJobId(String jobId) {
            this.jobId = jobId;
        }

        public String getDefaultServer() {
            return defaultServer;
        }

        public void setDefaultServer(String defaultServer) {
            this.defaultServer = defaultServer;
        }

        public String getDefaultDatabase() {
            return defaultDatabase;
        }

        public void setDefaultDatabase(String defaultDatabase) {
            this.defaultDatabase = defaultDatabase;
        }

        public String getDefaultSchema() {
            return defaultSchema;
        }

        public void setDefaultSchema(String defaultSchema) {
            this.defaultSchema = defaultSchema;
        }

        public SqlflowCatalog getCatalog() {
            return catalog;
        }

        public void setCatalog(SqlflowCatalog catalog) {
            this.catalog = catalog;
        }

        public JoinAnalysisGraph.LayoutDirection getLayoutDirection() {
            return layoutDirection;
        }

        public void setLayoutDirection(JoinAnalysisGraph.LayoutDirection layoutDirection) {
            this.layoutDirection = layoutDirection != null
                    ? layoutDirection
                    : JoinAnalysisGraph.LayoutDirection.RIGHT;
        }
    }
}
