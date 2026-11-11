package io.sqlflow.lineage.analyzer;

import io.sqlflow.contracts.lineage.AnalyzeMode;
import io.sqlflow.contracts.lineage.EffectType;
import io.sqlflow.contracts.lineage.EndpointIntroduction;
import io.sqlflow.contracts.lineage.RelationshipType;
import io.sqlflow.contracts.lineage.ResultSetType;
import io.sqlflow.contracts.vendor.DataObjectType;
import io.sqlflow.lineage.analyzer.ExpressionAnalyzer.EvalResult;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundColumn;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundObject;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundSourceRef;
import io.sqlflow.lineage.analyzer.ModelBindingManager.ObjectCategory;
import io.sqlflow.lineage.analyzer.QueryScopeResolver.ExpandedColumn;
import io.sqlflow.lineage.analyzer.QueryScopeResolver.QueryScope;
import io.sqlflow.lineage.analyzer.QueryScopeResolver.ScopeEntry;
import io.sqlflow.lineage.ast.Expr;
import io.sqlflow.lineage.ast.QualifiedName;
import io.sqlflow.lineage.ast.QueryAst.CteDef;
import io.sqlflow.lineage.ast.QueryAst.JoinClause;
import io.sqlflow.lineage.ast.QueryAst.LateralViewClause;
import io.sqlflow.lineage.ast.QueryAst.NamedTableSource;
import io.sqlflow.lineage.ast.QueryAst.NamedWindowDef;
import io.sqlflow.lineage.ast.QueryAst.OrderByItem;
import io.sqlflow.lineage.ast.QueryAst.ParenthesizedJoinSource;
import io.sqlflow.lineage.ast.QueryAst.PivotAggItem;
import io.sqlflow.lineage.ast.QueryAst.PivotSpec;
import io.sqlflow.lineage.ast.QueryAst.PivotValueItem;
import io.sqlflow.lineage.ast.QueryAst.QueryExpr;
import io.sqlflow.lineage.ast.QueryAst.SelectItem;
import io.sqlflow.lineage.ast.QueryAst.SelectQuery;
import io.sqlflow.lineage.ast.QueryAst.SetOpQuery;
import io.sqlflow.lineage.ast.QueryAst.SubqueryTableSource;
import io.sqlflow.lineage.ast.QueryAst.TableFunctionSource;
import io.sqlflow.lineage.ast.QueryAst.TableSource;
import io.sqlflow.lineage.ast.QueryAst.UnpivotSpec;
import io.sqlflow.lineage.ast.QueryAst.ValuesQuery;
import io.sqlflow.lineage.ast.QueryAst.WindowSpec;
import io.sqlflow.lineage.ast.SqlStmt.ColumnDef;
import io.sqlflow.lineage.lexer.SourceSpan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Semantic analyzer for {@code SELECT}, {@code WITH} CTEs, {@code JOIN}s, {@code PIVOT}/{@code UNPIVOT},
 * {@code LATERAL VIEW}/{@code UNNEST}/{@code FLATTEN}, {@code UNION}/{@code INTERSECT}/{@code EXCEPT},
 * and {@code VALUES} queries ({@code OBL-037..OBL-041}).
 */
public final class SelectAndSetOpAnalyzer {

    private SelectAndSetOpAnalyzer() {
    }

    public static BoundObject analyzeQuery(
            QueryExpr query,
            QueryScope parentScope,
            AnalyzerContext ctx,
            boolean topLevelSelect,
            ResultSetType rsType,
            String customRsName,
            EffectType effectType) {
        if (query == null) {
            return null;
        }
        if (query instanceof SelectQuery sq) {
            return analyzeSelectQuery(sq, parentScope, ctx, topLevelSelect, rsType, customRsName, effectType);
        }
        if (query instanceof SetOpQuery setOp) {
            return analyzeSetOpQuery(setOp, parentScope, ctx, topLevelSelect, customRsName, effectType);
        }
        if (query instanceof ValuesQuery vq) {
            return analyzeValuesQuery(vq, parentScope, ctx, customRsName);
        }
        return null;
    }

    private static BoundObject analyzeSelectQuery(
            SelectQuery sq,
            QueryScope parentScope,
            AnalyzerContext ctx,
            boolean topLevelSelect,
            ResultSetType rsType,
            String customRsName,
            EffectType effectType) {
        QueryScope scope = new QueryScope(parentScope, ctx);
        registerCtes(sq.ctes(), scope, ctx);

        List<BoundSourceRef> queryIndirect = new ArrayList<>(ctx.controlPredicates());

        // 1. FROM sources & JOINs
        if (sq.fromSources() != null) {
            for (TableSource ts : sq.fromSources()) {
                ScopeEntry entry = analyzeTableSource(ts, scope, ctx, queryIndirect);
                scope.addEntry(entry);
                emitCrudReadIfRequested(entry, ctx, ts.span());
            }
        }
        if (sq.joins() != null) {
            for (JoinClause jc : sq.joins()) {
                analyzeJoinClause(jc, scope, ctx, queryIndirect);
            }
        }
        if (sq.lateralViews() != null) {
            for (LateralViewClause lv : sq.lateralViews()) {
                analyzeLateralView(lv, scope, ctx);
            }
        }

        // 2. Named windows
        Map<String, WindowSpec> namedWindows = new LinkedHashMap<>();
        if (sq.windowDefs() != null) {
            for (NamedWindowDef nw : sq.windowDefs()) {
                if (nw.name() != null && nw.spec() != null) {
                    namedWindows.put(nw.name().toUpperCase(Locale.ROOT), nw.spec());
                }
            }
        }

        // 3. Evaluate SELECT items first so selectAliases are available to GROUP BY / HAVING / QUALIFY / ORDER BY
        EffectType eff = effectType != null ? effectType : EffectType.select;
        BoundObject targetRs = ctx.mgr().createResultSet(
                rsType != null ? rsType : ResultSetType.select_list,
                customRsName,
                topLevelSelect,
                ctx.routineId(),
                ctx.sqlHash(),
                sq.span());

        record ProjectedColumn(
                BoundColumn col,
                List<BoundSourceRef> direct,
                List<BoundSourceRef> itemIndirect,
                String fnName
        ) {
        }
        List<ProjectedColumn> projected = new ArrayList<>();
        if (sq.selectItems() != null) {
            int itemIdx = 0;
            for (SelectItem item : sq.selectItems()) {
                itemIdx++;
                if (item.expr() instanceof Expr.StarExpr star) {
                    List<ExpandedColumn> expanded = QueryScopeResolver.expandStar(scope, star, targetRs, eff);
                    for (ExpandedColumn ec : expanded) {
                        BoundColumn outCol = targetRs.getOrCreateColumn(
                                ctx.mgr(), ec.outputName(), false, item.span(), ctx.sqlHash());
                        if (ec.replaceExpr() != null) {
                            EvalResult rep = ExpressionAnalyzer.evaluate(
                                    ec.replaceExpr(), scope, "select", namedWindows);
                            projected.add(new ProjectedColumn(outCol, rep.direct(), rep.indirect(), rep.functionName()));
                        } else {
                            projected.add(new ProjectedColumn(outCol, List.of(ec.sourceRef()), List.of(), null));
                        }
                    }
                } else {
                    String outName = item.effectiveOutputName();
                    if ("EXPR".equals(outName)) {
                        outName = "COL_" + itemIdx;
                    }
                    BoundColumn outCol = targetRs.getOrCreateColumn(
                            ctx.mgr(),
                            outName,
                            item.aliasQuoted(),
                            item.aliasSpan() != null ? item.aliasSpan() : item.span(),
                            ctx.sqlHash());
                    EvalResult eval = ExpressionAnalyzer.evaluate(item.expr(), scope, "select", namedWindows);
                    List<BoundSourceRef> txDirect = new ArrayList<>(eval.direct().size());
                    for (BoundSourceRef d : eval.direct()) {
                        txDirect.add(ExpressionAnalyzer.attachExprTransform(
                                d, eval.transformType(), item.expr().span(), item.rawCode(), ctx));
                    }
                    if (item.alias() != null && !item.alias().isBlank()) {
                        scope.registerSelectAlias(item.alias(), item.aliasQuoted(),
                                !txDirect.isEmpty() ? txDirect : eval.indirect());
                    }
                    projected.add(new ProjectedColumn(outCol, txDirect, eval.indirect(), eval.functionName()));
                }
            }
        }

        // 4. Filter / grouping / ordering indirect clauses -> fdr
        if (sq.whereExpr() != null) {
            EvalResult w = ExpressionAnalyzer.evaluate(sq.whereExpr(), scope, "where", namedWindows);
            queryIndirect.addAll(w.allRefsWithClause("where"));
            if (ctx.option().isShowJoin()) {
                extractEqualityJoins(sq.whereExpr(), "inner", "where", scope, ctx);
            }
        }
        if (sq.connectBy() != null) {
            if (sq.connectBy().startWith() != null) {
                queryIndirect.addAll(ExpressionAnalyzer.evaluate(
                        sq.connectBy().startWith(), scope, "startwith", namedWindows).allRefsWithClause("startwith"));
            }
            if (sq.connectBy().connectBy() != null) {
                queryIndirect.addAll(ExpressionAnalyzer.evaluate(
                        sq.connectBy().connectBy(), scope, "connectby", namedWindows).allRefsWithClause("connectby"));
            }
        }
        if (sq.groupByExprs() != null) {
            for (Expr g : sq.groupByExprs()) {
                if (!(g instanceof Expr.LiteralExpr)) {
                    queryIndirect.addAll(ExpressionAnalyzer.evaluate(
                            g, scope, "groupby", namedWindows).allRefsWithClause("groupby"));
                }
            }
            if (!sq.groupByExprs().isEmpty()
                    && ctx.option().getFilterRelationTypes().contains(RelationshipType.frd.name())) {
                SourceSpan groupSpan = SourceSpan.between(
                        sq.groupByExprs().get(0).span(),
                        sq.groupByExprs().get(sq.groupByExprs().size() - 1).span());
                SourceSpan effectiveGroupSpan = groupSpan.isValid() ? groupSpan : sq.span();
                for (ScopeEntry entry : scope.entries()) {
                    BoundColumn rr = entry.object().getOrCreateRelationRows(ctx.mgr());
                    queryIndirect.add(ctx.toSourceRef(entry.object(), rr, entry.alias(), "groupby", effectiveGroupSpan));
                }
            }
        }
        if (sq.havingExpr() != null) {
            queryIndirect.addAll(ExpressionAnalyzer.evaluate(
                    sq.havingExpr(), scope, "having", namedWindows).allRefsWithClause("having"));
        }
        if (sq.qualifyExpr() != null) {
            queryIndirect.addAll(ExpressionAnalyzer.evaluate(
                    sq.qualifyExpr(), scope, "qualify", namedWindows).allRefsWithClause("qualify"));
        }
        if (sq.orderBy() != null) {
            for (OrderByItem ob : sq.orderBy()) {
                if (!(ob.expr() instanceof Expr.LiteralExpr)) {
                    queryIndirect.addAll(ExpressionAnalyzer.evaluate(
                            ob.expr(), scope, "orderby", namedWindows).allRefsWithClause("orderby"));
                }
            }
        }

        // 5. Emit column edges onto targetRs
        for (ProjectedColumn pc : projected) {
            if (!pc.direct().isEmpty()) {
                ctx.mgr().addColumnEdge(
                        RelationshipType.fdd, eff, targetRs, pc.col(), pc.direct(),
                        ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), pc.fnName(), null, null, null);
            }
            List<BoundSourceRef> allInd = new ArrayList<>(pc.itemIndirect().size() + queryIndirect.size());
            allInd.addAll(pc.itemIndirect());
            allInd.addAll(queryIndirect);
            emitIndirectEdges(ctx, eff, targetRs, pc.col(), allInd, pc.fnName());
        }

        // 6. SELECT ... INTO targetTables / targetVariables / DIRECTORY
        if (sq.intoClause() != null) {
            handleIntoClause(sq.intoClause(), targetRs, ctx);
        }
        return targetRs;
    }

    public static void registerCtes(List<CteDef> ctes, QueryScope scope, AnalyzerContext ctx) {
        if (ctes == null || ctes.isEmpty()) {
            return;
        }
        for (CteDef cte : ctes) {
            String cteName = "CTE-" + cte.name();
            if (cte.recursive()) {
                BoundObject preCte = ctx.mgr().createResultSet(
                        ResultSetType.cte, cteName, false, ctx.routineId(), ctx.sqlHash(), cte.span());
                preCte.setDisplayName(cte.name());
                if (cte.columnAliases() != null) {
                    for (String ca : cte.columnAliases()) {
                        preCte.getOrCreateColumn(ctx.mgr(), ca, false, cte.nameSpan(), ctx.sqlHash());
                    }
                }
                scope.registerCte(cte.name(), cte.nameQuoted(), preCte);
                BoundObject evaluated = analyzeQuery(
                        cte.query(), scope, ctx, false, ResultSetType.select_list, null, EffectType.select);
                if (evaluated != null) {
                    wirePositionalFlow(ctx, evaluated, preCte, cte.columnAliases(), EffectType.select);
                }
            } else {
                BoundObject cteRs = analyzeQuery(
                        cte.query(), scope, ctx, false, ResultSetType.cte, cteName, EffectType.select);
                if (cteRs != null) {
                    cteRs.setDisplayName(cte.name());
                    if (cte.columnAliases() != null && !cte.columnAliases().isEmpty()) {
                        List<BoundColumn> cols = cteRs.orderedColumns();
                        for (int i = 0; i < Math.min(cte.columnAliases().size(), cols.size()); i++) {
                            cols.get(i).setDisplayName(cte.columnAliases().get(i));
                        }
                    }
                    scope.registerCte(cte.name(), cte.nameQuoted(), cteRs);
                }
            }
        }
    }

    public static ScopeEntry analyzeTableSource(
            TableSource ts, QueryScope scope, AnalyzerContext ctx, List<BoundSourceRef> queryIndirect) {
        if (ts instanceof NamedTableSource nts) {
            ScopeEntry base = QueryScopeResolver.bindNamedTableSource(
                    scope, nts.tableName(), nts.alias(), nts.aliasQuoted(), nts.columnAliases(), false);
            return applyPivotOrUnpivot(base, nts.pivot(), nts.unpivot(), scope, ctx);
        }
        if (ts instanceof SubqueryTableSource sts) {
            String rsName = sts.alias() != null && !sts.alias().isBlank() ? sts.alias() : null;
            BoundObject subRs = analyzeQuery(
                    sts.query(), scope, ctx, false, ResultSetType.select_list, rsName, EffectType.select);
            ScopeEntry base = new ScopeEntry(
                    sts.alias(), sts.aliasQuoted(), subRs, null, sts.columnAliases(), !subRs.orderedColumns().isEmpty());
            return applyPivotOrUnpivot(base, sts.pivot(), sts.unpivot(), scope, ctx);
        }
        if (ts instanceof TableFunctionSource tfs) {
            return analyzeTableFunctionSource(tfs, scope, ctx);
        }
        if (ts instanceof ParenthesizedJoinSource pjs) {
            ScopeEntry left = analyzeTableSource(pjs.left(), scope, ctx, queryIndirect);
            scope.addEntry(left);
            if (pjs.joins() != null) {
                for (JoinClause jc : pjs.joins()) {
                    analyzeJoinClause(jc, scope, ctx, queryIndirect);
                }
            }
            return left;
        }
        throw new IllegalStateException("Unsupported TableSource: " + ts);
    }

    public static void analyzeJoinClause(
            JoinClause jc, QueryScope scope, AnalyzerContext ctx, List<BoundSourceRef> queryIndirect) {
        List<ScopeEntry> leftSnapshot = new ArrayList<>(scope.entries());
        ScopeEntry rightEntry = analyzeTableSource(jc.right(), scope, ctx, queryIndirect);
        scope.addEntry(rightEntry);
        emitCrudReadIfRequested(rightEntry, ctx, jc.right().span());

        String jType = jc.joinType() != null ? jc.joinType().toLowerCase(Locale.ROOT) : "inner";
        if (jc.onCondition() != null) {
            EvalResult onRes = ExpressionAnalyzer.evaluate(jc.onCondition(), scope, "join", null);
            queryIndirect.addAll(onRes.allRefsWithClause("on"));
            if (ctx.option().isShowJoin()) {
                extractEqualityJoins(jc.onCondition(), jType, "on", scope, ctx);
            }
        } else if (jc.usingColumns() != null && !jc.usingColumns().isEmpty()) {
            for (String uCol : jc.usingColumns()) {
                BoundColumn rCol = rightEntry.object().getOrCreateColumn(ctx.mgr(), uCol, false, jc.span(), ctx.sqlHash());
                BoundSourceRef rRef = ctx.toSourceRef(rightEntry.object(), rCol, rightEntry.alias(), "join", jc.span());
                queryIndirect.add(rRef);
                for (ScopeEntry left : leftSnapshot) {
                    BoundColumn lCol = left.object().getOrCreateColumn(ctx.mgr(), uCol, false, jc.span(), ctx.sqlHash());
                    BoundSourceRef lRef = ctx.toSourceRef(left.object(), lCol, left.alias(), "join", jc.span());
                    queryIndirect.add(lRef);
                    if (ctx.option().isShowJoin()) {
                        ctx.mgr().addColumnEdge(
                                RelationshipType.join, EffectType.join, rightEntry.object(), rCol, List.of(lRef),
                                ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, "using", jType, "USING(" + uCol + ")");
                    }
                }
            }
        } else if (jType.contains("natural") && !leftSnapshot.isEmpty()) {
            ScopeEntry left = leftSnapshot.get(leftSnapshot.size() - 1);
            for (BoundColumn lCol : left.object().orderedColumns()) {
                BoundColumn rCol = rightEntry.object().findColumn(ctx.env(), lCol.getName(), false);
                if (rCol != null) {
                    BoundSourceRef lRef = ctx.toSourceRef(left.object(), lCol, left.alias(), "join", jc.span());
                    queryIndirect.add(lRef);
                    if (ctx.option().isShowJoin()) {
                        ctx.mgr().addColumnEdge(
                                RelationshipType.join, EffectType.join, rightEntry.object(), rCol, List.of(lRef),
                                ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, "natural", jType, null);
                    }
                }
            }
        }
    }

    private static ScopeEntry analyzeTableFunctionSource(TableFunctionSource tfs, QueryScope scope, AnalyzerContext ctx) {
        String kind = tfs.kind() != null ? tfs.kind().toUpperCase(Locale.ROOT) : "FUNCTION";
        ResultSetType rsType = "UNNEST".equals(kind) ? ResultSetType.array
                : ("FLATTEN".equals(kind) ? ResultSetType.struct : ResultSetType.result_of);
        String fnName = tfs.funcName() != null ? tfs.funcName().lastName() : kind;
        String rsName = tfs.alias() != null && !tfs.alias().isBlank() ? tfs.alias() : "RESULT_OF_" + fnName;
        BoundObject rs = ctx.mgr().createResultSet(rsType, rsName, false, ctx.routineId(), ctx.sqlHash(), tfs.span());
        if ("UNNEST".equals(kind)) {
            rs.setSubType("unnest");
        }

        List<BoundSourceRef> inputRefs = new ArrayList<>();
        if (tfs.inputExpr() != null) {
            inputRefs.addAll(ExpressionAnalyzer.evaluate(tfs.inputExpr(), scope, "select", null).direct());
        }
        if (tfs.args() != null) {
            for (Expr arg : tfs.args()) {
                inputRefs.addAll(ExpressionAnalyzer.evaluate(arg, scope, "select", null).direct());
            }
        }

        // Check pipelined/TVF stitching from local routines
        if (ctx.option().isEnablePipelinedStitching() && tfs.funcName() != null) {
            BoundObject stitched = ProcAndDynamicSqlAnalyzer.tryStitchTableFunction(tfs, inputRefs, ctx);
            if (stitched != null) {
                return new ScopeEntry(tfs.alias(), false, stitched, null, tfs.columnAliases(), !stitched.orderedColumns().isEmpty());
            }
        }

        List<String> outCols = new ArrayList<>();
        if (tfs.explicitColumns() != null && !tfs.explicitColumns().isEmpty()) {
            for (ColumnDef cd : tfs.explicitColumns()) {
                outCols.add(cd.name());
            }
        } else if (tfs.columnAliases() != null && !tfs.columnAliases().isEmpty()) {
            outCols.addAll(tfs.columnAliases());
        } else if ("FLATTEN".equals(kind)) {
            outCols.addAll(List.of("SEQ", "KEY", "PATH", "INDEX", "VALUE", "THIS"));
        } else {
            outCols.add(tfs.alias() != null && !tfs.alias().isBlank() ? tfs.alias() : "VALUE");
        }
        if (tfs.withOffset()) {
            outCols.add(tfs.offsetAlias() != null && !tfs.offsetAlias().isBlank() ? tfs.offsetAlias() : "OFFSET");
        }
        for (String colName : outCols) {
            BoundColumn bc = rs.getOrCreateColumn(ctx.mgr(), colName, false, tfs.span(), ctx.sqlHash());
            if (!inputRefs.isEmpty()) {
                ctx.mgr().addColumnEdge(
                        RelationshipType.fdd, EffectType.select, rs, bc, inputRefs,
                        ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), fnName, null, null, null);
            }
        }
        return new ScopeEntry(tfs.alias(), false, rs, null, tfs.columnAliases(), true);
    }

    private static void analyzeLateralView(LateralViewClause lv, QueryScope scope, AnalyzerContext ctx) {
        String fnName = lv.funcName() != null ? lv.funcName().lastName() : "EXPLODE";
        String rsName = lv.tableAlias() != null && !lv.tableAlias().isBlank() ? lv.tableAlias() : "RESULT_OF_" + fnName;
        BoundObject lvRs = ctx.mgr().createResultSet(
                ResultSetType.function, rsName, false, ctx.routineId(), ctx.sqlHash(), lv.span());
        lvRs.setBuiltInFunction(AnalyzerContext.isBuiltInFunction(fnName));
        List<BoundSourceRef> refs = new ArrayList<>();
        if (lv.args() != null) {
            for (Expr arg : lv.args()) {
                refs.addAll(ExpressionAnalyzer.evaluate(arg, scope, "select", null).direct());
            }
        }
        List<String> colNames = (lv.columnAliases() != null && !lv.columnAliases().isEmpty())
                ? lv.columnAliases()
                : List.of("col");
        for (String cn : colNames) {
            BoundColumn col = lvRs.getOrCreateColumn(ctx.mgr(), cn, false, lv.span(), ctx.sqlHash());
            if (!refs.isEmpty()) {
                ctx.mgr().addColumnEdge(
                        RelationshipType.fdd, EffectType.select, lvRs, col, refs,
                        ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), fnName, null, null, null);
            }
        }
        scope.addEntry(new ScopeEntry(lv.tableAlias(), false, lvRs, null, lv.columnAliases(), true));
    }

    private static ScopeEntry applyPivotOrUnpivot(
            ScopeEntry base, PivotSpec pivot, UnpivotSpec unpivot, QueryScope outerScope, AnalyzerContext ctx) {
        if (pivot == null && unpivot == null) {
            return base;
        }
        QueryScope pScope = new QueryScope(outerScope, ctx);
        pScope.addEntry(base);
        if (pivot != null) {
            BoundObject pivRs = ctx.mgr().createResultSet(
                    ResultSetType.pivot_table, "PIVOT-TABLE", false, ctx.routineId(), ctx.sqlHash(), pivot.span());
            List<BoundSourceRef> forRefs = new ArrayList<>();
            if (pivot.forColumns() != null) {
                for (String fc : pivot.forColumns()) {
                    BoundColumn col = base.object().getOrCreateColumn(ctx.mgr(), fc, false, pivot.span(), ctx.sqlHash());
                    forRefs.add(ctx.toSourceRef(base.object(), col, base.alias(), "where", pivot.span()));
                }
            }
            for (BoundColumn bc : base.object().orderedColumns()) {
                boolean isFor = pivot.forColumns() != null && pivot.forColumns().stream()
                        .anyMatch(f -> ctx.env().identifiersMatch(DataObjectType.COLUMN, f, false, bc.getName(), false));
                if (!isFor) {
                    BoundColumn out = pivRs.getOrCreateColumn(ctx.mgr(), bc.getName(), false, pivot.span(), ctx.sqlHash());
                    ctx.mgr().addColumnEdge(
                            RelationshipType.fdd, EffectType.select, pivRs, out,
                            List.of(ctx.toSourceRef(base.object(), bc, base.alias(), "select", pivot.span())),
                            ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
                }
            }
            if (pivot.inValues() != null && pivot.aggregates() != null) {
                for (PivotValueItem pv : pivot.inValues()) {
                    String valLabel = pv.alias() != null && !pv.alias().isBlank()
                            ? pv.alias()
                            : ctx.extractSql(pv.span());
                    for (PivotAggItem agg : pivot.aggregates()) {
                        String colName = agg.alias() != null && !agg.alias().isBlank()
                                ? valLabel + "_" + agg.alias()
                                : valLabel;
                        BoundColumn pCol = pivRs.getOrCreateColumn(ctx.mgr(), colName, false, pv.span(), ctx.sqlHash());
                        EvalResult aggRes = ExpressionAnalyzer.evaluate(agg.aggExpr(), pScope, "select", null);
                        if (!aggRes.direct().isEmpty()) {
                            ctx.mgr().addColumnEdge(
                                    RelationshipType.fdd, EffectType.select, pivRs, pCol, aggRes.direct(),
                                    ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), aggRes.functionName(), null, null, null);
                        }
                        List<BoundSourceRef> ind = new ArrayList<>(aggRes.indirect());
                        ind.addAll(forRefs);
                        emitIndirectEdges(ctx, EffectType.select, pivRs, pCol, ind, aggRes.functionName());
                    }
                }
            }
            String alias = pivot.alias() != null ? pivot.alias() : base.alias();
            return new ScopeEntry(alias, false, pivRs, null, null, true);
        }
        BoundObject unpivRs = ctx.mgr().createResultSet(
                ResultSetType.unpivot_table, "UNPIVOT-TABLE", false, ctx.routineId(), ctx.sqlHash(), unpivot.span());
        List<BoundSourceRef> inColRefs = new ArrayList<>();
        if (unpivot.inSourceColumns() != null) {
            for (String sc : unpivot.inSourceColumns()) {
                BoundColumn col = base.object().getOrCreateColumn(ctx.mgr(), sc, false, unpivot.span(), ctx.sqlHash());
                inColRefs.add(ctx.toSourceRef(base.object(), col, base.alias(), "select", unpivot.span()));
            }
        }
        for (BoundColumn bc : base.object().orderedColumns()) {
            boolean unpivoted = unpivot.inSourceColumns() != null && unpivot.inSourceColumns().stream()
                    .anyMatch(s -> ctx.env().identifiersMatch(DataObjectType.COLUMN, s, false, bc.getName(), false));
            if (!unpivoted) {
                BoundColumn pass = unpivRs.getOrCreateColumn(ctx.mgr(), bc.getName(), false, unpivot.span(), ctx.sqlHash());
                ctx.mgr().addColumnEdge(
                        RelationshipType.fdd, EffectType.select, unpivRs, pass,
                        List.of(ctx.toSourceRef(base.object(), bc, base.alias(), "select", unpivot.span())),
                        ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
            }
        }
        if (unpivot.valueColumns() != null) {
            for (String vc : unpivot.valueColumns()) {
                BoundColumn vCol = unpivRs.getOrCreateColumn(ctx.mgr(), vc, false, unpivot.span(), ctx.sqlHash());
                if (!inColRefs.isEmpty()) {
                    ctx.mgr().addColumnEdge(
                            RelationshipType.fdd, EffectType.select, unpivRs, vCol, inColRefs,
                            ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
                }
            }
        }
        if (unpivot.nameColumns() != null) {
            for (String nc : unpivot.nameColumns()) {
                unpivRs.getOrCreateColumn(ctx.mgr(), nc, false, unpivot.span(), ctx.sqlHash());
            }
        }
        String alias = unpivot.alias() != null ? unpivot.alias() : base.alias();
        return new ScopeEntry(alias, false, unpivRs, null, null, true);
    }

    private static BoundObject analyzeSetOpQuery(
            SetOpQuery setOp,
            QueryScope parentScope,
            AnalyzerContext ctx,
            boolean topLevelSelect,
            String customRsName,
            EffectType effectType) {
        QueryScope scope = new QueryScope(parentScope, ctx);
        registerCtes(setOp.ctes(), scope, ctx);
        BoundObject leftRs = analyzeQuery(setOp.left(), scope, ctx, false, ResultSetType.select_list, null, EffectType.select);
        BoundObject rightRs = analyzeQuery(setOp.right(), scope, ctx, false, ResultSetType.select_list, null, EffectType.select);
        BoundObject unionRs = ctx.mgr().createResultSet(
                ResultSetType.select_list, customRsName, topLevelSelect, ctx.routineId(), ctx.sqlHash(), setOp.span());
        String op = setOp.operator() != null ? setOp.operator().toUpperCase(Locale.ROOT) : "UNION";
        unionRs.setType(op.contains("INTERSECT") ? "select_intersect"
                : (op.contains("EXCEPT") || op.contains("MINUS") ? "select_except" : "select_union"));

        List<BoundColumn> lCols = leftRs != null ? leftRs.orderedColumns() : List.of();
        List<BoundColumn> rCols = rightRs != null ? rightRs.orderedColumns() : List.of();
        int width = Math.max(lCols.size(), rCols.size());
        EffectType eff = effectType != null ? effectType : EffectType.select;
        for (int i = 0; i < width; i++) {
            String outName = i < lCols.size() ? lCols.get(i).getName() : rCols.get(i).getName();
            BoundColumn uCol = unionRs.getOrCreateColumn(ctx.mgr(), outName, false, setOp.span(), ctx.sqlHash());
            List<BoundSourceRef> srcs = new ArrayList<>(2);
            if (i < lCols.size()) {
                srcs.add(ctx.toSourceRef(leftRs, lCols.get(i), null, "select", setOp.span()));
            }
            if (i < rCols.size()) {
                srcs.add(ctx.toSourceRef(rightRs, rCols.get(i), null, "select", setOp.span()));
            }
            ctx.mgr().addColumnEdge(
                    RelationshipType.fdd, eff, unionRs, uCol, srcs,
                    ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
        }
        return unionRs;
    }

    private static BoundObject analyzeValuesQuery(
            ValuesQuery vq, QueryScope parentScope, AnalyzerContext ctx, String customRsName) {
        QueryScope scope = parentScope != null ? parentScope : new QueryScope(null, ctx);
        BoundObject valRs = ctx.mgr().createResultSet(
                ResultSetType.select_list,
                customRsName != null ? customRsName : "Values-Table",
                false,
                ctx.routineId(),
                ctx.sqlHash(),
                vq.span());
        valRs.setSubType("values_table");
        if (vq.rows() == null || vq.rows().isEmpty()) {
            return valRs;
        }
        int colCount = vq.rows().get(0).size();
        for (int c = 0; c < colCount; c++) {
            BoundColumn col = valRs.getOrCreateColumn(ctx.mgr(), "COL_" + (c + 1), false, vq.span(), ctx.sqlHash());
            List<BoundSourceRef> direct = new ArrayList<>();
            List<BoundSourceRef> indirect = new ArrayList<>();
            for (List<Expr> row : vq.rows()) {
                if (c < row.size()) {
                    EvalResult er = ExpressionAnalyzer.evaluate(row.get(c), scope, "select", null);
                    direct.addAll(er.direct());
                    indirect.addAll(er.indirect());
                }
            }
            if (!direct.isEmpty()) {
                ctx.mgr().addColumnEdge(
                        RelationshipType.fdd, EffectType.select, valRs, col, direct,
                        ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
            }
            emitIndirectEdges(ctx, EffectType.select, valRs, col, indirect, null);
        }
        return valRs;
    }

    private static void handleIntoClause(io.sqlflow.lineage.ast.QueryAst.IntoClause into, BoundObject srcRs, AnalyzerContext ctx) {
        List<BoundColumn> srcCols = srcRs.orderedColumns();
        if (into.targetTables() != null) {
            for (QualifiedName tblQn : into.targetTables()) {
                BoundObject tgt = ctx.mgr().bindTableOrView(
                        tblQn, ObjectCategory.TABLE, EndpointIntroduction.SELECT_INTO, true, false, true, ctx.sqlHash());
                if (into.temporaryTable()) {
                    tgt.setTemporary(true);
                }
                wirePositionalFlow(ctx, srcRs, tgt, null, EffectType.insert);
            }
        }
        if (into.targetVariables() != null && !into.targetVariables().isEmpty()) {
            for (int i = 0; i < into.targetVariables().size(); i++) {
                Expr.VariableExpr v = into.targetVariables().get(i);
                BoundObject varObj = ctx.bindVariable(v.rawName(), v.span());
                if (varObj != null && i < srcCols.size()) {
                    String cn = v.fieldName() != null ? v.fieldName() : v.rawName();
                    BoundColumn vc = varObj.getOrCreateColumn(ctx.mgr(), cn, false, v.span(), ctx.sqlHash());
                    ctx.mgr().addColumnEdge(
                            RelationshipType.fdd, EffectType.select, varObj, vc,
                            List.of(ctx.toSourceRef(srcRs, srcCols.get(i), null, "select", v.span())),
                            ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
                }
            }
        }
        if (into.directoryUri() != null && !into.directoryUri().isBlank()) {
            BoundObject pathObj = ctx.mgr().bindPathOrStageObject(
                    into.directoryUri(), ObjectCategory.PATH, "path", into.span(), true, ctx.sqlHash());
            wirePositionalFlow(ctx, srcRs, pathObj, null, EffectType.unload);
        }
    }

    public static void wirePositionalFlow(
            AnalyzerContext ctx, BoundObject srcObj, BoundObject tgtObj, List<String> explicitTgtCols, EffectType effect) {
        List<BoundColumn> srcCols = srcObj.orderedColumns();
        for (int i = 0; i < srcCols.size(); i++) {
            BoundColumn sc = srcCols.get(i);
            String tgtColName = (explicitTgtCols != null && i < explicitTgtCols.size())
                    ? explicitTgtCols.get(i)
                    : sc.getName();
            BoundColumn tc = tgtObj.getOrCreateColumn(ctx.mgr(), tgtColName, false, null, ctx.sqlHash());
            ctx.mgr().addColumnEdge(
                    RelationshipType.fdd, effect, tgtObj, tc,
                    List.of(ctx.toSourceRef(srcObj, sc, null, "select", null)),
                    ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
        }
    }

    public static void emitIndirectEdges(
            AnalyzerContext ctx,
            EffectType eff,
            BoundObject targetObj,
            BoundColumn targetCol,
            List<BoundSourceRef> indirect,
            String fnName) {
        if (indirect == null || indirect.isEmpty()) {
            return;
        }
        List<BoundSourceRef> rrSources = new ArrayList<>();
        List<BoundSourceRef> colSources = new ArrayList<>();
        for (BoundSourceRef r : indirect) {
            if (r != null && r.col() != null && "RelationRows".equalsIgnoreCase(r.col().getName())) {
                rrSources.add(r);
            } else if (r != null) {
                colSources.add(r);
            }
        }
        if (!colSources.isEmpty()) {
            ctx.mgr().addColumnEdge(
                    ExpressionAnalyzer.indirectRelType(ctx), eff, targetObj, targetCol, colSources,
                    ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), fnName, null, null, null);
        }
        if (!rrSources.isEmpty()) {
            RelationshipType rrType = ExpressionAnalyzer.recordSetRelType(ctx, rrSources.get(0));
            String rrFn = rrSources.get(0).function() != null ? rrSources.get(0).function() : fnName;
            ctx.mgr().addColumnEdge(
                    rrType, eff, targetObj, targetCol, rrSources,
                    ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), rrFn, null, null, null);
        }
    }

    public static void extractEqualityJoins(
            Expr cond, String joinType, String clause, QueryScope scope, AnalyzerContext ctx) {
        if (cond instanceof Expr.BinaryExpr bin) {
            if ("AND".equalsIgnoreCase(bin.operator()) || "OR".equalsIgnoreCase(bin.operator())) {
                extractEqualityJoins(bin.left(), joinType, clause, scope, ctx);
                extractEqualityJoins(bin.right(), joinType, clause, scope, ctx);
            } else if ("=".equals(bin.operator()) || "<=>".equals(bin.operator())) {
                EvalResult l = ExpressionAnalyzer.evaluate(bin.left(), scope, "join", null);
                EvalResult r = ExpressionAnalyzer.evaluate(bin.right(), scope, "join", null);
                String condSql = ctx.extractSql(bin.span());
                for (BoundSourceRef rRef : r.direct()) {
                    if (!l.direct().isEmpty() && rRef.obj() != l.direct().get(0).obj()) {
                        ctx.mgr().addColumnEdge(
                                RelationshipType.join, EffectType.join, rRef.obj(), rRef.col(), l.direct(),
                                ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(),
                                null, clause, joinType, condSql);
                    }
                }
            }
        }
    }

    public static void emitCrudReadIfRequested(ScopeEntry entry, AnalyzerContext ctx, SourceSpan span) {
        if (entry != null && (ctx.option().getAnalyzeMode() == AnalyzeMode.crud
                || ctx.option().getFilterRelationTypes().contains(RelationshipType.crud.name()))) {
            BoundObject obj = entry.object();
            if (obj.getCategory() == ObjectCategory.TABLE || obj.getCategory() == ObjectCategory.VIEW) {
                BoundColumn rr = obj.getOrCreateRelationRows(ctx.mgr());
                BoundObject rs = ctx.mgr().createResultSet(
                        ResultSetType.select_list, "CRUD_SELECT", false, ctx.routineId(), ctx.sqlHash(), span);
                BoundColumn tgt = rs.getOrCreateColumn(ctx.mgr(), obj.getName(), false, span, ctx.sqlHash());
                ctx.mgr().addColumnEdge(
                        RelationshipType.crud, EffectType.select, rs, tgt,
                        List.of(ctx.toSourceRef(obj, rr, entry.alias(), "select", span)),
                        ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
            }
        }
    }
}
