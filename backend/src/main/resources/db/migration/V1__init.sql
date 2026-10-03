-- SpotMe schema. Everything that matters lives in one Postgres database:
-- events, access, photos, face vectors and the job queue.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE organizers (
    id            uuid PRIMARY KEY,
    email         text NOT NULL UNIQUE,
    display_name  text NOT NULL,
    password_hash text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE events (
    id                  uuid PRIMARY KEY,
    organizer_id        uuid NOT NULL REFERENCES organizers (id),
    title               text NOT NULL,
    purpose_notice      text NOT NULL,
    notice_version      int  NOT NULL DEFAULT 1,
    -- draft: uploading and reviewing, published: attendees can search,
    -- deleting: purge in progress, purged: only this tombstone row is left
    status              text NOT NULL CHECK (status IN ('draft', 'published', 'deleting', 'purged')),
    consent_attested_at timestamptz NOT NULL,
    expires_at          timestamptz NOT NULL,
    -- bumped when deletion starts so in-flight workers can tell their result is stale
    deletion_epoch      int  NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now(),
    published_at        timestamptz,
    purged_at           timestamptz
);

-- One row per invited attendee. The invite link is the credential, so only its hash is stored.
CREATE TABLE memberships (
    id                     uuid PRIMARY KEY,
    event_id               uuid NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    label                  text NOT NULL,
    invite_token_hash      text NOT NULL UNIQUE,
    consent_notice_version int,
    consented_at           timestamptz,
    withdrawn_at           timestamptz,
    revoked_at             timestamptz,
    created_at             timestamptz NOT NULL DEFAULT now()
);

-- Bearer sessions for organizers and attendees (token hash only).
CREATE TABLE sessions (
    token_hash    text PRIMARY KEY,
    organizer_id  uuid REFERENCES organizers (id) ON DELETE CASCADE,
    membership_id uuid REFERENCES memberships (id) ON DELETE CASCADE,
    expires_at    timestamptz NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CHECK ((organizer_id IS NULL) <> (membership_id IS NULL))
);

CREATE TABLE photos (
    id             uuid PRIMARY KEY,
    event_id       uuid NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    object_key     text,
    thumb_key      text,
    -- hash of the uploaded bytes: dedupes uploads and blocks re-upload of a removed photo
    content_sha256 text NOT NULL,
    byte_size      bigint,
    width          int,
    height         int,
    status         text NOT NULL CHECK (status IN ('queued', 'processing', 'ready', 'failed', 'purged')),
    hidden_at      timestamptz, -- organizer review: not shown to attendees
    suppressed_at  timestamptz, -- removal requested: not shown to anyone until decided
    face_count     int,
    model_version  text,
    error_code     text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    indexed_at     timestamptz,
    purged_at      timestamptz,
    UNIQUE (event_id, content_sha256)
);
CREATE INDEX photos_event_status_idx ON photos (event_id, status);

-- One row per detected face. No global identity: a face only exists inside its event.
CREATE TABLE faces (
    id            bigserial PRIMARY KEY,
    event_id      uuid NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    photo_id      uuid NOT NULL REFERENCES photos (id) ON DELETE CASCADE,
    face_index    int  NOT NULL,
    box_x         int  NOT NULL,
    box_y         int  NOT NULL,
    box_w         int  NOT NULL,
    box_h         int  NOT NULL,
    det_score     real NOT NULL,
    embedding     vector(128) NOT NULL,
    model_version text NOT NULL,
    UNIQUE (photo_id, model_version, face_index)
);
CREATE INDEX faces_event_idx ON faces (event_id, model_version);

-- Durable job queue. Workers claim with FOR UPDATE SKIP LOCKED and hold a lease.
CREATE TABLE jobs (
    id           bigserial PRIMARY KEY,
    kind         text NOT NULL CHECK (kind IN ('INDEX_PHOTO', 'PURGE_PHOTO', 'PURGE_EVENT')),
    target_id    uuid NOT NULL,
    dedupe_key   text NOT NULL UNIQUE,
    state        text NOT NULL CHECK (state IN ('queued', 'running', 'succeeded', 'failed')),
    attempts     int  NOT NULL DEFAULT 0,
    max_attempts int  NOT NULL DEFAULT 5,
    run_after    timestamptz NOT NULL DEFAULT now(),
    lease_until  timestamptz,
    claim_token  uuid,
    claimed_by   text,
    last_error   text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    finished_at  timestamptz
);
CREATE INDEX jobs_claim_idx ON jobs (run_after, id) WHERE state = 'queued';
CREATE INDEX jobs_lease_idx ON jobs (lease_until) WHERE state = 'running';

CREATE TABLE removal_requests (
    id            uuid PRIMARY KEY,
    event_id      uuid NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    membership_id uuid REFERENCES memberships (id) ON DELETE SET NULL,
    photo_ids     uuid[] NOT NULL,
    note          text,
    status        text NOT NULL CHECK (status IN ('received', 'approved', 'rejected', 'completed')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    decided_at    timestamptz,
    completed_at  timestamptz
);

CREATE TABLE wrong_match_reports (
    membership_id uuid NOT NULL REFERENCES memberships (id) ON DELETE CASCADE,
    photo_id      uuid NOT NULL REFERENCES photos (id) ON DELETE CASCADE,
    created_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (membership_id, photo_id)
);

CREATE TABLE worker_heartbeats (
    worker_id  text PRIMARY KEY,
    started_at timestamptz NOT NULL DEFAULT now(),
    last_seen  timestamptz NOT NULL DEFAULT now(),
    jobs_done  bigint NOT NULL DEFAULT 0
);
