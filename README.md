# Graph Bench

**Using Drivine to compare the performance of Neo4j, FalkorDB, Oracle and Postgres.**

*15 October 2026. Postgres added 22 October 2026.*

[Drivine4j](https://github.com/liberation-data/drivine4j) is a graph mapping library for the JVM. You
describe your domain as fragments (a node) and views (a node with what is related to it), and
Drivine writes the statement that loads or saves one. Until now those statements were Cypher, for
Neo4j, FalkorDB, Memgraph and Neptune.

Drivine 0.1.0 adds Oracle. Oracle Database 23ai has a property graph, but it is not an openCypher
database: a graph is read with SQL/PGQ (`GRAPH_TABLE`) and cannot be written through at all. Drivine
keeps a graph there as two tables, reads it with SQL/PGQ and writes it as rows, behind the same
model, the same query DSL and the same object manager as on the graph engines.

Drivine 0.2.0 adds Postgres, keeping a graph in the same two tables, with labels and properties as
`jsonb`. Postgres has no graph query language at all: Drivine reads the tables with joins, and writes
them through a function it keeps in the database. Plain Postgres, with no extension but pgvector for
vector search.

That is what makes this comparison reasonable. Benchmarks across a graph database and a relational
one usually compare two different applications, each written for its store. Here there is one. A
`@GraphView` says what is wanted, a node and its neighbours, and not how to fetch it, so the same
line of Kotlin runs on all four engines and each is asked the same question in its own language.
The harness checks that they give the same answer.

This repository is that harness, and this page is what it found on a graph of 1.1 million nodes and
4.5 million relationships.

### What Drivine costs

Little. A load or a save is one statement and one round trip, however many relationships it has:
an issue with its repo, author, assignees, topics and comments loads in 1.2 to 3.5 ms on every
engine.

## The short version

- **No engine wins everywhere.** FalkorDB is fastest on most workloads, often by 3 to 4 times.
  Neo4j is never far off and has no bad result. Oracle keeps up on reads, but is 4 to 5 times
  slower than Neo4j on batch writes.
- **Postgres is the surprise.** Two plain tables and joins load the view of five relationships
  faster than any other engine, sustain more than twice the view loads a second of any other under
  eight threads, and group all 1.5 million FOLLOWS in less than half the graph engines' time. It
  writes 2 to 3 times faster than Oracle and beats Neo4j on single saves; bulk writes still trail
  Neo4j.
- **Neither SQL store has index-free adjacency, and it did not matter.** Walking relationships
  through an index cost Oracle and Postgres less for each path than Neo4j, out to 275,000 paths.
  What costs them is reading the nodes on the way. [The numbers are below.](#does-oracle-miss-index-free-adjacency)
- **A benchmark is a good bug hunt.** FalkorDB 6 is a new engine, rewritten in Rust, and we met
  three rough edges in it, all reported and all with a small workaround. Oracle Free 23.26 has a
  sharper one, where a graph query can return a wrong answer or end the server process. On
  Postgres it was Drivine's own writes that the benchmark found slow, fixed before the run shown.
- **Every engine gave the same answers.** Each read workload's results are digested and compared
  across the four. They agree on all of them.

## What was run

**The graph.** A code-collaboration model: six labels, ten relationship types, skewed degrees so
that a few nodes are very popular and most are not.

```
Person -[:MEMBER_OF]-> Org          Person -[:FOLLOWS]-> Person
Repo -[:OWNED_BY]-> Org             Repo -[:DEPENDS_ON]-> Repo
Issue -[:IN_REPO]-> Repo            Issue -[:OPENED_BY]-> Person
Issue -[:ASSIGNED_TO]-> Person      Issue -[:TAGGED]-> Topic
Comment -[:ON]-> Issue              Comment -[:WRITTEN_BY]-> Person
```

| | count |
|---|---:|
| Org | 1,000 |
| Person | 100,000 |
| Repo | 20,000 |
| Topic | 500 |
| Issue | 250,000 |
| Comment | 750,000 |
| **nodes** | **1,121,500** |
| **relationships** | **4,482,439** |

The data is generated from fixed seeds, so every engine holds exactly the same graph.

**The engines.** Neo4j 2026.07.1 (Community), FalkorDB 6.0.0 and Oracle Database Free 23.26,
through Drivine4j 0.1.6. Oracle ran on Drivine's main branch at
[977303f](https://github.com/liberation-data/drivine4j/commit/977303f), which adds a change to how
it indexes properties that is not released yet. Postgres 17 with pgvector (`pgvector/pgvector:pg17`)
was run a week later, through Drivine4j 0.2.0, which adds it and is not released yet
([liberation-data/drivine4j#109](https://github.com/liberation-data/drivine4j/pull/109)); the other
three were not run again.

**The limits.** Each engine ran alone, in a fresh container given 2 CPUs and 4 GB. Oracle Free
allows itself 2 CPU threads and 2 GB whatever it is given, so the others were held to the same
processors. Neo4j had a 1 GB heap and a 2 GB page cache; Postgres a 1 GB buffer cache
(`shared_buffers`), with the rest left to the operating system's cache, as Postgres expects. The host
is an Apple M2 Max.

**Two kinds of workload.**

1. *Through the object manager.* The same Kotlin, unchanged, on all four: `load`, `loadAll`,
   `count`, `save`, `update`, `saveAll` over fragments and views. Drivine writes the Cypher or the
   SQL. This measures the engine and the statement Drivine gives it together, which is what an
   application sees.

   ```kotlin
   @GraphView
   data class IssueDetail(
       @Root val issue: Issue,
       @GraphRelationship(type = "IN_REPO", direction = Direction.OUTGOING) val repo: Repo,
       @GraphRelationship(type = "OPENED_BY", direction = Direction.OUTGOING) val author: Person,
       @GraphRelationship(type = "ASSIGNED_TO", direction = Direction.OUTGOING) val assignees: List<Person>,
       @GraphRelationship(type = "TAGGED", direction = Direction.OUTGOING) val topics: List<Topic>,
       @GraphRelationship(type = "ON", direction = Direction.INCOMING) val comments: List<Comment>,
   )

   graphObjectManager.load(id, IssueDetail::class.java)   // the same line on all four engines
   ```

2. *Traversals written by hand*, in Cypher, in Oracle's SQL/PGQ and in Postgres's SQL. For example,
   who the people I follow follow:

   ```cypher
   MATCH (p:Person {id: $id})-[:FOLLOWS]->()-[:FOLLOWS]->(x) RETURN count(DISTINCT x)
   ```

   ```sql
   SELECT COUNT(DISTINCT nid) FROM GRAPH_TABLE (bench
       MATCH (p IS node)
             -[e1 IS edge WHERE e1.type = 'FOLLOWS']-> (a IS node) -[e2 IS edge WHERE e2.type = 'FOLLOWS']-> (x IS node)
       COLUMNS (e1.src AS start_, x.nid AS nid))
   WHERE start_ = (SELECT nid FROM bench_node WHERE JSON_VALUE(props, '$.id') = $id)
   ```

   ```sql
   SELECT COUNT(DISTINCT e2.dst) FROM bench_edge e1
   JOIN bench_edge e2 ON e2.src = e1.dst AND e2.type = 'FOLLOWS'
   WHERE e1.type = 'FOLLOWS'
     AND e1.src = (SELECT nid FROM bench_node WHERE (props ->> 'id') = $id AND labels @> '["Person"]')
   ```

Each workload is warmed up, then called up to 300 times with arguments picked from a seeded list,
so call number 17 asks every engine for the same node. Times are wall clock at the client.

## Results

**Every time on this page is in milliseconds (ms) unless its table says otherwise.** The exceptions
are throughput, in calls a second, and the bulk load, in seconds. Each column heading carries its
unit.

A cell is the median, with the 95th percentile in brackets. Lower is better.

### Reads through the object manager

| workload | Neo4j (ms) | FalkorDB (ms) | Oracle (ms) | Postgres (ms) |
|---|---:|---:|---:|---:|
| load a fragment by id | 1.83 (3.01) | **0.40** (0.66) | 0.60 (0.89) | 0.64 (1.03) |
| load a view: issue + repo, author, assignees, topics, comments | 2.22 (3.15) | 3.48 (3.80) | 1.52 (2.31) | **1.16** (2.16) |
| load a nested view: issue, comments, each comment's author | 1.65 (2.30) | 2.55 (3.04) | **0.81** (1.41) | 1.02 (3.41) |
| load a view with a `@Count`: repo + owner, dependencies, issue count | 1.41 (1.86) | **0.75** (0.86) | 0.78 (1.21) | 0.76 (1.17) |
| load a `@GraphPath` view: orgs of the people a person follows | 1.67 (2.58) | **0.43** (0.69) | 1.18 (4.47) | 0.86 (1.97) |
| `loadAll` by an indexed property: a repo's open issues | 1.33 (1.87) | **0.34** (0.45) | 0.40 (0.71) | 0.57 (0.89) |
| `count` by unindexed properties: scans 250,000 issues | 66.97 (81.47) | **25.38** (28.23) | 65.22 (69.82) | 72.86 (78.80) |
| `count` every comment | 1.09 (1.37) | **0.23** (0.27) | 18.73 (21.60) | 31.55 (54.55) |

### Traversals written by hand

| workload | Neo4j (ms) | FalkorDB (ms) | Oracle (ms) | Postgres (ms) |
|---|---:|---:|---:|---:|
| 1 hop: a person's followers, counted | 0.88 (1.12) | 0.27 (0.34) | **0.21** (0.27) | 0.23 (0.41) |
| 2 hops: who the people I follow follow, distinct | 0.98 (1.34) | **0.29** (0.41) | 0.73 (5.13) | 0.31 (0.85) |
| 3 hops: the same, one further | 2.14 (5.98) | **0.57** (1.88) | 3.15 (15.25) | 1.57 (6.45) |
| 1 to 4 hops: a repo's transitive dependencies | 1.00 (1.47) | **0.28** (0.36) | 0.57 (1.17) | 0.42 (0.82) |
| 3 hops and a group: an org's top topics among open issues | 1.55 (2.69) | **1.14** (1.52) | 2.13 (5.82) | 1.89 (7.17) |
| 3 hops and a group: a repo's top commenters | 1.05 (1.43) | 2.70 (2.96) | 2.13 (5.23) | **0.80** (1.40) |
| 3 hops and a group: repos the people I follow raise issues in | 1.22 (3.55) | 0.96 (1.89) | 1.11 (5.47) | **0.88** (4.23) |

### The whole graph

| workload | Neo4j (ms) | FalkorDB (ms) | Oracle (ms) | Postgres (ms) |
|---|---:|---:|---:|---:|
| every issue, grouped by state | 64.15 (76.67) | **44.79** (47.64) | 63.14 (65.62) | 88.58 (96.85) |
| every FOLLOWS (1.5 million), grouped: the ten most followed | 785.55 (990.19) | 732.97 (788.97) | 1169.60 (1242.30) | **315.71** (323.77) |

Over the whole graph, index-free adjacency is starting to pay for Oracle: grouping 1.5 million
FOLLOWS takes it half as long again as the graph engines. Postgres groups them in under half the
graph engines' time: it reads the relationships from the index over the nodes they reach, in that
order and without touching their rows, merges them with the nodes in the order of their keys, and
counts. A walk of the whole graph in order is cheap. It takes a lot of data before following a
pointer, O(1), outshines a probe of a B-tree only a few levels deep, O(log n).
[Below](#does-oracle-miss-index-free-adjacency), out to 275,000 paths, it has not yet.

### Writes through the object manager

| workload | Neo4j (ms) | FalkorDB (ms) | Oracle (ms) | Postgres (ms) |
|---|---:|---:|---:|---:|
| save a new fragment | 1.23 (1.86) | **0.44** (0.62) | 1.94 (2.63) | 0.75 (1.18) |
| update a fragment: load, change, save | 1.83 (2.60) | **0.80** (1.05) | 2.65 (3.43) | 1.41 (2.66) |
| save a new view: an issue and 10 new comments | 4.66 (7.90) | **1.27** (2.29) | 5.44 (6.39) | 2.04 (2.86) |
| save a new view: an issue and 100 new comments | 8.47 (20.32) | **3.85** (32.44) | 36.44 (43.96) | 11.87 (14.04) |
| `saveAll`: 1,000 new fragments | 43.15 (47.66) | **28.85** (34.94) | 214.68 (244.89) | 83.96 (107.66) |
| `saveAll`: 100 new views of 5 comments | **68.53** (73.64) | 108.15 (120.97) | 270.51 (344.06) | 111.74 (127.56) |

FalkorDB's writes are measured **without unique constraints**, which the other three have. See
[below](#falkordb-6-a-unique-constraint-makes-inserts-slower-as-the-label-grows) for why.

### Eight threads at once

Calls a second over 15 seconds, with the median latency of a call, in milliseconds, in brackets.
Higher is better.

| workload | Neo4j (calls/s) | FalkorDB (calls/s) | Oracle (calls/s) | Postgres (calls/s) |
|---|---:|---:|---:|---:|
| load a view: issue + repo, author, assignees, topics, comments | 2,850 (1.4) | 731 (10.7) | 3,531 (0.9) | **8,148** (0.9) |
| 2 hops: who the people I follow follow, distinct | 3,605 (1.2) | **14,536** (0.5) | 4,711 (0.6) | 11,792 (0.4) |

### Loading the graph

In **seconds**. Each engine was loaded by the quickest path an application could use: batches of rows in
Cypher for Neo4j and FalkorDB, JDBC batches into the graph's tables for Oracle, and `COPY` into the
same tables for Postgres. The paths differ, so this says what a bulk load costs on each and is not a
like for like comparison.

| | Neo4j | FalkorDB | Oracle | Postgres |
|---|---:|---:|---:|---:|
| 1,121,500 nodes | 18.5 s | 9.0 s | 48.0 s | **7.6 s** |
| 4,482,439 relationships | 81.8 s | **48.4 s** | 76.2 s | 63.2 s |
| container memory after the run | 1.9 GB | **1.1 GB** | 2.1 GB | 1.2 GB |

## Reading the results

**FalkorDB** is the quickest on point reads and on traversals: about 0.3 ms for one to four hops
where Neo4j takes about 1 ms. It is also the quickest writer, though without the constraints the
others carry. Its one slow result is a view load under eight threads, 731 calls a second against
2,850 to 8,148 for the others: the load costs FalkorDB about 2.8 ms of CPU, and it has two CPUs.

**Neo4j** is rarely the fastest and never far off. It is the only engine with no result needing an
explanation. Everything by id or by a short traversal lands between 1 and 2 ms.

**Oracle** is the surprise. Its graph is two tables, with labels and properties as JSON, read
through a SQL property graph, and it still loads views faster than either graph engine (1.52 ms
for five relationships, 0.81 ms for a nested one), and sustains more view loads a second than
either under eight threads. Traversals written by hand keep pace up to two hops and fall behind at three (3.2 ms
against 2.1 and 0.6), though
[that is not the price of the walk itself](#does-oracle-miss-index-free-adjacency). Writes are
where the relational storage shows. Saving one thing is close to Neo4j, but anything in bulk is
4 to 5 times slower: `saveAll` of 1,000 fragments takes 215 ms.

**Postgres** keeps the graph in Oracle's tables and reads them with nothing but joins, and does best
of the four on what an application mostly asks: views. It loads one with five relationships in
1.16 ms, the quickest here, and under eight threads sustains 8,148 view loads a second, more than
twice Oracle's 3,531 and nearly three times Neo4j's. Short traversals are close to FalkorDB's (0.23
and 0.31 ms for one and two hops), and the grouped three-hop traversals are the quickest of all on
two of the three. It writes a single object faster than Neo4j and 2 to 3 times faster than Oracle;
in bulk it trails Neo4j by 1.4 to 2 times, because Drivine's write function finds and writes each
node in turn. Its slow result is counting a whole label: Postgres keeps no count of a label's nodes, so a
count of 750,000 comments reads them, 32 ms against 1 ms for Neo4j, which keeps one.

Scans of a whole label come out about the same on all four, between 25 and 89 ms for 250,000
nodes. Grouping all 1.5 million FOLLOWS takes the graph engines about 0.75 seconds, Oracle 1.2, and
Postgres 0.32.

## Does Oracle miss index-free adjacency?

A native graph database keeps each node's relationships with the node, so going to a neighbour is
following a pointer. This is index-free adjacency, and it is the usual argument for a graph engine
over a relational one: a hop costs the same however big the graph is. Oracle and Postgres have none
of it. A relationship is a row in a table, and a node's relationships are found in a B-tree index
over that table, by the node they leave. FalkorDB takes a third road and keeps relationships as sparse
matrices, so a hop is a matrix multiplication.

So we measured the walk and nothing else: from one person, follow FOLLOWS for one, two, three and
four hops and count the paths. 150 people, each number of hops, the quickest of three calls, after
a full pass to warm everything. The people's follow counts vary widely, so the calls spread from 1
path to 275,590. Oracle, FalkorDB and Postgres count the same paths for every call. Neo4j counts one
fewer on 4 of the 150 four-hop calls: Cypher never walks a relationship twice in a path, and a cycle
of three people lets a four-hop walk come back over its first relationship. Counted on Postgres with
every relationship in the path distinct, those calls give Neo4j's numbers.

Median time of a call in **milliseconds**, by how many paths the call walked:

| paths walked | calls | Neo4j (ms) | FalkorDB (ms) | Oracle (ms) | Postgres (ms) |
|---|---:|---:|---:|---:|---:|
| under 100 | 264 | 0.73 | 0.23 | 0.18 | **0.15** |
| 100 to 1,000 | 122 | 0.77 | 0.26 | 0.25 | **0.21** |
| 1,000 to 10,000 | 102 | 1.33 | **0.35** | 0.65 | 0.61 |
| 10,000 to 100,000 | 77 | 5.12 | **0.90** | 3.05 | 3.42 |
| over 100,000 | 35 | 40.93 | **4.46** | 23.56 | 24.51 |

A straight line through each engine's calls gives a fixed cost for a call and a cost for each path:

| | fixed cost of a call | each path walked |
|---|---:|---:|
| Neo4j | 0.56 ms | 0.24 microseconds |
| FalkorDB | 0.25 ms | 0.025 microseconds |
| Oracle | 0.16 ms | 0.14 microseconds |
| Postgres | 0.13 ms | 0.14 microseconds |

**The missing adjacency never bites here.** Oracle's index walk costs a little over half of what
Neo4j's pointers cost for each path, at every size we reached. Postgres, walking the same kind of
index over the same table, costs the same for each path to two decimal places, and has the lowest
fixed cost of the four. FalkorDB's matrices are in a class of their own, five times cheaper again.

What does bite is **reading the nodes**. An index over `(src, type)` holds everything a walk needs,
so Oracle answers it without touching a node's row, and the last hop without touching a
relationship's row either. The moment a query wants something of the nodes it passes, each one is a
primary key probe and a JSON document to open. That is the difference between two numbers on this
page for three hops of FOLLOWS: 0.38 ms here, and 3.15 ms in the traversal table above, where the
query counts the distinct people at the end and not the paths. Postgres shows the same, 1.57 ms in
the traversal table. It is also why Oracle's batch writes and its grouped traversals trail: they are
about nodes, not about relationships.

Three cautions. This is counting, the kindest case for an index. The graph fits in memory, and a
B-tree over 4.5 million relationships is only a few levels deep; over billions, or colder, each
probe costs more, and that is exactly where index-free adjacency is meant to earn its keep. And this
is Neo4j Community, which does not have the faster runtime of the Enterprise edition.

## The bugs

### FalkorDB 6: a unique constraint makes inserts slower as the label grows

FalkorDB 6 is the engine rewritten in Rust, and the fastest engine on this page by a distance. Its
first release has three rough edges, all reported with a repro, none giving a wrong answer. One
shaped this benchmark.

With a unique constraint on a label, each insert costs time in proportion to the nodes already
there. Times in milliseconds:

| | 4.20.7 | 6.0.0 |
|---|---:|---:|
| nodes 1 to 5,000 | 118 ms | 4,300 ms |
| nodes 5,001 to 10,000 | 155 ms | 8,878 ms |
| nodes 10,001 to 20,000 | 307 ms | 36,280 ms |
| 20,000 nodes with no constraint | 11 ms | 26 ms |

A load of 750,000 comments is out of reach. So in this benchmark FalkorDB has a plain range index
over each label's id where Neo4j and Oracle have a unique constraint, and its write numbers do not
include the cost of checking one. With the constraints in place, at one hundredth of the scale,
`saveAll` of 1,000 fragments took 3.9 seconds. Reported as
[FalkorDB#3330](https://github.com/FalkorDB/FalkorDB/issues/3330). We will run it again with the constraints on when it is fixed.

### Oracle Free 23.26: a graph query that answers for the wrong nodes

This one matters more than a slow plan. Take a pattern that starts from one node, found by a
property with an index over it, and reads nothing else of that node:

```sql
SELECT COUNT(*) FROM GRAPH_TABLE (bench
    MATCH (p IS node WHERE JSON_VALUE(p.props, '$.id') = 'p926') -[e IS edge WHERE e.type = 'FOLLOWS']-> (x IS node)
    COLUMNS (x.nid AS nid))
```

Oracle's join elimination removes `p` from the plan, and the test of its id goes with it. The
statement then counts every FOLLOWS in the graph: on a small copy of this one, 15,194 where the answer is 2. With the id bound as
a parameter and not written into the statement, the same query ends the server process: `ORA-07445`
in `kkocfbMarkBindFroCB`, and `ORA-03113` to the client.

It is not the property graph's doing. The same happens in plain SQL, with two tables and a foreign
key, whenever the filter is `JSON_VALUE` of a JSON column that has a function-based index over it:

```sql
SELECT COUNT(*) FROM je_edge e JOIN je_node n ON n.nid = e.src
WHERE JSON_VALUE(n.props, '$.id') = 'p7'      -- 3 without the index, 3,000 with it
```

[`bugs/oracle-join-elimination.sql`](bugs/oracle-join-elimination.sql) is a script of thirty lines
that shows it from an empty schema.

The SQL in this benchmark avoids it by finding the start node first, with a subquery, as in the
[example above](#what-was-run), so there is no test of the node's properties for Oracle to drop.

## What to make of it

Take the shape of these results and not the last decimal place.

- **Postgres was run a week after the others**, on Drivine 0.2.0, which is not released yet. The
  host was the same, and was quieter for its run.
- **One run of each, on a busy laptop.** Other work was running on the host (load average 6 to 9
  on 12 cores). The same statement can differ by up to 2 times from one run to the next.
  Differences of 3 times and more are the ones to believe.
- **Oracle Free is not Oracle.** It is capped at 2 CPU threads and 2 GB of memory. The same
  statements on a licensed edition with real memory would behave differently, above all under
  concurrency.
- **Small machines, a warm cache.** At this size the graph fits in memory on all four. Nothing
  here says what happens when it does not.
- **The SQL is ours and untuned.** The SQL/PGQ is what a competent first attempt looks like, shaped
  to get around a bug, and Postgres's is plain joins. Someone who tunes Oracle or Postgres for a
  living would do better.
- **The object-manager numbers measure Drivine too.** How a statement is written decides how the
  engine plans it, and a different plan can move a number by orders of magnitude.
- **FalkorDB runs without unique constraints**, for the reason given above.

## Run it yourself

You need Docker and a JDK (17 or later).

```bash
./run.sh 1                  # all four engines, scale factor 1
./run.sh 0.05               # a twentieth of the graph, in a couple of minutes
./run.sh 1 neo4j oracle     # only some engines
```

`run.sh` starts a fresh container for each engine in turn (`gbench-neo4j`, `gbench-falkordb`,
`gbench-oracle`, `gbench-postgres`, on ports 17687, 16379, 11521 and 15432), loads the graph, runs the workloads, stops the
container and prints the tables. Results are written to `results/<engine>-sf<scale>.json`; the ones
behind this page are in [`results/`](results). To print the tables again:

```bash
build/install/graph-bench/bin/graph-bench report --sf 1
```

`build.gradle.kts` names the released Drivine the harness builds against. To build against a
checkout of Drivine's source instead, for a fix that is not released yet:

```bash
DRIVINE_SOURCE=../drivine4j ./run.sh 1
```

To run against a database of your own, set `BENCH_NEO4J_HOST`, `BENCH_NEO4J_PORT`,
`BENCH_NEO4J_PASSWORD`, and the like for `FALKORDB`, `ORACLE` and `POSTGRES`, and call the harness directly:

```bash
build/install/graph-bench/bin/graph-bench run --engine neo4j --sf 1 --phases load,read,traverse,concurrent,write
```

The hop by hop measurement is a phase of its own, `--phases hops`, and writes each call to
`results/hops-<engine>-sf<scale>.csv`.

### Where things are

| | |
|---|---|
| [`Model.kt`](src/main/kotlin/bench/Model.kt) | the fragments and views |
| [`Data.kt`](src/main/kotlin/bench/Data.kt) | the generator, and why FOLLOWS never runs both ways |
| [`Load.kt`](src/main/kotlin/bench/Load.kt) | the indexes, and a loader for Cypher, one for Oracle's tables and one for Postgres's |
| [`Workloads.kt`](src/main/kotlin/bench/Workloads.kt) | every workload, with the Cypher, SQL/PGQ and Postgres SQL side by side |
| [`Main.kt`](src/main/kotlin/bench/Main.kt) | timing, the digest that checks the engines agree, the report |

Two things about the data are deliberate. FOLLOWS never runs both ways between two people, and
DEPENDS_ON only points at a repo made earlier. Cypher refuses a walk that uses a relationship twice
and SQL/PGQ and Postgres's joins allow it, so without those two rules the engines would be counting
different things. They rule out two-person cycles and not longer ones, which is why Neo4j counts a
few four-hop paths fewer: see [above](#does-oracle-miss-index-free-adjacency).

## License

Apache 2.0.
