#!/usr/bin/env bash
set -euo pipefail

ROWS="${ROWS:-100000}"
RUNS="${RUNS:-3}"
CONFIGS="${CONFIGS:-file:1000 file:5000 chunk:1000 chunk:5000}"
DEADLINE_SECONDS="${DEADLINE_SECONDS:-600}"
OUT="${OUT:-data/experiments}"
JAR="${JAR:-target/recon-poc.jar}"

mkdir -p "$OUT/files" "$OUT/runs"
RESULTS="$OUT/results.csv"
SUMMARY="$OUT/summary.csv"
STAMP="$(date +%s)"

header_written=false
for config in $CONFIGS; do
    design="${config%%:*}"
    size="${config##*:}"
    for run in $(seq 1 "$RUNS"); do
        name="exp-${STAMP}-${design}-${size}-r${run}"
        echo "== ${design} design, batch ${size}, run ${run}/${RUNS}"
        java -jar "$JAR" generate "$ROWS" --out="$OUT/files/$name.csv" --seed=42 > /dev/null
        java -jar "$JAR" start "$OUT/files/$name.csv" --design="$design" --batch-size="$size" \
            --deadline-seconds="$DEADLINE_SECONDS" --wait > "$OUT/runs/$name.txt"
        outcome="$(awk '/^Outcome:/ {print $2}' "$OUT/runs/$name.txt")"
        counts="$(java -jar "$JAR" count-actions "recon-$name" --csv --label="${design}-${size}")"
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
for config in $CONFIGS; do
    label="${config%%:*}-${config##*:}"
    rows="$(awk -F, -v l="$label" 'NR > 1 && $1 == l' "$RESULTS")"
    col() { echo "$rows" | cut -d, -f"$1" | median; }
    echo "${label},$(echo "$rows" | wc -l | tr -d ' '),$(col 3),$(col 4),$(col 7),$(col 9),$(col 10),$(col 11),$(col 14),$(col 15),$(col 16),$(col 17)" >> "$SUMMARY"
done

echo
echo "Per run:  $RESULTS"
echo "Medians:  $SUMMARY"
column -t -s, "$SUMMARY"
