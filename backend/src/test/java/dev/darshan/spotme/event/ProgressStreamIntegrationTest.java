package dev.darshan.spotme.event;

import dev.darshan.spotme.support.Api;
import dev.darshan.spotme.support.IntegrationTest;
import dev.darshan.spotme.support.Models;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The organizer's live progress stream (Server-Sent Events) and its polling fallback. */
class ProgressStreamIntegrationTest extends IntegrationTest {

    @Autowired
    ProgressStream streams;

    String org;
    String event;
    HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void emptyEvent() {
        Models.assumePresent();
        org = api.registerOrganizer("sse-" + System.nanoTime() + "@example.com");
        event = api.createEvent(org, "Live progress");
    }

    private HttpResponse<Stream<String>> open(String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/events/" + event + "/progress/stream"))
                .header("Authorization", "Bearer " + token).header("Accept", "text/event-stream").build();
        return client.send(request, HttpResponse.BodyHandlers.ofLines());
    }

    /** Collects the "data:" lines of a stream on a background thread. */
    private static BlockingQueue<String> events(HttpResponse<Stream<String>> response) {
        BlockingQueue<String> data = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try {
                response.body().filter(line -> line.startsWith("data:")).forEach(line -> data.add(line.substring(5)));
            } catch (RuntimeException closed) {
                // the test closed the stream
            }
        });
        reader.setDaemon(true);
        reader.start();
        return data;
    }

    /** Waits for an event containing the text; older events are skipped. */
    private static String next(BlockingQueue<String> data, String containing) throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            String event = data.poll(500, TimeUnit.MILLISECONDS);
            if (event != null && event.contains(containing)) {
                return event;
            }
        }
        throw new AssertionError("no event with " + containing);
    }

    @Test
    void countsMoveLiveDuringAnUpload() throws Exception {
        HttpResponse<Stream<String>> response = open(org);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        BlockingQueue<String> data = events(response);

        // the current state arrives at once
        next(data, "\"queued\":0");

        api.upload(event, org, "hurley.jpg", Api.fixture("hurley.jpg"));
        next(data, "\"queued\":1");

        drainJobs();
        String done = next(data, "\"ready\":1");
        assertThat(done).contains("\"faces\":1");
        response.body().close();
    }

    @Test
    void aClosedStreamIsCleanedUp() throws Exception {
        int before = streams.openStreams();
        HttpResponse<Stream<String>> response = open(org);
        next(events(response), "\"queued\":0");
        assertThat(streams.openStreams()).isEqualTo(before + 1);

        response.body().close();
        // the next writes fail on the closed connection, and the stream is dropped
        api.upload(event, org, "glover.jpg", Api.fixture("glover.jpg"));
        drainJobs();
        await().atMost(Duration.ofSeconds(30)).until(() -> streams.openStreams() == before);
    }

    @Test
    void anotherOrganizerCannotWatch() throws Exception {
        String other = api.registerOrganizer("sse-other-" + System.nanoTime() + "@example.com");
        HttpResponse<Stream<String>> response = open(other);
        assertThat(response.statusCode()).isEqualTo(404);
        response.body().close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void pollingFallbackReturnsTheSameCounts() {
        api.upload(event, org, "hurley.jpg", Api.fixture("hurley.jpg"));
        var body = api.get("/api/events/" + event + "/progress", org).getBody();
        assertThat((java.util.Map<String, Object>) body.get("counts")).containsEntry("queued", 1);
        assertThat(api.get("/api/events/" + event + "/progress", null).getStatusCode().value()).isEqualTo(401);
    }
}
