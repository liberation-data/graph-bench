package bench

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** What a workload took, in milliseconds, and a digest of what it returned. */
data class Outcome(
    val group: String,
    val name: String,
    val calls: Int,
    val p50: Double,
    val p95: Double,
    val mean: Double,
    val min: Double,
    val max: Double,
    /** Calls a second, for a concurrent run. */
    val throughput: Double? = null,
    /** A digest of what the first calls returned: the same on every engine when they agree. */
    val digest: String? = null,
    /** For a search, the ids each of the first calls found, best first: to compare one engine's ranking with another's. */
    val rankings: List<List<String>>? = null,
)

data class LoadOutcome(val nodes: Long, val edges: Long, val schemaSeconds: Double, val nodeSeconds: Double, val edgeSeconds: Double)

/** The chunks the searches search, saved through Drivine, and the seconds the save and their indexes took. */
data class ChunksOutcome(val chunks: Int, val saveSeconds: Double, val indexSeconds: Double)

/**
 * What a run was made on and against, so two runs are compared knowing what differs between them: when it ended, the
 * machine and the JVM it ran on, the engine's image (by tag and by id) and the limits its container was given, the
 * Drivine it was built against, and this harness's own commit. What `run.sh` knows it passes in the environment; a
 * run started some other way leaves those out.
 */
data class RunDetails(
    val at: String,
    val machine: String,
    val jvm: String,
    val drivine: String?,
    val bench: String?,
    val image: String?,
    val imageId: String?,
    val limits: String?,
) {
    companion object {
        fun now(): RunDetails {
            val env = System.getenv()
            return RunDetails(
                at = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(),
                machine = machine(),
                jvm = "${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}",
                drivine = env["GBENCH_DRIVINE"]?.takeIf { it.isNotBlank() },
                bench = env["GBENCH_COMMIT"]?.takeIf { it.isNotBlank() },
                image = env["GBENCH_IMAGE"]?.takeIf { it.isNotBlank() },
                imageId = env["GBENCH_IMAGE_ID"]?.takeIf { it.isNotBlank() },
                limits = env["GBENCH_LIMITS"]?.takeIf { it.isNotBlank() },
            )
        }

        /** "Apple M2 Max, 12 cores, 64 GB, Mac OS X 26.1 aarch64": the processor, as the system names it, and the rest. */
        private fun machine(): String {
            val os = System.getProperty("os.name")
            val processor = runCatching {
                when {
                    os.startsWith("Mac") -> ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string").start().inputStream.bufferedReader().readText().trim()
                    else -> File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("model name") }?.substringAfter(":")?.trim()
                }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: System.getProperty("os.arch")
            val memory = (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
                ?.totalMemorySize?.let { "%d GB".format(Math.round(it / 1024.0 / 1024 / 1024)) }
            val cores = "${Runtime.getRuntime().availableProcessors()} cores"
            return listOfNotNull(processor, cores, memory, "$os ${System.getProperty("os.version")} ${System.getProperty("os.arch")}").joinToString(", ")
        }
    }
}

/** A run's results, as written to `results/<engine>-sf<scale>.json`. Files from before `run` was kept have none. */
data class Results(
    val engine: String,
    val scale: Double,
    val load: LoadOutcome?,
    val outcomes: List<Outcome>,
    val run: RunDetails? = null,
    val chunks: ChunksOutcome? = null,
)

private val json: ObjectMapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

/** How long a workload may be measured for before it is cut short, in seconds. */
private const val BUDGET_SECONDS = 60.0

/**
 * A value as text that reads the same whichever engine gave it: a map by its sorted keys, a list as a
 * set (the engines return related nodes in different orders), a whole number without a fraction.
 */
private fun canonical(value: Any?): String = when (value) {
    null -> "null"
    is Map<*, *> -> value.entries.filter { it.value != null && it.key != "labels" }.map { "${it.key}=${canonical(it.value)}" }.sorted().joinToString(",", "{", "}")
    is Collection<*> -> value.map { canonical(it) }.sorted().joinToString(",", "[", "]")
    is Number -> if (value.toDouble() == Math.floor(value.toDouble())) value.toLong().toString() else value.toString()
    is String, is Boolean -> value.toString()
    else -> canonical(json.convertValue(value, Map::class.java))
}

private fun digest(values: List<Any?>): String =
    MessageDigest.getInstance("SHA-256").digest(canonical(values).toByteArray()).take(4).joinToString("") { "%02x".format(it) }

private fun percentile(sorted: List<Double>, p: Double) = sorted[((sorted.size - 1) * p).toInt()]

private fun measure(workload: Workload): Outcome {
    repeat(workload.warmup) { workload.work(it) }
    val sampled = mutableListOf<Any?>()
    val millis = mutableListOf<Double>()
    val started = System.nanoTime()
    for (i in 0 until workload.iterations) {
        val before = System.nanoTime()
        val result = workload.work(workload.warmup + i)
        millis += (System.nanoTime() - before) / 1e6
        if (i < 5) sampled += result
        if ((System.nanoTime() - started) / 1e9 > BUDGET_SECONDS && millis.size >= 5) break
    }
    val sorted = millis.sorted()
    // A write returns ids made for the call, which no two runs share.
    val comparable = !workload.group.endsWith("write")
    return Outcome(
        workload.group, workload.name, sorted.size, percentile(sorted, 0.5), percentile(sorted, 0.95),
        sorted.average(), sorted.first(), sorted.last(), digest = if (comparable) digest(sampled) else null,
        rankings = if (workload.ranked) sampled.map { found -> (found as List<*>).map { it.toString() } } else null,
    )
}

private fun concurrently(workload: Workload, threads: Int, seconds: Int): Outcome {
    repeat(50) { workload.work(it) }
    val pool = Executors.newFixedThreadPool(threads)
    val next = AtomicInteger()
    val ready = CountDownLatch(1)
    val deadline = System.nanoTime() + seconds * 1_000_000_000L
    val started = System.nanoTime()
    val taken = (1..threads).map {
        pool.submit<List<Double>> {
            val mine = mutableListOf<Double>()
            ready.await()
            while (System.nanoTime() < deadline) {
                val before = System.nanoTime()
                workload.work(next.getAndIncrement())
                mine += (System.nanoTime() - before) / 1e6
            }
            mine
        }
    }
    ready.countDown()
    val sorted = taken.flatMap { it.get() }.sorted()
    val elapsed = (System.nanoTime() - started) / 1e9
    pool.shutdown()
    return Outcome(
        "$threads threads", workload.name, sorted.size, percentile(sorted, 0.5), percentile(sorted, 0.95),
        sorted.average(), sorted.first(), sorted.last(), throughput = sorted.size / elapsed,
    )
}

private fun show(o: Outcome) = println(
    "  %-70s p50 %9.2f ms  p95 %9.2f ms  n=%-4d %s %s".format(
        o.name.take(70), o.p50, o.p95, o.calls, o.throughput?.let { "%,.0f/s".format(it) } ?: "", o.digest ?: "",
    ),
)

/**
 * Where results are kept: `results/`, or another folder named by GBENCH_RESULTS, so runs of two Drivine versions, or on
 * two machines, sit side by side rather than over each other.
 */
private val resultsDir: String get() = System.getenv("GBENCH_RESULTS")?.takeIf { it.isNotBlank() } ?: "results"

private fun file(engine: String, scale: Double) = File("$resultsDir/$engine-sf$scale.json")

private fun run(engineName: String, factor: Double, phases: Set<String>) {
    val data = Dataset(Scale(factor))
    Engine.open(engineName).use { engine ->
        val previous = file(engineName, factor).takeIf { it.exists() }?.let { json.readValue<Results>(it) }
        var load = previous?.load
        if ("load" in phases) {
            val there = runCatching { Load.loaded(engine, data) }.getOrDefault(false)
            if (there) {
                println("$engineName: the graph is loaded already")
            } else {
                println("$engineName: loading %,d nodes".format(data.scale.nodes))
                val report = Load.run(engine, data)
                load = LoadOutcome(report.nodes, report.edges, report.schemaSeconds, report.nodeSeconds, report.edgeSeconds)
                println("$engineName: %,d nodes in %.1f s, %,d relationships in %.1f s".format(report.nodes, report.nodeSeconds, report.edges, report.edgeSeconds))
            }
        }
        var chunks = previous?.chunks
        val workloads = Workloads(engine, data.scale)
        val outcomes = previous?.outcomes.orEmpty().associateBy { it.group to it.name }.toMutableMap()
        fun each(phase: String, list: List<Workload>, measured: (Workload) -> Outcome) {
            if (phase !in phases) return
            println("$engineName: $phase")
            for (workload in list) {
                val outcome = runCatching { measured(workload) }.getOrElse {
                    println("  FAILED ${workload.name}: ${it.message?.lineSequence()?.firstOrNull()}")
                    return@getOrElse null
                } ?: continue
                show(outcome)
                outcomes[outcome.group to outcome.name] = outcome
            }
        }
        each("read", workloads.objectReads, ::measure)
        each("traverse", workloads.traversals, ::measure)
        each("concurrent", workloads.concurrent) { concurrently(it, 8, 15) }
        each("write", workloads.writes, ::measure)
        if ("search" in phases) {
            val searches = runCatching { searchWorkloads(engineName) }.getOrElse {
                println("$engineName: FAILED loading Guide's chunks: ${it.message?.lineSequence()?.firstOrNull()}")
                null
            }
            searches?.let { (loaded, search) ->
                if (loaded != null) chunks = loaded
                search.use { each("search", it.workloads.searches, ::measure) }
            }
        }
        if ("hops" in phases) {
            println("$engineName: hops")
            // Once over everything first: the server has then compiled what it runs and holds the graph in memory.
            Hops.measure(engine, workloads.walkers, 4)
            val rows = Hops.measure(engine, workloads.walkers, 4)
            File("$resultsDir/hops-$engineName-sf$factor.csv").writeText("hops,person,paths,ms\n" + rows.joinToString("\n") { it.joinToString(",") } + "\n")
            for ((hops, of) in rows.groupBy { it[0] }) {
                val paths = of.map { (it[2] as Long).toDouble() }.sorted()
                val millis = of.map { it[3] as Double }.sorted()
                println("  %d hops: median %,.0f paths, %.2f ms; largest %,.0f paths, %.2f ms".format(hops, percentile(paths, 0.5), percentile(millis, 0.5), paths.last(), millis.last()))
            }
        }
        file(engineName, factor).apply { parentFile.mkdirs() }
            .writeText(json.writeValueAsString(Results(engineName, factor, load, outcomes.values.toList(), RunDetails.now(), chunks)))
    }
}

/** The search workloads, over the chunks of Guide's seed in a graph of their own, and the engine they are run on. */
private class Search(val engine: Engine, val workloads: SearchWorkloads) : AutoCloseable {
    override fun close() = engine.close()
}

/**
 * Opens [engineName]'s search graph and loads Guide's chunks into it if they are not there: the load's numbers, when
 * there was one, and the searches. Null when GBENCH_SEED names no seed, and the searches are left out.
 */
private fun searchWorkloads(engineName: String): Pair<ChunksOutcome?, Search>? {
    val folder = Seed.folder ?: return null.also { println("$engineName: search left out: GBENCH_SEED names no folder with Guide's seed (see Seed.kt)") }
    val chunks = Seed.chunks(folder)
    val engine = Engine.open(engineName, SEARCH_GRAPH)
    var loaded: ChunksOutcome? = null
    if (runCatching { Load.chunksLoaded(engine, chunks) }.getOrDefault(false)) {
        println("$engineName: Guide's chunks are loaded already")
    } else {
        println("$engineName: loading %,d of Guide's chunks".format(chunks.size))
        val report = Load.chunks(engine, chunks)
        loaded = ChunksOutcome(report.chunks, report.saveSeconds, report.indexSeconds)
        println("$engineName: %,d chunks saved in %.1f s, indexed in %.1f s".format(report.chunks, report.saveSeconds, report.indexSeconds))
    }
    return loaded to Search(engine, SearchWorkloads(engine, chunks))
}

private fun report(factor: Double) {
    val engines = listOf("neo4j", "falkordb", "oracle", "postgres")
    val results = engines.mapNotNull { name -> file(name, factor).takeIf { it.exists() }?.let { json.readValue<Results>(it) } }
    if (results.isEmpty()) return println("No results for scale $factor")
    val names = results.map { it.engine }
    println("## Runs\n")
    println("| engine | ended | machine | image | Drivine | bench |")
    println("|---|---|---|---|---|---|")
    for (r in results) {
        val run = r.run
        println("| ${r.engine} | ${run?.at ?: "-"} | ${run?.machine ?: "-"} | ${run?.image ?: "-"} | ${run?.drivine ?: "-"} | ${run?.bench ?: "-"} |")
    }
    // Numbers from two machines, or with limits that differ, say as much about the machines as the engines.
    val machines = results.mapNotNull { it.run?.machine }.distinct()
    val limits = results.mapNotNull { it.run?.limits }.distinct()
    if (machines.size > 1) println("\nThese runs were made on ${machines.size} machines: their numbers don't compare as they would on one.")
    if (limits.size > 1) println("\nThese runs' containers had different limits: ${limits.joinToString(" and ")}.")
    if (results.any { it.run == null }) println("\nSome results were written before runs kept their details: what they were made on isn't known.")
    println()
    println("## Load\n")
    println("| | " + names.joinToString(" | ") + " |")
    println("|---|" + names.joinToString("") { "---:|" })
    fun loadRow(title: String, value: (LoadOutcome) -> String) =
        println("| $title | " + results.joinToString(" | ") { r -> r.load?.let(value) ?: "-" } + " |")
    loadRow("nodes") { "%,d".format(it.nodes) }
    loadRow("relationships") { "%,d".format(it.edges) }
    loadRow("nodes, seconds") { "%.1f".format(it.nodeSeconds) }
    loadRow("relationships, seconds") { "%.1f".format(it.edgeSeconds) }
    if (results.any { it.chunks != null }) {
        fun chunksRow(title: String, value: (ChunksOutcome) -> String) =
            println("| $title | " + results.joinToString(" | ") { r -> r.chunks?.let(value) ?: "-" } + " |")
        chunksRow("Guide's chunks, for the searches") { "%,d".format(it.chunks) }
        chunksRow("chunks saved, seconds") { "%.1f".format(it.saveSeconds) }
        chunksRow("chunks indexed, seconds") { "%.1f".format(it.indexSeconds) }
    }
    val keys = results.flatMap { r -> r.outcomes.map { it.group to it.name } }.distinct()
    for ((group, inGroup) in keys.groupBy { it.first }) {
        val concurrent = group.endsWith("threads")
        println("\n## $group (${if (concurrent) "calls a second, and p50 ms" else "p50 ms, p95 in brackets"})\n")
        println("| workload | " + names.joinToString(" | ") + " | same answer |")
        println("|---|" + names.joinToString("") { "---:|" } + ":---:|")
        for (key in inGroup) {
            val found = results.map { r -> r.outcomes.firstOrNull { it.group to it.name == key } }
            val cells = found.map { o ->
                when {
                    o == null -> "failed"
                    concurrent -> "%,.0f/s (%.1f)".format(o.throughput, o.p50)
                    else -> "%.2f (%.2f)".format(o.p50, o.p95)
                }
            }
            val digests = found.mapNotNull { it?.digest }
            val agree = if (digests.isEmpty()) "" else if (digests.distinct().size == 1 && digests.size == found.size) "yes" else "NO"
            println("| ${key.second} | " + cells.joinToString(" | ") + " | $agree |")
        }
        rankings(results, inGroup)
    }
}

/**
 * How the engines' rankings of the same searches compare: for each engine, how many it found a call, and of them how
 * many Neo4j's top found too (or the first engine's, where Neo4j wasn't run), with how many of the calls gave Neo4j's
 * order exactly; and the same between Oracle and Postgres, which both score by BM25 and should agree with each other.
 */
private fun rankings(results: List<Results>, keys: List<Pair<String, String>>) {
    val ranked = keys.filter { key -> results.any { r -> r.outcomes.any { it.group to it.name == key && it.rankings != null } } }
    if (ranked.isEmpty()) return
    fun of(r: Results?, key: Pair<String, String>) = r?.outcomes?.firstOrNull { it.group to it.name == key }?.rankings
    fun compared(mine: List<List<String>>, theirs: List<List<String>>): String {
        val calls = mine.zip(theirs)
        val shared = calls.map { (a, b) -> a.intersect(b.toSet()).size }.average()
        val same = calls.count { (a, b) -> a == b }
        return "%.1f shared, %d/%d in order".format(shared, same, calls.size)
    }
    val reference = results.firstOrNull { it.engine == "neo4j" } ?: results.first()
    val oracle = results.firstOrNull { it.engine == "oracle" }
    val postgres = results.firstOrNull { it.engine == "postgres" }
    println("\n### rankings: found a call, and how many of them ${reference.engine} found too\n")
    println("| workload | " + results.joinToString(" | ") { it.engine } + " | oracle and postgres |")
    println("|---|" + results.joinToString("") { "---|" } + "---|")
    for (key in ranked) {
        val mine = of(reference, key)
        val cells = results.map { r ->
            val found = of(r, key) ?: return@map "-"
            val count = "%.1f found".format(found.map { it.size }.average())
            if (r === reference || mine == null) count else "$count, ${compared(found, mine)}"
        }
        val sql = of(oracle, key)?.let { o -> of(postgres, key)?.let { p -> compared(o, p) } } ?: "-"
        println("| ${key.second} | " + cells.joinToString(" | ") + " | $sql |")
    }
}

fun main(args: Array<String>) {
    val options = args.drop(1).chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    val factor = (options["sf"] ?: "1").toDouble()
    when (args.firstOrNull()) {
        "run" -> run(
            options["engine"] ?: error("--engine neo4j|falkordb|oracle|postgres"), factor,
            (options["phases"] ?: "load,read,traverse,concurrent,write,search").split(",").toSet(),
        )
        "report" -> report(factor)
        else -> println("run --engine <neo4j|falkordb|oracle|postgres> [--sf 1] [--phases load,read,traverse,concurrent,write,search] | report [--sf 1]")
    }
}
