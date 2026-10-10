package dev.darshan.spotme.face;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VectorsTest {

    @Test
    void textFormRoundTrips() {
        float[] v = {0.25f, -1f, 0f, 0.125f};
        assertThat(Vectors.fromPgVector(Vectors.toPgVector(v))).containsExactly(v);
        // pgvector prints vectors with spaces after commas in some versions
        assertThat(Vectors.fromPgVector("[1, 2.5,-3]")).containsExactly(1f, 2.5f, -3f);
    }
}
