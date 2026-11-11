#!/usr/bin/env bash
# ==============================================================================
# Clean-Room SQLFlow 8.2.2.15 Process Terminator (bin/stop.sh)
# Fixes upstream multi-PID defect (`[ "$pidtotal" -eq 1 ]`) by terminating ALL
# matching PIDs gracefully (SIGTERM -> wait -> SIGKILL) on Linux & macOS Darwin.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=bin/_common.sh
. "${SCRIPT_DIR}/_common.sh"

SQLFLOW_HOME="$(sqlflow_resolve_home)"
TIMEOUT_SEC="${SQLFLOW_STOP_TIMEOUT_SEC:-10}"
CUSTOM_PATTERNS=()

while [ $# -gt 0 ]; do
  case "$1" in
    --pattern)
      CUSTOM_PATTERNS+=("${2:-}")
      shift 2
      ;;
    --timeout)
      TIMEOUT_SEC="${2:-10}"
      shift 2
      ;;
    -h|--help)
      echo "Usage: $(basename "$0") [--pattern <substring>] [--timeout <seconds>]"
      exit 0
      ;;
    *)
      shift
      ;;
  esac
done

if [ "${#CUSTOM_PATTERNS[@]}" -gt 0 ]; then
  for pat in "${CUSTOM_PATTERNS[@]}"; do
    [ -n "$pat" ] && sqlflow_stop_pattern "$pat" "$TIMEOUT_SEC"
  done
  exit 0
fi

# Stop supervisor first so it does not respawn services while shutting down
TARGETS=(
  "monitor.sh"
  "sqlflow-server.jar"
  "backend-service-8.2.2.15.jar"
  "gspLive.jar"
  "sqlservice.jar"
  "taskscheduler.jar"
  "eureka.jar"
)

for item in "${TARGETS[@]}"; do
  PIDS="$(sqlflow_find_pids "$item")"
  if [ -n "$PIDS" ]; then
    echo "[SQLFlow] Stopping ${item} (PIDs: $(printf '%s' "$PIDS" | tr '\n' ' '))..."
    sqlflow_stop_pattern "$item" "$TIMEOUT_SEC"
  fi
done

PID_FILE="${SQLFLOW_HOME}/log/sqlflow.pid"
if [ -f "$PID_FILE" ]; then
  RECORDED_PID="$(tr -d '[:space:]' < "$PID_FILE" 2>/dev/null || true)"
  if [ -n "$RECORDED_PID" ] && kill -0 "$RECORDED_PID" 2>/dev/null; then
    kill -TERM "$RECORDED_PID" 2>/dev/null || true
    sleep 0.5
    kill -KILL "$RECORDED_PID" 2>/dev/null || true
  fi
  rm -f "$PID_FILE"
fi

echo "[SQLFlow] All SQLFlow services stopped."
