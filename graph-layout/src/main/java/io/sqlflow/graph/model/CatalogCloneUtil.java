package io.sqlflow.graph.model;

import io.sqlflow.contracts.graph.SummaryInfo;
import io.sqlflow.contracts.lineage.ArgumentObject;
import io.sqlflow.contracts.lineage.CandidateTable;
import io.sqlflow.contracts.lineage.Column;
import io.sqlflow.contracts.lineage.Coordinate;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.DatabaseObject;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.JoinRelationship;
import io.sqlflow.contracts.lineage.PackageObject;
import io.sqlflow.contracts.lineage.ProcedureObject;
import io.sqlflow.contracts.lineage.ProcessNode;
import io.sqlflow.contracts.lineage.Relationship;
import io.sqlflow.contracts.lineage.RelationshipElement;
import io.sqlflow.contracts.lineage.SchemaObject;
import io.sqlflow.contracts.lineage.ServerObject;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.contracts.lineage.Transform;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds pruned, non-mutating {@link DataFlow} subgraphs and computes {@link SummaryInfo}
 * statistics for sliced or outgoing-closure subgraphs.
 */
public final class CatalogCloneUtil {

    private CatalogCloneUtil() {
    }

    public static DataFlow buildSubDataFlow(
            DataFlowIndex index,
            Set<String> retainedNodeIds,
            Set<String> retainedColumnIds,
            List<Relationship> retainedRelationships) {
        DataFlow source = index.getDataFlow();
        DataFlow out = new DataFlow();
        if (source != null) {
            out.setDbvendor(source.getDbvendor());
            out.setOrientation(source.getOrientation());
            out.setErrors(source.getErrors());
            out.setCommentBindings(source.getCommentBindings());
        }

        SqlflowCatalog outCatalog = buildPrunedCatalog(index, retainedNodeIds, retainedColumnIds);
        out.setDbobjs(outCatalog);

        if (source != null && source.getProcesses() != null) {
            Set<String> processIds = new LinkedHashSet<>();
            for (Relationship rel : retainedRelationships) {
                if (rel.getProcessId() != null) {
                    processIds.add(rel.getProcessId());
                }
            }
            List<ProcessNode> keptProcesses = new ArrayList<>();
            for (ProcessNode pn : source.getProcesses()) {
                if (pn != null && (retainedNodeIds.contains(pn.getId()) || processIds.contains(pn.getId()))) {
                    keptProcesses.add(copyProcessNode(pn));
                }
            }
            if (!keptProcesses.isEmpty()) {
                out.setProcesses(keptProcesses);
            }
        }

        out.setRelationships(retainedRelationships);
        out.setSummary(computeSummary(outCatalog, retainedRelationships));
        return out;
    }

    private static SqlflowCatalog buildPrunedCatalog(
            DataFlowIndex index,
            Set<String> retainedNodeIds,
            Set<String> retainedColumnIds) {
        DataFlow sourceDf = index.getDataFlow();
        SqlflowCatalog orig = sourceDf != null ? sourceDf.getDbobjs() : null;
        SqlflowCatalog cat = new SqlflowCatalog();
        if (orig != null) {
            cat.setCreatedBy(orig.getCreatedBy());
            cat.setCreateTime(orig.getCreateTime());
            cat.setCreatedAt(orig.getCreatedAt());
            cat.setExportTime(orig.getExportTime());
            cat.setDialect(orig.getDialect());
            cat.setPhysicalInstance(orig.getPhysicalInstance());
            cat.setDbLinks(orig.getDbLinks());
        }

        // Group retained NodeEntries by (server, database, schema)
        record ScopeKey(String server, String database, String schema) {
        }
        Map<ScopeKey, List<DataFlowIndex.NodeEntry>> grouped = new LinkedHashMap<>();
        Set<String> retainedPackages = new LinkedHashSet<>();

        for (String nodeId : retainedNodeIds) {
            DataFlowIndex.NodeEntry node = index.getNode(nodeId);
            if (node == null) {
                continue;
            }
            if (node.getParentPackageId() != null) {
                retainedPackages.add(node.getParentPackageId());
            }
            String srv = node.getServer() != null && !node.getServer().isBlank()
                    ? node.getServer()
                    : "DEFAULT_SERVER";
            String db = node.getDatabase() != null && !node.getDatabase().isBlank()
                    ? node.getDatabase()
                    : "DEFAULT";
            String sch = node.getSchema() != null && !node.getSchema().isBlank()
                    ? node.getSchema()
                    : "DEFAULT";
            grouped.computeIfAbsent(new ScopeKey(srv, db, sch), k -> new ArrayList<>()).add(node);
        }

        Map<String, Map<String, Map<String, SchemaObject>>> tree = new LinkedHashMap<>();
        for (Map.Entry<ScopeKey, List<DataFlowIndex.NodeEntry>> e : grouped.entrySet()) {
            ScopeKey sk = e.getKey();
            SchemaObject schemaObj = tree
                    .computeIfAbsent(sk.server(), s -> new LinkedHashMap<>())
                    .computeIfAbsent(sk.database(), d -> new LinkedHashMap<>())
                    .computeIfAbsent(sk.schema(), SchemaObject::new);

            List<DbObject> tables = new ArrayList<>();
            List<DbObject> views = new ArrayList<>();
            List<DbObject> others = new ArrayList<>();
            List<ProcedureObject> procedures = new ArrayList<>();
            List<ProcedureObject> functions = new ArrayList<>();
            List<ProcedureObject> triggers = new ArrayList<>();
            List<ProcessNode> processes = new ArrayList<>();

            for (DataFlowIndex.NodeEntry node : e.getValue()) {
                if (node.getParentPackageId() != null) {
                    continue; // Placed inside PackageObject below
                }
                String t = node.getType().toLowerCase(Locale.ROOT);
                if ("process".equals(t) && node.getRawProcess() != null) {
                    processes.add(copyProcessNode(node.getRawProcess()));
                } else if ("procedure".equals(t) && node.getRawProcedure() != null) {
                    procedures.add(copyProcedureObject(node.getRawProcedure()));
                } else if ("function".equals(t) && node.getRawProcedure() != null) {
                    functions.add(copyProcedureObject(node.getRawProcedure()));
                } else if ("trigger".equals(t) && node.getRawProcedure() != null) {
                    triggers.add(copyProcedureObject(node.getRawProcedure()));
                } else {
                    DbObject copied = toDbObject(node, retainedColumnIds);
                    if ("table".equals(t)) {
                        tables.add(copied);
                    } else if ("view".equals(t) || "materialized view".equals(t)) {
                        views.add(copied);
                    } else {
                        others.add(copied);
                    }
                }
            }

            if (!tables.isEmpty()) {
                schemaObj.setTables(tables);
            }
            if (!views.isEmpty()) {
                schemaObj.setViews(views);
            }
            if (!others.isEmpty()) {
                schemaObj.setOthers(others);
            }
            if (!procedures.isEmpty()) {
                schemaObj.setProcedures(procedures);
            }
            if (!functions.isEmpty()) {
                schemaObj.setFunctions(functions);
            }
            if (!triggers.isEmpty()) {
                schemaObj.setTriggers(triggers);
            }
            if (!processes.isEmpty()) {
                schemaObj.setProcesses(processes);
            }
        }

        // Attach retained PackageObjects
        for (String pkgId : retainedPackages) {
            DataFlowIndex.PackageEntry pkgEntry = index.getPackage(pkgId);
            if (pkgEntry == null) {
                continue;
            }
            String srv = pkgEntry.getServer() != null && !pkgEntry.getServer().isBlank()
                    ? pkgEntry.getServer()
                    : "DEFAULT_SERVER";
            String db = pkgEntry.getDatabase() != null && !pkgEntry.getDatabase().isBlank()
                    ? pkgEntry.getDatabase()
                    : "DEFAULT";
            String sch = pkgEntry.getSchema() != null && !pkgEntry.getSchema().isBlank()
                    ? pkgEntry.getSchema()
                    : "DEFAULT";
            SchemaObject schemaObj = tree
                    .computeIfAbsent(srv, s -> new LinkedHashMap<>())
                    .computeIfAbsent(db, d -> new LinkedHashMap<>())
                    .computeIfAbsent(sch, SchemaObject::new);
            PackageObject outPkg = new PackageObject(pkgEntry.getId(), pkgEntry.getName());
            outPkg.setType(pkgEntry.getType());
            outPkg.setCoordinates(pkgEntry.getCoordinates());
            List<ProcedureObject> pkgProcs = new ArrayList<>();
            List<ProcedureObject> pkgFuncs = new ArrayList<>();
            List<ProcedureObject> pkgTrigs = new ArrayList<>();
            List<DbObject> pkgVars = new ArrayList<>();
            for (String childId : pkgEntry.getChildNodeIds()) {
                if (!retainedNodeIds.contains(childId)) {
                    continue;
                }
                DataFlowIndex.NodeEntry child = index.getNode(childId);
                if (child == null) {
                    continue;
                }
                String ct = child.getType().toLowerCase(Locale.ROOT);
                if ("function".equals(ct) && child.getRawProcedure() != null) {
                    pkgFuncs.add(copyProcedureObject(child.getRawProcedure()));
                } else if ("trigger".equals(ct) && child.getRawProcedure() != null) {
                    pkgTrigs.add(copyProcedureObject(child.getRawProcedure()));
                } else if (child.getRawProcedure() != null) {
                    pkgProcs.add(copyProcedureObject(child.getRawProcedure()));
                } else {
                    pkgVars.add(toDbObject(child, retainedColumnIds));
                }
            }
            if (!pkgProcs.isEmpty()) {
                outPkg.setProcedures(pkgProcs);
            }
            if (!pkgFuncs.isEmpty()) {
                outPkg.setFunctions(pkgFuncs);
            }
            if (!pkgTrigs.isEmpty()) {
                outPkg.setTriggers(pkgTrigs);
            }
            if (!pkgVars.isEmpty()) {
                outPkg.setVariables(pkgVars);
            }
            List<PackageObject> existing = schemaObj.getPackages() != null
                    ? new ArrayList<>(schemaObj.getPackages())
                    : new ArrayList<>();
            existing.add(outPkg);
            schemaObj.setPackages(existing);
        }

        List<ServerObject> servers = new ArrayList<>();
        for (Map.Entry<String, Map<String, Map<String, SchemaObject>>> srvEntry : tree.entrySet()) {
            ServerObject srvObj = new ServerObject();
            srvObj.setName(srvEntry.getKey());
            srvObj.setSupportsCatalogs(true);
            srvObj.setSupportsSchemas(true);
            List<DatabaseObject> dbs = new ArrayList<>();
            for (Map.Entry<String, Map<String, SchemaObject>> dbEntry : srvEntry.getValue().entrySet()) {
                DatabaseObject dbObj = new DatabaseObject(dbEntry.getKey());
                dbObj.setSchemas(new ArrayList<>(dbEntry.getValue().values()));
                dbs.add(dbObj);
            }
            srvObj.setDatabases(dbs);
            servers.add(srvObj);
        }
        cat.setServers(servers);
        return cat;
    }

    private static DbObject toDbObject(DataFlowIndex.NodeEntry node, Set<String> retainedColumnIds) {
        DbObject src = node.getRawDbObject();
        DbObject out = new DbObject();
        out.setId(node.getId());
        out.setName(node.getName());
        out.setDisplayName(node.getDisplayName());
        out.setAlias(node.getAlias());
        out.setType(node.getType());
        out.setSubType(node.getSubType());
        out.setServer(node.getServer());
        out.setDatabase(node.getDatabase());
        out.setSchema(node.getSchema());
        if (!node.getCoordinates().isEmpty()) {
            out.setCoordinates(new ArrayList<>(node.getCoordinates()));
        }
        if (src != null) {
            out.setUri(src.getUri());
            out.setDbLink(src.getDbLink());
            out.setIsView(src.getIsView());
            out.setIsTarget(src.getIsTarget());
            out.setFromDDL(src.getFromDDL());
            out.setCreatedInSql(src.getCreatedInSql());
            out.setEndpointKind(src.getEndpointKind());
            out.setEndpointIntroduction(src.getEndpointIntroduction());
        }
        List<Column> cols = new ArrayList<>();
        for (DataFlowIndex.ColumnEntry ce : node.getColumns()) {
            if (retainedColumnIds == null || retainedColumnIds.isEmpty() || retainedColumnIds.contains(ce.getId())) {
                cols.add(copyColumn(ce.getRawColumn()));
            }
        }
        // If column-level filtering left 0 columns on a retained table, retain all non-system columns
        if (cols.isEmpty() && !node.getColumns().isEmpty()
                && (retainedColumnIds == null || retainedColumnIds.stream()
                        .noneMatch(cid -> node.getColumns().stream().anyMatch(c -> c.getId().equals(cid))))) {
            for (DataFlowIndex.ColumnEntry ce : node.getColumns()) {
                if (!ce.isSystemRelationRows()) {
                    cols.add(copyColumn(ce.getRawColumn()));
                }
            }
        }
        if (!cols.isEmpty()) {
            out.setColumns(cols);
        }
        return out;
    }

    public static Column copyColumn(Column src) {
        Column c = new Column();
        if (src == null) {
            return c;
        }
        c.setId(src.getId());
        c.setName(src.getName());
        c.setDisplayName(src.getDisplayName());
        c.setDataType(src.getDataType());
        c.setComment(src.getComment());
        c.setCoordinates(src.getCoordinates() != null ? new ArrayList<>(src.getCoordinates()) : null);
        c.setSource(src.getSource());
        c.setUiVisible(src.getUiVisible());
        c.setPrimaryKey(src.getPrimaryKey());
        c.setUnqiueKey(src.getUnqiueKey());
        c.setIndexKey(src.getIndexKey());
        c.setForeignKey(src.getForeignKey());
        c.setNullable(src.getNullable());
        c.setIdentity(src.getIdentity());
        c.setComputed(src.getComputed());
        c.setPersisted(src.getPersisted());
        c.setComputedDefinition(src.getComputedDefinition());
        c.setDefaultValue(src.getDefaultValue());
        c.setDefaultName(src.getDefaultName());
        c.setQualifiedTable(src.getQualifiedTable());
        c.setIsFunction(src.getIsFunction());
        c.setStar(src.getStar());
        return c;
    }

    public static Relationship copyRelationship(Relationship src, Integer distanceOverride) {
        Relationship r = "join".equalsIgnoreCase(src.getType()) ? new JoinRelationship() : new Relationship();
        r.setId(src.getId());
        r.setType(src.getType());
        r.setJoinType(src.getJoinType());
        r.setClause(src.getClause());
        r.setCondition(src.getCondition());
        r.setEffectType(src.getEffectType());
        r.setTarget(copyElement(src.getTarget()));
        if (src.getSources() != null) {
            List<RelationshipElement> copiedSources = new ArrayList<>(src.getSources().size());
            for (RelationshipElement el : src.getSources()) {
                copiedSources.add(copyElement(el));
            }
            r.setSources(copiedSources);
        }
        r.setProcessId(src.getProcessId());
        r.setProcessType(src.getProcessType());
        r.setProcedureId(src.getProcedureId());
        r.setFunction(src.getFunction());
        r.setPartition(src.getPartition());
        r.setSqlHash(src.getSqlHash());
        r.setSqlComment(src.getSqlComment());
        r.setTimestampMin(src.getTimestampMin());
        r.setTimestampMax(src.getTimestampMax());
        r.setCaller(copyElement(src.getCaller()));
        if (src.getCallees() != null) {
            List<RelationshipElement> copiedCallees = new ArrayList<>(src.getCallees().size());
            for (RelationshipElement el : src.getCallees()) {
                copiedCallees.add(copyElement(el));
            }
            r.setCallees(copiedCallees);
        }
        r.setDistance(distanceOverride != null ? distanceOverride : src.getDistance());
        r.setBuiltIn(src.getBuiltIn());
        r.setCallStmt(src.getCallStmt());
        if (src.getCallCoordinate() != null) {
            r.setCallCoordinate(new ArrayList<>(src.getCallCoordinate()));
        }
        return r;
    }

    public static RelationshipElement copyElement(RelationshipElement src) {
        if (src == null) {
            return null;
        }
        RelationshipElement el = new RelationshipElement();
        el.setId(src.getId());
        el.setName(src.getName());
        el.setColumn(src.getColumn());
        el.setColumnType(src.getColumnType());
        el.setSourceId(src.getSourceId());
        el.setSourceName(src.getSourceName());
        el.setTargetId(src.getTargetId());
        el.setTargetName(src.getTargetName());
        el.setParentId(src.getParentId());
        el.setParentName(src.getParentName());
        el.setParentAlias(src.getParentAlias());
        if (src.getCoordinates() != null) {
            el.setCoordinates(new ArrayList<>(src.getCoordinates()));
        }
        if (src.getTransforms() != null) {
            List<Transform> transforms = new ArrayList<>(src.getTransforms().size());
            for (Transform t : src.getTransforms()) {
                transforms.add(new Transform(
                        t.getType(),
                        t.getCode(),
                        t.getCoordinates() != null ? new ArrayList<>(t.getCoordinates()) : null));
            }
            el.setTransforms(transforms);
        }
        el.setClauseType(src.getClauseType());
        if (src.getCandidateParents() != null) {
            List<CandidateTable> cands = new ArrayList<>(src.getCandidateParents().size());
            for (CandidateTable ct : src.getCandidateParents()) {
                CandidateTable copy = new CandidateTable();
                copy.setId(ct.getId());
                copy.setName(ct.getName());
                cands.add(copy);
            }
            el.setCandidateParents(cands);
        }
        el.setValue(src.getValue());
        el.setSource(src.getSource());
        el.setType(src.getType());
        el.setFunction(src.getFunction());
        return el;
    }

    private static ProcedureObject copyProcedureObject(ProcedureObject src) {
        ProcedureObject p = new ProcedureObject(src.getId(), src.getName(), src.getType());
        if (src.getArguments() != null) {
            List<ArgumentObject> args = new ArrayList<>(src.getArguments().size());
            for (ArgumentObject a : src.getArguments()) {
                ArgumentObject ca = new ArgumentObject();
                ca.setId(a.getId());
                ca.setName(a.getName());
                ca.setDataType(a.getDataType());
                ca.setInout(a.getInout());
                ca.setCoordinates(a.getCoordinates() != null ? new ArrayList<>(a.getCoordinates()) : null);
                args.add(ca);
            }
            p.setArguments(args);
        }
        if (src.getCoordinates() != null) {
            p.setCoordinates(new ArrayList<>(src.getCoordinates()));
        }
        return p;
    }

    private static ProcessNode copyProcessNode(ProcessNode src) {
        ProcessNode p = new ProcessNode(src.getId(), src.getName(), src.getType());
        p.setProcedureId(src.getProcedureId());
        p.setProcedureName(src.getProcedureName());
        p.setQueryHashId(src.getQueryHashId());
        p.setUiVisible(src.getUiVisible());
        if (src.getCoordinates() != null) {
            p.setCoordinates(new ArrayList<>(src.getCoordinates()));
        }
        if (src.getTransforms() != null) {
            p.setTransforms(new ArrayList<>(src.getTransforms()));
        }
        return p;
    }

    public static SummaryInfo computeSummary(SqlflowCatalog catalog, List<Relationship> relationships) {
        SummaryInfo summary = new SummaryInfo();
        Set<String> dbs = new LinkedHashSet<>();
        Set<String> schemas = new LinkedHashSet<>();
        Set<String> tables = new LinkedHashSet<>();
        Set<String> views = new LinkedHashSet<>();
        Set<String> cols = new LinkedHashSet<>();
        Set<String> procs = new LinkedHashSet<>();
        Map<String, SummaryInfo.MostRelationTable> tableCounts = new LinkedHashMap<>();

        if (catalog != null && catalog.getServers() != null) {
            for (ServerObject srv : catalog.getServers()) {
                if (srv.getDatabases() == null) {
                    continue;
                }
                for (DatabaseObject db : srv.getDatabases()) {
                    dbs.add(DataFlowIndex.normalizeIdentifier(db.getName()));
                    if (db.getSchemas() == null) {
                        continue;
                    }
                    for (SchemaObject sch : db.getSchemas()) {
                        schemas.add(DataFlowIndex.normalizeIdentifier(db.getName() + "." + sch.getName()));
                        if (sch.getTables() != null) {
                            for (DbObject t : sch.getTables()) {
                                String key = DataFlowIndex.normalizeIdentifier(
                                        db.getName() + "." + sch.getName() + "." + t.getName());
                                tables.add(key);
                                tableCounts.putIfAbsent(
                                        t.getId(),
                                        new SummaryInfo.MostRelationTable(db.getName(), sch.getName(), t.getName(), 0));
                                if (t.getColumns() != null) {
                                    for (Column c : t.getColumns()) {
                                        cols.add(key + "." + DataFlowIndex.normalizeIdentifier(c.getName()));
                                    }
                                }
                            }
                        }
                        if (sch.getViews() != null) {
                            for (DbObject v : sch.getViews()) {
                                String key = DataFlowIndex.normalizeIdentifier(
                                        db.getName() + "." + sch.getName() + "." + v.getName());
                                views.add(key);
                                tableCounts.putIfAbsent(
                                        v.getId(),
                                        new SummaryInfo.MostRelationTable(db.getName(), sch.getName(), v.getName(), 0));
                                if (v.getColumns() != null) {
                                    for (Column c : v.getColumns()) {
                                        cols.add(key + "." + DataFlowIndex.normalizeIdentifier(c.getName()));
                                    }
                                }
                            }
                        }
                        if (sch.getProcesses() != null) {
                            for (ProcessNode pn : sch.getProcesses()) {
                                procs.add(pn.getQueryHashId() != null ? pn.getQueryHashId() : pn.getId());
                            }
                        }
                    }
                }
            }
        }

        int relCount = relationships != null ? relationships.size() : 0;
        if (relationships != null) {
            for (Relationship rel : relationships) {
                if (rel.getTarget() != null && rel.getTarget().getParentId() != null) {
                    SummaryInfo.MostRelationTable mrt = tableCounts.get(rel.getTarget().getParentId());
                    if (mrt != null) {
                        mrt.setRelationCount(mrt.getRelationCount() + 1);
                    }
                }
                if (rel.getSources() != null) {
                    for (RelationshipElement s : rel.getSources()) {
                        if (s.getParentId() != null) {
                            SummaryInfo.MostRelationTable mrt = tableCounts.get(s.getParentId());
                            if (mrt != null) {
                                mrt.setRelationCount(mrt.getRelationCount() + 1);
                            }
                        }
                    }
                }
            }
        }

        List<SummaryInfo.MostRelationTable> topTables = tableCounts.values().stream()
                .sorted(Comparator.comparingInt(SummaryInfo.MostRelationTable::getRelationCount).reversed())
                .limit(10)
                .toList();

        summary.setProcess(procs.size());
        summary.setDatabase(dbs.size());
        summary.setSchema(schemas.size());
        summary.setTable(tables.size());
        summary.setView(views.size());
        summary.setColumn(cols.size());
        summary.setRelationship(relCount);
        summary.setRelationCount(relCount);
        summary.setMostRelationTables(topTables);
        return summary;
    }
}
