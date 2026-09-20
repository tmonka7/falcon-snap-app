package com.falcon.snap.engine;

import android.content.Context;

import com.falcon.snap.model.Language;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Locates the model files on storage. Nothing is bundled in the APK and nothing is downloaded:
 * the app runs whatever models it finds here, so a retrained model is deployed by replacing a file.
 *
 * Layout, under any of the {@link #roots} (see MODELS.md):
 * <pre>
 *   models/ocr/det.onnx                        PP-OCRv5 text detection
 *   models/ocr/rec_KEY.onnx + rec_KEY.txt      PP-OCRv5 text recognition + its character dictionary
 *   models/nllb/encoder_model*.onnx            NLLB-200 encoder
 *   models/nllb/decoder_model_merged*.onnx     NLLB-200 decoder (or decoder_model + decoder_with_past_model)
 *   models/nllb/tokenizer.json
 * </pre>
 */
public final class ModelStore {
    public static final String DIR_OCR = "ocr";
    public static final String DIR_NLLB = "nllb";
    public static final String OCR_DET = "det.onnx";
    public static final String TOKENIZER = "tokenizer.json";

    /** Weight formats with float32 inputs/outputs, smallest and fastest first. fp16 I/O is not supported. */
    private static final String[] VARIANT_PREFERENCE = {"_quantized", "_int8", "_uint8", "_q4", "_bnb4", ""};

    private ModelStore() {
    }

    /** The files that make up one NLLB export. */
    public static final class NllbFiles {
        public File encoder;
        /** Merged decoder, or the first-step decoder when {@link #decoderWithPast} is set. */
        public File decoder;
        /** Only for non-merged exports. */
        public File decoderWithPast;
        public File tokenizer;

        public boolean isComplete() {
            return encoder != null && decoder != null && tokenizer != null;
        }
    }

    /**
     * Every folder that is searched, in priority order: the app folder on shared storage, the app
     * folder on each SD card, then private internal storage.
     */
    public static List<File> roots(Context context) {
        List<File> roots = new ArrayList<>();
        for (File dir : context.getExternalFilesDirs(null)) {
            if (dir != null) {
                roots.add(new File(dir, "models"));
            }
        }
        roots.add(new File(context.getFilesDir(), "models"));
        return roots;
    }

    /** Where imported models are written: the first root, created if needed. */
    public static File primaryRoot(Context context) {
        File root = roots(context).get(0);
        if (!root.exists()) {
            root.mkdirs();
        }
        return root;
    }

    public static File detector(Context context) {
        return find(context, DIR_OCR, OCR_DET);
    }

    public static File recognizer(Context context, String key) {
        return find(context, DIR_OCR, "rec_" + key + ".onnx");
    }

    /** May be null even when the recognizer exists: some ONNX exports embed the dictionary instead. */
    public static File dictionary(Context context, String key) {
        return find(context, DIR_OCR, "rec_" + key + ".txt");
    }

    /** The installed recognizer to use for this language, or null when none of its options is installed. */
    public static String recognizerKeyFor(Context context, Language language) {
        for (String key : language.ocrKeys) {
            if (recognizer(context, key) != null) {
                return key;
            }
        }
        return null;
    }

    public static boolean canRead(Context context, Language language) {
        return detector(context) != null && recognizerKeyFor(context, language) != null;
    }

    /** Returns null unless a complete NLLB export (encoder, decoder, tokenizer) is installed. */
    public static NllbFiles nllb(Context context) {
        NllbFiles files = nllbStatus(context);
        return files.isComplete() ? files : null;
    }

    /**
     * What is installed of NLLB, complete or not: the first complete export if there is one,
     * otherwise the first folder that has any of the files. Never null; fields may be.
     */
    public static NllbFiles nllbStatus(Context context) {
        NllbFiles partial = null;
        for (File root : roots(context)) {
            File dir = new File(root, DIR_NLLB);
            NllbFiles files = new NllbFiles();
            files.encoder = pickVariant(dir, "encoder_model");
            files.tokenizer = existing(new File(dir, TOKENIZER));
            files.decoder = pickVariant(dir, "decoder_model_merged");
            if (files.decoder == null) {
                // Non-merged export: the two decoders are only usable as a pair.
                files.decoderWithPast = pickVariant(dir, "decoder_with_past_model");
                files.decoder = files.decoderWithPast == null ? null : pickVariant(dir, "decoder_model");
            }
            if (files.isComplete()) {
                return files;
            }
            if (partial == null && (files.encoder != null || files.decoder != null || files.tokenizer != null)) {
                partial = files;
            }
        }
        return partial != null ? partial : new NllbFiles();
    }

    private static File find(Context context, String dir, String name) {
        for (File root : roots(context)) {
            File file = existing(new File(new File(root, dir), name));
            if (file != null) {
                return file;
            }
        }
        return null;
    }

    /** base + variant + ".onnx", e.g. encoder_model_quantized.onnx, in order of preference. */
    private static File pickVariant(File dir, String base) {
        for (String variant : VARIANT_PREFERENCE) {
            File file = existing(new File(dir, base + variant + ".onnx"));
            if (file != null) {
                return file;
            }
        }
        return null;
    }

    private static File existing(File file) {
        return file.isFile() && file.length() > 0 ? file : null;
    }

    /**
     * Which model folder a file belongs in, judged by its name alone, or null if it is not a model
     * file. Lets the importer accept both a "models/ocr + models/nllb" tree and a flat folder.
     */
    public static String folderFor(String fileName) {
        String name = fileName.toLowerCase(java.util.Locale.ROOT);
        if (name.equals(OCR_DET) || name.startsWith("rec_")) {
            return DIR_OCR;
        }
        if (name.equals(TOKENIZER) || name.startsWith("encoder_model") || name.startsWith("decoder_")) {
            return DIR_NLLB;
        }
        return null;
    }
}
