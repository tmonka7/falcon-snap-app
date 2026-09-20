package com.falcon.snap.engine;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;

import java.io.BufferedReader;
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
 * PaddleOCR (PP-OCRv6, and PP-OCRv5: same input format) on ONNX Runtime: text detection, then
 * recognition of each detected line. Pre- and post-processing follow the models' inference.yml, so
 * a retrained det/rec model exported to ONNX works unchanged.
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

    /** @param dictionary may be null when the recognizer carries its character list in its ONNX metadata */
    List<Line> run(Context context, Bitmap bitmap, ModelSource detectorModel, ModelSource recognizerModel,
                   ModelSource dictionary) throws OrtException, IOException {
        load(context, detectorModel, recognizerModel, dictionary);
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

    private void load(Context context, ModelSource detectorModel, ModelSource recognizerModel,
                      ModelSource dictionary) throws OrtException, IOException {
        if (!detectorModel.fingerprint().equals(detectorFingerprint)) {
            // Forget the old session first, so a failed load cannot leave a closed one marked as current.
            close(detector);
            detector = null;
            detectorFingerprint = null;
            detector = detectorModel.openSession(context);
            detectorFingerprint = detectorModel.fingerprint();
        }
        String wanted = recognizerModel.fingerprint() + (dictionary == null ? "" : dictionary.fingerprint());
        if (!wanted.equals(recognizerFingerprint)) {
            close(recognizer);
            recognizer = null;
            recognizerFingerprint = null;
            recognizer = recognizerModel.openSession(context);
            classes = loadClasses(context, recognizer, dictionary);
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

    private static List<String> loadClasses(Context context, OrtSession session, ModelSource dictionary)
            throws OrtException, IOException {
        List<String> classes = new ArrayList<>();
        classes.add("");
        if (dictionary != null) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(dictionary.open(context), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (classes.size() == 1 && !line.isEmpty() && line.charAt(0) == 0xFEFF) {
                        // A byte-order mark added by a Windows editor would otherwise become part of the first character.
                        line = line.substring(1);
                    }
                    // Do not trim: some dictionaries contain whitespace characters (PP-OCRv5's first entry is U+3000).
                    if (!line.isEmpty()) {
                        classes.add(line);
                    }
                }
            }
        } else {
            Map<String, String> metadata = session.getMetadata().getCustomMetadata();
            String embedded = metadata.get(METADATA_DICTIONARY);
            if (embedded == null) {
                throw new IOException("No character dictionary: put a .txt with the same name next to the recognition model");
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
            sigmoidIfLogits(prob);
            return DbPostProcessor.boxes(prob, mapW, mapH,
                    bitmap.getWidth() / (float) mapW, bitmap.getHeight() / (float) mapH,
                    bitmap.getWidth(), bitmap.getHeight());
        }
    }

    /**
     * Paddle's own export ends in a sigmoid, so the map holds probabilities. Exports made another way
     * (for example from a safetensors checkpoint) may stop at the logits; values outside 0..1 give that away.
     */
    private static void sigmoidIfLogits(float[] map) {
        boolean logits = false;
        for (float value : map) {
            if (value < 0f || value > 1f) {
                logits = true;
                break;
            }
        }
        if (logits) {
            for (int i = 0; i < map.length; i++) {
                map[i] = (float) (1.0 / (1.0 + Math.exp(-map[i])));
            }
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
            checkDictionaryFits(classCount);

            Line line = decodeCtc(scores, steps, classCount);
            if (line == null) {
                return null;
            }
            line.corners = quad;
            return line;
        }
    }

    /**
     * The model's class count must be blank + dictionary (+ space). A dictionary from another model
     * generation (PP-OCRv5 has 18383 characters, PP-OCRv6 18708) would silently decode to the wrong
     * characters, so refuse it with a message that says what is wrong.
     */
    private void checkDictionaryFits(int classCount) {
        int dictionarySize = classes.size() - 1;
        if (classCount != dictionarySize + 1 && classCount != dictionarySize + 2) {
            throw new IllegalStateException("The recognition model has " + classCount + " classes but its dictionary has "
                    + dictionarySize + " characters (expected " + (classCount - 2) + "). Use the dictionary that belongs to this model.");
        }
    }

    /** Greedy CTC: best class per step, collapse repeats, drop blanks. */
    private Line decodeCtc(float[] scores, int steps, int classCount) {
        // Paddle's export ends in a softmax; other exports may return logits. Rows of probabilities sum to 1.
        float firstRowSum = 0f;
        for (int c = 0; c < classCount; c++) {
            firstRowSum += scores[c];
        }
        boolean probabilities = Math.abs(firstRowSum - 1f) < 0.02f;

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
                confidenceSum += probabilities ? bestScore : softmaxOfBest(scores, t * classCount, classCount, bestScore);
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

    /** Softmax probability of the largest logit in one row: 1 / sum(exp(x - max)). */
    private static float softmaxOfBest(float[] scores, int offset, int classCount, float best) {
        double sum = 0;
        for (int c = 0; c < classCount; c++) {
            sum += Math.exp(scores[offset + c] - best);
        }
        return (float) (1.0 / sum);
    }

    private String classAt(int index, int classCount) {
        if (index < classes.size()) {
            return classes.get(index);
        }
        // PaddleOCR appends the space as the last class (use_space_char) without listing it in the dictionary.
        return index == classCount - 1 ? " " : "";
    }
}
