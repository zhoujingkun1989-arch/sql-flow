#!/usr/bin/env bash
# ==============================================================================
# Clean-Room SQLFlow 8.2.2.15 Host Supervisor Launcher (bin/backend.sh)
# Usage: ./backend.sh [enableTaskScheduler:0|1] [enableCorsWildcard:0|1] [/m <tier>]
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=bin/_common.sh
. "${SCRIPT_DIR}/_common.sh"

SQLFLOW_HOME="$(sqlflow_resolve_home)"
export SQLFLOW_HOME

ENABLE_TASKSCHEDULER="${1:-0}"
ENABLE_CORS_WILDCARD="${2:-0}"
MEM_TIER="$(sqlflow_extract_mem_tier_arg "$@")"

"${SCRIPT_DIR}/stop.sh"

# Match documented clean-slate session & tmp cache purge on host restart
rm -rf "${SQLFLOW_HOME}/tmp" "${SQLFLOW_HOME}/data/session"
sqlflow_bootstrap_home "$SQLFLOW_HOME"

MONITOR_LOG="${SQLFLOW_HOME}/log/monitor.out"
if [ -n "$MEM_TIER" ]; then
  export SQLFLOW_MEM="$MEM_TIER"
fi

cd "${SCRIPT_DIR}"
nohup "${SCRIPT_DIR}/monitor.sh" "$ENABLE_TASKSCHEDULER" "$ENABLE_CORS_WILDCARD" >> "$MONITOR_LOG" 2>&1 &
MON_PID=$!
echo "[SQLFlow] Started host supervisor monitor.sh (PID=${MON_PID}, log=${MONITOR_LOG})."
