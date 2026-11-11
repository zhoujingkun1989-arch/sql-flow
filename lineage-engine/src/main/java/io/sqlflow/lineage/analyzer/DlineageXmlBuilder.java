package io.sqlflow.lineage.analyzer;

import io.sqlflow.contracts.lineage.ArgumentObject;
import io.sqlflow.contracts.lineage.CandidateTable;
import io.sqlflow.contracts.lineage.CommentBindingBundle;
import io.sqlflow.contracts.lineage.Coordinate;
import io.sqlflow.contracts.lineage.ErrorItem;
import io.sqlflow.contracts.lineage.Option;
import io.sqlflow.contracts.lineage.Orientation;
import io.sqlflow.contracts.lineage.RelationshipElement;
import io.sqlflow.contracts.lineage.RelationshipType;
import io.sqlflow.contracts.lineage.Transform;
import io.sqlflow.contracts.lineage.xml.DlineageXml;
import io.sqlflow.contracts.lineage.xml.XmlArgument;
import io.sqlflow.contracts.lineage.xml.XmlCandidateTable;
import io.sqlflow.contracts.lineage.xml.XmlColumn;
import io.sqlflow.contracts.lineage.xml.XmlEndpoint;
import io.sqlflow.contracts.lineage.xml.XmlError;
import io.sqlflow.contracts.lineage.xml.XmlPackage;
import io.sqlflow.contracts.lineage.xml.XmlProcedure;
import io.sqlflow.contracts.lineage.xml.XmlProcess;
import io.sqlflow.contracts.lineage.xml.XmlRelationship;
import io.sqlflow.contracts.lineage.xml.XmlTable;
import io.sqlflow.contracts.lineage.xml.XmlTransform;
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
import java.util.List;
import java.util.Set;

/**
 * Builds the {@link DlineageXml} model from {@link ModelBindingManager} ({@code 03 §1.3, OBL-044}).
 */
public final class DlineageXmlBuilder {

    private DlineageXmlBuilder() {
    }

    public static DlineageXml build(
            ModelBindingManager mgr,
            Option option,
            CommentBindingBundle commentBindings) {
        Option opt = option != null ? option : mgr.option();
        boolean ignoreCoord = opt.isIgnoreCoordinate();
        Set<String> relRowObjIds = collectRelationRowObjectIds(mgr.edges());

        DlineageXml xml = new DlineageXml();
        List<XmlPackage> packages = new ArrayList<>();
        Set<String> packagedVarIds = new HashSet<>();
        for (BoundPackage pkg : mgr.packages()) {
            XmlPackage xp = new XmlPackage();
            xp.setId(pkg.getId());
            xp.setServer(pkg.getServer());
            xp.setDatabase(pkg.getDatabase());
            xp.setSchema(pkg.getSchema());
            xp.setName(pkg.getName());
            xp.setType("package");
            xp.setCoordinate(fmtCoord(pkg.getCoordinates(), ignoreCoord));
            if (!pkg.getProcedures().isEmpty()) {
                List<XmlProcedure> procs = new ArrayList<>(pkg.getProcedures().size());
                for (BoundRoutine r : pkg.getProcedures()) {
                    procs.add(toXmlProcedure(r, ignoreCoord));
                }
                xp.setProcedures(procs);
            }
            if (!pkg.getVariables().isEmpty()) {
                List<XmlTable> vars = new ArrayList<>(pkg.getVariables().size());
                for (BoundObject v : pkg.getVariables()) {
                    packagedVarIds.add(v.getId());
                    vars.add(toXmlTable(v, relRowObjIds.contains(v.getId()), ignoreCoord));
                }
                xp.setVariables(vars);
            }
            packages.add(xp);
        }
        if (!packages.isEmpty()) {
            xml.setPackages(packages);
        }

        List<XmlProcedure> procedures = new ArrayList<>();
        for (BoundRoutine r : mgr.routines()) {
            if (r.getPackageName() == null || r.getPackageName().isBlank()) {
                procedures.add(toXmlProcedure(r, ignoreCoord));
            }
        }
        if (!procedures.isEmpty()) {
            xml.setProcedures(procedures);
        }

        List<XmlTable> stages = new ArrayList<>();
        List<XmlTable> sequences = new ArrayList<>();
        List<XmlTable> datasources = new ArrayList<>();
        List<XmlTable> streams = new ArrayList<>();
        List<XmlTable> paths = new ArrayList<>();
        List<XmlTable> variables = new ArrayList<>();
        List<XmlTable> tables = new ArrayList<>();
        List<XmlTable> views = new ArrayList<>();
        List<XmlTable> resultsets = new ArrayList<>();

        for (BoundObject obj : mgr.allObjects()) {
            if (packagedVarIds.contains(obj.getId()) || obj.getCategory() == ObjectCategory.SYNONYM) {
                continue;
            }
            XmlTable xt = toXmlTable(obj, relRowObjIds.contains(obj.getId()), ignoreCoord);
            switch (obj.getCategory()) {
                case STAGE -> stages.add(xt);
                case SEQUENCE -> sequences.add(xt);
                case DATASOURCE -> datasources.add(xt);
                case STREAM -> streams.add(xt);
                case PATH -> paths.add(xt);
                case VARIABLE -> variables.add(xt);
                case VIEW -> views.add(xt);
                case RESULTSET -> resultsets.add(xt);
                case TABLE -> tables.add(xt);
                default -> tables.add(xt);
            }
        }
        if (!stages.isEmpty()) xml.setStages(stages);
        if (!sequences.isEmpty()) xml.setSequences(sequences);
        if (!datasources.isEmpty()) xml.setDatasources(datasources);
        if (!streams.isEmpty()) xml.setStreams(streams);
        if (!paths.isEmpty()) xml.setPaths(paths);
        if (!variables.isEmpty()) xml.setVariables(variables);

        if (!mgr.processes().isEmpty()) {
            List<XmlProcess> procs = new ArrayList<>(mgr.processes().size());
            for (BoundProcess bp : mgr.processes()) {
                XmlProcess xp = new XmlProcess();
                xp.setId(bp.getId());
                xp.setServer(bp.getServer());
                xp.setDatabase(bp.getDatabase());
                xp.setSchema(bp.getSchema());
                xp.setName(bp.getName());
                xp.setProcedureId(bp.getProcedureId());
                xp.setProcedureName(bp.getProcedureName());
                xp.setQueryHashId(bp.getQueryHashId());
                xp.setType(bp.getType());
                xp.setCoordinate(fmtCoord(bp.getCoordinates(), ignoreCoord));
                xp.setTransforms(toXmlTransforms(bp.getTransforms(), ignoreCoord));
                procs.add(xp);
            }
            xml.setProcesses(procs);
        }

        if (!tables.isEmpty()) xml.setTables(tables);
        if (!views.isEmpty()) xml.setViews(views);
        if (!resultsets.isEmpty()) xml.setResultsets(resultsets);

        if (!mgr.edges().isEmpty()) {
            List<XmlRelationship> rels = new ArrayList<>(mgr.edges().size());
            for (BoundEdge e : mgr.edges()) {
                rels.add(toXmlRelationship(e, ignoreCoord));
            }
            xml.setRelationships(rels);
        }

        if (!mgr.errors().isEmpty()) {
            List<XmlError> errs = new ArrayList<>(mgr.errors().size());
            for (ErrorItem err : mgr.errors()) {
                errs.add(new XmlError(
                        err.getErrorType(),
                        fmtCoord(err.getCoordinates(), ignoreCoord),
                        err.getErrorMessage()));
            }
            xml.setErrors(errs);
        }

        xml.setOrientation(Orientation.LEFT_TO_RIGHT);
        if (commentBindings != null) {
            xml.setCommentBindings(commentBindings);
        }
        return xml;
    }

    static Set<String> collectRelationRowObjectIds(List<BoundEdge> edges) {
        Set<String> ids = new HashSet<>();
        for (BoundEdge e : edges) {
            if (e.getTargetCol() != null && "RelationRows".equalsIgnoreCase(e.getTargetCol().getName()) && e.getTargetObj() != null) {
                ids.add(e.getTargetObj().getId());
            }
            for (BoundSourceRef s : e.getSources()) {
                if (s.col() != null && "RelationRows".equalsIgnoreCase(s.col().getName()) && s.obj() != null) {
                    ids.add(s.obj().getId());
                }
            }
        }
        return ids;
    }

    private static XmlProcedure toXmlProcedure(BoundRoutine r, boolean ignoreCoord) {
        XmlProcedure xp = new XmlProcedure();
        xp.setId(r.getId());
        xp.setServer(r.getServer());
        xp.setDatabase(r.getDatabase());
        xp.setSchema(r.getSchema());
        xp.setName(r.getName());
        xp.setType(r.getType());
        xp.setCoordinate(fmtCoord(r.getCoordinates(), ignoreCoord));
        if (!r.getBoundTables().isEmpty()) {
            xp.setBoundTables(String.join(",", r.getBoundTables()));
        }
        if (!r.getArguments().isEmpty()) {
            List<XmlArgument> args = new ArrayList<>(r.getArguments().size());
            for (ArgumentObject a : r.getArguments()) {
                XmlArgument xa = new XmlArgument(a.getId(), a.getName(), a.getDataType(), a.getInout());
                xa.setCoordinate(fmtCoord(a.getCoordinates(), ignoreCoord));
                args.add(xa);
            }
            xp.setArguments(args);
        }
        return xp;
    }

    private static XmlTable toXmlTable(BoundObject obj, boolean includeRelRows, boolean ignoreCoord) {
        XmlTable xt = new XmlTable(obj.getId(), obj.getName(), obj.getType());
        xt.setServer(obj.getServer());
        xt.setDatabase(obj.getDatabase());
        xt.setSchema(obj.getSchema());
        xt.setDisplayName(obj.getDisplayName());
        xt.setAlias(obj.getAlias());
        xt.setUri(obj.getUri());
        xt.setSubType(obj.getSubType());
        xt.setProcessIds(obj.formattedProcessIds());
        xt.setProcedureId(obj.getProcedureId());
        xt.setFileType(obj.getFileType());
        xt.setFileFormat(obj.getFileFormat());
        xt.setLocation(obj.getLocation());
        xt.setDataSourceName(obj.getDataSourceName());
        xt.setNamespace(obj.getNamespace());
        if (obj.isTarget()) {
            xt.setIsTarget("true");
        }
        xt.setCoordinate(fmtCoord(obj.getCoordinates(), ignoreCoord));
        xt.setFromDDL(obj.getFromDDL());
        if (obj.getEndpointKind() != null) {
            xt.setEndpointKind(obj.getEndpointKind().name());
        }
        if (obj.getEndpointIntroduction() != null) {
            xt.setEndpointIntroduction(obj.getEndpointIntroduction().name());
        }
        xt.setCreatedInSql(obj.getCreatedInSql());
        xt.setNormalizedName(obj.getNormalizedName());
        xt.setTemplatedParts(obj.getTemplatedParts());

        List<BoundColumn> cols = obj.orderedColumnsIncludingRelationRows(includeRelRows);
        if (!cols.isEmpty()) {
            List<XmlColumn> xcols = new ArrayList<>(cols.size());
            for (BoundColumn bc : cols) {
                xcols.add(toXmlColumn(bc, ignoreCoord));
            }
            xt.setColumns(xcols);
        }
        if (!obj.getCandidateTables().isEmpty()) {
            List<XmlCandidateTable> cands = new ArrayList<>(obj.getCandidateTables().size());
            for (CandidateTable ct : obj.getCandidateTables()) {
                cands.add(new XmlCandidateTable(ct.getId(), ct.getName()));
            }
            xt.setCandidates(cands);
        }
        return xt;
    }

    private static XmlColumn toXmlColumn(BoundColumn bc, boolean ignoreCoord) {
        XmlColumn xc = new XmlColumn(bc.getId(), bc.getName());
        xc.setDisplayName(bc.getDisplayName());
        xc.setCoordinate(fmtCoord(bc.getCoordinates(), ignoreCoord));
        xc.setSource(bc.getSource());
        xc.setQualifiedTable(bc.getQualifiedTable());
        xc.setIsFunction(bc.getIsFunction());
        xc.setDataType(bc.getDataType());
        xc.setPrimaryKey(bc.getPrimaryKey());
        xc.setUnqiueKey(bc.getUnqiueKey());
        xc.setIndexKey(bc.getIndexKey());
        xc.setForeignKey(bc.getForeignKey());
        xc.setNullable(bc.getNullable());
        xc.setIdentity(bc.getIdentity());
        xc.setComputed(bc.getComputed());
        xc.setPersisted(bc.getPersisted());
        xc.setComputedDefinition(bc.getComputedDefinition());
        xc.setDefaultValue(bc.getDefaultValue());
        xc.setDefaultName(bc.getDefaultName());
        xc.setStar(bc.getStar());
        return xc;
    }

    private static XmlRelationship toXmlRelationship(BoundEdge e, boolean ignoreCoord) {
        XmlRelationship xr = new XmlRelationship();
        xr.setId(e.getId());
        xr.setType(e.getType() != null ? e.getType().wireName() : RelationshipType.fdd.wireName());
        if (e.getEffectType() != null) {
            xr.setEffectType(e.getEffectType().wireName());
        }
        xr.setFunction(e.getFunction());
        xr.setPartition(e.getPartition());
        xr.setClause(e.getClause());
        xr.setJoinType(e.getJoinType());
        xr.setCondition(e.getCondition());
        xr.setProcessId(e.getProcessId());
        xr.setProcessType(e.getProcessType());
        xr.setProcedureId(e.getProcedureId());
        xr.setSqlHash(e.getSqlHash());
        xr.setSqlComment(e.getSqlComment());

        if (e.getType() == RelationshipType.call) {
            if (e.getBuiltIn() != null) {
                xr.setBuiltIn(String.valueOf(e.getBuiltIn()));
            }
            xr.setCallStmt(e.getCallStmt());
            xr.setCallCoordinate(fmtCoord(e.getCallCoordinate(), ignoreCoord));
            xr.setCaller(buildXmlCaller(e, ignoreCoord));
            if (e.getCallees() != null && !e.getCallees().isEmpty()) {
                List<XmlEndpoint> callees = new ArrayList<>(e.getCallees().size());
                for (RelationshipElement re : e.getCallees()) {
                    XmlEndpoint xe = new XmlEndpoint();
                    xe.setId(re.getId());
                    xe.setName(re.getName());
                    xe.setType(re.getType());
                    xe.setCoordinate(fmtCoord(re.getCoordinates(), ignoreCoord));
                    callees.add(xe);
                }
                xr.setCallees(callees);
            }
            return xr;
        }

        if (e.getTargetCol() != null && e.getTargetObj() != null) {
            XmlEndpoint tgt = new XmlEndpoint();
            tgt.setId(e.getTargetCol().getId());
            tgt.setColumn(e.getTargetCol().getName());
            tgt.setParentId(e.getTargetObj().getId());
            tgt.setParentName(e.getTargetObj().getDisplayName());
            tgt.setParentAlias(e.getTargetObj().getAlias());
            tgt.setCoordinate(fmtCoord(e.getTargetCol().getCoordinates(), ignoreCoord));
            tgt.setFunction(e.getFunction());
            xr.setTarget(tgt);
        }
        if (!e.getSources().isEmpty()) {
            List<XmlEndpoint> srcs = new ArrayList<>(e.getSources().size());
            for (BoundSourceRef s : e.getSources()) {
                XmlEndpoint xs = new XmlEndpoint();
                xs.setId(s.col().getId());
                xs.setColumn(s.col().getName());
                xs.setParentId(s.obj().getId());
                xs.setParentName(s.obj().getDisplayName());
                xs.setParentAlias(s.alias() != null ? s.alias() : s.obj().getAlias());
                xs.setClauseType(s.clauseType());
                xs.setCoordinate(fmtCoord(
                        s.coordinates() != null && !s.coordinates().isEmpty() ? s.coordinates() : s.col().getCoordinates(),
                        ignoreCoord));
                xs.setFunction(s.function());
                xs.setValue(s.value());
                xs.setSource(s.col().getSource());
                xs.setTransforms(toXmlTransforms(s.transforms(), ignoreCoord));
                if (s.candidateParents() != null && !s.candidateParents().isEmpty()) {
                    List<XmlCandidateTable> cands = new ArrayList<>(s.candidateParents().size());
                    for (CandidateTable ct : s.candidateParents()) {
                        cands.add(new XmlCandidateTable(ct.getId(), ct.getName()));
                    }
                    xs.setCandidateTables(cands);
                }
                srcs.add(xs);
            }
            xr.setSources(srcs);
        }
        return xr;
    }

    private static XmlEndpoint buildXmlCaller(BoundEdge e, boolean ignoreCoord) {
        XmlEndpoint caller = new XmlEndpoint();
        if (e.getCallerRoutine() != null) {
            caller.setId(e.getCallerRoutine().getId());
            caller.setName(e.getCallerRoutine().getName());
            caller.setType(e.getCallerRoutine().getType());
            caller.setCoordinate(fmtCoord(e.getCallerRoutine().getCoordinates(), ignoreCoord));
        } else if (e.getCallerObj() != null) {
            caller.setId(e.getCallerObj().getId());
            caller.setName(e.getCallerObj().getDisplayName());
            caller.setType(e.getCallerObj().getType());
        } else {
            caller.setId("0");
            caller.setName("SQL_SCRIPT");
            caller.setType("script");
        }
        return caller;
    }

    private static List<XmlTransform> toXmlTransforms(List<Transform> transforms, boolean ignoreCoord) {
        if (transforms == null || transforms.isEmpty()) {
            return null;
        }
        List<XmlTransform> list = new ArrayList<>(transforms.size());
        for (Transform t : transforms) {
            list.add(new XmlTransform(t.getType(), t.getCode(), fmtCoord(t.getCoordinates(), ignoreCoord)));
        }
        return list;
    }

    private static String fmtCoord(List<Coordinate> coords, boolean ignoreCoord) {
        return ignoreCoord || coords == null || coords.isEmpty() ? null : Coordinate.formatXmlCoordinate(coords);
    }
}
