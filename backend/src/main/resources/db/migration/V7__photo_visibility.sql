-- Organizers can share some photos (venue, stage, crowd) with every guest of the event.
-- Private stays the default: a private photo is only shown to guests whose face is in it.
-- Public photos are still indexed, so they also come back in a guest's own matches.
ALTER TABLE photos ADD COLUMN is_public boolean NOT NULL DEFAULT false;

CREATE INDEX photos_public_idx ON photos (event_id, created_at DESC) WHERE is_public;
