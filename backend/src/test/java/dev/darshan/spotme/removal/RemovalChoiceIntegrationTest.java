package dev.darshan.spotme.removal;

import dev.darshan.spotme.support.Api;
import dev.darshan.spotme.support.IntegrationTest;
import dev.darshan.spotme.support.Models;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A removal request takes only the photos where the guest is alone. */
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

    private Map<String, Object> removal(List<Map<String, Object>> found) {
        return Map.of("photoIds", found.stream().map(p -> p.get("id")).toList());
    }

    @Test
    void searchSaysWhichFaceMatchedAndWhetherTheGuestIsAlone() {
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
        var res = api.post("/api/attendee/removals", doug, removal(search(doug)));
        assertThat(res.getStatusCode().value()).isEqualTo(201);
        assertThat(res.getBody()).containsEntry("hidden", 1).containsEntry("keptGroupPhotos", 1);

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
        var res = api.post("/api/attendee/removals", doug, removal(groupOnly));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody()).containsEntry("code", "no_solo_photos");
        assertThat(count("SELECT count(*) FROM removal_requests WHERE event_id = ?::uuid", event)).isZero();
        assertThat(search(doug)).hasSize(2);
    }

    @Test
    void rejectingBringsTheSoloPhotoBack() {
        var res = api.post("/api/attendee/removals", doug, removal(search(doug)));
        api.post("/api/events/" + event + "/removals/" + res.getBody().get("id") + "/reject", org, null);
        assertThat(search(doug)).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT status FROM removal_requests WHERE id = ?::uuid",
                String.class, res.getBody().get("id"))).isEqualTo("rejected");
    }
}
