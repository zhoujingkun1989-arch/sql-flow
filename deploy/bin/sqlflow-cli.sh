#!/usr/bin/env bash
# ==============================================================================
# Clean-Room SQLFlow 8.2.2.15 Command-Line Interface (bin/sqlflow-cli.sh)
# Delegates to ../lib/sqlflow-cli.jar when present; otherwise runs a built-in
# clean-room POSIX/curl CLI supporting `split`, `incremental-job`, `lineage`,
# and `version` on Linux and macOS Darwin (resolving upstream missing-JAR defect).
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=bin/_common.sh
. "${SCRIPT_DIR}/_common.sh"

SQLFLOW_HOME="$(sqlflow_resolve_home)"

if [ -f "${SQLFLOW_HOME}/lib/sqlflow-cli.jar" ]; then
  JVM_FLAGS="$(sqlflow_build_jvm_flags "eureka" "4G" "${SQLFLOW_HOME}/tmp")"
  JAVA_CMD="${JAVA_HOME:+${JAVA_HOME}/bin/}java"
  # shellcheck disable=SC2086
  exec "$JAVA_CMD" $JVM_FLAGS -jar "${SQLFLOW_HOME}/lib/sqlflow-cli.jar" "$@"
fi

print_usage() {
  cat <<EOF
SQLFlow 8.2.2.15 Clean-Room CLI

Usage:
  $(basename "$0") <command> [options]

Commands:
  split             Split a large SQL script into statement-safe chunk files
  incremental-job   Submit (and optionally pre-split) a persistent lineage job
  lineage           Analyze SQL file or inline SQL via REST API
  version           Query server /version metadata
  help              Show this help message

Environment Variables:
  SQLFLOW_URL       Target server URL (default: http://localhost:8165)
  SQLFLOW_USER_ID   Default userId when -u / --user-id is omitted
  SQLFLOW_TOKEN     Default API secretKey / token when -t / --token is omitted
EOF
}

split_sql_local() {
  local input_file="$1"
  local chunk_mb="$2"
  local out_dir="$3"

  mkdir -p "$out_dir"
  local max_bytes=$(( chunk_mb * 1024 * 1024 ))
  if [ "$max_bytes" -lt 1 ]; then
    max_bytes=1048576
  fi

  local base_filename
  base_filename="$(basename "$input_file")"
  local stem="${base_filename%.*}"
  local ext="${base_filename##*.}"
  if [ "$stem" = "$base_filename" ]; then
    ext="sql"
  fi

  local file_bytes
  file_bytes="$(wc -c < "$input_file" | tr -d '[:space:]')"
  if [ "$file_bytes" -le "$max_bytes" ]; then
    local total_lines
    total_lines="$(wc -l < "$input_file" | tr -d '[:space:]')"
    [ "$total_lines" -lt 1 ] && total_lines=1
    local single_out="${out_dir}/${stem}_1_1_${total_lines}.${ext}"
    cp "$input_file" "$single_out"
    printf '%s\n' "$single_out"
    return 0
  fi

  awk -v max_bytes="$max_bytes" -v out_dir="$out_dir" -v stem="$stem" -v ext="$ext" '
    BEGIN {
      chunk_idx = 1
      start_line = 1
      cur_bytes = 0
      buf = ""
    }
    {
      line = $0 "\n"
      buf = buf line
      cur_bytes += length(line)
      # Split at statement semicolon boundary once chunk reaches threshold
      if (cur_bytes >= max_bytes && $0 ~ /;[[:space:]]*$/) {
        out_file = sprintf("%s/%s_%d_%d_%d.%s", out_dir, stem, chunk_idx, start_line, NR, ext)
        printf "%s", buf > out_file
        close(out_file)
        print out_file
        chunk_idx++
        start_line = NR + 1
        cur_bytes = 0
        buf = ""
      }
    }
    END {
      if (length(buf) > 0) {
        end_line = (NR >= start_line) ? NR : start_line
        out_file = sprintf("%s/%s_%d_%d_%d.%s", out_dir, stem, chunk_idx, start_line, end_line, ext)
        printf "%s", buf > out_file
        close(out_file)
        print out_file
      }
    }
  ' "$input_file"
}

SUBCOMMAND="${1:-help}"
if [ $# -gt 0 ]; then
  shift
fi

case "$SUBCOMMAND" in
  split)
    INPUT_FILE=""
    DBVENDOR="dbvoracle"
    CHUNK_SIZE_MB="5"
    OUTPUT_DIR=""
    BASE_URL="${SQLFLOW_URL:-http://localhost:8165}"
    USER_ID="${SQLFLOW_USER_ID:-gudu|0123456789}"
    TOKEN="${SQLFLOW_TOKEN:-}"

    while [ $# -gt 0 ]; do
      case "$1" in
        -f|--file) INPUT_FILE="${2:-}"; shift 2 ;;
        -d|--dbvendor) DBVENDOR="${2:-dbvoracle}"; shift 2 ;;
        -s|--chunk-size) CHUNK_SIZE_MB="${2:-5}"; shift 2 ;;
        -o|--output) OUTPUT_DIR="${2:-}"; shift 2 ;;
        --url) BASE_URL="${2:-http://localhost:8165}"; shift 2 ;;
        -u|--user-id) USER_ID="${2:-}"; shift 2 ;;
        -t|--token) TOKEN="${2:-}"; shift 2 ;;
        -h|--help)
          echo "Usage: split-file.sh -f <file> [-d <dbvendor>] [-s <chunkSizeMB>] [-o <outputDir>] [--url <url>] [-u <uid>] [-t <token>]"
          exit 0
          ;;
        *) shift ;;
      esac
    done

    if [ -z "$INPUT_FILE" ] || [ ! -f "$INPUT_FILE" ]; then
      echo "[sqlflow-cli split] ERROR: Input SQL file (-f) is required and must exist." >&2
      exit 1
    fi
    if [ -z "$OUTPUT_DIR" ]; then
      OUTPUT_DIR="$(dirname "$INPUT_FILE")/split_out"
    fi
    # Preserve unused vars for CLI compatibility logging
    : "$DBVENDOR" "$BASE_URL" "$USER_ID" "$TOKEN"
    split_sql_local "$INPUT_FILE" "$CHUNK_SIZE_MB" "$OUTPUT_DIR"
    ;;

  incremental-job)
    INPUT_FILE=""
    DBVENDOR=""
    JOB_NAME="cli-incremental-job"
    JOB_ID=""
    INCREMENTAL="true"
    CHUNK_SIZE_MB="5"
    BASE_URL="${SQLFLOW_URL:-http://localhost:8165}"
    USER_ID="${SQLFLOW_USER_ID:-gudu|0123456789}"
    TOKEN="${SQLFLOW_TOKEN:-}"

    while [ $# -gt 0 ]; do
      case "$1" in
        -f|--file) INPUT_FILE="${2:-}"; shift 2 ;;
        -d|--dbvendor) DBVENDOR="${2:-}"; shift 2 ;;
        -n|--job-name) JOB_NAME="${2:-cli-incremental-job}"; shift 2 ;;
        -j|--job-id) JOB_ID="${2:-}"; shift 2 ;;
        --no-incremental) INCREMENTAL="false"; shift ;;
        -s|--chunk-size) CHUNK_SIZE_MB="${2:-5}"; shift 2 ;;
        --url) BASE_URL="${2:-http://localhost:8165}"; shift 2 ;;
        -u|--user-id) USER_ID="${2:-}"; shift 2 ;;
        -t|--token) TOKEN="${2:-}"; shift 2 ;;
        -h|--help)
          echo "Usage: incremental-job.sh -f <file> -d <dbvendor> [-n <jobName>] [-j <jobId>] [--no-incremental] [-s <chunkSizeMB>] [--url <url>] [-u <uid>] [-t <token>]"
          exit 0
          ;;
        *) shift ;;
      esac
    done

    if [ -z "$INPUT_FILE" ] || [ ! -f "$INPUT_FILE" ] || [ -z "$DBVENDOR" ]; then
      echo "[sqlflow-cli incremental-job] ERROR: -f <file> and -d <dbvendor> are required." >&2
      exit 1
    fi

    TMP_SPLIT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/sqlflow-split.XXXXXX")"
    trap 'rm -rf "$TMP_SPLIT_DIR"' EXIT
    CHUNKS=()
    while IFS= read -r chunk_path; do
      [ -n "$chunk_path" ] && CHUNKS+=("$chunk_path")
    done < <(split_sql_local "$INPUT_FILE" "$CHUNK_SIZE_MB" "$TMP_SPLIT_DIR")

    CURL_ARGS=(
      -sf -X POST "${BASE_URL%/}/sqlflow/job/submitPersistJob"
      -F "userId=${USER_ID}"
      -F "dbvendor=${DBVENDOR}"
      -F "jobName=${JOB_NAME}"
      -F "incremental=${INCREMENTAL}"
      -F "firstSubmit=$( [ -z "$JOB_ID" ] && echo true || echo false )"
    )
    [ -n "$TOKEN" ] && CURL_ARGS+=(-F "token=${TOKEN}" -H "Authorization: Bearer ${TOKEN}")
    [ -n "$JOB_ID" ] && CURL_ARGS+=(-F "jobId=${JOB_ID}")
    for chunk in "${CHUNKS[@]}"; do
      CURL_ARGS+=(-F "sqlfiles=@${chunk}")
    done

    curl "${CURL_ARGS[@]}"
    printf '\n'
    ;;

  lineage)
    SQL_TEXT=""
    SQL_FILE=""
    DBVENDOR="dbvoracle"
    BASE_URL="${SQLFLOW_URL:-http://localhost:8165}"
    USER_ID="${SQLFLOW_USER_ID:-gudu|0123456789}"
    TOKEN="${SQLFLOW_TOKEN:-}"
    AS_CSV=0

    while [ $# -gt 0 ]; do
      case "$1" in
        -q|--sql) SQL_TEXT="${2:-}"; shift 2 ;;
        -f|--file) SQL_FILE="${2:-}"; shift 2 ;;
        -d|--dbvendor) DBVENDOR="${2:-dbvoracle}"; shift 2 ;;
        --url) BASE_URL="${2:-http://localhost:8165}"; shift 2 ;;
        -u|--user-id) USER_ID="${2:-}"; shift 2 ;;
        -t|--token) TOKEN="${2:-}"; shift 2 ;;
        --csv) AS_CSV=1; shift ;;
        *) shift ;;
      esac
    done

    if [ -n "$SQL_FILE" ] && [ -f "$SQL_FILE" ]; then
      SQL_TEXT="$(cat "$SQL_FILE")"
    fi
    if [ -z "$SQL_TEXT" ]; then
      echo "[sqlflow-cli lineage] ERROR: Provide SQL via -q <sql> or -f <file>." >&2
      exit 1
    fi

    if [ "$AS_CSV" -eq 1 ]; then
      CURL_ARGS=(
        -sf -X POST "${BASE_URL%/}/sqlflow/generation/sqlflow/exportLineageAsCsv"
        --data-urlencode "userId=${USER_ID}"
        --data-urlencode "dbvendor=${DBVENDOR}"
        --data-urlencode "sqltext=${SQL_TEXT}"
      )
      [ -n "$TOKEN" ] && CURL_ARGS+=(--data-urlencode "token=${TOKEN}" -H "Authorization: Bearer ${TOKEN}")
      curl "${CURL_ARGS[@]}"
    else
      ESCAPED_SQL="$(printf '%s' "$SQL_TEXT" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | awk '{printf "%s\\n", $0}')"
      curl -sf -X POST "${BASE_URL%/}/api/anonymous/lineage" \
        -H "Content-Type: application/json" \
        -d "{\"dbvendor\":\"${DBVENDOR}\",\"sqltext\":\"${ESCAPED_SQL}\"}"
    fi
    printf '\n'
    ;;

  version)
    BASE_URL="${SQLFLOW_URL:-http://localhost:8165}"
    [ "${1:-}" = "--url" ] && BASE_URL="${2:-http://localhost:8165}"
    curl -sf "${BASE_URL%/}/version"
    printf '\n'
    ;;

  help|-h|--help|*)
    print_usage
    ;;
esac
