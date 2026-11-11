#!/usr/bin/env bash
# ==============================================================================
# Clean-Room SQLFlow 8.2.2.15 Token & API Secret Key Helper (bin/generate_token.sh)
# Generates or exchanges programmatic credentials against :8165:
#   - Mode 1 (-u <userId> -k <secretKey>): POST /user/generateToken -> short-lived JWT
#   - Mode 2 (-e <email> -p <password>):   POST /user/register -> generateSecretKey -> generateToken
# ==============================================================================

set -euo pipefail

BASE_URL="${SQLFLOW_URL:-http://localhost:8165}"
USER_ID="${SQLFLOW_USER_ID:-}"
SECRET_KEY="${SQLFLOW_SECRET_KEY:-}"
EMAIL="${SQLFLOW_ADMIN_EMAIL:-admin@local.gudusoft.com}"
PASSWORD="${SQLFLOW_ADMIN_PASSWORD:-admin}"
OUTPUT_FORMAT="env"
DRY_RUN=0

while [ $# -gt 0 ]; do
  case "$1" in
    --url)
      BASE_URL="${2:-http://localhost:8165}"
      shift 2
      ;;
    -u|--user-id)
      USER_ID="${2:-}"
      shift 2
      ;;
    -k|--secret-key)
      SECRET_KEY="${2:-}"
      shift 2
      ;;
    -e|--email)
      EMAIL="${2:-}"
      shift 2
      ;;
    -p|--password)
      PASSWORD="${2:-}"
      shift 2
      ;;
    --raw)
      OUTPUT_FORMAT="raw"
      shift
      ;;
    --bearer)
      OUTPUT_FORMAT="bearer"
      shift
      ;;
    --json)
      OUTPUT_FORMAT="json"
      shift
      ;;
    --env)
      OUTPUT_FORMAT="env"
      shift
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    -h|--help)
      cat <<EOF
Usage: $(basename "$0") [options]

Options:
  --url <baseUrl>          SQLFlow server base URL (default: \$SQLFLOW_URL or http://localhost:8165)
  -u, --user-id <id>       User ID for direct secretKey -> JWT exchange
  -k, --secret-key <key>   API secret key from /user/generateSecretKey
  -e, --email <email>      On-premise login email (default: admin@local.gudusoft.com)
  -p, --password <pass>    On-premise login password (default: admin)
  --env                    Print shell export statements (default)
  --raw                    Print only the token / secretKey string
  --bearer                 Print "Authorization: Bearer <secretKey>"
  --json                   Print JSON credential summary
  --dry-run                Print target endpoint plan without network call
EOF
      exit 0
      ;;
    *)
      shift
      ;;
  esac
done

BASE_URL="${BASE_URL%/}"

if [ "$DRY_RUN" -eq 1 ]; then
  echo "target=${BASE_URL} userId=${USER_ID:-<resolve-via-register>} email=${EMAIL}"
  exit 0
fi

extract_json_string() {
  local json="$1"
  local field="$2"
  printf '%s' "$json" | sed -n "s/.*\"${field}\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" | head -n 1
}

LOGIN_TOKEN=""
if [ -z "$USER_ID" ] || [ -z "$SECRET_KEY" ]; then
  REG_RESP="$(curl -sf -X POST "${BASE_URL}/user/register" \
    --data-urlencode "userEmail=${EMAIL}" \
    --data-urlencode "password=${PASSWORD}")"

  if [ -z "$USER_ID" ]; then
    USER_ID="$(extract_json_string "$REG_RESP" "userId")"
  fi
  LOGIN_TOKEN="$(extract_json_string "$REG_RESP" "token")"

  if [ -z "$USER_ID" ]; then
    echo "[generate_token] ERROR: Failed to authenticate user '${EMAIL}' against ${BASE_URL}/user/register: ${REG_RESP}" >&2
    exit 1
  fi

  if [ -z "$SECRET_KEY" ]; then
    AUTH_HDR=()
    [ -n "$LOGIN_TOKEN" ] && AUTH_HDR=(-H "Authorization: Token ${LOGIN_TOKEN}")
    SK_RESP="$(curl -sf -X POST "${BASE_URL}/user/generateSecretKey" \
      ${AUTH_HDR[@]+"${AUTH_HDR[@]}"} \
      --data-urlencode "userId=${USER_ID}")"
    SECRET_KEY="$(extract_json_string "$SK_RESP" "secretKey")"
  fi
fi

if [ -z "$SECRET_KEY" ]; then
  echo "[generate_token] ERROR: Unable to obtain secretKey for userId=${USER_ID}" >&2
  exit 1
fi

TOK_RESP="$(curl -sf -X POST "${BASE_URL}/user/generateToken" \
  --data-urlencode "userId=${USER_ID}" \
  --data-urlencode "secretKey=${SECRET_KEY}")"
JWT_TOKEN="$(extract_json_string "$TOK_RESP" "token")"
if [ -z "$JWT_TOKEN" ]; then
  JWT_TOKEN="$SECRET_KEY"
fi

case "$OUTPUT_FORMAT" in
  raw)
    printf '%s\n' "$JWT_TOKEN"
    ;;
  bearer)
    printf 'Authorization: Bearer %s\n' "$SECRET_KEY"
    ;;
  json)
    printf '{"userId":"%s","secretKey":"%s","token":"%s"}\n' "$USER_ID" "$SECRET_KEY" "$JWT_TOKEN"
    ;;
  env|*)
    printf 'export SQLFLOW_USER_ID="%s"\n' "$USER_ID"
    printf 'export SQLFLOW_SECRET_KEY="%s"\n' "$SECRET_KEY"
    printf 'export SQLFLOW_TOKEN="%s"\n' "$JWT_TOKEN"
    ;;
esac
