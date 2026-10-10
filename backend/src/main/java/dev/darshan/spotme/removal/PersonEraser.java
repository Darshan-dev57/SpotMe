package dev.darshan.spotme.removal;

/**
 * Removes one person from a photo and fills the gap with background.
 * The face box is in the stored photo's pixels; the eraser finds the rest of the body itself.
 */
public interface PersonEraser {

    record FaceBox(int x, int y, int w, int h) {
    }

    /** Returns the edited photo as JPEG bytes. */
    byte[] erase(byte[] jpeg, FaceBox face) throws Exception;
}
