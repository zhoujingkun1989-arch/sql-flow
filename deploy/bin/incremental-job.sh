#!/usr/bin/env bash
# ==============================================================================
# Clean-Room SQLFlow 8.2.2.15 Incremental Job Submitter (bin/incremental-job.sh)
# Usage: ./incremental-job.sh -f <file> -d <vendor> [-n <jobName>] [-j <jobId>]
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${SCRIPT_DIR}/sqlflow-cli.sh" incremental-job "$@"
