-- What a guest chose when asking to be removed:
--   photos: requests made before the choice existed, which removed whole photos
--   solo:   only photos where the guest is the one detected face
--   erase:  solo photos, plus the guest erased from group photos (the group photo stays)
ALTER TABLE removal_requests ADD COLUMN mode text NOT NULL DEFAULT 'photos'
    CHECK (mode IN ('photos', 'solo', 'erase'));

-- One row per group photo a guest asked to be erased from. The photo stays hidden from everyone
-- until the edited copy replaces the original, or the organizer keeps the photo as it was.
CREATE TABLE photo_edits (
    id         uuid PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES removal_requests (id) ON DELETE CASCADE,
    event_id   uuid NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    photo_id   uuid NOT NULL REFERENCES photos (id) ON DELETE CASCADE,
    -- the face to erase; the box is copied so the edit does not depend on the faces row
    face_id    bigint NOT NULL,
    box_x      int NOT NULL,
    box_y      int NOT NULL,
    box_w      int NOT NULL,
    box_h      int NOT NULL,
    -- waiting: until the organizer approves, queued: job added, done: edited copy in place
    status     text NOT NULL CHECK (status IN ('waiting', 'queued', 'done', 'failed', 'cancelled')),
    created_at timestamptz NOT NULL DEFAULT now(),
    done_at    timestamptz,
    UNIQUE (request_id, photo_id)
);
CREATE INDEX photo_edits_open_idx ON photo_edits (photo_id) WHERE status IN ('waiting', 'queued');

ALTER TABLE jobs DROP CONSTRAINT jobs_kind_check;
ALTER TABLE jobs ADD CONSTRAINT jobs_kind_check
    CHECK (kind IN ('INDEX_PHOTO', 'PURGE_PHOTO', 'PURGE_EVENT', 'ERASE_PERSON'));
