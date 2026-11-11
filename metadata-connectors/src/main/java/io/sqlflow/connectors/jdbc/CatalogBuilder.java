package io.sqlflow.connectors.jdbc;

import io.sqlflow.connectors.util.ConnectorsJson;
import io.sqlflow.contracts.lineage.ArgumentObject;
import io.sqlflow.contracts.lineage.Column;
import io.sqlflow.contracts.lineage.DatabaseObject;
import io.sqlflow.contracts.lineage.DbLink;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.ErrorItem;
import io.sqlflow.contracts.lineage.PackageObject;
import io.sqlflow.contracts.lineage.ProcedureObject;
import io.sqlflow.contracts.lineage.QueryObject;
import io.sqlflow.contracts.lineage.SchemaObject;
import io.sqlflow.contracts.lineage.SequenceObject;
import io.sqlflow.contracts.lineage.ServerObject;
import io.sqlflow.contracts.lineage.SqlInfo;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.contracts.lineage.SynonymObject;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.contracts.vendor.IdentifierProfile;
import java.time.Instant;
import java.util.ArrayList;
import java.util. LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic assembler for {@link SqlflowCatalog}, {@link SqlInfo} lineage units, and canonical
 * Exporter Metadata JSON ({@code 02 §5.1, §6.3.4}, {@code 03 §2.4}).
 *
 * <p>Handles all 3 catalog/schema hierarchy topologies defined in {@code 03 §2.3}:
 * <ul>
 *   <li>{@code supportsCatalogs = false, supportsSchemas = true} (e.g., Oracle, Dameng)</li>
 *   <li>{@code supportsCatalogs = true, supportsSchemas = false} (e.g., MySQL, Hive, Impala, Teradata)</li>
 *   <li>{@code supportsCatalogs = true, supportsSchemas = true} (e.g., PostgreSQL, SQL Server, Snowflake, Redshift, DB2, Netezza, BigQuery)</li>
 * </ul>
 */
public final class CatalogBuilder {

    private final EDbVendor vendor;
    private final String serverName;
    private final boolean supportsCatalogs;
    private final boolean supportsSchemas;

    private long nextId = 1L;
    private int nextOriginIndex = 0;

    private final Map<String, DbSlot> databases = new LinkedHashMap<>();
    private final Map<String, SchemaSlot> serverSchemas = new LinkedHashMap<>();
    private final List<QueryObject> queries = new ArrayList<>();
    private final List<DbLink> dbLinks = new ArrayList<>();
    private final List<ErrorItem> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<SqlInfo> sqlUnits = new ArrayList<>();
    private final Set<String> seenSqlHashes = new LinkedHashSet<>();

    public CatalogBuilder(EDbVendor vendor, String serverName) {
        this.vendor = vendor != null ? vendor : EDbVendor.dbvpostgresql;
        this.serverName = (serverName == null || serverName.isBlank()) ? "default_server" : serverName.trim();
        IdentifierProfile profile = IdentifierProfile.forVendor(this.vendor);
        this.supportsCatalogs = profile.metadataSupportsCatalogs();
        // ClickHouse is also catalog-based without schema in SQLFlow Exporter (03 §2.3)
        this.supportsSchemas = this.vendor == EDbVendor.dbvclickhouse ? false : profile.metadataSupportsSchemas();
    }

    public EDbVendor vendor() {
        return vendor;
    }

    public String serverName() {
        return serverName;
    }

    public String allocateId() {
        return Long.toString(nextId++);
    }

    public DbObject getOrCreateTable(String database, String schema, String tableName) {
        return getOrCreateDbObject(database, schema, tableName, "table", false);
    }

    public DbObject getOrCreateView(String database, String schema, String viewName) {
        return getOrCreateDbObject(database, schema, viewName, "view", true);
    }

    public DbObject getOrCreateOtherObject(String database, String schema, String name, String objectType) {
        ContainerSlot slot = resolveContainer(database, schema);
        String key = normKey(name);
        return slot.others.computeIfAbsent(key, k -> {
            DbObject obj = new DbObject(allocateId(), name.trim(), objectType != null ? objectType : "other");
            obj.setServer(serverName);
            if (slot.databaseName != null && !slot.databaseName.isEmpty()) {
                obj.setDatabase(slot.databaseName);
            }
            if (slot.schemaName != null && !slot.schemaName.isEmpty()) {
                obj.setSchema(slot.schemaName);
            }
            obj.setFromDDL(true);
            obj.setColumns(new ArrayList<>());
            return obj;
        });
    }

    private DbObject getOrCreateDbObject(String database, String schema, String name, String type, boolean isView) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Object name must not be blank");
        }
        ContainerSlot slot = resolveContainer(database, schema);
        String key = normKey(name);
        Map<String, DbObject> map = isView ? slot.views : slot.tables;
        // If previously registered as table and now discovered to be a view, promote it cleanly
        if (isView && slot.tables.containsKey(key) && !slot.views.containsKey(key)) {
            DbObject moved = slot.tables.remove(key);
            moved.setType("view");
            moved.setIsView("true");
            slot.views.put(key, moved);
            return moved;
        }
        if (!isView && slot.views.containsKey(key)) {
            return slot.views.get(key);
        }
        return map.computeIfAbsent(key, k -> {
            DbObject obj = new DbObject(allocateId(), name.trim(), type);
            obj.setServer(serverName);
            if (slot.databaseName != null && !slot.databaseName.isEmpty()) {
                obj.setDatabase(slot.databaseName);
            }
            if (slot.schemaName != null && !slot.schemaName.isEmpty()) {
                obj.setSchema(slot.schemaName);
            }
            if (isView) {
                obj.setIsView("true");
            }
            obj.setFromDDL(true);
            obj.setColumns(new ArrayList<>());
            return obj;
        });
    }

    public Column addOrUpdateColumn(
            DbObject parent,
            String columnName,
            String dataType,
            Boolean nullable,
            String defaultValue,
            String comment) {
        if (parent == null || columnName == null || columnName.isBlank()) {
            return null;
        }
        List<Column> cols = parent.getColumns();
        if (cols == null) {
            cols = new ArrayList<>();
            parent.setColumns(cols);
        }
        String targetKey = normKey(columnName);
        Column col = null;
        for (Column existing : cols) {
            if (normKey(existing.getName()).equals(targetKey)) {
                col = existing;
                break;
            }
        }
        if (col == null) {
            col = new Column(allocateId(), columnName.trim(), trimToNull(dataType));
            cols.add(col);
        } else if (dataType != null && !dataType.isBlank()) {
            col.setDataType(dataType.trim());
        }
        if (nullable != null) {
            col.setNullable(nullable);
        }
        if (defaultValue != null && !defaultValue.isBlank()) {
            col.setDefaultValue(defaultValue.trim());
        }
        if (comment != null && !comment.isBlank()) {
            col.setComment(comment.trim());
        }
        return col;
    }

    public void markConstraintColumn(
            String database,
            String schema,
            String tableName,
            String columnName,
            String constraintKind) {
        if (tableName == null || columnName == null || constraintKind == null) {
            return;
        }
        ContainerSlot slot = resolveContainer(database, schema);
        String tKey = normKey(tableName);
        DbObject obj = slot.tables.get(tKey);
        if (obj == null) {
            obj = slot.views.get(tKey);
        }
        if (obj == null || obj.getColumns() == null) {
            return;
        }
        String cKey = normKey(columnName);
        for (Column col : obj.getColumns()) {
            if (normKey(col.getName()).equals(cKey)) {
                String k = constraintKind.trim().toUpperCase(Locale.ROOT);
                if (k.startsWith("P") || k.contains("PRIMARY")) {
                    col.setPrimaryKey(true);
                } else if (k.startsWith("U") || k.contains("UNIQUE")) {
                    col.setUnqiueKey(true);
                } else if (k.startsWith("F") || k.startsWith("R") || k.contains("FOREIGN")) {
                    col.setForeignKey(true);
                } else if (k.startsWith("I") || k.contains("INDEX")) {
                    col.setIndexKey(true);
                }
                break;
            }
        }
    }

    public PackageObject getOrCreatePackage(String database, String schema, String packageName) {
        ContainerSlot slot = resolveContainer(database, schema);
        return slot.packages.computeIfAbsent(normKey(packageName), k -> {
            PackageObject pkg = new PackageObject(allocateId(), packageName.trim());
            pkg.setProcedures(new ArrayList<>());
            pkg.setFunctions(new ArrayList<>());
            return pkg;
        });
    }

    public ProcedureObject addRoutine(
            String database,
            String schema,
            String packageName,
            String routineName,
            String routineType,
            String sourceCode) {
        if (routineName == null || routineName.isBlank()) {
            return null;
        }
        String normType = normalizeRoutineType(routineType);
        ProcedureObject proc = new ProcedureObject(allocateId(), routineName.trim(), normType);
        proc.setArguments(new ArrayList<>());

        if (packageName != null && !packageName.isBlank()) {
            PackageObject pkg = getOrCreatePackage(database, schema, packageName);
            if ("function".equals(normType)) {
                pkg.getFunctions().add(proc);
            } else {
                pkg.getProcedures().add(proc);
            }
        } else {
            ContainerSlot slot = resolveContainer(database, schema);
            switch (normType) {
                case "function" -> slot.functions.put(normKey(routineName), proc);
                case "trigger" -> slot.triggers.put(normKey(routineName), proc);
                default -> slot.procedures.put(normKey(routineName), proc);
            }
        }

        if (sourceCode != null && !sourceCode.isBlank()) {
            String objLabel = packageName != null && !packageName.isBlank()
                    ? packageName.trim() + "." + routineName.trim()
                    : routineName.trim();
            addQuerySource(database, schema, objLabel, normType, sourceCode, normType + "/" + objLabel + ".sql");
        }
        return proc;
    }

    public ArgumentObject addRoutineArgument(
            String database,
            String schema,
            String packageName,
            String routineName,
            String argName,
            String dataType,
            String inOut) {
        if (routineName == null || routineName.isBlank()) {
            return null;
        }
        ProcedureObject target = findRoutine(database, schema, packageName, routineName);
        if (target == null) {
            target = addRoutine(database, schema, packageName, routineName, "procedure", null);
        }
        if (target == null) {
            return null;
        }
        if (target.getArguments() == null) {
            target.setArguments(new ArrayList<>());
        }
        String effectiveName = (argName == null || argName.isBlank()) ? "RETURN_VALUE" : argName.trim();
        ArgumentObject arg = new ArgumentObject(
                allocateId(),
                effectiveName,
                trimToNull(dataType),
                inOut != null && !inOut.isBlank() ? inOut.trim().toUpperCase(Locale.ROOT) : "IN");
        target.getArguments().add(arg);
        return arg;
    }

    public void addViewDefinition(
            String database,
            String schema,
            String viewName,
            String viewSql,
            boolean materialized) {
        DbObject viewObj = getOrCreateView(database, schema, viewName);
        if (materialized) {
            viewObj.setSubType("materialized_view");
        }
        if (viewSql == null || viewSql.isBlank()) {
            return;
        }
        String ddl = ensureViewDdlWrapper(database, schema, viewName, viewSql.trim(), materialized);
        String type = materialized ? "materialized view" : "view";
        addQuerySource(database, schema, viewName.trim(), type, ddl, "view/" + viewName.trim() + ".sql");
    }

    public void addSynonym(
            String database,
            String schema,
            String synonymName,
            String targetDatabase,
            String targetSchema,
            String targetObjectName,
            String dbLinkName) {
        if (synonymName == null || synonymName.isBlank()) {
            return;
        }
        ContainerSlot slot = resolveContainer(database, schema);
        SynonymObject syn = new SynonymObject();
        syn.setId(allocateId());
        syn.setDatabase(trimToNull(slot.databaseName));
        syn.setSchema(trimToNull(slot.schemaName));
        syn.setName(synonymName.trim());
        syn.setSourceDatabase(trimToNull(targetDatabase));
        syn.setSourceSchema(trimToNull(targetSchema));
        syn.setSourceName(trimToNull(targetObjectName));
        syn.setSourceDbLinkName(trimToNull(dbLinkName));
        slot.synonyms.put(normKey(synonymName), syn);
    }

    public void addSequence(
            String database,
            String schema,
            String sequenceName,
            String startWith,
            String incrementBy,
            String minValue,
            String maxValue,
            Boolean cycle,
            Boolean order,
            String cache) {
        if (sequenceName == null || sequenceName.isBlank()) {
            return;
        }
        ContainerSlot slot = resolveContainer(database, schema);
        SequenceObject seq = new SequenceObject();
        seq.setId(allocateId());
        seq.setName(sequenceName.trim());
        seq.setStartWith(trimToNull(startWith));
        seq.setIncrementBy(trimToNull(incrementBy));
        seq.setMinValue(trimToNull(minValue));
        seq.setMaxValue(trimToNull(maxValue));
        seq.setCycle(cycle);
        seq.setOrder(order);
        seq.setCache(trimToNull(cache));
        slot.sequences.put(normKey(sequenceName), seq);
    }

    public void addDbLink(String owner, String linkName, String userName, String host) {
        if (linkName == null || linkName.isBlank()) {
            return;
        }
        dbLinks.add(new DbLink(trimToNull(owner), linkName.trim(), trimToNull(userName), trimToNull(host)));
    }

    public void addQuerySource(
            String database,
            String schema,
            String objectName,
            String objectType,
            String sourceCode,
            String virtualFilePath) {
        if (sourceCode == null || sourceCode.isBlank()) {
            return;
        }
        String trimmedSql = sourceCode.trim();
        QueryObject qo = new QueryObject(
                trimToNull(database),
                trimToNull(schema),
                trimToNull(objectName),
                objectType != null ? objectType : "query",
                trimmedSql);
        queries.add(qo);
        addSqlUnit(trimmedSql, virtualFilePath != null ? virtualFilePath : (objectName != null ? objectName + ".sql" : "query.sql"));
    }

    public void addSqlUnit(String sql, String filePath) {
        if (sql == null || sql.isBlank()) {
            return;
        }
        String trimmed = sql.trim();
        String hash = ConnectorsJson.md5Hex(trimmed);
        if (!seenSqlHashes.add(hash)) {
            return;
        }
        String path = (filePath == null || filePath.isBlank()) ? "query_" + nextOriginIndex + ".sql" : filePath;
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String fileName = slash >= 0 ? path.substring(slash + 1) : path;
        SqlInfo info = new SqlInfo(trimmed, fileName, path, vendor.wireName());
        info.setOriginIndex(nextOriginIndex++);
        info.setHashCode(hash);
        sqlUnits.add(info);
    }

    public void addWarning(String warning) {
        if (warning != null && !warning.isBlank()) {
            warnings.add(warning);
            errors.add(new ErrorItem("MetadataWarning", warning));
        }
    }

    public List<SqlInfo> buildSqlUnits() {
        return List.copyOf(sqlUnits);
    }

    public List<String> buildWarnings() {
        return List.copyOf(warnings);
    }

    public SqlflowCatalog buildCatalog() {
        SqlflowCatalog catalog = new SqlflowCatalog();
        long nowMs = System.currentTimeMillis();
        catalog.setCreatedBy("sqlflow-exporter-8.2.2.15");
        catalog.setExportTime(nowMs);
        catalog.setCreateTime(nowMs);
        catalog.setCreatedAt(Instant.ofEpochMilli(nowMs).toString());
        catalog.setExportId(UUID.randomUUID().toString());
        catalog.setDialect(vendor.wireName());
        catalog.setPhysicalInstance(serverName);

        ServerObject server = new ServerObject(serverName, vendor.wireName(), supportsCatalogs, supportsSchemas);
        if (!dbLinks.isEmpty()) {
            server.setDbLinks(List.copyOf(dbLinks));
            catalog.setDbLinks(List.copyOf(dbLinks));
        }
        if (!queries.isEmpty()) {
            server.setQueries(List.copyOf(queries));
            catalog.setQueries(List.copyOf(queries));
        }

        List<DatabaseObject> topDatabases = new ArrayList<>();
        if (!supportsCatalogs && supportsSchemas) {
            // Oracle / Dameng: ServerObject.schemas populated directly + synthetic DatabaseObject mirror for databases[] consumers
            List<SchemaObject> schemaList = new ArrayList<>();
            for (SchemaSlot slot : serverSchemas.values()) {
                schemaList.add(slot.toSchemaObject());
            }
            server.setSchemas(schemaList);
            if (!schemaList.isEmpty()) {
                DatabaseObject mirrorDb = new DatabaseObject(serverName);
                mirrorDb.setSchemas(schemaList);
                topDatabases.add(mirrorDb);
            }
        } else if (supportsCatalogs && !supportsSchemas) {
            // MySQL / Hive / Impala / Teradata / ClickHouse: DatabaseObject holds tables/views/procedures directly
            for (DbSlot dbSlot : databases.values()) {
                DatabaseObject dbObj = dbSlot.toCatalogOnlyDatabaseObject();
                topDatabases.add(dbObj);
            }
            server.setDatabases(topDatabases);
        } else {
            // PostgreSQL / SQL Server / Snowflake / Redshift / DB2 / Netezza / BigQuery / SQLite / DuckDB
            for (DbSlot dbSlot : databases.values()) {
                DatabaseObject dbObj = dbSlot.toSchemaAwareDatabaseObject();
                topDatabases.add(dbObj);
            }
            server.setDatabases(topDatabases);
        }

        catalog.setServers(List.of(server));
        catalog.setDatabases(topDatabases);
        if (!errors.isEmpty()) {
            catalog.setErrorMessages(List.copyOf(errors));
        }
        return catalog;
    }

    private ProcedureObject findRoutine(String database, String schema, String packageName, String routineName) {
        String rKey = normKey(routineName);
        if (packageName != null && !packageName.isBlank()) {
            ContainerSlot slot = resolveContainer(database, schema);
            PackageObject pkg = slot.packages.get(normKey(packageName));
            if (pkg == null) {
                return null;
            }
            if (pkg.getProcedures() != null) {
                for (ProcedureObject p : pkg.getProcedures()) {
                    if (normKey(p.getName()).equals(rKey)) {
                        return p;
                    }
                }
            }
            if (pkg.getFunctions() != null) {
                for (ProcedureObject f : pkg.getFunctions()) {
                    if (normKey(f.getName()).equals(rKey)) {
                        return f;
                    }
                }
            }
            return null;
        }
        ContainerSlot slot = resolveContainer(database, schema);
        ProcedureObject proc = slot.procedures.get(rKey);
        if (proc != null) {
            return proc;
        }
        proc = slot.functions.get(rKey);
        if (proc != null) {
            return proc;
        }
        return slot.triggers.get(rKey);
    }

    private String ensureViewDdlWrapper(
            String database,
            String schema,
            String viewName,
            String rawText,
            boolean materialized) {
        String upper = rawText.stripLeading().toUpperCase(Locale.ROOT);
        if (upper.startsWith("CREATE ") || upper.startsWith("REPLACE ") || upper.startsWith("ALTER ")) {
            return rawText;
        }
        String qualified = qualifyName(database, schema, viewName);
        String kw = materialized ? "CREATE MATERIALIZED VIEW " : "CREATE VIEW ";
        return kw + qualified + " AS\n" + rawText;
    }

    private String qualifyName(String database, String schema, String objectName) {
        StringBuilder sb = new StringBuilder();
        if (supportsCatalogs && database != null && !database.isBlank()) {
            sb.append(database.trim()).append('.');
        }
        if (supportsSchemas && schema != null && !schema.isBlank()) {
            sb.append(schema.trim()).append('.');
        }
        sb.append(objectName.trim());
        return sb.toString();
    }

    private ContainerSlot resolveContainer(String database, String schema) {
        if (!supportsCatalogs && supportsSchemas) {
            String effectiveSchema = firstNonBlank(schema, database, "DEFAULT");
            SchemaSlot s = serverSchemas.computeIfAbsent(normKey(effectiveSchema), k -> new SchemaSlot(null, effectiveSchema));
            return s.container;
        }
        if (supportsCatalogs && !supportsSchemas) {
            String effectiveDb = firstNonBlank(database, schema, "default");
            DbSlot d = databases.computeIfAbsent(normKey(effectiveDb), k -> new DbSlot(effectiveDb));
            return d.directContainer;
        }
        String effectiveDb = firstNonBlank(database, "default");
        String effectiveSchema = firstNonBlank(schema, defaultSchemaForVendor(vendor));
        DbSlot d = databases.computeIfAbsent(normKey(effectiveDb), k -> new DbSlot(effectiveDb));
        SchemaSlot s = d.schemas.computeIfAbsent(normKey(effectiveSchema), k -> new SchemaSlot(effectiveDb, effectiveSchema));
        return s.container;
    }

    private static String defaultSchemaForVendor(EDbVendor v) {
        return switch (v) {
            case dbvmssql, dbvazuresql, dbvsybase, dbvsybasease, dbvsybaseiq -> "dbo";
            case dbvsnowflake -> "PUBLIC";
            case dbvsqlite, dbvduckdb -> "main";
            default -> "public";
        };
    }

    private static String normalizeRoutineType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "procedure";
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("func") || "f".equals(lower) || "fn".equals(lower) || "if".equals(lower) || "tf".equals(lower) || "sf".equals(lower)) {
            return "function";
        }
        if (lower.contains("trig") || "tr".equals(lower)) {
            return "trigger";
        }
        if (lower.contains("macro") || "m".equals(lower)) {
            return "macro";
        }
        return "procedure";
    }

    private static String normKey(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c.trim();
            }
        }
        return "";
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static final class ContainerSlot {
        final String databaseName;
        final String schemaName;
        final Map<String, DbObject> tables = new LinkedHashMap<>();
        final Map<String, DbObject> views = new LinkedHashMap<>();
        final Map<String, PackageObject> packages = new LinkedHashMap<>();
        final Map<String, ProcedureObject> procedures = new LinkedHashMap<>();
        final Map<String, ProcedureObject> functions = new LinkedHashMap<>();
        final Map<String, ProcedureObject> triggers = new LinkedHashMap<>();
        final Map<String, SynonymObject> synonyms = new LinkedHashMap<>();
        final Map<String, SequenceObject> sequences = new LinkedHashMap<>();
        final Map<String, DbObject> others = new LinkedHashMap<>();

        ContainerSlot(String databaseName, String schemaName) {
            this.databaseName = databaseName;
            this.schemaName = schemaName;
        }

        void populateSchemaObject(SchemaObject target) {
            if (!tables.isEmpty()) {
                target.setTables(new ArrayList<>(tables.values()));
            }
            if (!views.isEmpty()) {
                target.setViews(new ArrayList<>(views.values()));
            }
            if (!packages.isEmpty()) {
                target.setPackages(new ArrayList<>(packages.values()));
            }
            if (!procedures.isEmpty()) {
                target.setProcedures(new ArrayList<>(procedures.values()));
            }
            if (!functions.isEmpty()) {
                target.setFunctions(new ArrayList<>(functions.values()));
            }
            if (!triggers.isEmpty()) {
                target.setTriggers(new ArrayList<>(triggers.values()));
            }
            if (!synonyms.isEmpty()) {
                target.setSynonyms(new ArrayList<>(synonyms.values()));
            }
            if (!sequences.isEmpty()) {
                target.setSequences(new ArrayList<>(sequences.values()));
            }
            if (!others.isEmpty()) {
                target.setOthers(new ArrayList<>(others.values()));
            }
        }

        void populateDatabaseObject(DatabaseObject target) {
            if (!tables.isEmpty()) {
                target.setTables(new ArrayList<>(tables.values()));
            }
            if (!views.isEmpty()) {
                target.setViews(new ArrayList<>(views.values()));
            }
            if (!packages.isEmpty()) {
                target.setPackages(new ArrayList<>(packages.values()));
            }
            if (!procedures.isEmpty()) {
                target.setProcedures(new ArrayList<>(procedures.values()));
            }
            if (!functions.isEmpty()) {
                target.setFunctions(new ArrayList<>(functions.values()));
            }
            if (!triggers.isEmpty()) {
                target.setTriggers(new ArrayList<>(triggers.values()));
            }
            if (!synonyms.isEmpty()) {
                target.setSynonyms(new ArrayList<>(synonyms.values()));
            }
            if (!sequences.isEmpty()) {
                target.setSequences(new ArrayList<>(sequences.values()));
            }
            if (!others.isEmpty()) {
                target.setOthers(new ArrayList<>(others.values()));
            }
        }
    }

    private static final class SchemaSlot {
        final String schemaName;
        final ContainerSlot container;

        SchemaSlot(String databaseName, String schemaName) {
            this.schemaName = schemaName;
            this.container = new ContainerSlot(databaseName, schemaName);
        }

        SchemaObject toSchemaObject() {
            SchemaObject obj = new SchemaObject(schemaName);
            container.populateSchemaObject(obj);
            return obj;
        }
    }

    private static final class DbSlot {
        final String dbName;
        final ContainerSlot directContainer;
        final Map<String, SchemaSlot> schemas = new LinkedHashMap<>();

        DbSlot(String dbName) {
            this.dbName = dbName;
            this.directContainer = new ContainerSlot(dbName, null);
        }

        DatabaseObject toCatalogOnlyDatabaseObject() {
            DatabaseObject db = new DatabaseObject(dbName);
            directContainer.populateDatabaseObject(db);
            return db;
        }

        DatabaseObject toSchemaAwareDatabaseObject() {
            DatabaseObject db = new DatabaseObject(dbName);
            List<SchemaObject> schemaList = new ArrayList<>();
            for (SchemaSlot slot : schemas.values()) {
                schemaList.add(slot.toSchemaObject());
            }
            db.setSchemas(schemaList);
            return db;
        }
    }
}
