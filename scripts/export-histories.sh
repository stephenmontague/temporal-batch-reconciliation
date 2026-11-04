#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/load-local-env.sh"

if [ $# -lt 1 ]; then
    echo "Usage: scripts/export-histories.sh <root-workflow-id> [out-dir]" >&2
    exit 1
fi

ROOT="$1"
OUT="${2:-data/histories/$ROOT}"
mkdir -p "$OUT"

temporal workflow list --query "RootWorkflowId = '$ROOT'" --output json \
    | jq -r '.[] | .execution | "\(.workflowId) \(.runId)"' \
    | while read -r workflow_id run_id; do
        temporal workflow show --workflow-id "$workflow_id" --run-id "$run_id" --output json \
            > "$OUT/$workflow_id.json"
    done

echo "Exported $(ls "$OUT" | wc -l | tr -d ' ') histories for $ROOT to $OUT"
