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

/** The name of the graph on FalkorDB, and of the property graph and its tables on Oracle. */
const val GRAPH = "bench"

/** One database, and Drivine's managers over it. */
class Engine(val name: String, val type: DatabaseType, val provider: ConnectionProvider) : AutoCloseable {

    private val registry = SubtypeRegistry()
    val pm = NonTransactionalPersistenceManager(provider, name, type, registry)
    val gom = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, registry)

    /** Whether a statement written by hand is SQL here, and not Cypher. */
    val sql: Boolean get() = type == DatabaseType.ORACLE

    fun execute(statement: String, parameters: Map<String, Any?> = emptyMap()) =
        pm.execute(QuerySpecification.withStatement(statement).bind(parameters))

    fun query(statement: String, parameters: Map<String, Any?> = emptyMap()): List<Any> =
        pm.query(QuerySpecification.withStatement(statement).bind(parameters))

    override fun close() = provider.end()

    companion object {
        private fun env(name: String, default: String) = System.getenv(name) ?: default

        fun open(name: String): Engine = when (name) {
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
            else -> error("No engine '$name': neo4j, falkordb or oracle")
        }
    }
}
