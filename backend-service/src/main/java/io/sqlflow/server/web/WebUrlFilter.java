package io.sqlflow.server.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * Transparently strips {@code /api/gspLive_backend} and {@code /gspLive_backend} URL prefixes
 * across all HTTP methods ({@code GET, POST, PUT, DELETE, OPTIONS, HEAD, PATCH}) so all 3 path
 * variants route identically on port {@code :8165} ({@code OBL-013}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WebUrlFilter extends OncePerRequestFilter {

    private static final String PREFIX_API_GSPLIVE = "/api/gspLive_backend";
    private static final String PREFIX_GSPLIVE = "/gspLive_backend";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String contextPath = request.getContextPath() != null ? request.getContextPath() : "";
        String rawUri = request.getRequestURI() != null ? request.getRequestURI() : "/";
        String pathWithinApp = rawUri.startsWith(contextPath) ? rawUri.substring(contextPath.length()) : rawUri;

        String stripped = stripBackendPrefix(pathWithinApp);
        if (stripped == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String origServletPath = request.getServletPath();
        String origPathInfo = request.getPathInfo();
        final String newRequestUri = contextPath + stripped;
        final String newServletPath;
        final String newPathInfo;

        if (origServletPath != null && !origServletPath.isEmpty()) {
            String s = stripBackendPrefix(origServletPath);
            newServletPath = s != null ? s : stripped;
            newPathInfo = origPathInfo;
        } else {
            newServletPath = "";
            String p = origPathInfo != null ? stripBackendPrefix(origPathInfo) : null;
            newPathInfo = origPathInfo != null ? (p != null ? p : stripped) : null;
        }

        HttpServletRequestWrapper wrapped = new HttpServletRequestWrapper(request) {
            @Override
            public String getRequestURI() {
                return newRequestUri;
            }

            @Override
            public String getServletPath() {
                return newServletPath;
            }

            @Override
            public String getPathInfo() {
                return newPathInfo;
            }

            @Override
            public StringBuffer getRequestURL() {
                StringBuffer url = new StringBuffer();
                String scheme = getScheme();
                int port = getServerPort();
                url.append(scheme).append("://").append(getServerName());
                if (("http".equalsIgnoreCase(scheme) && port != 80)
                        || ("https".equalsIgnoreCase(scheme) && port != 443)) {
                    url.append(':').append(port);
                }
                url.append(newRequestUri);
                return url;
            }
        };

        wrapped.removeAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE);
        ServletRequestPathUtils.parseAndCache(wrapped);
        filterChain.doFilter(wrapped, response);
    }

    public static String stripBackendPrefix(String path) {
        if (path == null) {
            return null;
        }
        if (path.equals(PREFIX_API_GSPLIVE)) {
            return "/";
        }
        if (path.startsWith(PREFIX_API_GSPLIVE + "/")) {
            return path.substring(PREFIX_API_GSPLIVE.length());
        }
        if (path.equals(PREFIX_GSPLIVE)) {
            return "/";
        }
        if (path.startsWith(PREFIX_GSPLIVE + "/")) {
            return path.substring(PREFIX_GSPLIVE.length());
        }
        return null;
    }
}
