package dev.darshan.spotme.media;

import dev.darshan.spotme.support.Api;
import dev.darshan.spotme.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UploadIntegrationTest extends IntegrationTest {

    @Test
    void uploadCreatesPhotoAndJobTogether() {
        String org = api.registerOrganizer("upload@example.com");
        String event = api.createEvent(org, "Upload");
        var res = api.upload(event, org, "../../etc/passwd.jpg", Api.fixture("hurley.jpg"));
        assertThat(res.getStatusCode().value()).isEqualTo(201);
        String id = (String) res.getBody().get("id");

        assertThat(jdbc.queryForObject("SELECT status FROM photos WHERE id = ?::uuid", String.class, id)).isEqualTo("queued");
        assertThat(jdbc.queryForObject("SELECT state FROM jobs WHERE dedupe_key = ?", String.class, "index:" + id)).isEqualTo("queued");
        // the stored key comes from our ids, never from the uploaded file name
        assertThat(jdbc.queryForObject("SELECT object_key FROM photos WHERE id = ?::uuid", String.class, id))
                .isEqualTo("events/" + event + "/" + id + ".jpg");
    }

    @Test
    void sameBytesTwiceIsADuplicateNotANewJob() {
        String org = api.registerOrganizer("dupe@example.com");
        String event = api.createEvent(org, "Dupe");
        api.upload(event, org, "a.jpg", Api.fixture("glover.jpg"));
        var second = api.upload(event, org, "b.jpg", Api.fixture("glover.jpg"));
        assertThat(second.getBody()).containsEntry("status", "duplicate");
        assertThat(count("SELECT count(*) FROM photos WHERE event_id = ?::uuid", event)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM jobs")).isEqualTo(1);
    }

    @Test
    void badFilesGetAClearErrorAndNothingIsStored() {
        String org = api.registerOrganizer("bad@example.com");
        String event = api.createEvent(org, "Bad");
        var res = api.upload(event, org, "notes.jpg", "not really a photo".getBytes());
        assertThat(res.getStatusCode().value()).isEqualTo(422);
        assertThat(res.getBody()).containsEntry("code", "unsupported_type");
        assertThat(count("SELECT count(*) FROM photos WHERE event_id = ?::uuid", event)).isZero();
    }

    @Test
    void onlyTheOwnerCanUploadOrSeeTheEvent() {
        String owner = api.registerOrganizer("owner@example.com");
        String stranger = api.registerOrganizer("stranger@example.com");
        String event = api.createEvent(owner, "Private");

        assertThat(api.upload(event, null, "x.jpg", Api.fixture("hurley.jpg")).getStatusCode().value()).isEqualTo(401);
        assertThat(api.upload(event, stranger, "x.jpg", Api.fixture("hurley.jpg")).getStatusCode().value()).isEqualTo(404);
        assertThat(api.get("/api/events/" + event, stranger).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void eventNeedsConsentConfirmationAndAShortRetention() {
        String org = api.registerOrganizer("consent@example.com");
        var noConsent = api.post("/api/events", org, Map.of("title", "x", "purposeNotice", "y",
                "expiresAt", Instant.now().plus(1, ChronoUnit.DAYS).toString(), "consentAttested", false));
        assertThat(noConsent.getStatusCode().value()).isEqualTo(400);

        var tooLong = api.post("/api/events", org, Map.of("title", "x", "purposeNotice", "y",
                "expiresAt", Instant.now().plus(200, ChronoUnit.DAYS).toString(), "consentAttested", true));
        assertThat(tooLong.getBody()).containsEntry("code", "expiry_too_far");
    }
}
