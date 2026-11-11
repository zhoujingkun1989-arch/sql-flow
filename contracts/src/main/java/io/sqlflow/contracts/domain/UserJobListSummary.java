package io.sqlflow.contracts.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Paginated job listing payload ({@code UserJobListSummary}) returned inside {@code ApiResponse<UserJobListSummary>}
 * by {@code POST /sqlflow/job/displayUserJobsSummary} ({@code 02 §5.5}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserJobListSummary {

    private int total;
    private int success;
    private int partialSuccess;
    private int fail;
    private List<String> jobIds;
    private List<JobDetail> jobDetails;

    public UserJobListSummary() {
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getSuccess() {
        return success;
    }

    public void setSuccess(int success) {
        this.success = success;
    }

    public int getPartialSuccess() {
        return partialSuccess;
    }

    public void setPartialSuccess(int partialSuccess) {
        this.partialSuccess = partialSuccess;
    }

    public int getFail() {
        return fail;
    }

    public void setFail(int fail) {
        this.fail = fail;
    }

    public List<String> getJobIds() {
        return jobIds;
    }

    public void setJobIds(List<String> jobIds) {
        this.jobIds = jobIds;
    }

    public List<JobDetail> getJobDetails() {
        return jobDetails;
    }

    public void setJobDetails(List<JobDetail> jobDetails) {
        this.jobDetails = jobDetails;
    }
}
