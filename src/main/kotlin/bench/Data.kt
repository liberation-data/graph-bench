package bench

import java.util.SplittableRandom
import kotlin.math.pow

/** How many of each node there are. A scale factor of 1 is about 1.1 million nodes and 3.7 million relationships. */
class Scale(val factor: Double) {
    private fun of(base: Int) = (base * factor).toInt().coerceAtLeast(10)

    val orgs = of(1_000)
    val persons = of(100_000)
    val repos = of(20_000)
    val topics = of(500).coerceAtMost(500)
    val issues = of(250_000)
    val comments = of(750_000)

    val nodes get() = orgs.toLong() + persons + repos + topics + issues + comments
}

/** A set of relationships of one type, from nodes of one label to nodes of another, as pairs of ids. */
class EdgeSet(val type: String, val from: String, val to: String, val pairs: Sequence<Pair<String, String>>)

/**
 * The graph, made the same way every time it is asked for: each stream has a seed of its own, so a
 * loader that reads them in order gives every engine the same nodes and relationships.
 *
 * Two shapes are deliberate. FOLLOWS never runs both ways between two people, and DEPENDS_ON only
 * points at a repo made earlier, so no walk of a few hops can use a relationship twice. Cypher
 * refuses such a walk and SQL/PGQ allows it; without them the two would count different things.
 */
class Dataset(val scale: Scale) {

    private val countries = listOf("AU", "DE", "US", "BR", "IN", "JP", "FR", "NL", "CA", "KE")
    private val languages = listOf("Kotlin", "Java", "Rust", "Go", "Python", "TypeScript", "Swift", "C++")
    private val words = ("graph query index path node edge label merge match plan cache batch stream shard vector " +
        "token latency commit schema driver cursor buffer thread socket parser").split(" ")

    private fun random(stream: Int) = SplittableRandom(0x5EEDL * 31 + stream)

    /** An index below [n], low ones far likelier: a few popular nodes and a long tail. */
    private fun SplittableRandom.skewed(n: Int, power: Double = 2.0): Int = (n * nextDouble().pow(power)).toInt().coerceAtMost(n - 1)

    private fun SplittableRandom.text(count: Int): String = (1..count).joinToString(" ") { words[nextInt(words.size)] }

    /** The repo of each issue, worked out once: the issue's node carries it and so does its IN_REPO. */
    private val issueRepo: IntArray = random(100).let { r -> IntArray(scale.issues) { r.skewed(scale.repos) } }

    val labels = listOf("Org", "Person", "Repo", "Topic", "Issue", "Comment")

    fun count(label: String): Int = when (label) {
        "Org" -> scale.orgs
        "Person" -> scale.persons
        "Repo" -> scale.repos
        "Topic" -> scale.topics
        "Issue" -> scale.issues
        "Comment" -> scale.comments
        else -> error(label)
    }

    fun nodes(label: String): Sequence<Map<String, Any>> = when (label) {
        "Org" -> random(1).let { r ->
            (0 until scale.orgs).asSequence().map { mapOf("id" to "o$it", "name" to "Org $it", "country" to countries[r.nextInt(countries.size)]) }
        }
        "Person" -> random(2).let { r ->
            (0 until scale.persons).asSequence().map {
                mapOf("id" to "p$it", "name" to "Person $it", "email" to "p$it@example.org", "karma" to r.nextInt(10_000))
            }
        }
        "Repo" -> random(3).let { r ->
            (0 until scale.repos).asSequence().map {
                mapOf("id" to "r$it", "name" to "repo-$it", "language" to languages[r.nextInt(languages.size)], "stars" to r.skewed(50_000, 4.0))
            }
        }
        "Topic" -> (0 until scale.topics).asSequence().map { mapOf("id" to "t$it", "name" to "topic-$it") }
        "Issue" -> random(4).let { r ->
            (0 until scale.issues).asSequence().map {
                val roll = r.nextInt(100)
                mapOf(
                    "id" to "i$it",
                    "title" to "Issue $it: ${r.text(6)}",
                    "state" to (if (roll < 40) "open" else if (roll < 95) "closed" else "triaged"),
                    "priority" to 1 + r.nextInt(4),
                    "createdAt" to 1_700_000_000_000L + r.nextLong(50_000_000_000L),
                    "repoKey" to "r${issueRepo[it]}",
                )
            }
        }
        "Comment" -> random(5).let { r ->
            (0 until scale.comments).asSequence().map {
                mapOf("id" to "c$it", "body" to r.text(12 + r.nextInt(20)), "createdAt" to 1_700_000_000_000L + r.nextLong(50_000_000_000L))
            }
        }
        else -> error(label)
    }

    /** Whether i may follow j: for each two people only one direction is allowed, picked by a hash of the pair. */
    private fun mayFollow(i: Int, j: Int): Boolean {
        val low = minOf(i, j)
        val high = maxOf(i, j)
        val bit = (((low * 31L + high) * 0x9E3779B97F4A7C15uL.toLong()) ushr 40) and 1L
        return (bit == 0L) == (i < j)
    }

    /** For each of [sources] nodes, the distinct targets [pick] gives it. The stream is seeded when it is read, so it reads the same each time. */
    private fun fanOut(stream: Int, sources: Int, from: String, to: String, pick: (SplittableRandom, Int, MutableSet<Int>) -> Unit): Sequence<Pair<String, String>> = sequence {
        val r = random(stream)
        val targets = LinkedHashSet<Int>()
        for (i in 0 until sources) {
            targets.clear()
            pick(r, i, targets)
            for (t in targets) yield("$from$i" to "$to$t")
        }
    }

    val edges: List<EdgeSet> by lazy {
        val s = scale
        listOf(
            EdgeSet("MEMBER_OF", "Person", "Org", fanOut(10, s.persons, "p", "o") { r, _, out ->
                repeat(if (r.nextInt(100) < 30) 2 else 1) { out += r.skewed(s.orgs) }
            }),
            EdgeSet("FOLLOWS", "Person", "Person", fanOut(11, s.persons, "p", "p") { r, i, out ->
                val wanted = 1 + (60 * r.nextDouble().pow(3.0)).toInt()
                var tries = 0
                while (out.size < wanted && tries++ < wanted * 4) {
                    val j = r.skewed(s.persons)
                    if (j != i && mayFollow(i, j)) out += j
                }
            }),
            EdgeSet("OWNED_BY", "Repo", "Org", fanOut(12, s.repos, "r", "o") { r, _, out -> out += r.skewed(s.orgs) }),
            EdgeSet("DEPENDS_ON", "Repo", "Repo", fanOut(13, s.repos, "r", "r") { r, i, out ->
                if (i > 0) repeat(r.nextInt(9)) { out += r.skewed(i) }
            }),
            EdgeSet("IN_REPO", "Issue", "Repo", fanOut(19, s.issues, "i", "r") { _, i, out -> out += issueRepo[i] }),
            EdgeSet("OPENED_BY", "Issue", "Person", fanOut(14, s.issues, "i", "p") { r, _, out -> out += r.skewed(s.persons) }),
            EdgeSet("ASSIGNED_TO", "Issue", "Person", fanOut(15, s.issues, "i", "p") { r, _, out ->
                val roll = r.nextInt(100)
                repeat(if (roll < 40) 0 else if (roll < 80) 1 else 2) { out += r.nextInt(s.persons) }
            }),
            EdgeSet("TAGGED", "Issue", "Topic", fanOut(16, s.issues, "i", "t") { r, _, out ->
                repeat(1 + r.nextInt(3)) { out += r.skewed(s.topics) }
            }),
            EdgeSet("ON", "Comment", "Issue", fanOut(17, s.comments, "c", "i") { r, _, out -> out += r.skewed(s.issues, 1.5) }),
            EdgeSet("WRITTEN_BY", "Comment", "Person", fanOut(18, s.comments, "c", "p") { r, _, out -> out += r.skewed(s.persons) }),
        )
    }
}
