package bench

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.drivine.connection.NativeDriverSource
import java.io.File
import java.util.Locale
import javax.sql.DataSource

/**
 * The walk of [Hops] in three versions, measured over and over at one scale factor: so that the cost
 * of a path, and of reading the node a path reaches, can be set against the size of the graph.
 *
 * - `count` counts the paths, as [Hops] does. Nothing of a node is read but the person it starts from.
 * - `reach` sums the internal ids of the nodes the paths end at: what it takes to know which node a path
 *   reaches, without reading it. On the SQL stores that is the relationship's row, as the index over
 *   `(src, type)` that a walk is answered from does not hold `dst`, and a count of the last hop needs
 *   only the index. On Neo4j, `count` reads the relationship already, and the two should cost the same.
 * - `read` sums the `karma` of the person at the end of each path. That is one node read a path, of the
 *   node the last relationship reaches: by its pointer on Neo4j, by its primary key on the SQL stores.
 *   The sum is the same on every engine, so it checks the engines read the same nodes.
 *
 * Each round calls every person, for each number of hops and each of the three, three times, and keeps the
 * first and the quickest. The first is the one more likely to find what it needs out of the cache.
 * On Postgres, the counts of the blocks it read are taken before and after each round, and after the
 * rounds each call is profiled once, for what it found in its cache and what it read from outside it.
 */
object Adjacency {

    val variants = listOf("count", "reach", "read")

    /** The `nid` of the person whose id is `$id`, found by the index over the label's ids on each SQL store. */
    private fun start(engine: Engine): String = when {
        engine.postgres -> "(SELECT nid FROM ${GRAPH}_node WHERE (props ->> 'id') = \$id AND labels @> '[\"Person\"]')"
        // The expression and the label test of the partial index Drivine makes for a uniqueness constraint on SQLite.
        engine.sqlite -> "(SELECT nid FROM ${GRAPH}_node WHERE " +
            "(CASE json_type(props, '$.\"id\"') WHEN 'text' THEN props ->> '$.\"id\"' WHEN 'null' THEN NULL ELSE props -> '$.\"id\"' END) = \$id " +
            "AND instr(labels, '\"Person\"') > 0)"
        else -> "(SELECT nid FROM ${GRAPH}_node WHERE JSON_VALUE(props, '$.id') = \$id)"
    }

    fun statement(engine: Engine, hops: Int, variant: String): String {
        return when {
            engine.postgres || engine.sqlite -> {
                val joins = (2..hops).joinToString("") { "\nJOIN ${GRAPH}_edge e$it ON e$it.src = e${it - 1}.dst AND e$it.type = 'FOLLOWS'" }
                val karma = if (engine.postgres) "(x.props ->> 'karma')::int" else "(x.props ->> '$.karma')"
                val where = "\nWHERE e1.type = 'FOLLOWS' AND e1.src = ${start(engine)}"
                when (variant) {
                    "count" -> "SELECT COUNT(*) FROM ${GRAPH}_edge e1$joins$where"
                    "reach" -> "SELECT COALESCE(SUM(e$hops.dst), 0) FROM ${GRAPH}_edge e1$joins$where"
                    else -> "SELECT COALESCE(SUM($karma), 0) FROM ${GRAPH}_edge e1$joins\nJOIN ${GRAPH}_node x ON x.nid = e$hops.dst$where"
                }
            }
            engine.sql -> {
                if (variant == "count") return Hops.sql(hops)
                val steps = (1..hops).joinToString(" ") { "-[e$it IS edge WHERE e$it.type = 'FOLLOWS']-> (n$it IS node)" }
                val column = if (variant == "reach") "e$hops.dst" else "JSON_VALUE(n$hops.props, '$.karma' RETURNING NUMBER)"
                """
                SELECT COALESCE(SUM(v), 0) FROM GRAPH_TABLE ($GRAPH MATCH (p IS node) $steps
                    COLUMNS (e1.src AS src, $column AS v))
                WHERE src = ${start(engine)}
                """.trimIndent()
            }
            else -> when (variant) {
                "count" -> Hops.cypher(hops)
                "reach" -> Hops.cypher(hops).replace("RETURN count(*)", "RETURN sum(id(n$hops))")
                else -> Hops.cypher(hops).replace("RETURN count(*)", "RETURN sum(n$hops.karma)")
            }
        }
    }

    /** Postgres's counts of the blocks of the graph's tables read from its cache and from outside it; empty elsewhere. */
    private fun cache(engine: Engine): Map<String, Any?> {
        if (!engine.postgres) return emptyMap()
        // Postgres counts a backend's reads in memory and publishes them about once a second.
        Thread.sleep(1_500)
        @Suppress("UNCHECKED_CAST")
        return engine.query(
            """
            SELECT jsonb_object_agg(relname, jsonb_build_object(
                'heap_read', heap_blks_read, 'heap_hit', heap_blks_hit, 'index_read', idx_blks_read, 'index_hit', idx_blks_hit))
            FROM pg_statio_user_tables WHERE relname IN ('${GRAPH}_node', '${GRAPH}_edge')
            """.trimIndent(),
        ).single() as Map<String, Any?>
    }

    /** The kinds of a Postgres plan's nodes, its children's after its own. */
    private fun kinds(plan: JsonNode): List<String> =
        listOf(plan["Node Type"].asText()) + (plan["Plans"]?.flatMap(::kinds) ?: emptyList())

    /**
     * One call of [statement] for [id] on Postgres, with what it took from the cache and from outside it:
     * the shared buffers' hits and reads, from `EXPLAIN (ANALYZE, BUFFERS)`, and the kinds of the plan's
     * nodes. Null elsewhere: Neo4j Community's runtime reports no page cache hits or misses in a profile,
     * and SQLite reads through the host's cache of its file.
     */
    private fun profile(engine: Engine, statement: String, id: String): List<Any>? = when {
        engine.postgres -> {
            @Suppress("UNCHECKED_CAST")
            val dataSource = (engine.provider as NativeDriverSource<DataSource>).nativeDriver
            dataSource.connection.use { c ->
                c.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + statement.replace("\$id", "?")).use { s ->
                    s.setString(1, id)
                    s.executeQuery().use { r ->
                        r.next()
                        val plan = ObjectMapper().readTree(r.getString(1))[0]["Plan"]
                        listOf(plan["Shared Hit Blocks"].asLong(), plan["Shared Read Blocks"].asLong(), kinds(plan).distinct().joinToString(" "))
                    }
                }
            }
        }
        else -> null
    }

    /**
     * Runs the rounds and writes `hops-<engine>-sf<scale>.csv`, `cache-` and `profile-` to `results/adjacency/`,
     * or to the directory `BENCH_RESULTS` names, as `adjacency.sh` does.
     */
    fun run(engine: Engine, people: List<String>, factor: Double, rounds: Int = 3, upTo: Int = 4) {
        val statements = variants.associateWith { v -> (1..upTo).associateWith { statement(engine, it, v) } }
        fun call(variant: String, hops: Int, id: String): Pair<Long, Double> {
            val before = System.nanoTime()
            val value = (engine.query(statements.getValue(variant).getValue(hops), mapOf("id" to id)).single() as Number?)?.toLong() ?: 0L
            return value to (System.nanoTime() - before) / 1e6
        }
        // Once over everything first: the server has then compiled what it runs and holds what it can in memory.
        for (variant in variants) for (hops in 1..upTo) people.forEach { call(variant, hops, it) }
        val rows = mutableListOf<String>()
        val counters = mutableListOf<Map<String, Any?>>()
        for (round in 1..rounds) {
            for (variant in variants) {
                val before = cache(engine)
                val started = System.nanoTime()
                for (hops in 1..upTo) {
                    // SQLite's cache is a setting of the connection, and the pool renews its one connection every half hour.
                    if (engine.sqlite) engine.execute(SQLITE_CACHE)
                    for (id in people) {
                        val calls = (1..3).map { call(variant, hops, id) }
                        rows += listOf(round, variant, hops, id, calls.first().first, "%.4f".format(Locale.ROOT, calls.first().second), "%.4f".format(Locale.ROOT, calls.minOf { it.second })).joinToString(",")
                    }
                }
                val seconds = (System.nanoTime() - started) / 1e9
                counters += mapOf("round" to round, "variant" to variant, "seconds" to seconds, "before" to before, "after" to cache(engine))
                println("  round %d, %s: %.1f s".format(round, variant, seconds))
            }
        }
        // Last, once more for each call, what it found in the cache and what it did not.
        val profiled = variants.flatMap { variant ->
            (1..upTo).flatMap { hops ->
                people.mapNotNull { id -> profile(engine, statements.getValue(variant).getValue(hops), id)?.let { listOf(variant, hops, id) + it } }
            }
        }
        val dir = File(System.getenv("BENCH_RESULTS") ?: "results/adjacency").apply { mkdirs() }
        if (profiled.isNotEmpty()) {
            File(dir, "profile-${engine.name}-sf$factor.csv").writeText("variant,hops,person,hits,misses,plan\n" + profiled.joinToString("\n") { it.joinToString(",") } + "\n")
        }
        File(dir, "hops-${engine.name}-sf$factor.csv").writeText("round,variant,hops,person,value,first_ms,min_ms\n" + rows.joinToString("\n") + "\n")
        File(dir, "cache-${engine.name}-sf$factor.json").writeText(
            jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(counters),
        )
    }
}
