package dev.darshan.spotme.privacy;

import dev.darshan.spotme.jobs.Job;
import dev.darshan.spotme.jobs.JobKind;
import dev.darshan.spotme.jobs.JobQueue;
import dev.darshan.spotme.jobs.StaleClaimException;
import dev.darshan.spotme.media.PhotoStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real deletion, done by background jobs so it can be retried until it finishes.
 * Order matters: hide first (instant), delete files, check they are gone, then delete rows.
 * Deleting only the face vectors would not be enough: the face is still in the original photo.
 */
@Service
public class PurgeService {

    private static final Logger log = LoggerFactory.getLogger(PurgeService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PhotoStorage storage;
    private final JobQueue queue;

    public PurgeService(JdbcTemplate jdbc, TransactionTemplate tx, PhotoStorage storage, JobQueue queue) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.storage = storage;
        this.queue = queue;
    }

    /** Hides the photo now and queues its purge. Safe to call twice. */
    @Transactional
    public void schedulePhotoPurge(UUID photoId) {
        jdbc.update("UPDATE photos SET suppressed_at = coalesce(suppressed_at, now()) WHERE id = ?", photoId);
        queue.enqueue(JobKind.PURGE_PHOTO, photoId, "purge-photo:" + photoId, 10);
    }

    /**
     * Starts deleting a whole event (organizer delete or expiry). Bumping deletion_epoch
     * makes any indexing that is running right now throw its result away at commit.
     */
    @Transactional
    public boolean startEventDeletion(UUID eventId) {
        int changed = jdbc.update("""
                UPDATE events SET status = 'deleting', deletion_epoch = deletion_epoch + 1
                WHERE id = ? AND status IN ('draft', 'published')
                """, eventId);
        if (changed == 1) {
            queue.enqueue(JobKind.PURGE_EVENT, eventId, "purge-event:" + eventId, 10);
            log.info("Event {} is being deleted", eventId);
        }
        return changed == 1;
    }

    public void purgePhoto(Job job) throws IOException {
        UUID photoId = job.targetId();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT object_key, thumb_key, status FROM photos WHERE id = ?", photoId);
        if (rows.isEmpty() || "purged".equals(rows.get(0).get("status"))) {
            finish(job);
            return;
        }
        jdbc.update("UPDATE photos SET suppressed_at = coalesce(suppressed_at, now()) WHERE id = ?", photoId);
        String original = (String) rows.get(0).get("object_key");
        String thumb = (String) rows.get(0).get("thumb_key");
        deleteAndVerify(original);
        deleteAndVerify(thumb);

        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM faces WHERE photo_id = ?", photoId);
            jdbc.update("DELETE FROM wrong_match_reports WHERE photo_id = ?", photoId);
            // the row stays as a tombstone: its content hash blocks the same photo being uploaded again
            jdbc.update("""
                    UPDATE photos SET status = 'purged', object_key = NULL, thumb_key = NULL, face_count = 0,
                           width = NULL, height = NULL, byte_size = NULL, purged_at = now()
                    WHERE id = ?
                    """, photoId);
            // a removal request is complete once every photo in it is purged
            jdbc.update("""
                    UPDATE removal_requests r SET status = 'completed', completed_at = now()
                    WHERE r.status = 'approved' AND ? = ANY (r.photo_ids)
                      AND NOT EXISTS (SELECT 1 FROM photos p WHERE p.id = ANY (r.photo_ids) AND p.status <> 'purged')
                    """, photoId);
            if (!queue.complete(job)) {
                throw new StaleClaimException(job.id());
            }
        });
    }

    public void purgeEvent(Job job) throws IOException {
        UUID eventId = job.targetId();
        String status = jdbc.queryForObject("SELECT status FROM events WHERE id = ?", String.class, eventId);
        if (!"deleting".equals(status)) {
            finish(job);
            return;
        }
        // every file under the event's folder goes, including orphans the DB never knew about
        String prefix = "events/" + eventId + "/";
        for (PhotoStorage.StoredObject object : storage.list(prefix)) {
            storage.delete(object.key());
        }
        if (!storage.list(prefix).isEmpty()) {
            throw new IOException("files still present after delete");
        }
        tx.executeWithoutResult(s -> {
            jdbc.update("DELETE FROM faces WHERE event_id = ?", eventId);
            jdbc.update("DELETE FROM photos WHERE event_id = ?", eventId);
            jdbc.update("DELETE FROM removal_requests WHERE event_id = ?", eventId);
            jdbc.update("DELETE FROM memberships WHERE event_id = ?", eventId); // cascades sessions and reports
            jdbc.update("DELETE FROM jobs WHERE kind = 'INDEX_PHOTO' AND state <> 'running' AND target_id NOT IN (SELECT id FROM photos)");
            jdbc.update("UPDATE events SET status = 'purged', purpose_notice = '', purged_at = now() WHERE id = ?", eventId);
            if (!queue.complete(job)) {
                throw new StaleClaimException(job.id());
            }
        });
        log.info("Event {} purged", eventId);
    }

    private void deleteAndVerify(String key) throws IOException {
        if (key == null) {
            return;
        }
        storage.delete(key);
        if (storage.exists(key)) {
            throw new IOException("file still present after delete");
        }
    }

    private void finish(Job job) {
        if (!queue.complete(job)) {
            throw new StaleClaimException(job.id());
        }
    }
}
