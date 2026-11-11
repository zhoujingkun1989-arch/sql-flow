package io.sqlflow.server.controller;

import io.sqlflow.contracts.domain.AiQueryResponse;
import io.sqlflow.contracts.domain.AiStatusEnvelope;
import io.sqlflow.contracts.domain.ApiResponse;
import io.sqlflow.contracts.domain.SuggestedQuestion;
import io.sqlflow.server.service.AiLineageService;
import io.sqlflow.server.service.LineageGenerationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI Lineage Assistant controller ({@code /sqlflow/generation/ai/**} and {@code /sqlflow/ai/**})
 * implementing Wire Invariant #16 ({@code OBL-079}).
 */
@RestController
public class AiLineageController {

    private final AiLineageService aiLineageService;
    private final ControllerParamHelper paramHelper;

    public AiLineageController(AiLineageService aiLineageService, ControllerParamHelper paramHelper) {
        this.aiLineageService = aiLineageService;
        this.paramHelper = paramHelper;
    }

    @RequestMapping(
            value = {"/sqlflow/generation/ai/status", "/sqlflow/ai/status"},
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AiStatusEnvelope status() {
        return aiLineageService.getStatus();
    }

    @PostMapping(
            value = {"/sqlflow/generation/ai/index", "/sqlflow/ai/index"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> buildIndex(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        return aiLineageService.buildIndex(paramHelper.extractParams(request, jsonBody));
    }

    @GetMapping(
            value = {"/sqlflow/generation/ai/index/status", "/sqlflow/ai/index/status"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> indexStatus(HttpServletRequest request) {
        Map<String, String> params = paramHelper.extractParams(request, null);
        String key = LineageGenerationService.firstNonBlank(params, "sessionId", "jobId", "id");
        return aiLineageService.getIndexStatus(key);
    }

    @DeleteMapping(
            value = {"/sqlflow/generation/ai/index", "/sqlflow/ai/index"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> deleteIndex(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String key = LineageGenerationService.firstNonBlank(params, "sessionId", "jobId", "id");
        return aiLineageService.deleteIndex(key);
    }

    @RequestMapping(
            value = {
                "/sqlflow/generation/ai/suggestions",
                "/sqlflow/generation/ai/suggest",
                "/sqlflow/ai/suggestions",
                "/sqlflow/ai/suggest"
            },
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<SuggestedQuestion>> suggestions(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        return aiLineageService.getSuggestions(paramHelper.extractParams(request, jsonBody));
    }

    @PostMapping(
            value = {"/sqlflow/generation/ai/suggestions/click", "/sqlflow/ai/suggestions/click"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> suggestionClick(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        return aiLineageService.recordSuggestionClick(paramHelper.extractParams(request, jsonBody));
    }

    @PostMapping(
            value = {"/sqlflow/generation/ai/query/sync", "/sqlflow/ai/query/sync"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<AiQueryResponse> querySync(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        return aiLineageService.querySync(paramHelper.extractParams(request, jsonBody));
    }

    @PostMapping(
            value = {"/sqlflow/generation/ai/node-query", "/sqlflow/ai/node-query"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<AiQueryResponse> nodeQuery(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        return aiLineageService.nodeQuery(paramHelper.extractParams(request, jsonBody));
    }

    @PostMapping(
            value = {"/sqlflow/generation/ai/query", "/sqlflow/ai/query"},
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamQuery(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        return aiLineageService.streamQuery(paramHelper.extractParams(request, jsonBody));
    }
}
