-- "Erase me completely" was dropped (see docs/DECISIONS.md). It was never switched on, so these
-- statements should find nothing, but they keep any existing rows safe:
-- an erase request becomes a solo request (it already held only the solo photos),
-- a group photo that was hidden for an edit comes back unless an open request still covers it.
UPDATE photos p SET suppressed_at = NULL
WHERE p.status <> 'purged'
  AND p.id IN (SELECT photo_id FROM photo_edits WHERE status IN ('waiting', 'queued'))
  AND NOT EXISTS (SELECT 1 FROM removal_requests r
                  WHERE r.status IN ('received', 'approved') AND p.id = ANY (r.photo_ids));

DROP TABLE photo_edits;

UPDATE removal_requests SET mode = 'solo' WHERE mode = 'erase';
ALTER TABLE removal_requests DROP CONSTRAINT removal_requests_mode_check;
ALTER TABLE removal_requests ADD CONSTRAINT removal_requests_mode_check CHECK (mode IN ('photos', 'solo'));

DELETE FROM jobs WHERE kind = 'ERASE_PERSON';
ALTER TABLE jobs DROP CONSTRAINT jobs_kind_check;
ALTER TABLE jobs ADD CONSTRAINT jobs_kind_check CHECK (kind IN ('INDEX_PHOTO', 'PURGE_PHOTO', 'PURGE_EVENT'));
