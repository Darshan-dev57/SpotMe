package dev.darshan.spotme.face;

import dev.darshan.spotme.config.SpotMeProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.function.Function;

/**
 * A fixed number of FaceEngine instances shared by worker threads and selfie searches.
 * This caps how much CPU and memory face inference can use at the same time.
 * Engines are created on first use, so the app starts fast and tests without models still run.
 */
@Component
public class FaceEnginePool {

    private final SpotMeProperties props;
    private final BlockingQueue<FaceEngine> idle;
    private int created = 0;

    public FaceEnginePool(SpotMeProperties props) {
        this.props = props;
        this.idle = new ArrayBlockingQueue<>(props.face().engines());
    }

    public <T> T use(Function<FaceEngine, T> work) {
        FaceEngine engine = borrow();
        try {
            return work.apply(engine);
        } finally {
            idle.offer(engine);
        }
    }

    private FaceEngine borrow() {
        FaceEngine engine = idle.poll();
        if (engine != null) {
            return engine;
        }
        synchronized (this) {
            if (created < props.face().engines()) {
                FaceEngine fresh = newEngine(); // count it only once it exists, so a failure can't use up a slot
                created++;
                return fresh;
            }
        }
        try {
            return idle.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a face engine", e);
        }
    }

    private FaceEngine newEngine() {
        Path dir = Path.of(props.modelsDir());
        if (!Files.exists(dir.resolve("face_recognition_sface_2021dec.onnx"))) {
            throw new IllegalStateException("Face models missing in " + dir.toAbsolutePath()
                    + ". Run scripts/fetch-models.sh first.");
        }
        FaceEngine.setOpenCvThreads(props.face().opencvThreads());
        return new FaceEngine(dir, props.face().detectScore(), props.face().maxSide(), props.face().minFaceSize());
    }
}
