package io.sqlflow.contracts.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.sqlflow.contracts.graph.SqlflowJSON;

/**
 * Non-standard response envelope for {@code POST /sqlflow/job/latestJob/sqlflow/graph}
 * ({@code 00 §3.3 #18}, {@code 02 §5.5}).
 *
 * <p>Wire invariant: exposes top-level {@code job}, {@code jobId}, and {@code sessionId} keys alongside
 * {@code code = 200} and {@code data = SqlflowJSON}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"code", "data", "job", "jobId", "sessionId", "error"})
public class LatestJobGraphEnvelope {

    private int code = 200;
    private SqlflowJSON data;
    private JobSummary job;
    private String jobId;
    private String sessionId;
    private String error;

    public LatestJobGraphEnvelope() {
    }

    public LatestJobGraphEnvelope(SqlflowJSON data, JobSummary job, String jobId, String sessionId) {
        this.code = 200;
        this.data = data;
        this.job = job;
        this.jobId = jobId;
        this.sessionId = sessionId;
    }

    public static LatestJobGraphEnvelope ok(SqlflowJSON data, JobSummary job, String jobId, String sessionId) {
        return new LatestJobGraphEnvelope(data, job, jobId, sessionId);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public SqlflowJSON getData() {
        return data;
    }

    public void setData(SqlflowJSON data) {
        this.data = data;
    }

    public JobSummary getJob() {
        return job;
    }

    public void setJob(JobSummary job) {
        this.job = job;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
