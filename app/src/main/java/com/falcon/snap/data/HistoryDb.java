package com.falcon.snap.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Translation history: rows in SQLite, images as files under filesDir/history. */
public final class HistoryDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "history.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "history";

    private static HistoryDb instance;

    private final Context appContext;

    public static synchronized HistoryDb get(Context context) {
        if (instance == null) {
            instance = new HistoryDb(context.getApplicationContext());
        }
        return instance;
    }

    private HistoryDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        appContext = context;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "created_at INTEGER NOT NULL, "
                + "src TEXT NOT NULL, "
                + "tgt TEXT NOT NULL, "
                + "source_text TEXT, "
                + "translated_text TEXT, "
                + "blocks_json TEXT, "
                + "original_path TEXT, "
                + "rendered_path TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    /** Directory the history images live in. */
    public File imageDir() {
        File dir = new File(appContext.getFilesDir(), "history");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /** Inserts the entry, or updates it when it already has an id. Returns the row id. */
    public long save(HistoryEntry entry) {
        ContentValues values = new ContentValues();
        values.put("created_at", entry.createdAt);
        values.put("src", entry.sourceLang);
        values.put("tgt", entry.targetLang);
        values.put("source_text", entry.sourceText);
        values.put("translated_text", entry.translatedText);
        values.put("blocks_json", entry.blocksJson);
        values.put("original_path", entry.originalPath);
        values.put("rendered_path", entry.renderedPath);
        SQLiteDatabase db = getWritableDatabase();
        if (entry.id >= 0) {
            int updated = db.update(TABLE, values, "id = ?", new String[]{String.valueOf(entry.id)});
            if (updated > 0) {
                return entry.id;
            }
        }
        entry.id = db.insert(TABLE, null, values);
        return entry.id;
    }

    public HistoryEntry find(long id) {
        try (Cursor cursor = getReadableDatabase().query(TABLE, null, "id = ?",
                new String[]{String.valueOf(id)}, null, null, null)) {
            return cursor.moveToFirst() ? read(cursor) : null;
        }
    }

    /** Newest first. */
    public List<HistoryEntry> list() {
        List<HistoryEntry> entries = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE, null, null, null, null, null,
                "created_at DESC")) {
            while (cursor.moveToNext()) {
                entries.add(read(cursor));
            }
        }
        return entries;
    }

    public void delete(HistoryEntry entry) {
        getWritableDatabase().delete(TABLE, "id = ?", new String[]{String.valueOf(entry.id)});
        deleteFile(entry.originalPath);
        deleteFile(entry.renderedPath);
    }

    public void deleteAll() {
        for (HistoryEntry entry : list()) {
            delete(entry);
        }
    }

    private static void deleteFile(String path) {
        if (path != null) {
            new File(path).delete();
        }
    }

    private static HistoryEntry read(Cursor cursor) {
        HistoryEntry entry = new HistoryEntry();
        entry.id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
        entry.createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"));
        entry.sourceLang = cursor.getString(cursor.getColumnIndexOrThrow("src"));
        entry.targetLang = cursor.getString(cursor.getColumnIndexOrThrow("tgt"));
        entry.sourceText = cursor.getString(cursor.getColumnIndexOrThrow("source_text"));
        entry.translatedText = cursor.getString(cursor.getColumnIndexOrThrow("translated_text"));
        entry.blocksJson = cursor.getString(cursor.getColumnIndexOrThrow("blocks_json"));
        entry.originalPath = cursor.getString(cursor.getColumnIndexOrThrow("original_path"));
        entry.renderedPath = cursor.getString(cursor.getColumnIndexOrThrow("rendered_path"));
        return entry;
    }
}
