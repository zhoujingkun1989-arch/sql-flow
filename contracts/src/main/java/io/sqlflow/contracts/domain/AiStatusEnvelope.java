package io.sqlflow.contracts.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Unwrapped response payload for {@code GET /sqlflow/ai/status} ({@code 02 §5.6}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"enabled", "model"})
public class AiStatusEnvelope {

    private boolean enabled;
    private String model;

    public AiStatusEnvelope() {
    }

    public AiStatusEnvelope(boolean enabled, String model) {
        this.enabled = enabled;
        this.model = model;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
