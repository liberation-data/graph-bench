# Graph Bench

**Using Drivine to compare the performance of Neo4j, FalkorDB and Oracle.**

*14 October 2026*

[Drivine4j](https://github.com/liberation-data/drivine4j) is a graph mapping library for the JVM. You
describe your domain as fragments (a node) and views (a node with what is related to it), and
Drivine writes the statement that loads or saves one. Until now those statements were Cypher, for
Neo4j, FalkorDB, Memgraph and Neptune.

Drivine 0.1.0 adds Oracle. Oracle Database 23ai has a property graph, but it is not an openCypher
database: a graph is read with SQL/PGQ (`GRAPH_TABLE`) and cannot be written through at all. Drivine
keeps a graph there as two tables, reads it with SQL/PGQ and writes it as rows, behind the same
model, the same query DSL and the same object manager as on the graph engines.

That is what makes this comparison reasonable. Benchmarks across a graph database and a relational
one usually compare two different applications, each written for its store. Here there is one. A
`@GraphView` says what is wanted, a node and its neighbours, and not how to fetch it, so the same
line of Kotlin runs on all three engines and each is asked the same question in its own language.
The harness checks that they give the same answer.

This repository is that harness, and this page is what it found on a graph of 1.1 million nodes and
4.5 million relationships.

### What Drivine costs

Little. A load is one statement and one round trip, however many relationships the view has, and so
is a save. In the results below, loading an issue with its repo, its author, its assignees, its
topics and its comments takes 1.4 to 3.3 ms on every engine. That is about what a traversal of
three hops written by hand takes on the same engine, and it comes back as a typed object. Saving an
issue with ten new comments, eleven nodes and ten relationships, is a single statement of 1.2 to
11.6 ms.

Where Drivine did cost something, this page says so: two of the four slow results below are
statements Drivine could write better. Both are filed
([#64](https://github.com/liberation-data/drivine4j/issues/64), [#65](https://github.com/liberation-data/drivine4j/issues/65)), and we will run this again when they are fixed.

## The short version

- **No engine wins everywhere.** FalkorDB is fastest on most workloads, often by 3 to 4 times.
  Neo4j is the only one with no bad outlier. Oracle keeps up on reads, and leads on loading a view
  with several relationships, but is 2.5 to 5 times slower than Neo4j on writes.
- **The outliers are plans, not engines.** Four results are off by one to three orders of
  magnitude. Each has a specific cause in how a statement was planned, and each is explained below.
- **Oracle has no index-free adjacency, and it did not matter.** Walking relationships through an
  index cost Oracle less for each path than Neo4j, out to 275,000 paths. What costs Oracle is
  reading the nodes on the way. [The numbers are below.](#does-oracle-miss-index-free-adjacency)
- **A benchmark is a good bug hunt.** FalkorDB 6 is a new engine, rewritten in Rust, and we met
  three rough edges in it, all reported and all with a small workaround. Oracle Free 23.26 has a
  sharper one, where a graph query can return a wrong answer or end the server process.
- **Every engine gave the same answers.** Each read workload's results are digested and compared
  across the three. They agree on all of them.

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

**The engines.** Neo4j 2026.07.1 (Community), FalkorDB 6.0.0 and Oracle Database Free 23.26, each
through Drivine4j 0.1.5.

**The limits.** Each engine ran alone, in a fresh container given 2 CPUs and 4 GB. Oracle Free
allows itself 2 CPU threads and 2 GB whatever it is given, so the others were held to the same
processors. Neo4j had a 1 GB heap and a 2 GB page cache. The host is an Apple M2 Max.

**Two kinds of workload.**

1. *Through the object manager.* The same Kotlin, unchanged, on all three: `load`, `loadAll`,
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

   graphObjectManager.load(id, IssueDetail::class.java)   // the same line on all three engines
   ```

2. *Traversals written by hand*, once in Cypher and once in SQL/PGQ. For example, who the people I
   follow follow:

   ```cypher
   MATCH (p:Person {id: $id})-[:FOLLOWS]->()-[:FOLLOWS]->(x) RETURN count(DISTINCT x)
   ```

   ```sql
   SELECT COUNT(DISTINCT nid) FROM GRAPH_TABLE (bench
       MATCH (p IS node WHERE JSON_VALUE(p.props, '$.id') = $id)
             -[e1 IS edge WHERE e1.type = 'FOLLOWS']-> (a IS node) -[e2 IS edge WHERE e2.type = 'FOLLOWS']-> (x IS node)
       COLUMNS (x.nid AS nid))
   ```

Each workload is warmed up, then called up to 300 times with arguments picked from a seeded list,
so call number 17 asks every engine for the same node. Times are wall clock at the client.

## Results

**Every time on this page is in milliseconds (ms) unless its table says otherwise.** The exceptions
are throughput, in calls a second, and the bulk load, in seconds. Each column heading carries its
unit.

A cell is the median, with the 95th percentile in brackets. Lower is better.

### Reads through the object manager

| workload | Neo4j (ms) | FalkorDB (ms) | Oracle (ms) |
|---|---:|---:|---:|
| load a fragment by id | 1.62 (2.05) | **0.39** (0.62) | 0.66 (1.12) |
| load a view: issue + repo, author, assignees, topics, comments | 2.16 (2.99) | 3.29 (3.67) | **1.41** (2.35) |
| load a nested view: issue, comments, each comment's author | 1.90 (4.65) | 2.50 (2.75) | **1.05** (1.94) |
| load a view with a `@Count`: repo + owner, dependencies, issue count | 1.39 (1.96) | **0.76** (0.94) | 0.94 (1.77) |
| load a `@GraphPath` view: orgs of the people a person follows | **1.85** (3.65) | 53.36 (187.77) | 1503.10 (1713.16) |
| `loadAll` by an indexed property: a repo's open issues | **1.34** (2.58) | 23.81 (25.42) | 71.76 (81.85) |
| `count` by unindexed properties: scans 250,000 issues | 64.96 (77.68) | **25.52** (27.66) | 68.47 (90.97) |
| `count` every comment | 1.16 (1.60) | **0.27** (0.31) | 20.13 (21.21) |

### Traversals written by hand

| workload | Neo4j (ms) | FalkorDB (ms) | Oracle (ms) |
|---|---:|---:|---:|
| 1 hop: a person's followers, counted | 0.99 (1.29) | **0.28** (0.43) | 0.55 (1.09) |
| 2 hops: who the people I follow follow, distinct | 0.98 (1.29) | **0.28** (0.40) | 1.23 (5.97) |
| 3 hops: the same, one further | 1.92 (5.89) | **0.58** (1.95) | 8.57 (37.99) |
| 1 to 4 hops: a repo's transitive dependencies | 1.12 (1.60) | **0.29** (0.40) | 1.10 (2.99) |
| 3 hops and a group: an org's top topics among open issues | 1.69 (3.10) | **1.23** (1.93) | 3.37 (10.68) |
| 3 hops and a group: a repo's top commenters | **1.46** (3.19) | 2.75 (3.06) | 2.06 (4.40) |
| 3 hops and a group: repos the people I follow raise issues in | 1.23 (3.88) | **0.98** (1.90) | 1.44 (8.79) |

### The whole graph

| workload | Neo4j (ms) | FalkorDB (ms) | Oracle (ms) |
|---|---:|---:|---:|
| every issue, grouped by state | 58.86 (73.48) | **47.14** (47.71) | 69.65 (72.00) |
| every FOLLOWS (1.5 million), grouped: the ten most followed | **765.99** (878.30) | 828.54 (845.92) | 835.50 (952.80) |

### Writes through the object manager

| workload | Neo4j (ms) | FalkorDB (ms) | Oracle (ms) |
|---|---:|---:|---:|
| save a new fragment | 1.49 (2.74) | **0.42** (0.55) | 3.68 (8.61) |
| update a fragment: load, change, save | 2.17 (3.15) | **0.74** (1.09) | 4.84 (13.30) |
| save a new view: an issue and 10 new comments | 3.66 (5.36) | **1.22** (1.93) | 11.55 (17.18) |
| save a new view: an issue and 100 new comments | 16.23 (33.89) | **3.81** (10.16) | 48.60 (65.51) |
| `saveAll`: 1,000 new fragments | 54.01 (81.66) | **34.46** (37.73) | 289.91 (321.98) |
| `saveAll`: 100 new views of 5 comments | **84.39** (105.12) | 95.23 (98.76) | 448.24 (491.05) |

FalkorDB's writes are measured **without unique constraints**, which the other two have. See
[below](#a-unique-constraint-makes-inserts-slower-as-the-label-grows) for why.

### Eight threads at once

Calls a second over 15 seconds, with the median latency of a call, in milliseconds, in brackets.
Higher is better.

| workload | Neo4j (calls/s) | FalkorDB (calls/s) | Oracle (calls/s) |
|---|---:|---:|---:|
| load a view: issue + repo, author, assignees, topics, comments | 2,662 (1.5) | 731 (10.8) | **3,328** (1.0) |
| 2 hops: who the people I follow follow, distinct | 2,775 (1.5) | **12,517** (0.5) | 1,585 (1.8) |

### Loading the graph

In **seconds**. Each engine was loaded by the quickest path an application could use: batches of rows in
Cypher for Neo4j and FalkorDB, JDBC batches into the graph's tables for Oracle. The paths differ, so
this says what a bulk load costs on each and is not a like for like comparison.

| | Neo4j | FalkorDB | Oracle |
|---|---:|---:|---:|
| 1,121,500 nodes | 16.0 s | **12.7 s** | 48.8 s |
| 4,482,439 relationships | 65.8 s | **51.1 s** | 92.0 s |
| container memory after the run | 1.9 GB | **1.2 GB** | 1.8 GB |

## Reading the results

**FalkorDB** is the quickest on point reads and on traversals: about 0.3 ms for one to four hops
where Neo4j takes about 1 ms. It is also the quickest writer, though without the constraints the
others carry. Version 6 is a rewrite of the engine in Rust, and the speed here says the rewrite is
paying off. Its slow results are two plans the new planner does not yet pick as well as the old one
did, below, and a view load under eight threads, where it managed 731 calls a second against roughly
3,000 for the others. We have not yet found out why.

**Neo4j** is rarely the fastest and never far off. It is the only engine here with no result that
needs explaining. Everything by id or by a short traversal lands between 1 and 2 ms.

**Oracle** is the surprise. Its graph is two tables, with labels and properties as JSON, read
through a SQL property graph, and it still loads a five-relationship view faster than either graph
engine (1.41 ms) and sustains the most view loads a second under eight threads. Traversals written
by hand keep pace up to two hops and fall behind at three (8.6 ms against 1.9 and 0.6), though
[that is not the price of the walk itself](#does-oracle-miss-index-free-adjacency). Writes are
where the relational storage shows: 2.5 to 5 times Neo4j across the board, and `saveAll` of 1,000
fragments takes 290 ms.

Scans of a whole label come out about the same on all three, between 25 and 70 ms for 250,000
nodes, and grouping all 1.5 million FOLLOWS takes each of them about 0.8 seconds.

## Does Oracle miss index-free adjacency?

A native graph database keeps each node's relationships with the node, so going to a neighbour is
following a pointer. This is index-free adjacency, and it is the usual argument for a graph engine
over a relational one: a hop costs the same however big the graph is. Oracle has none of it. A
relationship is a row in a table, and a node's relationships are found in a B-tree index over that
table, by the node they leave. FalkorDB takes a third road and keeps relationships as sparse
matrices, so a hop is a matrix multiplication.

So we measured the walk and nothing else: from one person, follow FOLLOWS for one, two, three and
four hops and count the paths. 150 people, each number of hops, the quickest of three calls, after
a full pass to warm everything. The people's follow counts vary widely, so the calls spread from 1
path to 275,590. All three engines count the same paths for every call.

Median time of a call in **milliseconds**, by how many paths the call walked:

| paths walked | calls | Neo4j (ms) | FalkorDB (ms) | Oracle (ms) |
|---|---:|---:|---:|---:|
| under 100 | 264 | 0.73 | 0.23 | **0.18** |
| 100 to 1,000 | 122 | 0.77 | 0.26 | **0.25** |
| 1,000 to 10,000 | 102 | 1.33 | **0.35** | 0.65 |
| 10,000 to 100,000 | 77 | 5.12 | **0.90** | 3.05 |
| over 100,000 | 35 | 40.93 | **4.46** | 23.56 |

A straight line through each engine's calls gives a fixed cost for a call and a cost for each path:

| | fixed cost of a call | each path walked |
|---|---:|---:|
| Neo4j | 0.56 ms | 0.24 microseconds |
| FalkorDB | 0.25 ms | 0.025 microseconds |
| Oracle | 0.16 ms | 0.14 microseconds |

**The missing adjacency never bites here.** Oracle's index walk costs a little over half of what
Neo4j's pointers cost for each path, at every size we reached, and its fixed cost is the lowest of
the three. FalkorDB's matrices are in a class of their own, five times cheaper again.

What does bite is **reading the nodes**. An index over `(src, type)` holds everything a walk needs,
so Oracle answers it without touching a node's row, and the last hop without touching a
relationship's row either. The moment a query wants something of the nodes it passes, each one is a
primary key probe and a JSON document to open. That is the difference between two numbers on this
page for three hops of FOLLOWS: 0.38 ms here, and 8.57 ms in the traversal table above, where the
query counts distinct people and the
[workaround for an Oracle bug](#oracle-free-2326-a-graph-query-that-answers-for-the-wrong-nodes)
makes Oracle visit every node on the way. It is also why Oracle's writes and its grouped traversals
trail: they are about nodes, not about relationships.

Three cautions. This is counting, the kindest case for an index. The graph fits in memory, and a
B-tree over 4.5 million relationships is only a few levels deep; over billions, or colder, each
probe costs more, and that is exactly where index-free adjacency is meant to earn its keep. And this
is Neo4j Community, which does not have the faster runtime of the Enterprise edition.

The nodes on the way are named in the Cypher, `(n1)`, `(n2)`. FalkorDB counts the paths through a
node with no name as one path for each place they end, so `()-[:FOLLOWS]->()` would have had it
count fewer, and do less.

## The four outliers, and why

### Oracle: a path view, 1.5 seconds

A `@GraphPath` field maps the far end of a walk of several hops. On Oracle, Drivine reads it as

```sql
SELECT JSON_ARRAYAGG(orgs_.doc ...)
FROM GRAPH_TABLE (bench MATCH (orgs_ IS node) COLUMNS (orgs_.nid, orgs_.doc)) orgs_
WHERE orgs_.nid IN (
    SELECT reached_.nid
    FROM GRAPH_TABLE (bench MATCH (person_ IS node) -[...FOLLOWS]-> (m1 IS node ...) -[...MEMBER_OF]-> (reached_ IS node ...)
                      COLUMNS (person_.nid AS src, reached_.nid)) reached_
    WHERE reached_.src = person_.nid)
```

The outer query is over every node, and Oracle does not turn the `IN` around to start from the
person. So it reads all 1.1 million nodes for each load. The time is linear in the size of the
graph: 40 ms at 11,000 nodes, 72 ms at 56,000, 1,503 ms at 1.1 million. This is Drivine's to fix:
reading the path outward from the person gives the same answer in under 20 ms
([drivine4j#64](https://github.com/liberation-data/drivine4j/issues/64)).

### Oracle: `loadAll` by an indexed property, 72 ms

The statement tests the label with `JSON_EXISTS(labels, '$[*]?(@ == "Issue")')`, which the index
over labels can answer, and the property with `JSON_VALUE(props, '$.repoKey') = $p`, which the index
over `repoKey` can answer. Oracle estimates that the label index returns one row and picks it. It
returns 250,000. With the label tested the way Drivine tests it for a load by id
(`INSTR(JSON_SERIALIZE(labels), '"Issue"') > 0`), the optimizer takes the `repoKey` index. Also
Drivine's to fix ([drivine4j#65](https://github.com/liberation-data/drivine4j/issues/65)).

### FalkorDB: `loadAll` by an indexed property, 24 ms

Drivine's query DSL writes several conditions as `WHERE (a AND b)`. FalkorDB 6.0.0 answers a
parenthesised `WHERE` with a label scan; without the parentheses it uses the index and takes 0.3 ms.
FalkorDB 4.20.7 uses the index either way. Reported as
[FalkorDB#3331](https://github.com/FalkorDB/FalkorDB/issues/3331), and Drivine has no need to write the parentheses
([drivine4j#66](https://github.com/liberation-data/drivine4j/issues/66)).

### FalkorDB: a path view, 53 ms

The path is read with `OPTIONAL MATCH (person)-[:FOLLOWS]->(:Person)-[:MEMBER_OF]->(orgs:Org)` from a
person already found. With a label on the far node, FalkorDB 6.0.0 plans the walk from that end: it
reads all 129,912 MEMBER_OF relationships and then joins back to the person. Without the label it
walks out from the person in 0.07 ms. FalkorDB 4.20.7 walks out from the person in both cases.
Reported as [FalkorDB#3332](https://github.com/FalkorDB/FalkorDB/issues/3332); Drivine can test the label afterwards in the meantime
([drivine4j#67](https://github.com/liberation-data/drivine4j/issues/67)).

## The bugs

A benchmark pushes a database down paths its own tests have not been yet, so it always finds
something. Here is what this one found.

### FalkorDB 6: a new engine, and three rough edges

FalkorDB 6 is the engine rewritten in Rust. That is a brave thing to do to a working database, and
on this evidence a good one: it is the fastest engine on this page by a distance. A first release of
a rewrite is bound to have a rough edge here and there, and we met three. All three are things the
previous version, 4.20.7, did well, so they are known ground and should be quick to win back. All
are reported, with a small repro each, and none of them gives a wrong answer.

Two are the plans described above ([#3331](https://github.com/FalkorDB/FalkorDB/issues/3331),
[#3332](https://github.com/FalkorDB/FalkorDB/issues/3332)). The third is the one that shaped this
benchmark.

#### A unique constraint makes inserts slower as the label grows

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

The SQL written by hand here therefore carries a hint that turns join elimination off,
`/*+ OPT_PARAM('_optimizer_join_elimination_enabled' 'false') */`. That costs Oracle a primary key
probe for each node a pattern passes through, so its traversal numbers are a little worse than they
would be without the bug. The statements Drivine generates read the starting node's columns, and are
not affected.

## What to make of it

Take the shape of these results and not the last decimal place.

- **One run, on a busy laptop.** Other work was running on the host (load average about 9 on 12
  cores). Differences of 20 or 30 percent mean nothing here. Differences of 3 times do.
- **Oracle Free is not Oracle.** It is capped at 2 CPU threads and 2 GB of memory. The same
  statements on a licensed edition with real memory would behave differently, above all under
  concurrency.
- **Small machines, a warm cache.** At this size the graph fits in memory on all three. Nothing
  here says what happens when it does not.
- **The SQL/PGQ is ours and untuned.** It is what a competent first attempt looks like, with one
  hint to get around a bug. Someone who tunes Oracle for a living would do better.
- **The object-manager numbers measure Drivine too.** Two of the four outliers are statements
  Drivine could write better, and the fix will move those numbers by orders of magnitude without
  either database changing.
- **FalkorDB runs without unique constraints**, for the reason given above.

## What changes in Drivine

Four things come out of this for Drivine4j:

1. On Oracle, read a path field from the root outward, and not as a test of every node
   ([#64](https://github.com/liberation-data/drivine4j/issues/64)).
2. On Oracle, test the label with `INSTR` when a property with an index is in the filter
   ([#65](https://github.com/liberation-data/drivine4j/issues/65)).
3. On FalkorDB, write a group of conditions without the parentheses around it
   ([#66](https://github.com/liberation-data/drivine4j/issues/66)).
4. On FalkorDB, leave the label off the far end of a path's pattern and test it afterwards
   ([#67](https://github.com/liberation-data/drivine4j/issues/67)).

The last two work around things FalkorDB may well fix first. When these are in, we will run the
benchmark again and publish the numbers beside these. The hand-written SQL/PGQ will change with
them, to the form the adjacency measurement uses, which keeps clear of the Oracle bug without a
hint.

## Run it yourself

You need Docker and a JDK (17 or later).

```bash
./run.sh 1                  # the run above: all three engines, scale factor 1
./run.sh 0.05               # a twentieth of the graph, in a couple of minutes
./run.sh 1 neo4j oracle     # only some engines
```

`run.sh` starts a fresh container for each engine in turn (`gbench-neo4j`, `gbench-falkordb`,
`gbench-oracle`, on ports 17687, 16379 and 11521), loads the graph, runs the workloads, stops the
container and prints the tables. Results are written to `results/<engine>-sf<scale>.json`; the ones
behind this page are in [`results/`](results). To print the tables again:

```bash
build/install/graph-bench/bin/graph-bench report --sf 1
```

To run against a database of your own, set `BENCH_NEO4J_HOST`, `BENCH_NEO4J_PORT`,
`BENCH_NEO4J_PASSWORD`, and the like for `FALKORDB` and `ORACLE`, and call the harness directly:

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
| [`Load.kt`](src/main/kotlin/bench/Load.kt) | the indexes, and a loader for Cypher and one for Oracle's tables |
| [`Workloads.kt`](src/main/kotlin/bench/Workloads.kt) | every workload, with the Cypher and SQL/PGQ side by side |
| [`Main.kt`](src/main/kotlin/bench/Main.kt) | timing, the digest that checks the engines agree, the report |

Two things about the data are deliberate. FOLLOWS never runs both ways between two people, and
DEPENDS_ON only points at a repo made earlier. Cypher refuses a walk that uses a relationship twice
and SQL/PGQ allows it, so without those two rules the engines would be counting different things.

## License

Apache 2.0.
