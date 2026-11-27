#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
mkdir -p results
run_dir=$(mktemp -d results/lookup.XXXXXXXX)
{
    date -u
    uname -a
    java -version
    cat gradle.properties
    if command -v lscpu >/dev/null; then
        lscpu
    fi
} > "$run_dir/environment.txt" 2>&1
printf 'Benchmark evidence directory: %s\n' "$run_dir"

if ! ./gradlew --no-daemon --console=plain "$@" benchmarkLookup > "$run_dir/gradle.log" 2>&1; then
    tail -40 "$run_dir/gradle.log" >&2
    exit 1
fi
awk '/^dataset,size,workload,batch,arm,/ || /^(vanilla_datapack|synthetic|same_slots|adaptive_builtin|adaptive_dense|adaptive_geometry),[0-9]+,[a-z_]+,[0-9]+,(vanilla|loop|occupancy|ingredient|combined|cache|combined-cache|hash-tree|hash-tree-cache|adaptive-[0-9]+),/' \
    "$run_dir/gradle.log" > "$run_dir/samples.csv"
expected=1751
for flag in "$@"; do
    if [[ "$flag" == -PlookupAdaptive* ]]; then
        expected=851
        awk '/^adaptive_setup,/' "$run_dir/gradle.log" > "$run_dir/setup.csv"
        break
    elif [[ "$flag" == -PlookupQuick* || "$flag" == -PlookupCacheQuick* || "$flag" == -PlookupTreeQuick* ]]; then
        expected=751
    fi
done
test "$(wc -l < "$run_dir/samples.csv")" -eq "$expected"
printf 'Samples saved to %s/samples.csv\n' "$run_dir"
