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
ENGINES=${*:-neo4j falkordb oracle}
LIMITS="--cpus 2 --memory 4g"

start() {
    docker rm -f "gbench-$1" >/dev/null 2>&1 || true
    case $1 in
        neo4j)
            docker run -d --name gbench-neo4j $LIMITS -p 17687:7687 -e NEO4J_AUTH=neo4j/benchbench \
                -e NEO4J_server_memory_heap_initial__size=1g -e NEO4J_server_memory_heap_max__size=1g \
                -e NEO4J_server_memory_pagecache_size=2g neo4j:2026.07.1 >/dev/null
            until docker logs gbench-neo4j 2>&1 | grep -q "Started\."; do sleep 2; done ;;
        falkordb)
            docker run -d --name gbench-falkordb $LIMITS -p 16379:6379 -e REDIS_ARGS="--save ''" falkordb/falkordb:6.0.0 >/dev/null
            until docker exec gbench-falkordb redis-cli ping 2>/dev/null | grep -q PONG; do sleep 1; done ;;
        oracle)
            docker run -d --name gbench-oracle $LIMITS -p 11521:1521 -e ORACLE_PASSWORD=bench -e APP_USER=bench \
                -e APP_USER_PASSWORD=bench gvenzl/oracle-free:23-faststart >/dev/null
            until docker logs gbench-oracle 2>&1 | grep -q "DATABASE IS READY TO USE"; do sleep 3; done ;;
    esac
}

./gradlew --console=plain -q ${DRIVINE_SOURCE:+--include-build "$DRIVINE_SOURCE"} installDist 2>&1 | grep -E "^e: |FAILED" || true
for engine in $ENGINES; do
    start "$engine"
    build/install/graph-bench/bin/graph-bench run --engine "$engine" --sf "$SF" 2> "results/$engine-sf$SF.log" || echo "$engine: run failed, see results/$engine-sf$SF.log"
    docker stats --no-stream --format "$engine: container memory {{.MemUsage}}" "gbench-$engine"
    docker stop "gbench-$engine" >/dev/null
done
build/install/graph-bench/bin/graph-bench report --sf "$SF"
