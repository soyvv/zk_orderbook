#!/usr/bin/env bash
# Run BookQueryBenchmark against every dataset in src/test/resources/datasets/
# for the reference (treemap) and the optimized (v1-chunked-array) impls.
# Measures top-N forEachLevel latency on a populated, steady-state book.
#
# Overrides (env vars):
#   IMPLS=treemap,v1-chunked,v1-chunked-array
#   DATASETS=10k_p01_cross_off,1000k_p10_cross_off
#   TOPNS=5,10
# Anything after `--` (or any extra args) is forwarded to JMH, e.g.:
#   ./run_query_perf.sh -prof gc          # add GC profiler
#   ./run_query_perf.sh -wi 1 -i 2 -f 1   # quick smoke run

set -euo pipefail
cd "$(dirname "$0")"

IMPLS="${IMPLS:-treemap,v1-chunked-array}"
TOPNS="${TOPNS:-5,10}"
DATASETS="${DATASETS:-$(
    find src/test/resources/datasets -maxdepth 1 -name '*.zip' \
        | xargs -n 1 basename \
        | sed 's/\.zip$//' \
        | sort \
        | paste -sd, -
)}"

if [ -z "$DATASETS" ]; then
    echo "[run_query_perf] no datasets found in src/test/resources/datasets/" >&2
    exit 1
fi

echo "[run_query_perf] impl=$IMPLS"
echo "[run_query_perf] dataset=$DATASETS"
echo "[run_query_perf] topN=$TOPNS"

mvn -q test-compile dependency:build-classpath -Dmdep.outputFile=cp.txt
trap 'rm -f cp.txt' EXIT

exec java -cp "$(cat cp.txt):target/test-classes:target/classes" \
    com.zzk.orderbook.perf.BookQueryBenchmark \
    -p impl="$IMPLS" \
    -p dataset="$DATASETS" \
    -p topN="$TOPNS" \
    "$@"
