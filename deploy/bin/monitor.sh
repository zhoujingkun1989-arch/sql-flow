#!/usr/bin/env bash
# ==============================================================================
# Clean-Room SQLFlow 8.2.2.15 Process & OOM Watchdog (bin/monitor.sh)
# Supports unified single-JVM server (:8165) and legacy split-JAR mode.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=bin/_common.sh
. "${SCRIPT_DIR}/_common.sh"

SQLFLOW_HOME="$(sqlflow_resolve_home)"
export SQLFLOW_HOME

ENABLE_TASKSCHEDULER="${1:-0}"
ENABLE_CORS_WILDCARD="${2:-0}"
INTERVAL_SEC="${SQLFLOW_MONITOR_INTERVAL_SEC:-5}"
OOM_REGEX='OutOfMemoryError|Java heap space|Metaspace|Direct buffer memory|GC overhead limit exceeded|unable to create new native thread|oom-killer'

CONF_FILE="${SQLFLOW_HOME}/conf/gudu_sqlflow.conf"
SERVER_PORT="${SQLFLOW_PORT:-$(sqlflow_conf_get "$CONF_FILE" "server_port" "8165")}"

STOPPING=0
trap 'STOPPING=1; exit 0' SIGTERM SIGINT

check_log_oom() {
  local log_file="$1"
  local last_lines="$2"
  if [ ! -f "$log_file" ]; then
    printf '0\n'
    return 0
  fi
  local current_lines
  current_lines="$(wc -l < "$log_file" 2>/dev/null | tr -d '[:space:]' || echo 0)"
  case "$current_lines" in ''|*[!0-9]*) current_lines=0 ;; esac
  if [ "$current_lines" -gt "$last_lines" ]; then
    local start_line=$(( last_lines + 1 ))
    if tail -n +"$start_line" "$log_file" 2>/dev/null | grep -Eiq "$OOM_REGEX"; then
      printf 'OOM:%s\n' "$current_lines"
      return 0
    fi
  fi
  printf '%s\n' "$current_lines"
}

LAST_UNIFIED_LINES=0
LAST_SQLSVC_LINES=0
LAST_GSPLIVE_LINES=0
UNIFIED_READY=0
UNIFIED_FAIL_STREAK=0

while [ "$STOPPING" -eq 0 ]; do
  if SERVER_JAR="$(sqlflow_resolve_server_jar "$SQLFLOW_HOME")" && [ "$(basename "$SERVER_JAR")" != "gspLive.jar" ]; then
    # Unified single-JVM topology
    JAR_BASENAME="$(basename "$SERVER_JAR")"
    if [ -z "$(sqlflow_find_pids "$JAR_BASENAME")" ]; then
      echo "[monitor] Launching unified SQLFlow server (${JAR_BASENAME})..."
      CORS_FLAG=()
      [ "$ENABLE_CORS_WILDCARD" = "1" ] && CORS_FLAG=(--cors-wildcard)
      "${SCRIPT_DIR}/run_sqlflow.sh" --daemon ${CORS_FLAG[@]+"${CORS_FLAG[@]}"}
      UNIFIED_READY=0
      UNIFIED_FAIL_STREAK=0
    else
      if curl -sf "http://localhost:${SERVER_PORT}/" >/dev/null 2>&1; then
        UNIFIED_READY=1
        UNIFIED_FAIL_STREAK=0
      elif [ "$UNIFIED_READY" -eq 1 ]; then
        UNIFIED_FAIL_STREAK=$(( UNIFIED_FAIL_STREAK + 1 ))
        if [ "$UNIFIED_FAIL_STREAK" -ge 3 ]; then
          echo "[monitor] Unified server on :${SERVER_PORT} failed 3 consecutive health checks; restarting..."
          sqlflow_stop_pattern "$JAR_BASENAME" 5
          UNIFIED_READY=0
          UNIFIED_FAIL_STREAK=0
        fi
      fi
    fi

    OOM_CHECK="$(check_log_oom "${SQLFLOW_HOME}/log/sqlflow.log" "$LAST_UNIFIED_LINES")"
    case "$OOM_CHECK" in
      OOM:*)
        LAST_UNIFIED_LINES="${OOM_CHECK#OOM:}"
        echo "[monitor] Detected OOM signature in sqlflow.log; terminating unified JVM for clean restart..."
        sqlflow_stop_pattern "$JAR_BASENAME" 2
        UNIFIED_READY=0
        ;;
      *)
        LAST_UNIFIED_LINES="$OOM_CHECK"
        ;;
    esac
  else
    # Legacy split-JAR compatibility path
    [ -f "${SQLFLOW_HOME}/lib/eureka.jar" ] && [ -z "$(sqlflow_find_pids "eureka.jar")" ] && "${SCRIPT_DIR}/eureka.sh"
    [ -f "${SQLFLOW_HOME}/lib/sqlservice.jar" ] && [ -z "$(sqlflow_find_pids "sqlservice.jar")" ] && "${SCRIPT_DIR}/sqlservice.sh"
    [ -f "${SQLFLOW_HOME}/lib/gspLive.jar" ] && [ -z "$(sqlflow_find_pids "gspLive.jar")" ] && "${SCRIPT_DIR}/gspLive.sh" "$ENABLE_CORS_WILDCARD"
    if [ "$ENABLE_TASKSCHEDULER" = "1" ] && [ -f "${SQLFLOW_HOME}/lib/taskscheduler.jar" ] && [ -z "$(sqlflow_find_pids "taskscheduler.jar")" ]; then
      "${SCRIPT_DIR}/taskscheduler.sh"
    fi

    OOM_SQL="$(check_log_oom "${SQLFLOW_HOME}/log/sqlservice.log" "$LAST_SQLSVC_LINES")"
    case "$OOM_SQL" in
      OOM:*) LAST_SQLSVC_LINES="${OOM_SQL#OOM:}"; sqlflow_stop_pattern "sqlservice.jar" 2 ;;
      *) LAST_SQLSVC_LINES="$OOM_SQL" ;;
    esac

    OOM_GSP="$(check_log_oom "${SQLFLOW_HOME}/log/gspLive.log" "$LAST_GSPLIVE_LINES")"
    case "$OOM_GSP" in
      OOM:*) LAST_GSPLIVE_LINES="${OOM_GSP#OOM:}"; sqlflow_stop_pattern "gspLive.jar" 2 ;;
      *) LAST_GSPLIVE_LINES="$OOM_GSP" ;;
    esac
  fi

  sleep "$INTERVAL_SEC" &
  SLEEP_PID=$!
  wait "$SLEEP_PID" || true
done
