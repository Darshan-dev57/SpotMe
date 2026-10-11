package dev.darshan.spotme.event;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The numbers behind the organizer's progress bar: photos per status, hidden photos, faces, worker. */
@Component
public class EventProgress {

    private final JdbcTemplate jdbc;

    public EventProgress(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> snapshot(UUID eventId) {
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String s : List.of("queued", "processing", "ready", "failed")) {
            counts.put(s, 0L);
        }
        jdbc.query("SELECT status, count(*) FROM photos WHERE event_id = ? AND status <> 'purged' GROUP BY status",
                rs -> {
                    counts.put(rs.getString(1), rs.getLong(2));
                }, eventId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("counts", counts);
        out.put("faces", jdbc.queryForObject("SELECT count(*) FROM faces WHERE event_id = ?", Long.class, eventId));
        out.put("hidden", jdbc.queryForObject(
                "SELECT count(*) FROM photos WHERE event_id = ? AND (hidden_at IS NOT NULL OR suppressed_at IS NOT NULL) AND status = 'ready'",
                Long.class, eventId));
        out.put("worker", workerStatus());
        return out;
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
