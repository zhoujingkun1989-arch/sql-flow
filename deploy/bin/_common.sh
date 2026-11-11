#!/usr/bin/env bash
# ==============================================================================
# Clean-Room SQLFlow 8.2.2.15 Shared Operational & JVM Helper Library
# Compatible with both Linux (procps / cgroup v1 & v2) and macOS Darwin.
# ==============================================================================

sqlflow_resolve_home() {
  if [ -n "${SQLFLOW_HOME:-}" ]; then
    printf '%s\n' "$SQLFLOW_HOME"
    return 0
  fi
  local script_dir
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  (cd "$script_dir/.." && pwd)
}

sqlflow_conf_get() {
  local conf_file="$1"
  local key="$2"
  local default_val="${3:-}"
  if [ ! -f "$conf_file" ]; then
    printf '%s\n' "$default_val"
    return 0
  fi
  local line
  line="$(grep -E "^[[:space:]]*${key}[[:space:]]*=" "$conf_file" 2>/dev/null | tail -n 1 || true)"
  if [ -z "$line" ]; then
    printf '%s\n' "$default_val"
    return 0
  fi
  local val="${line#*=}"
  # Trim leading/trailing whitespace and CR
  val="$(printf '%s' "$val" | tr -d '\r' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  if [ -z "$val" ]; then
    printf '%s\n' "$default_val"
  else
    printf '%s\n' "$val"
  fi
}

# Detects effective physical/container memory in KiB.
# Fixes upstream Darwin bug where `sysctl -n hw.memsize` (bytes) was treated as KiB (1024x error),
# and respects Linux cgroup v2 (`memory.max`) and cgroup v1 (`memory.limit_in_bytes`).
sqlflow_detect_memory_kib() {
  local os_name="${SQLFLOW_TEST_UNAME:-$(uname -s 2>/dev/null || echo Linux)}"
  local host_kib=8388608

  if [ "$os_name" = "Darwin" ]; then
    local hw_bytes="${SQLFLOW_TEST_DARWIN_MEMSIZE_BYTES:-}"
    if [ -z "$hw_bytes" ]; then
      hw_bytes="$(sysctl -n hw.memsize 2>/dev/null || echo 8589934592)"
    fi
    case "$hw_bytes" in
      ''|*[!0-9]*) hw_bytes=8589934592 ;;
    esac
    host_kib=$(( hw_bytes / 1024 ))
  else
    local meminfo_file="${SQLFLOW_TEST_MEMINFO_FILE:-/proc/meminfo}"
    if [ -r "$meminfo_file" ]; then
      local mem_total
      mem_total="$(awk '/^MemTotal:/ { print $2; exit }' "$meminfo_file" 2>/dev/null || true)"
      case "$mem_total" in
        ''|*[!0-9]*) host_kib=8388608 ;;
        *) host_kib="$mem_total" ;;
      esac
    fi
  fi

  local effective_kib="$host_kib"
  local cgroup_limit_bytes=""

  # Cgroup v2 check (/sys/fs/cgroup/memory.max)
  local cg_v2="${SQLFLOW_TEST_CGROUP_V2_FILE:-/sys/fs/cgroup/memory.max}"
  if [ -r "$cg_v2" ]; then
    local raw_v2
    raw_v2="$(tr -d '[:space:]' < "$cg_v2" 2>/dev/null || true)"
    case "$raw_v2" in
      ''|max|*[!0-9]*) ;;
      *)
        cgroup_limit_bytes="$raw_v2"
        ;;
    esac
  fi

  # Cgroup v1 fallback (/sys/fs/cgroup/memory/memory.limit_in_bytes)
  if [ -z "$cgroup_limit_bytes" ]; then
    local cg_v1="${SQLFLOW_TEST_CGROUP_V1_FILE:-/sys/fs/cgroup/memory/memory.limit_in_bytes}"
    if [ -r "$cg_v1" ]; then
      local raw_v1
      raw_v1="$(tr -d '[:space:]' < "$cg_v1" 2>/dev/null || true)"
      case "$raw_v1" in
        ''|*[!0-9]*) ;;
        *)
          cgroup_limit_bytes="$raw_v1"
          ;;
      esac
    fi
  fi

  # Ignore sentinel unlimited values (>= 1 TiB * 1024 * 1024 = ~1 PiB, e.g. 9223372036854771712)
  local max_sane_bytes=1125899906842624
  if [ -n "$cgroup_limit_bytes" ] && [ "$cgroup_limit_bytes" -gt 0 ] && [ "$cgroup_limit_bytes" -lt "$max_sane_bytes" ]; then
    local cg_kib=$(( cgroup_limit_bytes / 1024 ))
    if [ "$cg_kib" -gt 0 ] && [ "$cg_kib" -lt "$effective_kib" ]; then
      effective_kib="$cg_kib"
    fi
  fi

  printf '%s\n' "$effective_kib"
}

# Returns 0 if a finite cgroup memory limit is active and smaller than host memory.
sqlflow_is_cgroup_constrained() {
  local cg_v2="${SQLFLOW_TEST_CGROUP_V2_FILE:-/sys/fs/cgroup/memory.max}"
  local cg_v1="${SQLFLOW_TEST_CGROUP_V1_FILE:-/sys/fs/cgroup/memory/memory.limit_in_bytes}"
  local max_sane_bytes=1125899906842624

  if [ -r "$cg_v2" ]; then
    local raw_v2
    raw_v2="$(tr -d '[:space:]' < "$cg_v2" 2>/dev/null || true)"
    case "$raw_v2" in
      ''|max|*[!0-9]*) ;;
      *)
        if [ "$raw_v2" -gt 0 ] && [ "$raw_v2" -lt "$max_sane_bytes" ]; then
          return 0
        fi
        ;;
    esac
  fi

  if [ -r "$cg_v1" ]; then
    local raw_v1
    raw_v1="$(tr -d '[:space:]' < "$cg_v1" 2>/dev/null || true)"
    case "$raw_v1" in
      ''|*[!0-9]*) ;;
      *)
        if [ "$raw_v1" -gt 0 ] && [ "$raw_v1" -lt "$max_sane_bytes" ]; then
          return 0
        fi
        ;;
    esac
  fi

  return 1
}

# Computes JVM heap size string (e.g. "512m", "4g", "1433m") for a given role and optional `/m` tier.
# Roles: unified | sqlservice | gspLive | eureka | taskscheduler
# Precedence: SQLFLOW_MEM env -> explicit tier arg -> auto-detected KiB.
sqlflow_compute_heap() {
  local role="${1:-unified}"
  local manual_tier="${2:-}"

  # Honor SQLFLOW_MEM when provided (accepts tier names like 8G or explicit JVM sizes like 3072m / 6g)
  if [ -n "${SQLFLOW_MEM:-}" ] && [ -z "$manual_tier" ]; then
    local normalized_env
    normalized_env="$(printf '%s' "$SQLFLOW_MEM" | tr '[:lower:]' '[:upper:]')"
    case "$normalized_env" in
      4G|8G|16G|32G|64G)
        manual_tier="$normalized_env"
        ;;
      *[0-9][KMG])
        printf '%s\n' "$(printf '%s' "$SQLFLOW_MEM" | tr '[:upper:]' '[:lower:]')"
        return 0
        ;;
      *[0-9])
        printf '%sm\n' "$SQLFLOW_MEM"
        return 0
        ;;
    esac
  fi

  local tier=""
  if [ -n "$manual_tier" ]; then
    tier="$(printf '%s' "$manual_tier" | tr '[:lower:]' '[:upper:]')"
  else
    local mem_kib
    mem_kib="$(sqlflow_detect_memory_kib)"

    # When running inside a memory-capped cgroup container for unified/sqlservice,
    # cap heap to ~70% of container memory so JVM + Metaspace + DuckDB off-heap fit safely.
    if sqlflow_is_cgroup_constrained && { [ "$role" = "unified" ] || [ "$role" = "sqlservice" ]; }; then
      local total_mib=$(( mem_kib / 1024 ))
      local heap_mib=$(( (total_mib * 70) / 100 ))
      if [ "$heap_mib" -lt 256 ]; then
        heap_mib=256
      elif [ "$heap_mib" -gt 65536 ]; then
        heap_mib=65536
      fi
      printf '%sm\n' "$heap_mib"
      return 0
    fi

    if [ "$mem_kib" -le 4194304 ]; then
      tier="4G"
    elif [ "$mem_kib" -le 8388608 ]; then
      tier="8G"
    elif [ "$mem_kib" -le 16777216 ]; then
      tier="16G"
    elif [ "$mem_kib" -le 33554432 ]; then
      tier="32G"
    elif [ "$mem_kib" -le 67108864 ]; then
      tier="64G"
    else
      tier="MAX"
    fi
  fi

  case "$role" in
    eureka)
      case "$tier" in
        4G|8G) printf '256m\n' ;;
        *)     printf '512m\n' ;;
      esac
      ;;
    gspLive)
      case "$tier" in
        4G)  printf '512m\n' ;;
        8G)  printf '1536m\n' ;;
        16G) printf '3g\n' ;;
        32G) printf '5g\n' ;;
        64G) printf '10g\n' ;;
        *)   printf '18g\n' ;;
      esac
      ;;
    sqlservice)
      case "$tier" in
        4G)  printf '2g\n' ;;
        8G)  printf '4g\n' ;;
        16G) printf '10g\n' ;;
        32G) printf '18g\n' ;;
        64G) printf '31g\n' ;;
        *)   printf '64g\n' ;;
      esac
      ;;
    taskscheduler)
      case "$tier" in
        4G)  printf '512m\n' ;;
        8G)  printf '1g\n' ;;
        16G) printf '2g\n' ;;
        32G) printf '3g\n' ;;
        64G) printf '5g\n' ;;
        *)   printf '8g\n' ;;
      esac
      ;;
    unified|*)
      case "$tier" in
        4G)  printf '2g\n' ;;
        8G)  printf '4g\n' ;;
        16G) printf '8g\n' ;;
        32G) printf '16g\n' ;;
        64G) printf '32g\n' ;;
        *)   printf '48g\n' ;;
      esac
      ;;
  esac
}

# Detects installed Java major version (e.g., 17, 21, 25).
sqlflow_detect_java_major() {
  local ver_str="${SQLFLOW_TEST_JAVA_VERSION_STR:-}"
  if [ -z "$ver_str" ]; then
    local java_bin="${JAVA_BIN:-java}"
    if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
      java_bin="${JAVA_HOME}/bin/java"
    fi
    ver_str="$("$java_bin" -version 2>&1 | head -n 1 || true)"
  fi

  local raw_ver
  raw_ver="$(printf '%s' "$ver_str" | sed -n 's/.*version "\([^"]*\)".*/\1/p')"
  if [ -z "$raw_ver" ]; then
    raw_ver="$(printf '%s' "$ver_str" | awk '{ for (i=1; i<=NF; i++) if ($i ~ /^[0-9]+(\.[0-9]+)*/) { print $i; exit } }')"
  fi

  local major
  case "$raw_ver" in
    1.*)
      major="$(printf '%s' "$raw_ver" | cut -d. -f2)"
      ;;
    *)
      major="$(printf '%s' "$raw_ver" | cut -d. -f1 | cut -d- -f1)"
      ;;
  esac

  case "$major" in
    ''|*[!0-9]*) printf '17\n' ;;
    *) printf '%s\n' "$major" ;;
  esac
}

# Prints standard JVM flags (one token per space-delimited string) for SQLFlow on JDK 17/21/25+.
sqlflow_build_jvm_flags() {
  local role="${1:-unified}"
  local manual_tier="${2:-}"
  local tmp_dir="${3:-../tmp}"
  local java_major
  java_major="$(sqlflow_detect_java_major)"

  local flags="-server"
  flags="$flags --add-opens java.base/java.util=ALL-UNNAMED"
  flags="$flags --add-opens java.base/java.lang=ALL-UNNAMED"
  flags="$flags --add-opens java.base/sun.nio.ch=ALL-UNNAMED"
  flags="$flags --add-opens java.base/java.io=ALL-UNNAMED"

  if [ "$java_major" -ge 21 ]; then
    flags="$flags --enable-native-access=ALL-UNNAMED"
  fi

  # Only add auto -Xms/-Xmx when JAVA_OPTS does not already override -Xmx
  case " ${JAVA_OPTS:-} " in
    *" -Xmx"*|*" -XX:MaxRAMPercentage"*)
      ;;
    *)
      local heap
      heap="$(sqlflow_compute_heap "$role" "$manual_tier")"
      flags="$flags -Xms${heap} -Xmx${heap}"
      ;;
  esac

  flags="$flags -Djava.io.tmpdir=${tmp_dir}"
  flags="$flags -Dfile.encoding=UTF-8"
  flags="$flags -Djava.awt.headless=true"

  if [ "$role" = "unified" ] || [ "$role" = "sqlservice" ]; then
    flags="$flags -Doracle.jdbc.timezoneAsRegion=false"
    flags="$flags -XX:MetaspaceSize=128M -XX:MaxMetaspaceSize=512M"
    flags="$flags -Xss512k -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:InitiatingHeapOccupancyPercent=45"
  fi

  if [ -n "${JAVA_OPTS:-}" ]; then
    flags="$flags ${JAVA_OPTS}"
  fi

  printf '%s\n' "$flags"
}

# Parses `/m <tier>` or `-m <tier>` out of CLI args and echoes the tier (or empty string).
sqlflow_extract_mem_tier_arg() {
  local prev=""
  local arg
  for arg in "$@"; do
    if [ "$prev" = "/m" ] || [ "$prev" = "/M" ] || [ "$prev" = "-m" ] || [ "$prev" = "--mem" ]; then
      printf '%s\n' "$arg"
      return 0
    fi
    case "$arg" in
      /m:*|/M:*)
        printf '%s\n' "${arg#*:}"
        return 0
        ;;
      --mem=*)
        printf '%s\n' "${arg#*=}"
        return 0
        ;;
    esac
    prev="$arg"
  done
  printf '\n'
}

# Seeds runtime directories, missing conf files, and first-boot ephemeral keys under $SQLFLOW_HOME.
sqlflow_bootstrap_home() {
  local home_dir="${1:-$(sqlflow_resolve_home)}"
  local image_bundle_dir="${SQLFLOW_IMAGE_BUNDLE_DIR:-/opt/sqlflow-image}"

  mkdir -p \
    "${home_dir}/bin" \
    "${home_dir}/conf" \
    "${home_dir}/conf/keys" \
    "${home_dir}/data/job/task" \
    "${home_dir}/data/share" \
    "${home_dir}/data/permalink" \
    "${home_dir}/data/schema" \
    "${home_dir}/data/session/sqlflow" \
    "${home_dir}/data/user/settings" \
    "${home_dir}/lib" \
    "${home_dir}/log/sqlflow_logs" \
    "${home_dir}/static" \
    "${home_dir}/tmp/cache/sqlflow" \
    "${home_dir}/widget"

  # Refresh immutable application artifacts when a persistent volume is mounted at /sqlflow,
  # while preserving operator /sqlflow/{data,conf,log,tmp} state intact.
  if [ -d "$image_bundle_dir" ] && [ "$image_bundle_dir" != "$home_dir" ]; then
    local sub
    for sub in bin lib static widget conf.defaults; do
      if [ -d "${image_bundle_dir}/${sub}" ]; then
        rm -rf "${home_dir:?}/${sub}"
        cp -a "${image_bundle_dir}/${sub}" "${home_dir}/${sub}"
      fi
    done
    if [ -f "${image_bundle_dir}/docker-entrypoint.sh" ]; then
      cp -a "${image_bundle_dir}/docker-entrypoint.sh" "${home_dir}/docker-entrypoint.sh"
    fi
  fi

  local defaults_dir="${SQLFLOW_CONF_DEFAULTS:-${home_dir}/conf.defaults}"

  # Idempotently seed missing configuration templates when /sqlflow/conf is bind-mounted empty
  if [ -d "$defaults_dir" ]; then
    local cfg_name
    for cfg_name in gudu_sqlflow.conf customize.yaml sql_content_mapping.conf sqlflow_setup.sql; do
      if [ -f "${defaults_dir}/${cfg_name}" ] && [ ! -f "${home_dir}/conf/${cfg_name}" ]; then
        cp "${defaults_dir}/${cfg_name}" "${home_dir}/conf/${cfg_name}"
      fi
    done
  fi

  sqlflow_ensure_ephemeral_keys "$home_dir"
}

sqlflow_random_hex() {
  local byte_count="${1:-32}"
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex "$byte_count" 2>/dev/null && return 0
  fi
  if [ -r /dev/urandom ]; then
    od -An -N "$byte_count" -tx1 /dev/urandom | tr -d ' \n'
    printf '\n'
    return 0
  fi
  printf '%s%s%s\n' "$(date +%s)" "$$" "c1ea900d5e6f7a8b9c0d1e2f3a4b5c6d"
}

sqlflow_ensure_ephemeral_keys() {
  local home_dir="${1:-$(sqlflow_resolve_home)}"
  local keys_dir="${home_dir}/conf/keys"
  mkdir -p "$keys_dir"
  chmod 700 "$keys_dir" 2>/dev/null || true

  local jwt_file="${keys_dir}/jwt.secret"
  if [ -z "${SQLFLOW_JWT_SECRET:-}" ] && [ ! -s "$jwt_file" ]; then
    umask 077
    sqlflow_random_hex 32 > "$jwt_file"
    chmod 600 "$jwt_file" 2>/dev/null || true
  fi

  local des_file="${keys_dir}/des.secret"
  if [ -z "${SQLFLOW_DES_SECRET:-}" ] && [ ! -s "$des_file" ]; then
    umask 077
    sqlflow_random_hex 16 > "$des_file"
    chmod 600 "$des_file" 2>/dev/null || true
  fi

  local rsa_priv="${keys_dir}/rsa_private.pem"
  local rsa_pub="${keys_dir}/rsa_public.pem"
  if [ -z "${SQLFLOW_RSA_PRIVATE_KEY:-}" ] && [ ! -s "$rsa_priv" ]; then
    if command -v openssl >/dev/null 2>&1; then
      (
        umask 077
        openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:1024 -out "$rsa_priv" >/dev/null 2>&1 \
          || openssl genrsa -out "$rsa_priv" 1024 >/dev/null 2>&1
        openssl rsa -in "$rsa_priv" -pubout -out "$rsa_pub" >/dev/null 2>&1 || true
      )
      chmod 600 "$rsa_priv" 2>/dev/null || true
      chmod 644 "$rsa_pub" 2>/dev/null || true
    else
      # Deterministic structure fallback if openssl is absent on minimal host
      umask 077
      sqlflow_random_hex 64 > "$rsa_priv"
      sqlflow_random_hex 32 > "$rsa_pub"
      chmod 600 "$rsa_priv" 2>/dev/null || true
    fi
  fi
}

# Resolves unified server JAR path.
sqlflow_resolve_server_jar() {
  local home_dir="${1:-$(sqlflow_resolve_home)}"
  if [ -n "${SQLFLOW_SERVER_JAR:-}" ] && [ -f "$SQLFLOW_SERVER_JAR" ]; then
    printf '%s\n' "$SQLFLOW_SERVER_JAR"
    return 0
  fi

  local candidate
  for candidate in \
    "${home_dir}/lib/sqlflow-server.jar" \
    "${home_dir}/lib/backend-service-8.2.2.15.jar" \
    "${home_dir}/../backend-service/target/backend-service-8.2.2.15.jar" \
    "${home_dir}/lib/gspLive.jar"
  do
    if [ -f "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  # Glob fallback inside lib/
  local found
  found="$(ls "${home_dir}/lib"/backend-service-*.jar 2>/dev/null | head -n 1 || true)"
  if [ -n "$found" ] && [ -f "$found" ]; then
    printf '%s\n' "$found"
    return 0
  fi

  return 1
}

# Finds ALL matching PIDs for a fixed substring pattern (works on Linux & macOS Darwin).
# Excludes current shell ($$), parent shell ($PPID), and stop.sh / verify-scripts.sh callers.
sqlflow_find_pids() {
  local pattern="$1"
  if [ -z "$pattern" ]; then
    return 0
  fi
  local self_pid="$$"
  local sub_pid="${BASHPID:-$$}"
  local parent_pid="${PPID:-0}"

  ps -axo pid=,ppid=,command= 2>/dev/null | \
    SQLFLOW_FIND_PAT="$pattern" awk -v sp="$self_pid" -v bp="$sub_pid" -v pp="$parent_pid" '
      BEGIN { pat = ENVIRON["SQLFLOW_FIND_PAT"] }
      {
        pid = $1
        if (pid == sp || pid == bp || pid == pp) next
        if (index($0, "SQLFLOW_FIND_PAT=") > 0) next
        if (index($0, "stop.sh") > 0 && pat != "stop.sh") next
        if (index($0, pat) > 0) {
          print pid
        }
      }
    '
}

# Gracefully stops ALL processes matching `pattern` (SIGTERM -> wait -> SIGKILL).
# Fixes upstream multi-PID bug where `[ "$pidtotal" -eq 1 ]` skipped stopping >1 matching PIDs!
sqlflow_stop_pattern() {
  local pattern="$1"
  local timeout_sec="${2:-${SQLFLOW_STOP_TIMEOUT_SEC:-10}}"
  local pids
  pids="$(sqlflow_find_pids "$pattern")"
  if [ -z "$pids" ]; then
    return 0
  fi

  local pid
  for pid in $pids; do
    kill -TERM "$pid" 2>/dev/null || true
  done

  local max_ticks=$(( timeout_sec * 5 ))
  if [ "$max_ticks" -lt 1 ]; then
    max_ticks=1
  fi

  local tick=0
  while [ "$tick" -lt "$max_ticks" ]; do
    local remaining=""
    for pid in $pids; do
      if kill -0 "$pid" 2>/dev/null; then
        remaining="$remaining $pid"
      fi
    done
    if [ -z "$remaining" ]; then
      return 0
    fi
    sleep 0.2
    tick=$(( tick + 1 ))
  done

  for pid in $pids; do
    if kill -0 "$pid" 2>/dev/null; then
      kill -KILL "$pid" 2>/dev/null || true
    fi
  done
  return 0
}
