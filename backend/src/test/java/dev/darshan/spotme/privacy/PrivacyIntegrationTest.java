package dev.darshan.spotme.privacy;

import dev.darshan.spotme.jobs.Housekeeping;
import dev.darshan.spotme.jobs.IndexPhotoHandler;
import dev.darshan.spotme.jobs.Job;
import dev.darshan.spotme.support.Api;
import dev.darshan.spotme.support.IntegrationTest;
import dev.darshan.spotme.support.Models;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PrivacyIntegrationTest extends IntegrationTest {

    @Autowired
    Housekeeping housekeeping;

    @Autowired
    IndexPhotoHandler indexer;

    @BeforeEach
    void needsModels() {
        Models.assumePresent();
    }

    @Test
    void removalHidesAtOnceAndApprovalPurgesFilesVectorsAndBlocksReupload() throws Exception {
        String org = api.registerOrganizer("removal@example.com");
        String event = api.createEvent(org, "Removal");
        String photoId = (String) api.upload(event, org, "hurley.jpg", Api.fixture("hurley.jpg")).getBody().get("id");
        drainJobs();
        api.post("/api/events/" + event + "/publish", org, null);
        String doug = api.joinAsAttendee(org, event, "Doug");
        assertThat(found(doug)).containsExactly(photoId);

        var req = api.post("/api/attendee/removals", doug, Api.removal(photoId, "please remove"));
        assertThat(req.getStatusCode().value()).isEqualTo(201);
        // hidden immediately, before anyone approves
        assertThat(found(doug)).isEmpty();

        String requestId = (String) req.getBody().get("id");
        String[] keys = jdbc.queryForObject("SELECT object_key || ' ' || thumb_key FROM photos WHERE id = ?::uuid",
                String.class, photoId).split(" ");
        api.post("/api/events/" + event + "/removals/" + requestId + "/approve", org, null);
        drainJobs();

        for (String key : keys) {
            assertThat(Files.exists(storageDir().resolve(key))).as(key).isFalse();
        }
        assertThat(count("SELECT count(*) FROM faces WHERE photo_id = ?::uuid", photoId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM photos WHERE id = ?::uuid", String.class, photoId)).isEqualTo("purged");
        assertThat(jdbc.queryForObject("SELECT status FROM removal_requests WHERE id = ?::uuid", String.class, requestId))
                .isEqualTo("completed");

        var again = api.upload(event, org, "hurley.jpg", Api.fixture("hurley.jpg"));
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody()).containsEntry("code", "removed_photo");
    }

    @Test
    void rejectedRemovalBringsThePhotoBack() {
        String org = api.registerOrganizer("reject@example.com");
        String event = api.createEvent(org, "Reject");
        String photoId = (String) api.upload(event, org, "hurley.jpg", Api.fixture("hurley.jpg")).getBody().get("id");
        drainJobs();
        api.post("/api/events/" + event + "/publish", org, null);
        String doug = api.joinAsAttendee(org, event, "Doug");

        String requestId = (String) api.post("/api/attendee/removals", doug, Api.removal(photoId, null)).getBody().get("id");
        assertThat(found(doug)).isEmpty();
        api.post("/api/events/" + event + "/removals/" + requestId + "/reject", org, null);
        assertThat(found(doug)).containsExactly(photoId);
    }

    @Test
    void expiredEventIsPurgedCompletely() throws Exception {
        String org = api.registerOrganizer("expiry@example.com");
        String event = api.createEvent(org, "Expiry");
        api.upload(event, org, "crew-group.jpg", Api.fixture("crew-group.jpg"));
        api.upload(event, org, "glover.jpg", Api.fixture("glover.jpg"));
        drainJobs();
        api.post("/api/events/" + event + "/publish", org, null);
        var invite = api.post("/api/events/" + event + "/invites", org, Map.of("label", "Vic")).getBody();
        assertThat(Files.list(storageDir().resolve("events/" + event)).count()).isEqualTo(4);

        jdbc.update("UPDATE events SET expires_at = now() - interval '1 second' WHERE id = ?::uuid", event);
        housekeeping.expireEvents();
        drainJobs();

        assertThat(jdbc.queryForObject("SELECT status FROM events WHERE id = ?::uuid", String.class, event)).isEqualTo("purged");
        assertThat(Files.exists(storageDir().resolve("events/" + event))
                && Files.list(storageDir().resolve("events/" + event)).findAny().isPresent()).isFalse();
        assertThat(count("SELECT count(*) FROM faces WHERE event_id = ?::uuid", event)).isZero();
        assertThat(count("SELECT count(*) FROM photos WHERE event_id = ?::uuid", event)).isZero();
        assertThat(count("SELECT count(*) FROM memberships WHERE event_id = ?::uuid", event)).isZero();
        assertThat(api.get("/api/join/" + invite.get("token"), null).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void indexingThatFinishesAfterSuppressionIsThrownAway() {
        String org = api.registerOrganizer("race@example.com");
        String event = api.createEvent(org, "Race");
        String photoId = (String) api.upload(event, org, "crew-group.jpg", Api.fixture("crew-group.jpg")).getBody().get("id");

        // the worker has claimed the job and is "busy" with inference...
        Job job = queue.claim("slow-worker", 1, Duration.ofMinutes(1)).get(0);
        // ...when the event is deleted
        api.delete("/api/events/" + event, org);
        assertThat(worker.runOne(job)).isEqualTo("skipped");
        assertThat(count("SELECT count(*) FROM faces WHERE photo_id = ?::uuid", photoId)).isZero();
    }

    @Test
    void lateCommitIsDiscardedWhenTheDeletionEpochMoved() throws Exception {
        String org = api.registerOrganizer("epoch@example.com");
        String event = api.createEvent(org, "Epoch");
        String photoId = (String) api.upload(event, org, "behnken.jpg", Api.fixture("behnken.jpg")).getBody().get("id");
        Job job = queue.claim("slow-worker", 1, Duration.ofMinutes(1)).get(0);

        IndexPhotoHandler.Inference inference = indexer.infer(job);
        assertThat(inference.faces()).hasSize(1);
        // while inference was running, a deletion started (and was undone): only the epoch moved
        jdbc.update("UPDATE events SET deletion_epoch = deletion_epoch + 1 WHERE id = ?::uuid", event);

        assertThat(indexer.save(job, inference)).isEqualTo("discarded");
        assertThat(count("SELECT count(*) FROM faces WHERE photo_id = ?::uuid", photoId)).isZero();
    }

    @Test
    void orphanFilesAreRemovedButReferencedOnesStay() throws Exception {
        String org = api.registerOrganizer("orphan@example.com");
        String event = api.createEvent(org, "Orphans");
        api.upload(event, org, "hurley.jpg", Api.fixture("hurley.jpg"));
        Files.write(storageDir().resolve("events/" + event + "/left-behind.jpg"), new byte[]{1, 2, 3});

        int removed = housekeeping.removeOrphanFiles(Instant.now().plus(Duration.ofSeconds(5)));

        assertThat(removed).isGreaterThanOrEqualTo(1);
        assertThat(Files.exists(storageDir().resolve("events/" + event + "/left-behind.jpg"))).isFalse();
        assertThat(Files.list(storageDir().resolve("events/" + event)).count()).isEqualTo(2);
    }

    @SuppressWarnings("unchecked")
    private List<String> found(String attendeeToken) {
        var res = api.search(attendeeToken, Api.fixture("hurley.jpg"));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return ((List<Map<String, Object>>) res.getBody().get("photos")).stream().map(p -> (String) p.get("id")).toList();
    }
}
