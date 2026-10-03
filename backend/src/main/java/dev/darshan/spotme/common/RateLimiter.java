package dev.darshan.spotme.common;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * At most N calls per key (an attendee, an email) per rolling minute, kept in memory.
 * Good enough for one API process; several processes would need a shared store.
 */
public class RateLimiter<K> {

    private final int perMinute;
    private final Map<K, Deque<Long>> calls = new ConcurrentHashMap<>();

    public RateLimiter(int perMinute) {
        this.perMinute = perMinute;
    }

    public boolean tryAcquire(K key) {
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
