package dev.darshan.spotme.media;

import dev.darshan.spotme.config.S3Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The bucket storage against a real MinIO server in Docker. */
class S3PhotoStorageTest {

    static final MinIOContainer MINIO = Minio.container();
    static S3PhotoStorage storage;
    static S3PhotoStorage shortLinks;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void start() {
        MINIO.start();
        storage = new S3PhotoStorage(props("unit-photos", Duration.ofSeconds(60)));
        shortLinks = new S3PhotoStorage(props("unit-photos", Duration.ofSeconds(1)));
    }

    @AfterAll
    static void stop() {
        storage.close();
        shortLinks.close();
        MINIO.stop();
    }

    static S3Properties props(String bucket, Duration linkTtl) {
        return new S3Properties(MINIO.getS3URL(), "us-east-1", bucket, MINIO.getUserName(), MINIO.getPassword(), linkTtl);
    }

    @Test
    void storesReadsStreamsAndDeletes() throws Exception {
        byte[] bytes = "photo bytes".getBytes(StandardCharsets.UTF_8);
        storage.put("events/e1/p1.jpg", bytes);

        assertThat(storage.exists("events/e1/p1.jpg")).isTrue();
        assertThat(storage.get("events/e1/p1.jpg")).isEqualTo(bytes);
        try (InputStream in = storage.open("events/e1/p1.jpg")) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }

        storage.delete("events/e1/p1.jpg");
        assertThat(storage.exists("events/e1/p1.jpg")).isFalse();
    }

    @Test
    void aMissingKeyBehavesLikeAMissingFile() throws Exception {
        assertThatThrownBy(() -> storage.get("events/none/x.jpg")).isInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> storage.open("events/none/x.jpg")).isInstanceOf(NoSuchFileException.class);
        assertThat(storage.exists("events/none/x.jpg")).isFalse();
        // deletes are retried by the purge jobs, so deleting twice must not fail
        storage.delete("events/none/x.jpg");
    }

    @Test
    void listStaysInsideTheFolder() throws Exception {
        storage.put("events/abc/1.jpg", new byte[]{1});
        storage.put("events/abc/thumbs/1.jpg", new byte[]{2});
        storage.put("events/abcd/2.jpg", new byte[]{3});

        assertThat(storage.list("events/abc")).extracting(PhotoStorage.StoredObject::key)
                .containsExactlyInAnyOrder("events/abc/1.jpg", "events/abc/thumbs/1.jpg");
        assertThat(storage.list("events/abc")).allSatisfy(o -> assertThat(o.lastModified()).isNotNull());
        assertThat(storage.list("events")).extracting(PhotoStorage.StoredObject::key).contains("events/abcd/2.jpg");
    }

    @Test
    void theBucketIsPrivateAndSignedLinksExpire() throws Exception {
        byte[] bytes = "signed".getBytes(StandardCharsets.UTF_8);
        storage.put("events/e2/p2.jpg", bytes);

        // no signature: refused
        URI plain = URI.create(MINIO.getS3URL() + "/unit-photos/events/e2/p2.jpg");
        assertThat(fetch(plain).statusCode()).isEqualTo(403);

        URI link = storage.directLink("events/e2/p2.jpg", "spotme-p2.jpg").orElseThrow();
        HttpResponse<byte[]> ok = fetch(link);
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).isEqualTo(bytes);
        assertThat(ok.headers().firstValue("Content-Disposition")).hasValueSatisfying(
                v -> assertThat(v).contains("attachment").contains("spotme-p2.jpg"));

        URI shortLink = shortLinks.directLink("events/e2/p2.jpg", null).orElseThrow();
        Thread.sleep(2500);
        assertThat(fetch(shortLink).statusCode()).isEqualTo(403);
    }

    private static HttpResponse<byte[]> fetch(URI uri) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
