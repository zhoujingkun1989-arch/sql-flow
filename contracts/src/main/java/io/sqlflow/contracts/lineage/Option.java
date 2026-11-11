package io.sqlflow.contracts.lineage;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.contracts.vendor.IdentifierProfile;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Complete 74-field lineage engine configuration matching {@code gudusoft.gsqlparser.dlineage.dataflow.model.Option}
 * in SQLFlow 8.2.2.15 ({@code 03 §3.5.1}).
 *
 * <p>No static fields are declared on this class so reflective inspection sees all 74 instance fields directly.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Option {

    /* 01 */ private EDbVendor vendor = null;
    /* 02 */ private EDbVendor powerQueryInnerVendor = null;
    /* 03 */ private boolean simpleOutput = false;
    /* 04 */ private boolean ignoreRecordSet = false;
    /* 05 */ private boolean sqlflowIgnoreFunction = false;
    /* 06 */ private boolean simpleShowFunction = false;
    /* 07 */ private boolean simpleShowUdfFunctionOnly = false;
    /* 08 */ private boolean simpleShowSynonym = false;
    /* 09 */ private boolean simpleShowCursor = false;
    /* 10 */ private boolean simpleShowVariable = false;
    /* 11 */ private boolean simpleRetainIntermediate = false;
    /* 12 */ private boolean simpleShowTopSelectResultSet = false;
    /* 13 */ private boolean ignoreTopSelect = false;
    /* 14 */ private boolean ignoreTemporaryTable = false;
    /* 15 */ private boolean ignoreUnusedSynonym = true;
    /* 16 */ private boolean ignoreInsertIntoValues = true;
    /* 17 */ private boolean showConstantTable = false;
    /* 18 */ private boolean showCountTableColumn = true;
    /* 19 */ private boolean showCaseWhenAsDirect = true;
    /* 20 */ private boolean showJoin = false;
    /* 21 */ private boolean showCallRelation = false;
    /* 22 */ private boolean showERDiagram = false;
    /* 23 */ private boolean transform = false;
    /* 24 */ private boolean transformCoordinate = false;
    /* 25 */ private boolean ignoreCoordinate = false;
    /* 26 */ private boolean linkOrphanColumnToFirstTable = false;
    /* 27 */ private boolean showCandidateTable = true;
    /* 28 */ private boolean showImplicitSchema = false;
    /* 29 */ private boolean showComment = false;
    /* 30 */ private boolean collectCommentBindings = false;
    /* 31 */ private int commentBindingMaxRawLength = 8192;
    /* 32 */ private int commentBindingMaxCommentsPerSourceUnit = 50000;
    /* 33 */ private int commentBindingMaxTotalRawLength = 4194304;
    /* 34 */ private boolean textFormat = false;
    /* 35 */ private boolean output = true;
    /* 36 */ private boolean normalizeOutput = false;
    /* 37 */ private boolean traceSQL = false;
    /* 38 */ private boolean traceProcedure = false;
    /* 39 */ private boolean traceTablePosition = false;
    /* 40 */ private long startId = 0L;
    /* 41 */ private int parallel = Math.max(1, Runtime.getRuntime().availableProcessors() / 2 - 1);
    /* 42 */ private String defaultServer = null;
    /* 43 */ private String defaultDatabase = null;
    /* 44 */ private String defaultSchema = null;
    /* 45 */ private String filePathDatabase = null;
    /* 46 */ private String filePathSchema = null;
    /* 47 */ private Set<ResultSetType> targetResultSetTypes = new LinkedHashSet<>();
    /* 48 */ private Set<String> filterRelationTypes = new LinkedHashSet<>();
    /* 49 */ private Set<String> simpleShowRelationTypes = new LinkedHashSet<>();
    /* 50 */ private AnalyzeMode analyzeMode = null;
    /* 51 */ @JsonIgnore private IdentifierProfile identifierProfile = null;
    /* 52 */ private boolean enableMssqlColonBindVariables = false;
    /* 53 */ private boolean collectAuthoritativeLineageEvidence = false;
    /* 54 */ private boolean analyzeDynamicSql = true;
    /* 55 */ private DynamicSqlTrustMode dynamicSqlTrustMode = DynamicSqlTrustMode.LEGACY;
    /* 56 */ private boolean reportDynamicSqlSitesAsErrors = false;
    /* 57 */ private boolean dynamicSqlCaseSensitiveCompare = false;
    /* 58 */ private boolean recoverRoutineBodyErrors = false;
    /* 59 */ private boolean assumeExternalScriptPassthrough = true;
    /* 60 */ private Set<String> excludedProcedureNames = new LinkedHashSet<>();
    /* 61 */ private Set<String> excludedProcedurePatterns = new LinkedHashSet<>();
    /* 62 */ private boolean enablePipelinedStitching = true;
    /* 63 */ private boolean identityFirstVariablePools = false;
    /* 64 */ private int maxPipelinedExpansionDepth = 8;
    /* 65 */ private int maxStitchedSourcesPerColumn = 64;
    /* 66 */ private boolean autoDetectLargeFile = false;
    /* 67 */ private int largeSqlInfoCountThreshold = 1000;
    /* 68 */ private long largeSqlTotalSizeThreshold = 26214400L;
    /* 69 */ private int largeQueryCountThreshold = 1000;
    /* 70 */ private int largeShardCountThreshold = 10;
    /* 71 */ private int largeFileSplitSizeMB = 5;
    /* 72 */ private double largeSqlThresholdMultiplier = 1.5d;
    /* 73 */ private long estimatedMemoryPerTaskMB = 2560L;
    /* 74 */ @JsonIgnore private DataFlowHandleListener handleListener = null;

    public Option() {
    }

    public static Option forVendor(EDbVendor vendor) {
        Option opt = new Option();
        opt.setVendor(vendor);
        return opt;
    }

    /**
     * Applies composite {@code onlyDirect = true} flag ({@code 03 §3.5.2}).
     */
    public Option applyOnlyDirect() {
        this.filterRelationTypes.clear();
        this.filterRelationTypes.add(RelationshipType.fdd.name());
        this.showJoin = false;
        return this;
    }

    /**
     * Applies composite {@code erDiagram = true} flag ({@code 03 §3.5.2}).
     */
    public Option applyErDiagram() {
        this.showERDiagram = true;
        this.filterRelationTypes.clear();
        this.filterRelationTypes.add(RelationshipType.er.name());
        return this;
    }

    public EDbVendor getVendor() {
        return vendor;
    }

    public void setVendor(EDbVendor vendor) {
        this.vendor = vendor;
    }

    public EDbVendor getPowerQueryInnerVendor() {
        return powerQueryInnerVendor;
    }

    public void setPowerQueryInnerVendor(EDbVendor powerQueryInnerVendor) {
        this.powerQueryInnerVendor = powerQueryInnerVendor;
    }

    public boolean isSimpleOutput() {
        return simpleOutput;
    }

    public void setSimpleOutput(boolean simpleOutput) {
        this.simpleOutput = simpleOutput;
    }

    public boolean isIgnoreRecordSet() {
        return ignoreRecordSet;
    }

    public void setIgnoreRecordSet(boolean ignoreRecordSet) {
        this.ignoreRecordSet = ignoreRecordSet;
    }

    public boolean isSqlflowIgnoreFunction() {
        return sqlflowIgnoreFunction;
    }

    public void setSqlflowIgnoreFunction(boolean sqlflowIgnoreFunction) {
        this.sqlflowIgnoreFunction = sqlflowIgnoreFunction;
    }

    public boolean isSimpleShowFunction() {
        return simpleShowFunction;
    }

    public void setSimpleShowFunction(boolean simpleShowFunction) {
        this.simpleShowFunction = simpleShowFunction;
    }

    public boolean isSimpleShowUdfFunctionOnly() {
        return simpleShowUdfFunctionOnly;
    }

    public void setSimpleShowUdfFunctionOnly(boolean simpleShowUdfFunctionOnly) {
        this.simpleShowUdfFunctionOnly = simpleShowUdfFunctionOnly;
    }

    public boolean isSimpleShowSynonym() {
        return simpleShowSynonym;
    }

    public void setSimpleShowSynonym(boolean simpleShowSynonym) {
        this.simpleShowSynonym = simpleShowSynonym;
    }

    public boolean isSimpleShowCursor() {
        return simpleShowCursor;
    }

    public void setSimpleShowCursor(boolean simpleShowCursor) {
        this.simpleShowCursor = simpleShowCursor;
    }

    public boolean isSimpleShowVariable() {
        return simpleShowVariable;
    }

    public void setSimpleShowVariable(boolean simpleShowVariable) {
        this.simpleShowVariable = simpleShowVariable;
    }

    public boolean isSimpleRetainIntermediate() {
        return simpleRetainIntermediate;
    }

    public void setSimpleRetainIntermediate(boolean simpleRetainIntermediate) {
        this.simpleRetainIntermediate = simpleRetainIntermediate;
    }

    public boolean isSimpleShowTopSelectResultSet() {
        return simpleShowTopSelectResultSet;
    }

    public void setSimpleShowTopSelectResultSet(boolean simpleShowTopSelectResultSet) {
        this.simpleShowTopSelectResultSet = simpleShowTopSelectResultSet;
    }

    public boolean isIgnoreTopSelect() {
        return ignoreTopSelect;
    }

    public void setIgnoreTopSelect(boolean ignoreTopSelect) {
        this.ignoreTopSelect = ignoreTopSelect;
    }

    public boolean isIgnoreTemporaryTable() {
        return ignoreTemporaryTable;
    }

    public void setIgnoreTemporaryTable(boolean ignoreTemporaryTable) {
        this.ignoreTemporaryTable = ignoreTemporaryTable;
    }

    public boolean isIgnoreUnusedSynonym() {
        return ignoreUnusedSynonym;
    }

    public void setIgnoreUnusedSynonym(boolean ignoreUnusedSynonym) {
        this.ignoreUnusedSynonym = ignoreUnusedSynonym;
    }

    public boolean isIgnoreInsertIntoValues() {
        return ignoreInsertIntoValues;
    }

    public void setIgnoreInsertIntoValues(boolean ignoreInsertIntoValues) {
        this.ignoreInsertIntoValues = ignoreInsertIntoValues;
    }

    public boolean isShowConstantTable() {
        return showConstantTable;
    }

    public void setShowConstantTable(boolean showConstantTable) {
        this.showConstantTable = showConstantTable;
    }

    public boolean isShowCountTableColumn() {
        return showCountTableColumn;
    }

    public void setShowCountTableColumn(boolean showCountTableColumn) {
        this.showCountTableColumn = showCountTableColumn;
    }

    public boolean isShowCaseWhenAsDirect() {
        return showCaseWhenAsDirect;
    }

    public void setShowCaseWhenAsDirect(boolean showCaseWhenAsDirect) {
        this.showCaseWhenAsDirect = showCaseWhenAsDirect;
    }

    public boolean isShowJoin() {
        return showJoin;
    }

    public void setShowJoin(boolean showJoin) {
        this.showJoin = showJoin;
    }

    public boolean isShowCallRelation() {
        return showCallRelation;
    }

    public void setShowCallRelation(boolean showCallRelation) {
        this.showCallRelation = showCallRelation;
    }

    public boolean isShowERDiagram() {
        return showERDiagram;
    }

    public void setShowERDiagram(boolean showERDiagram) {
        this.showERDiagram = showERDiagram;
    }

    public boolean isTransform() {
        return transform;
    }

    public void setTransform(boolean transform) {
        this.transform = transform;
    }

    public boolean isTransformCoordinate() {
        return transformCoordinate;
    }

    public void setTransformCoordinate(boolean transformCoordinate) {
        this.transformCoordinate = transformCoordinate;
    }

    public boolean isIgnoreCoordinate() {
        return ignoreCoordinate;
    }

    public void setIgnoreCoordinate(boolean ignoreCoordinate) {
        this.ignoreCoordinate = ignoreCoordinate;
    }

    public boolean isLinkOrphanColumnToFirstTable() {
        return linkOrphanColumnToFirstTable;
    }

    public void setLinkOrphanColumnToFirstTable(boolean linkOrphanColumnToFirstTable) {
        this.linkOrphanColumnToFirstTable = linkOrphanColumnToFirstTable;
    }

    public boolean isShowCandidateTable() {
        return showCandidateTable;
    }

    public void setShowCandidateTable(boolean showCandidateTable) {
        this.showCandidateTable = showCandidateTable;
    }

    public boolean isShowImplicitSchema() {
        return showImplicitSchema;
    }

    public void setShowImplicitSchema(boolean showImplicitSchema) {
        this.showImplicitSchema = showImplicitSchema;
    }

    public boolean isShowComment() {
        return showComment;
    }

    public void setShowComment(boolean showComment) {
        this.showComment = showComment;
    }

    public boolean isCollectCommentBindings() {
        return collectCommentBindings;
    }

    public void setCollectCommentBindings(boolean collectCommentBindings) {
        this.collectCommentBindings = collectCommentBindings;
    }

    public int getCommentBindingMaxRawLength() {
        return commentBindingMaxRawLength;
    }

    public void setCommentBindingMaxRawLength(int commentBindingMaxRawLength) {
        this.commentBindingMaxRawLength = commentBindingMaxRawLength;
    }

    public int getCommentBindingMaxCommentsPerSourceUnit() {
        return commentBindingMaxCommentsPerSourceUnit;
    }

    public void setCommentBindingMaxCommentsPerSourceUnit(int commentBindingMaxCommentsPerSourceUnit) {
        this.commentBindingMaxCommentsPerSourceUnit = commentBindingMaxCommentsPerSourceUnit;
    }

    public int getCommentBindingMaxTotalRawLength() {
        return commentBindingMaxTotalRawLength;
    }

    public void setCommentBindingMaxTotalRawLength(int commentBindingMaxTotalRawLength) {
        this.commentBindingMaxTotalRawLength = commentBindingMaxTotalRawLength;
    }

    public boolean isTextFormat() {
        return textFormat;
    }

    public void setTextFormat(boolean textFormat) {
        this.textFormat = textFormat;
    }

    public boolean isOutput() {
        return output;
    }

    public void setOutput(boolean output) {
        this.output = output;
    }

    public boolean isNormalizeOutput() {
        return normalizeOutput;
    }

    public void setNormalizeOutput(boolean normalizeOutput) {
        this.normalizeOutput = normalizeOutput;
    }

    public boolean isTraceSQL() {
        return traceSQL;
    }

    public void setTraceSQL(boolean traceSQL) {
        this.traceSQL = traceSQL;
    }

    public boolean isTraceProcedure() {
        return traceProcedure;
    }

    public void setTraceProcedure(boolean traceProcedure) {
        this.traceProcedure = traceProcedure;
    }

    public boolean isTraceTablePosition() {
        return traceTablePosition;
    }

    public void setTraceTablePosition(boolean traceTablePosition) {
        this.traceTablePosition = traceTablePosition;
    }

    public long getStartId() {
        return startId;
    }

    public void setStartId(long startId) {
        this.startId = startId;
    }

    public int getParallel() {
        return parallel;
    }

    public void setParallel(int parallel) {
        this.parallel = parallel;
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

    public String getFilePathDatabase() {
        return filePathDatabase;
    }

    public void setFilePathDatabase(String filePathDatabase) {
        this.filePathDatabase = filePathDatabase;
    }

    public String getFilePathSchema() {
        return filePathSchema;
    }

    public void setFilePathSchema(String filePathSchema) {
        this.filePathSchema = filePathSchema;
    }

    public Set<ResultSetType> getTargetResultSetTypes() {
        return targetResultSetTypes;
    }

    public void setTargetResultSetTypes(Set<ResultSetType> targetResultSetTypes) {
        this.targetResultSetTypes = targetResultSetTypes != null ? targetResultSetTypes : new LinkedHashSet<>();
    }

    public Set<String> getFilterRelationTypes() {
        return filterRelationTypes;
    }

    public void setFilterRelationTypes(Set<String> filterRelationTypes) {
        this.filterRelationTypes = filterRelationTypes != null ? filterRelationTypes : new LinkedHashSet<>();
    }

    public Set<String> getSimpleShowRelationTypes() {
        return simpleShowRelationTypes;
    }

    public void setSimpleShowRelationTypes(Set<String> simpleShowRelationTypes) {
        this.simpleShowRelationTypes = simpleShowRelationTypes != null ? simpleShowRelationTypes : new LinkedHashSet<>();
    }

    public AnalyzeMode getAnalyzeMode() {
        return analyzeMode;
    }

    public void setAnalyzeMode(AnalyzeMode analyzeMode) {
        this.analyzeMode = analyzeMode;
    }

    @JsonIgnore
    public IdentifierProfile getIdentifierProfile() {
        return identifierProfile;
    }

    @JsonIgnore
    public void setIdentifierProfile(IdentifierProfile identifierProfile) {
        this.identifierProfile = identifierProfile;
    }

    public boolean isEnableMssqlColonBindVariables() {
        return enableMssqlColonBindVariables;
    }

    public void setEnableMssqlColonBindVariables(boolean enableMssqlColonBindVariables) {
        this.enableMssqlColonBindVariables = enableMssqlColonBindVariables;
    }

    public boolean isCollectAuthoritativeLineageEvidence() {
        return collectAuthoritativeLineageEvidence;
    }

    public void setCollectAuthoritativeLineageEvidence(boolean collectAuthoritativeLineageEvidence) {
        this.collectAuthoritativeLineageEvidence = collectAuthoritativeLineageEvidence;
    }

    public boolean isAnalyzeDynamicSql() {
        return analyzeDynamicSql;
    }

    public void setAnalyzeDynamicSql(boolean analyzeDynamicSql) {
        this.analyzeDynamicSql = analyzeDynamicSql;
    }

    public DynamicSqlTrustMode getDynamicSqlTrustMode() {
        return dynamicSqlTrustMode;
    }

    public void setDynamicSqlTrustMode(DynamicSqlTrustMode dynamicSqlTrustMode) {
        this.dynamicSqlTrustMode = dynamicSqlTrustMode;
    }

    public boolean isReportDynamicSqlSitesAsErrors() {
        return reportDynamicSqlSitesAsErrors;
    }

    public void setReportDynamicSqlSitesAsErrors(boolean reportDynamicSqlSitesAsErrors) {
        this.reportDynamicSqlSitesAsErrors = reportDynamicSqlSitesAsErrors;
    }

    public boolean isDynamicSqlCaseSensitiveCompare() {
        return dynamicSqlCaseSensitiveCompare;
    }

    public void setDynamicSqlCaseSensitiveCompare(boolean dynamicSqlCaseSensitiveCompare) {
        this.dynamicSqlCaseSensitiveCompare = dynamicSqlCaseSensitiveCompare;
    }

    public boolean isRecoverRoutineBodyErrors() {
        return recoverRoutineBodyErrors;
    }

    public void setRecoverRoutineBodyErrors(boolean recoverRoutineBodyErrors) {
        this.recoverRoutineBodyErrors = recoverRoutineBodyErrors;
    }

    public boolean isAssumeExternalScriptPassthrough() {
        return assumeExternalScriptPassthrough;
    }

    public void setAssumeExternalScriptPassthrough(boolean assumeExternalScriptPassthrough) {
        this.assumeExternalScriptPassthrough = assumeExternalScriptPassthrough;
    }

    public Set<String> getExcludedProcedureNames() {
        return excludedProcedureNames;
    }

    public void setExcludedProcedureNames(Set<String> excludedProcedureNames) {
        this.excludedProcedureNames = excludedProcedureNames != null ? excludedProcedureNames : new LinkedHashSet<>();
    }

    public Set<String> getExcludedProcedurePatterns() {
        return excludedProcedurePatterns;
    }

    public void setExcludedProcedurePatterns(Set<String> excludedProcedurePatterns) {
        this.excludedProcedurePatterns = excludedProcedurePatterns != null ? excludedProcedurePatterns : new LinkedHashSet<>();
    }

    public boolean isEnablePipelinedStitching() {
        return enablePipelinedStitching;
    }

    public void setEnablePipelinedStitching(boolean enablePipelinedStitching) {
        this.enablePipelinedStitching = enablePipelinedStitching;
    }

    public boolean isIdentityFirstVariablePools() {
        return identityFirstVariablePools;
    }

    public void setIdentityFirstVariablePools(boolean identityFirstVariablePools) {
        this.identityFirstVariablePools = identityFirstVariablePools;
    }

    public int getMaxPipelinedExpansionDepth() {
        return maxPipelinedExpansionDepth;
    }

    public void setMaxPipelinedExpansionDepth(int maxPipelinedExpansionDepth) {
        this.maxPipelinedExpansionDepth = maxPipelinedExpansionDepth;
    }

    public int getMaxStitchedSourcesPerColumn() {
        return maxStitchedSourcesPerColumn;
    }

    public void setMaxStitchedSourcesPerColumn(int maxStitchedSourcesPerColumn) {
        this.maxStitchedSourcesPerColumn = maxStitchedSourcesPerColumn;
    }

    public boolean isAutoDetectLargeFile() {
        return autoDetectLargeFile;
    }

    public void setAutoDetectLargeFile(boolean autoDetectLargeFile) {
        this.autoDetectLargeFile = autoDetectLargeFile;
    }

    public int getLargeSqlInfoCountThreshold() {
        return largeSqlInfoCountThreshold;
    }

    public void setLargeSqlInfoCountThreshold(int largeSqlInfoCountThreshold) {
        this.largeSqlInfoCountThreshold = largeSqlInfoCountThreshold;
    }

    public long getLargeSqlTotalSizeThreshold() {
        return largeSqlTotalSizeThreshold;
    }

    public void setLargeSqlTotalSizeThreshold(long largeSqlTotalSizeThreshold) {
        this.largeSqlTotalSizeThreshold = largeSqlTotalSizeThreshold;
    }

    public int getLargeQueryCountThreshold() {
        return largeQueryCountThreshold;
    }

    public void setLargeQueryCountThreshold(int largeQueryCountThreshold) {
        this.largeQueryCountThreshold = largeQueryCountThreshold;
    }

    public int getLargeShardCountThreshold() {
        return largeShardCountThreshold;
    }

    public void setLargeShardCountThreshold(int largeShardCountThreshold) {
        this.largeShardCountThreshold = largeShardCountThreshold;
    }

    public int getLargeFileSplitSizeMB() {
        return largeFileSplitSizeMB;
    }

    public void setLargeFileSplitSizeMB(int largeFileSplitSizeMB) {
        this.largeFileSplitSizeMB = largeFileSplitSizeMB;
    }

    public double getLargeSqlThresholdMultiplier() {
        return largeSqlThresholdMultiplier;
    }

    public void setLargeSqlThresholdMultiplier(double largeSqlThresholdMultiplier) {
        this.largeSqlThresholdMultiplier = largeSqlThresholdMultiplier;
    }

    public long getEstimatedMemoryPerTaskMB() {
        return estimatedMemoryPerTaskMB;
    }

    public void setEstimatedMemoryPerTaskMB(long estimatedMemoryPerTaskMB) {
        this.estimatedMemoryPerTaskMB = estimatedMemoryPerTaskMB;
    }

    @JsonIgnore
    public DataFlowHandleListener getHandleListener() {
        return handleListener;
    }

    @JsonIgnore
    public void setHandleListener(DataFlowHandleListener handleListener) {
        this.handleListener = handleListener;
    }
}
