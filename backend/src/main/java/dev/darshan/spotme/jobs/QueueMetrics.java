package dev.darshan.spotme.jobs;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Gauges read from the database: queue depth by state, how long the oldest ready job has waited,
 * and the bytes of stored photos. Read every 15 seconds instead of on every scrape, so a busy
 * Prometheus can't add load to the database.
 */
@Component
public class QueueMetrics {

    private static final Logger log = LoggerFactory.getLogger(QueueMetrics.class);

    private final JdbcTemplate jdbc;
    private final AtomicLong queued = new AtomicLong();
    private final AtomicLong running = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong oldestWaitSeconds = new AtomicLong();
    private final AtomicLong photoBytes = new AtomicLong();

    public QueueMetrics(JdbcTemplate jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        gauge(metrics, "spotme.jobs", queued, "Jobs by state", "state", "queued");
        gauge(metrics, "spotme.jobs", running, "Jobs by state", "state", "running");
        gauge(metrics, "spotme.jobs", failed, "Jobs by state", "state", "failed");
        Gauge.builder("spotme.jobs.oldest.wait", oldestWaitSeconds, AtomicLong::get)
                .description("How long the oldest ready job has waited")
                .baseUnit("seconds")
                .register(metrics);
        Gauge.builder("spotme.storage.photos", photoBytes, AtomicLong::get)
                .description("Size of the stored original photos (not purged)")
                .baseUnit("bytes")
                .register(metrics);
    }

    private static void gauge(MeterRegistry metrics, String name, AtomicLong value, String description,
                              String tagKey, String tagValue) {
        Gauge.builder(name, value, AtomicLong::get).description(description).tag(tagKey, tagValue).register(metrics);
    }

    @Scheduled(fixedDelay = 15_000)
    public void refresh() {
        try {
            jdbc.query("""
                    SELECT count(*) FILTER (WHERE state = 'queued'),
                           count(*) FILTER (WHERE state = 'running'),
                           count(*) FILTER (WHERE state = 'failed'),
                           COALESCE(EXTRACT(EPOCH FROM now() - min(run_after)
                                    FILTER (WHERE state = 'queued' AND run_after <= now())), 0)::bigint
                    FROM jobs
                    """, rs -> {
                queued.set(rs.getLong(1));
                running.set(rs.getLong(2));
                failed.set(rs.getLong(3));
                oldestWaitSeconds.set(rs.getLong(4));
            });
            Long bytes = jdbc.queryForObject(
                    "SELECT COALESCE(sum(byte_size), 0) FROM photos WHERE status <> 'purged'", Long.class);
            photoBytes.set(bytes == null ? 0 : bytes);
        } catch (RuntimeException e) {
            // the database is down: keep the last values, the health check shows the outage
            log.warn("Could not read queue metrics: {}", e.getMessage());
        }
    }
}
