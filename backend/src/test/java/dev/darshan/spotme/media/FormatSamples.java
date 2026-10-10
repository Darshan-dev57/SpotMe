package dev.darshan.spotme.media;

import dev.darshan.spotme.face.FaceEngine;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Builds real files in each supported format for tests, from a picture or a JPEG fixture. */
public final class FormatSamples {

    private FormatSamples() {
    }

    public static BufferedImage testPattern(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, ((x * 255 / w) << 16) | ((y * 255 / h) << 8) | 0x40);
            }
        }
        return img;
    }

    /** Re-encodes a JPEG (for example a test fixture) into another format. */
    public static byte[] convert(byte[] jpeg, String format) throws IOException {
        return encode(ImageIO.read(new ByteArrayInputStream(jpeg)), format);
    }

    /** webp via OpenCV (Java has no WebP writer), the rest via Java's own ImageIO writers. */
    public static byte[] encode(BufferedImage img, String format) throws IOException {
        if (format.equals("webp")) {
            FaceEngine.loadNative();
            BufferedImage bgr = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
            bgr.getGraphics().drawImage(img, 0, 0, null);
            Mat mat = new Mat(bgr.getHeight(), bgr.getWidth(), CvType.CV_8UC3);
            mat.put(0, 0, ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData());
            MatOfByte out = new MatOfByte();
            Imgcodecs.imencode(".webp", mat, out);
            mat.release();
            return out.toArray();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(img, format, out)) {
            throw new IOException("no writer for " + format);
        }
        return out.toByteArray();
    }

    /** The start of an ISO media file: an ftyp box with a major brand and compatible brands. */
    public static byte[] ftyp(String major, String... compatible) {
        int size = 16 + 4 * compatible.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(size);
        out.writeBytes("ftyp".getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(major.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(new byte[4]); // minor version
        for (String brand : compatible) {
            out.writeBytes(brand.getBytes(StandardCharsets.ISO_8859_1));
        }
        out.writeBytes(new byte[64]);
        return out.toByteArray();
    }
}
