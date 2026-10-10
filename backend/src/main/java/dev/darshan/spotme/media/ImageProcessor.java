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

import org.opencv.core.CvType;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.Semaphore;

/**
 * Turns untrusted upload bytes into a safe image:
 * 1. check the real type from the first bytes (not the file name or Content-Type),
 * 2. read only the header to get width x height and reject huge images BEFORE decoding,
 * 3. decode (EXIF rotation is applied here),
 * 4. re-encode as a fresh JPEG, which drops every metadata block (GPS, camera serial, ...).
 *
 * <p>Accepted: JPEG, PNG, WebP, TIFF, BMP and GIF (first frame). Whatever comes in, a JPEG is
 * stored, so the rest of the app only ever sees JPEG. HEIC and AVIF have no small Java decoder,
 * so the web page converts them to JPEG before upload and the server refuses them with a clear
 * message.
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
            throw new RejectedImageException("file_too_large", "That file is larger than " + maxBytes / 1_000_000 + " MB.");
        }
        String type = sniffType(bytes);
        if (type == null) {
            throw new RejectedImageException("unsupported_type",
                    "Only JPEG, PNG, WebP, TIFF, BMP, GIF, HEIC and AVIF photos are supported.");
        }
        if (type.equals("image/heic") || type.equals("image/avif")) {
            throw new RejectedImageException("unsupported_type",
                    "HEIC and AVIF photos are converted to JPEG by the SpotMe page before upload. Upload them there, or export them as JPEG.");
        }
        if (type.equals("image/webp") && WebpHeader.isAnimated(bytes)) {
            throw new RejectedImageException("unsupported_type", "Animated WebP files are not supported.");
        }
        long[] size = type.equals("image/webp") ? WebpHeader.size(bytes) : readHeaderSize(bytes);
        if (size == null) {
            throw new RejectedImageException("corrupt_image", "This image could not be read.");
        }
        if (size[0] * size[1] > maxPixels) {
            throw new RejectedImageException("too_many_pixels", "That photo is over " + pixelLimit() + ".");
        }
        try {
            decodeSlots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RejectedImageException("busy", "Server is busy, try again.");
        }
        try {
            // OpenCV reads JPEG, PNG, WebP, TIFF and BMP itself; it has no GIF reader
            Mat mat = type.equals("image/gif") ? decodeGifFirstFrame(bytes)
                    : Imgcodecs.imdecode(new MatOfByte(bytes), Imgcodecs.IMREAD_COLOR);
            if (mat.empty()) {
                throw new RejectedImageException("corrupt_image", "This image could not be read.");
            }
            return mat;
        } finally {
            decodeSlots.release();
        }
    }

    /** The pixel cap in words, e.g. "16 megapixels" (the hosted demo uses a lower cap than the default). */
    String pixelLimit() {
        return maxPixels >= 1_000_000 ? maxPixels / 1_000_000 + " megapixels" : maxPixels + " pixels";
    }

    // ISO "ftyp" brands used by HEIC/HEIF and AVIF files
    private static final Set<String> HEIF_BRANDS = Set.of("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1");
    private static final Set<String> AVIF_BRANDS = Set.of("avif", "avis");

    /**
     * Magic numbers: JPEG FF D8 FF, PNG 89 'PNG', WebP 'RIFF' .... 'WEBP', TIFF 'II*0' or 'MM0*',
     * BMP 'BM', GIF 'GIF8', HEIC/AVIF an ISO box 'ftyp' at byte 4 followed by a brand.
     */
    static String sniffType(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 12 && ascii(b, 0, 4).equals("RIFF") && ascii(b, 8, 4).equals("WEBP")) {
            return "image/webp";
        }
        if (b.length >= 8 && (ascii(b, 0, 4).equals("II*\0") || ascii(b, 0, 4).equals("MM\0*"))) {
            return "image/tiff";
        }
        if (b.length >= 26 && b[0] == 'B' && b[1] == 'M') {
            return "image/bmp";
        }
        if (b.length >= 6 && (ascii(b, 0, 6).equals("GIF87a") || ascii(b, 0, 6).equals("GIF89a"))) {
            return "image/gif";
        }
        if (b.length >= 12 && ascii(b, 4, 4).equals("ftyp")) {
            // the major brand, then the compatible brands up to the end of the ftyp box
            int boxEnd = Math.min(b.length, ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF));
            boolean heif = false;
            for (int i = 8; i + 4 <= boxEnd; i += 4) {
                String brand = ascii(b, i, 4);
                if (AVIF_BRANDS.contains(brand)) {
                    return "image/avif";
                }
                heif |= HEIF_BRANDS.contains(brand);
            }
            return heif ? "image/heic" : null;
        }
        return null;
    }

    private static String ascii(byte[] b, int from, int length) {
        return new String(b, from, length, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /** GIF: Java's own reader gives the first frame, painted on white (GIFs often use transparency). */
    static Mat decodeGifFirstFrame(byte[] bytes) {
        BufferedImage frame;
        try {
            frame = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException e) {
            return new Mat();
        }
        if (frame == null) {
            return new Mat();
        }
        BufferedImage bgr = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = bgr.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, bgr.getWidth(), bgr.getHeight());
        g.drawImage(frame, 0, 0, null);
        g.dispose();
        byte[] pixels = ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData();
        Mat mat = new Mat(bgr.getHeight(), bgr.getWidth(), CvType.CV_8UC3);
        mat.put(0, 0, pixels);
        return mat;
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
