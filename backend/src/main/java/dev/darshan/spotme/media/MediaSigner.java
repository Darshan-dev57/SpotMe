package dev.darshan.spotme.media;

import dev.darshan.spotme.common.Tokens;
import dev.darshan.spotme.config.SpotMeProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Short-lived signed links for thumbnails and originals, so an <img> tag can load a
 * private photo without sending the bearer token. The signature only proves WE made the
 * link; the media endpoint still re-checks live access on every request.
 */
@Component
public class MediaSigner {

    private final byte[] secret;
    private final long ttlSeconds;

    public MediaSigner(SpotMeProperties props) {
        String configured = props.mediaSecret();
        // no secret configured (local dev): make a random one, links just stop working after a restart
        this.secret = (configured == null || configured.isBlank() ? Tokens.newToken() : configured)
                .getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = props.search().mediaLinkTtl().toSeconds();
    }

    /** subject is "m:{membershipId}" for attendees or "o:{organizerId}" for organizers. */
    public String link(UUID photoId, String variant, String subject) {
        long exp = Instant.now().getEpochSecond() + ttlSeconds;
        return "/api/media/" + photoId + "/" + variant + "?sub=" + subject + "&exp=" + exp
                + "&sig=" + sign(photoId, variant, subject, exp);
    }

    public boolean verify(UUID photoId, String variant, String subject, long exp, String sig) {
        if (sig == null || exp < Instant.now().getEpochSecond()) {
            return false;
        }
        byte[] expected = sign(photoId, variant, subject, exp).getBytes(StandardCharsets.UTF_8);
        // constant-time compare so the signature can't be guessed byte by byte
        return MessageDigest.isEqual(expected, sig.getBytes(StandardCharsets.UTF_8));
    }

    String sign(UUID photoId, String variant, String subject, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] out = mac.doFinal((photoId + "|" + variant + "|" + subject + "|" + exp).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
