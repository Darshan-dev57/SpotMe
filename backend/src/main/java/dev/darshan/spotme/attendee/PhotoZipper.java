package dev.darshan.spotme.attendee;

import dev.darshan.spotme.common.ApiException;
import dev.darshan.spotme.common.RateLimiter;
import dev.darshan.spotme.media.PhotoStorage;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Streams a guest's photos as one ZIP, written straight to the response one file at a time:
 * no temp file and never more than one copy buffer in memory, whatever the number of photos.
 * A guest may only download what they are allowed to see: photos their last search showed
 * them (kept in guest_matches) and photos the organizer made public.
 */
@Service
public class PhotoZipper {

    /** ZIPs one guest can start per minute. */
    static final int PER_MINUTE = 3;
    /** ZIPs running at the same time on the whole server; each one keeps a connection and the disk busy. */
    static final int AT_ONCE = 2;

    private static final Logger log = LoggerFactory.getLogger(PhotoZipper.class);

    /** Ready, visible and not reported as "not me" by this guest. */
    private static final String VISIBLE = """
            p.status = 'ready' AND p.hidden_at IS NULL AND p.suppressed_at IS NULL
            AND NOT EXISTS (SELECT 1 FROM wrong_match_reports r WHERE r.membership_id = ? AND r.photo_id = p.id)
            """;

    private final JdbcTemplate jdbc;
    private final PhotoStorage storage;
    private final RateLimiter<UUID> limiter = new RateLimiter<>(PER_MINUTE);
    private final Semaphore running = new Semaphore(AT_ONCE);

    public PhotoZipper(JdbcTemplate jdbc, PhotoStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    /** Remembers what one search showed the guest. The previous search's list is replaced. */
    public void rememberMatches(UUID membershipId, List<UUID> photoIds) {
        jdbc.update("DELETE FROM guest_matches WHERE membership_id = ?", membershipId);
        jdbc.batchUpdate("INSERT INTO guest_matches (membership_id, photo_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                photoIds, 100, (ps, photoId) -> {
                    ps.setObject(1, membershipId);
                    ps.setObject(2, photoId);
                });
    }

    public void forget(UUID membershipId) {
        jdbc.update("DELETE FROM guest_matches WHERE membership_id = ?", membershipId);
    }

    /** Storage keys of every photo the guest's last search returned that they can still see. */
    public List<String> allMatches(UUID membershipId, UUID eventId) {
        return jdbc.queryForList("SELECT p.object_key FROM guest_matches g JOIN photos p ON p.id = g.photo_id "
                        + "WHERE g.membership_id = ? AND p.event_id = ? AND " + VISIBLE + " ORDER BY p.created_at, p.id",
                String.class, membershipId, eventId, membershipId);
    }

    /** Storage keys of the chosen photos the guest may see; ids they may not see are left out. */
    public List<String> chosen(UUID membershipId, UUID eventId, List<UUID> photoIds) {
        return jdbc.queryForList("SELECT p.object_key FROM photos p WHERE p.event_id = ? AND p.id = ANY (?) AND " + VISIBLE
                        + " AND (p.is_public OR EXISTS (SELECT 1 FROM guest_matches g WHERE g.membership_id = ? AND g.photo_id = p.id))"
                        + " ORDER BY p.created_at, p.id",
                String.class, eventId, photoIds.stream().distinct().toArray(UUID[]::new), membershipId, membershipId);
    }

    /** Writes the ZIP to the response. Checks the per-guest and server-wide limits first. */
    public void stream(UUID membershipId, List<String> keys, String fileName, HttpServletResponse response) throws IOException {
        if (keys.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "nothing_to_download", "There are no photos to download.");
        }
        if (!limiter.tryAcquire(membershipId)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "slow_down", "Too many downloads. Wait a minute and try again.");
        }
        if (!running.tryAcquire()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "busy", "Other downloads are running. Try again in a minute.");
        }
        try {
            response.setContentType("application/zip");
            response.setHeader("Content-Disposition",
                    ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString());
            response.setHeader("Cache-Control", "no-store");
            try (ZipOutputStream zip = new ZipOutputStream(response.getOutputStream())) {
                // the photos are JPEGs, already compressed: storing them saves CPU on a 2-core box
                zip.setLevel(Deflater.NO_COMPRESSION);
                int n = 0;
                for (String key : keys) {
                    try (InputStream in = storage.open(key)) {
                        zip.putNextEntry(new ZipEntry(String.format("photo-%03d.jpg", ++n)));
                        in.transferTo(zip);
                        zip.closeEntry();
                    } catch (NoSuchFileException e) {
                        // deleted after the list was read (a purge raced the download): skip it
                        log.info("Skipped a photo deleted during a ZIP download");
                    }
                }
            }
        } finally {
            running.release();
        }
    }
}
