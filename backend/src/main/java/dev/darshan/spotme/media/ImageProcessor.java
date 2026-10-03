package dev.darshan.spotme.media;

import dev.darshan.spotme.config.SpotMeProperties;
import dev.darshan.spotme.face.FaceEngine;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfInt;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.Semaphore;

/**
 * Turns untrusted upload bytes into a safe image:
 * 1. check the real type from the first bytes (not the file name or Content-Type),
 * 2. read only the header to get width x height and reject huge images BEFORE decoding,
 * 3. decode (EXIF rotation is applied here),
 * 4. re-encode as a fresh JPEG, which drops every metadata block (GPS, camera serial, ...).
 */
@Component
public class ImageProcessor {

    /** Thrown for files we will never accept; the message is safe to show to the user. */
    public static class RejectedImageException extends Exception {
        private final String code;

        public RejectedImageException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public record CleanImage(byte[] jpeg, byte[] thumbnail, int width, int height) {
    }

    private final long maxBytes;
    private final long maxPixels;
    private final int thumbSide;
    // a 40 MP photo is ~120 MB once decoded, so only a couple of decodes may run at once
    private final Semaphore decodeSlots;

    public ImageProcessor(SpotMeProperties props) {
        this.maxBytes = props.upload().maxBytes();
        this.maxPixels = props.upload().maxPixels();
        this.thumbSide = props.upload().thumbnailSide();
        this.decodeSlots = new Semaphore(props.upload().maxConcurrentDecodes());
        FaceEngine.loadNative();
    }

    public CleanImage clean(byte[] bytes, boolean withThumbnail) throws RejectedImageException {
        Mat image = decode(bytes);
        try {
            byte[] jpeg = encodeJpeg(image, 90);
            byte[] thumb = null;
            if (withThumbnail) {
                Mat small = resizeToFit(image, thumbSide);
                thumb = encodeJpeg(small, 80);
                small.release();
            }
            return new CleanImage(jpeg, thumb, image.cols(), image.rows());
        } finally {
            image.release();
        }
    }

    /** Validates and decodes to a BGR Mat. The caller must release() it. */
    public Mat decode(byte[] bytes) throws RejectedImageException {
        if (bytes.length == 0) {
            throw new RejectedImageException("empty_file", "The file is empty.");
        }
        if (bytes.length > maxBytes) {
            throw new RejectedImageException("file_too_large", "That file is larger than 20 MB.");
        }
        String type = sniffType(bytes);
        if (type == null) {
            throw new RejectedImageException("unsupported_type", "Only JPEG and PNG photos are supported.");
        }
        long[] size = readHeaderSize(bytes);
        if (size == null) {
            throw new RejectedImageException("corrupt_image", "This image could not be read.");
        }
        if (size[0] * size[1] > maxPixels) {
            throw new RejectedImageException("too_many_pixels", "That photo is over 40 megapixels.");
        }
        try {
            decodeSlots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RejectedImageException("busy", "Server is busy, try again.");
        }
        try {
            Mat mat = Imgcodecs.imdecode(new MatOfByte(bytes), Imgcodecs.IMREAD_COLOR);
            if (mat.empty()) {
                throw new RejectedImageException("corrupt_image", "This image could not be read.");
            }
            return mat;
        } finally {
            decodeSlots.release();
        }
    }

    /** Magic numbers: JPEG starts FF D8 FF, PNG starts 89 'P' 'N' 'G'. */
    static String sniffType(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        return null;
    }

    /** Reads only the image header (cheap) so a "decompression bomb" is refused before it is decoded. */
    static long[] readHeaderSize(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                return new long[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static Mat resizeToFit(Mat image, int maxSide) {
        double scale = Math.min(1.0, (double) maxSide / Math.max(image.cols(), image.rows()));
        Mat out = new Mat();
        Imgproc.resize(image, out, new Size(Math.max(1, Math.round(image.cols() * scale)),
                Math.max(1, Math.round(image.rows() * scale))), 0, 0, Imgproc.INTER_AREA);
        return out;
    }

    static byte[] encodeJpeg(Mat image, int quality) {
        MatOfByte out = new MatOfByte();
        Imgcodecs.imencode(".jpg", image, out, new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, quality));
        byte[] bytes = out.toArray();
        out.release();
        return bytes;
    }
}
