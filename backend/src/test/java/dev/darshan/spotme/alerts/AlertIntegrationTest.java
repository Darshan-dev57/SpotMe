package dev.darshan.spotme.alerts;

import dev.darshan.spotme.jobs.Housekeeping;
import dev.darshan.spotme.support.Api;
import dev.darshan.spotme.support.IntegrationTest;
import dev.darshan.spotme.support.Models;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** "Tell me about new photos": what the private link shows, and when the kept face vector is deleted. */
class AlertIntegrationTest extends IntegrationTest {

    @Autowired
    Housekeeping housekeeping;

    String org;
    String event;
    String doug;

    @BeforeEach
    void publishedEventWithOneGroupPhoto() {
        Models.assumePresent();
        org = api.registerOrganizer("alerts-" + System.nanoTime() + "@example.com");
        event = api.createEvent(org, "Alerts");
        api.upload(event, org, "crew-group.jpg", Api.fixture("crew-group.jpg"));
        drainJobs();
        api.post("/api/events/" + event + "/publish", org, null);
        doug = api.joinAsAttendee(org, event, "Doug");
    }

    private String optIn(String attendee) {
        var res = api.postImage("/api/attendee/alerts", attendee, Api.fixture("hurley.jpg"));
        assertThat(res.getStatusCode().value()).isEqualTo(201);
        assertThat((String) res.getBody().get("url")).contains("/alerts#");
        return (String) res.getBody().get("token");
    }

    private Map<String, Object> check(String token) {
        return api.get("/api/alerts/" + token, null).getBody();
    }

    private long alerts() {
        return count("SELECT count(*) FROM photo_alerts WHERE event_id = ?::uuid", event);
    }

    @Test
    void linkShowsOnlyNewPhotosOfTheGuest() {
        String token = optIn(doug);
        // the group photo was already in the results Doug just saw
        assertThat(check(token)).containsEntry("newCount", 0);

        String solo = (String) api.upload(event, org, "hurley.jpg", Api.fixture("hurley.jpg")).getBody().get("id");
        api.upload(event, org, "glover.jpg", Api.fixture("glover.jpg"));
        drainJobs();

        Map<String, Object> result = check(token);
        assertThat(result).containsEntry("newCount", 1).containsEntry("eventTitle", "Alerts");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> photos = (List<Map<String, Object>>) result.get("photos");
        assertThat(photos.get(0)).containsEntry("id", solo);
        assertThat(api.getBytes((String) photos.get(0).get("thumbUrl")).getStatusCode().value()).isEqualTo(200);

        api.post("/api/alerts/" + token + "/seen", null, Map.of("until", result.get("checkedAt")));
        assertThat(check(token)).containsEntry("newCount", 0);
    }

    @Test
    void turningAlertsOffDeletesTheFaceVector() {
        String token = optIn(doug);
        assertThat(alerts()).isEqualTo(1);
        assertThat(api.delete("/api/alerts/" + token, null).getStatusCode().value()).isEqualTo(200);
        assertThat(alerts()).isZero();
        assertThat(api.get("/api/alerts/" + token, null).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void aNewLinkReplacesTheOldOne() {
        String first = optIn(doug);
        String second = optIn(doug);
        assertThat(alerts()).isEqualTo(1);
        assertThat(api.get("/api/alerts/" + first, null).getStatusCode().value()).isEqualTo(404);
        assertThat(check(second)).containsEntry("newCount", 0);
    }

    @Test
    void withdrawingDeletesTheAlert() {
        String token = optIn(doug);
        api.post("/api/attendee/withdraw", doug, null);
        assertThat(alerts()).isZero();
        assertThat(api.get("/api/alerts/" + token, null).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void revokingTheGuestDeletesTheAlert() {
        String token = optIn(doug);
        String membership = jdbc.queryForObject(
                "SELECT membership_id::text FROM photo_alerts WHERE event_id = ?::uuid", String.class, event);
        api.delete("/api/events/" + event + "/invites/" + membership, org);
        assertThat(alerts()).isZero();
        assertThat(api.get("/api/alerts/" + token, null).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void changingTheNoticeDeletesAlerts() {
        optIn(doug);
        api.post("/api/events/" + event + "/notice", org, Map.of("purposeNotice", "A new purpose."));
        assertThat(alerts()).isZero();
    }

    @Test
    void expiredEventTakesItsAlertsWithIt() {
        optIn(doug);
        jdbc.update("UPDATE events SET expires_at = now() - interval '1 second' WHERE id = ?::uuid", event);
        housekeeping.expireEvents();
        drainJobs();
        assertThat(alerts()).isZero();
    }

    @Test
    void optInNeedsAClearSelfie() {
        var res = api.postImage("/api/attendee/alerts", doug, Api.fixture("crew-group.jpg"));
        assertThat(res.getStatusCode().value()).isEqualTo(422);
        assertThat(res.getBody()).containsEntry("code", "multiple_faces");
        assertThat(alerts()).isZero();
    }
}
