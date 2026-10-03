package dev.darshan.spotme.jobs;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A durable job queue in a plain Postgres table.
 *
 * <ul>
 *   <li>enqueue runs inside the caller's transaction, so "photo row + its job" commit together;</li>
 *   <li>claim uses FOR UPDATE SKIP LOCKED so many workers never grab the same row, and gives
 *       each claim a lease (deadline) and a fresh random token;</li>
 *   <li>complete/fail only work while the token still matches, so a worker whose lease ran
 *       out cannot overwrite the result of the worker that took over.</li>
 * </ul>
 * Execution is at-least-once: a crashed job runs again, so every handler must be idempotent.
 */
@Repository
public class JobQueue {

    private final JdbcTemplate jdbc;

    public JobQueue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Adds a job unless one with the same dedupe key already exists. Returns true if it was added. */
    public boolean enqueue(JobKind kind, UUID targetId, String dedupeKey, int maxAttempts) {
        int rows = jdbc.update("""
                INSERT INTO jobs (kind, target_id, dedupe_key, state, max_attempts)
                VALUES (?, ?, ?, 'queued', ?)
                ON CONFLICT (dedupe_key) DO NOTHING
                """, kind.name(), targetId, dedupeKey, maxAttempts);
        return rows == 1;
    }

    /** Puts a finished or failed job back in the queue (used for "retry" and re-indexing). */
    public void requeue(String dedupeKey) {
        jdbc.update("""
                UPDATE jobs SET state = 'queued', attempts = 0, run_after = now(), last_error = NULL,
                       claim_token = NULL, lease_until = NULL, finished_at = NULL, updated_at = now()
                WHERE dedupe_key = ? AND state IN ('failed', 'succeeded')
                """, dedupeKey);
    }

    /**
     * Claims up to {@code limit} ready jobs. This is one short statement (its own transaction):
     * the slow work happens later, outside any lock.
     */
    public List<Job> claim(String workerId, int limit, Duration lease) {
        return jdbc.query("""
                UPDATE jobs SET state = 'running',
                                attempts = attempts + 1,
                                claim_token = gen_random_uuid(),
                                claimed_by = ?,
                                lease_until = now() + make_interval(secs => ?),
                                updated_at = now()
                WHERE id IN (
                    SELECT id FROM jobs
                    WHERE state = 'queued' AND run_after <= now()
                    ORDER BY run_after, id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED)
                RETURNING id, kind, target_id, attempts, max_attempts, claim_token
                """, (rs, i) -> new Job(rs.getLong("id"), JobKind.valueOf(rs.getString("kind")),
                        rs.getObject("target_id", UUID.class), rs.getInt("attempts"), rs.getInt("max_attempts"),
                        rs.getObject("claim_token", UUID.class)),
                workerId, lease.toMillis() / 1000.0, limit);
    }

    /**
     * Marks the job done. Call this inside the same transaction that saves the job's output:
     * if it returns false the caller throws and the output is rolled back.
     */
    public boolean complete(Job job) {
        return jdbc.update("""
                UPDATE jobs SET state = 'succeeded', finished_at = now(), lease_until = NULL, updated_at = now()
                WHERE id = ? AND claim_token = ? AND state = 'running'
                """, job.id(), job.claimToken()) == 1;
    }

    /**
     * Records a failure. Temporary failures go back to the queue with exponential backoff;
     * permanent ones (or the last attempt) end in 'failed'. Returns the new state.
     */
    public String fail(Job job, String errorCode, boolean permanent) {
        boolean giveUp = permanent || job.attempts() >= job.maxAttempts();
        long delayMs = giveUp ? 0 : backoffMillis(job.attempts());
        jdbc.update("""
                UPDATE jobs SET state = ?, last_error = ?, run_after = now() + make_interval(secs => ?),
                       claim_token = NULL, lease_until = NULL, updated_at = now(),
                       finished_at = CASE WHEN ? THEN now() ELSE NULL END
                WHERE id = ? AND claim_token = ? AND state = 'running'
                """, giveUp ? "failed" : "queued", truncate(errorCode), delayMs / 1000.0, giveUp, job.id(), job.claimToken());
        return giveUp ? "failed" : "queued";
    }

    /**
     * The reaper: jobs whose lease ran out (worker crashed or hung) go back to the queue,
     * or to 'failed' if they already used every attempt. Returns how many were recovered.
     */
    public int reapExpiredLeases() {
        int failed = jdbc.update("""
                UPDATE jobs SET state = 'failed', last_error = 'lease_expired', claim_token = NULL,
                       lease_until = NULL, finished_at = now(), updated_at = now()
                WHERE state = 'running' AND lease_until < now() AND attempts >= max_attempts
                """);
        int requeued = jdbc.update("""
                UPDATE jobs SET state = 'queued', claim_token = NULL, lease_until = NULL, run_after = now(),
                       updated_at = now()
                WHERE state = 'running' AND lease_until < now()
                """);
        return failed + requeued;
    }

    /** 2s, 4s, 8s ... capped at 5 minutes, plus up to 20% random jitter so retries don't line up. */
    static long backoffMillis(int attempt) {
        long base = 2000L * (1L << Math.min(Math.max(attempt - 1, 0), 10));
        long capped = Math.min(base, 300_000L);
        return capped + ThreadLocalRandom.current().nextLong(capped / 5 + 1);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 200 ? s : s.substring(0, 200);
    }
}
