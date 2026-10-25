package bench

import org.drivine.manager.update
import org.drivine.query.dsl.query
import java.util.SplittableRandom
import java.util.UUID

/** One thing to time: [work] is given the number of the call, and picks its arguments by it. */
class Workload(val group: String, val name: String, val warmup: Int, val iterations: Int, val work: (Int) -> Any?)

/**
 * A statement written by hand, in Cypher and in SQL/PGQ. `§name` is a parameter in both, and `§start`
 * in the SQL is the `nid` of the node whose id is `§id`.
 *
 * The SQL finds the node a pattern starts from by that `nid`, tested against the first relationship's
 * end, and names no column of the node itself. Oracle Free 23.26 drops the node a pattern starts from,
 * and a test of its id with it, when the test is `JSON_VALUE` of its properties and nothing else of the
 * node is read: with the id written into the statement that answers for every node, and with the id
 * bound it ends the server process (`ORA-07445` in `kkocfbMarkBindFroCB`). Written this way there is
 * no such test to drop, and Oracle may answer the first hop from the relationships' index alone.
 */
class Raw(cypher: String, sql: String, postgres: String) {
    val cypher = cypher.trimIndent().replace('§', '$')
    val sql = sql.trimIndent().replace("§start", "(SELECT nid FROM ${GRAPH}_node WHERE JSON_VALUE(props, '$.id') = §id)").replace('§', '$')
    val postgres = postgres.trimIndent().replace('§', '$')

    fun on(engine: Engine, vararg parameters: Pair<String, Any?>): List<Any> {
        check(!engine.sqlite) { "The traversals written by hand are not written for SQLite: it runs the load and adjacency phases" }
        return engine.query(if (engine.postgres) postgres else if (engine.sql) sql else cypher, mapOf(*parameters))
    }
}

/**
 * Postgres reads the graph Drivine keeps there with joins over its tables. A walk starts from the `nid`
 * of the node whose id is `§id`, found by its label and id: the index over a label's ids is a partial
 * index, over that label's nodes alone, so the label is named for the index to answer.
 */
private fun start(label: String) = "(SELECT nid FROM ${GRAPH}_node WHERE (props ->> 'id') = §id AND labels @> '[\"$label\"]')"

object Queries {

    val followers = Raw(
        "MATCH (:Person)-[:FOLLOWS]->(p:Person {id: §id}) RETURN count(*)",
        """
        SELECT COUNT(*) FROM GRAPH_TABLE ($GRAPH
            MATCH (a IS node) -[e IS edge WHERE e.type = 'FOLLOWS']-> (p IS node)
            COLUMNS (e.dst AS start_))
        WHERE start_ = §start
        """,
        """
        SELECT COUNT(*) FROM ${GRAPH}_edge e WHERE e.type = 'FOLLOWS' AND e.dst = ${start("Person")}
        """,
    )

    val twoHops = Raw(
        "MATCH (p:Person {id: §id})-[:FOLLOWS]->()-[:FOLLOWS]->(x) RETURN count(DISTINCT x)",
        """
        SELECT COUNT(DISTINCT nid) FROM GRAPH_TABLE ($GRAPH
            MATCH (p IS node)
                  -[e1 IS edge WHERE e1.type = 'FOLLOWS']-> (a IS node) -[e2 IS edge WHERE e2.type = 'FOLLOWS']-> (x IS node)
            COLUMNS (e1.src AS start_, x.nid AS nid))
        WHERE start_ = §start
        """,
        """
        SELECT COUNT(DISTINCT e2.dst) FROM ${GRAPH}_edge e1
        JOIN ${GRAPH}_edge e2 ON e2.src = e1.dst AND e2.type = 'FOLLOWS'
        WHERE e1.type = 'FOLLOWS' AND e1.src = ${start("Person")}
        """,
    )

    val threeHops = Raw(
        "MATCH (p:Person {id: §id})-[:FOLLOWS]->()-[:FOLLOWS]->()-[:FOLLOWS]->(x) RETURN count(DISTINCT x)",
        """
        SELECT COUNT(DISTINCT nid) FROM GRAPH_TABLE ($GRAPH
            MATCH (p IS node)
                  -[e1 IS edge WHERE e1.type = 'FOLLOWS']-> (a IS node) -[e2 IS edge WHERE e2.type = 'FOLLOWS']-> (b IS node)
                  -[e3 IS edge WHERE e3.type = 'FOLLOWS']-> (x IS node)
            COLUMNS (e1.src AS start_, x.nid AS nid))
        WHERE start_ = §start
        """,
        """
        SELECT COUNT(DISTINCT e3.dst) FROM ${GRAPH}_edge e1
        JOIN ${GRAPH}_edge e2 ON e2.src = e1.dst AND e2.type = 'FOLLOWS'
        JOIN ${GRAPH}_edge e3 ON e3.src = e2.dst AND e3.type = 'FOLLOWS'
        WHERE e1.type = 'FOLLOWS' AND e1.src = ${start("Person")}
        """,
    )

    /**
     * A relationship taken several times has no one end to test, so the SQL reads the `nid` of the node
     * itself; and it tests it with `IN`, which Oracle takes into each length of the walk. With `=` it
     * walks from every repo and tests the start afterwards.
     */
    val dependencies = Raw(
        "MATCH (r:Repo {id: §id})-[:DEPENDS_ON*1..4]->(d) RETURN count(DISTINCT d)",
        """
        SELECT COUNT(DISTINCT nid) FROM GRAPH_TABLE ($GRAPH
            MATCH (r IS node) -[e IS edge WHERE e.type = 'DEPENDS_ON']->{1,4} (d IS node)
            COLUMNS (r.nid AS start_, d.nid AS nid))
        WHERE start_ IN §start
        """,
        """
        WITH RECURSIVE reached (nid, depth) AS (
            SELECT e.dst, 1 FROM ${GRAPH}_edge e WHERE e.type = 'DEPENDS_ON' AND e.src = ${start("Repo")}
            UNION
            SELECT e.dst, r.depth + 1 FROM reached r JOIN ${GRAPH}_edge e ON e.src = r.nid AND e.type = 'DEPENDS_ON' WHERE r.depth < 4
        )
        SELECT COUNT(DISTINCT nid) FROM reached
        """,
    )

    val orgTopics = Raw(
        """
        MATCH (o:Org {id: §id})<-[:OWNED_BY]-(:Repo)<-[:IN_REPO]-(i:Issue)-[:TAGGED]->(t:Topic)
        WHERE i.state = 'open'
        WITH t.id AS topic, count(*) AS n
        ORDER BY n DESC, topic LIMIT 10
        RETURN {topic: topic, n: n} AS r
        """,
        """
        SELECT JSON_OBJECT('topic' VALUE topic, 'n' VALUE n RETURNING JSON) AS r FROM (
            SELECT topic, COUNT(*) AS n FROM GRAPH_TABLE ($GRAPH
                MATCH (o IS node) <-[e1 IS edge WHERE e1.type = 'OWNED_BY']- (r IS node)
                      <-[e2 IS edge WHERE e2.type = 'IN_REPO']- (i IS node WHERE JSON_VALUE(i.props, '$.state') = 'open')
                      -[e3 IS edge WHERE e3.type = 'TAGGED']-> (t IS node)
                COLUMNS (e1.dst AS start_, JSON_VALUE(t.props, '$.id') AS topic))
            WHERE start_ = §start
            GROUP BY topic ORDER BY n DESC, topic FETCH FIRST 10 ROWS ONLY)
        """,
        """
        SELECT jsonb_build_object('topic', topic, 'n', n) AS r FROM (
            SELECT t.props ->> 'id' AS topic, COUNT(*) AS n
            FROM ${GRAPH}_edge e1
            JOIN ${GRAPH}_edge e2 ON e2.dst = e1.src AND e2.type = 'IN_REPO'
            JOIN ${GRAPH}_node i ON i.nid = e2.src AND (i.props ->> 'state') = 'open'
            JOIN ${GRAPH}_edge e3 ON e3.src = i.nid AND e3.type = 'TAGGED'
            JOIN ${GRAPH}_node t ON t.nid = e3.dst
            WHERE e1.type = 'OWNED_BY' AND e1.dst = ${start("Org")}
            GROUP BY topic ORDER BY n DESC, topic LIMIT 10) g
        """,
    )

    val repoCommenters = Raw(
        """
        MATCH (r:Repo {id: §id})<-[:IN_REPO]-(:Issue)<-[:ON]-(:Comment)-[:WRITTEN_BY]->(p:Person)
        WITH p.id AS person, count(*) AS n
        ORDER BY n DESC, person LIMIT 10
        RETURN {person: person, n: n} AS r
        """,
        """
        SELECT JSON_OBJECT('person' VALUE person, 'n' VALUE n RETURNING JSON) AS r FROM (
            SELECT person, COUNT(*) AS n FROM GRAPH_TABLE ($GRAPH
                MATCH (r IS node) <-[e1 IS edge WHERE e1.type = 'IN_REPO']- (i IS node)
                      <-[e2 IS edge WHERE e2.type = 'ON']- (c IS node) -[e3 IS edge WHERE e3.type = 'WRITTEN_BY']-> (p IS node)
                COLUMNS (e1.dst AS start_, JSON_VALUE(p.props, '$.id') AS person))
            WHERE start_ = §start
            GROUP BY person ORDER BY n DESC, person FETCH FIRST 10 ROWS ONLY)
        """,
        """
        SELECT jsonb_build_object('person', person, 'n', n) AS r FROM (
            SELECT p.props ->> 'id' AS person, COUNT(*) AS n
            FROM ${GRAPH}_edge e1
            JOIN ${GRAPH}_edge e2 ON e2.dst = e1.src AND e2.type = 'ON'
            JOIN ${GRAPH}_edge e3 ON e3.src = e2.src AND e3.type = 'WRITTEN_BY'
            JOIN ${GRAPH}_node p ON p.nid = e3.dst
            WHERE e1.type = 'IN_REPO' AND e1.dst = ${start("Repo")}
            GROUP BY person ORDER BY n DESC, person LIMIT 10) g
        """,
    )

    val recommendedRepos = Raw(
        """
        MATCH (p:Person {id: §id})-[:FOLLOWS]->(:Person)<-[:OPENED_BY]-(:Issue)-[:IN_REPO]->(r:Repo)
        WITH r.id AS repo, count(*) AS n
        ORDER BY n DESC, repo LIMIT 10
        RETURN {repo: repo, n: n} AS r
        """,
        """
        SELECT JSON_OBJECT('repo' VALUE repo, 'n' VALUE n RETURNING JSON) AS r FROM (
            SELECT repo, COUNT(*) AS n FROM GRAPH_TABLE ($GRAPH
                MATCH (p IS node) -[e1 IS edge WHERE e1.type = 'FOLLOWS']-> (f IS node)
                      <-[e2 IS edge WHERE e2.type = 'OPENED_BY']- (i IS node) -[e3 IS edge WHERE e3.type = 'IN_REPO']-> (r IS node)
                COLUMNS (e1.src AS start_, JSON_VALUE(r.props, '$.id') AS repo))
            WHERE start_ = §start
            GROUP BY repo ORDER BY n DESC, repo FETCH FIRST 10 ROWS ONLY)
        """,
        """
        SELECT jsonb_build_object('repo', repo, 'n', n) AS r FROM (
            SELECT r.props ->> 'id' AS repo, COUNT(*) AS n
            FROM ${GRAPH}_edge e1
            JOIN ${GRAPH}_edge e2 ON e2.dst = e1.dst AND e2.type = 'OPENED_BY'
            JOIN ${GRAPH}_edge e3 ON e3.src = e2.src AND e3.type = 'IN_REPO'
            JOIN ${GRAPH}_node r ON r.nid = e3.dst
            WHERE e1.type = 'FOLLOWS' AND e1.src = ${start("Person")}
            GROUP BY repo ORDER BY n DESC, repo LIMIT 10) g
        """,
    )

    val issuesByState = Raw(
        "MATCH (i:Issue) WITH i.state AS state, count(*) AS n RETURN {state: state, n: n} AS r",
        """
        SELECT JSON_OBJECT('state' VALUE state, 'n' VALUE COUNT(*) RETURNING JSON) AS r FROM GRAPH_TABLE ($GRAPH
            MATCH (i IS node WHERE JSON_EXISTS(i.labels, '$[*]?(@ == "Issue")'))
            COLUMNS (JSON_VALUE(i.props, '$.state') AS state))
        GROUP BY state
        """,
        """
        SELECT jsonb_build_object('state', props ->> 'state', 'n', COUNT(*)) AS r FROM ${GRAPH}_node
        WHERE labels @> '["Issue"]'
        GROUP BY props ->> 'state'
        """,
    )

    val mostFollowed = Raw(
        """
        MATCH (:Person)-[:FOLLOWS]->(p:Person)
        WITH p.id AS person, count(*) AS n
        ORDER BY n DESC, person LIMIT 10
        RETURN {person: person, n: n} AS r
        """,
        """
        SELECT JSON_OBJECT('person' VALUE person, 'n' VALUE n RETURNING JSON) AS r FROM (
            SELECT person, COUNT(*) AS n FROM GRAPH_TABLE ($GRAPH
                MATCH (a IS node) -[e IS edge WHERE e.type = 'FOLLOWS']-> (p IS node)
                COLUMNS (JSON_VALUE(p.props, '$.id') AS person))
            GROUP BY person ORDER BY n DESC, person FETCH FIRST 10 ROWS ONLY)
        """,
        """
        SELECT jsonb_build_object('person', person, 'n', n) AS r FROM (
            SELECT p.props ->> 'id' AS person, COUNT(*) AS n
            FROM ${GRAPH}_edge e JOIN ${GRAPH}_node p ON p.nid = e.dst
            WHERE e.type = 'FOLLOWS'
            GROUP BY person ORDER BY n DESC, person LIMIT 10) g
        """,
    )
}

/**
 * A walk of [hops] FOLLOWS out from one person, counting the paths: the cost of going from a node
 * to its neighbours and nothing else. Neo4j and FalkorDB keep a node's relationships with it. Oracle
 * finds them in an index over the relationships' table, by the node they leave.
 *
 * The SQL finds the person's `nid` first and tests the first relationship's `src` against it, so it
 * names no column of the node the pattern starts from, as [Raw] does and for the same reason.
 */
object Hops {
    /** The nodes on the way are named: FalkorDB takes paths through a node with no name as one path, and counts fewer. */
    fun cypher(hops: Int): String = "MATCH (p:Person {id: \$id})" + (1..hops).joinToString("") { "-[:FOLLOWS]->(n$it)" } + " RETURN count(*)"

    fun sql(hops: Int): String {
        val steps = (1..hops).joinToString(" ") { "-[e$it IS edge WHERE e$it.type = 'FOLLOWS']-> (n$it IS node)" }
        return """
            SELECT COUNT(*) FROM GRAPH_TABLE ($GRAPH MATCH (p IS node) $steps COLUMNS (e1.src AS src))
            WHERE src = (SELECT nid FROM ${GRAPH}_node WHERE JSON_VALUE(props, '$.id') = ${'$'}id)
        """.trimIndent()
    }

    /** The walk on Postgres: a join of the relationships' table for each hop, from the person's `nid`. */
    fun postgres(hops: Int): String {
        val joins = (2..hops).joinToString("") { "\nJOIN ${GRAPH}_edge e$it ON e$it.src = e${it - 1}.dst AND e$it.type = 'FOLLOWS'" }
        return "SELECT COUNT(*) FROM ${GRAPH}_edge e1$joins\nWHERE e1.type = 'FOLLOWS' AND e1.src = " +
            "(SELECT nid FROM ${GRAPH}_node WHERE (props ->> 'id') = \$id AND labels @> '[\"Person\"]')"
    }

    /** For each of [people] and each number of hops up to [upTo]: the paths counted, and the quickest of three calls in milliseconds. */
    fun measure(engine: Engine, people: List<String>, upTo: Int): List<List<Any>> = (1..upTo).flatMap { hops ->
        val statement = if (engine.postgres) postgres(hops) else if (engine.sql) sql(hops) else cypher(hops)
        fun call(id: String): Pair<Long, Double> {
            val before = System.nanoTime()
            val paths = (engine.query(statement, mapOf("id" to id)).single() as Number).toLong()
            return paths to (System.nanoTime() - before) / 1e6
        }
        people.take(20).forEach { call(it) }
        people.map { id -> (1..3).map { call(id) }.let { calls -> listOf(hops, id, calls.first().first, calls.minOf { it.second }) } }
    }
}

class Workloads(private val engine: Engine, private val scale: Scale) {

    /** The people the hop-by-hop walks start from: the same on every engine. */
    val walkers: List<String> = SplittableRandom(99).let { r -> List(150) { "p" + r.nextInt(scale.persons) } }


    private val gom = engine.gom

    /** The ids a workload asks for, call by call: the same on every engine. */
    private fun ids(prefix: String, below: Int, seed: Long): (Int) -> String {
        val picked = SplittableRandom(seed).let { r -> List(2_000) { prefix + r.nextInt(below) } }
        return { picked[it % picked.size] }
    }

    private fun fresh(prefix: String) = "$prefix-new-" + UUID.randomUUID()
    private fun newIssue() = Issue(fresh("i"), "A new issue", "open", 2, System.currentTimeMillis(), "r0")
    private fun newComments(n: Int) = List(n) { Comment(fresh("c"), "a comment written by the benchmark, number $it", System.currentTimeMillis()) }
    private fun newPerson() = fresh("p").let { Person(it, "New person", "$it@example.org", 0) }

    private val person = ids("p", scale.persons, 1)
    private val issue = ids("i", scale.issues, 2)
    private val repo = ids("r", scale.repos, 3)
    private val org = ids("o", scale.orgs, 4)

    val objectReads: List<Workload> = listOf(
        Workload("object manager: read", "load a fragment by id", 30, 300) { gom.load(person(it), Person::class.java) },
        Workload("object manager: read", "load a view: issue + repo, author, assignees, topics, comments", 30, 300) {
            gom.load(issue(it), IssueDetail::class.java)
        },
        Workload("object manager: read", "load a nested view: issue, comments, each comment's author", 30, 300) {
            gom.load(issue(it), IssueThread::class.java)
        },
        Workload("object manager: read", "load a view with a count: repo + owner, dependencies, issue count", 30, 300) {
            gom.load(repo(it), RepoOverview::class.java)
        },
        Workload("object manager: read", "load a path view: orgs of the people a person follows", 20, 200) {
            gom.load(person(it), PersonReach::class.java)
        },
        Workload("object manager: read", "loadAll by an indexed property: a repo's open issues", 20, 200) {
            val key = repo(it)
            gom.loadAll(Issue::class.java, IssueQueryDsl.INSTANCE) {
                where {
                    query.repoKey eq key
                    query.state eq "open"
                }
            }
        },
        Workload("object manager: read", "count by unindexed properties: open issues of priority 1", 3, 20) {
            gom.count(Issue::class.java, IssueQueryDsl.INSTANCE) {
                where {
                    query.state eq "open"
                    query.priority eq 1
                }
            }
        },
        Workload("object manager: read", "count every comment", 3, 20) { gom.count(Comment::class.java) },
    )

    val traversals: List<Workload> = listOf(
        Workload("traversal", "1 hop: a person's followers, counted", 30, 300) { Queries.followers.on(engine, "id" to person(it)) },
        Workload("traversal", "2 hops: who the people I follow follow, distinct", 20, 200) { Queries.twoHops.on(engine, "id" to person(it)) },
        Workload("traversal", "3 hops: the same, one further", 5, 50) { Queries.threeHops.on(engine, "id" to person(it)) },
        Workload("traversal", "1 to 4 hops: a repo's transitive dependencies", 20, 200) { Queries.dependencies.on(engine, "id" to repo(it)) },
        Workload("traversal", "3 hops and a group: an org's top topics among open issues", 20, 200) { Queries.orgTopics.on(engine, "id" to org(it)) },
        Workload("traversal", "3 hops and a group: a repo's top commenters", 20, 200) { Queries.repoCommenters.on(engine, "id" to repo(it)) },
        Workload("traversal", "3 hops and a group: repos the people I follow raise issues in", 20, 200) {
            Queries.recommendedRepos.on(engine, "id" to person(it))
        },
        Workload("whole graph", "every issue, grouped by state", 2, 10) { Queries.issuesByState.on(engine) },
        Workload("whole graph", "every FOLLOWS, grouped: the ten most followed", 2, 10) { Queries.mostFollowed.on(engine) },
    )

    val writes: List<Workload> = listOf(
        Workload("object manager: write", "save a new fragment", 10, 100) { gom.save(newPerson()) },
        Workload("object manager: write", "update a fragment: load, change, save", 10, 100) {
            gom.update<Issue>(issue(it)) { loaded -> loaded.copy(title = "Retitled " + UUID.randomUUID()) }
        },
        Workload("object manager: write", "save a new view: an issue and 10 new comments", 5, 50) {
            gom.save(IssueComments(newIssue(), newComments(10)))
        },
        Workload("object manager: write", "save a new view: an issue and 100 new comments", 3, 20) {
            gom.save(IssueComments(newIssue(), newComments(100)))
        },
        Workload("object manager: write", "saveAll: 1,000 new fragments", 2, 10) { gom.saveAll(List(1_000) { newPerson() }) },
        Workload("object manager: write", "saveAll: 100 new views of 5 comments", 2, 10) {
            gom.saveAll(List(100) { IssueComments(newIssue(), newComments(5)) })
        },
    )

    /** What the threads of a concurrent run each do, over and over. */
    val concurrent: List<Workload> = listOf(
        Workload("8 threads", "load a view: issue + repo, author, assignees, topics, comments", 0, 0) { gom.load(issue(it), IssueDetail::class.java) },
        Workload("8 threads", "2 hops: who the people I follow follow, distinct", 0, 0) { Queries.twoHops.on(engine, "id" to person(it)) },
    )
}
