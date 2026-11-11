package io.sqlflow.server.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Unifies parameter extraction across query strings, {@code application/x-www-form-urlencoded},
 * {@code multipart/form-data}, and {@code application/json} request bodies so every gateway and
 * service endpoint accepts both form-encoded and JSON callers seamlessly.
 */
@Component
public class ControllerParamHelper {

    private final ObjectMapper objectMapper;

    public ControllerParamHelper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, String> extractParams(HttpServletRequest request, Map<String, Object> jsonBody) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (request != null) {
            Map<String, String[]> paramMap = request.getParameterMap();
            if (paramMap != null) {
                for (Map.Entry<String, String[]> entry : paramMap.entrySet()) {
                    String[] vals = entry.getValue();
                    if (vals != null && vals.length > 0 && vals[0] != null) {
                        merged.put(entry.getKey(), vals[0]);
                    }
                }
            }
            if (jsonBody == null && isJsonRequest(request)) {
                jsonBody = tryReadJsonBody(request);
            }
        }
        if (jsonBody != null) {
            for (Map.Entry<String, Object> entry : jsonBody.entrySet()) {
                Object val = entry.getValue();
                if (val == null) {
                    continue;
                }
                if (val instanceof String || val instanceof Number || val instanceof Boolean) {
                    merged.put(entry.getKey(), String.valueOf(val));
                } else {
                    try {
                        merged.put(entry.getKey(), objectMapper.writeValueAsString(val));
                    } catch (IOException ignored) {
                        merged.put(entry.getKey(), String.valueOf(val));
                    }
                }
            }
        }
        return merged;
    }

    public Map<String, Object> extractObjectMap(HttpServletRequest request, Map<String, Object> jsonBody) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (request != null && request.getParameterMap() != null) {
            for (Map.Entry<String, String[]> e : request.getParameterMap().entrySet()) {
                if (e.getValue() != null && e.getValue().length > 0) {
                    out.put(e.getKey(), e.getValue()[0]);
                }
            }
            if (jsonBody == null && isJsonRequest(request)) {
                jsonBody = tryReadJsonBody(request);
            }
        }
        if (jsonBody != null) {
            out.putAll(jsonBody);
        }
        return out;
    }

    private static boolean isJsonRequest(HttpServletRequest request) {
        String ct = request.getContentType();
        return ct != null && ct.toLowerCase().contains(MediaType.APPLICATION_JSON_VALUE);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> tryReadJsonBody(HttpServletRequest request) {
        Object cached = request.getAttribute(io.sqlflow.server.web.OptionalJsonBodyArgumentResolver.CACHED_JSON_BODY_ATTR);
        if (cached instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        try {
            byte[] bytes = request.getInputStream().readAllBytes();
            if (bytes.length == 0) {
                return null;
            }
            String raw = new String(bytes, StandardCharsets.UTF_8).trim();
            if (raw.startsWith("{")) {
                Map<String, Object> parsed =
                        objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
                request.setAttribute(io.sqlflow.server.web.OptionalJsonBodyArgumentResolver.CACHED_JSON_BODY_ATTR, parsed);
                return parsed;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
