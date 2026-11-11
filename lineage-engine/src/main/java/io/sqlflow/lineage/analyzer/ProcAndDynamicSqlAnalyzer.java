package io.sqlflow.lineage.analyzer;

import io.sqlflow.contracts.lineage.ArgumentObject;
import io.sqlflow.contracts.lineage.EffectType;
import io.sqlflow.contracts.lineage.EndpointIntroduction;
import io.sqlflow.contracts.lineage.RelationshipElement;
import io.sqlflow.contracts.lineage.RelationshipType;
import io.sqlflow.contracts.lineage.ResultSetType;
import io.sqlflow.contracts.vendor.DataObjectType;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.lineage.analyzer.ExpressionAnalyzer.EvalResult;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundColumn;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundObject;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundPackage;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundRoutine;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundSourceRef;
import io.sqlflow.lineage.analyzer.ModelBindingManager.ObjectCategory;
import io.sqlflow.lineage.analyzer.QueryScopeResolver.QueryScope;
import io.sqlflow.lineage.ast.Expr;
import io.sqlflow.lineage.ast.QualifiedName;
import io.sqlflow.lineage.ast.QueryAst.QueryExpr;
import io.sqlflow.lineage.ast.QueryAst.TableFunctionSource;
import io.sqlflow.lineage.ast.SqlStmt;
import io.sqlflow.lineage.ast.SqlStmt.AnonymousBlockStmt;
import io.sqlflow.lineage.ast.SqlStmt.AssignStmt;
import io.sqlflow.lineage.ast.SqlStmt.CallStmt;
import io.sqlflow.lineage.ast.SqlStmt.ColumnDef;
import io.sqlflow.lineage.ast.SqlStmt.ConditionalBranch;
import io.sqlflow.lineage.ast.SqlStmt.CreatePackageStmt;
import io.sqlflow.lineage.ast.SqlStmt.CreateRoutineStmt;
import io.sqlflow.lineage.ast.SqlStmt.DeclareVarStmt;
import io.sqlflow.lineage.ast.SqlStmt.DynamicSqlStmt;
import io.sqlflow.lineage.ast.SqlStmt.FetchCursorStmt;
import io.sqlflow.lineage.ast.SqlStmt.ForLoopStmt;
import io.sqlflow.lineage.ast.SqlStmt.IfStmt;
import io.sqlflow.lineage.ast.SqlStmt.MdxCalculatedMember;
import io.sqlflow.lineage.ast.SqlStmt.MdxSelectStmt;
import io.sqlflow.lineage.ast.SqlStmt.OpenCursorStmt;
import io.sqlflow.lineage.ast.SqlStmt.PowerQueryLetStmt;
import io.sqlflow.lineage.ast.SqlStmt.PowerQueryStep;
import io.sqlflow.lineage.ast.SqlStmt.ReturnStmt;
import io.sqlflow.lineage.ast.SqlStmt.RoutineArg;
import io.sqlflow.lineage.ast.SqlStmt.WhileLoopStmt;
import io.sqlflow.lineage.catalog.TSQLEnv.CatalogArgument;
import io.sqlflow.lineage.catalog.TSQLEnv.CatalogColumn;
import io.sqlflow.lineage.catalog.TSQLEnv.CatalogRoutine;
import io.sqlflow.lineage.catalog.TSQLEnv.CatalogTable;
import io.sqlflow.lineage.parser.StatementParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Semantic analyzer for packages, stored procedures/functions/triggers, cursors, variables,
 * control flow, call edges, dynamic SQL ({@code EXECUTE IMMEDIATE}/{@code sp_executesql}), Power Query M, and MDX.
 */
public final class ProcAndDynamicSqlAnalyzer {

    private ProcAndDynamicSqlAnalyzer() {
    }

    public static void analyzePackage(CreatePackageStmt stmt, AnalyzerContext ctx) {
        BoundPackage pkg = ctx.mgr().bindPackage(stmt.packageName(), stmt.span(), ctx.sqlHash());
        AnalyzerContext pkgCtx = ctx.withPackage(pkg);
        if (stmt.variables() != null) {
            for (DeclareVarStmt v : stmt.variables()) {
                analyzeDeclareVar(v, pkgCtx);
            }
        }
        if (stmt.routines() != null) {
            for (CreateRoutineStmt r : stmt.routines()) {
                String key = pkgCtx.env().lookupKey(DataObjectType.PROCEDURE, r.routineName().lastName(), false);
                pkgCtx.localRoutineAsts().put(key, r);
            }
            for (CreateRoutineStmt r : stmt.routines()) {
                analyzeRoutine(r, pkgCtx);
            }
        }
        if (stmt.initStatements() != null) {
            DmlDdlAndProcAnalyzer.analyzeStatements(stmt.initStatements(), pkgCtx);
        }
    }

    public static void analyzeRoutine(CreateRoutineStmt stmt, AnalyzerContext ctx) {
        if (stmt.routineName() == null || stmt.routineName().isEmpty()) {
            return;
        }
        String rName = stmt.routineName().lastName();
        if (isExcludedRoutine(rName, stmt.routineName().fullDisplayName(), ctx)) {
            return;
        }
        String routineKey = ctx.env().lookupKey(DataObjectType.PROCEDURE, rName, false);
        ctx.localRoutineAsts().put(routineKey, stmt);

        BoundRoutine routine = ctx.mgr().bindRoutine(
                stmt.routineName(), stmt.kind(), ctx.enclosingPackage(), stmt.span(), ctx.sqlHash());
        List<CatalogArgument> catArgs = new ArrayList<>();
        if (stmt.arguments() != null && routine.getArguments().isEmpty()) {
            for (RoutineArg arg : stmt.arguments()) {
                ArgumentObject ao = new ArgumentObject(
                        ctx.mgr().nextObjectId(), arg.name(), arg.dataType(), arg.inout());
                ao.setCoordinates(ctx.mgr().toCoords(arg.span(), ctx.sqlHash()));
                routine.getArguments().add(ao);
                catArgs.add(new CatalogArgument(arg.name(), arg.dataType(), arg.inout()));
            }
        }
        ctx.env().registerRoutine(new CatalogRoutine(
                routine.getServer(), routine.getDatabase(), routine.getSchema(), routine.getPackageName(),
                rName, stmt.kind(), stmt.returnType(), stmt.pipelined(), List.of(), catArgs, null));

        BoundObject trigTbl = null;
        if ("TRIGGER".equalsIgnoreCase(stmt.kind()) && stmt.triggerTable() != null && !stmt.triggerTable().isEmpty()) {
            trigTbl = ctx.mgr().bindTableOrView(
                    stmt.triggerTable(), ObjectCategory.TABLE, EndpointIntroduction.CATALOG_REFERENCE,
                    false, false, false, ctx.sqlHash());
        }

        AnalyzerContext rCtx = ctx.withRoutine(routine, trigTbl);
        if (stmt.arguments() != null) {
            for (RoutineArg arg : stmt.arguments()) {
                if (arg.name() != null && !arg.name().isBlank()) {
                    rCtx.bindVariable(arg.name(), arg.span());
                    if (arg.defaultExpr() != null) {
                        String sVal = ExpressionAnalyzer.evalStaticString(arg.defaultExpr(), rCtx);
                        if (sVal != null) {
                            rCtx.staticStringVars().put(rCtx.varKey(arg.name()), sVal);
                        }
                    }
                }
            }
        }
        if (stmt.pipelined() || stmt.returnsTable()) {
            BoundObject pipeRs = rCtx.mgr().createResultSet(
                    ResultSetType.result_of, "RESULT_OF_" + rName, false, routine.getId(), rCtx.sqlHash(), stmt.span());
            if (stmt.returnTableColumns() != null) {
                for (ColumnDef cd : stmt.returnTableColumns()) {
                    pipeRs.getOrCreateColumn(rCtx.mgr(), cd.name(), cd.quoted(), cd.span(), rCtx.sqlHash());
                }
            }
            routine.setPipelinedResultObject(pipeRs);
        }
        if (stmt.declarations() != null) {
            for (DeclareVarStmt decl : stmt.declarations()) {
                analyzeDeclareVar(decl, rCtx);
            }
        }
        if (stmt.bodyStatements() != null) {
            DmlDdlAndProcAnalyzer.analyzeStatements(stmt.bodyStatements(), rCtx);
        }
    }

    public static void analyzeAnonymousBlock(AnonymousBlockStmt stmt, AnalyzerContext ctx) {
        if (stmt.declarations() != null) {
            for (DeclareVarStmt decl : stmt.declarations()) {
                analyzeDeclareVar(decl, ctx);
            }
        }
        if (stmt.bodyStatements() != null) {
            DmlDdlAndProcAnalyzer.analyzeStatements(stmt.bodyStatements(), ctx);
        }
    }

    public static void analyzeDeclareVar(DeclareVarStmt stmt, AnalyzerContext ctx) {
        String kind = stmt.varKind() != null ? stmt.varKind().toUpperCase(Locale.ROOT) : "SCALAR";
        if ("CURSOR".equals(kind)) {
            BoundObject curObj = ctx.bindCursor(stmt.name(), stmt.cursorQuery(), stmt.span());
            if (stmt.cursorQuery() != null) {
                BoundObject qRs = SelectAndSetOpAnalyzer.analyzeQuery(
                        stmt.cursorQuery(), new QueryScope(null, ctx), ctx, false,
                        ResultSetType.select_list, null, EffectType.cursor);
                if (qRs != null) {
                    SelectAndSetOpAnalyzer.wirePositionalFlow(ctx, qRs, curObj, null, EffectType.cursor);
                }
            }
            return;
        }
        BoundObject varObj = ctx.bindVariable(stmt.name(), stmt.span());
        if (varObj == null) {
            return;
        }
        if ("TABLE".equals(kind) && stmt.tableColumns() != null) {
            for (ColumnDef cd : stmt.tableColumns()) {
                varObj.getOrCreateColumn(ctx.mgr(), cd.name(), cd.quoted(), cd.span(), ctx.sqlHash());
            }
        } else if ("ROWTYPE".equals(kind) && stmt.refObject() != null && !stmt.refObject().isEmpty()) {
            varObj.setSubType("record_type");
            BoundObject refTbl = ctx.mgr().bindTableOrView(
                    stmt.refObject(), ObjectCategory.TABLE, EndpointIntroduction.CATALOG_REFERENCE,
                    false, false, false, ctx.sqlHash());
            CatalogTable ct = ctx.env().resolveTable(stmt.refObject());
            if (ct != null) {
                for (CatalogColumn cc : ct.orderedColumns()) {
                    BoundColumn sc = refTbl.getOrCreateColumn(ctx.mgr(), cc.getName(), false, stmt.span(), ctx.sqlHash());
                    BoundColumn vc = varObj.getOrCreateColumn(ctx.mgr(), cc.getName(), false, stmt.span(), ctx.sqlHash());
                    ctx.mgr().addColumnEdge(
                            RelationshipType.fdd, EffectType.rowtype, varObj, vc,
                            List.of(ctx.toSourceRef(refTbl, sc, null, "select", stmt.span())),
                            ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
                }
            }
        }
        if (stmt.defaultExpr() != null) {
            assignExprToVariable(stmt.name(), null, stmt.defaultExpr(), stmt.span(), ctx);
        }
    }

    public static void analyzeAssign(AssignStmt stmt, AnalyzerContext ctx) {
        assignExprToVariable(stmt.targetVariable(), stmt.targetField(), stmt.valueExpr(), stmt.span(), ctx);
    }

    private static void assignExprToVariable(
            String varName, String fieldName, Expr valueExpr, io.sqlflow.lineage.lexer.SourceSpan span, AnalyzerContext ctx) {
        if (varName == null || valueExpr == null) {
            return;
        }
        BoundObject varObj = ctx.bindVariable(varName, span);
        String colName = fieldName != null && !fieldName.isBlank() ? fieldName : varName;
        BoundColumn varCol = varObj.getOrCreateColumn(ctx.mgr(), colName, false, span, ctx.sqlHash());
        EvalResult er = ExpressionAnalyzer.evaluate(valueExpr, new QueryScope(null, ctx), "assign", null);
        if (!er.direct().isEmpty()) {
            ctx.mgr().addColumnEdge(
                    RelationshipType.fdd, EffectType.select, varObj, varCol, er.direct(),
                    ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), er.functionName(), null, null, null);
        }
        List<BoundSourceRef> allInd = new ArrayList<>(er.indirect());
        allInd.addAll(ctx.controlPredicates());
        SelectAndSetOpAnalyzer.emitIndirectEdges(ctx, EffectType.select, varObj, varCol, allInd, er.functionName());

        if (fieldName == null) {
            String sVal = ExpressionAnalyzer.evalStaticString(valueExpr, ctx);
            if (sVal != null) {
                ctx.staticStringVars().put(ctx.varKey(varName), sVal);
            }
        }
    }

    public static void analyzeOpenCursor(OpenCursorStmt stmt, AnalyzerContext ctx) {
        BoundObject curObj = ctx.bindCursor(stmt.cursorName(), stmt.forQuery(), stmt.span());
        if (stmt.forQuery() != null) {
            BoundObject qRs = SelectAndSetOpAnalyzer.analyzeQuery(
                    stmt.forQuery(), new QueryScope(null, ctx), ctx, false,
                    ResultSetType.select_list, null, EffectType.cursor);
            if (qRs != null) {
                SelectAndSetOpAnalyzer.wirePositionalFlow(ctx, qRs, curObj, null, EffectType.cursor);
            }
        } else if (stmt.dynamicSqlExpr() != null && ctx.option().isAnalyzeDynamicSql()) {
            executeDynamicSqlString(ExpressionAnalyzer.evalStaticString(stmt.dynamicSqlExpr(), ctx),
                    List.of(), stmt.span(), ctx, curObj);
        }
    }

    public static void analyzeFetchCursor(FetchCursorStmt stmt, AnalyzerContext ctx) {
        BoundObject curObj = ctx.findCursor(stmt.cursorName());
        if (curObj == null || stmt.intoVariables() == null) {
            return;
        }
        List<BoundColumn> curCols = curObj.orderedColumns();
        if (stmt.intoVariables().size() == 1 && curCols.size() > 1) {
            BoundObject recVar = ctx.bindVariable(stmt.intoVariables().get(0), stmt.span());
            SelectAndSetOpAnalyzer.wirePositionalFlow(ctx, curObj, recVar, null, EffectType.cursor);
            return;
        }
        for (int i = 0; i < stmt.intoVariables().size(); i++) {
            BoundObject vObj = ctx.bindVariable(stmt.intoVariables().get(i), stmt.span());
            BoundColumn vc = vObj.getOrCreateColumn(ctx.mgr(), stmt.intoVariables().get(i), false, stmt.span(), ctx.sqlHash());
            BoundColumn cc = i < curCols.size()
                    ? curCols.get(i)
                    : curObj.getOrCreateColumn(ctx.mgr(), "COL_" + (i + 1), false, stmt.span(), ctx.sqlHash());
            ctx.mgr().addColumnEdge(
                    RelationshipType.fdd, EffectType.cursor, vObj, vc,
                    List.of(ctx.toSourceRef(curObj, cc, null, "select", stmt.span())),
                    ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
        }
    }

    public static void analyzeForLoop(ForLoopStmt stmt, AnalyzerContext ctx) {
        BoundObject recSource = null;
        if (stmt.inlineQuery() != null) {
            recSource = SelectAndSetOpAnalyzer.analyzeQuery(
                    stmt.inlineQuery(), new QueryScope(null, ctx), ctx, false,
                    ResultSetType.cursor, stmt.loopVar(), EffectType.cursor);
        } else if (stmt.cursorName() != null) {
            recSource = ctx.findCursor(stmt.cursorName());
        }
        if (recSource != null && stmt.loopVar() != null) {
            ctx.bindVariable(stmt.loopVar(), stmt.span());
            BoundObject recVar = ctx.findVariable(stmt.loopVar());
            if (recVar != null) {
                SelectAndSetOpAnalyzer.wirePositionalFlow(ctx, recSource, recVar, null, EffectType.cursor);
            }
        }
        if (stmt.bodyStatements() != null) {
            DmlDdlAndProcAnalyzer.analyzeStatements(stmt.bodyStatements(), ctx);
        }
    }

    public static void analyzeControlFlow(SqlStmt stmt, AnalyzerContext ctx) {
        QueryScope scope = new QueryScope(null, ctx);
        if (stmt instanceof IfStmt ifs) {
            List<BoundSourceRef> cumulative = new ArrayList<>();
            if (ifs.branches() != null) {
                for (ConditionalBranch br : ifs.branches()) {
                    List<BoundSourceRef> condRefs = br.condition() != null
                            ? ExpressionAnalyzer.evaluate(br.condition(), scope, "when", null).allRefsWithClause("when")
                            : List.of();
                    cumulative.addAll(condRefs);
                    if (br.statements() != null) {
                        DmlDdlAndProcAnalyzer.analyzeStatements(br.statements(), ctx.withControlPredicates(cumulative));
                    }
                }
            }
            if (ifs.elseStatements() != null) {
                DmlDdlAndProcAnalyzer.analyzeStatements(ifs.elseStatements(), ctx.withControlPredicates(cumulative));
            }
        } else if (stmt instanceof WhileLoopStmt wl) {
            List<BoundSourceRef> condRefs = wl.condition() != null
                    ? ExpressionAnalyzer.evaluate(wl.condition(), scope, "when", null).allRefsWithClause("when")
                    : List.of();
            if (wl.bodyStatements() != null) {
                DmlDdlAndProcAnalyzer.analyzeStatements(wl.bodyStatements(), ctx.withControlPredicates(condRefs));
            }
        }
    }

    public static void analyzeReturn(ReturnStmt stmt, AnalyzerContext ctx) {
        BoundObject retTarget = ctx.enclosingRoutine() != null && ctx.enclosingRoutine().getPipelinedResultObject() != null
                ? ctx.enclosingRoutine().getPipelinedResultObject()
                : ctx.mgr().createResultSet(ResultSetType.result_of,
                        ctx.enclosingRoutine() != null ? "RESULT_OF_" + ctx.enclosingRoutine().getName() : "RETURN_RS",
                        ctx.enclosingRoutine() == null, ctx.routineId(), ctx.sqlHash(), stmt.span());
        if (stmt.returnQuery() != null) {
            BoundObject qRs = SelectAndSetOpAnalyzer.analyzeQuery(
                    stmt.returnQuery(), new QueryScope(null, ctx), ctx, false,
                    ResultSetType.select_list, null, EffectType.select);
            if (qRs != null) {
                SelectAndSetOpAnalyzer.wirePositionalFlow(ctx, qRs, retTarget, null, EffectType.select);
            }
        } else if (stmt.returnExpr() != null) {
            EvalResult er = ExpressionAnalyzer.evaluate(stmt.returnExpr(), new QueryScope(null, ctx), "select", null);
            BoundColumn rc = retTarget.getOrCreateColumn(ctx.mgr(), "RETURN_VALUE", false, stmt.span(), ctx.sqlHash());
            if (!er.direct().isEmpty()) {
                ctx.mgr().addColumnEdge(
                        RelationshipType.fdd, EffectType.select, retTarget, rc, er.direct(),
                        ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), er.functionName(), null, null, null);
            }
        }
    }

    public static void analyzeCall(CallStmt stmt, AnalyzerContext ctx) {
        if (stmt.routineName() == null || stmt.routineName().isEmpty()) {
            return;
        }
        String rName = stmt.routineName().lastName();
        String firstPart = stmt.routineName().parts().get(0).value().toUpperCase(Locale.ROOT);
        boolean builtIn = (stmt.routineName().size() > 1
                        && (firstPart.startsWith("DBMS_") || firstPart.startsWith("UTL_") || "SYS".equals(firstPart)))
                || rName.toUpperCase(Locale.ROOT).startsWith("SP_")
                || AnalyzerContext.isBuiltInFunction(rName);
        BoundRoutine callee = ctx.mgr().bindRoutine(stmt.routineName(), "procedure", null, stmt.span(), ctx.sqlHash());

        if (ctx.option().isShowCallRelation() || ctx.option().getFilterRelationTypes().contains(RelationshipType.call.name())) {
            RelationshipElement calleeEl = new RelationshipElement();
            calleeEl.setId(callee.getId());
            calleeEl.setName(callee.getName());
            calleeEl.setType(callee.getType());
            ctx.mgr().addCallEdge(
                    ctx.enclosingRoutine(), null, List.of(calleeEl), builtIn,
                    ctx.extractSql(stmt.span()), stmt.span(), ctx.sqlHash());
        }

        QueryScope scope = new QueryScope(null, ctx);
        List<BoundSourceRef> argRefs = new ArrayList<>();
        if (stmt.arguments() != null) {
            for (Expr arg : stmt.arguments()) {
                argRefs.addAll(ExpressionAnalyzer.evaluate(arg, scope, "select", null).direct());
            }
        }
        if (stmt.intoVariable() != null && !stmt.intoVariable().isBlank()) {
            BoundObject intoVar = ctx.bindVariable(stmt.intoVariable(), stmt.span());
            BoundColumn ic = intoVar.getOrCreateColumn(ctx.mgr(), stmt.intoVariable(), false, stmt.span(), ctx.sqlHash());
            if (!argRefs.isEmpty()) {
                ctx.mgr().addColumnEdge(
                        RelationshipType.fdd, EffectType.function, intoVar, ic, argRefs,
                        ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), rName, null, null, null);
            }
        }
    }

    public static void analyzeDynamicSql(DynamicSqlStmt stmt, AnalyzerContext ctx) {
        if ("EXTERNAL_SCRIPT".equalsIgnoreCase(stmt.kind())) {
            if (stmt.externalInputQuery() != null) {
                BoundObject inRs = SelectAndSetOpAnalyzer.analyzeQuery(
                        stmt.externalInputQuery(), new QueryScope(null, ctx), ctx,
                        stmt.externalResultColumns() == null || stmt.externalResultColumns().isEmpty(),
                        ResultSetType.select_list, null, EffectType.external_script_passthrough);
                if (inRs != null && ctx.option().isAssumeExternalScriptPassthrough()
                        && stmt.externalResultColumns() != null && !stmt.externalResultColumns().isEmpty()) {
                    BoundObject outRs = ctx.mgr().createResultSet(
                            ResultSetType.select_list, null, true, ctx.routineId(), ctx.sqlHash(), stmt.span());
                    List<String> names = new ArrayList<>();
                    for (ColumnDef cd : stmt.externalResultColumns()) {
                        names.add(cd.name());
                    }
                    SelectAndSetOpAnalyzer.wirePositionalFlow(ctx, inRs, outRs, names, EffectType.external_script_passthrough);
                }
            }
            return;
        }
        if (!ctx.option().isAnalyzeDynamicSql() || ctx.dynamicDepth() >= 5) {
            return;
        }
        String resolvedSql = ExpressionAnalyzer.evalStaticString(stmt.sqlExpr(), ctx);
        executeDynamicSqlString(resolvedSql, stmt.intoVariables(), stmt.span(), ctx, null);
    }

    private static void executeDynamicSqlString(
            String resolvedSql, List<String> intoVars, io.sqlflow.lineage.lexer.SourceSpan span,
            AnalyzerContext ctx, BoundObject cursorTarget) {
        if (resolvedSql == null || resolvedSql.isBlank()) {
            if (ctx.option().isReportDynamicSqlSitesAsErrors()) {
                ctx.mgr().addError("DynamicSqlUnresolved", "Unresolved dynamic SQL expression", span, ctx.sqlHash());
            }
            return;
        }
        AnalyzerContext dynCtx = ctx.withDynamicSql(resolvedSql, ctx.vendor());
        StatementParser.ParsedScript parsed = StatementParser.parseScript(
                resolvedSql, ctx.vendor(), null, ctx.option().isEnableMssqlColonBindVariables());
        for (SqlStmt inner : parsed.statements()) {
            if (inner instanceof SqlStmt.QueryStmt qs && (cursorTarget != null || (intoVars != null && !intoVars.isEmpty()))) {
                BoundObject rs = SelectAndSetOpAnalyzer.analyzeQuery(
                        qs.query(), new QueryScope(null, dynCtx), dynCtx, false,
                        ResultSetType.select_list, null, EffectType.select);
                if (rs != null && cursorTarget != null) {
                    SelectAndSetOpAnalyzer.wirePositionalFlow(dynCtx, rs, cursorTarget, null, EffectType.cursor);
                }
                if (rs != null && intoVars != null) {
                    List<BoundColumn> cols = rs.orderedColumns();
                    for (int i = 0; i < Math.min(intoVars.size(), cols.size()); i++) {
                        BoundObject vObj = dynCtx.bindVariable(intoVars.get(i), span);
                        BoundColumn vc = vObj.getOrCreateColumn(dynCtx.mgr(), intoVars.get(i), false, span, dynCtx.sqlHash());
                        dynCtx.mgr().addColumnEdge(
                                RelationshipType.fdd, EffectType.select, vObj, vc,
                                List.of(dynCtx.toSourceRef(rs, cols.get(i), null, "select", span)),
                                dynCtx.activeProcess(), dynCtx.enclosingRoutine(), dynCtx.sqlHash(), null, null, null, null);
                    }
                }
            } else {
                DmlDdlAndProcAnalyzer.analyzeSingleStatement(inner, dynCtx);
            }
        }
    }

    public static void analyzePowerQuery(PowerQueryLetStmt stmt, AnalyzerContext ctx) {
        if (stmt.steps() == null || stmt.steps().isEmpty()) {
            return;
        }
        Map<String, BoundObject> stepObjs = new LinkedHashMap<>();
        EDbVendor innerVendor = ctx.option().getPowerQueryInnerVendor() != null
                ? ctx.option().getPowerQueryInnerVendor()
                : EDbVendor.dbvmssql;
        for (PowerQueryStep step : stmt.steps()) {
            String kind = step.funcKind() != null ? step.funcKind().toUpperCase(Locale.ROOT) : "GENERIC";
            BoundObject current = null;
            if ("NATIVE_QUERY".equals(kind) && step.nativeSql() != null && !step.nativeSql().isBlank()) {
                AnalyzerContext innerCtx = ctx.withDynamicSql(step.nativeSql(), innerVendor);
                StatementParser.ParsedScript ps = StatementParser.parseScript(step.nativeSql(), innerVendor);
                for (SqlStmt s : ps.statements()) {
                    if (s instanceof SqlStmt.QueryStmt qs) {
                        boolean isOutputStep = step.stepName().equals(stmt.outputStepName());
                        current = SelectAndSetOpAnalyzer.analyzeQuery(
                                qs.query(), new QueryScope(null, innerCtx), innerCtx, isOutputStep,
                                ResultSetType.select_list, step.stepName(), EffectType.select);
                    } else {
                        DmlDdlAndProcAnalyzer.analyzeSingleStatement(s, innerCtx);
                    }
                }
            } else if ("TABLE_NAVIGATION".equals(kind) && step.tableName() != null) {
                List<String> parts = new ArrayList<>(3);
                if (step.databaseName() != null) {
                    parts.add(step.databaseName());
                }
                if (step.schemaName() != null) {
                    parts.add(step.schemaName());
                }
                parts.add(step.tableName());
                current = ctx.mgr().bindTableOrView(
                        QualifiedName.ofParts(parts, step.span()), ObjectCategory.TABLE,
                        EndpointIntroduction.CATALOG_REFERENCE, false, false, false, ctx.sqlHash());
            } else {
                current = ctx.mgr().createResultSet(
                        ResultSetType.select_list, step.stepName(),
                        step.stepName().equals(stmt.outputStepName()), ctx.routineId(), ctx.sqlHash(), step.span());
                if (step.referencedSteps() != null) {
                    for (String refName : step.referencedSteps()) {
                        BoundObject up = stepObjs.get(refName);
                        if (up != null) {
                            if (step.selectedColumns() != null && !step.selectedColumns().isEmpty()) {
                                for (String cn : step.selectedColumns()) {
                                    BoundColumn sc = up.getOrCreateColumn(ctx.mgr(), cn, false, step.span(), ctx.sqlHash());
                                    BoundColumn tc = current.getOrCreateColumn(ctx.mgr(), cn, false, step.span(), ctx.sqlHash());
                                    ctx.mgr().addColumnEdge(
                                            RelationshipType.fdd, EffectType.select, current, tc,
                                            List.of(ctx.toSourceRef(up, sc, null, "select", step.span())),
                                            ctx.activeProcess(), null, ctx.sqlHash(), null, null, null, null);
                                }
                            } else {
                                SelectAndSetOpAnalyzer.wirePositionalFlow(ctx, up, current, null, EffectType.select);
                            }
                            if (step.renamedColumns() != null) {
                                for (var ren : step.renamedColumns().entrySet()) {
                                    BoundColumn sc = up.getOrCreateColumn(ctx.mgr(), ren.getKey(), false, step.span(), ctx.sqlHash());
                                    BoundColumn tc = current.getOrCreateColumn(ctx.mgr(), ren.getValue(), false, step.span(), ctx.sqlHash());
                                    ctx.mgr().addColumnEdge(
                                            RelationshipType.fdd, EffectType.select, current, tc,
                                            List.of(ctx.toSourceRef(up, sc, null, "select", step.span())),
                                            ctx.activeProcess(), null, ctx.sqlHash(), null, null, null, null);
                                }
                            }
                        }
                    }
                }
            }
            if (current != null) {
                stepObjs.put(step.stepName(), current);
            }
        }
    }

    public static void analyzeMdx(MdxSelectStmt stmt, AnalyzerContext ctx) {
        QueryScope scope = new QueryScope(null, ctx);
        if (stmt.cubeName() != null && !stmt.cubeName().isEmpty()) {
            scope.addEntry(QueryScopeResolver.bindNamedTableSource(scope, stmt.cubeName(), null, false, null, false));
        }
        BoundObject rs = ctx.mgr().createResultSet(ResultSetType.select_list, null, true, null, ctx.sqlHash(), stmt.span());
        if (stmt.calculatedMembers() != null) {
            for (MdxCalculatedMember cm : stmt.calculatedMembers()) {
                String mName = cm.memberName() != null ? cm.memberName().lastName() : "MEMBER";
                BoundColumn mc = rs.getOrCreateColumn(ctx.mgr(), mName, false, cm.span(), ctx.sqlHash());
                EvalResult er = ExpressionAnalyzer.evaluate(cm.formula(), scope, "select", null);
                if (!er.direct().isEmpty()) {
                    ctx.mgr().addColumnEdge(
                            RelationshipType.fdd, EffectType.select, rs, mc, er.direct(),
                            ctx.activeProcess(), null, ctx.sqlHash(), er.functionName(), null, null, null);
                }
                scope.registerSelectAlias(mName, false, List.of(ctx.toSourceRef(rs, mc, null, "select", cm.span())));
            }
        }
        List<BoundSourceRef> slicerInd = stmt.whereSlicer() != null
                ? ExpressionAnalyzer.evaluate(stmt.whereSlicer(), scope, "where", null).allRefsWithClause("where")
                : List.of();
        if (stmt.axisExpressions() != null) {
            int idx = 0;
            for (Expr ax : stmt.axisExpressions()) {
                idx++;
                EvalResult er = ExpressionAnalyzer.evaluate(ax, scope, "select", null);
                String cn = !er.direct().isEmpty() ? er.direct().get(0).col().getName() : "AXIS_" + idx;
                BoundColumn ac = rs.getOrCreateColumn(ctx.mgr(), cn, false, ax.span(), ctx.sqlHash());
                if (!er.direct().isEmpty()) {
                    ctx.mgr().addColumnEdge(
                            RelationshipType.fdd, EffectType.select, rs, ac, er.direct(),
                            ctx.activeProcess(), null, ctx.sqlHash(), er.functionName(), null, null, null);
                }
                SelectAndSetOpAnalyzer.emitIndirectEdges(ctx, EffectType.select, rs, ac, slicerInd, null);
            }
        }
    }

    public static BoundObject tryStitchTableFunction(
            TableFunctionSource tfs, List<BoundSourceRef> callerArgs, AnalyzerContext ctx) {
        if (ctx.routineCallDepth() >= ctx.option().getMaxPipelinedExpansionDepth()) {
            return null;
        }
        String fnKey = ctx.env().lookupKey(DataObjectType.PROCEDURE, tfs.funcName().lastName(), false);
        CreateRoutineStmt routineAst = ctx.localRoutineAsts().get(fnKey);
        if (routineAst == null) {
            return null;
        }
        BoundRoutine rObj = ctx.mgr().bindRoutine(tfs.funcName(), routineAst.kind(), null, tfs.span(), ctx.sqlHash());
        if (rObj.getPipelinedResultObject() != null && !rObj.getPipelinedResultObject().orderedColumns().isEmpty()) {
            return rObj.getPipelinedResultObject();
        }
        return null;
    }

    private static boolean isExcludedRoutine(String simpleName, String fullName, AnalyzerContext ctx) {
        for (String ex : ctx.option().getExcludedProcedureNames()) {
            if (ex != null && (ex.equalsIgnoreCase(simpleName) || ex.equalsIgnoreCase(fullName))) {
                return true;
            }
        }
        for (String pat : ctx.option().getExcludedProcedurePatterns()) {
            if (pat != null && !pat.isBlank() && Pattern.compile(pat, Pattern.CASE_INSENSITIVE).matcher(fullName).find()) {
                return true;
            }
        }
        return false;
    }
}
