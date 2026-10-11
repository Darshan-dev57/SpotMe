-- Importing photos from a public Google Drive folder link. One row per import, one row per photo in it.
-- Each photo is its own DRIVE_FILE job, so a big folder never sits in memory and a crash only redoes one photo.
CREATE TABLE drive_imports (
    id               uuid PRIMARY KEY,
    event_id         uuid NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    folder_id        text NOT NULL,
    -- listing: reading the folder, importing: photo jobs queued, done, failed: see error
    status           text NOT NULL CHECK (status IN ('listing', 'importing', 'done', 'failed')),
    files_found      int  NOT NULL DEFAULT 0,
    files_over_limit int  NOT NULL DEFAULT 0, -- photos past the per-import limit, not imported
    skipped          int  NOT NULL DEFAULT 0, -- files that are not photos (videos, documents)
    error            text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    finished_at      timestamptz
);
CREATE INDEX drive_imports_event_idx ON drive_imports (event_id, created_at);

CREATE TABLE drive_import_files (
    id        uuid PRIMARY KEY,
    import_id uuid NOT NULL REFERENCES drive_imports (id) ON DELETE CASCADE,
    file_id   text NOT NULL,
    name      text NOT NULL,
    mime_type text,
    status    text NOT NULL CHECK (status IN ('queued', 'imported', 'duplicate', 'failed')),
    photo_id  uuid,
    error     text,
    UNIQUE (import_id, file_id)
);

ALTER TABLE jobs DROP CONSTRAINT jobs_kind_check;
ALTER TABLE jobs ADD CONSTRAINT jobs_kind_check
    CHECK (kind IN ('INDEX_PHOTO', 'PURGE_PHOTO', 'PURGE_EVENT', 'DRIVE_LIST', 'DRIVE_FILE'));

-- Background imports run behind indexing and deletion: lower priority numbers are claimed first.
ALTER TABLE jobs ADD COLUMN priority smallint NOT NULL DEFAULT 0;
DROP INDEX jobs_claim_idx;
CREATE INDEX jobs_claim_idx ON jobs (priority, run_after, id) WHERE state = 'queued';
