package io.sqlflow.contracts.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Unwrapped response payload for {@code POST /sqlflow/user/generateSecretKey} ({@code 00 §3.3 #10}, {@code 01 §3.3.1}).
 *
 * <p>Wire invariant: {@code code} MUST serialize as JSON string {@code "200"} (NOT integer {@code 200}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"userId", "code", "secretKey", "error"})
public class SecretKeyResponse {

    public static final String OK_CODE = "200";

    private String userId;
    private String code = OK_CODE;
    private String secretKey;
    private String error;

    public SecretKeyResponse() {
    }

    public SecretKeyResponse(String userId, String secretKey) {
        this.userId = userId;
        this.code = OK_CODE;
        this.secretKey = secretKey;
    }

    public static SecretKeyResponse ok(String userId, String secretKey) {
        return new SecretKeyResponse(userId, secretKey);
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
