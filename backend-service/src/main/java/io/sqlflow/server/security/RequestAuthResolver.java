package io.sqlflow.server.security;

import io.sqlflow.contracts.domain.UserInfo;
import io.sqlflow.server.config.SqlflowRuntimeConfig;
import io.sqlflow.server.persistence.SqliteRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * Central request identity, JWT/secretKey token, and account-tier resolver implementing
 * Layers 2–4 of the 5-layer security pipeline ({@code 00 §3.1}, {@code 01 §3.1}).
 */
@Component
public class RequestAuthResolver {

    public static final String ATTR_V1_USER_ID = "sqlflow.v1.userId";
    public static final String ATTR_V1_SECRET_KEY = "sqlflow.v1.secretKey";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final SqlflowRuntimeConfig config;
    private final SqliteRepository sqliteRepository;
    private final CryptoService cryptoService;

    public RequestAuthResolver(
            SqlflowRuntimeConfig config,
            SqliteRepository sqliteRepository,
            CryptoService cryptoService) {
        this.config = config;
        this.sqliteRepository = sqliteRepository;
        this.cryptoService = cryptoService;
    }

    /**
     * Resolves and validates the caller's {@code userId}.
     */
    public String resolveAndValidateUser(HttpServletRequest request, String explicitUserId, boolean requirePremium) {
        if (request != null) {
            Object v1Uid = request.getAttribute(ATTR_V1_USER_ID);
            if (v1Uid != null && !v1Uid.toString().isBlank()) {
                String uid = v1Uid.toString();
                if (requirePremium) {
                    assertPremium(uid);
                }
                return uid;
            }
        }

        // Layer 3a: ignore_user_token bypass
        if (config.isIgnoreUserToken()) {
            if (explicitUserId != null && !explicitUserId.isBlank()) {
                UserInfo found = sqliteRepository.findByUserId(explicitUserId);
                if (found != null) {
                    return found.getUserId();
                }
            }
            return config.getAnonymousUserId();
        }

        String candidateToken = extractExplicitToken(request);
        String uid = explicitUserId;
        if ((uid == null || uid.isBlank()) && request != null) {
            uid = request.getParameter("userId");
        }

        // If caller supplied a JWT/secretKey without userId, resolve userId from token
        if ((uid == null || uid.isBlank()) && candidateToken != null && !candidateToken.isBlank()) {
            String fromJwt = cryptoService.verifyJwtAndGetUserId(candidateToken);
            if (fromJwt != null) {
                uid = fromJwt;
            } else {
                UserInfo bySk = sqliteRepository.findBySecretKey(candidateToken);
                if (bySk != null) {
                    uid = bySk.getUserId();
                }
            }
        }

        if (uid == null || uid.isBlank()) {
            uid = UserInfo.GUEST_USER_ID;
        }

        // Built-in guest user gudu|0123456789
        if (UserInfo.GUEST_USER_ID.equals(uid)) {
            if (candidateToken != null && !candidateToken.isBlank()) {
                // If an explicit Bearer/token was supplied with guest userId, verify it isn't a forged JWT
                verifySuppliedToken(uid, candidateToken);
            }
            if (requirePremium && !config.isOnPremise()) {
                throw SqlflowBusinessException.notPremium(
                        "Your basic account is expired, please upgrade to premium.", true);
            }
            return uid;
        }

        UserInfo user = sqliteRepository.findByUserId(uid);
        if (user == null) {
            throw SqlflowBusinessException.invalidUser("Invalid user.");
        }

        if (candidateToken != null && !candidateToken.isBlank()) {
            verifySuppliedToken(uid, candidateToken);
        }

        // Check account expiry if endDate is set
        if (user.getEndDate() != null && user.getEndDate().length() >= 10) {
            try {
                LocalDate end = LocalDate.parse(user.getEndDate().substring(0, 10), DATE_FMT);
                if (end.isBefore(LocalDate.now())) {
                    throw SqlflowBusinessException.invalidUser("Your account has expired.");
                }
            } catch (SqlflowBusinessException e) {
                throw e;
            } catch (Exception ignored) {
            }
        }

        if (requirePremium) {
            assertPremium(uid);
        }
        return user.getUserId();
    }

    public void assertPremium(String userId) {
        if (config.isIgnoreUserToken()) {
            return;
        }
        if (UserInfo.GUEST_USER_ID.equals(userId)) {
            if (!config.isOnPremise()) {
                throw SqlflowBusinessException.notPremium(
                        "Your basic account is expired, please upgrade to premium.", true);
            }
            return;
        }
        UserInfo user = sqliteRepository.findByUserId(userId);
        if (user == null) {
            throw SqlflowBusinessException.invalidUser("Invalid user.");
        }
        if (!"premium".equalsIgnoreCase(user.getUserType())) {
            throw SqlflowBusinessException.notPremium(
                    "Your basic account is expired, please upgrade to premium.", true);
        }
    }

    private void verifySuppliedToken(String expectedUserId, String token) {
        // Check JWT
        String jwtUser = cryptoService.verifyJwtAndGetUserId(token);
        if (jwtUser != null) {
            if (!UserInfo.GUEST_USER_ID.equals(expectedUserId) && !expectedUserId.equals(jwtUser)) {
                throw SqlflowBusinessException.invalidUser("Token user mismatch.");
            }
            return;
        }
        // Check user's secretKey
        UserInfo bySk = sqliteRepository.findBySecretKey(token);
        if (bySk != null && (UserInfo.GUEST_USER_ID.equals(expectedUserId) || expectedUserId.equals(bySk.getUserId()))) {
            return;
        }
        throw SqlflowBusinessException.invalidUser("Invalid or expired token.");
    }

    private String extractExplicitToken(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String paramToken = request.getParameter("token");
        if (paramToken != null && !paramToken.isBlank()) {
            return paramToken.trim();
        }
        String headerToken = request.getHeader("token");
        if (headerToken != null && !headerToken.isBlank()) {
            return headerToken.trim();
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && !auth.isBlank()) {
            String trimmed = auth.trim();
            if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
                String val = trimmed.substring(7).trim();
                return val.isEmpty() ? null : val;
            }
            // Note: Web SPA sends "Authorization: Token <staticConfigToken>" or empty "Token " by default (04 §12.1 #3).
            // Accept "Token <val>" when it is either a compact 3-part JWT or a registered API secretKey.
            if (trimmed.regionMatches(true, 0, "Token ", 0, 6)) {
                String val = trimmed.substring(6).trim();
                if (!val.isEmpty()
                        && (val.chars().filter(c -> c == '.').count() == 2
                                || sqliteRepository.findBySecretKey(val) != null)) {
                    return val;
                }
            }
        }
        return null;
    }
}
