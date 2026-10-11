package dev.darshan.spotme.drive;

import dev.darshan.spotme.common.ApiException;
import dev.darshan.spotme.config.SpotMeProperties;
import dev.darshan.spotme.jobs.Job;
import dev.darshan.spotme.jobs.JobKind;
import dev.darshan.spotme.jobs.JobQueue;
import dev.darshan.spotme.jobs.StaleClaimException;
import dev.darshan.spotme.media.PhotoIntake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The two Drive import jobs:
 * <ul>
 *   <li>DRIVE_LIST reads the folder and its subfolders, saves one row per photo and queues one DRIVE_FILE
 *       job per photo, all in one transaction;</li>
 *   <li>DRIVE_FILE downloads one photo and hands it to {@link PhotoIntake}, the same path an upload takes,
 *       so it is checked, re-encoded, deduplicated and indexed like any other photo.</li>
 * </ul>
 * One photo per job keeps memory flat on a small server, and the queue gives retries and crash recovery.
 */
@Component
public class DriveImportHandler {

    private static final Logger log = LoggerFactory.getLogger(DriveImportHandler.class);

    static final int MAX_FILES = 2000;
    static final int MAX_FOLDERS = 50;
    static final int MAX_DEPTH = 5;
    static final int RESIZED_SIDE = 4000;
    /** Types the server decodes itself; anything else (HEIC, RAW, ...) comes as Google's JPEG rendering. */
    static final Set<String> DIRECT_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/tiff", "image/bmp", "image/gif");
    /** Upload refusals that Google's smaller JPEG copy can fix. */
    private static final Set<String> RETRY_WITH_COPY = Set.of("too_many_pixels", "file_too_large", "unsupported_type", "corrupt_image");

    /** A photo that can't be imported; the code is shown to the organizer. Retrying won't help. */
    static class ImportFailure extends Exception {
        ImportFailure(String code) {
            super(code);
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JobQueue queue;
    private final DriveClient drive;
    private final PhotoIntake intake;
    private final long maxBytes;
    private final int maxAttempts;

    public DriveImportHandler(JdbcTemplate jdbc, TransactionTemplate tx, JobQueue queue, DriveClient drive,
                              PhotoIntake intake, SpotMeProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.queue = queue;
        this.drive = drive;
        this.intake = intake;
        this.maxBytes = props.upload().maxBytes();
        this.maxAttempts = props.worker().maxAttempts();
    }

    // ---------- DRIVE_LIST ----------

    public String list(Job job) throws IOException {
        UUID importId = job.targetId();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT i.folder_id, i.status, (e.status IN ('draft', 'published') AND e.expires_at > now()) AS live
                FROM drive_imports i JOIN events e ON e.id = i.event_id WHERE i.id = ?
                """, importId);
        if (rows.isEmpty() || !"listing".equals(rows.get(0).get("status"))) {
            finish(job);
            return "skipped";
        }
        if (!(Boolean) rows.get(0).get("live")) {
            failImport(job, importId, "event_closed");
            return "failed";
        }

        // breadth first through the folder and its subfolders
        Map<String, DriveClient.Entry> photos = new LinkedHashMap<>(); // by file id, so a file seen twice counts once
        int skipped = 0;
        int overLimit = 0;
        Deque<String[]> folders = new ArrayDeque<>();
        Set<String> seenFolders = new HashSet<>();
        folders.add(new String[]{(String) rows.get(0).get("folder_id"), "0"});
        while (!folders.isEmpty() && seenFolders.size() < MAX_FOLDERS) {
            String[] next = folders.poll();
            if (!seenFolders.add(next[0])) {
                continue;
            }
            int depth = Integer.parseInt(next[1]);
            List<DriveClient.Entry> entries;
            try {
                entries = drive.listFolder(next[0]);
            } catch (DriveClient.FolderNotPublicException e) {
                if (depth == 0) {
                    failImport(job, importId, "folder_not_public");
                    return "failed";
                }
                continue; // a subfolder shared more narrowly than its parent is left out
            }
            for (DriveClient.Entry entry : entries) {
                if (entry.folder()) {
                    if (depth < MAX_DEPTH) {
                        folders.add(new String[]{entry.id(), String.valueOf(depth + 1)});
                    }
                } else if (entry.mimeType() == null || !entry.mimeType().startsWith("image/")) {
                    skipped++;
                } else if (photos.size() < MAX_FILES) {
                    photos.putIfAbsent(entry.id(), entry);
                } else if (!photos.containsKey(entry.id())) {
                    overLimit++;
                }
            }
        }

        int skippedFinal = skipped;
        int overFinal = overLimit;
        tx.executeWithoutResult(s -> {
            for (DriveClient.Entry photo : photos.values()) {
                UUID fileRow = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO drive_import_files (id, import_id, file_id, name, mime_type, status)
                        VALUES (?, ?, ?, ?, ?, 'queued')
                        """, fileRow, importId, photo.id(), truncate(photo.name(), 300), photo.mimeType());
                queue.enqueue(JobKind.DRIVE_FILE, fileRow, "drive-file:" + fileRow, maxAttempts);
            }
            jdbc.update("""
                    UPDATE drive_imports SET status = ?, files_found = ?, skipped = ?, files_over_limit = ?,
                           finished_at = CASE WHEN ? THEN now() END
                    WHERE id = ?
                    """, photos.isEmpty() ? "done" : "importing", photos.size(), skippedFinal, overFinal,
                    photos.isEmpty(), importId);
            finish(job);
        });
        log.info("Drive import {}: {} photos queued, {} other files, {} over the limit", importId, photos.size(), skippedFinal, overFinal);
        return "listed";
    }

    // ---------- DRIVE_FILE ----------

    public String file(Job job) throws IOException {
        UUID fileRow = job.targetId();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT f.file_id, f.mime_type, f.status, f.import_id, i.event_id,
                       (e.status IN ('draft', 'published') AND e.expires_at > now()) AS live
                FROM drive_import_files f JOIN drive_imports i ON i.id = f.import_id JOIN events e ON e.id = i.event_id
                WHERE f.id = ?
                """, fileRow);
        if (rows.isEmpty() || !"queued".equals(rows.get(0).get("status"))) {
            finish(job);
            return "skipped";
        }
        Map<String, Object> row = rows.get(0);
        UUID importId = (UUID) row.get("import_id");
        if (!(Boolean) row.get("live")) {
            record(job, fileRow, importId, "failed", null, "event_closed");
            return "failed";
        }
        try {
            PhotoIntake.Result result = importOne((UUID) row.get("event_id"), (String) row.get("file_id"), (String) row.get("mime_type"));
            String status = "duplicate".equals(result.status()) ? "duplicate" : "imported";
            record(job, fileRow, importId, status, result.photoId(), null);
            return status;
        } catch (ImportFailure e) {
            record(job, fileRow, importId, "failed", null, e.getMessage());
            return "failed";
        }
    }

    /**
     * The original file when the server can read it; otherwise, or when it is over the size or pixel
     * limit, Google's JPEG rendering at most 4000 px on the longest side.
     */
    PhotoIntake.Result importOne(UUID eventId, String fileId, String mimeType) throws IOException, ImportFailure {
        byte[] original = DIRECT_TYPES.contains(mimeType) ? drive.downloadOriginal(fileId, maxBytes) : null;
        if (original != null) {
            try {
                return intake.add(eventId, original);
            } catch (ApiException e) {
                if (!RETRY_WITH_COPY.contains(e.code())) {
                    throw new ImportFailure(e.code());
                }
            }
        }
        byte[] copy = drive.downloadResized(fileId, RESIZED_SIDE, maxBytes);
        if (copy == null) {
            throw new ImportFailure("download_failed");
        }
        try {
            return intake.add(eventId, copy);
        } catch (ApiException e) {
            throw new ImportFailure(e.code());
        }
    }

    // ---------- bookkeeping ----------

    /** Called by the worker when a DRIVE_FILE job used up its retries (for example Google kept timing out). */
    public void markFileFailed(UUID fileRow, String code) {
        tx.executeWithoutResult(s -> {
            List<UUID> importIds = jdbc.queryForList(
                    "UPDATE drive_import_files SET status = 'failed', error = ? WHERE id = ? AND status = 'queued' RETURNING import_id",
                    UUID.class, code, fileRow);
            importIds.forEach(this::finishImportIfDone);
        });
    }

    /** Called by the worker when the DRIVE_LIST job used up its retries. */
    public void markImportFailed(UUID importId, String code) {
        jdbc.update("UPDATE drive_imports SET status = 'failed', error = ?, finished_at = now() WHERE id = ? AND status = 'listing'",
                code, importId);
    }

    private void record(Job job, UUID fileRow, UUID importId, String status, UUID photoId, String error) {
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE drive_import_files SET status = ?, photo_id = ?, error = ? WHERE id = ? AND status = 'queued'",
                    status, photoId, error, fileRow);
            finishImportIfDone(importId);
            finish(job);
        });
    }

    private void finishImportIfDone(UUID importId) {
        jdbc.update("""
                UPDATE drive_imports SET status = 'done', finished_at = now()
                WHERE id = ? AND status = 'importing'
                  AND NOT EXISTS (SELECT 1 FROM drive_import_files WHERE import_id = ? AND status = 'queued')
                """, importId, importId);
    }

    private void failImport(Job job, UUID importId, String code) {
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE drive_imports SET status = 'failed', error = ?, finished_at = now() WHERE id = ?", code, importId);
            finish(job);
        });
    }

    private void finish(Job job) {
        if (!queue.complete(job)) {
            throw new StaleClaimException(job.id());
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
