package dev.darshan.spotme;

import dev.darshan.spotme.support.Api;
import dev.darshan.spotme.support.IntegrationTest;
import dev.darshan.spotme.support.Models;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Upload -> index -> publish -> invite -> consent -> selfie search -> download, over real HTTP. */
class PipelineIntegrationTest extends IntegrationTest {

    @BeforeEach
    void needsModels() {
        Models.assumePresent();
    }

    @Test
    void attendeeFindsTheGroupPhotoAndOnlyFromTheirOwnEvent() {
        String org = api.registerOrganizer("pipeline@example.com");
        String eventA = api.createEvent(org, "Crew evening");
        String eventB = api.createEvent(org, "Other party");

        var group = api.upload(eventA, org, "crew-group.jpg", Api.fixture("crew-group.jpg"));
        assertThat(group.getStatusCode().value()).isEqualTo(201);
        api.upload(eventA, org, "behnken.jpg", Api.fixture("behnken.jpg"));
        // the same Hurley portrait exists only in event B
        var otherEventPhoto = api.upload(eventB, org, "hurley.jpg", Api.fixture("hurley.jpg"));

        assertThat(drainJobs()).isEqualTo(3);
        Map<String, Object> a = api.get("/api/events/" + eventA, org).getBody();
        assertThat((Map<String, Object>) a.get("counts")).containsEntry("ready", 2);
        assertThat(a.get("faces")).isEqualTo(5);

        // not published yet: attendees can't search
        String attendee = api.joinAsAttendee(org, eventA, "Doug");
        assertThat(api.search(attendee, Api.fixture("hurley.jpg")).getStatusCode().value()).isEqualTo(409);

        api.post("/api/events/" + eventA + "/publish", org, null);
        var res = api.search(attendee, Api.fixture("hurley.jpg"));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getFirst("Server-Timing")).contains("faces;dur=");
        List<Map<String, Object>> photos = (List<Map<String, Object>>) res.getBody().get("photos");
        assertThat(photos).extracting(p -> p.get("id")).containsExactly(group.getBody().get("id"));
        assertThat(photos).extracting(p -> p.get("id")).doesNotContain(otherEventPhoto.getBody().get("id"));

        // the signed links work, and editing them doesn't
        String thumb = (String) photos.get(0).get("thumbUrl");
        assertThat(api.getBytes(thumb).getStatusCode().value()).isEqualTo(200);
        assertThat(api.getBytes(thumb.replace("/thumb?", "/original?")).getStatusCode().value()).isEqualTo(403);
        var original = api.getBytes((String) photos.get(0).get("downloadUrl"));
        assertThat(original.getStatusCode().value()).isEqualTo(200);
        assertThat(original.getHeaders().getContentDisposition().isAttachment()).isTrue();
    }

    @Test
    void selfieWithSeveralPeopleOrNoFaceIsRefused() {
        String org = api.registerOrganizer("selfie-rules@example.com");
        String event = api.createEvent(org, "Rules");
        api.post("/api/events/" + event + "/publish", org, null);
        String attendee = api.joinAsAttendee(org, event, "Sam");

        var group = api.search(attendee, Api.fixture("crew-group.jpg"));
        assertThat(group.getStatusCode().value()).isEqualTo(422);
        assertThat(group.getBody()).containsEntry("code", "multiple_faces");

        var notImage = api.search(attendee, "hello".getBytes());
        assertThat(notImage.getBody()).containsEntry("code", "unsupported_type");
    }

    @Test
    void withdrawnOrRevokedAttendeesLoseAccessImmediately() {
        String org = api.registerOrganizer("withdraw@example.com");
        String event = api.createEvent(org, "Withdraw");
        api.post("/api/events/" + event + "/publish", org, null);

        String sam = api.joinAsAttendee(org, event, "Sam");
        assertThat(api.post("/api/attendee/withdraw", sam, null).getStatusCode().value()).isEqualTo(200);
        assertThat(api.search(sam, Api.fixture("hurley.jpg")).getStatusCode().value()).isEqualTo(401);

        String kim = api.joinAsAttendee(org, event, "Kim");
        String kimMembership = jdbc.queryForObject("SELECT id::text FROM memberships WHERE label = 'Kim'", String.class);
        api.delete("/api/events/" + event + "/invites/" + kimMembership, org);
        assertThat(api.search(kim, Api.fixture("hurley.jpg")).getStatusCode().value()).isEqualTo(401);
    }
}
