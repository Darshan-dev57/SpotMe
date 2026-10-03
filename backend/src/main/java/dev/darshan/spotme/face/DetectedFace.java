package dev.darshan.spotme.face;

/**
 * One face found in an image. The box is in original-image pixels and the
 * embedding is the 128-number SFace vector, already L2-normalised.
 */
public record DetectedFace(int x, int y, int width, int height, float score, float[] embedding) {

    public int area() {
        return width * height;
    }
}
