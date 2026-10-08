package dev.darshan.spotme.face;

/** pgvector accepts vectors as text like "[0.1,0.2,...]". */
public final class Vectors {

    private Vectors() {
    }

    public static String toPgVector(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    /** The reverse of toPgVector, for a vector read back as text. */
    public static float[] fromPgVector(String text) {
        String inner = text.trim();
        inner = inner.substring(1, inner.length() - 1);
        if (inner.isBlank()) {
            return new float[0];
        }
        String[] parts = inner.split(",");
        float[] v = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            v[i] = Float.parseFloat(parts[i].trim());
        }
        return v;
    }
}
