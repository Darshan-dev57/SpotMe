package dev.darshan.spotme.demo;

import dev.darshan.spotme.jobs.JobKind;
import dev.darshan.spotme.support.Api;
import dev.darshan.spotme.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DemoIntegrationTest extends IntegrationTest {

    static final Path PHOTOS = demoPhotos();

    @DynamicPropertySource
    static void demo(DynamicPropertyRegistry registry) {
        registry.add("spotme.demo.enabled", () -> "true");
        registry.add("spotme.demo.photos-dir", PHOTOS::toString);
        registry.add("spotme.demo.invites-per-minute", () -> "3");
    }

    @Autowired
    DemoService demo;

    @Test
    void startupCreatesOnePublishedDemoEventWithThePhotos() {
        demo.ensureDemoEvent(); // already ran at startup: a second run must not make another event
        assertThat(count("SELECT count(*) FROM events WHERE demo AND status = 'published'")).isEqualTo(1);
        var summary = api.get("/api/demo", null);
        assertThat(summary.getStatusCode().value()).isEqualTo(200);
        assertThat(summary.getBody()).containsEntry("photoCount", 2);
    }

    @Test
    void aVisitorJoinsWithTheirOwnInviteAndFindsTheGroupPhoto() {
        // each test starts with an empty job table, so queue the demo photos again and index them
        for (UUID id : jdbc.queryForList(
                "SELECT p.id FROM photos p JOIN events e ON e.id = p.event_id WHERE e.demo AND p.status = 'queued'", UUID.class)) {
            queue.enqueue(JobKind.INDEX_PHOTO, id, "index:" + id, 5);
        }
        drainJobs();
        var invite = api.post("/api/demo/invite", null, null);
        assertThat(invite.getStatusCode().value()).isEqualTo(201);
        String token = (String) invite.getBody().get("token");

        var notice = api.get("/api/join/" + token, null).getBody();
        assertThat(notice).containsEntry("demo", true).containsEntry("published", true);

        var consent = api.post("/api/join/" + token + "/consent", null, Map.of("noticeVersion", 1, "agree", true));
        String session = (String) consent.getBody().get("sessionToken");
        var result = api.search(session, Api.fixture("hurley.jpg"));
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(((Number) result.getBody().get("count")).intValue()).isEqualTo(1);
    }

    @Test
    void demoInvitesAreRateLimitedPerAddress() {
        // a made-up visitor address, so this test has its own limit
        var http = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { }).build();
        int created = 0;
        int limited = 0;
        for (int i = 0; i < 6; i++) {
            int status = http.post().uri("/api/demo/invite").header("X-Forwarded-For", "203.0.113.7")
                    .retrieve().toBodilessEntity().getStatusCode().value();
            if (status == 201) {
                created++;
            } else if (status == 429) {
                limited++;
            }
        }
        assertThat(created).isEqualTo(3);
        assertThat(limited).isEqualTo(3);
    }

    @Test
    void aMadeUpForwardedForEntryDoesNotDodgeTheLimit() {
        // the visitor writes a new fake address into X-Forwarded-For each time; our proxy appends the real one
        var http = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { }).build();
        int limited = 0;
        for (int i = 0; i < 6; i++) {
            int status = http.post().uri("/api/demo/invite")
                    .header("X-Forwarded-For", "198.18.0." + (i + 1) + ", 198.51.100.23")
                    .retrieve().toBodilessEntity().getStatusCode().value();
            if (status == 429) {
                limited++;
            }
        }
        assertThat(limited).isEqualTo(3);
    }

    @Test
    void theDemoOrganizerCannotSignIn() {
        var res = api.post("/api/auth/login", null, Map.of("email", DemoService.ORGANIZER_EMAIL, "password", "password123"));
        assertThat(res.getStatusCode().value()).isEqualTo(401);
    }

    /** The group photo (Hurley is in it) and a portrait of someone else. */
    private static Path demoPhotos() {
        try {
            Path dir = Files.createTempDirectory("spotme-demo");
            Files.write(dir.resolve("01.jpg"), Api.fixture("crew-group.jpg"));
            Files.write(dir.resolve("02.jpg"), Api.fixture("glover.jpg"));
            Files.writeString(dir.resolve("notes.txt"), "not a photo, ignored");
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
