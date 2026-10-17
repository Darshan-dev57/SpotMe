# Changelog

All notable changes to SpotMe. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed
- The home page explains the product in plain words: a headline, three steps, who it is for and a short privacy note.
- The sample event is now 37 NASA photos of the Crew-1 mission. Visitors tap one of the four astronauts on the
  home page and see the whole album with that person's photos outlined.
- The sample event explains that a visitor's own selfie only finds photos they are in, and points to creating
  an event to try it with their own photos.

## [1.0.0] - 2026-10-11

First complete release.

### Organizers
- Accounts with BCrypt passwords and opaque session tokens stored as SHA-256 hashes.
- Events with a purpose notice, versioned consent, an expiry date and a publish step.
- Photo upload one by one, as a whole folder, or from a shared Google Drive folder (no API key).
- Eight image formats (JPEG, PNG, WebP, TIFF, BMP, GIF on the server; HEIC and AVIF converted in the browser).
- Live indexing progress over Server-Sent Events, with a polling fallback.
- Public and private photos, bulk visibility controls, hide and retry.
- Guest invite links with QR codes (PNG download), revocation and a removal request queue.

### Guests
- Join from a personal link, read the notice, agree, and search with one selfie (upload or live camera).
- Only the photos the guest appears in, plus the photos the organizer shared with everyone.
- Download one photo or a streamed ZIP of all matches.
- Remove me (solo photos hidden at once, deleted when the organizer approves), withdraw consent, and
  opt-in alerts for new photos on a private link.

### Backend
- Durable job queue in PostgreSQL: `FOR UPDATE SKIP LOCKED`, leases, retries with exponential backoff and
  jitter, crash recovery.
- pgvector search scoped to one event; the query fix took search SQL from p50 129.5 ms to 7.3 ms on an
  11,251-photo event.
- Ingestion that checks magic bytes and the pixel count before decoding, re-encodes to JPEG (EXIF and GPS
  dropped) and deduplicates by SHA-256.
- HMAC-signed, expiring photo links checked against membership and consent on every request.
- Photo storage behind one interface: local disk, or an S3-compatible bucket (MinIO locally) with
  short-lived signed bucket links.
- Prometheus metrics, request ids and job ids on every log line, JSON logs on the server.
- Purge jobs that delete files, thumbnails and face vectors, and an orphan file sweep.
- A public sample event with public domain NASA photos.

### Security
- Tests for the threats in `docs/THREAT_MODEL.md`: organizer isolation, cross-event links, revoked and expired
  links, selfie never stored, malicious uploads, Drive SSRF, rate limit bypass.
- Fixed before release: a job claim could take more jobs than its limit; a guest could double the face lookup
  limit by switching endpoints; the invite limit trusted a made-up `X-Forwarded-For` header.
- Errors return short JSON with no stack traces.

### Measured
- 11,253 photos uploaded at 1,479.6 photos/min with 0 failed uploads on a 473 MB server.
- At the 0.51 threshold: precision 0.986, recall 0.863, 3.7% of strangers got any photo
  (`docs/EVALUATION.md`).

### Tooling
- `scripts/start.sh` runs everything locally with one command; a clean clone check is in `docs/RUNNING.md`.
- 146 backend tests (JUnit 5, Testcontainers with real PostgreSQL and MinIO) and 38 web tests (Vitest) in
  GitHub Actions on every push.

[1.0.0]: https://github.com/Darshan-dev57/SpotMe/releases/tag/v1.0.0
