package dev.darshan.spotme.attendee;

import dev.darshan.spotme.support.Api;
import dev.darshan.spotme.support.IntegrationTest;
import dev.darshan.spotme.support.Models;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The attendee's actions after a search, and the edges of their access. */
class AttendeeIntegrationTest extends IntegrationTest {

    String org;
    String event;
    String groupPhoto;
    String attendee;

    @BeforeEach
    void eventWithOneIndexedGroupPhoto() {
        Models.assumePresent();
        org = api.registerOrganizer("att-" + System.nanoTime() + "@example.com");
        event = api.createEvent(org, "Attendee flows");
        groupPhoto = (String) api.upload(event, org, "crew-group.jpg", Api.fixture("crew-group.jpg")).getBody().get("id");
        drainJobs();
        api.post("/api/events/" + event + "/publish", org, null);
        attendee = api.joinAsAttendee(org, event, "Doug");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> search(String token) {
        return (List<Map<String, Object>>) api.search(token, Api.fixture("hurley.jpg")).getBody().get("photos");
    }

    @Test
    void wrongMatchHidesThePhotoOnlyForTheReporter() {
        assertThat(search(attendee)).hasSize(1);
        api.post("/api/attendee/wrong-match", attendee, Map.of("photoId", groupPhoto));
        assertThat(search(attendee)).isEmpty();

        String other = api.joinAsAttendee(org, event, "Bob");
        assertThat(search(other)).hasSize(1);
        assertThat(api.getList("/api/events/" + event + "/photos", org).get(0)).containsEntry("wrongMatchReports", 1);
    }

    @Test
    void changingTheNoticeRequiresConsentAgain() {
        assertThat(api.search(attendee, Api.fixture("hurley.jpg")).getStatusCode().value()).isEqualTo(200);
        api.post("/api/events/" + event + "/notice", org, Map.of("purposeNotice", "New purpose: also used for the yearbook."));
        assertThat(api.search(attendee, Api.fixture("hurley.jpg")).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void oldNoticeVersionCannotBeAccepted() {
        var invite = api.post("/api/events/" + event + "/invites", org, Map.of("label", "Late")).getBody();
        api.post("/api/events/" + event + "/notice", org, Map.of("purposeNotice", "Changed."));
        var res = api.post("/api/join/" + invite.get("token") + "/consent", null, Map.of("noticeVersion", 1, "agree", true));
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody()).containsEntry("code", "notice_changed");
    }

    @Test
    void hiddenPhotoDisappearsFromSearchAndItsLinksStopWorking() {
        String thumb = (String) search(attendee).get(0).get("thumbUrl");
        assertThat(api.getBytes(thumb).getStatusCode().value()).isEqualTo(200);

        api.post("/api/events/" + event + "/photos/" + groupPhoto + "/hide", org, null);

        assertThat(search(attendee)).isEmpty();
        assertThat(api.getBytes(thumb).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void cannotAskToRemoveAPhotoFromAnotherEvent() {
        String otherEvent = api.createEvent(org, "Elsewhere");
        String foreign = (String) api.upload(otherEvent, org, "glover.jpg", Api.fixture("glover.jpg")).getBody().get("id");
        var res = api.post("/api/attendee/removals", attendee, Api.removal(foreign, null));
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT suppressed_at IS NULL FROM photos WHERE id = ?::uuid", Boolean.class, foreign)).isTrue();
    }

    @Test
    void rejectingOneRequestKeepsAPhotoHiddenWhileAnotherIsOpen() {
        String solo = (String) api.upload(event, org, "hurley.jpg", Api.fixture("hurley.jpg")).getBody().get("id");
        drainJobs();
        String bob = api.joinAsAttendee(org, event, "Bob");
        String first = (String) api.post("/api/attendee/removals", attendee, Api.removal(solo, null)).getBody().get("id");
        api.post("/api/attendee/removals", bob, Api.removal(solo, null));

        api.post("/api/events/" + event + "/removals/" + first + "/reject", org, null);

        assertThat(jdbc.queryForObject("SELECT suppressed_at IS NOT NULL FROM photos WHERE id = ?::uuid", Boolean.class, solo)).isTrue();
        assertThat(api.getList("/api/events/" + event + "/removals", org)).hasSize(2);
    }

    @Test
    void zipContainsOnlyPhotosTheAttendeeMayDownload() throws Exception {
        String hidden = (String) api.upload(event, org, "behnken.jpg", Api.fixture("behnken.jpg")).getBody().get("id");
        drainJobs();
        api.post("/api/events/" + event + "/photos/" + hidden + "/hide", org, null);
        assertThat(search(attendee)).hasSize(1);

        byte[] zip = RestClient.create("http://localhost:" + port).post().uri("/api/attendee/download")
                .header("Authorization", "Bearer " + attendee).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("photoIds", List.of(groupPhoto, hidden))).retrieve().body(byte[].class);

        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                names.add(e.getName());
            }
        }
        assertThat(names).containsExactly("photo-001.jpg");
    }

    @Test
    void organizerLoginIsRateLimitedPerEmail() {
        api.post("/api/auth/register", null, Map.of("email", "limit@example.com", "name", "L", "password", "password123"));
        for (int i = 0; i < 10; i++) {
            assertThat(api.post("/api/auth/login", null, Map.of("email", "limit@example.com", "password", "wrong-password"))
                    .getBody()).containsEntry("code", "bad_credentials");
        }
        assertThat(api.post("/api/auth/login", null, Map.of("email", "limit@example.com", "password", "password123"))
                .getStatusCode().value()).isEqualTo(429);
    }

    @Test
    void guestResultsCarryNoFaceBoxesOrVectors() {
        List<Map<String, Object>> photos = search(attendee);
        assertThat(photos).isNotEmpty();
        for (Map<String, Object> photo : photos) {
            assertThat(photo.keySet()).containsOnly("id", "thumbUrl", "downloadUrl", "faceId", "solo");
        }
    }

    @Test
    void aBadRequestGetsShortJsonWithoutInternals() {
        var res = api.post("/api/attendee/wrong-match", attendee, Map.of("photoId", "not-a-uuid"));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getHeaders().getContentType()).isNotNull();
        assertThat(res.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(res.getBody()).containsOnlyKeys("code", "message");
        assertThat(res.getBody().toString()).doesNotContain("Exception", "at dev.", "trace");
    }
}
