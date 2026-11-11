#!/usr/bin/env bash
# ==============================================================================
# Clean-Room SQLFlow 8.2.2.15 Compatibility Launcher: taskscheduler.sh
# In SQLFlow 8.2.2.15, scheduled jobs run inside SqlflowTaskService (@Scheduled).
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=bin/_common.sh
. "${SCRIPT_DIR}/_common.sh"

SQLFLOW_HOME="$(sqlflow_resolve_home)"
MEM_TIER="$(sqlflow_extract_mem_tier_arg "$@")"
JVM_FLAGS="$(sqlflow_build_jvm_flags "taskscheduler" "$MEM_TIER" "../tmp")"

if [ "${SQLFLOW_DRY_RUN:-0}" = "1" ]; then
  echo "java ${JVM_FLAGS} -Dloader.path=../lib/gsp.jar -cp ../lib/taskscheduler.jar -Dloader.main=com.gudusoft.gspLive.taskscheduler.Application org.springframework.boot.loader.launch.PropertiesLauncher"
  exit 0
fi

if [ ! -f "${SQLFLOW_HOME}/lib/taskscheduler.jar" ]; then
  echo "[taskscheduler.sh] Scheduled jobs execute natively inside the unified SQLFlow server."
  exit 0
fi

mkdir -p "${SQLFLOW_HOME}/log" "${SQLFLOW_HOME}/tmp"
JAVA_CMD="${JAVA_HOME:+${JAVA_HOME}/bin/}java"
cd "${SCRIPT_DIR}"
# shellcheck disable=SC2086
nohup "$JAVA_CMD" $JVM_FLAGS \
  -Dloader.path="../lib/gsp.jar" \
  -cp "../lib/taskscheduler.jar" \
  -Dloader.main=com.gudusoft.gspLive.taskscheduler.Application \
  org.springframework.boot.loader.launch.PropertiesLauncher >> "${SQLFLOW_HOME}/log/taskscheduler.log" 2>&1 &
echo "[taskscheduler.sh] Started taskscheduler.jar (PID=$!)."
