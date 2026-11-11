# SQLFlow 8.2.2.15 Command-Line & Operational Scripts Reference

All scripts in `bin/` are POSIX/bash compatible and run cleanly on both **Linux** (including cgroup v1/v2 containers) and **macOS (`Darwin`)** across **JDK 17, JDK 21, and JDK 25+**.

## 1. Server Lifecycle Scripts

| Script | Description |
| :--- | :--- |
| `./run_sqlflow.sh [--daemon] [/m <tier>] [--port 8165]` | Starts the unified `:8165` SQLFlow server (foreground by default, or background with `--daemon`). |
| `./backend.sh [enableScheduler] [enableCorsWildcard] [/m <tier>]` | Stops existing instances, clears `../tmp` and `../data/session`, and starts the `monitor.sh` watchdog. |
| `./stop.sh` | Gracefully stops (`SIGTERM` -> wait -> `SIGKILL`) **all** running SQLFlow PIDs. |
| `./restart.sh` | Runs `stop.sh` followed by `backend.sh`. |
| `./status.sh [--json]` | Prints active PIDs, detected memory/heap sizing, Java version, and HTTP `:8165` health. |
| `./init_regular.sh` | Applies `../conf/sqlflow_setup.sql` (13 `MergeTree` tables) to ClickHouse when using the optional `regular` profile. |
| `./generate_token.sh` | Exchanges `(userId, secretKey)` or on-premise credentials for API tokens (`SQLFLOW_USER_ID`, `SQLFLOW_SECRET_KEY`, `SQLFLOW_TOKEN`). |

## 2. CLI Wrappers (`sqlflow-cli.sh`, `split-file.sh`, `incremental-job.sh`)

### Environment Variables

- `SQLFLOW_URL` — Base server URL (default: `http://localhost:8165`)
- `SQLFLOW_USER_ID` — Default `userId` when `-u` / `--user-id` is omitted
- `SQLFLOW_TOKEN` — Default API `secretKey` or JWT token when `-t` / `--token` is omitted
- `SQLFLOW_MEM` — Explicit JVM heap override (e.g., `4g`, `2048m`, or tier `8G`)
- `JAVA_OPTS` — Custom JVM flags appended (or overriding `-Xmx`) at startup

### Split Large SQL Files

```bash
./split-file.sh -f /path/to/warehouse_dump.sql -d dbvsnowflake -s 5 -o ./chunks
```

### Submit Persistent / Incremental Lineage Jobs

```bash
export SQLFLOW_URL="http://localhost:8165"
eval "$(./generate_token.sh -e admin@local.gudusoft.com -p admin)"

./incremental-job.sh \
  -f /path/to/etl_batch.sql \
  -d dbvpostgresql \
  -n "nightly-warehouse-lineage" \
  -s 5
```

### Quick Ad-Hoc Lineage Query

```bash
./sqlflow-cli.sh lineage -d dbvoracle -q "CREATE VIEW v_emp AS SELECT emp_id, dept_id FROM hr.employees;"
```
