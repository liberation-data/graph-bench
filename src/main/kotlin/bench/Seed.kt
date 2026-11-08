package bench

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

/**
 * Guide's content seed: the docs Guide answers from, as its Postgres database holds them after an ingest, written out
 * by Guide's CI (`scripts/export-postgres-seed.sh` in embabel/guide). It is not kept here. Fetch it into a folder of
 * its own, and name that folder in GBENCH_SEED:
 *
 * ```
 * gh run download 42417580385 -R embabel/guide -n seed-postgres -D <folder>
 * ```
 *
 * The folder holds `nodes.tsv` and `edges.tsv`, Drivine's node and relationship tables on Postgres as `COPY` writes
 * them, and `apply.sql`, which Guide runs to load them; nothing here runs it. Only `nodes.tsv` is read, and of it only
 * the chunks: each one's id, text and embedding. They are written into each engine through Drivine (see
 * [Load.chunks]), so all four hold the same corpus.
 */
object Seed {

    /** The folder GBENCH_SEED names, or null when it names none. */
    val folder: File? get() = System.getenv("GBENCH_SEED")?.takeIf { it.isNotBlank() }?.let(::File)

    private val json = jacksonObjectMapper()

    /** The chunks of the seed in [folder], in the order the seed has them. */
    fun chunks(folder: File): List<Chunk> {
        val nodes = File(folder, "nodes.tsv")
        require(nodes.isFile) { "No nodes.tsv in $folder: fetch Guide's seed there, as Seed.kt says" }
        return nodes.useLines { lines ->
            lines.mapNotNull { line ->
                // nid, labels, props, stamp: each a field of COPY's text format.
                val fields = line.split('\t')
                val labels = json.readValue(unescaped(fields[1]), List::class.java)
                if ("Chunk" !in labels) return@mapNotNull null
                val props = json.readValue(unescaped(fields[2]), Map::class.java)
                Chunk(
                    id = props["id"] as String,
                    text = props["text"] as String,
                    embedding = (props["embedding"] as List<*>).map { (it as Number).toFloat() },
                )
            }.toList()
        }.also {
            // A seed with no chunks would pass for one loaded already (a count of none is all of them) and leave no searches.
            require(it.isNotEmpty()) { "No chunks in $nodes: is it Guide's content seed?" }
        }
    }

    /** A field of `COPY`'s text format as the text it stands for: a backslash and what follows it are one character. */
    private fun unescaped(field: String): String {
        if ('\\' !in field) return field
        val out = StringBuilder(field.length)
        var i = 0
        while (i < field.length) {
            val c = field[i]
            if (c != '\\' || i + 1 == field.length) {
                out.append(c); i++; continue
            }
            val next = field[i + 1]
            i += 2
            when (next) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'r' -> out.append('\r')
                'b' -> out.append('\b')
                'f' -> out.append('\u000c')
                'v' -> out.append('\u000b')
                'x' -> {
                    val hex = field.substring(i).takeWhile { it.isLetterOrDigit() && it.lowercaseChar() in "0123456789abcdef" }.take(2)
                    out.append(hex.toInt(16).toChar()); i += hex.length
                }
                in '0'..'7' -> {
                    val octal = (next + field.substring(i).takeWhile { it in '0'..'7' }.take(2))
                    out.append(octal.toInt(8).toChar()); i += octal.length - 1
                }
                else -> out.append(next)
            }
        }
        return out.toString()
    }
}
