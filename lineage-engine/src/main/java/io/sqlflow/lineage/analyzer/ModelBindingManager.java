package io.sqlflow.lineage.analyzer;

import io.sqlflow.contracts.lineage.ArgumentObject;
import io.sqlflow.contracts.lineage.CandidateTable;
import io.sqlflow.contracts.lineage.Coordinate;
import io.sqlflow.contracts.lineage.EffectType;
import io.sqlflow.contracts.lineage.EndpointIntroduction;
import io.sqlflow.contracts.lineage.EndpointKind;
import io.sqlflow.contracts.lineage.ErrorItem;
import io.sqlflow.contracts.lineage.Option;
import io.sqlflow.contracts.lineage.RelationshipElement;
import io.sqlflow.contracts.lineage.RelationshipType;
import io.sqlflow.contracts.lineage.ResultSetType;
import io.sqlflow.contracts.lineage.Transform;
import io.sqlflow.contracts.vendor.DataObjectType;
import io.sqlflow.lineage.ast.QualifiedName;
import io.sqlflow.lineage.catalog.TSQLEnv;
import io.sqlflow.lineage.catalog.TSQLEnv.CatalogColumn;
import io.sqlflow.lineage.catalog.TSQLEnv.CatalogTable;
import io.sqlflow.lineage.lexer.SourceSpan;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Central lineage graph model and monotonic ID allocator ({@code ModelBindingManager} in {@code 03 §2.1, §3.1–§3.4}).
 */
public final class ModelBindingManager {

    public static final String ORPHAN_PSEUDO_TABLE = "pseudo_table_include_orphan_column";
    public static final String SQL_CONSTANTS_TABLE = "SQL_CONSTANTS";
    public static final int MAX_ERRORS = 500;

    public enum ObjectCategory {
        TABLE,
        VIEW,
        RESULTSET,
        VARIABLE,
        STAGE,
        SEQUENCE,
        DATASOURCE,
        STREAM,
        PATH,
        SYNONYM
    }

    private final TSQLEnv sqlEnv;
    private final Option option;
    private long objectIdSeq;
    private long relationIdSeq;
    private int selectRsCounter = 0;

    private final Map<String, BoundObject> physicalObjects = new LinkedHashMap<>();
    private final List<BoundObject> allObjects = new ArrayList<>();
    private final Map<String, BoundPackage> packages = new LinkedHashMap<>();
    private final Map<String, BoundRoutine> routines = new LinkedHashMap<>();
    private final List<BoundProcess> processes = new ArrayList<>();
    private final List<BoundEdge> edges = new ArrayList<>();
    private final Set<String> edgeDedupKeys = new LinkedHashSet<>();
    private final List<StarLink> starLinks = new ArrayList<>();
    private final List<ErrorItem> errors = new ArrayList<>();

    private BoundObject constantsTable;
    private BoundObject orphanTable;

    public ModelBindingManager(TSQLEnv sqlEnv, Option option) {
        this.sqlEnv = Objects.requireNonNull(sqlEnv, "sqlEnv");
        this.option = option != null ? option : new Option();
        this.objectIdSeq = this.option.getStartId();
        this.relationIdSeq = this.option.getStartId();
    }

    public TSQLEnv sqlEnv() {
        return sqlEnv;
    }

    public Option option() {
        return option;
    }

    public String nextObjectId() {
        return String.valueOf(++objectIdSeq);
    }

    public String nextRelationId() {
        return String.valueOf(++relationIdSeq);
    }

    public List<Coordinate> toCoords(SourceSpan span, String sqlHash) {
        if (option.isIgnoreCoordinate() || span == null) {
            return null;
        }
        return span.toCoordinates(sqlHash);
    }

    public void addError(String errorType, String message, SourceSpan span, String sqlHash) {
        if (errors.size() >= MAX_ERRORS) {
            return;
        }
        List<Coordinate> coords = span != null && !option.isIgnoreCoordinate()
                ? span.toCoordinates(sqlHash)
                : null;
        errors.add(new ErrorItem(
                errorType != null ? errorType : "SyntaxError",
                message != null ? message : "SQL analysis error",
                coords));
    }

    public void addErrorItem(ErrorItem item) {
        if (item != null && errors.size() < MAX_ERRORS) {
            errors.add(item);
        }
    }

    public List<ErrorItem> errors() {
        return errors;
    }

    // -------------------------------------------------------------------------
    // Physical & Intermediate Object Binding
    // -------------------------------------------------------------------------

    public BoundObject bindTableOrView(
            QualifiedName qname,
            ObjectCategory defaultCategory,
            EndpointIntroduction intro,
            boolean isTarget,
            boolean fromDdl,
            boolean createdInSql,
            String sqlHash) {
        if (qname == null || qname.isEmpty()) {
            return getOrCreateOrphanTable(sqlHash);
        }

        // Handle external storage URIs (`s3://...`, `gs://...`, `hdfs://...`) and Snowflake `@stage`
        if (qname.uriRef()) {
            return bindPathOrStageObject(qname.lastName(), ObjectCategory.PATH, "path", qname.span(), isTarget, sqlHash);
        }
        if (qname.stageRef()) {
            return bindPathOrStageObject(qname.lastName(), ObjectCategory.STAGE, "stage", qname.span(), isTarget, sqlHash);
        }

        TSQLEnv.ResolvedCoordinates rc = sqlEnv.resolveNameParts(qname);
        String objName = rc.objectName();
        EndpointKind epKind = classifyEndpointKind(objName, qname.templated());

        // Check if catalog knows this object as a view, stream, stage, or table
        CatalogTable catTbl = sqlEnv.resolveTable(qname);
        ObjectCategory category = defaultCategory;
        String wireType = defaultCategory == ObjectCategory.VIEW ? "view" : "table";
        String effDb = rc.explicitDatabase() ? rc.database() : (option.isShowImplicitSchema() ? rc.database() : null);
        String effSch = rc.explicitSchema() ? rc.schema() : (option.isShowImplicitSchema() ? rc.schema() : null);

        if (catTbl != null) {
            if (effDb == null && catTbl.getDatabase() != null && (rc.explicitDatabase() || option.isShowImplicitSchema() || option.getDefaultDatabase() != null)) {
                effDb = catTbl.getDatabase();
            }
            if (effSch == null && catTbl.getSchema() != null && (rc.explicitSchema() || option.isShowImplicitSchema() || option.getDefaultSchema() != null)) {
                effSch = catTbl.getSchema();
            }
            String catType = catTbl.getObjectType() != null ? catTbl.getObjectType().toLowerCase(Locale.ROOT) : "table";
            if (catType.contains("view") && defaultCategory == ObjectCategory.TABLE && !fromDdl) {
                category = ObjectCategory.VIEW;
                wireType = catType;
            } else if ("stream".equals(catType)) {
                category = ObjectCategory.STREAM;
                wireType = "stream";
            } else if ("stage".equals(catType)) {
                category = ObjectCategory.STAGE;
                wireType = "stage";
            }
        } else {
            if (effDb == null && option.getDefaultDatabase() != null && !option.getDefaultDatabase().isBlank()) {
                effDb = sqlEnv.normalizePart(DataObjectType.CATALOG, option.getDefaultDatabase(), false);
            }
            if (effSch == null && option.getDefaultSchema() != null && !option.getDefaultSchema().isBlank()) {
                effSch = sqlEnv.normalizePart(DataObjectType.SCHEMA, option.getDefaultSchema(), false);
            }
        }

        String lookupDb = effDb != null ? effDb : (catTbl != null && catTbl.getDatabase() != null ? catTbl.getDatabase() : sqlEnv.currentDatabase());
        String lookupSch = effSch != null ? effSch : (catTbl != null && catTbl.getSchema() != null ? catTbl.getSchema() : sqlEnv.currentSchema());
        String key = category.name() + "|"
                + sqlEnv.lookupKey(DataObjectType.CATALOG, lookupDb, false) + "|"
                + sqlEnv.lookupKey(DataObjectType.SCHEMA, lookupSch, false) + "|"
                + sqlEnv.lookupKey(DataObjectType.TABLE, objName, rc.quotedObject());

        BoundObject obj = physicalObjects.get(key);
        if (obj == null) {
            obj = new BoundObject(
                    nextObjectId(),
                    category,
                    rc.server() != null ? rc.server() : option.getDefaultServer(),
                    effDb,
                    effSch,
                    objName,
                    wireType);
            obj.setLookupDatabase(lookupDb);
            obj.setLookupSchema(lookupSch);
            obj.setEndpointKind(epKind);
            obj.setEndpointIntroduction(intro != null ? intro : EndpointIntroduction.CATALOG_REFERENCE);
            obj.setNormalizedName(buildDisplayName(effDb, effSch, objName));
            obj.setDisplayName(buildDisplayName(effDb, effSch, objName));
            if (qname.dbLink() != null && !qname.dbLink().isBlank()) {
                obj.setDbLink(qname.dbLink());
            }
            if (qname.templated()) {
                obj.setTemplatedParts(Boolean.TRUE);
            }
            if (epKind == EndpointKind.LOCAL_TEMP_TABLE
                    || epKind == EndpointKind.GLOBAL_TEMP_TABLE
                    || epKind == EndpointKind.TABLE_VARIABLE
                    || epKind == EndpointKind.TEMPDB_OBJECT) {
                obj.setTemporary(true);
            }
            physicalObjects.put(key, obj);
            allObjects.add(obj);

            // Seed columns from catalog if fromDDL or ER diagram requested
            if (catTbl != null && (catTbl.isFromDdl() || option.isShowERDiagram())) {
                if (catTbl.isFromDdl()) {
                    obj.setFromDDL(Boolean.TRUE);
                }
                for (CatalogColumn cc : catTbl.orderedColumns()) {
                    BoundColumn bc = obj.getOrCreateColumn(this, cc.getName(), false, null, sqlHash);
                    copyCatalogColumnMetadata(cc, bc);
                }
            }
        }

        if (isTarget) {
            obj.setTarget(true);
        }
        if (fromDdl) {
            obj.setFromDDL(Boolean.TRUE);
        }
        if (createdInSql) {
            obj.setCreatedInSql(Boolean.TRUE);
        }
        obj.addCoordinates(toCoords(qname.span(), sqlHash));
        return obj;
    }

    public BoundObject bindPathOrStageObject(
            String uriOrStage,
            ObjectCategory category,
            String wireType,
            SourceSpan span,
            boolean isTarget,
            String sqlHash) {
        String key = category.name() + "|||" + uriOrStage;
        BoundObject obj = physicalObjects.get(key);
        if (obj == null) {
            obj = new BoundObject(
                    nextObjectId(),
                    category,
                    option.getDefaultServer(),
                    null,
                    null,
                    uriOrStage,
                    wireType);
            obj.setDisplayName(uriOrStage);
            if (category == ObjectCategory.PATH) {
                obj.setUri(uriOrStage);
                obj.setLocation(uriOrStage);
                obj.setFileType(extractUriScheme(uriOrStage));
            } else if (category == ObjectCategory.STAGE) {
                obj.setLocation(uriOrStage);
            }
            obj.setEndpointKind(EndpointKind.CATALOG_OBJECT);
            obj.setEndpointIntroduction(EndpointIntroduction.CATALOG_REFERENCE);
            physicalObjects.put(key, obj);
            allObjects.add(obj);
        }
        if (isTarget) {
            obj.setTarget(true);
        }
        obj.addCoordinates(toCoords(span, sqlHash));
        return obj;
    }

    public BoundObject bindSequenceObject(QualifiedName seqName, SourceSpan span, String sqlHash) {
        TSQLEnv.ResolvedCoordinates rc = sqlEnv.resolveNameParts(seqName);
        String key = "SEQUENCE|"
                + sqlEnv.lookupKey(DataObjectType.CATALOG, rc.database(), false) + "|"
                + sqlEnv.lookupKey(DataObjectType.SCHEMA, rc.schema(), false) + "|"
                + sqlEnv.lookupKey(DataObjectType.TABLE, rc.objectName(), rc.quotedObject());
        BoundObject obj = physicalObjects.get(key);
        if (obj == null) {
            obj = new BoundObject(
                    nextObjectId(),
                    ObjectCategory.SEQUENCE,
                    rc.server(),
                    rc.explicitDatabase() ? rc.database() : null,
                    rc.explicitSchema() ? rc.schema() : null,
                    rc.objectName(),
                    "sequence");
            obj.setDisplayName(buildDisplayName(obj.getDatabase(), obj.getSchema(), obj.getName()));
            obj.setEndpointKind(EndpointKind.CATALOG_OBJECT);
            obj.setEndpointIntroduction(EndpointIntroduction.CATALOG_REFERENCE);
            physicalObjects.put(key, obj);
            allObjects.add(obj);
        }
        obj.addCoordinates(toCoords(span, sqlHash));
        return obj;
    }

    public BoundObject bindSynonymObject(
            QualifiedName synName,
            BoundObject targetObj,
            boolean fromDdl,
            SourceSpan span,
            String sqlHash) {
        TSQLEnv.ResolvedCoordinates rc = sqlEnv.resolveNameParts(synName);
        String key = "SYNONYM|"
                + sqlEnv.lookupKey(DataObjectType.CATALOG, rc.database(), false) + "|"
                + sqlEnv.lookupKey(DataObjectType.SCHEMA, rc.schema(), false) + "|"
                + sqlEnv.lookupKey(DataObjectType.TABLE, rc.objectName(), rc.quotedObject());
        BoundObject obj = physicalObjects.get(key);
        if (obj == null) {
            obj = new BoundObject(
                    nextObjectId(),
                    ObjectCategory.SYNONYM,
                    rc.server(),
                    rc.explicitDatabase() ? rc.database() : null,
                    rc.explicitSchema() ? rc.schema() : null,
                    rc.objectName(),
                    "synonym");
            obj.setDisplayName(buildDisplayName(obj.getDatabase(), obj.getSchema(), obj.getName()));
            obj.setSynonymTarget(targetObj);
            obj.setFromDDL(fromDdl ? Boolean.TRUE : null);
            physicalObjects.put(key, obj);
            allObjects.add(obj);
        }
        obj.addCoordinates(toCoords(span, sqlHash));
        return obj;
    }

    public BoundObject createResultSet(
            ResultSetType rsType,
            String customName,
            boolean topLevelSelect,
            String procedureId,
            String sqlHash,
            SourceSpan span) {
        String name;
        if (customName != null && !customName.isBlank()) {
            name = customName;
        } else if (rsType == ResultSetType.select_list) {
            name = "RS-" + (++selectRsCounter);
        } else {
            String prefix = rsType.defaultDisplayPrefix();
            name = prefix.endsWith("-") || prefix.endsWith("_")
                    ? prefix + (++selectRsCounter)
                    : (prefix.isEmpty() ? rsType.wireType().toUpperCase(Locale.ROOT) + "-" + (++selectRsCounter) : prefix);
        }

        ObjectCategory cat = rsType == ResultSetType.variable ? ObjectCategory.VARIABLE : ObjectCategory.RESULTSET;
        BoundObject rs = new BoundObject(
                nextObjectId(),
                cat,
                option.getDefaultServer(),
                null,
                null,
                name,
                rsType.wireType());
        rs.setResultSetType(rsType);
        rs.setDisplayName(name);
        rs.setTopLevelSelect(topLevelSelect);
        rs.setProcedureId(procedureId);
        rs.setQueryHashId(sqlHash);
        rs.setLookupDatabase(sqlEnv.currentDatabase());
        rs.setLookupSchema(sqlEnv.currentSchema());

        if (rsType == ResultSetType.cte) {
            rs.setEndpointKind(EndpointKind.CTE);
            rs.setEndpointIntroduction(EndpointIntroduction.CTE_DEFINITION);
        } else if (rsType == ResultSetType.function || rsType == ResultSetType.result_of) {
            rs.setEndpointKind(EndpointKind.TVF_RESULT);
            rs.setEndpointIntroduction(EndpointIntroduction.FUNCTION_CALL);
        } else if (rsType == ResultSetType.variable) {
            rs.setEndpointKind(EndpointKind.VARIABLE);
            rs.setEndpointIntroduction(EndpointIntroduction.VARIABLE_REFERENCE);
        } else {
            rs.setEndpointKind(EndpointKind.DERIVED_RESULTSET);
            rs.setEndpointIntroduction(EndpointIntroduction.DERIVED_SUBQUERY);
        }
        rs.addCoordinates(toCoords(span, sqlHash));
        allObjects.add(rs);
        return rs;
    }

    public BoundObject getOrCreateConstantsTable(String sqlHash) {
        if (constantsTable == null) {
            constantsTable = new BoundObject(
                    nextObjectId(),
                    ObjectCategory.TABLE,
                    option.getDefaultServer(),
                    null,
                    null,
                    SQL_CONSTANTS_TABLE,
                    "constant");
            constantsTable.setDisplayName(SQL_CONSTANTS_TABLE);
            constantsTable.setEndpointKind(EndpointKind.CONSTANT);
            constantsTable.setEndpointIntroduction(EndpointIntroduction.LITERAL);
            constantsTable.setQueryHashId(sqlHash);
            allObjects.add(constantsTable);
        }
        return constantsTable;
    }

    public BoundObject getOrCreateOrphanTable(String sqlHash) {
        if (orphanTable == null) {
            orphanTable = new BoundObject(
                    nextObjectId(),
                    ObjectCategory.TABLE,
                    option.getDefaultServer(),
                    null,
                    null,
                    ORPHAN_PSEUDO_TABLE,
                    "table");
            orphanTable.setDisplayName(ORPHAN_PSEUDO_TABLE);
            orphanTable.setEndpointKind(EndpointKind.UNKNOWN);
            orphanTable.setEndpointIntroduction(EndpointIntroduction.UNKNOWN);
            orphanTable.setQueryHashId(sqlHash);
            allObjects.add(orphanTable);
        }
        return orphanTable;
    }

    // -------------------------------------------------------------------------
    // Packages, Routines, Processes
    // -------------------------------------------------------------------------

    public BoundPackage bindPackage(QualifiedName pkgName, SourceSpan span, String sqlHash) {
        TSQLEnv.ResolvedCoordinates rc = sqlEnv.resolveNameParts(pkgName);
        String key = sqlEnv.lookupKey(DataObjectType.CATALOG, rc.database(), false) + "|"
                + sqlEnv.lookupKey(DataObjectType.SCHEMA, rc.schema(), false) + "|"
                + sqlEnv.lookupKey(DataObjectType.PROCEDURE, rc.objectName(), rc.quotedObject());
        BoundPackage pkg = packages.get(key);
        if (pkg == null) {
            pkg = new BoundPackage(
                    nextObjectId(),
                    rc.server(),
                    rc.explicitDatabase() ? rc.database() : null,
                    rc.explicitSchema() ? rc.schema() : null,
                    rc.objectName());
            pkg.setLookupDatabase(rc.database());
            pkg.setLookupSchema(rc.schema());
            packages.put(key, pkg);
        }
        pkg.addCoordinates(toCoords(span, sqlHash));
        return pkg;
    }

    public BoundRoutine bindRoutine(
            QualifiedName routineName,
            String kind,
            BoundPackage parentPackage,
            SourceSpan span,
            String sqlHash) {
        TSQLEnv.ResolvedCoordinates rc = sqlEnv.resolveNameParts(routineName);
        String pkgName = parentPackage != null ? parentPackage.getName() : null;
        String db = parentPackage != null && parentPackage.getDatabase() != null
                ? parentPackage.getDatabase()
                : (rc.explicitDatabase() ? rc.database() : null);
        String sch = parentPackage != null && parentPackage.getSchema() != null
                ? parentPackage.getSchema()
                : (rc.explicitSchema() ? rc.schema() : null);
        String wireKind = kind != null ? kind.toLowerCase(Locale.ROOT) : "procedure";

        String key = sqlEnv.lookupKey(DataObjectType.CATALOG, db != null ? db : rc.database(), false) + "|"
                + sqlEnv.lookupKey(DataObjectType.SCHEMA, sch != null ? sch : rc.schema(), false) + "|"
                + (pkgName != null ? sqlEnv.lookupKey(DataObjectType.PROCEDURE, pkgName, false) : "") + "|"
                + sqlEnv.lookupKey(DataObjectType.PROCEDURE, rc.objectName(), rc.quotedObject());

        BoundRoutine routine = routines.get(key);
        if (routine == null) {
            String displayName = pkgName != null ? pkgName + "." + rc.objectName() : rc.objectName();
            routine = new BoundRoutine(
                    nextObjectId(),
                    rc.server(),
                    db,
                    sch,
                    pkgName,
                    displayName,
                    wireKind);
            routine.setLookupDatabase(rc.database());
            routine.setLookupSchema(rc.schema());
            routines.put(key, routine);
            if (parentPackage != null) {
                parentPackage.getProcedures().add(routine);
            }
        }
        routine.addCoordinates(toCoords(span, sqlHash));
        return routine;
    }

    public BoundProcess createProcess(
            String name,
            String type,
            BoundRoutine enclosingRoutine,
            String sqlHash,
            SourceSpan span,
            String rawSqlCode) {
        BoundProcess proc = new BoundProcess(
                nextObjectId(),
                option.getDefaultServer(),
                sqlEnv.currentDatabase(),
                sqlEnv.currentSchema(),
                name,
                type);
        if (enclosingRoutine != null) {
            proc.setProcedureId(enclosingRoutine.getId());
            proc.setProcedureName(enclosingRoutine.getName());
        }
        proc.setQueryHashId(sqlHash);
        proc.addCoordinates(toCoords(span, sqlHash));
        if (option.isTransform() && rawSqlCode != null && !rawSqlCode.isBlank()) {
            List<Coordinate> txCoords = option.isTransformCoordinate() ? toCoords(span, sqlHash) : null;
            proc.getTransforms().add(new Transform(type, rawSqlCode, txCoords));
        }
        processes.add(proc);
        return proc;
    }

    // -------------------------------------------------------------------------
    // Directed Lineage Edges & Star-Link Back-Propagation
    // -------------------------------------------------------------------------

    public BoundEdge addColumnEdge(
            RelationshipType relType,
            EffectType effectType,
            BoundObject targetObj,
            BoundColumn targetCol,
            List<BoundSourceRef> sources,
            BoundProcess process,
            BoundRoutine routine,
            String sqlHash,
            String functionName,
            String clause,
            String joinType,
            String condition) {
        if (targetObj == null || targetCol == null || sources == null || sources.isEmpty()) {
            return null;
        }
        // Filter self-loops on identical column ID
        List<BoundSourceRef> validSources = new ArrayList<>(sources.size());
        Set<String> seenSrcIds = new LinkedHashSet<>();
        for (BoundSourceRef src : sources) {
            if (src == null || src.col() == null || src.obj() == null) {
                continue;
            }
            if (src.col().getId().equals(targetCol.getId())) {
                continue;
            }
            String srcKey = src.col().getId() + ":" + (src.clauseType() != null ? src.clauseType() : "");
            if (seenSrcIds.add(srcKey)) {
                validSources.add(src);
            }
        }
        if (validSources.isEmpty()) {
            return null;
        }

        // Deduplicate identical (type, effect, targetCol, sortedSrcCols) edges
        StringBuilder sig = new StringBuilder();
        sig.append(relType.name()).append('|')
                .append(effectType != null ? effectType.name() : "").append('|')
                .append(targetCol.getId()).append('|');
        for (BoundSourceRef s : validSources) {
            sig.append(s.col().getId()).append(',');
        }
        if (joinType != null) {
            sig.append('|').append(joinType);
        }
        if (!edgeDedupKeys.add(sig.toString())) {
            return null;
        }

        if (process != null) {
            targetObj.addProcessId(process.getId());
            for (BoundSourceRef s : validSources) {
                s.obj().addProcessId(process.getId());
            }
        }
        if (routine != null) {
            if (targetObj.getCategory() == ObjectCategory.TABLE || targetObj.getCategory() == ObjectCategory.VIEW) {
                routine.getBoundTables().add(targetObj.getDisplayName());
            }
            for (BoundSourceRef s : validSources) {
                if (s.obj().getCategory() == ObjectCategory.TABLE || s.obj().getCategory() == ObjectCategory.VIEW) {
                    routine.getBoundTables().add(s.obj().getDisplayName());
                }
            }
        }

        BoundEdge edge = new BoundEdge(
                nextRelationId(),
                relType,
                effectType,
                targetObj,
                targetCol,
                validSources);
        if (process != null) {
            edge.setProcessId(process.getId());
            edge.setProcessType(process.getType());
        }
        if (routine != null) {
            edge.setProcedureId(routine.getId());
        }
        edge.setSqlHash(sqlHash);
        edge.setFunction(functionName);
        edge.setClause(clause);
        edge.setJoinType(joinType);
        edge.setCondition(condition);
        edges.add(edge);
        return edge;
    }

    public BoundEdge addCallEdge(
            BoundRoutine callerRoutine,
            BoundObject callerObj,
            List<RelationshipElement> callees,
            boolean builtIn,
            String callStmtText,
            SourceSpan callSpan,
            String sqlHash) {
        if (callees == null || callees.isEmpty()) {
            return null;
        }
        BoundEdge edge = new BoundEdge(
                nextRelationId(),
                RelationshipType.call,
                null,
                null,
                null,
                List.of());
        edge.setCallerRoutine(callerRoutine);
        edge.setCallerObj(callerObj);
        edge.setCallees(callees);
        edge.setBuiltIn(builtIn);
        edge.setCallStmt(callStmtText);
        edge.setCallCoordinate(toCoords(callSpan, sqlHash));
        edge.setSqlHash(sqlHash);
        edges.add(edge);
        return edge;
    }

    public void registerStarLink(StarLink link) {
        if (link != null) {
            starLinks.add(link);
        }
    }

    /**
     * Back-propagates concrete column references through {@code SELECT *} chains when upstream physical
     * tables lacked prior DDL metadata ({@code StarLinkResolver} in {@code 03 §2.2, OBL-037}).
     */
    public void resolveStarBackPropagation() {
        if (starLinks.isEmpty()) {
            return;
        }
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 8) {
            changed = false;
            // Iterate in reverse so downstream concrete columns on views/CTEs propagate back to base tables,
            // and then forward so base table columns propagate forward!
            for (int i = starLinks.size() - 1; i >= 0; i--) {
                StarLink link = starLinks.get(i);
                for (BoundColumn targetCol : new ArrayList<>(link.targetObj().orderedColumns())) {
                    if (isPseudoOrStarColumn(targetCol.getName())) {
                        continue;
                    }
                    if (isExcludedByExcept(targetCol.getName(), link.exceptCols())) {
                        continue;
                    }
                    // If targetCol has no incoming fdd edge yet, materialize matching column on sourceObj
                    if (!hasIncomingDirectEdge(targetCol)) {
                        BoundColumn upstreamCol = link.sourceObj().getOrCreateColumn(
                                this, targetCol.getName(), false, null, link.sqlHash());
                        BoundEdge added = addColumnEdge(
                                RelationshipType.fdd,
                                link.effectType(),
                                link.targetObj(),
                                targetCol,
                                List.of(new BoundSourceRef(
                                        upstreamCol,
                                        link.sourceObj(),
                                        link.sourceAlias(),
                                        "SELECT",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null)),
                                null,
                                null,
                                link.sqlHash(),
                                null,
                                null,
                                null,
                                null);
                        if (added != null) {
                            added.setProcessId(link.processId());
                            added.setProcedureId(link.procedureId());
                            changed = true;
                        }
                    }
                }
            }
            for (StarLink link : starLinks) {
                for (BoundColumn srcCol : new ArrayList<>(link.sourceObj().orderedColumns())) {
                    if (isPseudoOrStarColumn(srcCol.getName())) {
                        continue;
                    }
                    if (isExcludedByExcept(srcCol.getName(), link.exceptCols())) {
                        continue;
                    }
                    BoundColumn tgtCol = link.targetObj().getOrCreateColumn(
                            this, srcCol.getName(), false, null, link.sqlHash());
                    BoundEdge added = addColumnEdge(
                            RelationshipType.fdd,
                            link.effectType(),
                            link.targetObj(),
                            tgtCol,
                            List.of(new BoundSourceRef(
                                    srcCol,
                                    link.sourceObj(),
                                    link.sourceAlias(),
                                    "SELECT",
                                    null,
                                    null,
                                    null,
                                    null,
                                    null)),
                            null,
                            null,
                            link.sqlHash(),
                            null,
                            null,
                            null,
                            null);
                    if (added != null) {
                        added.setProcessId(link.processId());
                        added.setProcedureId(link.procedureId());
                        changed = true;
                    }
                }
            }
        }
    }

    private boolean hasIncomingDirectEdge(BoundColumn col) {
        for (BoundEdge e : edges) {
            if (e.getType() == RelationshipType.fdd
                    && e.getTargetCol() != null
                    && e.getTargetCol().getId().equals(col.getId())) {
                return true;
            }
        }
        return false;
    }

    private boolean isExcludedByExcept(String colName, List<String> exceptCols) {
        if (exceptCols == null || exceptCols.isEmpty() || colName == null) {
            return false;
        }
        for (String ex : exceptCols) {
            if (sqlEnv.identifiersMatch(DataObjectType.COLUMN, colName, false, ex, false)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPseudoOrStarColumn(String name) {
        return name == null
                || "*".equals(name)
                || name.endsWith(".*")
                || "RelationRows".equalsIgnoreCase(name);
    }

    public static void copyCatalogColumnMetadata(CatalogColumn cc, BoundColumn bc) {
        if (cc == null || bc == null) {
            return;
        }
        if (cc.getDataType() != null) {
            bc.setDataType(cc.getDataType());
        }
        if (Boolean.TRUE.equals(cc.getPrimaryKey())) {
            bc.setPrimaryKey(Boolean.TRUE);
        }
        if (Boolean.TRUE.equals(cc.getUnqiueKey())) {
            bc.setUnqiueKey(Boolean.TRUE);
        }
        if (Boolean.TRUE.equals(cc.getIndexKey())) {
            bc.setIndexKey(Boolean.TRUE);
        }
        if (Boolean.TRUE.equals(cc.getForeignKey())) {
            bc.setForeignKey(Boolean.TRUE);
        }
        if (cc.getNullable() != null) {
            bc.setNullable(cc.getNullable());
        }
        if (Boolean.TRUE.equals(cc.getIdentity())) {
            bc.setIdentity(Boolean.TRUE);
        }
        if (Boolean.TRUE.equals(cc.getComputed())) {
            bc.setComputed(Boolean.TRUE);
        }
        if (cc.getPersisted() != null) {
            bc.setPersisted(cc.getPersisted());
        }
        if (cc.getComputedDefinition() != null) {
            bc.setComputedDefinition(cc.getComputedDefinition());
        }
        if (cc.getDefaultValue() != null) {
            bc.setDefaultValue(cc.getDefaultValue());
        }
        if (cc.getDefaultName() != null) {
            bc.setDefaultName(cc.getDefaultName());
        }
        if (cc.getComment() != null) {
            bc.setComment(cc.getComment());
        }
    }

    public List<BoundObject> allObjects() {
        return allObjects;
    }

    public Collection<BoundPackage> packages() {
        return packages.values();
    }

    public Collection<BoundRoutine> routines() {
        return routines.values();
    }

    public List<BoundProcess> processes() {
        return processes;
    }

    public List<BoundEdge> edges() {
        return edges;
    }

    public void replaceEdges(List<BoundEdge> updatedEdges) {
        edges.clear();
        edges.addAll(updatedEdges);
    }

    public void replaceObjects(List<BoundObject> updatedObjects) {
        allObjects.clear();
        allObjects.addAll(updatedObjects);
    }

    private static EndpointKind classifyEndpointKind(String name, boolean templated) {
        if (templated || (name != null && (name.contains("{{") || name.contains("${")))) {
            return EndpointKind.DYNAMIC_TEMPLATE;
        }
        if (name == null || name.isEmpty()) {
            return EndpointKind.UNKNOWN;
        }
        if (name.startsWith("##")) {
            return EndpointKind.GLOBAL_TEMP_TABLE;
        }
        if (name.startsWith("#")) {
            return EndpointKind.LOCAL_TEMP_TABLE;
        }
        if (name.startsWith("@")) {
            return EndpointKind.TABLE_VARIABLE;
        }
        return EndpointKind.CATALOG_OBJECT;
    }

    public static String buildDisplayName(String db, String schema, String name) {
        StringBuilder sb = new StringBuilder();
        if (db != null && !db.isBlank()) {
            sb.append(db).append('.');
        }
        if (schema != null && !schema.isBlank()) {
            sb.append(schema).append('.');
        }
        sb.append(name != null ? name : "");
        return sb.toString();
    }

    private static String extractUriScheme(String uri) {
        if (uri == null) {
            return null;
        }
        int idx = uri.indexOf("://");
        return idx > 0 ? uri.substring(0, idx).toLowerCase(Locale.ROOT) : null;
    }

    // -------------------------------------------------------------------------
    // Bound Node Classes
    // -------------------------------------------------------------------------

    public record StarLink(
            BoundObject sourceObj,
            String sourceAlias,
            BoundObject targetObj,
            List<String> exceptCols,
            EffectType effectType,
            String processId,
            String procedureId,
            String sqlHash
    ) {
    }

    public record BoundSourceRef(
            BoundColumn col,
            BoundObject obj,
            String alias,
            String clauseType,
            List<Coordinate> coordinates,
            List<Transform> transforms,
            List<CandidateTable> candidateParents,
            String function,
            String value
    ) {
    }

    public static final class BoundObject {
        private final String id;
        private ObjectCategory category;
        private String server;
        private String database;
        private String schema;
        private String lookupDatabase;
        private String lookupSchema;
        private String name;
        private String displayName;
        private String alias;
        private String uri;
        private String type;
        private ResultSetType resultSetType;
        private String subType;
        private String fileType;
        private String fileFormat;
        private String location;
        private String dataSourceName;
        private String namespace;
        private String dbLink;
        private boolean target;
        private Boolean fromDDL;
        private Boolean createdInSql;
        private EndpointKind endpointKind;
        private EndpointIntroduction endpointIntroduction;
        private String normalizedName;
        private Boolean templatedParts;
        private String procedureId;
        private String queryHashId;
        private boolean topLevelSelect;
        private boolean temporary;
        private boolean builtInFunction;
        private BoundObject synonymTarget;

        private final Set<String> processIds = new LinkedHashSet<>();
        private final List<Coordinate> coordinates = new ArrayList<>();
        private final List<CandidateTable> candidateTables = new ArrayList<>();
        private final Map<String, BoundColumn> columns = new LinkedHashMap<>();
        private BoundColumn relationRowsColumn;

        public BoundObject(
                String id,
                ObjectCategory category,
                String server,
                String database,
                String schema,
                String name,
                String type) {
            this.id = id;
            this.category = category;
            this.server = server;
            this.database = database;
            this.schema = schema;
            this.name = name;
            this.type = type;
            this.displayName = buildDisplayName(database, schema, name);
        }

        public BoundColumn getOrCreateColumn(
                ModelBindingManager mgr,
                String rawColName,
                boolean quotedCol,
                SourceSpan span,
                String sqlHash) {
            String effectiveName = rawColName != null && !rawColName.isBlank() ? rawColName : "EXPR";
            if ("RelationRows".equalsIgnoreCase(effectiveName)) {
                return getOrCreateRelationRows(mgr);
            }
            String normName;
            String key;
            if ("*".equals(effectiveName) || effectiveName.endsWith(".*") || category == ObjectCategory.RESULTSET) {
                normName = quotedCol ? mgr.sqlEnv().profile().stripQuotes(effectiveName) : effectiveName;
                key = mgr.sqlEnv().lookupKey(DataObjectType.COLUMN, normName, quotedCol);
                if (key.isEmpty()) {
                    key = normName.toLowerCase(Locale.ROOT);
                }
            } else {
                normName = mgr.sqlEnv().normalizePart(DataObjectType.COLUMN, effectiveName, quotedCol);
                if (normName == null || normName.isEmpty()) {
                    normName = effectiveName;
                }
                key = mgr.sqlEnv().lookupKey(DataObjectType.COLUMN, normName, quotedCol);
            }

            BoundColumn col = columns.get(key);
            if (col == null) {
                col = new BoundColumn(mgr.nextObjectId(), this, normName);
                if ("*".equals(normName) || normName.endsWith(".*")) {
                    col.setStar(Boolean.TRUE);
                }
                // Enrich from catalog if physical table/view
                if (category == ObjectCategory.TABLE || category == ObjectCategory.VIEW) {
                    QualifiedName qn = buildQualifiedLookup();
                    CatalogTable ct = mgr.sqlEnv().resolveTable(qn);
                    CatalogColumn cc = mgr.sqlEnv().resolveColumn(ct, normName, quotedCol);
                    copyCatalogColumnMetadata(cc, col);
                }
                columns.put(key, col);
            }
            col.addCoordinates(mgr.toCoords(span, sqlHash));
            return col;
        }

        public BoundColumn findColumn(TSQLEnv env, String colName, boolean quoted) {
            if (colName == null) {
                return null;
            }
            if ("RelationRows".equalsIgnoreCase(colName)) {
                return relationRowsColumn;
            }
            String key = env.lookupKey(DataObjectType.COLUMN, colName, quoted);
            BoundColumn exact = columns.get(key);
            if (exact != null) {
                return exact;
            }
            for (BoundColumn c : columns.values()) {
                if (env.identifiersMatch(DataObjectType.COLUMN, c.getName(), false, colName, quoted)) {
                    return c;
                }
            }
            return null;
        }

        public BoundColumn getOrCreateRelationRows(ModelBindingManager mgr) {
            if (relationRowsColumn == null) {
                relationRowsColumn = new BoundColumn(mgr.nextObjectId(), this, "RelationRows");
                relationRowsColumn.setSource("system");
            }
            return relationRowsColumn;
        }

        public BoundColumn peekRelationRows() {
            return relationRowsColumn;
        }

        public List<BoundColumn> orderedColumns() {
            return List.copyOf(columns.values());
        }

        public List<BoundColumn> orderedColumnsIncludingRelationRows(boolean includeRelationRows) {
            List<BoundColumn> list = new ArrayList<>(columns.values());
            if (includeRelationRows && relationRowsColumn != null) {
                list.add(relationRowsColumn);
            }
            return list;
        }

        private QualifiedName buildQualifiedLookup() {
            List<String> parts = new ArrayList<>(3);
            String db = database != null ? database : lookupDatabase;
            String sch = schema != null ? schema : lookupSchema;
            if (db != null && !db.isBlank()) {
                parts.add(db);
            }
            if (sch != null && !sch.isBlank()) {
                parts.add(sch);
            }
            parts.add(name);
            return QualifiedName.ofParts(parts, null);
        }

        public void addCoordinates(List<Coordinate> coords) {
            if (coords == null) {
                return;
            }
            for (Coordinate c : coords) {
                if (c != null && !coordinates.contains(c)) {
                    coordinates.add(c);
                }
            }
        }

        public void addProcessId(String pid) {
            if (pid != null && !pid.isBlank()) {
                processIds.add(pid);
            }
        }

        public String formattedProcessIds() {
            return processIds.isEmpty() ? null : String.join(",", processIds);
        }

        public String getId() {
            return id;
        }

        public ObjectCategory getCategory() {
            return category;
        }

        public void setCategory(ObjectCategory category) {
            this.category = category;
        }

        public String getServer() {
            return server;
        }

        public void setServer(String server) {
            this.server = server;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public String getSchema() {
            return schema;
        }

        public void setSchema(String schema) {
            this.schema = schema;
        }

        public String getLookupDatabase() {
            return lookupDatabase;
        }

        public void setLookupDatabase(String lookupDatabase) {
            this.lookupDatabase = lookupDatabase;
        }

        public String getLookupSchema() {
            return lookupSchema;
        }

        public void setLookupSchema(String lookupSchema) {
            this.lookupSchema = lookupSchema;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDisplayName() {
            return displayName != null ? displayName : name;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getAlias() {
            return alias;
        }

        public void setAlias(String alias) {
            this.alias = alias;
        }

        public String getUri() {
            return uri;
        }

        public void setUri(String uri) {
            this.uri = uri;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public ResultSetType getResultSetType() {
            return resultSetType;
        }

        public void setResultSetType(ResultSetType resultSetType) {
            this.resultSetType = resultSetType;
        }

        public String getSubType() {
            return subType;
        }

        public void setSubType(String subType) {
            this.subType = subType;
        }

        public String getFileType() {
            return fileType;
        }

        public void setFileType(String fileType) {
            this.fileType = fileType;
        }

        public String getFileFormat() {
            return fileFormat;
        }

        public void setFileFormat(String fileFormat) {
            this.fileFormat = fileFormat;
        }

        public String getLocation() {
            return location;
        }

        public void setLocation(String location) {
            this.location = location;
        }

        public String getDataSourceName() {
            return dataSourceName;
        }

        public void setDataSourceName(String dataSourceName) {
            this.dataSourceName = dataSourceName;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getDbLink() {
            return dbLink;
        }

        public void setDbLink(String dbLink) {
            this.dbLink = dbLink;
        }

        public boolean isTarget() {
            return target;
        }

        public void setTarget(boolean target) {
            this.target = target;
        }

        public Boolean getFromDDL() {
            return fromDDL;
        }

        public void setFromDDL(Boolean fromDDL) {
            this.fromDDL = fromDDL;
        }

        public Boolean getCreatedInSql() {
            return createdInSql;
        }

        public void setCreatedInSql(Boolean createdInSql) {
            this.createdInSql = createdInSql;
        }

        public EndpointKind getEndpointKind() {
            return endpointKind;
        }

        public void setEndpointKind(EndpointKind endpointKind) {
            this.endpointKind = endpointKind;
        }

        public EndpointIntroduction getEndpointIntroduction() {
            return endpointIntroduction;
        }

        public void setEndpointIntroduction(EndpointIntroduction endpointIntroduction) {
            this.endpointIntroduction = endpointIntroduction;
        }

        public String getNormalizedName() {
            return normalizedName;
        }

        public void setNormalizedName(String normalizedName) {
            this.normalizedName = normalizedName;
        }

        public Boolean getTemplatedParts() {
            return templatedParts;
        }

        public void setTemplatedParts(Boolean templatedParts) {
            this.templatedParts = templatedParts;
        }

        public String getProcedureId() {
            return procedureId;
        }

        public void setProcedureId(String procedureId) {
            this.procedureId = procedureId;
        }

        public String getQueryHashId() {
            return queryHashId;
        }

        public void setQueryHashId(String queryHashId) {
            this.queryHashId = queryHashId;
        }

        public boolean isTopLevelSelect() {
            return topLevelSelect;
        }

        public void setTopLevelSelect(boolean topLevelSelect) {
            this.topLevelSelect = topLevelSelect;
        }

        public boolean isTemporary() {
            return temporary;
        }

        public void setTemporary(boolean temporary) {
            this.temporary = temporary;
        }

        public boolean isBuiltInFunction() {
            return builtInFunction;
        }

        public void setBuiltInFunction(boolean builtInFunction) {
            this.builtInFunction = builtInFunction;
        }

        public BoundObject getSynonymTarget() {
            return synonymTarget;
        }

        public void setSynonymTarget(BoundObject synonymTarget) {
            this.synonymTarget = synonymTarget;
        }

        public List<Coordinate> getCoordinates() {
            return coordinates;
        }

        public List<CandidateTable> getCandidateTables() {
            return candidateTables;
        }
    }

    public static final class BoundColumn {
        private final String id;
        private final BoundObject parent;
        private String name;
        private String displayName;
        private String dataType;
        private String comment;
        private String source;
        private String qualifiedTable;
        private Boolean isFunction;
        private Boolean star;
        private Boolean primaryKey;
        private Boolean unqiueKey;
        private Boolean indexKey;
        private Boolean foreignKey;
        private Boolean nullable;
        private Boolean identity;
        private Boolean computed;
        private Boolean persisted;
        private String computedDefinition;
        private String defaultValue;
        private String defaultName;
        private final List<Coordinate> coordinates = new ArrayList<>();

        public BoundColumn(String id, BoundObject parent, String name) {
            this.id = id;
            this.parent = parent;
            this.name = name;
        }

        public void addCoordinates(List<Coordinate> coords) {
            if (coords == null) {
                return;
            }
            for (Coordinate c : coords) {
                if (c != null && !coordinates.contains(c)) {
                    coordinates.add(c);
                }
            }
        }

        public String getId() {
            return id;
        }

        public BoundObject getParent() {
            return parent;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getDataType() {
            return dataType;
        }

        public void setDataType(String dataType) {
            this.dataType = dataType;
        }

        public String getComment() {
            return comment;
        }

        public void setComment(String comment) {
            this.comment = comment;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getQualifiedTable() {
            return qualifiedTable;
        }

        public void setQualifiedTable(String qualifiedTable) {
            this.qualifiedTable = qualifiedTable;
        }

        public Boolean getIsFunction() {
            return isFunction;
        }

        public void setIsFunction(Boolean isFunction) {
            this.isFunction = isFunction;
        }

        public Boolean getStar() {
            return star;
        }

        public void setStar(Boolean star) {
            this.star = star;
        }

        public Boolean getPrimaryKey() {
            return primaryKey;
        }

        public void setPrimaryKey(Boolean primaryKey) {
            this.primaryKey = primaryKey;
        }

        public Boolean getUnqiueKey() {
            return unqiueKey;
        }

        public void setUnqiueKey(Boolean unqiueKey) {
            this.unqiueKey = unqiueKey;
        }

        public Boolean getIndexKey() {
            return indexKey;
        }

        public void setIndexKey(Boolean indexKey) {
            this.indexKey = indexKey;
        }

        public Boolean getForeignKey() {
            return foreignKey;
        }

        public void setForeignKey(Boolean foreignKey) {
            this.foreignKey = foreignKey;
        }

        public Boolean getNullable() {
            return nullable;
        }

        public void setNullable(Boolean nullable) {
            this.nullable = nullable;
        }

        public Boolean getIdentity() {
            return identity;
        }

        public void setIdentity(Boolean identity) {
            this.identity = identity;
        }

        public Boolean getComputed() {
            return computed;
        }

        public void setComputed(Boolean computed) {
            this.computed = computed;
        }

        public Boolean getPersisted() {
            return persisted;
        }

        public void setPersisted(Boolean persisted) {
            this.persisted = persisted;
        }

        public String getComputedDefinition() {
            return computedDefinition;
        }

        public void setComputedDefinition(String computedDefinition) {
            this.computedDefinition = computedDefinition;
        }

        public String getDefaultValue() {
            return defaultValue;
        }

        public void setDefaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
        }

        public String getDefaultName() {
            return defaultName;
        }

        public void setDefaultName(String defaultName) {
            this.defaultName = defaultName;
        }

        public List<Coordinate> getCoordinates() {
            return coordinates;
        }
    }

    public static final class BoundPackage {
        private final String id;
        private final String server;
        private final String database;
        private final String schema;
        private String lookupDatabase;
        private String lookupSchema;
        private final String name;
        private final List<BoundRoutine> procedures = new ArrayList<>();
        private final List<BoundObject> variables = new ArrayList<>();
        private final List<Coordinate> coordinates = new ArrayList<>();

        public BoundPackage(String id, String server, String database, String schema, String name) {
            this.id = id;
            this.server = server;
            this.database = database;
            this.schema = schema;
            this.name = name;
        }

        public void addCoordinates(List<Coordinate> coords) {
            if (coords != null) {
                for (Coordinate c : coords) {
                    if (c != null && !coordinates.contains(c)) {
                        coordinates.add(c);
                    }
                }
            }
        }

        public String getId() {
            return id;
        }

        public String getServer() {
            return server;
        }

        public String getDatabase() {
            return database;
        }

        public String getSchema() {
            return schema;
        }

        public String getLookupDatabase() {
            return lookupDatabase;
        }

        public void setLookupDatabase(String lookupDatabase) {
            this.lookupDatabase = lookupDatabase;
        }

        public String getLookupSchema() {
            return lookupSchema;
        }

        public void setLookupSchema(String lookupSchema) {
            this.lookupSchema = lookupSchema;
        }

        public String getName() {
            return name;
        }

        public List<BoundRoutine> getProcedures() {
            return procedures;
        }

        public List<BoundObject> getVariables() {
            return variables;
        }

        public List<Coordinate> getCoordinates() {
            return coordinates;
        }
    }

    public static final class BoundRoutine {
        private final String id;
        private final String server;
        private final String database;
        private final String schema;
        private String lookupDatabase;
        private String lookupSchema;
        private final String packageName;
        private final String name;
        private final String type;
        private final List<ArgumentObject> arguments = new ArrayList<>();
        private final List<Coordinate> coordinates = new ArrayList<>();
        private final Set<String> boundTables = new LinkedHashSet<>();
        private BoundObject pipelinedResultObject;

        public BoundRoutine(
                String id,
                String server,
                String database,
                String schema,
                String packageName,
                String name,
                String type) {
            this.id = id;
            this.server = server;
            this.database = database;
            this.schema = schema;
            this.packageName = packageName;
            this.name = name;
            this.type = type;
        }

        public void addCoordinates(List<Coordinate> coords) {
            if (coords != null) {
                for (Coordinate c : coords) {
                    if (c != null && !coordinates.contains(c)) {
                        coordinates.add(c);
                    }
                }
            }
        }

        public String getId() {
            return id;
        }

        public String getServer() {
            return server;
        }

        public String getDatabase() {
            return database;
        }

        public String getSchema() {
            return schema;
        }

        public String getLookupDatabase() {
            return lookupDatabase;
        }

        public void setLookupDatabase(String lookupDatabase) {
            this.lookupDatabase = lookupDatabase;
        }

        public String getLookupSchema() {
            return lookupSchema;
        }

        public void setLookupSchema(String lookupSchema) {
            this.lookupSchema = lookupSchema;
        }

        public String getPackageName() {
            return packageName;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        public List<ArgumentObject> getArguments() {
            return arguments;
        }

        public List<Coordinate> getCoordinates() {
            return coordinates;
        }

        public Set<String> getBoundTables() {
            return boundTables;
        }

        public BoundObject getPipelinedResultObject() {
            return pipelinedResultObject;
        }

        public void setPipelinedResultObject(BoundObject pipelinedResultObject) {
            this.pipelinedResultObject = pipelinedResultObject;
        }
    }

    public static final class BoundProcess {
        private final String id;
        private final String server;
        private final String database;
        private final String schema;
        private final String name;
        private final String type;
        private String procedureId;
        private String procedureName;
        private String queryHashId;
        private final List<Coordinate> coordinates = new ArrayList<>();
        private final List<Transform> transforms = new ArrayList<>();

        public BoundProcess(String id, String server, String database, String schema, String name, String type) {
            this.id = id;
            this.server = server;
            this.database = database;
            this.schema = schema;
            this.name = name;
            this.type = type;
        }

        public void addCoordinates(List<Coordinate> coords) {
            if (coords != null) {
                for (Coordinate c : coords) {
                    if (c != null && !coordinates.contains(c)) {
                        coordinates.add(c);
                    }
                }
            }
        }

        public String getId() {
            return id;
        }

        public String getServer() {
            return server;
        }

        public String getDatabase() {
            return database;
        }

        public String getSchema() {
            return schema;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        public String getProcedureId() {
            return procedureId;
        }

        public void setProcedureId(String procedureId) {
            this.procedureId = procedureId;
        }

        public String getProcedureName() {
            return procedureName;
        }

        public void setProcedureName(String procedureName) {
            this.procedureName = procedureName;
        }

        public String getQueryHashId() {
            return queryHashId;
        }

        public void setQueryHashId(String queryHashId) {
            this.queryHashId = queryHashId;
        }

        public List<Coordinate> getCoordinates() {
            return coordinates;
        }

        public List<Transform> getTransforms() {
            return transforms;
        }
    }

    public static final class BoundEdge {
        private final String id;
        private RelationshipType type;
        private EffectType effectType;
        private final BoundObject targetObj;
        private final BoundColumn targetCol;
        private final List<BoundSourceRef> sources;
        private String joinType;
        private String clause;
        private String condition;
        private String function;
        private String partition;
        private String processId;
        private String processType;
        private String procedureId;
        private String sqlHash;
        private String sqlComment;
        private BoundRoutine callerRoutine;
        private BoundObject callerObj;
        private List<RelationshipElement> callees;
        private Boolean builtIn;
        private String callStmt;
        private List<Coordinate> callCoordinate;

        public BoundEdge(
                String id,
                RelationshipType type,
                EffectType effectType,
                BoundObject targetObj,
                BoundColumn targetCol,
                List<BoundSourceRef> sources) {
            this.id = id;
            this.type = type;
            this.effectType = effectType;
            this.targetObj = targetObj;
            this.targetCol = targetCol;
            this.sources = new ArrayList<>(sources);
        }

        public String getId() {
            return id;
        }

        public RelationshipType getType() {
            return type;
        }

        public void setType(RelationshipType type) {
            this.type = type;
        }

        public EffectType getEffectType() {
            return effectType;
        }

        public void setEffectType(EffectType effectType) {
            this.effectType = effectType;
        }

        public BoundObject getTargetObj() {
            return targetObj;
        }

        public BoundColumn getTargetCol() {
            return targetCol;
        }

        public List<BoundSourceRef> getSources() {
            return sources;
        }

        public String getJoinType() {
            return joinType;
        }

        public void setJoinType(String joinType) {
            this.joinType = joinType;
        }

        public String getClause() {
            return clause;
        }

        public void setClause(String clause) {
            this.clause = clause;
        }

        public String getCondition() {
            return condition;
        }

        public void setCondition(String condition) {
            this.condition = condition;
        }

        public String getFunction() {
            return function;
        }

        public void setFunction(String function) {
            this.function = function;
        }

        public String getPartition() {
            return partition;
        }

        public void setPartition(String partition) {
            this.partition = partition;
        }

        public String getProcessId() {
            return processId;
        }

        public void setProcessId(String processId) {
            this.processId = processId;
        }

        public String getProcessType() {
            return processType;
        }

        public void setProcessType(String processType) {
            this.processType = processType;
        }

        public String getProcedureId() {
            return procedureId;
        }

        public void setProcedureId(String procedureId) {
            this.procedureId = procedureId;
        }

        public String getSqlHash() {
            return sqlHash;
        }

        public void setSqlHash(String sqlHash) {
            this.sqlHash = sqlHash;
        }

        public String getSqlComment() {
            return sqlComment;
        }

        public void setSqlComment(String sqlComment) {
            this.sqlComment = sqlComment;
        }

        public BoundRoutine getCallerRoutine() {
            return callerRoutine;
        }

        public void setCallerRoutine(BoundRoutine callerRoutine) {
            this.callerRoutine = callerRoutine;
        }

        public BoundObject getCallerObj() {
            return callerObj;
        }

        public void setCallerObj(BoundObject callerObj) {
            this.callerObj = callerObj;
        }

        public List<RelationshipElement> getCallees() {
            return callees;
        }

        public void setCallees(List<RelationshipElement> callees) {
            this.callees = callees;
        }

        public Boolean getBuiltIn() {
            return builtIn;
        }

        public void setBuiltIn(Boolean builtIn) {
            this.builtIn = builtIn;
        }

        public String getCallStmt() {
            return callStmt;
        }

        public void setCallStmt(String callStmt) {
            this.callStmt = callStmt;
        }

        public List<Coordinate> getCallCoordinate() {
            return callCoordinate;
        }

        public void setCallCoordinate(List<Coordinate> callCoordinate) {
            this.callCoordinate = callCoordinate;
        }
    }
}
