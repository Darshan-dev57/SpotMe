package dev.darshan.spotme.removal;

import dev.darshan.spotme.common.ApiException;
import dev.darshan.spotme.event.EventAccess;
import dev.darshan.spotme.privacy.PurgeService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A guest asks to be removed, the organizer approves or rejects.
 *
 * Only photos where the guest is the one detected face are removed: they are hidden at once and
 * deleted when the organizer approves. Group photos stay as they are.
 */
@Service
public class RemovalService {

    public record Created(UUID id, int hidden, int keptGroupPhotos) {
    }

    private final JdbcTemplate jdbc;
    private final EventAccess access;
    private final PurgeService purge;

    public RemovalService(JdbcTemplate jdbc, EventAccess access, PurgeService purge) {
        this.jdbc = jdbc;
        this.access = access;
        this.purge = purge;
    }

    /** @param photoIds the photos the guest was found in (from their search result) */
    @Transactional
    public Created request(UUID eventId, UUID membershipId, List<UUID> photoIds, String note) {
        Map<UUID, Integer> faceCounts = new HashMap<>();
        jdbc.query("SELECT id, coalesce(face_count, 0) FROM photos WHERE event_id = ? AND id = ANY (?) AND status <> 'purged'",
                rs -> {
                    faceCounts.put(rs.getObject(1, UUID.class), rs.getInt(2));
                }, eventId, photoIds.stream().distinct().toArray(UUID[]::new));
        if (faceCounts.size() != photoIds.stream().distinct().count()) {
            throw ApiException.notFound("Photo");
        }
        List<UUID> solo = new ArrayList<>();
        faceCounts.forEach((photoId, faces) -> {
            if (faces <= 1) {
                solo.add(photoId);
            }
        });
        if (solo.isEmpty()) {
            throw ApiException.badRequest("no_solo_photos",
                    "You are not alone in any of these photos, so there is nothing to remove. Group photos stay as they are.");
        }

        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO removal_requests (id, event_id, membership_id, photo_ids, note, status, mode)
                VALUES (?, ?, ?, ?, ?, 'received', 'solo')
                """, id, eventId, membershipId, solo.toArray(UUID[]::new), note);
        jdbc.update("UPDATE photos SET suppressed_at = coalesce(suppressed_at, now()) WHERE id = ANY (?)",
                (Object) solo.toArray(UUID[]::new));
        return new Created(id, solo.size(), faceCounts.size() - solo.size());
    }

    /** Approve: the solo photos are deleted for good. */
    @Transactional
    public void approve(UUID eventId, UUID requestId) {
        UUID[] photoIds = access.pendingRemoval(requestId, eventId);
        jdbc.update("UPDATE removal_requests SET status = 'approved', decided_at = now() WHERE id = ?", requestId);
        for (UUID photoId : photoIds) {
            purge.schedulePhotoPurge(photoId);
        }
    }

    /** Reject: nothing is deleted, and the photos come back unless another request still covers them. */
    @Transactional
    public void reject(UUID eventId, UUID requestId) {
        UUID[] photoIds = access.pendingRemoval(requestId, eventId);
        jdbc.update("UPDATE removal_requests SET status = 'rejected', decided_at = now() WHERE id = ?", requestId);
        for (UUID photoId : photoIds) {
            showIfNothingOpen(photoId);
        }
    }

    /** Un-hides a photo unless an open request still wants it deleted. */
    public void showIfNothingOpen(UUID photoId) {
        jdbc.update("""
                UPDATE photos p SET suppressed_at = NULL
                WHERE p.id = ? AND p.status <> 'purged'
                  AND NOT EXISTS (SELECT 1 FROM removal_requests r
                                  WHERE r.status IN ('received', 'approved') AND p.id = ANY (r.photo_ids))
                """, photoId);
    }
}
