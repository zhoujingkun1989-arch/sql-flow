#!/usr/bin/env bash
# ==============================================================================
# Clean-Room SQLFlow 8.2.2.15 Unified Server Launcher (bin/run_sqlflow.sh)
# Launches the single-JVM :8165 server on Linux and macOS Darwin.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=bin/_common.sh
. "${SCRIPT_DIR}/_common.sh"

SQLFLOW_HOME="$(sqlflow_resolve_home)"
export SQLFLOW_HOME

DAEMON=0
DRY_RUN=0
MEM_TIER=""
CORS_WILDCARD=0
SPRING_PROFILE=""
PORT_OVERRIDE=""
EXTRA_ARGS=()

while [ $# -gt 0 ]; do
  case "$1" in
    -d|--daemon)
      DAEMON=1
      shift
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    /m|/M|-m|--mem)
      MEM_TIER="${2:-}"
      shift 2
      ;;
    /m:*|/M:*|--mem=*)
      MEM_TIER="${1#*:}"
      MEM_TIER="${MEM_TIER#*=}"
      shift
      ;;
    --port)
      PORT_OVERRIDE="${2:-}"
      shift 2
      ;;
    --profile)
      SPRING_PROFILE="${2:-}"
      shift 2
      ;;
    --cors-wildcard)
      CORS_WILDCARD=1
      shift
      ;;
    -h|--help)
      cat <<EOF
Usage: $(basename "$0") [options] [-- <spring-args>]

Options:
  -d, --daemon           Start unified server in background (logs to ../log/sqlflow.log)
  /m, -m, --mem <tier>   Heap tier (4G|8G|16G|32G|64G) or explicit size via SQLFLOW_MEM
  --port <port>          Override HTTP listen port (default: 8165)
  --profile <name>       Override Spring profile (default: duckdb)
  --cors-wildcard        Force --cros.allowedOrigins=*
  --dry-run              Print resolved JVM command and exit 0
EOF
      exit 0
      ;;
    --)
      shift
      while [ $# -gt 0 ]; do
        EXTRA_ARGS+=("$1")
        shift
      done
      ;;
    *)
      EXTRA_ARGS+=("$1")
      shift
      ;;
  esac
done

sqlflow_bootstrap_home "$SQLFLOW_HOME"

CONF_FILE="${SQLFLOW_HOME}/conf/gudu_sqlflow.conf"
SERVER_PORT="${PORT_OVERRIDE:-${SQLFLOW_PORT:-$(sqlflow_conf_get "$CONF_FILE" "server_port" "8165")}}"
CORS_ORIGINS="$(sqlflow_conf_get "$CONF_FILE" "cros.allowedOrigins" "*")"
if [ "$CORS_WILDCARD" -eq 1 ]; then
  CORS_ORIGINS="*"
fi

JVM_FLAGS="$(sqlflow_build_jvm_flags "unified" "$MEM_TIER" "${SQLFLOW_HOME}/tmp")"

JAVA_CMD="java"
if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
  JAVA_CMD="${JAVA_HOME}/bin/java"
fi

if [ "$DRY_RUN" -eq 1 ]; then
  SERVER_JAR="$(sqlflow_resolve_server_jar "$SQLFLOW_HOME" || echo "${SQLFLOW_HOME}/lib/sqlflow-server.jar")"
  echo "${JAVA_CMD} ${JVM_FLAGS} -Dsqlflow.home=${SQLFLOW_HOME} -Dserver.port=${SERVER_PORT} -Dcros.allowedOrigins=${CORS_ORIGINS} -jar ${SERVER_JAR}"
  exit 0
fi

if ! SERVER_JAR="$(sqlflow_resolve_server_jar "$SQLFLOW_HOME")"; then
  echo "[SQLFlow] ERROR: Unified server JAR not found under ${SQLFLOW_HOME}/lib/ (build backend-service first)." >&2
  exit 1
fi

if [ -n "$SPRING_PROFILE" ]; then
  EXTRA_ARGS+=("--spring.profiles.active=${SPRING_PROFILE}")
fi

cd "${SQLFLOW_HOME}/bin"

if [ "$DAEMON" -eq 1 ]; then
  LOG_FILE="${SQLFLOW_HOME}/log/sqlflow.log"
  PID_FILE="${SQLFLOW_HOME}/log/sqlflow.pid"
  # shellcheck disable=SC2086
  nohup "$JAVA_CMD" $JVM_FLAGS \
    -Dsqlflow.home="${SQLFLOW_HOME}" \
    -Dserver.port="${SERVER_PORT}" \
    -Dcros.allowedOrigins="${CORS_ORIGINS}" \
    -jar "$SERVER_JAR" \
    ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"} >> "$LOG_FILE" 2>&1 &
  BG_PID=$!
  printf '%s\n' "$BG_PID" > "$PID_FILE"
  echo "[SQLFlow] Started unified server in background (PID=${BG_PID}, port=${SERVER_PORT}, log=${LOG_FILE})."
else
  # shellcheck disable=SC2086
  exec "$JAVA_CMD" $JVM_FLAGS \
    -Dsqlflow.home="${SQLFLOW_HOME}" \
    -Dserver.port="${SERVER_PORT}" \
    -Dcros.allowedOrigins="${CORS_ORIGINS}" \
    -jar "$SERVER_JAR" \
    ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}
fi
