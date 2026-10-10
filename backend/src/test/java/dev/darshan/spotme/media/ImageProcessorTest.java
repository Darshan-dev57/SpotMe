package dev.darshan.spotme.media;

import dev.darshan.spotme.config.SpotMeProperties;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageProcessorTest {

    static SpotMeProperties props(long maxPixels) {
        return new SpotMeProperties("/tmp", "/tmp", "http://localhost:3000", "", "x",
                new SpotMeProperties.Upload(5_000_000, maxPixels, 2, 480),
                new SpotMeProperties.Face(0.8f, 1280, 40, 1, 1),
                new SpotMeProperties.Search(0.6, 100, 10, Duration.ofMinutes(10)),
                new SpotMeProperties.Worker(false, 1, Duration.ofMinutes(2), Duration.ofMillis(500), 5, Duration.ofSeconds(60)),
                new SpotMeProperties.Eval(false));
    }

    private final ImageProcessor processor = new ImageProcessor(props(40_000_000));

    @Test
    void rejectsFilesThatAreNotImagesWhateverTheirName() {
        byte[] text = "#!/bin/sh\necho hi".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> processor.clean(text, true))
                .isInstanceOf(ImageProcessor.RejectedImageException.class)
                .hasFieldOrPropertyWithValue("code", "unsupported_type");
    }

    @Test
    void rejectsATruncatedJpeg() throws IOException {
        byte[] jpeg = jpeg(400, 300);
        byte[] broken = java.util.Arrays.copyOf(jpeg, 40);
        assertThatThrownBy(() -> processor.clean(broken, true))
                .hasFieldOrPropertyWithValue("code", "corrupt_image");
    }

    @Test
    void rejectsTooManyPixelsBeforeDecoding() throws IOException {
        ImageProcessor strict = new ImageProcessor(props(10_000));
        assertThatThrownBy(() -> strict.clean(jpeg(200, 100), true))
                .hasFieldOrPropertyWithValue("code", "too_many_pixels");
    }

    @Test
    void reEncodingDropsExifAndMakesAThumbnail() throws Exception {
        byte[] withExif = insertExif(jpeg(1200, 800), "GPS 12.9716 N 77.5946 E");
        assertThat(new String(withExif, StandardCharsets.ISO_8859_1)).contains("Exif", "GPS 12.9716");

        ImageProcessor.CleanImage clean = processor.clean(withExif, true);

        String out = new String(clean.jpeg(), StandardCharsets.ISO_8859_1);
        assertThat(out).doesNotContain("Exif").doesNotContain("GPS 12.9716");
        assertThat(clean.width()).isEqualTo(1200);
        assertThat(clean.height()).isEqualTo(800);
        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(clean.thumbnail()));
        assertThat(Math.max(thumb.getWidth(), thumb.getHeight())).isEqualTo(480);
    }

    @Test
    void acceptsPng() throws IOException {
        BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        assertThat(ImageProcessor.sniffType(out.toByteArray())).isEqualTo("image/png");
    }

    static byte[] jpeg(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x += 10) {
            img.setRGB(x, h / 2, 0xFF8800);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    /** Puts a fake APP1 "Exif" segment right after the JPEG start marker, like a phone camera does. */
    static byte[] insertExif(byte[] jpeg, String payload) {
        byte[] body = ("Exif\0\0" + payload).getBytes(StandardCharsets.ISO_8859_1);
        int len = body.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);                       // FF D8
        out.write(0xFF);
        out.write(0xE1);                             // APP1
        out.write((len >> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(body, 0, body.length);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }
}
