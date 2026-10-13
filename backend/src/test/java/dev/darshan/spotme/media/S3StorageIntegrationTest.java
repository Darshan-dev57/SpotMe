package dev.darshan.spotme.media;

import dev.darshan.spotme.support.Api;
import dev.darshan.spotme.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The whole photo life cycle with photos in a MinIO bucket instead of on disk. */
class S3StorageIntegrationTest extends IntegrationTest {

    static final MinIOContainer MINIO = Minio.container();

    static {
        MINIO.start();
    }

    @DynamicPropertySource
    static void s3(DynamicPropertyRegistry registry) {
        registry.add("spotme.storage-type", () -> "s3");
        registry.add("spotme.s3.endpoint", MINIO::getS3URL);
        registry.add("spotme.s3.bucket", () -> "it-photos");
        registry.add("spotme.s3.access-key", MINIO::getUserName);
        registry.add("spotme.s3.secret-key", MINIO::getPassword);
    }

    @Autowired
    PhotoStorage storage;

    private final HttpClient http = HttpClient.newHttpClient(); // does not follow redirects

    @Test
    void uploadSearchDownloadAndDeleteAllUseTheBucket() throws Exception {
        assertThat(storage).isInstanceOf(S3PhotoStorage.class);

        String org = api.registerOrganizer("s3@example.com");
        String event = api.createEvent(org, "Bucket party");
        String photo = (String) api.upload(event, org, "hurley.jpg", Api.fixture("hurley.jpg")).getBody().get("id");
        String original = jdbc.queryForObject("SELECT object_key FROM photos WHERE id = ?::uuid", String.class, photo);
        String thumb = jdbc.queryForObject("SELECT thumb_key FROM photos WHERE id = ?::uuid", String.class, photo);
        assertThat(storage.exists(original)).isTrue();
        assertThat(storage.exists(thumb)).isTrue();

        // the worker reads the photo back from the bucket to find faces
        drainJobs();
        assertThat(count("SELECT count(*) FROM faces WHERE photo_id = ?::uuid", photo)).isEqualTo(1);

        api.post("/api/events/" + event + "/publish", org, null);
        String guest = api.joinAsAttendee(org, event, "Guest");
        @SuppressWarnings("unchecked")
        var photos = (List<Map<String, Object>>) api.search(guest, Api.fixture("hurley.jpg")).getBody().get("photos");
        assertThat(photos).hasSize(1);

        // the app checks the guest, then sends them on to a short-lived signed bucket link
        String download = "http://localhost:" + port + photos.get(0).get("downloadUrl");
        HttpResponse<byte[]> redirect = get(URI.create(download));
        assertThat(redirect.statusCode()).isEqualTo(302);
        URI bucketLink = URI.create(redirect.headers().firstValue("Location").orElseThrow());
        assertThat(bucketLink.toString()).startsWith(MINIO.getS3URL()).contains("X-Amz-Signature=");
        HttpResponse<byte[]> file = get(bucketLink);
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.body()).isEqualTo(storage.get(original));

        // the organizer deletes the photo: original and thumbnail leave the bucket, the app link stops working
        api.delete("/api/events/" + event + "/photos/" + photo, org);
        drainJobs();
        assertThat(storage.exists(original)).isFalse();
        assertThat(storage.exists(thumb)).isFalse();
        assertThat(get(URI.create(download)).statusCode()).isIn(403, 404);

        // deleting the event empties its folder in the bucket
        api.upload(event, org, "behnken.jpg", Api.fixture("behnken.jpg"));
        assertThat(storage.list("events/" + event)).isNotEmpty();
        api.delete("/api/events/" + event, org);
        drainJobs();
        assertThat(storage.list("events/" + event)).isEmpty();
    }

    private HttpResponse<byte[]> get(URI uri) throws Exception {
        return http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
