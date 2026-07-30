#!/usr/bin/env bash
#
# Repeats the cold-scan metrics in separate JVMs and prints the spread.
#
# `docs/performance.md` records `cold_full_scan` going from ~13 s at 3,050 items to 167.6 s at 15,050 -
# a 12.8x time increase for a 5x item increase - and says the shape cannot be called superlinear from a
# single observation per size. This is the missing repetition.
#
# Separate JVMs, not a loop inside the harness, and that is the whole point. `cold_scan`,
# `cold_analyze` and `cold_full_scan` measure a first execution: the scanner and analyzer are
# JIT-cold, no page cache is warm for the database file, and no aggregation cache exists. Looping
# inside one JVM would make every iteration after the first systematically faster - biased, not merely
# noisier - which is exactly what the harness's own class doc says about why it does not repeat them.
# One `gradlew` invocation per repetition is the only way to get a second cold observation.
#
# Usage:
#   scripts/cold-scan-repetitions.sh [repetitions] [seriesCount] [booksPerSeries] [oneShotCount]
#
# Defaults reproduce the 3,050-item size, which runs in about a minute per repetition. The 15,050-item
# size takes several minutes per repetition; pass it explicitly when you mean it:
#   scripts/cold-scan-repetitions.sh 5 1500 10 50
set -euo pipefail

REPETITIONS="${1:-5}"
SERIES_COUNT="${2:-300}"
BOOKS_PER_SERIES="${3:-10}"
ONE_SHOT_COUNT="${4:-50}"

if ! [[ "$REPETITIONS" =~ ^[1-9][0-9]*$ ]]; then
  echo "repetitions must be a positive integer, got: $REPETITIONS" >&2
  exit 2
fi

cd "$(dirname "$0")/.."

# Only the cold metrics plus the drain outcome. The drain matters here too: a repetition whose queue
# did not drain produced api.* numbers that are not comparable, and silently averaging it in would
# reintroduce the very defect this file exists to help retire.
KEYS='cold_scan.wall|cold_analyze.wall|cold_full_scan.wall|queue_drain.drained|unchanged_rescan.wall.p50'

echo "repetition,metric,value,unit,items"
for repetition in $(seq 1 "$REPETITIONS"); do
  # A fresh Gradle daemon per repetition would be even stricter, but the daemon does not hold the
  # measured state: the harness creates its library and database in a fresh temporary directory each
  # run, and the JVM that executes the test is forked per `Test` task execution. What matters is that
  # the scan is the first one in its own process, and it is.
  # `--rerun`, not `--rerun-tasks`: the latter re-executes every task in the graph including
  # compilation, which triples the wall time without changing what is measured. `--console=plain` and
  # no `--quiet`, because the metrics are printed by the test to stdout and `--quiet` swallows them.
  ./gradlew performanceHarness --rerun --console=plain \
    "-Pxoboro.perf.seriesCount=$SERIES_COUNT" \
    "-Pxoboro.perf.booksPerSeries=$BOOKS_PER_SERIES" \
    "-Pxoboro.perf.oneShotCount=$ONE_SHOT_COUNT" 2>&1 |
    grep -E "^\s*xoboro\.perf\.($KEYS)=" |
    sed -E 's/^[[:space:]]*xoboro\.perf\.([^=]+)=([^ ]+) unit=([^ ]+) items=([0-9]+).*$/'"$repetition"',\1,\2,\3,\4/'
done
