package dev.darshan.spotme.face;

import nu.pattern.OpenCV;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.FaceDetectorYN;
import org.opencv.objdetect.FaceRecognizerSF;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Detect -> align -> embed, using OpenCV's YuNet detector and SFace recognizer.
 *
 * <p>An instance is NOT thread-safe (the OpenCV nets keep internal buffers), so every
 * worker thread borrows its own instance from {@link FaceEnginePool}.
 */
public class FaceEngine {

    /** Bump this when the models or the preprocessing change. Vectors from different versions are never compared. */
    public static final String MODEL_VERSION = "yunet-2023mar+sface-2021dec/p1";
    public static final int EMBEDDING_SIZE = 128;

    private static volatile boolean nativeLoaded = false;

    private final FaceDetectorYN detector;
    private final FaceRecognizerSF recognizer;
    private final int maxSide;
    private final int minFaceSize;

    public FaceEngine(Path modelsDir, float scoreThreshold, int maxSide, int minFaceSize) {
        loadNative();
        Path yunet = modelsDir.resolve("face_detection_yunet_2023mar.onnx");
        Path sface = modelsDir.resolve("face_recognition_sface_2021dec.onnx");
        // input size is reset for every image in detect()
        this.detector = FaceDetectorYN.create(yunet.toString(), "", new Size(320, 320), scoreThreshold, 0.3f, 5000);
        this.recognizer = FaceRecognizerSF.create(sface.toString(), "");
        this.maxSide = maxSide;
        this.minFaceSize = minFaceSize;
    }

    public static synchronized void loadNative() {
        if (!nativeLoaded) {
            OpenCV.loadLocally();
            nativeLoaded = true;
        }
    }

    /** Limits OpenCV's own thread pool for this process (0 = OpenCV default). */
    public static void setOpenCvThreads(int threads) {
        loadNative();
        if (threads > 0) {
            Core.setNumThreads(threads);
        }
    }

    /**
     * Finds every face in a BGR image and returns one embedding per face.
     * Large photos are scaled down first: detection cost grows with pixel count and
     * faces in event photos are still big enough at this size.
     */
    public List<DetectedFace> detectAndEmbed(Mat bgr) {
        double scale = Math.min(1.0, (double) maxSide / Math.max(bgr.cols(), bgr.rows()));
        Mat work = bgr;
        if (scale < 1.0) {
            work = new Mat();
            Imgproc.resize(bgr, work, new Size(Math.round(bgr.cols() * scale), Math.round(bgr.rows() * scale)),
                    0, 0, Imgproc.INTER_AREA);
        }
        Mat faces = new Mat();
        Mat aligned = new Mat();
        Mat feature = new Mat();
        List<DetectedFace> result = new ArrayList<>();
        try {
            detector.setInputSize(new Size(work.cols(), work.rows()));
            detector.detect(work, faces);
            // each row: x, y, w, h, 5 landmark points (10 values), score
            for (int i = 0; i < faces.rows(); i++) {
                Mat row = faces.row(i);
                float[] v = new float[15];
                row.get(0, 0, v);
                if (v[2] < minFaceSize || v[3] < minFaceSize) {
                    continue; // too small to match reliably
                }
                recognizer.alignCrop(work, row, aligned);
                recognizer.feature(aligned, feature);
                float[] emb = new float[EMBEDDING_SIZE];
                feature.get(0, 0, emb);
                normalize(emb);
                result.add(new DetectedFace(
                        (int) Math.round(v[0] / scale), (int) Math.round(v[1] / scale),
                        (int) Math.round(v[2] / scale), (int) Math.round(v[3] / scale),
                        v[14], emb));
            }
        } finally {
            faces.release();
            aligned.release();
            feature.release();
            if (work != bgr) {
                work.release();
            }
        }
        return result;
    }

    static void normalize(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += x * x;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0) {
            return;
        }
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) (v[i] / norm);
        }
    }

    /** Cosine similarity of two normalised vectors (1 = same direction). */
    public static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }
}
