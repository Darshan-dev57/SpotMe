package dev.darshan.spotme.drive;

import dev.darshan.spotme.media.FormatSamples;
import dev.darshan.spotme.support.Api;
import dev.darshan.spotme.support.IntegrationTest;
import dev.darshan.spotme.support.Models;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Drive import through the real API, job queue and photo intake. Only the calls to Google are replaced
 * by a fake folder, so the test runs offline.
 */
class DriveImportIntegrationTest extends IntegrationTest {

    private static final String LINK = "https://drive.google.com/drive/folders/rootFolder0001?usp=sharing";

    @MockitoBean
    DriveClient drive;

    @BeforeEach
    void fakeFolder() throws Exception {
        // root: a photo, a PDF and a subfolder
        when(drive.listFolder("rootFolder0001")).thenReturn(List.of(
                new DriveClient.Entry("crewPhoto001", "crew.jpg", false, "image/jpeg"),
                new DriveClient.Entry("notesPdf0001", "notes.pdf", false, "application/pdf"),
                new DriveClient.Entry("subFolder0001", "Day 2", true, null)));
        // subfolder: a portrait, a photo too big to download, an iPhone photo, the same crew photo again, a broken file
        when(drive.listFolder("subFolder0001")).thenReturn(List.of(
                new DriveClient.Entry("hurley000001", "hurley.jpg", false, "image/jpeg"),
                new DriveClient.Entry("bigPhoto0001", "big.jpg", false, "image/jpeg"),
                new DriveClient.Entry("iphone000001", "IMG_0001.HEIC", false, "image/heic"),
                new DriveClient.Entry("crewCopy0001", "crew copy.jpg", false, "image/jpeg"),
                new DriveClient.Entry("broken000001", "broken.jpg", false, "image/jpeg"),
                new DriveClient.Entry("clipVideo001", "clip.mp4", false, "video/mp4")));
        byte[] crew = Api.fixture("crew-group.jpg");
        when(drive.downloadOriginal(eq("crewPhoto001"), anyLong())).thenReturn(crew);
        when(drive.downloadOriginal(eq("crewCopy0001"), anyLong())).thenReturn(crew);
        when(drive.downloadOriginal(eq("hurley000001"), anyLong())).thenReturn(Api.fixture("hurley.jpg"));
        when(drive.downloadOriginal(eq("bigPhoto0001"), anyLong())).thenReturn(null); // over the size limit
        when(drive.downloadResized(eq("bigPhoto0001"), anyInt(), anyLong())).thenReturn(Api.fixture("glover.jpg"));
        when(drive.downloadResized(eq("iphone000001"), anyInt(), anyLong()))
                .thenReturn(FormatSamples.convert(Api.fixture("behnken.jpg"), "png"));
        when(drive.downloadOriginal(eq("broken000001"), anyLong())).thenReturn("not a photo".getBytes());
        when(drive.downloadResized(eq("broken000001"), anyInt(), anyLong())).thenReturn(null);
    }

    @Test
    void importsEveryPhotoInTheFolderAndItsSubfolders() {
        Models.assumePresent();
        String org = api.registerOrganizer("drive@example.com");
        String event = api.createEvent(org, "Drive");

        var start = api.post("/api/events/" + event + "/drive-imports", org, Map.of("link", LINK));
        assertThat(start.getStatusCode().value()).isEqualTo(202);
        assertThat(start.getBody()).containsEntry("status", "listing");

        drainJobs(); // the listing job, one job per photo, then the indexing jobs they queue

        Map<String, Object> result = api.getList("/api/events/" + event + "/drive-imports", org).get(0);
        assertThat(result).containsEntry("status", "done")
                .containsEntry("found", 6)
                .containsEntry("imported", 4)
                .containsEntry("duplicates", 1)
                .containsEntry("failed", 1)
                .containsEntry("skipped", 2)
                .containsEntry("queued", 0);
        assertThat((List<Map<String, Object>>) result.get("failures"))
                .containsExactly(Map.of("name", "broken.jpg", "error", "download_failed"));

        // the imported photos went through the normal path: stored as JPEG and indexed
        assertThat(count("SELECT count(*) FROM photos WHERE event_id = ?::uuid AND status = 'ready'", event)).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM photos WHERE event_id = ?::uuid AND object_key NOT LIKE '%.jpg'", event)).isZero();
    }

    @Test
    void aPrivateFolderFailsWithAClearReason() throws Exception {
        when(drive.listFolder("privateFold01")).thenThrow(new DriveClient.FolderNotPublicException());
        String org = api.registerOrganizer("drive-private@example.com");
        String event = api.createEvent(org, "Private Drive");
        api.post("/api/events/" + event + "/drive-imports", org, Map.of("link", "https://drive.google.com/drive/folders/privateFold01"));
        drainJobs();
        Map<String, Object> result = api.getList("/api/events/" + event + "/drive-imports", org).get(0);
        assertThat(result).containsEntry("status", "failed").containsEntry("error", "folder_not_public");
    }

    @Test
    void refusesBadLinksStrangersAndASecondImportAtOnce() {
        String org = api.registerOrganizer("drive-rules@example.com");
        String stranger = api.registerOrganizer("drive-stranger@example.com");
        String event = api.createEvent(org, "Rules");

        var bad = api.post("/api/events/" + event + "/drive-imports", org, Map.of("link", "https://example.com/folders/abcdefghijkl"));
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
        assertThat(bad.getBody()).containsEntry("code", "bad_drive_link");

        var notOwner = api.post("/api/events/" + event + "/drive-imports", stranger, Map.of("link", LINK));
        assertThat(notOwner.getStatusCode().value()).isEqualTo(404);

        assertThat(api.post("/api/events/" + event + "/drive-imports", org, Map.of("link", LINK)).getStatusCode().value()).isEqualTo(202);
        var second = api.post("/api/events/" + event + "/drive-imports", org, Map.of("link", LINK));
        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(second.getBody()).containsEntry("code", "import_running");
    }
}
