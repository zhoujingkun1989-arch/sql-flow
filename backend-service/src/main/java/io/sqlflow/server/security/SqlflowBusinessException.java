package io.sqlflow.server.security;

/**
 * Unified domain exception carrying SQLFlow business status codes (Wire Invariant #1).
 */
public class SqlflowBusinessException extends RuntimeException {

    private final int code;
    private final int httpStatus;
    private final boolean expired;
    private final String machineCode;
    private final String jobId;

    public SqlflowBusinessException(
            int code,
            int httpStatus,
            String message,
            boolean expired,
            String machineCode,
            String jobId) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.expired = expired;
        this.machineCode = machineCode;
        this.jobId = jobId;
    }

    public SqlflowBusinessException(int code, String message) {
        this(code, 200, message, false, null, null);
    }

    public static SqlflowBusinessException badRequest(String message) {
        return new SqlflowBusinessException(400, 200, message, false, null, null);
    }

    public static SqlflowBusinessException invalidUser(String message) {
        return new SqlflowBusinessException(401, 200, message, false, null, null);
    }

    public static SqlflowBusinessException invalidApiKey(String message) {
        return new SqlflowBusinessException(401, 401, message, false, null, null);
    }

    public static SqlflowBusinessException forbidden(String message) {
        return new SqlflowBusinessException(403, 200, message, false, null, null);
    }

    public static SqlflowBusinessException notFound(String message) {
        return new SqlflowBusinessException(404, 200, message, false, null, null);
    }

    public static SqlflowBusinessException jobExpired(String jobId, String message) {
        return new SqlflowBusinessException(410, 200, message, true, null, jobId);
    }

    public static SqlflowBusinessException licenseError(int code, String machineCode, String message) {
        return new SqlflowBusinessException(code, 200, message, false, machineCode, null);
    }

    public static SqlflowBusinessException notPremium(String message, boolean expired) {
        return new SqlflowBusinessException(426, 200, message, expired, null, null);
    }

    public static SqlflowBusinessException recordSqlDisabled() {
        return new SqlflowBusinessException(
                509,
                200,
                "The 'record_sql' configuration is not enabled, hence unable to return user SQL records.",
                false,
                null,
                null);
    }

    public static SqlflowBusinessException badOperation(String message) {
        return new SqlflowBusinessException(500, 200, message, false, null, null);
    }

    public int getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public boolean isExpired() {
        return expired;
    }

    public String getMachineCode() {
        return machineCode;
    }

    public String getJobId() {
        return jobId;
    }
}
