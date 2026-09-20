package com.falcon.snap;

import android.app.Application;

import com.falcon.snap.engine.OcrEngine;
import com.falcon.snap.engine.TranslatorEngine;

public class App extends Application {
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // The NLLB model holds about 1 GB of native memory. Once the app is in the background, give
        // it back instead of getting the whole process killed; the next translation reloads it.
        if (level >= TRIM_MEMORY_BACKGROUND) {
            TranslatorEngine.release();
            OcrEngine.release();
        }
    }
}
