package dev.darshan.spotme.common;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    @Test
    void allowsTheLimitThenRefusesPerAttendee() {
        RateLimiter<UUID> limiter = new RateLimiter<>(3);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertThat(limiter.tryAcquire(a)).isTrue();
        assertThat(limiter.tryAcquire(a)).isTrue();
        assertThat(limiter.tryAcquire(a)).isTrue();
        assertThat(limiter.tryAcquire(a)).isFalse();
        assertThat(limiter.tryAcquire(b)).isTrue();
    }
}
