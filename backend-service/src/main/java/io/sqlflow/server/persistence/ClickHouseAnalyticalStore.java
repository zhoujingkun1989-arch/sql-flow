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
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ClickHouse analytical persistence strategy ({@code regular} / {@code scale} profiles, {@code 02 §7.4},
 * {@code OBL-069}) managing all 14 {@code sqlflow.*} {@code MergeTree} tables with 6-month TTL,
 * {@code ngrambf_v1} data-skipping indexes ({@code lineage_index}, {@code sql_index}, {@code batch_index}),
 * and ClickHouse {@code ALTER TABLE sqlflow.<table> DELETE / UPDATE} mutation syntax.
 */
public class ClickHouseAnalyticalStore implements AnalyticalStore {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseAnalyticalStore.class);

    @FunctionalInterface
    public interface ConnectionSupplier {
        Connection open() throws SQLException;
    }

    private final SqlflowRuntimeConfig config;
    private final ConnectionSupplier connectionSupplier;

    public ClickHouseAnalyticalStore(SqlflowRuntimeConfig config) {
        this.config = config;
        this.connectionSupplier = this::openDefaultConnection;
    }

    public ClickHouseAnalyticalStore(SqlflowRuntimeConfig config, ConnectionSupplier connectionSupplier) {
        this.config = config;
        this.connectionSupplier = Objects.requireNonNull(connectionSupplier, "connectionSupplier");
    }

    @Override
    public String storeType() {
        return "clickhouse";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    private Connection openDefaultConnection() throws SQLException {
        String url = config.getRaw("clickhouse.url", "jdbc:clickhouse://localhost:8123/sqlflow");
        String user = config.getRaw("clickhouse.username", "default");
        String pass = config.getRaw("clickhouse.password", "");
        return DriverManager.getConnection(url, user, pass);
    }

    private Connection openConnection() throws SQLException {
        return connectionSupplier.open();
    }

    /**
     * Authoritative ClickHouse DDL for {@code CREATE DATABASE IF NOT EXISTS sqlflow} and all 14
     * {@code sqlflow.*} {@code MergeTree} analytical tables ({@code 02 §7.3 & §7.4}).
     */
    public static List<String> buildClickHouseSetupStatements() {
        return List.of(
                "CREATE DATABASE IF NOT EXISTS sqlflow",
                // 1. Raw column-level lineage edges
                """
                CREATE TABLE IF NOT EXISTS sqlflow.dataflow_table (
                  job_id String,
                  lineage_hash String,
                  db_instance String,
                  user_name String,
                  target_table String,
                  target_column String,
                  source_table String,
                  source_column String,
                  star_stmt String,
                  expand_star String,
                  execute_time DateTime,
                  create_time DateTime,
                  relation_type String,
                  indirect_clause String,
                  INDEX lineage_index(lineage_hash) TYPE ngrambf_v1(3, 256, 2, 0) GRANULARITY 4
                ) ENGINE = MergeTree
                PARTITION BY toDate(create_time)
                ORDER BY (job_id)
                TTL create_time + toIntervalMonth(6)
                SETTINGS index_granularity = 8192
                """,
                // 2. Deduplicated/aggregated column-level lineage edges
                """
                CREATE TABLE IF NOT EXISTS sqlflow.dataflow_aggregation_table (
                  job_id String,
                  lineage_hash String,
                  db_instance String,
                  user_name String,
                  target_table String,
                  target_column String,
                  source_table String,
                  source_column String,
                  star_stmt String,
                  count Int64,
                  earliest_execute_time DateTime,
                  latest_execute_time DateTime,
                  create_time DateTime,
                  relation_type String,
                  INDEX lineage_index(lineage_hash) TYPE ngrambf_v1(3, 256, 2, 0) GRANULARITY 4
                ) ENGINE = MergeTree
                PARTITION BY toDate(create_time)
                ORDER BY (job_id)
                TTL create_time + toIntervalMonth(6)
                SETTINGS index_granularity = 8192
                """,
                // 3. Discovered catalog objects per job
                """
                CREATE TABLE IF NOT EXISTS sqlflow.dataflow_table_metadata (
                  job_id String,
                  database String,
                  schema String,
                  table String,
                  type Int16,
                  create_time DateTime,
                  process_ids String
                ) ENGINE = MergeTree
                PARTITION BY toDate(create_time)
                ORDER BY (job_id)
                TTL create_time + toIntervalMonth(6)
                SETTINGS index_granularity = 8192
                """,
                // 4. Lineage edge <-> SQL statement execution log mapping
                """
                CREATE TABLE IF NOT EXISTS sqlflow.dataflow_sql_map_table (
                  job_id String,
                  lineage_hash String,
                  sql_hash String,
                  sql String,
                  process_type String,
                  file_path String,
                  execute_time DateTime,
                  db_instance String,
                  db String,
                  user String,
                  user_id String,
                  pid String,
                  xid String,
                  create_time DateTime,
                  transform String DEFAULT 'N',
                  INDEX lineage_index(lineage_hash) TYPE ngrambf_v1(3, 256, 2, 0) GRANULARITY 4,
                  INDEX sql_index(sql_hash) TYPE ngrambf_v1(3, 256, 2, 0) GRANULARITY 4
                ) ENGINE = MergeTree
                PARTITION BY toDate(create_time)
                ORDER BY (job_id)
                TTL create_time + toIntervalMonth(6)
                SETTINGS index_granularity = 8192
                """,
                // 5. Unique SQL text store per job
                """
                CREATE TABLE IF NOT EXISTS sqlflow.sql_table (
                  job_id String,
                  sql_hash String,
                  type String,
                  sql String,
                  create_time DateTime,
                  INDEX sql_index(sql_hash) TYPE ngrambf_v1(3, 256, 2, 0) GRANULARITY 4
                ) ENGINE = MergeTree
                PARTITION BY toDate(create_time)
                ORDER BY (job_id)
                TTL create_time + toIntervalMonth(6)
                SETTINGS index_granularity = 8192
                """,
                // 6. Transitive root upstream closure
                """
                CREATE TABLE IF NOT EXISTS sqlflow.left_most_table (
                  job_id String,
                  lineage_hash String,
                  db_instance String,
                  user_name String,
                  target_table String,
                  target_column String,
                  source_table String,
                  source_column String,
                  star_stmt String,
                  expand_star String,
                  earliest_execute_time DateTime,
                  latest_execute_time DateTime,
                  lineage_paths String,
                  create_time DateTime,
                  INDEX lineage_index(lineage_hash) TYPE ngrambf_v1(3, 256, 2, 0) GRANULARITY 4
                ) ENGINE = MergeTree
                PARTITION BY toDate(create_time)
                ORDER BY (job_id)
                TTL create_time + toIntervalMonth(6)
                SETTINGS index_granularity = 8192
                """,
                // 7. Leaf-only root upstream source table/column mapping
                """
                CREATE TABLE IF NOT EXISTS sqlflow.left_most_source_table (
                  job_id String,
                  lineage_hash String,
                  db_instance String,
                  user_name String,
                  target_table String,
                  target_column String,
                  source_table String,
                  source_column String,
                  star_stmt String,
                  expand_star String,
                  earliest_execute_time DateTime,
                  latest_execute_time DateTime,
                  lineage_paths String,
                  create_time DateTime,
                  INDEX lineage_index(lineage_hash) TYPE ngrambf_v1(3, 256, 2, 0) GRANULARITY 4
                ) ENGINE = MergeTree
                PARTITION BY toDate(create_time)
                ORDER BY (job_id)
                TTL create_time + toIntervalMonth(6)
                SETTINGS index_granularity = 8192
                """,
                // 8. Downstream SQL impact analysis rows
                """
                CREATE TABLE IF NOT EXISTS sqlflow.column_level_out_going (
                  job_id String,
                  batch_id String,
                  db_instance String,
                  source_table String,
                  column String,
                  table_name_consumed String,
                  column_consumed String,
                  user_name String,
                  stmt_consumed String,
                  first_execution String,
                  last_execution String,
                  number_of_executions String,
                  create_time DateTime,
                  INDEX batch_index(batch_id) TYPE ngrambf_v1(3, 256, 2, 0) GRANULARITY 4
                ) ENGINE = MergeTree
                PARTITION BY toDate(create_time)
                ORDER BY (job_id)
                TTL create_time + toIntervalMonth(6)
                SETTINGS index_granularity = 8192
                """,
                // 9. Raw table-level lineage edges
                """
                CREATE TABLE IF NOT EXISTS sqlflow.table_level_dataflow (
                  job_id String,
                  db_instance String,
                  user_name String,
                  target_table String,
                  source_table String,
                  execute_time DateTime,
                  create_time DateTime,
                  relation_type String
                ) ENGINE = MergeTree
                PARTITION BY toDate(create_time)
                ORDER BY (job_id)
                TTL create_time + toIntervalMonth(6)
                SETTINGS index_granularity = 8192
                """,
                // 10. Aggregated table-level lineage edges
                """
                CREATE TABLE IF NOT EXISTS sqlflow.table_level_aggregation (
                  job_id String,
                  db_instance String,
                  user_name String,
                  target_table String,
                  source_table String,
                  count Int64,
                  earliest_execute_time DateTime,
                  latest_execute_time DateTime,
                  create_time DateTime,
                  relation_type String
                ) ENGINE = MergeTree
                PARTITION BY toDate(create_time)
                ORDER BY (job_id)
                TTL create_time + toIntervalMonth(6)
                SETTINGS index_granularity = 8192
                """,
                // 11. Downstream consumer user summary
                """
                CREATE TABLE IF NOT EXISTS sqlflow.column_level_out_going_user (
                  job_id String,
                  batch_id String,
                  db_instance String,
                  user_name String,
                  stmt_consumed String,
                  create_time DateTime,
                  INDEX batch_index(batch_id) TYPE ngrambf_v1(3, 256, 2, 0) GRANULARITY 4
                ) ENGINE = MergeTree
                PARTITION BY toDate(create_time)
                ORDER BY (job_id)
                TTL create_time + toIntervalMonth(6)
                SETTINGS index_granularity = 8192
                """,
                // 12. Statement-level transform expressions
                """
                CREATE TABLE IF NOT EXISTS sqlflow.sql_transform (
                  job_id String,
                  sql_hash String,
                  code String,
                  type String,
                  index Int16,
                  create_time DateTime,
                  INDEX sql_index(sql_hash) TYPE ngrambf_v1(3, 256, 2, 0) GRANULARITY 4
                ) ENGINE = MergeTree
                PARTITION BY toDate(create_time)
                ORDER BY (job_id)
                TTL create_time + toIntervalMonth(6)
                SETTINGS index_granularity = 8192
                """,
                // 13. Edge-level transform expressions + source coordinates
                """
                CREATE TABLE IF NOT EXISTS sqlflow.lineage_transform (
                  job_id String,
                  lineage_hash String,
                  code String,
                  type String,
                  index Int16,
                  coordinate String,
                  create_time DateTime,
                  INDEX lineage_index(lineage_hash) TYPE ngrambf_v1(3, 256, 2, 0) GRANULARITY 4
                ) ENGINE = MergeTree
                PARTITION BY toDate(create_time)
                ORDER BY (job_id)
                TTL create_time + toIntervalMonth(6)
                SETTINGS index_granularity = 8192
                """,
                // 14. Persistent job execution registry
                """
                CREATE TABLE IF NOT EXISTS sqlflow.job (
                  job_id String,
                  job_name String,
                  user_id String,
                  user_email String,
                  group String,
                  db_vendor String,
                  status String,
                  error_message String,
                  session_id String,
                  execute_time String,
                  execute_time_millis Int64 DEFAULT 0,
                  create_time DateTime,
                  update_time DateTime,
                  default_server String,
                  default_database String,
                  default_schema String,
                  persist UInt8 DEFAULT 0,
                  incremental UInt8 DEFAULT 0,
                  schedulable UInt8 DEFAULT 0,
                  cron_expression String,
                  last_execute_time String,
                  sqlsource String,
                  version String,
                  ip String,
                  os String,
                  browser String,
                  use_method String,
                  api String,
                  query_id String,
                  has_metadata UInt8 DEFAULT 0
                ) ENGINE = MergeTree
                PARTITION BY toDate(create_time)
                ORDER BY (job_id, create_time)
                TTL create_time + toIntervalMonth(6)
                SETTINGS index_granularity = 8192
                """);
    }

    @Override
    public void initSchema() {
        try (Connection conn = openConnection(); Statement st = conn.createStatement()) {
            for (String ddl : buildClickHouseSetupStatements()) {
                st.execute(ddl);
            }
        } catch (SQLException e) {
            log.info("ClickHouse endpoint not reachable during startup (lazy mode enabled): {}", e.getMessage());
        }
    }

    @Override
    public List<String> listTables() {
        List<String> list = new ArrayList<>();
        try (Connection conn = openConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT name FROM system.tables WHERE database = 'sqlflow' ORDER BY name");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(rs.getString(1));
            }
        } catch (SQLException e) {
            return DuckDbAnalyticalStore.ALL_14_TABLES;
        }
        return list;
    }

    @Override
    public void persistJobLineage(
            JobDetail job, DataFlow dataFlow, List<SqlEntry> sqlEntries, LeftMostSourceTable leftMost) {
        if (job == null || job.getJobId() == null) {
            return;
        }
        String jobId = job.getJobId();
        Timestamp now = Timestamp.from(Instant.now());
        try (Connection conn = openConnection()) {
            if (!Boolean.TRUE.equals(job.getIncremental())) {
                deleteJobDataInternal(conn, jobId);
            } else {
                try (PreparedStatement delJob =
                        conn.prepareStatement("ALTER TABLE sqlflow.job DELETE WHERE job_id = ?")) {
                    delJob.setString(1, jobId);
                    delJob.executeUpdate();
                }
            }

            // 1. Insert job row
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO sqlflow.job (
                      job_id, job_name, user_id, user_email, group, db_vendor, status, error_message,
                      session_id, execute_time, execute_time_millis, create_time, update_time,
                      default_server, default_database, default_schema, persist, incremental,
                      schedulable, cron_expression, last_execute_time, sqlsource, version, has_metadata
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                ps.setString(1, jobId);
                ps.setString(2, nn(job.getJobName()));
                ps.setString(3, nn(job.getUserId()));
                ps.setString(4, nn(job.getUserEmail()));
                ps.setString(5, nn(job.getGroup()));
                ps.setString(6, nn(job.getDbVendor()));
                ps.setString(7, job.getStatus() != null ? job.getStatus().getWireValue() : "success");
                ps.setString(8, nn(job.getErrorMessage()));
                ps.setString(9, nn(job.getSessionId()));
                ps.setString(10, nn(job.getExecuteTime()));
                ps.setLong(11, job.getExecuteTimeMillis() != null ? job.getExecuteTimeMillis() : 0L);
                ps.setTimestamp(12, now);
                ps.setTimestamp(13, now);
                ps.setString(14, nn(job.getDefaultServer()));
                ps.setString(15, nn(job.getDefaultDatabase()));
                ps.setString(16, nn(job.getDefaultSchema()));
                ps.setInt(17, Boolean.TRUE.equals(job.getPersist()) ? 1 : 0);
                ps.setInt(18, Boolean.TRUE.equals(job.getIncremental()) ? 1 : 0);
                ps.setInt(19, Boolean.TRUE.equals(job.getSchedulable()) ? 1 : 0);
                ps.setString(20, nn(job.getCronExpression()));
                ps.setString(21, nn(job.getLastExecuteTime()));
                ps.setString(22, nn(job.getSqlsource()));
                ps.setString(23, job.getVersion() != null ? job.getVersion() : "8.2.2.15");
                ps.setInt(24, Boolean.TRUE.equals(job.getHasMetadata()) ? 1 : 0);
                ps.executeUpdate();
            }

            // 2. Insert SQL statements & sql_transform
            String primarySql = "";
            String primarySqlHash = "";
            String primaryFile = "";
            if (sqlEntries != null && !sqlEntries.isEmpty()) {
                primarySql = nn(sqlEntries.get(0).sqlText());
                primarySqlHash = nn(sqlEntries.get(0).sqlHash());
                primaryFile = nn(sqlEntries.get(0).fileName());
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO sqlflow.sql_table (job_id, sql_hash, type, sql, create_time)
                        VALUES (?, ?, 'sql', ?, ?)
                        """)) {
                    for (SqlEntry entry : sqlEntries) {
                        ps.setString(1, jobId);
                        ps.setString(2, nn(entry.sqlHash()));
                        ps.setString(3, nn(entry.sqlText()));
                        ps.setTimestamp(4, now);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }

            if (dataFlow == null) {
                return;
            }

            // 3. Insert discovered catalog objects
            Map<String, DbObject> objById = new LinkedHashMap<>();
            Map<String, String> colTableById = new LinkedHashMap<>();
            Map<String, String> colNameById = new LinkedHashMap<>();
            List<DbObject> dbObjects = CatalogUtils.collectAllDbObjects(dataFlow);
            if (!dbObjects.isEmpty()) {
                try (PreparedStatement metaPs = conn.prepareStatement("""
                        INSERT INTO sqlflow.dataflow_table_metadata (job_id, database, schema, table, type, create_time, process_ids)
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
                        metaPs.setString(4, nn(dbo.getName()));
                        metaPs.setShort(5, typeCode);
                        metaPs.setTimestamp(6, now);
                        metaPs.setString(7, dbo.getProcessIds() != null ? String.join(",", dbo.getProcessIds()) : "");
                        metaPs.addBatch();

                        if (dbo.getColumns() != null) {
                            for (Column c : dbo.getColumns()) {
                                if (c != null && c.getId() != null) {
                                    colTableById.put(c.getId(), fullTable);
                                    colNameById.put(c.getId(), nn(c.getName()));
                                    colTableById.put(dbo.getId() + ":" + c.getName(), fullTable);
                                    colNameById.put(dbo.getId() + ":" + c.getName(), nn(c.getName()));
                                }
                            }
                        }
                    }
                    metaPs.executeBatch();
                }
            }

            // 4. Insert column-level & table-level lineage edges + transforms
            Set<String> seenTablePairs = new LinkedHashSet<>();
            if (dataFlow.getRelationships() != null) {
                try (PreparedStatement dfPs = conn.prepareStatement("""
                        INSERT INTO sqlflow.dataflow_table (
                          job_id, lineage_hash, db_instance, user_name, target_table, target_column,
                          source_table, source_column, star_stmt, expand_star, execute_time, create_time,
                          relation_type, indirect_clause
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '0', '0', ?, ?, ?, ?)
                        """);
                        PreparedStatement aggPs = conn.prepareStatement("""
                        INSERT INTO sqlflow.dataflow_aggregation_table (
                          job_id, lineage_hash, db_instance, user_name, target_table, target_column,
                          source_table, source_column, star_stmt, count, earliest_execute_time,
                          latest_execute_time, create_time, relation_type
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '0', 1, ?, ?, ?, ?)
                        """);
                        PreparedStatement sqlMapPs = conn.prepareStatement("""
                        INSERT INTO sqlflow.dataflow_sql_map_table (
                          job_id, lineage_hash, sql_hash, sql, process_type, file_path,
                          execute_time, db_instance, db, user, user_id, pid, xid, create_time, transform
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '', '', ?, ?)
                        """);
                        PreparedStatement trPs = conn.prepareStatement("""
                        INSERT INTO sqlflow.lineage_transform (
                          job_id, lineage_hash, code, type, index, coordinate, create_time
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """);
                        PreparedStatement sqlTrPs = conn.prepareStatement("""
                        INSERT INTO sqlflow.sql_transform (
                          job_id, sql_hash, code, type, index, create_time
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """);
                        PreparedStatement tblDfPs = conn.prepareStatement("""
                        INSERT INTO sqlflow.table_level_dataflow (
                          job_id, db_instance, user_name, target_table, source_table, execute_time, create_time, relation_type
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """);
                        PreparedStatement tblAggPs = conn.prepareStatement("""
                        INSERT INTO sqlflow.table_level_aggregation (
                          job_id, db_instance, user_name, target_table, source_table, count,
                          earliest_execute_time, latest_execute_time, create_time, relation_type
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
                            String lineageHash = CryptoService.md5Hex(
                                    jobId + "|" + srcTable + "|" + srcCol + "|" + tgtTable + "|" + tgtCol + "|" + relType);
                            SqlEntry matchedSql = matchSqlEntry(rel, sqlEntries, primaryFile, primarySql, primarySqlHash);
                            List<Transform> transforms = tgt.getTransforms() != null && !tgt.getTransforms().isEmpty()
                                    ? tgt.getTransforms()
                                    : src.getTransforms();
                            boolean hasTransform = transforms != null && !transforms.isEmpty();

                            dfPs.setString(1, jobId);
                            dfPs.setString(2, lineageHash);
                            dfPs.setString(3, defaultIfBlank(job.getDefaultServer(), "DEFAULT"));
                            dfPs.setString(4, nn(job.getUserId()));
                            dfPs.setString(5, tgtTable);
                            dfPs.setString(6, tgtCol);
                            dfPs.setString(7, srcTable);
                            dfPs.setString(8, srcCol);
                            dfPs.setTimestamp(9, now);
                            dfPs.setTimestamp(10, now);
                            dfPs.setString(11, relType);
                            dfPs.setString(12, "fdr".equalsIgnoreCase(relType) ? effectType : "");
                            dfPs.addBatch();

                            aggPs.setString(1, jobId);
                            aggPs.setString(2, lineageHash);
                            aggPs.setString(3, defaultIfBlank(job.getDefaultServer(), "DEFAULT"));
                            aggPs.setString(4, nn(job.getUserId()));
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
                            sqlMapPs.setString(3, nn(matchedSql.sqlHash()));
                            sqlMapPs.setString(4, nn(matchedSql.sqlText()));
                            sqlMapPs.setString(5, effectType);
                            sqlMapPs.setString(6, nn(matchedSql.fileName()));
                            sqlMapPs.setTimestamp(7, now);
                            sqlMapPs.setString(8, defaultIfBlank(job.getDefaultServer(), "DEFAULT"));
                            sqlMapPs.setString(9, defaultIfBlank(job.getDefaultDatabase(), "DEFAULT"));
                            sqlMapPs.setString(10, nn(job.getUserName()));
                            sqlMapPs.setString(11, nn(job.getUserId()));
                            sqlMapPs.setTimestamp(12, now);
                            sqlMapPs.setString(13, hasTransform ? "Y" : "N");
                            sqlMapPs.addBatch();

                            if (hasTransform) {
                                short idx = 0;
                                for (Transform tr : transforms) {
                                    short curIdx = idx++;
                                    trPs.setString(1, jobId);
                                    trPs.setString(2, lineageHash);
                                    trPs.setString(3, nn(tr.getCode()));
                                    trPs.setString(4, nn(tr.getType()));
                                    trPs.setShort(5, curIdx);
                                    trPs.setString(6, formatCoords(tgt.getCoordinates()));
                                    trPs.setTimestamp(7, now);
                                    trPs.addBatch();

                                    sqlTrPs.setString(1, jobId);
                                    sqlTrPs.setString(2, nn(matchedSql.sqlHash()));
                                    sqlTrPs.setString(3, nn(tr.getCode()));
                                    sqlTrPs.setString(4, nn(tr.getType()));
                                    sqlTrPs.setShort(5, curIdx);
                                    sqlTrPs.setTimestamp(6, now);
                                    sqlTrPs.addBatch();
                                }
                            }

                            String tblKey = srcTable + "->" + tgtTable + ":" + relType;
                            if (!srcTable.isEmpty() && !tgtTable.isEmpty() && seenTablePairs.add(tblKey)) {
                                tblDfPs.setString(1, jobId);
                                tblDfPs.setString(2, defaultIfBlank(job.getDefaultServer(), "DEFAULT"));
                                tblDfPs.setString(3, nn(job.getUserId()));
                                tblDfPs.setString(4, tgtTable);
                                tblDfPs.setString(5, srcTable);
                                tblDfPs.setTimestamp(6, now);
                                tblDfPs.setTimestamp(7, now);
                                tblDfPs.setString(8, relType);
                                tblDfPs.addBatch();

                                tblAggPs.setString(1, jobId);
                                tblAggPs.setString(2, defaultIfBlank(job.getDefaultServer(), "DEFAULT"));
                                tblAggPs.setString(3, nn(job.getUserId()));
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
                    sqlTrPs.executeBatch();
                    tblDfPs.executeBatch();
                    tblAggPs.executeBatch();
                }
            }

            // 5. Insert left_most_source_table & left_most_table
            List<LeftMostSourceTable.TargetTable> lmTables = leftMost != null
                    ? (leftMost.getElements() != null ? leftMost.getElements() : leftMost.getTargetTables())
                    : null;
            if (lmTables != null && !lmTables.isEmpty()) {
                try (PreparedStatement lmPs = conn.prepareStatement("""
                        INSERT INTO sqlflow.left_most_source_table (
                          job_id, lineage_hash, db_instance, user_name, target_table, target_column,
                          source_table, source_column, star_stmt, expand_star, earliest_execute_time,
                          latest_execute_time, lineage_paths, create_time
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '0', '0', ?, ?, ?, ?)
                        """);
                        PreparedStatement lmtPs = conn.prepareStatement("""
                        INSERT INTO sqlflow.left_most_table (
                          job_id, lineage_hash, db_instance, user_name, target_table, target_column,
                          source_table, source_column, star_stmt, expand_star, earliest_execute_time,
                          latest_execute_time, lineage_paths, create_time
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '0', '0', ?, ?, ?, ?)
                        """)) {
                    for (LeftMostSourceTable.TargetTable tt : lmTables) {
                        if (tt == null || tt.getColumns() == null) {
                            continue;
                        }
                        String tgtTblName = defaultIfBlank(tt.getName(), tt.getRelationName());
                        for (TargetColWrap tcWrap : wrapTargetColumns(tt)) {
                            for (LeftMostSourceTable.SourceTableColumn ref : tcWrap.sources()) {
                                String srcTblName = defaultIfBlank(ref.getTableName(), "");
                                String srcColName = defaultIfBlank(ref.getName(), "");
                                String lHash = CryptoService.md5Hex(
                                        jobId + "|lm|" + srcTblName + "|" + srcColName + "|" + tgtTblName + "|" + tcWrap.name());
                                String pathStr = srcTblName + "." + srcColName + " -> " + tgtTblName + "." + tcWrap.name();
                                for (PreparedStatement p : List.of(lmPs, lmtPs)) {
                                    p.setString(1, jobId);
                                    p.setString(2, lHash);
                                    p.setString(3, defaultIfBlank(job.getDefaultServer(), "DEFAULT"));
                                    p.setString(4, nn(job.getUserId()));
                                    p.setString(5, tgtTblName);
                                    p.setString(6, nn(tcWrap.name()));
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
            log.debug("ClickHouse persistJobLineage skipped/failed for {}: {}", jobId, e.getMessage());
        }
    }

    private record TargetColWrap(String name, List<LeftMostSourceTable.SourceTableColumn> sources) {}

    private static List<TargetColWrap> wrapTargetColumns(LeftMostSourceTable.TargetTable tt) {
        List<TargetColWrap> out = new ArrayList<>();
        if (tt.getColumns() != null) {
            for (LeftMostSourceTable.TargetColumn tc : tt.getColumns()) {
                if (tc != null && tc.getSourceColumns() != null) {
                    out.add(new TargetColWrap(tc.getName(), tc.getSourceColumns()));
                }
            }
        }
        return out;
    }

    @Override
    public void updateJobStatus(String jobId, String status, String errorMessage, long executeTimeMillis) {
        try (Connection conn = openConnection();
                PreparedStatement ps = conn.prepareStatement("""
                        ALTER TABLE sqlflow.job
                        UPDATE status = ?, error_message = ?, execute_time_millis = ?, update_time = now()
                        WHERE job_id = ?
                        """)) {
            ps.setString(1, nn(status));
            ps.setString(2, nn(errorMessage));
            ps.setLong(3, executeTimeMillis);
            ps.setString(4, nn(jobId));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.debug("ClickHouse updateJobStatus skipped: {}", e.getMessage());
        }
    }

    @Override
    public void deleteJobData(String jobId) {
        try (Connection conn = openConnection()) {
            deleteJobDataInternal(conn, jobId);
        } catch (SQLException e) {
            log.debug("ClickHouse deleteJobData skipped: {}", e.getMessage());
        }
    }

    private void deleteJobDataInternal(Connection conn, String jobId) throws SQLException {
        for (String table : DuckDbAnalyticalStore.ALL_14_TABLES) {
            try (PreparedStatement ps =
                    conn.prepareStatement("ALTER TABLE sqlflow." + table + " DELETE WHERE job_id = ?")) {
                ps.setString(1, nn(jobId));
                ps.executeUpdate();
            }
        }
    }

    @Override
    public List<List<String>> fetchRegularJobTableRelationSqls(
            String jobId, String sourceTable, String sourceColumn, String targetTable, String targetColumn) {
        List<List<String>> result = new ArrayList<>();
        String sql = """
                SELECT DISTINCT m.sql, m.sql_hash, m.file_path, a.source_table, a.target_table
                FROM sqlflow.dataflow_aggregation_table AS a
                INNER JOIN sqlflow.dataflow_sql_map_table AS m
                  ON a.job_id = m.job_id AND a.lineage_hash = m.lineage_hash
                WHERE a.job_id = ?
                """;
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, nn(jobId));
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
            log.debug("ClickHouse fetchRegularJobTableRelationSqls skipped: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> fetchTableRelationSqlBySqlHash(String jobId, String sqlHash) {
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean hasJob = jobId != null && !jobId.isBlank();
        String query = hasJob
                ? "SELECT job_id, sql_hash, sql, file_path, process_type FROM sqlflow.dataflow_sql_map_table WHERE job_id = ? AND sql_hash = ?"
                : "SELECT job_id, sql_hash, sql, file_path, process_type FROM sqlflow.dataflow_sql_map_table WHERE sql_hash = ?";
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
            if (hasJob) {
                ps.setString(1, jobId);
                ps.setString(2, nn(sqlHash));
            } else {
                ps.setString(1, nn(sqlHash));
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
            log.debug("ClickHouse fetchTableRelationSqlBySqlHash skipped: {}", e.getMessage());
        }
        return rows;
    }

    @Override
    public List<Map<String, Object>> fetchLeftMostTableLineageSQL(
            String jobId, String targetTable, String targetColumn, String sourceTable, String sourceColumn) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String query = """
                SELECT l.source_table, l.source_column, l.target_table, l.target_column, l.lineage_paths, s.sql, s.sql_hash
                FROM sqlflow.left_most_source_table AS l
                LEFT JOIN sqlflow.sql_table AS s ON l.job_id = s.job_id
                WHERE l.job_id = ?
                """;
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, nn(jobId));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (targetTable != null && !targetTable.isBlank()
                            && !tableMatches(rs.getString("target_table"), targetTable)) {
                        continue;
                    }
                    if (sourceTable != null && !sourceTable.isBlank()
                            && !tableMatches(rs.getString("source_table"), sourceTable)) {
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
            log.debug("ClickHouse fetchLeftMostTableLineageSQL skipped: {}", e.getMessage());
        }
        return rows;
    }

    @Override
    public Map<String, Object> persistAndFetchSourceTableAffectSQL(
            String jobId, String batchId, DataFlow baseFlow, List<SqlEntry> affectSqls, boolean columnLevel) {
        Timestamp now = Timestamp.from(Instant.now());
        List<Map<String, Object>> affectedRows = new ArrayList<>();
        try (Connection conn = openConnection()) {
            if (affectSqls != null && !affectSqls.isEmpty()) {
                try (PreparedStatement outPs = conn.prepareStatement("""
                        INSERT INTO sqlflow.column_level_out_going (
                          job_id, batch_id, db_instance, source_table, column, table_name_consumed,
                          column_consumed, user_name, stmt_consumed, first_execution, last_execution,
                          number_of_executions, create_time
                        ) VALUES (?, ?, 'DEFAULT', ?, ?, ?, ?, 'admin', ?, ?, ?, '1', ?)
                        """);
                        PreparedStatement usrPs = conn.prepareStatement("""
                        INSERT INTO sqlflow.column_level_out_going_user (
                          job_id, batch_id, db_instance, user_name, stmt_consumed, create_time
                        ) VALUES (?, ?, 'DEFAULT', 'admin', ?, ?)
                        """)) {
                    List<DbObject> baseObjs = CatalogUtils.collectAllDbObjects(baseFlow);
                    for (SqlEntry se : affectSqls) {
                        usrPs.setString(1, nn(jobId));
                        usrPs.setString(2, nn(batchId));
                        usrPs.setString(3, nn(se.sqlText()));
                        usrPs.setTimestamp(4, now);
                        usrPs.addBatch();

                        for (DbObject dbo : baseObjs) {
                            if (dbo.getName() != null
                                    && se.sqlText() != null
                                    && se.sqlText().toLowerCase(Locale.ROOT).contains(dbo.getName().toLowerCase(Locale.ROOT))) {
                                String colName = (dbo.getColumns() != null && !dbo.getColumns().isEmpty())
                                        ? nn(dbo.getColumns().get(0).getName())
                                        : "*";
                                outPs.setString(1, nn(jobId));
                                outPs.setString(2, nn(batchId));
                                outPs.setString(3, dbo.getName());
                                outPs.setString(4, colName);
                                outPs.setString(5, dbo.getName());
                                outPs.setString(6, colName);
                                outPs.setString(7, se.sqlText());
                                outPs.setString(8, now.toString());
                                outPs.setString(9, now.toString());
                                outPs.setTimestamp(10, now);
                                outPs.addBatch();
                            }
                        }
                    }
                    usrPs.executeBatch();
                    outPs.executeBatch();
                }
            }

            try (PreparedStatement qPs = conn.prepareStatement("""
                    SELECT source_table, column, stmt_consumed
                    FROM sqlflow.column_level_out_going
                    WHERE job_id = ? AND batch_id = ?
                    """)) {
                qPs.setString(1, nn(jobId));
                qPs.setString(2, nn(batchId));
                try (ResultSet rs = qPs.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("sourceTable", rs.getString("source_table"));
                        row.put("column", rs.getString("column"));
                        row.put("stmtConsumed", rs.getString("stmt_consumed"));
                        affectedRows.add(row);
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("ClickHouse persistAndFetchSourceTableAffectSQL skipped: {}", e.getMessage());
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("jobId", jobId);
        res.put("batchId", batchId);
        res.put("columnLevel", columnLevel);
        res.put("affectedSqls", affectedRows);
        return res;
    }

    @Override
    public Path exportJobParquet(String jobId, String batchId, Path targetParquetFile) {
        throw new UnsupportedOperationException("Parquet export requires DuckDB analytical store profile.");
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

    private static String nn(String val) {
        return val != null ? val : "";
    }
}
