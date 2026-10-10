package dev.darshan.spotme.media;

import dev.darshan.spotme.common.ApiException;
import dev.darshan.spotme.common.Tokens;
import dev.darshan.spotme.config.SpotMeProperties;
import dev.darshan.spotme.event.EventAccess;
import dev.darshan.spotme.jobs.JobKind;
import dev.darshan.spotme.jobs.JobQueue;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Organizer photo upload, one photo per request (the web page sends a few in parallel).
 * Each file gets its own answer, so one broken file never fails the whole batch.
 */
@RestController
public class UploadController {

    private static final Logger log = LoggerFactory.getLogger(UploadController.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EventAccess access;
    private final ImageProcessor images;
    private final PhotoStorage storage;
    private final JobQueue queue;
    private final int maxAttempts;

    public UploadController(JdbcTemplate jdbc, TransactionTemplate tx, EventAccess access, ImageProcessor images,
                            PhotoStorage storage, JobQueue queue, SpotMeProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.access = access;
        this.images = images;
        this.storage = storage;
        this.queue = queue;
        this.maxAttempts = props.worker().maxAttempts();
    }

    @PostMapping("/api/events/{eventId}/photos")
    public ResponseEntity<Map<String, Object>> upload(@PathVariable UUID eventId, @RequestParam("file") MultipartFile file,
                                                      HttpServletRequest http) throws IOException {
        access.requireOwner(eventId, http);
        access.requireLive(eventId);

        byte[] raw = file.getBytes();
        String sha = Tokens.sha256Hex(raw);

        // same bytes already uploaded to this event: return the existing photo, no new work
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id, status FROM photos WHERE event_id = ? AND content_sha256 = ?", eventId, sha);
        if (!existing.isEmpty()) {
            if ("purged".equals(existing.get(0).get("status"))) {
                throw ApiException.conflict("removed_photo", "This photo was removed on request and can't be uploaded again.");
            }
            return ResponseEntity.ok(result(existing.get(0).get("id"), "duplicate", null));
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
            if (e instanceof org.springframework.dao.DuplicateKeyException) {
                return ResponseEntity.ok(result(null, "duplicate", null));
            }
            throw e;
        }
        log.debug("Stored photo {} ({}x{})", photoId, clean.width(), clean.height());
        return ResponseEntity.status(HttpStatus.CREATED).body(result(photoId, "queued", null));
    }

    /** Lets the organizer retry a failed photo after fixing the cause. */
    @PostMapping("/api/events/{eventId}/photos/{photoId}/retry")
    public Map<String, Object> retry(@PathVariable UUID eventId, @PathVariable UUID photoId, HttpServletRequest http) {
        access.requireOwner(eventId, http);
        access.requirePhotoInEvent(photoId, eventId);
        tx.executeWithoutResult(s -> {
            int changed = jdbc.update("UPDATE photos SET status = 'queued', error_code = NULL WHERE id = ? AND status = 'failed'", photoId);
            if (changed == 0) {
                throw ApiException.conflict("not_failed", "Only failed photos can be retried.");
            }
            queue.requeue("index:" + photoId);
        });
        return result(photoId, "queued", null);
    }

    private static Map<String, Object> result(Object id, String status, String error) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("status", status);
        if (error != null) {
            out.put("error", error);
        }
        return out;
    }
}
