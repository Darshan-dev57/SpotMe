package dev.darshan.spotme.drive;

import dev.darshan.spotme.common.ApiException;
import dev.darshan.spotme.config.SpotMeProperties;
import dev.darshan.spotme.jobs.JobKind;
import dev.darshan.spotme.jobs.JobQueue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Starts a Drive folder import and reports its progress. The work itself runs as jobs (DriveImportHandler). */
@Service
public class DriveImportService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JobQueue queue;
    private final int maxAttempts;

    public DriveImportService(JdbcTemplate jdbc, TransactionTemplate tx, JobQueue queue, SpotMeProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.queue = queue;
        this.maxAttempts = props.worker().maxAttempts();
    }

    /** Saves the import and queues the job that lists the folder. One running import per event. */
    public UUID start(UUID eventId, String link) {
        String folderId = DriveLinks.folderId(link);
        if (folderId == null) {
            throw ApiException.badRequest("bad_drive_link",
                    "Paste a Google Drive folder link, like https://drive.google.com/drive/folders/...");
        }
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            // lock the event row so two clicks can't start two imports at once
            jdbc.queryForObject("SELECT id FROM events WHERE id = ? FOR UPDATE", UUID.class, eventId);
            Integer running = jdbc.queryForObject(
                    "SELECT count(*) FROM drive_imports WHERE event_id = ? AND status IN ('listing', 'importing')",
                    Integer.class, eventId);
            if (running != null && running > 0) {
                throw ApiException.conflict("import_running", "A Drive import is already running for this event.");
            }
            jdbc.update("INSERT INTO drive_imports (id, event_id, folder_id, status) VALUES (?, ?, ?, 'listing')",
                    id, eventId, folderId);
            queue.enqueue(JobKind.DRIVE_LIST, id, "drive-list:" + id, maxAttempts);
        });
        return id;
    }

    /** The newest imports of an event with their counts, newest first. */
    public List<Map<String, Object>> list(UUID eventId) {
        List<Map<String, Object>> imports = jdbc.queryForList("""
                SELECT i.id, i.folder_id, i.status, i.files_found, i.files_over_limit, i.skipped, i.error,
                       i.created_at, i.finished_at,
                       count(f.id) FILTER (WHERE f.status = 'queued')    AS queued,
                       count(f.id) FILTER (WHERE f.status = 'imported')  AS imported,
                       count(f.id) FILTER (WHERE f.status = 'duplicate') AS duplicates,
                       count(f.id) FILTER (WHERE f.status = 'failed')    AS failed
                FROM drive_imports i LEFT JOIN drive_import_files f ON f.import_id = i.id
                WHERE i.event_id = ?
                GROUP BY i.id ORDER BY i.created_at DESC LIMIT 5
                """, eventId);
        return imports.stream().map(row -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", row.get("id"));
            out.put("folderUrl", "https://drive.google.com/drive/folders/" + row.get("folder_id"));
            out.put("status", row.get("status"));
            out.put("found", row.get("files_found"));
            out.put("overLimit", row.get("files_over_limit"));
            out.put("skipped", row.get("skipped"));
            out.put("queued", row.get("queued"));
            out.put("imported", row.get("imported"));
            out.put("duplicates", row.get("duplicates"));
            out.put("failed", row.get("failed"));
            out.put("error", row.get("error"));
            out.put("createdAt", row.get("created_at"));
            out.put("finishedAt", row.get("finished_at"));
            out.put("failures", jdbc.queryForList("""
                    SELECT name, error FROM drive_import_files WHERE import_id = ? AND status = 'failed'
                    ORDER BY name LIMIT 5
                    """, row.get("id")));
            return out;
        }).toList();
    }
}
