package dev.darshan.spotme.media;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Where image bytes live. Keys look like "events/{eventId}/{photoId}.jpg".
 * Local disk today; an S3-compatible bucket could implement the same four methods.
 */
public interface PhotoStorage {

    void put(String key, byte[] bytes) throws IOException;

    byte[] get(String key) throws IOException;

    /** Deleting a missing key is not an error (deletes are retried). */
    void delete(String key) throws IOException;

    boolean exists(String key);

    /** Every stored key under a prefix, with its last-modified time (used by the orphan cleaner). */
    List<StoredObject> list(String prefix) throws IOException;

    record StoredObject(String key, Instant lastModified) {
    }
}
