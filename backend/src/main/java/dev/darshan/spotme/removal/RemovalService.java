package dev.darshan.spotme.removal;

import dev.darshan.spotme.common.ApiException;
import dev.darshan.spotme.config.SpotMeProperties;
import dev.darshan.spotme.event.EventAccess;
import dev.darshan.spotme.jobs.JobKind;
import dev.darshan.spotme.jobs.JobQueue;
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
 * The guest picks one of two choices for the photos they were found in:
 * - solo:  photos where they are the only face are hidden now and deleted when approved.
 *          Group photos stay as they are.
 * - erase: the same for solo photos, and they are erased from group photos. A group photo is hidden
 *          until the edited copy replaces it, so nobody sees the original in the meantime.
 */
@Service
public class RemovalService {

    public record Created(UUID id, int hidden, int erasing, int keptGroupPhotos) {
    }

    private final JdbcTemplate jdbc;
    private final EventAccess access;
    private final PurgeService purge;
    private final JobQueue queue;
    private final boolean eraseEnabled;
    private final int maxAttempts;

    public RemovalService(JdbcTemplate jdbc, EventAccess access, PurgeService purge, JobQueue queue, SpotMeProperties props) {
        this.jdbc = jdbc;
        this.access = access;
        this.purge = purge;
        this.queue = queue;
        this.eraseEnabled = props.erase().enabled();
        this.maxAttempts = props.worker().maxAttempts();
    }

    public boolean eraseEnabled() {
        return eraseEnabled;
    }

    /**
     * @param faceByPhoto each photo the guest was found in, with the face that matched them
     *                    (from the search result; only needed for group photos when erasing)
     */
    @Transactional
    public Created request(UUID eventId, UUID membershipId, String mode, Map<UUID, Long> faceByPhoto, String note) {
        boolean erase = mode.equals("erase");
        if (erase && !eraseEnabled) {
            throw ApiException.conflict("erase_unavailable", "Erasing you from group photos is not available yet.");
        }
        Map<UUID, Integer> faceCounts = new HashMap<>();
        jdbc.query("SELECT id, coalesce(face_count, 0) FROM photos WHERE event_id = ? AND id = ANY (?) AND status <> 'purged'",
                rs -> {
                    faceCounts.put(rs.getObject(1, UUID.class), rs.getInt(2));
                }, eventId, faceByPhoto.keySet().toArray(UUID[]::new));
        if (faceCounts.size() != faceByPhoto.size()) {
            throw ApiException.notFound("Photo");
        }
        List<UUID> solo = new ArrayList<>();
        List<UUID> group = new ArrayList<>();
        faceCounts.forEach((photoId, faces) -> (faces <= 1 ? solo : group).add(photoId));
        if (solo.isEmpty() && (!erase || group.isEmpty())) {
            throw ApiException.badRequest("no_solo_photos",
                    "You are not alone in any of these photos, so there is nothing to remove. Group photos stay as they are.");
        }

        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO removal_requests (id, event_id, membership_id, photo_ids, note, status, mode)
                VALUES (?, ?, ?, ?, ?, 'received', ?)
                """, id, eventId, membershipId, solo.toArray(UUID[]::new), note, mode);
        hide(solo);
        if (erase) {
            for (UUID photoId : group) {
                addEdit(id, eventId, photoId, faceByPhoto.get(photoId));
            }
            hide(group);
        }
        return new Created(id, solo.size(), erase ? group.size() : 0, erase ? 0 : group.size());
    }

    private void addEdit(UUID requestId, UUID eventId, UUID photoId, Long faceId) {
        List<Map<String, Object>> box = faceId == null ? List.of() : jdbc.queryForList(
                "SELECT box_x, box_y, box_w, box_h FROM faces WHERE id = ? AND photo_id = ?", faceId, photoId);
        if (box.isEmpty()) {
            throw ApiException.badRequest("bad_face", "Search again, then try once more.");
        }
        Map<String, Object> b = box.get(0);
        jdbc.update("""
                INSERT INTO photo_edits (id, request_id, event_id, photo_id, face_id, box_x, box_y, box_w, box_h, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'waiting')
                """, UUID.randomUUID(), requestId, eventId, photoId, faceId,
                b.get("box_x"), b.get("box_y"), b.get("box_w"), b.get("box_h"));
    }

    private void hide(List<UUID> photoIds) {
        if (!photoIds.isEmpty()) {
            jdbc.update("UPDATE photos SET suppressed_at = coalesce(suppressed_at, now()) WHERE id = ANY (?)",
                    (Object) photoIds.toArray(UUID[]::new));
        }
    }

    /** Approve: solo photos are deleted for good, group photos get their erase job (when erasing is on). */
    @Transactional
    public void approve(UUID eventId, UUID requestId) {
        UUID[] photoIds = access.pendingRemoval(requestId, eventId);
        jdbc.update("UPDATE removal_requests SET status = 'approved', decided_at = now() WHERE id = ?", requestId);
        for (UUID photoId : photoIds) {
            purge.schedulePhotoPurge(photoId);
        }
        if (eraseEnabled) {
            List<UUID> edits = jdbc.queryForList(
                    "SELECT id FROM photo_edits WHERE request_id = ? AND status = 'waiting'", UUID.class, requestId);
            for (UUID editId : edits) {
                jdbc.update("UPDATE photo_edits SET status = 'queued' WHERE id = ?", editId);
                queue.enqueue(JobKind.ERASE_PERSON, editId, "erase:" + editId, maxAttempts);
            }
        }
    }

    /** Reject: nothing is deleted or edited, and the photos come back unless another request still covers them. */
    @Transactional
    public void reject(UUID eventId, UUID requestId) {
        access.pendingRemoval(requestId, eventId);
        jdbc.update("UPDATE removal_requests SET status = 'rejected', decided_at = now() WHERE id = ?", requestId);
        jdbc.update("UPDATE photo_edits SET status = 'cancelled' WHERE request_id = ? AND status IN ('waiting', 'queued')",
                requestId);
        List<UUID> photos = jdbc.queryForList("""
                SELECT unnest(photo_ids) FROM removal_requests WHERE id = ?
                UNION SELECT photo_id FROM photo_edits WHERE request_id = ?
                """, UUID.class, requestId, requestId);
        for (UUID photoId : photos) {
            showIfNothingOpen(photoId);
        }
    }

    /** Un-hides a photo unless an open request still wants it deleted or edited. */
    public void showIfNothingOpen(UUID photoId) {
        jdbc.update("""
                UPDATE photos p SET suppressed_at = NULL
                WHERE p.id = ? AND p.status <> 'purged'
                  AND NOT EXISTS (SELECT 1 FROM removal_requests r
                                  WHERE r.status IN ('received', 'approved') AND p.id = ANY (r.photo_ids))
                  AND NOT EXISTS (SELECT 1 FROM photo_edits e
                                  WHERE e.photo_id = p.id AND e.status IN ('waiting', 'queued'))
                """, photoId);
    }

    /** Marks a request complete once its photos are deleted and its edits are done. */
    public void completeIfFinished(UUID requestId) {
        jdbc.update("""
                UPDATE removal_requests r SET status = 'completed', completed_at = now()
                WHERE r.id = ? AND r.status = 'approved'
                  AND NOT EXISTS (SELECT 1 FROM photos p WHERE p.id = ANY (r.photo_ids) AND p.status <> 'purged')
                  AND NOT EXISTS (SELECT 1 FROM photo_edits e WHERE e.request_id = r.id AND e.status IN ('waiting', 'queued'))
                """, requestId);
    }
}
