package io.sqlflow.lineage.analyzer;

import io.sqlflow.contracts.lineage.Column;
import io.sqlflow.contracts.lineage.CommentBindingBundle;
import io.sqlflow.contracts.lineage.Coordinate;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.DatabaseObject;
import io.sqlflow.contracts.lineage.DbLink;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.ErrorItem;
import io.sqlflow.contracts.lineage.JoinRelationship;
import io.sqlflow.contracts.lineage.Option;
import io.sqlflow.contracts.lineage.Orientation;
import io.sqlflow.contracts.lineage.PackageObject;
import io.sqlflow.contracts.lineage.ProcedureObject;
import io.sqlflow.contracts.lineage.ProcessNode;
import io.sqlflow.contracts.lineage.Relationship;
import io.sqlflow.contracts.lineage.RelationshipElement;
import io.sqlflow.contracts.lineage.RelationshipType;
import io.sqlflow.contracts.lineage.SchemaObject;
import io.sqlflow.contracts.lineage.SequenceObject;
import io.sqlflow.contracts.lineage.ServerObject;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.contracts.lineage.SynonymObject;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.contracts.vendor.IdentifierProfile;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundColumn;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundEdge;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundObject;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundPackage;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundProcess;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundRoutine;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundSourceRef;
import io.sqlflow.lineage.analyzer.ModelBindingManager.ObjectCategory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Serializes {@link ModelBindingManager} into {@link DataFlow} and text lineage ({@code 03 §1.4–§1.5, OBL-044}).
 */
public final class DataFlowSerializer {

    private DataFlowSerializer() {
    }

    public static DataFlow toDataFlow(
            ModelBindingManager mgr,
            EDbVendor vendor,
            Option option,
            CommentBindingBundle commentBindings) {
        Option opt = option != null ? option : mgr.option();
        EDbVendor effVendor = vendor != null ? vendor : mgr.sqlEnv().vendor();
        IdentifierProfile profile = mgr.sqlEnv().profile();
        boolean ignoreCoord = opt.isIgnoreCoordinate();
        Set<String> relRowObjIds = DlineageXmlBuilder.collectRelationRowObjectIds(mgr.edges());

        DataFlow df = new DataFlow();
        df.setDbvendor(effVendor.wireName());
        df.setOrientation(Orientation.LEFT_TO_RIGHT);

        boolean supCat = profile.metadataSupportsCatalogs();
        boolean supSch = profile.metadataSupportsSchemas();
        String defaultSrv = nonBlank(opt.getDefaultServer(), "DEFAULT_SERVER");
        String defaultDb = nonBlank(opt.getDefaultDatabase(), "DEFAULT");
        String defaultSch = nonBlank(opt.getDefaultSchema(), "DEFAULT");

        Map<String, Bucket> buckets = new LinkedHashMap<>();
        Set<String> dbLinkNames = new HashSet<>();
        List<DbLink> dbLinks = new ArrayList<>();
        Set<String> packagedVarIds = new HashSet<>();

        for (BoundPackage pkg : mgr.packages()) {
            Bucket b = resolveBucket(buckets, pkg.getDatabase(), pkg.getSchema(), supCat, supSch, defaultDb, defaultSch);
            PackageObject po = new PackageObject(pkg.getId(), pkg.getName());
            po.setCoordinates(coordsOrNull(pkg.getCoordinates(), ignoreCoord));
            List<ProcedureObject> procs = new ArrayList<>();
            List<ProcedureObject> funcs = new ArrayList<>();
            List<ProcedureObject> trigs = new ArrayList<>();
            for (BoundRoutine r : pkg.getProcedures()) {
                routeRoutine(toProcedureObject(r, ignoreCoord), r.getType(), procs, funcs, trigs);
            }
            if (!procs.isEmpty()) po.setProcedures(procs);
            if (!funcs.isEmpty()) po.setFunctions(funcs);
            if (!trigs.isEmpty()) po.setTriggers(trigs);
            if (!pkg.getVariables().isEmpty()) {
                List<DbObject> vars = new ArrayList<>(pkg.getVariables().size());
                for (BoundObject v : pkg.getVariables()) {
                    packagedVarIds.add(v.getId());
                    vars.add(toDbObject(v, relRowObjIds.contains(v.getId()), ignoreCoord));
                }
                po.setVariables(vars);
            }
            b.packages.add(po);
        }

        for (BoundRoutine r : mgr.routines()) {
            if (r.getPackageName() != null && !r.getPackageName().isBlank()) {
                continue;
            }
            Bucket b = resolveBucket(buckets, r.getDatabase(), r.getSchema(), supCat, supSch, defaultDb, defaultSch);
            routeRoutine(toProcedureObject(r, ignoreCoord), r.getType(), b.procedures, b.functions, b.triggers);
        }

        for (BoundObject obj : mgr.allObjects()) {
            if (packagedVarIds.contains(obj.getId())) {
                continue;
            }
            if (obj.getDbLink() != null && !obj.getDbLink().isBlank() && dbLinkNames.add(obj.getDbLink())) {
                dbLinks.add(new DbLink(obj.getSchema(), obj.getDbLink(), null, null));
            }
            Bucket b = resolveBucket(buckets, obj.getDatabase(), obj.getSchema(), supCat, supSch, defaultDb, defaultSch);
            if (obj.getCategory() == ObjectCategory.SYNONYM) {
                SynonymObject syn = new SynonymObject();
                syn.setId(obj.getId());
                syn.setDatabase(obj.getDatabase());
                syn.setSchema(obj.getSchema());
                syn.setName(obj.getName());
                if (obj.getSynonymTarget() != null) {
                    syn.setSourceId(obj.getSynonymTarget().getId());
                    syn.setSourceName(obj.getSynonymTarget().getName());
                    syn.setSourceSchema(obj.getSynonymTarget().getSchema());
                    syn.setSourceDatabase(obj.getSynonymTarget().getDatabase());
                    syn.setSourceDbLinkName(obj.getSynonymTarget().getDbLink());
                }
                syn.setCoordinates(coordsOrNull(obj.getCoordinates(), ignoreCoord));
                b.synonyms.add(syn);
            } else if (obj.getCategory() == ObjectCategory.SEQUENCE) {
                SequenceObject seq = new SequenceObject();
                seq.setId(obj.getId());
                seq.setName(obj.getName());
                seq.setCoordinates(coordsOrNull(obj.getCoordinates(), ignoreCoord));
                b.sequences.add(seq);
            } else {
                DbObject dbo = toDbObject(obj, relRowObjIds.contains(obj.getId()), ignoreCoord);
                if (obj.getCategory() == ObjectCategory.VIEW) {
                    dbo.setIsView("true");
                    b.views.add(dbo);
                } else if (obj.getCategory() == ObjectCategory.TABLE && !"constant".equalsIgnoreCase(obj.getType())) {
                    b.tables.add(dbo);
                } else {
                    b.others.add(dbo);
                }
            }
        }

        List<ProcessNode> topProcesses = new ArrayList<>();
        for (BoundProcess bp : mgr.processes()) {
            ProcessNode pn = new ProcessNode(bp.getId(), bp.getName(), bp.getType());
            pn.setProcedureId(bp.getProcedureId());
            pn.setProcedureName(bp.getProcedureName());
            pn.setQueryHashId(bp.getQueryHashId());
            pn.setCoordinates(coordsOrNull(bp.getCoordinates(), ignoreCoord));
            if (!bp.getTransforms().isEmpty()) {
                pn.setTransforms(new ArrayList<>(bp.getTransforms()));
            }
            topProcesses.add(pn);
            Bucket b = resolveBucket(buckets, bp.getDatabase(), bp.getSchema(), supCat, supSch, defaultDb, defaultSch);
            b.processes.add(pn);
        }
        if (!topProcesses.isEmpty()) {
            df.setProcesses(topProcesses);
        }

        df.setDbobjs(buildCatalog(effVendor, defaultSrv, supCat, supSch, buckets, dbLinks));

        List<Relationship> rels = new ArrayList<>(mgr.edges().size());
        for (BoundEdge e : mgr.edges()) {
            rels.add(toRelationship(e, ignoreCoord));
        }
        df.setRelationships(rels);

        if (!mgr.errors().isEmpty()) {
            List<ErrorItem> errs = new ArrayList<>(mgr.errors().size());
            for (ErrorItem err : mgr.errors()) {
                errs.add(new ErrorItem(
                        err.getErrorType(),
                        err.getErrorMessage(),
                        coordsOrNull(err.getCoordinates(), ignoreCoord)));
            }
            df.setErrors(errs);
        }
        if (commentBindings != null) {
            df.setCommentBindings(commentBindings);
        }
        return df;
    }

    public static String formatTextLineage(DataFlow df) {
        if (df == null || df.getRelationships() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Relationship r : df.getRelationships()) {
            if (r.getTarget() == null || r.getSources() == null) {
                continue;
            }
            String tgt = formatElem(r.getTarget());
            for (RelationshipElement s : r.getSources()) {
                sb.append(formatElem(s)).append(" -> ").append(tgt)
                        .append(" [").append(r.getType()).append("]\n");
            }
        }
        return sb.toString();
    }

    private static String formatElem(RelationshipElement e) {
        if (e.getParentName() != null && e.getColumn() != null) {
            return e.getParentName() + "." + e.getColumn();
        }
        return e.getName() != null ? e.getName() : (e.getColumn() != null ? e.getColumn() : "");
    }

    private static SqlflowCatalog buildCatalog(
            EDbVendor vendor,
            String serverName,
            boolean supCat,
            boolean supSch,
            Map<String, Bucket> buckets,
            List<DbLink> dbLinks) {
        SqlflowCatalog catalog = new SqlflowCatalog();
        catalog.setDialect(vendor.wireName());
        ServerObject srv = new ServerObject(serverName, vendor.wireName(), supCat, supSch);
        if (!dbLinks.isEmpty()) {
            srv.setDbLinks(dbLinks);
            catalog.setDbLinks(dbLinks);
        }
        if (!supCat && supSch) {
            List<SchemaObject> schemas = new ArrayList<>();
            for (Bucket b : buckets.values()) {
                schemas.add(b.toSchemaObject());
            }
            if (!schemas.isEmpty()) {
                srv.setSchemas(schemas);
            }
        } else if (supCat && !supSch) {
            List<DatabaseObject> dbs = new ArrayList<>();
            for (Bucket b : buckets.values()) {
                dbs.add(b.toDatabaseObjectDirect());
            }
            if (!dbs.isEmpty()) {
                srv.setDatabases(dbs);
            }
        } else {
            Map<String, List<SchemaObject>> byDb = new LinkedHashMap<>();
            for (Bucket b : buckets.values()) {
                byDb.computeIfAbsent(b.dbName, k -> new ArrayList<>()).add(b.toSchemaObject());
            }
            List<DatabaseObject> dbs = new ArrayList<>();
            for (Map.Entry<String, List<SchemaObject>> entry : byDb.entrySet()) {
                DatabaseObject dbo = new DatabaseObject(entry.getKey());
                dbo.setSchemas(entry.getValue());
                dbs.add(dbo);
            }
            if (!dbs.isEmpty()) {
                srv.setDatabases(dbs);
            }
        }
        catalog.setServers(List.of(srv));
        return catalog;
    }

    private static DbObject toDbObject(BoundObject obj, boolean includeRelRows, boolean ignoreCoord) {
        DbObject dbo = new DbObject(obj.getId(), obj.getName(), obj.getType());
        dbo.setServer(obj.getServer());
        dbo.setDatabase(obj.getDatabase());
        dbo.setSchema(obj.getSchema());
        dbo.setDisplayName(obj.getDisplayName());
        dbo.setAlias(obj.getAlias());
        dbo.setUri(obj.getUri());
        dbo.setSubType(obj.getSubType());
        dbo.setFileType(obj.getFileType());
        dbo.setFileFormat(obj.getFileFormat());
        dbo.setLocation(obj.getLocation());
        dbo.setDataSourceName(obj.getDataSourceName());
        dbo.setNamespace(obj.getNamespace());
        dbo.setDbLink(obj.getDbLink());
        if (obj.isTarget()) {
            dbo.setIsTarget("true");
        }
        dbo.setFromDDL(obj.getFromDDL());
        dbo.setCreatedInSql(obj.getCreatedInSql());
        if (obj.getEndpointKind() != null) {
            dbo.setEndpointKind(obj.getEndpointKind().name());
        }
        if (obj.getEndpointIntroduction() != null) {
            dbo.setEndpointIntroduction(obj.getEndpointIntroduction().name());
        }
        dbo.setNormalizedName(obj.getNormalizedName());
        if (Boolean.TRUE.equals(obj.getTemplatedParts())) {
            dbo.setTemplatedParts("true");
        }
        dbo.setProcedureId(obj.getProcedureId());
        dbo.setQueryHashId(obj.getQueryHashId());
        dbo.setProcessIds(obj.formattedProcessIds());
        dbo.setCoordinates(coordsOrNull(obj.getCoordinates(), ignoreCoord));
        if (!obj.getCandidateTables().isEmpty()) {
            dbo.setCandidateTables(new ArrayList<>(obj.getCandidateTables()));
        }
        List<BoundColumn> cols = obj.orderedColumnsIncludingRelationRows(includeRelRows);
        if (!cols.isEmpty()) {
            List<Column> dtoCols = new ArrayList<>(cols.size());
            for (BoundColumn bc : cols) {
                Column c = new Column(bc.getId(), bc.getName(), bc.getDataType());
                c.setDisplayName(bc.getDisplayName());
                c.setComment(bc.getComment());
                c.setCoordinates(coordsOrNull(bc.getCoordinates(), ignoreCoord));
                c.setSource(bc.getSource());
                c.setPrimaryKey(bc.getPrimaryKey());
                c.setUnqiueKey(bc.getUnqiueKey());
                c.setIndexKey(bc.getIndexKey());
                c.setForeignKey(bc.getForeignKey());
                c.setNullable(bc.getNullable());
                c.setIdentity(bc.getIdentity());
                c.setComputed(bc.getComputed());
                c.setPersisted(bc.getPersisted());
                c.setComputedDefinition(bc.getComputedDefinition());
                c.setDefaultValue(bc.getDefaultValue());
                c.setDefaultName(bc.getDefaultName());
                c.setQualifiedTable(bc.getQualifiedTable());
                c.setIsFunction(bc.getIsFunction());
                c.setStar(bc.getStar());
                dtoCols.add(c);
            }
            dbo.setColumns(dtoCols);
        }
        return dbo;
    }

    private static Relationship toRelationship(BoundEdge e, boolean ignoreCoord) {
        Relationship r = e.getType() == RelationshipType.join
                ? new JoinRelationship(e.getId(), e.getJoinType(), e.getClause(), e.getCondition())
                : new Relationship(e.getId(), e.getType() != null ? e.getType() : RelationshipType.fdd);
        if (e.getType() != RelationshipType.join) {
            r.setJoinType(e.getJoinType());
            r.setClause(e.getClause());
            r.setCondition(e.getCondition());
        }
        if (e.getEffectType() != null) {
            r.setEffectType(e.getEffectType().wireName());
        }
        r.setProcessId(e.getProcessId());
        r.setProcessType(e.getProcessType());
        r.setProcedureId(e.getProcedureId());
        r.setFunction(e.getFunction());
        r.setPartition(e.getPartition());
        r.setSqlHash(e.getSqlHash());
        r.setSqlComment(e.getSqlComment());

        if (e.getType() == RelationshipType.call) {
            r.setBuiltIn(e.getBuiltIn());
            r.setCallStmt(e.getCallStmt());
            r.setCallCoordinate(coordsOrNull(e.getCallCoordinate(), ignoreCoord));
            RelationshipElement caller = new RelationshipElement();
            if (e.getCallerRoutine() != null) {
                caller.setId(e.getCallerRoutine().getId());
                caller.setName(e.getCallerRoutine().getName());
                caller.setType(e.getCallerRoutine().getType());
                caller.setCoordinates(coordsOrNull(e.getCallerRoutine().getCoordinates(), ignoreCoord));
            } else if (e.getCallerObj() != null) {
                caller.setId(e.getCallerObj().getId());
                caller.setName(e.getCallerObj().getDisplayName());
                caller.setType(e.getCallerObj().getType());
            } else {
                caller.setId("0");
                caller.setName("SQL_SCRIPT");
                caller.setType("script");
            }
            r.setCaller(caller);
            if (e.getCallees() != null && !e.getCallees().isEmpty()) {
                r.setCallees(new ArrayList<>(e.getCallees()));
            }
            return r;
        }

        if (e.getTargetCol() != null && e.getTargetObj() != null) {
            RelationshipElement tgt = new RelationshipElement(
                    e.getTargetCol().getId(),
                    e.getTargetCol().getName(),
                    e.getTargetObj().getId(),
                    e.getTargetObj().getDisplayName());
            tgt.setParentAlias(e.getTargetObj().getAlias());
            tgt.setCoordinates(coordsOrNull(e.getTargetCol().getCoordinates(), ignoreCoord));
            tgt.setFunction(e.getFunction());
            r.setTarget(tgt);
        }
        if (!e.getSources().isEmpty()) {
            List<RelationshipElement> srcs = new ArrayList<>(e.getSources().size());
            for (BoundSourceRef s : e.getSources()) {
                RelationshipElement se = new RelationshipElement(
                        s.col().getId(),
                        s.col().getName(),
                        s.obj().getId(),
                        s.obj().getDisplayName());
                se.setParentAlias(s.alias() != null ? s.alias() : s.obj().getAlias());
                se.setClauseType(s.clauseType());
                se.setCoordinates(coordsOrNull(
                        s.coordinates() != null && !s.coordinates().isEmpty() ? s.coordinates() : s.col().getCoordinates(),
                        ignoreCoord));
                if (s.transforms() != null && !s.transforms().isEmpty()) {
                    se.setTransforms(new ArrayList<>(s.transforms()));
                }
                if (s.candidateParents() != null && !s.candidateParents().isEmpty()) {
                    se.setCandidateParents(new ArrayList<>(s.candidateParents()));
                }
                se.setFunction(s.function());
                se.setValue(s.value());
                se.setSource(s.col().getSource());
                srcs.add(se);
            }
            r.setSources(srcs);
        }
        return r;
    }

    private static ProcedureObject toProcedureObject(BoundRoutine r, boolean ignoreCoord) {
        ProcedureObject po = new ProcedureObject(r.getId(), r.getName(), r.getType());
        if (!r.getArguments().isEmpty()) {
            po.setArguments(new ArrayList<>(r.getArguments()));
        }
        po.setCoordinates(coordsOrNull(r.getCoordinates(), ignoreCoord));
        return po;
    }

    private static void routeRoutine(
            ProcedureObject po,
            String type,
            List<ProcedureObject> procs,
            List<ProcedureObject> funcs,
            List<ProcedureObject> trigs) {
        String t = type != null ? type.toLowerCase(Locale.ROOT) : "procedure";
        if (t.contains("function")) {
            funcs.add(po);
        } else if (t.contains("trigger")) {
            trigs.add(po);
        } else {
            procs.add(po);
        }
    }

    private static Bucket resolveBucket(
            Map<String, Bucket> buckets,
            String rawDb,
            String rawSch,
            boolean supCat,
            boolean supSch,
            String defDb,
            String defSch) {
        String db = supCat ? nonBlank(rawDb, defDb) : defDb;
        String sch = supSch ? nonBlank(rawSch, defSch) : defSch;
        String key = db + "|" + sch;
        return buckets.computeIfAbsent(key, k -> new Bucket(db, sch));
    }

    private static List<Coordinate> coordsOrNull(List<Coordinate> coords, boolean ignoreCoord) {
        return ignoreCoord || coords == null || coords.isEmpty() ? null : new ArrayList<>(coords);
    }

    private static String nonBlank(String v, String fallback) {
        return v != null && !v.isBlank() ? v : fallback;
    }

    private static final class Bucket {
        final String dbName;
        final String schName;
        final List<DbObject> tables = new ArrayList<>();
        final List<DbObject> views = new ArrayList<>();
        final List<PackageObject> packages = new ArrayList<>();
        final List<ProcedureObject> procedures = new ArrayList<>();
        final List<ProcedureObject> functions = new ArrayList<>();
        final List<ProcedureObject> triggers = new ArrayList<>();
        final List<SynonymObject> synonyms = new ArrayList<>();
        final List<SequenceObject> sequences = new ArrayList<>();
        final List<DbObject> others = new ArrayList<>();
        final List<ProcessNode> processes = new ArrayList<>();

        Bucket(String dbName, String schName) {
            this.dbName = dbName;
            this.schName = schName;
        }

        SchemaObject toSchemaObject() {
            SchemaObject so = new SchemaObject(schName);
            if (!tables.isEmpty()) so.setTables(tables);
            if (!views.isEmpty()) so.setViews(views);
            if (!packages.isEmpty()) so.setPackages(packages);
            if (!procedures.isEmpty()) so.setProcedures(procedures);
            if (!functions.isEmpty()) so.setFunctions(functions);
            if (!triggers.isEmpty()) so.setTriggers(triggers);
            if (!synonyms.isEmpty()) so.setSynonyms(synonyms);
            if (!sequences.isEmpty()) so.setSequences(sequences);
            if (!others.isEmpty()) so.setOthers(others);
            if (!processes.isEmpty()) so.setProcesses(processes);
            return so;
        }

        DatabaseObject toDatabaseObjectDirect() {
            DatabaseObject dbo = new DatabaseObject(dbName);
            if (!tables.isEmpty()) dbo.setTables(tables);
            if (!views.isEmpty()) dbo.setViews(views);
            if (!packages.isEmpty()) dbo.setPackages(packages);
            if (!procedures.isEmpty()) dbo.setProcedures(procedures);
            if (!functions.isEmpty()) dbo.setFunctions(functions);
            if (!triggers.isEmpty()) dbo.setTriggers(triggers);
            if (!synonyms.isEmpty()) dbo.setSynonyms(synonyms);
            if (!sequences.isEmpty()) dbo.setSequences(sequences);
            if (!others.isEmpty()) dbo.setOthers(others);
            if (!processes.isEmpty()) dbo.setProcesses(processes);
            return dbo;
        }
    }
}
