package io.sqlflow.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.server.config.SqlflowRuntimeConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Layer 1 Security Interceptor: enforces {@code anonymous_sql_Execute_limit} (default 50 requests
 * per UTC calendar day per client IP) on {@code /api/anonymous/**}, emitting {@code X-RateLimit-*}
 * headers and returning HTTP 429 JSON when exceeded ({@code OBL-028}).
 */
@Component
public class AnonymousRateLimitInterceptor implements HandlerInterceptor {

    private final SqlflowRuntimeConfig config;
    private final ObjectMapper objectMapper;
    private final Map<String, AtomicInteger> dailyCounters = new ConcurrentHashMap<>();

    public AnonymousRateLimitInterceptor(SqlflowRuntimeConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        int limit = Math.max(1, config.getAnonymousSqlExecuteLimit());
        LocalDate utcToday = LocalDate.now(ZoneOffset.UTC);
        long resetEpochSec = utcToday.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        String clientIp = resolveClientIp(request);

        // Evict stale day keys lazily when map grows
        String prefix = utcToday + "|";
        if (dailyCounters.size() > 2048) {
            dailyCounters.keySet().removeIf(k -> !k.startsWith(prefix));
        }

        String bucketKey = prefix + clientIp;
        int current = dailyCounters.computeIfAbsent(bucketKey, k -> new AtomicInteger(0)).incrementAndGet();
        int remaining = Math.max(0, limit - current);

        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
        response.setHeader("X-RateLimit-Reset", String.valueOf(resetEpochSec));

        if (current > limit) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", 429);
            body.put("success", false);
            body.put("error", "rate_limit_exceeded");
            body.put(
                    "message",
                    "Anonymous API rate limit exceeded (" + limit + " requests/day per IP). Get an API key for higher limits.");
            body.put("docs", "https://docs.gudusoft.com");
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return false;
        }
        return true;
    }

    public void resetAll() {
        dailyCounters.clear();
    }

    public static String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            String first = (comma > 0 ? xff.substring(0, comma) : xff).trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String xri = request.getHeader("X-Real-IP");
        if (xri != null && !xri.isBlank()) {
            return xri.trim();
        }
        String addr = request.getRemoteAddr();
        return (addr == null || addr.isBlank()) ? "127.0.0.1" : addr;
    }
}
