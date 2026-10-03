package dev.darshan.spotme.jobs;

import dev.darshan.spotme.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class JobQueueIntegrationTest extends IntegrationTest {

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM jobs");
    }

    private void enqueue(int n) {
        for (int i = 0; i < n; i++) {
            UUID id = UUID.randomUUID();
            queue.enqueue(JobKind.INDEX_PHOTO, id, "test:" + id, 3);
        }
    }

    @Test
    void sameDedupeKeyIsEnqueuedOnce() {
        UUID id = UUID.randomUUID();
        assertThat(queue.enqueue(JobKind.INDEX_PHOTO, id, "index:" + id, 3)).isTrue();
        assertThat(queue.enqueue(JobKind.INDEX_PHOTO, id, "index:" + id, 3)).isFalse();
        assertThat(count("SELECT count(*) FROM jobs")).isEqualTo(1);
    }

    @Test
    void parallelWorkersNeverClaimTheSameJob() throws Exception {
        enqueue(200);
        Set<Long> claimed = ConcurrentHashMap.newKeySet();
        List<Long> duplicates = java.util.Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int w = 0; w < 8; w++) {
            String worker = "w" + w;
            futures.add(pool.submit(() -> {
                while (true) {
                    List<Job> jobs = queue.claim(worker, 3, Duration.ofMinutes(1));
                    if (jobs.isEmpty()) {
                        return;
                    }
                    for (Job j : jobs) {
                        if (!claimed.add(j.id())) {
                            duplicates.add(j.id());
                        }
                    }
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        assertThat(duplicates).isEmpty();
        assertThat(claimed).hasSize(200);
        assertThat(count("SELECT count(DISTINCT claimed_by) FROM jobs")).isGreaterThan(1);
    }

    @Test
    void staleTokenCannotCompleteAfterLeaseExpiresAndJobIsReclaimed() throws Exception {
        enqueue(1);
        Job first = queue.claim("crashed-worker", 1, Duration.ofMillis(200)).get(0);
        Thread.sleep(400);
        assertThat(queue.reapExpiredLeases()).isEqualTo(1);

        Job second = queue.claim("healthy-worker", 1, Duration.ofMinutes(1)).get(0);
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.claimToken()).isNotEqualTo(first.claimToken());
        assertThat(second.attempts()).isEqualTo(2);

        assertThat(queue.complete(first)).as("old worker comes back to life").isFalse();
        assertThat(queue.complete(second)).isTrue();
        assertThat(jdbc.queryForObject("SELECT state FROM jobs", String.class)).isEqualTo("succeeded");
    }

    @Test
    void temporaryFailureIsRetriedLaterThenGivesUp() {
        enqueue(1);
        Job a1 = queue.claim("w", 1, Duration.ofMinutes(1)).get(0);
        assertThat(queue.fail(a1, "IOException", false)).isEqualTo("queued");
        // backoff: not claimable right away
        assertThat(queue.claim("w", 1, Duration.ofMinutes(1))).isEmpty();

        jdbc.update("UPDATE jobs SET run_after = now()");
        Job a2 = queue.claim("w", 1, Duration.ofMinutes(1)).get(0);
        queue.fail(a2, "IOException", false);
        jdbc.update("UPDATE jobs SET run_after = now()");
        Job a3 = queue.claim("w", 1, Duration.ofMinutes(1)).get(0);
        assertThat(queue.fail(a3, "IOException", false)).isEqualTo("failed");
        assertThat(jdbc.queryForObject("SELECT state || ':' || attempts || ':' || last_error FROM jobs", String.class))
                .isEqualTo("failed:3:IOException");
    }

    @Test
    void permanentFailureStopsImmediately() {
        enqueue(1);
        Job job = queue.claim("w", 1, Duration.ofMinutes(1)).get(0);
        assertThat(queue.fail(job, "corrupt_image", true)).isEqualTo("failed");
    }

    @Test
    void leaseThatRunsOutOnTheLastAttemptEndsAsFailed() throws Exception {
        UUID id = UUID.randomUUID();
        queue.enqueue(JobKind.INDEX_PHOTO, id, "last:" + id, 1);
        queue.claim("w", 1, Duration.ofMillis(100));
        Thread.sleep(300);
        queue.reapExpiredLeases();
        assertThat(jdbc.queryForObject("SELECT state || ':' || last_error FROM jobs", String.class))
                .isEqualTo("failed:lease_expired");
    }
}
