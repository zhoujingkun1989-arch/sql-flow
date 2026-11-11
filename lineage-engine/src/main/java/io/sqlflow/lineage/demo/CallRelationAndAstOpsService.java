package io.sqlflow.lineage.demo;

import io.sqlflow.contracts.lineage.Coordinate;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.AstSqlRequest;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.CallEdge;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.CallRelationResult;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.ColumnImpactResult;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.ConstantItem;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.FindConstantsResult;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.JoinConvertRequest;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.RemoveSchemaRequest;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.RenameColumnRequest;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.RenameTableRequest;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.RewriteSqlResult;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.XmlAstResult;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.lineage.ast.Expr;
import io.sqlflow.lineage.ast.QueryAst.JoinClause;
import io.sqlflow.lineage.ast.QueryAst.NamedTableSource;
import io.sqlflow.lineage.ast.QueryAst.QueryExpr;
import io.sqlflow.lineage.ast.QueryAst.SelectItem;
import io.sqlflow.lineage.ast.QueryAst.SelectQuery;
import io.sqlflow.lineage.ast.QueryAst.SetOpQuery;
import io.sqlflow.lineage.ast.QueryAst.SubqueryTableSource;
import io.sqlflow.lineage.ast.QueryAst.TableSource;
import io.sqlflow.lineage.ast.SqlStmt;
import io.sqlflow.lineage.demo.AstXmlAndOpsHelper.ExpressionAnalysisItem;
import io.sqlflow.lineage.demo.AstXmlAndOpsHelper.JoinInfo;
import io.sqlflow.lineage.demo.AstXmlAndOpsHelper.WhereConditionInfo;
import io.sqlflow.lineage.lexer.SourceSpan;
import io.sqlflow.lineage.lexer.SqlDialectRules;
import io.sqlflow.lineage.lexer.SqlLexer;
import io.sqlflow.lineage.lexer.SqlToken;
import io.sqlflow.lineage.lexer.TokenType;
import io.sqlflow.lineage.parser.StatementParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Routine call hierarchy analyzer ({@code /demo/callRelation}) and AST inspection/refactoring
 * service ({@code 02 §4.9}, {@code OBL-065}..{@code OBL-066}).
 */
public final class CallRelationAndAstOpsService {

    private final SyntaxAndKeywordService syntaxService;
    private final TableColumnInspector tableInspector;

    public CallRelationAndAstOpsService(
            SyntaxAndKeywordService syntaxService,
            TableColumnInspector tableInspector) {
        this.syntaxService = syntaxService;
        this.tableInspector = tableInspector;
    }

    public CallRelationResult callRelation(AstSqlRequest request) {
        String sql = request != null && request.getSql() != null ? request.getSql() : "";
        EDbVendor vendor = request != null && request.getDbvendor() != null
                ? request.getDbvendor()
                : EDbVendor.dbvoracle;

        StatementParser.ParsedScript parsed = SyntaxAndKeywordService.parseScriptPackageAware(sql, vendor);
        Set<String> declaredRoutines = new LinkedHashSet<>();
        for (SqlStmt stmt : parsed.statements()) {
            collectDeclaredRoutines(stmt, null, declaredRoutines);
        }

        List<CallEdge> edges = new ArrayList<>();
        for (SqlStmt stmt : parsed.statements()) {
            walkCallsInStmt(stmt, "anonymous", null, vendor, declaredRoutines, edges);
        }

        StringBuilder xml = new StringBuilder();
        xml.append("<callRelations count=\"").append(edges.size()).append("\">");
        for (CallEdge e : edges) {
            String coordAttr = Coordinate.formatXmlCoordinate(e.getCoordinates());
            xml.append("<call caller=\"").append(AstXmlAndOpsHelper.esc(e.getCaller()))
                    .append("\" callee=\"").append(AstXmlAndOpsHelper.esc(e.getCallee()))
                    .append("\" type=\"").append(AstXmlAndOpsHelper.esc(e.getCallType())).append("\"");
            if (coordAttr != null) {
                xml.append(" coordinate=\"").append(AstXmlAndOpsHelper.esc(coordAttr)).append("\"");
            }
            xml.append("/>");
        }
        xml.append("</callRelations>");

        CallRelationResult res = new CallRelationResult();
        res.setCalls(edges);
        res.setXml(xml.toString());
        return res;
    }

    public ColumnImpactResult columnImpact(AstSqlRequest request) {
        String sql = request != null && request.getSql() != null ? request.getSql() : "";
        EDbVendor vendor = request != null && request.getDbvendor() != null
                ? request.getDbvendor()
                : EDbVendor.dbvoracle;

        Map<String, LinkedHashSet<String>> impactMap = new LinkedHashMap<>();
        TableColumnInspector.DetailedInspection base = tableInspector.inspect(sql, vendor, null);
        for (Map.Entry<String, List<String>> e : base.tables().entrySet()) {
            impactMap.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
        }

        StatementParser.ParsedScript parsed = SyntaxAndKeywordService.parseScriptPackageAware(sql, vendor);
        for (SqlStmt stmt : parsed.statements()) {
            collectProjectionImpacts(stmt, impactMap);
        }

        Map<String, List<String>> outMap = new LinkedHashMap<>();
        StringBuilder xml = new StringBuilder("<columnImpact>");
        for (Map.Entry<String, LinkedHashSet<String>> e : impactMap.entrySet()) {
            List<String> list = new ArrayList<>(e.getValue());
            outMap.put(e.getKey(), list);
            xml.append("<source name=\"").append(AstXmlAndOpsHelper.esc(e.getKey())).append("\">");
            for (String item : list) {
                xml.append("<target name=\"").append(AstXmlAndOpsHelper.esc(item)).append("\"/>");
            }
            xml.append("</source>");
        }
        xml.append("</columnImpact>");

        ColumnImpactResult res = new ColumnImpactResult();
        res.setImpacts(outMap);
        res.setXml(xml.toString());
        return res;
    }

    public FindConstantsResult findConstants(AstSqlRequest request) {
        String sql = request != null && request.getSql() != null ? request.getSql() : "";
        EDbVendor vendor = request != null && request.getDbvendor() != null
                ? request.getDbvendor()
                : EDbVendor.dbvoracle;
        List<ConstantItem> constants = new ArrayList<>();
        for (SqlToken tok : SqlLexer.tokenize(sql, vendor).tokens()) {
            if (tok.isEof()) {
                break;
            }
            Coordinate coord = Coordinate.of(tok.line(), tok.column());
            if (tok.type() == TokenType.STRING_LITERAL) {
                constants.add(new ConstantItem(tok.rawText(), "string", coord));
            } else if (tok.type() == TokenType.NUMBER_LITERAL) {
                constants.add(new ConstantItem(tok.rawText(), "number", coord));
            } else if (!tok.quoted() && tok.isAnyWord("TRUE", "FALSE")) {
                constants.add(new ConstantItem(tok.rawText(), "boolean", coord));
            } else if (!tok.quoted() && tok.isWord("NULL")) {
                constants.add(new ConstantItem(tok.rawText(), "null", coord));
            }
        }
        return new FindConstantsResult(constants);
    }

    public RewriteSqlResult joinConvert(JoinConvertRequest request) {
        return SqlRefactorHelper.joinConvert(request);
    }

    public RewriteSqlResult renameTable(RenameTableRequest request) {
        return SqlRefactorHelper.renameTable(request);
    }

    public RewriteSqlResult renameColumn(RenameColumnRequest request) {
        return SqlRefactorHelper.renameColumn(request);
    }

    public RewriteSqlResult removeSchema(RemoveSchemaRequest request) {
        return SqlRefactorHelper.removeSchema(request);
    }

    public XmlAstResult toXml(AstSqlRequest request) {
        return AstXmlAndOpsHelper.toXml(request, syntaxService);
    }

    public List<JoinInfo> extractJoins(AstSqlRequest request) {
        return AstXmlAndOpsHelper.extractJoins(request);
    }

    public List<WhereConditionInfo> extractWhereConditions(AstSqlRequest request) {
        return AstXmlAndOpsHelper.extractWhereConditions(request);
    }

    public RewriteSqlResult removeCondition(AstSqlRequest request, String columnOrParam) {
        return AstXmlAndOpsHelper.removeCondition(request, columnOrParam);
    }

    public RewriteSqlResult generateAlterScript(AstSqlRequest sourceDdl, AstSqlRequest targetDdl) {
        return AstXmlAndOpsHelper.generateAlterScript(sourceDdl, targetDdl);
    }

    public RewriteSqlResult sqlTranslate(AstSqlRequest request, EDbVendor targetVendor) {
        return AstXmlAndOpsHelper.sqlTranslate(request, targetVendor);
    }

    public List<ExpressionAnalysisItem> analyzeExpressions(AstSqlRequest request) {
        return AstXmlAndOpsHelper.analyzeExpressions(request);
    }

    private static void collectDeclaredRoutines(SqlStmt stmt, String pkgPrefix, Set<String> out) {
        if (stmt instanceof SqlStmt.CreateRoutineStmt cr) {
            String name = cr.routineName().fullDisplayName();
            out.add(name.toLowerCase(Locale.ROOT));
            out.add(cr.routineName().lastName().toLowerCase(Locale.ROOT));
            if (pkgPrefix != null) {
                out.add((pkgPrefix + "." + cr.routineName().lastName()).toLowerCase(Locale.ROOT));
            }
        } else if (stmt instanceof SqlStmt.CreatePackageStmt cp) {
            String pkg = cp.packageName().fullDisplayName();
            for (SqlStmt.CreateRoutineStmt r : cp.routines()) {
                collectDeclaredRoutines(r, pkg, out);
            }
        }
    }

    private static void walkCallsInStmt(
            SqlStmt stmt,
            String caller,
            String currentPkg,
            EDbVendor vendor,
            Set<String> declaredRoutines,
            List<CallEdge> edges) {
        if (stmt == null) {
            return;
        }
        if (stmt instanceof SqlStmt.CreatePackageStmt cp) {
            String pkg = cp.packageName().fullDisplayName();
            for (SqlStmt.CreateRoutineStmt r : cp.routines()) {
                walkCallsInStmt(r, pkg + "." + r.routineName().lastName(), pkg, vendor, declaredRoutines, edges);
            }
            for (SqlStmt init : cp.initStatements()) {
                walkCallsInStmt(init, pkg, pkg, vendor, declaredRoutines, edges);
            }
        } else if (stmt instanceof SqlStmt.CreateRoutineStmt cr) {
            String routineCaller = currentPkg != null && !cr.routineName().fullDisplayName().contains(".")
                    ? currentPkg + "." + cr.routineName().lastName()
                    : cr.routineName().fullDisplayName();
            for (SqlStmt.DeclareVarStmt d : cr.declarations()) {
                walkCallsInQuery(d.cursorQuery(), routineCaller, currentPkg, declaredRoutines, edges);
                walkCallsInExpr(d.defaultExpr(), routineCaller, currentPkg, declaredRoutines, edges);
            }
            for (SqlStmt b : cr.bodyStatements()) {
                walkCallsInStmt(b, routineCaller, currentPkg, vendor, declaredRoutines, edges);
            }
        } else if (stmt instanceof SqlStmt.AnonymousBlockStmt ab) {
            for (SqlStmt b : ab.bodyStatements()) {
                walkCallsInStmt(b, caller, currentPkg, vendor, declaredRoutines, edges);
            }
        } else if (stmt instanceof SqlStmt.CallStmt call) {
            String callee = qualifyPackageLocal(call.routineName().fullDisplayName(), currentPkg, declaredRoutines);
            String kind = callee.contains(".") ? "package_procedure" : "procedure";
            edges.add(makeCallEdge(caller, callee, kind, call.span()));
            for (Expr arg : call.arguments()) {
                walkCallsInExpr(arg, caller, currentPkg, declaredRoutines, edges);
            }
        } else if (stmt instanceof SqlStmt.DynamicSqlStmt dyn) {
            walkCallsInExpr(dyn.sqlExpr(), caller, currentPkg, declaredRoutines, edges);
            if (dyn.sqlExpr() instanceof Expr.LiteralExpr lit && "STRING".equals(lit.literalType())) {
                for (SqlStmt inner : StatementParser.parseScript(lit.value(), vendor).statements()) {
                    walkCallsInStmt(inner, caller, currentPkg, vendor, declaredRoutines, edges);
                }
            }
        } else if (stmt instanceof SqlStmt.AssignStmt asg) {
            walkCallsInExpr(asg.valueExpr(), caller, currentPkg, declaredRoutines, edges);
        } else if (stmt instanceof SqlStmt.ReturnStmt ret) {
            walkCallsInExpr(ret.returnExpr(), caller, currentPkg, declaredRoutines, edges);
            walkCallsInQuery(ret.returnQuery(), caller, currentPkg, declaredRoutines, edges);
        } else if (stmt instanceof SqlStmt.IfStmt ifs) {
            for (SqlStmt.ConditionalBranch br : ifs.branches()) {
                walkCallsInExpr(br.condition(), caller, currentPkg, declaredRoutines, edges);
                for (SqlStmt s : br.statements()) {
                    walkCallsInStmt(s, caller, currentPkg, vendor, declaredRoutines, edges);
                }
            }
            for (SqlStmt s : ifs.elseStatements()) {
                walkCallsInStmt(s, caller, currentPkg, vendor, declaredRoutines, edges);
            }
        } else if (stmt instanceof SqlStmt.WhileLoopStmt wl) {
            walkCallsInExpr(wl.condition(), caller, currentPkg, declaredRoutines, edges);
            for (SqlStmt s : wl.bodyStatements()) {
                walkCallsInStmt(s, caller, currentPkg, vendor, declaredRoutines, edges);
            }
        } else if (stmt instanceof SqlStmt.ForLoopStmt fl) {
            walkCallsInQuery(fl.inlineQuery(), caller, currentPkg, declaredRoutines, edges);
            for (SqlStmt s : fl.bodyStatements()) {
                walkCallsInStmt(s, caller, currentPkg, vendor, declaredRoutines, edges);
            }
        } else if (stmt instanceof SqlStmt.QueryStmt qs) {
            walkCallsInQuery(qs.query(), caller, currentPkg, declaredRoutines, edges);
        } else if (stmt instanceof SqlStmt.InsertStmt ins) {
            walkCallsInQuery(ins.sourceQuery(), caller, currentPkg, declaredRoutines, edges);
        } else if (stmt instanceof SqlStmt.CreateViewStmt cv) {
            walkCallsInQuery(cv.query(), cv.viewName().fullDisplayName(), currentPkg, declaredRoutines, edges);
        }
    }

    private static void walkCallsInQuery(
            QueryExpr q, String caller, String currentPkg, Set<String> declaredRoutines, List<CallEdge> edges) {
        if (q instanceof SelectQuery sq) {
            for (SelectItem si : sq.selectItems()) {
                walkCallsInExpr(si.expr(), caller, currentPkg, declaredRoutines, edges);
            }
            walkCallsInExpr(sq.whereExpr(), caller, currentPkg, declaredRoutines, edges);
            for (TableSource ts : sq.fromSources()) {
                if (ts instanceof SubqueryTableSource sub) {
                    walkCallsInQuery(sub.query(), caller, currentPkg, declaredRoutines, edges);
                }
            }
        } else if (q instanceof SetOpQuery so) {
            walkCallsInQuery(so.left(), caller, currentPkg, declaredRoutines, edges);
            walkCallsInQuery(so.right(), caller, currentPkg, declaredRoutines, edges);
        }
    }

    private static void walkCallsInExpr(
            Expr e, String caller, String currentPkg, Set<String> declaredRoutines, List<CallEdge> edges) {
        if (e == null) {
            return;
        }
        if (e instanceof Expr.FuncCallExpr fn) {
            String rawName = fn.name().fullDisplayName();
            if (!SqlDialectRules.isBuiltInFunction(rawName)
                    && (fn.name().size() >= 2 || declaredRoutines.contains(rawName.toLowerCase(Locale.ROOT)))) {
                String callee = qualifyPackageLocal(rawName, currentPkg, declaredRoutines);
                edges.add(makeCallEdge(caller, callee, "function", fn.span()));
            }
            for (Expr arg : fn.args()) {
                walkCallsInExpr(arg, caller, currentPkg, declaredRoutines, edges);
            }
        } else if (e instanceof Expr.BinaryExpr b) {
            walkCallsInExpr(b.left(), caller, currentPkg, declaredRoutines, edges);
            walkCallsInExpr(b.right(), caller, currentPkg, declaredRoutines, edges);
        } else if (e instanceof Expr.UnaryExpr u) {
            walkCallsInExpr(u.operand(), caller, currentPkg, declaredRoutines, edges);
        } else if (e instanceof Expr.CaseExpr ce) {
            walkCallsInExpr(ce.operand(), caller, currentPkg, declaredRoutines, edges);
            for (Expr.WhenClause wc : ce.whenClauses()) {
                walkCallsInExpr(wc.whenExpr(), caller, currentPkg, declaredRoutines, edges);
                walkCallsInExpr(wc.thenExpr(), caller, currentPkg, declaredRoutines, edges);
            }
            walkCallsInExpr(ce.elseExpr(), caller, currentPkg, declaredRoutines, edges);
        } else if (e instanceof Expr.SubqueryExpr sub) {
            walkCallsInQuery(sub.query(), caller, currentPkg, declaredRoutines, edges);
        }
    }

    private static String qualifyPackageLocal(String callee, String currentPkg, Set<String> declared) {
        if (currentPkg != null && !callee.contains(".")) {
            String candidate = currentPkg + "." + callee;
            if (declared.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
        return callee;
    }

    private static CallEdge makeCallEdge(String caller, String callee, String callType, SourceSpan span) {
        CallEdge edge = new CallEdge();
        edge.setCaller(caller);
        edge.setCallee(callee);
        edge.setCallType(callType);
        edge.setCoordinates(span != null && span.isValid()
                ? span.toCoordinates(null)
                : List.of(Coordinate.of(1L, 1L)));
        return edge;
    }

    private static void collectProjectionImpacts(
            SqlStmt stmt, Map<String, LinkedHashSet<String>> impactMap) {
        if (stmt instanceof SqlStmt.CreateViewStmt cv && cv.query() instanceof SelectQuery sq) {
            String viewName = cv.viewName().fullDisplayName();
            recordSelectQueryImpacts(sq, viewName, cv.columnNames(), impactMap);
        } else if (stmt instanceof SqlStmt.InsertStmt ins && ins.sourceQuery() instanceof SelectQuery sq) {
            recordSelectQueryImpacts(sq, ins.targetTable().fullDisplayName(), ins.targetColumns(), impactMap);
        } else if (stmt instanceof SqlStmt.QueryStmt qs && qs.query() instanceof SelectQuery sq) {
            recordSelectQueryImpacts(sq, "RS", List.of(), impactMap);
        }
    }

    private static void recordSelectQueryImpacts(
            SelectQuery sq, String targetPrefix, List<String> explicitCols,
            Map<String, LinkedHashSet<String>> impactMap) {
        Map<String, String> aliasToTbl = new LinkedHashMap<>();
        String singleTbl = null;
        for (TableSource ts : sq.fromSources()) {
            if (ts instanceof NamedTableSource nts) {
                String t = nts.tableName().fullDisplayName();
                singleTbl = sq.fromSources().size() == 1 && sq.joins().isEmpty() ? t : null;
                if (nts.alias() != null) {
                    aliasToTbl.put(nts.alias().toLowerCase(Locale.ROOT), t);
                }
            }
        }
        for (JoinClause jc : sq.joins()) {
            if (jc.right() instanceof NamedTableSource nts && nts.alias() != null) {
                aliasToTbl.put(nts.alias().toLowerCase(Locale.ROOT), nts.tableName().fullDisplayName());
            }
        }
        for (int i = 0; i < sq.selectItems().size(); i++) {
            SelectItem si = sq.selectItems().get(i);
            String outCol = i < explicitCols.size() ? explicitCols.get(i) : si.effectiveOutputName();
            String target = targetPrefix + "." + outCol;
            Set<String> cols = new LinkedHashSet<>();
            collectSourceCols(si.expr(), aliasToTbl, singleTbl, cols);
            for (String src : cols) {
                impactMap.computeIfAbsent(src, k -> new LinkedHashSet<>()).add(target);
            }
        }
    }

    private static void collectSourceCols(
            Expr e, Map<String, String> aliasMap, String singleTbl, Set<String> out) {
        if (e == null) {
            return;
        }
        if (e instanceof Expr.ColumnRefExpr c) {
            if (c.name().size() >= 2) {
                String q = c.name().parts().get(c.name().size() - 2).value().toLowerCase(Locale.ROOT);
                String tbl = aliasMap.getOrDefault(q, c.name().parts().get(c.name().size() - 2).value());
                out.add(tbl + "." + c.name().lastName());
            } else if (singleTbl != null) {
                out.add(singleTbl + "." + c.name().lastName());
            }
        } else if (e instanceof Expr.BinaryExpr b) {
            collectSourceCols(b.left(), aliasMap, singleTbl, out);
            collectSourceCols(b.right(), aliasMap, singleTbl, out);
        } else if (e instanceof Expr.FuncCallExpr fn) {
            for (Expr a : fn.args()) {
                collectSourceCols(a, aliasMap, singleTbl, out);
            }
        } else if (e instanceof Expr.CaseExpr ce) {
            collectSourceCols(ce.operand(), aliasMap, singleTbl, out);
            for (Expr.WhenClause wc : ce.whenClauses()) {
                collectSourceCols(wc.whenExpr(), aliasMap, singleTbl, out);
                collectSourceCols(wc.thenExpr(), aliasMap, singleTbl, out);
            }
            collectSourceCols(ce.elseExpr(), aliasMap, singleTbl, out);
        } else if (e instanceof Expr.CastExpr c) {
            collectSourceCols(c.expr(), aliasMap, singleTbl, out);
        }
    }
}
