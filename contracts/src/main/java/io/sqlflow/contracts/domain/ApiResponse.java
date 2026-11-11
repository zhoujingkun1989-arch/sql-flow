package io.sqlflow.contracts.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Primary SQLService & Web API JSON envelope ({@code /sqlflow/generation/*}, {@code /sqlflow/job/*},
 * {@code /sqlflow/user/*}, {@code /sqlflow/admin/*}) ({@code 00 §3.3 #3}, {@code 02 §5.1}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"code", "data", "error", "msg", "message", "sessionId", "jobId", "mode", "userId", "expired"})
public class ApiResponse<T> {

    private Integer code;
    private T data;
    private String error;
    private String msg;
    private String message;
    private String sessionId;
    private String jobId;
    private String mode;
    private String userId;
    private Boolean expired;

    public ApiResponse() {
    }

    public ApiResponse(Integer code, T data, String error, String sessionId) {
        this.code = code;
        this.data = data;
        this.error = error;
        this.sessionId = sessionId;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, data, null, null);
    }

    public static <T> ApiResponse<T> okWithSession(T data, String sessionId) {
        return new ApiResponse<>(200, data, null, sessionId);
    }

    public static <T> ApiResponse<T> okWithMode(T data, String sessionId, String mode) {
        ApiResponse<T> res = new ApiResponse<>(200, data, null, sessionId);
        res.setMode(mode);
        return res;
    }

    public static <T> ApiResponse<T> error(int code, String error) {
        return new ApiResponse<>(code, null, error, null);
    }

    public static <T> ApiResponse<T> failMsg(int code, String msg) {
        ApiResponse<T> res = new ApiResponse<>(code, null, null, null);
        res.setMsg(msg);
        return res;
    }

    public static <T> ApiResponse<T> expired(String error) {
        ApiResponse<T> res = new ApiResponse<>(600, null, error, null);
        res.setExpired(Boolean.TRUE);
        return res;
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Boolean getExpired() {
        return expired;
    }

    public void setExpired(Boolean expired) {
        this.expired = expired;
    }
}
