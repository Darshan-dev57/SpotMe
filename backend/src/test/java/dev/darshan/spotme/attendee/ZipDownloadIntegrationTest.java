package dev.darshan.spotme.attendee;

import dev.darshan.spotme.support.Api;
import dev.darshan.spotme.support.IntegrationTest;
import dev.darshan.spotme.support.Models;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** "Download all" and "download selected": streamed ZIPs with only the photos the guest may see. */
class ZipDownloadIntegrationTest extends IntegrationTest {

    String org;
    String event;
    String groupPhoto;
    String gloverPhoto;
    String doug;
    RestClient http;

    @BeforeEach
    void eventWithAMatchAndAStranger() {
        Models.assumePresent();
        http = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(s -> s.isError(), (req, res) -> { }).build();
        org = api.registerOrganizer("zip-" + System.nanoTime() + "@example.com");
        event = api.createEvent(org, "Zip party");
        groupPhoto = (String) api.upload(event, org, "crew-group.jpg", Api.fixture("crew-group.jpg")).getBody().get("id");
        gloverPhoto = (String) api.upload(event, org, "glover.jpg", Api.fixture("glover.jpg")).getBody().get("id");
        drainJobs();
        api.post("/api/events/" + event + "/publish", org, null);
        doug = api.joinAsAttendee(org, event, "Doug");
        assertThat(api.search(doug, Api.fixture("hurley.jpg")).getBody()).containsEntry("count", 1);
    }

    private String downloadLink(String guest) {
        var res = api.post("/api/attendee/download-link", guest, null);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return (String) res.getBody().get("url");
    }

    private ResponseEntity<byte[]> get(String url) {
        return http.get().uri(url).retrieve().toEntity(byte[].class);
    }

    private ResponseEntity<byte[]> downloadChosen(String guest, List<String> ids) {
        return http.post().uri("/api/attendee/download").header("Authorization", "Bearer " + guest)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("photoIds", ids)).retrieve().toEntity(byte[].class);
    }

    private static List<String> entries(byte[] zip) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                byte[] data = in.readAllBytes();
                // every entry is a real JPEG
                assertThat(data[0] & 0xff).isEqualTo(0xff);
                assertThat(data[1] & 0xff).isEqualTo(0xd8);
                names.add(e.getName());
            }
        }
        return names;
    }

    @Test
    void downloadAllHasExactlyTheGuestsMatchesAndIsStreamed() throws Exception {
        ResponseEntity<byte[]> res = get(downloadLink(doug));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getContentType().toString()).isEqualTo("application/zip");
        assertThat(res.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("zip-party-photos.zip");
        // no Content-Length: the ZIP is written while the photos are read, never buffered whole
        assertThat(res.getHeaders().getContentLength()).isEqualTo(-1);
        assertThat(entries(res.getBody())).containsExactly("photo-001.jpg");
    }

    @Test
    void aGuestCannotZipAPrivatePhotoTheyWereNotShown() throws Exception {
        var res = downloadChosen(doug, List.of(groupPhoto, gloverPhoto));
        assertThat(entries(res.getBody())).hasSize(1);

        assertThat(downloadChosen(doug, List.of(gloverPhoto)).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void aPublicPhotoCanBeZippedByAnyGuest() throws Exception {
        api.post("/api/events/" + event + "/photos/visibility", org, Map.of("photoIds", List.of(gloverPhoto), "visibility", "public"));
        String stranger = api.joinAsAttendee(org, event, "Stranger");

        assertThat(entries(downloadChosen(stranger, List.of(gloverPhoto)).getBody())).hasSize(1);
        // but "download all" is only for a guest's own matches
        assertThat(api.post("/api/attendee/download-link", stranger, null).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void aTamperedOrForeignLinkIsRefused() {
        String url = downloadLink(doug);
        String bob = api.joinAsAttendee(org, event, "Bob");
        api.search(bob, Api.fixture("hurley.jpg"));
        String bobsUrl = downloadLink(bob);

        String bobsId = bobsUrl.substring("/api/zip/".length(), bobsUrl.indexOf('?'));
        String dougsQuery = url.substring(url.indexOf('?'));
        // Doug's signature on Bob's id
        assertThat(get("/api/zip/" + bobsId + dougsQuery).getStatusCode().value()).isEqualTo(403);
        assertThat(get(url.replace("&sig=", "&sig=x")).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void withdrawingEndsTheDownloadLink() {
        String url = downloadLink(doug);
        api.post("/api/attendee/withdraw", doug, null);

        assertThat(get(url).getStatusCode().value()).isEqualTo(403);
        assertThat(count("SELECT count(*) FROM guest_matches gm JOIN memberships m ON m.id = gm.membership_id WHERE m.event_id = ?::uuid", event)).isZero();
    }

    @Test
    void aHiddenPhotoLeavesTheZip() {
        String url = downloadLink(doug);
        api.post("/api/events/" + event + "/photos/" + groupPhoto + "/hide", org, null);
        assertThat(get(url).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void zipsAreRateLimitedPerGuest() {
        for (int i = 0; i < PhotoZipper.PER_MINUTE; i++) {
            assertThat(get(downloadLink(doug)).getStatusCode().value()).isEqualTo(200);
        }
        assertThat(get(downloadLink(doug)).getStatusCode().value()).isEqualTo(429);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aNewSearchReplacesTheRememberedMatches() {
        String sql = "SELECT gm.photo_id::text FROM guest_matches gm JOIN memberships m ON m.id = gm.membership_id "
                + "WHERE m.event_id = ?::uuid AND m.label = 'Doug'";
        assertThat(jdbc.queryForList(sql, String.class, event)).containsExactly(groupPhoto);

        var photos = (List<Map<String, Object>>) api.search(doug, Api.fixture("glover.jpg")).getBody().get("photos");

        assertThat(jdbc.queryForList(sql, String.class, event))
                .containsExactlyInAnyOrderElementsOf(photos.stream().map(p -> (String) p.get("id")).toList());
    }
}
