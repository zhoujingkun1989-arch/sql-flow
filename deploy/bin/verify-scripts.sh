#!/usr/bin/env bash
# ==============================================================================
# Automated Offline Verification Suite for SQLFlow Operational Scripts & Configs
# Verifies:
#   1. `bash -n` syntax validity on docker-entrypoint.sh and all bin/*.sh
#   2. macOS Darwin `sysctl -n hw.memsize` bytes-vs-KiB 1024x fix
#   3. Linux `/proc/meminfo`, cgroup v2 (`memory.max`), & cgroup v1 memory sizing
#   4. Manual `/m 4g..64G` tiers, `SQLFLOW_MEM`, and `JAVA_OPTS` precedence
#   5. JDK 17 vs JDK 21 / JDK 25 `--enable-native-access=ALL-UNNAMED` flags
#   6. Empty-volume seeding & idempotency in `docker-entrypoint.sh --bootstrap-only`
#   7. `stop.sh` multi-PID termination fix (3 simultaneous matching PIDs -> 0)
#   8. Default `conf/*` files & 13 ClickHouse tables + `split-file.sh` chunking
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

# shellcheck source=bin/_common.sh
. "${SCRIPT_DIR}/_common.sh"

PASS_COUNT=0
FAIL_COUNT=0

assert_eq() {
  local actual="$1"
  local expected="$2"
  local msg="$3"
  if [ "$actual" = "$expected" ]; then
    PASS_COUNT=$(( PASS_COUNT + 1 ))
    printf '  [PASS] %s (=%s)\n' "$msg" "$actual"
  else
    FAIL_COUNT=$(( FAIL_COUNT + 1 ))
    printf '  [FAIL] %s: expected "%s", got "%s"\n' "$msg" "$expected" "$actual" >&2
  fi
}

assert_contains() {
  local haystack="$1"
  local needle="$2"
  local msg="$3"
  if printf '%s' "$haystack" | grep -Fq -- "$needle"; then
    PASS_COUNT=$(( PASS_COUNT + 1 ))
    printf '  [PASS] %s\n' "$msg"
  else
    FAIL_COUNT=$(( FAIL_COUNT + 1 ))
    printf '  [FAIL] %s: missing "%s" in "%s"\n' "$msg" "$needle" "$haystack" >&2
  fi
}

assert_not_contains() {
  local haystack="$1"
  local needle="$2"
  local msg="$3"
  if printf '%s' "$haystack" | grep -Fq -- "$needle"; then
    FAIL_COUNT=$(( FAIL_COUNT + 1 ))
    printf '  [FAIL] %s: unexpected "%s" in "%s"\n' "$msg" "$needle" "$haystack" >&2
  else
    PASS_COUNT=$(( PASS_COUNT + 1 ))
    printf '  [PASS] %s\n' "$msg"
  fi
}

TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/sqlflow-verify.XXXXXX")"
cleanup() {
  rm -rf "$TMP_ROOT"
}
trap cleanup EXIT

echo "=== [1/7] Syntax-checking deploy/docker-entrypoint.sh and deploy/bin/*.sh ==="
for sh_file in "${DEPLOY_DIR}/docker-entrypoint.sh" "${SCRIPT_DIR}"/*.sh; do
  bash -n "$sh_file"
  PASS_COUNT=$(( PASS_COUNT + 1 ))
  printf '  [PASS] bash -n %s\n' "$(basename "$sh_file")"
done

echo "=== [2/7] Testing Darwin hw.memsize bytes-to-KiB fix (00 §6.1 #9) ==="
NO_CG="${TMP_ROOT}/nonexistent-cgroup"

# 16 GiB Mac = 17,179,869,184 bytes -> must be 16,777,216 KiB (NOT >64GB 118g!)
DARWIN_16G_KIB="$(
  SQLFLOW_TEST_UNAME="Darwin" \
  SQLFLOW_TEST_DARWIN_MEMSIZE_BYTES="17179869184" \
  SQLFLOW_TEST_CGROUP_V2_FILE="$NO_CG" \
  SQLFLOW_TEST_CGROUP_V1_FILE="$NO_CG" \
  sqlflow_detect_memory_kib
)"
assert_eq "$DARWIN_16G_KIB" "16777216" "Darwin 16 GiB bytes -> 16777216 KiB"

DARWIN_16G_SQLSVC_HEAP="$(
  SQLFLOW_TEST_UNAME="Darwin" \
  SQLFLOW_TEST_DARWIN_MEMSIZE_BYTES="17179869184" \
  SQLFLOW_TEST_CGROUP_V2_FILE="$NO_CG" \
  SQLFLOW_TEST_CGROUP_V1_FILE="$NO_CG" \
  SQLFLOW_MEM="" \
  sqlflow_compute_heap "sqlservice"
)"
assert_eq "$DARWIN_16G_SQLSVC_HEAP" "10g" "Darwin 16 GiB sqlservice heap is 10g (not 118g)"

DARWIN_8G_UNIFIED_HEAP="$(
  SQLFLOW_TEST_UNAME="Darwin" \
  SQLFLOW_TEST_DARWIN_MEMSIZE_BYTES="8589934592" \
  SQLFLOW_TEST_CGROUP_V2_FILE="$NO_CG" \
  SQLFLOW_TEST_CGROUP_V1_FILE="$NO_CG" \
  SQLFLOW_MEM="" \
  sqlflow_compute_heap "unified"
)"
assert_eq "$DARWIN_8G_UNIFIED_HEAP" "4g" "Darwin 8 GiB unified heap is 4g"

echo "=== [3/7] Testing Linux /proc/meminfo, cgroup v2 & v1, /m tiers, SQLFLOW_MEM, JAVA_OPTS ==="
FAKE_MEMINFO="${TMP_ROOT}/meminfo_64g"
printf 'MemTotal:       67108864 kB\nMemFree:        32000000 kB\n' > "$FAKE_MEMINFO"

FAKE_CGV2_MAX="${TMP_ROOT}/cgroup_v2_max"
printf 'max\n' > "$FAKE_CGV2_MAX"

LINUX_64G_KIB="$(
  SQLFLOW_TEST_UNAME="Linux" \
  SQLFLOW_TEST_MEMINFO_FILE="$FAKE_MEMINFO" \
  SQLFLOW_TEST_CGROUP_V2_FILE="$FAKE_CGV2_MAX" \
  SQLFLOW_TEST_CGROUP_V1_FILE="$NO_CG" \
  sqlflow_detect_memory_kib
)"
assert_eq "$LINUX_64G_KIB" "67108864" "Linux 64 GiB MemTotal with cgroup.max='max'"

# 2 GiB cgroup v2 container on a 64 GiB Linux host -> 2,097,152 KiB -> 70% = 1433m heap
FAKE_CGV2_2G="${TMP_ROOT}/cgroup_v2_2g"
printf '2147483648\n' > "$FAKE_CGV2_2G"

CGV2_2G_KIB="$(
  SQLFLOW_TEST_UNAME="Linux" \
  SQLFLOW_TEST_MEMINFO_FILE="$FAKE_MEMINFO" \
  SQLFLOW_TEST_CGROUP_V2_FILE="$FAKE_CGV2_2G" \
  SQLFLOW_TEST_CGROUP_V1_FILE="$NO_CG" \
  sqlflow_detect_memory_kib
)"
assert_eq "$CGV2_2G_KIB" "2097152" "Cgroup v2 2 GiB cap overrides 64 GiB host MemTotal"

CGV2_2G_HEAP="$(
  SQLFLOW_TEST_UNAME="Linux" \
  SQLFLOW_TEST_MEMINFO_FILE="$FAKE_MEMINFO" \
  SQLFLOW_TEST_CGROUP_V2_FILE="$FAKE_CGV2_2G" \
  SQLFLOW_TEST_CGROUP_V1_FILE="$NO_CG" \
  SQLFLOW_MEM="" \
  sqlflow_compute_heap "unified"
)"
assert_eq "$CGV2_2G_HEAP" "1433m" "Cgroup v2 2 GiB container unified heap safely sized to 1433m (70%)"

# 4 GiB cgroup v1 container -> 4,194,304 KiB -> 70% = 2867m heap
FAKE_CGV1_4G="${TMP_ROOT}/cgroup_v1_4g"
printf '4294967296\n' > "$FAKE_CGV1_4G"

CGV1_4G_HEAP="$(
  SQLFLOW_TEST_UNAME="Linux" \
  SQLFLOW_TEST_MEMINFO_FILE="$FAKE_MEMINFO" \
  SQLFLOW_TEST_CGROUP_V2_FILE="$NO_CG" \
  SQLFLOW_TEST_CGROUP_V1_FILE="$FAKE_CGV1_4G" \
  SQLFLOW_MEM="" \
  sqlflow_compute_heap "unified"
)"
assert_eq "$CGV1_4G_HEAP" "2867m" "Cgroup v1 4 GiB container unified heap safely sized to 2867m (70%)"

# Lowercase `/m 4g` vs uppercase `/m 4G` parity on gspLive
GSPLIVE_4G_LOWER="$(SQLFLOW_MEM="" sqlflow_compute_heap "gspLive" "4g")"
GSPLIVE_4G_UPPER="$(SQLFLOW_MEM="" sqlflow_compute_heap "gspLive" "4G")"
assert_eq "$GSPLIVE_4G_LOWER" "512m" "gspLive /m 4g (lowercase) -> 512m"
assert_eq "$GSPLIVE_4G_UPPER" "512m" "gspLive /m 4G (uppercase) -> 512m"

# SQLFLOW_MEM and JAVA_OPTS precedence
ENV_MEM_HEAP="$(SQLFLOW_MEM="3072m" sqlflow_compute_heap "unified")"
assert_eq "$ENV_MEM_HEAP" "3072m" "SQLFLOW_MEM=3072m honored directly"

CUSTOM_JAVA_OPTS_FLAGS="$(JAVA_OPTS="-Xmx1999m -Dfoo=bar" sqlflow_build_jvm_flags "unified")"
assert_contains "$CUSTOM_JAVA_OPTS_FLAGS" "-Xmx1999m -Dfoo=bar" "JAVA_OPTS -Xmx1999m preserved"
assert_not_contains "$CUSTOM_JAVA_OPTS_FLAGS" "-Xms" "Auto -Xms suppressed when JAVA_OPTS sets -Xmx"

echo "=== [4/7] Testing JDK 17 vs JDK 21 / JDK 25 --enable-native-access flags ==="
JDK17_FLAGS="$(
  SQLFLOW_TEST_JAVA_VERSION_STR='openjdk version "17.0.13" 2024-10-15' \
  JAVA_OPTS="" \
  sqlflow_build_jvm_flags "unified" "8G"
)"
assert_contains "$JDK17_FLAGS" "--add-opens java.base/sun.nio.ch=ALL-UNNAMED" "JDK 17 includes --add-opens"
assert_not_contains "$JDK17_FLAGS" "--enable-native-access=ALL-UNNAMED" "JDK 17 omits --enable-native-access"

JDK21_FLAGS="$(
  SQLFLOW_TEST_JAVA_VERSION_STR='openjdk version "21.0.5" 2024-10-15 LTS' \
  JAVA_OPTS="" \
  sqlflow_build_jvm_flags "unified" "8G"
)"
assert_contains "$JDK21_FLAGS" "--enable-native-access=ALL-UNNAMED" "JDK 21 includes --enable-native-access=ALL-UNNAMED"

JDK25_FLAGS="$(
  SQLFLOW_TEST_JAVA_VERSION_STR='openjdk version "25" 2025-09-16' \
  JAVA_OPTS="" \
  sqlflow_build_jvm_flags "unified" "8G"
)"
assert_contains "$JDK25_FLAGS" "--enable-native-access=ALL-UNNAMED" "JDK 25 includes --enable-native-access=ALL-UNNAMED"

echo "=== [5/7] Testing docker-entrypoint.sh empty-volume seeding & idempotency ==="
MOCK_HOME="${TMP_ROOT}/sqlflow_home"
mkdir -p "${MOCK_HOME}/bin" "${MOCK_HOME}/conf"
cp "${SCRIPT_DIR}/_common.sh" "${MOCK_HOME}/bin/_common.sh"

SQLFLOW_HOME="$MOCK_HOME" \
SQLFLOW_CONF_DEFAULTS="${DEPLOY_DIR}/conf" \
  bash "${DEPLOY_DIR}/docker-entrypoint.sh" --bootstrap-only >/dev/null

for seeded_cfg in gudu_sqlflow.conf customize.yaml sql_content_mapping.conf sqlflow_setup.sql; do
  if [ -s "${MOCK_HOME}/conf/${seeded_cfg}" ]; then
    PASS_COUNT=$(( PASS_COUNT + 1 ))
    printf '  [PASS] Seeded missing conf/%s into empty bind-mounted conf/\n' "$seeded_cfg"
  else
    FAIL_COUNT=$(( FAIL_COUNT + 1 ))
    printf '  [FAIL] Missing seeded conf/%s\n' "$seeded_cfg" >&2
  fi
done

for req_dir in \
  "data/job/task" \
  "data/share" \
  "data/permalink" \
  "data/schema" \
  "data/session/sqlflow" \
  "data/user/settings" \
  "log/sqlflow_logs" \
  "tmp/cache/sqlflow"
do
  if [ -d "${MOCK_HOME}/${req_dir}" ]; then
    PASS_COUNT=$(( PASS_COUNT + 1 ))
    printf '  [PASS] Created runtime directory %s\n' "$req_dir"
  else
    FAIL_COUNT=$(( FAIL_COUNT + 1 ))
    printf '  [FAIL] Missing runtime directory %s\n' "$req_dir" >&2
  fi
done

for key_file in jwt.secret des.secret rsa_private.pem rsa_public.pem; do
  if [ -s "${MOCK_HOME}/conf/keys/${key_file}" ]; then
    PASS_COUNT=$(( PASS_COUNT + 1 ))
    printf '  [PASS] Generated ephemeral key conf/keys/%s\n' "$key_file"
  else
    FAIL_COUNT=$(( FAIL_COUNT + 1 ))
    printf '  [FAIL] Ephemeral key conf/keys/%s missing or empty\n' "$key_file" >&2
  fi
done

# Verify idempotency: operator edits & generated secrets must survive subsequent boots
FIRST_JWT_SECRET="$(cat "${MOCK_HOME}/conf/keys/jwt.secret")"
printf '\n# custom_operator_marker=preserve_me\n' >> "${MOCK_HOME}/conf/gudu_sqlflow.conf"

SQLFLOW_HOME="$MOCK_HOME" \
SQLFLOW_CONF_DEFAULTS="${DEPLOY_DIR}/conf" \
  bash "${DEPLOY_DIR}/docker-entrypoint.sh" --bootstrap-only >/dev/null

SECOND_JWT_SECRET="$(cat "${MOCK_HOME}/conf/keys/jwt.secret")"
assert_eq "$SECOND_JWT_SECRET" "$FIRST_JWT_SECRET" "Ephemeral jwt.secret preserved across restarts"
assert_contains "$(cat "${MOCK_HOME}/conf/gudu_sqlflow.conf")" "custom_operator_marker=preserve_me" "Operator conf edit preserved across restarts"

echo "=== [6/7] Testing stop.sh multi-PID termination fix (3 processes -> 0) ==="
MULTI_TOKEN="sqlflow_multipid_test_$$_${RANDOM}"
DUMMY_RUNNER="${TMP_ROOT}/${MULTI_TOKEN}.sh"
cat > "$DUMMY_RUNNER" <<'EOF'
#!/usr/bin/env bash
trap 'exit 0' TERM INT
while true; do
  sleep 1
done
EOF
chmod +x "$DUMMY_RUNNER"

"$DUMMY_RUNNER" &
P1=$!
"$DUMMY_RUNNER" &
P2=$!
"$DUMMY_RUNNER" &
P3=$!
sleep 0.3

MATCHED_BEFORE="$(sqlflow_find_pids "$MULTI_TOKEN" | wc -l | tr -d '[:space:]')"
assert_eq "$MATCHED_BEFORE" "3" "Detected all 3 concurrent dummy processes before stop.sh"

bash "${SCRIPT_DIR}/stop.sh" --pattern "$MULTI_TOKEN" --timeout 3 >/dev/null
wait "$P1" "$P2" "$P3" 2>/dev/null || true

MATCHED_AFTER="$(sqlflow_find_pids "$MULTI_TOKEN" | wc -l | tr -d '[:space:]')"
assert_eq "$MATCHED_AFTER" "0" "stop.sh terminated all 3 matching PIDs cleanly"

echo "=== [7/7] Verifying conf/* contracts & split-file.sh offline chunking ==="
CONF_PATH="${DEPLOY_DIR}/conf/gudu_sqlflow.conf"
assert_eq "$(sqlflow_conf_get "$CONF_PATH" "deployment")" "premise" "gudu_sqlflow.conf deployment=premise"
assert_eq "$(sqlflow_conf_get "$CONF_PATH" "ignore_user_token")" "false" "gudu_sqlflow.conf ignore_user_token=false"
assert_eq "$(sqlflow_conf_get "$CONF_PATH" "anonymous_sql_Execute_limit")" "50" "gudu_sqlflow.conf anonymous_sql_Execute_limit=50"
assert_eq "$(sqlflow_conf_get "$CONF_PATH" "cros.allowedOrigins")" "*" "gudu_sqlflow.conf cros.allowedOrigins=*"

MAPPING_RULES_COUNT="$(grep -Ev '^[[:space:]]*(#|$)' "${DEPLOY_DIR}/conf/sql_content_mapping.conf" | wc -l | tr -d '[:space:]')"
assert_eq "$MAPPING_RULES_COUNT" "7" "sql_content_mapping.conf contains all 7 regex substitution rules"

CH_TABLES_COUNT="$(grep -Ec '^CREATE TABLE IF NOT EXISTS sqlflow\.' "${DEPLOY_DIR}/conf/sqlflow_setup.sql" | tr -d '[:space:]')"
assert_eq "$CH_TABLES_COUNT" "13" "sqlflow_setup.sql defines all 13 ClickHouse MergeTree tables"

CH_TTL_COUNT="$(grep -Ec 'TTL create_time \+ toIntervalMonth\(6\)' "${DEPLOY_DIR}/conf/sqlflow_setup.sql" | tr -d '[:space:]')"
assert_eq "$CH_TTL_COUNT" "13" "All 13 ClickHouse tables enforce 6-month TTL"

SAMPLE_SQL="${TMP_ROOT}/sample.sql"
printf 'SELECT 1 FROM dual;\nSELECT 2 FROM dual;\n' > "$SAMPLE_SQL"
SPLIT_OUT="${TMP_ROOT}/split_chunks"
SPLIT_RESULT="$(bash "${SCRIPT_DIR}/split-file.sh" -f "$SAMPLE_SQL" -d dbvoracle -s 1 -o "$SPLIT_OUT")"
assert_contains "$SPLIT_RESULT" "sample_1_1_2.sql" "split-file.sh produces expected chunk filename"

echo ""
echo "=================================================================="
printf 'Verification Summary: %d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
echo "=================================================================="

if [ "$FAIL_COUNT" -ne 0 ]; then
  exit 1
fi
exit 0
