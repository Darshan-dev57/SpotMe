package dev.darshan.spotme.search;

import dev.darshan.spotme.face.DetectedFace;
import dev.darshan.spotme.face.FaceEngine;
import dev.darshan.spotme.face.FaceEnginePool;
import dev.darshan.spotme.face.Vectors;
import dev.darshan.spotme.media.ImageProcessor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.opencv.core.Mat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Selfie -> one face vector (kept only in memory) -> exact cosine search inside ONE event.
 */
@Service
public class SearchService {

    /** Why a selfie could not be used. The message is shown to the attendee. */
    public static class BadSelfieException extends Exception {
        private final String code;

        public BadSelfieException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * One matching photo. faceId is the face in it that matched best, faceCount how many faces the
     * photo has (1 means the guest is alone in it).
     */
    public record Match(UUID photoId, long faceId, double distance, int faceCount) {

        public boolean solo() {
            return faceCount <= 1;
        }
    }

    /** Timings in milliseconds, sent back in the Server-Timing header. */
    public record Timings(double decode, double faces, double sql) {
    }

    public record Result(List<Match> matches, Timings timings) {
    }

    // a second face counts as "another person in the selfie" if it is at least this big compared with the largest
    static final double SECOND_FACE_RATIO = 0.25;

    private final ImageProcessor images;
    private final FaceEnginePool engines;
    private final JdbcTemplate jdbc;
    private final Timer searchTimer;

    public SearchService(ImageProcessor images, FaceEnginePool engines, JdbcTemplate jdbc, MeterRegistry metrics) {
        this.images = images;
        this.engines = engines;
        this.jdbc = jdbc;
        // whole guest search: decode the selfie, find its face, query the event
        this.searchTimer = Timer.builder("spotme.search")
                .description("Time for one selfie search, from upload to matches")
                .publishPercentiles(0.5, 0.95)
                .register(metrics);
    }

    public float[] selfieVector(byte[] selfie, long[] nanos) throws BadSelfieException {
        long t0 = System.nanoTime();
        Mat image;
        try {
            image = images.decode(selfie);
        } catch (ImageProcessor.RejectedImageException e) {
            throw new BadSelfieException(e.code(), e.getMessage());
        }
        long t1 = System.nanoTime();
        List<DetectedFace> faces;
        try {
            faces = engines.use(engine -> engine.detectAndEmbed(image));
        } finally {
            image.release();
        }
        nanos[0] = t1 - t0;
        nanos[1] = System.nanoTime() - t1;
        return pickSingleFace(faces).embedding();
    }

    /** Exactly one clear face, otherwise we refuse instead of guessing whose photos to show. */
    static DetectedFace pickSingleFace(List<DetectedFace> faces) throws BadSelfieException {
        if (faces.isEmpty()) {
            throw new BadSelfieException("no_face", "We couldn't find a face. Try a clearer, well-lit selfie.");
        }
        List<DetectedFace> sorted = faces.stream().sorted(Comparator.comparingInt(DetectedFace::area).reversed()).toList();
        DetectedFace main = sorted.get(0);
        if (sorted.size() > 1 && sorted.get(1).area() >= main.area() * SECOND_FACE_RATIO) {
            throw new BadSelfieException("multiple_faces", "More than one face is in this photo. Use a selfie with only you.");
        }
        return main;
    }

    /**
     * Exact (no index) nearest-photo search, always filtered to one event.
     * A photo matches if ANY face in it is close enough; we return its best face and distance.
     * Hidden, suppressed and "wrong match"-reported photos are left out.
     *
     * Two details keep this fast in big events (measured on the hosted box with 12,800 faces in one
     * event: the SQL went from about 130 ms to a few ms):
     * - MATERIALIZED turns the selfie's text into a vector once. Without it, Postgres can re-run the
     *   text-to-vector cast for every face row once the JDBC driver switches to a prepared statement.
     * - The distance limit is in WHERE, so only close faces reach DISTINCT ON. A photo passes if any
     *   face passes, so this gives the same result as filtering afterwards.
     */
    public List<Match> search(UUID eventId, UUID membershipId, float[] query, double maxDistance, int limit) {
        return search(eventId, membershipId, query, maxDistance, limit, null);
    }

    /**
     * The same search, but when indexedAfter is set only photos indexed after that moment count
     * (used for new-photo alerts). A normal search passes null and has no time filter at all.
     */
    public List<Match> search(UUID eventId, UUID membershipId, float[] query, double maxDistance, int limit,
                              Instant indexedAfter) {
        List<Object> args = new ArrayList<>(List.of(Vectors.toPgVector(query), eventId, FaceEngine.MODEL_VERSION, maxDistance));
        String newOnly = "";
        if (indexedAfter != null) {
            newOnly = "AND p.indexed_at > ?";
            args.add(Timestamp.from(indexedAfter));
        }
        args.add(membershipId);
        args.add(limit);
        // <=> is pgvector's cosine DISTANCE (1 - cosine similarity): smaller is more alike
        return jdbc.query("""
                WITH q AS MATERIALIZED (SELECT ?::vector AS v),
                best AS (
                    SELECT DISTINCT ON (f.photo_id) f.photo_id, f.id AS face_id,
                           (f.embedding <=> q.v) AS distance, p.face_count
                    FROM faces f
                    CROSS JOIN q
                    JOIN photos p ON p.id = f.photo_id
                    WHERE f.event_id = ? AND f.model_version = ?
                      AND (f.embedding <=> q.v) <= ?
                      AND p.status = 'ready' AND p.hidden_at IS NULL AND p.suppressed_at IS NULL
                      %s
                      AND NOT EXISTS (SELECT 1 FROM wrong_match_reports w WHERE w.photo_id = p.id AND w.membership_id = ?)
                    ORDER BY f.photo_id, distance
                )
                SELECT photo_id, face_id, distance, face_count FROM best
                ORDER BY distance
                LIMIT ?
                """.formatted(newOnly),
                (rs, i) -> new Match(rs.getObject(1, UUID.class), rs.getLong(2), rs.getDouble(3), rs.getInt(4)),
                args.toArray());
    }

    public Result run(UUID eventId, UUID membershipId, byte[] selfie, double maxDistance, int limit)
            throws BadSelfieException {
        long start = System.nanoTime();
        long[] nanos = new long[2];
        float[] vector = selfieVector(selfie, nanos);
        long t = System.nanoTime();
        List<Match> matches = search(eventId, membershipId, vector, maxDistance, limit);
        double sqlMs = (System.nanoTime() - t) / 1e6;
        searchTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        return new Result(matches, new Timings(nanos[0] / 1e6, nanos[1] / 1e6, sqlMs));
    }
}
