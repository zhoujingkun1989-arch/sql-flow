package io.sqlflow.lineage.analyzer;

import io.sqlflow.contracts.lineage.AnalyzeMode;
import io.sqlflow.contracts.lineage.EffectType;
import io.sqlflow.contracts.lineage.EndpointIntroduction;
import io.sqlflow.contracts.lineage.RelationshipType;
import io.sqlflow.contracts.lineage.ResultSetType;
import io.sqlflow.lineage.analyzer.ExpressionAnalyzer.EvalResult;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundColumn;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundObject;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundSourceRef;
import io.sqlflow.lineage.analyzer.ModelBindingManager.ObjectCategory;
import io.sqlflow.lineage.analyzer.QueryScopeResolver.QueryScope;
import io.sqlflow.lineage.analyzer.QueryScopeResolver.ScopeEntry;
import io.sqlflow.lineage.ast.Expr;
import io.sqlflow.lineage.ast.QualifiedName;
import io.sqlflow.lineage.ast.QueryAst.JoinClause;
import io.sqlflow.lineage.ast.QueryAst.OutputClause;
import io.sqlflow.lineage.ast.QueryAst.SelectItem;
import io.sqlflow.lineage.ast.QueryAst.TableSource;
import io.sqlflow.lineage.ast.QueryAst.ValuesQuery;
import io.sqlflow.lineage.ast.SqlStmt;
import io.sqlflow.lineage.ast.SqlStmt.AssignmentItem;
import io.sqlflow.lineage.ast.SqlStmt.CopyIoStmt;
import io.sqlflow.lineage.ast.SqlStmt.DeleteStmt;
import io.sqlflow.lineage.ast.SqlStmt.InsertBranch;
import io.sqlflow.lineage.ast.SqlStmt.InsertStmt;
import io.sqlflow.lineage.ast.SqlStmt.MergeStmt;
import io.sqlflow.lineage.ast.SqlStmt.MergeWhenClause;
import io.sqlflow.lineage.ast.SqlStmt.MultiInsertStmt;
import io.sqlflow.lineage.ast.SqlStmt.UpdateStmt;
import io.sqlflow.lineage.catalog.TSQLEnv.CatalogColumn;
import io.sqlflow.lineage.catalog.TSQLEnv.CatalogTable;
import io.sqlflow.lineage.lexer.SourceSpan;
import io.sqlflow.lineage.parser.StatementParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Semantic analyzer for {@code INSERT}, multi-table {@code INSERT ALL/FIRST}, {@code UPDATE},
 * {@code DELETE}, {@code MERGE}, {@code COPY INTO}/{@code UNLOAD}/{@code LOAD DATA}, and {@code OUTPUT}/{@code RETURNING}.
 */
public final class DmlAnalyzer {

    private DmlAnalyzer() {
    }

    public static void analyzeInsert(InsertStmt stmt, AnalyzerContext ctx) {
        BoundObject target = stmt.directoryTarget()
                ? ctx.mgr().bindPathOrStageObject(
                        stmt.targetTable().lastName(), ObjectCategory.PATH, "path", stmt.span(), true, ctx.sqlHash())
                : ctx.mgr().bindTableOrView(
                        stmt.targetTable(), ObjectCategory.TABLE, EndpointIntroduction.CATALOG_REFERENCE,
                        true, false, false, ctx.sqlHash());
        EffectType eff = stmt.directoryTarget() ? EffectType.unload
                : (stmt.replaceInto() ? EffectType.replace : EffectType.insert);
        emitCrudWriteIfRequested(target, eff, ctx, stmt.span());

        List<String> targetColNames = resolveTargetColumnNames(stmt.targetTable(), stmt.targetColumns(), target, ctx);
        QueryScope scope = new QueryScope(null, ctx);

        if (stmt.sourceQuery() instanceof ValuesQuery vq && ctx.option().isIgnoreInsertIntoValues()) {
            if (vq.rows() != null && !vq.rows().isEmpty()) {
                int cols = vq.rows().get(0).size();
                for (int c = 0; c < cols; c++) {
                    String colName = c < targetColNames.size() ? targetColNames.get(c) : "COL_" + (c + 1);
                    SourceSpan colSpan = (stmt.targetColumnSpans() != null && c < stmt.targetColumnSpans().size())
                            ? stmt.targetColumnSpans().get(c)
                            : stmt.span();
                    BoundColumn tgtCol = target.getOrCreateColumn(ctx.mgr(), colName, false, colSpan, ctx.sqlHash());
                    List<BoundSourceRef> direct = new ArrayList<>();
                    List<BoundSourceRef> indirect = new ArrayList<>(ctx.controlPredicates());
                    for (List<Expr> row : vq.rows()) {
                        if (c < row.size()) {
                            EvalResult er = ExpressionAnalyzer.evaluate(row.get(c), scope, "insert", null);
                            direct.addAll(er.direct());
                            indirect.addAll(er.indirect());
                        }
                    }
                    if (!direct.isEmpty()) {
                        ctx.mgr().addColumnEdge(
                                RelationshipType.fdd, eff, target, tgtCol, direct,
                                ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
                    }
                    SelectAndSetOpAnalyzer.emitIndirectEdges(ctx, eff, target, tgtCol, indirect, null);
                }
            }
        } else if (stmt.sourceQuery() != null) {
            BoundObject srcRs = SelectAndSetOpAnalyzer.analyzeQuery(
                    stmt.sourceQuery(), scope, ctx, false, ResultSetType.insert_select, "INSERT-SELECT", eff);
            if (srcRs != null) {
                wireRsToTarget(ctx, srcRs, target, targetColNames, stmt.targetColumnSpans(), eff, ctx.controlPredicates());
            }
        }
        handleOutputClause(stmt.outputClause(), target, scope, ctx);
    }

    public static void analyzeMultiInsert(MultiInsertStmt stmt, AnalyzerContext ctx) {
        QueryScope baseScope = new QueryScope(null, ctx);
        List<BoundSourceRef> sharedInd = new ArrayList<>(ctx.controlPredicates());
        if (stmt.sharedSourceQuery() != null) {
            BoundObject srcRs = SelectAndSetOpAnalyzer.analyzeQuery(
                    stmt.sharedSourceQuery(), baseScope, ctx, false, ResultSetType.insert_select, "INSERT-SELECT", EffectType.insert);
            if (srcRs != null) {
                baseScope.addEntry(new ScopeEntry(null, false, srcRs, null, null, !srcRs.orderedColumns().isEmpty()));
            }
        } else {
            if (stmt.sharedFromSources() != null) {
                for (TableSource ts : stmt.sharedFromSources()) {
                    baseScope.addEntry(SelectAndSetOpAnalyzer.analyzeTableSource(ts, baseScope, ctx, sharedInd));
                }
            }
            if (stmt.sharedJoins() != null) {
                for (JoinClause jc : stmt.sharedJoins()) {
                    SelectAndSetOpAnalyzer.analyzeJoinClause(jc, baseScope, ctx, sharedInd);
                }
            }
            if (stmt.sharedWhere() != null) {
                sharedInd.addAll(ExpressionAnalyzer.evaluate(stmt.sharedWhere(), baseScope, "where", null).allRefsWithClause("where"));
            }
        }

        boolean isFirst = "INSERT_FIRST".equalsIgnoreCase(stmt.style());
        List<BoundSourceRef> cumulativeWhenRefs = new ArrayList<>();
        if (stmt.branches() == null) {
            return;
        }
        for (InsertBranch br : stmt.branches()) {
            List<BoundSourceRef> branchInd = new ArrayList<>(sharedInd);
            if (isFirst) {
                branchInd.addAll(cumulativeWhenRefs);
            }
            if (br.whenCondition() != null) {
                List<BoundSourceRef> wRefs = ExpressionAnalyzer.evaluate(
                        br.whenCondition(), baseScope, "when", null).allRefsWithClause("when");
                branchInd.addAll(wRefs);
                if (isFirst) {
                    cumulativeWhenRefs.addAll(wRefs);
                }
            }
            BoundObject tgt = br.directoryTarget()
                    ? ctx.mgr().bindPathOrStageObject(
                            br.targetTable().lastName(), ObjectCategory.PATH, "path", br.span(), true, ctx.sqlHash())
                    : ctx.mgr().bindTableOrView(
                            br.targetTable(), ObjectCategory.TABLE, EndpointIntroduction.CATALOG_REFERENCE,
                            true, false, false, ctx.sqlHash());
            EffectType eff = br.directoryTarget() ? EffectType.unload : EffectType.insert;
            List<String> tgtCols = resolveTargetColumnNames(br.targetTable(), br.targetColumns(), tgt, ctx);

            if (br.explicitValues() != null && !br.explicitValues().isEmpty()) {
                for (int i = 0; i < br.explicitValues().size(); i++) {
                    String cn = i < tgtCols.size() ? tgtCols.get(i) : "COL_" + (i + 1);
                    BoundColumn tc = tgt.getOrCreateColumn(ctx.mgr(), cn, false, br.span(), ctx.sqlHash());
                    EvalResult er = ExpressionAnalyzer.evaluate(br.explicitValues().get(i), baseScope, "insert", null);
                    if (!er.direct().isEmpty()) {
                        ctx.mgr().addColumnEdge(
                                RelationshipType.fdd, eff, tgt, tc, er.direct(),
                                ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), er.functionName(), null, null, null);
                    }
                    List<BoundSourceRef> allInd = new ArrayList<>(er.indirect());
                    allInd.addAll(branchInd);
                    SelectAndSetOpAnalyzer.emitIndirectEdges(ctx, eff, tgt, tc, allInd, er.functionName());
                }
            } else if (br.branchQuery() != null) {
                BoundObject brRs = SelectAndSetOpAnalyzer.analyzeQuery(
                        br.branchQuery(), baseScope, ctx, false, ResultSetType.insert_select, "INSERT-SELECT", eff);
                if (brRs != null) {
                    wireRsToTarget(ctx, brRs, tgt, tgtCols, br.targetColumnSpans(), eff, branchInd);
                }
            } else if (!baseScope.entries().isEmpty()) {
                BoundObject drivingRs = baseScope.entries().get(0).object();
                wireRsToTarget(ctx, drivingRs, tgt, tgtCols, br.targetColumnSpans(), eff, branchInd);
            }
        }
    }

    public static void analyzeUpdate(UpdateStmt stmt, AnalyzerContext ctx) {
        QueryScope scope = new QueryScope(null, ctx);
        List<BoundSourceRef> indirect = new ArrayList<>(ctx.controlPredicates());
        ScopeEntry primaryTarget = null;
        if (stmt.targetTables() != null) {
            for (int i = 0; i < stmt.targetTables().size(); i++) {
                TableSource ts = stmt.targetTables().get(i);
                ScopeEntry se = (ts instanceof io.sqlflow.lineage.ast.QueryAst.NamedTableSource nts)
                        ? QueryScopeResolver.bindNamedTableSource(
                                scope, nts.tableName(), nts.alias(), nts.aliasQuoted(), nts.columnAliases(), i == 0)
                        : SelectAndSetOpAnalyzer.analyzeTableSource(ts, scope, ctx, indirect);
                scope.addEntry(se);
                if (i == 0) {
                    primaryTarget = se;
                    emitCrudWriteIfRequested(se.object(), EffectType.update, ctx, stmt.span());
                }
            }
        }
        if (stmt.targetJoins() != null) {
            for (JoinClause jc : stmt.targetJoins()) {
                SelectAndSetOpAnalyzer.analyzeJoinClause(jc, scope, ctx, indirect);
            }
        }
        if (stmt.fromSources() != null) {
            for (TableSource ts : stmt.fromSources()) {
                scope.addEntry(SelectAndSetOpAnalyzer.analyzeTableSource(ts, scope, ctx, indirect));
            }
        }
        if (stmt.fromJoins() != null) {
            for (JoinClause jc : stmt.fromJoins()) {
                SelectAndSetOpAnalyzer.analyzeJoinClause(jc, scope, ctx, indirect);
            }
        }
        if (stmt.whereExpr() != null) {
            indirect.addAll(ExpressionAnalyzer.evaluate(stmt.whereExpr(), scope, "where", null).allRefsWithClause("where"));
        }
        if (primaryTarget == null || stmt.assignments() == null) {
            return;
        }
        BoundObject updateSetRs = ctx.mgr().createResultSet(
                ResultSetType.update_set, "UPDATE-SET", false, ctx.routineId(), ctx.sqlHash(), stmt.span());
        for (AssignmentItem item : stmt.assignments()) {
            applyAssignmentItem(item, primaryTarget.object(), updateSetRs, EffectType.update, scope, indirect, ctx);
        }
        handleOutputClause(stmt.outputClause(), primaryTarget.object(), scope, ctx);
    }

    public static void analyzeDelete(DeleteStmt stmt, AnalyzerContext ctx) {
        QueryScope scope = new QueryScope(null, ctx);
        ScopeEntry tgt = QueryScopeResolver.bindNamedTableSource(
                scope, stmt.targetTable(), stmt.targetAlias(), false, null, true);
        scope.addEntry(tgt);
        emitCrudWriteIfRequested(tgt.object(), EffectType.delete, ctx, stmt.span());

        List<BoundSourceRef> indirect = new ArrayList<>(ctx.controlPredicates());
        if (stmt.usingSources() != null) {
            for (TableSource ts : stmt.usingSources()) {
                scope.addEntry(SelectAndSetOpAnalyzer.analyzeTableSource(ts, scope, ctx, indirect));
            }
        }
        if (stmt.joins() != null) {
            for (JoinClause jc : stmt.joins()) {
                SelectAndSetOpAnalyzer.analyzeJoinClause(jc, scope, ctx, indirect);
            }
        }
        if (stmt.whereExpr() != null) {
            indirect.addAll(ExpressionAnalyzer.evaluate(stmt.whereExpr(), scope, "where", null).allRefsWithClause("where"));
        }
        if (!indirect.isEmpty()) {
            BoundColumn rr = tgt.object().getOrCreateRelationRows(ctx.mgr());
            SelectAndSetOpAnalyzer.emitIndirectEdges(ctx, EffectType.delete, tgt.object(), rr, indirect, null);
        }
        handleOutputClause(stmt.outputClause(), tgt.object(), scope, ctx);
    }

    public static void analyzeMerge(MergeStmt stmt, AnalyzerContext ctx) {
        QueryScope scope = new QueryScope(null, ctx);
        ScopeEntry tgtEntry = QueryScopeResolver.bindNamedTableSource(
                scope, stmt.targetTable(), stmt.targetAlias(), false, null, true);
        scope.addEntry(tgtEntry);
        emitCrudWriteIfRequested(tgtEntry.object(), EffectType.merge, ctx, stmt.span());

        List<BoundSourceRef> onIndirect = new ArrayList<>(ctx.controlPredicates());
        if (stmt.usingSource() != null) {
            scope.addEntry(SelectAndSetOpAnalyzer.analyzeTableSource(stmt.usingSource(), scope, ctx, onIndirect));
        }
        if (stmt.onCondition() != null) {
            onIndirect.addAll(ExpressionAnalyzer.evaluate(stmt.onCondition(), scope, "on", null).allRefsWithClause("on"));
            if (ctx.option().isShowJoin()) {
                SelectAndSetOpAnalyzer.extractEqualityJoins(stmt.onCondition(), "merge", "on", scope, ctx);
            }
        }
        if (stmt.whenClauses() != null) {
            for (MergeWhenClause wc : stmt.whenClauses()) {
                List<BoundSourceRef> branchInd = new ArrayList<>(onIndirect);
                if (wc.andCondition() != null) {
                    branchInd.addAll(ExpressionAnalyzer.evaluate(
                            wc.andCondition(), scope, "when", null).allRefsWithClause("when"));
                }
                String action = wc.action() != null ? wc.action().toUpperCase(Locale.ROOT) : "";
                if ("UPDATE".equals(action) && wc.updateAssignments() != null) {
                    BoundObject muRs = ctx.mgr().createResultSet(
                            ResultSetType.merge_update, "MERGE-UPDATE", false, ctx.routineId(), ctx.sqlHash(), wc.span());
                    for (AssignmentItem ai : wc.updateAssignments()) {
                        applyAssignmentItem(ai, tgtEntry.object(), muRs, EffectType.merge_update, scope, branchInd, ctx);
                    }
                    if (wc.deleteWhere() != null) {
                        List<BoundSourceRef> delInd = ExpressionAnalyzer.evaluate(
                                wc.deleteWhere(), scope, "where", null).allRefsWithClause("where");
                        BoundColumn rr = tgtEntry.object().getOrCreateRelationRows(ctx.mgr());
                        SelectAndSetOpAnalyzer.emitIndirectEdges(ctx, EffectType.delete, tgtEntry.object(), rr, delInd, null);
                    }
                } else if ("INSERT".equals(action)) {
                    BoundObject miRs = ctx.mgr().createResultSet(
                            ResultSetType.merge_insert, "MERGE-INSERT", false, ctx.routineId(), ctx.sqlHash(), wc.span());
                    List<String> insCols = resolveTargetColumnNames(
                            stmt.targetTable(), wc.insertColumns(), tgtEntry.object(), ctx);
                    if (wc.insertValues() != null && !wc.insertValues().isEmpty()) {
                        for (int i = 0; i < wc.insertValues().size(); i++) {
                            String cn = i < insCols.size() ? insCols.get(i) : "COL_" + (i + 1);
                            BoundColumn midCol = miRs.getOrCreateColumn(ctx.mgr(), cn, false, wc.span(), ctx.sqlHash());
                            BoundColumn tgtCol = tgtEntry.object().getOrCreateColumn(ctx.mgr(), cn, false, wc.span(), ctx.sqlHash());
                            EvalResult er = ExpressionAnalyzer.evaluate(wc.insertValues().get(i), scope, "insert", null);
                            if (!er.direct().isEmpty()) {
                                ctx.mgr().addColumnEdge(
                                        RelationshipType.fdd, EffectType.merge_insert, miRs, midCol, er.direct(),
                                        ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), er.functionName(), null, null, null);
                            }
                            SelectAndSetOpAnalyzer.emitIndirectEdges(ctx, EffectType.merge, miRs, midCol, branchInd, null);
                            ctx.mgr().addColumnEdge(
                                    RelationshipType.fdd, EffectType.merge_insert, tgtEntry.object(), tgtCol,
                                    List.of(ctx.toSourceRef(miRs, midCol, null, "insert", wc.span())),
                                    ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
                        }
                    } else if (scope.entries().size() > 1) {
                        BoundObject usingObj = scope.entries().get(1).object();
                        wireRsToTarget(ctx, usingObj, miRs, insCols, null, EffectType.merge_insert, branchInd);
                        wireRsToTarget(ctx, miRs, tgtEntry.object(), insCols, null, EffectType.merge_insert, List.of());
                    }
                } else if ("DELETE".equals(action)) {
                    BoundColumn rr = tgtEntry.object().getOrCreateRelationRows(ctx.mgr());
                    SelectAndSetOpAnalyzer.emitIndirectEdges(ctx, EffectType.delete, tgtEntry.object(), rr, branchInd, null);
                }
            }
        }
        handleOutputClause(stmt.outputClause(), tgtEntry.object(), scope, ctx);
    }

    public static void analyzeCopyIo(CopyIoStmt stmt, AnalyzerContext ctx) {
        String op = stmt.operation() != null ? stmt.operation().toUpperCase(Locale.ROOT) : "COPY";
        BoundObject target = bindTableStageOrPath(stmt.targetObject(), true, stmt.span(), ctx);
        if (stmt.fileFormat() != null && target != null) {
            target.setFileFormat(stmt.fileFormat());
        }
        EffectType eff = ("UNLOAD".equals(op)
                || (target != null && (target.getCategory() == ObjectCategory.STAGE || target.getCategory() == ObjectCategory.PATH)))
                ? EffectType.unload
                : EffectType.copy;

        BoundObject srcObj = null;
        if (stmt.sourceQuery() != null) {
            srcObj = SelectAndSetOpAnalyzer.analyzeQuery(
                    stmt.sourceQuery(), new QueryScope(null, ctx), ctx, false, ResultSetType.select_list, null, eff);
        } else if (stmt.sourceObject() != null && !stmt.sourceObject().isEmpty()) {
            String rawSrc = stmt.sourceObject().lastName();
            if ("UNLOAD".equals(op) && rawSrc != null && rawSrc.trim().toUpperCase(Locale.ROOT).startsWith("SELECT")) {
                StatementParser.ParsedScript inner = StatementParser.parseScript(rawSrc, ctx.vendor());
                for (SqlStmt s : inner.statements()) {
                    if (s instanceof SqlStmt.QueryStmt qs) {
                        srcObj = SelectAndSetOpAnalyzer.analyzeQuery(
                                qs.query(), new QueryScope(null, ctx.withDynamicSql(rawSrc, ctx.vendor())),
                                ctx.withDynamicSql(rawSrc, ctx.vendor()), false, ResultSetType.select_list, null, eff);
                    }
                }
            } else {
                srcObj = bindTableStageOrPath(stmt.sourceObject(), false, stmt.span(), ctx);
            }
        }
        if (srcObj == null || target == null) {
            return;
        }
        List<BoundColumn> srcCols = srcObj.orderedColumns();
        if (!srcCols.isEmpty()) {
            wireRsToTarget(ctx, srcObj, target, stmt.targetColumns(), null, eff, ctx.controlPredicates());
        } else if (stmt.targetColumns() != null && !stmt.targetColumns().isEmpty()) {
            for (String tcName : stmt.targetColumns()) {
                BoundColumn sc = srcObj.getOrCreateColumn(ctx.mgr(), tcName, false, stmt.span(), ctx.sqlHash());
                BoundColumn tc = target.getOrCreateColumn(ctx.mgr(), tcName, false, stmt.span(), ctx.sqlHash());
                ctx.mgr().addColumnEdge(
                        RelationshipType.fdd, eff, target, tc,
                        List.of(ctx.toSourceRef(srcObj, sc, null, "select", stmt.span())),
                        ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
            }
        } else {
            BoundColumn sc = srcObj.getOrCreateColumn(ctx.mgr(), "*", false, stmt.span(), ctx.sqlHash());
            BoundColumn tc = target.getOrCreateColumn(ctx.mgr(), "*", false, stmt.span(), ctx.sqlHash());
            ctx.mgr().addColumnEdge(
                    RelationshipType.fdd, eff, target, tc,
                    List.of(ctx.toSourceRef(srcObj, sc, null, "select", stmt.span())),
                    ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
        }
    }

    private static void applyAssignmentItem(
            AssignmentItem ai,
            BoundObject primaryTarget,
            BoundObject stagingRs,
            EffectType eff,
            QueryScope scope,
            List<BoundSourceRef> indirect,
            AnalyzerContext ctx) {
        if (ai.targetColumns() == null || ai.targetColumns().isEmpty()) {
            return;
        }
        if (ai.tupleSubquery() != null) {
            BoundObject subRs = SelectAndSetOpAnalyzer.analyzeQuery(
                    ai.tupleSubquery(), scope, ctx, false, ResultSetType.update_select, "UPDATE-SELECT", eff);
            List<BoundColumn> subCols = subRs != null ? subRs.orderedColumns() : List.of();
            for (int i = 0; i < ai.targetColumns().size(); i++) {
                QualifiedName tQn = ai.targetColumns().get(i);
                BoundObject tgtObj = resolveUpdateTargetObject(tQn, primaryTarget, scope);
                BoundColumn stgCol = stagingRs.getOrCreateColumn(ctx.mgr(), tQn.lastName(), tQn.lastQuoted(), tQn.span(), ctx.sqlHash());
                BoundColumn tgtCol = tgtObj.getOrCreateColumn(ctx.mgr(), tQn.lastName(), tQn.lastQuoted(), tQn.span(), ctx.sqlHash());
                if (i < subCols.size()) {
                    ctx.mgr().addColumnEdge(
                            RelationshipType.fdd, eff, stagingRs, stgCol,
                            List.of(ctx.toSourceRef(subRs, subCols.get(i), null, "update", ai.span())),
                            ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
                }
                SelectAndSetOpAnalyzer.emitIndirectEdges(ctx, eff, stagingRs, stgCol, indirect, null);
                ctx.mgr().addColumnEdge(
                        RelationshipType.fdd, eff, tgtObj, tgtCol,
                        List.of(ctx.toSourceRef(stagingRs, stgCol, null, "update", ai.span())),
                        ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
            }
            return;
        }
        EvalResult val = ExpressionAnalyzer.evaluate(ai.valueExpr(), scope, "update", null);
        for (QualifiedName tQn : ai.targetColumns()) {
            BoundObject tgtObj = resolveUpdateTargetObject(tQn, primaryTarget, scope);
            BoundColumn stgCol = stagingRs.getOrCreateColumn(ctx.mgr(), tQn.lastName(), tQn.lastQuoted(), tQn.span(), ctx.sqlHash());
            BoundColumn tgtCol = tgtObj.getOrCreateColumn(ctx.mgr(), tQn.lastName(), tQn.lastQuoted(), tQn.span(), ctx.sqlHash());
            if (!val.direct().isEmpty()) {
                List<BoundSourceRef> txRefs = new ArrayList<>(val.direct().size());
                for (BoundSourceRef d : val.direct()) {
                    txRefs.add(ExpressionAnalyzer.attachExprTransform(
                            d, val.transformType(), ai.valueExpr() != null ? ai.valueExpr().span() : ai.span(), ai.rawCode(), ctx));
                }
                ctx.mgr().addColumnEdge(
                        RelationshipType.fdd, eff, stagingRs, stgCol, txRefs,
                        ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), val.functionName(), null, null, null);
            }
            List<BoundSourceRef> allInd = new ArrayList<>(val.indirect());
            allInd.addAll(indirect);
            SelectAndSetOpAnalyzer.emitIndirectEdges(ctx, eff, stagingRs, stgCol, allInd, val.functionName());
            ctx.mgr().addColumnEdge(
                    RelationshipType.fdd, eff, tgtObj, tgtCol,
                    List.of(ctx.toSourceRef(stagingRs, stgCol, null, "update", ai.span())),
                    ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
        }
    }

    private static BoundObject resolveUpdateTargetObject(QualifiedName colQn, BoundObject fallback, QueryScope scope) {
        if (colQn.size() >= 2) {
            QualifiedName qual = new QualifiedName(
                    colQn.parts().subList(0, colQn.size() - 1), null, false, false, false, colQn.span());
            ScopeEntry se = scope.matchTableQualifier(qual);
            if (se != null) {
                se.object().setTarget(true);
                return se.object();
            }
        }
        return fallback;
    }

    private static void handleOutputClause(OutputClause out, BoundObject dmlTarget, QueryScope baseScope, AnalyzerContext ctx) {
        if (out == null || out.items() == null || out.items().isEmpty()) {
            return;
        }
        QueryScope outScope = new QueryScope(baseScope, ctx);
        outScope.addEntry(new ScopeEntry("INSERTED", false, dmlTarget, null, null, !dmlTarget.orderedColumns().isEmpty()));
        outScope.addEntry(new ScopeEntry("DELETED", false, dmlTarget, null, null, !dmlTarget.orderedColumns().isEmpty()));
        BoundObject outRs = ctx.mgr().createResultSet(
                ResultSetType.output, "OUTPUT", out.intoTable() == null, ctx.routineId(), ctx.sqlHash(), out.span());
        for (SelectItem item : out.items()) {
            if (item.expr() instanceof Expr.StarExpr star) {
                for (var ec : QueryScopeResolver.expandStar(outScope, star, outRs, EffectType.output)) {
                    BoundColumn oc = outRs.getOrCreateColumn(ctx.mgr(), ec.outputName(), false, item.span(), ctx.sqlHash());
                    ctx.mgr().addColumnEdge(
                            RelationshipType.fdd, EffectType.output, outRs, oc, List.of(ec.sourceRef()),
                            ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
                }
            } else {
                BoundColumn oc = outRs.getOrCreateColumn(ctx.mgr(), item.effectiveOutputName(), false, item.span(), ctx.sqlHash());
                EvalResult er = ExpressionAnalyzer.evaluate(item.expr(), outScope, "output", null);
                if (!er.direct().isEmpty()) {
                    ctx.mgr().addColumnEdge(
                            RelationshipType.fdd, EffectType.output, outRs, oc, er.direct(),
                            ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), er.functionName(), null, null, null);
                }
            }
        }
        if (out.intoTable() != null && !out.intoTable().isEmpty()) {
            BoundObject intoObj = out.intoTable().lastName().startsWith("@")
                    ? ctx.bindVariable(out.intoTable().lastName(), out.span())
                    : ctx.mgr().bindTableOrView(
                            out.intoTable(), ObjectCategory.TABLE, EndpointIntroduction.CATALOG_REFERENCE,
                            true, false, false, ctx.sqlHash());
            if (intoObj != null) {
                SelectAndSetOpAnalyzer.wirePositionalFlow(ctx, outRs, intoObj, out.intoColumns(), EffectType.output);
            }
        }
    }

    public static void wireRsToTarget(
            AnalyzerContext ctx,
            BoundObject srcRs,
            BoundObject target,
            List<String> targetColNames,
            List<SourceSpan> targetColSpans,
            EffectType eff,
            List<BoundSourceRef> extraIndirect) {
        List<BoundColumn> srcCols = srcRs.orderedColumns();
        for (int i = 0; i < srcCols.size(); i++) {
            BoundColumn sc = srcCols.get(i);
            String tgtName = (targetColNames != null && i < targetColNames.size())
                    ? targetColNames.get(i)
                    : sc.getName();
            SourceSpan sp = (targetColSpans != null && i < targetColSpans.size()) ? targetColSpans.get(i) : null;
            BoundColumn tc = target.getOrCreateColumn(ctx.mgr(), tgtName, false, sp, ctx.sqlHash());
            ctx.mgr().addColumnEdge(
                    RelationshipType.fdd, eff, target, tc,
                    List.of(ctx.toSourceRef(srcRs, sc, null, "insert", sp)),
                    ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
            SelectAndSetOpAnalyzer.emitIndirectEdges(ctx, eff, target, tc, extraIndirect, null);
        }
    }

    private static List<String> resolveTargetColumnNames(
            QualifiedName targetQn, List<String> explicitCols, BoundObject targetObj, AnalyzerContext ctx) {
        if (explicitCols != null && !explicitCols.isEmpty()) {
            return explicitCols;
        }
        CatalogTable ct = ctx.env().resolveTable(targetQn);
        if (ct != null && !ct.orderedColumns().isEmpty()) {
            List<String> names = new ArrayList<>(ct.orderedColumns().size());
            for (CatalogColumn cc : ct.orderedColumns()) {
                names.add(cc.getName());
            }
            return names;
        }
        List<String> fromObj = new ArrayList<>();
        for (BoundColumn bc : targetObj.orderedColumns()) {
            if (!"*".equals(bc.getName()) && !"RelationRows".equalsIgnoreCase(bc.getName())) {
                fromObj.add(bc.getName());
            }
        }
        return fromObj;
    }

    private static BoundObject bindTableStageOrPath(QualifiedName qn, boolean isTarget, SourceSpan span, AnalyzerContext ctx) {
        if (qn == null || qn.isEmpty()) {
            return null;
        }
        String raw = qn.lastName();
        if (qn.stageRef() || (raw != null && raw.startsWith("@"))) {
            return ctx.mgr().bindPathOrStageObject(raw, ObjectCategory.STAGE, "stage", span, isTarget, ctx.sqlHash());
        }
        if (qn.uriRef() || QualifiedName.isExternalUri(raw) || (raw != null && (raw.contains("/") || raw.contains("\\")))) {
            return ctx.mgr().bindPathOrStageObject(raw, ObjectCategory.PATH, "path", span, isTarget, ctx.sqlHash());
        }
        return ctx.mgr().bindTableOrView(
                qn, ObjectCategory.TABLE, EndpointIntroduction.CATALOG_REFERENCE, isTarget, false, false, ctx.sqlHash());
    }

    public static void emitCrudWriteIfRequested(BoundObject target, EffectType eff, AnalyzerContext ctx, SourceSpan span) {
        if (target != null && (ctx.option().getAnalyzeMode() == AnalyzeMode.crud
                || ctx.option().getFilterRelationTypes().contains(RelationshipType.crud.name()))) {
            BoundColumn rr = target.getOrCreateRelationRows(ctx.mgr());
            BoundObject src = ctx.mgr().createResultSet(
                    ResultSetType.select_list, "CRUD_STMT", false, ctx.routineId(), ctx.sqlHash(), span);
            BoundColumn sc = src.getOrCreateColumn(ctx.mgr(), eff.name(), false, span, ctx.sqlHash());
            ctx.mgr().addColumnEdge(
                    RelationshipType.crud, eff, target, rr,
                    List.of(ctx.toSourceRef(src, sc, null, eff.name(), span)),
                    ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
        }
    }
}
