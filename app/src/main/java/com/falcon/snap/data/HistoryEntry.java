package com.falcon.snap.data;

/** One saved translation. */
public final class HistoryEntry {
    public long id = -1;
    public long createdAt;
    /** App-level language codes (see Language.code). */
    public String sourceLang;
    public String targetLang;
    public String sourceText;
    public String translatedText;
    /** Serialized TextBlockItem list, so a saved entry can be re-rendered and edited. */
    public String blocksJson;
    /** Untouched photo; null when "Save Original Image" was off. */
    public String originalPath;
    /** Photo with the text replaced. */
    public String renderedPath;
}
