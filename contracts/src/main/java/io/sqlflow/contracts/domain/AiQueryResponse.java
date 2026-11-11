package io.sqlflow.contracts.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * AI natural-language lineage query response payload inside {@code ApiResponse<AiQueryResponse>}
 * for {@code /sqlflow/generation/ai/query/sync} and {@code /sqlflow/generation/ai/node-query}
 * ({@code 02 §4.5 & §5.4}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({
    "answer",
    "referencedNodes",
    "referencedEdges",
    "confidence",
    "basedOn",
    "error"
})
public class AiQueryResponse {

    private String answer;
    private List<String> referencedNodes;
    private List<ReferencedEdge> referencedEdges;
    private String confidence;
    private List<String> basedOn;
    private String error;

    public AiQueryResponse() {
    }

    public AiQueryResponse(
            String answer,
            List<String> referencedNodes,
            List<ReferencedEdge> referencedEdges,
            String confidence,
            List<String> basedOn) {
        this.answer = answer;
        this.referencedNodes = referencedNodes;
        this.referencedEdges = referencedEdges;
        this.confidence = confidence;
        this.basedOn = basedOn;
    }

    public static AiQueryResponse error(String error) {
        AiQueryResponse res = new AiQueryResponse();
        res.setError(error);
        return res;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public List<String> getReferencedNodes() {
        return referencedNodes;
    }

    public void setReferencedNodes(List<String> referencedNodes) {
        this.referencedNodes = referencedNodes;
    }

    public List<ReferencedEdge> getReferencedEdges() {
        return referencedEdges;
    }

    public void setReferencedEdges(List<ReferencedEdge> referencedEdges) {
        this.referencedEdges = referencedEdges;
    }

    public String getConfidence() {
        return confidence;
    }

    public void setConfidence(String confidence) {
        this.confidence = confidence;
    }

    public List<String> getBasedOn() {
        return basedOn;
    }

    public void setBasedOn(List<String> basedOn) {
        this.basedOn = basedOn;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"from", "to"})
    public static class ReferencedEdge {
        private String from;
        private String to;

        public ReferencedEdge() {
        }

        public ReferencedEdge(String from, String to) {
            this.from = from;
            this.to = to;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public String getTo() {
            return to;
        }

        public void setTo(String to) {
            this.to = to;
        }
    }
}
