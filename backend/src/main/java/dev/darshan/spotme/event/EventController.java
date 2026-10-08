package dev.darshan.spotme.event;

import dev.darshan.spotme.auth.AuthInterceptor;
import dev.darshan.spotme.common.ApiException;
import dev.darshan.spotme.common.Tokens;
import dev.darshan.spotme.config.SpotMeProperties;
import dev.darshan.spotme.media.MediaSigner;
import dev.darshan.spotme.privacy.PurgeService;
import dev.darshan.spotme.removal.RemovalService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Organizer side: events, their status, invites, review and deletion. */
@RestController
@RequestMapping("/api/events")
public class EventController {

    static final Duration MAX_RETENTION = Duration.ofDays(90);

    public record CreateEventRequest(
            @NotBlank @Size(max = 80) String title,
            @NotBlank @Size(max = 1000) String purposeNotice,
            @NotNull @Future Instant expiresAt,
            // the organizer confirms every pictured adult agreed BEFORE any face is indexed
            @AssertTrue(message = "must be confirmed before photos can be indexed") boolean consentAttested) {
    }

    public record InviteRequest(@NotBlank @Size(max = 60) String label) {
    }

    public record NoticeRequest(@NotBlank @Size(max = 1000) String purposeNotice) {
    }

    private final JdbcTemplate jdbc;
    private final EventAccess access;
    private final PurgeService purge;
    private final RemovalService removals;
    private final MediaSigner signer;
    private final SpotMeProperties props;

    public EventController(JdbcTemplate jdbc, EventAccess access, PurgeService purge, RemovalService removals,
                           MediaSigner signer, SpotMeProperties props) {
        this.jdbc = jdbc;
        this.access = access;
        this.purge = purge;
        this.removals = removals;
        this.signer = signer;
        this.props = props;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody CreateEventRequest req, HttpServletRequest http) {
        UUID organizerId = AuthInterceptor.organizer(http).organizerId();
        if (req.expiresAt().isAfter(Instant.now().plus(MAX_RETENTION))) {
            throw ApiException.badRequest("expiry_too_far", "Events can be kept for at most 90 days.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO events (id, organizer_id, title, purpose_notice, status, consent_attested_at, expires_at)
                VALUES (?, ?, ?, ?, 'draft', now(), ?)
                """, id, organizerId, req.title().trim(), req.purposeNotice().trim(), Timestamp.from(req.expiresAt()));
        return get(id, http);
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest http) {
        UUID organizerId = AuthInterceptor.organizer(http).organizerId();
        return jdbc.queryForList("""
                SELECT e.id, e.title, e.status, e.expires_at AS "expiresAt", e.created_at AS "createdAt",
                       (SELECT count(*) FROM photos p WHERE p.event_id = e.id AND p.status <> 'purged') AS "photoCount",
                       (SELECT count(*) FROM photos p WHERE p.event_id = e.id AND p.status = 'ready') AS "readyCount"
                FROM events e WHERE e.organizer_id = ? AND e.status <> 'purged'
                ORDER BY e.created_at DESC
                """, organizerId);
    }

    /** Event details plus live pipeline numbers for the progress screen. */
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id, HttpServletRequest http) {
        access.requireOwner(id, http);
        Map<String, Object> event = jdbc.queryForMap("""
                SELECT id, title, purpose_notice AS "purposeNotice", notice_version AS "noticeVersion", status,
                       expires_at AS "expiresAt", created_at AS "createdAt", published_at AS "publishedAt",
                       consent_attested_at AS "consentAttestedAt"
                FROM events WHERE id = ?
                """, id);
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String s : List.of("queued", "processing", "ready", "failed")) {
            counts.put(s, 0L);
        }
        jdbc.query("SELECT status, count(*) FROM photos WHERE event_id = ? AND status <> 'purged' GROUP BY status",
                rs -> {
                    counts.put(rs.getString(1), rs.getLong(2));
                }, id);
        event.put("counts", counts);
        event.put("faces", jdbc.queryForObject("SELECT count(*) FROM faces WHERE event_id = ?", Long.class, id));
        event.put("hidden", jdbc.queryForObject(
                "SELECT count(*) FROM photos WHERE event_id = ? AND (hidden_at IS NOT NULL OR suppressed_at IS NOT NULL) AND status <> 'purged'",
                Long.class, id));
        event.put("pendingRemovals", jdbc.queryForObject(
                "SELECT count(*) FROM removal_requests WHERE event_id = ? AND status IN ('received', 'approved')", Long.class, id));
        event.put("worker", workerStatus());
        return event;
    }

    /**
     * Changing the notice bumps its version. Attendees agreed to the old text, so their
     * sessions stop working until they read and accept the new one.
     */
    @PostMapping("/{id}/notice")
    public Map<String, Object> updateNotice(@PathVariable UUID id, @Valid @RequestBody NoticeRequest req,
                                            HttpServletRequest http) {
        access.requireOwner(id, http);
        access.requireLive(id);
        jdbc.update("UPDATE events SET purpose_notice = ?, notice_version = notice_version + 1 WHERE id = ?",
                req.purposeNotice().trim(), id);
        return get(id, http);
    }

    @PostMapping("/{id}/publish")
    public Map<String, Object> publish(@PathVariable UUID id, HttpServletRequest http) {
        access.requireOwner(id, http);
        int changed = jdbc.update("UPDATE events SET status = 'published', published_at = now() WHERE id = ? AND status = 'draft'", id);
        if (changed == 0) {
            throw ApiException.conflict("not_draft", "Only a draft event can be published.");
        }
        return get(id, http);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> delete(@PathVariable UUID id, HttpServletRequest http) {
        access.requireOwner(id, http);
        purge.startEventDeletion(id);
        return Map.of("status", "deleting");
    }

    // ---------- photos (review) ----------

    @GetMapping("/{id}/photos")
    public List<Map<String, Object>> photos(@PathVariable UUID id, @RequestParam(required = false) String status,
                                            HttpServletRequest http) {
        UUID organizerId = access.requireOwner(id, http);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.id, p.status, p.face_count AS "faceCount", p.error_code AS "errorCode", p.width, p.height,
                       p.hidden_at IS NOT NULL AS hidden, p.suppressed_at IS NOT NULL AS suppressed,
                       (SELECT count(*) FROM wrong_match_reports r WHERE r.photo_id = p.id) AS "wrongMatchReports",
                       p.created_at AS "createdAt"
                FROM photos p
                WHERE p.event_id = ? AND p.status <> 'purged' AND (?::text IS NULL OR p.status = ?::text)
                ORDER BY p.created_at DESC
                LIMIT 1000
                """, id, status, status);
        for (Map<String, Object> row : rows) {
            if (!"failed".equals(row.get("status"))) {
                row.put("thumbUrl", signer.link((UUID) row.get("id"), "thumb", "o:" + organizerId));
            }
        }
        return rows;
    }

    @PostMapping("/{id}/photos/{photoId}/hide")
    public Map<String, Object> hide(@PathVariable UUID id, @PathVariable UUID photoId, HttpServletRequest http) {
        access.requireOwner(id, http);
        jdbc.update("UPDATE photos SET hidden_at = now() WHERE id = ? AND event_id = ?", photoId, id);
        return Map.of("hidden", true);
    }

    @PostMapping("/{id}/photos/{photoId}/unhide")
    public Map<String, Object> unhide(@PathVariable UUID id, @PathVariable UUID photoId, HttpServletRequest http) {
        access.requireOwner(id, http);
        jdbc.update("UPDATE photos SET hidden_at = NULL WHERE id = ? AND event_id = ?", photoId, id);
        return Map.of("hidden", false);
    }

    @DeleteMapping("/{id}/photos/{photoId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> deletePhoto(@PathVariable UUID id, @PathVariable UUID photoId, HttpServletRequest http) {
        access.requireOwner(id, http);
        access.requirePhotoInEvent(photoId, id);
        purge.schedulePhotoPurge(photoId);
        return Map.of("status", "purging");
    }

    // ---------- invites ----------

    /** Creates a personal invite link. The raw token is shown once; only its hash is stored. */
    @PostMapping("/{id}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> invite(@PathVariable UUID id, @Valid @RequestBody InviteRequest req, HttpServletRequest http) {
        access.requireOwner(id, http);
        access.requireLive(id);
        String token = Tokens.newToken();
        UUID membershipId = UUID.randomUUID();
        jdbc.update("INSERT INTO memberships (id, event_id, label, invite_token_hash) VALUES (?, ?, ?, ?)",
                membershipId, id, req.label().trim(), Tokens.sha256Hex(token));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", membershipId);
        out.put("label", req.label().trim());
        out.put("token", token);
        // the token goes after "#", so browsers never send it to a server in the page request
        out.put("joinUrl", props.webOrigin().split(",")[0] + "/join#" + token);
        return out;
    }

    @GetMapping("/{id}/invites")
    public List<Map<String, Object>> invites(@PathVariable UUID id, HttpServletRequest http) {
        access.requireOwner(id, http);
        return jdbc.queryForList("""
                SELECT id, label, consented_at AS "consentedAt", withdrawn_at AS "withdrawnAt",
                       revoked_at AS "revokedAt", created_at AS "createdAt"
                FROM memberships WHERE event_id = ? ORDER BY created_at
                """, id);
    }

    @DeleteMapping("/{id}/invites/{membershipId}")
    public Map<String, Object> revoke(@PathVariable UUID id, @PathVariable UUID membershipId, HttpServletRequest http) {
        access.requireOwner(id, http);
        jdbc.update("UPDATE memberships SET revoked_at = now() WHERE id = ? AND event_id = ?", membershipId, id);
        jdbc.update("DELETE FROM sessions WHERE membership_id = ?", membershipId);
        return Map.of("revoked", true);
    }

    // ---------- removal requests ----------

    @GetMapping("/{id}/removals")
    public List<Map<String, Object>> removals(@PathVariable UUID id, HttpServletRequest http) {
        UUID organizerId = access.requireOwner(id, http);
        List<Map<String, Object>> requests = jdbc.queryForList("""
                SELECT r.id, r.status, r.mode, r.note, r.created_at AS "createdAt", r.decided_at AS "decidedAt",
                       r.completed_at AS "completedAt", m.label AS "requestedBy"
                FROM removal_requests r LEFT JOIN memberships m ON m.id = r.membership_id
                WHERE r.event_id = ? ORDER BY r.created_at DESC
                """, id);
        // one query for all photos of all requests, grouped below
        Map<Object, List<Map<String, Object>>> photosByRequest = new LinkedHashMap<>();
        jdbc.query("""
                SELECT r.id AS request_id, p.id, p.status
                FROM removal_requests r CROSS JOIN unnest(r.photo_ids) AS pid
                JOIN photos p ON p.id = pid
                WHERE r.event_id = ?
                """, rs -> {
            Map<String, Object> photo = new LinkedHashMap<>();
            UUID photoId = rs.getObject("id", UUID.class);
            photo.put("id", photoId);
            photo.put("status", rs.getString("status"));
            if (!"purged".equals(rs.getString("status"))) {
                photo.put("thumbUrl", signer.link(photoId, "thumb", "o:" + organizerId));
            }
            photosByRequest.computeIfAbsent(rs.getObject("request_id"), k -> new ArrayList<>()).add(photo);
        }, id);
        // group photos the guest asked to be erased from, with the face to erase
        Map<Object, List<Map<String, Object>>> editsByRequest = new LinkedHashMap<>();
        jdbc.query("""
                SELECT e.request_id, e.photo_id, e.status, e.box_x, e.box_y, e.box_w, e.box_h, p.status AS photo_status
                FROM photo_edits e JOIN photos p ON p.id = e.photo_id
                WHERE e.event_id = ?
                """, rs -> {
            Map<String, Object> edit = new LinkedHashMap<>();
            UUID photoId = rs.getObject("photo_id", UUID.class);
            edit.put("photoId", photoId);
            edit.put("status", rs.getString("status"));
            edit.put("face", Map.of("x", rs.getInt("box_x"), "y", rs.getInt("box_y"), "w", rs.getInt("box_w"), "h", rs.getInt("box_h")));
            if (!"purged".equals(rs.getString("photo_status"))) {
                edit.put("thumbUrl", signer.link(photoId, "thumb", "o:" + organizerId));
            }
            editsByRequest.computeIfAbsent(rs.getObject("request_id"), k -> new ArrayList<>()).add(edit);
        }, id);
        for (Map<String, Object> request : requests) {
            request.put("photos", photosByRequest.getOrDefault(request.get("id"), List.of()));
            request.put("edits", editsByRequest.getOrDefault(request.get("id"), List.of()));
        }
        return requests;
    }

    /** Approve = solo photos are deleted for good; group photos are edited when erasing is on. */
    @PostMapping("/{id}/removals/{requestId}/approve")
    public Map<String, Object> approve(@PathVariable UUID id, @PathVariable UUID requestId, HttpServletRequest http) {
        access.requireOwner(id, http);
        removals.approve(id, requestId);
        return Map.of("status", "approved");
    }

    /** Reject = the photos come back as they were, unless another open request still covers them. */
    @PostMapping("/{id}/removals/{requestId}/reject")
    public Map<String, Object> reject(@PathVariable UUID id, @PathVariable UUID requestId, HttpServletRequest http) {
        access.requireOwner(id, http);
        removals.reject(id, requestId);
        return Map.of("status", "rejected");
    }

    private Map<String, Object> workerStatus() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT max(last_seen) AS last_seen, bool_or(last_seen > now() - interval '30 seconds') AS active FROM worker_heartbeats");
        Map<String, Object> out = new LinkedHashMap<>();
        Object active = rows.get(0).get("active");
        out.put("active", Boolean.TRUE.equals(active));
        out.put("lastSeen", rows.get(0).get("last_seen"));
        return out;
    }

}
