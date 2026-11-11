package io.sqlflow.contracts.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Date;

/**
 * Shared lineage graph link record ({@code ShareInfo}) ({@code 02 §5.6}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ShareInfo {

    private String shareId;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss", timezone = "UTC")
    private Date shareDate;

    private Boolean tableLevel;
    private String userId;
    private String jobId;
    private String sessionId;
    private String title;

    public ShareInfo() {
    }

    public ShareInfo(String shareId, Date shareDate, Boolean tableLevel, String userId) {
        this.shareId = shareId;
        this.shareDate = shareDate;
        this.tableLevel = tableLevel;
        this.userId = userId;
    }

    public String getShareId() {
        return shareId;
    }

    public void setShareId(String shareId) {
        this.shareId = shareId;
    }

    public Date getShareDate() {
        return shareDate;
    }

    public void setShareDate(Date shareDate) {
        this.shareDate = shareDate;
    }

    public Boolean getTableLevel() {
        return tableLevel;
    }

    public void setTableLevel(Boolean tableLevel) {
        this.tableLevel = tableLevel;
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

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}
