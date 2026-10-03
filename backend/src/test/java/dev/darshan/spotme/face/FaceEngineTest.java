package dev.darshan.spotme.face;

import dev.darshan.spotme.support.Api;
import dev.darshan.spotme.support.Models;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the real YuNet + SFace models on public-domain NASA crew photos. */
class FaceEngineTest {

    static FaceEngine engine;

    @BeforeAll
    static void load() {
        Models.assumePresent();
        engine = new FaceEngine(Models.dir(), 0.8f, 1280, 40);
    }

    static List<DetectedFace> faces(String fixture) {
        Mat img = Imgcodecs.imdecode(new MatOfByte(Api.fixture(fixture)), Imgcodecs.IMREAD_COLOR);
        return engine.detectAndEmbed(img);
    }

    @Test
    void findsAllFourPeopleInTheGroupPhoto() {
        List<DetectedFace> faces = faces("crew-group.jpg");
        assertThat(faces).hasSize(4);
        for (DetectedFace f : faces) {
            assertThat(f.embedding()).hasSize(128);
            assertThat(f.score()).isGreaterThan(0.8f);
        }
    }

    @Test
    void portraitIsCloserToTheSamePersonInTheGroupThanToAnyoneElse() {
        // left to right in the group photo: Glover, Hopkins, Hurley, Behnken
        List<DetectedFace> group = faces("crew-group.jpg").stream()
                .sorted(Comparator.comparingInt(DetectedFace::x)).toList();
        float[] hurley = faces("hurley.jpg").get(0).embedding();

        double same = FaceEngine.cosine(hurley, group.get(2).embedding());
        assertThat(same).isGreaterThan(0.8);
        for (int i : new int[]{0, 1, 3}) {
            assertThat(FaceEngine.cosine(hurley, group.get(i).embedding())).isLessThan(0.4);
        }
    }

    @Test
    void embeddingsAreUnitLength() {
        float[] v = faces("behnken.jpg").get(0).embedding();
        double norm = Math.sqrt(FaceEngine.cosine(v, v));
        assertThat(norm).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
    }
}
