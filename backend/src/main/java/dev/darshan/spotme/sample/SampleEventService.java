package dev.darshan.spotme.sample;

import dev.darshan.spotme.common.ApiException;
import dev.darshan.spotme.common.Tokens;
import dev.darshan.spotme.config.SpotMeProperties;
import dev.darshan.spotme.media.PhotoIntake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The public sample event. When spotme.sample.enabled is on, there is always one published sample event,
 * filled with the photos in spotme.sample.photos-dir. Visitors get their own invite to it, so each
 * visitor still reads the notice, consents and can withdraw like a real guest.
 *
 * The sample event follows the normal rules: it expires after 90 days and is purged by the
 * expiry sweeper, and a fresh one is created a day before that.
 */
@Service
public class SampleEventService {

    static final String ORGANIZER_EMAIL = "sample@spotme.invalid";
    static final String TITLE = "Crew Dragon launch week";
    static final String NOTICE = "This is the SpotMe sample event. The photos are public domain NASA photos of the "
            + "Crew Dragon launch week in May 2020. Your selfie is compared in memory and never saved. "
            + "This invite only works for this event and ends when you leave or the event expires.";

    private static final Logger log = LoggerFactory.getLogger(SampleEventService.class);
    private static final Duration LIFETIME = Duration.ofDays(90);

    private final JdbcTemplate jdbc;
    private final PhotoIntake intake;
    private final SpotMeProperties.Sample settings;

    public SampleEventService(JdbcTemplate jdbc, PhotoIntake intake, SpotMeProperties props) {
        this.jdbc = jdbc;
        this.intake = intake;
        this.settings = props.sample();
    }

    public boolean enabled() {
        return settings != null && settings.enabled();
    }

    /** The live sample event: published and not expiring within a day. */
    public Optional<UUID> liveEvent() {
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM events
                WHERE sample AND status = 'published' AND expires_at > now() + interval '1 day'
                ORDER BY created_at DESC LIMIT 1
                """, UUID.class);
        return ids.stream().findFirst();
    }

    /** Runs at startup and every few hours, so a sample event always exists. */
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelay = 6 * 3600 * 1000, initialDelay = 6 * 3600 * 1000)
    public void ensureSampleEvent() {
        if (!enabled() || liveEvent().isPresent()) {
            return;
        }
        List<Path> photos = samplePhotos();
        if (photos.isEmpty()) {
            log.warn("Sample event is on but {} has no .jpg photos, so no sample event was created", settings.photosDir());
            return;
        }
        UUID eventId = createEvent();
        int added = 0;
        for (Path photo : photos) {
            try {
                intake.add(eventId, Files.readAllBytes(photo));
                added++;
            } catch (IOException | ApiException e) {
                log.warn("Skipped sample photo {}: {}", photo.getFileName(), e.getMessage());
            }
        }
        log.info("Created sample event {} with {} photos queued for indexing", eventId, added);
    }

    /**
     * In the sample event, photos with no faces in them (the rocket, the launch pad) are shared with every
     * visitor, so the "Event photos" section shows what an organizer would use it for.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void shareSceneryPhotos() {
        if (!enabled()) {
            return;
        }
        jdbc.update("""
                UPDATE photos SET is_public = true
                WHERE status = 'ready' AND face_count = 0 AND NOT is_public
                  AND event_id IN (SELECT id FROM events WHERE sample AND status = 'published')
                """);
    }

    /** A personal invite to the sample event for one visitor. Returns the raw invite token. */
    public String newVisitorInvite() {
        UUID eventId = liveEvent().orElseThrow(() -> ApiException.notFound("Sample event"));
        String token = Tokens.newToken();
        jdbc.update("INSERT INTO memberships (id, event_id, label, invite_token_hash) VALUES (?, ?, 'Visitor', ?)",
                UUID.randomUUID(), eventId, Tokens.sha256Hex(token));
        return token;
    }

    /** Title and progress for the landing page. */
    public Map<String, Object> summary() {
        UUID eventId = liveEvent().orElseThrow(() -> ApiException.notFound("Sample event"));
        return jdbc.queryForMap("""
                SELECT title,
                       count(p.id) FILTER (WHERE p.status <> 'purged') AS "photoCount",
                       count(p.id) FILTER (WHERE p.status = 'ready') AS "readyCount"
                FROM events e LEFT JOIN photos p ON p.event_id = e.id
                WHERE e.id = ? GROUP BY e.title
                """, eventId);
    }

    private UUID createEvent() {
        UUID organizerId = jdbc.queryForList("SELECT id FROM organizers WHERE email = ?", UUID.class, ORGANIZER_EMAIL)
                .stream().findFirst().orElseGet(this::createOrganizer);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO events (id, organizer_id, title, purpose_notice, status, consent_attested_at, expires_at,
                                    published_at, sample)
                VALUES (?, ?, ?, ?, 'published', now(), ?, now(), true)
                """, id, organizerId, TITLE, NOTICE, Timestamp.from(Instant.now().plus(LIFETIME)));
        return id;
    }

    /** Nobody signs in as the sample organizer: the password is random and thrown away. */
    private UUID createOrganizer() {
        UUID id = UUID.randomUUID();
        String password = new BCryptPasswordEncoder().encode(Tokens.newToken());
        jdbc.update("INSERT INTO organizers (id, email, display_name, password_hash) VALUES (?, ?, 'SpotMe sample', ?)",
                id, ORGANIZER_EMAIL, password);
        return id;
    }

    private List<Path> samplePhotos() {
        if (settings.photosDir() == null || settings.photosDir().isBlank()) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(Path.of(settings.photosDir()))) {
            return files.filter(f -> f.getFileName().toString().toLowerCase().endsWith(".jpg")).sorted().toList();
        } catch (IOException e) {
            log.warn("Could not read sample photos from {}: {}", settings.photosDir(), e.getMessage());
            return List.of();
        }
    }
}
