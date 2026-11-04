# Starts an engine's container, as run.sh and adjacency.sh have it: 2 CPUs and 4 GB, which is what Oracle
# Free allows itself whatever it is given. Sourced, not run.
#
# BENCH_CONTAINER_PREFIX names the containers (gbench-neo4j and so on by default), and BENCH_NEO4J_PORT,
# BENCH_FALKORDB_PORT, BENCH_ORACLE_PORT and BENCH_POSTGRES_PORT the ports, which the harness reads too.
PREFIX=${BENCH_CONTAINER_PREFIX:-gbench}
LIMITS="--cpus 2 --memory 4g"

# What each run is made with, kept beside its numbers (RunDetails), for run.sh and adjacency.sh alike: this harness's
# commit, the Drivine built against (the released version, or the commit of the source checkout), the image each
# engine is run from and its id once started, and the containers' limits.
# A copy without its git history (as a run on another machine is given) says its commit in .bench-commit, written
# where the history is: `git rev-parse --short HEAD > .bench-commit`.
commit_of() {
    if git -C "$1" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        git -C "$1" rev-parse --short HEAD | tr -d '\n'; git -C "$1" diff --quiet HEAD || printf -- '-dirty'
    elif [ -f "$1/.bench-commit" ]; then tr -d '\n' < "$1/.bench-commit"
    fi
}
export GBENCH_COMMIT; GBENCH_COMMIT=$(commit_of .)
export GBENCH_DRIVINE
if [ -n "${DRIVINE_SOURCE:-}" ]; then GBENCH_DRIVINE="source $(commit_of "$DRIVINE_SOURCE")"
elif [ -n "${DRIVINE_VERSION:-}" ]; then GBENCH_DRIVINE=$DRIVINE_VERSION
else GBENCH_DRIVINE=$(sed -n 's/^val drivineVersion = .*?: "\(.*\)"/\1/p' build.gradle.kts); fi
export GBENCH_IMAGE GBENCH_IMAGE_ID GBENCH_LIMITS

start() {
    docker rm -f "$PREFIX-$1" >/dev/null 2>&1 || true
    GBENCH_IMAGE=
    GBENCH_LIMITS="2 CPUs, 4 GB"
    case $1 in
        neo4j)
            GBENCH_IMAGE=neo4j:2026.07.1
            docker run -d --name $PREFIX-neo4j $LIMITS -p ${BENCH_NEO4J_PORT:-17687}:7687 -e NEO4J_AUTH=neo4j/benchbench \
                -e NEO4J_server_memory_heap_initial__size=1g -e NEO4J_server_memory_heap_max__size=1g \
                -e NEO4J_server_memory_pagecache_size=2g "$GBENCH_IMAGE" >/dev/null
            until docker logs $PREFIX-neo4j 2>&1 | grep -q "Started\."; do sleep 2; done ;;
        falkordb)
            GBENCH_IMAGE=falkordb/falkordb:6.0.0
            docker run -d --name $PREFIX-falkordb $LIMITS -p ${BENCH_FALKORDB_PORT:-16379}:6379 -e REDIS_ARGS="--save ''" "$GBENCH_IMAGE" >/dev/null
            until docker exec $PREFIX-falkordb redis-cli ping 2>/dev/null | grep -q PONG; do sleep 1; done ;;
        oracle)
            GBENCH_IMAGE=gvenzl/oracle-free:23-faststart
            docker run -d --name $PREFIX-oracle $LIMITS -p ${BENCH_ORACLE_PORT:-11521}:1521 -e ORACLE_PASSWORD=bench -e APP_USER=bench \
                -e APP_USER_PASSWORD=bench "$GBENCH_IMAGE" >/dev/null
            until docker logs $PREFIX-oracle 2>&1 | grep -q "DATABASE IS READY TO USE"; do sleep 3; done ;;
        postgres)
            # Memory as the others are given it: a cache of 1 GB of the 4, as Neo4j's heap is, and the rest the
            # operating system's to cache files in, which is where Postgres expects the rest of its reads to come from.
            GBENCH_IMAGE=pgvector/pgvector:pg17
            docker run -d --name $PREFIX-postgres $LIMITS -p ${BENCH_POSTGRES_PORT:-15432}:5432 -e POSTGRES_DB=bench -e POSTGRES_USER=bench \
                -e POSTGRES_PASSWORD=bench "$GBENCH_IMAGE" \
                -c shared_buffers=1GB -c effective_cache_size=3GB -c maintenance_work_mem=256MB -c work_mem=32MB >/dev/null
            # The image starts a server to set itself up and stops it: ready is the second time it says so.
            until [ "$(docker logs $PREFIX-postgres 2>&1 | grep -c "ready to accept connections")" -ge 2 ]; do sleep 1; done ;;
        sqlite) GBENCH_LIMITS="none: a file, opened in the harness's own process" ;;
    esac
    # The tag says what was asked for; the id, what was run, as a tag like :latest moves.
    GBENCH_IMAGE_ID=$([ -n "$GBENCH_IMAGE" ] && docker image inspect --format '{{.Id}}' "$GBENCH_IMAGE" 2>/dev/null || true)
}

