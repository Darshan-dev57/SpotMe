package dev.darshan.spotme.jobs;

import dev.darshan.spotme.config.SpotMeProperties;
import dev.darshan.spotme.media.PhotoStorage;
import dev.darshan.spotme.privacy.PurgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Periodic background chores, run only in the worker process:
 * reaping expired leases, starting deletion of expired events, removing orphan files,
 * and a heartbeat so the organizer page can show "worker active".
 */
@Component
public class Housekeeping {

    private static final Logger log = LoggerFactory.getLogger(Housekeeping.class);
    private static final Duration ORPHAN_AGE = Duration.ofMinutes(15);

    private final JobQueue queue;
    private final PurgeService purge;
    private final PhotoStorage storage;
    private final JdbcTemplate jdbc;
    private final JobWorker worker;
    private final boolean enabled;

    public Housekeeping(JobQueue queue, PurgeService purge, PhotoStorage storage, JdbcTemplate jdbc,
                        JobWorker worker, SpotMeProperties props) {
        this.queue = queue;
        this.purge = purge;
        this.storage = storage;
        this.jdbc = jdbc;
        this.worker = worker;
        this.enabled = props.worker().enabled();
    }

    @Scheduled(fixedDelay = 5000)
    public void reapAndBeat() {
        if (!enabled) {
            return;
        }
        int recovered = queue.reapExpiredLeases();
        if (recovered > 0) {
            log.info("Recovered {} jobs with expired leases", recovered);
        }
        if (worker.isRunning()) {
            jdbc.update("UPDATE worker_heartbeats SET last_seen = now() WHERE worker_id = ?",
                    java.lang.management.ManagementFactory.getRuntimeMXBean().getName());
        }
    }

    @Scheduled(fixedDelayString = "${spotme.worker.expiry-sweep-interval}")
    public void scheduledExpiry() {
        if (enabled) {
            expireEvents();
        }
    }

    /** Starts deletion for every event past its expiry date. Returns how many were started. */
    public int expireEvents() {
        List<UUID> expired = jdbc.queryForList(
                "SELECT id FROM events WHERE status IN ('draft', 'published') AND expires_at <= now()", UUID.class);
        int started = 0;
        for (UUID id : expired) {
            if (purge.startEventDeletion(id)) {
                started++;
            }
        }
        return started;
    }

    /**
     * A file can be written and then the database insert can fail (or the process can die
     * in between). Those files belong to no photo row; delete them once they are old enough
     * that no upload could still be in progress.
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    public void scheduledOrphanCleanup() {
        if (!enabled) {
            return;
        }
        try {
            removeOrphanFiles(Instant.now().minus(ORPHAN_AGE));
        } catch (Exception e) {
            log.warn("Orphan cleanup failed: {}", e.getMessage());
        }
    }

    public int removeOrphanFiles(Instant olderThan) throws java.io.IOException {
        Set<String> known = new HashSet<>();
        jdbc.query("SELECT object_key, thumb_key FROM photos WHERE status <> 'purged'", rs -> {
            known.add(rs.getString(1));
            known.add(rs.getString(2));
        });
        int removed = 0;
        for (PhotoStorage.StoredObject object : storage.list("events")) {
            if (!known.contains(object.key()) && object.lastModified().isBefore(olderThan)) {
                storage.delete(object.key());
                removed++;
            }
        }
        if (removed > 0) {
            log.info("Removed {} orphan files", removed);
        }
        return removed;
    }
}
