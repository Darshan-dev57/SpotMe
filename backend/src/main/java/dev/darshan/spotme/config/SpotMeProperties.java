package dev.darshan.spotme.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** All app settings in one place, bound from the "spotme.*" keys in application.properties. */
@ConfigurationProperties(prefix = "spotme")
public record SpotMeProperties(
        String storageDir,
        String modelsDir,
        String webOrigin,
        String webDir,
        String mediaSecret,
        Upload upload,
        Face face,
        Search search,
        Worker worker,
        Eval eval,
        Demo demo) {

    public record Upload(long maxBytes, long maxPixels, int maxConcurrentDecodes, int thumbnailSide, int maxPhotosPerEvent) {
    }

    /** Detector settings. maxSide: photos are scaled so the long side is at most this many pixels. */
    public record Face(float detectScore, int maxSide, int minFaceSize, int engines, int opencvThreads) {
    }

    /**
     * maxDistance is a pgvector cosine distance (1 - cosine similarity).
     * It was picked from the accuracy harness, see docs/MEASUREMENTS.md.
     */
    public record Search(double maxDistance, int maxResults, int perMinuteLimit, Duration mediaLinkTtl) {
    }

    public record Worker(boolean enabled, int threads, Duration lease, Duration pollInterval,
                         int maxAttempts, Duration expirySweepInterval) {
    }

    /** The evaluation endpoint returns raw distances, so it is off unless the eval profile turns it on. */
    public record Eval(boolean enabled) {
    }

    /**
     * The public demo: a published event filled from photosDir that any visitor can join.
     * invitesPerMinute limits demo invites per IP address.
     */
    public record Demo(boolean enabled, String photosDir, int invitesPerMinute) {
    }
}
