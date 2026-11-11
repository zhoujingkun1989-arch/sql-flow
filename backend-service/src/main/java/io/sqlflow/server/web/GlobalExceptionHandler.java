package io.sqlflow.server.web;

import io.sqlflow.contracts.domain.ApiResponse;
import io.sqlflow.contracts.domain.R;
import io.sqlflow.contracts.domain.ResultCode;
import io.sqlflow.server.config.SqlflowTimeoutException;
import io.sqlflow.server.security.SqlflowBusinessException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Enforces Wire Invariant #1 across all surfaces:
 * <ul>
 *   <li>Standard endpoints: HTTP {@code 200 OK} with JSON {@code {"code": <errCode>, "error": "..."}}</li>
 *   <li>{@code /v1/sqlflow/**}: {@code R<Void>} envelope ({@code HTTP 401} on invalid API key, {@code 200}/{@code 400} otherwise)</li>
 *   <li>{@code /api/anonymous/**}: HTTP {@code 400}/{@code 429}/{@code 500} JSON</li>
 *   <li>{@code /sqlflow/generation/sqlflow/join-analysis}: HTTP {@code 400}/{@code 403}/{@code 500} JSON</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(SqlflowBusinessException.class)
    public ResponseEntity<Object> handleBusiness(SqlflowBusinessException ex, HttpServletRequest request) {
        String uri = request != null ? request.getRequestURI() : "";
        if (uri != null && uri.contains("/v1/sqlflow/")) {
            int httpCode = ex.getHttpStatus() == 401 ? 401 : 200;
            return ResponseEntity.status(httpCode)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(R.fail(ex.getCode(), ex.getMessage()));
        }
        if (uri != null && uri.contains("/api/anonymous/")) {
            int status = ex.getCode() >= 400 && ex.getCode() < 600 ? ex.getCode() : 500;
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("code", status);
            err.put("error", status == 400 ? "bad_request" : "internal_error");
            err.put("message", ex.getMessage());
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(err);
        }
        if (uri != null && uri.endsWith("/join-analysis")) {
            int status = ex.getCode() >= 400 && ex.getCode() < 600 ? ex.getCode() : 500;
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("code", status, "error", ex.getMessage()));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ex.getCode());
        body.put("error", ex.getMessage());
        if (ex.isExpired()) {
            body.put("expired", true);
        }
        if (ex.getMachineCode() != null) {
            body.put("machine.code", ex.getMachineCode());
        }
        if (ex.getJobId() != null) {
            body.put("jobId", ex.getJobId());
        }
        return ResponseEntity.status(ex.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @ExceptionHandler(SqlflowTimeoutException.class)
    public ResponseEntity<Object> handleTimeout(SqlflowTimeoutException ex, HttpServletRequest request) {
        String uri = request != null ? request.getRequestURI() : "";
        if (uri != null && uri.endsWith("/join-analysis")) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("code", 500, "error", ex.getMessage()));
        }
        if (uri != null && uri.contains("/v1/sqlflow/")) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(R.fail(500, ex.getMessage()));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(500, ex.getMessage()));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Object> handleBadRequest(Exception ex, HttpServletRequest request) {
        String uri = request != null ? request.getRequestURI() : "";
        if (uri != null && uri.contains("/api/anonymous/")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("code", 400, "error", "bad_request", "message", ex.getMessage()));
        }
        if (uri != null && uri.endsWith("/join-analysis")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("code", 400, "error", ex.getMessage()));
        }
        if (uri != null && uri.contains("/v1/sqlflow/")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(R.fail(ResultCode.PARAM_MISS.getCode(), ex.getMessage()));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(400, ex.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Object> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(404, ex.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Object> handleUploadLimit(MaxUploadSizeExceededException ex) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(500, "Uploaded file exceeds maximum allowed size (500MB)."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.warn("Unhandled controller error on {}: {}", request != null ? request.getRequestURI() : "?", ex.getMessage());
        String uri = request != null ? request.getRequestURI() : "";
        if (uri != null && uri.contains("/api/anonymous/")) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("code", 500, "error", "internal_error", "message", String.valueOf(ex.getMessage())));
        }
        if (uri != null && uri.endsWith("/join-analysis")) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("code", 500, "error", "join-analysis failed"));
        }
        if (uri != null && uri.contains("/v1/sqlflow/")) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(R.fail(500, ex.getMessage() != null ? ex.getMessage() : "Server exception"));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(500, ex.getMessage() != null ? ex.getMessage() : "Internal server error"));
    }
}
