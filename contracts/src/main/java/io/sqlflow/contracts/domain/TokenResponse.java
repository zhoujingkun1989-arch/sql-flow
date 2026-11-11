package io.sqlflow.contracts.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Unwrapped response payload for {@code POST /user/generateToken} ({@code 00 §3.3 #10}, {@code 01 §3.3.4}).
 *
 * <p>Wire invariant: {@code code} MUST serialize as JSON string {@code "200"} (NOT integer {@code 200}),
 * and {@code userSource} is a {@link String} ({@code "premise"}, {@code "default"}, {@code "auth0"}, etc.).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"userId", "code", "token", "userSource", "error"})
public class TokenResponse {

    public static final String OK_CODE = "200";

    private String userId;
    private String code = OK_CODE;
    private String token;
    private String userSource;
    private String error;

    public TokenResponse() {
    }

    public TokenResponse(String userId, String token, String userSource) {
        this.userId = userId;
        this.code = OK_CODE;
        this.token = token;
        this.userSource = userSource;
    }

    public static TokenResponse ok(String userId, String token, String userSource) {
        return new TokenResponse(userId, token, userSource);
    }

    public static TokenResponse error(String code, String error) {
        TokenResponse res = new TokenResponse();
        res.setCode(code);
        res.setError(error);
        return res;
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

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getUserSource() {
        return userSource;
    }

    public void setUserSource(String userSource) {
        this.userSource = userSource;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
