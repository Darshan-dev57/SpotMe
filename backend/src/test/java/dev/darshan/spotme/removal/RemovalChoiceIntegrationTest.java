package dev.darshan.spotme.removal;

import dev.darshan.spotme.support.Api;
import dev.darshan.spotme.support.IntegrationTest;
import dev.darshan.spotme.support.Models;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** "Only my solo photos" with erasing switched off (the default). */
class RemovalChoiceIntegrationTest extends IntegrationTest {

    String org;
    String event;
    String groupPhoto;
    String soloPhoto;
    String doug;

    @BeforeEach
    void eventWithAGroupPhotoAndASoloPhoto() {
        Models.assumePresent();
        org = api.registerOrganizer("choice-" + System.nanoTime() + "@example.com");
        event = api.createEvent(org, "Removal choice");
        groupPhoto = (String) api.upload(event, org, "crew-group.jpg", Api.fixture("crew-group.jpg")).getBody().get("id");
        soloPhoto = (String) api.upload(event, org, "hurley.jpg", Api.fixture("hurley.jpg")).getBody().get("id");
        drainJobs();
        api.post("/api/events/" + event + "/publish", org, null);
        doug = api.joinAsAttendee(org, event, "Doug");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> search(String token) {
        return (List<Map<String, Object>>) api.search(token, Api.fixture("hurley.jpg")).getBody().get("photos");
    }

    private Map<String, Object> removal(String mode, List<Map<String, Object>> found) {
        List<Map<String, Object>> photos = found.stream()
                .map(p -> Map.<String, Object>of("photoId", p.get("id"), "faceId", p.get("faceId"))).toList();
        return Map.of("mode", mode, "photos", photos);
    }

    @Test
    void searchSaysWhichFaceMatchedAndWhetherTheGuestIsAlone() {
        var body = api.search(doug, Api.fixture("hurley.jpg")).getBody();
        assertThat(body).containsEntry("eraseAvailable", false);
        List<Map<String, Object>> found = search(doug);
        assertThat(found).hasSize(2);
        for (Map<String, Object> p : found) {
            boolean solo = p.get("id").equals(soloPhoto);
            assertThat(p).containsEntry("solo", solo);
            long faceId = ((Number) p.get("faceId")).longValue();
            assertThat(count("SELECT count(*) FROM faces WHERE id = ? AND photo_id = ?::uuid", faceId, p.get("id"))).isEqualTo(1);
        }
    }

    @Test
    void soloChoiceRemovesOnlyPhotosWhereTheGuestIsAlone() {
        var res = api.post("/api/attendee/removals", doug, removal("solo", search(doug)));
        assertThat(res.getStatusCode().value()).isEqualTo(201);
        assertThat(res.getBody()).containsEntry("hidden", 1).containsEntry("keptGroupPhotos", 1).containsEntry("erasing", 0);

        // the solo photo is hidden at once, the group photo stays for everyone
        assertThat(search(doug)).extracting(p -> p.get("id")).containsExactly(groupPhoto);

        api.post("/api/events/" + event + "/removals/" + res.getBody().get("id") + "/approve", org, null);
        drainJobs();
        assertThat(jdbc.queryForObject("SELECT status FROM photos WHERE id = ?::uuid", String.class, soloPhoto)).isEqualTo("purged");
        assertThat(jdbc.queryForObject("SELECT status || ' ' || (suppressed_at IS NULL) FROM photos WHERE id = ?::uuid",
                String.class, groupPhoto)).isEqualTo("ready true");
        assertThat(jdbc.queryForObject("SELECT status || ' ' || mode FROM removal_requests WHERE id = ?::uuid",
                String.class, res.getBody().get("id"))).isEqualTo("completed solo");
    }

    @Test
    void soloChoiceWithOnlyGroupPhotosChangesNothing() {
        List<Map<String, Object>> groupOnly = search(doug).stream().filter(p -> p.get("id").equals(groupPhoto)).toList();
        var res = api.post("/api/attendee/removals", doug, removal("solo", groupOnly));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody()).containsEntry("code", "no_solo_photos");
        assertThat(count("SELECT count(*) FROM removal_requests WHERE event_id = ?::uuid", event)).isZero();
        assertThat(search(doug)).hasSize(2);
    }

    @Test
    void eraseCannotBeChosenWhileItIsSwitchedOff() {
        var res = api.post("/api/attendee/removals", doug, removal("erase", search(doug)));
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody()).containsEntry("code", "erase_unavailable");
        assertThat(search(doug)).hasSize(2);
    }

    @Test
    void unknownModeIsRejected() {
        var res = api.post("/api/attendee/removals", doug, removal("everything", search(doug)));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }
}
