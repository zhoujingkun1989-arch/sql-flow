package io.sqlflow.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.contracts.domain.R;
import io.sqlflow.contracts.domain.ResultCode;
import io.sqlflow.contracts.domain.UserInfo;
import io.sqlflow.server.persistence.SqliteRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Layer 2 Security Interceptor: validates {@code Authorization: Bearer <secretKey>} across all 60
 * {@code /v1/sqlflow/**} endpoints (except public {@code GET /v1/sqlflow/sqlflow/graph/share/{shareId}}),
 * returning HTTP 401 {@code R<Void>} on missing or invalid API keys ({@code OBL-019, OBL-027}).
 */
@Component
public class ApiV1AuthInterceptor implements HandlerInterceptor {

    private final SqliteRepository sqliteRepository;
    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper;

    public ApiV1AuthInterceptor(
            SqliteRepository sqliteRepository,
            CryptoService cryptoService,
            ObjectMapper objectMapper) {
        this.sqliteRepository = sqliteRepository;
        this.cryptoService = cryptoService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        if ("GET".equalsIgnoreCase(request.getMethod())
                && uri != null
                && (uri.contains("/v1/sqlflow/sqlflow/graph/share/")
                        || uri.contains("/api/sqlflow/v1/sqlflow/graph/share/"))) {
            return true;
        }

        String secretKey = extractSecretKey(request);
        if (secretKey == null || secretKey.isBlank()) {
            writeUnauthorized(response, "Missing Authorization: Bearer <secretKey> header");
            return false;
        }

        UserInfo owner = sqliteRepository.findBySecretKey(secretKey);
        if (owner == null) {
            String jwtUserId = cryptoService.verifyJwtAndGetUserId(secretKey);
            if (jwtUserId != null) {
                owner = sqliteRepository.findByUserId(jwtUserId);
            }
        }
        if (owner == null) {
            writeUnauthorized(response, "Invalid API secretKey");
            return false;
        }

        request.setAttribute(RequestAuthResolver.ATTR_V1_USER_ID, owner.getUserId());
        request.setAttribute(RequestAuthResolver.ATTR_V1_SECRET_KEY, secretKey);
        return true;
    }

    private String extractSecretKey(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null) {
            String trimmed = auth.trim();
            if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
                String token = trimmed.substring(7).trim();
                if (!token.isEmpty()) {
                    return token;
                }
            } else if (trimmed.regionMatches(true, 0, "Token ", 0, 6)) {
                String token = trimmed.substring(6).trim();
                if (!token.isEmpty()) {
                    return token;
                }
            }
        }
        String headerSk = request.getHeader("secretKey");
        if (headerSk != null && !headerSk.isBlank()) {
            return headerSk.trim();
        }
        String paramSk = request.getParameter("secretKey");
        if (paramSk != null && !paramSk.isBlank()) {
            return paramSk.trim();
        }
        return null;
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        R<Void> fail = R.fail(ResultCode.UN_AUTHORIZED.getCode(), message);
        response.getWriter().write(objectMapper.writeValueAsString(fail));
    }
}
