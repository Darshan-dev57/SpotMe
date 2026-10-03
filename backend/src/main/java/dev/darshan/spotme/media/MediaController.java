package dev.darshan.spotme.media;

import dev.darshan.spotme.common.ApiException;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** The only way photo bytes leave the server. */
@RestController
public class MediaController {

    private final MediaSigner signer;
    private final JdbcTemplate jdbc;
    private final PhotoStorage storage;

    public MediaController(MediaSigner signer, JdbcTemplate jdbc, PhotoStorage storage) {
        this.signer = signer;
        this.jdbc = jdbc;
        this.storage = storage;
    }

    @GetMapping("/api/media/{photoId}/{variant}")
    public ResponseEntity<byte[]> media(@PathVariable UUID photoId, @PathVariable String variant,
                                        @RequestParam String sub, @RequestParam long exp,
                                        @RequestParam String sig) throws IOException {
        if (!variant.equals("thumb") && !variant.equals("original")) {
            throw ApiException.notFound("Photo");
        }
        if (!signer.verify(photoId, variant, sub, exp, sig)) {
            throw ApiException.forbidden("This link has expired. Refresh the page.");
        }
        String key = allowedKey(photoId, variant, sub);
        if (key == null) {
            throw ApiException.forbidden("You no longer have access to this photo.");
        }
        byte[] bytes;
        try {
            bytes = storage.get(key);
        } catch (java.nio.file.NoSuchFileException e) {
            throw ApiException.notFound("Photo");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_JPEG);
        headers.setCacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate());
        if (variant.equals("original")) {
            headers.setContentDisposition(ContentDisposition.attachment().filename("spotme-" + photoId + ".jpg").build());
        }
        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    /** Live access check, so revoking access or hiding a photo works even for links already handed out. */
    private String allowedKey(UUID photoId, String variant, String sub) {
        String column = variant.equals("thumb") ? "p.thumb_key" : "p.object_key";
        List<String> keys;
        if (sub.startsWith("o:")) {
            UUID organizerId = parseUuid(sub.substring(2));
            keys = jdbc.queryForList("SELECT " + column + " FROM photos p JOIN events e ON e.id = p.event_id "
                    + "WHERE p.id = ? AND e.organizer_id = ? AND p.status <> 'purged' AND e.status IN ('draft', 'published')",
                    String.class, photoId, organizerId);
        } else if (sub.startsWith("m:")) {
            UUID membershipId = parseUuid(sub.substring(2));
            keys = jdbc.queryForList("SELECT " + column + """
                     FROM photos p
                    JOIN events e ON e.id = p.event_id
                    JOIN memberships m ON m.event_id = e.id
                    WHERE p.id = ? AND m.id = ?
                      AND m.consented_at IS NOT NULL AND m.withdrawn_at IS NULL AND m.revoked_at IS NULL
                      AND e.status = 'published' AND e.expires_at > now()
                      AND p.status = 'ready' AND p.hidden_at IS NULL AND p.suppressed_at IS NULL
                    """, String.class, photoId, membershipId);
        } else {
            return null;
        }
        return keys.isEmpty() ? null : keys.get(0);
    }

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw ApiException.forbidden("Bad link.");
        }
    }
}
