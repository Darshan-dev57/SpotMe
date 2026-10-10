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
}
