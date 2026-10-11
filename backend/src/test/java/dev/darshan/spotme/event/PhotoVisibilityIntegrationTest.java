package dev.darshan.spotme.event;

import dev.darshan.spotme.support.Api;
import dev.darshan.spotme.support.IntegrationTest;
import dev.darshan.spotme.support.Models;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Public photos are shown to every guest; private ones only to guests whose face matches. */
class PhotoVisibilityIntegrationTest extends IntegrationTest {

    String org;
    String event;
    String groupPhoto;
    String gloverPhoto;

    @BeforeEach
    void eventWithTwoPhotos() {
        Models.assumePresent();
        org = api.registerOrganizer("vis-" + System.nanoTime() + "@example.com");
        event = api.createEvent(org, "Visibility");
        groupPhoto = (String) api.upload(event, org, "crew-group.jpg", Api.fixture("crew-group.jpg")).getBody().get("id");
        gloverPhoto = (String) api.upload(event, org, "glover.jpg", Api.fixture("glover.jpg")).getBody().get("id");
        drainJobs();
        api.post("/api/events/" + event + "/publish", org, null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> eventPhotos(String guest) {
        var res = api.get("/api/attendee/event-photos", guest);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return (List<Map<String, Object>>) res.getBody().get("photos");
    }

    private void setVisibility(String visibility, String... photoIds) {
        var res = api.post("/api/events/" + event + "/photos/visibility", org,
                Map.of("photoIds", List.of(photoIds), "visibility", visibility));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void photosArePrivateByDefault() {
        String guest = api.joinAsAttendee(org, event, "Asha");
        assertThat(eventPhotos(guest)).isEmpty();
        assertThat(api.getList("/api/events/" + event + "/photos", org))
                .allSatisfy(p -> assertThat(p).containsEntry("public", false));
    }

    @Test
    void publicPhotoIsShownToAGuestWhoIsNotInIt() {
        setVisibility("public", groupPhoto);
        String guest = api.joinAsAttendee(org, event, "Stranger");

        List<Map<String, Object>> shared = eventPhotos(guest);
        assertThat(shared).extracting(p -> p.get("id")).containsExactly(groupPhoto);
        assertThat(api.getBytes((String) shared.get(0).get("thumbUrl")).getStatusCode().value()).isEqualTo(200);
        assertThat(api.get("/api/events/" + event, org).getBody()).containsEntry("publicPhotos", 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publicPhotosAreStillIndexedAndMatched() {
        setVisibility("public", groupPhoto);
        String doug = api.joinAsAttendee(org, event, "Doug");
        var matches = (List<Map<String, Object>>) api.search(doug, Api.fixture("hurley.jpg")).getBody().get("photos");
        assertThat(matches).extracting(p -> p.get("id")).containsExactly(groupPhoto);
    }

    @Test
    void makingAPhotoPrivateAgainHidesItFromEveryone() {
        setVisibility("public", groupPhoto, gloverPhoto);
        setVisibility("private", groupPhoto);
        String guest = api.joinAsAttendee(org, event, "Rohan");
        assertThat(eventPhotos(guest)).extracting(p -> p.get("id")).containsExactly(gloverPhoto);
    }

    @Test
    void hiddenPublicPhotoIsNotShared() {
        setVisibility("public", groupPhoto);
        api.post("/api/events/" + event + "/photos/" + groupPhoto + "/hide", org, null);
        assertThat(eventPhotos(api.joinAsAttendee(org, event, "Meera"))).isEmpty();
    }

    @Test
    void photosOfAnotherEventCannotBeChanged() {
        String otherOrg = api.registerOrganizer("vis-other-" + System.nanoTime() + "@example.com");
        String otherEvent = api.createEvent(otherOrg, "Someone else's");
        String foreign = (String) api.upload(otherEvent, otherOrg, "behnken.jpg", Api.fixture("behnken.jpg")).getBody().get("id");

        var res = api.post("/api/events/" + event + "/photos/visibility", org,
                Map.of("photoIds", List.of(foreign), "visibility", "public"));

        assertThat(res.getBody()).containsEntry("updated", 0);
        assertThat(jdbc.queryForObject("SELECT is_public FROM photos WHERE id = ?::uuid", Boolean.class, foreign)).isFalse();
        // and the other organizer's event cannot be reached at all
        assertThat(api.post("/api/events/" + otherEvent + "/photos/visibility", org,
                Map.of("photoIds", List.of(foreign), "visibility", "public")).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void unknownVisibilityIsRejected() {
        var res = api.post("/api/events/" + event + "/photos/visibility", org,
                Map.of("photoIds", List.of(groupPhoto), "visibility", "everyone"));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }
}
