package io.sqlflow.contracts.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Lightweight job summary DTO aligned 1:1 with {@code JobVo} & {@code Job} wire keys used in job listings
 * and {@link LatestJobGraphEnvelope} ({@code 01 §3.3.7}, {@code 02 §5.5}, {@code 04 §12.3}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({
    "jobId",
    "jobName",
    "userId",
    "userName",
    "userEmail",
    "group",
    "dbVendor",
    "sqlsource",
    "status",
    "regularJob",
    "persist",
    "incremental",
    "schedulable",
    "cronExpression",
    "cancelSchedule",
    "onTop",
    "createTime",
    "startTime",
    "endTime",
    "executeTime",
    "executeTimeMillis",
    "lastExecuteTime",
    "spendTime",
    "errorMessage"
})
public class JobSummary {

    @JsonAlias({"id", "jobId"})
    private String jobId;

    private String jobName;
    private String userId;
    private String userName;
    private String userEmail;
    private String group;

    @JsonAlias({"dbvendor", "dbVendor"})
    private String dbVendor;

    @JsonAlias({"sqlSource", "sqlsource"})
    private String sqlsource;

    private JobStatus status;

    @JsonAlias({"regular", "regularJob"})
    private Boolean regularJob;

    private Boolean persist;
    private Boolean incremental;
    private Boolean schedulable;

    @JsonAlias({"cron", "cronExpression"})
    private String cronExpression;

    private Boolean cancelSchedule;

    @JsonAlias({"topLevel", "onTop"})
    private Boolean onTop;

    private String createTime;
    private String startTime;
    private String endTime;
    private String executeTime;
    private Long executeTimeMillis;
    private String lastExecuteTime;
    private Long spendTime;
    private String errorMessage;

    public JobSummary() {
    }

    public String getJobId() {
        return jobId;
    }

    @JsonAlias({"id", "jobId"})
    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    @JsonIgnore
    public String getId() {
        return jobId;
    }

    @JsonIgnore
    public void setId(String id) {
        this.jobId = id;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getDbVendor() {
        return dbVendor;
    }

    @JsonAlias({"dbvendor", "dbVendor"})
    public void setDbVendor(String dbVendor) {
        this.dbVendor = dbVendor;
    }

    @JsonIgnore
    public String getDbvendor() {
        return dbVendor;
    }

    @JsonIgnore
    public void setDbvendor(String dbvendor) {
        this.dbVendor = dbvendor;
    }

    public String getSqlsource() {
        return sqlsource;
    }

    @JsonAlias({"sqlSource", "sqlsource"})
    public void setSqlsource(String sqlsource) {
        this.sqlsource = sqlsource;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public Boolean getRegularJob() {
        return regularJob;
    }

    @JsonAlias({"regular", "regularJob"})
    public void setRegularJob(Boolean regularJob) {
        this.regularJob = regularJob;
    }

    @JsonIgnore
    public Boolean getRegular() {
        return regularJob;
    }

    @JsonIgnore
    public void setRegular(Boolean regular) {
        this.regularJob = regular;
    }

    public Boolean getPersist() {
        return persist;
    }

    public void setPersist(Boolean persist) {
        this.persist = persist;
    }

    public Boolean getIncremental() {
        return incremental;
    }

    public void setIncremental(Boolean incremental) {
        this.incremental = incremental;
    }

    public Boolean getSchedulable() {
        return schedulable;
    }

    public void setSchedulable(Boolean schedulable) {
        this.schedulable = schedulable;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    @JsonAlias({"cron", "cronExpression"})
    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    @JsonIgnore
    public String getCron() {
        return cronExpression;
    }

    @JsonIgnore
    public void setCron(String cron) {
        this.cronExpression = cron;
    }

    public Boolean getCancelSchedule() {
        return cancelSchedule;
    }

    public void setCancelSchedule(Boolean cancelSchedule) {
        this.cancelSchedule = cancelSchedule;
    }

    public Boolean getOnTop() {
        return onTop;
    }

    @JsonAlias({"topLevel", "onTop"})
    public void setOnTop(Boolean onTop) {
        this.onTop = onTop;
    }

    @JsonIgnore
    public Boolean getTopLevel() {
        return onTop;
    }

    @JsonIgnore
    public void setTopLevel(Boolean topLevel) {
        this.onTop = topLevel;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public String getExecuteTime() {
        return executeTime;
    }

    public void setExecuteTime(String executeTime) {
        this.executeTime = executeTime;
    }

    public Long getExecuteTimeMillis() {
        return executeTimeMillis;
    }

    public void setExecuteTimeMillis(Long executeTimeMillis) {
        this.executeTimeMillis = executeTimeMillis;
    }

    public String getLastExecuteTime() {
        return lastExecuteTime;
    }

    public void setLastExecuteTime(String lastExecuteTime) {
        this.lastExecuteTime = lastExecuteTime;
    }

    public Long getSpendTime() {
        return spendTime;
    }

    public void setSpendTime(Long spendTime) {
        this.spendTime = spendTime;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
