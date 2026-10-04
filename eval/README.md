# Evaluation

Everything here measures the real pipeline through the real HTTP API. Numbers in
`docs/MEASUREMENTS.md` come from the JSON files in `eval/out/`, produced by these scripts.

## Dataset

`prepare_lfw.py` builds a reproducible test event from **LFW (Labeled Faces in the Wild)**, a public
research benchmark of news photos of public figures (http://vis-www.cs.umass.edu/lfw/). LFW was not
collected with the consent model SpotMe asks of organizers; it is used only as a standard, public
benchmark. No LFW image is committed to this repository: the script downloads the archive and writes
everything to `eval/data/`, which git ignores.

What the script builds (seed 7):

| Part | Count | Notes |
|---|---|---|
| People in the event | 100 | 5 appearances each + 1 held-out "selfie" |
| People not in the event | 50 | 1 query each: they search but should get nothing |
| Other guests (distractors) | 300 | 1 appearance each |
| Event photos | 438 | 1600x1067 composites with 1 to 4 faces (55% solo) |
| Splits | tune / test | people alternate between the two splits |

The composites are synthetic: real event photos have more pose, blur and lighting variation, so
treat the accuracy numbers as an upper bound for well-lit, mostly frontal faces.

## Running

```bash
docker compose up -d                       # Postgres + pgvector on :5434
./scripts/fetch-models.sh
python3 eval/prepare_lfw.py                # ~180 MB download, ~5 minutes
# API with the eval profile (exposes raw distances to the event owner only)
cd backend && mvn -q spring-boot:run -Dspring-boot.run.profiles=api,eval
# in another shell
python3 eval/run_eval.py upload
# start a worker process and time the indexing
cd backend && mvn -q spring-boot:run -Dspring-boot.run.profiles=worker
python3 eval/run_eval.py wait
python3 eval/run_eval.py accuracy
python3 eval/run_eval.py latency
pip install psycopg2-binary numpy && python3 eval/ann_bench.py
```

## Scoring

Scoring is per **photo**, because that is what an attendee sees: a group photo is one result even if
it has four faces. For each query at each threshold:

- true positive: a returned photo that contains the person
- false positive: a returned photo that does not
- false negative: a photo with the person that was not returned

Negative queries (people not in the event) are counted separately: any result for them is a wrong,
private photo shown to the wrong person. Queries the API refuses (no face, several faces) count as
misses, not as skipped.
