package dev.darshan.spotme.event;

import dev.darshan.spotme.auth.AuthInterceptor;
import dev.darshan.spotme.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** Small permission checks shared by the organizer endpoints. */
@Component
public class EventAccess {

    private final JdbcTemplate jdbc;

    public EventAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 404 (not 403) for someone else's event, so event ids can't be probed. Returns the organizer id. */
    public UUID requireOwner(UUID eventId, HttpServletRequest http) {
        UUID organizerId = AuthInterceptor.organizer(http).organizerId();
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM events WHERE id = ? AND organizer_id = ? AND status <> 'purged'",
                Integer.class, eventId, organizerId);
        if (n == null || n == 0) {
            throw ApiException.notFound("Event");
        }
        return organizerId;
    }

    /** Uploads and invites are only allowed while the event is draft/published and not expired. */
    public void requireLive(UUID eventId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM events WHERE id = ? AND status IN ('draft', 'published') AND expires_at > now()",
                Integer.class, eventId);
        if (n == null || n == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "event_closed", "This event is expired or being deleted.");
        }
    }

    public void requirePhotoInEvent(UUID photoId, UUID eventId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM photos WHERE id = ? AND event_id = ?",
                Integer.class, photoId, eventId);
        if (n == null || n == 0) {
            throw ApiException.notFound("Photo");
        }
    }

    public UUID[] pendingRemoval(UUID requestId, UUID eventId) {
        List<Array> rows = jdbc.queryForList(
                "SELECT photo_ids FROM removal_requests WHERE id = ? AND event_id = ? AND status = 'received'",
                Array.class, requestId, eventId);
        if (rows.isEmpty()) {
            throw ApiException.conflict("not_pending", "This request was already decided.");
        }
        try {
            return (UUID[]) rows.get(0).getArray();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
