# Measurements

Every number in this repository comes from a JSON file in [`eval/out/`](../eval/out). This page explains
what each file measured, on what machine, and how to reproduce it. The scripts are in [`eval/`](../eval).

## Machines

| Name | What it is | Used for |
|---|---|---|
| Development machine | Debian 12, 4 vCPUs, 8 GB RAM, shared with other work (load average 2 to 4.6 during runs) | upload, indexing, crash recovery, accuracy, latency, vector search, memory |
| Hosted demo box | Debian 12, 2 vCPUs, 473 MB RAM, 1 GB swap file added by `deploy/start.sh` | `hosted.json` |

Because the development machine was shared, treat its throughput numbers as conservative.

## Evaluation data

Accuracy, latency and throughput use an evaluation event built by `eval/prepare_lfw.py` from LFW
(Labeled Faces in the Wild), a public face recognition benchmark. It has 438 photos (1600x1067 composites with
1 to 4 faces, 807 faces in total), 100 people who appear 5 times each plus one held-out "selfie", 50 people who
are not in the event at all, and 300 one-off distractors. People alternate between a **tune** split, used to
pick the threshold, and a **test** split, checked once afterwards. No LFW image is committed; see
[`eval/README.md`](../eval/README.md).

The hosted check uses the 36 public domain NASA photos of the demo event instead.

## Accuracy (`accuracy.json`)

Scored per photo, because that is what a guest sees: a returned photo is correct if the person is in it.
Queries the API refuses (no face, or more than one face) count as misses.

| Threshold (cosine distance) | Test precision | Test recall | Wrong photos | People not in the event who got any result |
|---|---|---|---|---|
| 0.50 | 1.0 | 0.8548 | 0 | 0 of 25 |
| **0.55 (chosen)** | **0.9955** | **0.8871** | **1** | **0 of 25** |
| 0.60 | 0.9574 | 0.9073 | 10 | 2 of 25 |
| 0.65 | 0.7643 | 0.9153 | 70 | 10 of 25 |

On the tune split the same threshold gave precision 1.0 and recall 0.684. The tune split has more refused
queries (15 against 7 on the test split, mostly selfies with a second face), which is most of the gap.

![Precision and recall by threshold](images/accuracy.png)

## Search latency (`latency.json`)

174 real guest searches through `POST /api/attendee/search` with LFW selfies (250x250 px), after 10 warm-up
requests, on the development machine. The server reports its own split in the `Server-Timing` header.

| Part | p50 | p95 |
|---|---|---|
| Whole request, measured by the client | 59.2 ms | 79.7 ms |
| Decode the selfie | 1.4 ms | 2.2 ms |
| Detect and embed the face | 30.1 ms | 39.8 ms |
| SQL distance search in the event | 16.9 ms | 32.3 ms |

The slowest of the 174 requests took 113.8 ms.

## Upload (`upload.json`)

The 438 evaluation photos uploaded 4 at a time (as the web page does): 6.91 s in total, 58.4 ms p50 and
93.6 ms p95 per request. Upload only validates, re-encodes and stores; faces are found later by the worker.

## Indexing throughput (`indexing.json`)

Time for a separate worker process to index all 438 photos (807 faces), for different numbers of worker
threads and OpenCV threads per inference:

| Worker threads x OpenCV threads | Photos per minute | Peak worker memory (RSS) |
|---|---|---|
| 1 x 1 | 224.2 | 467,616 kB |
| 1 x 4 | 405.7 | 476,564 kB |
| 2 x 2 (default) | 582.4 | 682,772 kB |
| 4 x 1 | 730.8 | 1,111,164 kB |

More worker threads are faster but each one holds its own face engine, so memory grows with them. The default
of 2 x 2 is the middle ground for a 4-CPU machine.

## Crash recovery (`indexing.json`, `crash_recovery`)

The worker was killed with `SIGKILL` after 203 photos were ready and while 2 jobs were in flight, then started
again. Result: all 438 photos ready, 807 faces, 0 duplicate face rows, 438 jobs succeeded, 2 of them on a
second attempt. The queue was empty 60.4 s after the restart, which matches the 60 s lease: the two
abandoned jobs became claimable only when their leases ran out.

## Exact search against HNSW (`ann_bench.json`)

`eval/ann_bench.py` loads 100,000 synthetic unit vectors (128 dimensions, 200 events with 500 faces each) into a
separate `bench` schema and runs 200 queries for the 10 nearest faces. Each vector is a "person" direction plus
noise, so vectors of the same person cluster the way face embeddings do. Recall is measured against exact search.

| Query | p50 | p95 | recall@10 | Results returned (of 10) |
|---|---|---|---|---|
| Exact, filtered to one event | 0.63 ms | 0.82 ms | 1.0 | 10 |
| Exact, whole table | 22.7 ms | 28.11 ms | 1.0 | 10 |
| HNSW, whole table, ef_search 40 | 1.84 ms | 2.76 ms | 0.684 | 10 |
| HNSW, whole table, ef_search 100 | 2.93 ms | 3.59 ms | 0.79 | 10 |
| HNSW, whole table, ef_search 200 | 5.01 ms | 6.91 ms | 0.875 | 10 |
| HNSW plus the event filter | 2.1 ms | 4.55 ms | 0.411 | 4.12 |
| HNSW plus the event filter, iterative scan | 24.16 ms | 36.26 ms | 0.824 | 10 |

Building the HNSW index took 50.8 s and 83.1 MB (the table itself is 58.5 MB). Synthetic vectors say nothing
about face accuracy; this benchmark measures index behaviour only. The event-filter problem does not depend on
the data: HNSW finds its nearest candidates first and the `WHERE event_id = ?` filter removes most of them.

## Memory (`memory.json`)

The app process with the hosted settings (128 MB heap, one face engine, one worker thread), on the development
machine: 182 MB resident after start-up, 357 MB after the first photo was indexed (the face models are loaded
into OpenCV's native memory on first use), 376 MB after 12 photos. Native Memory Tracking reported 229,721 kB
committed by the JVM itself. Peak resident memory while indexing the 36 demo photos was between 467 and
493 MB across three flag combinations, which is why the hosted box gets a swap file.

## Hosted demo (`hosted.json`)

`eval/hosted_check.py` run from outside against https://spotme.sites.tab.bot: it created an event, uploaded
the 36 demo photos 2 at a time, waited for indexing, ran 24 timed searches (after 3 warm-up searches) with
three sample selfies, then deleted the event.

| Measure | Result |
|---|---|
| Upload of 36 photos | 4.9 s (p50 261.9 ms per request, including the network) |
| From first upload to all 36 indexed | 6.4 s |
| Search, measured by the client (includes the network) | p50 166.1 ms, p95 275.2 ms |
| Detect and embed (server) | p50 36.9 ms, p95 48.6 ms |
| SQL search (server) | p50 1.4 ms, p95 1.8 ms |
| Matches | Bob Behnken 15 photos, Doug Hurley 4, Victor Glover 0 (he is not in the album) |

## Reproducing

```bash
docker compose up -d && ./scripts/fetch-models.sh
python3 eval/prepare_lfw.py                         # builds eval/data (not committed)
cd backend && mvn -q spring-boot:run -Dspring-boot.run.profiles=api,eval
python3 eval/run_eval.py upload                     # upload.json
cd backend && mvn -q spring-boot:run -Dspring-boot.run.profiles=worker
python3 eval/run_eval.py wait                       # indexing throughput
python3 eval/run_eval.py accuracy                   # accuracy.json
python3 eval/run_eval.py latency                    # latency.json
python3 eval/ann_bench.py                           # ann_bench.json
python3 eval/plot_accuracy.py                       # docs/images/accuracy.png
scripts/fetch-demo-photos.sh && python3 eval/hosted_check.py   # hosted.json
```
