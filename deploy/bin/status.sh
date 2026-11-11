#!/usr/bin/env bash
# ==============================================================================
# Clean-Room SQLFlow 8.2.2.15 Runtime Status Inspector (bin/status.sh)
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=bin/_common.sh
. "${SCRIPT_DIR}/_common.sh"

SQLFLOW_HOME="$(sqlflow_resolve_home)"
CONF_FILE="${SQLFLOW_HOME}/conf/gudu_sqlflow.conf"
SERVER_PORT="${SQLFLOW_PORT:-$(sqlflow_conf_get "$CONF_FILE" "server_port" "8165")}"
JSON_OUT=0

if [ "${1:-}" = "--json" ]; then
  JSON_OUT=1
fi

UNIFIED_PIDS="$(
  {
    sqlflow_find_pids "sqlflow-server.jar"
    sqlflow_find_pids "backend-service-8.2.2.15.jar"
    sqlflow_find_pids "gspLive.jar"
  } | sort -u | tr '\n' ' ' | sed -e 's/[[:space:]]*$//'
)"

HTTP_HEALTH="down"
if command -v curl >/dev/null 2>&1 && curl -sf --max-time 3 "http://localhost:${SERVER_PORT}/version" >/dev/null 2>&1; then
  HTTP_HEALTH="healthy"
elif command -v curl >/dev/null 2>&1 && curl -sf --max-time 3 "http://localhost:${SERVER_PORT}/" >/dev/null 2>&1; then
  HTTP_HEALTH="starting"
fi

MEM_KIB="$(sqlflow_detect_memory_kib)"
COMPUTED_HEAP="$(sqlflow_compute_heap "unified")"
JAVA_MAJOR="$(sqlflow_detect_java_major)"

if [ "$JSON_OUT" -eq 1 ]; then
  printf '{"home":"%s","port":%s,"pids":"%s","http":"%s","memoryKiB":%s,"computedHeap":"%s","javaMajor":%s}\n' \
    "$SQLFLOW_HOME" "$SERVER_PORT" "$UNIFIED_PIDS" "$HTTP_HEALTH" "$MEM_KIB" "$COMPUTED_HEAP" "$JAVA_MAJOR"
else
  echo "SQLFlow Home   : ${SQLFLOW_HOME}"
  echo "Server Port    : ${SERVER_PORT}"
  echo "Active PIDs    : ${UNIFIED_PIDS:-none}"
  echo "HTTP Status    : ${HTTP_HEALTH}"
  echo "Detected RAM   : $(( MEM_KIB / 1024 )) MiB (${MEM_KIB} KiB) -> default heap ${COMPUTED_HEAP}"
  echo "Java Major     : ${JAVA_MAJOR}"
fi

if [ -n "$UNIFIED_PIDS" ]; then
  exit 0
fi
exit 1
