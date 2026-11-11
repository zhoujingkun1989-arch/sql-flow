package io.sqlflow.contracts.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Full 50-ordinal persistent {@code Job} record ({@code job.json}) and {@code queryJobDetail} DTO
 * ({@code 02 §5.5}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class JobDetail extends JobSummary {

    private String showRelationType;
    private Boolean ignoreRecordSet;
    private Boolean treatArgumentsInCountFunctionAsDirectDataflow;
    private Boolean showConstantTable;
    private Boolean showTransform;
    private Boolean ignoreFunction;
    private DataSource dataSource;
    private String defaultServer;
    private String defaultDatabase;
    private String defaultSchema;
    private List<String> fileNames;
    private List<String> errorMessages;
    private List<Object> errorMessageDetails;
    private String sessionId;
    private Boolean hasMetadata;
    private String csvFormat;
    private String objectCodeEncloseChar;
    private String objectCodeEscapeChar;
    private Boolean parallel;
    private String lineageRepo;
    private String lineageType;
    private List<SubJob> subJobs;
    private String version;
    private String extra;
    private String showIndirectClause;
    private String ip;
    private String os;
    private String browser;
    private String useMethod;
    private Boolean hideLineageFromStoredProcedure;
    private String api;
    private String queryId;

    // Storage cache paths & threshold settings
    private String sqlfilePath;
    private String dataflowPath;
    private String dataflowJsonPath;
    private String graphDataflowJsonPath;
    private String recordDataflowJsonPath;
    private String simpleDataflowPath;
    private String simpleIgnoreFunctionDataflowPath;
    private String simpleShowFunctionDataflowPath;
    private String simpleDataflowJsonPath;
    private String simpleIgnoreFunctionDataflowJsonPath;
    private String simpleShowFunctionDataflowJsonPath;
    private String erdDataflowPath;
    private String erdDataflowJsonPath;
    private String textPath;
    private String sqlenvPath;
    private String delimiter;
    private Boolean defaultShowCallRelation;
    private Boolean defaultShowCandidateTable;
    private Integer successSubJobCount;
    private Long dataflowFileSize;
    private String errorFilePath;
    private String lastModifiedTime;
    private Integer viewThreshold;
    private Integer tableThreshold;
    private Integer indirectThreshold;
    private Boolean mergeResultSet;
    private Boolean hasView;

    public JobDetail() {
    }

    public String getShowRelationType() {
        return showRelationType;
    }

    public void setShowRelationType(String showRelationType) {
        this.showRelationType = showRelationType;
    }

    public Boolean getIgnoreRecordSet() {
        return ignoreRecordSet;
    }

    public void setIgnoreRecordSet(Boolean ignoreRecordSet) {
        this.ignoreRecordSet = ignoreRecordSet;
    }

    public Boolean getTreatArgumentsInCountFunctionAsDirectDataflow() {
        return treatArgumentsInCountFunctionAsDirectDataflow;
    }

    public void setTreatArgumentsInCountFunctionAsDirectDataflow(
            Boolean treatArgumentsInCountFunctionAsDirectDataflow) {
        this.treatArgumentsInCountFunctionAsDirectDataflow = treatArgumentsInCountFunctionAsDirectDataflow;
    }

    public Boolean getShowConstantTable() {
        return showConstantTable;
    }

    public void setShowConstantTable(Boolean showConstantTable) {
        this.showConstantTable = showConstantTable;
    }

    public Boolean getShowTransform() {
        return showTransform;
    }

    public void setShowTransform(Boolean showTransform) {
        this.showTransform = showTransform;
    }

    public Boolean getIgnoreFunction() {
        return ignoreFunction;
    }

    public void setIgnoreFunction(Boolean ignoreFunction) {
        this.ignoreFunction = ignoreFunction;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
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

    public List<String> getFileNames() {
        return fileNames;
    }

    public void setFileNames(List<String> fileNames) {
        this.fileNames = fileNames;
    }

    public List<String> getErrorMessages() {
        return errorMessages;
    }

    public void setErrorMessages(List<String> errorMessages) {
        this.errorMessages = errorMessages;
    }

    public List<Object> getErrorMessageDetails() {
        return errorMessageDetails;
    }

    public void setErrorMessageDetails(List<Object> errorMessageDetails) {
        this.errorMessageDetails = errorMessageDetails;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Boolean getHasMetadata() {
        return hasMetadata;
    }

    public void setHasMetadata(Boolean hasMetadata) {
        this.hasMetadata = hasMetadata;
    }

    public String getCsvFormat() {
        return csvFormat;
    }

    public void setCsvFormat(String csvFormat) {
        this.csvFormat = csvFormat;
    }

    public String getObjectCodeEncloseChar() {
        return objectCodeEncloseChar;
    }

    public void setObjectCodeEncloseChar(String objectCodeEncloseChar) {
        this.objectCodeEncloseChar = objectCodeEncloseChar;
    }

    public String getObjectCodeEscapeChar() {
        return objectCodeEscapeChar;
    }

    public void setObjectCodeEscapeChar(String objectCodeEscapeChar) {
        this.objectCodeEscapeChar = objectCodeEscapeChar;
    }

    public Boolean getParallel() {
        return parallel;
    }

    public void setParallel(Boolean parallel) {
        this.parallel = parallel;
    }

    public String getLineageRepo() {
        return lineageRepo;
    }

    public void setLineageRepo(String lineageRepo) {
        this.lineageRepo = lineageRepo;
    }

    public String getLineageType() {
        return lineageType;
    }

    public void setLineageType(String lineageType) {
        this.lineageType = lineageType;
    }

    public List<SubJob> getSubJobs() {
        return subJobs;
    }

    public void setSubJobs(List<SubJob> subJobs) {
        this.subJobs = subJobs;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getExtra() {
        return extra;
    }

    public void setExtra(String extra) {
        this.extra = extra;
    }

    public String getShowIndirectClause() {
        return showIndirectClause;
    }

    public void setShowIndirectClause(String showIndirectClause) {
        this.showIndirectClause = showIndirectClause;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getOs() {
        return os;
    }

    public void setOs(String os) {
        this.os = os;
    }

    public String getBrowser() {
        return browser;
    }

    public void setBrowser(String browser) {
        this.browser = browser;
    }

    public String getUseMethod() {
        return useMethod;
    }

    public void setUseMethod(String useMethod) {
        this.useMethod = useMethod;
    }

    public Boolean getHideLineageFromStoredProcedure() {
        return hideLineageFromStoredProcedure;
    }

    public void setHideLineageFromStoredProcedure(Boolean hideLineageFromStoredProcedure) {
        this.hideLineageFromStoredProcedure = hideLineageFromStoredProcedure;
    }

    public String getApi() {
        return api;
    }

    public void setApi(String api) {
        this.api = api;
    }

    public String getQueryId() {
        return queryId;
    }

    public void setQueryId(String queryId) {
        this.queryId = queryId;
    }

    public String getSqlfilePath() {
        return sqlfilePath;
    }

    public void setSqlfilePath(String sqlfilePath) {
        this.sqlfilePath = sqlfilePath;
    }

    public String getDataflowPath() {
        return dataflowPath;
    }

    public void setDataflowPath(String dataflowPath) {
        this.dataflowPath = dataflowPath;
    }

    public String getDataflowJsonPath() {
        return dataflowJsonPath;
    }

    public void setDataflowJsonPath(String dataflowJsonPath) {
        this.dataflowJsonPath = dataflowJsonPath;
    }

    public String getGraphDataflowJsonPath() {
        return graphDataflowJsonPath;
    }

    public void setGraphDataflowJsonPath(String graphDataflowJsonPath) {
        this.graphDataflowJsonPath = graphDataflowJsonPath;
    }

    public String getRecordDataflowJsonPath() {
        return recordDataflowJsonPath;
    }

    public void setRecordDataflowJsonPath(String recordDataflowJsonPath) {
        this.recordDataflowJsonPath = recordDataflowJsonPath;
    }

    public String getSimpleDataflowPath() {
        return simpleDataflowPath;
    }

    public void setSimpleDataflowPath(String simpleDataflowPath) {
        this.simpleDataflowPath = simpleDataflowPath;
    }

    public String getSimpleIgnoreFunctionDataflowPath() {
        return simpleIgnoreFunctionDataflowPath;
    }

    public void setSimpleIgnoreFunctionDataflowPath(String simpleIgnoreFunctionDataflowPath) {
        this.simpleIgnoreFunctionDataflowPath = simpleIgnoreFunctionDataflowPath;
    }

    public String getSimpleShowFunctionDataflowPath() {
        return simpleShowFunctionDataflowPath;
    }

    public void setSimpleShowFunctionDataflowPath(String simpleShowFunctionDataflowPath) {
        this.simpleShowFunctionDataflowPath = simpleShowFunctionDataflowPath;
    }

    public String getSimpleDataflowJsonPath() {
        return simpleDataflowJsonPath;
    }

    public void setSimpleDataflowJsonPath(String simpleDataflowJsonPath) {
        this.simpleDataflowJsonPath = simpleDataflowJsonPath;
    }

    public String getSimpleIgnoreFunctionDataflowJsonPath() {
        return simpleIgnoreFunctionDataflowJsonPath;
    }

    public void setSimpleIgnoreFunctionDataflowJsonPath(String simpleIgnoreFunctionDataflowJsonPath) {
        this.simpleIgnoreFunctionDataflowJsonPath = simpleIgnoreFunctionDataflowJsonPath;
    }

    public String getSimpleShowFunctionDataflowJsonPath() {
        return simpleShowFunctionDataflowJsonPath;
    }

    public void setSimpleShowFunctionDataflowJsonPath(String simpleShowFunctionDataflowJsonPath) {
        this.simpleShowFunctionDataflowJsonPath = simpleShowFunctionDataflowJsonPath;
    }

    public String getErdDataflowPath() {
        return erdDataflowPath;
    }

    public void setErdDataflowPath(String erdDataflowPath) {
        this.erdDataflowPath = erdDataflowPath;
    }

    public String getErdDataflowJsonPath() {
        return erdDataflowJsonPath;
    }

    public void setErdDataflowJsonPath(String erdDataflowJsonPath) {
        this.erdDataflowJsonPath = erdDataflowJsonPath;
    }

    public String getTextPath() {
        return textPath;
    }

    public void setTextPath(String textPath) {
        this.textPath = textPath;
    }

    public String getSqlenvPath() {
        return sqlenvPath;
    }

    public void setSqlenvPath(String sqlenvPath) {
        this.sqlenvPath = sqlenvPath;
    }

    public String getDelimiter() {
        return delimiter;
    }

    public void setDelimiter(String delimiter) {
        this.delimiter = delimiter;
    }

    public Boolean getDefaultShowCallRelation() {
        return defaultShowCallRelation;
    }

    public void setDefaultShowCallRelation(Boolean defaultShowCallRelation) {
        this.defaultShowCallRelation = defaultShowCallRelation;
    }

    public Boolean getDefaultShowCandidateTable() {
        return defaultShowCandidateTable;
    }

    public void setDefaultShowCandidateTable(Boolean defaultShowCandidateTable) {
        this.defaultShowCandidateTable = defaultShowCandidateTable;
    }

    public Integer getSuccessSubJobCount() {
        return successSubJobCount;
    }

    public void setSuccessSubJobCount(Integer successSubJobCount) {
        this.successSubJobCount = successSubJobCount;
    }

    public Long getDataflowFileSize() {
        return dataflowFileSize;
    }

    public void setDataflowFileSize(Long dataflowFileSize) {
        this.dataflowFileSize = dataflowFileSize;
    }

    public String getErrorFilePath() {
        return errorFilePath;
    }

    public void setErrorFilePath(String errorFilePath) {
        this.errorFilePath = errorFilePath;
    }

    public String getLastModifiedTime() {
        return lastModifiedTime;
    }

    public void setLastModifiedTime(String lastModifiedTime) {
        this.lastModifiedTime = lastModifiedTime;
    }

    public Integer getViewThreshold() {
        return viewThreshold;
    }

    public void setViewThreshold(Integer viewThreshold) {
        this.viewThreshold = viewThreshold;
    }

    public Integer getTableThreshold() {
        return tableThreshold;
    }

    public void setTableThreshold(Integer tableThreshold) {
        this.tableThreshold = tableThreshold;
    }

    public Integer getIndirectThreshold() {
        return indirectThreshold;
    }

    public void setIndirectThreshold(Integer indirectThreshold) {
        this.indirectThreshold = indirectThreshold;
    }

    public Boolean getMergeResultSet() {
        return mergeResultSet;
    }

    public void setMergeResultSet(Boolean mergeResultSet) {
        this.mergeResultSet = mergeResultSet;
    }

    public Boolean getHasView() {
        return hasView;
    }

    public void setHasView(Boolean hasView) {
        this.hasView = hasView;
    }
}
