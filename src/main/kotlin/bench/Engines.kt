package bench

import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification

/** The name of the graph on FalkorDB, and of the property graph and its tables on Oracle, and of the tables on Postgres. */
const val GRAPH = "bench"

/**
 * SQLite's page cache, 1 GB, as Postgres has in `shared_buffers`; the rest comes from the host's cache of
 * the file. It is a setting of a connection, so it is given to each connection that is to have it.
 */
const val SQLITE_CACHE = "PRAGMA cache_size = -1048576"

/** One database, and Drivine's managers over it. */
class Engine(val name: String, val type: DatabaseType, val provider: ConnectionProvider) : AutoCloseable {

    private val registry = SubtypeRegistry()
    val pm = NonTransactionalPersistenceManager(provider, name, type, registry)
    val gom = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, registry)

    /** Whether a statement written by hand is SQL here, and not Cypher. */
    val sql: Boolean get() = !type.speaksCypher

    /** Whether the SQL is Postgres's, which keeps a graph in Oracle's tables and reads it with joins, not SQL/PGQ. */
    val postgres: Boolean get() = type == DatabaseType.POSTGRES

    /** Whether this is SQLite, a file opened in this process, which keeps a graph in the same tables and is read with joins too. */
    val sqlite: Boolean get() = name == "sqlite"

    fun execute(statement: String, parameters: Map<String, Any?> = emptyMap()) =
        pm.execute(QuerySpecification.withStatement(statement).bind(parameters))

    fun query(statement: String, parameters: Map<String, Any?> = emptyMap()): List<Any> =
        pm.query(QuerySpecification.withStatement(statement).bind(parameters))

    override fun close() = provider.end()

    companion object {
        private fun env(name: String, default: String) = System.getenv(name) ?: default

        /**
         * SQLite is in Drivine's main branch and not yet in a release: until it is, the harness finds it by
         * name, so that it still builds against the release, and it is run with `DRIVINE_SOURCE`.
         */
        private fun sqlite(path: String): ConnectionProvider {
            val boxed = Int::class.javaObjectType
            val factory = JdbcConnectionProvider::class.java.getMethod(
                "sqlite", String::class.java, String::class.java, boxed, boxed, SubtypeRegistry::class.java, String::class.java,
            )
            // One connection, so the page cache it is given below is the one every statement reads through.
            return factory.invoke(null, "sqlite", path, 1, null, null, GRAPH) as ConnectionProvider
        }

        /** [name]'s database. SQLite's is a file of its own for each scale factor, [factor]. */
        fun open(name: String, factor: Double): Engine = when (name) {
            "neo4j" -> Engine(
                name, DatabaseType.NEO4J,
                Neo4jConnectionProvider(
                    name = name, type = DatabaseType.NEO4J,
                    host = env("BENCH_NEO4J_HOST", "localhost"), port = env("BENCH_NEO4J_PORT", "17687").toInt(),
                    user = "neo4j", password = env("BENCH_NEO4J_PASSWORD", "benchbench"), database = null, config = emptyMap(),
                ),
            )
            "falkordb" -> Engine(
                name, DatabaseType.FALKORDB,
                FalkorDbConnectionProvider(
                    name = name, host = env("BENCH_FALKORDB_HOST", "localhost"), port = env("BENCH_FALKORDB_PORT", "16379").toInt(),
                    password = null, graphName = GRAPH,
                ),
            )
            "oracle" -> Engine(
                name, DatabaseType.ORACLE,
                JdbcConnectionProvider.pooled(
                    name, DatabaseType.ORACLE,
                    JdbcConnectionProvider.oracleUrl(env("BENCH_ORACLE_HOST", "localhost"), env("BENCH_ORACLE_PORT", "11521").toInt(), "FREEPDB1"),
                    "bench", env("BENCH_ORACLE_PASSWORD", "bench"),
                    graphName = GRAPH,
                ),
            )
            "postgres" -> Engine(
                name, DatabaseType.POSTGRES,
                JdbcConnectionProvider.pooled(
                    name, DatabaseType.POSTGRES,
                    JdbcConnectionProvider.postgresUrl(env("BENCH_POSTGRES_HOST", "localhost"), env("BENCH_POSTGRES_PORT", "15432").toInt(), "bench"),
                    "bench", env("BENCH_POSTGRES_PASSWORD", "bench"),
                    graphName = GRAPH,
                ),
            )
            "sqlite" -> {
                val path = env("BENCH_SQLITE_PATH", "build/sqlite/bench-sf$factor.db")
                java.io.File(path).absoluteFile.parentFile.mkdirs()
                Engine(name, DatabaseType.valueOf("SQLITE"), sqlite(path)).also { it.execute(SQLITE_CACHE) }
            }
            else -> error("No engine '$name': neo4j, falkordb, oracle, postgres or sqlite")
        }
    }
}
