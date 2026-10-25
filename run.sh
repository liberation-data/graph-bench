#!/bin/bash
# Runs the benchmark against each engine in turn, each in a fresh container of its own with the same
# limits: 2 CPUs and 4 GB, which is what Oracle Free allows itself whatever it is given.
#
#   ./run.sh <scale factor> [engine ...]
#
# DRIVINE_SOURCE=<a checkout of drivine4j> builds against that source and not the released version.
set -euo pipefail
cd "$(dirname "$0")"
SF=${1:-1}; shift || true
ENGINES=${*:-neo4j falkordb oracle postgres}
source ./containers.sh

./gradlew --console=plain -q ${DRIVINE_SOURCE:+--include-build "$DRIVINE_SOURCE"} installDist 2>&1 | grep -E "^e: |FAILED" || true
for engine in $ENGINES; do
    start "$engine"
    build/install/graph-bench/bin/graph-bench run --engine "$engine" --sf "$SF" 2> "results/$engine-sf$SF.log" || echo "$engine: run failed, see results/$engine-sf$SF.log"
    [ "$engine" = sqlite ] && continue
    docker stats --no-stream --format "$engine: container memory {{.MemUsage}}" "$PREFIX-$engine"
    docker stop "$PREFIX-$engine" >/dev/null
done
build/install/graph-bench/bin/graph-bench report --sf "$SF"
