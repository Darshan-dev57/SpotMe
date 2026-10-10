package dev.darshan.spotme.search;

import dev.darshan.spotme.face.DetectedFace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SelfieRulesTest {

    static DetectedFace face(int size) {
        return new DetectedFace(0, 0, size, size, 0.9f, new float[128]);
    }

    @Test
    void noFaceIsRejected() {
        assertThatThrownBy(() -> SearchService.pickSingleFace(List.of()))
                .hasFieldOrPropertyWithValue("code", "no_face");
    }

    @Test
    void twoSimilarSizedFacesAreRejected() {
        assertThatThrownBy(() -> SearchService.pickSingleFace(List.of(face(200), face(150))))
                .hasFieldOrPropertyWithValue("code", "multiple_faces");
    }

    @Test
    void aSmallFaceInTheBackgroundIsIgnored() throws Exception {
        DetectedFace main = face(300);
        assertThat(SearchService.pickSingleFace(List.of(face(60), main))).isSameAs(main);
    }
}
