package io.sqlflow.contracts.domain;

/**
 * All 14 frozen bytecode-verified status codes for V1 & Anonymous API {@link R} envelopes
 * ({@code 01 §3.2.2}).
 */
public enum ResultCode implements IResultCode {
    SUCCESS(200, "Operation successful"),
    FAILURE(400, "Business exception"),
    UN_AUTHORIZED(401, "Request unauthorized"),
    CLIENT_UN_AUTHORIZED(401, "Client request unauthorized"),
    NOT_FOUND(404, "404 Request not found"),
    MSG_NOT_READABLE(400, "Message not readable"),
    METHOD_NOT_SUPPORTED(405, "Current request method not supported"),
    MEDIA_TYPE_NOT_SUPPORTED(415, "Current media type not supported"),
    REQ_REJECT(403, "Request rejected"),
    INTERNAL_SERVER_ERROR(500, "Server exception"),
    PARAM_MISS(400, "Missing required request parameters"),
    PARAM_TYPE_ERROR(400, "Request parameter type error"),
    PARAM_BIND_ERROR(400, "Request parameter binding error"),
    PARAM_VALID_ERROR(400, "Parameter validation failed");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
