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
# GBENCH_SEED=<folder> holds Guide's content seed, for the search workloads (see src/main/kotlin/bench/Seed.kt): with
# none, they are left out. GBENCH_PHASES=<phases> runs only those, as `search` or `load,read`; by default all but
# `hops`, as the harness has them.
# GBENCH_ORACLE_TEXT=1 gives Oracle's chunks a full-text index, so its searches go through Oracle Text (see Load.chunks);
# the results don't record it, so keep that run in a GBENCH_RESULTS folder of its own.
# GBENCH_PG_DEFER, GBENCH_PG_ONE_TX, GBENCH_PG_EXPLAIN and GBENCH_PG_GUARDS probe Postgres's edge load (drivine4j#684):
# see PostgresLoader in src/main/kotlin/bench/Load.kt. GBENCH_MIN_FREE_GB (20 by default) is the free disk Docker must have
# for an engine to be run.
set -euo pipefail
cd "$(dirname "$0")"
SF=${1:-1}; shift || true
ENGINES=${*:-neo4j falkordb oracle postgres}
LIMITS="--cpus 2 --memory 4g"

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

# The image each engine is run from.
image_of() { case $1 in neo4j) echo neo4j:2026.07.1 ;; falkordb) echo falkordb/falkordb:6.0.0 ;; oracle) echo gvenzl/oracle-free:23-faststart ;; postgres) echo pgvector/pgvector:pg17 ;; esac; }

# The disk Docker keeps containers on, in GB free, read from inside a container of [image]: on Docker Desktop it is the
# virtual machine's, which the host's own df doesn't show.
free_gb() { docker run --rm --entrypoint df "$1" -Pk / | awk 'NR == 2 { print int($4 / 1048576) }'; }

# Refuses to start [engine] on [image] with less than GBENCH_MIN_FREE_GB free (20 by default): a Postgres load at scale 10
# writes some 10 GB, and one that runs out of disk fails in the middle, or never comes up.
enough_disk() {
    local free; free=$(free_gb "$2")
    echo "$1: Docker's disk has ${free:-no} GB free (GBENCH_MIN_FREE_GB=${GBENCH_MIN_FREE_GB:-20})" >&2
    if [ "${free:-0}" -lt "${GBENCH_MIN_FREE_GB:-20}" ]; then
        echo "$1: not run, too little disk" >&2
        return 1
    fi
}

start() {
    docker rm -f -v "gbench-$1" >/dev/null 2>&1 || true
    case $1 in
        neo4j)
            GBENCH_IMAGE=$(image_of "$1")
            docker run -d --name gbench-neo4j $LIMITS -p 17687:7687 -e NEO4J_AUTH=neo4j/benchbench \
                -e NEO4J_server_memory_heap_initial__size=1g -e NEO4J_server_memory_heap_max__size=1g \
                -e NEO4J_server_memory_pagecache_size=2g "$GBENCH_IMAGE" >/dev/null
            until docker logs gbench-neo4j 2>&1 | grep -q "Started\."; do sleep 2; done ;;
        falkordb)
            GBENCH_IMAGE=$(image_of "$1")
            docker run -d --name gbench-falkordb $LIMITS -p 16379:6379 -e REDIS_ARGS="--save ''" "$GBENCH_IMAGE" >/dev/null
            until docker exec gbench-falkordb redis-cli ping 2>/dev/null | grep -q PONG; do sleep 1; done ;;
        oracle)
            GBENCH_IMAGE=$(image_of "$1")
            docker run -d --name gbench-oracle $LIMITS -p 11521:1521 -e ORACLE_PASSWORD=bench -e APP_USER=bench \
                -e APP_USER_PASSWORD=bench "$GBENCH_IMAGE" >/dev/null
            until docker logs gbench-oracle 2>&1 | grep -q "DATABASE IS READY TO USE"; do sleep 3; done ;;
        postgres)
            # Memory as the others are given it: a cache of 1 GB of the 4, as Neo4j's heap is, and the rest the
            # operating system's to cache files in, which is where Postgres expects the rest of its reads to come from.
            GBENCH_IMAGE=$(image_of "$1")
            docker run -d --name gbench-postgres $LIMITS -p 15432:5432 -e POSTGRES_DB=bench -e POSTGRES_USER=bench \
                -e POSTGRES_PASSWORD=bench "$GBENCH_IMAGE" \
                -c shared_buffers=1GB -c effective_cache_size=3GB -c maintenance_work_mem=256MB -c work_mem=32MB >/dev/null
            # The image starts a server to set itself up and stops it: ready is the second time it says so.
            until [ "$(docker logs gbench-postgres 2>&1 | grep -c "ready to accept connections")" -ge 2 ]; do sleep 1; done ;;
    esac
    # The tag says what was asked for; the id, what was run, as a tag like :latest moves.
    GBENCH_IMAGE_ID=$(docker image inspect --format '{{.Id}}' "$GBENCH_IMAGE" 2>/dev/null || true)
}

./gradlew --console=plain -q ${DRIVINE_SOURCE:+--include-build "$DRIVINE_SOURCE"} ${DRIVINE_VERSION:+-PdrivineVersion=$DRIVINE_VERSION} installDist 2>&1 | grep -E "^e: |FAILED" || true
for engine in $ENGINES; do
    docker rm -f -v "gbench-$engine" >/dev/null 2>&1 || true
    enough_disk "$engine" "$(image_of "$engine")" || continue
    start "$engine"
    build/install/graph-bench/bin/graph-bench run --engine "$engine" --sf "$SF" ${GBENCH_PHASES:+--phases "$GBENCH_PHASES"} 2> "$GBENCH_RESULTS/$engine-sf$SF.log" || echo "$engine: run failed, see $GBENCH_RESULTS/$engine-sf$SF.log"
    docker stats --no-stream --format "$engine: container memory {{.MemUsage}}" "gbench-$engine"
    # Its own log and how it ended, kept beside the run's, before the container goes.
    docker logs "gbench-$engine" > "$GBENCH_RESULTS/$engine-sf$SF.container.log" 2>&1 || true
    docker inspect --format "$engine: container {{.State.Status}}, exit {{.State.ExitCode}}, OOM killed {{.State.OOMKilled}}" "gbench-$engine" || true
    # With its volume: a stopped container keeps its data, some 10 GB at scale 10, and the next run then fills the disk.
    docker rm -f -v "gbench-$engine" >/dev/null
done
build/install/graph-bench/bin/graph-bench report --sf "$SF"
