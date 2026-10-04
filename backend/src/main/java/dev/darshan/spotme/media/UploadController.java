package dev.darshan.spotme.media;

import dev.darshan.spotme.common.ApiException;
import dev.darshan.spotme.event.EventAccess;
import dev.darshan.spotme.jobs.JobQueue;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Organizer photo upload, one photo per request (the web page sends a few in parallel).
 * Each file gets its own answer, so one broken file never fails the whole batch.
 */
@RestController
public class UploadController {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EventAccess access;
    private final PhotoIntake intake;
    private final JobQueue queue;

    public UploadController(JdbcTemplate jdbc, TransactionTemplate tx, EventAccess access, PhotoIntake intake,
                            JobQueue queue) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.access = access;
        this.intake = intake;
        this.queue = queue;
    }

    @PostMapping("/api/events/{eventId}/photos")
    public ResponseEntity<Map<String, Object>> upload(@PathVariable UUID eventId, @RequestParam("file") MultipartFile file,
                                                      HttpServletRequest http) throws IOException {
        access.requireOwner(eventId, http);
        access.requireLive(eventId);

        PhotoIntake.Result result = intake.add(eventId, file.getBytes());
        HttpStatus status = "queued".equals(result.status()) ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result(result.photoId(), result.status()));
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
        return result(photoId, "queued");
    }

    private static Map<String, Object> result(Object id, String status) {
        Map<String, Object> out = new LinkedHashMap<>(); // allows a null id, unlike Map.of
        out.put("id", id);
        out.put("status", status);
        return out;
    }
}
