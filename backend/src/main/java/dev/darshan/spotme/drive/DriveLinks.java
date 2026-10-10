package dev.darshan.spotme.drive;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds the folder id in a Google Drive folder link as people paste it. */
public final class DriveLinks {

    private static final Pattern FOLDER_PATH = Pattern.compile("/folders/([A-Za-z0-9_-]{10,})");
    private static final Pattern ID_PARAM = Pattern.compile("(?:^|&)id=([A-Za-z0-9_-]{10,})");

    private DriveLinks() {
    }

    /**
     * Accepts drive.google.com/drive/folders/ID (with or without /u/0/ and ?usp=sharing) and
     * drive.google.com/open?id=ID. Returns null for anything else, including file links and other sites.
     */
    public static String folderId(String link) {
        if (link == null) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(link.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"drive.google.com".equalsIgnoreCase(uri.getHost())) {
            return null;
        }
        Matcher path = FOLDER_PATH.matcher(uri.getRawPath() == null ? "" : uri.getRawPath());
        if (path.find()) {
            return path.group(1);
        }
        if ("/open".equals(uri.getRawPath()) && uri.getRawQuery() != null) {
            Matcher id = ID_PARAM.matcher(uri.getRawQuery());
            if (id.find()) {
                return id.group(1);
            }
        }
        return null;
    }
}
