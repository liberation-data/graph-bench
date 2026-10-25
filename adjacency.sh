#!/bin/bash
# The hop by hop walk at one scale factor, for the question of when index-free adjacency pays: see
# "When does index-free adjacency pay?" in the README. For each engine in turn, in a fresh container
# with run.sh's limits: load the graph, restart the server, write down the store's size, then run the
# adjacency phase.
#
#   ./adjacency.sh <scale factor> [engine ...]       # neo4j postgres sqlite by default
#
# Everything goes to results/adjacency/ (or BENCH_RESULTS): the calls, the engine's cache counters, the sizes of the
# store, and the host's load and containers before and after, since other work on the host moves
# the numbers. SQLite is a file under build/sqlite/, opened by the harness itself, with no container.
set -euo pipefail
cd "$(dirname "$0")"
# The scale factor as the harness writes it in a file's name: 1.0, 0.3.
SF=$(awk -v s="${1:-1}" 'BEGIN { if (s == int(s)) printf "%.1f", s; else print s + 0 }'); shift || true
ENGINES=${*:-neo4j postgres sqlite}
source ./containers.sh
# Where everything goes: results/adjacency/, or the directory BENCH_RESULTS names, for a second run.
OUT=${BENCH_RESULTS:-results/adjacency}
mkdir -p "$OUT"
bench=build/install/graph-bench/bin/graph-bench
# The results of the load too, kept apart from those of run.sh.
export BENCH_RESULTS=$OUT

# The host as it is: its load average and every container's use of it, those not the benchmark's as "other".
host() {
    echo "== $1 $(date -u +%FT%TZ)"
    uptime
    docker stats --no-stream --format "{{.Name}} cpu {{.CPUPerc}} mem {{.MemUsage}} io {{.BlockIO}}" |
        awk -v ours="^$PREFIX-" '$1 !~ ours { $1 = "other" } { print }'
}

# The server stopped and started again: Neo4j writes its store files out when it stops, and has no
# procedure to make it do so in Community, so its sizes are only right after. Postgres is restarted
# too, so that each engine's walk starts from a server that has just started, warmed by a pass first.
# FalkorDB is not: it keeps its graph in memory alone here, and would start again empty.
restart() {
    docker restart "$PREFIX-$1" >/dev/null
    case $1 in
        neo4j) until docker exec "$PREFIX-neo4j" cypher-shell -u neo4j -p benchbench "RETURN 1" >/dev/null 2>&1; do sleep 2; done ;;
        postgres) until docker exec "$PREFIX-postgres" pg_isready -U bench -d bench >/dev/null 2>&1; do sleep 1; done ;;
        # The log keeps the first start's line: ready again is the second time it says so.
        oracle) until [ "$(docker logs "$PREFIX-oracle" 2>&1 | grep -c "DATABASE IS READY TO USE")" -ge 2 ]; do sleep 3; done ;;
    esac
}

# What the store takes, on disk or in memory, each part on a line.
sizes() {
    case $1 in
        neo4j) docker exec "$PREFIX-neo4j" sh -c 'cd /data/databases/neo4j && du -ab . | sort -k2' ;;
        postgres) docker exec "$PREFIX-postgres" psql -U bench -d bench -At -F ' ' -c "
            SELECT c.relname, c.relkind, pg_relation_size(c.oid) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relname LIKE 'bench%' ORDER BY 1;
            SELECT 'database', 'd', pg_database_size('bench');
            CREATE EXTENSION IF NOT EXISTS pageinspect;
            SELECT 'levels below the root of ' || i, (bt_metap(i)).level FROM unnest(ARRAY['bench_edge_src', 'bench_node_pkey', 'bench_uq_personid_aa4b5586']) i;
            SELECT 'FOLLOWS out of a person: mean, median, p90, p99, max', round(avg(n), 2), percentile_disc(ARRAY[0.5, 0.9, 0.99, 1]) WITHIN GROUP (ORDER BY n)
            FROM (SELECT count(*) AS n FROM bench_edge WHERE type = 'FOLLOWS' GROUP BY src) o;
            SELECT 'FOLLOWS into a person: mean, median, p90, p99, max', round(avg(n), 2), percentile_disc(ARRAY[0.5, 0.9, 0.99, 1]) WITHIN GROUP (ORDER BY n)
            FROM (SELECT count(*) AS n FROM bench_edge WHERE type = 'FOLLOWS' GROUP BY dst) i;" ;;
        sqlite) db=${BENCH_SQLITE_PATH:-build/sqlite/bench-sf$SF.db}
            echo "file $(wc -c < "$db")"
            # The depth of each b-tree is the number of steps in the path dbstat gives its deepest page.
            sqlite3 "$db" "SELECT name, SUM(pgsize), MAX(length(path) - length(replace(path, '/', ''))) FROM dbstat GROUP BY name ORDER BY name" ;;
        falkordb) docker exec "$PREFIX-falkordb" redis-cli GRAPH.MEMORY USAGE bench
            docker exec "$PREFIX-falkordb" redis-cli INFO memory | grep used_memory: ;;
    esac
}

./gradlew --console=plain -q ${DRIVINE_SOURCE:+--include-build "$DRIVINE_SOURCE"} installDist 2>&1 | grep -E "^e: |FAILED" || true
for engine in $ENGINES; do
    log="$OUT/env-$engine-sf$SF.txt"
    host "before start" > "$log"
    start "$engine"
    "$bench" run --engine "$engine" --sf "$SF" --phases load 2> "$OUT/$engine-sf$SF.log" | tee -a "$log"
    [ "$engine" = sqlite ] || [ "$engine" = falkordb ] || restart "$engine"
    sizes "$engine" > "$OUT/sizes-$engine-sf$SF.txt" 2>&1 || true
    host "before adjacency" >> "$log"
    "$bench" run --engine "$engine" --sf "$SF" --phases adjacency 2>> "$OUT/$engine-sf$SF.log" | tee -a "$log"
    host "after adjacency" >> "$log"
    [ "$engine" = sqlite ] && continue
    docker rm -f -v "$PREFIX-$engine" >/dev/null
done
