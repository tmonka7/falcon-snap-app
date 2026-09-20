package com.falcon.snap;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.falcon.snap.data.HistoryDb;
import com.falcon.snap.data.HistoryEntry;
import com.falcon.snap.engine.BitmapUtils;
import com.falcon.snap.model.Language;
import com.falcon.snap.ui.Dialogs;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Saved translations, filterable by language pair. Tap to reopen, long-press to delete. */
public class HistoryActivity extends BaseActivity {
    private static final int MAX_PAIR_CHIPS = 3;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> thumbnails = new LruCache<>(40);
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
    private final HistoryAdapter adapter = new HistoryAdapter();

    private LinearLayout chipRow;
    private View emptyView;
    private List<HistoryEntry> entries = new ArrayList<>();
    /** "src>tgt", or null for All. */
    private String filter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        setupTopBar(R.string.title_history);
        setTopAction(R.drawable.ic_delete, R.string.cd_delete_all, v -> {
            if (!entries.isEmpty()) {
                Dialogs.confirmDelete(this, R.string.delete_all_title, this::deleteAll);
            }
        });

        chipRow = findViewById(R.id.chip_row);
        emptyView = findViewById(R.id.history_empty);
        RecyclerView list = findViewById(R.id.history_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Entries can be re-saved from the result screen, so refresh every time we come back.
        thumbnails.evictAll();
        reload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    private void reload() {
        if (io.isShutdown()) {
            // Posted by a delete that finished after the screen was closed.
            return;
        }
        HistoryDb db = HistoryDb.get(this);
        io.execute(() -> {
            List<HistoryEntry> loaded = db.list();
            main.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                entries = loaded;
                buildChips();
                applyFilter();
            });
        });
    }

    private void deleteAll() {
        HistoryDb db = HistoryDb.get(this);
        io.execute(() -> {
            db.deleteAll();
            main.post(this::reload);
        });
    }

    private void delete(HistoryEntry entry) {
        HistoryDb db = HistoryDb.get(this);
        io.execute(() -> {
            db.delete(entry);
            main.post(this::reload);
        });
    }

    private static String pairKey(HistoryEntry entry) {
        return entry.sourceLang + ">" + entry.targetLang;
    }

    private static String pairLabel(HistoryEntry entry) {
        return Language.byCode(entry.sourceLang).shortName + " → " + Language.byCode(entry.targetLang).shortName;
    }

    /** "All" plus one chip per language pair, in order of first appearance (newest first). */
    private void buildChips() {
        List<String> keys = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (HistoryEntry entry : entries) {
            String key = pairKey(entry);
            if (!keys.contains(key) && keys.size() < MAX_PAIR_CHIPS) {
                keys.add(key);
                labels.add(pairLabel(entry));
            }
        }
        if (filter != null && !keys.contains(filter)) {
            filter = null;
        }
        chipRow.removeAllViews();
        addChip(getString(R.string.filter_all), null);
        for (int i = 0; i < keys.size(); i++) {
            addChip(labels.get(i), keys.get(i));
        }
    }

    private void addChip(String label, @Nullable String key) {
        boolean selected = key == null ? filter == null : key.equals(filter);
        float density = getResources().getDisplayMetrics().density;
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(12);
        chip.setTextColor(ContextCompat.getColor(this, selected ? R.color.white : R.color.text_primary));
        chip.setBackgroundResource(selected ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
        chip.setPadding((int) (16 * density), (int) (7 * density), (int) (16 * density), (int) (7 * density));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd((int) (8 * density));
        chip.setOnClickListener(v -> {
            filter = key;
            buildChips();
            applyFilter();
        });
        chipRow.addView(chip, params);
    }

    private void applyFilter() {
        List<HistoryEntry> visible = new ArrayList<>();
        for (HistoryEntry entry : entries) {
            if (filter == null || filter.equals(pairKey(entry))) {
                visible.add(entry);
            }
        }
        adapter.setEntries(visible);
        emptyView.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void open(HistoryEntry entry) {
        Intent intent = new Intent(this, ResultActivity.class);
        intent.putExtra(ResultActivity.EXTRA_HISTORY_ID, entry.id);
        startActivity(intent);
    }

    /** Decodes the thumbnail in the background; the tag check drops results for recycled rows. */
    private void loadThumbnail(ImageView view, @Nullable String path) {
        view.setTag(path);
        view.setImageBitmap(path == null ? null : thumbnails.get(path));
        if (path == null || thumbnails.get(path) != null) {
            return;
        }
        io.execute(() -> {
            Bitmap bitmap = BitmapUtils.decodeThumbnail(path, 160);
            if (bitmap == null) {
                return;
            }
            main.post(() -> {
                thumbnails.put(path, bitmap);
                if (path.equals(view.getTag())) {
                    view.setImageBitmap(bitmap);
                }
            });
        });
    }

    private final class HistoryAdapter extends RecyclerView.Adapter<EntryHolder> {
        private List<HistoryEntry> visible = new ArrayList<>();

        void setEntries(List<HistoryEntry> visible) {
            this.visible = visible;
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return visible.size();
        }

        @NonNull
        @Override
        public EntryHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new EntryHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_history, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull EntryHolder holder, int position) {
            HistoryEntry entry = visible.get(position);
            holder.original.setText(firstLine(entry.sourceText));
            holder.translated.setText(firstLine(entry.translatedText));
            holder.date.setText(dateFormat.format(new Date(entry.createdAt)));
            holder.pair.setText(pairLabel(entry));
            loadThumbnail(holder.thumb, entry.renderedPath);
            holder.itemView.setOnClickListener(v -> open(entry));
            holder.itemView.setOnLongClickListener(v -> {
                Dialogs.confirmDelete(HistoryActivity.this, R.string.delete_entry_title, () -> delete(entry));
                return true;
            });
        }
    }

    private static String firstLine(@Nullable String text) {
        if (text == null) {
            return "";
        }
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline) + " …";
    }

    private static final class EntryHolder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView original;
        final TextView translated;
        final TextView date;
        final TextView pair;

        EntryHolder(View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.item_thumb);
            original = itemView.findViewById(R.id.item_original);
            translated = itemView.findViewById(R.id.item_translated);
            date = itemView.findViewById(R.id.item_date);
            pair = itemView.findViewById(R.id.item_pair);
        }
    }
}
