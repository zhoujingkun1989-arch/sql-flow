package io.sqlflow.lineage.analyzer;

import io.sqlflow.contracts.graph.SummaryInfo;
import io.sqlflow.contracts.graph.SummaryInfo.MostRelationTable;
import io.sqlflow.contracts.lineage.Column;
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
import io.sqlflow.contracts.lineage.SchemaObject;
import io.sqlflow.contracts.lineage.SequenceObject;
import io.sqlflow.contracts.lineage.ServerObject;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.contracts.lineage.SynonymObject;
import io.sqlflow.contracts.vendor.DataObjectType;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.contracts.vendor.IdentifierProfile;
import io.sqlflow.lineage.catalog.TSQLEnv;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Implements {@code summarize(DataFlow)} and {@code mergeDataFlows(List<DataFlow>, Option)} ({@code OBL-044, OBL-050}).
 */
public final class DataFlowMergerAndSummarizer {

    private DataFlowMergerAndSummarizer() {
    }

    public static SummaryInfo summarize(DataFlow df) {
        SummaryInfo info = new SummaryInfo();
        if (df == null) {
            info.setRelationCount(0);
            info.setMostRelationTables(List.of());
            return info;
        }

        Set<String> dbNames = new LinkedHashSet<>();
        Set<String> schemaNames = new LinkedHashSet<>();
        Set<String> dblinkNames = new LinkedHashSet<>();
        Map<String, DbObject> tableAndViewById = new LinkedHashMap<>();
        int tableCount = 0;
        int viewCount = 0;
        int colCount = 0;

        SqlflowCatalog cat = df.getDbobjs();
        if (cat != null) {
            collectDbLinks(cat.getDbLinks(), dblinkNames);
            if (cat.getServers() != null) {
                for (ServerObject srv : cat.getServers()) {
                    collectDbLinks(srv.getDbLinks(), dblinkNames);
                    if (srv.getDatabases() != null) {
                        for (DatabaseObject dbo : srv.getDatabases()) {
                            if (dbo.getName() != null) dbNames.add(dbo.getName());
                            if (dbo.getSchemas() != null) {
                                for (SchemaObject so : dbo.getSchemas()) {
                                    if (so.getName() != null) schemaNames.add(dbo.getName() + "." + so.getName());
                                    int[] counts = countSchemaObjects(so, tableAndViewById);
                                    tableCount += counts[0];
                                    viewCount += counts[1];
                                    colCount += counts[2];
                                }
                            }
                            int[] direct = countDatabaseDirectObjects(dbo, tableAndViewById);
                            tableCount += direct[0];
                            viewCount += direct[1];
                            colCount += direct[2];
                        }
                    }
                    if (srv.getSchemas() != null) {
                        for (SchemaObject so : srv.getSchemas()) {
                            if (so.getName() != null) schemaNames.add(so.getName());
                            int[] counts = countSchemaObjects(so, tableAndViewById);
                            tableCount += counts[0];
                            viewCount += counts[1];
                            colCount += counts[2];
                        }
                    }
                }
            }
        }

        List<Relationship> rels = df.getRelationships() != null ? df.getRelationships() : List.of();
        Map<String, Integer> relCountByObjId = new LinkedHashMap<>();
        for (Relationship r : rels) {
            Set<String> touchedInEdge = new HashSet<>();
            if (r.getTarget() != null && r.getTarget().getParentId() != null) {
                touchedInEdge.add(r.getTarget().getParentId());
            }
            if (r.getSources() != null) {
                for (RelationshipElement s : r.getSources()) {
                    if (s.getParentId() != null) {
                        touchedInEdge.add(s.getParentId());
                    }
                }
            }
            for (String objId : touchedInEdge) {
                if (tableAndViewById.containsKey(objId)) {
                    relCountByObjId.merge(objId, 1, Integer::sum);
                }
            }
        }

        List<MostRelationTable> topTables = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : relCountByObjId.entrySet()) {
            DbObject obj = tableAndViewById.get(entry.getKey());
            if (obj != null) {
                topTables.add(new MostRelationTable(obj.getDatabase(), obj.getSchema(), obj.getName(), entry.getValue()));
            }
        }
        topTables.sort(Comparator.comparingInt(MostRelationTable::getRelationCount).reversed());
        if (topTables.size() > 10) {
            topTables = new ArrayList<>(topTables.subList(0, 10));
        }

        info.setProcess(df.getProcesses() != null ? df.getProcesses().size() : 0);
        info.setDatabase(dbNames.size());
        info.setSchema(schemaNames.size());
        info.setTable(tableCount);
        info.setView(viewCount);
        info.setColumn(colCount);
        info.setRelationship(rels.size());
        info.setRelationCount(rels.size());
        info.setDblink(dblinkNames.size());
        info.setMostRelationTables(topTables);
        return info;
    }

    public static DataFlow mergeDataFlows(List<DataFlow> batches, Option option) {
        Option opt = option != null ? option : new Option();
        EDbVendor vendor = opt.getVendor() != null ? opt.getVendor() : EDbVendor.dbvoracle;
        if (batches != null) {
            for (DataFlow b : batches) {
                if (b != null && b.getDbvendor() != null) {
                    vendor = EDbVendor.fromStringOrDefault(b.getDbvendor(), vendor);
                    break;
                }
            }
        }
        TSQLEnv env = new TSQLEnv(vendor, opt.getIdentifierProfile(), opt);
        IdentifierProfile profile = env.profile();
        boolean supCat = profile.metadataSupportsCatalogs();
        boolean supSch = profile.metadataSupportsSchemas();
        String defSrv = nonBlank(opt.getDefaultServer(), "DEFAULT_SERVER");
        String defDb = nonBlank(opt.getDefaultDatabase(), "DEFAULT");
        String defSch = nonBlank(opt.getDefaultSchema(), "DEFAULT");

        long[] idSeq = {opt.getStartId()};
        Map<String, MergedObj> mergedPhysical = new LinkedHashMap<>();
        List<MergedObj> allMergedObjs = new ArrayList<>();
        List<PackageObject> mergedPackages = new ArrayList<>();
        List<ProcedureObject> mergedProcs = new ArrayList<>();
        List<ProcedureObject> mergedFuncs = new ArrayList<>();
        List<ProcedureObject> mergedTrigs = new ArrayList<>();
        List<SynonymObject> mergedSyns = new ArrayList<>();
        List<SequenceObject> mergedSeqs = new ArrayList<>();
        List<ProcessNode> mergedProcesses = new ArrayList<>();
        List<DbLink> mergedDbLinks = new ArrayList<>();
        Set<String> dbLinkKeys = new HashSet<>();
        List<Relationship> mergedRels = new ArrayList<>();
        Set<String> relDedup = new LinkedHashSet<>();
        List<ErrorItem> mergedErrors = new ArrayList<>();

        if (batches != null) {
            int batchIdx = 0;
            for (DataFlow b : batches) {
                if (b == null) {
                    batchIdx++;
                    continue;
                }
                Map<String, String> objIdMap = new HashMap<>();
                Map<String, String> colIdMap = new HashMap<>();
                Map<String, String> mergedParentNames = new HashMap<>();

                SqlflowCatalog cat = b.getDbobjs();
                if (cat != null && cat.getServers() != null) {
                    for (ServerObject srv : cat.getServers()) {
                        if (srv.getDbLinks() != null) {
                            for (DbLink l : srv.getDbLinks()) {
                                if (l.getName() != null && dbLinkKeys.add(l.getName())) {
                                    mergedDbLinks.add(l);
                                }
                            }
                        }
                        if (srv.getDatabases() != null) {
                            for (DatabaseObject dbo : srv.getDatabases()) {
                                if (dbo.getSchemas() != null) {
                                    for (SchemaObject so : dbo.getSchemas()) {
                                        ingestContainer(batchIdx, dbo.getName(), so.getName(), so.getTables(), "table",
                                                env, idSeq, mergedPhysical, allMergedObjs, objIdMap, colIdMap, mergedParentNames);
                                        ingestContainer(batchIdx, dbo.getName(), so.getName(), so.getViews(), "view",
                                                env, idSeq, mergedPhysical, allMergedObjs, objIdMap, colIdMap, mergedParentNames);
                                        ingestContainer(batchIdx, dbo.getName(), so.getName(), so.getOthers(), "other",
                                                env, idSeq, mergedPhysical, allMergedObjs, objIdMap, colIdMap, mergedParentNames);
                                        copyCatalogExtras(so.getPackages(), so.getProcedures(), so.getFunctions(),
                                                so.getTriggers(), so.getSynonyms(), so.getSequences(), idSeq, objIdMap,
                                                mergedPackages, mergedProcs, mergedFuncs, mergedTrigs, mergedSyns, mergedSeqs);
                                    }
                                }
                                ingestContainer(batchIdx, dbo.getName(), null, dbo.getTables(), "table",
                                        env, idSeq, mergedPhysical, allMergedObjs, objIdMap, colIdMap, mergedParentNames);
                                ingestContainer(batchIdx, dbo.getName(), null, dbo.getViews(), "view",
                                        env, idSeq, mergedPhysical, allMergedObjs, objIdMap, colIdMap, mergedParentNames);
                                ingestContainer(batchIdx, dbo.getName(), null, dbo.getOthers(), "other",
                                        env, idSeq, mergedPhysical, allMergedObjs, objIdMap, colIdMap, mergedParentNames);
                                copyCatalogExtras(dbo.getPackages(), dbo.getProcedures(), dbo.getFunctions(),
                                        dbo.getTriggers(), dbo.getSynonyms(), dbo.getSequences(), idSeq, objIdMap,
                                        mergedPackages, mergedProcs, mergedFuncs, mergedTrigs, mergedSyns, mergedSeqs);
                            }
                        }
                        if (srv.getSchemas() != null) {
                            for (SchemaObject so : srv.getSchemas()) {
                                ingestContainer(batchIdx, null, so.getName(), so.getTables(), "table",
                                        env, idSeq, mergedPhysical, allMergedObjs, objIdMap, colIdMap, mergedParentNames);
                                ingestContainer(batchIdx, null, so.getName(), so.getViews(), "view",
                                        env, idSeq, mergedPhysical, allMergedObjs, objIdMap, colIdMap, mergedParentNames);
                                ingestContainer(batchIdx, null, so.getName(), so.getOthers(), "other",
                                        env, idSeq, mergedPhysical, allMergedObjs, objIdMap, colIdMap, mergedParentNames);
                                copyCatalogExtras(so.getPackages(), so.getProcedures(), so.getFunctions(),
                                        so.getTriggers(), so.getSynonyms(), so.getSequences(), idSeq, objIdMap,
                                        mergedPackages, mergedProcs, mergedFuncs, mergedTrigs, mergedSyns, mergedSeqs);
                            }
                        }
                    }
                }

                Map<String, String> procIdMap = new HashMap<>();
                if (b.getProcesses() != null) {
                    for (ProcessNode pn : b.getProcesses()) {
                        String newPid = String.valueOf(++idSeq[0]);
                        procIdMap.put(pn.getId(), newPid);
                        ProcessNode copy = new ProcessNode(newPid, pn.getName(), pn.getType());
                        copy.setProcedureId(objIdMap.getOrDefault(pn.getProcedureId(), pn.getProcedureId()));
                        copy.setProcedureName(pn.getProcedureName());
                        copy.setQueryHashId(pn.getQueryHashId());
                        copy.setCoordinates(pn.getCoordinates());
                        copy.setTransforms(pn.getTransforms());
                        mergedProcesses.add(copy);
                    }
                }

                if (b.getRelationships() != null) {
                    for (Relationship rel : b.getRelationships()) {
                        Relationship remapped = remapRelationship(rel, idSeq, objIdMap, colIdMap, mergedParentNames, procIdMap);
                        String sig = buildRelSignature(remapped);
                        if (relDedup.add(sig)) {
                            mergedRels.add(remapped);
                        }
                    }
                }

                if (b.getErrors() != null) {
                    mergedErrors.addAll(b.getErrors());
                }
                batchIdx++;
            }
        }

        DataFlow out = new DataFlow();
        out.setDbvendor(vendor.wireName());
        out.setOrientation(Orientation.LEFT_TO_RIGHT);
        out.setDbobjs(assembleMergedCatalog(vendor, defSrv, defDb, defSch, supCat, supSch,
                allMergedObjs, mergedPackages, mergedProcs, mergedFuncs, mergedTrigs,
                mergedSyns, mergedSeqs, mergedProcesses, mergedDbLinks));
        out.setRelationships(mergedRels);
        if (!mergedProcesses.isEmpty()) out.setProcesses(mergedProcesses);
        if (!mergedErrors.isEmpty()) out.setErrors(mergedErrors);
        out.setSummary(summarize(out));
        return out;
    }

    private static void ingestContainer(
            int batchIdx,
            String containerDb,
            String containerSch,
            List<DbObject> list,
            String bucketKind,
            TSQLEnv env,
            long[] idSeq,
            Map<String, MergedObj> mergedPhysical,
            List<MergedObj> allMergedObjs,
            Map<String, String> objIdMap,
            Map<String, String> colIdMap,
            Map<String, String> mergedParentNames) {
        if (list == null) return;
        for (DbObject src : list) {
            String db = src.getDatabase() != null ? src.getDatabase() : containerDb;
            String sch = src.getSchema() != null ? src.getSchema() : containerSch;
            boolean shareAcrossBatches = "table".equals(bucketKind)
                    || "view".equals(bucketKind)
                    || isSharedExternalType(src.getType());
            String key = shareAcrossBatches
                    ? bucketKind + "|"
                            + env.lookupKey(DataObjectType.CATALOG, db, false) + "|"
                            + env.lookupKey(DataObjectType.SCHEMA, sch, false) + "|"
                            + env.lookupKey(DataObjectType.TABLE, src.getName(), false)
                    : "batch" + batchIdx + "|" + src.getId();

            MergedObj target = shareAcrossBatches ? mergedPhysical.get(key) : null;
            if (target == null) {
                DbObject copy = cloneDbObjectHeader(src, String.valueOf(++idSeq[0]));
                target = new MergedObj(bucketKind, db, sch, copy);
                if (shareAcrossBatches) {
                    mergedPhysical.put(key, target);
                }
                allMergedObjs.add(target);
            } else {
                mergeDbObjectFlags(src, target.dbo);
            }
            if (src.getId() != null) {
                objIdMap.put(src.getId(), target.dbo.getId());
                mergedParentNames.put(target.dbo.getId(),
                        target.dbo.getDisplayName() != null ? target.dbo.getDisplayName() : target.dbo.getName());
            }
            if (src.getColumns() != null) {
                for (Column sc : src.getColumns()) {
                    String colKey = env.lookupKey(DataObjectType.COLUMN, sc.getName(), false);
                    Column mc = target.columnsByKey.get(colKey);
                    if (mc == null) {
                        mc = cloneColumn(sc, String.valueOf(++idSeq[0]));
                        target.columnsByKey.put(colKey, mc);
                    } else {
                        mergeColumnMetadata(sc, mc);
                    }
                    if (sc.getId() != null) {
                        colIdMap.put(sc.getId(), mc.getId());
                    }
                }
            }
        }
    }

    private static Relationship remapRelationship(
            Relationship rel,
            long[] idSeq,
            Map<String, String> objIdMap,
            Map<String, String> colIdMap,
            Map<String, String> mergedParentNames,
            Map<String, String> procIdMap) {
        String newId = String.valueOf(++idSeq[0]);
        Relationship out = "join".equalsIgnoreCase(rel.getType())
                ? new JoinRelationship(newId, rel.getJoinType(), rel.getClause(), rel.getCondition())
                : new Relationship();
        out.setId(newId);
        out.setType(rel.getType());
        out.setJoinType(rel.getJoinType());
        out.setClause(rel.getClause());
        out.setCondition(rel.getCondition());
        out.setEffectType(rel.getEffectType());
        out.setProcessId(procIdMap.getOrDefault(rel.getProcessId(), rel.getProcessId()));
        out.setProcessType(rel.getProcessType());
        out.setProcedureId(objIdMap.getOrDefault(rel.getProcedureId(), rel.getProcedureId()));
        out.setFunction(rel.getFunction());
        out.setPartition(rel.getPartition());
        out.setSqlHash(rel.getSqlHash());
        out.setSqlComment(rel.getSqlComment());
        out.setBuiltIn(rel.getBuiltIn());
        out.setCallStmt(rel.getCallStmt());
        out.setCallCoordinate(rel.getCallCoordinate());
        if (rel.getCaller() != null) {
            out.setCaller(remapElem(rel.getCaller(), objIdMap, colIdMap, mergedParentNames));
        }
        if (rel.getCallees() != null) {
            List<RelationshipElement> callees = new ArrayList<>(rel.getCallees().size());
            for (RelationshipElement c : rel.getCallees()) {
                callees.add(remapElem(c, objIdMap, colIdMap, mergedParentNames));
            }
            out.setCallees(callees);
        }
        if (rel.getTarget() != null) {
            out.setTarget(remapElem(rel.getTarget(), objIdMap, colIdMap, mergedParentNames));
        }
        if (rel.getSources() != null) {
            List<RelationshipElement> srcs = new ArrayList<>(rel.getSources().size());
            for (RelationshipElement s : rel.getSources()) {
                srcs.add(remapElem(s, objIdMap, colIdMap, mergedParentNames));
            }
            out.setSources(srcs);
        }
        return out;
    }

    private static RelationshipElement remapElem(
            RelationshipElement e,
            Map<String, String> objIdMap,
            Map<String, String> colIdMap,
            Map<String, String> mergedParentNames) {
        RelationshipElement copy = new RelationshipElement();
        String mappedId = colIdMap.getOrDefault(e.getId(), objIdMap.getOrDefault(e.getId(), e.getId()));
        String mappedParent = objIdMap.getOrDefault(e.getParentId(), e.getParentId());
        copy.setId(mappedId);
        copy.setName(e.getName());
        copy.setColumn(e.getColumn());
        copy.setColumnType(e.getColumnType());
        copy.setParentId(mappedParent);
        copy.setParentName(mergedParentNames.getOrDefault(mappedParent, e.getParentName()));
        copy.setParentAlias(e.getParentAlias());
        copy.setCoordinates(e.getCoordinates());
        copy.setTransforms(e.getTransforms());
        copy.setClauseType(e.getClauseType());
        copy.setCandidateParents(e.getCandidateParents());
        copy.setValue(e.getValue());
        copy.setSource(e.getSource());
        copy.setType(e.getType());
        copy.setFunction(e.getFunction());
        return copy;
    }

    private static String buildRelSignature(Relationship r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.getType()).append('|').append(r.getEffectType()).append('|');
        if (r.getTarget() != null) {
            sb.append(r.getTarget().getParentId()).append(':').append(r.getTarget().getId());
        }
        sb.append("<-");
        if (r.getSources() != null) {
            for (RelationshipElement s : r.getSources()) {
                sb.append(s.getParentId()).append(':').append(s.getId()).append(',');
            }
        }
        if (r.getCaller() != null) {
            sb.append("call:").append(r.getCaller().getId()).append("->");
            if (r.getCallees() != null) {
                for (RelationshipElement c : r.getCallees()) sb.append(c.getName()).append(',');
            }
        }
        return sb.toString();
    }

    private static SqlflowCatalog assembleMergedCatalog(
            EDbVendor vendor, String defSrv, String defDb, String defSch,
            boolean supCat, boolean supSch, List<MergedObj> objs,
            List<PackageObject> pkgs, List<ProcedureObject> procs, List<ProcedureObject> funcs,
            List<ProcedureObject> trigs, List<SynonymObject> syns, List<SequenceObject> seqs,
            List<ProcessNode> processes, List<DbLink> dbLinks) {
        SqlflowCatalog cat = new SqlflowCatalog();
        cat.setDialect(vendor.wireName());
        ServerObject srv = new ServerObject(defSrv, vendor.wireName(), supCat, supSch);
        if (!dbLinks.isEmpty()) {
            srv.setDbLinks(dbLinks);
            cat.setDbLinks(dbLinks);
        }
        Map<String, SchemaObject> schemaMap = new LinkedHashMap<>();
        for (MergedObj mo : objs) {
            if (!mo.columnsByKey.isEmpty()) {
                mo.dbo.setColumns(new ArrayList<>(mo.columnsByKey.values()));
            }
            String db = supCat ? nonBlank(mo.db, defDb) : defDb;
            String sch = supSch ? nonBlank(mo.sch, defSch) : defSch;
            SchemaObject so = schemaMap.computeIfAbsent(db + "|" + sch, k -> new SchemaObject(sch));
            if ("table".equals(mo.bucketKind)) {
                if (so.getTables() == null) so.setTables(new ArrayList<>());
                so.getTables().add(mo.dbo);
            } else if ("view".equals(mo.bucketKind)) {
                if (so.getViews() == null) so.setViews(new ArrayList<>());
                so.getViews().add(mo.dbo);
            } else {
                if (so.getOthers() == null) so.setOthers(new ArrayList<>());
                so.getOthers().add(mo.dbo);
            }
        }
        SchemaObject defaultContainer = schemaMap.computeIfAbsent(defDb + "|" + defSch, k -> new SchemaObject(defSch));
        if (!pkgs.isEmpty()) defaultContainer.setPackages(pkgs);
        if (!procs.isEmpty()) defaultContainer.setProcedures(procs);
        if (!funcs.isEmpty()) defaultContainer.setFunctions(funcs);
        if (!trigs.isEmpty()) defaultContainer.setTriggers(trigs);
        if (!syns.isEmpty()) defaultContainer.setSynonyms(syns);
        if (!seqs.isEmpty()) defaultContainer.setSequences(seqs);
        if (!processes.isEmpty()) defaultContainer.setProcesses(processes);

        if (!supCat && supSch) {
            srv.setSchemas(new ArrayList<>(schemaMap.values()));
        } else if (supCat && !supSch) {
            List<DatabaseObject> dbs = new ArrayList<>();
            for (Map.Entry<String, SchemaObject> e : schemaMap.entrySet()) {
                String dbName = e.getKey().substring(0, e.getKey().indexOf('|'));
                SchemaObject so = e.getValue();
                DatabaseObject dbo = new DatabaseObject(dbName);
                dbo.setTables(so.getTables());
                dbo.setViews(so.getViews());
                dbo.setOthers(so.getOthers());
                dbo.setPackages(so.getPackages());
                dbo.setProcedures(so.getProcedures());
                dbo.setFunctions(so.getFunctions());
                dbo.setTriggers(so.getTriggers());
                dbo.setSynonyms(so.getSynonyms());
                dbo.setSequences(so.getSequences());
                dbo.setProcesses(so.getProcesses());
                dbs.add(dbo);
            }
            srv.setDatabases(dbs);
        } else {
            Map<String, List<SchemaObject>> byDb = new LinkedHashMap<>();
            for (Map.Entry<String, SchemaObject> e : schemaMap.entrySet()) {
                String dbName = e.getKey().substring(0, e.getKey().indexOf('|'));
                byDb.computeIfAbsent(dbName, k -> new ArrayList<>()).add(e.getValue());
            }
            List<DatabaseObject> dbs = new ArrayList<>();
            for (Map.Entry<String, List<SchemaObject>> e : byDb.entrySet()) {
                DatabaseObject dbo = new DatabaseObject(e.getKey());
                dbo.setSchemas(e.getValue());
                dbs.add(dbo);
            }
            srv.setDatabases(dbs);
        }
        cat.setServers(List.of(srv));
        return cat;
    }

    private static void copyCatalogExtras(
            List<PackageObject> pkgs, List<ProcedureObject> procs, List<ProcedureObject> funcs,
            List<ProcedureObject> trigs, List<SynonymObject> syns, List<SequenceObject> seqs,
            long[] idSeq, Map<String, String> objIdMap,
            List<PackageObject> outPkgs, List<ProcedureObject> outProcs, List<ProcedureObject> outFuncs,
            List<ProcedureObject> outTrigs, List<SynonymObject> outSyns, List<SequenceObject> outSeqs) {
        if (pkgs != null) outPkgs.addAll(pkgs);
        if (procs != null) for (ProcedureObject p : procs) outProcs.add(remapProc(p, idSeq, objIdMap));
        if (funcs != null) for (ProcedureObject f : funcs) outFuncs.add(remapProc(f, idSeq, objIdMap));
        if (trigs != null) for (ProcedureObject t : trigs) outTrigs.add(remapProc(t, idSeq, objIdMap));
        if (syns != null) outSyns.addAll(syns);
        if (seqs != null) outSeqs.addAll(seqs);
    }

    private static ProcedureObject remapProc(ProcedureObject src, long[] idSeq, Map<String, String> objIdMap) {
        String nid = String.valueOf(++idSeq[0]);
        if (src.getId() != null) objIdMap.put(src.getId(), nid);
        ProcedureObject p = new ProcedureObject(nid, src.getName(), src.getType());
        p.setArguments(src.getArguments());
        p.setCoordinates(src.getCoordinates());
        return p;
    }

    private static DbObject cloneDbObjectHeader(DbObject s, String newId) {
        DbObject d = new DbObject(newId, s.getName(), s.getType());
        d.setServer(s.getServer());
        d.setDatabase(s.getDatabase());
        d.setSchema(s.getSchema());
        d.setDisplayName(s.getDisplayName());
        d.setAlias(s.getAlias());
        d.setUri(s.getUri());
        d.setSubType(s.getSubType());
        d.setDbLink(s.getDbLink());
        d.setIsView(s.getIsView());
        d.setIsTarget(s.getIsTarget());
        d.setFromDDL(s.getFromDDL());
        d.setCreatedInSql(s.getCreatedInSql());
        d.setEndpointKind(s.getEndpointKind());
        d.setEndpointIntroduction(s.getEndpointIntroduction());
        d.setNormalizedName(s.getNormalizedName());
        d.setTemplatedParts(s.getTemplatedParts());
        d.setCoordinates(s.getCoordinates());
        return d;
    }

    private static void mergeDbObjectFlags(DbObject src, DbObject dst) {
        if ("true".equals(src.getIsTarget())) dst.setIsTarget("true");
        if (Boolean.TRUE.equals(src.getFromDDL())) dst.setFromDDL(Boolean.TRUE);
        if (Boolean.TRUE.equals(src.getCreatedInSql())) dst.setCreatedInSql(Boolean.TRUE);
    }

    private static Column cloneColumn(Column sc, String newId) {
        Column c = new Column(newId, sc.getName(), sc.getDataType());
        c.setDisplayName(sc.getDisplayName());
        c.setComment(sc.getComment());
        c.setCoordinates(sc.getCoordinates());
        c.setSource(sc.getSource());
        c.setPrimaryKey(sc.getPrimaryKey());
        c.setUnqiueKey(sc.getUnqiueKey());
        c.setIndexKey(sc.getIndexKey());
        c.setForeignKey(sc.getForeignKey());
        c.setNullable(sc.getNullable());
        c.setIdentity(sc.getIdentity());
        c.setComputed(sc.getComputed());
        c.setPersisted(sc.getPersisted());
        c.setComputedDefinition(sc.getComputedDefinition());
        c.setDefaultValue(sc.getDefaultValue());
        c.setDefaultName(sc.getDefaultName());
        c.setQualifiedTable(sc.getQualifiedTable());
        c.setIsFunction(sc.getIsFunction());
        c.setStar(sc.getStar());
        return c;
    }

    private static void mergeColumnMetadata(Column sc, Column mc) {
        if (mc.getDataType() == null && sc.getDataType() != null) mc.setDataType(sc.getDataType());
        if (Boolean.TRUE.equals(sc.getPrimaryKey())) mc.setPrimaryKey(Boolean.TRUE);
        if (Boolean.TRUE.equals(sc.getUnqiueKey())) mc.setUnqiueKey(Boolean.TRUE);
        if (Boolean.TRUE.equals(sc.getIndexKey())) mc.setIndexKey(Boolean.TRUE);
        if (Boolean.TRUE.equals(sc.getForeignKey())) mc.setForeignKey(Boolean.TRUE);
    }

    private static boolean isSharedExternalType(String type) {
        if (type == null) return false;
        String t = type.toLowerCase(Locale.ROOT);
        return "stage".equals(t) || "path".equals(t) || "stream".equals(t) || "datasource".equals(t);
    }

    private static int[] countSchemaObjects(SchemaObject so, Map<String, DbObject> tableAndViewById) {
        int t = 0, v = 0, c = 0;
        if (so.getTables() != null) {
            for (DbObject o : so.getTables()) {
                t++;
                if (o.getId() != null) tableAndViewById.put(o.getId(), o);
                if (o.getColumns() != null) c += o.getColumns().size();
            }
        }
        if (so.getViews() != null) {
            for (DbObject o : so.getViews()) {
                v++;
                if (o.getId() != null) tableAndViewById.put(o.getId(), o);
                if (o.getColumns() != null) c += o.getColumns().size();
            }
        }
        return new int[] {t, v, c};
    }

    private static int[] countDatabaseDirectObjects(DatabaseObject dbo, Map<String, DbObject> tableAndViewById) {
        SchemaObject tmp = new SchemaObject(dbo.getName());
        tmp.setTables(dbo.getTables());
        tmp.setViews(dbo.getViews());
        return countSchemaObjects(tmp, tableAndViewById);
    }

    private static void collectDbLinks(List<DbLink> links, Set<String> names) {
        if (links == null) return;
        for (DbLink l : links) {
            if (l != null && l.getName() != null) names.add(l.getName());
        }
    }

    private static String nonBlank(String v, String fallback) {
        return v != null && !v.isBlank() ? v : fallback;
    }

    private static final class MergedObj {
        final String bucketKind;
        final String db;
        final String sch;
        final DbObject dbo;
        final Map<String, Column> columnsByKey = new LinkedHashMap<>();

        MergedObj(String bucketKind, String db, String sch, DbObject dbo) {
            this.bucketKind = bucketKind;
            this.db = db;
            this.sch = sch;
            this.dbo = dbo;
        }
    }
}
