package dev.darshan.spotme.config;

import dev.darshan.spotme.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The app serves the exported web UI next to the API, the way it runs in production. */
class WebUiIntegrationTest extends IntegrationTest {

    static final Path WEB_DIR = exportedSite();

    @DynamicPropertySource
    static void webDir(DynamicPropertyRegistry registry) {
        registry.add("spotme.web-dir", WEB_DIR::toString);
    }

    @Test
    void homePageAndExportedPagesAreServed() {
        assertThat(text(api.getBytes("/"))).contains("home page");
        assertThat(text(api.getBytes("/organizer/events"))).contains("events page");
        assertThat(text(api.getBytes("/organizer/events.html"))).contains("events page");
        assertThat(api.getBytes("/organizer/events").getHeaders().getContentType().toString()).startsWith("text/html");
    }

    @Test
    void hashedAssetsAreCachedForAYear() {
        var res = api.getBytes("/_next/static/app.123.js");
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getCacheControl()).contains("max-age=31536000").contains("immutable");
    }

    @Test
    void unknownPageGetsThe404PageWithA404Status() {
        var res = api.getBytes("/no-such-page");
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(text(res)).contains("not found page");
    }

    @Test
    void unknownApiRouteStillAnswersJson() {
        var res = api.get("/api/no-such-route", null);
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getBody()).containsEntry("code", "not_found");
    }

    @Test
    void filesOutsideTheWebFolderAreNotReachable() {
        // secret.txt sits one folder above the web folder
        for (String path : new String[]{"/../secret.txt", "/%2e%2e/secret.txt", "/organizer/..%2f..%2fsecret.txt"}) {
            var res = api.getBytes(path);
            assertThat(res.getStatusCode().is2xxSuccessful()).as(path).isFalse();
        }
    }

    private static String text(ResponseEntity<byte[]> res) {
        assertThat(res.getBody()).isNotNull();
        return new String(res.getBody(), StandardCharsets.UTF_8);
    }

    private static Path exportedSite() {
        try {
            Path parent = Files.createTempDirectory("spotme-web");
            Files.writeString(parent.resolve("secret.txt"), "not for the web");
            Path dir = Files.createDirectories(parent.resolve("site"));
            Files.createDirectories(dir.resolve("organizer"));
            Files.createDirectories(dir.resolve("_next/static"));
            Files.writeString(dir.resolve("index.html"), "<html>home page</html>");
            Files.writeString(dir.resolve("organizer/events.html"), "<html>events page</html>");
            Files.writeString(dir.resolve("404.html"), "<html>not found page</html>");
            Files.writeString(dir.resolve("_next/static/app.123.js"), "console.log(1)");
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
