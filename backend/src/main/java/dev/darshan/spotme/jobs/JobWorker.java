package dev.darshan.spotme.jobs;

import dev.darshan.spotme.config.SpotMeProperties;
import dev.darshan.spotme.drive.DriveImportHandler;
import dev.darshan.spotme.privacy.PurgeService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The worker loop: N threads, each claims one job, runs it, records the outcome.
 * Started only when spotme.worker.enabled=true (the worker profile, or local "all in one").
 */
@Component
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobQueue queue;
    private final IndexPhotoHandler indexHandler;
    private final PurgeService purgeService;
    private final DriveImportHandler driveHandler;
    private final JdbcTemplate jdbc;
    private final SpotMeProperties.Worker config;
    private final String workerId;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong done = new AtomicLong();
    private final List<Thread> threads = new ArrayList<>();

    public JobWorker(JobQueue queue, IndexPhotoHandler indexHandler, PurgeService purgeService,
                     DriveImportHandler driveHandler, JdbcTemplate jdbc, SpotMeProperties props) {
        this.queue = queue;
        this.indexHandler = indexHandler;
        this.purgeService = purgeService;
        this.driveHandler = driveHandler;
        this.jdbc = jdbc;
        this.config = props.worker();
        // "pid@host" is enough to tell workers apart in the jobs table
        this.workerId = ManagementFactory.getRuntimeMXBean().getName();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startIfEnabled() {
        if (config.enabled()) {
            start();
        }
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        jdbc.update("""
                INSERT INTO worker_heartbeats (worker_id) VALUES (?)
                ON CONFLICT (worker_id) DO UPDATE SET started_at = now(), last_seen = now()
                """, workerId);
        for (int i = 0; i < config.threads(); i++) {
            Thread t = new Thread(this::loop, "spotme-worker-" + i);
            t.start();
            threads.add(t);
        }
        log.info("Worker {} started with {} threads", workerId, config.threads());
    }

    @PreDestroy
    public synchronized void stop() {
        running.set(false);
        for (Thread t : threads) {
            t.interrupt();
        }
        for (Thread t : threads) {
            try {
                t.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        threads.clear();
    }

    public boolean isRunning() {
        return running.get();
    }

    private void loop() {
        while (running.get()) {
            try {
                List<Job> jobs = queue.claim(workerId, 1, config.lease());
                if (jobs.isEmpty()) {
                    Thread.sleep(config.pollInterval().toMillis());
                    continue;
                }
                runOne(jobs.get(0));
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                // database down or similar: wait a bit and try again
                log.warn("Worker loop error: {}", e.getMessage());
                sleepQuietly(2000);
            }
        }
    }

    /** Runs one claimed job and records success or failure. Public so tests can drive it step by step. */
    public String runOne(Job job) {
        try {
            String outcome = switch (job.kind()) {
                case INDEX_PHOTO -> indexHandler.handle(job);
                case PURGE_PHOTO -> {
                    purgeService.purgePhoto(job);
                    yield "purged";
                }
                case PURGE_EVENT -> {
                    purgeService.purgeEvent(job);
                    yield "purged";
                }
                case DRIVE_LIST -> driveHandler.list(job);
                case DRIVE_FILE -> driveHandler.file(job);
            };
            done.incrementAndGet();
            jdbc.update("UPDATE worker_heartbeats SET last_seen = now(), jobs_done = jobs_done + 1 WHERE worker_id = ?", workerId);
            return outcome;
        } catch (StaleClaimException e) {
            log.info("Job {} was taken over by another worker, result dropped", job.id());
            return "stale";
        } catch (IndexPhotoHandler.PermanentFailure e) {
            queue.fail(job, e.getMessage(), true);
            indexHandler.markFailed(job.targetId(), e.getMessage());
            return "failed";
        } catch (Exception e) {
            String code = e.getClass().getSimpleName();
            String state = queue.fail(job, code, false);
            if ("failed".equals(state)) {
                switch (job.kind()) {
                    case INDEX_PHOTO -> indexHandler.markFailed(job.targetId(), "processing_error");
                    case DRIVE_FILE -> driveHandler.markFileFailed(job.targetId(), "download_failed");
                    case DRIVE_LIST -> driveHandler.markImportFailed(job.targetId(), "listing_failed");
                    default -> {
                    }
                }
            }
            log.warn("Job {} ({}) attempt {} failed: {}", job.id(), job.kind(), job.attempts(), code);
            return "retry";
        }
    }

    public long jobsDone() {
        return done.get();
    }

    public String workerId() {
        return workerId;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
