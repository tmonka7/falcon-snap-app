package com.falcon.snap.engine;

import com.falcon.snap.model.Language;
import com.falcon.snap.model.TextBlockItem;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates text blocks with ML Kit's on-device translator. The language models (about 30 MB
 * each) are downloaded the first time a language is used; after that it works offline.
 */
public final class TranslatorEngine {
    public interface Callback {
        /** A language model has to be downloaded first; this can take a while. */
        void onDownloadingModel();

        void onDone();

        void onError(Exception e);
    }

    private TranslatorEngine() {
    }

    /**
     * Fills in {@link TextBlockItem#translatedText}. With {@code all} false, blocks that already
     * have a translation (for example one the user typed) are left alone.
     * Callbacks are delivered on the main thread.
     */
    public static void translate(List<TextBlockItem> blocks, Language source, Language target,
                                 boolean all, Callback callback) {
        List<TextBlockItem> pending = new ArrayList<>();
        for (TextBlockItem block : blocks) {
            if (!all && block.translatedText != null) {
                continue;
            }
            if (!hasLetters(block.sourceText)) {
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

        // Same ML Kit language on both sides: Simplified <-> Traditional Chinese, or no-op.
        if (source.mlKitCode.equals(target.mlKitCode)) {
            for (TextBlockItem block : pending) {
                block.translatedText = toTargetScript(toModelScript(block.sourceText, source), target);
            }
            callback.onDone();
            return;
        }

        RemoteModelManager models = RemoteModelManager.getInstance();
        Task<Boolean> hasSource = models.isModelDownloaded(new TranslateRemoteModel.Builder(source.mlKitCode).build());
        Task<Boolean> hasTarget = models.isModelDownloaded(new TranslateRemoteModel.Builder(target.mlKitCode).build());
        Tasks.whenAllComplete(hasSource, hasTarget).addOnCompleteListener(ignored -> {
            boolean ready = hasSource.isSuccessful() && Boolean.TRUE.equals(hasSource.getResult())
                    && hasTarget.isSuccessful() && Boolean.TRUE.equals(hasTarget.getResult());
            if (!ready) {
                callback.onDownloadingModel();
            }
            runTranslation(pending, source, target, callback);
        });
    }

    private static void runTranslation(List<TextBlockItem> pending, Language source, Language target,
                                       Callback callback) {
        Translator translator = Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(source.mlKitCode)
                .setTargetLanguage(target.mlKitCode)
                .build());
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(unused -> {
                    List<Task<String>> tasks = new ArrayList<>();
                    for (TextBlockItem block : pending) {
                        tasks.add(translator.translate(toModelScript(block.sourceText, source)));
                    }
                    Tasks.whenAllComplete(tasks).addOnCompleteListener(done -> {
                        int failed = 0;
                        Exception lastError = null;
                        for (int i = 0; i < tasks.size(); i++) {
                            Task<String> task = tasks.get(i);
                            if (task.isSuccessful()) {
                                pending.get(i).translatedText = toTargetScript(task.getResult(), target);
                            } else {
                                failed++;
                                lastError = task.getException();
                            }
                        }
                        translator.close();
                        if (failed == tasks.size()) {
                            callback.onError(lastError != null ? lastError : new RuntimeException("Translation failed"));
                        } else {
                            callback.onDone();
                        }
                    });
                })
                .addOnFailureListener(e -> {
                    translator.close();
                    callback.onError(e);
                });
    }

    /** ML Kit's "zh" model is Simplified Chinese; Traditional input is converted before translating. */
    private static String toModelScript(String text, Language source) {
        return source.isTraditionalChinese() ? ChineseConverter.toSimplified(text) : text;
    }

    private static String toTargetScript(String text, Language target) {
        return target.isTraditionalChinese() ? ChineseConverter.toTraditional(text) : text;
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
