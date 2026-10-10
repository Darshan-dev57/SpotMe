package dev.darshan.spotme.removal;

import dev.darshan.spotme.jobs.Job;
import dev.darshan.spotme.jobs.JobQueue;
import dev.darshan.spotme.jobs.StaleClaimException;
import dev.darshan.spotme.media.ImageProcessor;
import dev.darshan.spotme.media.PhotoStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ERASE_PERSON job: makes the edited copy of a group photo and swaps it in for the original.
 * The new files are written first, then one transaction points the photo at them, drops the erased
 * face's vector and shows the photo again. The original files are deleted last.
 */
@Component
public class ErasePersonHandler {

    private static final Logger log = LoggerFactory.getLogger(ErasePersonHandler.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PhotoStorage storage;
    private final ImageProcessor images;
    private final PersonEraser eraser;
    private final RemovalService removals;
    private final JobQueue queue;

    public ErasePersonHandler(JdbcTemplate jdbc, TransactionTemplate tx, PhotoStorage storage, ImageProcessor images,
                              PersonEraser eraser, RemovalService removals, JobQueue queue) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.storage = storage;
        this.images = images;
        this.eraser = eraser;
        this.removals = removals;
        this.queue = queue;
    }

    public String handle(Job job) throws Exception {
        UUID editId = job.targetId();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT e.request_id, e.photo_id, e.face_id, e.box_x, e.box_y, e.box_w, e.box_h, e.status,
                       p.event_id, p.object_key, p.thumb_key, p.status AS photo_status
                FROM photo_edits e JOIN photos p ON p.id = e.photo_id
                WHERE e.id = ?
                """, editId);
        // cancelled by the organizer, or the photo was deleted in the meantime
        if (rows.isEmpty() || !"queued".equals(rows.get(0).get("status")) || !"ready".equals(rows.get(0).get("photo_status"))) {
            finish(job);
            return "skipped";
        }
        Map<String, Object> row = rows.get(0);
        UUID photoId = (UUID) row.get("photo_id");
        UUID requestId = (UUID) row.get("request_id");
        String oldKey = (String) row.get("object_key");
        String oldThumb = (String) row.get("thumb_key");

        PersonEraser.FaceBox box = new PersonEraser.FaceBox((Integer) row.get("box_x"), (Integer) row.get("box_y"),
                (Integer) row.get("box_w"), (Integer) row.get("box_h"));
        byte[] edited = eraser.erase(storage.get(oldKey), box);
        ImageProcessor.CleanImage clean = images.clean(edited, true);

        String base = "events/" + row.get("event_id") + "/" + photoId + "_e" + editId.toString().substring(0, 8);
        String key = base + ".jpg";
        String thumbKey = base + "_thumb.jpg";
        storage.put(key, clean.jpeg());
        storage.put(thumbKey, clean.thumbnail());

        tx.executeWithoutResult(s -> {
            int swapped = jdbc.update("""
                    UPDATE photos SET object_key = ?, thumb_key = ?, byte_size = ?, width = ?, height = ?
                    WHERE id = ? AND object_key = ?
                    """, key, thumbKey, (long) clean.jpeg().length, clean.width(), clean.height(), photoId, oldKey);
            if (swapped != 1) {
                throw new StaleClaimException(job.id());
            }
            jdbc.update("DELETE FROM faces WHERE id = ?", row.get("face_id"));
            jdbc.update("UPDATE photos SET face_count = (SELECT count(*) FROM faces WHERE photo_id = ?) WHERE id = ?",
                    photoId, photoId);
            jdbc.update("UPDATE photo_edits SET status = 'done', done_at = now() WHERE id = ?", editId);
            removals.showIfNothingOpen(photoId);
            removals.completeIfFinished(requestId);
            if (!queue.complete(job)) {
                throw new StaleClaimException(job.id());
            }
        });
        // if this fails the orphan cleaner removes the old files later
        try {
            storage.delete(oldKey);
            storage.delete(oldThumb);
        } catch (Exception e) {
            log.warn("Could not delete the original of edited photo {}: {}", photoId, e.getMessage());
        }
        return "erased";
    }

    /** Called when the job has used up its attempts: the photo stays hidden and the organizer can keep it. */
    public void markFailed(UUID editId) {
        jdbc.update("UPDATE photo_edits SET status = 'failed' WHERE id = ? AND status = 'queued'", editId);
    }

    private void finish(Job job) {
        if (!queue.complete(job)) {
            throw new StaleClaimException(job.id());
        }
    }
}
