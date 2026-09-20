package com.falcon.snap.engine;

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Where a model (or its dictionary) comes from: a file on storage, or an asset bundled in the APK.
 * Only the small OCR models are ever bundled; NLLB is far too large for an APK.
 */
public final class ModelSource {
    private final File file;
    private final String assetName;

    private ModelSource(File file, String assetName) {
        this.file = file;
        this.assetName = assetName;
    }

    /** Returns null for a null file, so lookups can be chained. */
    public static ModelSource of(File file) {
        return file == null ? null : new ModelSource(file, null);
    }

    static ModelSource asset(String assetName) {
        return new ModelSource(null, assetName);
    }

    /** True when the model ships inside the app rather than sitting on storage. */
    public boolean isBundled() {
        return assetName != null;
    }

    public String name() {
        return isBundled() ? assetName : file.getName();
    }

    /** Size in bytes, or -1 when it cannot be determined (a compressed asset). */
    public long size(Context context) {
        if (!isBundled()) {
            return file.length();
        }
        try (AssetFileDescriptor descriptor = context.getAssets().openFd(assetName)) {
            return descriptor.getLength();
        } catch (IOException e) {
            return -1;
        }
    }

    /** Identifies one version of the model, so a replaced (retrained) file is reloaded. */
    String fingerprint() {
        return isBundled() ? "asset:" + assetName : Onnx.fingerprint(file);
    }

    InputStream open(Context context) throws IOException {
        return isBundled() ? context.getAssets().open(assetName) : new FileInputStream(file);
    }

    OrtSession openSession(Context context) throws OrtException, IOException {
        if (!isBundled()) {
            return Onnx.open(file);
        }
        // An asset has no file path, so ONNX Runtime gets the bytes. Fine for OCR-sized models.
        try (InputStream in = open(context)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.max(1 << 20, in.available()));
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) {
                bytes.write(buffer, 0, read);
            }
            return Onnx.open(bytes.toByteArray());
        }
    }
}
