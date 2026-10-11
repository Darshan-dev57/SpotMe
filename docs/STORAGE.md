# Photo storage

Every photo and thumbnail goes through one interface, `PhotoStorage` (`put`, `get`, `open`, `delete`, `exists`,
`list`, `directLink`). Two implementations, picked with `SPOTME_STORAGE_TYPE`:

| | `local` (default) | `s3` |
|---|---|---|
| Class | `LocalPhotoStorage` | `S3PhotoStorage` (AWS SDK for Java 2) |
| Where | files under `SPOTME_STORAGE_DIR` | a private bucket on any S3-compatible server; MinIO locally |
| Guest downloads | the app checks the signed link, then streams the bytes | the app checks the signed link, then answers `302` to a bucket link signed for 60 s |
| Used by | local runs, the hosted site | local runs with `--profile s3`, a deployment with several app instances |

The app's own signed links stay the gate in both cases: every photo request is checked against the guest's
membership, consent and the photo's state before any bytes or bucket link go out. The bucket link that follows
is signed for `spotme.s3.link-ttl` (60 s), so a guest whose access ends keeps at most that much time on a link
they already opened.

## Running with MinIO

MinIO no longer publishes official Docker images, so `docker-compose.yml` uses the community build
`pgsty/minio`. It is free and runs on your machine; no cloud account is needed.

```bash
docker compose --profile s3 up -d          # PostgreSQL and MinIO (console on http://localhost:9001)
cd backend
SPOTME_STORAGE_TYPE=s3 SPOTME_WEB_DIR=../web/out SPOTME_WEB_ORIGIN=http://localhost:8080 mvn spring-boot:run
```

The bucket `spotme-photos` is created on start. The defaults (`localhost:9000`, user `spotme`, password
`spotme-minio`) match `docker-compose.yml` and are for local use only. For a real S3 service set
`SPOTME_S3_ENDPOINT`, `SPOTME_S3_REGION`, `SPOTME_S3_BUCKET`, `SPOTME_S3_ACCESS_KEY` and `SPOTME_S3_SECRET_KEY`.

## Deletion

Deleting works the same on both: purge jobs delete the original and the thumbnail by key, an event purge
deletes everything under `events/{eventId}/`, and the orphan sweep (every 10 minutes) deletes files that no photo row
points to. Deleting a key that is already gone counts as success, so a purge that crashed halfway can run again.

## Tests

- `S3PhotoStorageTest` runs against a real MinIO in Docker: store, read, stream, delete, a missing key behaves
  like a missing file, `list("events/abc")` does not return `events/abcd/...`, the bucket refuses unsigned
  requests, and a signed link works and then expires.
- `S3StorageIntegrationTest` runs the whole app on MinIO: upload (original and thumbnail land in the bucket),
  indexing reads the photo back, a guest's download is redirected to a signed bucket link that returns the
  photo, deleting the photo removes both objects and the old link stops working, and deleting the event leaves
  nothing under its folder.
- Every other integration test runs on local disk.

## Still a limitation

Rate limits (search, login, invites) are kept in memory, per app process. With shared bucket storage several
app instances could run, but each would keep its own limits; a shared store such as Redis or a PostgreSQL table
would be needed for that.
