package dev.darshan.spotme.support;

import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;

/** Face tests need the ONNX models from scripts/fetch-models.sh; without them they are skipped, not failed. */
public final class Models {

    private Models() {
    }

    public static Path dir() {
        return Path.of(System.getProperty("spotme.models.dir", "../models"));
    }

    public static void assumePresent() {
        Assumptions.assumeTrue(Files.exists(dir().resolve("face_recognition_sface_2021dec.onnx")),
                "face models missing, run scripts/fetch-models.sh");
    }
}
