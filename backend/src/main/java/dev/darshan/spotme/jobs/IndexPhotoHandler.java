package dev.darshan.spotme.jobs;

import dev.darshan.spotme.face.DetectedFace;
import dev.darshan.spotme.face.FaceEngine;
import dev.darshan.spotme.face.FaceEnginePool;
import dev.darshan.spotme.face.Vectors;
import dev.darshan.spotme.media.ImageProcessor;
import dev.darshan.spotme.media.PhotoStorage;
import org.opencv.core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * INDEX_PHOTO: read the stored photo, find faces, save one vector per face.
 *
 * <p>The slow part (inference) runs with no transaction open. The save is one transaction
 * that first re-checks everything that could have changed meanwhile: the event may be
 * deleting, the photo may have been suppressed, and our claim may have expired.
 */
@Component
public class IndexPhotoHandler {

    private static final Logger log = LoggerFactory.getLogger(IndexPhotoHandler.class);

    /** A failure that retrying will not fix (bad image). */
    public static class PermanentFailure extends RuntimeException {
        public PermanentFailure(String code) {
            super(code);
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PhotoStorage storage;
    private final ImageProcessor images;
    private final FaceEnginePool engines;
    private final JobQueue queue;

    public IndexPhotoHandler(JdbcTemplate jdbc, TransactionTemplate tx, PhotoStorage storage,
                             ImageProcessor images, FaceEnginePool engines, JobQueue queue) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.storage = storage;
        this.images = images;
        this.engines = engines;
        this.queue = queue;
    }

    /** What the slow half produced; null faces means "nothing to do". */
    public record Inference(int epochAtStart, List<DetectedFace> faces) {
    }

    /** Returns a short outcome word for logs and tests: indexed, skipped or discarded. */
    public String handle(Job job) throws IOException {
        Inference inference = infer(job);
        if (inference == null) {
            finishWithoutOutput(job);
            return "skipped";
        }
        return save(job, inference);
    }

    /** The slow half: read the photo and run the face models. No transaction or lock is held. */
    public Inference infer(Job job) throws IOException {
        UUID photoId = job.targetId();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.object_key, p.status, p.suppressed_at, e.status AS event_status, e.deletion_epoch
                FROM photos p JOIN events e ON e.id = p.event_id WHERE p.id = ?
                """, photoId);
        if (rows.isEmpty() || !isIndexable(rows.get(0))) {
            return null;
        }
        int epochAtStart = (Integer) rows.get(0).get("deletion_epoch");
        String objectKey = (String) rows.get(0).get("object_key");
        jdbc.update("UPDATE photos SET status = 'processing' WHERE id = ? AND status IN ('queued', 'processing')", photoId);
        try {
            byte[] bytes = storage.get(objectKey);
            Mat image = images.decode(bytes);
            try {
                return new Inference(epochAtStart, engines.use(engine -> engine.detectAndEmbed(image)));
            } finally {
                image.release();
            }
        } catch (NoSuchFileException e) {
            throw new PermanentFailure("file_missing");
        } catch (ImageProcessor.RejectedImageException e) {
            throw new PermanentFailure(e.code());
        }
    }

    /**
     * The fast half: one transaction that re-checks everything that could have changed while
     * we were busy (event deleted, photo suppressed, our claim taken over) and only then saves.
     */
    public String save(Job job, Inference inference) {
        UUID photoId = job.targetId();
        List<DetectedFace> faces = inference.faces();
        return tx.execute(status -> {
            Map<String, Object> now = jdbc.queryForMap("""
                    SELECT p.status, p.suppressed_at, e.status AS event_status, e.deletion_epoch
                    FROM photos p JOIN events e ON e.id = p.event_id
                    WHERE p.id = ? FOR UPDATE OF p
                    """, photoId);
            boolean stillWanted = "processing".equals(now.get("status"))
                    && now.get("suppressed_at") == null
                    && isLiveEvent((String) now.get("event_status"))
                    && inference.epochAtStart() == (Integer) now.get("deletion_epoch");
            if (!stillWanted) {
                if (!queue.complete(job)) {
                    throw new StaleClaimException(job.id());
                }
                return "discarded";
            }
            // idempotent: a re-run replaces this photo's faces instead of adding more
            jdbc.update("DELETE FROM faces WHERE photo_id = ? AND model_version = ?", photoId, FaceEngine.MODEL_VERSION);
            for (int i = 0; i < faces.size(); i++) {
                DetectedFace f = faces.get(i);
                jdbc.update("""
                        INSERT INTO faces (event_id, photo_id, face_index, box_x, box_y, box_w, box_h, det_score,
                                           embedding, model_version)
                        SELECT event_id, id, ?, ?, ?, ?, ?, ?, ?::vector, ? FROM photos WHERE id = ?
                        """, i, f.x(), f.y(), f.width(), f.height(), f.score(),
                        Vectors.toPgVector(f.embedding()), FaceEngine.MODEL_VERSION, photoId);
            }
            jdbc.update("""
                    UPDATE photos SET status = 'ready', face_count = ?, model_version = ?, indexed_at = now(), error_code = NULL
                    WHERE id = ?
                    """, faces.size(), FaceEngine.MODEL_VERSION, photoId);
            if (!queue.complete(job)) {
                // another worker owns this job now: roll back everything above
                throw new StaleClaimException(job.id());
            }
            log.debug("Indexed photo {} with {} faces", photoId, faces.size());
            return "indexed";
        });
    }

    /** Called by the worker after the last failed attempt so the organizer sees it. */
    public void markFailed(UUID photoId, String code) {
        jdbc.update("UPDATE photos SET status = 'failed', error_code = ? WHERE id = ? AND status IN ('queued', 'processing')",
                code, photoId);
    }

    private void finishWithoutOutput(Job job) {
        if (!queue.complete(job)) {
            throw new StaleClaimException(job.id());
        }
    }

    private static boolean isIndexable(Map<String, Object> row) {
        String status = (String) row.get("status");
        return ("queued".equals(status) || "processing".equals(status))
                && row.get("suppressed_at") == null
                && isLiveEvent((String) row.get("event_status"));
    }

    private static boolean isLiveEvent(String eventStatus) {
        return "draft".equals(eventStatus) || "published".equals(eventStatus);
    }
}
