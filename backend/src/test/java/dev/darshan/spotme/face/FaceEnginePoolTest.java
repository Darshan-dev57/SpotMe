package dev.darshan.spotme.face;

import dev.darshan.spotme.config.SpotMeProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class FaceEnginePoolTest {

    @Test
    void missingModelsFailFastEveryTimeInsteadOfBlocking() {
        var props = new SpotMeProperties("/tmp", "/tmp/spotme-no-models-here", "http://localhost:3000", "", "x",
                new SpotMeProperties.Upload(5_000_000, 1_000_000, 1, 480, 100),
                new SpotMeProperties.Face(0.8f, 1280, 40, 1, 1),
                new SpotMeProperties.Search(0.55, 100, 10, Duration.ofMinutes(10)),
                new SpotMeProperties.Worker(false, 1, Duration.ofMinutes(1), Duration.ofMillis(500), 5, Duration.ofSeconds(60)),
                new SpotMeProperties.Eval(false),
                new SpotMeProperties.Demo(false, "", 5));
        var pool = new FaceEnginePool(props);

        // with one engine allowed, a failed first attempt must not leave the second caller waiting forever
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> pool.use(engine -> 1))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Face models missing");
            }
        });
    }
}
