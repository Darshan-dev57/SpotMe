package dev.darshan.spotme.event;

import dev.darshan.spotme.support.Api;
import dev.darshan.spotme.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The organizer photo list comes in pages, so a big event can be browsed to the end. */
class PhotoListIntegrationTest extends IntegrationTest {

    String org;
    String event;

    @BeforeEach
    void eventWithThreePhotos() {
        org = api.registerOrganizer("pages-" + System.nanoTime() + "@example.com");
        event = api.createEvent(org, "Pages");
        for (String name : List.of("behnken.jpg", "glover.jpg", "hurley.jpg")) {
            api.upload(event, org, name, Api.fixture(name));
        }
    }

    private List<Map<String, Object>> page(int offset, int limit) {
        return api.getList("/api/events/" + event + "/photos?offset=" + offset + "&limit=" + limit, org);
    }

    @Test
    void pagesTogetherHoldEveryPhotoOnce() {
        List<Object> ids = new ArrayList<>();
        page(0, 2).forEach(p -> ids.add(p.get("id")));
        page(2, 2).forEach(p -> ids.add(p.get("id")));
        assertThat(ids).hasSize(3).doesNotHaveDuplicates();
        assertThat(page(3, 2)).isEmpty();
    }

    @Test
    void withoutParametersTheFirstPageIsReturned() {
        assertThat(api.getList("/api/events/" + event + "/photos", org)).hasSize(3);
    }

    @Test
    void badPageIsRejected() {
        String path = "/api/events/" + event + "/photos?limit=";
        assertThat(api.get(path + "0", org).getStatusCode().value()).isEqualTo(400);
        assertThat(api.get(path + (EventController.MAX_PHOTO_PAGE + 1), org).getStatusCode().value()).isEqualTo(400);
        assertThat(api.get("/api/events/" + event + "/photos?offset=-1", org).getStatusCode().value()).isEqualTo(400);
    }
}
