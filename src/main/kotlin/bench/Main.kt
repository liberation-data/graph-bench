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
)

data class LoadOutcome(val nodes: Long, val edges: Long, val schemaSeconds: Double, val nodeSeconds: Double, val edgeSeconds: Double)

data class Results(val engine: String, val scale: Double, val load: LoadOutcome?, val outcomes: List<Outcome>)

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

private fun file(engine: String, scale: Double) = File("results/$engine-sf$scale.json")

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
        file(engineName, factor).apply { parentFile.mkdirs() }.writeText(json.writeValueAsString(Results(engineName, factor, load, outcomes.values.toList())))
    }
}

private fun report(factor: Double) {
    val engines = listOf("neo4j", "falkordb", "oracle")
    val results = engines.mapNotNull { name -> file(name, factor).takeIf { it.exists() }?.let { json.readValue<Results>(it) } }
    if (results.isEmpty()) return println("No results for scale $factor")
    val names = results.map { it.engine }
    println("## Load\n")
    println("| | " + names.joinToString(" | ") + " |")
    println("|---|" + names.joinToString("") { "---:|" })
    fun loadRow(title: String, value: (LoadOutcome) -> String) =
        println("| $title | " + results.joinToString(" | ") { r -> r.load?.let(value) ?: "-" } + " |")
    loadRow("nodes") { "%,d".format(it.nodes) }
    loadRow("relationships") { "%,d".format(it.edges) }
    loadRow("nodes, seconds") { "%.1f".format(it.nodeSeconds) }
    loadRow("relationships, seconds") { "%.1f".format(it.edgeSeconds) }
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
    }
}

fun main(args: Array<String>) {
    val options = args.drop(1).chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    val factor = (options["sf"] ?: "1").toDouble()
    when (args.firstOrNull()) {
        "run" -> run(
            options["engine"] ?: error("--engine neo4j|falkordb|oracle"), factor,
            (options["phases"] ?: "load,read,traverse,concurrent,write").split(",").toSet(),
        )
        "report" -> report(factor)
        else -> println("run --engine <neo4j|falkordb|oracle> [--sf 1] [--phases load,read,traverse,concurrent,write] | report [--sf 1]")
    }
}
