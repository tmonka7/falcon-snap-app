package com.falcon.snap.engine;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.falcon.snap.model.Language;
import com.falcon.snap.model.TextBlockItem;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Offline translation of text blocks with NLLB-200 ({@link NllbTranslator}), using the model files
 * found by {@link ModelStore}. The model (about 1 GB in memory) is loaded on first use and kept
 * until {@link #release()}.
 */
public final class TranslatorEngine {
    public interface Callback {
        /** The model has to be loaded from storage first; this takes several seconds. */
        void onLoadingModel();

        void onProgress(int done, int total);

        void onDone();

        /** A {@link ModelsMissingException} means the NLLB files are not installed. */
        void onError(Exception e);
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Guards the translator against being released while a translation is running. */
    private static final ReentrantLock LOCK = new ReentrantLock();
    private static NllbTranslator translator;
    private static String loadedFingerprint;

    private TranslatorEngine() {
    }

    /**
     * Fills in {@link TextBlockItem#translatedText}. With {@code all} false, blocks that already
     * have a translation (for example one the user typed) are left alone.
     * Must be called on the main thread; callbacks are delivered there too.
     */
    public static void translate(Context context, List<TextBlockItem> blocks, Language source, Language target,
                                 boolean all, Callback callback) {
        List<TextBlockItem> pending = new ArrayList<>();
        for (TextBlockItem block : blocks) {
            if (!all && block.translatedText != null) {
                continue;
            }
            if (!hasLetters(block.sourceText) || source.nllbCode.equals(target.nllbCode)) {
                // Prices, phone numbers, punctuation: nothing to translate, keep the original pixels.
                block.translatedText = block.sourceText;
                continue;
            }
            pending.add(block);
        }
        if (pending.isEmpty()) {
            callback.onDone();
            return;
        }
        List<String> texts = new ArrayList<>();
        for (TextBlockItem block : pending) {
            texts.add(block.sourceText);
        }

        Context appContext = context.getApplicationContext();
        WORKER.execute(() -> {
            LOCK.lock();
            try {
                NllbTranslator nllb = obtainTranslator(appContext, callback);
                if (!nllb.supports(source.nllbCode) || !nllb.supports(target.nllbCode)) {
                    throw new IllegalArgumentException("The installed NLLB tokenizer does not know "
                            + source.nllbCode + " / " + target.nllbCode);
                }
                List<String> results = new ArrayList<>();
                for (int i = 0; i < texts.size(); i++) {
                    results.add(translateText(nllb, texts.get(i), source, target));
                    int done = i + 1;
                    MAIN.post(() -> callback.onProgress(done, texts.size()));
                }
                MAIN.post(() -> {
                    // Blocks belong to the UI thread; only touch them here.
                    for (int i = 0; i < pending.size(); i++) {
                        pending.get(i).translatedText = results.get(i);
                    }
                    callback.onDone();
                });
            } catch (Exception | OutOfMemoryError e) {
                Exception error = e instanceof Exception ? (Exception) e : new RuntimeException(e);
                MAIN.post(() -> callback.onError(error));
            } finally {
                LOCK.unlock();
            }
        });
    }

    /**
     * Frees the translation model if it is idle. Called when the app goes to the background so it
     * does not sit on a gigabyte of memory; the next translation reloads it.
     */
    public static void release() {
        if (LOCK.tryLock()) {
            try {
                if (translator != null) {
                    translator.close();
                    translator = null;
                    loadedFingerprint = null;
                }
            } finally {
                LOCK.unlock();
            }
        }
    }

    /** Caller holds LOCK. Reloads when the files on storage changed, e.g. after a retrained model was copied in. */
    private static NllbTranslator obtainTranslator(Context context, Callback callback) throws Exception {
        ModelStore.NllbFiles files = ModelStore.nllb(context);
        if (files == null) {
            throw new ModelsMissingException("nllb/ (encoder_model, decoder_model_merged, " + ModelStore.TOKENIZER + ")");
        }
        String fingerprint = Onnx.fingerprint(files.encoder) + Onnx.fingerprint(files.decoder)
                + Onnx.fingerprint(files.tokenizer);
        if (translator == null || !fingerprint.equals(loadedFingerprint)) {
            MAIN.post(callback::onLoadingModel);
            if (translator != null) {
                translator.close();
                translator = null;
            }
            translator = new NllbTranslator(files);
            loadedFingerprint = fingerprint;
        }
        return translator;
    }

    /** NLLB is a sentence-level model, so a paragraph is translated sentence by sentence. */
    private static String translateText(NllbTranslator nllb, String text, Language source, Language target)
            throws Exception {
        StringBuilder out = new StringBuilder();
        for (String sentence : splitSentences(text)) {
            String translated = hasLetters(sentence)
                    ? nllb.translate(sentence, source.nllbCode, target.nllbCode) : sentence;
            if (out.length() > 0 && !target.joinsWithoutSpaces()) {
                out.append(' ');
            }
            out.append(translated);
        }
        return out.toString();
    }

    private static List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean cjkStop = c == '。' || c == '！' || c == '？' || c == '；';
            boolean latinStop = (c == '.' || c == '!' || c == '?')
                    && (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1)));
            if (cjkStop || latinStop) {
                addSentence(sentences, text.substring(start, i + 1));
                start = i + 1;
            }
        }
        addSentence(sentences, text.substring(start));
        return sentences;
    }

    private static void addSentence(List<String> sentences, String sentence) {
        String trimmed = sentence.trim();
        if (!trimmed.isEmpty()) {
            sentences.add(trimmed);
        }
    }

    private static boolean hasLetters(String text) {
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (Character.isLetter(codePoint)) {
                return true;
            }
            i += Character.charCount(codePoint);
        }
        return false;
    }
}
