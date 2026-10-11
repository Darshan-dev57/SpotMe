package dev.darshan.spotme.security;

import dev.darshan.spotme.common.FaceSearchLimiter;
import dev.darshan.spotme.support.Api;
import dev.darshan.spotme.support.IntegrationTest;
import dev.darshan.spotme.support.Models;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;

/** The access and privacy rules from docs/THREAT_MODEL.md, checked over real HTTP. */
class AccessRulesIntegrationTest extends IntegrationTest {

    @Autowired
    FaceSearchLimiter faceLimiter;

    @Test
    void anotherOrganizerGetsNotFoundOnEveryEndpointOfMyEvent() {
        String owner = api.registerOrganizer("owner-a@example.com");
        String stranger = api.registerOrganizer("owner-b@example.com");
        String event = api.createEvent(owner, "Owner A party");
        String photo = (String) api.upload(event, owner, "hurley.jpg", Api.fixture("hurley.jpg")).getBody().get("id");
        api.post("/api/events/" + event + "/invites", owner, Map.of("label", "Guest"));
        String membership = jdbc.queryForObject(
                "SELECT id::text FROM memberships WHERE event_id = ?::uuid", String.class, event);
        String base = "/api/events/" + event;
        String someId = UUID.randomUUID().toString();

        List<ResponseEntity<Map<String, Object>>> answers = List.of(
                api.get(base, stranger),
                api.get(base + "/progress", stranger),
                api.get(base + "/photos", stranger),
                api.get(base + "/invites", stranger),
                api.get(base + "/removals", stranger),
                api.get(base + "/drive-imports", stranger),
                api.post(base + "/notice", stranger, Map.of("purposeNotice", "changed by someone else")),
                api.post(base + "/publish", stranger, null),
                api.post(base + "/invites", stranger, Map.of("label", "Intruder")),
                api.post(base + "/photos/visibility", stranger, Map.of("photoIds", List.of(photo), "visibility", "public")),
                api.post(base + "/photos/" + photo + "/hide", stranger, null),
                api.post(base + "/photos/" + photo + "/unhide", stranger, null),
                api.post(base + "/photos/" + photo + "/retry", stranger, null),
                api.post(base + "/removals/" + someId + "/approve", stranger, null),
                api.post(base + "/removals/" + someId + "/reject", stranger, null),
                api.post(base + "/drive-imports", stranger, Map.of("link", "https://drive.google.com/drive/folders/abc123")),
                api.upload(event, stranger, "x.jpg", Api.fixture("behnken.jpg")),
                api.delete(base + "/photos/" + photo, stranger),
                api.delete(base + "/invites/" + membership, stranger),
                api.delete(base, stranger));

        // 404 and not 403, so another organizer can't even tell the event exists
        assertThat(answers).allSatisfy(res -> assertThat(res.getStatusCode().value()).isEqualTo(404));

        // and nothing of the owner's changed
        assertThat(count("SELECT count(*) FROM events WHERE id = ?::uuid AND status = 'draft'", event)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM photos WHERE event_id = ?::uuid", event)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM photos WHERE id = ?::uuid AND hidden_at IS NULL AND is_public = false", photo))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM memberships WHERE event_id = ?::uuid AND revoked_at IS NULL", event)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM events WHERE id = ?::uuid AND purpose_notice LIKE '%someone else%'", event))
                .isZero();
    }

    @Test
    void aRevokedInviteLinkCannotBeOpenedOrAcceptedAgain() {
        String org = api.registerOrganizer("revoke-link@example.com");
        String event = api.createEvent(org, "Revoke link");
        String invite = (String) api.post("/api/events/" + event + "/invites", org, Map.of("label", "Ex guest"))
                .getBody().get("token");
        assertThat(api.get("/api/join/" + invite, null).getStatusCode().value()).isEqualTo(200);
        String membership = jdbc.queryForObject(
                "SELECT id::text FROM memberships WHERE event_id = ?::uuid", String.class, event);

        api.delete("/api/events/" + event + "/invites/" + membership, org);

        var notice = api.get("/api/join/" + invite, null);
        assertThat(notice.getStatusCode().value()).isEqualTo(404);
        assertThat(notice.getBody()).containsEntry("code", "invite_invalid");
        var consent = api.post("/api/join/" + invite + "/consent", null, Map.of("noticeVersion", 1, "agree", true));
        assertThat(consent.getStatusCode().value()).isEqualTo(404);
        assertThat(consent.getBody()).doesNotContainKey("sessionToken");
    }

    @Test
    void theSelfieIsNeverStored() throws IOException {
        Models.assumePresent();
        String org = api.registerOrganizer("selfie-store@example.com");
        String event = api.createEvent(org, "Selfie store");
        api.upload(event, org, "crew-group.jpg", Api.fixture("crew-group.jpg"));
        drainJobs();
        api.post("/api/events/" + event + "/publish", org, null);
        String guest = api.joinAsAttendee(org, event, "Guest");

        List<String> filesBefore = storedFiles();
        long facesBefore = count("SELECT count(*) FROM faces");
        long photosBefore = count("SELECT count(*) FROM photos");
        long alertsBefore = count("SELECT count(*) FROM photo_alerts");
        long jobsBefore = count("SELECT count(*) FROM jobs");

        var res = api.search(guest, Api.fixture("hurley.jpg"));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat((List<?>) res.getBody().get("photos")).hasSize(1);

        // no new file anywhere in storage, and no row that could hold the selfie or its face vector
        assertThat(storedFiles()).isEqualTo(filesBefore);
        assertThat(count("SELECT count(*) FROM faces")).isEqualTo(facesBefore);
        assertThat(count("SELECT count(*) FROM photos")).isEqualTo(photosBefore);
        assertThat(count("SELECT count(*) FROM photo_alerts")).isEqualTo(alertsBefore);
        assertThat(count("SELECT count(*) FROM jobs")).isEqualTo(jobsBefore);
    }

    @Test
    void searchAndAlertsShareOneRateLimit() {
        String org = api.registerOrganizer("limit-share@example.com");
        String event = api.createEvent(org, "Limit share");
        api.post("/api/events/" + event + "/publish", org, null);
        String guest = api.joinAsAttendee(org, event, "Guest");
        UUID membership = jdbc.queryForObject(
                "SELECT id FROM memberships WHERE event_id = ?::uuid", UUID.class, event);

        // spend all but one of this minute's face lookups (the test limit is 1000)
        for (int i = 0; i < 999; i++) {
            assertThat(faceLimiter.tryAcquire(membership)).isTrue();
        }
        // the last one goes on the alert opt-in (whatever it answers, it is not refused for rate)...
        var alert = api.postImage("/api/attendee/alerts", guest, Api.fixture("hurley.jpg"));
        assertThat(alert.getStatusCode().value()).isNotEqualTo(429);
        // ...so search, the other way in, is now refused too
        var search = api.search(guest, Api.fixture("hurley.jpg"));
        assertThat(search.getStatusCode().value()).isEqualTo(429);
        assertThat(search.getBody()).containsEntry("code", "slow_down");
        assertThat(api.postImage("/api/attendee/alerts", guest, Api.fixture("hurley.jpg")).getStatusCode().value())
                .isEqualTo(429);
        // the raw-distance endpoint used for evaluation is switched off outside the eval profile
        assertThat(api.postImage("/api/events/" + event + "/eval-search", org, Api.fixture("hurley.jpg"))
                .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void oversizedAndFakeImagesAreRefusedAndNothingIsStored() {
        String org = api.registerOrganizer("bad-images@example.com");
        String event = api.createEvent(org, "Bad images");
        List<String> filesBefore = storedFiles();

        // just over the 20 MB photo cap (a much bigger body is cut off by Tomcat before it is read)
        var tooBig = api.upload(event, org, "huge.jpg", new byte[20_100_000]);
        assertThat(tooBig.getStatusCode().value()).isEqualTo(413);
        assertThat(tooBig.getBody()).containsEntry("code", "file_too_large");

        // a tiny PNG whose header claims 10000 x 10000 pixels: refused before any decoding
        var bomb = api.upload(event, org, "bomb.png", pngHeaderOnly(10_000, 10_000));
        assertThat(bomb.getStatusCode().value()).isEqualTo(413);
        assertThat(bomb.getBody()).containsEntry("code", "too_many_pixels");

        // a web page renamed to .jpg is not an image whatever its name says
        var html = api.upload(event, org, "photo.jpg", "<html><script>alert(1)</script></html>".getBytes());
        assertThat(html.getStatusCode().value()).isEqualTo(422);
        assertThat(html.getBody()).containsEntry("code", "unsupported_type");

        assertThat(List.of(tooBig, bomb, html)).allSatisfy(res -> {
            assertThat(res.getBody()).containsOnlyKeys("code", "message");
            assertThat(res.getBody().toString()).doesNotContain("Exception", "at dev.darshan");
        });
        assertThat(count("SELECT count(*) FROM photos WHERE event_id = ?::uuid", event)).isZero();
        assertThat(storedFiles()).isEqualTo(filesBefore);

        // the selfie body has the same size cap
        api.post("/api/events/" + event + "/publish", org, null);
        String guest = api.joinAsAttendee(org, event, "Guest");
        var bigSelfie = api.search(guest, new byte[20_000_001]);
        assertThat(bigSelfie.getStatusCode().value()).isEqualTo(413);
        assertThat(bigSelfie.getBody()).containsEntry("code", "file_too_large");
    }

    private List<String> storedFiles() {
        try (Stream<Path> files = Files.walk(storageDir())) {
            return files.filter(Files::isRegularFile).map(p -> storageDir().relativize(p).toString()).sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** PNG signature, a real IHDR chunk with the given size, and IEND. No pixel data at all. */
    private static byte[] pngHeaderOnly(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height)
                .put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        chunk(out, "IHDR", ihdr.array());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        CRC32 crc = new CRC32();
        crc.update(type.getBytes());
        crc.update(data);
        out.writeBytes(type.getBytes());
        out.writeBytes(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
