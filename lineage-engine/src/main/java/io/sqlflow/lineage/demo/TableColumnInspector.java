package io.sqlflow.lineage.demo;

import io.sqlflow.contracts.lineage.Coordinate;
import io.sqlflow.contracts.lineage.ErrorItem;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.AstSqlRequest;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.TableColumnResult;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.lineage.ast.Expr;
import io.sqlflow.lineage.ast.QualifiedName;
import io.sqlflow.lineage.ast.QueryAst.CteDef;
import io.sqlflow.lineage.ast.QueryAst.JoinClause;
import io.sqlflow.lineage.ast.QueryAst.NamedTableSource;
import io.sqlflow.lineage.ast.QueryAst.OrderByItem;
import io.sqlflow.lineage.ast.QueryAst.ParenthesizedJoinSource;
import io.sqlflow.lineage.ast.QueryAst.QueryExpr;
import io.sqlflow.lineage.ast.QueryAst.SelectItem;
import io.sqlflow.lineage.ast.QueryAst.SelectQuery;
import io.sqlflow.lineage.ast.QueryAst.SetOpQuery;
import io.sqlflow.lineage.ast.QueryAst.SubqueryTableSource;
import io.sqlflow.lineage.ast.QueryAst.TableSource;
import io.sqlflow.lineage.ast.QueryAst.ValuesQuery;
import io.sqlflow.lineage.ast.SqlStmt;
import io.sqlflow.lineage.lexer.SourceSpan;
import io.sqlflow.lineage.parser.StatementParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves physical tables, column usages, table aliases, statement CRUD types, orphan columns,
 * and summary/detail CSV exports ({@code 02 §4.9 #3, #12}, {@code OBL-062}).
 */
public final class TableColumnInspector {

    private static final Set<String> PSEUDO_CONST_IDENTIFIERS = Set.of(
            "SYSDATE", "SYSTIMESTAMP", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP",
            "USER", "CURRENT_USER", "SESSION_USER", "SYSTEM_USER", "ROWNUM", "ROWID", "LEVEL", "DUAL");

    public record StatementCrudSummary(
            String crudOperation,
            List<String> targetTables,
            List<String> sourceTables
    ) {
    }

    public record ColumnUsageDetail(
            String table,
            String column,
            String dataType,
            String effectType,
            boolean joinColumn,
            String coordinates
    ) {
    }

    public record DetailedInspection(
            Map<String, List<String>> tables,
            List<String> orphanColumns,
            List<StatementCrudSummary> statementSummaries,
            List<ColumnUsageDetail> columnDetails,
            List<ErrorItem> errors
    ) {
    }

    public TableColumnResult getTableColumns(AstSqlRequest request) {
        return tableColumn(request);
    }

    public TableColumnResult tableColumn(AstSqlRequest request) {
        String sql = request != null ? request.getSql() : "";
        EDbVendor vendor = request != null && request.getDbvendor() != null
                ? request.getDbvendor()
                : EDbVendor.dbvoracle;
        DetailedInspection detail = inspect(sql, vendor, null);
        TableColumnResult res = new TableColumnResult();
        res.setTables(detail.tables());
        res.setOrphanColumns(detail.orphanColumns());
        if (!detail.errors().isEmpty()) {
            res.setErrors(detail.errors());
        }
        return res;
    }

    public String formatCsv(String sql, EDbVendor vendor, String mode, String delimiter) {
        DetailedInspection detail = inspect(sql, vendor != null ? vendor : EDbVendor.dbvoracle, null);
        String delim = (delimiter == null || delimiter.isEmpty()) ? "," : ("\\t".equals(delimiter) ? "\t" : delimiter);
        boolean detailMode = "detail".equalsIgnoreCase(mode);
        StringBuilder sb = new StringBuilder();
        if (detailMode) {
            sb.append(csvCell("table")).append(delim)
                    .append(csvCell("column")).append(delim)
                    .append(csvCell("dataType")).append(delim)
                    .append(csvCell("effectType")).append(delim)
                    .append(csvCell("joinColumn")).append(delim)
                    .append(csvCell("coordinates")).append('\n');
            for (ColumnUsageDetail d : detail.columnDetails()) {
                sb.append(csvCell(d.table())).append(delim)
                        .append(csvCell(d.column())).append(delim)
                        .append(csvCell(d.dataType())).append(delim)
                        .append(csvCell(d.effectType())).append(delim)
                        .append(csvCell(String.valueOf(d.joinColumn()))).append(delim)
                        .append(csvCell(d.coordinates())).append('\n');
            }
        } else {
            sb.append(csvCell("table")).append(delim)
                    .append(csvCell("column")).append(delim)
                    .append(csvCell("dataType")).append('\n');
            Map<String, String> types = new LinkedHashMap<>();
            for (ColumnUsageDetail d : detail.columnDetails()) {
                if (d.dataType() != null && !d.dataType().isEmpty()) {
                    types.putIfAbsent(d.table() + "." + d.column(), d.dataType());
                }
            }
            for (Map.Entry<String, List<String>> entry : detail.tables().entrySet()) {
                String tbl = entry.getKey();
                if (entry.getValue().isEmpty()) {
                    sb.append(csvCell(tbl)).append(delim).append(csvCell("")).append(delim).append(csvCell("")).append('\n');
                } else {
                    for (String col : entry.getValue()) {
                        String dt = types.getOrDefault(tbl + "." + col, "");
                        sb.append(csvCell(tbl)).append(delim).append(csvCell(col)).append(delim).append(csvCell(dt)).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    public DetailedInspection inspect(String sql, EDbVendor vendor, String metadataTriples) {
        Map<String, LinkedHashSet<String>> tableCols = new LinkedHashMap<>();
        LinkedHashSet<String> orphans = new LinkedHashSet<>();
        List<StatementCrudSummary> crudSummaries = new ArrayList<>();
        List<ColumnUsageDetail> details = new ArrayList<>();
        List<ErrorItem> errors = new ArrayList<>();
        Map<String, LinkedHashSet<String>> schemaCatalog = new LinkedHashMap<>();
        Map<String, String> dataTypeCatalog = new LinkedHashMap<>();

        loadMetadataTriples(metadataTriples, schemaCatalog);
        if (sql == null || sql.isBlank()) {
            return new DetailedInspection(Map.of(), List.of(), List.of(), List.of(), List.of());
        }

        StatementParser.ParsedScript parsed = SyntaxAndKeywordService.parseScriptPackageAware(sql, vendor);
        Ctx ctx = new Ctx(tableCols, orphans, crudSummaries, details, errors, schemaCatalog, dataTypeCatalog);
        for (SqlStmt stmt : parsed.statements()) {
            inspectStmt(stmt, ctx, Scope.root());
        }

        Map<String, List<String>> outTables = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> e : tableCols.entrySet()) {
            outTables.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return new DetailedInspection(outTables, new ArrayList<>(orphans), crudSummaries, details, errors);
    }

    private void inspectStmt(SqlStmt stmt, Ctx ctx, Scope parentScope) {
        if (stmt == null) {
            return;
        }
        if (stmt instanceof SqlStmt.ErrorStmt err) {
            ctx.errors.add(new ErrorItem(err.errorType(), err.message(), err.span().toCoordinates(null)));
        } else if (stmt instanceof SqlStmt.QueryStmt qs) {
            Set<String> srcs = inspectQuery(qs.query(), ctx, parentScope, "select");
            ctx.crudSummaries.add(new StatementCrudSummary("SELECT", List.of(), new ArrayList<>(srcs)));
        } else if (stmt instanceof SqlStmt.InsertStmt ins) {
            String tgt = registerTableName(ins.targetTable(), ctx);
            for (int i = 0; i < ins.targetColumns().size(); i++) {
                SourceSpan sp = i < ins.targetColumnSpans().size() ? ins.targetColumnSpans().get(i) : ins.span();
                recordCol(tgt, ins.targetColumns().get(i), "insert", false, sp, ctx);
            }
            Set<String> srcs = ins.sourceQuery() != null ? inspectQuery(ins.sourceQuery(), ctx, parentScope, "select") : Set.of();
            ctx.crudSummaries.add(new StatementCrudSummary("INSERT", List.of(tgt), new ArrayList<>(srcs)));
        } else if (stmt instanceof SqlStmt.MultiInsertStmt mi) {
            Scope sc = Scope.child(parentScope);
            Set<String> srcs = new LinkedHashSet<>();
            registerSourcesAndJoins(mi.sharedFromSources(), mi.sharedJoins(), sc, ctx, srcs);
            inspectExpr(mi.sharedWhere(), sc, ctx, "where", false);
            if (mi.sharedSourceQuery() != null) {
                srcs.addAll(inspectQuery(mi.sharedSourceQuery(), ctx, sc, "select"));
            }
            List<String> tgts = new ArrayList<>();
            for (SqlStmt.InsertBranch br : mi.branches()) {
                String t = registerTableName(br.targetTable(), ctx);
                tgts.add(t);
                for (String c : br.targetColumns()) {
                    recordCol(t, c, "insert", false, br.span(), ctx);
                }
                for (Expr v : br.explicitValues()) {
                    inspectExpr(v, sc, ctx, "insert", false);
                }
                if (br.branchQuery() != null) {
                    srcs.addAll(inspectQuery(br.branchQuery(), ctx, sc, "select"));
                }
            }
            ctx.crudSummaries.add(new StatementCrudSummary("INSERT", tgts, new ArrayList<>(srcs)));
        } else if (stmt instanceof SqlStmt.UpdateStmt upd) {
            Scope sc = Scope.child(parentScope);
            Set<String> tgts = new LinkedHashSet<>();
            Set<String> srcs = new LinkedHashSet<>();
            registerSourcesAndJoins(upd.targetTables(), upd.targetJoins(), sc, ctx, tgts);
            registerSourcesAndJoins(upd.fromSources(), upd.fromJoins(), sc, ctx, srcs);
            String defaultTarget = tgts.isEmpty() ? null : tgts.iterator().next();
            for (SqlStmt.AssignmentItem asg : upd.assignments()) {
                for (QualifiedName lhs : asg.targetColumns()) {
                    resolveQualifiedCol(lhs, sc, defaultTarget, "update", false, ctx);
                }
                inspectExpr(asg.valueExpr(), sc, ctx, "update", false);
                if (asg.tupleSubquery() != null) {
                    srcs.addAll(inspectQuery(asg.tupleSubquery(), ctx, sc, "select"));
                }
            }
            inspectExpr(upd.whereExpr(), sc, ctx, "where", false);
            ctx.crudSummaries.add(new StatementCrudSummary("UPDATE", new ArrayList<>(tgts), new ArrayList<>(srcs)));
        } else if (stmt instanceof SqlStmt.DeleteStmt del) {
            Scope sc = Scope.child(parentScope);
            String tgt = registerTableName(del.targetTable(), ctx);
            if (tgt != null) {
                sc.addPhysicalTable(tgt, del.targetAlias());
            }
            Set<String> srcs = new LinkedHashSet<>();
            registerSourcesAndJoins(del.usingSources(), del.joins(), sc, ctx, srcs);
            inspectExpr(del.whereExpr(), sc, ctx, "delete", false);
            ctx.crudSummaries.add(new StatementCrudSummary("DELETE", tgt != null ? List.of(tgt) : List.of(), new ArrayList<>(srcs)));
        } else if (stmt instanceof SqlStmt.MergeStmt mrg) {
            Scope sc = Scope.child(parentScope);
            String tgt = registerTableName(mrg.targetTable(), ctx);
            if (tgt != null) {
                sc.addPhysicalTable(tgt, mrg.targetAlias());
            }
            Set<String> srcs = new LinkedHashSet<>();
            registerSource(mrg.usingSource(), sc, ctx, srcs);
            inspectExpr(mrg.onCondition(), sc, ctx, "merge", true);
            for (SqlStmt.MergeWhenClause wc : mrg.whenClauses()) {
                inspectExpr(wc.andCondition(), sc, ctx, "merge", false);
                for (SqlStmt.AssignmentItem asg : wc.updateAssignments()) {
                    for (QualifiedName lhs : asg.targetColumns()) {
                        resolveQualifiedCol(lhs, sc, tgt, "update", false, ctx);
                    }
                    inspectExpr(asg.valueExpr(), sc, ctx, "update", false);
                }
                for (String ic : wc.insertColumns()) {
                    recordCol(tgt, ic, "insert", false, wc.span(), ctx);
                }
                for (Expr iv : wc.insertValues()) {
                    inspectExpr(iv, sc, ctx, "insert", false);
                }
                inspectExpr(wc.deleteWhere(), sc, ctx, "delete", false);
            }
            ctx.crudSummaries.add(new StatementCrudSummary("MERGE", tgt != null ? List.of(tgt) : List.of(), new ArrayList<>(srcs)));
        } else if (stmt instanceof SqlStmt.CreateTableStmt ct) {
            String tbl = registerTableName(ct.tableName(), ctx);
            for (SqlStmt.ColumnDef col : ct.columns()) {
                ctx.schemaCatalog.computeIfAbsent(tbl.toLowerCase(Locale.ROOT), k -> new LinkedHashSet<>())
                        .add(col.name().toLowerCase(Locale.ROOT));
                if (col.dataType() != null) {
                    ctx.dataTypeCatalog.put(tbl + "." + col.name(), col.dataType());
                }
                recordCol(tbl, col.name(), "create_table", false, col.span(), ctx);
            }
            Set<String> srcs = ct.asSelect() != null ? inspectQuery(ct.asSelect(), ctx, parentScope, "select") : Set.of();
            ctx.crudSummaries.add(new StatementCrudSummary("CREATE_TABLE", List.of(tbl), new ArrayList<>(srcs)));
        } else if (stmt instanceof SqlStmt.CreateViewStmt cv) {
            String view = registerTableName(cv.viewName(), ctx);
            for (String col : cv.columnNames()) {
                recordCol(view, col, "create_view", false, cv.span(), ctx);
            }
            Set<String> srcs = inspectQuery(cv.query(), ctx, parentScope, "select");
            ctx.crudSummaries.add(new StatementCrudSummary("CREATE_VIEW", List.of(view), new ArrayList<>(srcs)));
        } else if (stmt instanceof SqlStmt.AlterTableStmt alt) {
            String tbl = registerTableName(alt.tableName(), ctx);
            for (SqlStmt.AlterAction act : alt.actions()) {
                if (act.addedColumn() != null) {
                    recordCol(tbl, act.addedColumn().name(), "alter_table", false, act.span(), ctx);
                } else if (act.droppedOrOldColumn() != null) {
                    recordCol(tbl, act.droppedOrOldColumn(), "alter_table", false, act.span(), ctx);
                }
            }
            ctx.crudSummaries.add(new StatementCrudSummary("ALTER_TABLE", List.of(tbl), List.of()));
        } else if (stmt instanceof SqlStmt.DropStmt drop) {
            List<String> tgts = new ArrayList<>();
            for (QualifiedName n : drop.names()) {
                tgts.add(registerTableName(n, ctx));
            }
            ctx.crudSummaries.add(new StatementCrudSummary("DROP", tgts, List.of()));
        } else if (stmt instanceof SqlStmt.TruncateStmt tr) {
            String tbl = registerTableName(tr.tableName(), ctx);
            ctx.crudSummaries.add(new StatementCrudSummary("TRUNCATE", List.of(tbl), List.of()));
        } else if (stmt instanceof SqlStmt.CreateRoutineStmt cr) {
            for (SqlStmt b : cr.bodyStatements()) {
                inspectStmt(b, ctx, parentScope);
            }
        } else if (stmt instanceof SqlStmt.CreatePackageStmt cp) {
            for (SqlStmt.CreateRoutineStmt r : cp.routines()) {
                inspectStmt(r, ctx, parentScope);
            }
        } else if (stmt instanceof SqlStmt.AnonymousBlockStmt ab) {
            for (SqlStmt b : ab.bodyStatements()) {
                inspectStmt(b, ctx, parentScope);
            }
        }
    }

    private Set<String> inspectQuery(QueryExpr query, Ctx ctx, Scope parentScope, String effect) {
        if (query == null) {
            return Set.of();
        }
        if (query instanceof SetOpQuery setOp) {
            Scope cteScope = Scope.child(parentScope);
            registerCtes(setOp.ctes(), cteScope, ctx);
            Set<String> all = new LinkedHashSet<>(inspectQuery(setOp.left(), ctx, cteScope, effect));
            all.addAll(inspectQuery(setOp.right(), ctx, cteScope, effect));
            return all;
        }
        if (query instanceof ValuesQuery vq) {
            for (List<Expr> row : vq.rows()) {
                for (Expr e : row) {
                    inspectExpr(e, parentScope, ctx, effect, false);
                }
            }
            return Set.of();
        }
        SelectQuery sq = (SelectQuery) query;
        Scope sc = Scope.child(parentScope);
        registerCtes(sq.ctes(), sc, ctx);

        Set<String> physicalInQuery = new LinkedHashSet<>();
        registerSourcesAndJoins(sq.fromSources(), sq.joins(), sc, ctx, physicalInQuery);

        for (SelectItem item : sq.selectItems()) {
            if (item.alias() != null && !item.alias().isEmpty()) {
                sc.selectAliases.add(item.alias().toLowerCase(Locale.ROOT));
            }
            inspectExpr(item.expr(), sc, ctx, "select", false);
        }
        inspectExpr(sq.whereExpr(), sc, ctx, "where", false);
        for (Expr g : sq.groupByExprs()) {
            inspectExpr(g, sc, ctx, "groupby", false);
        }
        inspectExpr(sq.havingExpr(), sc, ctx, "having", false);
        inspectExpr(sq.qualifyExpr(), sc, ctx, "qualify", false);
        for (OrderByItem ob : sq.orderBy()) {
            inspectExpr(ob.expr(), sc, ctx, "orderby", false);
        }
        return physicalInQuery;
    }

    private void registerCtes(List<CteDef> ctes, Scope sc, Ctx ctx) {
        for (CteDef cte : ctes) {
            Set<String> underlying = inspectQuery(cte.query(), ctx, sc, "select");
            sc.cteUnderlyingTables.put(cte.name().toLowerCase(Locale.ROOT), underlying);
        }
    }

    private void registerSourcesAndJoins(
            List<TableSource> sources, List<JoinClause> joins, Scope sc, Ctx ctx, Set<String> outPhysical) {
        for (TableSource ts : sources) {
            registerSource(ts, sc, ctx, outPhysical);
        }
        for (JoinClause jc : joins) {
            registerSource(jc.right(), sc, ctx, outPhysical);
        }
        for (JoinClause jc : joins) {
            inspectExpr(jc.onCondition(), sc, ctx, "join", true);
            for (String uCol : jc.usingColumns()) {
                for (String pt : sc.physicalTables) {
                    recordCol(pt, uCol, "join", true, jc.span(), ctx);
                }
            }
        }
    }

    private void registerSource(TableSource ts, Scope sc, Ctx ctx, Set<String> outPhysical) {
        if (ts == null) {
            return;
        }
        if (ts instanceof NamedTableSource nts) {
            String rawFull = nts.tableName().fullDisplayName();
            String lower = rawFull.toLowerCase(Locale.ROOT);
            Set<String> cteBacking = sc.lookupCte(lower);
            if (cteBacking != null) {
                sc.virtualAliases.put(lower, cteBacking);
                if (nts.alias() != null && !nts.alias().isBlank()) {
                    sc.virtualAliases.put(nts.alias().toLowerCase(Locale.ROOT), cteBacking);
                }
                outPhysical.addAll(cteBacking);
                return;
            }
            if (PSEUDO_CONST_IDENTIFIERS.contains(rawFull.toUpperCase(Locale.ROOT))) {
                return;
            }
            String tbl = registerTableName(nts.tableName(), ctx);
            sc.addPhysicalTable(tbl, nts.alias());
            outPhysical.add(tbl);
        } else if (ts instanceof SubqueryTableSource sub) {
            Set<String> subTables = inspectQuery(sub.query(), ctx, sc, "select");
            if (sub.alias() != null && !sub.alias().isBlank()) {
                sc.virtualAliases.put(sub.alias().toLowerCase(Locale.ROOT), subTables);
            }
            outPhysical.addAll(subTables);
        } else if (ts instanceof ParenthesizedJoinSource pjs) {
            registerSource(pjs.left(), sc, ctx, outPhysical);
            for (JoinClause jc : pjs.joins()) {
                registerSource(jc.right(), sc, ctx, outPhysical);
                inspectExpr(jc.onCondition(), sc, ctx, "join", true);
            }
        }
    }

    private void inspectExpr(Expr expr, Scope sc, Ctx ctx, String effect, boolean joinCol) {
        if (expr == null) {
            return;
        }
        if (expr instanceof Expr.ColumnRefExpr colRef) {
            resolveQualifiedCol(colRef.name(), sc, null, effect, joinCol, ctx);
        } else if (expr instanceof Expr.StarExpr star && star.qualifier() != null && !star.qualifier().isEmpty()) {
            String qual = star.qualifier().fullDisplayName();
            String resolved = sc.resolveQualifier(qual);
            if (resolved != null) {
                recordCol(resolved, "*", effect, joinCol, star.span(), ctx);
            }
        } else if (expr instanceof Expr.BinaryExpr bin) {
            inspectExpr(bin.left(), sc, ctx, effect, joinCol);
            inspectExpr(bin.right(), sc, ctx, effect, joinCol);
        } else if (expr instanceof Expr.UnaryExpr un) {
            inspectExpr(un.operand(), sc, ctx, effect, joinCol);
        } else if (expr instanceof Expr.FuncCallExpr fn) {
            for (Expr arg : fn.args()) {
                inspectExpr(arg, sc, ctx, effect, joinCol);
            }
            inspectExpr(fn.filterWhere(), sc, ctx, effect, joinCol);
            if (fn.overWindow() != null) {
                for (Expr p : fn.overWindow().partitionBy()) {
                    inspectExpr(p, sc, ctx, effect, joinCol);
                }
                for (OrderByItem ob : fn.overWindow().orderBy()) {
                    inspectExpr(ob.expr(), sc, ctx, effect, joinCol);
                }
            }
        } else if (expr instanceof Expr.CaseExpr ce) {
            inspectExpr(ce.operand(), sc, ctx, effect, joinCol);
            for (Expr.WhenClause wc : ce.whenClauses()) {
                inspectExpr(wc.whenExpr(), sc, ctx, effect, joinCol);
                inspectExpr(wc.thenExpr(), sc, ctx, effect, joinCol);
            }
            inspectExpr(ce.elseExpr(), sc, ctx, effect, joinCol);
        } else if (expr instanceof Expr.CastExpr cast) {
            inspectExpr(cast.expr(), sc, ctx, effect, joinCol);
        } else if (expr instanceof Expr.InListExpr inList) {
            inspectExpr(inList.expr(), sc, ctx, effect, joinCol);
            for (Expr item : inList.items()) {
                inspectExpr(item, sc, ctx, effect, joinCol);
            }
        } else if (expr instanceof Expr.BetweenExpr btw) {
            inspectExpr(btw.expr(), sc, ctx, effect, joinCol);
            inspectExpr(btw.low(), sc, ctx, effect, joinCol);
            inspectExpr(btw.high(), sc, ctx, effect, joinCol);
        } else if (expr instanceof Expr.ExtractExpr ext) {
            inspectExpr(ext.source(), sc, ctx, effect, joinCol);
        } else if (expr instanceof Expr.SubqueryExpr sub) {
            inspectExpr(sub.testExpr(), sc, ctx, effect, joinCol);
            inspectQuery(sub.query(), ctx, sc, "select");
        }
    }

    private void resolveQualifiedCol(
            QualifiedName qname, Scope sc, String fallbackTable, String effect, boolean joinCol, Ctx ctx) {
        if (qname == null || qname.isEmpty()) {
            return;
        }
        String col = qname.lastName();
        if (qname.size() == 1) {
            if (PSEUDO_CONST_IDENTIFIERS.contains(col.toUpperCase(Locale.ROOT))) {
                return;
            }
            if (("orderby".equals(effect) || "groupby".equals(effect) || "having".equals(effect))
                    && sc.selectAliases.contains(col.toLowerCase(Locale.ROOT))) {
                return;
            }
            String owner = sc.resolveUnqualifiedOwner(col, fallbackTable, ctx.schemaCatalog);
            if (owner != null) {
                recordCol(owner, col, effect, joinCol, qname.span(), ctx);
            } else if (!sc.hasVirtualBackingOnly()) {
                ctx.orphans.add(col);
            }
            return;
        }

        String qualifier = qname.parts().subList(0, qname.size() - 1).stream()
                .map(QualifiedName.NamePart::value)
                .reduce((a, b) -> a + "." + b)
                .orElse("");
        Set<String> virtualTargets = sc.lookupVirtualQualifier(qualifier);
        if (virtualTargets != null) {
            if (virtualTargets.size() == 1) {
                recordCol(virtualTargets.iterator().next(), col, effect, joinCol, qname.span(), ctx);
            }
            return;
        }
        String resolvedTable = sc.resolveQualifier(qualifier);
        if (resolvedTable != null) {
            recordCol(resolvedTable, col, effect, joinCol, qname.span(), ctx);
        } else {
            ctx.orphans.add(qname.fullDisplayName());
        }
    }

    private static String registerTableName(QualifiedName qname, Ctx ctx) {
        if (qname == null || qname.isEmpty()) {
            return null;
        }
        String tbl = qname.fullDisplayName();
        ctx.tableCols.computeIfAbsent(tbl, k -> new LinkedHashSet<>());
        return tbl;
    }

    private static void recordCol(
            String table, String col, String effect, boolean joinCol, SourceSpan span, Ctx ctx) {
        if (table == null || table.isBlank() || col == null || col.isBlank()) {
            return;
        }
        ctx.tableCols.computeIfAbsent(table, k -> new LinkedHashSet<>()).add(col);
        String dt = ctx.dataTypeCatalog.getOrDefault(table + "." + col, "");
        String coord = span != null && span.isValid()
                ? Coordinate.formatXmlCoordinate(span.toCoordinates(null))
                : "";
        ctx.details.add(new ColumnUsageDetail(table, col, dt, effect, joinCol, coord != null ? coord : ""));
    }

    private static void loadMetadataTriples(String raw, Map<String, LinkedHashSet<String>> catalog) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String token : raw.split("[,\\r\\n]+")) {
            String trimmed = token.trim();
            int lastDot = trimmed.lastIndexOf('.');
            if (lastDot > 0 && lastDot < trimmed.length() - 1) {
                String tblPart = trimmed.substring(0, lastDot).toLowerCase(Locale.ROOT);
                String colPart = trimmed.substring(lastDot + 1).toLowerCase(Locale.ROOT);
                catalog.computeIfAbsent(tblPart, k -> new LinkedHashSet<>()).add(colPart);
                int prevDot = tblPart.lastIndexOf('.');
                if (prevDot > 0) {
                    catalog.computeIfAbsent(tblPart.substring(prevDot + 1), k -> new LinkedHashSet<>()).add(colPart);
                }
            }
        }
    }

    private static String csvCell(String val) {
        String safe = val != null ? val.replace("\"", "\"\"") : "";
        return "\"" + safe + "\"";
    }

    private record Ctx(
            Map<String, LinkedHashSet<String>> tableCols,
            LinkedHashSet<String> orphans,
            List<StatementCrudSummary> crudSummaries,
            List<ColumnUsageDetail> details,
            List<ErrorItem> errors,
            Map<String, LinkedHashSet<String>> schemaCatalog,
            Map<String, String> dataTypeCatalog
    ) {
    }

    private static final class Scope {
        private final Scope parent;
        private final List<String> physicalTables = new ArrayList<>();
        private final Map<String, String> aliasToPhysical = new LinkedHashMap<>();
        private final Map<String, Set<String>> cteUnderlyingTables = new LinkedHashMap<>();
        private final Map<String, Set<String>> virtualAliases = new LinkedHashMap<>();
        private final Set<String> selectAliases = new LinkedHashSet<>();

        private Scope(Scope parent) {
            this.parent = parent;
        }

        static Scope root() {
            return new Scope(null);
        }

        static Scope child(Scope parent) {
            return new Scope(parent);
        }

        void addPhysicalTable(String fullTable, String alias) {
            if (!physicalTables.contains(fullTable)) {
                physicalTables.add(fullTable);
            }
            aliasToPhysical.put(fullTable.toLowerCase(Locale.ROOT), fullTable);
            int dot = fullTable.lastIndexOf('.');
            if (dot >= 0) {
                aliasToPhysical.putIfAbsent(fullTable.substring(dot + 1).toLowerCase(Locale.ROOT), fullTable);
            }
            if (alias != null && !alias.isBlank()) {
                aliasToPhysical.put(alias.toLowerCase(Locale.ROOT), fullTable);
            }
        }

        Set<String> lookupCte(String lowerName) {
            for (Scope s = this; s != null; s = s.parent) {
                if (s.cteUnderlyingTables.containsKey(lowerName)) {
                    return s.cteUnderlyingTables.get(lowerName);
                }
            }
            return null;
        }

        Set<String> lookupVirtualQualifier(String qualifier) {
            String key = qualifier.toLowerCase(Locale.ROOT);
            for (Scope s = this; s != null; s = s.parent) {
                if (s.virtualAliases.containsKey(key)) {
                    return s.virtualAliases.get(key);
                }
            }
            return null;
        }

        String resolveQualifier(String qualifier) {
            String key = qualifier.toLowerCase(Locale.ROOT);
            for (Scope s = this; s != null; s = s.parent) {
                if (s.aliasToPhysical.containsKey(key)) {
                    return s.aliasToPhysical.get(key);
                }
            }
            return null;
        }

        boolean hasVirtualBackingOnly() {
            return physicalTables.isEmpty() && !virtualAliases.isEmpty();
        }

        String resolveUnqualifiedOwner(
                String col, String fallbackTable, Map<String, LinkedHashSet<String>> schemaCatalog) {
            if (fallbackTable != null) {
                return fallbackTable;
            }
            if (physicalTables.size() == 1 && virtualAliases.isEmpty()) {
                return physicalTables.get(0);
            }
            if (physicalTables.isEmpty() && virtualAliases.size() == 1) {
                Set<String> backing = virtualAliases.values().iterator().next();
                if (backing.size() == 1) {
                    return backing.iterator().next();
                }
            }
            String lowerCol = col.toLowerCase(Locale.ROOT);
            String matched = null;
            for (String tbl : physicalTables) {
                Set<String> cols = schemaCatalog.get(tbl.toLowerCase(Locale.ROOT));
                if (cols != null && cols.contains(lowerCol)) {
                    if (matched != null && !matched.equals(tbl)) {
                        return null; // ambiguous across tables
                    }
                    matched = tbl;
                }
            }
            if (matched != null) {
                return matched;
            }
            return parent != null ? parent.resolveUnqualifiedOwner(col, null, schemaCatalog) : null;
        }
    }
}
