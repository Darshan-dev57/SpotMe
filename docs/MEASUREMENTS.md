# Measurements

Every number in this repository comes from a JSON file in [`eval/out/`](../eval/out). This page explains
what each file measured, on what machine, and how to reproduce it. The scripts are in [`eval/`](../eval).

## Machines

| Name | What it is | Used for |
|---|---|---|
| Development machine | Debian 12, 4 vCPUs, 8 GB RAM, shared with other work (load average 2 to 4.6 during runs) | upload, indexing, crash recovery, accuracy, latency, vector search, memory |
| Hosted demo box | Debian 12, 2 vCPUs, 473 MB RAM, 1 GB swap file added by `deploy/start.sh` | `hosted.json`, `scale.json`, `ingest_live.json` |

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
| **0.55 (picked here)** | **0.9955** | **0.8871** | **1** | **0 of 25** |
| 0.60 | 0.9574 | 0.9073 | 10 | 2 of 25 |
| 0.65 | 0.7643 | 0.9153 | 70 | 10 of 25 |

The app now uses **0.51**, picked on the 11,251-photo scale test (see "Finding one person in the big event").
On this test split precision is 1.0 at both 0.50 and 0.525, with recall 0.8548 and 0.879, so 0.51 lies between.

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

## Scale test on the hosted box (`scale.json`, `scale_sweep.json`)

`eval/scale_test.py` drove https://spotme.sites.tab.bot from outside, through the same API the web pages use,
with the **full LFW set**: 13,233 photos of 5,749 people. Every person with 2 or more photos kept one back as
their selfie (1,680 people) and 300 people with a single photo were kept out of the event to search as strangers
(seed 11). The other 11,253 photos went into one event called "Scale test". Two of them are byte-identical files,
so the event holds 11,251 photos with 12,940 faces. LFW photos are 250x250 px news photos, smaller than the
composites above, so throughput numbers are not comparable with the development machine.

**Upload and indexing** (4 uploads at a time, like the organizer page):

| Measure | Result |
|---|---|
| Upload of 11,253 photos | 456.3 s (1,479.6 photos per minute), 0 failed, 0 retried |
| Upload request, measured by the client | p50 147.3 ms, p95 234.5 ms, p99 422.7 ms |
| From first upload to queue empty | 466.6 s (1,446.7 photos per minute), 0 failed |
| Largest queue seen | 24 jobs: the single worker kept up with the uploads, so its ceiling was not reached |
| Box memory (473 MB) | lowest available 96 MB, swap used at most 26 MB, app process at most 275 MB |
| Disk | 380 MB used by the photos, 19.2 GB still free |
| Errors | 0 server errors, health UP in all 30 samples (every 15 s during upload and indexing) |

**Bug found: search got slow in a big event.** The first 678 answered searches spent p50 129.5 ms in SQL (p95
170.9 ms), against 1.4 ms in the 36-photo demo event. Two causes in `SearchService`: after a few calls the JDBC
driver switches to a server-side prepared statement, and Postgres then turned the selfie's text into a vector
again for every face row; and every face of the event went through the join and the `GROUP BY` before the
distance limit was applied. The fix makes the vector once (`WITH q AS MATERIALIZED`) and moves the distance limit
into `WHERE`. A photo passes if any of its faces passes, so the result is the same: all 678 searches
that ran before the fix returned exactly the same photos after it.

| Search with a selfie | Before the fix (678 searches) | After the fix (1,733 searches) |
|---|---|---|
| Whole request, measured by the client (includes the network) | p50 295.0 ms, p95 392.7 ms | p50 163.1 ms, p95 246.9 ms, p99 455.6 ms |
| SQL search (server) | p50 129.5 ms, p95 170.9 ms | p50 7.3 ms, p95 12.2 ms |
| Detect and embed (server) | p50 25.1 ms, p95 36.5 ms | p50 26.8 ms, p95 40.3 ms |

After the fix, searching 11,251 photos is about as fast as searching the 36 demo photos was (`hosted.json`:
p50 166.1 ms, p95 275.2 ms). The slowest request took 1270.0 ms. "Before" counts the 678 answered searches that ran
before the fix was deployed; the run was then stopped and all 1,980 queries were run again on the fixed build. While photos were still being uploaded and indexed, 8 probe searches (one a minute) took
p50 223.6 ms.

**Accuracy at 11,251 photos** (threshold 0.55, scored the same way as `accuracy.json`):

| Measure | Small evaluation event (438 photos) | Scale test (11,251 photos) |
|---|---|---|
| Precision | 0.9955 | 0.9172 (534 wrong of 6,446 photos returned) |
| Recall | 0.8871 | 0.79 (0.8303 if the 200-result cap is taken into account) |
| People who found at least one of their photos | | 1,425 of 1,680 |
| Selfies refused (more than one face, or none) | 7 (test split) | 219 of 1,680 |
| People not in the event who got any result | 0 of 25 | 48 of 272 (17.6%; 28 more selfies were refused) |

The search engine holds up at this size; the threshold does not. With 16 times more faces in the event there are
many more look-alikes: Serena Williams's search, for example, also returned 6 photos of Venus Williams. The 0.55 threshold was tuned on
438 photos and lets 17.6% of strangers see at least one photo of someone else. A returned photo only counts as
correct if the searched person is the labelled person in it, so a person who is only in the background counts as
wrong, and LFW has a few people under two names (Joseph Blatter and Sepp Blatter), so precision is slightly
pessimistic. Recall is capped for the few people with more than 200 photos (George W. Bush has 529 in the event).

`eval/scale_sweep.py` repeats the scoring offline for other thresholds. It runs the same two models with the
same settings through OpenCV's Python bindings on this split, because the hosted box does not return raw
distances. At 0.55 it gives precision 0.917, recall 0.7838 and 48 of 271 strangers, close to the live result,
which is the check that it behaves like the app:

| Threshold | Precision | Recall | Strangers with any result (of 271) |
|---|---|---|---|
| 0.45 | 0.9855 | 0.6983 | 5 (1.8%) |
| **0.50** | **0.9779** | **0.7552** | **8 (3.0%)** |
| 0.55 (used until the change to 0.51) | 0.917 | 0.7838 | 48 (17.7%) |
| 0.60 | 0.6056 | 0.8021 | 186 (68.6%) |

At this size 0.50 is the better setting: it gives up about 3 points of recall for 6 times fewer wrong strangers.
The app now uses 0.51; the live numbers after the change are at the end of the next section.

One limit showed up on the organizer side: the photo list returned only the newest 1,000 photos (a fixed
`LIMIT` in `EventController`), so in an event this big the other 10,251 could not be browsed from the organizer
page. The list now comes in pages of 500 with a "Load more" button. The event's counts and the guest search
were never affected.

The event is still on the hosted box. The test registered its own organizer account and did not touch the demo
event.

## Finding one person in the big event (`scale_person.json`)

`eval/scale_person.py` asks the question a guest cares about: if I am in 10 photos of a huge event, do all 10
come back? It uses the scale-test event above (11,251 photos) and every LFW person with **10 or more photos in
it: 143 people, 4,011 photos**. Each searched once on the hosted app with the same held-out selfie as before.
20 of those selfies were refused because they show two faces; those people searched with their first event
photo that has one face, and that photo is left out of their count.

| At threshold 0.55, live (first run) | All 143 people | People with 10 to 15 photos (61) |
|---|---|---|
| Photos found | 3,548 of 4,011 | 697 of 720 |
| Median share of own photos found | 100% | 100% |
| Got every photo | 103 | 48 |
| Got 90% or more | 129 | 56 |
| Got under 50% | 2 | 0 |
| Wrong photos returned | 125 (for 63 people) | 55 (for 19 people) |
| Every own photo ranked above every wrong one | 107 | 51 |

The total is pulled down by the 200-result cap: George W Bush has 529 photos and Colin Powell 234, and both
got 200 results that were nearly all correct. Without those two, 3,151 of 3,248 photos came back.
Search took 220 ms at p50 and 465 ms at p95 from outside (147 searches). One search took
7.4 s end to end while the server's own timing for it was under 100 ms, so that wait was on the network.

What the misses look like (checked by eye):

- **Selfie quality decides most bad cases.** David Beckham's held-out photo is of a wax figure: 8 of 30.
  With a normal front photo of him: 29 of 29. Naomi Watts (head tilted back, laughing) went from 16 of 21 to
  19 of 20 with a front photo, Roger Federer from 9 of 13 to 11 of 12.
- **The worst wrong-photo cases are look-alike relatives.** Jeb Bush got all 10 of his photos plus 24 of
  George W Bush. Serena Williams got 50 of 51 plus 6 of Venus Williams.
- **Big changes in look are missed.** Anna Kournikova (visor shadow, sun cream, hand on face, very
  different hair and make-up) found 6 of 11, and only 2 of 10 even with a clear front photo.
- **Good cases** have a front-facing, evenly lit selfie: John Ashcroft 52 of 52, Jacques Chirac 51 of 51,
  Arnold Schwarzenegger 41 of 41, all with no wrong photos.

**Offline estimate at 0.50** (same models as the app on the development machine, not live; at 0.55 the same
copy gave 3,550 found and 124 wrong against the live 3,548 and 125): 3,428 found, 76 people with every photo,
111 with 90% or more, 5 under 50%, and 47 wrong photos. So 0.50 removes about
two thirds of the wrong photos, but 120 fewer own photos come back (the mean share per person drops from 96% to 92%).

**After the change to 0.51** (`scale_person_051.json`, same 143 people and selfies, plus the 300 strangers, all
live). 0.50 would have found only 92.9% of the photos of people with 10 to 15 photos (offline), below the 93%
we wanted to keep; 0.51 was the lowest threshold in steps of 0.01 that kept it (offline 94.4%):

| Live | 0.55 | 0.51 |
|---|---|---|
| Photos found, all 143 people | 3,548 of 4,011 | 3,461 of 4,011 |
| Photos found, people with 10 to 15 photos | 697 of 720 (96.8%) | 680 of 720 (94.4%) |
| People who got every photo | 103 | 84 |
| People who got 90% or more | 129 | 119 |
| Wrong photos returned to the 143 | 125 | 51 |
| Strangers who got any photo | 48 of 272 (17.6%, `scale.json`) | 10 of 272 (3.7%) |
| Photos shown to strangers | 70 | 11 |

Search took 225 ms at p50 and 359 ms at p95 (419 searches). The offline copy at 0.51 gave
3,463 found and 52 wrong, against the live 3,461 and 51. Over all 1,680 scale-test selfies the offline
copy gives precision 0.9743 and recall 0.7643 at 0.51 (0.917 and 0.7838 at 0.55); that full run was not
repeated live. The threshold was picked on the same people it is measured on, so these numbers are a little
optimistic.

## Removing a person from a group photo (`erase_test.json`)

A feasibility test, not part of the app. The question: can free models running on our own machine remove one
guest from a group photo so that the photo still looks natural, and what does it cost?

`eval/erase_test.py` runs four steps on each photo: YuNet finds the faces, YOLOX (OpenCV Zoo) finds the person
boxes, MobileSAM cuts out the outline of the person whose face was picked, and LaMa fills that area from the
background around it. The mask is grown by 14 px or 15% of the face height so no edge of the person is left, and
the fill works on a crop twice the size of the person, resized to the 512 x 512 the model takes.

Twenty group photos from Wikimedia Commons (CC0, CC BY, CC BY-SA; sources in `eval/erase_photos.json`, images not
committed), one person removed from each, judged by eye at phone size:

| Where the person is | Natural | Small flaw | Smudge |
|---|---|---|---|
| At the side (8) | 2 | 4 | 2 |
| In the middle (4) | 1 | 2 | 1 |
| Overlapping others (8) | 1 | 1 | 6 |
| All (20) | 4 | 7 | 9 |

A small flaw is something left behind that a careful look finds: an object the person held, their shadow, sign
text cut in half. A smudge is a visible smear. It works when the person stands a little apart with background
behind them. It fails when they hug or touch someone (a smear, or their hand left on a friend's shoulder) and in
crowds, where there is no background to reveal and the model paints a blurred body.

MI-GAN, a much smaller fill model (28 MB), was tried on the same masks and left a ghost of the person in nearly
every photo.

Cost, two threads per model on the development box: 5.2 s per photo (median, max 6.2 s) plus 11 s to load the
models. Measured one model at a time in a fresh process (`eval/erase_memory.py`): LaMa adds 874 MB, MobileSAM
322 MB, YOLOX 107 MB; with all of them loaded the process peaks at 2.06 GB. Most of LaMa's peak is working memory
for one 512 x 512 image, so turning off the memory arena or graph optimisation did not lower it.

The hosted box has 473 MB in total and about 100 MB free while the app runs, so none of this fits there. It
needs a machine with at least 1 GB free for the job, which is why the erase option stays switched off.

## Image formats, folder upload and Drive import (`ingest_live.json`)

Run on Oct 11 2026 against the hosted box at https://spotme.sites.tab.bot by `eval/ingest_check.mjs`: the folder
goes through the real organizer page (headless Chromium, phone-size window), the Drive folder through the API.

**Formats and folder upload.** One public domain NASA group photo (`crew-group.jpg`, 1280x720, four people)
was saved in eight formats with standard encoders (ImageMagick for PNG, TIFF, BMP and a two-frame animated
GIF; libwebp; libheif with x265 for HEIC; Pillow's libavif for AVIF). They went into one folder with a
subfolder holding two NASA portraits and a 24 megapixel public domain JPEG, plus three files that are not
photos (`notes.txt`, `clip.mp4`, `.DS_Store`). The folder was picked with *Choose a folder*.

| Check | Result |
|---|---|
| Files uploaded | 11 of 11, 3 skipped as not photos |
| HEIC and AVIF | converted to JPEG in the browser (Chromium has no HEIC decoder, so the HEIC library was loaded) |
| 24 MP photo (hosted cap 16 MP) | refused with 413, re-sent by the page as a 4000x2667 copy, accepted |
| Indexed | 11 of 11 ready, 39 faces; each of the eight format copies has 4 faces |
| Selfie search (Behnken portrait) | 8 results: exactly the eight format copies of the group photo |

The same folder gave the same result on three runs. Drag and drop of a folder is covered by unit tests of the folder
walker, not by a browser run.

**Drive import.** A public folder of 149 press conference photos (13 at the top, 136 in a subfolder; all 6000x4000
JPEGs, placed in the public domain by their owner) was imported by pasting its link.

| Check | Result |
|---|---|
| Listed | 149 photos, 0 other files |
| Imported | 149 of 149, 0 failed, 0 duplicates |
| Time | 411.8 s from the start to the last photo (2.8 s per photo, indexing included); an earlier run took 479 s |
| Path taken | every original was over the 16 MP cap, so each used Google's 4000 px rendering |
| Indexed | 149 of 149 ready, 1,200 faces |
| Box memory during the import (27 samples) | lowest 56 MB available, app process at most 357 MB, swap at most 358 MB, no restart |
| Selfie search | a face cropped from one photo returned 23 photos of that speaker (close-ups checked by eye) |

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
# full LFW set against the hosted box (extract lfw.tgz into eval/data/scale/lfw first)
python3 eval/scale_test.py prepare && python3 eval/scale_test.py upload
python3 eval/scale_test.py search && python3 eval/scale_test.py summary   # scale.json
python3 eval/scale_sweep.py                                               # scale_sweep.json
python3 eval/scale_person.py                                              # scale_person.json (app at 0.55)
python3 eval/scale_person.py 0.51                                         # scale_person_051.json (app at 0.51)
# person removal test (needs onnxruntime and the models listed in erase_test.json)
FOLDER=... SELFIE=... DRIVE_LINK=... node eval/ingest_check.mjs           # ingest_live.json
python3 eval/erase_test.py --models M --photos P --out O --plan plan.json  # per-photo times, summed into erase_test.json
```
