package dev.darshan.spotme.media;

import dev.darshan.spotme.support.Api;
import dev.darshan.spotme.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "spotme.upload.max-photos-per-event=2")
class EventPhotoLimitIntegrationTest extends IntegrationTest {

    @Test
    void anEventStopsTakingPhotosAtTheLimit() {
        String org = api.registerOrganizer("photo-limit@example.com");
        String event = api.createEvent(org, "Limit");
        assertThat(api.upload(event, org, "1.jpg", Api.fixture("hurley.jpg")).getStatusCode().value()).isEqualTo(201);
        assertThat(api.upload(event, org, "2.jpg", Api.fixture("glover.jpg")).getStatusCode().value()).isEqualTo(201);

        var third = api.upload(event, org, "3.jpg", Api.fixture("behnken.jpg"));
        assertThat(third.getStatusCode().value()).isEqualTo(409);
        assertThat(third.getBody()).containsEntry("code", "event_full");

        // a duplicate of a photo already in the event is still answered, not refused
        assertThat(api.upload(event, org, "again.jpg", Api.fixture("hurley.jpg")).getBody()).containsEntry("status", "duplicate");
        assertThat(count("SELECT count(*) FROM photos WHERE event_id = ?::uuid", event)).isEqualTo(2);
    }
}
