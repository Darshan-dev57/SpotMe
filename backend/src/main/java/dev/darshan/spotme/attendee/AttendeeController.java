package dev.darshan.spotme.attendee;

import dev.darshan.spotme.auth.AuthInterceptor;
import dev.darshan.spotme.auth.Principal;
import dev.darshan.spotme.auth.SessionService;
import dev.darshan.spotme.common.ApiException;
import dev.darshan.spotme.common.FaceSearchLimiter;
import dev.darshan.spotme.common.Tokens;
import dev.darshan.spotme.config.SpotMeProperties;
import dev.darshan.spotme.media.MediaSigner;
import dev.darshan.spotme.removal.RemovalService;
import dev.darshan.spotme.search.SearchService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Attendee side: open invite, read the notice, agree, search with a selfie,
 * download, report a wrong match, ask for removal, withdraw.
 */
@RestController
public class AttendeeController {

    public record ConsentRequest(@NotNull Integer noticeVersion, boolean agree) {
    }

    public record PhotoIdsRequest(@NotEmpty @Size(max = 200) List<UUID> photoIds) {
    }

    /** The photos the guest was found in; only the ones where they are alone are removed. */
    public record RemovalRequest(@NotEmpty @Size(max = 200) List<@NotNull UUID> photoIds,
                                 @Size(max = 500) String note) {
    }

    public record PhotoRequest(@NotNull UUID photoId) {
    }

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final SearchService search;
    private final MediaSigner signer;
    private final SpotMeProperties props;
    private final RemovalService removals;
    private final FaceSearchLimiter limiter;
    private final PhotoZipper zipper;

    public AttendeeController(JdbcTemplate jdbc, SessionService sessions, SearchService search, MediaSigner signer,
                              SpotMeProperties props, RemovalService removals, PhotoZipper zipper,
                              FaceSearchLimiter limiter) {
        this.jdbc = jdbc;
        this.zipper = zipper;
        this.removals = removals;
        this.sessions = sessions;
        this.search = search;
        this.signer = signer;
        this.props = props;
        this.limiter = limiter;
    }

    // ---------- invite and consent (no session yet: the invite token is the credential) ----------

    /** What the attendee reads BEFORE agreeing: who, why, how long, and what happens to the selfie. */
    @GetMapping("/api/join/{token}")
    public Map<String, Object> notice(@PathVariable String token) {
        Map<String, Object> m = membershipByInvite(token);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventTitle", m.get("title"));
        out.put("organizer", m.get("organizer"));
        out.put("purposeNotice", m.get("purpose_notice"));
        out.put("noticeVersion", m.get("notice_version"));
        out.put("expiresAt", m.get("expires_at"));
        out.put("published", "published".equals(m.get("status")));
        out.put("invitedAs", m.get("label"));
        out.put("withdrawn", m.get("withdrawn_at") != null);
        out.put("sample", Boolean.TRUE.equals(m.get("sample")));
        return out;
    }

    @PostMapping("/api/join/{token}/consent")
    public Map<String, Object> consent(@PathVariable String token, @Valid @RequestBody ConsentRequest req) {
        Map<String, Object> m = membershipByInvite(token);
        if (!req.agree()) {
            throw ApiException.badRequest("consent_required", "You need to agree before searching.");
        }
        if (m.get("withdrawn_at") != null) {
            throw ApiException.forbidden("You withdrew from this event. Ask the organizer for a new invite.");
        }
        if (!req.noticeVersion().equals(m.get("notice_version"))) {
            throw ApiException.conflict("notice_changed", "The notice changed. Please read it again.");
        }
        UUID membershipId = (UUID) m.get("id");
        jdbc.update("UPDATE memberships SET consented_at = now(), consent_notice_version = ? WHERE id = ?",
                req.noticeVersion(), membershipId);
        return Map.of("sessionToken", sessions.startAttendeeSession(membershipId));
    }

    // ---------- search ----------

    /**
     * The selfie is the raw request body (image/jpeg or image/png), not multipart, so it is
     * read straight into memory and never written to a temp file. It is dropped after this call.
     */
    @PostMapping(value = "/api/attendee/search", consumes = {"image/jpeg", "image/png", "application/octet-stream"})
    public ResponseEntity<Map<String, Object>> search(HttpServletRequest http, HttpServletResponse response) throws IOException {
        Principal.Attendee me = AuthInterceptor.attendee(http);
        requirePublished(me.eventId());
        if (!limiter.tryAcquire(me.membershipId())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "slow_down", "Too many searches. Wait a minute and try again.");
        }
        byte[] selfie = readLimited(http.getInputStream(), props.upload().maxBytes());

        SearchService.Result result;
        try {
            result = search.run(me.eventId(), me.membershipId(), selfie, props.search().maxDistance(), props.search().maxResults());
        } catch (SearchService.BadSelfieException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, e.code(), e.getMessage());
        }
        // remember what this guest was shown, so a ZIP download can be checked against it
        zipper.rememberMatches(me.membershipId(), result.matches().stream().map(SearchService.Match::photoId).toList());
        String subject = "m:" + me.membershipId();
        List<Map<String, Object>> photos = new ArrayList<>();
        for (SearchService.Match match : result.matches()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", match.photoId());
            p.put("thumbUrl", signer.link(match.photoId(), "thumb", subject));
            p.put("downloadUrl", signer.link(match.photoId(), "original", subject));
            p.put("faceId", match.faceId());
            p.put("solo", match.solo());
            photos.add(p);
        }
        Long pending = jdbc.queryForObject(
                "SELECT count(*) FROM photos WHERE event_id = ? AND status IN ('queued', 'processing')", Long.class, me.eventId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", photos.size());
        body.put("photos", photos);
        body.put("stillIndexing", pending != null && pending > 0);
        SearchService.Timings t = result.timings();
        response.setHeader("Server-Timing", String.format(java.util.Locale.ROOT,
                "decode;dur=%.1f, faces;dur=%.1f, sql;dur=%.1f", t.decode(), t.faces(), t.sql()));
        return ResponseEntity.ok(body);
    }

    /** Most public photos one page of the guest's "Event photos" section returns. */
    static final int EVENT_PHOTOS_PAGE = 60;

    /**
     * The photos the organizer shared with everyone (venue, stage, crowd), newest first.
     * Private photos never appear here; they only come back to guests whose face matches.
     */
    @GetMapping("/api/attendee/event-photos")
    public Map<String, Object> eventPhotos(@RequestParam(defaultValue = "0") int offset, HttpServletRequest http) {
        Principal.Attendee me = AuthInterceptor.attendee(http);
        requirePublished(me.eventId());
        if (offset < 0) {
            throw ApiException.badRequest("bad_page", "offset must be 0 or more.");
        }
        String visible = "event_id = ? AND is_public AND status = 'ready' AND hidden_at IS NULL AND suppressed_at IS NULL";
        Long total = jdbc.queryForObject("SELECT count(*) FROM photos WHERE " + visible, Long.class, me.eventId());
        List<UUID> ids = jdbc.queryForList("SELECT id FROM photos WHERE " + visible
                + " ORDER BY created_at DESC, id LIMIT ? OFFSET ?", UUID.class, me.eventId(), EVENT_PHOTOS_PAGE, offset);
        String subject = "m:" + me.membershipId();
        List<Map<String, Object>> photos = new ArrayList<>();
        for (UUID id : ids) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", id);
            p.put("thumbUrl", signer.link(id, "thumb", subject));
            p.put("downloadUrl", signer.link(id, "original", subject));
            photos.add(p);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", total);
        body.put("photos", photos);
        return body;
    }

    // ---------- after the search ----------

    /** Hides one photo from this attendee's results and flags it for the organizer. */
    @PostMapping("/api/attendee/wrong-match")
    public Map<String, Object> wrongMatch(@Valid @RequestBody PhotoRequest req, HttpServletRequest http) {
        Principal.Attendee me = AuthInterceptor.attendee(http);
        requirePhotosInEvent(List.of(req.photoId()), me.eventId());
        jdbc.update("INSERT INTO wrong_match_reports (membership_id, photo_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                me.membershipId(), req.photoId());
        return Map.of("reported", true);
    }

    /**
     * "Remove me": the guest picks what happens to the photos they were found in (see RemovalService).
     * The affected photos are hidden from everyone right away (reversible); the organizer then
     * approves or rejects. A lookalike selfie alone never deletes or edits anything.
     */
    @PostMapping("/api/attendee/removals")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> requestRemoval(@Valid @RequestBody RemovalRequest req, HttpServletRequest http) {
        Principal.Attendee me = AuthInterceptor.attendee(http);
        RemovalService.Created created = removals.request(me.eventId(), me.membershipId(), req.photoIds(), req.note());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", created.id());
        out.put("status", "received");
        out.put("hidden", created.hidden());
        out.put("keptGroupPhotos", created.keptGroupPhotos());
        return out;
    }

    @GetMapping("/api/attendee/removals")
    public List<Map<String, Object>> myRemovals(HttpServletRequest http) {
        Principal.Attendee me = AuthInterceptor.attendee(http);
        return jdbc.queryForList("""
                SELECT r.id, r.status, r.mode, cardinality(r.photo_ids) AS photos,
                       r.created_at AS "createdAt", r.completed_at AS "completedAt"
                FROM removal_requests r WHERE r.membership_id = ? ORDER BY r.created_at DESC
                """, me.membershipId());
    }

    /** Withdraw consent: this invite stops working at once, every session is ended and alerts are deleted. */
    @PostMapping("/api/attendee/withdraw")
    public Map<String, Object> withdraw(HttpServletRequest http) {
        Principal.Attendee me = AuthInterceptor.attendee(http);
        jdbc.update("UPDATE memberships SET withdrawn_at = now() WHERE id = ?", me.membershipId());
        jdbc.update("DELETE FROM photo_alerts WHERE membership_id = ?", me.membershipId());
        zipper.forget(me.membershipId());
        sessions.endAllForMembership(me.membershipId());
        return Map.of("withdrawn", true);
    }

    /** Streams the chosen photos as one ZIP. Photos the guest may not see are left out. */
    @PostMapping(value = "/api/attendee/download", produces = "application/zip")
    public void downloadZip(@Valid @RequestBody PhotoIdsRequest req, HttpServletRequest http,
                            HttpServletResponse response) throws IOException {
        Principal.Attendee me = AuthInterceptor.attendee(http);
        requirePublished(me.eventId());
        List<String> keys = zipper.chosen(me.membershipId(), me.eventId(), req.photoIds());
        zipper.stream(me.membershipId(), keys, zipName(me.eventId()), response);
    }

    /**
     * A two-minute link for "Download all". Opening it lets the browser save the ZIP straight to
     * disk (a fetch would have to hold the whole file in memory first, which a phone may not manage).
     */
    @PostMapping("/api/attendee/download-link")
    public Map<String, Object> downloadLink(HttpServletRequest http) {
        Principal.Attendee me = AuthInterceptor.attendee(http);
        requirePublished(me.eventId());
        int count = zipper.allMatches(me.membershipId(), me.eventId()).size();
        if (count == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "nothing_to_download", "Search with a selfie first.");
        }
        return Map.of("url", signer.zipLink(me.membershipId()), "count", count);
    }

    /** Every photo from the guest's last search, as one ZIP. The signed link is the credential. */
    @GetMapping(value = "/api/zip/{membershipId}", produces = "application/zip")
    public void downloadAll(@PathVariable UUID membershipId, @RequestParam long exp, @RequestParam String sig,
                            HttpServletResponse response) throws IOException {
        if (!signer.verifyZip(membershipId, exp, sig)) {
            throw ApiException.forbidden("This download link has expired. Go back and tap Download again.");
        }
        List<UUID> events = jdbc.queryForList("""
                SELECT m.event_id FROM memberships m JOIN events e ON e.id = m.event_id
                WHERE m.id = ? AND m.consented_at IS NOT NULL AND m.consent_notice_version = e.notice_version
                  AND m.withdrawn_at IS NULL AND m.revoked_at IS NULL
                  AND e.status = 'published' AND e.expires_at > now()
                """, UUID.class, membershipId);
        if (events.isEmpty()) {
            throw ApiException.forbidden("You no longer have access to this event.");
        }
        UUID eventId = events.get(0);
        zipper.stream(membershipId, zipper.allMatches(membershipId, eventId), zipName(eventId), response);
    }

    private String zipName(UUID eventId) {
        String title = jdbc.queryForObject("SELECT title FROM events WHERE id = ?", String.class, eventId);
        String slug = title == null ? "" : title.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return (slug.isEmpty() ? "spotme" : slug) + "-photos.zip";
    }

    // ---------- helpers ----------

    private Map<String, Object> membershipByInvite(String token) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT m.id, m.label, m.withdrawn_at, e.title, e.purpose_notice, e.notice_version, e.expires_at,
                       e.status, e.sample, o.display_name AS organizer
                FROM memberships m JOIN events e ON e.id = m.event_id JOIN organizers o ON o.id = e.organizer_id
                WHERE m.invite_token_hash = ? AND m.revoked_at IS NULL
                  AND e.status IN ('draft', 'published') AND e.expires_at > now()
                """, Tokens.sha256Hex(token));
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "invite_invalid", "This invite link is not valid or the event has ended.");
        }
        return rows.get(0);
    }

    private void requirePublished(UUID eventId) {
        String status = jdbc.queryForObject("SELECT status FROM events WHERE id = ?", String.class, eventId);
        if (!"published".equals(status)) {
            throw ApiException.conflict("not_published", "The organizer hasn't published the photos yet. Check back soon.");
        }
    }

    private void requirePhotosInEvent(List<UUID> photoIds, UUID eventId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM photos WHERE event_id = ? AND id = ANY (?) AND status <> 'purged'",
                Integer.class, eventId, photoIds.stream().distinct().toArray(UUID[]::new));
        if (n == null || n != photoIds.stream().distinct().count()) {
            throw ApiException.notFound("Photo");
        }
    }

    static byte[] readLimited(InputStream in, long max) throws IOException {
        byte[] bytes = in.readNBytes((int) max + 1);
        if (bytes.length > max) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large", "That photo is larger than 20 MB.");
        }
        return bytes;
    }
}
