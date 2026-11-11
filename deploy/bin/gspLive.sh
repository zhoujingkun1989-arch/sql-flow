#!/usr/bin/env bash
# ==============================================================================
# Clean-Room SQLFlow 8.2.2.15 Gateway / Unified Server Launcher: gspLive.sh
# Supports $1="1" (--cros.allowedOrigins=*) and `/m {4G|8G|16G|32G|64G}`.
# Automatically delegates to run_sqlflow.sh --daemon when unified JAR is deployed.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=bin/_common.sh
. "${SCRIPT_DIR}/_common.sh"

SQLFLOW_HOME="$(sqlflow_resolve_home)"
MEM_TIER="$(sqlflow_extract_mem_tier_arg "$@")"
CORS_ARG=""
if [ "${1:-}" = "1" ]; then
  CORS_ARG="--cros.allowedOrigins=*"
fi
JVM_FLAGS="$(sqlflow_build_jvm_flags "gspLive" "$MEM_TIER" "../tmp")"

if [ "${SQLFLOW_DRY_RUN:-0}" = "1" ]; then
  echo "java ${JVM_FLAGS} -jar ../lib/gspLive.jar ${CORS_ARG}"
  exit 0
fi

if [ ! -f "${SQLFLOW_HOME}/lib/gspLive.jar" ]; then
  RUN_ARGS=(--daemon)
  [ -n "$CORS_ARG" ] && RUN_ARGS+=(--cors-wildcard)
  [ -n "$MEM_TIER" ] && RUN_ARGS+=(--mem "$MEM_TIER")
  exec "${SCRIPT_DIR}/run_sqlflow.sh" "${RUN_ARGS[@]}"
fi

mkdir -p "${SQLFLOW_HOME}/log" "${SQLFLOW_HOME}/tmp"
JAVA_CMD="${JAVA_HOME:+${JAVA_HOME}/bin/}java"
cd "${SCRIPT_DIR}"
# shellcheck disable=SC2086
nohup "$JAVA_CMD" $JVM_FLAGS -jar "../lib/gspLive.jar" "$CORS_ARG" >> "${SQLFLOW_HOME}/log/gspLive.log" 2>&1 &
echo "[gspLive.sh] Started gspLive.jar (PID=$!)."
