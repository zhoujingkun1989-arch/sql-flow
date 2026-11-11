package io.sqlflow.contracts.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * AI suggested lineage question DTO returned in {@code ApiResponse<List<SuggestedQuestion>>}
 * from {@code GET /sqlflow/generation/ai/suggestions} ({@code 02 §4.5 & §5.4}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"question", "category", "targetNode"})
public class SuggestedQuestion {

    private String question;
    private String category;
    private String targetNode;

    public SuggestedQuestion() {
    }

    public SuggestedQuestion(String question, String category, String targetNode) {
        this.question = question;
        this.category = category;
        this.targetNode = targetNode;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getTargetNode() {
        return targetNode;
    }

    public void setTargetNode(String targetNode) {
        this.targetNode = targetNode;
    }
}
