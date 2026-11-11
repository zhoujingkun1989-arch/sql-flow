package io.sqlflow.server.persistence;

import io.sqlflow.contracts.domain.JobDetail;
import io.sqlflow.contracts.graph.LeftMostSourceTable;
import io.sqlflow.contracts.lineage.Column;
import io.sqlflow.contracts.lineage.Coordinate;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.Relationship;
import io.sqlflow.contracts.lineage.RelationshipElement;
import io.sqlflow.contracts.lineage.Transform;
import io.sqlflow.server.config.SqlflowRuntimeConfig;
import io.sqlflow.server.security.CryptoService;
import io.sqlflow.server.spi.CatalogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DuckDB analytical persistence store ({@code conf/sqlflow.duckdb}) managing schema {@code "sqlflow"}
 * and all 14 analytical tables from {@code 02 §7.3}, plus native {@code COPY ... TO ... (FORMAT PARQUET)} export.
 */
public class DuckDbAnalyticalStore implements AnalyticalStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DuckDbAnalyticalStore.class);

    public static final List<String> ALL_14_TABLES = List.of(
            "dataflow_table",
            "dataflow_aggregation_table",
            "dataflow_table_metadata",
            "dataflow_sql_map_table",
            "sql_table",
            "left_most_table",
            "left_most_source_table",
            "column_level_out_going",
            "table_level_dataflow",
            "table_level_aggregation",
            "column_level_out_going_user",
            "sql_transform",
            "lineage_transform",
            "job");

    private static final String[] DUCKDB_DDL = {
        "CREATE SCHEMA IF NOT EXISTS \"sqlflow\"",
        """
        CREATE TABLE IF NOT EXISTS "sqlflow"."dataflow_table" (
          "job_id" TEXT,
          "lineage_hash" TEXT,
          "db_instance" TEXT,
          "user_name" TEXT,
          "target_table" TEXT,
          "target_column" TEXT,
          "source_table" TEXT,
          "source_column" TEXT,
          "star_stmt" TEXT,
          "expand_star" TEXT,
          "execute_time" TIMESTAMP,
          "create_time" TIMESTAMP,
          "relation_type" TEXT,
          "indirect_clause" TEXT
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS "sqlflow"."dataflow_aggregation_table" (
          "job_id" TEXT,
          "lineage_hash" TEXT,
          "db_instance" TEXT,
          "user_name" TEXT,
          "target_table" TEXT,
          "target_column" TEXT,
          "source_table" TEXT,
          "source_column" TEXT,
          "star_stmt" TEXT,
          "count" BIGINT,
          "earliest_execute_time" TIMESTAMP,
          "latest_execute_time" TIMESTAMP,
          "create_time" TIMESTAMP,
          "relation_type" TEXT
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS "sqlflow"."dataflow_table_metadata" (
          "job_id" TEXT,
          "database" TEXT,
          "schema" TEXT,
          "table" TEXT,
          "type" SMALLINT,
          "create_time" TIMESTAMP,
          "process_ids" TEXT
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS "sqlflow"."dataflow_sql_map_table" (
          "job_id" TEXT,
          "lineage_hash" TEXT,
          "sql_hash" TEXT,
          "sql" TEXT,
          "process_type" TEXT,
          "file_path" TEXT,
          "execute_time" TIMESTAMP,
          "db_instance" TEXT,
          "db" TEXT,
          "user" TEXT,
          "user_id" TEXT,
          "pid" TEXT,
          "xid" TEXT,
          "create_time" TIMESTAMP,
          "transform" TEXT DEFAULT 'N'
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS "sqlflow"."sql_table" (
          "job_id" TEXT,
          "sql_hash" TEXT,
          "type" TEXT,
          "sql" TEXT,
          "create_time" TIMESTAMP
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS "sqlflow"."left_most_table" (
          "job_id" TEXT,
          "lineage_hash" TEXT,
          "db_instance" TEXT,
          "user_name" TEXT,
          "target_table" TEXT,
          "target_column" TEXT,
          "source_table" TEXT,
          "source_column" TEXT,
          "star_stmt" TEXT,
          "expand_star" TEXT,
          "earliest_execute_time" TIMESTAMP,
          "latest_execute_time" TIMESTAMP,
          "lineage_paths" TEXT,
          "create_time" TIMESTAMP
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS "sqlflow"."left_most_source_table" (
          "job_id" TEXT,
          "lineage_hash" TEXT,
          "db_instance" TEXT,
          "user_name" TEXT,
          "target_table" TEXT,
          "target_column" TEXT,
          "source_table" TEXT,
          "source_column" TEXT,
          "star_stmt" TEXT,
          "expand_star" TEXT,
          "earliest_execute_time" TIMESTAMP,
          "latest_execute_time" TIMESTAMP,
          "lineage_paths" TEXT,
          "create_time" TIMESTAMP
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS "sqlflow"."column_level_out_going" (
          "job_id" TEXT,
          "batch_id" TEXT,
          "db_instance" TEXT,
          "source_table" TEXT,
          "column" TEXT,
          "table_name_consumed" TEXT,
          "column_consumed" TEXT,
          "user_name" TEXT,
          "stmt_consumed" TEXT,
          "first_execution" TEXT,
          "last_execution" TEXT,
          "number_of_executions" TEXT,
          "create_time" TIMESTAMP
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS "sqlflow"."table_level_dataflow" (
          "job_id" TEXT,
          "db_instance" TEXT,
          "user_name" TEXT,
          "target_table" TEXT,
          "source_table" TEXT,
          "execute_time" TIMESTAMP,
          "create_time" TIMESTAMP,
          "relation_type" TEXT
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS "sqlflow"."table_level_aggregation" (
          "job_id" TEXT,
          "db_instance" TEXT,
          "user_name" TEXT,
          "target_table" TEXT,
          "source_table" TEXT,
          "count" BIGINT,
          "earliest_execute_time" TIMESTAMP,
          "latest_execute_time" TIMESTAMP,
          "create_time" TIMESTAMP,
          "relation_type" TEXT
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS "sqlflow"."column_level_out_going_user" (
          "job_id" TEXT,
          "batch_id" TEXT,
          "db_instance" TEXT,
          "user_name" TEXT,
          "stmt_consumed" TEXT,
          "create_time" TIMESTAMP
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS "sqlflow"."sql_transform" (
          "job_id" TEXT,
          "sql_hash" TEXT,
          "code" TEXT,
          "type" TEXT,
          "index" SMALLINT,
          "create_time" TIMESTAMP
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS "sqlflow"."lineage_transform" (
          "job_id" TEXT,
          "lineage_hash" TEXT,
          "code" TEXT,
          "type" TEXT,
          "index" SMALLINT,
          "coordinate" TEXT,
          "create_time" TIMESTAMP
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS "sqlflow"."job" (
          "job_id" TEXT PRIMARY KEY,
          "job_name" TEXT,
          "user_id" TEXT,
          "user_email" TEXT,
          "group" TEXT,
          "db_vendor" TEXT,
          "status" TEXT,
          "error_message" TEXT,
          "session_id" TEXT,
          "execute_time" TEXT,
          "execute_time_millis" BIGINT DEFAULT 0,
          "create_time" TIMESTAMP,
          "update_time" TIMESTAMP,
          "default_server" TEXT,
          "default_database" TEXT,
          "default_schema" TEXT,
          "persist" BOOLEAN DEFAULT FALSE,
          "incremental" BOOLEAN DEFAULT FALSE,
          "schedulable" BOOLEAN DEFAULT FALSE,
          "cron_expression" TEXT,
          "last_execute_time" TEXT,
          "sqlsource" TEXT,
          "version" TEXT,
          "ip" TEXT,
          "os" TEXT,
          "browser" TEXT,
          "use_method" TEXT,
          "api" TEXT,
          "query_id" TEXT,
          "has_metadata" BOOLEAN DEFAULT FALSE
        )
        """
    };

    private final SqlflowRuntimeConfig config;
    private Connection sharedConn;
    private Path initializedPath;
    private String schemaPrefix = "\"sqlflow\".";

    public DuckDbAnalyticalStore(SqlflowRuntimeConfig config) {
        this.config = config;
    }

    private String qualify(String sql) {
        return sql.replace("\"sqlflow\".", schemaPrefix);
    }

    private PreparedStatement prepare(Connection conn, String sql) throws SQLException {
        return conn.prepareStatement(qualify(sql));
    }

    @Override
    public String storeType() {
        return "duckdb";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public synchronized void close() {
        if (sharedConn != null) {
            try {
                sharedConn.close();
            } catch (SQLException ignored) {
            }
            sharedConn = null;
        }
    }

    private synchronized Connection getConnection() throws SQLException {
        Path duckPath = config.getDuckDbPath();
        if (sharedConn != null && !sharedConn.isClosed() && duckPath.equals(initializedPath)) {
            return sharedConn;
        }
        close();
        try {
            if (duckPath.getParent() != null) {
                Files.createDirectories(duckPath.getParent());
            }
        } catch (IOException e) {
            throw new SQLException("Failed to create DuckDB parent directory: " + duckPath, e);
        }
        Connection conn = DriverManager.getConnection("jdbc:duckdb:" + duckPath.toAbsolutePath());
        try (Statement st = conn.createStatement()) {
            String catalog = "sqlflow";
            try (ResultSet rs = st.executeQuery("SELECT current_database()")) {
                if (rs.next() && rs.getString(1) != null && !rs.getString(1).isBlank()) {
                    catalog = rs.getString(1);
                }
            }
            this.schemaPrefix = "\"" + catalog.replace("\"", "\"\"") + "\".\"sqlflow\".";
            for (String ddl : DUCKDB_DDL) {
                st.execute(qualify(ddl));
            }
        }
        this.sharedConn = conn;
        this.initializedPath = duckPath;
        return conn;
    }

    @Override
    public synchronized void initSchema() {
        try {
            getConnection();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize DuckDB schema at " + config.getDuckDbPath(), e);
        }
    }

    @Override
    public synchronized List<String> listTables() {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement ps = prepare(getConnection(), 
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'sqlflow' ORDER BY table_name");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to inspect DuckDB sqlflow tables", e);
        }
        return tables;
    }

    @Override
    public synchronized void persistJobLineage(
            JobDetail job, DataFlow dataFlow, List<SqlEntry> sqlEntries, LeftMostSourceTable leftMost) {
        if (job == null || job.getJobId() == null) {
            return;
        }
        String jobId = job.getJobId();
        Timestamp now = Timestamp.from(Instant.now());
        try {
            Connection conn = getConnection();
            if (!Boolean.TRUE.equals(job.getIncremental())) {
                deleteJobDataInternal(conn, jobId);
            } else {
                try (PreparedStatement delJob = prepare(conn, "DELETE FROM \"sqlflow\".\"job\" WHERE \"job_id\" = ?")) {
                    delJob.setString(1, jobId);
                    delJob.executeUpdate();
                }
            }

            // 1. Insert job record
            try (PreparedStatement ps = prepare(conn, """
                    INSERT INTO "sqlflow"."job" (
                      "job_id", "job_name", "user_id", "user_email", "group", "db_vendor", "status",
                      "error_message", "session_id", "execute_time", "execute_time_millis", "create_time",
                      "update_time", "default_server", "default_database", "default_schema", "persist",
                      "incremental", "schedulable", "cron_expression", "last_execute_time", "sqlsource",
                      "version", "has_metadata"
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                ps.setString(1, jobId);
                ps.setString(2, job.getJobName());
                ps.setString(3, job.getUserId());
                ps.setString(4, job.getUserEmail());
                ps.setString(5, job.getGroup());
                ps.setString(6, job.getDbVendor());
                ps.setString(7, job.getStatus() != null ? job.getStatus().name() : "success");
                ps.setString(8, job.getErrorMessage());
                ps.setString(9, job.getSessionId());
                ps.setString(10, job.getExecuteTime());
                ps.setLong(11, job.getExecuteTimeMillis() != null ? job.getExecuteTimeMillis() : 0L);
                ps.setTimestamp(12, now);
                ps.setTimestamp(13, now);
                ps.setString(14, job.getDefaultServer());
                ps.setString(15, job.getDefaultDatabase());
                ps.setString(16, job.getDefaultSchema());
                ps.setBoolean(17, Boolean.TRUE.equals(job.getPersist()));
                ps.setBoolean(18, Boolean.TRUE.equals(job.getIncremental()));
                ps.setBoolean(19, Boolean.TRUE.equals(job.getSchedulable()));
                ps.setString(20, job.getCronExpression());
                ps.setString(21, job.getLastExecuteTime());
                ps.setString(22, job.getSqlsource());
                ps.setString(23, job.getVersion() != null ? job.getVersion() : "5.3.0");
                ps.setBoolean(24, Boolean.TRUE.equals(job.getHasMetadata()));
                ps.executeUpdate();
            }

            // 2. Insert SQL entries
            String primarySql = "";
            String primarySqlHash = "";
            String primaryFile = "";
            if (sqlEntries != null && !sqlEntries.isEmpty()) {
                primarySql = sqlEntries.get(0).sqlText();
                primarySqlHash = sqlEntries.get(0).sqlHash();
                primaryFile = sqlEntries.get(0).fileName();
                try (PreparedStatement ps = prepare(conn, """
                        INSERT INTO "sqlflow"."sql_table" ("job_id", "sql_hash", "type", "sql", "create_time")
                        VALUES (?, ?, 'sql', ?, ?)
                        """)) {
                    for (SqlEntry entry : sqlEntries) {
                        ps.setString(1, jobId);
                        ps.setString(2, entry.sqlHash());
                        ps.setString(3, entry.sqlText());
                        ps.setTimestamp(4, now);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }

            if (dataFlow == null) {
                return;
            }

            Map<String, DbObject> objById = new LinkedHashMap<>();
            Map<String, String> colTableById = new LinkedHashMap<>();
            Map<String, String> colNameById = new LinkedHashMap<>();

            List<DbObject> dbObjects = CatalogUtils.collectAllDbObjects(dataFlow);
            if (!dbObjects.isEmpty()) {
                try (PreparedStatement metaPs = prepare(conn, """
                        INSERT INTO "sqlflow"."dataflow_table_metadata" ("job_id", "database", "schema", "table", "type", "create_time", "process_ids")
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    for (DbObject dbo : dbObjects) {
                        if (dbo == null || dbo.getId() == null) {
                            continue;
                        }
                        objById.put(dbo.getId(), dbo);
                        String fullTable = qualifyTable(dbo);
                        short typeCode = "view".equalsIgnoreCase(dbo.getType()) ? (short) 1 : (short) 0;
                        metaPs.setString(1, jobId);
                        metaPs.setString(2, defaultIfBlank(dbo.getDatabase(), "DEFAULT"));
                        metaPs.setString(3, defaultIfBlank(dbo.getSchema(), "DEFAULT"));
                        metaPs.setString(4, dbo.getName());
                        metaPs.setShort(5, typeCode);
                        metaPs.setTimestamp(6, now);
                        metaPs.setString(7, dbo.getProcessIds() != null ? String.join(",", dbo.getProcessIds()) : "");
                        metaPs.addBatch();

                        if (dbo.getColumns() != null) {
                            for (Column c : dbo.getColumns()) {
                                if (c != null && c.getId() != null) {
                                    colTableById.put(c.getId(), fullTable);
                                    colNameById.put(c.getId(), c.getName());
                                    colTableById.put(dbo.getId() + ":" + c.getName(), fullTable);
                                    colNameById.put(dbo.getId() + ":" + c.getName(), c.getName());
                                }
                            }
                        }
                    }
                    metaPs.executeBatch();
                }
            }

            // 3. Persist column-level and table-level lineage edges
            Set<String> seenTablePairs = new LinkedHashSet<>();
            if (dataFlow.getRelationships() != null) {
                try (PreparedStatement dfPs = prepare(conn, """
                        INSERT INTO "sqlflow"."dataflow_table" (
                          "job_id", "lineage_hash", "db_instance", "user_name", "target_table", "target_column",
                          "source_table", "source_column", "star_stmt", "expand_star", "execute_time", "create_time",
                          "relation_type", "indirect_clause"
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '0', '0', ?, ?, ?, ?)
                        """);
                        PreparedStatement aggPs = prepare(conn, """
                        INSERT INTO "sqlflow"."dataflow_aggregation_table" (
                          "job_id", "lineage_hash", "db_instance", "user_name", "target_table", "target_column",
                          "source_table", "source_column", "star_stmt", "count", "earliest_execute_time",
                          "latest_execute_time", "create_time", "relation_type"
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '0', 1, ?, ?, ?, ?)
                        """);
                        PreparedStatement sqlMapPs = prepare(conn, """
                        INSERT INTO "sqlflow"."dataflow_sql_map_table" (
                          "job_id", "lineage_hash", "sql_hash", "sql", "process_type", "file_path",
                          "execute_time", "db_instance", "db", "user", "user_id", "create_time", "transform"
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """);
                        PreparedStatement trPs = prepare(conn, """
                        INSERT INTO "sqlflow"."lineage_transform" (
                          "job_id", "lineage_hash", "code", "type", "index", "coordinate", "create_time"
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """);
                        PreparedStatement tblDfPs = prepare(conn, """
                        INSERT INTO "sqlflow"."table_level_dataflow" (
                          "job_id", "db_instance", "user_name", "target_table", "source_table", "execute_time", "create_time", "relation_type"
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """);
                        PreparedStatement tblAggPs = prepare(conn, """
                        INSERT INTO "sqlflow"."table_level_aggregation" (
                          "job_id", "db_instance", "user_name", "target_table", "source_table", "count",
                          "earliest_execute_time", "latest_execute_time", "create_time", "relation_type"
                        ) VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?)
                        """)) {

                    for (Relationship rel : dataFlow.getRelationships()) {
                        if (rel == null || rel.getTarget() == null || rel.getSources() == null) {
                            continue;
                        }
                        RelationshipElement tgt = rel.getTarget();
                        String tgtTable = resolveElementTable(tgt, objById, colTableById);
                        String tgtCol = defaultIfBlank(tgt.getColumn(), colNameById.getOrDefault(tgt.getId(), ""));
                        String relType = defaultIfBlank(rel.getType(), "fdd");
                        String effectType = defaultIfBlank(rel.getEffectType(), "select");

                        for (RelationshipElement src : rel.getSources()) {
                            if (src == null) {
                                continue;
                            }
                            String srcTable = resolveElementTable(src, objById, colTableById);
                            String srcCol = defaultIfBlank(src.getColumn(), colNameById.getOrDefault(src.getId(), ""));
                            String lineageHash = CryptoService.md5Hex(jobId + "|" + srcTable + "|" + srcCol + "|" + tgtTable + "|" + tgtCol + "|" + relType);

                            SqlEntry matchedSql = matchSqlEntry(rel, sqlEntries, primaryFile, primarySql, primarySqlHash);
                            List<Transform> transforms = tgt.getTransforms() != null && !tgt.getTransforms().isEmpty()
                                    ? tgt.getTransforms()
                                    : src.getTransforms();
                            boolean hasTransform = transforms != null && !transforms.isEmpty();

                            dfPs.setString(1, jobId);
                            dfPs.setString(2, lineageHash);
                            dfPs.setString(3, defaultIfBlank(job.getDefaultServer(), "DEFAULT"));
                            dfPs.setString(4, job.getUserId());
                            dfPs.setString(5, tgtTable);
                            dfPs.setString(6, tgtCol);
                            dfPs.setString(7, srcTable);
                            dfPs.setString(8, srcCol);
                            dfPs.setTimestamp(9, now);
                            dfPs.setTimestamp(10, now);
                            dfPs.setString(11, relType);
                            dfPs.setString(12, "fdr".equalsIgnoreCase(relType) ? effectType : null);
                            dfPs.addBatch();

                            aggPs.setString(1, jobId);
                            aggPs.setString(2, lineageHash);
                            aggPs.setString(3, defaultIfBlank(job.getDefaultServer(), "DEFAULT"));
                            aggPs.setString(4, job.getUserId());
                            aggPs.setString(5, tgtTable);
                            aggPs.setString(6, tgtCol);
                            aggPs.setString(7, srcTable);
                            aggPs.setString(8, srcCol);
                            aggPs.setTimestamp(9, now);
                            aggPs.setTimestamp(10, now);
                            aggPs.setTimestamp(11, now);
                            aggPs.setString(12, relType);
                            aggPs.addBatch();

                            sqlMapPs.setString(1, jobId);
                            sqlMapPs.setString(2, lineageHash);
                            sqlMapPs.setString(3, matchedSql.sqlHash());
                            sqlMapPs.setString(4, matchedSql.sqlText());
                            sqlMapPs.setString(5, effectType);
                            sqlMapPs.setString(6, matchedSql.fileName());
                            sqlMapPs.setTimestamp(7, now);
                            sqlMapPs.setString(8, defaultIfBlank(job.getDefaultServer(), "DEFAULT"));
                            sqlMapPs.setString(9, defaultIfBlank(job.getDefaultDatabase(), "DEFAULT"));
                            sqlMapPs.setString(10, job.getUserName());
                            sqlMapPs.setString(11, job.getUserId());
                            sqlMapPs.setTimestamp(12, now);
                            sqlMapPs.setString(13, hasTransform ? "Y" : "N");
                            sqlMapPs.addBatch();

                            if (hasTransform) {
                                short idx = 0;
                                for (Transform tr : transforms) {
                                    trPs.setString(1, jobId);
                                    trPs.setString(2, lineageHash);
                                    trPs.setString(3, tr.getCode());
                                    trPs.setString(4, tr.getType());
                                    trPs.setShort(5, idx++);
                                    trPs.setString(6, formatCoords(tgt.getCoordinates()));
                                    trPs.setTimestamp(7, now);
                                    trPs.addBatch();
                                }
                            }

                            String tblKey = srcTable + "->" + tgtTable + ":" + relType;
                            if (!srcTable.isEmpty() && !tgtTable.isEmpty() && seenTablePairs.add(tblKey)) {
                                tblDfPs.setString(1, jobId);
                                tblDfPs.setString(2, defaultIfBlank(job.getDefaultServer(), "DEFAULT"));
                                tblDfPs.setString(3, job.getUserId());
                                tblDfPs.setString(4, tgtTable);
                                tblDfPs.setString(5, srcTable);
                                tblDfPs.setTimestamp(6, now);
                                tblDfPs.setTimestamp(7, now);
                                tblDfPs.setString(8, relType);
                                tblDfPs.addBatch();

                                tblAggPs.setString(1, jobId);
                                tblAggPs.setString(2, defaultIfBlank(job.getDefaultServer(), "DEFAULT"));
                                tblAggPs.setString(3, job.getUserId());
                                tblAggPs.setString(4, tgtTable);
                                tblAggPs.setString(5, srcTable);
                                tblAggPs.setTimestamp(6, now);
                                tblAggPs.setTimestamp(7, now);
                                tblAggPs.setTimestamp(8, now);
                                tblAggPs.setString(9, relType);
                                tblAggPs.addBatch();
                            }
                        }
                    }

                    dfPs.executeBatch();
                    aggPs.executeBatch();
                    sqlMapPs.executeBatch();
                    trPs.executeBatch();
                    tblDfPs.executeBatch();
                    tblAggPs.executeBatch();
                }
            }

            // 4. Persist left_most_table & left_most_source_table
            List<LeftMostSourceTable.TargetTable> lmTables = leftMost != null
                    ? (leftMost.getElements() != null ? leftMost.getElements() : leftMost.getTargetTables())
                    : null;
            if (lmTables != null && !lmTables.isEmpty()) {
                try (PreparedStatement lmPs = prepare(conn, """
                        INSERT INTO "sqlflow"."left_most_source_table" (
                          "job_id", "lineage_hash", "db_instance", "user_name", "target_table", "target_column",
                          "source_table", "source_column", "star_stmt", "expand_star", "earliest_execute_time",
                          "latest_execute_time", "lineage_paths", "create_time"
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '0', '0', ?, ?, ?, ?)
                        """);
                        PreparedStatement lmtPs = prepare(conn, """
                        INSERT INTO "sqlflow"."left_most_table" (
                          "job_id", "lineage_hash", "db_instance", "user_name", "target_table", "target_column",
                          "source_table", "source_column", "star_stmt", "expand_star", "earliest_execute_time",
                          "latest_execute_time", "lineage_paths", "create_time"
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '0', '0', ?, ?, ?, ?)
                        """)) {
                    for (LeftMostSourceTable.TargetTable tt : lmTables) {
                        if (tt == null || tt.getColumns() == null) {
                            continue;
                        }
                        String tgtTblName = defaultIfBlank(tt.getName(), tt.getRelationName());
                        for (LeftMostSourceTable.TargetColumn tc : tt.getColumns()) {
                            if (tc == null || tc.getSourceColumns() == null) {
                                continue;
                            }
                            for (LeftMostSourceTable.SourceTableColumn ref : tc.getSourceColumns()) {
                                String srcTblName = defaultIfBlank(ref.getTableName(), "");
                                String srcColName = defaultIfBlank(ref.getName(), "");
                                String lHash = CryptoService.md5Hex(jobId + "|lm|" + srcTblName + "|" + srcColName + "|" + tgtTblName + "|" + tc.getName());
                                String pathStr = srcTblName + "." + srcColName + " -> " + tgtTblName + "." + tc.getName();
                                for (PreparedStatement p : List.of(lmPs, lmtPs)) {
                                    p.setString(1, jobId);
                                    p.setString(2, lHash);
                                    p.setString(3, defaultIfBlank(job.getDefaultServer(), "DEFAULT"));
                                    p.setString(4, job.getUserId());
                                    p.setString(5, tgtTblName);
                                    p.setString(6, tc.getName());
                                    p.setString(7, srcTblName);
                                    p.setString(8, srcColName);
                                    p.setTimestamp(9, now);
                                    p.setTimestamp(10, now);
                                    p.setString(11, pathStr);
                                    p.setTimestamp(12, now);
                                    p.addBatch();
                                }
                            }
                        }
                    }
                    lmPs.executeBatch();
                    lmtPs.executeBatch();
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to persist job {} into DuckDB: {}", jobId, e.getMessage());
        }
    }

    @Override
    public synchronized void updateJobStatus(String jobId, String status, String errorMessage, long executeTimeMillis) {
        try (PreparedStatement ps = prepare(getConnection(), """
                UPDATE "sqlflow"."job"
                SET "status" = ?, "error_message" = ?, "execute_time_millis" = ?, "update_time" = ?
                WHERE "job_id" = ?
                """)) {
            ps.setString(1, status);
            ps.setString(2, errorMessage);
            ps.setLong(3, executeTimeMillis);
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.setString(5, jobId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to update DuckDB job {}: {}", jobId, e.getMessage());
        }
    }

    @Override
    public synchronized void deleteJobData(String jobId) {
        try {
            deleteJobDataInternal(getConnection(), jobId);
        } catch (SQLException e) {
            log.warn("Failed to delete DuckDB job {}: {}", jobId, e.getMessage());
        }
    }

    private void deleteJobDataInternal(Connection conn, String jobId) throws SQLException {
        for (String table : ALL_14_TABLES) {
            try (PreparedStatement ps = prepare(conn, "DELETE FROM \"sqlflow\".\"" + table + "\" WHERE \"job_id\" = ?")) {
                ps.setString(1, jobId);
                ps.executeUpdate();
            }
        }
    }

    @Override
    public synchronized List<List<String>> fetchRegularJobTableRelationSqls(
            String jobId, String sourceTable, String sourceColumn, String targetTable, String targetColumn) {
        List<List<String>> result = new ArrayList<>();
        String sql = """
                SELECT DISTINCT m."sql", m."sql_hash", m."file_path", a."source_table", a."target_table"
                FROM "sqlflow"."dataflow_aggregation_table" a
                JOIN "sqlflow"."dataflow_sql_map_table" m
                  ON a."job_id" = m."job_id" AND a."lineage_hash" = m."lineage_hash"
                WHERE a."job_id" = ?
                """;
        try (PreparedStatement ps = prepare(getConnection(), sql)) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String srcTbl = rs.getString("source_table");
                    String tgtTbl = rs.getString("target_table");
                    if (tableMatches(srcTbl, sourceTable) && tableMatches(tgtTbl, targetTable)) {
                        result.add(List.of(
                                defaultIfBlank(rs.getString("sql"), ""),
                                defaultIfBlank(rs.getString("sql_hash"), ""),
                                defaultIfBlank(rs.getString("file_path"), "")));
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("fetchRegularJobTableRelationSqls failed: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public synchronized List<Map<String, Object>> fetchTableRelationSqlBySqlHash(String jobId, String sqlHash) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String query = (jobId != null && !jobId.isBlank())
                ? "SELECT \"job_id\", \"sql_hash\", \"sql\", \"file_path\", \"process_type\" FROM \"sqlflow\".\"dataflow_sql_map_table\" WHERE \"job_id\" = ? AND \"sql_hash\" = ?"
                : "SELECT \"job_id\", \"sql_hash\", \"sql\", \"file_path\", \"process_type\" FROM \"sqlflow\".\"dataflow_sql_map_table\" WHERE \"sql_hash\" = ?";
        try (PreparedStatement ps = prepare(getConnection(), query)) {
            if (jobId != null && !jobId.isBlank()) {
                ps.setString(1, jobId);
                ps.setString(2, sqlHash);
            } else {
                ps.setString(1, sqlHash);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("jobId", rs.getString("job_id"));
                    m.put("sqlHash", rs.getString("sql_hash"));
                    m.put("sql", rs.getString("sql"));
                    m.put("filePath", rs.getString("file_path"));
                    m.put("processType", rs.getString("process_type"));
                    rows.add(m);
                }
            }
        } catch (SQLException e) {
            log.warn("fetchTableRelationSqlBySqlHash failed: {}", e.getMessage());
        }
        return rows;
    }

    @Override
    public synchronized List<Map<String, Object>> fetchLeftMostTableLineageSQL(
            String jobId, String targetTable, String targetColumn, String sourceTable, String sourceColumn) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String query = """
                SELECT l."source_table", l."source_column", l."target_table", l."target_column", l."lineage_paths", s."sql", s."sql_hash"
                FROM "sqlflow"."left_most_source_table" l
                LEFT JOIN "sqlflow"."sql_table" s ON l."job_id" = s."job_id"
                WHERE l."job_id" = ?
                """;
        try (PreparedStatement ps = prepare(getConnection(), query)) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (targetTable != null && !targetTable.isBlank() && !tableMatches(rs.getString("target_table"), targetTable)) {
                        continue;
                    }
                    if (sourceTable != null && !sourceTable.isBlank() && !tableMatches(rs.getString("source_table"), sourceTable)) {
                        continue;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sourceTable", rs.getString("source_table"));
                    m.put("sourceColumn", rs.getString("source_column"));
                    m.put("targetTable", rs.getString("target_table"));
                    m.put("targetColumn", rs.getString("target_column"));
                    m.put("lineagePaths", rs.getString("lineage_paths"));
                    m.put("sql", rs.getString("sql"));
                    m.put("sqlHash", rs.getString("sql_hash"));
                    rows.add(m);
                }
            }
        } catch (SQLException e) {
            log.warn("fetchLeftMostTableLineageSQL failed: {}", e.getMessage());
        }
        return rows;
    }

    @Override
    public synchronized Map<String, Object> persistAndFetchSourceTableAffectSQL(
            String jobId, String batchId, DataFlow baseFlow, List<SqlEntry> affectSqls, boolean columnLevel) {
        Timestamp now = Timestamp.from(Instant.now());
        List<Map<String, Object>> affectedRows = new ArrayList<>();
        try {
            Connection conn = getConnection();
            try (PreparedStatement outPs = prepare(conn, """
                    INSERT INTO "sqlflow"."column_level_out_going" (
                      "job_id", "batch_id", "db_instance", "source_table", "column", "table_name_consumed",
                      "column_consumed", "user_name", "stmt_consumed", "first_execution", "last_execution",
                      "number_of_executions", "create_time"
                    ) VALUES (?, ?, 'DEFAULT', ?, ?, ?, ?, 'admin', ?, ?, ?, '1', ?)
                    """);
                    PreparedStatement usrPs = prepare(conn, """
                    INSERT INTO "sqlflow"."column_level_out_going_user" (
                      "job_id", "batch_id", "db_instance", "user_name", "stmt_consumed", "create_time"
                    ) VALUES (?, ?, 'DEFAULT', 'admin', ?, ?)
                    """)) {
                for (SqlEntry se : affectSqls) {
                    usrPs.setString(1, jobId);
                    usrPs.setString(2, batchId);
                    usrPs.setString(3, se.sqlText());
                    usrPs.setTimestamp(4, now);
                    usrPs.addBatch();

                    List<DbObject> baseObjs = CatalogUtils.collectAllDbObjects(baseFlow);
                    if (!baseObjs.isEmpty()) {
                        for (DbObject dbo : baseObjs) {
                            if (dbo.getName() != null && se.sqlText().toLowerCase(Locale.ROOT).contains(dbo.getName().toLowerCase(Locale.ROOT))) {
                                String colName = (dbo.getColumns() != null && !dbo.getColumns().isEmpty())
                                        ? dbo.getColumns().get(0).getName()
                                        : "*";
                                outPs.setString(1, jobId);
                                outPs.setString(2, batchId);
                                outPs.setString(3, dbo.getName());
                                outPs.setString(4, colName);
                                outPs.setString(5, dbo.getName());
                                outPs.setString(6, colName);
                                outPs.setString(7, se.sqlText());
                                outPs.setString(8, now.toString());
                                outPs.setString(9, now.toString());
                                outPs.setTimestamp(10, now);
                                outPs.addBatch();

                                Map<String, Object> row = new LinkedHashMap<>();
                                row.put("sourceTable", dbo.getName());
                                row.put("column", colName);
                                row.put("stmtConsumed", se.sqlText());
                                affectedRows.add(row);
                            }
                        }
                    }
                }
                usrPs.executeBatch();
                outPs.executeBatch();
            }
        } catch (SQLException e) {
            log.warn("persistAndFetchSourceTableAffectSQL failed: {}", e.getMessage());
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("jobId", jobId);
        res.put("batchId", batchId);
        res.put("columnLevel", columnLevel);
        res.put("affectedSqls", affectedRows);
        return res;
    }

    @Override
    public synchronized Path exportJobParquet(String jobId, String batchId, Path targetParquetFile) {
        try {
            if (targetParquetFile.getParent() != null) {
                Files.createDirectories(targetParquetFile.getParent());
            }
            Files.deleteIfExists(targetParquetFile);
            String escapedJobId = (jobId == null ? "" : jobId).replace("'", "''");
            String escapedPath = targetParquetFile.toAbsolutePath().toString().replace("'", "''");
            String copySql = "COPY (SELECT * FROM \"sqlflow\".\"dataflow_table\" WHERE \"job_id\" = '"
                    + escapedJobId + "') TO '" + escapedPath + "' (FORMAT PARQUET)";
            try (Statement st = getConnection().createStatement()) {
                st.execute(qualify(copySql));
            }
            return targetParquetFile;
        } catch (Exception e) {
            throw new IllegalStateException("DuckDB Parquet export failed for job " + jobId + ": " + e.getMessage(), e);
        }
    }

    private static String qualifyTable(DbObject dbo) {
        StringBuilder sb = new StringBuilder();
        if (dbo.getDatabase() != null && !dbo.getDatabase().isBlank() && !"DEFAULT".equalsIgnoreCase(dbo.getDatabase())) {
            sb.append(dbo.getDatabase()).append('.');
        }
        if (dbo.getSchema() != null && !dbo.getSchema().isBlank() && !"DEFAULT".equalsIgnoreCase(dbo.getSchema())) {
            sb.append(dbo.getSchema()).append('.');
        }
        sb.append(dbo.getName() != null ? dbo.getName() : "");
        return sb.toString();
    }

    private static String resolveElementTable(
            RelationshipElement el, Map<String, DbObject> objById, Map<String, String> colTableById) {
        if (el.getParentName() != null && !el.getParentName().isBlank()) {
            return el.getParentName();
        }
        if (el.getParentId() != null && objById.containsKey(el.getParentId())) {
            return qualifyTable(objById.get(el.getParentId()));
        }
        if (el.getId() != null && colTableById.containsKey(el.getId())) {
            return colTableById.get(el.getId());
        }
        return "";
    }

    private static SqlEntry matchSqlEntry(
            Relationship rel, List<SqlEntry> entries, String fallbackFile, String fallbackSql, String fallbackHash) {
        if (entries == null || entries.isEmpty()) {
            return new SqlEntry(fallbackFile, fallbackSql, fallbackHash);
        }
        if (rel.getSqlHash() != null) {
            for (SqlEntry e : entries) {
                if (rel.getSqlHash().equals(e.sqlHash())) {
                    return e;
                }
            }
        }
        return entries.get(0);
    }

    private static String formatCoords(List<Coordinate> coords) {
        if (coords == null || coords.isEmpty()) {
            return "";
        }
        Coordinate c = coords.get(0);
        return "[" + c.getX() + "," + c.getY() + "]";
    }

    private static boolean tableMatches(String candidate, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        if (candidate == null) {
            return false;
        }
        String c = candidate.toLowerCase(Locale.ROOT);
        String f = filter.toLowerCase(Locale.ROOT);
        return c.equals(f) || c.endsWith("." + f) || f.endsWith("." + c);
    }

    private static String defaultIfBlank(String val, String def) {
        return (val == null || val.isBlank()) ? def : val;
    }
}
