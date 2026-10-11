package dev.darshan.spotme.media;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/**
 * Where image bytes live. Keys look like "events/{eventId}/{photoId}.jpg".
 * Local disk today; an S3-compatible bucket could implement the same four methods.
 */
public interface PhotoStorage {

    void put(String key, byte[] bytes) throws IOException;

    byte[] get(String key) throws IOException;

    /** Reads a file as a stream, so a large download never has to fit in memory. The caller closes it. */
    InputStream open(String key) throws IOException;

    /** Deleting a missing key is not an error (deletes are retried). */
    void delete(String key) throws IOException;

    boolean exists(String key);

    /** Every stored key under a prefix, with its last-modified time (used by the orphan cleaner). */
    List<StoredObject> list(String prefix) throws IOException;

    record StoredObject(String key, Instant lastModified) {
    }
}
