package dev.darshan.spotme.jobs;

import dev.darshan.spotme.support.Api;
import dev.darshan.spotme.support.IntegrationTest;
import dev.darshan.spotme.support.Models;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A worker dies in the middle of a batch. Its jobs must come back after the lease,
 * every photo must end up indexed exactly once, and the dead worker must not be able
 * to write a late result over the new one.
 */
class CrashRecoveryIntegrationTest extends IntegrationTest {

    @Autowired
    IndexPhotoHandler handler;

    @BeforeEach
    void needsModels() {
        Models.assumePresent();
    }

    @Test
    void killedWorkersJobsAreRecoveredWithOneOutputPerPhoto() throws Exception {
        String org = api.registerOrganizer("crash@example.com");
        String event = api.createEvent(org, "Crash test");
        String[] fixtures = {"crew-group.jpg", "hurley.jpg", "behnken.jpg", "glover.jpg"};
        for (String f : fixtures) {
            api.upload(event, org, f, Api.fixture(f));
        }

        // worker A claims two jobs with a short lease, then "dies" (never finishes them)
        List<Job> lost = queue.claim("worker-A", 2, Duration.ofMillis(300));
        assertThat(lost).hasSize(2);

        // worker B processes what it can see right now: the other two
        assertThat(drainJobs()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM photos WHERE event_id = ?::uuid AND status = 'ready'", event)).isEqualTo(2);

        // A's lease runs out, the reaper puts its jobs back, B finishes them
        Thread.sleep(500);
        assertThat(queue.reapExpiredLeases()).isEqualTo(2);
        assertThat(drainJobs()).isEqualTo(2);

        // A wakes up and tries to finish one of its old jobs: rejected, nothing written twice
        assertThat(worker.runOne(lost.get(0))).isEqualTo("stale");

        assertThat(count("SELECT count(*) FROM photos WHERE event_id = ?::uuid AND status = 'ready'", event)).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM faces WHERE event_id = ?::uuid", event)).isEqualTo(7); // 4 + 1 + 1 + 1
        assertThat(count("""
                SELECT count(*) FROM (SELECT photo_id, face_index FROM faces WHERE event_id = ?::uuid
                                      GROUP BY photo_id, face_index HAVING count(*) > 1) d
                """, event)).isZero();
        assertThat(count("SELECT count(*) FROM jobs WHERE state = 'succeeded'")).isEqualTo(4);
        assertThat(count("SELECT max(attempts) FROM jobs")).isEqualTo(2);
    }

    @Test
    void reRunningAFinishedPhotoReplacesItsFacesInsteadOfAddingMore() {
        String org = api.registerOrganizer("rerun@example.com");
        String event = api.createEvent(org, "Re-run");
        String photoId = (String) api.upload(event, org, "crew-group.jpg", Api.fixture("crew-group.jpg")).getBody().get("id");
        drainJobs();

        // at-least-once delivery: the same photo is processed again
        jdbc.update("UPDATE photos SET status = 'queued' WHERE id = ?::uuid", photoId);
        queue.requeue("index:" + photoId);
        drainJobs();

        assertThat(count("SELECT count(*) FROM faces WHERE photo_id = ?::uuid", photoId)).isEqualTo(4);
    }

    @Test
    void brokenFileFailsAloneAndTheRestOfTheBatchFinishes() throws Exception {
        String org = api.registerOrganizer("broken@example.com");
        String event = api.createEvent(org, "Broken file");
        String good = (String) api.upload(event, org, "hurley.jpg", Api.fixture("hurley.jpg")).getBody().get("id");
        String bad = (String) api.upload(event, org, "glover.jpg", Api.fixture("glover.jpg")).getBody().get("id");
        // the stored file gets corrupted on disk after upload
        String key = jdbc.queryForObject("SELECT object_key FROM photos WHERE id = ?::uuid", String.class, bad);
        java.nio.file.Files.write(storageDir().resolve(key), new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3});

        drainJobs();

        assertThat(jdbc.queryForObject("SELECT status FROM photos WHERE id = ?::uuid", String.class, good)).isEqualTo("ready");
        assertThat(jdbc.queryForObject("SELECT status || ':' || error_code FROM photos WHERE id = ?::uuid", String.class, bad))
                .isEqualTo("failed:corrupt_image");
        assertThat(jdbc.queryForObject("SELECT state || ':' || attempts FROM jobs WHERE target_id = ?::uuid", String.class, bad))
                .isEqualTo("failed:1");
    }
}
