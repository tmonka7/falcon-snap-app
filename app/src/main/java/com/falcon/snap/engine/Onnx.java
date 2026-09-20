package com.falcon.snap.engine;

import java.io.File;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/** Shared ONNX Runtime environment and session creation. */
final class Onnx {
    private Onnx() {
    }

    static OrtEnvironment env() {
        return OrtEnvironment.getEnvironment();
    }

    /** Loads a model straight from its file, so large models are never copied through the Java heap. */
    static OrtSession open(File model) throws OrtException {
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            // Big cores only: more threads than that makes int8 matmuls slower, not faster.
            options.setIntraOpNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
            return env().createSession(model.getAbsolutePath(), options);
        }
    }

    /** Identifies one version of a model file, so a replaced (retrained) file is reloaded. */
    static String fingerprint(File file) {
        return file.getAbsolutePath() + ':' + file.length() + ':' + file.lastModified();
    }
}
