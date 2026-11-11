package io.sqlflow.server.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.server.controller.OptionalJsonBody;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Argument resolver for {@link OptionalJsonBody} parameters.
 */
public class OptionalJsonBodyArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String CACHED_JSON_BODY_ATTR = "__sqlflow_cached_json_body";
    private static final Object EMPTY_SENTINEL = new Object();

    private final ObjectMapper objectMapper;

    public OptionalJsonBodyArgumentResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(OptionalJsonBody.class);
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            return null;
        }
        String ct = request.getContentType();
        if (ct == null || !ct.toLowerCase(Locale.ROOT).contains(MediaType.APPLICATION_JSON_VALUE)) {
            return null;
        }

        Object cached = request.getAttribute(CACHED_JSON_BODY_ATTR);
        if (cached == null) {
            try {
                byte[] bytes = request.getInputStream().readAllBytes();
                String raw = new String(bytes, StandardCharsets.UTF_8).trim();
                if (raw.isEmpty()) {
                    cached = EMPTY_SENTINEL;
                } else if (raw.startsWith("[")) {
                    cached = objectMapper.readValue(raw, new TypeReference<List<Object>>() {});
                } else {
                    cached = objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
                }
                request.setAttribute(CACHED_JSON_BODY_ATTR, cached);
            } catch (Exception ex) {
                HttpInputMessage inputMessage = new ServletServerHttpRequest(request);
                throw new HttpMessageNotReadableException(
                        "Invalid JSON request body: " + ex.getMessage(), ex, inputMessage);
            }
        }

        if (cached == EMPTY_SENTINEL) {
            return null;
        }
        Class<?> paramType = parameter.getParameterType();
        if (paramType == Object.class) {
            return cached;
        }
        if (Map.class.isAssignableFrom(paramType) && cached instanceof Map<?, ?>) {
            return cached;
        }
        return null;
    }
}
