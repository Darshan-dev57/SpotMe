# Evaluation

Every number on this page comes from a real run of SpotMe through its HTTP API, saved as JSON in `eval/out/`.
F1 and the false negative rate are worked out from the measured precision and recall; nothing else is
calculated or estimated unless the row says so.

The LFW-based synthetic evaluation is useful for reproducible comparison, but it is not representative of all real event conditions such as blur, side profiles, poor lighting, masks, or crowded photos.

## Summary at the live threshold (0.51)

Measured on the hosted site (2 vCPUs, 473 MB RAM) on 2026-10-10 and 2026-10-11, in the scale-test event
(except worker recovery, a local run):
11,251 LFW photos with 12,940 faces. The face models, the 0.51 threshold, the search query and JPEG decoding
have not changed since these runs; later commits added other image formats, metrics and storage options.

| Metric | Result | How it was measured | Source |
|---|---|---|---|
| Precision | 0.986 | 3,461 correct of 3,512 photos returned | `scale_person_051.json` |
| Recall | 0.863 | 3,461 of the 4,011 photos of the people searched | `scale_person_051.json` |
| F1 | 0.920 | from the two rows above | worked out |
| False negative rate | 0.137 | 1 - recall (550 of 4,011 photos missed) | worked out |
| False positive rate | 0.037 | 10 of 272 people who are not in the event got any photo back | `scale_person_051.json` |
| Search latency p50 / p95 | 163.1 ms / 246.9 ms | 1,733 searches, timed from a client outside the box (includes the network) | `scale.json` |
| Search SQL p50 / p95 | 7.3 ms / 12.2 ms | the vector query alone, from `Server-Timing` | `scale.json` |
| Indexing throughput | 1,446.7 photos/min | 11,253 photos uploaded and indexed in 466.6 s by one worker thread | `scale.json` |
| Upload throughput | 1,479.6 photos/min | 11,253 uploads in 456.3 s, 0 failed, 0 server errors | `scale.json` |
| Worker recovery | 60.4 s | local run on the 438-photo event: worker killed with 2 jobs in flight; restart to empty queue, bounded by the 60 s lease; 438 of 438 photos indexed, 0 duplicate faces | `indexing.json` |

Who was searched: the 143 people with 10 or more photos in the event, each with one held-out photo as the selfie
(20 selfies with two faces were refused and are not in these counts), and 300 people who are not in the event.
A returned photo counts as correct only if the searched person is the labelled person in it. Results are capped
at 200 per search, which limits recall for the few people with more than 200 photos.

The false positive rate is per person, not per photo: a per-photo rate would divide by more than 11,000 photos
per search and look much smaller than what a stranger actually sees.

## Results by threshold

Same 11,253-photo split, scored offline with the same models and settings as the app (1,980 selfies, 271
strangers answered). Refused selfies count as misses.

| Threshold | Precision | Recall | F1 | FNR | Wrong photos | Strangers with any photo |
|---|---|---|---|---|---|---|
| 0.4 | 0.989 | 0.602 | 0.748 | 0.398 | 49 | 3 (1.1%) |
| 0.45 | 0.986 | 0.698 | 0.817 | 0.302 | 77 | 5 (1.8%) |
| 0.5 | 0.978 | 0.755 | 0.852 | 0.245 | 128 | 8 (2.9%) |
| 0.525 | 0.961 | 0.773 | 0.857 | 0.227 | 233 | 23 (8.5%) |
| 0.55 | 0.917 | 0.784 | 0.845 | 0.216 | 531 | 48 (17.7%) |
| 0.575 | 0.810 | 0.794 | 0.802 | 0.206 | 1,394 | 103 (38.0%) |
| 0.6 | 0.606 | 0.802 | 0.690 | 0.198 | 3,909 | 186 (68.6%) |

The live threshold sits between 0.50 and 0.525. It was checked on the 143-person subset above, offline and live:

| Threshold | Precision | Recall | F1 | Wrong photos | Run |
|---|---|---|---|---|---|
| 0.5 | 0.986 | 0.855 | 0.916 | 47 | offline |
| 0.51 | 0.985 | 0.863 | 0.920 | 52 | offline |
| 0.55 | 0.966 | 0.885 | 0.924 | 124 | offline |
| 0.51 | 0.985 | 0.863 | 0.920 | 51 | live, hosted site |

Offline and live agree at 0.51 (3,463 and 3,461 photos found), so the offline rows are a fair guide for the
other thresholds. At 0.55 strangers got a photo 17.6% of the time; 0.51 brought that to 3.7%.

## Small event

The 438-photo event from `eval/prepare_lfw.py` (100 people in the event, 25 strangers per split), test split:

| Threshold | Precision | Recall | F1 | Wrong photos | Strangers with any photo |
|---|---|---|---|---|---|
| 0.45 | 1.000 | 0.766 | 0.868 | 0 | 0 of 25 |
| 0.5 | 1.000 | 0.855 | 0.922 | 0 | 0 of 25 |
| 0.525 | 1.000 | 0.879 | 0.936 | 0 | 0 of 25 |
| 0.55 | 0.996 | 0.887 | 0.938 | 1 | 0 of 25 |
| 0.575 | 0.987 | 0.903 | 0.943 | 3 | 1 of 25 |
| 0.6 | 0.957 | 0.907 | 0.932 | 10 | 2 of 25 |

Local indexing of the same 438 photos (807 faces) on the development machine:

| Worker threads x OpenCV threads | Seconds | Photos/min | Peak worker memory |
|---|---|---|---|
| 2 x 2 | 45.0 | 582.4 | 666 MB |
| 1 x 1 | 117.0 | 224.2 | 456 MB |
| 1 x 4 | 64.6 | 405.7 | 465 MB |
| 4 x 1 | 35.9 | 730.8 | 1085 MB |

## Reproducing

The scripts and the dataset build are described in [eval/README.md](../eval/README.md); how each number was
measured is in [MEASUREMENTS.md](MEASUREMENTS.md). No LFW image or derived data is committed: the scripts
download LFW and write to `eval/data/`, which git ignores.
