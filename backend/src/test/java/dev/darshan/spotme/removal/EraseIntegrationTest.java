package dev.darshan.spotme.removal;

import dev.darshan.spotme.support.Api;
import dev.darshan.spotme.support.IntegrationTest;
import dev.darshan.spotme.support.Models;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Erase me completely" with erasing switched on. A stand-in eraser paints a grey box over the face,
 * which is enough to check the job, the swap of the files and what the guests see.
 */
@TestPropertySource(properties = "spotme.erase.enabled=true")
class EraseIntegrationTest extends IntegrationTest {

    @TestConfiguration
    static class GreyBoxEraser {
        @Bean
        @Primary
        PersonEraser greyBox() {
            return (jpeg, face) -> {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
                Graphics2D g = img.createGraphics();
                g.setColor(Color.GRAY);
                g.fillRect(face.x() - face.w() / 2, face.y() - face.h() / 2, face.w() * 2, face.h() * 3);
                g.dispose();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(img, "jpg", out);
                return out.toByteArray();
            };
        }
    }

    String org;
    String event;
    String groupPhoto;
    String soloPhoto;
    String doug;

    @BeforeEach
    void eventWithAGroupPhotoAndASoloPhoto() {
        Models.assumePresent();
        org = api.registerOrganizer("erase-" + System.nanoTime() + "@example.com");
        event = api.createEvent(org, "Erase");
        groupPhoto = (String) api.upload(event, org, "crew-group.jpg", Api.fixture("crew-group.jpg")).getBody().get("id");
        soloPhoto = (String) api.upload(event, org, "hurley.jpg", Api.fixture("hurley.jpg")).getBody().get("id");
        drainJobs();
        api.post("/api/events/" + event + "/publish", org, null);
        doug = api.joinAsAttendee(org, event, "Doug");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> search(String token, String selfie) {
        return (List<Map<String, Object>>) api.search(token, Api.fixture(selfie)).getBody().get("photos");
    }

    private Map<String, Object> eraseAll(List<Map<String, Object>> found) {
        List<Map<String, Object>> photos = found.stream()
                .map(p -> Map.<String, Object>of("photoId", p.get("id"), "faceId", p.get("faceId"))).toList();
        return Map.of("mode", "erase", "photos", photos);
    }

    @Test
    void groupPhotoStaysHiddenUntilTheEditedCopyReplacesIt() throws Exception {
        String bob = api.joinAsAttendee(org, event, "Bob");
        assertThat(search(bob, "behnken.jpg")).extracting(p -> p.get("id")).containsExactly(groupPhoto);

        var res = api.post("/api/attendee/removals", doug, eraseAll(search(doug, "hurley.jpg")));
        assertThat(res.getStatusCode().value()).isEqualTo(201);
        assertThat(res.getBody()).containsEntry("hidden", 1).containsEntry("erasing", 1);
        String requestId = (String) res.getBody().get("id");

        // nobody sees the original group photo while the request is open
        assertThat(search(bob, "behnken.jpg")).isEmpty();
        Map<String, Object> request = api.getList("/api/events/" + event + "/removals", org).get(0);
        assertThat(request).containsEntry("mode", "erase");
        assertThat((List<?>) request.get("edits")).hasSize(1);

        String oldKey = jdbc.queryForObject("SELECT object_key FROM photos WHERE id = ?::uuid", String.class, groupPhoto);
        api.post("/api/events/" + event + "/removals/" + requestId + "/approve", org, null);
        drainJobs();

        // the edited copy is in place, the original file is gone and so is Doug's face vector
        String newKey = jdbc.queryForObject("SELECT object_key FROM photos WHERE id = ?::uuid", String.class, groupPhoto);
        assertThat(newKey).isNotEqualTo(oldKey);
        assertThat(Files.exists(storageDir().resolve(oldKey))).isFalse();
        assertThat(Files.exists(storageDir().resolve(newKey))).isTrue();
        assertThat(jdbc.queryForObject("SELECT face_count FROM photos WHERE id = ?::uuid", Integer.class, groupPhoto)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM faces WHERE photo_id = ?::uuid", groupPhoto)).isEqualTo(3);

        // the group photo is back for the others, and Doug's selfie finds nothing any more
        assertThat(search(bob, "behnken.jpg")).extracting(p -> p.get("id")).containsExactly(groupPhoto);
        assertThat(search(doug, "hurley.jpg")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM removal_requests WHERE id = ?::uuid", String.class, requestId))
                .isEqualTo("completed");
        assertThat(jdbc.queryForObject("SELECT status FROM photo_edits WHERE request_id = ?::uuid", String.class, requestId))
                .isEqualTo("done");
    }

    @Test
    void rejectingAnEraseRequestBringsTheOriginalBack() {
        String oldKey = jdbc.queryForObject("SELECT object_key FROM photos WHERE id = ?::uuid", String.class, groupPhoto);
        var res = api.post("/api/attendee/removals", doug, eraseAll(search(doug, "hurley.jpg")));
        api.post("/api/events/" + event + "/removals/" + res.getBody().get("id") + "/reject", org, null);
        drainJobs();

        assertThat(search(doug, "hurley.jpg")).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT object_key FROM photos WHERE id = ?::uuid", String.class, groupPhoto)).isEqualTo(oldKey);
        assertThat(jdbc.queryForObject("SELECT status FROM photo_edits WHERE request_id = ?::uuid", String.class,
                res.getBody().get("id"))).isEqualTo("cancelled");
    }

    @Test
    void faceFromAnotherPhotoIsRefusedAndNothingIsHidden() {
        Map<String, Object> solo = search(doug, "hurley.jpg").stream().filter(p -> p.get("id").equals(soloPhoto)).findFirst().orElseThrow();
        var body = Map.of("mode", "erase", "photos", List.of(
                Map.of("photoId", groupPhoto, "faceId", solo.get("faceId"))));
        var res = api.post("/api/attendee/removals", doug, body);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody()).containsEntry("code", "bad_face");
        assertThat(search(doug, "hurley.jpg")).hasSize(2);
        assertThat(count("SELECT count(*) FROM removal_requests WHERE event_id = ?::uuid", event)).isZero();
    }
}
