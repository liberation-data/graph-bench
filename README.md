# Graph Bench

**Using Drivine to compare the performance of Neo4j, FalkorDB, Oracle and Postgres.**

*15 October 2026. Postgres added 22 October 2026. Run again on a quiet test machine, an Apple M1, through
Drivine4j 0.6.1, with Neo4j and Postgres also at 3, 10 and 30 times the size, 8 November 2026.*

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
4.5 million relationships, and, for Neo4j and Postgres, on graphs of up to 34 million nodes and 134 million.

### What Drivine costs

Little. A load or a save is one statement and one round trip, however many relationships it has:
an issue with its repo, author, assignees, topics and comments loads in 1.7 to 3.8 ms on every
engine.

## The short version

- **No engine wins everywhere.** FalkorDB is fastest on most workloads, often by 3 to 5 times.
  Neo4j is never far off and has no bad result. Oracle keeps up on reads, but is 5 to 6 times
  slower than Neo4j on batch writes.
- **Postgres is the surprise.** Two plain tables and joins load the view of five relationships
  faster than any other engine, sustain more than twice the view loads a second of any other under
  eight threads, and group all 1.5 million FOLLOWS in about two thirds of the graph engines' time. It
  writes 3 to 4 times faster than Oracle and beats Neo4j on single saves; bulk writes still trail
  Neo4j.
- **Neither SQL store has index-free adjacency, and at this size it did not matter.** Walking relationships
  through an index cost Oracle and Postgres less for each path than Neo4j, out to 275,000 paths.
  What costs them is reading the nodes on the way. [The numbers are below.](#does-oracle-miss-index-free-adjacency)
- **Grow the graph and Neo4j overtakes Postgres** on most traversals, three hops by 3 times the size and two hops by 10 (3.4 and 11
  million nodes): at 10 times, two hops take Neo4j 1.2 ms and Postgres 7.8, three hops 3.6 ms and 80. Neo4j's lead
  on counting a label and on bulk writes grows with the graph too. Postgres keeps point reads, and the walk itself,
  reading nothing of the nodes, stays cheaper for it at every size. [The numbers are below.](#bigger-graphs-neo4j-and-postgres-at-up-to-30-times-the-size)
- **A benchmark is a good bug hunt.** FalkorDB 6 is a new engine, rewritten in Rust, and we met
  three rough edges in it, all reported and all with a small workaround. Oracle Free 23.26 has a
  sharper one, where a graph query can return a wrong answer or end the server process. On
  Postgres it was Drivine's own writes that the benchmark found slow, fixed before the run shown.
- **Every engine gave the same answers.** Each read workload's results are digested and compared
  across the four. They agree on all of them.
- **Search is the SQL stores' weak spot.** Vector search is quick everywhere (1.5 to 5 ms). Full-text search on
  Postgres takes 38 ms for rare words and 459 ms for common short ones, where Neo4j takes 5 or 6; and it ranks
  unlike Neo4j's index. [The numbers are below.](#searching-full-text-and-vectors)

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

**The engines.** Neo4j 2026.07.1 (Community), FalkorDB 6.0.0, Oracle Database Free 23.26
(`gvenzl/oracle-free:23-faststart`) and Postgres 17 with pgvector (`pgvector/pgvector:pg17`), all four through
Drivine4j 0.6.1, one after another on the same day. Each results file records the image, its digest, the Drivine
version and the machine.

**The limits.** Each engine ran alone, in a fresh container given 2 CPUs and 4 GB. Oracle Free
allows itself 2 CPU threads and 2 GB whatever it is given, so the others were held to the same
processors. Neo4j had a 1 GB heap and a 2 GB page cache; Postgres a 1 GB buffer cache
(`shared_buffers`), with the rest left to the operating system's cache, as Postgres expects. The host
is a test machine kept for this, an Apple M1 mac mini (8 cores, 16 GB), with nothing else running on it.

The first run of this page, in October, was on an Apple M2 Max laptop through Drivine4j 0.1.6 and 0.2.0; its results
are still in [`results/`](results). The M1 is slower for Neo4j and Oracle above all (a fragment by id in 2.95 ms against 1.83), so
the two runs don't compare number for number. The shape is the same.

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

A cell is the median, with the 95th percentile in brackets. Lower is better. The results are in
[`results/m1-0.6.1`](results/m1-0.6.1).

### Reads through the object manager

| workload | Neo4j (ms) | FalkorDB (ms) | Oracle (ms) | Postgres (ms) |
|---|---:|---:|---:|---:|
| load a fragment by id | 2.95 (3.92) | **0.51** (0.70) | 0.88 (1.18) | 0.75 (1.04) |
| load a view: issue + repo, author, assignees, topics, comments | 3.66 (5.13) | 3.84 (4.40) | 2.72 (4.20) | **1.69** (2.88) |
| load a nested view: issue, comments, each comment's author | 2.72 (4.02) | 2.88 (3.71) | 1.34 (2.26) | **0.97** (1.52) |
| load a view with a `@Count`: repo + owner, dependencies, issue count | 2.02 (3.00) | 0.89 (1.09) | 1.12 (1.71) | **0.83** (1.15) |
| load a `@GraphPath` view: orgs of the people a person follows | 2.82 (3.99) | **0.59** (0.88) | 1.62 (4.89) | 0.85 (1.55) |
| `loadAll` by an indexed property: a repo's open issues | 1.71 (2.44) | **0.45** (0.57) | 0.70 (1.36) | 0.58 (0.77) |
| `count` by unindexed properties: scans 250,000 issues | 70.77 (77.91) | **21.69** (22.09) | 67.37 (67.90) | 72.52 (83.45) |
| `count` every comment | 1.42 (1.91) | **0.35** (0.44) | 20.91 (21.17) | 26.94 (57.35) |

### Traversals written by hand

| workload | Neo4j (ms) | FalkorDB (ms) | Oracle (ms) | Postgres (ms) |
|---|---:|---:|---:|---:|
| 1 hop: a person's followers, counted | 1.28 (1.63) | 0.37 (0.45) | 0.23 (0.31) | **0.22** (0.33) |
| 2 hops: who the people I follow follow, distinct | 1.34 (1.73) | 0.37 (0.52) | 1.01 (5.69) | **0.25** (0.52) |
| 3 hops: the same, one further | 2.35 (5.70) | **0.70** (1.94) | 4.20 (20.43) | 0.88 (3.56) |
| 1 to 4 hops: a repo's transitive dependencies | 1.32 (1.66) | 0.41 (0.51) | 0.72 (1.54) | **0.35** (0.54) |
| 3 hops and a group: an org's top topics among open issues | 1.82 (2.68) | **1.25** (1.58) | 3.15 (9.11) | 1.51 (3.36) |
| 3 hops and a group: a repo's top commenters | 1.35 (1.73) | 2.90 (3.06) | 2.99 (6.54) | **0.72** (1.04) |
| 3 hops and a group: repos the people I follow raise issues in | 1.42 (3.19) | 1.10 (1.92) | 1.44 (7.25) | **0.74** (4.12) |

### The whole graph

| workload | Neo4j (ms) | FalkorDB (ms) | Oracle (ms) | Postgres (ms) |
|---|---:|---:|---:|---:|
| every issue, grouped by state | 74.02 (75.90) | **40.76** (41.10) | 69.47 (69.72) | 77.95 (86.24) |
| every FOLLOWS (1.5 million), grouped: the ten most followed | 736.47 (792.71) | 686.43 (692.33) | 1372.23 (1376.36) | **458.19** (461.50) |

Over the whole graph, index-free adjacency is starting to pay for Oracle: grouping 1.5 million
FOLLOWS takes it nearly twice as long as the graph engines. Postgres groups them in about two thirds of the
graph engines' time: it reads the relationships from the index over the nodes they reach, in that
order and without touching their rows, merges them with the nodes in the order of their keys, and
counts. A walk of the whole graph in order is cheap. It takes a lot of data before following a
pointer, O(1), outshines a probe of a B-tree only a few levels deep, O(log n).
[Below](#does-oracle-miss-index-free-adjacency), out to 275,000 paths, it has not yet; and
[further below](#bigger-graphs-neo4j-and-postgres-at-up-to-30-times-the-size), on a graph 30 times the size, it
still has not for this query.

### Writes through the object manager

| workload | Neo4j (ms) | FalkorDB (ms) | Oracle (ms) | Postgres (ms) |
|---|---:|---:|---:|---:|
| save a new fragment | 2.46 (3.91) | **0.46** (0.60) | 3.15 (4.82) | 1.04 (1.76) |
| update a fragment: load, change, save | 3.31 (4.18) | **1.30** (2.03) | 3.56 (4.21) | 1.37 (1.65) |
| save a new view: an issue and 10 new comments | 4.69 (6.27) | **1.51** (1.80) | 6.97 (9.14) | 2.21 (2.90) |
| save a new view: an issue and 100 new comments | 9.63 (12.95) | **4.11** (13.41) | 44.33 (51.92) | 10.66 (11.26) |
| `saveAll`: 1,000 new fragments | 46.81 (54.07) | **33.09** (41.32) | 289.52 (298.70) | 67.49 (68.81) |
| `saveAll`: 100 new views of 5 comments | **94.08** (109.68) | 103.55 (115.59) | 515.55 (606.58) | 120.79 (139.66) |

FalkorDB's writes are measured **without unique constraints**, which the other three have. See
[below](#falkordb-6-a-unique-constraint-makes-inserts-slower-as-the-label-grows) for why.

### Eight threads at once

Calls a second over 15 seconds, with the median latency of a call, in milliseconds, in brackets.
Higher is better.

| workload | Neo4j (calls/s) | FalkorDB (calls/s) | Oracle (calls/s) | Postgres (calls/s) |
|---|---:|---:|---:|---:|
| load a view: issue + repo, author, assignees, topics, comments | 1,772 (2.1) | 679 (11.5) | 2,370 (1.6) | **5,230** (1.2) |
| 2 hops: who the people I follow follow, distinct | 2,687 (1.6) | **11,050** (0.6) | 3,366 (0.9) | 8,208 (0.5) |

### Loading the graph

In **seconds**. Each engine was loaded by the quickest path an application could use: batches of rows in
Cypher for Neo4j and FalkorDB, JDBC batches into the graph's tables for Oracle, and `COPY` into the
same tables for Postgres. The paths differ, so this says what a bulk load costs on each and is not a
like for like comparison.

| | Neo4j | FalkorDB | Oracle | Postgres |
|---|---:|---:|---:|---:|
| 1,121,500 nodes | 19.4 s | 11.4 s | 59.6 s | **7.5 s** |
| 4,482,439 relationships | 78.0 s | 55.2 s | 77.6 s | **54.7 s** |

### Drivine 0.4.1 against 0.6.1

Every workload above was also run through Drivine4j 0.4.1, on the same machine the same day, into
[`results/m1-0.4.1`](results/m1-0.4.1), to see whether two releases of Drivine changed anything. They did not: every
workload gives the same answer on both, and the times agree within about 1.5 times, the bulk loads
within about a second. No difference reaches the 2 times that [the cautions](#what-to-make-of-it) ask for.

## Reading the results

**FalkorDB** is the quickest on point reads and on writes, though without the constraints the others carry, and close
to Postgres on short traversals: about 0.4 ms for one to four hops where Neo4j takes about 1.3 ms. Its one slow result is a view load
under eight threads, 679 calls a second against 1,772 to 5,230 for the others: the load costs FalkorDB about 2.9 ms
of CPU, and it has two CPUs.

**Neo4j** is rarely the fastest and never far off. It is the only engine with no result needing an
explanation. Everything by id or by a short traversal lands between 1.3 and 3 ms.

**Oracle** is the surprise. Its graph is two tables, with labels and properties as JSON, read
through a SQL property graph, and it still loads views faster than either graph engine (2.72 ms
for five relationships, 1.34 ms for a nested one), and sustains more view loads a second than
either under eight threads. Traversals written by hand keep pace up to two hops and fall behind at three (4.2 ms
against 2.35 and 0.70), though
[that is not the price of the walk itself](#does-oracle-miss-index-free-adjacency). Writes are
where the relational storage shows. Saving one thing is close to Neo4j, but anything in bulk is
5 to 6 times slower: `saveAll` of 1,000 fragments takes 290 ms.

**Postgres** keeps the graph in Oracle's tables and reads them with nothing but joins, and does best
of the four on what an application mostly asks: views. It loads one with five relationships in
1.69 ms, the quickest here, and under eight threads sustains 5,230 view loads a second, more than
twice Oracle's 2,370 and nearly three times Neo4j's. Short traversals are quicker than FalkorDB's (0.22
and 0.25 ms for one and two hops, against 0.37), and the grouped three-hop traversals are the quickest of all on
two of the three. It writes a single object faster than Neo4j and about 3 times faster than Oracle;
in bulk it trails Neo4j by 1.3 to 1.4 times, because Drivine's write function finds and writes each
node in turn. Its slow result is counting a whole label: Postgres keeps no count of a label's nodes, so a
count of 750,000 comments reads them, 27 ms against 1.4 ms for Neo4j, which keeps one.

Scans of a whole label come out about the same on all four, between 22 and 78 ms for 250,000
nodes. Grouping all 1.5 million FOLLOWS takes the graph engines about 0.7 seconds, Oracle 1.4, and
Postgres 0.46.

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
| under 100 | 264 | 0.74 | 0.29 | **0.18** | **0.18** |
| 100 to 1,000 | 122 | 0.86 | 0.32 | 0.26 | **0.25** |
| 1,000 to 10,000 | 102 | 1.51 | **0.40** | 0.72 | 0.63 |
| 10,000 to 100,000 | 77 | 7.63 | **0.96** | 5.02 | 3.56 |
| over 100,000 | 35 | 51.98 | **4.74** | 36.97 | 25.59 |

A straight line through each engine's calls gives a fixed cost for a call and a cost for each path:

| | fixed cost of a call | each path walked |
|---|---:|---:|
| Neo4j | 0.68 ms | 0.29 microseconds |
| FalkorDB | 0.31 ms | 0.026 microseconds |
| Oracle | 0.13 ms | 0.21 microseconds |
| Postgres | 0.17 ms | 0.15 microseconds |

**The missing adjacency never bites here.** Oracle's index walk costs about three quarters of what
Neo4j's pointers cost for each path, at every size we reached, and has the lowest fixed cost of the four. Postgres,
walking the same kind of index over the same table, costs half of what Neo4j does for each path. FalkorDB's matrices
are in a class of their own, six to eight times cheaper again.

What does bite is **reading the nodes**. An index over `(src, type)` holds everything a walk needs,
so Oracle answers it without touching a node's row, and the last hop without touching a
relationship's row either. The moment a query wants something of the nodes it passes, each one is a
primary key probe and a JSON document to open. That is the difference between two numbers on this
page for three hops of FOLLOWS: 0.47 ms here (the median of the 150 calls), and 4.20 ms in the traversal table
above, where the query counts the distinct people at the end and not the paths. Postgres shows the same, 0.42 ms
here and 0.88 ms in the traversal table. It is also why Oracle's batch writes and its grouped traversals trail: they are
about nodes, not about relationships.

Three cautions. This is counting, the kindest case for an index. The graph fits in memory, and a
B-tree over 4.5 million relationships is only a few levels deep; over billions, or colder, each
probe costs more, and that is exactly where index-free adjacency is meant to earn its keep. The next section grows
the graph to 30 times this size to look. And this is Neo4j Community, which does not have the faster runtime of the
Enterprise edition.

## Bigger graphs: Neo4j and Postgres at up to 30 times the size

At 1.1 million nodes the graph fits in each container's 4 GB, and Postgres keeps up with Neo4j or beats it.
So where does that turn? Neo4j and Postgres were run again at 3, 10 and 30 times the size, the same graph generated
bigger, with the same limits as above: 2 CPUs and 4 GB, so the larger graphs are well beyond memory. The searches were
left out. The results are in [`results/m1-scaling`](results/m1-scaling), scale 1 a run of its own beside the one above.

| scale | nodes | relationships |
|---:|---:|---:|
| ×1 | 1,121,500 | 4,482,439 |
| ×3 | 3,363,500 | 13,448,688 |
| ×10 | 11,210,500 | 44,818,559 |
| ×30 | 33,630,500 | 134,509,246 |

Both gave the same answers at every scale. Each cell is Neo4j's median and then Postgres's, in **milliseconds**, the
quicker in bold:

| workload | ×1 | ×3 | ×10 | ×30 |
|---|---:|---:|---:|---:|
| load a fragment by id | 2.92 / **0.74** | 2.64 / **0.92** | 2.04 / **0.90** | 2.00 / **1.22** |
| load a view: issue and five relationships | 3.70 / **1.62** | 3.52 / **2.54** | 4.76 / **4.23** | 5.91 / **5.47** |
| load a view with a `@Count` | 2.00 / **0.89** | 2.00 / **1.23** | **2.61** / 3.76 | **3.96** / 6.17 |
| load a `@GraphPath` view | 2.82 / **0.79** | 2.67 / **2.29** | **2.51** / 5.32 | **6.55** / 7.17 |
| `loadAll` by an indexed property | 1.74 / **0.57** | 1.75 / **0.84** | 5.06 / **2.07** | **4.97** / 5.29 |
| `count` every comment | **1.43** / 27.3 | **1.38** / 107 | **1.28** / 329 | **0.99** / 1,559 |
| 1 hop, counted | 1.25 / **0.27** | 1.33 / **0.30** | 1.14 / **0.58** | 2.25 / **1.08** |
| 2 hops, distinct | 1.42 / **0.31** | 1.41 / **0.85** | **1.23** / 7.80 | **8.10** / 17.3 |
| 3 hops, distinct | 2.32 / **1.02** | **2.95** / 3.20 | **3.61** / 80.2 | 172 / **170** |
| 1 to 4 hops: transitive dependencies | 1.42 / **0.45** | 1.43 / **0.58** | 1.63 / **0.71** | **2.27** / 2.35 |
| 3 hops and a group: an org's top topics | 1.99 / **1.51** | **1.90** / 4.15 | **4.51** / 30.3 | **49.6** / 64.2 |
| 3 hops and a group: a repo's top commenters | 1.43 / **0.72** | **1.52** / 2.94 | **4.35** / 16.6 | **12.6** / 18.2 |
| 3 hops and a group: repos the people I follow raise issues in | 1.54 / **0.74** | 1.61 / **1.45** | **1.99** / 10.8 | 21.7 / **17.9** |
| every issue, grouped by state | **62.2** / 67.2 | **200** / 302 | **664** / 998 | **1,887** / 12,489 |
| every FOLLOWS, grouped: the ten most followed | 723 / **449** | 2,542 / **1,167** | 10,817 / **4,374** | 571,884 / **42,012** |
| save a new fragment | 2.26 / **0.94** | 2.55 / **0.99** | 2.45 / **1.04** | 2.69 / **1.37** |
| save a new view: an issue and 100 new comments | **10.4** / 10.6 | **10.5** / 11.3 | **12.5** / 25.2 | **12.3** / 59.7 |
| `saveAll`: 1,000 new fragments | **45.1** / 70.2 | **54.1** / 70.8 | **59.6** / 70.1 | **54.9** / 364 |

Under eight threads, in **calls a second**, Neo4j's and then Postgres's, the higher in bold:

| workload | ×1 | ×3 | ×10 | ×30 |
|---|---:|---:|---:|---:|
| load a view: issue and five relationships | 1,867 / **5,228** | **1,793** / 1,728 | **1,720** / 1,407 | **2,175** / 1,351 |
| 2 hops, distinct | 2,610 / **8,239** | 2,584 / **7,240** | 2,461 / **4,366** | **773** / 345 |

Loading, in **seconds**, by the paths described [above](#loading-the-graph):

| | Neo4j nodes | Neo4j relationships | Postgres nodes | Postgres relationships |
|---:|---:|---:|---:|---:|
| ×1 | 19 s | 78 s | **7 s** | **55 s** |
| ×3 | 51 s | 250 s | **23 s** | **208 s** |
| ×10 | 175 s | **915 s** | **78 s** | 1,499 s |
| ×30 | 550 s | **3,724 s** | **251 s** | 16,738 s |

- **Traversals that read the nodes turn by 3 or 10 times the size.** At 10 times, two hops take Neo4j 1.2 ms and
  Postgres 7.8; three hops 3.6 ms and 80; the grouped three-hop traversals are 4 to 7 times quicker on Neo4j. Neo4j's
  times grow far less up to there than Postgres's, which grow with the graph, likely because each node on the way is a probe of the node
  table's key and a row to read, and fewer of those rows are in memory.
- **The walk itself never turns.** Counting the paths and reading nothing of the nodes, as in
  [the section above](#does-oracle-miss-index-free-adjacency), Postgres costs less for each path than Neo4j at every
  scale: 0.15 microseconds against 0.29 at 1 times, 0.27 against 0.39 at 30. What costs Postgres is the nodes.
- **At 30 times Neo4j slows down too.** Its store is then far larger than its 2 GB page cache, and three hops take it 172 ms,
  about what they take Postgres; grouping every FOLLOWS takes it nine and a half minutes, against 42 seconds for
  Postgres, which reads the relationships from its index in order.
- **Postgres keeps point reads and saves of one fragment** at every scale: a fragment by id in 1.2 ms against 2.0 at 30 times.
  The view of five relationships is still Postgres's, just, at 5.5 ms against 5.9.
- **Neo4j's lead on whole labels and in bulk grows.** It keeps a count of each label, so counting the comments costs it
  1 ms at every size, and Postgres 1.6 s at 30 times. `saveAll` of 1,000 fragments costs Neo4j the same at every size,
  and Postgres 70 ms up to 10 times and 364 ms at 30.
- **Postgres's bulk load grows faster than the graph.** Its relationships took 55 s at 1 times and 4.6 hours at 30,
  where Neo4j's took an hour: 300 times as long for 30 times the rows. The load is the harness's own `COPY` and
  `INSERT … SELECT`, into Drivine's edge table, with its two foreign keys and three indexes; where the time goes is not
  measured yet.

## Searching: full text and vectors

*Added 8 November 2026. Run on the test machine of the numbers above, through Drivine4j 0.6.1, each engine alone in a fresh container with the same limits as above. The results are in [`results/search-2026-11-08`](results/search-2026-11-08) and, for Oracle Text, [`results/search-2026-11-08-oracle-text`](results/search-2026-11-08-oracle-text).*

[Guide](https://github.com/embabel/guide), Embabel's assistant for its own docs, moved from Neo4j Aura to Postgres.
Everything above at scale 1 had Postgres quickest or close to it, but Guide's full-text search was slower there than on Aura:
on a laptop's Postgres 17, 41 ms for "MCP server" and 487 ms for "how do I define an action", where Aura's Lucene
answers both in tens of milliseconds. Its vector search was within 40 ms of Aura's. Nothing above searches, so
nothing above could have caught it. These workloads do.

**The corpus.** Guide's own: the 1,583 chunks of Embabel's docs that Guide answers from, a kilobyte or two of text
each (1.7 million characters in all) and an embedding of 384 numbers. They are taken from Guide's content seed, the
export of its Postgres database that Guide's CI makes after an ingest, and saved into each engine through Drivine,
with `saveAll`, as a `@NodeFragment` of their own:

```kotlin
@NodeFragment(labels = ["Chunk"])
data class Chunk(
    @NodeId val id: String,
    @FullTextIndex val text: String,
    @VectorIndex(similarity = SimilarityFunction.COSINE) val embedding: List<Float>,
)
```

They are kept in a graph of their own (`guide`), as Guide keeps them in a database of a few thousand nodes, not among
the 1.1 million of the graph above. On Neo4j, which has one database, they are a label apart.

**The searches.** Each through the object manager, the same line on every engine, as Guide searches:

```kotlin
graphObjectManager.loadMatching(Chunk::class.java, "how do I define an action", 5)   // full text
graphObjectManager.loadNearest(Chunk::class.java, queryEmbedding, 5)                  // vector
```

| workload | what it is for |
|---|---|
| full text, a rare term: "MCP server", top 5 | words few chunks hold (52 and 35 of the 1,583): the case an index answers best |
| full text, common short words: "how do I define an action", top 5 | a question as a person types it: words many chunks hold ("an" is in 608), three of them under three letters |
| vector, a chunk's own embedding: nearest 5 by cosine | the nearest is the chunk itself, so a search that misses it is wrong |
| vector, an embedding near a chunk's: nearest 5 by cosine | a chunk's embedding moved by a random vector half its length (a cosine of about 0.9), as a question is near the passage that answers it and not on it |

No embedding model is run: the query vectors are the chunks' own, picked by a fixed seed, so call number 17 asks every
engine the same. Each search returns the chunks whole, embedding and all, as Guide's does.

**What each engine does with them.** Drivine makes each index from the annotations and writes each search:

| | full-text index | full-text search | vector index |
|---|---|---|---|
| Neo4j | Lucene | `db.index.fulltext.queryNodes`, scored by Lucene's BM25 | HNSW |
| FalkorDB | its own (RediSearch) | `db.idx.fulltext.queryNodes` | HNSW |
| Oracle | none | BM25 in SQL, over every chunk | neighbour partitions (IVF) |
| Postgres | trigrams (`pg_trgm`, GIN) | BM25 in SQL, over the chunks the trigram index finds | HNSW (pgvector) |

Postgres and Oracle have no Lucene, so Drivine scores a text by BM25 itself, in SQL, the way Lucene does: each word's
count in the text, weighed by how rare the word is among the chunks and against the text's length
([`Bm25.kt`](https://github.com/liberation-data/drivine4j/blob/main/src/main/kotlin/org/drivine/backend/sql/Bm25.kt)).
On Postgres the chunks scored are first narrowed to those whose text holds the words of the query, through the trigram
index. That is where the second query should hurt: a word under three letters ("I", "an", "do") has no trigram to look
for, so nothing is narrowed and every chunk is scored, one regular expression for each word of the query, and the
counts of the label are worked out again for each search. Oracle is given no full-text index, so that the two SQL
stores are scored alike; with `GBENCH_ORACLE_TEXT=1` it gets one, and Drivine then searches through Oracle Text where
the database has it, scored as Oracle Text scores.

**How fast.** Milliseconds a search, p50 and in brackets p95, over 1,583 chunks:

| workload | Neo4j | FalkorDB | Oracle | Oracle Text | Postgres |
|---|---:|---:|---:|---:|---:|
| full text: "MCP server" | 6.3 (10.8) | 2.1 (5.5) | 565 (570) | 7.2 (8.8) | 38 (42) |
| full text: "how do I define an action" | 5.4 (6.8) | 0.4 (0.5)\* | 4,232 (4,242) | 27 (30) | 459 (470) |
| vector: a chunk's own embedding | 4.9 (6.1) | 1.5 (1.8) | 3.2 (4.0) | 3.0 (3.4) | 3.1 (5.3) |
| vector: an embedding near a chunk's | 3.3 (4.0) | 1.8 (3.3) | 3.3 (9.0) | 3.1 (7.5) | 2.5 (2.9) |

\* FalkorDB found nothing for this query (see below), so it had nothing to score.

Saving the 1,583 chunks took 1.0 to 1.6 s on every engine, and indexing them 0.1 to 1.6 s (Oracle Text 2.9 s).

- **Vector search is no problem anywhere.** All five answer in 1.5 to 5 ms at p50. Postgres's HNSW is as quick as
  Neo4j's.
- **Full text is where the SQL stores fall behind.** Postgres takes 38 ms for the rare words, 6 times Neo4j, and
  459 ms for the common ones, 85 times Neo4j: Guide's 41 ms and 487 ms, measured again. The second query is slow for
  the reason given above: its short words have no trigrams, so every chunk is scored, by one regular expression a
  word. Oracle, with no full-text index, scores every chunk for both queries, and takes 0.6 s and 4.2 s.
- **Oracle Text is fast,** 7 and 27 ms, but it ranks by its own scoring, not BM25 (below).

**Whether they agree.** Each search keeps the ids it found, best first, for its first five calls. The report sets each
engine's beside Neo4j's: how many it found a call, how many of those Neo4j found too, and in how many of the five calls
the order was Neo4j's exactly; and Oracle's beside Postgres's.

| workload | FalkorDB | Oracle | Oracle Text | Postgres | Oracle and Postgres |
|---|---|---|---|---|---|
| full text: "MCP server" | 1 of Neo4j's 5 | 4 of 5 | 0 of 5 | 4 of 5 | the same 5, in the same order |
| full text: "how do I define an action" | **found none** | 2 of 5 | 0 of 5 | 2 of 5 | the same 5, in the same order |
| vector: a chunk's own embedding | 5 of 5, in order | 4.8 of 5 (in order 4 of 5 calls) | 4.6 of 5 | 5 of 5, in order | 4.8 of 5 |
| vector: an embedding near a chunk's | 5 of 5, in order | 4.6 of 5 (in order 3 of 5 calls) | 4.8 of 5 | 5 of 5, in order | 4.6 of 5 |

- **Oracle and Postgres rank full text exactly alike,** as they share Drivine's BM25, but not as Neo4j does. Drivine
  weighs a text's length in characters and Lucene in words: scored by hand over the same chunks, BM25 with lengths in
  words gives Neo4j's top five for "MCP server" in Neo4j's order, and with lengths in characters gives the SQL stores'.
  For the second query Neo4j differs from both by more. Reported as
  [drivine4j#665](https://github.com/liberation-data/drivine4j/issues/665).
- **FalkorDB finds nothing for "how do I define an action".** Drivine passes it the query as it is, and FalkorDB takes a
  query as all of its words, not any of them; no chunk holds all six. Reported as
  [drivine4j#666](https://github.com/liberation-data/drivine4j/issues/666).
- **Oracle Text shares none of Neo4j's five** for either query: it finds five, by a score of its own.
- **The vector searches agree,** but for Oracle's index, which is approximate in a different way from HNSW (neighbour
  partitions) and now and then puts another chunk in the five.

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

- **One run of each, on a quiet machine.** Nothing else ran on the test machine while the engines did. Neo4j and
  Postgres were run twice at scale 1 through 0.6.1, in two of the runs on this page, and their medians differ from one to the other by
  up to 1.3 times. Differences of 2 times and more are the ones to believe.
- **Oracle Free is not Oracle.** It is capped at 2 CPU threads and 2 GB of memory. The same
  statements on a licensed edition with real memory would behave differently, above all under
  concurrency.
- **Small machines, a warm cache.** At scale 1 the graph fits in memory on all four. Only Neo4j and Postgres were
  run on graphs that do not, [up to 30 times the size](#bigger-graphs-neo4j-and-postgres-at-up-to-30-times-the-size),
  and still on 2 CPUs and 4 GB: a server sized for its graph would move those numbers.
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
container and prints the tables. Results are written to `results/<engine>-sf<scale>.json`, or to the folder named
by `GBENCH_RESULTS`, so that runs of two Drivine versions, or on two machines, are kept apart. `DRIVINE_VERSION`
builds against another release of Drivine than `build.gradle.kts` names. The runs behind this page were these, with
the searches' seed in `seed/guide` (below):

```bash
GBENCH_RESULTS=results/m1-0.6.1 GBENCH_SEED=seed/guide GBENCH_PHASES=load,read,traverse,concurrent,write,search,hops ./run.sh 1
GBENCH_RESULTS=results/m1-0.4.1 DRIVINE_VERSION=0.4.1 GBENCH_PHASES=load,read,traverse,concurrent,write,hops ./run.sh 1
for sf in 1 3 10 30; do
    GBENCH_RESULTS=results/m1-scaling GBENCH_PHASES=load,read,traverse,concurrent,write,hops ./run.sh $sf neo4j postgres
done
```

At 30 times the run of both engines took over 7 hours, most of it Postgres's load. To print the tables again:

```bash
GBENCH_RESULTS=results/m1-0.6.1 build/install/graph-bench/bin/graph-bench report --sf 1
```

`build.gradle.kts` names the released Drivine the harness builds against. To build against a
checkout of Drivine's source instead, for a fix that is not released yet:

```bash
DRIVINE_SOURCE=../drivine4j ./run.sh 1
```

To run against a database of your own, set `BENCH_NEO4J_HOST`, `BENCH_NEO4J_PORT`,
`BENCH_NEO4J_PASSWORD`, and the like for `FALKORDB`, `ORACLE` and `POSTGRES`, and call the harness directly:

```bash
build/install/graph-bench/bin/graph-bench run --engine neo4j --sf 1 --phases load,read,traverse,concurrent,write,search
```

The hop by hop measurement is a phase of its own, `--phases hops`, and writes each call to
`results/hops-<engine>-sf<scale>.csv`.

The searches need Guide's content seed, which is not kept here. Fetch it into a folder of its own and name the folder
in `GBENCH_SEED`; without it the searches are left out. `GBENCH_PHASES` runs only the phases named:

```bash
gh run download 42417580385 -R embabel/guide -n seed-postgres -D seed/guide   # nodes.tsv, edges.tsv, apply.sql
GBENCH_SEED=seed/guide ./run.sh 1                         # everything, the searches too
GBENCH_SEED=seed/guide GBENCH_PHASES=search ./run.sh 1    # only the searches
```

`seed/` is ignored by git but sits inside the harness's folder, so a copy of that folder (as a run on another machine is given) carries the seed with it.
Only the chunks of `nodes.tsv` are read, and nothing in the seed is run: `apply.sql` is Guide's, for loading its own
database.

The run named above is the seed the numbers were measured on, and GitHub keeps its files only until 7 December 2026.
After that, take `seed-postgres` from a later run of Guide's "Export Seed Database" workflow
(`gh run list -R embabel/guide -w export-seed.yml`): it is a later ingest of the docs, so its chunks, and the numbers
and rankings over them, will not be the same.

### Where things are

| | |
|---|---|
| [`Model.kt`](src/main/kotlin/bench/Model.kt) | the fragments and views, and the chunk the searches find |
| [`Data.kt`](src/main/kotlin/bench/Data.kt) | the generator, and why FOLLOWS never runs both ways |
| [`Load.kt`](src/main/kotlin/bench/Load.kt) | the indexes, a loader for Cypher, one for Oracle's tables and one for Postgres's, and the chunks' save and search indexes |
| [`Workloads.kt`](src/main/kotlin/bench/Workloads.kt) | every workload, with the Cypher, SQL/PGQ and Postgres SQL side by side |
| [`Seed.kt`](src/main/kotlin/bench/Seed.kt) | Guide's chunks, read from its content seed, for the searches |
| [`Main.kt`](src/main/kotlin/bench/Main.kt) | timing, the digest that checks the engines agree, the report |

Two things about the data are deliberate. FOLLOWS never runs both ways between two people, and
DEPENDS_ON only points at a repo made earlier. Cypher refuses a walk that uses a relationship twice
and SQL/PGQ and Postgres's joins allow it, so without those two rules the engines would be counting
different things. They rule out two-person cycles and not longer ones, which is why Neo4j counts a
few four-hop paths fewer: see [above](#does-oracle-miss-index-free-adjacency).

## License

Apache 2.0.
