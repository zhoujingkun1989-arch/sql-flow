package io.sqlflow.lineage.analyzer;

import io.sqlflow.contracts.lineage.DataFlowHandleListener;
import io.sqlflow.contracts.lineage.EffectType;
import io.sqlflow.contracts.lineage.ResultSetType;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundProcess;
import io.sqlflow.lineage.analyzer.QueryScopeResolver.QueryScope;
import io.sqlflow.lineage.ast.SqlStmt;
import io.sqlflow.lineage.catalog.PolymorphicInputLoader.ExecutableSqlUnit;
import io.sqlflow.lineage.parser.StatementParser;
import java.util.ArrayList;
import java.util.List;

/**
 * Top-level multi-unit SQL script orchestrator and statement dispatcher ({@code OBL-033, OBL-048..OBL-049}).
 *
 * <p>Performs a forward catalog DDL pre-pass across parsed units so out-of-order {@code SELECT *} and
 * unqualified column references resolve against tables/views defined anywhere in the batch, followed by
 * full statement-level semantic lineage extraction with partial-failure error recovery.
 */
public final class DmlDdlAndProcAnalyzer {

    public record ParsedUnitBundle(
            ExecutableSqlUnit unit,
            StatementParser.ParsedScript script
    ) {
    }

    private DmlDdlAndProcAnalyzer() {
    }

    public static List<ParsedUnitBundle> parseAndAnalyzeUnits(
            List<ExecutableSqlUnit> units,
            String explicitDelimiter,
            AnalyzerContext rootCtx) {
        if (units == null || units.isEmpty()) {
            DdlAnalyzer.emitCatalogErEdgesIfRequested(rootCtx);
            return List.of();
        }

        DataFlowHandleListener listener = rootCtx.option().getHandleListener();
        List<ParsedUnitBundle> parsedBundles = new ArrayList<>(units.size());

        // Pass 1: Parse all SQL units and pre-register explicit DDL tables/views/synonyms in TSQLEnv
        for (ExecutableSqlUnit unit : units) {
            if (listener != null && listener.isCanceled()) {
                break;
            }
            EDbVendor unitVendor = unit.unitVendor() != null ? unit.unitVendor() : rootCtx.vendor();
            if (!unitVendor.isImplemented()) {
                rootCtx.mgr().addError(
                        "UnsupportedDialect",
                        "Dialect '" + unitVendor.wireName() + "' is not enabled for full AST lineage",
                        null,
                        unit.sqlHash());
                continue;
            }
            applyUnitContext(unit, rootCtx);
            StatementParser.ParsedScript script = StatementParser.parseScript(
                    unit.sql(),
                    unitVendor,
                    explicitDelimiter,
                    rootCtx.option().isEnableMssqlColonBindVariables());
            for (SqlStmt stmt : script.statements()) {
                DdlAnalyzer.preRegisterTableOrView(stmt, rootCtx.env());
            }
            parsedBundles.add(new ParsedUnitBundle(unit, script));
        }

        // Emit ER relationships from imported catalog metadata if requested
        DdlAnalyzer.emitCatalogErEdgesIfRequested(rootCtx);

        // Pass 2: Full semantic lineage analysis per unit & statement
        int totalUnits = parsedBundles.size();
        for (int i = 0; i < totalUnits; i++) {
            if (listener != null && listener.isCanceled()) {
                break;
            }
            ParsedUnitBundle bundle = parsedBundles.get(i);
            ExecutableSqlUnit unit = bundle.unit();
            applyUnitContext(unit, rootCtx);
            AnalyzerContext unitCtx = rootCtx.withUnit(unit.sql(), unit.sqlHash(), unit.unitVendor());
            analyzeStatements(bundle.script().statements(), unitCtx);
            if (listener != null) {
                listener.onStatementAnalyzed(i + 1, totalUnits);
            }
        }
        return parsedBundles;
    }

    private static void applyUnitContext(ExecutableSqlUnit unit, AnalyzerContext ctx) {
        if (unit.database() != null && !unit.database().isBlank()) {
            ctx.env().setCurrentDatabase(unit.database());
        }
        if (unit.schema() != null && !unit.schema().isBlank()) {
            ctx.env().setCurrentSchema(unit.schema());
        }
    }

    public static void analyzeStatements(List<SqlStmt> statements, AnalyzerContext ctx) {
        if (statements == null || statements.isEmpty()) {
            return;
        }
        for (SqlStmt stmt : statements) {
            if (ctx.option().getHandleListener() != null && ctx.option().getHandleListener().isCanceled()) {
                break;
            }
            try {
                analyzeSingleStatement(stmt, ctx);
            } catch (RuntimeException ex) {
                ctx.mgr().addError(
                        "AnalysisError",
                        ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName(),
                        stmt != null ? stmt.span() : null,
                        ctx.sqlHash());
            }
        }
    }

    public static void analyzeSingleStatement(SqlStmt stmt, AnalyzerContext baseCtx) {
        if (stmt == null || stmt instanceof SqlStmt.NoOpStmt || stmt instanceof SqlStmt.CloseCursorStmt) {
            return;
        }
        if (stmt instanceof SqlStmt.ErrorStmt err) {
            baseCtx.mgr().addError(err.errorType(), err.message(), err.span(), baseCtx.sqlHash());
            return;
        }

        AnalyzerContext ctx = maybeCreateProcess(stmt, baseCtx);

        if (stmt instanceof SqlStmt.QueryStmt q) {
            SelectAndSetOpAnalyzer.analyzeQuery(
                    q.query(), new QueryScope(null, ctx), ctx,
                    ctx.enclosingRoutine() == null, ResultSetType.select_list, null, EffectType.select);
        } else if (stmt instanceof SqlStmt.InsertStmt ins) {
            DmlAnalyzer.analyzeInsert(ins, ctx);
        } else if (stmt instanceof SqlStmt.MultiInsertStmt mins) {
            DmlAnalyzer.analyzeMultiInsert(mins, ctx);
        } else if (stmt instanceof SqlStmt.UpdateStmt upd) {
            DmlAnalyzer.analyzeUpdate(upd, ctx);
        } else if (stmt instanceof SqlStmt.DeleteStmt del) {
            DmlAnalyzer.analyzeDelete(del, ctx);
        } else if (stmt instanceof SqlStmt.MergeStmt mrg) {
            DmlAnalyzer.analyzeMerge(mrg, ctx);
        } else if (stmt instanceof SqlStmt.CopyIoStmt cp) {
            DmlAnalyzer.analyzeCopyIo(cp, ctx);
        } else if (stmt instanceof SqlStmt.CreateTableStmt ct) {
            DdlAnalyzer.analyzeCreateTable(ct, ctx);
        } else if (stmt instanceof SqlStmt.CreateViewStmt cv) {
            DdlAnalyzer.analyzeCreateView(cv, ctx);
        } else if (stmt instanceof SqlStmt.AlterTableStmt alt) {
            DdlAnalyzer.analyzeAlterTable(alt, ctx);
        } else if (stmt instanceof SqlStmt.DropStmt
                || stmt instanceof SqlStmt.TruncateStmt
                || stmt instanceof SqlStmt.CreateSynonymStmt
                || stmt instanceof SqlStmt.CreateSequenceStmt
                || stmt instanceof SqlStmt.CreateStageOrStreamStmt
                || stmt instanceof SqlStmt.CreateDatabaseOrSchemaStmt
                || stmt instanceof SqlStmt.UseContextStmt
                || stmt instanceof SqlStmt.CommentOnStmt) {
            DdlAnalyzer.analyzeSimpleDdl(stmt, ctx);
        } else if (stmt instanceof SqlStmt.CreatePackageStmt pkg) {
            ProcAndDynamicSqlAnalyzer.analyzePackage(pkg, ctx);
        } else if (stmt instanceof SqlStmt.CreateRoutineStmt rt) {
            ProcAndDynamicSqlAnalyzer.analyzeRoutine(rt, ctx);
        } else if (stmt instanceof SqlStmt.AnonymousBlockStmt ab) {
            ProcAndDynamicSqlAnalyzer.analyzeAnonymousBlock(ab, ctx);
        } else if (stmt instanceof SqlStmt.DeclareVarStmt dv) {
            ProcAndDynamicSqlAnalyzer.analyzeDeclareVar(dv, ctx);
        } else if (stmt instanceof SqlStmt.AssignStmt asg) {
            ProcAndDynamicSqlAnalyzer.analyzeAssign(asg, ctx);
        } else if (stmt instanceof SqlStmt.OpenCursorStmt oc) {
            ProcAndDynamicSqlAnalyzer.analyzeOpenCursor(oc, ctx);
        } else if (stmt instanceof SqlStmt.FetchCursorStmt fc) {
            ProcAndDynamicSqlAnalyzer.analyzeFetchCursor(fc, ctx);
        } else if (stmt instanceof SqlStmt.ForLoopStmt fl) {
            ProcAndDynamicSqlAnalyzer.analyzeForLoop(fl, ctx);
        } else if (stmt instanceof SqlStmt.IfStmt || stmt instanceof SqlStmt.WhileLoopStmt) {
            ProcAndDynamicSqlAnalyzer.analyzeControlFlow(stmt, ctx);
        } else if (stmt instanceof SqlStmt.ReturnStmt ret) {
            ProcAndDynamicSqlAnalyzer.analyzeReturn(ret, ctx);
        } else if (stmt instanceof SqlStmt.CallStmt call) {
            ProcAndDynamicSqlAnalyzer.analyzeCall(call, ctx);
        } else if (stmt instanceof SqlStmt.DynamicSqlStmt dyn) {
            ProcAndDynamicSqlAnalyzer.analyzeDynamicSql(dyn, ctx);
        } else if (stmt instanceof SqlStmt.PowerQueryLetStmt pq) {
            ProcAndDynamicSqlAnalyzer.analyzePowerQuery(pq, ctx);
        } else if (stmt instanceof SqlStmt.MdxSelectStmt mdx) {
            ProcAndDynamicSqlAnalyzer.analyzeMdx(mdx, ctx);
        }
    }

    private static AnalyzerContext maybeCreateProcess(SqlStmt stmt, AnalyzerContext ctx) {
        if (!ctx.option().isTraceSQL() || ctx.activeProcess() != null) {
            return ctx;
        }
        String pType = classifyProcessType(stmt);
        if (pType == null) {
            return ctx;
        }
        String pName = ctx.enclosingRoutine() != null
                ? ctx.enclosingRoutine().getName() + "." + pType
                : pType + "-" + (ctx.mgr().processes().size() + 1);
        BoundProcess proc = ctx.mgr().createProcess(
                pName, pType, ctx.enclosingRoutine(), ctx.sqlHash(), stmt.span(), ctx.extractSql(stmt.span()));
        return ctx.withProcess(proc);
    }

    private static String classifyProcessType(SqlStmt stmt) {
        if (stmt instanceof SqlStmt.QueryStmt) return "select";
        if (stmt instanceof SqlStmt.InsertStmt || stmt instanceof SqlStmt.MultiInsertStmt) return "insert";
        if (stmt instanceof SqlStmt.UpdateStmt) return "update";
        if (stmt instanceof SqlStmt.DeleteStmt) return "delete";
        if (stmt instanceof SqlStmt.MergeStmt) return "merge";
        if (stmt instanceof SqlStmt.CopyIoStmt) return "copy";
        if (stmt instanceof SqlStmt.CreateTableStmt ct && ct.asSelect() != null) return "create_table";
        if (stmt instanceof SqlStmt.CreateViewStmt) return "create_view";
        return null;
    }
}
