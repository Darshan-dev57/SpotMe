package dev.darshan.spotme.media;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MediaSignerTest {

    private final MediaSigner signer = new MediaSigner(ImageProcessorTest.props(1));

    @Test
    void acceptsItsOwnLink() {
        UUID photo = UUID.randomUUID();
        long exp = Instant.now().getEpochSecond() + 60;
        String sig = signer.sign(photo, "thumb", "m:abc", exp);
        assertThat(signer.verify(photo, "thumb", "m:abc", exp, sig)).isTrue();
    }

    @Test
    void rejectsAnyChangedPart() {
        UUID photo = UUID.randomUUID();
        long exp = Instant.now().getEpochSecond() + 60;
        String sig = signer.sign(photo, "thumb", "m:abc", exp);
        assertThat(signer.verify(UUID.randomUUID(), "thumb", "m:abc", exp, sig)).isFalse();
        assertThat(signer.verify(photo, "original", "m:abc", exp, sig)).isFalse();
        assertThat(signer.verify(photo, "thumb", "m:other", exp, sig)).isFalse();
        assertThat(signer.verify(photo, "thumb", "m:abc", exp + 1, sig)).isFalse();
    }

    @Test
    void rejectsExpiredLinks() {
        UUID photo = UUID.randomUUID();
        long past = Instant.now().getEpochSecond() - 1;
        assertThat(signer.verify(photo, "thumb", "m:abc", past, signer.sign(photo, "thumb", "m:abc", past))).isFalse();
    }

    @Test
    void linkContainsEverythingNeededToVerify() {
        UUID photo = UUID.randomUUID();
        assertThat(signer.link(photo, "original", "o:x"))
                .startsWith("/api/media/" + photo + "/original?sub=o:x&exp=")
                .contains("&sig=");
    }
}
