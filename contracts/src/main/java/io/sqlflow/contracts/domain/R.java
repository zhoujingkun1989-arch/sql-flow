package io.sqlflow.contracts.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * V1 & Anonymous API JSON envelope ({@code R<T>}) used by {@code /api/sqlflow/v1/**},
 * {@code /api/sqlflow/anonymous/**}, and web user management endpoints ({@code 01 §3.2.1}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"code", "success", "data", "message", "msg", "error", "docs"})
public class R<T> {

    private int code;
    private Boolean success;
    private T data;
    private String message;
    private String msg;
    private String error;
    private String docs;

    public R() {
    }

    public R(int code, Boolean success, T data, String message) {
        this.code = code;
        this.success = success;
        this.data = data;
        this.message = message;
    }

    public static <T> R<T> data(T data) {
        return new R<>(ResultCode.SUCCESS.getCode(), Boolean.TRUE, data, ResultCode.SUCCESS.getMessage());
    }

    public static <T> R<T> success(IResultCode resultCode) {
        return new R<>(resultCode.getCode(), Boolean.TRUE, null, resultCode.getMessage());
    }

    public static <T> R<T> fail(IResultCode resultCode) {
        return new R<>(resultCode.getCode(), Boolean.FALSE, null, resultCode.getMessage());
    }

    public static <T> R<T> fail(int code, String msg) {
        R<T> r = new R<>(code, Boolean.FALSE, null, msg);
        r.setMsg(msg);
        return r;
    }

    public static <T> R<T> failWithError(int code, String error, String docs) {
        R<T> r = new R<>(code, Boolean.FALSE, null, null);
        r.setError(error);
        r.setDocs(docs);
        return r;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getDocs() {
        return docs;
    }

    public void setDocs(String docs) {
        this.docs = docs;
    }
}
