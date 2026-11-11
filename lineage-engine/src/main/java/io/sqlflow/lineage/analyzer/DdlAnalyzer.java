package io.sqlflow.lineage.analyzer;

import io.sqlflow.contracts.lineage.EffectType;
import io.sqlflow.contracts.lineage.EndpointIntroduction;
import io.sqlflow.contracts.lineage.RelationshipType;
import io.sqlflow.contracts.lineage.ResultSetType;
import io.sqlflow.contracts.vendor.DataObjectType;
import io.sqlflow.lineage.analyzer.ExpressionAnalyzer.EvalResult;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundColumn;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundObject;
import io.sqlflow.lineage.analyzer.ModelBindingManager.ObjectCategory;
import io.sqlflow.lineage.analyzer.QueryScopeResolver.QueryScope;
import io.sqlflow.lineage.analyzer.QueryScopeResolver.ScopeEntry;
import io.sqlflow.lineage.ast.QualifiedName;
import io.sqlflow.lineage.ast.SqlStmt.AlterAction;
import io.sqlflow.lineage.ast.SqlStmt.AlterTableStmt;
import io.sqlflow.lineage.ast.SqlStmt.ColumnDef;
import io.sqlflow.lineage.ast.SqlStmt.CommentOnStmt;
import io.sqlflow.lineage.ast.SqlStmt.CreateDatabaseOrSchemaStmt;
import io.sqlflow.lineage.ast.SqlStmt.CreateSequenceStmt;
import io.sqlflow.lineage.ast.SqlStmt.CreateStageOrStreamStmt;
import io.sqlflow.lineage.ast.SqlStmt.CreateSynonymStmt;
import io.sqlflow.lineage.ast.SqlStmt.CreateTableStmt;
import io.sqlflow.lineage.ast.SqlStmt.CreateViewStmt;
import io.sqlflow.lineage.ast.SqlStmt.DropStmt;
import io.sqlflow.lineage.ast.SqlStmt.ForeignKeySpec;
import io.sqlflow.lineage.ast.SqlStmt.TableConstraint;
import io.sqlflow.lineage.ast.SqlStmt.TruncateStmt;
import io.sqlflow.lineage.ast.SqlStmt.UseContextStmt;
import io.sqlflow.lineage.catalog.TSQLEnv;
import io.sqlflow.lineage.catalog.TSQLEnv.CatalogColumn;
import io.sqlflow.lineage.catalog.TSQLEnv.CatalogForeignKey;
import io.sqlflow.lineage.catalog.TSQLEnv.CatalogSequence;
import io.sqlflow.lineage.catalog.TSQLEnv.CatalogSynonym;
import io.sqlflow.lineage.catalog.TSQLEnv.CatalogTable;
import io.sqlflow.lineage.lexer.SourceSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Semantic analyzer for DDL, catalog context, PK/UK/FK constraints ({@code er} + {@code unqiueKey}),
 * CTAS, {@code CREATE VIEW}, {@code ALTER TABLE}, synonyms, sequences, stages, and streams.
 */
public final class DdlAnalyzer {

    private DdlAnalyzer() {
    }

    public static void preRegisterTableOrView(Object stmt, TSQLEnv env) {
        if (stmt instanceof CreateTableStmt ct && ct.tableName() != null && !ct.tableName().isEmpty()) {
            CatalogTable tbl = env.registerTable(ct.tableName(), "table", true);
            if (tbl != null && ct.columns() != null) {
                for (ColumnDef cd : ct.columns()) {
                    env.addOrUpdateColumn(tbl, toCatalogColumn(cd), cd.quoted());
                }
            }
        } else if (stmt instanceof CreateViewStmt cv && cv.viewName() != null && !cv.viewName().isEmpty()) {
            CatalogTable vw = env.registerTable(cv.viewName(), cv.materialized() ? "materialized view" : "view", true);
            if (vw != null && cv.columnNames() != null) {
                for (String cn : cv.columnNames()) {
                    env.addOrUpdateColumn(vw, new CatalogColumn(cn, null), false);
                }
            }
        } else if (stmt instanceof CreateSynonymStmt syn && syn.synonymName() != null && syn.targetObject() != null) {
            TSQLEnv.ResolvedCoordinates srcRc = env.resolveNameParts(syn.synonymName());
            TSQLEnv.ResolvedCoordinates tgtRc = env.resolveNameParts(syn.targetObject());
            env.registerSynonym(new CatalogSynonym(
                    srcRc.server(), srcRc.database(), srcRc.schema(), srcRc.objectName(),
                    tgtRc.server(), tgtRc.database(), tgtRc.schema(), tgtRc.objectName(),
                    syn.targetObject().dbLink()));
        }
    }

    public static void analyzeCreateTable(CreateTableStmt stmt, AnalyzerContext ctx) {
        EndpointIntroduction intro = stmt.asSelect() != null ? EndpointIntroduction.CTAS
                : (stmt.cloneTable() != null ? EndpointIntroduction.CLONE_TABLE : EndpointIntroduction.CREATE_TABLE_DDL);
        CatalogTable catTbl = ctx.env().registerTable(stmt.tableName(), "table", true);
        BoundObject tblObj = ctx.mgr().bindTableOrView(
                stmt.tableName(), ObjectCategory.TABLE, intro,
                stmt.asSelect() != null || stmt.likeTable() != null || stmt.cloneTable() != null,
                true, true, ctx.sqlHash());
        if (stmt.temporary() || stmt.globalTemporary()) {
            tblObj.setTemporary(true);
        }
        if (stmt.locationUri() != null && !stmt.locationUri().isBlank()) {
            tblObj.setLocation(stmt.locationUri());
            tblObj.setUri(stmt.locationUri());
        }
        if (stmt.fileFormat() != null && !stmt.fileFormat().isBlank()) {
            tblObj.setFileFormat(stmt.fileFormat());
        }
        DmlAnalyzer.emitCrudWriteIfRequested(tblObj, EffectType.create_table, ctx, stmt.span());

        // 1. Explicit DDL columns & inline constraints
        List<ColumnDef> computedCols = new ArrayList<>();
        if (stmt.columns() != null) {
            for (ColumnDef cd : stmt.columns()) {
                CatalogColumn cc = toCatalogColumn(cd);
                ctx.env().addOrUpdateColumn(catTbl, cc, cd.quoted());
                BoundColumn bc = tblObj.getOrCreateColumn(ctx.mgr(), cd.name(), cd.quoted(), cd.span(), ctx.sqlHash());
                ModelBindingManager.copyCatalogColumnMetadata(cc, bc);
                if (!ctx.option().isShowComment()) {
                    bc.setComment(null);
                }
                if (cd.inlineForeignKey() != null) {
                    applyForeignKey(tblObj, catTbl, cd.inlineForeignKey(), ctx);
                }
                if (cd.computed() && cd.computedExpr() != null) {
                    computedCols.add(cd);
                }
            }
        }

        // 2. Table-level constraints (PK, UNIQUE -> unqiueKey, INDEX, FOREIGN_KEY -> er)
        if (stmt.constraints() != null) {
            for (TableConstraint tc : stmt.constraints()) {
                applyTableConstraint(tblObj, catTbl, tc, ctx);
            }
        }

        // 3. Computed column expressions (sibling column lineage)
        if (!computedCols.isEmpty()) {
            QueryScope selfScope = new QueryScope(null, ctx);
            selfScope.addEntry(new ScopeEntry(null, false, tblObj, catTbl, null, true));
            for (ColumnDef cd : computedCols) {
                BoundColumn targetCol = tblObj.getOrCreateColumn(ctx.mgr(), cd.name(), cd.quoted(), cd.span(), ctx.sqlHash());
                EvalResult er = ExpressionAnalyzer.evaluate(cd.computedExpr(), selfScope, "select", null);
                if (!er.direct().isEmpty()) {
                    ctx.mgr().addColumnEdge(
                            RelationshipType.fdd, EffectType.create_table, tblObj, targetCol, er.direct(),
                            ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), er.functionName(), null, null, null);
                }
            }
        }

        // 4. CTAS (`CREATE TABLE ... AS SELECT ...`)
        if (stmt.asSelect() != null) {
            BoundObject rs = SelectAndSetOpAnalyzer.analyzeQuery(
                    stmt.asSelect(), new QueryScope(null, ctx), ctx, false,
                    ResultSetType.select_list, null, EffectType.create_table);
            if (rs != null) {
                List<String> explicitNames = new ArrayList<>();
                if (stmt.columns() != null) {
                    for (ColumnDef cd : stmt.columns()) {
                        explicitNames.add(cd.name());
                    }
                }
                List<BoundColumn> srcCols = rs.orderedColumns();
                for (int i = 0; i < srcCols.size(); i++) {
                    String cn = i < explicitNames.size() ? explicitNames.get(i) : srcCols.get(i).getName();
                    BoundColumn tc = tblObj.getOrCreateColumn(ctx.mgr(), cn, false, stmt.span(), ctx.sqlHash());
                    ctx.env().addOrUpdateColumn(catTbl, new CatalogColumn(cn, null), false);
                    ctx.mgr().addColumnEdge(
                            RelationshipType.fdd, EffectType.create_table, tblObj, tc,
                            List.of(ctx.toSourceRef(rs, srcCols.get(i), null, "select", stmt.span())),
                            ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
                }
            }
        }

        // 5. LIKE / CLONE / EXTERNAL LOCATION
        if (stmt.likeTable() != null) {
            copyTableStructureAndEdges(stmt.likeTable(), tblObj, catTbl, EffectType.like_table, stmt.span(), ctx);
        }
        if (stmt.cloneTable() != null) {
            copyTableStructureAndEdges(stmt.cloneTable(), tblObj, catTbl, EffectType.clone_table, stmt.span(), ctx);
        }
        if (stmt.external() && stmt.locationUri() != null && !stmt.locationUri().isBlank()) {
            BoundObject pathObj = ctx.mgr().bindPathOrStageObject(
                    stmt.locationUri(), ObjectCategory.PATH, "path", stmt.span(), false, ctx.sqlHash());
            List<BoundColumn> cols = tblObj.orderedColumns();
            if (cols.isEmpty()) {
                BoundColumn sc = pathObj.getOrCreateColumn(ctx.mgr(), "*", false, stmt.span(), ctx.sqlHash());
                BoundColumn tc = tblObj.getOrCreateColumn(ctx.mgr(), "*", false, stmt.span(), ctx.sqlHash());
                ctx.mgr().addColumnEdge(
                        RelationshipType.fdd, EffectType.copy, tblObj, tc,
                        List.of(ctx.toSourceRef(pathObj, sc, null, "select", stmt.span())),
                        ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
            } else {
                for (BoundColumn tc : cols) {
                    BoundColumn sc = pathObj.getOrCreateColumn(ctx.mgr(), tc.getName(), false, stmt.span(), ctx.sqlHash());
                    ctx.mgr().addColumnEdge(
                            RelationshipType.fdd, EffectType.copy, tblObj, tc,
                            List.of(ctx.toSourceRef(pathObj, sc, null, "select", stmt.span())),
                            ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
                }
            }
        }
    }

    public static void analyzeCreateView(CreateViewStmt stmt, AnalyzerContext ctx) {
        String viewType = stmt.materialized() ? "materialized view" : "view";
        CatalogTable catView = ctx.env().registerTable(stmt.viewName(), viewType, true);
        BoundObject viewObj = ctx.mgr().bindTableOrView(
                stmt.viewName(), ObjectCategory.VIEW, EndpointIntroduction.CATALOG_REFERENCE,
                true, true, true, ctx.sqlHash());
        viewObj.setType(viewType);
        if (stmt.query() != null) {
            ctx.viewAsts().put(viewObj.getId(), stmt.query());
            BoundObject rs = SelectAndSetOpAnalyzer.analyzeQuery(
                    stmt.query(), new QueryScope(null, ctx), ctx, false,
                    ResultSetType.select_list, null, EffectType.create_view);
            if (rs != null) {
                List<BoundColumn> srcCols = rs.orderedColumns();
                for (int i = 0; i < srcCols.size(); i++) {
                    String vColName = (stmt.columnNames() != null && i < stmt.columnNames().size())
                            ? stmt.columnNames().get(i)
                            : srcCols.get(i).getName();
                    SourceSpan sp = (stmt.columnSpans() != null && i < stmt.columnSpans().size())
                            ? stmt.columnSpans().get(i)
                            : stmt.span();
                    BoundColumn vc = viewObj.getOrCreateColumn(ctx.mgr(), vColName, false, sp, ctx.sqlHash());
                    if (!"*".equals(vColName)) {
                        ctx.env().addOrUpdateColumn(catView, new CatalogColumn(vColName, null), false);
                    }
                    ctx.mgr().addColumnEdge(
                            RelationshipType.fdd, EffectType.create_view, viewObj, vc,
                            List.of(ctx.toSourceRef(rs, srcCols.get(i), null, "select", sp)),
                            ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
                }
            }
        }
    }

    public static void analyzeAlterTable(AlterTableStmt stmt, AnalyzerContext ctx) {
        BoundObject tblObj = ctx.mgr().bindTableOrView(
                stmt.tableName(), ObjectCategory.TABLE, EndpointIntroduction.CATALOG_REFERENCE,
                true, true, false, ctx.sqlHash());
        CatalogTable catTbl = ctx.env().registerTable(stmt.tableName(), "table", true);
        if (stmt.actions() == null) {
            return;
        }
        for (AlterAction act : stmt.actions()) {
            String kind = act.actionType() != null ? act.actionType().toUpperCase(Locale.ROOT) : "";
            switch (kind) {
                case "ADD_CONSTRAINT" -> {
                    if (act.constraint() != null) {
                        applyTableConstraint(tblObj, catTbl, act.constraint(), ctx);
                    }
                }
                case "ADD_COLUMN" -> {
                    if (act.addedColumn() != null) {
                        CatalogColumn cc = toCatalogColumn(act.addedColumn());
                        ctx.env().addOrUpdateColumn(catTbl, cc, act.addedColumn().quoted());
                        BoundColumn bc = tblObj.getOrCreateColumn(
                                ctx.mgr(), act.addedColumn().name(), act.addedColumn().quoted(), act.span(), ctx.sqlHash());
                        ModelBindingManager.copyCatalogColumnMetadata(cc, bc);
                        DmlAnalyzer.emitCrudWriteIfRequested(tblObj, EffectType.add_table_column, ctx, act.span());
                    }
                }
                case "DROP_COLUMN" -> DmlAnalyzer.emitCrudWriteIfRequested(tblObj, EffectType.drop_table_column, ctx, act.span());
                case "RENAME_COLUMN" -> {
                    if (act.droppedOrOldColumn() != null && act.newColumnName() != null) {
                        BoundColumn oldCol = tblObj.getOrCreateColumn(ctx.mgr(), act.droppedOrOldColumn(), false, act.span(), ctx.sqlHash());
                        BoundColumn newCol = tblObj.getOrCreateColumn(ctx.mgr(), act.newColumnName(), false, act.span(), ctx.sqlHash());
                        ctx.mgr().addColumnEdge(
                                RelationshipType.fdd, EffectType.rename_table, tblObj, newCol,
                                List.of(ctx.toSourceRef(tblObj, oldCol, null, "alter", act.span())),
                                ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
                    }
                }
                case "RENAME_TABLE" -> {
                    if (act.otherTable() != null) {
                        ctx.env().renameTable(stmt.tableName(), act.otherTable());
                        BoundObject newTbl = ctx.mgr().bindTableOrView(
                                act.otherTable(), ObjectCategory.TABLE, EndpointIntroduction.CATALOG_REFERENCE,
                                true, true, true, ctx.sqlHash());
                        copyTableStructureAndEdges(stmt.tableName(), newTbl, ctx.env().resolveTable(act.otherTable()),
                                EffectType.rename_table, act.span(), ctx);
                    }
                }
                case "SWAP_WITH" -> {
                    if (act.otherTable() != null) {
                        BoundObject other = ctx.mgr().bindTableOrView(
                                act.otherTable(), ObjectCategory.TABLE, EndpointIntroduction.CATALOG_REFERENCE,
                                true, false, false, ctx.sqlHash());
                        copyTableStructureAndEdges(stmt.tableName(), other, ctx.env().resolveTable(act.otherTable()),
                                EffectType.swap_table, act.span(), ctx);
                    }
                }
                case "EXCHANGE_PARTITION", "SWITCH_PARTITION" -> {
                    if (act.otherTable() != null) {
                        EffectType eff = "SWITCH_PARTITION".equals(kind)
                                ? EffectType.switch_partition
                                : EffectType.exchange_partition;
                        BoundObject other = ctx.mgr().bindTableOrView(
                                act.otherTable(), ObjectCategory.TABLE, EndpointIntroduction.CATALOG_REFERENCE,
                                true, false, false, ctx.sqlHash());
                        copyTableStructureAndEdges(stmt.tableName(), other, ctx.env().resolveTable(act.otherTable()),
                                eff, act.span(), ctx);
                    }
                }
                default -> {
                }
            }
        }
    }

    public static void analyzeSimpleDdl(Object stmt, AnalyzerContext ctx) {
        if (stmt instanceof DropStmt drop && drop.names() != null) {
            for (QualifiedName qn : drop.names()) {
                BoundObject obj = ctx.mgr().bindTableOrView(
                        qn, ObjectCategory.TABLE, EndpointIntroduction.CATALOG_REFERENCE, true, true, false, ctx.sqlHash());
                ctx.env().dropTable(qn);
                DmlAnalyzer.emitCrudWriteIfRequested(obj, EffectType.drop_table, ctx, drop.span());
            }
        } else if (stmt instanceof TruncateStmt tr && tr.tableName() != null) {
            BoundObject obj = ctx.mgr().bindTableOrView(
                    tr.tableName(), ObjectCategory.TABLE, EndpointIntroduction.CATALOG_REFERENCE, true, false, false, ctx.sqlHash());
            DmlAnalyzer.emitCrudWriteIfRequested(obj, EffectType.truncate_table, ctx, tr.span());
        } else if (stmt instanceof CreateSynonymStmt syn && syn.synonymName() != null && syn.targetObject() != null) {
            preRegisterTableOrView(syn, ctx.env());
            if (!ctx.option().isIgnoreUnusedSynonym()) {
                BoundObject target = ctx.mgr().bindTableOrView(
                        syn.targetObject(), ObjectCategory.TABLE, EndpointIntroduction.CATALOG_REFERENCE,
                        false, false, false, ctx.sqlHash());
                ctx.mgr().bindSynonymObject(syn.synonymName(), target, true, syn.span(), ctx.sqlHash());
            }
        } else if (stmt instanceof CreateSequenceStmt seq && seq.sequenceName() != null) {
            TSQLEnv.ResolvedCoordinates rc = ctx.env().resolveNameParts(seq.sequenceName());
            ctx.env().registerSequence(new CatalogSequence(rc.server(), rc.database(), rc.schema(), rc.objectName()));
            ctx.mgr().bindSequenceObject(seq.sequenceName(), seq.span(), ctx.sqlHash());
        } else if (stmt instanceof CreateStageOrStreamStmt ss && ss.name() != null) {
            String k = ss.kind() != null ? ss.kind().toUpperCase(Locale.ROOT) : "STAGE";
            ObjectCategory cat = "STREAM".equals(k) ? ObjectCategory.STREAM
                    : ("DATASOURCE".equals(k) ? ObjectCategory.DATASOURCE : ObjectCategory.STAGE);
            BoundObject obj = ctx.mgr().bindPathOrStageObject(
                    ss.name().lastName(), cat, k.toLowerCase(Locale.ROOT), ss.span(), true, ctx.sqlHash());
            if (ss.urlOrLocation() != null) {
                obj.setLocation(ss.urlOrLocation());
                BoundObject path = ctx.mgr().bindPathOrStageObject(
                        ss.urlOrLocation(), ObjectCategory.PATH, "path", ss.span(), false, ctx.sqlHash());
                BoundColumn sc = path.getOrCreateColumn(ctx.mgr(), "*", false, ss.span(), ctx.sqlHash());
                BoundColumn tc = obj.getOrCreateColumn(ctx.mgr(), "*", false, ss.span(), ctx.sqlHash());
                ctx.mgr().addColumnEdge(
                        RelationshipType.fdd, EffectType.copy, obj, tc,
                        List.of(ctx.toSourceRef(path, sc, null, "select", ss.span())),
                        ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
            }
            if (ss.sourceTable() != null && !ss.sourceTable().isEmpty()) {
                copyTableStructureAndEdges(ss.sourceTable(), obj, null, EffectType.select, ss.span(), ctx);
            }
        } else if (stmt instanceof CreateDatabaseOrSchemaStmt cds && cds.cloneSource() != null && !cds.cloneSource().isEmpty()) {
            EffectType eff = "SCHEMA".equalsIgnoreCase(cds.kind()) ? EffectType.clone_schema : EffectType.clone_database;
            BoundObject src = ctx.mgr().bindTableOrView(
                    cds.cloneSource(), ObjectCategory.TABLE, EndpointIntroduction.CATALOG_REFERENCE, false, true, false, ctx.sqlHash());
            BoundObject tgt = ctx.mgr().bindTableOrView(
                    cds.name(), ObjectCategory.TABLE, EndpointIntroduction.CLONE_TABLE, true, true, true, ctx.sqlHash());
            BoundColumn sc = src.getOrCreateColumn(ctx.mgr(), "*", false, cds.span(), ctx.sqlHash());
            BoundColumn tc = tgt.getOrCreateColumn(ctx.mgr(), "*", false, cds.span(), ctx.sqlHash());
            ctx.mgr().addColumnEdge(
                    RelationshipType.fdd, eff, tgt, tc,
                    List.of(ctx.toSourceRef(src, sc, null, "select", cds.span())),
                    ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
        } else if (stmt instanceof UseContextStmt use) {
            ctx.env().applyUseContext(use.kind(), use.target());
        } else if (stmt instanceof CommentOnStmt cm && ctx.option().isShowComment() && cm.target() != null) {
            if ("COLUMN".equalsIgnoreCase(cm.targetKind()) && cm.target().size() >= 2) {
                QualifiedName tblQn = new QualifiedName(
                        cm.target().parts().subList(0, cm.target().size() - 1), null, false, false, false, cm.span());
                BoundObject tbl = ctx.mgr().bindTableOrView(
                        tblQn, ObjectCategory.TABLE, EndpointIntroduction.CATALOG_REFERENCE, false, false, false, ctx.sqlHash());
                BoundColumn col = tbl.getOrCreateColumn(ctx.mgr(), cm.target().lastName(), cm.target().lastQuoted(), cm.span(), ctx.sqlHash());
                col.setComment(cm.comment());
            }
        }
    }

    public static void emitCatalogErEdgesIfRequested(AnalyzerContext ctx) {
        if (!ctx.option().isShowERDiagram() && !ctx.option().getFilterRelationTypes().contains(RelationshipType.er.name())) {
            return;
        }
        for (CatalogTable ct : ctx.env().allTables()) {
            List<String> tblParts = new ArrayList<>(3);
            if (ct.getDatabase() != null) {
                tblParts.add(ct.getDatabase());
            }
            if (ct.getSchema() != null) {
                tblParts.add(ct.getSchema());
            }
            tblParts.add(ct.getName());
            BoundObject srcTbl = ctx.mgr().bindTableOrView(
                    QualifiedName.ofParts(tblParts, null),
                    ObjectCategory.TABLE, EndpointIntroduction.CATALOG_REFERENCE, false, ct.isFromDdl(), false, ctx.sqlHash());
            for (CatalogForeignKey fk : ct.getForeignKeys()) {
                List<String> refParts = new ArrayList<>(3);
                if (fk.refDatabase() != null) {
                    refParts.add(fk.refDatabase());
                }
                if (fk.refSchema() != null) {
                    refParts.add(fk.refSchema());
                }
                if (fk.refTable() != null) {
                    refParts.add(fk.refTable());
                }
                if (refParts.isEmpty()) {
                    continue;
                }
                BoundObject refTbl = ctx.mgr().bindTableOrView(
                        QualifiedName.ofParts(refParts, null),
                        ObjectCategory.TABLE, EndpointIntroduction.CATALOG_REFERENCE, false, false, false, ctx.sqlHash());
                emitErColumns(srcTbl, fk.sourceColumns(), refTbl, fk.refColumns(), null, ctx);
            }
        }
    }

    private static void applyTableConstraint(BoundObject tblObj, CatalogTable catTbl, TableConstraint tc, AnalyzerContext ctx) {
        String cType = tc.constraintType() != null ? tc.constraintType().toUpperCase(Locale.ROOT) : "";
        if (tc.columns() != null) {
            for (String cn : tc.columns()) {
                BoundColumn bc = tblObj.getOrCreateColumn(ctx.mgr(), cn, false, tc.span(), ctx.sqlHash());
                CatalogColumn cc = ctx.env().resolveColumn(catTbl, cn, false);
                if ("PRIMARY_KEY".equals(cType)) {
                    bc.setPrimaryKey(Boolean.TRUE);
                    if (cc != null) {
                        cc.setPrimaryKey(Boolean.TRUE);
                    }
                } else if ("UNIQUE".equals(cType)) {
                    bc.setUnqiueKey(Boolean.TRUE);
                    if (cc != null) {
                        cc.setUnqiueKey(Boolean.TRUE);
                    }
                } else if ("INDEX".equals(cType)) {
                    bc.setIndexKey(Boolean.TRUE);
                    if (cc != null) {
                        cc.setIndexKey(Boolean.TRUE);
                    }
                }
            }
        }
        if (tc.foreignKey() != null) {
            applyForeignKey(tblObj, catTbl, tc.foreignKey(), ctx);
        }
    }

    private static void applyForeignKey(BoundObject srcTbl, CatalogTable catTbl, ForeignKeySpec fk, AnalyzerContext ctx) {
        if (fk.referencedTable() == null || fk.referencedTable().isEmpty()) {
            return;
        }
        TSQLEnv.ResolvedCoordinates refRc = ctx.env().resolveNameParts(fk.referencedTable());
        if (catTbl != null) {
            catTbl.addForeignKey(new CatalogForeignKey(
                    fk.constraintName(), fk.localColumns(),
                    refRc.database(), refRc.schema(), refRc.objectName(), fk.referencedColumns()));
        }
        if (fk.localColumns() != null) {
            for (String lc : fk.localColumns()) {
                BoundColumn bc = srcTbl.getOrCreateColumn(ctx.mgr(), lc, false, fk.span(), ctx.sqlHash());
                bc.setForeignKey(Boolean.TRUE);
                CatalogColumn cc = ctx.env().resolveColumn(catTbl, lc, false);
                if (cc != null) {
                    cc.setForeignKey(Boolean.TRUE);
                }
            }
        }
        if (ctx.option().isShowERDiagram() || ctx.option().getFilterRelationTypes().contains(RelationshipType.er.name())) {
            BoundObject refTbl = ctx.mgr().bindTableOrView(
                    fk.referencedTable(), ObjectCategory.TABLE, EndpointIntroduction.CATALOG_REFERENCE,
                    false, false, false, ctx.sqlHash());
            emitErColumns(srcTbl, fk.localColumns(), refTbl, fk.referencedColumns(), fk.span(), ctx);
        }
    }

    private static void emitErColumns(
            BoundObject srcTbl, List<String> srcCols, BoundObject refTbl, List<String> refCols, SourceSpan span, AnalyzerContext ctx) {
        if (srcCols == null || srcCols.isEmpty()) {
            return;
        }
        List<BoundColumn> refPkFallback = new ArrayList<>();
        for (BoundColumn bc : refTbl.orderedColumns()) {
            if (Boolean.TRUE.equals(bc.getPrimaryKey())) {
                refPkFallback.add(bc);
            }
        }
        for (int i = 0; i < srcCols.size(); i++) {
            BoundColumn fkCol = srcTbl.getOrCreateColumn(ctx.mgr(), srcCols.get(i), false, span, ctx.sqlHash());
            fkCol.setForeignKey(Boolean.TRUE);
            String pkName = (refCols != null && i < refCols.size())
                    ? refCols.get(i)
                    : (i < refPkFallback.size() ? refPkFallback.get(i).getName() : srcCols.get(i));
            BoundColumn pkCol = refTbl.getOrCreateColumn(ctx.mgr(), pkName, false, span, ctx.sqlHash());
            ctx.mgr().addColumnEdge(
                    RelationshipType.er, EffectType.foreign_key, refTbl, pkCol,
                    List.of(ctx.toSourceRef(srcTbl, fkCol, null, "foreign_key", span)),
                    ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
        }
    }

    private static void copyTableStructureAndEdges(
            QualifiedName srcQn, BoundObject tgtObj, CatalogTable tgtCat, EffectType eff, SourceSpan span, AnalyzerContext ctx) {
        BoundObject srcObj = ctx.mgr().bindTableOrView(
                srcQn, ObjectCategory.TABLE, EndpointIntroduction.CATALOG_REFERENCE, false, false, false, ctx.sqlHash());
        CatalogTable srcCat = ctx.env().resolveTable(srcQn);
        List<String> colNames = new ArrayList<>();
        if (srcCat != null) {
            for (CatalogColumn cc : srcCat.orderedColumns()) {
                colNames.add(cc.getName());
                if (tgtCat != null) {
                    ctx.env().addOrUpdateColumn(tgtCat, new CatalogColumn(cc.getName(), cc.getDataType()), false);
                }
            }
        }
        if (colNames.isEmpty()) {
            for (BoundColumn bc : srcObj.orderedColumns()) {
                colNames.add(bc.getName());
            }
        }
        if (colNames.isEmpty()) {
            colNames.add("*");
        }
        for (String cn : colNames) {
            BoundColumn sc = srcObj.getOrCreateColumn(ctx.mgr(), cn, false, span, ctx.sqlHash());
            BoundColumn tc = tgtObj.getOrCreateColumn(ctx.mgr(), cn, false, span, ctx.sqlHash());
            ctx.mgr().addColumnEdge(
                    RelationshipType.fdd, eff, tgtObj, tc,
                    List.of(ctx.toSourceRef(srcObj, sc, null, "select", span)),
                    ctx.activeProcess(), ctx.enclosingRoutine(), ctx.sqlHash(), null, null, null, null);
        }
    }

    private static CatalogColumn toCatalogColumn(ColumnDef cd) {
        CatalogColumn cc = new CatalogColumn(cd.name(), cd.dataType());
        if (cd.primaryKey()) {
            cc.setPrimaryKey(Boolean.TRUE);
        }
        if (cd.uniqueKey()) {
            cc.setUnqiueKey(Boolean.TRUE);
        }
        if (cd.indexKey()) {
            cc.setIndexKey(Boolean.TRUE);
        }
        if (cd.inlineForeignKey() != null) {
            cc.setForeignKey(Boolean.TRUE);
        }
        if (cd.nullable() != null) {
            cc.setNullable(cd.nullable());
        }
        if (cd.identity()) {
            cc.setIdentity(Boolean.TRUE);
        }
        if (cd.computed()) {
            cc.setComputed(Boolean.TRUE);
            cc.setPersisted(cd.persisted());
            cc.setComputedDefinition(cd.computedDefinition());
        }
        cc.setDefaultValue(cd.defaultValue());
        cc.setDefaultName(cd.defaultName());
        cc.setComment(cd.comment());
        return cc;
    }
}
