package dev.darshan.spotme.media;

/**
 * Reads width and height from a WebP header without decoding (Java's ImageIO has no WebP reader).
 * A WebP file is a RIFF container whose first chunk is one of:
 * VP8 (lossy), VP8L (lossless) or VP8X (extended, may hold animation or alpha).
 * Layouts from the WebP container spec: https://developers.google.com/speed/webp/docs/riff_container
 */
final class WebpHeader {

    private WebpHeader() {
    }

    /** {width, height}, or null if the header is not a valid WebP header. */
    static long[] size(byte[] b) {
        if (b.length < 30) {
            return null;
        }
        String chunk = new String(b, 12, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
        switch (chunk) {
            case "VP8 " -> {
                // 3-byte frame tag, then the start code 9D 01 2A, then 14-bit width and height
                if ((b[23] & 0xFF) != 0x9D || (b[24] & 0xFF) != 0x01 || (b[25] & 0xFF) != 0x2A) {
                    return null;
                }
                long w = ((b[26] & 0xFF) | (b[27] & 0xFF) << 8) & 0x3FFF;
                long h = ((b[28] & 0xFF) | (b[29] & 0xFF) << 8) & 0x3FFF;
                return valid(w, h);
            }
            case "VP8L" -> {
                // signature 0x2F, then 14 bits width-1 and 14 bits height-1
                if ((b[20] & 0xFF) != 0x2F) {
                    return null;
                }
                long bits = (b[21] & 0xFFL) | (b[22] & 0xFFL) << 8 | (b[23] & 0xFFL) << 16 | (b[24] & 0xFFL) << 24;
                return valid((bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1);
            }
            case "VP8X" -> {
                // flags (4 bytes), then 24-bit canvas width-1 and height-1
                long w = ((b[24] & 0xFFL) | (b[25] & 0xFFL) << 8 | (b[26] & 0xFFL) << 16) + 1;
                long h = ((b[27] & 0xFFL) | (b[28] & 0xFFL) << 8 | (b[29] & 0xFFL) << 16) + 1;
                return valid(w, h);
            }
            default -> {
                return null;
            }
        }
    }

    /** True for an animated WebP (VP8X chunk with the animation flag), which OpenCV can't decode. */
    static boolean isAnimated(byte[] b) {
        return b.length >= 21 && new String(b, 12, 4, java.nio.charset.StandardCharsets.ISO_8859_1).equals("VP8X")
                && (b[20] & 0x02) != 0;
    }

    private static long[] valid(long w, long h) {
        return w > 0 && h > 0 ? new long[]{w, h} : null;
    }
}
