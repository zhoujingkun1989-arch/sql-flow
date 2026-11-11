#!/usr/bin/env bash
# ==============================================================================
# Clean-Room SQLFlow 8.2.2.15 Large SQL File Splitter Wrapper (bin/split-file.sh)
# Usage: ./split-file.sh -f <file> [-d <vendor>] [-s <chunkMB>] [-o <dir>]
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${SCRIPT_DIR}/sqlflow-cli.sh" split "$@"
