"""
Exact search vs HNSW in pgvector, on synthetic vectors (M4 of the plan).

This measures index behaviour only: speed, recall against the exact answer and the
"filter after the index" problem. Synthetic vectors say nothing about face accuracy.

  pip install psycopg2-binary numpy
  python3 eval/ann_bench.py            # uses the docker compose database on :5434

It works in its own schema "bench" and drops it at the end.
"""
import json
import os
import re
import statistics
import time

import numpy as np
import psycopg2

DSN = os.environ.get("SPOTME_BENCH_DSN", "dbname=spotme user=spotme password=spotme host=localhost port=5434")
EVENTS = 200
FACES_PER_EVENT = 500      # 200 x 500 = 100k vectors
PEOPLE_PER_EVENT = 120
QUERIES = 200
K = 10
DIM = 128
NOISE = 0.06   # per-dimension noise: same-person cosine similarity lands around 0.7, like real face vectors
rng = np.random.default_rng(11)


def unit(v):
    return v / np.linalg.norm(v, axis=-1, keepdims=True)


def vec(v):
    return "[" + ",".join(f"{x:.6f}" for x in v) + "]"


def pct(xs, p):
    return float(np.percentile(xs, p))


def main():
    conn = psycopg2.connect(DSN)
    conn.autocommit = True
    cur = conn.cursor()
    cur.execute("DROP SCHEMA IF EXISTS bench CASCADE; CREATE SCHEMA bench")
    cur.execute("CREATE TABLE bench.faces (id serial PRIMARY KEY, event_id int NOT NULL, embedding vector(128) NOT NULL)")
    cur.execute("CREATE INDEX ON bench.faces (event_id)")

    # each event has its own "people"; a face is that person's direction plus noise
    centroids = unit(rng.normal(size=(EVENTS, PEOPLE_PER_EVENT, DIM)))
    rows = []
    for e in range(EVENTS):
        people = rng.integers(0, PEOPLE_PER_EVENT, FACES_PER_EVENT)
        faces = unit(centroids[e, people] + rng.normal(scale=NOISE, size=(FACES_PER_EVENT, DIM)))
        rows.extend((e, vec(f)) for f in faces)
    t = time.perf_counter()
    args = ",".join(cur.mogrify("(%s,%s)", r).decode() for r in rows)
    cur.execute("INSERT INTO bench.faces (event_id, embedding) VALUES " + args)
    cur.execute("ANALYZE bench.faces")
    load_s = time.perf_counter() - t

    queries = []
    for _ in range(QUERIES):
        e = int(rng.integers(0, EVENTS))
        p = int(rng.integers(0, PEOPLE_PER_EVENT))
        q = unit(centroids[e, p] + rng.normal(scale=NOISE, size=DIM))
        queries.append((e, vec(q)))

    def run(sql_for, setup=()):
        for s in setup:
            cur.execute(s)
        times, answers = [], []
        for e, q in queries:
            t0 = time.perf_counter()
            cur.execute(sql_for, (q, e, q, K) if "event_id" in sql_for else (q, q, K))
            answers.append([r[0] for r in cur.fetchall()])
            times.append((time.perf_counter() - t0) * 1000)
        return times, answers

    filtered = ("SELECT id FROM bench.faces WHERE embedding <=> %s::vector >= 0 AND event_id = %s "
                "ORDER BY embedding <=> %s::vector LIMIT %s")
    unfiltered = ("SELECT id FROM bench.faces WHERE embedding <=> %s::vector >= 0 "
                  "ORDER BY embedding <=> %s::vector LIMIT %s")

    exact_f_t, exact_f = run(filtered.replace("embedding <=> %s::vector >= 0 AND ", "1=1 AND %s::text IS NOT NULL AND "))
    exact_g_t, exact_g = run(unfiltered.replace("embedding <=> %s::vector >= 0", "%s::text IS NOT NULL"))

    cur.execute("EXPLAIN " + filtered.replace("embedding <=> %s::vector >= 0 AND ", "1=1 AND %s::text IS NOT NULL AND "),
                (queries[0][1], queries[0][0], queries[0][1], K))
    plan_exact = [re.sub(r"'\[[^']*\]'", "'[...]'", r[0]) for r in cur.fetchall()]

    t = time.perf_counter()
    cur.execute("CREATE INDEX bench_hnsw ON bench.faces USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64)")
    build_s = time.perf_counter() - t
    cur.execute("SELECT pg_relation_size('bench.bench_hnsw'), pg_relation_size('bench.faces')")
    index_bytes, table_bytes = cur.fetchone()

    def recall(approx, exact):
        hits = [len(set(a) & set(x)) / len(x) for a, x in zip(approx, exact) if x]
        return statistics.mean(hits)

    def counts(answers):
        return statistics.mean(len(a) for a in answers)

    results = {"vectors": EVENTS * FACES_PER_EVENT, "events": EVENTS, "k": K, "queries": QUERIES,
               "load_seconds": round(load_s, 1), "hnsw_build_seconds": round(build_s, 1),
               "hnsw_index_mb": round(index_bytes / 1e6, 1), "table_mb": round(table_bytes / 1e6, 1),
               "plan_exact_event_filtered": plan_exact}

    def row(name, times, answers, exact):
        results[name] = {"p50_ms": round(pct(times, 50), 2), "p95_ms": round(pct(times, 95), 2),
                         "recall_at_10": round(recall(answers, exact), 3), "avg_results": round(counts(answers), 2)}

    row("exact_event_filtered", exact_f_t, exact_f, exact_f)
    row("exact_whole_table", exact_g_t, exact_g, exact_g)
    plain = unfiltered.replace("embedding <=> %s::vector >= 0", "%s::text IS NOT NULL")
    by_event = filtered.replace("embedding <=> %s::vector >= 0 AND ", "%s::text IS NOT NULL AND ")
    for ef in (40, 100, 200):
        t1, a1 = run(plain, [f"SET hnsw.ef_search = {ef}"])
        row(f"hnsw_whole_table_ef{ef}", t1, a1, exact_g)

    # With the event_id btree present the planner picks it (exact) for event queries.
    # Drop it to see what HNSW does with a filter: it finds ef_search neighbours, THEN filters.
    cur.execute("DROP INDEX bench.faces_event_id_idx")
    t2, a2 = run(by_event, ["SET hnsw.ef_search = 40", "SET hnsw.iterative_scan = off"])
    row("hnsw_event_filtered_post_filter", t2, a2, exact_f)
    cur.execute("EXPLAIN " + by_event, (queries[0][1], queries[0][0], queries[0][1], K))
    results["plan_hnsw_event_filtered"] = [re.sub(r"'\[[^']*\]'", "'[...]'", r[0]) for r in cur.fetchall()]
    t3, a3 = run(by_event, ["SET hnsw.ef_search = 40", "SET hnsw.iterative_scan = relaxed_order"])
    row("hnsw_event_filtered_iterative_scan", t3, a3, exact_f)
    cur.execute("SET hnsw.iterative_scan = off")

    cur.execute("DROP SCHEMA bench CASCADE")
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out", "ann_bench.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w") as f:
        json.dump(results, f, indent=1)
    print(json.dumps(results, indent=1))


if __name__ == "__main__":
    main()
