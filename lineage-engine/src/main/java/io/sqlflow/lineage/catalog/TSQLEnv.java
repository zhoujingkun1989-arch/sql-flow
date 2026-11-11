package io.sqlflow.lineage.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.sqlflow.contracts.lineage.ArgumentObject;
import io.sqlflow.contracts.lineage.Column;
import io.sqlflow.contracts.lineage.DatabaseObject;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.Option;
import io.sqlflow.contracts.lineage.PackageObject;
import io.sqlflow.contracts.lineage.ProcedureObject;
import io.sqlflow.contracts.lineage.SchemaObject;
import io.sqlflow.contracts.lineage.SequenceObject;
import io.sqlflow.contracts.lineage.ServerObject;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.contracts.lineage.SynonymObject;
import io.sqlflow.contracts.vendor.DataObjectType;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.contracts.vendor.IdentifierProfile;
import io.sqlflow.contracts.vendor.IdentifierRule;
import io.sqlflow.lineage.ast.QualifiedName;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Multi-dialect {@code TSQLEnv} catalog environment ({@code 03 §2.2}, {@code OBL-036..OBL-038, OBL-050}).
 *
 * <p>Stores normalized metadata for servers, databases/catalogs, schemas, tables, views, columns,
 * PK/UK/FK constraints, synonyms, packages, procedures/functions, and sequences, respecting each
 * dialect's {@link IdentifierProfile} case-folding and case-comparison rules.
 */
public final class TSQLEnv {

    public static final String DEFAULT_SERVER_NAME = "DEFAULT_SERVER";
    public static final String DEFAULT_DATABASE_NAME = "DEFAULT_DATABASE";
    public static final String DEFAULT_SCHEMA_NAME = "DEFAULT_SCHEMA";

    private final EDbVendor vendor;
    private final IdentifierProfile profile;

    private String currentServer;
    private String currentDatabase;
    private String currentSchema;

    private final Map<String, CatalogTable> tables = new LinkedHashMap<>();
    private final Map<String, CatalogSynonym> synonyms = new LinkedHashMap<>();
    private final Map<String, CatalogRoutine> routines = new LinkedHashMap<>();
    private final Map<String, CatalogSequence> sequences = new LinkedHashMap<>();

    public TSQLEnv(EDbVendor vendor, IdentifierProfile customProfile, Option option) {
        this.vendor = vendor != null ? vendor : EDbVendor.dbvoracle;
        this.profile = IdentifierProfile.forVendorOr(customProfile, this.vendor);
        if (option != null) {
            this.currentServer = trimToNull(option.getDefaultServer());
            this.currentDatabase = trimToNull(option.getDefaultDatabase());
            this.currentSchema = trimToNull(option.getDefaultSchema());
        }
    }

    public TSQLEnv(EDbVendor vendor) {
        this(vendor, null, null);
    }

    public EDbVendor vendor() {
        return vendor;
    }

    public IdentifierProfile profile() {
        return profile;
    }

    public String currentServer() {
        return currentServer;
    }

    public void setCurrentServer(String currentServer) {
        this.currentServer = trimToNull(currentServer);
    }

    public String currentDatabase() {
        return currentDatabase;
    }

    public void setCurrentDatabase(String currentDatabase) {
        this.currentDatabase = trimToNull(currentDatabase);
    }

    public String currentSchema() {
        return currentSchema;
    }

    public void setCurrentSchema(String currentSchema) {
        this.currentSchema = trimToNull(currentSchema);
    }

    public void applyUseContext(String contextKind, QualifiedName target) {
        if (target == null || target.isEmpty()) {
            return;
        }
        String kind = contextKind == null ? "DATABASE" : contextKind.toUpperCase(Locale.ROOT);
        if (kind.contains("SCHEMA")) {
            if (target.parts().size() >= 2) {
                this.currentDatabase = normalizePart(
                        DataObjectType.CATALOG, target.parts().get(0).value(), target.parts().get(0).quoted());
                this.currentSchema = normalizePart(
                        DataObjectType.SCHEMA, target.parts().get(1).value(), target.parts().get(1).quoted());
            } else {
                if (profile.metadataSupportsSchemas()) {
                    this.currentSchema = normalizePart(DataObjectType.SCHEMA, target.lastName(), target.lastQuoted());
                } else {
                    this.currentDatabase = normalizePart(DataObjectType.CATALOG, target.lastName(), target.lastQuoted());
                }
            }
        } else if (kind.contains("WAREHOUSE") || kind.contains("ROLE")) {
            // Warehouse/Role switches do not alter database/schema namespace
        } else {
            // USE <db> or SET CATALOG <db>
            if (profile.metadataSupportsCatalogs()) {
                this.currentDatabase = normalizePart(DataObjectType.CATALOG, target.lastName(), target.lastQuoted());
            } else if (profile.metadataSupportsSchemas()) {
                this.currentSchema = normalizePart(DataObjectType.SCHEMA, target.lastName(), target.lastQuoted());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Identifier Normalization & Lookup Keys
    // -------------------------------------------------------------------------

    public String normalizePart(DataObjectType type, String raw, boolean quoted) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String clean = quoted ? profile.stripQuotes(raw) : raw;
        IdentifierRule rule = profile.ruleFor(type);
        return rule.normalize(clean, quoted, profile.maxUtf8Bytes());
    }

    public String lookupKey(DataObjectType type, String raw, boolean quoted) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String clean = quoted ? profile.stripQuotes(raw) : raw;
        IdentifierRule rule = profile.ruleFor(type);
        String key = rule.canonicalLookupKey(clean, quoted, profile.maxUtf8Bytes());
        return key != null ? key : "";
    }

    public boolean identifiersMatch(DataObjectType type, String left, boolean leftQuoted, String right, boolean rightQuoted) {
        if (left == null || right == null) {
            return false;
        }
        return profile.ruleFor(type).matches(left, leftQuoted, right, rightQuoted, profile.maxUtf8Bytes());
    }

    public ResolvedCoordinates resolveNameParts(QualifiedName qname) {
        if (qname == null || qname.isEmpty()) {
            return new ResolvedCoordinates(currentServer, currentDatabase, currentSchema, "", false, false, false, false);
        }
        if (qname.uriRef() || qname.stageRef()) {
            return new ResolvedCoordinates(
                    currentServer,
                    currentDatabase,
                    currentSchema,
                    qname.lastName(),
                    false,
                    false,
                    false,
                    false);
        }

        List<QualifiedName.NamePart> parts = qname.parts();
        int n = parts.size();
        String rawSrv = null;
        String rawDb = null;
        String rawSch = null;
        String rawObj = parts.get(n - 1).value();
        boolean qSrv = false;
        boolean qDb = false;
        boolean qSch = false;
        boolean qObj = parts.get(n - 1).quoted();

        if (n >= 4) {
            rawSrv = parts.get(n - 4).value();
            qSrv = parts.get(n - 4).quoted();
            rawDb = parts.get(n - 3).value();
            qDb = parts.get(n - 3).quoted();
            rawSch = parts.get(n - 2).value();
            qSch = parts.get(n - 2).quoted();
        } else if (n == 3) {
            rawDb = parts.get(0).value();
            qDb = parts.get(0).quoted();
            rawSch = parts.get(1).value();
            qSch = parts.get(1).quoted();
        } else if (n == 2) {
            String p0 = parts.get(0).value();
            boolean q0 = parts.get(0).quoted();
            if (!profile.metadataSupportsSchemas() && profile.metadataSupportsCatalogs()) {
                // MySQL, Hive, Impala, Teradata: db.table
                rawDb = p0;
                qDb = q0;
            } else {
                // Oracle, PostgreSQL, Snowflake, MSSQL, BigQuery (2-part dataset.table): schema.table
                rawSch = p0;
                qSch = q0;
            }
        }

        String normSrv = rawSrv != null ? normalizePart(DataObjectType.CATALOG, rawSrv, qSrv) : currentServer;
        String normDb = rawDb != null ? normalizePart(DataObjectType.CATALOG, rawDb, qDb) : currentDatabase;
        String normSch = rawSch != null ? normalizePart(DataObjectType.SCHEMA, rawSch, qSch) : currentSchema;
        String normObj = normalizePart(DataObjectType.TABLE, rawObj, qObj);

        return new ResolvedCoordinates(
                normSrv,
                normDb,
                normSch,
                normObj,
                rawDb != null,
                rawSch != null,
                qObj,
                qname.templated());
    }

    private String compositeTableKey(String db, String schema, String tableName, boolean quotedTable) {
        String dbKey = db != null ? lookupKey(DataObjectType.CATALOG, db, false) : "";
        String schKey = schema != null ? lookupKey(DataObjectType.SCHEMA, schema, false) : "";
        String tblKey = lookupKey(DataObjectType.TABLE, tableName, quotedTable);
        return dbKey + "|" + schKey + "|" + tblKey;
    }

    // -------------------------------------------------------------------------
    // Table & View Registration / Lookup
    // -------------------------------------------------------------------------

    public CatalogTable registerTable(
            String server,
            String database,
            String schema,
            String tableName,
            boolean quotedTable,
            String objectType,
            boolean fromDdl) {
        String normDb = database != null ? normalizePart(DataObjectType.CATALOG, database, false) : currentDatabase;
        String normSch = schema != null ? normalizePart(DataObjectType.SCHEMA, schema, false) : currentSchema;
        String normTbl = normalizePart(DataObjectType.TABLE, tableName, quotedTable);
        if (normTbl == null || normTbl.isEmpty()) {
            return null;
        }
        String key = compositeTableKey(normDb, normSch, normTbl, quotedTable);
        CatalogTable existing = tables.get(key);
        if (existing != null) {
            if (fromDdl) {
                existing.setFromDdl(true);
            }
            if (objectType != null && !objectType.isBlank()) {
                existing.setObjectType(objectType);
            }
            return existing;
        }
        CatalogTable created = new CatalogTable(
                server != null ? server : currentServer,
                normDb,
                normSch,
                normTbl,
                quotedTable,
                objectType != null ? objectType : "table",
                fromDdl);
        tables.put(key, created);
        return created;
    }

    public CatalogTable registerTable(QualifiedName qname, String objectType, boolean fromDdl) {
        ResolvedCoordinates rc = resolveNameParts(qname);
        return registerTable(
                rc.server(),
                rc.database(),
                rc.schema(),
                rc.objectName(),
                rc.quotedObject(),
                objectType,
                fromDdl);
    }

    public void addOrUpdateColumn(CatalogTable table, CatalogColumn col, boolean quotedCol) {
        if (table == null || col == null || col.getName() == null || col.getName().isBlank()) {
            return;
        }
        String normCol = normalizePart(DataObjectType.COLUMN, col.getName(), quotedCol);
        col.setName(normCol);
        String colKey = lookupKey(DataObjectType.COLUMN, normCol, quotedCol);
        table.putColumn(colKey, col);
    }

    public CatalogTable resolveTable(QualifiedName qname) {
        if (qname == null || qname.isEmpty()) {
            return null;
        }
        return resolveTableInternal(qname, new LinkedHashSet<>());
    }

    private CatalogTable resolveTableInternal(QualifiedName qname, Set<String> visitedSynonyms) {
        ResolvedCoordinates rc = resolveNameParts(qname);
        String exactKey = compositeTableKey(rc.database(), rc.schema(), rc.objectName(), rc.quotedObject());
        CatalogTable direct = tables.get(exactKey);
        if (direct != null) {
            return direct;
        }

        // Check synonym chain before fallback
        CatalogSynonym syn = findSynonym(rc.database(), rc.schema(), rc.objectName(), rc.quotedObject());
        if (syn != null && visitedSynonyms.size() < 16) {
            String synKey = compositeTableKey(syn.getDatabase(), syn.getSchema(), syn.getName(), false);
            if (visitedSynonyms.add(synKey)) {
                List<String> targetParts = new ArrayList<>(3);
                if (syn.getTargetDatabase() != null && !syn.getTargetDatabase().isBlank()) {
                    targetParts.add(syn.getTargetDatabase());
                }
                if (syn.getTargetSchema() != null && !syn.getTargetSchema().isBlank()) {
                    targetParts.add(syn.getTargetSchema());
                }
                targetParts.add(syn.getTargetName());
                QualifiedName synTarget = QualifiedName.ofParts(targetParts, qname.span());
                CatalogTable resolvedSyn = resolveTableInternal(synTarget, visitedSynonyms);
                if (resolvedSyn != null) {
                    return resolvedSyn;
                }
            }
        }

        // Fallback search when database or schema was omitted in query
        String targetTblKey = lookupKey(DataObjectType.TABLE, rc.objectName(), rc.quotedObject());
        String reqDbKey = rc.explicitDatabase() && rc.database() != null
                ? lookupKey(DataObjectType.CATALOG, rc.database(), false)
                : null;
        String reqSchKey = rc.explicitSchema() && rc.schema() != null
                ? lookupKey(DataObjectType.SCHEMA, rc.schema(), false)
                : null;

        List<CatalogTable> matches = new ArrayList<>(2);
        for (CatalogTable candidate : tables.values()) {
            String candTblKey = lookupKey(DataObjectType.TABLE, candidate.getName(), candidate.isQuoted());
            if (!candTblKey.equals(targetTblKey)) {
                continue;
            }
            if (reqDbKey != null) {
                String candDbKey = candidate.getDatabase() != null
                        ? lookupKey(DataObjectType.CATALOG, candidate.getDatabase(), false)
                        : "";
                if (!reqDbKey.equals(candDbKey)) {
                    continue;
                }
            }
            if (reqSchKey != null) {
                String candSchKey = candidate.getSchema() != null
                        ? lookupKey(DataObjectType.SCHEMA, candidate.getSchema(), false)
                        : "";
                if (!reqSchKey.equals(candSchKey)) {
                    continue;
                }
            }
            matches.add(candidate);
        }

        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.size() > 1) {
            // Prefer current session schema/database if one matches
            for (CatalogTable cand : matches) {
                boolean dbMatch = currentDatabase == null
                        || (cand.getDatabase() != null && identifiersMatch(DataObjectType.CATALOG, currentDatabase, false, cand.getDatabase(), false));
                boolean schMatch = currentSchema == null
                        || (cand.getSchema() != null && identifiersMatch(DataObjectType.SCHEMA, currentSchema, false, cand.getSchema(), false));
                if (dbMatch && schMatch) {
                    return cand;
                }
            }
            return matches.get(0);
        }
        return null;
    }

    public CatalogColumn resolveColumn(CatalogTable table, String colName, boolean quotedCol) {
        if (table == null || colName == null) {
            return null;
        }
        String key = lookupKey(DataObjectType.COLUMN, colName, quotedCol);
        return table.getColumnsMap().get(key);
    }

    /**
     * Finds which candidate {@link CatalogTable}s declare {@code colName}.
     */
    public List<CatalogTable> findTablesContainingColumn(
            Collection<CatalogTable> candidates,
            String colName,
            boolean quotedCol) {
        if (candidates == null || candidates.isEmpty() || colName == null) {
            return List.of();
        }
        List<CatalogTable> found = new ArrayList<>(2);
        for (CatalogTable tbl : candidates) {
            if (tbl != null && resolveColumn(tbl, colName, quotedCol) != null) {
                found.add(tbl);
            }
        }
        return found;
    }

    public boolean dropTable(QualifiedName qname) {
        ResolvedCoordinates rc = resolveNameParts(qname);
        String key = compositeTableKey(rc.database(), rc.schema(), rc.objectName(), rc.quotedObject());
        return tables.remove(key) != null;
    }

    public void renameTable(QualifiedName oldName, QualifiedName newName) {
        CatalogTable existing = resolveTable(oldName);
        if (existing == null || newName == null || newName.isEmpty()) {
            return;
        }
        String oldKey = compositeTableKey(existing.getDatabase(), existing.getSchema(), existing.getName(), existing.isQuoted());
        tables.remove(oldKey);
        ResolvedCoordinates newRc = resolveNameParts(newName);
        if (newRc.explicitDatabase()) {
            existing.setDatabase(newRc.database());
        }
        if (newRc.explicitSchema()) {
            existing.setSchema(newRc.schema());
        }
        existing.setName(newRc.objectName());
        existing.setQuoted(newRc.quotedObject());
        String newKey = compositeTableKey(existing.getDatabase(), existing.getSchema(), existing.getName(), existing.isQuoted());
        tables.put(newKey, existing);
    }

    // -------------------------------------------------------------------------
    // Synonyms, Routines, Sequences
    // -------------------------------------------------------------------------

    public void registerSynonym(CatalogSynonym synonym) {
        if (synonym == null || synonym.getName() == null) {
            return;
        }
        String key = compositeTableKey(synonym.getDatabase(), synonym.getSchema(), synonym.getName(), false);
        synonyms.put(key, synonym);
    }

    public CatalogSynonym findSynonym(String db, String schema, String name, boolean quoted) {
        String exact = compositeTableKey(db, schema, name, quoted);
        CatalogSynonym s = synonyms.get(exact);
        if (s != null) {
            return s;
        }
        String nameKey = lookupKey(DataObjectType.TABLE, name, quoted);
        for (CatalogSynonym cand : synonyms.values()) {
            if (lookupKey(DataObjectType.TABLE, cand.getName(), false).equals(nameKey)) {
                return cand;
            }
        }
        return null;
    }

    public CatalogSynonym resolveSynonym(QualifiedName qname) {
        if (qname == null || qname.isEmpty()) {
            return null;
        }
        ResolvedCoordinates rc = resolveNameParts(qname);
        return findSynonym(rc.database(), rc.schema(), rc.objectName(), rc.quotedObject());
    }

    public void registerRoutine(CatalogRoutine routine) {
        if (routine == null || routine.getName() == null) {
            return;
        }
        String dbKey = routine.getDatabase() != null ? lookupKey(DataObjectType.CATALOG, routine.getDatabase(), false) : "";
        String schKey = routine.getSchema() != null ? lookupKey(DataObjectType.SCHEMA, routine.getSchema(), false) : "";
        String pkgKey = routine.getPackageName() != null ? lookupKey(DataObjectType.PROCEDURE, routine.getPackageName(), false) : "";
        String procKey = lookupKey(DataObjectType.PROCEDURE, routine.getName(), false);
        routines.put(dbKey + "|" + schKey + "|" + pkgKey + "|" + procKey, routine);
    }

    public CatalogRoutine resolveRoutine(QualifiedName qname) {
        if (qname == null || qname.isEmpty()) {
            return null;
        }
        List<QualifiedName.NamePart> parts = qname.parts();
        String procName = parts.get(parts.size() - 1).value();
        String procKey = lookupKey(DataObjectType.PROCEDURE, procName, qname.lastQuoted());
        String pkgOrSch = parts.size() >= 2 ? parts.get(parts.size() - 2).value() : null;

        for (CatalogRoutine cand : routines.values()) {
            if (!lookupKey(DataObjectType.PROCEDURE, cand.getName(), false).equals(procKey)) {
                continue;
            }
            if (pkgOrSch != null) {
                boolean matchesPkg = cand.getPackageName() != null
                        && lookupKey(DataObjectType.PROCEDURE, cand.getPackageName(), false)
                                .equals(lookupKey(DataObjectType.PROCEDURE, pkgOrSch, false));
                boolean matchesSch = cand.getSchema() != null
                        && lookupKey(DataObjectType.SCHEMA, cand.getSchema(), false)
                                .equals(lookupKey(DataObjectType.SCHEMA, pkgOrSch, false));
                if (matchesPkg || matchesSch) {
                    return cand;
                }
            } else {
                return cand;
            }
        }
        return null;
    }

    public void registerSequence(CatalogSequence sequence) {
        if (sequence == null || sequence.name() == null) {
            return;
        }
        String key = compositeTableKey(sequence.database(), sequence.schema(), sequence.name(), false);
        sequences.put(key, sequence);
    }

    public Collection<CatalogTable> allTables() {
        return Collections.unmodifiableCollection(tables.values());
    }

    public Collection<CatalogSynonym> allSynonyms() {
        return Collections.unmodifiableCollection(synonyms.values());
    }

    public Collection<CatalogRoutine> allRoutines() {
        return Collections.unmodifiableCollection(routines.values());
    }

    public Collection<CatalogSequence> allSequences() {
        return Collections.unmodifiableCollection(sequences.values());
    }

    // -------------------------------------------------------------------------
    // Populate from SqlflowCatalog (initialCatalog / Exporter JSON / Grabit JSON)
    // -------------------------------------------------------------------------

    public void populateFromSqlflowCatalog(SqlflowCatalog catalog) {
        if (catalog == null) {
            return;
        }
        if (catalog.getServers() != null) {
            for (ServerObject srv : catalog.getServers()) {
                if (srv == null) {
                    continue;
                }
                String srvName = trimToNull(srv.getName());
                if (srv.getDatabases() != null) {
                    for (DatabaseObject db : srv.getDatabases()) {
                        importDatabaseObject(srvName, db);
                    }
                }
                if (srv.getSchemas() != null) {
                    for (SchemaObject sch : srv.getSchemas()) {
                        importSchemaObject(srvName, null, sch);
                    }
                }
            }
        }
        if (catalog.getDatabases() != null) {
            for (DatabaseObject db : catalog.getDatabases()) {
                importDatabaseObject(currentServer, db);
            }
        }
    }

    private void importDatabaseObject(String srvName, DatabaseObject db) {
        if (db == null) {
            return;
        }
        String dbName = trimToNull(db.getName());
        if (db.getSchemas() != null) {
            for (SchemaObject sch : db.getSchemas()) {
                importSchemaObject(srvName, dbName, sch);
            }
        }
        importContainerLists(
                srvName,
                dbName,
                null,
                db.getTables(),
                db.getViews(),
                db.getOthers(),
                db.getSynonyms(),
                db.getPackages(),
                db.getProcedures(),
                db.getFunctions(),
                db.getTriggers(),
                db.getSequences());
    }

    private void importSchemaObject(String srvName, String dbName, SchemaObject sch) {
        if (sch == null) {
            return;
        }
        String schName = trimToNull(sch.getName());
        importContainerLists(
                srvName,
                dbName,
                schName,
                sch.getTables(),
                sch.getViews(),
                sch.getOthers(),
                sch.getSynonyms(),
                sch.getPackages(),
                sch.getProcedures(),
                sch.getFunctions(),
                sch.getTriggers(),
                sch.getSequences());
    }

    private void importContainerLists(
            String srvName,
            String dbName,
            String schName,
            List<DbObject> tblList,
            List<DbObject> viewList,
            List<DbObject> otherList,
            List<SynonymObject> synList,
            List<PackageObject> pkgList,
            List<ProcedureObject> procList,
            List<ProcedureObject> funcList,
            List<ProcedureObject> trigList,
            List<SequenceObject> seqList) {
        if (tblList != null) {
            for (DbObject t : tblList) {
                importDbObject(srvName, dbName, schName, t, "table");
            }
        }
        if (viewList != null) {
            for (DbObject v : viewList) {
                importDbObject(srvName, dbName, schName, v, "view");
            }
        }
        if (otherList != null) {
            for (DbObject o : otherList) {
                if (o != null && o.getType() != null
                        && Set.of("stage", "stream", "datasource", "table", "view").contains(o.getType().toLowerCase(Locale.ROOT))) {
                    importDbObject(srvName, dbName, schName, o, o.getType());
                }
            }
        }
        if (synList != null) {
            for (SynonymObject s : synList) {
                if (s == null || s.getName() == null) {
                    continue;
                }
                registerSynonym(new CatalogSynonym(
                        srvName,
                        firstNonBlank(s.getDatabase(), dbName),
                        firstNonBlank(s.getSchema(), schName),
                        s.getName(),
                        s.getSourceServer(),
                        s.getSourceDatabase(),
                        s.getSourceSchema(),
                        s.getSourceName(),
                        s.getSourceDbLinkName()));
            }
        }
        if (pkgList != null) {
            for (PackageObject pkg : pkgList) {
                if (pkg == null || pkg.getName() == null) {
                    continue;
                }
                if (pkg.getProcedures() != null) {
                    for (ProcedureObject p : pkg.getProcedures()) {
                        importProcedureObject(srvName, dbName, schName, pkg.getName(), p, "procedure");
                    }
                }
                if (pkg.getFunctions() != null) {
                    for (ProcedureObject f : pkg.getFunctions()) {
                        importProcedureObject(srvName, dbName, schName, pkg.getName(), f, "function");
                    }
                }
            }
        }
        if (procList != null) {
            for (ProcedureObject p : procList) {
                importProcedureObject(srvName, dbName, schName, null, p, "procedure");
            }
        }
        if (funcList != null) {
            for (ProcedureObject f : funcList) {
                importProcedureObject(srvName, dbName, schName, null, f, "function");
            }
        }
        if (trigList != null) {
            for (ProcedureObject tr : trigList) {
                importProcedureObject(srvName, dbName, schName, null, tr, "trigger");
            }
        }
        if (seqList != null) {
            for (SequenceObject seq : seqList) {
                if (seq != null && seq.getName() != null) {
                    registerSequence(new CatalogSequence(srvName, dbName, schName, seq.getName()));
                }
            }
        }
    }

    private void importDbObject(String srvName, String dbName, String schName, DbObject obj, String defaultType) {
        if (obj == null || obj.getName() == null || obj.getName().isBlank()) {
            return;
        }
        String effDb = firstNonBlank(obj.getDatabase(), dbName);
        String effSch = firstNonBlank(obj.getSchema(), schName);
        if (DEFAULT_DATABASE_NAME.equalsIgnoreCase(effDb)) {
            effDb = currentDatabase;
        }
        if (DEFAULT_SCHEMA_NAME.equalsIgnoreCase(effSch)) {
            effSch = currentSchema;
        }
        CatalogTable ct = registerTable(
                firstNonBlank(obj.getServer(), srvName),
                effDb,
                effSch,
                obj.getName(),
                false,
                firstNonBlank(obj.getType(), defaultType),
                Boolean.TRUE.equals(obj.getFromDDL()));
        if (ct != null && obj.getColumns() != null) {
            for (Column c : obj.getColumns()) {
                if (c == null || c.getName() == null || "RelationRows".equalsIgnoreCase(c.getName())) {
                    continue;
                }
                CatalogColumn cc = new CatalogColumn(c.getName(), c.getDataType());
                cc.setPrimaryKey(c.getPrimaryKey());
                cc.setUnqiueKey(c.getUnqiueKey());
                cc.setIndexKey(c.getIndexKey());
                cc.setForeignKey(c.getForeignKey());
                cc.setNullable(c.getNullable());
                cc.setIdentity(c.getIdentity());
                cc.setComputed(c.getComputed());
                cc.setPersisted(c.getPersisted());
                cc.setComputedDefinition(c.getComputedDefinition());
                cc.setDefaultValue(c.getDefaultValue());
                cc.setDefaultName(c.getDefaultName());
                cc.setComment(c.getComment());
                addOrUpdateColumn(ct, cc, false);
            }
        }
    }

    private void importProcedureObject(
            String srvName,
            String dbName,
            String schName,
            String pkgName,
            ProcedureObject p,
            String defaultType) {
        if (p == null || p.getName() == null) {
            return;
        }
        List<CatalogArgument> args = new ArrayList<>();
        if (p.getArguments() != null) {
            for (ArgumentObject a : p.getArguments()) {
                if (a != null) {
                    args.add(new CatalogArgument(a.getName(), a.getDataType(), a.getInout()));
                }
            }
        }
        registerRoutine(new CatalogRoutine(
                srvName,
                dbName,
                schName,
                pkgName,
                p.getName(),
                firstNonBlank(p.getType(), defaultType),
                null,
                false,
                List.of(),
                args,
                null));
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String firstNonBlank(String a, String b) {
        String ta = trimToNull(a);
        return ta != null ? ta : trimToNull(b);
    }

    // -------------------------------------------------------------------------
    // Catalog Value Models (Jackson-serializable for sqlenv.bin snapshot)
    // -------------------------------------------------------------------------

    public record ResolvedCoordinates(
            String server,
            String database,
            String schema,
            String objectName,
            boolean explicitDatabase,
            boolean explicitSchema,
            boolean quotedObject,
            boolean templated
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class CatalogTable {
        private String server;
        private String database;
        private String schema;
        private String name;
        private boolean quoted;
        private String objectType;
        private boolean fromDdl;
        private String viewSql;
        private LinkedHashMap<String, CatalogColumn> columns = new LinkedHashMap<>();
        private List<CatalogForeignKey> foreignKeys = new ArrayList<>();

        public CatalogTable() {
        }

        public CatalogTable(
                String server,
                String database,
                String schema,
                String name,
                boolean quoted,
                String objectType,
                boolean fromDdl) {
            this.server = server;
            this.database = database;
            this.schema = schema;
            this.name = name;
            this.quoted = quoted;
            this.objectType = objectType;
            this.fromDdl = fromDdl;
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

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isQuoted() {
            return quoted;
        }

        public void setQuoted(boolean quoted) {
            this.quoted = quoted;
        }

        public String getObjectType() {
            return objectType;
        }

        public void setObjectType(String objectType) {
            this.objectType = objectType;
        }

        public boolean isFromDdl() {
            return fromDdl;
        }

        public void setFromDdl(boolean fromDdl) {
            this.fromDdl = fromDdl;
        }

        public String getViewSql() {
            return viewSql;
        }

        public void setViewSql(String viewSql) {
            this.viewSql = viewSql;
        }

        public LinkedHashMap<String, CatalogColumn> getColumnsMap() {
            return columns;
        }

        public void setColumnsMap(LinkedHashMap<String, CatalogColumn> columns) {
            this.columns = columns != null ? columns : new LinkedHashMap<>();
        }

        public List<CatalogColumn> orderedColumns() {
            return List.copyOf(columns.values());
        }

        public void putColumn(String key, CatalogColumn col) {
            CatalogColumn prev = columns.get(key);
            if (prev == null) {
                columns.put(key, col);
            } else {
                prev.mergeFrom(col);
            }
        }

        public List<CatalogForeignKey> getForeignKeys() {
            return foreignKeys;
        }

        public void setForeignKeys(List<CatalogForeignKey> foreignKeys) {
            this.foreignKeys = foreignKeys != null ? foreignKeys : new ArrayList<>();
        }

        public void addForeignKey(CatalogForeignKey fk) {
            if (fk != null) {
                this.foreignKeys.add(fk);
            }
        }

        public String qualifiedDisplayName() {
            StringBuilder sb = new StringBuilder();
            if (database != null && !database.isBlank()) {
                sb.append(database).append('.');
            }
            if (schema != null && !schema.isBlank()) {
                sb.append(schema).append('.');
            }
            sb.append(name);
            return sb.toString();
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class CatalogColumn {
        private String name;
        private String dataType;
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
        private String comment;

        public CatalogColumn() {
        }

        public CatalogColumn(String name, String dataType) {
            this.name = name;
            this.dataType = dataType;
        }

        public void mergeFrom(CatalogColumn other) {
            if (other == null) {
                return;
            }
            if (other.dataType != null) {
                this.dataType = other.dataType;
            }
            if (Boolean.TRUE.equals(other.primaryKey)) {
                this.primaryKey = Boolean.TRUE;
            }
            if (Boolean.TRUE.equals(other.unqiueKey)) {
                this.unqiueKey = Boolean.TRUE;
            }
            if (Boolean.TRUE.equals(other.indexKey)) {
                this.indexKey = Boolean.TRUE;
            }
            if (Boolean.TRUE.equals(other.foreignKey)) {
                this.foreignKey = Boolean.TRUE;
            }
            if (other.nullable != null) {
                this.nullable = other.nullable;
            }
            if (Boolean.TRUE.equals(other.identity)) {
                this.identity = Boolean.TRUE;
            }
            if (Boolean.TRUE.equals(other.computed)) {
                this.computed = Boolean.TRUE;
            }
            if (other.persisted != null) {
                this.persisted = other.persisted;
            }
            if (other.computedDefinition != null) {
                this.computedDefinition = other.computedDefinition;
            }
            if (other.defaultValue != null) {
                this.defaultValue = other.defaultValue;
            }
            if (other.defaultName != null) {
                this.defaultName = other.defaultName;
            }
            if (other.comment != null) {
                this.comment = other.comment;
            }
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDataType() {
            return dataType;
        }

        public void setDataType(String dataType) {
            this.dataType = dataType;
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

        public String getComment() {
            return comment;
        }

        public void setComment(String comment) {
            this.comment = comment;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CatalogForeignKey(
            String constraintName,
            List<String> sourceColumns,
            String refDatabase,
            String refSchema,
            String refTable,
            List<String> refColumns
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class CatalogSynonym {
        private String server;
        private String database;
        private String schema;
        private String name;
        private String targetServer;
        private String targetDatabase;
        private String targetSchema;
        private String targetName;
        private String dbLink;

        public CatalogSynonym() {
        }

        public CatalogSynonym(
                String server,
                String database,
                String schema,
                String name,
                String targetServer,
                String targetDatabase,
                String targetSchema,
                String targetName,
                String dbLink) {
            this.server = server;
            this.database = database;
            this.schema = schema;
            this.name = name;
            this.targetServer = targetServer;
            this.targetDatabase = targetDatabase;
            this.targetSchema = targetSchema;
            this.targetName = targetName;
            this.dbLink = dbLink;
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

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getTargetServer() {
            return targetServer;
        }

        public void setTargetServer(String targetServer) {
            this.targetServer = targetServer;
        }

        public String getTargetDatabase() {
            return targetDatabase;
        }

        public void setTargetDatabase(String targetDatabase) {
            this.targetDatabase = targetDatabase;
        }

        public String getTargetSchema() {
            return targetSchema;
        }

        public void setTargetSchema(String targetSchema) {
            this.targetSchema = targetSchema;
        }

        public String getTargetName() {
            return targetName;
        }

        public void setTargetName(String targetName) {
            this.targetName = targetName;
        }

        public String getDbLink() {
            return dbLink;
        }

        public void setDbLink(String dbLink) {
            this.dbLink = dbLink;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CatalogArgument(String name, String dataType, String inout) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class CatalogRoutine {
        private String server;
        private String database;
        private String schema;
        private String packageName;
        private String name;
        private String routineType;
        private String returnType;
        private boolean pipelined;
        private List<CatalogColumn> returnTableColumns = new ArrayList<>();
        private List<CatalogArgument> arguments = new ArrayList<>();
        private String sourceCode;

        public CatalogRoutine() {
        }

        public CatalogRoutine(
                String server,
                String database,
                String schema,
                String packageName,
                String name,
                String routineType,
                String returnType,
                boolean pipelined,
                List<CatalogColumn> returnTableColumns,
                List<CatalogArgument> arguments,
                String sourceCode) {
            this.server = server;
            this.database = database;
            this.schema = schema;
            this.packageName = packageName;
            this.name = name;
            this.routineType = routineType;
            this.returnType = returnType;
            this.pipelined = pipelined;
            if (returnTableColumns != null) {
                this.returnTableColumns.addAll(returnTableColumns);
            }
            if (arguments != null) {
                this.arguments.addAll(arguments);
            }
            this.sourceCode = sourceCode;
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

        public String getPackageName() {
            return packageName;
        }

        public void setPackageName(String packageName) {
            this.packageName = packageName;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getRoutineType() {
            return routineType;
        }

        public void setRoutineType(String routineType) {
            this.routineType = routineType;
        }

        public String getReturnType() {
            return returnType;
        }

        public void setReturnType(String returnType) {
            this.returnType = returnType;
        }

        public boolean isPipelined() {
            return pipelined;
        }

        public void setPipelined(boolean pipelined) {
            this.pipelined = pipelined;
        }

        public List<CatalogColumn> getReturnTableColumns() {
            return returnTableColumns;
        }

        public void setReturnTableColumns(List<CatalogColumn> returnTableColumns) {
            this.returnTableColumns = returnTableColumns != null ? returnTableColumns : new ArrayList<>();
        }

        public List<CatalogArgument> getArguments() {
            return arguments;
        }

        public void setArguments(List<CatalogArgument> arguments) {
            this.arguments = arguments != null ? arguments : new ArrayList<>();
        }

        public String getSourceCode() {
            return sourceCode;
        }

        public void setSourceCode(String sourceCode) {
            this.sourceCode = sourceCode;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CatalogSequence(String server, String database, String schema, String name) {
    }
}
