#!/usr/bin/env bash
set -euo pipefail

ROWS="${ROWS:-100000}"
RUNS="${RUNS:-3}"
BATCH_SIZES="${BATCH_SIZES:-1000 5000}"
DEADLINE_SECONDS="${DEADLINE_SECONDS:-600}"
OUT="${OUT:-data/experiments}"
# Only the CLI runs here; start basic-workflow-worker and fake-vendor first
CLI_JAR="${CLI_JAR:-batch-processing-cli/target/batch-processing-cli.jar}"

mkdir -p "$OUT/files" "$OUT/runs"
RESULTS="$OUT/results.csv"
SUMMARY="$OUT/summary.csv"
STAMP="$(date +%s)"

header_written=false
for size in $BATCH_SIZES; do
    for run in $(seq 1 "$RUNS"); do
        # Every run gets its own file from the same seed: identical rows, transaction IDs prefixed with the file name.
        # The store skips IDs it has already seen, so a reused file would find nothing left to do.
        # The file is named for its pattern, so the workflow ID is the file name.
        name="exp-${STAMP}-${size}-r${run}"
        echo "== batch ${size}, run ${run}/${RUNS}"
        java -jar "$CLI_JAR" generate "$ROWS" --out="$OUT/files/$name.csv" --seed=42 > /dev/null
        java -jar "$CLI_JAR" start "$OUT/files/$name.csv" --batch-size="$size" \
            --deadline-seconds="$DEADLINE_SECONDS" --wait > "$OUT/runs/$name.txt"
        workflow_id="$(awk '/^Workflow:/ {print $2}' "$OUT/runs/$name.txt")"
        outcome="$(awk '/^Outcome:/ {print $2}' "$OUT/runs/$name.txt")"
        counts="$(java -jar "$CLI_JAR" count-actions "$workflow_id" --csv --label="batch-${size}")"
        if [ "$header_written" = false ]; then
            echo "$(echo "$counts" | head -n 1),outcome" > "$RESULTS"
            header_written=true
        fi
        echo "$(echo "$counts" | tail -n 1),${outcome}" >> "$RESULTS"
        echo "   outcome ${outcome}, actions $(echo "$counts" | tail -n 1 | cut -d, -f4)"
    done
done

median() {
    sort -n | awk '{v[NR] = $1} END {if (NR % 2) print v[(NR + 1) / 2]; else print (v[NR / 2] + v[NR / 2 + 1]) / 2}'
}

echo "config,runs,workflows,total_actions,activities,heartbeats_est,timers,signals_received,root_history_events,max_child_history_events,total_history_events,wall_clock_seconds" > "$SUMMARY"
for size in $BATCH_SIZES; do
    label="batch-${size}"
    rows="$(awk -F, -v l="$label" 'NR > 1 && $1 == l' "$RESULTS")"
    col() { echo "$rows" | cut -d, -f"$1" | median; }
    echo "${label},$(echo "$rows" | wc -l | tr -d ' '),$(col 3),$(col 4),$(col 7),$(col 9),$(col 10),$(col 11),$(col 14),$(col 15),$(col 16),$(col 17)" >> "$SUMMARY"
done

echo
echo "Per run:  $RESULTS"
echo "Medians:  $SUMMARY"
column -t -s, "$SUMMARY"
