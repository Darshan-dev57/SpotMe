package dev.darshan.spotme.common;

import dev.darshan.spotme.jobs.QueueMetrics;
import dev.darshan.spotme.support.Api;
import dev.darshan.spotme.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Metrics, request ids and job ids in the logs, and no secrets in the logs. */
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityIntegrationTest extends IntegrationTest {

    @Autowired
    QueueMetrics queueMetrics;

    private RestClient raw() {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
                .build();
    }

    @Test
    void everyResponseCarriesARequestId() {
        ResponseEntity<String> given = raw().get().uri("/actuator/health").header("X-Request-Id", "abc-123")
                .retrieve().toEntity(String.class);
        assertThat(given.getHeaders().getFirst("X-Request-Id")).isEqualTo("abc-123");

        ResponseEntity<String> none = raw().get().uri("/actuator/health").retrieve().toEntity(String.class);
        assertThat(none.getHeaders().getFirst("X-Request-Id")).matches("[0-9a-f-]{36}");

        // an id that could break a log line is replaced, not copied
        ResponseEntity<String> unsafe = raw().get().uri("/api/events").header("X-Request-Id", "a b {c}")
                .retrieve().toEntity(String.class);
        assertThat(unsafe.getStatusCode().value()).isEqualTo(401);
        assertThat(unsafe.getHeaders().getFirst("X-Request-Id")).matches("[0-9a-f-]{36}");
    }

    @Test
    void prometheusShowsQueueSearchErrorsAndPool() {
        String org = api.registerOrganizer("metrics@example.com");
        String event = api.createEvent(org, "Metrics");
        assertThat(api.upload(event, org, "behnken.jpg", Api.fixture("behnken.jpg")).getStatusCode().value()).isEqualTo(201);
        assertThat(api.upload(event, org, "notes.jpg", "not a photo".getBytes()).getStatusCode().value()).isEqualTo(422);
        drainJobs();
        api.post("/api/events/" + event + "/publish", org, null);
        String guest = api.joinAsAttendee(org, event, "Guest");
        assertThat(api.search(guest, Api.fixture("behnken.jpg")).getStatusCode().value()).isEqualTo(200);

        // 10 wrong passwords are answered 401, the 11th hits the login limit
        for (int i = 0; i < 11; i++) {
            api.post("/api/auth/login", null, Map.of("email", "metrics@example.com", "password", "wrong-password"));
        }
        queueMetrics.refresh();

        String text = raw().get().uri("/actuator/prometheus").retrieve().body(String.class);
        assertThat(value(text, "spotme_jobs{", "state=\"queued\"")).isGreaterThanOrEqualTo(0);
        assertThat(value(text, "spotme_jobs_oldest_wait_seconds")).isGreaterThanOrEqualTo(0);
        assertThat(value(text, "spotme_storage_photos_bytes")).isPositive();
        assertThat(value(text, "spotme_jobs_finished_total{", "kind=\"INDEX_PHOTO\"", "outcome=\"indexed\"")).isPositive();
        assertThat(value(text, "spotme_jobs_duration_seconds_count{", "kind=\"INDEX_PHOTO\"")).isPositive();
        assertThat(value(text, "spotme_search_seconds{", "quantile=\"0.95\"")).isPositive();
        assertThat(value(text, "spotme_search_seconds_count")).isPositive();
        assertThat(value(text, "spotme_uploads_total{", "result=\"queued\"")).isPositive();
        assertThat(value(text, "spotme_uploads_total{", "result=\"rejected\"")).isPositive();
        assertThat(value(text, "spotme_auth_failures_total{", "code=\"bad_credentials\"")).isGreaterThanOrEqualTo(10);
        assertThat(value(text, "spotme_rate_limit_rejections_total{", "route=\"/api/auth/login\"")).isPositive();
        assertThat(value(text, "hikaricp_connections_active")).isGreaterThanOrEqualTo(0);
        assertThat(value(text, "http_server_requests_seconds_count")).isPositive();
    }

    @Test
    void logsCarryJobIdsButNeverPasswordsOrTokens(CapturedOutput output) throws Exception {
        String password = "Secret-Pass-" + System.nanoTime();
        var registered = api.post("/api/auth/register", null,
                Map.of("email", "logs@example.com", "name", "Logs", "password", password)).getBody();
        String org = (String) registered.get("token");
        String event = api.createEvent(org, "Logs");
        String photo = (String) api.upload(event, org, "hurley.jpg", Api.fixture("hurley.jpg")).getBody().get("id");
        var invite = api.post("/api/events/" + event + "/invites", org, Map.of("label", "Guest")).getBody();
        String inviteToken = (String) invite.get("token");

        // the stored file disappears, so the index job fails and logs it
        String key = jdbc.queryForObject("SELECT object_key FROM photos WHERE id = ?::uuid", String.class, photo);
        Files.delete(storageDir().resolve(key));
        long jobId = jdbc.queryForObject("SELECT id FROM jobs WHERE target_id = ?::uuid", Long.class, photo);
        drainJobs();

        api.post("/api/events/" + event + "/publish", org, null);
        String guest = api.joinAsAttendee(org, event, "Guest 2");
        api.search(guest, Api.fixture("hurley.jpg"));
        api.post("/api/auth/login", null, Map.of("email", "logs@example.com", "password", "wrong-" + password));

        assertThat(output.getAll()).contains("[job-" + jobId + "]").contains("failed for good: file_missing");
        assertThat(output.getAll()).doesNotContain(password, org, inviteToken, guest);
    }

    /** The value of the first sample line that starts with the name and has every tag. */
    private static double value(String text, String start, String... tags) {
        return Arrays.stream(text.split("\n"))
                .filter(line -> line.startsWith(start))
                .filter(line -> Arrays.stream(tags).allMatch(line::contains))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                .findFirst()
                .orElse(-1);
    }
}
