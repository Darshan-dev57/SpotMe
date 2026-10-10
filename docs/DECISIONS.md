# Design decisions

Each entry records what was chosen, why, what else was considered, what the choice costs, and what
evidence would make it worth revisiting. Numbers come from the files in `eval/out/`; see
[MEASUREMENTS.md](MEASUREMENTS.md) for how each one was produced.

## Contents

1. [One Spring Boot application with two roles](#1-one-spring-boot-application-with-two-roles)
2. [PostgreSQL with pgvector for the face vectors](#2-postgresql-with-pgvector-for-the-face-vectors)
3. [Exact search inside one event, no vector index](#3-exact-search-inside-one-event-no-vector-index)
4. [A job queue in a Postgres table](#4-a-job-queue-in-a-postgres-table)
5. [Leases, claim tokens and a deletion epoch](#5-leases-claim-tokens-and-a-deletion-epoch)
6. [OpenCV YuNet and SFace for faces](#6-opencv-yunet-and-sface-for-faces)
7. [A match threshold of 0.55, and no "maybe" tier](#7-a-match-threshold-of-055-and-no-maybe-tier)
8. [The selfie is never stored](#8-the-selfie-is-never-stored)
9. [A personal invite link per guest](#9-a-personal-invite-link-per-guest)
10. [Versioned notice and consent](#10-versioned-notice-and-consent)
11. [Removal hides at once, deletion follows approval](#11-removal-hides-at-once-deletion-follows-approval)
12. [Opaque session tokens, stored as hashes](#12-opaque-session-tokens-stored-as-hashes)
13. [Short-lived signed links for photos](#13-short-lived-signed-links-for-photos)
14. [Untrusted uploads are checked, capped and re-encoded](#14-untrusted-uploads-are-checked-capped-and-re-encoded)
15. [Photo files on local disk behind an interface](#15-photo-files-on-local-disk-behind-an-interface)
16. [Plain SQL with JdbcTemplate and Flyway](#16-plain-sql-with-jdbctemplate-and-flyway)
17. [A static Next.js export served by Spring Boot](#17-a-static-nextjs-export-served-by-spring-boot)
18. [Hosting on one small box with a start script](#18-hosting-on-one-small-box-with-a-start-script)
19. [Integration tests against real Postgres](#19-integration-tests-against-real-postgres)
20. [A public demo with public domain photos](#20-a-public-demo-with-public-domain-photos)

---

## 1. One Spring Boot application with two roles

- **Choice:** a single codebase and jar. Spring profiles pick the role: `api` serves HTTP and searches,
  `worker` runs the job loop and the sweepers, and the default runs both in one process.
- **Why:** the two halves share the schema, the face engine and the storage code. Splitting them into
  services would add a network contract and a deployment for no gain at this size. The roles still let the
  slow indexing work run on another machine when needed, because the only thing they share is Postgres.
- **Alternative:** separate services for upload, indexing and search.
- **Tradeoff:** one deployable means one failure domain in the all-in-one mode: a crash during indexing also
  drops the API until the process restarts.
- **What would change it:** an indexing backlog that hurts search latency, or a need to scale indexing on
  GPU machines separately from the API. The worker profile is the seam for that split.

## 2. PostgreSQL with pgvector for the face vectors

- **Choice:** face embeddings live in a `vector(128)` column next to the photos and events they belong to.
- **Why:** search must always be scoped to one event and must never return hidden, removed or reported
  photos. With vectors in the same database those are plain `WHERE` clauses and joins, checked in the same
  transaction as the data they protect. Deleting an event deletes its vectors with `ON DELETE CASCADE`.
- **Alternative:** a dedicated vector database (Qdrant, Milvus) or a FAISS index in memory.
- **Tradeoff:** a dedicated engine scales further and offers more index types. Here it would be a second
  store to keep consistent with Postgres, and every privacy rule would have to be enforced twice.
- **What would change it:** collections far beyond one event (see the next decision), or a need for
  search across events, which this product deliberately does not offer.

## 3. Exact search inside one event, no vector index

- **Choice:** a sequential distance scan over the faces of one event, found through a B-tree index on
  `(event_id, model_version)`. No HNSW or IVFFlat index.
- **Why:** measured on 100,000 synthetic face-like vectors spread over 200 events (`eval/out/ann_bench.json`):

  | Query | p50 | p95 | recall@10 |
  |---|---|---|---|
  | Exact, filtered to one event | 0.63 ms | 0.82 ms | 1.0 |
  | Exact, whole table | 22.7 ms | 28.11 ms | 1.0 |
  | HNSW, whole table, ef_search 40 | 1.84 ms | 2.76 ms | 0.684 |
  | HNSW, whole table, ef_search 200 | 5.01 ms | 6.91 ms | 0.875 |
  | HNSW plus the event filter | 2.1 ms | 4.55 ms | 0.411 |
  | HNSW plus the event filter, iterative scan | 24.16 ms | 36.26 ms | 0.824 |

  An event holds hundreds or thousands of faces, so the exact scan is already under a millisecond and never
  misses a match. HNSW filters after its graph search, so with an event filter it returned 4.12 of the 10
  wanted results on average.
- **Alternative:** an HNSW index (built in 50.8 s, 83.1 MB for the 100,000 vectors).
- **Tradeoff:** exact search grows linearly with the size of one event. A festival with millions of faces
  in a single event would be slow.
- **What would change it:** single events large enough that the exact scan passes a latency budget, for
  example about 50 ms at p95. Then a per-event partial index or partitioning by event would come first.

## 4. A job queue in a Postgres table

- **Choice:** a `jobs` table. Workers claim rows with `SELECT ... FOR UPDATE SKIP LOCKED`, so parallel
  workers never take the same job.
- **Why:** the upload writes the photo row and its indexing job in one transaction, so there is never a photo
  without a job or a job without a photo. A message broker cannot join that transaction. Retries, backoff,
  dedupe keys and failure reasons are all columns that can be queried with SQL.
- **Alternative:** RabbitMQ, Kafka or Redis streams, which would also need an outbox to stay consistent.
- **Tradeoff:** polling costs a small query every 500 ms per worker when idle, and the table has to be kept
  small. Throughput is bounded by Postgres, not by a broker.
- **What would change it:** many thousands of jobs per second or many consumer types needing fan-out. The
  measured indexing rate on 2 worker threads was 582.4 photos per minute (`eval/out/indexing.json`),
  far below where the table would be the bottleneck.

## 5. Leases, claim tokens and a deletion epoch

- **Choice:** a claim sets a lease deadline (60 s) and a fresh random token. Completing a job requires the
  same token. A reaper puts jobs with expired leases back in the queue. Events carry a `deletion_epoch`
  that the worker re-checks before saving.
- **Why:** a worker can die mid-photo. The lease brings the job back without a human; the token stops a slow
  worker from overwriting the result of the worker that took over; the epoch stops faces from being saved
  for an event that started deleting while inference ran. Measured by killing the worker after 203 of 438
  photos: all 438 ended ready, 807 faces, 0 duplicate face rows, 2 jobs needed a second attempt, and the
  queue drained 60.4 s after restart, bounded by the lease (`eval/out/indexing.json`).
- **Alternative:** a plain `status = 'running'` flag, which leaves stuck jobs forever after a crash.
- **Tradeoff:** a crashed job waits up to one lease before it is retried, and jobs must be idempotent (a
  re-run replaces a photo's faces instead of adding more).
- **What would change it:** jobs much longer than a minute would need lease renewal (a heartbeat per job).

## 6. OpenCV YuNet and SFace for faces

- **Choice:** YuNet (2023mar) for detection and SFace (2021dec) for 128-number embeddings, both ONNX models
  from the OpenCV Zoo, run in-process through OpenCV's Java bindings. YuNet is MIT licensed and SFace is
  Apache 2.0. The script that downloads them pins the commit and checks SHA-256.
- **Why:** they run on a CPU in tens of milliseconds (face step p50 30.1 ms per selfie,
  `eval/out/latency.json`), need no GPU or paid API, and their licences allow any use. Running them in the
  JVM keeps the whole pipeline in Java.
- **Alternative:** a cloud face API (per-call cost, and faces leave the system), InsightFace models (higher
  accuracy, but the pretrained models are for non-commercial research only), dlib (slower on CPU).
- **Tradeoff:** SFace is less accurate than larger models, especially on small, blurred or turned faces.
  OpenCV's native memory is outside the Java heap: loading the models adds about 175 MB of resident
  memory (`eval/out/memory.json`).
- **What would change it:** accuracy on real event photos that falls clearly below the LFW numbers, or a
  GPU becoming available. The `model_version` column on every face row allows a re-index with a new model
  side by side.

## 7. A match threshold of 0.55, and no "maybe" tier

- **Choice:** a photo is a match when one of its faces is within cosine distance 0.55 of the selfie.
- **Why:** the threshold was picked on the tune half of an LFW evaluation event and then checked once on the
  held-out test half (`eval/out/accuracy.json`). On the test half at 0.55: precision 0.9955 (220 correct
  photos, 1 wrong), recall 0.8871, and none of the 25 people who were not in the event got any result.
  A wrong match shows someone a private photo of a stranger, so precision is weighted above recall.
- **Alternative considered and rejected:** a second "possible match" section for distances 0.55 to 0.60.
  On the test half that band adds 5 correct photos and 9 wrong ones, so most of what it shows is wrong.
- **Tradeoff:** about one in nine of a guest's photos is not found (recall 0.8871 on the test half, lower on
  real photos with harder poses). The guest can search again with a better selfie.
- **What would change it:** measurements on consented real event photos. LFW composites are cleaner than
  real crowds, so the numbers are an upper bound.

## 8. The selfie is never stored

- **Choice:** the selfie arrives as the request body, is decoded and embedded in memory, compared, and
  dropped. Neither the image nor its vector is written anywhere, and error logs record only the exception type and message.
- **Why:** a selfie is the most sensitive input in the system. Data that is never stored cannot leak and
  needs no deletion process.
- **Alternative:** register the selfie once, as several event photo apps do, and deliver new matches
  automatically as photos arrive.
- **Tradeoff:** no automatic "new photos of you" notification. A guest who comes back must search again.
- **What would change it:** strong demand for live delivery during an event, and then only as an explicit
  opt-in with its own expiry.

## 9. A personal invite link per guest

- **Choice:** the organizer creates one link per guest. Only the SHA-256 of the link's token is stored, and
  the token sits after `#` in the URL, so browsers never send it to a server in the page request.
- **Why:** a link is a credential. A per-guest link can be revoked on its own, shows who consented, and limits
  what one forwarded link exposes. The organizer page can show each link as a QR code.
- **Alternative:** one QR code for the whole event, the common pattern in event photo apps.
- **Tradeoff:** more work for the organizer with many guests (names are pasted one per line).
- **What would change it:** large public events where guests are not known in advance. That would need a
  different access model (for example sign-in with a verified email) rather than a shared link.

## 10. Versioned notice and consent

- **Choice:** every event has a purpose notice with a version number. A guest's consent records the version
  they agreed to; changing the notice bumps the version, and existing guest sessions stop working until the
  guest reads and agrees again.
- **Why:** consent is only meaningful for the text that was actually shown.
- **Alternative:** a single "agreed" flag.
- **Tradeoff:** editing the notice logs every guest out of the search.
- **What would change it:** nothing technical; this follows from the privacy model.

## 11. Removal hides at once, deletion follows approval

- **Choice:** a guest can ask for photos to be removed. They are hidden from everyone immediately
  (`suppressed_at`) and their links stop working. If the organizer approves, a purge job deletes the files and
  face vectors and keeps only the content hash, which blocks the same file from being uploaded again. If
  the organizer rejects, the photo comes back unless another request for it is still open.
- **Why:** the person asking should not have to wait for a human, and the organizer should not lose photos
  to a mistaken click.
- **Alternative:** delete on request, or hide only after review.
- **Tradeoff:** a group photo removed for one person disappears for everyone in it.
- **What would change it:** blurring one face instead of deleting the photo, which is listed as a next step.

## 12. Opaque session tokens, stored as hashes

- **Choice:** sign-in returns a random token. The database keeps only its SHA-256 and an expiry
  (7 days for organizers, 2 days for guests). Every request looks the session up, and for guests also
  re-checks consent, revocation and the event state.
- **Why:** access must end the moment a guest withdraws or is removed. A self-contained JWT stays valid until
  it expires unless a revocation list is added, which is the same lookup with extra steps.
- **Alternative:** JWT access tokens with refresh tokens.
- **Tradeoff:** one indexed query per request.
- **What would change it:** many API instances where that lookup becomes a measurable cost; a short-lived
  cache would come before a token format change.

## 13. Short-lived signed links for photos

- **Choice:** thumbnails and originals are served from `/api/media/...` URLs carrying an HMAC signature, a
  subject (organizer or guest) and a 10 minute expiry. The server re-checks access when the link is used.
- **Why:** `<img>` tags cannot send an `Authorization` header. A signed link works in an image tag, and the
  live re-check means hiding a photo or removing a guest takes effect even for links already handed out.
- **Alternative:** public file URLs (unsafe) or fetching every image through JavaScript with the token.
- **Tradeoff:** links expire, so a page left open longer than 10 minutes must be refreshed.
- **What would change it:** moving files to object storage, where the provider's pre-signed URLs would replace
  the HMAC links.

## 14. Untrusted uploads are checked, capped and re-encoded

- **Choice:** the real type is read from the first bytes (JPEG or PNG only), the pixel count is read from the
  header and capped before decoding, and every photo is decoded and re-encoded as JPEG, which drops EXIF
  data such as GPS position. Files are stored under keys made from generated ids, never the uploaded name.
  A semaphore limits how many photos decode at once.
- **Why:** a small file can declare a huge image (a decompression bomb), EXIF can reveal where a photo was
  taken, and file names can contain paths.
- **Alternative:** trust the `Content-Type` header and store the original file.
- **Tradeoff:** re-encoding costs some CPU and a little image quality.
- **What would change it:** support for HEIC or RAW photos, which would need a converter step.

## 15. Photo files on local disk behind an interface

- **Choice:** a `PhotoStorage` interface with one implementation that writes to a local folder.
- **Why:** one machine needs no object store, and the interface keeps the rest of the code unaware of where
  files live. A sweeper removes files that no photo row refers to (left behind when a process dies between
  writing a file and committing its row).
- **Alternative:** S3 or MinIO from the start.
- **Tradeoff:** files live on one disk, so the app cannot run on several machines without shared storage.
- **What would change it:** a second app instance, or backups that need versioned storage.

## 16. Plain SQL with JdbcTemplate and Flyway

- **Choice:** SQL written by hand through `JdbcTemplate`; schema changes as numbered Flyway migrations.
- **Why:** the important queries (claiming jobs, distance search, re-checks under `FOR UPDATE`) are SQL-first
  and use pgvector operators. Writing them directly keeps every query visible in review.
- **Alternative:** Spring Data JPA with Hibernate.
- **Tradeoff:** more mapping code for simple reads.
- **What would change it:** a large CRUD surface, where repositories would save code.

## 17. A static Next.js export served by Spring Boot

- **Choice:** the web UI is a static export (`output: "export"`). In production Spring Boot serves it next to
  the API, so the browser talks to one origin.
- **Why:** no Node server to run, no CORS in production, and one process to deploy. Every page works as
  client-side React calling the API.
- **Alternative:** Next.js on its own server or a hosting platform, with the API on another origin.
- **Tradeoff:** no server-side rendering for logged-in pages (none of them need it).
- **What would change it:** pages that need server rendering for search engines or link previews.

## 18. Hosting on one small box with a start script

- **Choice:** the hosted demo runs on one Debian box with about 470 MB of memory and 2 CPUs. `deploy/start.sh`
  installs PostgreSQL 16 with pgvector and a Java 21 runtime if they are missing, fetches OpenCV and the face
  models with checksum checks, sizes everything down (one face engine, one worker thread, 128 MB heap,
  `shared_buffers` 32 MB), adds a 1 GB swap file and starts the app.
- **Why:** the box has no Docker. A script that is safe to run again on every start is simple to read and
  needs nothing else on the machine.
- **Alternative:** Docker Compose, or managed Postgres plus a container platform.
- **Tradeoff:** the app plus the face models need more memory than the box has (peak resident memory while
  indexing was 467 to 493 MB in local runs, `eval/out/memory.json`), so the swap file is required. Photos are
  capped at 16 megapixels and 60 per event on the demo.
- **What would change it:** real traffic. A 1 to 2 GB machine removes the need for swap.

## 19. Integration tests against real Postgres

- **Choice:** integration tests start the same `pgvector/pgvector:pg16` image with Testcontainers and drive the
  real HTTP API. Background workers are switched off in tests, which run jobs one by one so every step is
  deterministic. Crash tests kill workers mid-batch.
- **Why:** `SKIP LOCKED`, leases, cascades and vector operators cannot be tested meaningfully with mocks or
  an in-memory database.
- **Alternative:** H2 or mocked repositories.
- **Tradeoff:** the suite needs Docker; a full `mvn verify` takes under a minute on the development machine.
- **What would change it:** nothing; the slow part is container start-up, shared by all test classes.

## 20. A public demo with public domain photos

- **Choice:** with `spotme.demo.enabled`, the app keeps one published demo event filled with 36 photos of the
  SpaceX Demo-2 launch week from the NASA Image and Video Library. Each visitor gets a personal invite
  (rate limited per address) and can try official NASA portraits as sample selfies.
- **Why:** visitors can try the whole guest flow without signing up, and NASA imagery is generally not
  subject to copyright in the United States. LFW, used for measurement, has no such licence statement and is
  kept out of the repository and the demo.
- **Alternative:** a demo that asks for the visitor's own selfie only (they would find no photos), or a
  shared demo login.
- **Tradeoff:** the sample selfies are of public figures, and the demo shows how the product works rather
  than how it performs on a real party.
- **What would change it:** a consented set of real event photos.
