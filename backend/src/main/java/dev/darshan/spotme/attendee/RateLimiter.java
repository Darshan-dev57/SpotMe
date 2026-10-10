package dev.darshan.spotme.attendee;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * At most N searches per attendee per rolling minute, kept in memory.
 * Good enough for one API process; several processes would need a shared store.
 */
public class RateLimiter {

    private final int perMinute;
    private final Map<UUID, Deque<Long>> calls = new ConcurrentHashMap<>();

    public RateLimiter(int perMinute) {
        this.perMinute = perMinute;
    }

    public boolean tryAcquire(UUID key) {
        long now = System.currentTimeMillis();
        Deque<Long> times = calls.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > 60_000) {
                times.pollFirst();
            }
            if (times.size() >= perMinute) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }
}
