"""
Fits the calls of the adjacency phase: for each engine and scale factor, the fixed cost of a call, the
cost of each path walked, and the cost of reading the node at the end of each path.

    python3 adjacency.py                 # every results/adjacency/hops-*.csv
    python3 adjacency.py --json fits.json
    python3 adjacency.py --dir results/adjacency/repeat   # the second run

A call's time is taken to be  fixed + per_path * paths  when it counts paths, a line of its own
when it also finds which node each path reaches (`reach`), and another when it reads that node
(`read`). The differences of their slopes are what each step further costs a path: `per_reach`
and `per_node`, which add up to `per_read`. The calls span
1 path to hundreds of thousands, and their noise grows with their size, so the line is fitted to
minimise the sum of each call's absolute error relative to its time (least absolute relative
deviation, by iteratively reweighted least squares): the small calls set the fixed cost, the large
ones the cost of a path, and one slow call moves neither much.

The intervals are 95% bootstrap intervals over the people walked from: 1,000 resamples of the 150
people, each taking all of a person's calls, so that a person whose walks are unusual counts once.
Each call is the quickest of three; the first of the three is fitted too, as `first`, for the calls
that found what they needed out of the cache.
"""
import argparse
import csv
import glob
import json
import os
import re
from collections import defaultdict

import numpy as np

SEED = 7


def fit(x, y, iterations=60):
    """a, b minimising sum |y - a - b x| / y."""
    x = np.asarray(x, float)
    y = np.asarray(y, float)
    design = np.column_stack([np.ones_like(x), x])
    w = 1 / y ** 2
    coef = np.linalg.lstsq(design * np.sqrt(w)[:, None], y * np.sqrt(w), rcond=None)[0]
    for _ in range(iterations):
        r = np.abs(y - design @ coef) / y
        w = 1 / (y ** 2 * np.maximum(r, 1e-4))
        new = np.linalg.lstsq(design * np.sqrt(w)[:, None], y * np.sqrt(w), rcond=None)[0]
        if np.allclose(new, coef, rtol=1e-7, atol=1e-9):
            break
        coef = new
    return coef


def read(path):
    rows = list(csv.DictReader(open(path)))
    paths = {(r["hops"], r["person"]): int(r["value"]) for r in rows if r["variant"] == "count"}
    out = defaultdict(list)  # variant -> [(person, paths, min_ms, first_ms, round)]
    for r in rows:
        out[r["variant"]].append((r["person"], paths[(r["hops"], r["person"])], float(r["min_ms"]), float(r["first_ms"]), int(r["round"])))
    return out


def fits(calls, column, people, rng, resamples):
    """Fixed and per-path cost of the count calls, per-path of the read calls, their difference; with intervals."""
    by_person = {v: defaultdict(list) for v in calls}
    for v, rows in calls.items():
        for person, p, mn, first, _ in rows:
            by_person[v][person].append((p, mn if column == "min" else first))

    def one(chosen):
        out = {}
        for v in calls:
            pts = [pt for person in chosen for pt in by_person[v][person]]
            a, b = fit([p for p, _ in pts], [ms for _, ms in pts])
            out[v] = (a, b)
        return out

    point = one(people)
    boot = [one(rng.choice(people, size=len(people), replace=True)) for _ in range(resamples)]

    def interval(get):
        values = np.array([get(s) for s in boot])
        return [float(np.percentile(values, 2.5)), float(np.percentile(values, 97.5))]

    result = {
        "fixed_ms": [float(point["count"][0])] + interval(lambda s: s["count"][0]),
        "per_path_us": [float(point["count"][1] * 1e3)] + interval(lambda s: s["count"][1] * 1e3),
    }
    # What each step further costs a path: knowing the node it reaches, then reading that node.
    steps = [("reach", "count", "per_reach_us"), ("read", "reach", "per_node_us"), ("read", "count", "per_read_us")]
    for upper, lower, name in steps:
        if upper in calls and lower in calls:
            result[name] = [float((point[upper][1] - point[lower][1]) * 1e3)] + interval(lambda s: (s[upper][1] - s[lower][1]) * 1e3)
    for v in calls:
        result[v + "_line"] = {"fixed_ms": float(point[v][0]), "per_path_us": float(point[v][1] * 1e3)}
    return result, point, boot


def crossover(sql, graph):
    """
    How many paths a call must walk for [graph]'s line to drop below [sql]'s: where
    a_s + b_s p = a_g + b_g p. Infinite where the SQL store's line is the lower at every size, or
    becomes the lower as calls grow, and zero where it is the higher at every size.
    """
    (a_s, b_s), (a_g, b_g) = sql, graph
    if b_s <= b_g:
        return 0.0 if b_s == b_g and a_s > a_g else float("inf")
    return max(0.0, (a_g - a_s) / (b_s - b_g))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dir", default="results/adjacency")
    parser.add_argument("--json")
    parser.add_argument("--resamples", type=int, default=1000)
    args = parser.parse_args()
    found = []
    lines = {}
    for path in sorted(glob.glob(os.path.join(args.dir, "hops-*-sf*.csv"))):
        engine, sf = re.match(r"hops-(\w+)-sf([\d.]+)\.csv", os.path.basename(path)).groups()
        calls = read(path)
        people = sorted({row[0] for row in calls["count"]})
        rng = np.random.default_rng(SEED)
        entry = {"engine": engine, "sf": float(sf), "calls": sum(len(v) for v in calls.values()),
                 "largest_paths": max(p for _, p, *_ in calls["count"]),
                 "min": None, "first": None}
        for column in ("min", "first"):
            entry[column], point, boot = fits(calls, column, people, rng, args.resamples)
            lines[(engine, float(sf), column)] = (point, boot)
        # Each round on its own, for how much the line moves from one pass over the people to the next.
        rounds = sorted({row[4] for row in calls["count"]})
        entry["per_round"] = []
        for k in rounds:
            one = {v: [row for row in rows if row[4] == k] for v, rows in calls.items()}
            a, b = fit([p for _, p, *_ in one["count"]], [row[2] for row in one["count"]])
            r = {"round": k, "fixed_ms": float(a), "per_path_us": float(b * 1e3)}
            if "read" in one:
                _, rb = fit([p for _, p, *_ in one["read"]], [row[2] for row in one["read"]])
                r["per_read_us"] = float((rb - b) * 1e3)
            entry["per_round"].append(r)
        found.append(entry)

    def cell(v):
        return "%.3g [%.3g, %.3g]" % tuple(v)

    for column, title in (("min", "the quickest of each call's three"), ("first", "the first of each call's three")):
        print("\n%s\n" % title.capitalize())
        print("| engine | sf | fixed (ms) | per path (µs) | + reach (µs) | + node (µs) | per read (µs) | rounds: per path (µs) |")
        print("|---|---:|---:|---:|---:|---:|---:|---|")
        for e in sorted(found, key=lambda e: (e["engine"], e["sf"])):
            m = e[column]
            rounds = ", ".join("%.3g" % r["per_path_us"] for r in e["per_round"]) if column == "min" else ""
            extra = [cell(m[k]) if k in m else "-" for k in ("per_reach_us", "per_node_us", "per_read_us")]
            print("| %s | %g | %s | %s | %s | %s |" % (e["engine"], e["sf"], cell(m["fixed_ms"]), cell(m["per_path_us"]), " | ".join(extra), rounds))

    # Where Neo4j's line crosses each other engine's, for counting paths and for reading the node at their end.
    crossings = []
    for column in ("min", "first"):
        print("\nWhere Neo4j's line drops below, from %s calls\n" % ("the quickest" if column == "min" else "the first"))
        print("| engine | sf | counting: Neo4j quicker beyond (paths) | reading: Neo4j quicker beyond (node reads) |")
        print("|---|---:|---:|---:|")
        for (engine, sf, col), (point, boot) in sorted(lines.items()):
            graph = lines.get(("neo4j", sf, col))
            if engine in ("neo4j", "falkordb") or col != column or graph is None:
                continue
            cells = []
            for v in ("count", "read"):
                at = crossover(point[v], graph[0][v])
                # Resample i of each engine drew the same people, as each file's generator starts from SEED
                # and the people are the same: the two lines are set against each other on the same people.
                samples = np.array([crossover(b[v], g[v]) for b, g in zip(boot, graph[1])])
                # Sorted, not np.percentile, which makes nothing of an infinite sample.
                ordered = np.sort(samples)
                low, high = ordered[int(0.025 * (len(ordered) - 1))], ordered[int(0.975 * (len(ordered) - 1))]
                # JSON has no infinity: never is written as null.
                finite = [None if x == float("inf") else float(x) for x in (at, low, high)]
                crossings.append({"engine": engine, "sf": sf, "column": column, "variant": v, "paths": finite[0], "interval": finite[1:]})
                cells.append("%s [%s, %s]" % tuple("never" if x == float("inf") else "%.0f" % x for x in (at, low, high)))
            print("| %s | %g | %s |" % (engine, sf, " | ".join(cells)))
    if args.json:
        with open(args.json, "w") as f:
            json.dump({"fits": found, "crossings": crossings}, f, indent=2)


if __name__ == "__main__":
    main()
