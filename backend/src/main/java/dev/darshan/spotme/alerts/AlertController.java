package dev.darshan.spotme.alerts;

import dev.darshan.spotme.auth.AuthInterceptor;
import dev.darshan.spotme.auth.Principal;
import dev.darshan.spotme.common.ApiException;
import dev.darshan.spotme.common.RateLimiter;
import dev.darshan.spotme.common.Tokens;
import dev.darshan.spotme.config.SpotMeProperties;
import dev.darshan.spotme.face.FaceEngine;
import dev.darshan.spotme.face.Vectors;
import dev.darshan.spotme.media.MediaSigner;
import dev.darshan.spotme.search.SearchService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * New-photo alerts, in the app only (no email or SMS).
 *
 * After a search a guest can opt in. Unlike a normal search, their face vector is then kept, in
 * photo_alerts, so later uploads can be checked against it. They get a private link that shows how many
 * new photos of them have appeared since they last looked. The vector is deleted when they turn alerts
 * off, withdraw, the organizer changes the notice or revokes them, or the event is deleted.
 */
@RestController
public class AlertController {

    public record SeenRequest(Instant until) {
    }

    private final JdbcTemplate jdbc;
    private final SearchService search;
    private final MediaSigner signer;
    private final SpotMeProperties props;
    private final RateLimiter<UUID> limiter;

    public AlertController(JdbcTemplate jdbc, SearchService search, MediaSigner signer, SpotMeProperties props) {
        this.jdbc = jdbc;
        this.search = search;
        this.signer = signer;
        this.props = props;
        this.limiter = new RateLimiter<>(props.search().perMinuteLimit());
    }

    /** Opt in. The body is the same selfie the guest just searched with; a new link replaces an old one. */
    @PostMapping(value = "/api/attendee/alerts", consumes = {"image/jpeg", "image/png", "application/octet-stream"})
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(HttpServletRequest http) throws IOException {
        Principal.Attendee me = AuthInterceptor.attendee(http);
        requireLiveEvent(me.eventId());
        if (!limiter.tryAcquire(me.membershipId())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "slow_down", "Too many tries. Wait a minute and try again.");
        }
        byte[] selfie = http.getInputStream().readNBytes((int) props.upload().maxBytes() + 1);
        if (selfie.length > props.upload().maxBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large", "That photo is larger than 20 MB.");
        }
        float[] vector;
        try {
            vector = search.selfieVector(selfie, new long[2]);
        } catch (SearchService.BadSelfieException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, e.code(), e.getMessage());
        }
        String token = Tokens.newToken();
        jdbc.update("""
                INSERT INTO photo_alerts (id, membership_id, event_id, token_hash, embedding, model_version)
                VALUES (?, ?, ?, ?, ?::vector, ?)
                ON CONFLICT (membership_id) DO UPDATE
                SET token_hash = excluded.token_hash, embedding = excluded.embedding,
                    model_version = excluded.model_version, seen_until = now(), created_at = now()
                """, UUID.randomUUID(), me.membershipId(), me.eventId(), Tokens.sha256Hex(token),
                Vectors.toPgVector(vector), FaceEngine.MODEL_VERSION);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        // like invite links, the token goes after "#" so it never reaches a server log
        out.put("url", props.webOrigin().split(",")[0] + "/alerts#" + token);
        return out;
    }

    /** What the private link shows: the photos of the guest indexed since they last looked. */
    @GetMapping("/api/alerts/{token}")
    public Map<String, Object> check(@PathVariable String token) {
        Map<String, Object> alert = alertByToken(token);
        UUID alertId = (UUID) alert.get("id");
        if (!limiter.tryAcquire(alertId)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "slow_down", "Too many checks. Wait a minute and try again.");
        }
        UUID membershipId = (UUID) alert.get("membership_id");
        Instant seenUntil = ((Timestamp) alert.get("seen_until")).toInstant();
        Instant checkedAt = Instant.now();
        List<SearchService.Match> matches = search.search((UUID) alert.get("event_id"), membershipId,
                Vectors.fromPgVector((String) alert.get("vector")), props.search().maxDistance(),
                props.search().maxResults(), seenUntil);

        String subject = "m:" + membershipId;
        List<Map<String, Object>> photos = new ArrayList<>();
        for (SearchService.Match match : matches) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", match.photoId());
            p.put("thumbUrl", signer.link(match.photoId(), "thumb", subject));
            p.put("downloadUrl", signer.link(match.photoId(), "original", subject));
            photos.add(p);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventTitle", alert.get("title"));
        out.put("expiresAt", alert.get("expires_at"));
        out.put("seenUntil", seenUntil);
        out.put("checkedAt", checkedAt);
        out.put("newCount", photos.size());
        out.put("photos", photos);
        return out;
    }

    /** "Mark as seen": photos up to the moment of the last check stop counting as new. */
    @PostMapping("/api/alerts/{token}/seen")
    public Map<String, Object> seen(@PathVariable String token, @RequestBody(required = false) SeenRequest req) {
        Map<String, Object> alert = alertByToken(token);
        Instant until = req == null || req.until() == null || req.until().isAfter(Instant.now()) ? Instant.now() : req.until();
        jdbc.update("UPDATE photo_alerts SET seen_until = greatest(seen_until, ?) WHERE id = ?",
                Timestamp.from(until), alert.get("id"));
        return Map.of("seenUntil", until);
    }

    /** Turn alerts off: the stored face vector is deleted at once. */
    @DeleteMapping("/api/alerts/{token}")
    public Map<String, Object> delete(@PathVariable String token) {
        int deleted = jdbc.update("DELETE FROM photo_alerts WHERE token_hash = ?", Tokens.sha256Hex(token));
        if (deleted == 0) {
            throw ApiException.notFound("Alert");
        }
        return Map.of("deleted", true);
    }

    private Map<String, Object> alertByToken(String token) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id, a.membership_id, a.event_id, a.embedding::text AS vector, a.seen_until,
                       e.title, e.expires_at
                FROM photo_alerts a
                JOIN events e ON e.id = a.event_id
                JOIN memberships m ON m.id = a.membership_id
                WHERE a.token_hash = ? AND a.model_version = ?
                  AND e.status = 'published' AND e.expires_at > now()
                  AND m.consented_at IS NOT NULL AND m.withdrawn_at IS NULL AND m.revoked_at IS NULL
                """, Tokens.sha256Hex(token), FaceEngine.MODEL_VERSION);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "alert_gone", "This link no longer works.");
        }
        return rows.get(0);
    }

    private void requireLiveEvent(UUID eventId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM events WHERE id = ? AND status = 'published' AND expires_at > now()", Integer.class, eventId);
        if (n == null || n == 0) {
            throw ApiException.conflict("not_published", "Alerts are available once the album is published.");
        }
    }
}
