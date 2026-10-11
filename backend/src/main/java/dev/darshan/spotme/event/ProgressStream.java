package dev.darshan.spotme.event;

import dev.darshan.spotme.common.ApiException;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Live indexing progress for organizers over Server-Sent Events. Once a second, for each event
 * someone is watching, the counts are read once and sent to every open stream of that event,
 * but only when they changed. A comment line every 15 seconds keeps proxies from closing an idle
 * stream and finds clients that went away. Streams are removed on completion, timeout and error.
 */
@Component
public class ProgressStream {

    /** A stream ends after 10 minutes; the page simply opens a new one. */
    static final long TIMEOUT_MS = 10 * 60 * 1000;
    /** Open streams on the whole server; past this the page falls back to polling. */
    static final int MAX_STREAMS = 20;
    static final long HEARTBEAT_MS = 15_000;

    private final EventProgress progress;
    private final Map<UUID, List<SseEmitter>> streams = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastSent = new ConcurrentHashMap<>();
    private final AtomicInteger open = new AtomicInteger();
    private volatile long lastHeartbeat = System.currentTimeMillis();

    public ProgressStream(EventProgress progress) {
        this.progress = progress;
    }

    public synchronized SseEmitter subscribe(UUID eventId) {
        if (open.incrementAndGet() > MAX_STREAMS) {
            open.decrementAndGet();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "streams_full", "Live updates are busy; the page will refresh every few seconds.");
        }
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        streams.computeIfAbsent(eventId, id -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(eventId, emitter));
        emitter.onTimeout(() -> remove(eventId, emitter));
        emitter.onError(e -> remove(eventId, emitter));
        // the first message is the current state, so the page never waits for a change
        send(eventId, emitter, progress.snapshot(eventId));
        return emitter;
    }

    /** How many streams are open right now (used by tests and the health page). */
    public int openStreams() {
        return open.get();
    }

    @Scheduled(fixedDelay = 1000)
    public void push() {
        boolean heartbeat = System.currentTimeMillis() - lastHeartbeat >= HEARTBEAT_MS;
        if (heartbeat) {
            lastHeartbeat = System.currentTimeMillis();
        }
        for (Map.Entry<UUID, List<SseEmitter>> entry : streams.entrySet()) {
            UUID eventId = entry.getKey();
            Map<String, Object> data = progress.snapshot(eventId);
            String key = changeKey(data);
            boolean changed = !key.equals(lastSent.get(eventId));
            for (SseEmitter emitter : entry.getValue()) {
                if (changed) {
                    send(eventId, emitter, data);
                } else if (heartbeat) {
                    try {
                        emitter.send(SseEmitter.event().comment("keep-alive"));
                    } catch (IOException | IllegalStateException e) {
                        remove(eventId, emitter);
                    }
                }
            }
            if (changed) {
                lastSent.put(eventId, key);
            }
        }
    }

    /**
     * Ends every open stream when the app stops. Otherwise graceful shutdown waits the full
     * 30 seconds for streams that would stay open for up to 10 minutes.
     */
    @EventListener(ContextClosedEvent.class)
    public void closeAll() {
        for (List<SseEmitter> list : streams.values()) {
            for (SseEmitter emitter : list) {
                emitter.complete();
            }
        }
    }

    private void send(UUID eventId, SseEmitter emitter, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name("progress").data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // the browser went away: forget the stream now instead of on the next timeout
            remove(eventId, emitter);
        }
    }

    private synchronized void remove(UUID eventId, SseEmitter emitter) {
        List<SseEmitter> list = streams.get(eventId);
        if (list != null && list.remove(emitter)) {
            open.decrementAndGet();
            if (list.isEmpty()) {
                streams.remove(eventId, list);
                lastSent.remove(eventId);
            }
        }
    }

    /** What counts as a change: the numbers on screen, not the worker's heartbeat time. */
    private static String changeKey(Map<String, Object> snapshot) {
        Object worker = snapshot.get("worker");
        Object active = worker instanceof Map<?, ?> w ? w.get("active") : null;
        return snapshot.get("counts") + "|" + snapshot.get("faces") + "|" + snapshot.get("hidden") + "|" + active;
    }
}
