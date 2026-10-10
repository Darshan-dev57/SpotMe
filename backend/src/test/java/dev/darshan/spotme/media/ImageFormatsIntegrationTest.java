package dev.darshan.spotme.media;

import dev.darshan.spotme.support.Api;
import dev.darshan.spotme.support.IntegrationTest;
import dev.darshan.spotme.support.Models;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The group photo uploaded as WebP, TIFF, BMP and GIF is stored as JPEG and found by a selfie. */
class ImageFormatsIntegrationTest extends IntegrationTest {

    @Test
    void everyServerSideFormatIsIndexedAndFound() throws Exception {
        Models.assumePresent();
        String org = api.registerOrganizer("formats@example.com");
        String event = api.createEvent(org, "Formats");
        byte[] group = Api.fixture("crew-group.jpg");

        Map<String, String> ids = new java.util.LinkedHashMap<>();
        for (String format : new String[]{"webp", "tiff", "bmp", "gif"}) {
            var res = api.upload(event, org, "crew." + format, FormatSamples.convert(group, format));
            assertThat(res.getStatusCode().value()).as(format).isEqualTo(201);
            ids.put(format, (String) res.getBody().get("id"));
        }
        assertThat(drainJobs()).isEqualTo(4);

        for (var entry : ids.entrySet()) {
            String key = jdbc.queryForObject("SELECT object_key FROM photos WHERE id = ?::uuid", String.class, entry.getValue());
            assertThat(key).endsWith(".jpg");
            assertThat(jdbc.queryForObject("SELECT face_count FROM photos WHERE id = ?::uuid", Integer.class, entry.getValue()))
                    .as(entry.getKey()).isEqualTo(4);
        }

        api.post("/api/events/" + event + "/publish", org, null);
        String attendee = api.joinAsAttendee(org, event, "Bob");
        var res = api.search(attendee, FormatSamples.convert(Api.fixture("behnken.jpg"), "webp"));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> photos = (List<Map<String, Object>>) res.getBody().get("photos");
        assertThat(photos).extracting(p -> p.get("id")).containsExactlyInAnyOrderElementsOf(ids.values());
    }

    @Test
    void heicSentStraightToTheApiIsRefusedWithAHint() {
        String org = api.registerOrganizer("heic@example.com");
        String event = api.createEvent(org, "Heic");
        var res = api.upload(event, org, "IMG_0001.HEIC", FormatSamples.ftyp("heic", "mif1", "heic"));
        assertThat(res.getStatusCode().value()).isEqualTo(422);
        assertThat(res.getBody()).containsEntry("code", "unsupported_type");
        assertThat((String) res.getBody().get("message")).contains("HEIC");
    }
}
