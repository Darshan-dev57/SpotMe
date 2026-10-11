package dev.darshan.spotme.media;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Where image bytes live. Keys look like "events/{eventId}/{photoId}.jpg".
 * Two implementations: {@link LocalPhotoStorage} (files on disk, the default) and {@link S3PhotoStorage}
 * (an S3-compatible bucket such as MinIO), picked with spotme.storage-type.
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

    /**
     * A short-lived signed link straight to the stored file, if this storage can make one. The caller has
     * already checked that the guest may see the photo. downloadName, when not null, makes the browser save
     * the file under that name. Local disk can't sign links, so the app streams the bytes itself.
     */
    default Optional<URI> directLink(String key, String downloadName) {
        return Optional.empty();
    }

    record StoredObject(String key, Instant lastModified) {
    }
}
