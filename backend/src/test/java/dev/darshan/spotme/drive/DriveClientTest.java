package dev.darshan.spotme.drive;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DriveClientTest {

    @Test
    void parsesARealFolderPage() throws IOException {
        String html;
        try (InputStream in = getClass().getResourceAsStream("/fixtures/drive-folder.html")) {
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<DriveClient.Entry> entries = DriveClient.parseListing(html);

        assertThat(entries).hasSize(14);
        DriveClient.Entry folder = entries.get(0);
        assertThat(folder.folder()).isTrue();
        assertThat(folder.name()).isEqualTo("RAW PHOTOS");
        assertThat(folder.id()).isEqualTo("13RDa1-E1AqGgVdKYPSb11uc-EFX23rm-");
        assertThat(entries.subList(1, 14)).allSatisfy(e -> {
            assertThat(e.folder()).isFalse();
            assertThat(e.mimeType()).isEqualTo("image/jpeg");
            assertThat(e.name()).endsWith(".JPG");
        });
        assertThat(entries.get(1).id()).isEqualTo("1p6ulmIk9mXiPMQOB8lH64yjHKaD319Pr");
    }

    @Test
    void anEmptyOrUnknownPageHasNoEntries() {
        assertThat(DriveClient.parseListing("<html><body>Sign in</body></html>")).isEmpty();
    }

    @Test
    void onlyTalksToGoogleOverHttps() {
        assertThat(DriveClient.isAllowed(URI.create("https://drive.google.com/uc?id=x"))).isTrue();
        assertThat(DriveClient.isAllowed(URI.create("https://drive.usercontent.google.com/download?id=x"))).isTrue();
        assertThat(DriveClient.isAllowed(URI.create("https://lh3.googleusercontent.com/d/x=s4000"))).isTrue();

        assertThat(DriveClient.isAllowed(URI.create("http://drive.google.com/uc?id=x"))).isFalse();
        assertThat(DriveClient.isAllowed(URI.create("https://drive.google.com.evil.example/"))).isFalse();
        assertThat(DriveClient.isAllowed(URI.create("https://169.254.169.254/latest/meta-data"))).isFalse();
        assertThat(DriveClient.isAllowed(URI.create("https://localhost:8080/actuator"))).isFalse();
        assertThat(DriveClient.isAllowed(URI.create("https://drive.google.com:8443/"))).isFalse();
        assertThat(DriveClient.isAllowed(URI.create("https://user@drive.google.com/"))).isFalse();
        assertThat(DriveClient.isAllowed(URI.create("file:///etc/passwd"))).isFalse();
    }

    @Test
    void stopsReadingPastTheByteLimit() throws IOException {
        assertThat(DriveClient.readAtMost(new ByteArrayInputStream(new byte[100]), 100)).hasSize(100);
        assertThat(DriveClient.readAtMost(new ByteArrayInputStream(new byte[101]), 100)).isNull();
    }
}
