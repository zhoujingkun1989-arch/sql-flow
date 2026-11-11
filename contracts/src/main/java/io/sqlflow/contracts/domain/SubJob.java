package io.sqlflow.contracts.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * Batch sub-job status entry inside {@link JobDetail} ({@code 02 §5.5}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({
    "batchId",
    "fileNames",
    "createTime",
    "type",
    "status",
    "executeTime"
})
public class SubJob {

    private String batchId;
    private List<String> fileNames;
    private String createTime;
    private SubJobType type;
    private JobStatus status;
    private String executeTime;

    public SubJob() {
    }

    public SubJob(
            String batchId,
            List<String> fileNames,
            String createTime,
            SubJobType type,
            JobStatus status,
            String executeTime) {
        this.batchId = batchId;
        this.fileNames = fileNames;
        this.createTime = createTime;
        this.type = type;
        this.status = status;
        this.executeTime = executeTime;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public List<String> getFileNames() {
        return fileNames;
    }

    public void setFileNames(List<String> fileNames) {
        this.fileNames = fileNames;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public SubJobType getType() {
        return type;
    }

    public void setType(SubJobType type) {
        this.type = type;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public String getExecuteTime() {
        return executeTime;
    }

    public void setExecuteTime(String executeTime) {
        this.executeTime = executeTime;
    }
}
