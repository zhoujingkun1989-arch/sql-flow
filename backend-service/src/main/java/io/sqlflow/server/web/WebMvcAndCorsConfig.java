package io.sqlflow.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.server.config.SqlflowRuntimeConfig;
import io.sqlflow.server.security.AnonymousRateLimitInterceptor;
import io.sqlflow.server.security.ApiV1AuthInterceptor;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configures CORS ({@code cros.allowedOrigins} & {@code cors.allowedOrigins}), security interceptors,
 * and static asset mounts for Web SPA ({@code /}) and Embeddable Widget SDK ({@code /widget/**})
 * ({@code OBL-014, OBL-015, OBL-120}).
 */
@Configuration
public class WebMvcAndCorsConfig implements WebMvcConfigurer {

    private final SqlflowRuntimeConfig config;
    private final AnonymousRateLimitInterceptor anonymousRateLimitInterceptor;
    private final ApiV1AuthInterceptor apiV1AuthInterceptor;
    private final ObjectMapper objectMapper;

    public WebMvcAndCorsConfig(
            SqlflowRuntimeConfig config,
            AnonymousRateLimitInterceptor anonymousRateLimitInterceptor,
            ApiV1AuthInterceptor apiV1AuthInterceptor,
            ObjectMapper objectMapper) {
        this.config = config;
        this.anonymousRateLimitInterceptor = anonymousRateLimitInterceptor;
        this.apiV1AuthInterceptor = apiV1AuthInterceptor;
        this.objectMapper = objectMapper;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new OptionalJsonBodyArgumentResolver(objectMapper));
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = config.getAllowedOrigins();
        CorsRegistration reg = registry.addMapping("/**")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD", "PATCH")
                .allowedHeaders("*")
                .exposedHeaders(
                        "Content-Disposition",
                        "X-Frame-Options",
                        "X-RateLimit-Limit",
                        "X-RateLimit-Remaining",
                        "X-RateLimit-Reset")
                .allowCredentials(true)
                .maxAge(3600);
        if (origins.contains("*")) {
            reg.allowedOriginPatterns("*");
        } else {
            reg.allowedOrigins(origins.toArray(String[]::new));
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(anonymousRateLimitInterceptor)
                .addPathPatterns("/api/anonymous/**", "/api/sqlflow/anonymous/**");
        registry.addInterceptor(apiV1AuthInterceptor)
                .addPathPatterns("/v1/sqlflow/**", "/api/sqlflow/v1/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String widgetFileUri = config.getWidgetDir().toUri().toString();
        if (!widgetFileUri.endsWith("/")) {
            widgetFileUri = widgetFileUri + "/";
        }
        registry.addResourceHandler("/widget/**")
                .addResourceLocations(widgetFileUri, "classpath:/widget/");

        String staticFileUri = config.getStaticDir().toUri().toString();
        if (!staticFileUri.endsWith("/")) {
            staticFileUri = staticFileUri + "/";
        }
        registry.addResourceHandler("/**")
                .addResourceLocations(staticFileUri, "classpath:/static/");
    }
}
