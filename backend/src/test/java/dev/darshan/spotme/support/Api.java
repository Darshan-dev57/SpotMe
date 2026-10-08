package dev.darshan.spotme.support;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/** A tiny HTTP client for tests: real requests against the running app, errors returned not thrown. */
public class Api {

    private final RestClient http;

    public Api(String baseUrl) {
        this.http = RestClient.builder().baseUrl(baseUrl)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
                .build();
    }

    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> post(String path, String token, Object body) {
        var spec = http.post().uri(path).contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            spec = spec.body(body);
        }
        return (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>) spec.retrieve().toEntity(Map.class);
    }

    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> get(String path, String token) {
        var spec = http.get().uri(path);
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        return (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>) spec.retrieve().toEntity(Map.class);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getList(String path, String token) {
        return http.get().uri(path).header("Authorization", "Bearer " + token).retrieve().body(List.class);
    }

    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> delete(String path, String token) {
        var spec = http.delete().uri(path);
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        return (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>) spec.retrieve().toEntity(Map.class);
    }

    public ResponseEntity<byte[]> getBytes(String path) {
        return http.get().uri(path).retrieve().toEntity(byte[].class);
    }

    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> upload(String eventId, String token, String fileName, byte[] bytes) {
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        var spec = http.post().uri("/api/events/" + eventId + "/photos");
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        return (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>) spec
                .contentType(MediaType.MULTIPART_FORM_DATA).body(form)
                .retrieve().toEntity(Map.class);
    }

    public ResponseEntity<Map<String, Object>> search(String attendeeToken, byte[] selfie) {
        return postImage("/api/attendee/search", attendeeToken, selfie);
    }

    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> postImage(String path, String attendeeToken, byte[] image) {
        return (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>) http.post().uri(path)
                .header("Authorization", "Bearer " + attendeeToken)
                .contentType(MediaType.IMAGE_JPEG).body(image)
                .retrieve().toEntity(Map.class);
    }

    /** Body for POST /api/attendee/removals with one photo. */
    public static Map<String, Object> removal(String photoId, String note) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("photoIds", List.of(photoId));
        body.put("note", note);
        return body;
    }

    // ---------- multi-step helpers ----------

    public String registerOrganizer(String email) {
        var res = post("/api/auth/register", null, Map.of("email", email, "name", "Test Organizer", "password", "password123"));
        return (String) res.getBody().get("token");
    }

    public String createEvent(String token, String title) {
        var res = post("/api/events", token, Map.of(
                "title", title,
                "purposeNotice", "Find your photos from " + title + ". Deleted after the event expires.",
                "expiresAt", Instant.now().plus(7, ChronoUnit.DAYS).toString(),
                "consentAttested", true));
        return (String) res.getBody().get("id");
    }

    /** Creates an invite, accepts the notice and returns the attendee session token. */
    public String joinAsAttendee(String organizerToken, String eventId, String label) {
        var invite = post("/api/events/" + eventId + "/invites", organizerToken, Map.of("label", label)).getBody();
        String inviteToken = (String) invite.get("token");
        var notice = get("/api/join/" + inviteToken, null).getBody();
        var consent = post("/api/join/" + inviteToken + "/consent", null,
                Map.of("noticeVersion", notice.get("noticeVersion"), "agree", true)).getBody();
        return (String) consent.get("sessionToken");
    }

    public static byte[] fixture(String name) {
        try (InputStream in = Api.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("missing fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
