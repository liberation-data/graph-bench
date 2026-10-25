#!/bin/bash
# Runs the benchmark against each engine in turn, each in a fresh container of its own with the same
# limits: 2 CPUs and 4 GB, which is what Oracle Free allows itself whatever it is given.
#
#   ./run.sh <scale factor> [engine ...]
#
# DRIVINE_SOURCE=<a checkout of drivine4j> builds against that source and not the released version.
# DRIVINE_VERSION=<a released version> builds against that one, to compare it with build.gradle.kts's own.
# GBENCH_RESULTS=<folder> keeps the results there and not in results/: a run of each version, or on each machine, in
# a folder of its own, so they can be compared rather than written over one another.
set -euo pipefail
cd "$(dirname "$0")"
SF=${1:-1}; shift || true
ENGINES=${*:-neo4j falkordb oracle postgres}
# What each run is made with, kept beside its numbers in results/ (RunDetails): this harness's commit, the Drivine
# built against (the released version, or the commit of the source checkout), and the containers' limits.
# A copy without its git history (as a run on another machine is given) says its commit in .bench-commit, written
# where the history is: `git rev-parse --short HEAD > .bench-commit`.
commit_of() {
    if git -C "$1" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        git -C "$1" rev-parse --short HEAD | tr -d '\n'; git -C "$1" diff --quiet HEAD || printf -- '-dirty'
    elif [ -f "$1/.bench-commit" ]; then tr -d '\n' < "$1/.bench-commit"
    fi
}
export GBENCH_COMMIT; GBENCH_COMMIT=$(commit_of .)
export GBENCH_RESULTS=${GBENCH_RESULTS:-results}
mkdir -p "$GBENCH_RESULTS"
export GBENCH_DRIVINE
if [ -n "${DRIVINE_SOURCE:-}" ]; then GBENCH_DRIVINE="source $(commit_of "$DRIVINE_SOURCE")"
elif [ -n "${DRIVINE_VERSION:-}" ]; then GBENCH_DRIVINE=$DRIVINE_VERSION
else GBENCH_DRIVINE=$(sed -n 's/^val drivineVersion = .*?: "\(.*\)"/\1/p' build.gradle.kts); fi
export GBENCH_LIMITS="2 CPUs, 4 GB"
export GBENCH_IMAGE GBENCH_IMAGE_ID

source ./containers.sh

./gradlew --console=plain -q ${DRIVINE_SOURCE:+--include-build "$DRIVINE_SOURCE"} ${DRIVINE_VERSION:+-PdrivineVersion=$DRIVINE_VERSION} installDist 2>&1 | grep -E "^e: |FAILED" || true
for engine in $ENGINES; do
    start "$engine"
    build/install/graph-bench/bin/graph-bench run --engine "$engine" --sf "$SF" 2> "$GBENCH_RESULTS/$engine-sf$SF.log" || echo "$engine: run failed, see $GBENCH_RESULTS/$engine-sf$SF.log"
    [ "$engine" = sqlite ] && continue
    docker stats --no-stream --format "$engine: container memory {{.MemUsage}}" "$PREFIX-$engine"
    docker stop "$PREFIX-$engine" >/dev/null
done
build/install/graph-bench/bin/graph-bench report --sf "$SF"
