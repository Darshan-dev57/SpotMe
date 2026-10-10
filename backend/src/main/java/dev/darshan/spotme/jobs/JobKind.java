package dev.darshan.spotme.jobs;

/**
 * The kinds of background job. Lower priority numbers are claimed first, so a long Drive import
 * (priority 1) never holds back indexing or deletion: each imported photo is indexed right after it
 * arrives, and an organizer's own uploads go ahead of the import.
 */
public enum JobKind {
    INDEX_PHOTO(0),
    PURGE_PHOTO(0),
    PURGE_EVENT(0),
    DRIVE_LIST(1),
    DRIVE_FILE(1);

    private final int priority;

    JobKind(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }
}
