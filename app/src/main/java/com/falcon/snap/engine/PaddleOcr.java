package com.falcon.snap.engine;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/**
 * PaddleOCR PP-OCRv5 on ONNX Runtime: text detection, then recognition of each detected line.
 * Pre- and post-processing follow the models' inference.yml, so any PP-OCRv5 det/rec model
 * (including a retrained one) exported to ONNX works unchanged.
 *
 * Not thread-safe; {@link OcrEngine} calls it from a single worker thread.
 */
final class PaddleOcr {
    /** One recognized line. corners = tl, tr, br, bl of the text, in bitmap pixels. */
    static final class Line {
        float[] corners;
        String text;
        float confidence;
    }

    /** Detection runs on a copy whose longest side is at most this (rounded to a multiple of 32). */
    private static final int DET_MAX_SIDE = 1280;
    private static final float[] DET_MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] DET_STD = {0.229f, 0.224f, 0.225f};

    private static final int REC_HEIGHT = 48;
    /** Narrow crops are padded up to this width, as in PaddleOCR (rec_image_shape 3,48,320). */
    private static final int REC_MIN_WIDTH = 320;
    private static final int REC_MAX_WIDTH = 2400;
    private static final float REC_MIN_CONFIDENCE = 0.5f;
    /** Embedded-dictionary key used by ONNX exports that carry their character list in metadata. */
    private static final String METADATA_DICTIONARY = "character";

    private OrtSession detector;
    private String detectorFingerprint;
    private OrtSession recognizer;
    private String recognizerFingerprint;
    /** CTC classes: index 0 is the blank, then the dictionary, then (usually) the space. */
    private List<String> classes;

    List<Line> run(Bitmap bitmap, File detFile, File recFile, File dictFile) throws OrtException, IOException {
        load(detFile, recFile, dictFile);
        List<Line> lines = new ArrayList<>();
        for (float[] quad : detect(bitmap)) {
            Line line = recognize(bitmap, quad);
            if (line != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    void release() {
        close(detector);
        close(recognizer);
        detector = null;
        recognizer = null;
        detectorFingerprint = null;
        recognizerFingerprint = null;
    }

    private void load(File detFile, File recFile, File dictFile) throws OrtException, IOException {
        if (!Onnx.fingerprint(detFile).equals(detectorFingerprint)) {
            close(detector);
            detector = Onnx.open(detFile);
            detectorFingerprint = Onnx.fingerprint(detFile);
        }
        String wanted = Onnx.fingerprint(recFile) + (dictFile == null ? "" : Onnx.fingerprint(dictFile));
        if (!wanted.equals(recognizerFingerprint)) {
            close(recognizer);
            recognizer = Onnx.open(recFile);
            classes = loadClasses(recognizer, dictFile);
            recognizerFingerprint = wanted;
        }
    }

    private static void close(OrtSession session) {
        if (session != null) {
            try {
                session.close();
            } catch (OrtException ignored) {
                // Nothing useful to do; the native memory is released with the process at worst.
            }
        }
    }

    private static List<String> loadClasses(OrtSession session, File dictFile) throws OrtException, IOException {
        List<String> classes = new ArrayList<>();
        classes.add("");
        if (dictFile != null) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(dictFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Do not trim: some entries are whitespace characters (the first is U+3000).
                    if (!line.isEmpty()) {
                        classes.add(line);
                    }
                }
            }
        } else {
            Map<String, String> metadata = session.getMetadata().getCustomMetadata();
            String embedded = metadata.get(METADATA_DICTIONARY);
            if (embedded == null) {
                throw new IOException("No character dictionary: add rec_KEY.txt next to the recognition model");
            }
            Collections.addAll(classes, embedded.split("\n"));
        }
        return classes;
    }

    // ---------------------------------------------------------------- detection

    private List<float[]> detect(Bitmap bitmap) throws OrtException {
        float scale = Math.min(1f, DET_MAX_SIDE / (float) Math.max(bitmap.getWidth(), bitmap.getHeight()));
        int width = roundTo32(bitmap.getWidth() * scale);
        int height = roundTo32(bitmap.getHeight() * scale);
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
        int[] pixels = new int[width * height];
        scaled.getPixels(pixels, 0, width, 0, 0, width, height);
        if (scaled != bitmap) {
            scaled.recycle();
        }

        // NCHW, channels in B, G, R order: PaddleOCR decodes images as BGR and applies the
        // ImageNet mean/std to the channels in that order.
        int plane = width * height;
        FloatBuffer input = FloatBuffer.allocate(3 * plane);
        for (int i = 0; i < plane; i++) {
            int pixel = pixels[i];
            input.put(i, ((pixel & 0xFF) / 255f - DET_MEAN[0]) / DET_STD[0]);
            input.put(plane + i, (((pixel >> 8) & 0xFF) / 255f - DET_MEAN[1]) / DET_STD[1]);
            input.put(2 * plane + i, (((pixel >> 16) & 0xFF) / 255f - DET_MEAN[2]) / DET_STD[2]);
        }

        String inputName = detector.getInputNames().iterator().next();
        try (OnnxTensor tensor = OnnxTensor.createTensor(Onnx.env(), input, new long[]{1, 3, height, width});
             OrtSession.Result result = detector.run(Collections.singletonMap(inputName, tensor))) {
            OnnxTensor output = (OnnxTensor) result.get(0);
            long[] shape = output.getInfo().getShape();
            int mapH = (int) shape[shape.length - 2];
            int mapW = (int) shape[shape.length - 1];
            float[] prob = new float[mapW * mapH];
            output.getFloatBuffer().get(prob);
            return DbPostProcessor.boxes(prob, mapW, mapH,
                    bitmap.getWidth() / (float) mapW, bitmap.getHeight() / (float) mapH,
                    bitmap.getWidth(), bitmap.getHeight());
        }
    }

    private static int roundTo32(float value) {
        return Math.max(32, Math.round(value / 32f) * 32);
    }

    // ---------------------------------------------------------------- recognition

    private Line recognize(Bitmap bitmap, float[] quad) throws OrtException {
        float lineWidth = (float) Math.hypot(quad[2] - quad[0], quad[3] - quad[1]);
        float lineHeight = (float) Math.hypot(quad[6] - quad[0], quad[7] - quad[1]);
        if (lineWidth < 2f || lineHeight < 2f) {
            return null;
        }
        int cropWidth = Math.max(8, Math.min(REC_MAX_WIDTH, (int) Math.ceil(REC_HEIGHT * lineWidth / lineHeight)));
        int tensorWidth = Math.max(REC_MIN_WIDTH, cropWidth);

        // Perspective-warp the (possibly rotated) quad straight into a 48 px high strip.
        Bitmap crop = Bitmap.createBitmap(cropWidth, REC_HEIGHT, Bitmap.Config.ARGB_8888);
        Matrix warp = new Matrix();
        float[] target = {0, 0, cropWidth, 0, cropWidth, REC_HEIGHT, 0, REC_HEIGHT};
        warp.setPolyToPoly(quad, 0, target, 0, 4);
        new Canvas(crop).drawBitmap(bitmap, warp, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        int[] pixels = new int[cropWidth * REC_HEIGHT];
        crop.getPixels(pixels, 0, cropWidth, 0, 0, cropWidth, REC_HEIGHT);
        crop.recycle();

        // BGR, scaled to [-1, 1]. The padding on the right stays 0, as in PaddleOCR.
        int plane = tensorWidth * REC_HEIGHT;
        FloatBuffer input = FloatBuffer.allocate(3 * plane);
        for (int y = 0; y < REC_HEIGHT; y++) {
            for (int x = 0; x < cropWidth; x++) {
                int pixel = pixels[y * cropWidth + x];
                int at = y * tensorWidth + x;
                input.put(at, (pixel & 0xFF) / 127.5f - 1f);
                input.put(plane + at, ((pixel >> 8) & 0xFF) / 127.5f - 1f);
                input.put(2 * plane + at, ((pixel >> 16) & 0xFF) / 127.5f - 1f);
            }
        }

        String inputName = recognizer.getInputNames().iterator().next();
        try (OnnxTensor tensor = OnnxTensor.createTensor(Onnx.env(), input, new long[]{1, 3, REC_HEIGHT, tensorWidth});
             OrtSession.Result result = recognizer.run(Collections.singletonMap(inputName, tensor))) {
            OnnxTensor output = (OnnxTensor) result.get(0);
            TensorInfo info = output.getInfo();
            long[] shape = info.getShape();
            int steps = (int) shape[shape.length - 2];
            int classCount = (int) shape[shape.length - 1];
            float[] scores = new float[steps * classCount];
            output.getFloatBuffer().get(scores);

            Line line = decodeCtc(scores, steps, classCount);
            if (line == null) {
                return null;
            }
            line.corners = quad;
            return line;
        }
    }

    /** Greedy CTC: best class per step, collapse repeats, drop blanks. */
    private Line decodeCtc(float[] scores, int steps, int classCount) {
        StringBuilder text = new StringBuilder();
        float confidenceSum = 0f;
        int characters = 0;
        int previous = 0;
        for (int t = 0; t < steps; t++) {
            int best = 0;
            float bestScore = scores[t * classCount];
            for (int c = 1; c < classCount; c++) {
                float score = scores[t * classCount + c];
                if (score > bestScore) {
                    bestScore = score;
                    best = c;
                }
            }
            if (best != 0 && best != previous) {
                text.append(classAt(best, classCount));
                confidenceSum += bestScore;
                characters++;
            }
            previous = best;
        }
        String result = text.toString().trim();
        if (result.isEmpty() || confidenceSum / characters < REC_MIN_CONFIDENCE) {
            return null;
        }
        Line line = new Line();
        line.text = result;
        line.confidence = confidenceSum / characters;
        return line;
    }

    private String classAt(int index, int classCount) {
        if (index < classes.size()) {
            return classes.get(index);
        }
        // PaddleOCR appends the space as the last class (use_space_char) without listing it in the dictionary.
        return index == classCount - 1 ? " " : "";
    }
}
