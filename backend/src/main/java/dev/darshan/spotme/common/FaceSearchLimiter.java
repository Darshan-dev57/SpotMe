package dev.darshan.spotme.common;

import dev.darshan.spotme.config.SpotMeProperties;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * One budget of face lookups per guest per minute, shared by every endpoint that turns a selfie into a
 * face vector (search and the new-photo alert opt-in). Separate limiters per endpoint would let a guest
 * add the limits together by switching endpoints.
 */
@Component
public class FaceSearchLimiter {

    private final RateLimiter<UUID> limiter;

    public FaceSearchLimiter(SpotMeProperties props) {
        this.limiter = new RateLimiter<>(props.search().perMinuteLimit());
    }

    public boolean tryAcquire(UUID membershipId) {
        return limiter.tryAcquire(membershipId);
    }
}
