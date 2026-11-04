package bench

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.connection.NativeDriverSource
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.UniquenessConstraintSpec
import org.postgresql.PGConnection
import javax.sql.DataSource

/**
 * Fills an engine with the [Dataset], by the quickest means the engine has that an application could
 * use: batches of rows in Cypher for the graph engines, JDBC batches into the graph's tables for
 * Oracle and SQLite, and `COPY` into the same tables for Postgres. The paths differ, so load times say what a
 * bulk load costs on each and are not a like for like comparison of one statement.
 */
object Load {

    /** How many nodes and relationships were written, and how long each part took, in seconds. */
    class Report(val nodes: Long, val edges: Long, val schemaSeconds: Double, val nodeSeconds: Double, val edgeSeconds: Double)

    private fun <T> timed(work: () -> T): Pair<T, Double> = System.nanoTime().let { start -> work() to (System.nanoTime() - start) / 1e9 }

    /** The indexes every engine gets: each label's id, unique where the engine can afford it, and an issue's repo. */
    fun schema(engine: Engine, data: Dataset) {
        if (engine.name == "falkordb") {
            // FalkorDB makes a graph when it is first written to, and schema commands need it there.
            engine.execute("CREATE (:_Init)")
            engine.execute("MATCH (n:_Init) DELETE n")
        }
        for (label in data.labels) {
            // FalkorDB 6.0.0 checks a unique constraint in time that grows with the label: 10,000 nodes take
            // 17 seconds to create and the next 10,000 take 35. It gets a plain index over the id.
            if (engine.name == "falkordb") engine.pm.indexes.ensure(RangeIndexSpec(label, "id"))
            else engine.pm.constraints.ensure(UniquenessConstraintSpec(label, "id"))
        }
        engine.pm.indexes.ensure(RangeIndexSpec("Issue", "repoKey"))
        if (engine.name == "neo4j") engine.execute("CALL db.awaitIndexes(300)")
    }

    fun loaded(engine: Engine, data: Dataset): Boolean = engine.gom.count(Comment::class.java) >= data.scale.comments

    fun run(engine: Engine, data: Dataset): Report {
        val (_, schemaSeconds) = timed { schema(engine, data) }
        val loader = when {
            engine.postgres -> PostgresLoader(engine)
            engine.sqlite -> SqliteLoader(engine)
            engine.sql -> OracleLoader(engine)
            else -> CypherLoader(engine)
        }
        val (nodes, nodeSeconds) = timed {
            data.labels.sumOf { label ->
                loader.nodes(label, data.nodes(label)).also { println("  loaded %,d %s".format(it, label)) }
            }
        }
        val (edges, edgeSeconds) = timed {
            loader.beforeEdges()
            data.edges.sumOf { set -> loader.edges(set).also { println("  loaded %,d %s".format(it, set.type)) } }
        }
        loader.finish()
        return Report(nodes, edges, schemaSeconds, nodeSeconds, edgeSeconds)
    }

    private interface Loader {
        fun nodes(label: String, rows: Sequence<Map<String, Any>>): Long
        fun beforeEdges() {}
        fun edges(set: EdgeSet): Long
        fun finish() {}
    }

    private class CypherLoader(private val engine: Engine) : Loader {
        // FalkorDB is sent its parameters as text in the query, so a batch is kept smaller there.
        private val batch = if (engine.name == "falkordb") 5_000 else 20_000

        override fun nodes(label: String, rows: Sequence<Map<String, Any>>): Long {
            var count = 0L
            rows.chunked(batch).forEach {
                engine.execute("UNWIND \$rows AS row CREATE (n:$label) SET n = row", mapOf("rows" to it))
                count += it.size
            }
            return count
        }

        override fun edges(set: EdgeSet): Long {
            var count = 0L
            set.pairs.chunked(batch).forEach { pairs ->
                engine.execute(
                    "UNWIND \$rows AS row MATCH (a:${set.from} {id: row.s}) MATCH (b:${set.to} {id: row.d}) CREATE (a)-[:${set.type}]->(b)",
                    mapOf("rows" to pairs.map { mapOf("s" to it.first, "d" to it.second) }),
                )
                count += pairs.size
            }
            return count
        }
    }

    /**
     * Rows into the tables Drivine keeps a graph in: `bench_node (labels, props)` and
     * `bench_edge (src, dst, type)`. A relationship row names its nodes by `nid`, which Oracle
     * assigns, so the pairs of ids go to a staging table and are joined to a table of id to `nid`.
     */
    private class OracleLoader(engine: Engine) : Loader {
        @Suppress("UNCHECKED_CAST")
        private val dataSource = (engine.provider as NativeDriverSource<DataSource>).nativeDriver
        private val json = ObjectMapper()
        private val batch = 5_000

        private fun run(vararg statements: String) = dataSource.connection.use { c ->
            c.createStatement().use { s -> statements.forEach { s.execute(it) } }
            if (!c.autoCommit) c.commit()
        }

        override fun nodes(label: String, rows: Sequence<Map<String, Any>>): Long {
            var count = 0L
            val labels = json.writeValueAsString(listOf(label))
            dataSource.connection.use { c ->
                c.autoCommit = false
                c.prepareStatement("INSERT INTO ${GRAPH}_node (labels, props) VALUES (?, ?)").use { insert ->
                    rows.chunked(batch).forEach { chunk ->
                        for (row in chunk) {
                            insert.setString(1, labels)
                            insert.setString(2, json.writeValueAsString(row))
                            insert.addBatch()
                        }
                        insert.executeBatch()
                        c.commit()
                        count += chunk.size
                    }
                }
                c.autoCommit = true
            }
            return count
        }

        override fun beforeEdges() {
            run(
                "DROP TABLE IF EXISTS ${GRAPH}_load_map PURGE",
                "DROP TABLE IF EXISTS ${GRAPH}_load_stage PURGE",
                "CREATE TABLE ${GRAPH}_load_map (id VARCHAR2(64) PRIMARY KEY, nid NUMBER NOT NULL) ORGANIZATION INDEX",
                "CREATE TABLE ${GRAPH}_load_stage (s VARCHAR2(64) NOT NULL, d VARCHAR2(64) NOT NULL)",
                """
                INSERT INTO ${GRAPH}_load_map (id, nid)
                SELECT JSON_VALUE(n.props, '$.id'), n.nid FROM ${GRAPH}_node n
                WHERE JSON_EXISTS(n.labels, '$[*]?(@ in ("Org", "Person", "Repo", "Topic", "Issue", "Comment"))')
                """.trimIndent(),
                "BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, '${GRAPH.uppercase()}_LOAD_MAP'); END;",
            )
        }

        override fun edges(set: EdgeSet): Long {
            var count = 0L
            dataSource.connection.use { c ->
                c.autoCommit = false
                c.prepareStatement("INSERT INTO ${GRAPH}_load_stage (s, d) VALUES (?, ?)").use { insert ->
                    set.pairs.chunked(batch).forEach { chunk ->
                        for ((s, d) in chunk) {
                            insert.setString(1, s)
                            insert.setString(2, d)
                            insert.addBatch()
                        }
                        insert.executeBatch()
                        count += chunk.size
                    }
                }
                c.prepareStatement(
                    """
                    INSERT INTO ${GRAPH}_edge (src, dst, type)
                    SELECT s.nid, d.nid, ? FROM ${GRAPH}_load_stage st
                    JOIN ${GRAPH}_load_map s ON s.id = st.s JOIN ${GRAPH}_load_map d ON d.id = st.d
                    """.trimIndent(),
                ).use { move ->
                    move.setString(1, set.type)
                    val moved = move.executeUpdate()
                    check(moved.toLong() == count) { "${set.type}: $count pairs gave $moved relationships" }
                }
                c.commit()
                c.autoCommit = true
            }
            run("TRUNCATE TABLE ${GRAPH}_load_stage")
            return count
        }

        override fun finish() {
            run(
                "DROP TABLE ${GRAPH}_load_map PURGE",
                "DROP TABLE ${GRAPH}_load_stage PURGE",
                "BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, '${GRAPH.uppercase()}_NODE'); DBMS_STATS.GATHER_TABLE_STATS(USER, '${GRAPH.uppercase()}_EDGE'); END;",
            )
        }
    }

    /**
     * Rows into the tables Drivine keeps a graph in on Postgres, the same tables as on Oracle, by `COPY`,
     * Postgres's way in for many rows, which its JDBC driver offers an application. A relationship row
     * names its nodes by `nid`, so the pairs of ids go to a staging table and are joined to a table of
     * id to `nid`, as on Oracle.
     */
    private class PostgresLoader(engine: Engine) : Loader {
        @Suppress("UNCHECKED_CAST")
        private val dataSource = (engine.provider as NativeDriverSource<DataSource>).nativeDriver
        private val json = ObjectMapper()

        private fun run(vararg statements: String) = dataSource.connection.use { c ->
            c.createStatement().use { s -> statements.forEach { s.execute(it) } }
        }

        /** [text] as a field of CSV: in quotes, a quote in it doubled. */
        private fun csv(text: String) = "\"" + text.replace("\"", "\"\"") + "\""

        /** Copies [lines] of CSV into [table]'s [columns]: each a line of fields, with its own line end. */
        private fun copy(table: String, columns: String, lines: Sequence<String>): Long = dataSource.connection.use { c ->
            val copy = c.unwrap(PGConnection::class.java).copyAPI.copyIn("COPY $table ($columns) FROM STDIN WITH (FORMAT csv)")
            try {
                lines.forEach { line -> line.toByteArray().let { copy.writeToCopy(it, 0, it.size) } }
                copy.endCopy()
            } finally {
                if (copy.isActive) copy.cancelCopy()
            }
        }

        override fun nodes(label: String, rows: Sequence<Map<String, Any>>): Long {
            val labels = csv(json.writeValueAsString(listOf(label)))
            return copy("${GRAPH}_node", "labels, props", rows.map { "$labels,${csv(json.writeValueAsString(it))}\n" })
        }

        override fun beforeEdges() {
            run(
                "DROP TABLE IF EXISTS ${GRAPH}_load_map, ${GRAPH}_load_stage",
                "CREATE UNLOGGED TABLE ${GRAPH}_load_map AS SELECT props ->> 'id' AS id, nid FROM ${GRAPH}_node",
                "ALTER TABLE ${GRAPH}_load_map ADD PRIMARY KEY (id)",
                "CREATE UNLOGGED TABLE ${GRAPH}_load_stage (s text NOT NULL, d text NOT NULL)",
                "ANALYZE ${GRAPH}_load_map",
            )
        }

        override fun edges(set: EdgeSet): Long {
            val count = copy("${GRAPH}_load_stage", "s, d", set.pairs.asSequence().map { (s, d) -> "${csv(s)},${csv(d)}\n" })
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO ${GRAPH}_edge (src, dst, type)
                    SELECT s.nid, d.nid, ? FROM ${GRAPH}_load_stage st
                    JOIN ${GRAPH}_load_map s ON s.id = st.s JOIN ${GRAPH}_load_map d ON d.id = st.d
                    """.trimIndent(),
                ).use { move ->
                    move.setString(1, set.type)
                    val moved = move.executeUpdate()
                    check(moved.toLong() == count) { "${set.type}: $count pairs gave $moved relationships" }
                }
            }
            run("TRUNCATE ${GRAPH}_load_stage")
            return count
        }

        /**
         * The staging tables go, and the graph's are vacuumed and analysed, as after any bulk load: so the
         * planner knows the tables, and an index-only scan, a count by an index, need not visit the rows
         * the load wrote to see that they are there. Oracle's loader gathers its statistics likewise.
         */
        override fun finish() {
            run("DROP TABLE ${GRAPH}_load_map, ${GRAPH}_load_stage", "VACUUM ANALYZE ${GRAPH}_node", "VACUUM ANALYZE ${GRAPH}_edge")
        }
    }

    /**
     * Rows into the tables Drivine keeps a graph in on SQLite, the same tables again, with labels and
     * properties as JSON text: JDBC batches, a transaction to each, which is the quickest way into SQLite
     * from the JVM. A relationship row names its nodes by `nid`, so the pairs of ids go to a staging table
     * and are joined to a table of id to `nid`, as on Oracle and Postgres.
     */
    private class SqliteLoader(engine: Engine) : Loader {
        @Suppress("UNCHECKED_CAST")
        private val dataSource = (engine.provider as NativeDriverSource<DataSource>).nativeDriver
        private val json = ObjectMapper()
        private val batch = 20_000

        private fun run(vararg statements: String) = dataSource.connection.use { c ->
            c.createStatement().use { s -> statements.forEach { s.execute(it) } }
            if (!c.autoCommit) c.commit()
        }

        /** Rows of [insert], each bound by [bind], committed a batch at a time. */
        private fun <T> batches(insert: String, rows: Sequence<T>, bind: (java.sql.PreparedStatement, T) -> Unit): Long {
            var count = 0L
            dataSource.connection.use { c ->
                c.createStatement().use { it.execute(SQLITE_CACHE) }
                c.autoCommit = false
                c.prepareStatement(insert).use { statement ->
                    rows.chunked(batch).forEach { chunk ->
                        for (row in chunk) {
                            bind(statement, row)
                            statement.addBatch()
                        }
                        statement.executeBatch()
                        c.commit()
                        count += chunk.size
                    }
                }
                c.autoCommit = true
            }
            return count
        }

        override fun nodes(label: String, rows: Sequence<Map<String, Any>>): Long {
            val labels = json.writeValueAsString(listOf(label))
            return batches("INSERT INTO ${GRAPH}_node (labels, props) VALUES (?, ?)", rows) { s, row ->
                s.setString(1, labels)
                s.setString(2, json.writeValueAsString(row))
            }
        }

        override fun beforeEdges() {
            run(
                "DROP TABLE IF EXISTS ${GRAPH}_load_map",
                "DROP TABLE IF EXISTS ${GRAPH}_load_stage",
                "CREATE TABLE ${GRAPH}_load_map (id TEXT PRIMARY KEY, nid INTEGER NOT NULL) WITHOUT ROWID",
                "CREATE TABLE ${GRAPH}_load_stage (s TEXT NOT NULL, d TEXT NOT NULL)",
                "INSERT INTO ${GRAPH}_load_map (id, nid) SELECT props ->> '$.id', nid FROM ${GRAPH}_node",
            )
        }

        override fun edges(set: EdgeSet): Long {
            val count = batches("INSERT INTO ${GRAPH}_load_stage (s, d) VALUES (?, ?)", set.pairs) { s, (src, dst) ->
                s.setString(1, src)
                s.setString(2, dst)
            }
            dataSource.connection.use { c ->
                c.createStatement().use { it.execute(SQLITE_CACHE) }
                c.prepareStatement(
                    """
                    INSERT INTO ${GRAPH}_edge (src, dst, type)
                    SELECT s.nid, d.nid, ? FROM ${GRAPH}_load_stage st
                    JOIN ${GRAPH}_load_map s ON s.id = st.s JOIN ${GRAPH}_load_map d ON d.id = st.d
                    """.trimIndent(),
                ).use { move ->
                    move.setString(1, set.type)
                    val moved = move.executeUpdate()
                    check(moved.toLong() == count) { "${set.type}: $count pairs gave $moved relationships" }
                }
            }
            run("DELETE FROM ${GRAPH}_load_stage")
            return count
        }

        /** The staging tables go, and SQLite's planner is given statistics, as Postgres's and Oracle's are. */
        override fun finish() {
            run("DROP TABLE ${GRAPH}_load_map", "DROP TABLE ${GRAPH}_load_stage", "ANALYZE")
        }
    }
}
