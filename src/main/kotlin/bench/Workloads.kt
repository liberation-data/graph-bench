package bench

import org.drivine.manager.update
import org.drivine.query.dsl.query
import java.util.SplittableRandom
import java.util.UUID

/** One thing to time: [work] is given the number of the call, and picks its arguments by it. */
class Workload(val group: String, val name: String, val warmup: Int, val iterations: Int, val work: (Int) -> Any?)

/**
 * A statement written by hand, in Cypher and in SQL/PGQ. `§name` is a parameter in both.
 *
 * The SQL turns join elimination off. Oracle Free 23.26 drops the node a pattern starts from, and
 * the test of its id with it, when nothing else of the node is read: with the id written into the
 * statement that answers for every node, and with the id bound it ends the server process
 * (`ORA-07445` in `kkocfbMarkBindFroCB`). The price is a primary key probe for each node a pattern
 * passes through.
 */
class Raw(cypher: String, sql: String) {
    val cypher = cypher.trimIndent().replace('§', '$')
    val sql = sql.trimIndent().replace('§', '$').replaceFirst("SELECT", "SELECT /*+ OPT_PARAM('_optimizer_join_elimination_enabled' 'false') */")

    fun on(engine: Engine, vararg parameters: Pair<String, Any?>): List<Any> =
        engine.query(if (engine.sql) sql else cypher, mapOf(*parameters))
}

object Queries {

    val followers = Raw(
        "MATCH (:Person)-[:FOLLOWS]->(p:Person {id: §id}) RETURN count(*)",
        """
        SELECT COUNT(*) FROM GRAPH_TABLE ($GRAPH
            MATCH (a IS node) -[e IS edge WHERE e.type = 'FOLLOWS']-> (p IS node WHERE JSON_VALUE(p.props, '$.id') = §id)
            COLUMNS (e.eid AS eid))
        """,
    )

    val twoHops = Raw(
        "MATCH (p:Person {id: §id})-[:FOLLOWS]->()-[:FOLLOWS]->(x) RETURN count(DISTINCT x)",
        """
        SELECT COUNT(DISTINCT nid) FROM GRAPH_TABLE ($GRAPH
            MATCH (p IS node WHERE JSON_VALUE(p.props, '$.id') = §id)
                  -[e1 IS edge WHERE e1.type = 'FOLLOWS']-> (a IS node) -[e2 IS edge WHERE e2.type = 'FOLLOWS']-> (x IS node)
            COLUMNS (x.nid AS nid))
        """,
    )

    val threeHops = Raw(
        "MATCH (p:Person {id: §id})-[:FOLLOWS]->()-[:FOLLOWS]->()-[:FOLLOWS]->(x) RETURN count(DISTINCT x)",
        """
        SELECT COUNT(DISTINCT nid) FROM GRAPH_TABLE ($GRAPH
            MATCH (p IS node WHERE JSON_VALUE(p.props, '$.id') = §id)
                  -[e1 IS edge WHERE e1.type = 'FOLLOWS']-> (a IS node) -[e2 IS edge WHERE e2.type = 'FOLLOWS']-> (b IS node)
                  -[e3 IS edge WHERE e3.type = 'FOLLOWS']-> (x IS node)
            COLUMNS (x.nid AS nid))
        """,
    )

    val dependencies = Raw(
        "MATCH (r:Repo {id: §id})-[:DEPENDS_ON*1..4]->(d) RETURN count(DISTINCT d)",
        """
        SELECT COUNT(DISTINCT nid) FROM GRAPH_TABLE ($GRAPH
            MATCH (r IS node WHERE JSON_VALUE(r.props, '$.id') = §id) -[e IS edge WHERE e.type = 'DEPENDS_ON']->{1,4} (d IS node)
            COLUMNS (d.nid AS nid))
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
                MATCH (o IS node WHERE JSON_VALUE(o.props, '$.id') = §id) <-[e1 IS edge WHERE e1.type = 'OWNED_BY']- (r IS node)
                      <-[e2 IS edge WHERE e2.type = 'IN_REPO']- (i IS node WHERE JSON_VALUE(i.props, '$.state') = 'open')
                      -[e3 IS edge WHERE e3.type = 'TAGGED']-> (t IS node)
                COLUMNS (JSON_VALUE(t.props, '$.id') AS topic))
            GROUP BY topic ORDER BY n DESC, topic FETCH FIRST 10 ROWS ONLY)
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
                MATCH (r IS node WHERE JSON_VALUE(r.props, '$.id') = §id) <-[e1 IS edge WHERE e1.type = 'IN_REPO']- (i IS node)
                      <-[e2 IS edge WHERE e2.type = 'ON']- (c IS node) -[e3 IS edge WHERE e3.type = 'WRITTEN_BY']-> (p IS node)
                COLUMNS (JSON_VALUE(p.props, '$.id') AS person))
            GROUP BY person ORDER BY n DESC, person FETCH FIRST 10 ROWS ONLY)
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
                MATCH (p IS node WHERE JSON_VALUE(p.props, '$.id') = §id) -[e1 IS edge WHERE e1.type = 'FOLLOWS']-> (f IS node)
                      <-[e2 IS edge WHERE e2.type = 'OPENED_BY']- (i IS node) -[e3 IS edge WHERE e3.type = 'IN_REPO']-> (r IS node)
                COLUMNS (JSON_VALUE(r.props, '$.id') AS repo))
            GROUP BY repo ORDER BY n DESC, repo FETCH FIRST 10 ROWS ONLY)
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
    )
}

/**
 * A walk of [hops] FOLLOWS out from one person, counting the paths: the cost of going from a node
 * to its neighbours and nothing else. Neo4j and FalkorDB keep a node's relationships with it. Oracle
 * finds them in an index over the relationships' table, by the node they leave.
 *
 * The SQL finds the person's `nid` first and tests the first relationship's `src` against it, so it
 * names no column of the node the pattern starts from. That keeps clear of the Oracle fault [Raw]
 * turns join elimination off for, and leaves Oracle free to answer from the index alone.
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

    /** For each of [people] and each number of hops up to [upTo]: the paths counted, and the quickest of three calls in milliseconds. */
    fun measure(engine: Engine, people: List<String>, upTo: Int): List<List<Any>> = (1..upTo).flatMap { hops ->
        val statement = if (engine.sql) sql(hops) else cypher(hops)
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
