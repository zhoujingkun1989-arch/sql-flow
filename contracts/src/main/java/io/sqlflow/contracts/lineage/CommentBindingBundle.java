package io.sqlflow.contracts.lineage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Structured SQL comment-to-AST binding bundle emitted when {@code Option.collectCommentBindings == true}
 * ({@code 03 §1.5.4}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CommentBindingBundle(
        Integer version,
        String bindingRulesVersion,
        String state,
        List<String> supportedHostKinds,
        List<SourceUnit> sourceUnits,
        List<CommentItem> comments,
        List<Host> hosts,
        List<Binding> bindings,
        List<Coverage> coverage,
        List<Diagnostic> diagnostics,
        Limits limits
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SourceUnit(
            Integer inputOrdinal,
            String vendor,
            String contentDigest,
            String originKind,
            String fileName
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Range(
            Integer endOffset,
            Integer startLine,
            Integer startColumn,
            Integer endLine,
            Integer endColumn
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CommentItem(
            String sourceUnitId,
            Range range,
            String lexicalKind,
            String raw,
            String rawEncoding,
            Integer originalLength,
            Boolean rawTruncated
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Target(String id, String parentId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Host(
            String sourceUnitId,
            String kind,
            String syntaxKind,
            Range range,
            Range nameRange,
            String queryBlockId,
            String parentHostId,
            Integer ordinal,
            String bindingState,
            List<Target> targets,
            String processId,
            String procedureId
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Binding(String hostId, String placement, String ruleId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Coverage(String hostKind, String state) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Diagnostic(String sourceUnitId, String commentId, String hostId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Limits(Integer maxCommentsPerSourceUnit, Integer maxTotalRawLength) {
    }
}
