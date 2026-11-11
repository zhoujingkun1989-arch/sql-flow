package io.sqlflow.lineage.analyzer;

import io.sqlflow.contracts.lineage.CandidateTable;
import io.sqlflow.contracts.lineage.EffectType;
import io.sqlflow.contracts.lineage.EndpointIntroduction;
import io.sqlflow.contracts.lineage.RelationshipType;
import io.sqlflow.contracts.vendor.DataObjectType;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundColumn;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundObject;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundSourceRef;
import io.sqlflow.lineage.analyzer.ModelBindingManager.ObjectCategory;
import io.sqlflow.lineage.analyzer.ModelBindingManager.StarLink;
import io.sqlflow.lineage.ast.Expr;
import io.sqlflow.lineage.ast.QualifiedName;
import io.sqlflow.lineage.catalog.TSQLEnv.CatalogColumn;
import io.sqlflow.lineage.catalog.TSQLEnv.CatalogSynonym;
import io.sqlflow.lineage.catalog.TSQLEnv.CatalogTable;
import io.sqlflow.lineage.lexer.SourceSpan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Hierarchical query scope, CTE, table/alias, synonym, {@code SELECT *}, {@code USING}, and orphan column resolver
 * ({@code OBL-036}).
 */
public final class QueryScopeResolver {

    public record ScopeEntry(
            String alias,
            boolean aliasQuoted,
            BoundObject object,
            CatalogTable catalogTable,
            List<String> columnAliases,
            boolean schemaKnown
    ) {
        public String effectiveAliasOrName() {
            return alias != null && !alias.isBlank() ? alias : object.getName();
        }
    }

    public record ExpandedColumn(
            String outputName,
            BoundSourceRef sourceRef,
            Expr replaceExpr
    ) {
    }

    public static final class QueryScope {
        private final QueryScope parent;
        private final AnalyzerContext ctx;
        private final Map<String, BoundObject> ctes = new LinkedHashMap<>();
        private final List<ScopeEntry> entries = new ArrayList<>();
        private final Map<String, List<BoundSourceRef>> selectAliases = new LinkedHashMap<>();
        private final Map<String, BoundObject> loopRecords = new LinkedHashMap<>();

        public QueryScope(QueryScope parent, AnalyzerContext ctx) {
            this.parent = parent;
            this.ctx = ctx;
            if (parent != null && !parent.loopRecords.isEmpty()) {
                this.loopRecords.putAll(parent.loopRecords);
            }
        }

        public AnalyzerContext ctx() {
            return ctx;
        }

        public QueryScope parent() {
            return parent;
        }

        public List<ScopeEntry> entries() {
            return entries;
        }

        public void registerCte(String name, boolean quoted, BoundObject cteObj) {
            String key = ctx.env().lookupKey(DataObjectType.TABLE, name, quoted);
            ctes.put(key, cteObj);
        }

        public BoundObject findCte(QualifiedName qname) {
            if (qname == null || qname.size() != 1) {
                return null;
            }
            String key = ctx.env().lookupKey(DataObjectType.TABLE, qname.lastName(), qname.lastQuoted());
            BoundObject local = ctes.get(key);
            if (local != null) {
                return local;
            }
            return parent != null ? parent.findCte(qname) : null;
        }

        public void addEntry(ScopeEntry entry) {
            if (entry != null) {
                entries.add(entry);
            }
        }

        public void registerSelectAlias(String alias, boolean quoted, List<BoundSourceRef> refs) {
            if (alias == null || alias.isBlank() || refs == null || refs.isEmpty()) {
                return;
            }
            String key = ctx.env().lookupKey(DataObjectType.COLUMN, alias, quoted);
            selectAliases.put(key, refs);
        }

        public List<BoundSourceRef> findSelectAlias(String colName, boolean quoted) {
            String key = ctx.env().lookupKey(DataObjectType.COLUMN, colName, quoted);
            return selectAliases.get(key);
        }

        public void registerLoopRecord(String varName, BoundObject recObj) {
            if (varName != null && recObj != null) {
                loopRecords.put(ctx.varKey(varName), recObj);
            }
        }

        public BoundObject findLoopRecord(String varName) {
            if (varName == null) {
                return null;
            }
            BoundObject local = loopRecords.get(ctx.varKey(varName));
            return local != null ? local : (parent != null ? parent.findLoopRecord(varName) : null);
        }

        public ScopeEntry matchTableQualifier(QualifiedName qualifier) {
            if (qualifier == null || qualifier.isEmpty()) {
                return null;
            }
            ScopeEntry local = matchInEntries(entries, qualifier);
            if (local != null) {
                return local;
            }
            return parent != null ? parent.matchTableQualifier(qualifier) : null;
        }

        private ScopeEntry matchInEntries(List<ScopeEntry> list, QualifiedName qualifier) {
            String target = qualifier.lastName();
            boolean quoted = qualifier.lastQuoted();
            if (qualifier.size() == 1) {
                for (ScopeEntry e : list) {
                    if (e.alias() != null && ctx.env().identifiersMatch(
                            DataObjectType.TABLE, e.alias(), e.aliasQuoted(), target, quoted)) {
                        return e;
                    }
                }
            }
            for (ScopeEntry e : list) {
                if (ctx.env().identifiersMatch(
                        DataObjectType.TABLE, e.object().getName(), false, target, quoted)) {
                    return e;
                }
            }
            return null;
        }
    }

    private QueryScopeResolver() {
    }

    public static ScopeEntry bindNamedTableSource(
            QueryScope scope,
            QualifiedName tableName,
            String alias,
            boolean aliasQuoted,
            List<String> columnAliases,
            boolean isTarget) {
        AnalyzerContext ctx = scope.ctx();
        BoundObject cte = !isTarget ? scope.findCte(tableName) : null;
        if (cte != null) {
            applyPositionalAliasesIfNeeded(ctx, cte, columnAliases, tableName.span());
            return new ScopeEntry(alias, aliasQuoted, cte, null, columnAliases, !cte.orderedColumns().isEmpty());
        }

        if (tableName != null && tableName.size() == 1) {
            BoundObject varTbl = ctx.findVariable(tableName.lastName());
            if (varTbl != null) {
                return new ScopeEntry(alias, aliasQuoted, varTbl, null, columnAliases, !varTbl.orderedColumns().isEmpty());
            }
        }

        CatalogSynonym syn = ctx.env().resolveSynonym(tableName);
        CatalogTable catTbl = ctx.env().resolveTable(tableName);
        BoundObject targetObj;
        if (syn != null) {
            List<String> synTargetParts = new ArrayList<>(3);
            if (syn.getTargetDatabase() != null && !syn.getTargetDatabase().isBlank()) {
                synTargetParts.add(syn.getTargetDatabase());
            }
            if (syn.getTargetSchema() != null && !syn.getTargetSchema().isBlank()) {
                synTargetParts.add(syn.getTargetSchema());
            }
            synTargetParts.add(syn.getTargetName());
            QualifiedName synTargetQn = new QualifiedName(
                    QualifiedName.ofParts(synTargetParts, tableName.span()).parts(),
                    syn.getDbLink(),
                    false,
                    false,
                    false,
                    tableName.span());
            BoundObject realObj = ctx.mgr().bindTableOrView(
                    synTargetQn,
                    ObjectCategory.TABLE,
                    EndpointIntroduction.CATALOG_REFERENCE,
                    isTarget,
                    false,
                    false,
                    ctx.sqlHash());
            BoundObject synObj = ctx.mgr().bindSynonymObject(tableName, realObj, false, tableName.span(), ctx.sqlHash());
            targetObj = ctx.option().isSimpleShowSynonym() ? synObj : realObj;
            catTbl = ctx.env().resolveTable(synTargetQn);
        } else {
            targetObj = ctx.mgr().bindTableOrView(
                    tableName,
                    ObjectCategory.TABLE,
                    EndpointIntroduction.CATALOG_REFERENCE,
                    isTarget,
                    false,
                    false,
                    ctx.sqlHash());
        }

        if (alias != null && !alias.isBlank() && targetObj.getAlias() == null) {
            targetObj.setAlias(alias);
        }
        boolean known = (catTbl != null && !catTbl.orderedColumns().isEmpty())
                || !targetObj.orderedColumns().isEmpty()
                || (columnAliases != null && !columnAliases.isEmpty());
        applyPositionalAliasesIfNeeded(ctx, targetObj, columnAliases, tableName != null ? tableName.span() : null);
        return new ScopeEntry(alias, aliasQuoted, targetObj, catTbl, columnAliases, known);
    }

    private static void applyPositionalAliasesIfNeeded(
            AnalyzerContext ctx, BoundObject obj, List<String> colAliases, SourceSpan span) {
        if (colAliases == null || colAliases.isEmpty()) {
            return;
        }
        List<BoundColumn> existing = obj.orderedColumns();
        if (existing.isEmpty()) {
            for (String colAlias : colAliases) {
                obj.getOrCreateColumn(ctx.mgr(), colAlias, false, span, ctx.sqlHash());
            }
        }
    }

    public static List<BoundSourceRef> resolveColumnRef(
            QueryScope scope,
            QualifiedName name,
            List<String> structPath,
            String clauseType,
            SourceSpan span) {
        AnalyzerContext ctx = scope.ctx();
        if (name == null || name.isEmpty()) {
            return List.of();
        }

        // 1. Check loop record / trigger pseudo-record / variable for 2-part or 1-part reference
        if (name.size() == 2) {
            String prefix = name.parts().get(0).value();
            String field = name.lastName();
            BoundObject loopRec = scope.findLoopRecord(prefix);
            if (loopRec != null) {
                BoundColumn col = loopRec.getOrCreateColumn(ctx.mgr(), field, name.lastQuoted(), span, ctx.sqlHash());
                return List.of(ctx.toSourceRef(loopRec, col, prefix, clauseType, span));
            }
            if (ctx.triggerTable() != null && isTriggerPseudoRecord(prefix)) {
                BoundColumn col = ctx.triggerTable().getOrCreateColumn(
                        ctx.mgr(), field, name.lastQuoted(), span, ctx.sqlHash());
                return List.of(ctx.toSourceRef(ctx.triggerTable(), col, prefix, clauseType, span));
            }
            BoundObject recVar = ctx.findVariable(prefix);
            if (recVar != null) {
                BoundColumn col = recVar.getOrCreateColumn(ctx.mgr(), field, name.lastQuoted(), span, ctx.sqlHash());
                return List.of(ctx.toSourceRef(recVar, col, prefix, clauseType, span));
            }
        } else if (name.size() == 1) {
            BoundObject scalarVar = ctx.findVariable(name.lastName());
            if (scalarVar != null && scope.entries().isEmpty()) {
                List<BoundColumn> cols = scalarVar.orderedColumns();
                BoundColumn col = cols.isEmpty()
                        ? scalarVar.getOrCreateColumn(ctx.mgr(), name.lastName(), false, span, ctx.sqlHash())
                        : cols.get(0);
                return List.of(ctx.toSourceRef(scalarVar, col, null, clauseType, span));
            }
        }

        // 2. Qualified column: prefix.col (or db.sch.tbl.col)
        if (name.size() >= 2) {
            QualifiedName tblQual = new QualifiedName(
                    name.parts().subList(0, name.size() - 1),
                    name.dbLink(),
                    false,
                    false,
                    name.templated(),
                    span);
            ScopeEntry matched = scope.matchTableQualifier(tblQual);
            if (matched != null) {
                String colName = resolveAliasedColumnName(matched, name.lastName(), name.lastQuoted(), ctx);
                BoundColumn col = matched.object().getOrCreateColumn(
                        ctx.mgr(), colName, name.lastQuoted(), span, ctx.sqlHash());
                return List.of(ctx.toSourceRef(matched.object(), col, matched.alias(), clauseType, span));
            }
            // Fallback: struct field access on unqualified column in scope (e.g. payload.user_id)
            if (name.size() == 2 && !scope.entries().isEmpty()) {
                QualifiedName rootCol = QualifiedName.single(
                        name.parts().get(0).raw(),
                        name.parts().get(0).value(),
                        name.parts().get(0).quoted(),
                        name.parts().get(0).templated(),
                        span);
                return resolveUnqualified(scope, rootCol, clauseType, span);
            }
            // Direct physical table reference outside FROM (e.g. UPDATE target or correlated schema.tbl.col)
            BoundObject directObj = ctx.mgr().bindTableOrView(
                    tblQual, ObjectCategory.TABLE, EndpointIntroduction.CATALOG_REFERENCE, false, false, false, ctx.sqlHash());
            BoundColumn col = directObj.getOrCreateColumn(ctx.mgr(), name.lastName(), name.lastQuoted(), span, ctx.sqlHash());
            return List.of(ctx.toSourceRef(directObj, col, null, clauseType, span));
        }

        // 3. Unqualified column
        return resolveUnqualified(scope, name, clauseType, span);
    }

    private static List<BoundSourceRef> resolveUnqualified(
            QueryScope scope, QualifiedName colQn, String clauseType, SourceSpan span) {
        AnalyzerContext ctx = scope.ctx();
        String colName = colQn.lastName();
        boolean quoted = colQn.lastQuoted();

        // Check current scope entries
        List<ScopeEntry> entries = scope.entries();
        if (entries.size() == 1) {
            ScopeEntry only = entries.get(0);
            String actualCol = resolveAliasedColumnName(only, colName, quoted, ctx);
            BoundColumn col = only.object().getOrCreateColumn(ctx.mgr(), actualCol, quoted, span, ctx.sqlHash());
            return List.of(ctx.toSourceRef(only.object(), col, only.alias(), clauseType, span));
        }
        if (entries.size() > 1) {
            for (ScopeEntry entry : entries) {
                if (entryHasColumn(ctx, entry, colName, quoted)) {
                    String actualCol = resolveAliasedColumnName(entry, colName, quoted, ctx);
                    BoundColumn col = entry.object().getOrCreateColumn(ctx.mgr(), actualCol, quoted, span, ctx.sqlHash());
                    return List.of(ctx.toSourceRef(entry.object(), col, entry.alias(), clauseType, span));
                }
            }
        }

        // Check SELECT projection aliases (for GROUP BY / HAVING / QUALIFY / ORDER BY / lateral alias)
        List<BoundSourceRef> selectAliasRefs = scope.findSelectAlias(colName, quoted);
        if (selectAliasRefs != null && !selectAliasRefs.isEmpty()) {
            return selectAliasRefs;
        }

        // Check procedural variable before falling back to outer scope or orphan table
        BoundObject localVar = ctx.findVariable(colName);
        if (localVar != null) {
            BoundColumn varCol = localVar.orderedColumns().isEmpty()
                    ? localVar.getOrCreateColumn(ctx.mgr(), colName, false, span, ctx.sqlHash())
                    : localVar.orderedColumns().get(0);
            return List.of(ctx.toSourceRef(localVar, varCol, null, clauseType, span));
        }

        // Check enclosing parent scopes (correlated subqueries)
        if (scope.parent() != null) {
            QueryScope p = scope.parent();
            while (p != null) {
                if (p.entries().size() == 1 && entries.isEmpty()) {
                    ScopeEntry only = p.entries().get(0);
                    BoundColumn col = only.object().getOrCreateColumn(ctx.mgr(), colName, quoted, span, ctx.sqlHash());
                    return List.of(ctx.toSourceRef(only.object(), col, only.alias(), clauseType, span));
                }
                for (ScopeEntry entry : p.entries()) {
                    if (entryHasColumn(ctx, entry, colName, quoted)) {
                        BoundColumn col = entry.object().getOrCreateColumn(ctx.mgr(), colName, quoted, span, ctx.sqlHash());
                        return List.of(ctx.toSourceRef(entry.object(), col, entry.alias(), clauseType, span));
                    }
                }
                p = p.parent();
            }
        }

        // Ambiguous unqualified column across multiple schema-less tables in scope
        if (entries.size() > 1) {
            List<CandidateTable> candidates = null;
            if (ctx.option().isShowCandidateTable()) {
                candidates = new ArrayList<>(entries.size());
                for (ScopeEntry e : entries) {
                    candidates.add(new CandidateTable(e.object().getId(), e.object().getDisplayName()));
                }
            }
            if (ctx.option().isLinkOrphanColumnToFirstTable()) {
                ScopeEntry first = entries.get(0);
                BoundColumn col = first.object().getOrCreateColumn(ctx.mgr(), colName, quoted, span, ctx.sqlHash());
                return List.of(new BoundSourceRef(
                        col,
                        first.object(),
                        first.alias(),
                        clauseType,
                        ctx.mgr().toCoords(span, ctx.sqlHash()),
                        ctx.makeTransforms(clauseType != null ? clauseType.toLowerCase(Locale.ROOT) : "select", span, null),
                        candidates,
                        null,
                        null));
            }
            BoundObject orphan = ctx.mgr().getOrCreateOrphanTable(ctx.sqlHash());
            BoundColumn orphanCol = orphan.getOrCreateColumn(ctx.mgr(), colName, quoted, span, ctx.sqlHash());
            if (candidates != null) {
                for (CandidateTable ct : candidates) {
                    if (!orphan.getCandidateTables().contains(ct)) {
                        orphan.getCandidateTables().add(ct);
                    }
                }
            }
            return List.of(new BoundSourceRef(
                    orphanCol,
                    orphan,
                    null,
                    clauseType,
                    ctx.mgr().toCoords(span, ctx.sqlHash()),
                    ctx.makeTransforms(clauseType != null ? clauseType.toLowerCase(Locale.ROOT) : "select", span, null),
                    candidates,
                    null,
                    null));
        }

        // Zero FROM tables (e.g., standalone expression referencing unknown identifier)
        BoundObject orphan = ctx.mgr().getOrCreateOrphanTable(ctx.sqlHash());
        BoundColumn orphanCol = orphan.getOrCreateColumn(ctx.mgr(), colName, quoted, span, ctx.sqlHash());
        return List.of(ctx.toSourceRef(orphan, orphanCol, null, clauseType, span));
    }

    public static List<ExpandedColumn> expandStar(
            QueryScope scope,
            Expr.StarExpr star,
            BoundObject targetRs,
            EffectType effectType) {
        AnalyzerContext ctx = scope.ctx();
        List<ScopeEntry> targets;
        if (star.qualifier() != null && !star.qualifier().isEmpty()) {
            ScopeEntry matched = scope.matchTableQualifier(star.qualifier());
            targets = matched != null ? List.of(matched) : List.of();
        } else {
            targets = scope.entries();
        }

        List<ExpandedColumn> out = new ArrayList<>();
        for (ScopeEntry entry : targets) {
            List<String> knownColNames = collectKnownColumnNames(ctx, entry);
            if (!knownColNames.isEmpty()) {
                for (int i = 0; i < knownColNames.size(); i++) {
                    String rawColName = knownColNames.get(i);
                    String exposedName = (entry.columnAliases() != null && i < entry.columnAliases().size())
                            ? entry.columnAliases().get(i)
                            : rawColName;
                    if (isExcepted(ctx, exposedName, star.exceptColumns())) {
                        continue;
                    }
                    String finalOutName = findRenamed(ctx, exposedName, star.renameColumns());
                    Expr replaceExpr = findReplaced(ctx, exposedName, star.replaceColumns());
                    BoundColumn srcCol = entry.object().getOrCreateColumn(
                            ctx.mgr(), rawColName, false, star.span(), ctx.sqlHash());
                    out.add(new ExpandedColumn(
                            finalOutName,
                            ctx.toSourceRef(entry.object(), srcCol, entry.alias(), "SELECT", star.span()),
                            replaceExpr));
                }
            } else {
                // Schema-unknown source: register StarLink for back-propagation AND emit * column
                if (targetRs != null) {
                    ctx.mgr().registerStarLink(new StarLink(
                            entry.object(),
                            entry.alias(),
                            targetRs,
                            star.exceptColumns(),
                            effectType != null ? effectType : EffectType.select,
                            ctx.activeProcess() != null ? ctx.activeProcess().getId() : null,
                            ctx.routineId(),
                            ctx.sqlHash()));
                }
                BoundColumn starSrc = entry.object().getOrCreateColumn(
                        ctx.mgr(), "*", false, star.span(), ctx.sqlHash());
                out.add(new ExpandedColumn(
                        "*",
                        ctx.toSourceRef(entry.object(), starSrc, entry.alias(), "SELECT", star.span()),
                        null));
            }
        }
        return out;
    }

    private static List<String> collectKnownColumnNames(AnalyzerContext ctx, ScopeEntry entry) {
        Set<String> names = new LinkedHashSet<>();
        if (entry.catalogTable() != null) {
            for (CatalogColumn cc : entry.catalogTable().orderedColumns()) {
                if (cc.getName() != null && !"*".equals(cc.getName()) && !"RelationRows".equalsIgnoreCase(cc.getName())) {
                    names.add(cc.getName());
                }
            }
        }
        for (BoundColumn bc : entry.object().orderedColumns()) {
            if (bc.getName() != null && !"*".equals(bc.getName()) && !"RelationRows".equalsIgnoreCase(bc.getName())) {
                names.add(bc.getName());
            }
        }
        return new ArrayList<>(names);
    }

    private static boolean entryHasColumn(AnalyzerContext ctx, ScopeEntry entry, String colName, boolean quoted) {
        if (entry.columnAliases() != null) {
            for (String ca : entry.columnAliases()) {
                if (ctx.env().identifiersMatch(DataObjectType.COLUMN, ca, false, colName, quoted)) {
                    return true;
                }
            }
        }
        if (entry.catalogTable() != null && ctx.env().resolveColumn(entry.catalogTable(), colName, quoted) != null) {
            return true;
        }
        return entry.object().findColumn(ctx.env(), colName, quoted) != null;
    }

    private static String resolveAliasedColumnName(ScopeEntry entry, String colName, boolean quoted, AnalyzerContext ctx) {
        if (entry.columnAliases() != null && !entry.columnAliases().isEmpty()) {
            List<BoundColumn> cols = entry.object().orderedColumns();
            for (int i = 0; i < entry.columnAliases().size(); i++) {
                if (ctx.env().identifiersMatch(DataObjectType.COLUMN, entry.columnAliases().get(i), false, colName, quoted)) {
                    if (i < cols.size()) {
                        return cols.get(i).getName();
                    }
                }
            }
        }
        return colName;
    }

    private static boolean isExcepted(AnalyzerContext ctx, String colName, List<String> excepts) {
        if (excepts == null || excepts.isEmpty()) {
            return false;
        }
        for (String ex : excepts) {
            if (ctx.env().identifiersMatch(DataObjectType.COLUMN, colName, false, ex, false)) {
                return true;
            }
        }
        return false;
    }

    private static String findRenamed(AnalyzerContext ctx, String colName, Map<String, String> renames) {
        if (renames == null || renames.isEmpty()) {
            return colName;
        }
        for (Map.Entry<String, String> e : renames.entrySet()) {
            if (ctx.env().identifiersMatch(DataObjectType.COLUMN, colName, false, e.getKey(), false)) {
                return e.getValue();
            }
        }
        return colName;
    }

    private static Expr findReplaced(AnalyzerContext ctx, String colName, Map<String, Expr> replaces) {
        if (replaces == null || replaces.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, Expr> e : replaces.entrySet()) {
            if (ctx.env().identifiersMatch(DataObjectType.COLUMN, colName, false, e.getKey(), false)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static boolean isTriggerPseudoRecord(String name) {
        if (name == null) {
            return false;
        }
        String clean = name.startsWith(":") ? name.substring(1) : name;
        String upper = clean.toUpperCase(Locale.ROOT);
        return "NEW".equals(upper) || "OLD".equals(upper) || "INSERTED".equals(upper) || "DELETED".equals(upper);
    }
}
