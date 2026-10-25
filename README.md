# Graph Bench

**Using Drivine to compare the performance of Neo4j, FalkorDB, Oracle and Postgres.**

*15 October 2026. Postgres added 22 October 2026. The walk at six sizes, and SQLite, added 25 October 2026.*

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
  Run again at six sizes, out to 33.6 million nodes and well out of the cache, the walk itself
  never cost Postgres more than Neo4j; reading the nodes did, past a few hundred of them in memory and
  about 30 out of it. [See when index-free adjacency pays.](#when-does-index-free-adjacency-pay)
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
is Neo4j Community, which does not have the faster runtime of the Enterprise edition. The next
section takes up the first two: the same walk at six sizes, in and out of the cache, and reading
the nodes as it goes.

## When does index-free adjacency pay?

*25 October 2026.*

The section above walks one graph, of 1.1 million nodes. The case for index-free adjacency is about
size: following a pointer costs the same however big the graph is, and a probe of a B-tree costs
more as the tree grows, O(1) against O(log n). It is also about memory: a graph engine is meant to
win most clearly once the graph no longer fits in its cache. One size says nothing about either. So
we ran the same walk at six sizes, from 112,150 nodes to 33.6 million, the largest well out of every
engine's cache, and added two versions of it that read something of the node each path reaches.

**The short of it.** Walking relationships, the SQL stores never paid for having no index-free
adjacency, in memory or out of it: out of it, Postgres walked six times quicker than Neo4j. What
they pay for is reading the nodes at the end of the walk. In memory Neo4j is the quicker once a
query reads more than about 200 to 900 nodes, the fewer the larger the graph; out of memory, once it
reads more than about 30.

### What was run

**The walk.** As above: from each of 150 people, follow FOLLOWS out for one to four hops. Three
versions of it, each a statement of its own:

- *count* counts the paths, as above. Nothing is read of any node but the person it starts from.
- *reach* sums the internal ids of the nodes the paths end at: it has to know which node each
  path reaches, and reads nothing of it.
- *read* sums the `karma` of the person each path ends at: one node read for each path.

`reach` is there because of how the SQL stores are indexed. Drivine's index over the relationships
is over `(src, type)` and does not hold `dst`. A hop that goes on from a node has to read the
relationship's row to learn where it goes, and only the last hop of a count is answered from the
index alone. On Neo4j a count reads each relationship already, and `reach` costs nothing more.

**The sizes.** The graph above at scale factors 0.1, 0.3, 1, 3, 10 and 30. The generator gives each
person the same number of people to follow at every size ([`Data.kt`](src/main/kotlin/bench/Data.kt):
between 1 and 61, drawn the same way whatever the size), and that is what the walk follows.
Measured, a person follows 15.6 to 15.8 others at every size, the median 8, the most 60. What does
grow is how many follow the most followed: whom a person follows is skewed to the first people made,
so the most followed person has 1,164 followers at 0.1 and 26,252 at 30, as the square root of the
graph. The walk never goes that way. The calls walk the same number of paths at every size, 7.5 to
8.8 million four-hop paths over the 150 people, the largest call 230,000 to 275,000.

**The engines.** Neo4j 2026.07.1 Community and Postgres 17, in the containers and with the limits
above: 2 CPUs, 4 GB, a 2 GB page cache and a 1 GB heap for Neo4j, 1 GB of `shared_buffers` for
Postgres. FalkorDB 6.0.0 too, up to scale factor 3: at 10 its graph would need about 8 GB of the 4.
And SQLite 3.53, in which Drivine keeps a graph in the same two tables with the same indexes, read
with the same joins as Postgres. SQLite is in Drivine's main branch and not yet released; every
engine here was run through that branch at
[b3da3d6](https://github.com/liberation-data/drivine4j/commit/b3da3d6). SQLite has no server: it is
a file opened in the harness's own process, on the host and not in a container, with a page cache of
1 GB of its own and the host's 32 GB of memory behind it. Its numbers have no network, no protocol
and no container in them. Oracle was not run again: its loads are the slowest of the four, and at
scale factor 30 Postgres's alone took five and a half hours.

**How it was timed.** The server is restarted after the load (FalkorDB's is not: it keeps its graph only in memory), and every call is made once to warm
it. Then three rounds: in each, every person, each number of hops and each version, called three
times. A call's time is the quickest of its three, as above, and the first of the three is kept as
well, for what a call costs when it does not find everything in the cache. A line is fitted to each
engine's calls at each size: a fixed cost for a call, and a cost for each path. With calls from 1
path to 275,000, and noise that grows with the call, it is fitted to each call's error relative to
its time, so that the small calls set the fixed cost and the large ones the cost of a path, and one
slow call moves neither much. The ranges in brackets are 95% intervals from resampling the 150
people. They say how well one run fixes the line, not how much the next run would move it: for that,
see the cautions at the end. The fit is [`adjacency.py`](adjacency.py), and every call is in
[`results/adjacency/`](results/adjacency).

All four engines count the same paths and sum the same karma on every call, but for Neo4j's few
four-hop paths that come back over their first relationship, as above.

### How big the stores are, and their caches

On disk, in **gigabytes (GB)**, after the load. For Neo4j, its relationship and node stores and, in
brackets, all of it; for Postgres, the relationships' table and the index a walk uses and, in
brackets, the whole database:

| scale | nodes | relationships | Neo4j (GB) | Postgres (GB) | SQLite (GB) |
|---:|---:|---:|---:|---:|---:|
| 0.1 | 112,150 | 445,374 | 0.02 (0.05) | 0.05 (0.11) | 0.06 |
| 0.3 | 336,450 | 1,338,251 | 0.07 (0.19) | 0.14 (0.30) | 0.17 |
| 1 | 1,121,500 | 4,482,439 | 0.18 (0.58) | 0.46 (0.99) | 0.60 |
| 3 | 3,363,500 | 13,448,688 | 0.54 (1.62) | 1.35 (2.95) | 1.82 |
| 10 | 11,210,500 | 44,818,559 | 1.88 (5.61) | 4.49 (9.86) | 6.18 |
| 30 | 33,630,500 | 134,509,246 | 5.20 (16.52) | 13.49 (29.69) | 18.86 |

**Neo4j** walks its relationship and node stores, and for `read` its property store too. They fit in
its 2 GB page cache up to scale factor 3. At 10 the two stores come to 1.9 GB, and karma is in a
property store of 1.1 GB more: a call's first time is about half as much again as its quickest. At
30 they do not fit at all. The container read 354 GB from disk in the adjacency phase, and in every
round, not only the first, half of the counting calls took more than five times as long the first
time as the quickest.

**Postgres** walks the FOLLOWS rows of the relationships' table, the part of their index over people,
and, to read, the people. Its 1 GB buffer cache holds all of that up to scale factor 3: no call there
read a block from outside it. At 10 it does not: the calls of a version found up to 7% of the blocks they needed
outside the cache, and the container read 2.1 GB from disk. At 30, 8% of a count's blocks, 47% of a
reach's and 16% of a read's, and the container read 427 GB from disk.

**SQLite** is cached by the host, whose 32 GB is shared with everything else running on it. At 30
its file is 19 GB, and its first calls cost twice its quickest, so some of it was likely read from
disk; that was not measured.

The B-tree a walk goes down, over the relationships by `src`, is three levels deep on Postgres from
0.1 to 3 and four at 10 and 30; on SQLite, three up to 1 and four from 3 to 30.

### What a path costs

The quickest of three calls, in **microseconds (µs) for each path**, and the fixed cost of a call in
**milliseconds (ms)**:

| scale | Neo4j (µs) | Postgres (µs) | SQLite (µs) | FalkorDB (µs) | Neo4j fixed (ms) | Postgres fixed (ms) | SQLite fixed (ms) |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 0.1 | 0.15 (0.14–0.15) | 0.120 (0.119–0.121) | 0.049 | **0.025** | 0.69 | 0.16 | **0.026** |
| 0.3 | 0.28 (0.27–0.29) | 0.128 (0.126–0.129) | 0.053 | **0.026** | 0.91 | 0.17 | **0.025** |
| 1 | 0.25 (0.25–0.26) | 0.139 (0.138–0.142) | 0.055 | **0.027** | 0.68 | 0.17 | **0.025** |
| 3 | 0.28 (0.27–0.29) | 0.149 (0.147–0.152) | 0.059 | **0.027** | 0.63 | 0.17 | **0.024** |
| 10 | 0.29 (0.28–0.30) | 0.176 (0.174–0.179) | **0.061** | - | 0.57 | 0.16 | **0.025** |
| 30 | 0.38 (0.37–0.40) | 0.243 (0.232–0.252) | **0.178** | - | 0.62 | 0.18 | **0.060** |

The same, from the first of each call's three, at the two sizes that do not fit:

| scale | Neo4j (µs) | Postgres (µs) | SQLite (µs) |
|---:|---:|---:|---:|
| 10 | 0.45 (0.44–0.46) | 0.32 (0.31–0.32) | **0.10** |
| 30 | 12.8 (12.5–13.1) | 2.07 (1.91–2.14) | **0.34** |

### What reading a node costs

What reading the node each path reaches adds to a path, in **microseconds (µs)**: the slope of `read`
less the slope of `count`. For the SQL stores it is split in two: learning which node the path
reaches (`reach`, the relationship's row), and then reading it (the node's key, its row and its JSON).
The quickest of three calls:

| scale | Neo4j (µs) | Postgres: reach + node (µs) | SQLite: reach + node (µs) | FalkorDB (µs) |
|---:|---:|---:|---:|---:|
| 0.1 | 0.20 (0.19–0.21) | 0.78 = 0.03 + 0.75 | 0.50 = 0.05 + 0.45 | **0.02** |
| 0.3 | 0.25 (0.23–0.26) | 1.07 = 0.07 + 1.01 | 0.56 = 0.06 + 0.50 | **0.02** |
| 1 | 0.34 (0.32–0.34) | 1.10 = 0.09 + 1.01 | 0.58 = 0.06 + 0.52 | **0.03** |
| 3 | 0.45 (0.42–0.48) | 1.47 = 0.15 + 1.32 | 0.65 = 0.06 + 0.59 | **0.05** |
| 10 | **0.54** (0.52–0.57) | 1.62 = 0.19 + 1.44 | 0.69 = 0.06 + 0.63 | - |
| 30 | **0.58** (0.56–0.61) | 3.06 = 0.44 + 2.62 | 1.07 = 0.08 + 0.99 | - |

And from the first of each call's three:

| scale | Neo4j (µs) | Postgres: reach + node (µs) | SQLite: reach + node (µs) |
|---:|---:|---:|---:|
| 10 | **0.82** (0.80–0.84) | 2.85 = 0.45 + 2.41 | 1.05 = 0.09 + 0.96 |
| 30 | 4.2 (4.0–4.5) | 34.9 = 11.4 + 23.5 | **2.9** = 0.1 + 2.8 |

The SQL stores' intervals are within 7% of the figure in memory and within 21% out of it, and are left
out. `python3 adjacency.py` prints them all.

### Reading it

**In memory, the B-tree never costs the SQL stores the walk.** From 112,150 nodes to 11.2 million,
Postgres's cost for each path goes from 0.120 to 0.176 µs, and Neo4j's from 0.15 to 0.29. Postgres
is the cheaper at every size, by about half from 1 up (at 0.1 the second run had the two within a
tenth of each other), and SQLite cheaper again, at a fifth of Neo4j's. Neither line is flat. Postgres's grows by less than a tenth for each tripling of the graph
up to 3, with its B-tree the same three levels deep, and by a fifth from 3 to 10, where the tree
gains a level and the walk leaves the buffer cache. Neo4j's grows too. In this run it jumps from 0.1
to 0.3 and is then about level; in the second, quieter run (see the cautions) it grows steadily, from
0.12 to 0.22 µs between 0.1 and 3, more than Postgres's 0.109 to 0.144 over the same sizes. Neither
the pointer nor the B-tree is flat in practice.

That is what a B-tree should do. A walk goes down the tree once for each node it goes on from, and
then reads that node's relationships along a leaf, side by side. A person follows 16 people on
average, so one descent is shared by 16 paths, and a level more in the tree adds a sixteenth of a
descent to each path. On Postgres an entry of the index takes 33 bytes, so a page holds about 250 of
them: the tree is four levels deep from about 15 million relationships, and would be five from about
4 billion. Most of the growth that is seen does not come from the depth, which does not change from
0.1 to 3. It comes from the walk touching more memory as the graph grows, which costs Neo4j too.
Counting paths, the lines do not cross at any size here, and nothing in their slopes says they would
in memory at any size a laptop could hold.

**Out of memory, the B-tree is the better layout for the walk.** At scale factor 30 both servers read
from disk, and Neo4j's first calls cost 12.8 µs a path, nearly 30 times what they cost at 10, where
Postgres's cost 2.07 µs, about 7 times. The median four-hop count took Neo4j 358 ms the first time
and Postgres 59. The index keeps a node's relationships together, in the leaf that one descent
finds, and a count reads a relationship's row only for the hops that go on. Neo4j keeps each node's
relationships on a chain through its relationship store, and a person with fewer than 50 keeps them
all on the one chain: those it follows, those it is followed by, its comments and its issues, each
written when its type was loaded, far apart in the store. Following a pointer is O(1), but out of
memory each one can be a read from disk, and a walk follows many.

**Reading the nodes is where the crossover is.** To read a node, Postgres reads the relationship's row
to learn which node it is, goes down a second B-tree, over the nodes' keys, to the node's row, and
opens its JSON: 0.78 to 1.62 µs a node in memory, against 0.20 to 0.54 for Neo4j, whose relationship
record says where its node is. Here a descent is shared by nothing: there is one for each node read.
So a query on Postgres starts 0.4 to 0.7 ms ahead, the difference in fixed costs, and gives it back at
about 1 µs for every node it reads. Out of memory each of those is a read from disk, and the gap
grows to 35 µs a node against 4. Neo4j is the quicker when a query reads more nodes than:

| scale | counting paths | Postgres, reading the node of each, quickest call | Postgres, first call | SQLite, quickest call |
|---:|---:|---:|---:|---:|
| 0.1 | never | 890 (860–910) | 1,010 (980–1,050) | 3,200 (3,100–3,300) |
| 0.3 | never | 850 (820–880) | 840 (800–880) | 8,800 (7,600–10,900) |
| 1 | never | 780 (750–800) | 800 (780–830) | 12,500 (10,500–14,400) |
| 3 | never | 400 (380–420) | 330 (310–340) | never in this run |
| 10 | never | 450 (440–460) | 230 (220–240) | never |
| 30 | never | 180 (170–190) | **31** (25–38) | 2,000 (1,800–2,100) |

SQLite's line for reading a node is as steep as Neo4j's or less from 3 to 10, and starts 0.6 ms
lower, so the two never meet there. At 30 they do, at about 2,000 nodes, though that run was the
noisiest of all.

### Is it that the SQL stores are written in C?

In part, and not the part one would guess. The fixed cost of a call is not the walk: it is the round
trip, the protocol, and finding or making the plan. Postgres's is 0.16 to 0.18 ms at every size, and
Neo4j's 0.57 to 0.91 ms, over Bolt, the driver's session, a transaction and the Cypher planner's
cache. SQLite's, 0.025 ms (0.06 at 30), has no network and no server at all. FalkorDB's, 0.22 to 0.25 ms, is a
server written in C and Rust, behind Redis's protocol, close to Postgres's. Most of the gap between
Neo4j and Postgres on small queries is this, and it is not the language of the engine.

The cost of a path is the walk, and there the language may tell. Postgres, through a B-tree, costs
half of what Neo4j does through its pointers, and SQLite, through a B-tree too, a fifth. But Neo4j
Community runs a walk in its slotted runtime, checks at each hop that no relationship is used twice
in a path, and reads past every relationship of a person on its chain to find those it follows. The
Enterprise edition's pipelined runtime is quicker, and was not run.

FalkorDB is the counterpoint. It is native, written in C and Rust like a relational engine, and it
keeps no chain of pointers: a hop is a product of sparse matrices. It costs 0.025 µs a path, a tenth
of Neo4j and a fifth of Postgres, at every size it ran, and reading a node adds 0.02 to 0.05 µs. That
is not index-free adjacency winning, nor C. It is a different way of keeping a graph, built to walk
many paths at a time.

### Cautions

- **Two runs at most, on a busy host.** Other work was running throughout, with a load average of 5
  to 36 on 12 cores, recorded before and after each engine's walk in `results/adjacency/env-*.txt`.
  Scale factors 0.1 to 3 were run a second time, the same night, in `results/adjacency/repeat/`.
  The second run was quieter and quicker: Neo4j's cost for each path 0.12 to 0.22 µs where the first
  had 0.15 to 0.28, Postgres's 0.109 to 0.144 where it had 0.120 to 0.149, and the number of nodes
  past which Neo4j reads quicker than Postgres 440 to 900 where it was 400 to 890. Neo4j moved the
  most, its first run nearly twice its second at 0.3; the shape did not change. SQLite's crossover is the least steady, 2,200 to
  3,900 nodes in the second run where the first had 3,200 to never, because its line for reading and
  Neo4j's are nearly parallel. The intervals above do not include any of this.
- **SQLite has advantages no server has.** It runs in the harness's process with no network, and its
  file is cached by the host's 32 GB, not a container's 4. Its numbers say what a walk costs SQLite,
  not what an application would see from SQLite as from a server. Its run at 30 was the noisiest of
  all, the load average rising from 8 to 21.
- **The first of three calls is not a cold call.** It is the first that round, after the warm pass and
  the rounds before. Out of memory it is a mix of reads from disk and from the cache, and the mix
  depends on how much of the walk the cache holds. Its numbers say which way and roughly how far, not
  what one call on a cold server costs.
- **This is one walk, of one relationship type.** Paths over relationships of many types, or out of
  nodes with thousands of relationships, would weigh the costs differently. Neo4j groups the
  relationships of a node with 50 or more by type, and a walk out of such nodes would read less.
- **The node read is Drivine's layout as much as the store's.** On the SQL stores a node's properties
  are a JSON document, `jsonb` on Postgres and text on SQLite, opened for each read, and the index
  over the relationships does not hold `dst`. An index that held it would take away the `reach` part
  of a read, 11.4 of the 34.9 µs a node out of memory on Postgres; a column for karma, much of the
  rest. Neither was tried.
- **The cache is small on purpose.** It is the cache, not the graph, that decides which side of the
  line a store is on. A Neo4j with 32 GB of page cache would hold scale factor 30 as easily as this
  one held 3, and so would a Postgres.
- **A laptop's disk.** Out of memory every engine was reading from the host's SSD through Docker's
  virtual machine. A server's disks, or a cloud volume, would cost each read differently.

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
- **Small machines, a warm cache.** At this size the graph fits in memory on all four. Only the
  walk was run on graphs that do not, [below](#when-does-index-free-adjacency-pay).
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

The walk at several sizes is `adjacency.sh`, which for each engine in turn loads the graph, restarts
the server, writes down the store's size and runs the `adjacency` phase, recording the host's load
before and after. SQLite is one of its engines, and needs Drivine's main branch until the next
release. Everything goes to `results/adjacency/`, and `adjacency.py` fits the lines:

```bash
DRIVINE_SOURCE=../drivine4j ./adjacency.sh 3                 # Neo4j, Postgres and SQLite
DRIVINE_SOURCE=../drivine4j ./adjacency.sh 1 neo4j falkordb
python3 adjacency.py                                         # numpy; prints the tables
```

`BENCH_CONTAINER_PREFIX` and the `BENCH_*_PORT` variables move the containers off `run.sh`'s names
and ports, for a host where those are taken.

### Where things are

| | |
|---|---|
| [`Model.kt`](src/main/kotlin/bench/Model.kt) | the fragments and views |
| [`Data.kt`](src/main/kotlin/bench/Data.kt) | the generator, and why FOLLOWS never runs both ways |
| [`Load.kt`](src/main/kotlin/bench/Load.kt) | the indexes, and a loader for Cypher, one for Oracle's tables, one for Postgres's and one for SQLite's |
| [`Workloads.kt`](src/main/kotlin/bench/Workloads.kt) | every workload, with the Cypher, SQL/PGQ and Postgres SQL side by side |
| [`Adjacency.kt`](src/main/kotlin/bench/Adjacency.kt) | the walk at several sizes: counting, reaching and reading, on every engine |
| [`adjacency.py`](adjacency.py) | the lines fitted to the walk's calls, and where they cross |
| [`Main.kt`](src/main/kotlin/bench/Main.kt) | timing, the digest that checks the engines agree, the report |

Two things about the data are deliberate. FOLLOWS never runs both ways between two people, and
DEPENDS_ON only points at a repo made earlier. Cypher refuses a walk that uses a relationship twice
and SQL/PGQ and Postgres's joins allow it, so without those two rules the engines would be counting
different things. They rule out two-person cycles and not longer ones, which is why Neo4j counts a
few four-hop paths fewer: see [above](#does-oracle-miss-index-free-adjacency).

## License

Apache 2.0.
