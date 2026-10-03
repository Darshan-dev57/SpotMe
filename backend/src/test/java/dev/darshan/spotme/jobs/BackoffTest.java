package dev.darshan.spotme.jobs;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffTest {

    @Test
    void doublesEachAttemptWithBoundedJitter() {
        for (int attempt = 1; attempt <= 5; attempt++) {
            long base = 2000L << (attempt - 1);
            long delay = JobQueue.backoffMillis(attempt);
            assertThat(delay).isBetween(base, base + base / 5);
        }
    }

    @Test
    void isCappedAtFiveMinutes() {
        assertThat(JobQueue.backoffMillis(30)).isBetween(300_000L, 360_000L);
    }
}
