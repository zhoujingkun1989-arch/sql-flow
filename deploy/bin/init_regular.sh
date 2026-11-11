#!/usr/bin/env bash
# ==============================================================================
# Clean-Room SQLFlow 8.2.2.15 ClickHouse Schema Initializer (bin/init_regular.sh)
# Applies ../conf/sqlflow_setup.sql (13 MergeTree analytical tables) to ClickHouse.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=bin/_common.sh
. "${SCRIPT_DIR}/_common.sh"

SQLFLOW_HOME="$(sqlflow_resolve_home)"
SQL_FILE="${SQLFLOW_HOME}/conf/sqlflow_setup.sql"
CH_HOST="${CLICKHOUSE_HOST:-localhost}"
CH_PORT="${CLICKHOUSE_PORT:-9000}"
CH_USER="${CLICKHOUSE_USER:-default}"
CH_PASSWORD="${CLICKHOUSE_PASSWORD:-sqlflow@gudu}"
DRY_RUN=0

while [ $# -gt 0 ]; do
  case "$1" in
    -h|--host)
      CH_HOST="${2:-localhost}"
      shift 2
      ;;
    --port)
      CH_PORT="${2:-9000}"
      shift 2
      ;;
    -u|--user)
      CH_USER="${2:-default}"
      shift 2
      ;;
    -p|--password)
      CH_PASSWORD="${2:-}"
      shift 2
      ;;
    -f|--sql-file)
      SQL_FILE="${2:-}"
      shift 2
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    --help)
      echo "Usage: $(basename "$0") [-h <host>] [--port <port>] [-u <user>] [-p <password>] [-f <sql-file>] [--dry-run]"
      exit 0
      ;;
    *)
      shift
      ;;
  esac
done

if [ ! -f "$SQL_FILE" ]; then
  echo "[init_regular] ERROR: ClickHouse setup DDL file not found: ${SQL_FILE}" >&2
  exit 1
fi

if [ "$DRY_RUN" -eq 1 ]; then
  echo "clickhouse-client -m -h ${CH_HOST} --port ${CH_PORT} --user ${CH_USER} --password <redacted> --multiquery < ${SQL_FILE}"
  exit 0
fi

if ! command -v clickhouse-client >/dev/null 2>&1; then
  echo "[init_regular] ERROR: clickhouse-client not found on PATH (only required for ClickHouse 'regular' profile; default 'duckdb' profile initializes automatically)." >&2
  exit 1
fi

clickhouse-client -m \
  -h "$CH_HOST" \
  --port "$CH_PORT" \
  --user "$CH_USER" \
  --password "$CH_PASSWORD" \
  --multiquery < "$SQL_FILE"

echo "[init_regular] ClickHouse 'sqlflow' database and 13 MergeTree tables initialized cleanly."
