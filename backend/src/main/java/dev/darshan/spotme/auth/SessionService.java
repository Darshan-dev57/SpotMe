package dev.darshan.spotme.auth;

import dev.darshan.spotme.common.Tokens;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Opaque bearer sessions. The client keeps the raw token; the database only keeps
 * its SHA-256, so a leaked database dump cannot be replayed as a login.
 */
@Service
public class SessionService {

    static final Duration ORGANIZER_TTL = Duration.ofDays(7);
    static final Duration ATTENDEE_TTL = Duration.ofDays(2);

    private final JdbcTemplate jdbc;

    public SessionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String startOrganizerSession(UUID organizerId) {
        String token = Tokens.newToken();
        jdbc.update("INSERT INTO sessions (token_hash, organizer_id, expires_at) VALUES (?, ?, ?)",
                Tokens.sha256Hex(token), organizerId, Timestamp.from(Instant.now().plus(ORGANIZER_TTL)));
        return token;
    }

    public String startAttendeeSession(UUID membershipId) {
        String token = Tokens.newToken();
        jdbc.update("INSERT INTO sessions (token_hash, membership_id, expires_at) VALUES (?, ?, ?)",
                Tokens.sha256Hex(token), membershipId, Timestamp.from(Instant.now().plus(ATTENDEE_TTL)));
        return token;
    }

    /**
     * Looks the token up and re-checks the attendee's live access every time, so a
     * withdrawn, revoked or expired membership stops working immediately.
     */
    public Optional<Principal> resolve(String token) {
        List<Principal> rows = jdbc.query("""
                SELECT s.organizer_id, s.membership_id, m.event_id
                FROM sessions s
                LEFT JOIN memberships m ON m.id = s.membership_id
                LEFT JOIN events e ON e.id = m.event_id
                WHERE s.token_hash = ? AND s.expires_at > now()
                  AND (s.organizer_id IS NOT NULL
                       OR (m.consented_at IS NOT NULL AND m.withdrawn_at IS NULL AND m.revoked_at IS NULL
                           AND e.status IN ('draft', 'published') AND e.expires_at > now()))
                """, (rs, i) -> {
            UUID organizerId = rs.getObject("organizer_id", UUID.class);
            if (organizerId != null) {
                return new Principal.Organizer(organizerId);
            }
            return new Principal.Attendee(rs.getObject("membership_id", UUID.class), rs.getObject("event_id", UUID.class));
        }, Tokens.sha256Hex(token));
        return rows.stream().findFirst();
    }

    public void end(String token) {
        jdbc.update("DELETE FROM sessions WHERE token_hash = ?", Tokens.sha256Hex(token));
    }

    public void endAllForMembership(UUID membershipId) {
        jdbc.update("DELETE FROM sessions WHERE membership_id = ?", membershipId);
    }
}
