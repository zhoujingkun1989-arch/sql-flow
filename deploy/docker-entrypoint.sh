#!/usr/bin/env bash
# ==============================================================================
# Clean-Room SQLFlow 8.2.2.15 OCI Container Entrypoint (/sqlflow/docker-entrypoint.sh)
# - Idempotently seeds missing default /sqlflow/conf/* files on empty bind mounts
# - Creates /sqlflow/{data,log,tmp} hierarchy
# - Generates ephemeral JWT / DES / RSA keys on first boot when not externally supplied
# - Honors JAVA_OPTS / SQLFLOW_MEM / cgroup v1 & v2 memory limits + JDK 21+/25+ flags
# - Launches unified :8165 server with immediate SIGTERM / SIGINT forwarding
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export SQLFLOW_HOME="${SQLFLOW_HOME:-$SCRIPT_DIR}"

if [ -f "${SCRIPT_DIR}/bin/_common.sh" ]; then
  # shellcheck source=bin/_common.sh
  . "${SCRIPT_DIR}/bin/_common.sh"
elif [ -f "${SQLFLOW_HOME}/bin/_common.sh" ]; then
  # shellcheck source=bin/_common.sh
  . "${SQLFLOW_HOME}/bin/_common.sh"
else
  echo "[SQLFlow] ERROR: Missing helper library ${SQLFLOW_HOME}/bin/_common.sh" >&2
  exit 1
fi

BOOTSTRAP_ONLY=0
MEM_TIER=""
PASSTHROUGH_ARGS=()

while [ $# -gt 0 ]; do
  case "$1" in
    --bootstrap-only)
      BOOTSTRAP_ONLY=1
      shift
      ;;
    /m|/M|-m|--mem)
      MEM_TIER="${2:-}"
      shift 2
      ;;
    *)
      PASSTHROUGH_ARGS+=("$1")
      shift
      ;;
  esac
done

echo "[SQLFlow] Bootstrapping SQLFlow runtime root at ${SQLFLOW_HOME}..."
sqlflow_bootstrap_home "${SQLFLOW_HOME}"

# Export ephemeral secrets for unified server if operator did not pass env vars
if [ -z "${SQLFLOW_JWT_SECRET:-}" ] && [ -s "${SQLFLOW_HOME}/conf/keys/jwt.secret" ]; then
  SQLFLOW_JWT_SECRET="$(tr -d '[:space:]' < "${SQLFLOW_HOME}/conf/keys/jwt.secret")"
  export SQLFLOW_JWT_SECRET
fi
if [ -z "${SQLFLOW_DES_SECRET:-}" ] && [ -s "${SQLFLOW_HOME}/conf/keys/des.secret" ]; then
  SQLFLOW_DES_SECRET="$(tr -d '[:space:]' < "${SQLFLOW_HOME}/conf/keys/des.secret")"
  export SQLFLOW_DES_SECRET
fi
export SQLFLOW_RSA_PRIVATE_KEY_PATH="${SQLFLOW_RSA_PRIVATE_KEY_PATH:-${SQLFLOW_HOME}/conf/keys/rsa_private.pem}"
export SQLFLOW_RSA_PUBLIC_KEY_PATH="${SQLFLOW_RSA_PUBLIC_KEY_PATH:-${SQLFLOW_HOME}/conf/keys/rsa_public.pem}"

CONF_FILE="${SQLFLOW_HOME}/conf/gudu_sqlflow.conf"
SERVER_PORT="${SQLFLOW_PORT:-$(sqlflow_conf_get "$CONF_FILE" "server_port" "8165")}"
CORS_ORIGINS="$(sqlflow_conf_get "$CONF_FILE" "cros.allowedOrigins" "*")"
JVM_FLAGS="$(sqlflow_build_jvm_flags "unified" "$MEM_TIER" "${SQLFLOW_HOME}/tmp")"

if [ "$BOOTSTRAP_ONLY" -eq 1 ] || [ "${SQLFLOW_DRY_RUN:-0}" = "1" ]; then
  echo "[SQLFlow] Bootstrap complete (port=${SERVER_PORT}, jvm_flags=${JVM_FLAGS})."
  exit 0
fi

# Resolve unified server JAR (or fallback legacy multi-JAR supervisor if only split JARs exist)
SERVER_JAR=""
if SERVER_JAR="$(sqlflow_resolve_server_jar "${SQLFLOW_HOME}")"; then
  :
elif [ -f "${SQLFLOW_HOME}/lib/eureka.jar" ] && [ -f "${SQLFLOW_HOME}/lib/sqlservice.jar" ] && [ -f "${SQLFLOW_HOME}/lib/gspLive.jar" ]; then
  echo "[SQLFlow] Unified JAR not found; starting legacy 3-JVM topology..."
  SHUTTING_DOWN=0
  trap 'SHUTTING_DOWN=1; echo "[SQLFlow] Caught signal, stopping services..."; "${SQLFLOW_HOME}/bin/stop.sh"; exit 0' SIGTERM SIGINT
  (cd "${SQLFLOW_HOME}/bin" && ./eureka.sh)
  sleep 5
  (cd "${SQLFLOW_HOME}/bin" && ./sqlservice.sh)
  sleep 5
  (cd "${SQLFLOW_HOME}/bin" && ./gspLive.sh)
  while [ "$SHUTTING_DOWN" -eq 0 ]; do
    sleep 30 &
    SLEEP_PID=$!
    wait "$SLEEP_PID" || true
    [ "$SHUTTING_DOWN" -eq 1 ] && break
    [ -z "$(sqlflow_find_pids "eureka.jar")" ] && (cd "${SQLFLOW_HOME}/bin" && ./eureka.sh)
    [ -z "$(sqlflow_find_pids "sqlservice.jar")" ] && (cd "${SQLFLOW_HOME}/bin" && ./sqlservice.sh)
    [ -z "$(sqlflow_find_pids "gspLive.jar")" ] && (cd "${SQLFLOW_HOME}/bin" && ./gspLive.sh)
  done
  exit 0
else
  echo "[SQLFlow] ERROR: No SQLFlow server JAR found under ${SQLFLOW_HOME}/lib/" >&2
  exit 1
fi

JAVA_CMD="java"
if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
  JAVA_CMD="${JAVA_HOME}/bin/java"
fi

SERVER_PID=""
shutdown_handler() {
  echo "[SQLFlow] Received shutdown signal; terminating server (PID=${SERVER_PID:-none})..."
  if [ -n "${SERVER_PID:-}" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill -TERM "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  exit 0
}
trap shutdown_handler SIGTERM SIGINT

# Run with CWD=${SQLFLOW_HOME}/bin so ../conf, ../data, ../log, ../tmp relative paths match 1:1
cd "${SQLFLOW_HOME}/bin"

# shellcheck disable=SC2086
"$JAVA_CMD" $JVM_FLAGS \
  -Dsqlflow.home="${SQLFLOW_HOME}" \
  -Dserver.port="${SERVER_PORT}" \
  -Dcros.allowedOrigins="${CORS_ORIGINS}" \
  -jar "${SERVER_JAR}" \
  ${PASSTHROUGH_ARGS[@]+"${PASSTHROUGH_ARGS[@]}"} &
SERVER_PID=$!

echo "[SQLFlow] Unified SQLFlow 8.2.2.15 starting on port ${SERVER_PORT} (PID=${SERVER_PID}, JAR=${SERVER_JAR})"
wait "$SERVER_PID"
EXIT_CODE=$?
exit "$EXIT_CODE"
