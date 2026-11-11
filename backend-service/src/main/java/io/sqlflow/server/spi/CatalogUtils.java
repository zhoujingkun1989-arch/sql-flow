package io.sqlflow.server.spi;

import io.sqlflow.contracts.graph.SummaryInfo;
import io.sqlflow.contracts.lineage.Column;
import io.sqlflow.contracts.lineage.Coordinate;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.DatabaseObject;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.ErrorItem;
import io.sqlflow.contracts.lineage.PackageObject;
import io.sqlflow.contracts.lineage.ProcedureObject;
import io.sqlflow.contracts.lineage.Relationship;
import io.sqlflow.contracts.lineage.RelationshipElement;
import io.sqlflow.contracts.lineage.SchemaObject;
import io.sqlflow.contracts.lineage.ServerObject;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.contracts.lineage.Transform;
import io.sqlflow.contracts.lineage.xml.DlineageXml;
import io.sqlflow.contracts.lineage.xml.XmlColumn;
import io.sqlflow.contracts.lineage.xml.XmlEndpoint;
import io.sqlflow.contracts.lineage.xml.XmlError;
import io.sqlflow.contracts.lineage.xml.XmlRelationship;
import io.sqlflow.contracts.lineage.xml.XmlTable;
import io.sqlflow.contracts.lineage.xml.XmlTransform;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared catalog traversal, indexing, summary calculation, and XML conversion utilities.
 */
public final class CatalogUtils {

    private CatalogUtils() {
    }

    public static List<DbObject> collectAllDbObjects(DataFlow dataFlow) {
        if (dataFlow == null || dataFlow.getDbobjs() == null) {
            return List.of();
        }
        return collectAllDbObjects(dataFlow.getDbobjs());
    }

    public static List<DbObject> collectAllDbObjects(SqlflowCatalog catalog) {
        if (catalog == null) {
            return List.of();
        }
        Map<String, DbObject> byId = new LinkedHashMap<>();
        if (catalog.getServers() != null) {
            for (ServerObject srv : catalog.getServers()) {
                if (srv == null) {
                    continue;
                }
                if (srv.getDatabases() != null) {
                    for (DatabaseObject db : srv.getDatabases()) {
                        collectFromDatabase(srv.getName(), db, byId);
                    }
                }
                if (srv.getSchemas() != null) {
                    for (SchemaObject sc : srv.getSchemas()) {
                        collectFromSchema(srv.getName(), null, sc, byId);
                    }
                }
            }
        }
        if (catalog.getDatabases() != null) {
            for (DatabaseObject db : catalog.getDatabases()) {
                collectFromDatabase(null, db, byId);
            }
        }
        return new ArrayList<>(byId.values());
    }

    private static void collectFromDatabase(String serverName, DatabaseObject db, Map<String, DbObject> out) {
        if (db == null) {
            return;
        }
        String dbName = db.getName();
        addDbObjectList(serverName, dbName, null, db.getTables(), "table", out);
        addDbObjectList(serverName, dbName, null, db.getViews(), "view", out);
        addDbObjectList(serverName, dbName, null, db.getOthers(), "resultset", out);
        addProcedures(serverName, dbName, null, db.getProcedures(), "procedure", out);
        addProcedures(serverName, dbName, null, db.getFunctions(), "function", out);
        if (db.getPackages() != null) {
            for (PackageObject pkg : db.getPackages()) {
                if (pkg != null) {
                    DbObject dbo = new DbObject(
                            pkg.getId() != null ? pkg.getId() : "pkg_" + out.size(),
                            pkg.getName(),
                            "package");
                    dbo.setServer(serverName);
                    dbo.setDatabase(dbName);
                    out.putIfAbsent(dbo.getId(), dbo);
                }
            }
        }
        if (db.getSchemas() != null) {
            for (SchemaObject sc : db.getSchemas()) {
                collectFromSchema(serverName, dbName, sc, out);
            }
        }
    }

    private static void collectFromSchema(
            String serverName, String dbName, SchemaObject sc, Map<String, DbObject> out) {
        if (sc == null) {
            return;
        }
        String schemaName = sc.getName();
        addDbObjectList(serverName, dbName, schemaName, sc.getTables(), "table", out);
        addDbObjectList(serverName, dbName, schemaName, sc.getViews(), "view", out);
        addDbObjectList(serverName, dbName, schemaName, sc.getOthers(), "resultset", out);
        addProcedures(serverName, dbName, schemaName, sc.getProcedures(), "procedure", out);
        addProcedures(serverName, dbName, schemaName, sc.getFunctions(), "function", out);
        if (sc.getPackages() != null) {
            for (PackageObject pkg : sc.getPackages()) {
                if (pkg != null) {
                    DbObject dbo = new DbObject(
                            pkg.getId() != null ? pkg.getId() : "pkg_" + out.size(),
                            pkg.getName(),
                            "package");
                    dbo.setServer(serverName);
                    dbo.setDatabase(dbName);
                    dbo.setSchema(schemaName);
                    out.putIfAbsent(dbo.getId(), dbo);
                }
            }
        }
    }

    private static void addProcedures(
            String serverName,
            String dbName,
            String schemaName,
            List<ProcedureObject> procs,
            String defaultType,
            Map<String, DbObject> out) {
        if (procs == null) {
            return;
        }
        for (ProcedureObject proc : procs) {
            if (proc == null) {
                continue;
            }
            DbObject dbo = new DbObject(
                    proc.getId() != null ? proc.getId() : "proc_" + out.size(),
                    proc.getName(),
                    proc.getType() != null ? proc.getType() : defaultType);
            dbo.setServer(serverName);
            dbo.setDatabase(dbName);
            dbo.setSchema(schemaName);
            dbo.setCoordinates(proc.getCoordinates());
            out.putIfAbsent(dbo.getId(), dbo);
        }
    }

    private static void addDbObjectList(
            String serverName,
            String dbName,
            String schemaName,
            List<DbObject> list,
            String fallbackType,
            Map<String, DbObject> out) {
        if (list == null) {
            return;
        }
        for (DbObject obj : list) {
            if (obj == null) {
                continue;
            }
            if (obj.getServer() == null && serverName != null) {
                obj.setServer(serverName);
            }
            if (obj.getDatabase() == null && dbName != null) {
                obj.setDatabase(dbName);
            }
            if (obj.getSchema() == null && schemaName != null) {
                obj.setSchema(schemaName);
            }
            if (obj.getType() == null) {
                obj.setType(fallbackType);
            }
            String key = obj.getId() != null
                    ? obj.getId()
                    : (obj.getDatabase() + "." + obj.getSchema() + "." + obj.getName() + "_" + out.size());
            if (obj.getId() == null) {
                obj.setId(key);
            }
            out.putIfAbsent(key, obj);
        }
    }

    public static SqlflowCatalog buildCatalog(String serverName, String vendorName, List<DbObject> objects) {
        SqlflowCatalog catalog = new SqlflowCatalog();
        catalog.setDialect(vendorName);
        String resolvedServer = (serverName != null && !serverName.isBlank()) ? serverName : "default";
        ServerObject server = new ServerObject(resolvedServer, vendorName, Boolean.TRUE, Boolean.TRUE);

        Map<String, Map<String, List<DbObject>>> byDbSchema = new LinkedHashMap<>();
        for (DbObject obj : objects) {
            if (obj == null) {
                continue;
            }
            String db = (obj.getDatabase() != null && !obj.getDatabase().isBlank())
                    ? obj.getDatabase()
                    : "DEFAULT";
            String sc = (obj.getSchema() != null && !obj.getSchema().isBlank())
                    ? obj.getSchema()
                    : "DEFAULT";
            byDbSchema
                    .computeIfAbsent(db, k -> new LinkedHashMap<>())
                    .computeIfAbsent(sc, k -> new ArrayList<>())
                    .add(obj);
        }

        List<DatabaseObject> databases = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<DbObject>>> dbEntry : byDbSchema.entrySet()) {
            DatabaseObject dbObj = new DatabaseObject(dbEntry.getKey());
            List<SchemaObject> schemas = new ArrayList<>();
            for (Map.Entry<String, List<DbObject>> scEntry : dbEntry.getValue().entrySet()) {
                SchemaObject scObj = new SchemaObject(scEntry.getKey());
                List<DbObject> tables = new ArrayList<>();
                List<DbObject> views = new ArrayList<>();
                List<DbObject> others = new ArrayList<>();
                for (DbObject item : scEntry.getValue()) {
                    String t = item.getType() != null ? item.getType().toLowerCase(Locale.ROOT) : "table";
                    if ("view".equals(t) || "materialized view".equals(t)) {
                        views.add(item);
                    } else if ("table".equals(t)) {
                        tables.add(item);
                    } else {
                        others.add(item);
                    }
                }
                if (!tables.isEmpty()) {
                    scObj.setTables(tables);
                }
                if (!views.isEmpty()) {
                    scObj.setViews(views);
                }
                if (!others.isEmpty()) {
                    scObj.setOthers(others);
                }
                schemas.add(scObj);
            }
            dbObj.setSchemas(schemas);
            databases.add(dbObj);
        }
        server.setDatabases(databases);
        catalog.setServers(List.of(server));
        catalog.setDatabases(databases);
        return catalog;
    }

    public static SummaryInfo computeSummary(DataFlow dataFlow) {
        SummaryInfo summary = new SummaryInfo();
        if (dataFlow == null) {
            return summary;
        }
        List<DbObject> allObjects = collectAllDbObjects(dataFlow);
        Set<String> dbs = new LinkedHashSet<>();
        Set<String> schemas = new LinkedHashSet<>();
        int tables = 0;
        int views = 0;
        int columns = 0;
        Map<String, DbObject> objectById = new LinkedHashMap<>();

        for (DbObject obj : allObjects) {
            if (obj.getId() != null) {
                objectById.put(obj.getId(), obj);
            }
            if (obj.getDatabase() != null && !obj.getDatabase().isBlank()) {
                dbs.add(obj.getDatabase().toUpperCase(Locale.ROOT));
            }
            if (obj.getSchema() != null && !obj.getSchema().isBlank()) {
                schemas.add((obj.getDatabase() + "." + obj.getSchema()).toUpperCase(Locale.ROOT));
            }
            String type = obj.getType() != null ? obj.getType().toLowerCase(Locale.ROOT) : "table";
            if ("table".equals(type)) {
                tables++;
            } else if ("view".equals(type) || "materialized view".equals(type)) {
                views++;
            }
            if (obj.getColumns() != null) {
                for (Column col : obj.getColumns()) {
                    if (col != null && !"system".equalsIgnoreCase(col.getSource())) {
                        columns++;
                    }
                }
            }
        }

        List<Relationship> rels = dataFlow.getRelationships() != null ? dataFlow.getRelationships() : List.of();
        Map<String, Integer> tableRelCount = new LinkedHashMap<>();
        for (Relationship rel : rels) {
            if (rel == null) {
                continue;
            }
            Set<String> touchedParentIds = new LinkedHashSet<>();
            if (rel.getTarget() != null && rel.getTarget().getParentId() != null) {
                touchedParentIds.add(rel.getTarget().getParentId());
            }
            if (rel.getSources() != null) {
                for (RelationshipElement src : rel.getSources()) {
                    if (src != null && src.getParentId() != null) {
                        touchedParentIds.add(src.getParentId());
                    }
                }
            }
            for (String pid : touchedParentIds) {
                tableRelCount.merge(pid, 1, Integer::sum);
            }
        }

        List<SummaryInfo.MostRelationTable> topTables = tableRelCount.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(10)
                .map(e -> {
                    DbObject obj = objectById.get(e.getKey());
                    String db = obj != null ? obj.getDatabase() : null;
                    String sc = obj != null ? obj.getSchema() : null;
                    String nm = obj != null ? obj.getName() : e.getKey();
                    return new SummaryInfo.MostRelationTable(db, sc, nm, e.getValue());
                })
                .toList();

        summary.setProcess(dataFlow.getProcesses() != null ? dataFlow.getProcesses().size() : 0);
        summary.setDatabase(dbs.size());
        summary.setSchema(schemas.size());
        summary.setTable(tables);
        summary.setView(views);
        summary.setColumn(columns);
        summary.setRelationship(rels.size());
        summary.setRelationCount(rels.size());
        summary.setDblink(
                dataFlow.getDbobjs() != null && dataFlow.getDbobjs().getDbLinks() != null
                        ? dataFlow.getDbobjs().getDbLinks().size()
                        : 0);
        summary.setMostRelationTables(topTables);
        return summary;
    }

    public static DlineageXml toDlineageXml(DataFlow dataFlow) {
        DlineageXml xml = new DlineageXml();
        if (dataFlow == null) {
            return xml;
        }
        List<XmlTable> tables = new ArrayList<>();
        List<XmlTable> views = new ArrayList<>();
        List<XmlTable> resultsets = new ArrayList<>();

        for (DbObject obj : collectAllDbObjects(dataFlow)) {
            XmlTable xt = new XmlTable(obj.getId(), obj.getName(), obj.getType());
            xt.setServer(obj.getServer());
            xt.setDatabase(obj.getDatabase());
            xt.setSchema(obj.getSchema());
            xt.setDisplayName(obj.getDisplayName());
            xt.setAlias(obj.getAlias());
            xt.setSubType(obj.getSubType());
            xt.setIsTarget(obj.getIsTarget());
            xt.setFromDDL(obj.getFromDDL());
            xt.setCoordinate(Coordinate.formatXmlCoordinate(obj.getCoordinates()));
            if (obj.getColumns() != null && !obj.getColumns().isEmpty()) {
                List<XmlColumn> xCols = new ArrayList<>(obj.getColumns().size());
                for (Column col : obj.getColumns()) {
                    if (col == null) {
                        continue;
                    }
                    XmlColumn xc = new XmlColumn(col.getId(), col.getName());
                    xc.setDisplayName(col.getDisplayName());
                    xc.setDataType(col.getDataType());
                    xc.setSource(col.getSource());
                    xc.setPrimaryKey(col.getPrimaryKey());
                    xc.setUnqiueKey(col.getUnqiueKey());
                    xc.setIndexKey(col.getIndexKey());
                    xc.setForeignKey(col.getForeignKey());
                    xc.setNullable(col.getNullable());
                    xc.setCoordinate(Coordinate.formatXmlCoordinate(col.getCoordinates()));
                    xCols.add(xc);
                }
                xt.setColumns(xCols);
            }
            String t = obj.getType() != null ? obj.getType().toLowerCase(Locale.ROOT) : "table";
            if ("view".equals(t) || "materialized view".equals(t)) {
                views.add(xt);
            } else if ("table".equals(t)) {
                tables.add(xt);
            } else {
                resultsets.add(xt);
            }
        }
        if (!tables.isEmpty()) {
            xml.setTables(tables);
        }
        if (!views.isEmpty()) {
            xml.setViews(views);
        }
        if (!resultsets.isEmpty()) {
            xml.setResultsets(resultsets);
        }

        if (dataFlow.getRelationships() != null && !dataFlow.getRelationships().isEmpty()) {
            List<XmlRelationship> xRels = new ArrayList<>(dataFlow.getRelationships().size());
            for (Relationship rel : dataFlow.getRelationships()) {
                if (rel == null) {
                    continue;
                }
                XmlRelationship xr = new XmlRelationship();
                xr.setId(rel.getId());
                xr.setType(rel.getType());
                xr.setEffectType(rel.getEffectType());
                xr.setClause(rel.getClause());
                xr.setJoinType(rel.getJoinType());
                xr.setCondition(rel.getCondition());
                xr.setSqlHash(rel.getSqlHash());
                xr.setSqlComment(rel.getSqlComment());
                xr.setProcedureId(rel.getProcedureId());
                if (rel.getTarget() != null) {
                    xr.setTarget(toXmlEndpoint(rel.getTarget()));
                }
                if (rel.getCaller() != null) {
                    xr.setCaller(toXmlEndpoint(rel.getCaller()));
                }
                if (rel.getSources() != null && !rel.getSources().isEmpty()) {
                    xr.setSources(rel.getSources().stream().map(CatalogUtils::toXmlEndpoint).toList());
                }
                if (rel.getCallees() != null && !rel.getCallees().isEmpty()) {
                    xr.setCallees(rel.getCallees().stream().map(CatalogUtils::toXmlEndpoint).toList());
                }
                xRels.add(xr);
            }
            xml.setRelationships(xRels);
        }

        if (dataFlow.getErrors() != null && !dataFlow.getErrors().isEmpty()) {
            List<XmlError> xErrs = new ArrayList<>();
            for (ErrorItem err : dataFlow.getErrors()) {
                if (err != null) {
                    XmlError xe = new XmlError();
                    xe.setErrorType(err.getErrorType());
                    xe.setErrorMessage(err.getErrorMessage());
                    xe.setCoordinate(Coordinate.formatXmlCoordinate(err.getCoordinates()));
                    xErrs.add(xe);
                }
            }
            xml.setErrors(xErrs);
        }
        xml.setOrientation(dataFlow.getOrientation());
        return xml;
    }

    private static XmlEndpoint toXmlEndpoint(RelationshipElement el) {
        XmlEndpoint xe = new XmlEndpoint();
        xe.setId(el.getId());
        xe.setColumn(el.getColumn());
        xe.setName(el.getName());
        xe.setColumnType(el.getColumnType());
        xe.setParentId(el.getParentId());
        xe.setParentName(el.getParentName());
        xe.setParentAlias(el.getParentAlias());
        xe.setSourceId(el.getSourceId());
        xe.setSourceName(el.getSourceName());
        xe.setTargetId(el.getTargetId());
        xe.setTargetName(el.getTargetName());
        xe.setClauseType(el.getClauseType());
        xe.setCoordinate(Coordinate.formatXmlCoordinate(el.getCoordinates()));
        if (el.getTransforms() != null && !el.getTransforms().isEmpty()) {
            List<XmlTransform> xTransforms = new ArrayList<>();
            for (Transform tr : el.getTransforms()) {
                if (tr != null) {
                    XmlTransform xt = new XmlTransform();
                    xt.setType(tr.getType());
                    xt.setCode(tr.getCode());
                    xt.setCoordinate(Coordinate.formatXmlCoordinate(tr.getCoordinates()));
                    xTransforms.add(xt);
                }
            }
            xe.setTransforms(xTransforms);
        }
        return xe;
    }

    public static String stripQuotes(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        while (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"')
                    || (first == '\'' && last == '\'')
                    || (first == '`' && last == '`')
                    || (first == '[' && last == ']')) {
                s = s.substring(1, s.length() - 1).trim();
            } else {
                break;
            }
        }
        return s;
    }
}
