-- =============================================================================
-- Clean-Room SQLFlow 8.2.2.15 ClickHouse Analytical Schema (sqlflow_setup.sql)
-- Used with the optional ClickHouse `regular` profile via `bin/init_regular.sh`.
-- Creates all 13 MergeTree analytical tables with daily partitioning and 6-month TTL.
-- =============================================================================

CREATE DATABASE IF NOT EXISTS sqlflow;

-- 1. Raw column-level lineage edges
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
    relation_type String DEFAULT 'fdd',
    indirect_clause String DEFAULT '',
    INDEX lineage_index(lineage_hash) TYPE ngrambf_v1(3, 256, 2, 0) GRANULARITY 4
) ENGINE = MergeTree
PARTITION BY toDate(create_time)
ORDER BY (job_id)
TTL create_time + toIntervalMonth(6)
SETTINGS index_granularity = 8192;

ALTER TABLE sqlflow.dataflow_table ADD COLUMN IF NOT EXISTS relation_type String DEFAULT 'fdd';
ALTER TABLE sqlflow.dataflow_table ADD COLUMN IF NOT EXISTS indirect_clause String DEFAULT '';

-- 2. Deduplicated / aggregated column-level lineage edges
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
    relation_type String DEFAULT 'fdd',
    INDEX lineage_index(lineage_hash) TYPE ngrambf_v1(3, 256, 2, 0) GRANULARITY 4
) ENGINE = MergeTree
PARTITION BY toDate(create_time)
ORDER BY (job_id)
TTL create_time + toIntervalMonth(6)
SETTINGS index_granularity = 8192;

ALTER TABLE sqlflow.dataflow_aggregation_table ADD COLUMN IF NOT EXISTS relation_type String DEFAULT 'fdd';

-- 3. Discovered catalog objects per job (type: 0=table, 1=view, 2=path, 3=variable)
CREATE TABLE IF NOT EXISTS sqlflow.dataflow_table_metadata (
    job_id String,
    database String,
    schema String,
    table String,
    type Int16,
    create_time DateTime,
    process_ids String DEFAULT ''
) ENGINE = MergeTree
PARTITION BY toDate(create_time)
ORDER BY (job_id)
TTL create_time + toIntervalMonth(6)
SETTINGS index_granularity = 8192;

ALTER TABLE sqlflow.dataflow_table_metadata ADD COLUMN IF NOT EXISTS process_ids String DEFAULT '';

-- 4. Lineage hash <-> SQL hash / statement execution audit map
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
SETTINGS index_granularity = 8192;

ALTER TABLE sqlflow.dataflow_sql_map_table ADD COLUMN IF NOT EXISTS transform String DEFAULT 'N';

-- 5. Deduplicated SQL statements per job
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
SETTINGS index_granularity = 8192;

-- 6. Transitive root upstream closure (all intermediate & root hops)
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
SETTINGS index_granularity = 8192;

-- 7. Leaf-only root upstream source table/column mapping
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
SETTINGS index_granularity = 8192;

-- 8. Per-batch outgoing column impact & execution statistics
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
SETTINGS index_granularity = 8192;

-- 9. Raw table-to-table lineage edges
CREATE TABLE IF NOT EXISTS sqlflow.table_level_dataflow (
    job_id String,
    db_instance String,
    user_name String,
    target_table String,
    source_table String,
    execute_time DateTime,
    create_time DateTime,
    relation_type String DEFAULT 'fdd'
) ENGINE = MergeTree
PARTITION BY toDate(create_time)
ORDER BY (job_id)
TTL create_time + toIntervalMonth(6)
SETTINGS index_granularity = 8192;

-- 10. Aggregated table-to-table lineage counts
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
    relation_type String DEFAULT 'fdd'
) ENGINE = MergeTree
PARTITION BY toDate(create_time)
ORDER BY (job_id)
TTL create_time + toIntervalMonth(6)
SETTINGS index_granularity = 8192;

-- 11. Per-batch downstream user statement consumption summary
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
SETTINGS index_granularity = 8192;

-- 12. Statement-level SQL transform code segments
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
SETTINGS index_granularity = 8192;

-- 13. Lineage-edge transform expressions & source coordinates
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
SETTINGS index_granularity = 8192;
