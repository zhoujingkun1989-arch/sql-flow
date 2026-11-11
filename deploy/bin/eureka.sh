#!/usr/bin/env bash
# ==============================================================================
# Clean-Room SQLFlow 8.2.2.15 Compatibility Launcher: eureka.sh
# In unified single-JVM mode (:8165), Eureka is unnecessary (no-op unless legacy
# ../lib/eureka.jar is explicitly installed). Fixes Darwin memory sizing & JDK 21+ flags.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=bin/_common.sh
. "${SCRIPT_DIR}/_common.sh"

SQLFLOW_HOME="$(sqlflow_resolve_home)"
MEM_TIER="$(sqlflow_extract_mem_tier_arg "$@")"
JVM_FLAGS="$(sqlflow_build_jvm_flags "eureka" "$MEM_TIER" "../tmp")"

if [ "${SQLFLOW_DRY_RUN:-0}" = "1" ]; then
  echo "java ${JVM_FLAGS} -jar ../lib/eureka.jar"
  exit 0
fi

if [ ! -f "${SQLFLOW_HOME}/lib/eureka.jar" ]; then
  echo "[eureka.sh] Unified single-JVM architecture active; external eureka.jar is not required."
  exit 0
fi

mkdir -p "${SQLFLOW_HOME}/log" "${SQLFLOW_HOME}/tmp"
JAVA_CMD="${JAVA_HOME:+${JAVA_HOME}/bin/}java"
cd "${SCRIPT_DIR}"
# shellcheck disable=SC2086
nohup "$JAVA_CMD" $JVM_FLAGS -jar "../lib/eureka.jar" >> "${SQLFLOW_HOME}/log/eureka.log" 2>&1 &
echo "[eureka.sh] Started eureka.jar (PID=$!)."
