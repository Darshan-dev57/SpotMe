package dev.darshan.spotme.media;

import dev.darshan.spotme.common.ApiException;
import dev.darshan.spotme.common.Tokens;
import dev.darshan.spotme.config.SpotMeProperties;
import dev.darshan.spotme.jobs.JobKind;
import dev.darshan.spotme.jobs.JobQueue;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Takes in one photo for an event: dedupe, clean, store the files, then the photo row and its
 * indexing job in one transaction. Used by the upload endpoint and by the demo seeder.
 */
@Service
public class PhotoIntake {

    /** What happened to one photo. photoId is null for a duplicate that lost a parallel race. */
    public record Result(UUID photoId, String status) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ImageProcessor images;
    private final PhotoStorage storage;
    private final JobQueue queue;
    private final int maxAttempts;
    private final int maxPhotosPerEvent;

    public PhotoIntake(JdbcTemplate jdbc, TransactionTemplate tx, ImageProcessor images, PhotoStorage storage,
                       JobQueue queue, SpotMeProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.images = images;
        this.storage = storage;
        this.queue = queue;
        this.maxAttempts = props.worker().maxAttempts();
        this.maxPhotosPerEvent = props.upload().maxPhotosPerEvent();
    }

    public Result add(UUID eventId, byte[] raw) throws IOException {
        String sha = Tokens.sha256Hex(raw);

        // same bytes already uploaded to this event: return the existing photo, no new work
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id, status FROM photos WHERE event_id = ? AND content_sha256 = ?", eventId, sha);
        if (!existing.isEmpty()) {
            if ("purged".equals(existing.get(0).get("status"))) {
                throw ApiException.conflict("removed_photo", "This photo was removed on request and can't be uploaded again.");
            }
            return new Result((UUID) existing.get(0).get("id"), "duplicate");
        }

        Long photos = jdbc.queryForObject(
                "SELECT count(*) FROM photos WHERE event_id = ? AND status <> 'purged'", Long.class, eventId);
        if (photos != null && photos >= maxPhotosPerEvent) {
            throw ApiException.conflict("event_full", "This event already has " + maxPhotosPerEvent + " photos, the most allowed.");
        }

        ImageProcessor.CleanImage clean;
        try {
            clean = images.clean(raw, true);
        } catch (ImageProcessor.RejectedImageException e) {
            HttpStatus status = e.code().equals("too_many_pixels") || e.code().equals("file_too_large")
                    ? HttpStatus.PAYLOAD_TOO_LARGE : HttpStatus.UNPROCESSABLE_CONTENT;
            throw new ApiException(status, e.code(), e.getMessage());
        }

        // 1) write the files under keys built from ids we generate (never the user's file name)
        UUID photoId = UUID.randomUUID();
        String key = "events/" + eventId + "/" + photoId + ".jpg";
        String thumbKey = "events/" + eventId + "/" + photoId + "_thumb.jpg";
        storage.put(key, clean.jpeg());
        storage.put(thumbKey, clean.thumbnail());

        // 2) photo row + its indexing job in ONE transaction: either both exist or neither does
        try {
            tx.executeWithoutResult(s -> {
                jdbc.update("""
                        INSERT INTO photos (id, event_id, object_key, thumb_key, content_sha256, byte_size, width, height, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'queued')
                        """, photoId, eventId, key, thumbKey, sha, (long) clean.jpeg().length, clean.width(), clean.height());
                queue.enqueue(JobKind.INDEX_PHOTO, photoId, "index:" + photoId, maxAttempts);
            });
        } catch (RuntimeException e) {
            // the database said no (maybe a parallel duplicate): don't leave the files behind.
            // If we crash before this line, the orphan cleaner removes them later.
            storage.delete(key);
            storage.delete(thumbKey);
            if (e instanceof DuplicateKeyException) {
                return new Result(null, "duplicate");
            }
            throw e;
        }
        return new Result(photoId, "queued");
    }
}
