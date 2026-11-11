#!/usr/bin/env bash
# ==============================================================================
# Clean-Room SQLFlow 8.2.2.15 Compatibility Launcher: sqlservice.sh
# Fixes upstream Darwin hw.memsize 1024x bug, honors cgroups, and passes
# --enable-native-access=ALL-UNNAMED on JDK 21+/25+.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=bin/_common.sh
. "${SCRIPT_DIR}/_common.sh"

SQLFLOW_HOME="$(sqlflow_resolve_home)"
MEM_TIER="$(sqlflow_extract_mem_tier_arg "$@")"
JVM_FLAGS="$(sqlflow_build_jvm_flags "sqlservice" "$MEM_TIER" "../tmp")"

if [ "${SQLFLOW_DRY_RUN:-0}" = "1" ]; then
  echo "java ${JVM_FLAGS} -Dloader.path=../lib/gsp.jar -cp ../lib/sqlservice.jar -Dloader.main=com.gudusoft.gspLive.sqlservice.Application org.springframework.boot.loader.launch.PropertiesLauncher"
  exit 0
fi

if [ ! -f "${SQLFLOW_HOME}/lib/sqlservice.jar" ]; then
  echo "[sqlservice.sh] Unified single-JVM architecture active; sqlservice runs in-process inside sqlflow-server.jar."
  exit 0
fi

mkdir -p "${SQLFLOW_HOME}/log" "${SQLFLOW_HOME}/tmp"
JAVA_CMD="${JAVA_HOME:+${JAVA_HOME}/bin/}java"
cd "${SCRIPT_DIR}"
# shellcheck disable=SC2086
nohup "$JAVA_CMD" $JVM_FLAGS \
  -Dloader.path="../lib/gsp.jar" \
  -Djavax.accessibility.assistive_technologies=" " \
  -cp "../lib/sqlservice.jar" \
  -Dloader.main=com.gudusoft.gspLive.sqlservice.Application \
  org.springframework.boot.loader.launch.PropertiesLauncher >> "${SQLFLOW_HOME}/log/sqlservice.log" 2>&1 &
echo "[sqlservice.sh] Started sqlservice.jar (PID=$!)."
