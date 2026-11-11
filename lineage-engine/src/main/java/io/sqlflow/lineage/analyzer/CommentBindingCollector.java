package io.sqlflow.lineage.analyzer;

import io.sqlflow.contracts.lineage.CommentBindingBundle;
import io.sqlflow.contracts.lineage.CommentBindingBundle.Binding;
import io.sqlflow.contracts.lineage.CommentBindingBundle.CommentItem;
import io.sqlflow.contracts.lineage.CommentBindingBundle.Coverage;
import io.sqlflow.contracts.lineage.CommentBindingBundle.Diagnostic;
import io.sqlflow.contracts.lineage.CommentBindingBundle.Host;
import io.sqlflow.contracts.lineage.CommentBindingBundle.Limits;
import io.sqlflow.contracts.lineage.CommentBindingBundle.Range;
import io.sqlflow.contracts.lineage.CommentBindingBundle.SourceUnit;
import io.sqlflow.contracts.lineage.CommentBindingBundle.Target;
import io.sqlflow.contracts.lineage.Coordinate;
import io.sqlflow.contracts.lineage.Option;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundColumn;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundObject;
import io.sqlflow.lineage.catalog.PolymorphicInputLoader;
import io.sqlflow.lineage.catalog.PolymorphicInputLoader.ExecutableSqlUnit;
import io.sqlflow.lineage.lexer.SourceSpan;
import io.sqlflow.lineage.lexer.SqlLexer;
import io.sqlflow.lineage.lexer.SqlToken;
import io.sqlflow.lineage.lexer.TokenType;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects SQL comments and binds them to adjacent AST/lineage hosts when
 * {@link Option#isCollectCommentBindings()} is enabled ({@code 03 §1.5.4}).
 */
public final class CommentBindingCollector {

    private static final List<String> SUPPORTED_HOST_KINDS = List.of(
            "TABLE",
            "VIEW",
            "COLUMN",
            "SELECT_ITEM",
            "STATEMENT");

    private CommentBindingCollector() {
    }

    public static CommentBindingBundle collect(
            List<ExecutableSqlUnit> units,
            ModelBindingManager mgr,
            Option option) {
        if (option == null || !option.isCollectCommentBindings()) {
            return null;
        }
        int maxRawLen = Math.max(64, option.getCommentBindingMaxRawLength());
        int maxPerUnit = Math.max(1, option.getCommentBindingMaxCommentsPerSourceUnit());
        int maxTotalLen = Math.max(1024, option.getCommentBindingMaxTotalRawLength());

        List<SourceUnit> sourceUnits = new ArrayList<>();
        List<CommentItem> comments = new ArrayList<>();
        List<Host> hosts = new ArrayList<>();
        List<Binding> bindings = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();

        int totalChars = 0;
        boolean truncatedState = false;

        int unitIdx = 0;
        for (ExecutableSqlUnit unit : units) {
            if (unit == null || unit.sql() == null) {
                unitIdx++;
                continue;
            }
            String suId = "su-" + unitIdx;
            sourceUnits.add(new SourceUnit(
                    unit.originIndex(),
                    unit.unitVendor().wireName(),
                    PolymorphicInputLoader.computeMd5Hex(unit.sql()),
                    unit.fileName() != null ? "FILE" : "INLINE_SQL",
                    unit.fileName()));

            List<SqlToken> commentTokens = SqlLexer.tokenize(
                            unit.sql(), unit.unitVendor(), option.isEnableMssqlColonBindVariables())
                    .comments();
            int unitComments = 0;
            for (SqlToken tok : commentTokens) {
                if (tok.type() != TokenType.LINE_COMMENT && tok.type() != TokenType.BLOCK_COMMENT) {
                    continue;
                }
                if (unitComments >= maxPerUnit || totalChars >= maxTotalLen) {
                    truncatedState = true;
                    break;
                }
                String raw = tok.rawText() != null ? tok.rawText() : tok.value();
                int origLen = raw.length();
                boolean itemTruncated = false;
                if (raw.length() > maxRawLen) {
                    raw = raw.substring(0, maxRawLen);
                    itemTruncated = true;
                }
                if (totalChars + raw.length() > maxTotalLen) {
                    raw = raw.substring(0, Math.max(0, maxTotalLen - totalChars));
                    itemTruncated = true;
                    truncatedState = true;
                }
                totalChars += raw.length();
                unitComments++;

                String lexicalKind = raw.startsWith("/*") ? "BLOCK_COMMENT" : "LINE_COMMENT";
                SourceSpan sp = tok.span();
                Range range = new Range(
                        sp.endOffset(),
                        (int) sp.startLine(),
                        (int) sp.startCol(),
                        (int) sp.endLine(),
                        (int) sp.endCol());
                comments.add(new CommentItem(
                        suId,
                        range,
                        lexicalKind,
                        raw,
                        "UTF-8",
                        origLen,
                        itemTruncated ? Boolean.TRUE : Boolean.FALSE));
            }
            unitIdx++;
        }

        // Build hosts from bound objects and columns that have 1-based coordinates
        int hostOrd = 0;
        for (BoundObject obj : mgr.allObjects()) {
            Range r = firstRangeFromCoords(obj.getCoordinates());
            if (r == null) {
                continue;
            }
            String hostId = "host-" + (hostOrd++);
            String kind = switch (obj.getCategory()) {
                case VIEW -> "VIEW";
                case TABLE -> "TABLE";
                default -> "STATEMENT";
            };
            hosts.add(new Host(
                    sourceUnits.isEmpty() ? "su-0" : "su-0",
                    kind,
                    obj.getType(),
                    r,
                    r,
                    null,
                    null,
                    hostOrd,
                    "BOUND",
                    List.of(new Target(obj.getId(), null)),
                    obj.formattedProcessIds(),
                    obj.getProcedureId()));

            for (BoundColumn col : obj.orderedColumns()) {
                Range cr = firstRangeFromCoords(col.getCoordinates());
                if (cr == null) {
                    continue;
                }
                String colHostId = "host-" + (hostOrd++);
                hosts.add(new Host(
                        "su-0",
                        "COLUMN",
                        "COLUMN",
                        cr,
                        cr,
                        null,
                        hostId,
                        hostOrd,
                        "BOUND",
                        List.of(new Target(col.getId(), obj.getId())),
                        obj.formattedProcessIds(),
                        obj.getProcedureId()));
            }
        }

        // Bind comments to nearest subsequent/same-line host
        for (CommentItem c : comments) {
            Host best = null;
            int bestDist = Integer.MAX_VALUE;
            String placement = "LEADING";
            for (Host h : hosts) {
                if (h.range() == null || c.range() == null) {
                    continue;
                }
                if (c.range().startLine().equals(h.range().endLine())
                        && c.range().startColumn() >= h.range().endColumn()) {
                    best = h;
                    placement = "TRAILING_SAME_LINE";
                    break;
                }
                int lineDiff = h.range().startLine() - c.range().endLine();
                if (lineDiff >= 0 && lineDiff <= 2 && lineDiff < bestDist) {
                    bestDist = lineDiff;
                    best = h;
                    placement = "LEADING";
                }
            }
            if (best != null) {
                bindings.add(new Binding(
                        "host-" + best.ordinal(),
                        placement,
                        placement.equals("TRAILING_SAME_LINE") ? "RULE_INLINE_TRAILING" : "RULE_ADJACENT_LEADING"));
            }
        }

        List<Coverage> coverage = SUPPORTED_HOST_KINDS.stream()
                .map(k -> new Coverage(k, "SUPPORTED"))
                .toList();

        return new CommentBindingBundle(
                1,
                "1.0",
                truncatedState ? "PARTIAL_LIMIT_REACHED" : "COMPLETE",
                SUPPORTED_HOST_KINDS,
                sourceUnits,
                comments,
                hosts,
                bindings,
                coverage,
                diagnostics,
                new Limits(maxPerUnit, maxTotalLen));
    }

    private static Range firstRangeFromCoords(List<Coordinate> coords) {
        if (coords == null || coords.size() < 2) {
            return null;
        }
        Coordinate s = coords.get(0);
        Coordinate e = coords.get(1);
        if (s == null || e == null || s.getX() < 1) {
            return null;
        }
        return new Range(null, (int) s.getX(), (int) s.getY(), (int) e.getX(), (int) e.getY());
    }
}
