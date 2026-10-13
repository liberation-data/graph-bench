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
statements Drivine could write better, and both are being fixed.

## The short version

- **No engine wins everywhere.** FalkorDB is fastest on most workloads, often by 3 to 4 times.
  Neo4j is the only one with no bad outlier. Oracle keeps up on reads, and leads on loading a view
  with several relationships, but is 2.5 to 5 times slower than Neo4j on writes.
- **The outliers are plans, not engines.** Four results are off by one to three orders of
  magnitude. Each has a specific cause in how a statement was planned, and each is explained below.
- **We found four bugs on the way.** Three are regressions in FalkorDB 6.0.0, now reported. One is
  in Oracle Free 23.26, where a graph query can return a wrong answer or end the server process.
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

Milliseconds, median, with the 95th percentile in brackets. Lower is better.

### Reads through the object manager

| workload | Neo4j | FalkorDB | Oracle |
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

| workload | Neo4j | FalkorDB | Oracle |
|---|---:|---:|---:|
| 1 hop: a person's followers, counted | 0.99 (1.29) | **0.28** (0.43) | 0.55 (1.09) |
| 2 hops: who the people I follow follow, distinct | 0.98 (1.29) | **0.28** (0.40) | 1.23 (5.97) |
| 3 hops: the same, one further | 1.92 (5.89) | **0.58** (1.95) | 8.57 (37.99) |
| 1 to 4 hops: a repo's transitive dependencies | 1.12 (1.60) | **0.29** (0.40) | 1.10 (2.99) |
| 3 hops and a group: an org's top topics among open issues | 1.69 (3.10) | **1.23** (1.93) | 3.37 (10.68) |
| 3 hops and a group: a repo's top commenters | **1.46** (3.19) | 2.75 (3.06) | 2.06 (4.40) |
| 3 hops and a group: repos the people I follow raise issues in | 1.23 (3.88) | **0.98** (1.90) | 1.44 (8.79) |

### The whole graph

| workload | Neo4j | FalkorDB | Oracle |
|---|---:|---:|---:|
| every issue, grouped by state | 58.86 (73.48) | **47.14** (47.71) | 69.65 (72.00) |
| every FOLLOWS (1.5 million), grouped: the ten most followed | **765.99** (878.30) | 828.54 (845.92) | 835.50 (952.80) |

### Writes through the object manager

| workload | Neo4j | FalkorDB | Oracle |
|---|---:|---:|---:|
| save a new fragment | 1.49 (2.74) | **0.42** (0.55) | 3.68 (8.61) |
| update a fragment: load, change, save | 2.17 (3.15) | **0.74** (1.09) | 4.84 (13.30) |
| save a new view: an issue and 10 new comments | 3.66 (5.36) | **1.22** (1.93) | 11.55 (17.18) |
| save a new view: an issue and 100 new comments | 16.23 (33.89) | **3.81** (10.16) | 48.60 (65.51) |
| `saveAll`: 1,000 new fragments | 54.01 (81.66) | **34.46** (37.73) | 289.91 (321.98) |
| `saveAll`: 100 new views of 5 comments | **84.39** (105.12) | 95.23 (98.76) | 448.24 (491.05) |

FalkorDB's writes are measured **without unique constraints**, which the other two have. See
[the first bug](#falkordb-600-a-unique-constraint-makes-inserts-quadratic) for why.

### Eight threads at once

Calls a second over 15 seconds, with the median latency in milliseconds in brackets. Higher is better.

| workload | Neo4j | FalkorDB | Oracle |
|---|---:|---:|---:|
| load a view: issue + repo, author, assignees, topics, comments | 2,662 (1.5) | 731 (10.8) | **3,328** (1.0) |
| 2 hops: who the people I follow follow, distinct | 2,775 (1.5) | **12,517** (0.5) | 1,585 (1.8) |

### Loading the graph

Seconds. Each engine was loaded by the quickest path an application could use: batches of rows in
Cypher for Neo4j and FalkorDB, JDBC batches into the graph's tables for Oracle. The paths differ, so
this says what a bulk load costs on each and is not a like for like comparison.

| | Neo4j | FalkorDB | Oracle |
|---|---:|---:|---:|
| 1,121,500 nodes | 16.0 | **12.7** | 48.8 |
| 4,482,439 relationships | 65.8 | **51.1** | 92.0 |
| container memory after the run | 1.9 GB | **1.2 GB** | 1.8 GB |

## Reading the results

**FalkorDB** is the quickest on point reads and on traversals: about 0.3 ms for one to four hops
where Neo4j takes about 1 ms. It is also the quickest writer, though without the constraints the
others carry. Its weak spots are two planner regressions in 6.0.0, below, and a view load under
eight threads, where it managed 731 calls a second against roughly 3,000 for the others. We have not
yet found out why.

**Neo4j** is rarely the fastest and never far off. It is the only engine here with no result that
needs explaining. Everything by id or by a short traversal lands between 1 and 2 ms.

**Oracle** is the surprise. Its graph is two tables, with labels and properties as JSON, read
through a SQL property graph, and it still loads a five-relationship view faster than either graph
engine (1.41 ms) and sustains the most view loads a second under eight threads. Traversals written
by hand keep pace up to two hops and fall behind at three (8.6 ms against 1.9 and 0.6). Writes are
where the relational storage shows: 2.5 to 5 times Neo4j across the board, and `saveAll` of 1,000
fragments takes 290 ms.

Scans of a whole label come out about the same on all three, between 25 and 70 ms for 250,000
nodes, and grouping all 1.5 million FOLLOWS takes each of them about 0.8 seconds.

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
graph: 40 ms at 11,000 nodes, 72 ms at 56,000, 1,503 ms at 1.1 million. This is Drivine's to fix.

### Oracle: `loadAll` by an indexed property, 72 ms

The statement tests the label with `JSON_EXISTS(labels, '$[*]?(@ == "Issue")')`, which the index
over labels can answer, and the property with `JSON_VALUE(props, '$.repoKey') = $p`, which the index
over `repoKey` can answer. Oracle estimates that the label index returns one row and picks it. It
returns 250,000. With the label tested the way Drivine tests it for a load by id
(`INSTR(JSON_SERIALIZE(labels), '"Issue"') > 0`), the optimizer takes the `repoKey` index. Also
Drivine's to fix.

### FalkorDB: `loadAll` by an indexed property, 24 ms

Drivine's query DSL writes several conditions as `WHERE (a AND b)`. FalkorDB 6.0.0 answers a
parenthesised `WHERE` with a label scan; without the parentheses it uses the index and takes 0.3 ms.
FalkorDB 4.20.7 uses the index either way. Reported as
[FalkorDB#3331](https://github.com/FalkorDB/FalkorDB/issues/3331).

### FalkorDB: a path view, 53 ms

The path is read with `OPTIONAL MATCH (person)-[:FOLLOWS]->(:Person)-[:MEMBER_OF]->(orgs:Org)` from a
person already found. With a label on the far node, FalkorDB 6.0.0 plans the walk from that end: it
reads all 129,912 MEMBER_OF relationships and then joins back to the person. Without the label it
walks out from the person in 0.07 ms. FalkorDB 4.20.7 walks out from the person in both cases.
Reported as [FalkorDB#3332](https://github.com/FalkorDB/FalkorDB/issues/3332).

## The bugs

### FalkorDB 6.0.0: a unique constraint makes inserts quadratic

With a unique constraint on a label, each insert costs time in proportion to the nodes already
there:

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
[FalkorDB#3330](https://github.com/FalkorDB/FalkorDB/issues/3330).

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

1. On Oracle, read a path field from the root outward, and not as a test of every node.
2. On Oracle, test the label with `INSTR` when a property with an index is in the filter.
3. On FalkorDB, write a group of conditions without the parentheses around it.
4. On FalkorDB, leave the label off the far end of a path's pattern and test it afterwards.

The last two work around regressions that FalkorDB may well fix first.

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
