package io.sqlflow.lineage.analyzer;

import io.sqlflow.contracts.lineage.Option;
import io.sqlflow.contracts.lineage.RelationshipType;
import io.sqlflow.contracts.lineage.ResultSetType;
import io.sqlflow.contracts.lineage.Transform;
import io.sqlflow.contracts.spi.LineageEngineSpi.DegradationMode;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundColumn;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundEdge;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundObject;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundSourceRef;
import io.sqlflow.lineage.analyzer.ModelBindingManager.ObjectCategory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Post-processes the bound lineage graph according to the 74-field {@link Option} flags and
 * automatic complexity degradation rules ({@code OBL-042..OBL-047}).
 */
public final class DataFlowPostProcessor {

    public static final int AUTO_COLLAPSE_EDGE_THRESHOLD = 2500;
    public static final int SUMMARY_EDGE_THRESHOLD = 5000;
    public static final int SUMMARY_OBJECT_THRESHOLD = 200;

    private DataFlowPostProcessor() {
    }

    public static DegradationMode process(ModelBindingManager mgr, Option option) {
        Option opt = option != null ? option : new Option();

        // 1. Two-way SELECT * back-propagation
        mgr.resolveStarBackPropagation();

        // 2. Drop top-level SELECT resultsets when ignoreTopSelect == true
        if (opt.isIgnoreTopSelect()) {
            removeObjectsAndIncidentEdges(mgr, BoundObject::isTopLevelSelect);
        }

        // 3. Remove function nodes when sqlflowIgnoreFunction == true
        if (opt.isSqlflowIgnoreFunction()) {
            collapseIntermediateNodes(mgr, obj -> obj.getResultSetType() == ResultSetType.function);
        }

        // 4. Determine whether automatic complexity degradation triggers ignoreRecordSet
        boolean autoSimple = !opt.isShowERDiagram()
                && !opt.isIgnoreRecordSet()
                && !opt.isSimpleOutput()
                && mgr.edges().size() > AUTO_COLLAPSE_EDGE_THRESHOLD;

        if (opt.isIgnoreRecordSet() || opt.isSimpleOutput() || autoSimple) {
            boolean keepTopSelect = !opt.isIgnoreTopSelect()
                    && (opt.isSimpleShowTopSelectResultSet() || !hasAnyPhysicalTarget(mgr));
            collapseIntermediateNodes(mgr, obj -> shouldCollapseInSimpleMode(obj, opt, keepTopSelect));
        }

        // 5. Collapse temporary tables (#tmp, ##gtmp, CREATE TEMP TABLE) when ignoreTemporaryTable == true
        if (opt.isIgnoreTemporaryTable()) {
            collapseIntermediateNodes(mgr, BoundObject::isTemporary);
        }

        // 6. Filter relationship types (e.g. onlyDirect -> {"fdd"}, erDiagram -> {"er"})
        if (opt.getFilterRelationTypes() != null && !opt.getFilterRelationTypes().isEmpty()) {
            Set<String> allowed = new LinkedHashSet<>();
            for (String rt : opt.getFilterRelationTypes()) {
                if (rt != null && !rt.isBlank()) {
                    allowed.add(rt.trim().toLowerCase(Locale.ROOT));
                }
            }
            if (!allowed.isEmpty()) {
                List<BoundEdge> kept = new ArrayList<>();
                for (BoundEdge e : mgr.edges()) {
                    if (e.getType() != null && allowed.contains(e.getType().wireName().toLowerCase(Locale.ROOT))) {
                        kept.add(e);
                    }
                }
                mgr.replaceEdges(kept);
            }
        }

        // 7. Prune unused synonyms & isolated collapsed resultsets
        pruneUnreferencedIntermediateObjects(mgr, opt);

        // 8. Deterministic sorting when normalizeOutput == true
        if (opt.isNormalizeOutput()) {
            normalizeOrder(mgr);
        }

        // 9. Determine final DegradationMode
        if (!opt.isShowERDiagram()
                && (mgr.edges().size() > SUMMARY_EDGE_THRESHOLD || countPhysicalTablesAndViews(mgr) > SUMMARY_OBJECT_THRESHOLD)) {
            return DegradationMode.SUMMARY;
        }
        if (opt.isTraceSQL()) {
            return DegradationMode.TABLE_ONLY;
        }
        return DegradationMode.FULL;
    }

    private static boolean shouldCollapseInSimpleMode(BoundObject obj, Option opt, boolean keepTopSelect) {
        ObjectCategory cat = obj.getCategory();
        if (cat != ObjectCategory.RESULTSET && cat != ObjectCategory.VARIABLE && cat != ObjectCategory.SYNONYM) {
            return false;
        }
        if (cat == ObjectCategory.SYNONYM) {
            return !opt.isSimpleShowSynonym();
        }
        if (cat == ObjectCategory.VARIABLE) {
            return (opt.isSimpleOutput() || opt.isIgnoreRecordSet()) && !opt.isSimpleShowVariable();
        }
        ResultSetType rsType = obj.getResultSetType();
        if (rsType != null && opt.getTargetResultSetTypes() != null && opt.getTargetResultSetTypes().contains(rsType)) {
            return false;
        }
        if (obj.isTopLevelSelect() && keepTopSelect) {
            return false;
        }
        if (rsType == ResultSetType.cursor) {
            return !opt.isSimpleShowCursor();
        }
        if (rsType == ResultSetType.function) {
            if (opt.isSimpleShowFunction()) {
                return false;
            }
            if (opt.isSimpleShowUdfFunctionOnly() && !obj.isBuiltInFunction()) {
                return false;
            }
            return true;
        }
        return true;
    }

    private static void collapseIntermediateNodes(
            ModelBindingManager mgr, java.util.function.Predicate<BoundObject> collapsePred) {
        Set<String> collapsibleObjIds = new LinkedHashSet<>();
        for (BoundObject obj : mgr.allObjects()) {
            if (collapsePred.test(obj)) {
                collapsibleObjIds.add(obj.getId());
            }
        }
        if (collapsibleObjIds.isEmpty()) {
            return;
        }

        // Iteratively splice each collapsible object out of column-level edges
        boolean progress = true;
        int guard = 0;
        while (progress && guard++ < 16) {
            progress = false;
            List<BoundEdge> currentEdges = new ArrayList<>(mgr.edges());
            Map<String, List<BoundEdge>> incomingByTargetCol = new LinkedHashMap<>();
            for (BoundEdge e : currentEdges) {
                if (e.getTargetObj() != null
                        && e.getTargetCol() != null
                        && collapsibleObjIds.contains(e.getTargetObj().getId())
                        && e.getType() != null
                        && e.getType().ordinal() <= RelationshipType.fdr.ordinal()) {
                    incomingByTargetCol
                            .computeIfAbsent(e.getTargetCol().getId(), k -> new ArrayList<>())
                            .add(e);
                }
            }

            List<BoundEdge> nextEdges = new ArrayList<>();
            Set<String> dedup = new LinkedHashSet<>();

            for (BoundEdge outEdge : currentEdges) {
                if (outEdge.getType() == RelationshipType.call
                        || outEdge.getTargetObj() == null
                        || outEdge.getTargetCol() == null) {
                    addIfUnique(nextEdges, dedup, outEdge);
                    continue;
                }

                boolean hasCollapsibleSource = false;
                for (BoundSourceRef src : outEdge.getSources()) {
                    if (src.obj() != null && collapsibleObjIds.contains(src.obj().getId())) {
                        hasCollapsibleSource = true;
                        break;
                    }
                }
                if (!hasCollapsibleSource) {
                    addIfUnique(nextEdges, dedup, outEdge);
                    continue;
                }

                progress = true;
                List<BoundSourceRef> keptDirectSources = new ArrayList<>();
                Map<RelationshipType, List<BoundSourceRef>> expandedByType = new LinkedHashMap<>();

                for (BoundSourceRef src : outEdge.getSources()) {
                    if (src.obj() == null || !collapsibleObjIds.contains(src.obj().getId())) {
                        keptDirectSources.add(src);
                        continue;
                    }
                    List<BoundEdge> inEdges = incomingByTargetCol.get(src.col().getId());
                    if (inEdges == null && !"RelationRows".equalsIgnoreCase(src.col().getName())) {
                        continue;
                    }
                    if (inEdges == null && src.obj().peekRelationRows() != null) {
                        inEdges = incomingByTargetCol.get(src.obj().peekRelationRows().getId());
                    }
                    if (inEdges == null) {
                        continue;
                    }
                    for (BoundEdge inEdge : inEdges) {
                        RelationshipType combinedType = RelationshipType.collapseTransitive(
                                inEdge.getType(), outEdge.getType());
                        List<BoundSourceRef> bucket = expandedByType.computeIfAbsent(
                                combinedType, k -> new ArrayList<>());
                        for (BoundSourceRef upSrc : inEdge.getSources()) {
                            if (!upSrc.col().getId().equals(outEdge.getTargetCol().getId())) {
                                bucket.add(mergeTransforms(upSrc, src));
                            }
                        }
                    }
                }

                if (!keptDirectSources.isEmpty()) {
                    expandedByType.computeIfAbsent(outEdge.getType(), k -> new ArrayList<>())
                            .addAll(keptDirectSources);
                }

                for (Map.Entry<RelationshipType, List<BoundSourceRef>> entry : expandedByType.entrySet()) {
                    List<BoundSourceRef> dedupSrcs = deduplicateSources(entry.getValue(), outEdge.getTargetCol().getId());
                    if (dedupSrcs.isEmpty()) {
                        continue;
                    }
                    BoundEdge spliced = new BoundEdge(
                            mgr.nextRelationId(),
                            entry.getKey(),
                            outEdge.getEffectType(),
                            outEdge.getTargetObj(),
                            outEdge.getTargetCol(),
                            dedupSrcs);
                    spliced.setProcessId(outEdge.getProcessId());
                    spliced.setProcessType(outEdge.getProcessType());
                    spliced.setProcedureId(outEdge.getProcedureId());
                    spliced.setSqlHash(outEdge.getSqlHash());
                    spliced.setFunction(outEdge.getFunction());
                    spliced.setClause(outEdge.getClause());
                    addIfUnique(nextEdges, dedup, spliced);
                }
            }
            mgr.replaceEdges(nextEdges);
        }

        // Remove edges whose target is a collapsed intermediate node, and remove collapsed objects
        List<BoundEdge> survivingEdges = new ArrayList<>();
        for (BoundEdge e : mgr.edges()) {
            if (e.getTargetObj() != null && collapsibleObjIds.contains(e.getTargetObj().getId())) {
                continue;
            }
            survivingEdges.add(e);
        }
        mgr.replaceEdges(survivingEdges);

        List<BoundObject> survivingObjs = new ArrayList<>();
        for (BoundObject o : mgr.allObjects()) {
            if (!collapsibleObjIds.contains(o.getId())) {
                survivingObjs.add(o);
            }
        }
        mgr.replaceObjects(survivingObjs);
    }

    private static void removeObjectsAndIncidentEdges(
            ModelBindingManager mgr, java.util.function.Predicate<BoundObject> pred) {
        Set<String> dropIds = new LinkedHashSet<>();
        for (BoundObject o : mgr.allObjects()) {
            if (pred.test(o)) {
                dropIds.add(o.getId());
            }
        }
        if (dropIds.isEmpty()) {
            return;
        }
        List<BoundEdge> keptEdges = new ArrayList<>();
        for (BoundEdge e : mgr.edges()) {
            if (e.getTargetObj() != null && dropIds.contains(e.getTargetObj().getId())) {
                continue;
            }
            List<BoundSourceRef> srcs = new ArrayList<>();
            for (BoundSourceRef s : e.getSources()) {
                if (s.obj() == null || !dropIds.contains(s.obj().getId())) {
                    srcs.add(s);
                }
            }
            if (e.getType() == RelationshipType.call || !srcs.isEmpty()) {
                e.getSources().clear();
                e.getSources().addAll(srcs);
                keptEdges.add(e);
            }
        }
        mgr.replaceEdges(keptEdges);
        List<BoundObject> keptObjs = new ArrayList<>();
        for (BoundObject o : mgr.allObjects()) {
            if (!dropIds.contains(o.getId())) {
                keptObjs.add(o);
            }
        }
        mgr.replaceObjects(keptObjs);
    }

    private static void pruneUnreferencedIntermediateObjects(ModelBindingManager mgr, Option opt) {
        Set<String> referencedObjIds = new LinkedHashSet<>();
        for (BoundEdge e : mgr.edges()) {
            if (e.getTargetObj() != null) {
                referencedObjIds.add(e.getTargetObj().getId());
            }
            for (BoundSourceRef s : e.getSources()) {
                if (s.obj() != null) {
                    referencedObjIds.add(s.obj().getId());
                }
            }
            if (e.getCallerObj() != null) {
                referencedObjIds.add(e.getCallerObj().getId());
            }
        }
        List<BoundObject> kept = new ArrayList<>();
        for (BoundObject o : mgr.allObjects()) {
            if (o.getCategory() == ObjectCategory.SYNONYM && opt.isIgnoreUnusedSynonym()
                    && !referencedObjIds.contains(o.getId())) {
                continue;
            }
            if ((o.getCategory() == ObjectCategory.RESULTSET || ModelBindingManager.SQL_CONSTANTS_TABLE.equals(o.getName()))
                    && !referencedObjIds.contains(o.getId())
                    && !o.isTopLevelSelect()) {
                continue;
            }
            kept.add(o);
        }
        mgr.replaceObjects(kept);
    }

    private static void normalizeOrder(ModelBindingManager mgr) {
        List<BoundObject> sortedObjs = new ArrayList<>(mgr.allObjects());
        sortedObjs.sort(Comparator.comparing(BoundObject::getDisplayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(BoundObject::getId));
        mgr.replaceObjects(sortedObjs);

        List<BoundEdge> sortedEdges = new ArrayList<>(mgr.edges());
        sortedEdges.sort(Comparator.comparing((BoundEdge e) -> e.getType().name())
                .thenComparing(e -> e.getTargetObj() != null ? e.getTargetObj().getDisplayName() : "")
                .thenComparing(e -> e.getTargetCol() != null ? e.getTargetCol().getName() : ""));
        mgr.replaceEdges(sortedEdges);
    }

    private static boolean hasAnyPhysicalTarget(ModelBindingManager mgr) {
        for (BoundObject o : mgr.allObjects()) {
            if (o.isTarget() && o.getCategory() != ObjectCategory.RESULTSET) {
                return true;
            }
        }
        return false;
    }

    private static int countPhysicalTablesAndViews(ModelBindingManager mgr) {
        int count = 0;
        for (BoundObject o : mgr.allObjects()) {
            if (o.getCategory() == ObjectCategory.TABLE || o.getCategory() == ObjectCategory.VIEW) {
                count++;
            }
        }
        return count;
    }

    private static BoundSourceRef mergeTransforms(BoundSourceRef up, BoundSourceRef mid) {
        if (mid.transforms() == null || mid.transforms().isEmpty()) {
            return up;
        }
        List<Transform> merged = new ArrayList<>();
        if (up.transforms() != null) {
            merged.addAll(up.transforms());
        }
        for (Transform t : mid.transforms()) {
            if (!merged.contains(t)) {
                merged.add(t);
            }
        }
        return new BoundSourceRef(
                up.col(), up.obj(), up.alias(), up.clauseType(),
                up.coordinates(), merged, up.candidateParents(), up.function(), up.value());
    }

    private static List<BoundSourceRef> deduplicateSources(List<BoundSourceRef> list, String targetColId) {
        List<BoundSourceRef> out = new ArrayList<>(list.size());
        Set<String> seen = new LinkedHashSet<>();
        for (BoundSourceRef s : list) {
            if (s == null || s.col() == null || s.col().getId().equals(targetColId)) {
                continue;
            }
            if (seen.add(s.col().getId())) {
                out.add(s);
            }
        }
        return out;
    }

    private static void addIfUnique(List<BoundEdge> edges, Set<String> dedup, BoundEdge e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getType().name()).append('|')
                .append(e.getEffectType() != null ? e.getEffectType().name() : "").append('|')
                .append(e.getTargetCol() != null ? e.getTargetCol().getId() : "").append('|');
        for (BoundSourceRef s : e.getSources()) {
            sb.append(s.col().getId()).append(',');
        }
        if (e.getType() == RelationshipType.call) {
            sb.append(e.getId());
        }
        if (dedup.add(sb.toString())) {
            edges.add(e);
        }
    }
}
