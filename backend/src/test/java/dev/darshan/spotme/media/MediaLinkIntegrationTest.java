package dev.darshan.spotme.media;

import dev.darshan.spotme.support.Api;
import dev.darshan.spotme.support.IntegrationTest;
import dev.darshan.spotme.support.Models;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Signed photo links: they expire, and a valid signature never opens a photo of another event. */
class MediaLinkIntegrationTest extends IntegrationTest {

    @Autowired
    MediaSigner signer;

    UUID photoB;
    UUID guestA;
    UUID guestB;

    @BeforeEach
    void twoPublishedEvents() {
        Models.assumePresent();
        String org = api.registerOrganizer("links-" + UUID.randomUUID() + "@example.com");
        String eventA = api.createEvent(org, "Event A");
        String eventB = api.createEvent(org, "Event B");
        api.upload(eventA, org, "behnken.jpg", Api.fixture("behnken.jpg"));
        photoB = UUID.fromString((String) api.upload(eventB, org, "hurley.jpg", Api.fixture("hurley.jpg")).getBody().get("id"));
        drainJobs();
        api.post("/api/events/" + eventA + "/publish", org, null);
        api.post("/api/events/" + eventB + "/publish", org, null);
        api.joinAsAttendee(org, eventA, "Guest A");
        api.joinAsAttendee(org, eventB, "Guest B");
        guestA = jdbc.queryForObject("SELECT id FROM memberships WHERE event_id = ?::uuid", UUID.class, eventA);
        guestB = jdbc.queryForObject("SELECT id FROM memberships WHERE event_id = ?::uuid", UUID.class, eventB);
    }

    @Test
    void anExpiredLinkStopsWorking() {
        long past = Instant.now().getEpochSecond() - 1;
        String expired = "/api/media/" + photoB + "/thumb?sub=m:" + guestB + "&exp=" + past
                + "&sig=" + signer.sign(photoB, "thumb", "m:" + guestB, past);
        var res = api.getBytes(expired);
        assertThat(res.getStatusCode().value()).isEqualTo(403);

        // the same photo with a fresh link opens, so it was the expiry that closed it
        assertThat(api.getBytes(signer.link(photoB, "thumb", "m:" + guestB)).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void aGuestOfOneEventCannotOpenAPhotoOfAnotherEventEvenWithAValidSignature() {
        // the server's own signature, made for guest A: still refused, because access is checked live
        String link = signer.link(photoB, "original", "m:" + guestA);
        assertThat(api.getBytes(link).getStatusCode().value()).isEqualTo(403);
        assertThat(api.getBytes(signer.link(photoB, "thumb", "m:" + guestA)).getStatusCode().value()).isEqualTo(403);

        // and an organizer subject that isn't the owner gets nothing either
        assertThat(api.getBytes(signer.link(photoB, "thumb", "o:" + UUID.randomUUID())).getStatusCode().value())
                .isEqualTo(403);
    }
}
