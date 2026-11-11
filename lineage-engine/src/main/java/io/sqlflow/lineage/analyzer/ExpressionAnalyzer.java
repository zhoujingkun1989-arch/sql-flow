package io.sqlflow.lineage.analyzer;

import io.sqlflow.contracts.lineage.EffectType;
import io.sqlflow.contracts.lineage.RelationshipElement;
import io.sqlflow.contracts.lineage.RelationshipType;
import io.sqlflow.contracts.lineage.ResultSetType;
import io.sqlflow.contracts.lineage.Transform;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundColumn;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundObject;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundRoutine;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundSourceRef;
import io.sqlflow.lineage.analyzer.QueryScopeResolver.ExpandedColumn;
import io.sqlflow.lineage.analyzer.QueryScopeResolver.QueryScope;
import io.sqlflow.lineage.analyzer.QueryScopeResolver.ScopeEntry;
import io.sqlflow.lineage.ast.Expr;
import io.sqlflow.lineage.ast.QueryAst.OrderByItem;
import io.sqlflow.lineage.ast.QueryAst.SelectItem;
import io.sqlflow.lineage.ast.QueryAst.WindowSpec;
import io.sqlflow.lineage.lexer.SourceSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Evaluates all 15 {@link Expr} AST variants into direct ({@code fdd}) and indirect ({@code fdr})
 * column lineage references ({@code OBL-037..OBL-041}).
 */
public final class ExpressionAnalyzer {

    public record EvalResult(
            List<BoundSourceRef> direct,
            List<BoundSourceRef> indirect,
            String functionName,
            String transformType
    ) {
        public static EvalResult empty() {
            return new EvalResult(List.of(), List.of(), null, null);
        }

        public List<BoundSourceRef> allRefsWithClause(String clauseOverride) {
            List<BoundSourceRef> all = new ArrayList<>(direct.size() + indirect.size());
            for (BoundSourceRef r : direct) {
                all.add(withClause(r, clauseOverride));
            }
            for (BoundSourceRef r : indirect) {
                all.add(withClause(r, clauseOverride));
            }
            return all;
        }
    }

    private ExpressionAnalyzer() {
    }

    public static EvalResult evaluate(
            Expr expr,
            QueryScope scope,
            String clauseType,
            Map<String, WindowSpec> namedWindows) {
        if (expr == null || scope == null) {
            return EvalResult.empty();
        }
        AnalyzerContext ctx = scope.ctx();

        if (expr instanceof Expr.ColumnRefExpr col) {
            List<BoundSourceRef> refs = QueryScopeResolver.resolveColumnRef(
                    scope, col.name(), col.jsonOrStructPath(), clauseType, col.span());
            String txType = (col.jsonOrStructPath() != null && !col.jsonOrStructPath().isEmpty())
                    ? "expression"
                    : "alias";
            return new EvalResult(refs, List.of(), null, txType);
        }
        if (expr instanceof Expr.StarExpr star) {
            List<ExpandedColumn> expanded = QueryScopeResolver.expandStar(scope, star, null, EffectType.select);
            List<BoundSourceRef> direct = new ArrayList<>(expanded.size());
            for (ExpandedColumn ec : expanded) {
                direct.add(ec.sourceRef());
            }
            return new EvalResult(direct, List.of(), null, "alias");
        }
        if (expr instanceof Expr.LiteralExpr lit) {
            if (!ctx.option().isShowConstantTable()) {
                return EvalResult.empty();
            }
            BoundObject constTbl = ctx.mgr().getOrCreateConstantsTable(ctx.sqlHash());
            String valText = lit.value() != null && !lit.value().isBlank() ? lit.value() : "NULL";
            BoundColumn constCol = constTbl.getOrCreateColumn(ctx.mgr(), valText, false, lit.span(), ctx.sqlHash());
            BoundSourceRef ref = new BoundSourceRef(
                    constCol,
                    constTbl,
                    null,
                    clauseType,
                    ctx.mgr().toCoords(lit.span(), ctx.sqlHash()),
                    ctx.makeTransforms("constant", lit.span(), valText),
                    null,
                    null,
                    valText);
            return new EvalResult(List.of(ref), List.of(), null, "constant");
        }
        if (expr instanceof Expr.VariableExpr varExpr) {
            return evalVariable(varExpr, scope, clauseType);
        }
        if (expr instanceof Expr.SequenceExpr seq) {
            BoundObject seqObj = ctx.mgr().bindSequenceObject(seq.sequenceName(), seq.span(), ctx.sqlHash());
            BoundColumn seqCol = seqObj.getOrCreateColumn(
                    ctx.mgr(), seq.operation(), false, seq.span(), ctx.sqlHash());
            return new EvalResult(
                    List.of(ctx.toSourceRef(seqObj, seqCol, null, clauseType, seq.span())),
                    List.of(),
                    null,
                    "expression");
        }
        if (expr instanceof Expr.FuncCallExpr fn) {
            return evalFunctionCall(fn, scope, clauseType, namedWindows);
        }
        if (expr instanceof Expr.CaseExpr caseExpr) {
            return evalCaseExpr(caseExpr, scope, clauseType, namedWindows);
        }
        if (expr instanceof Expr.BinaryExpr bin) {
            EvalResult l = evaluate(bin.left(), scope, clauseType, namedWindows);
            EvalResult r = evaluate(bin.right(), scope, clauseType, namedWindows);
            return merge(l, r, "expression");
        }
        if (expr instanceof Expr.UnaryExpr un) {
            EvalResult sub = evaluate(un.operand(), scope, clauseType, namedWindows);
            return new EvalResult(sub.direct(), sub.indirect(), sub.functionName(), "expression");
        }
        if (expr instanceof Expr.CastExpr cast) {
            EvalResult sub = evaluate(cast.expr(), scope, clauseType, namedWindows);
            return new EvalResult(sub.direct(), sub.indirect(), sub.functionName(), "expression");
        }
        if (expr instanceof Expr.ExtractExpr ext) {
            EvalResult sub = evaluate(ext.source(), scope, clauseType, namedWindows);
            return new EvalResult(sub.direct(), sub.indirect(), "EXTRACT", "function");
        }
        if (expr instanceof Expr.InListExpr inList) {
            List<BoundSourceRef> direct = new ArrayList<>();
            List<BoundSourceRef> indirect = new ArrayList<>();
            collectInto(evaluate(inList.expr(), scope, clauseType, namedWindows), direct, indirect);
            if (inList.items() != null) {
                for (Expr item : inList.items()) {
                    collectInto(evaluate(item, scope, clauseType, namedWindows), direct, indirect);
                }
            }
            return new EvalResult(direct, indirect, null, "expression");
        }
        if (expr instanceof Expr.BetweenExpr btw) {
            List<BoundSourceRef> direct = new ArrayList<>();
            List<BoundSourceRef> indirect = new ArrayList<>();
            collectInto(evaluate(btw.expr(), scope, clauseType, namedWindows), direct, indirect);
            collectInto(evaluate(btw.low(), scope, clauseType, namedWindows), direct, indirect);
            collectInto(evaluate(btw.high(), scope, clauseType, namedWindows), direct, indirect);
            return new EvalResult(direct, indirect, null, "expression");
        }
        if (expr instanceof Expr.StructArrayExpr sa) {
            List<BoundSourceRef> direct = new ArrayList<>();
            List<BoundSourceRef> indirect = new ArrayList<>();
            if (sa.elements() != null) {
                for (SelectItem el : sa.elements()) {
                    collectInto(evaluate(el.expr(), scope, clauseType, namedWindows), direct, indirect);
                }
            }
            return new EvalResult(direct, indirect, sa.kind(), "expression");
        }
        if (expr instanceof Expr.SubqueryExpr subq) {
            return evalSubqueryExpr(subq, scope, clauseType);
        }
        return EvalResult.empty();
    }

    private static EvalResult evalVariable(Expr.VariableExpr v, QueryScope scope, String clauseType) {
        AnalyzerContext ctx = scope.ctx();
        if ("PSEUDO_RECORD".equals(v.kind()) && ctx.triggerTable() != null) {
            String colName = v.fieldName() != null && !v.fieldName().isBlank() ? v.fieldName() : v.cleanName();
            BoundColumn col = ctx.triggerTable().getOrCreateColumn(ctx.mgr(), colName, false, v.span(), ctx.sqlHash());
            return new EvalResult(
                    List.of(ctx.toSourceRef(ctx.triggerTable(), col, v.cleanName(), clauseType, v.span())),
                    List.of(),
                    null,
                    "alias");
        }
        BoundObject loopRec = scope.findLoopRecord(v.cleanName());
        if (loopRec != null) {
            String colName = v.fieldName() != null && !v.fieldName().isBlank() ? v.fieldName() : v.cleanName();
            BoundColumn col = loopRec.getOrCreateColumn(ctx.mgr(), colName, false, v.span(), ctx.sqlHash());
            return new EvalResult(
                    List.of(ctx.toSourceRef(loopRec, col, v.cleanName(), clauseType, v.span())),
                    List.of(),
                    null,
                    "alias");
        }
        BoundObject varObj = ctx.bindVariable(v.rawName(), v.span());
        if (varObj == null) {
            return EvalResult.empty();
        }
        String colName = v.fieldName() != null && !v.fieldName().isBlank() ? v.fieldName() : v.rawName();
        BoundColumn col = varObj.getOrCreateColumn(ctx.mgr(), colName, false, v.span(), ctx.sqlHash());
        return new EvalResult(
                List.of(ctx.toSourceRef(varObj, col, null, clauseType, v.span())),
                List.of(),
                null,
                "alias");
    }

    private static EvalResult evalFunctionCall(
            Expr.FuncCallExpr fn,
            QueryScope scope,
            String clauseType,
            Map<String, WindowSpec> namedWindows) {
        AnalyzerContext ctx = scope.ctx();
        String rawFnName = fn.name() != null ? fn.name().lastName() : "FUNC";
        String upperFn = rawFnName.toUpperCase(Locale.ROOT);
        boolean builtIn = AnalyzerContext.isBuiltInFunction(upperFn) && (fn.name() == null || fn.name().size() == 1);

        if (ctx.option().isShowCallRelation() && (!builtIn || (fn.name() != null && fn.name().size() > 1))) {
            BoundRoutine callee = ctx.mgr().bindRoutine(fn.name(), "function", null, fn.span(), ctx.sqlHash());
            RelationshipElement calleeEl = new RelationshipElement();
            calleeEl.setId(callee.getId());
            calleeEl.setName(callee.getName());
            calleeEl.setType("function");
            ctx.mgr().addCallEdge(
                    ctx.enclosingRoutine(),
                    null,
                    List.of(calleeEl),
                    builtIn,
                    ctx.extractSql(fn.span()),
                    fn.span(),
                    ctx.sqlHash());
        }

        List<BoundSourceRef> direct = new ArrayList<>();
        List<BoundSourceRef> indirect = new ArrayList<>();

        // COUNT(*) / COUNT(1) / COUNT(col) rule (03 §3.1.3)
        if ("COUNT".equals(upperFn) || "COUNT_IF".equals(upperFn)) {
            boolean hasColArg = false;
            if (fn.args() != null) {
                for (Expr arg : fn.args()) {
                    if (arg instanceof Expr.StarExpr || arg instanceof Expr.LiteralExpr) {
                        continue;
                    }
                    EvalResult r = evaluate(arg, scope, clauseType, namedWindows);
                    for (BoundSourceRef d : r.direct()) {
                        indirect.add(withFunction(d, "COUNT"));
                        hasColArg = true;
                    }
                    for (BoundSourceRef ind : r.indirect()) {
                        indirect.add(withFunction(ind, "COUNT"));
                        hasColArg = true;
                    }
                }
            }
            if (!hasColArg && ctx.option().isShowCountTableColumn()) {
                for (ScopeEntry entry : scope.entries()) {
                    BoundColumn rr = entry.object().getOrCreateRelationRows(ctx.mgr());
                    indirect.add(new BoundSourceRef(
                            rr,
                            entry.object(),
                            entry.alias(),
                            clauseType,
                            ctx.mgr().toCoords(fn.span(), ctx.sqlHash()),
                            ctx.makeTransforms("function", fn.span(), null),
                            null,
                            "COUNT",
                            null));
                }
            }
        } else if ("IF".equals(upperFn) || "IIF".equals(upperFn)) {
            if (fn.args() != null && !fn.args().isEmpty()) {
                EvalResult condRes = evaluate(fn.args().get(0), scope, "when", namedWindows);
                if (ctx.option().isShowCaseWhenAsDirect()) {
                    direct.addAll(condRes.direct());
                    indirect.addAll(condRes.indirect());
                } else {
                    indirect.addAll(condRes.allRefsWithClause("when"));
                }
                for (int i = 1; i < fn.args().size(); i++) {
                    collectInto(evaluate(fn.args().get(i), scope, "then", namedWindows), direct, indirect);
                }
            }
        } else if ("DECODE".equals(upperFn) && fn.args() != null && fn.args().size() >= 3) {
            EvalResult first = evaluate(fn.args().get(0), scope, "case", namedWindows);
            if (ctx.option().isShowCaseWhenAsDirect()) {
                direct.addAll(first.direct());
                indirect.addAll(first.indirect());
            } else {
                indirect.addAll(first.allRefsWithClause("case"));
            }
            for (int i = 1; i < fn.args().size(); i++) {
                boolean isValueBranch = (i % 2 == 0) || (i == fn.args().size() - 1 && fn.args().size() % 2 == 0);
                EvalResult argRes = evaluate(fn.args().get(i), scope, isValueBranch ? "then" : "when", namedWindows);
                if (isValueBranch || ctx.option().isShowCaseWhenAsDirect()) {
                    collectInto(argRes, direct, indirect);
                } else {
                    indirect.addAll(argRes.allRefsWithClause("when"));
                }
            }
        } else if (fn.args() != null) {
            for (Expr arg : fn.args()) {
                collectInto(evaluate(arg, scope, clauseType, namedWindows), direct, indirect);
            }
        }

        if (fn.withinGroupOrder() != null) {
            for (OrderByItem ob : fn.withinGroupOrder()) {
                indirect.addAll(evaluate(ob.expr(), scope, "orderby", namedWindows).allRefsWithClause("orderby"));
            }
        }
        if (fn.filterWhere() != null) {
            indirect.addAll(evaluate(fn.filterWhere(), scope, "where", namedWindows).allRefsWithClause("where"));
        }
        if (fn.overWindow() != null) {
            collectWindowSpec(fn.overWindow(), scope, namedWindows, indirect);
        }

        // Materialize intermediate RESULT_OF_<FN> node when function nodes are enabled
        boolean emitFnNode = !ctx.option().isSqlflowIgnoreFunction()
                && (!direct.isEmpty() || !indirect.isEmpty())
                && (ctx.option().isSimpleShowFunction()
                        || (ctx.option().isSimpleShowUdfFunctionOnly() && !builtIn));
        if (emitFnNode) {
            BoundObject fnObj = ctx.mgr().createResultSet(
                    ResultSetType.function,
                    "RESULT_OF_" + rawFnName,
                    false,
                    ctx.routineId(),
                    ctx.sqlHash(),
                    fn.span());
            fnObj.setBuiltInFunction(builtIn);
            BoundColumn fnCol = fnObj.getOrCreateColumn(ctx.mgr(), rawFnName, false, fn.span(), ctx.sqlHash());
            fnCol.setIsFunction(Boolean.TRUE);
            if (!direct.isEmpty()) {
                ctx.mgr().addColumnEdge(
                        RelationshipType.fdd, EffectType.function, fnObj, fnCol, direct,
                        ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), rawFnName, null, null, null);
            }
            if (!indirect.isEmpty()) {
                ctx.mgr().addColumnEdge(
                        indirectRelType(ctx), EffectType.function, fnObj, fnCol, indirect,
                        ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), rawFnName, null, null, null);
            }
            return new EvalResult(
                    List.of(ctx.toSourceRef(fnObj, fnCol, null, clauseType, fn.span())),
                    List.of(),
                    rawFnName,
                    "function");
        }

        return new EvalResult(direct, indirect, rawFnName, "function");
    }

    private static void collectWindowSpec(
            WindowSpec spec,
            QueryScope scope,
            Map<String, WindowSpec> namedWindows,
            List<BoundSourceRef> indirect) {
        if (spec == null) {
            return;
        }
        if (spec.refWindowName() != null && namedWindows != null) {
            WindowSpec ref = namedWindows.get(spec.refWindowName().toUpperCase(Locale.ROOT));
            if (ref != null && ref != spec) {
                collectWindowSpec(ref, scope, null, indirect);
            }
        }
        if (spec.partitionBy() != null) {
            for (Expr p : spec.partitionBy()) {
                indirect.addAll(evaluate(p, scope, "groupby", namedWindows).allRefsWithClause("groupby"));
            }
        }
        if (spec.orderBy() != null) {
            for (OrderByItem ob : spec.orderBy()) {
                indirect.addAll(evaluate(ob.expr(), scope, "orderby", namedWindows).allRefsWithClause("orderby"));
            }
        }
    }

    private static EvalResult evalCaseExpr(
            Expr.CaseExpr c,
            QueryScope scope,
            String clauseType,
            Map<String, WindowSpec> namedWindows) {
        AnalyzerContext ctx = scope.ctx();
        boolean whenAsDirect = ctx.option().isShowCaseWhenAsDirect();
        List<BoundSourceRef> direct = new ArrayList<>();
        List<BoundSourceRef> indirect = new ArrayList<>();

        if (c.operand() != null) {
            EvalResult opRes = evaluate(c.operand(), scope, "case", namedWindows);
            if (whenAsDirect) {
                collectInto(opRes, direct, indirect);
            } else {
                indirect.addAll(opRes.allRefsWithClause("case"));
            }
        }
        if (c.whenClauses() != null) {
            for (Expr.WhenClause wc : c.whenClauses()) {
                if (wc.whenExpr() != null) {
                    EvalResult wRes = evaluate(wc.whenExpr(), scope, "when", namedWindows);
                    if (whenAsDirect) {
                        collectInto(wRes, direct, indirect);
                    } else {
                        indirect.addAll(wRes.allRefsWithClause("when"));
                    }
                }
                if (wc.thenExpr() != null) {
                    collectInto(evaluate(wc.thenExpr(), scope, "then", namedWindows), direct, indirect);
                }
            }
        }
        if (c.elseExpr() != null) {
            collectInto(evaluate(c.elseExpr(), scope, "else", namedWindows), direct, indirect);
        }
        return new EvalResult(direct, indirect, null, "case");
    }

    private static EvalResult evalSubqueryExpr(Expr.SubqueryExpr subq, QueryScope scope, String clauseType) {
        AnalyzerContext ctx = scope.ctx();
        List<BoundSourceRef> direct = new ArrayList<>();
        List<BoundSourceRef> indirect = new ArrayList<>();
        if (subq.testExpr() != null) {
            collectInto(evaluate(subq.testExpr(), scope, clauseType, null), direct, indirect);
        }
        if (subq.query() != null) {
            BoundObject rs = SelectAndSetOpAnalyzer.analyzeQuery(
                    subq.query(), scope, ctx, false, ResultSetType.select_list, null, EffectType.select);
            if (rs != null) {
                String kind = subq.kind() != null ? subq.kind().toUpperCase(Locale.ROOT) : "SCALAR";
                if (kind.contains("EXISTS")) {
                    BoundColumn rr = rs.getOrCreateRelationRows(ctx.mgr());
                    indirect.add(ctx.toSourceRef(rs, rr, null, clauseType, subq.span()));
                } else {
                    for (BoundColumn col : rs.orderedColumns()) {
                        BoundSourceRef ref = ctx.toSourceRef(rs, col, null, clauseType, subq.span());
                        if (kind.contains("IN")) {
                            indirect.add(ref);
                        } else {
                            direct.add(ref);
                        }
                    }
                }
            }
        }
        return new EvalResult(direct, indirect, null, "expression");
    }

    public static String evalStaticString(Expr expr, AnalyzerContext ctx) {
        if (expr == null) {
            return null;
        }
        if (expr instanceof Expr.LiteralExpr lit) {
            return "NULL".equalsIgnoreCase(lit.literalType()) ? "" : lit.value();
        }
        if (expr instanceof Expr.VariableExpr v) {
            return ctx.staticStringVars().get(ctx.varKey(v.rawName()));
        }
        if (expr instanceof Expr.ColumnRefExpr c) {
            return c.name().size() == 1 ? ctx.staticStringVars().get(ctx.varKey(c.name().lastName())) : null;
        }
        if (expr instanceof Expr.CastExpr cast) {
            return evalStaticString(cast.expr(), ctx);
        }
        if (expr instanceof Expr.BinaryExpr bin) {
            if ("+".equals(bin.operator()) || "||".equals(bin.operator())) {
                String l = evalStaticString(bin.left(), ctx);
                String r = evalStaticString(bin.right(), ctx);
                if (l != null && r != null) {
                    return l + r;
                }
                return l != null ? l : r;
            }
            return null;
        }
        if (expr instanceof Expr.FuncCallExpr fn) {
            String name = fn.name() != null ? fn.name().lastName().toUpperCase(Locale.ROOT) : "";
            if (("CONCAT".equals(name) || "CONCAT_WS".equals(name)) && fn.args() != null) {
                String sep = "CONCAT_WS".equals(name) && !fn.args().isEmpty()
                        ? evalStaticString(fn.args().get(0), ctx)
                        : "";
                int start = "CONCAT_WS".equals(name) ? 1 : 0;
                StringBuilder sb = new StringBuilder();
                for (int i = start; i < fn.args().size(); i++) {
                    String part = evalStaticString(fn.args().get(i), ctx);
                    if (part != null) {
                        if (i > start && sep != null) {
                            sb.append(sep);
                        }
                        sb.append(part);
                    }
                }
                return sb.isEmpty() ? null : sb.toString();
            }
            if ("REPLACE".equals(name) && fn.args() != null && fn.args().size() >= 3) {
                String base = evalStaticString(fn.args().get(0), ctx);
                String from = evalStaticString(fn.args().get(1), ctx);
                String to = evalStaticString(fn.args().get(2), ctx);
                if (base != null && from != null && to != null) {
                    return base.replace(from, to);
                }
            }
        }
        return null;
    }

    public static RelationshipType indirectRelType(AnalyzerContext ctx) {
        if (ctx.option().getFilterRelationTypes().contains(RelationshipType.fddi.name())) {
            return RelationshipType.fddi;
        }
        return RelationshipType.fdr;
    }

    public static RelationshipType recordSetRelType(AnalyzerContext ctx, BoundSourceRef ref) {
        if (ref != null && ref.col() != null && "RelationRows".equalsIgnoreCase(ref.col().getName())
                && ctx.option().getFilterRelationTypes().contains(RelationshipType.frd.name())) {
            return RelationshipType.frd;
        }
        return indirectRelType(ctx);
    }

    public static BoundSourceRef attachExprTransform(
            BoundSourceRef ref, String txType, SourceSpan exprSpan, String fallbackCode, AnalyzerContext ctx) {
        if (ref == null || !ctx.option().isTransform()) {
            return ref;
        }
        List<Transform> txs = ctx.makeTransforms(txType != null ? txType : "expression", exprSpan, fallbackCode);
        if (txs == null || txs.isEmpty()) {
            return ref;
        }
        return new BoundSourceRef(
                ref.col(), ref.obj(), ref.alias(), ref.clauseType(),
                ref.coordinates(), txs, ref.candidateParents(), ref.function(), ref.value());
    }

    private static BoundSourceRef withClause(BoundSourceRef r, String clause) {
        if (clause == null || clause.equals(r.clauseType())) {
            return r;
        }
        return new BoundSourceRef(
                r.col(), r.obj(), r.alias(), clause, r.coordinates(),
                r.transforms(), r.candidateParents(), r.function(), r.value());
    }

    private static BoundSourceRef withFunction(BoundSourceRef r, String fn) {
        return new BoundSourceRef(
                r.col(), r.obj(), r.alias(), r.clauseType(), r.coordinates(),
                r.transforms(), r.candidateParents(), fn, r.value());
    }

    private static void collectInto(EvalResult r, List<BoundSourceRef> direct, List<BoundSourceRef> indirect) {
        direct.addAll(r.direct());
        indirect.addAll(r.indirect());
    }

    private static EvalResult merge(EvalResult a, EvalResult b, String txType) {
        List<BoundSourceRef> direct = new ArrayList<>(a.direct().size() + b.direct().size());
        direct.addAll(a.direct());
        direct.addAll(b.direct());
        List<BoundSourceRef> indirect = new ArrayList<>(a.indirect().size() + b.indirect().size());
        indirect.addAll(a.indirect());
        indirect.addAll(b.indirect());
        return new EvalResult(direct, indirect, a.functionName() != null ? a.functionName() : b.functionName(), txType);
    }
}
