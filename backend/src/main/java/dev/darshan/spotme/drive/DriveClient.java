package dev.darshan.spotme.drive;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads public Google Drive folders without an API key.
 *
 * <ul>
 *   <li>listing: {@code drive.google.com/embeddedfolderview?id=...}, the page Google serves for embedding a
 *       shared folder; each file or subfolder is one {@code flip-entry} block;</li>
 *   <li>original file: {@code drive.google.com/uc?export=download&id=...}, which redirects to
 *       {@code drive.usercontent.google.com};</li>
 *   <li>resized copy: {@code lh3.googleusercontent.com/d/<id>=s4000}, a JPEG Google renders with the longest
 *       side at most 4000 px (used for photos over our limits and formats we can't read, such as HEIC).</li>
 * </ul>
 *
 * Safety: only https, only the Google hosts below, redirects followed by hand so every hop is checked,
 * short timeouts, and bodies are read only up to a byte limit.
 */
@Component
public class DriveClient {

    /** One file or folder in a Drive folder listing. mimeType is null for folders. */
    public record Entry(String id, String name, boolean folder, String mimeType) {
    }

    /** A folder that is private, deleted or never existed. */
    public static class FolderNotPublicException extends IOException {
        public FolderNotPublicException() {
            super("folder_not_public");
        }
    }

    static final Set<String> ALLOWED_HOSTS = Set.of("drive.google.com", "drive.usercontent.google.com",
            "lh3.googleusercontent.com");
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_LISTING_BYTES = 5_000_000;
    private static final Pattern ENTRY = Pattern.compile("<div class=\"flip-entry\" id=\"entry-([A-Za-z0-9_-]+)\"");
    private static final Pattern TITLE = Pattern.compile("class=\"flip-entry-title\">([^<]*)<");
    private static final Pattern MIME = Pattern.compile("googleusercontent\\.com/16/type/([a-zA-Z0-9.+/_-]+)");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public List<Entry> listFolder(String folderId) throws IOException {
        Download page = get("https://drive.google.com/embeddedfolderview?id=" + folderId, MAX_LISTING_BYTES);
        if (page == null || page.status == 401 || page.status == 403 || page.status == 404) {
            throw new FolderNotPublicException();
        }
        if (page.status != 200) {
            throw new IOException("drive_listing_" + page.status);
        }
        return parseListing(new String(page.body, java.nio.charset.StandardCharsets.UTF_8));
    }

    /** The original file, or null if it is bigger than maxBytes or Google answers with a page instead of a file. */
    public byte[] downloadOriginal(String fileId, long maxBytes) throws IOException {
        Download d = get("https://drive.google.com/uc?export=download&id=" + fileId, maxBytes);
        if (d == null || d.status != 200 || d.contentType.startsWith("text/html")) {
            return null; // too big, or the "can't scan this file for viruses" page Google shows for large files
        }
        return d.body;
    }

    /** Google's own JPEG rendering with the longest side at most maxSide pixels, or null if there is none. */
    public byte[] downloadResized(String fileId, int maxSide, long maxBytes) throws IOException {
        Download d = get("https://lh3.googleusercontent.com/d/" + fileId + "=s" + maxSide, maxBytes);
        if (d == null || d.status != 200 || !d.contentType.startsWith("image/")) {
            return null;
        }
        return d.body;
    }

    /** Pulls entries out of the embedded folder page. Package-private so tests can feed it a saved page. */
    static List<Entry> parseListing(String html) {
        List<Entry> entries = new ArrayList<>();
        Matcher m = ENTRY.matcher(html);
        List<Integer> starts = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        while (m.find()) {
            starts.add(m.start());
            ids.add(m.group(1));
        }
        // each entry's block runs until the next entry starts
        for (int i = 0; i < ids.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) : html.length();
            String block = html.substring(starts.get(i), end);
            Matcher title = TITLE.matcher(block);
            String name = title.find() ? unescape(title.group(1).trim()) : ids.get(i);
            boolean folder = block.contains("aria-label=\"Folder\"") || block.contains("/drive/folders/" + ids.get(i));
            Matcher mime = MIME.matcher(block);
            entries.add(new Entry(ids.get(i), name, folder, folder ? null : (mime.find() ? mime.group(1).toLowerCase() : null)));
        }
        return entries;
    }

    /** True only for https URLs on the Google hosts this client may talk to. */
    static boolean isAllowed(URI uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && ALLOWED_HOSTS.contains(uri.getHost().toLowerCase()) && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getUserInfo() == null;
    }

    private record Download(int status, String contentType, byte[] body) {
    }

    /** GET with redirects checked hop by hop. Returns null when the body is over maxBytes. */
    private Download get(String url, long maxBytes) throws IOException {
        URI uri = URI.create(url);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!isAllowed(uri)) {
                throw new IOException("blocked_host");
            }
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "SpotMe photo import").GET().build();
            HttpResponse<InputStream> res;
            try {
                res = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
            int status = res.statusCode();
            if (status >= 300 && status < 400) {
                res.body().close();
                String location = res.headers().firstValue("Location").orElseThrow(() -> new IOException("redirect_without_location"));
                uri = uri.resolve(location);
                continue;
            }
            String type = res.headers().firstValue("Content-Type").orElse("").toLowerCase();
            long declared = res.headers().firstValueAsLong("Content-Length").orElse(-1);
            try (InputStream in = res.body()) {
                if (declared > maxBytes) {
                    return null;
                }
                byte[] body = readAtMost(in, maxBytes);
                return body == null ? null : new Download(status, type, body);
            }
        }
        throw new IOException("too_many_redirects");
    }

    /** Reads the stream, or returns null as soon as it goes past maxBytes (never holds more than that). */
    static byte[] readAtMost(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > maxBytes) {
                return null;
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static String unescape(String s) {
        return s.replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }
}
