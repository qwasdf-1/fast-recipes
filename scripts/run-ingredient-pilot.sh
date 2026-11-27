#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
output=${1:?usage: scripts/run-ingredient-pilot.sh RESULTS_DIR [server_baseline.py setup arguments]}
shift
mkdir -p "$output"

# Same first and last modes balance the heavy-pack pilot over fresh JVMs.
run_arm() {
    local label=$1 dataset=$2 mode=$3
    local log="$output/$label.log"
    if [[ -f "$output/$label.passed" ]]; then
        return
    fi
    if [[ -f "$log" ]]; then
        mv "$log" "$(mktemp "$output/$label.interrupted.XXXXXXXX")"
    fi
    shift 3
    if ! python3 benchmarks/server_baseline.py "$@" --dataset "$dataset" --lookup "$mode" \
        --four-slot-craft --trials 8 --warmup 4 > "$log" 2>&1; then
        tail -30 "$log" >&2
        return 1
    fi
    touch "$output/$label.passed"
    cat "$log"
}

modes=(off loop occupancy ingredient combined)
for index in "${!modes[@]}"; do
    run_arm "builtin-$index" vanilla "${modes[index]}" "$@"
done
for index in "${!modes[@]}"; do
    run_arm "same-slots-forward-$index" same-slots "${modes[index]}" "$@"
done
for index in 4 3 2 1 0; do
    run_arm "same-slots-reverse-$index" same-slots "${modes[index]}" "$@"
done
