package io.sqlflow.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.contracts.domain.AiQueryResponse;
import io.sqlflow.contracts.domain.AiStatusEnvelope;
import io.sqlflow.contracts.domain.ApiResponse;
import io.sqlflow.contracts.domain.SuggestedQuestion;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.Relationship;
import io.sqlflow.contracts.lineage.RelationshipElement;
import io.sqlflow.server.config.SqlflowRuntimeConfig;
import io.sqlflow.server.persistence.SessionCacheService;
import io.sqlflow.server.spi.CatalogUtils;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI Lineage Assistant service ({@code /sqlflow/generation/ai/**}) implementing:
 * <ul>
 *   <li>Unwrapped {@code GET /ai/status} ({@link AiStatusEnvelope}, Wire Invariant #16)</li>
 *   <li>Graph index lifecycle ({@code POST /ai/index}, {@code GET /ai/index/status}, {@code DELETE /ai/index})</li>
 *   <li>Context-aware suggested questions & telemetry ({@code /ai/suggestions}, {@code /ai/suggestions/click})</li>
 *   <li>Synchronous & node-focused lineage Q&A ({@code /ai/query/sync}, {@code /ai/node-query})</li>
 *   <li>{@code text/event-stream} SSE streaming ({@code POST /ai/query}) emitting {@code metadata},
 *       {@code chunk}, {@code complete}, {@code no_match}, and {@code error} frames with configurable
 *       OpenAI-compatible HTTP streaming client ({@code OBL-079}).</li>
 * </ul>
 */
@Service
public class AiLineageService {

    private static final Logger log = LoggerFactory.getLogger(AiLineageService.class);

    private final SqlflowRuntimeConfig config;
    private final SessionCacheService sessionCacheService;
    private final JobCenterService jobCenterService;
    private final LineageGenerationService lineageGenerationService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sqlflow-ai-sse");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, IndexedSession> indexStore = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> suggestionClicks = new ConcurrentHashMap<>();

    public AiLineageService(
            SqlflowRuntimeConfig config,
            SessionCacheService sessionCacheService,
            JobCenterService jobCenterService,
            LineageGenerationService lineageGenerationService,
            ObjectMapper objectMapper) {
        this.config = config;
        this.sessionCacheService = sessionCacheService;
        this.jobCenterService = jobCenterService;
        this.lineageGenerationService = lineageGenerationService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public AiStatusEnvelope getStatus() {
        SqlflowRuntimeConfig.AiConfig ai = config.getAiConfig();
        return new AiStatusEnvelope(ai.isEnabled(), ai.getModel() != null ? ai.getModel() : "gpt-4o-mini");
    }

    public ApiResponse<Map<String, Object>> buildIndex(Map<String, String> params) {
        String key = resolveSessionOrJobKey(params);
        DataFlow df = resolveDataFlowForKey(key);
        String inlineSql = LineageGenerationService.firstNonBlank(params, "sqltext", "sql", "sqlText");
        if (df == null && inlineSql != null && !inlineSql.isBlank()) {
            df = lineageGenerationService.resolveOrAnalyzeDataFlow(params.get("userId"), params, null, null);
            if (df != null) {
                sessionCacheService.saveDataFlow(key, df);
            }
        }
        IndexedSession indexed = createIndexedSession(key, df);
        indexStore.put(key, indexed);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", key);
        out.put("indexed", true);
        out.put("status", "ready");
        out.put("nodeCount", indexed.nodes.size());
        out.put("edgeCount", indexed.edges.size());
        out.put("indexedAt", indexed.indexedAt);
        return ApiResponse.ok(out);
    }

    public ApiResponse<Map<String, Object>> getIndexStatus(String key) {
        String effectiveKey = (key == null || key.isBlank()) ? "default" : key.trim();
        IndexedSession indexed = indexStore.get(effectiveKey);
        if (indexed == null) {
            DataFlow df = resolveDataFlowForKey(effectiveKey);
            if (df != null) {
                indexed = createIndexedSession(effectiveKey, df);
                indexStore.put(effectiveKey, indexed);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", effectiveKey);
        out.put("indexed", indexed != null);
        out.put("status", indexed != null ? "ready" : "not_indexed");
        out.put("nodeCount", indexed != null ? indexed.nodes.size() : 0);
        out.put("edgeCount", indexed != null ? indexed.edges.size() : 0);
        return ApiResponse.ok(out);
    }

    public ApiResponse<Map<String, Object>> deleteIndex(String key) {
        boolean removed = key != null && indexStore.remove(key.trim()) != null;
        return ApiResponse.ok(Map.of("deleted", removed, "sessionId", key != null ? key : ""));
    }

    public ApiResponse<List<SuggestedQuestion>> getSuggestions(Map<String, String> params) {
        String key = resolveSessionOrJobKey(params);
        IndexedSession indexed = ensureIndexedOrNull(key);
        List<SuggestedQuestion> list = new ArrayList<>();
        if (indexed != null && !indexed.nodes.isEmpty()) {
            String firstNode = indexed.nodes.get(0);
            String lastNode = indexed.nodes.get(indexed.nodes.size() - 1);
            list.add(new SuggestedQuestion(
                    "What upstream tables feed " + lastNode + "?",
                    "upstream",
                    lastNode));
            list.add(new SuggestedQuestion(
                    "Which downstream views or tables depend on " + firstNode + "?",
                    "downstream",
                    firstNode));
            list.add(new SuggestedQuestion(
                    "Summarize the end-to-end column transformations in this lineage graph.",
                    "transform",
                    lastNode));
        } else {
            list.add(new SuggestedQuestion(
                    "What are the root source tables in this SQL script?",
                    "upstream",
                    null));
            list.add(new SuggestedQuestion(
                    "Which target tables are impacted by downstream transformations?",
                    "downstream",
                    null));
        }
        return ApiResponse.ok(list);
    }

    public ApiResponse<Map<String, Object>> recordSuggestionClick(Map<String, String> params) {
        String q = params.getOrDefault("question", "default");
        long count = suggestionClicks.computeIfAbsent(q, k -> new AtomicLong()).incrementAndGet();
        return ApiResponse.ok(Map.of("recorded", true, "clicks", count));
    }

    public ApiResponse<AiQueryResponse> querySync(Map<String, String> params) {
        String key = resolveSessionOrJobKey(params);
        String question = LineageGenerationService.firstNonBlank(params, "question", "query", "prompt");
        if (question == null || question.isBlank()) {
            return ApiResponse.error(400, "question is required");
        }
        IndexedSession indexed = ensureIndexedOrNull(key);
        AiQueryResponse res = answerFromContextOrLlm(indexed, question, null);
        return ApiResponse.ok(res);
    }

    public ApiResponse<AiQueryResponse> nodeQuery(Map<String, String> params) {
        String key = resolveSessionOrJobKey(params);
        String nodeName = LineageGenerationService.firstNonBlank(params, "nodeName", "table", "targetNode", "dbObjId");
        String queryType = params.getOrDefault("queryType", "summary");
        String prompt = switch (queryType.toLowerCase(Locale.ROOT)) {
            case "upstream" -> "Trace all upstream source tables and columns feeding " + nodeName + ".";
            case "downstream" -> "Identify all downstream tables and columns impacted by changes to " + nodeName + ".";
            case "transform" -> "Explain the SQL expressions and transformations applied to " + nodeName + ".";
            default -> "Summarize the role and lineage connections of " + nodeName + ".";
        };
        IndexedSession indexed = ensureIndexedOrNull(key);
        AiQueryResponse res = answerFromContextOrLlm(indexed, prompt, nodeName);
        return ApiResponse.ok(res);
    }

    /**
     * Wire Invariant #16: Streams SSE frames ({@code metadata}, {@code chunk}, {@code complete},
     * {@code no_match}, or {@code error}) over {@code text/event-stream}.
     */
    public SseEmitter streamQuery(Map<String, String> params) {
        SseEmitter emitter = new SseEmitter(60_000L);
        String key = resolveSessionOrJobKey(params);
        String question = LineageGenerationService.firstNonBlank(params, "question", "query", "prompt");

        sseExecutor.submit(() -> {
            try {
                if (question == null || question.isBlank()) {
                    sendSseFrame(emitter, "error", Map.of(
                            "type", "error",
                            "error", "question is required"));
                    emitter.complete();
                    return;
                }

                IndexedSession indexed = ensureIndexedOrNull(key);
                if (indexed == null || indexed.nodes.isEmpty()) {
                    sendSseFrame(emitter, "no_match", Map.of(
                            "type", "no_match",
                            "sessionId", key,
                            "message", "No indexed lineage graph found for session " + key));
                    emitter.complete();
                    return;
                }

                AiQueryResponse localAnalysis = buildDeterministicGraphAnswer(indexed, question, null);
                sendSseFrame(emitter, "metadata", Map.of(
                        "type", "metadata",
                        "sessionId", key,
                        "referencedNodes", localAnalysis.getReferencedNodes(),
                        "referencedEdges", localAnalysis.getReferencedEdges(),
                        "confidence", localAnalysis.getConfidence()));

                SqlflowRuntimeConfig.AiConfig ai = config.getAiConfig();
                if (ai.isEnabled() && ai.getApiUrl() != null && !ai.getApiUrl().isBlank()) {
                    String fullText = streamFromOpenAiEndpoint(ai, indexed, question, emitter);
                    localAnalysis.setAnswer(fullText);
                } else {
                    String answer = localAnalysis.getAnswer();
                    int step = Math.max(16, answer.length() / 3);
                    for (int i = 0; i < answer.length(); i += step) {
                        String delta = answer.substring(i, Math.min(answer.length(), i + step));
                        sendSseFrame(emitter, "chunk", Map.of("type", "chunk", "content", delta, "delta", delta));
                    }
                }

                sendSseFrame(emitter, "complete", Map.of(
                        "type", "complete",
                        "answer", localAnalysis.getAnswer(),
                        "referencedNodes", localAnalysis.getReferencedNodes(),
                        "referencedEdges", localAnalysis.getReferencedEdges(),
                        "confidence", localAnalysis.getConfidence(),
                        "basedOn", localAnalysis.getBasedOn()));
                emitter.complete();
            } catch (Exception e) {
                log.debug("AI SSE stream error: {}", e.getMessage());
                try {
                    sendSseFrame(emitter, "error", Map.of(
                            "type", "error",
                            "error", e.getMessage() != null ? e.getMessage() : "AI query failed"));
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
        });
        return emitter;
    }

    // -------------------------------------------------------------------------
    // Internal Graph Context & OpenAI Streaming Client
    // -------------------------------------------------------------------------

    private String streamFromOpenAiEndpoint(
            SqlflowRuntimeConfig.AiConfig ai,
            IndexedSession indexed,
            String question,
            SseEmitter emitter) throws Exception {
        String endpoint = ai.getApiUrl().endsWith("/chat/completions")
                ? ai.getApiUrl()
                : (ai.getApiUrl().replaceAll("/+$", "") + "/chat/completions");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", ai.getModel() != null ? ai.getModel() : "gpt-4o-mini");
        payload.put("stream", true);
        payload.put("temperature", ai.getTemperature());
        payload.put("max_tokens", ai.getMaxTokens());
        payload.put("messages", List.of(
                Map.of("role", "system", "content", "You are SQLFlow Lineage Assistant. Context:\n" + indexed.contextSummary),
                Map.of("role", "user", "content", question)));

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(Math.max(5, ai.getTimeoutSeconds())))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
        if (ai.getApiKey() != null && !ai.getApiKey().isBlank()) {
            reqBuilder.header("Authorization", "Bearer " + ai.getApiKey());
        }

        HttpResponse< java.io.InputStream > resp =
                httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("Upstream AI provider returned HTTP " + resp.statusCode());
        }

        StringBuilder full = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("data:")) {
                    continue;
                }
                String dataPart = trimmed.substring(5).trim();
                if ("[DONE]".equals(dataPart) || dataPart.isEmpty()) {
                    break;
                }
                JsonNode node = objectMapper.readTree(dataPart);
                String delta = "";
                JsonNode choices = node.path("choices");
                if (choices.isArray() && !choices.isEmpty()) {
                    delta = choices.get(0).path("delta").path("content").asText("");
                    if (delta.isEmpty()) {
                        delta = choices.get(0).path("message").path("content").asText("");
                    }
                } else if (node.has("content")) {
                    delta = node.path("content").asText("");
                }
                if (!delta.isEmpty()) {
                    full.append(delta);
                    sendSseFrame(emitter, "chunk", Map.of("type", "chunk", "content", delta, "delta", delta));
                }
            }
        }
        return full.toString();
    }

    private AiQueryResponse answerFromContextOrLlm(IndexedSession indexed, String question, String focusNode) {
        if (indexed == null || indexed.nodes.isEmpty()) {
            return new AiQueryResponse(
                    "No lineage graph is currently indexed for this session.",
                    List.of(),
                    List.of(),
                    "low",
                    List.of("empty_session"));
        }
        AiQueryResponse base = buildDeterministicGraphAnswer(indexed, question, focusNode);
        SqlflowRuntimeConfig.AiConfig ai = config.getAiConfig();
        if (ai.isEnabled() && ai.getApiUrl() != null && !ai.getApiUrl().isBlank()) {
            try {
                String endpoint = ai.getApiUrl().endsWith("/chat/completions")
                        ? ai.getApiUrl()
                        : (ai.getApiUrl().replaceAll("/+$", "") + "/chat/completions");
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("model", ai.getModel() != null ? ai.getModel() : "gpt-4o-mini");
                payload.put("stream", false);
                payload.put("messages", List.of(
                        Map.of("role", "system", "content", "Lineage graph:\n" + indexed.contextSummary),
                        Map.of("role", "user", "content", question)));
                HttpRequest.Builder rb = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(Math.max(5, ai.getTimeoutSeconds())))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
                if (ai.getApiKey() != null && !ai.getApiKey().isBlank()) {
                    rb.header("Authorization", "Bearer " + ai.getApiKey());
                }
                HttpResponse<String> resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    JsonNode root = objectMapper.readTree(resp.body());
                    String content = root.path("choices").path(0).path("message").path("content").asText("");
                    if (!content.isBlank()) {
                        base.setAnswer(content);
                    }
                }
            } catch (Exception e) {
                log.debug("Fallback to deterministic graph answer: {}", e.getMessage());
            }
        }
        return base;
    }

    private AiQueryResponse buildDeterministicGraphAnswer(
            IndexedSession indexed, String question, String focusNode) {
        String qLower = question.toLowerCase(Locale.ROOT);
        List<String> matchedNodes = new ArrayList<>();
        for (String n : indexed.nodes) {
            if ((focusNode != null && n.equalsIgnoreCase(focusNode))
                    || qLower.contains(n.toLowerCase(Locale.ROOT))) {
                matchedNodes.add(n);
            }
        }
        if (matchedNodes.isEmpty()) {
            matchedNodes.addAll(indexed.nodes.subList(0, Math.min(5, indexed.nodes.size())));
        }
        List<AiQueryResponse.ReferencedEdge> matchedEdges = indexed.edges.subList(0, Math.min(8, indexed.edges.size()));
        StringBuilder sb = new StringBuilder();
        sb.append("Based on the analyzed lineage graph (")
                .append(indexed.nodes.size()).append(" objects, ")
                .append(indexed.edges.size()).append(" lineage edges): ");
        if (!matchedEdges.isEmpty()) {
            List<String> flows = matchedEdges.stream()
                    .map(e -> e.getFrom() + " -> " + e.getTo())
                    .toList();
            sb.append("data flows across ").append(String.join(", ", flows)).append(".");
        } else {
            sb.append("referenced objects include ").append(String.join(", ", matchedNodes)).append(".");
        }
        return new AiQueryResponse(
                sb.toString(),
                matchedNodes,
                matchedEdges,
                "high",
                List.of("sqlflow_lineage_graph"));
    }

    private void sendSseFrame(SseEmitter emitter, String eventName, Map<String, Object> payload) throws Exception {
        String json = objectMapper.writeValueAsString(payload);
        emitter.send(SseEmitter.event()
                .name(eventName)
                .data(json, MediaType.APPLICATION_JSON));
    }

    private String resolveSessionOrJobKey(Map<String, String> params) {
        String k = LineageGenerationService.firstNonBlank(params, "sessionId", "jobId", "id");
        return (k == null || k.isBlank()) ? "default" : k.trim();
    }

    private DataFlow resolveDataFlowForKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        DataFlow df = sessionCacheService.loadDataFlow(key);
        if (df == null) {
            df = jobCenterService.loadJobDataFlowOrNull(key);
        }
        return df;
    }

    private IndexedSession ensureIndexedOrNull(String key) {
        IndexedSession existing = indexStore.get(key);
        if (existing != null) {
            return existing;
        }
        DataFlow df = resolveDataFlowForKey(key);
        if (df == null) {
            return null;
        }
        IndexedSession created = createIndexedSession(key, df);
        indexStore.put(key, created);
        return created;
    }

    private IndexedSession createIndexedSession(String key, DataFlow df) {
        Set<String> nodeNames = new LinkedHashSet<>();
        Map<String, String> idToName = new LinkedHashMap<>();
        List<AiQueryResponse.ReferencedEdge> edges = new ArrayList<>();
        if (df != null) {
            for (DbObject obj : CatalogUtils.collectAllDbObjects(df)) {
                String name = obj.getName() != null ? obj.getName() : obj.getId();
                if (name != null && !name.isBlank()) {
                    nodeNames.add(name);
                    if (obj.getId() != null) {
                        idToName.put(obj.getId(), name);
                    }
                }
            }
            if (df.getRelationships() != null) {
                Set<String> seenPairs = new LinkedHashSet<>();
                for (Relationship rel : df.getRelationships()) {
                    if (rel == null || rel.getTarget() == null || rel.getSources() == null) {
                        continue;
                    }
                    String tgt = resolveElementTable(rel.getTarget(), idToName);
                    for (RelationshipElement srcEl : rel.getSources()) {
                        String src = resolveElementTable(srcEl, idToName);
                        if (src != null && tgt != null && !src.equals(tgt) && seenPairs.add(src + "->" + tgt)) {
                            edges.add(new AiQueryResponse.ReferencedEdge(src, tgt));
                        }
                    }
                }
            }
        }
        StringBuilder summary = new StringBuilder();
        summary.append("Objects: ").append(String.join(", ", nodeNames)).append("\nEdges: ");
        for (AiQueryResponse.ReferencedEdge e : edges) {
            summary.append(e.getFrom()).append(" -> ").append(e.getTo()).append("; ");
        }
        return new IndexedSession(key, new ArrayList<>(nodeNames), edges, summary.toString(), Instant.now().toString());
    }

    private static String resolveElementTable(RelationshipElement el, Map<String, String> idToName) {
        if (el == null) {
            return null;
        }
        if (el.getParentName() != null && !el.getParentName().isBlank()) {
            return el.getParentName();
        }
        if (el.getParentId() != null && idToName.containsKey(el.getParentId())) {
            return idToName.get(el.getParentId());
        }
        return el.getColumn();
    }

    private record IndexedSession(
            String sessionId,
            List<String> nodes,
            List<AiQueryResponse.ReferencedEdge> edges,
            String contextSummary,
            String indexedAt) {
    }
}
