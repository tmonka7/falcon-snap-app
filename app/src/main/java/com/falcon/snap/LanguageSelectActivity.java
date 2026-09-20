package com.falcon.snap;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.falcon.snap.engine.ModelStore;
import com.falcon.snap.model.Language;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Picks the source or the target language. The choice is written to prefs before finishing with
 * RESULT_OK. When picking a source, languages whose OCR model is not installed are marked.
 */
public class LanguageSelectActivity extends BaseActivity {
    public static final String EXTRA_PICK_SOURCE = "pick_source";

    private boolean pickSource;
    private Language current;
    /** Codes of the languages whose OCR model is installed. */
    private final Set<String> readableCodes = new HashSet<>();
    private final LanguageAdapter adapter = new LanguageAdapter();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_language_select);
        setupTopBar(R.string.title_select_language);

        pickSource = getIntent().getBooleanExtra(EXTRA_PICK_SOURCE, true);
        current = pickSource ? prefs.source() : prefs.target();
        for (Language language : Language.all()) {
            if (ModelStore.canRead(this, language)) {
                readableCodes.add(language.code);
            }
        }
        TextView subtitle = findViewById(R.id.top_subtitle);
        subtitle.setText(pickSource ? R.string.subtitle_translate_from : R.string.subtitle_translate_to);
        subtitle.setVisibility(View.VISIBLE);

        RecyclerView list = findViewById(R.id.language_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        EditText search = findViewById(R.id.search_input);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                showLanguages(s.toString());
            }
        });
        showLanguages("");
    }

    /** Rows are either a String (section header) or a Language. */
    private void showLanguages(String query) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<Object> rows = new ArrayList<>();
        if (needle.isEmpty()) {
            List<Language> recents = prefs.recents();
            if (!recents.isEmpty()) {
                rows.add(getString(R.string.recent_languages));
                rows.addAll(recents);
            }
        }
        List<Language> matches = new ArrayList<>();
        for (Language language : Language.all()) {
            boolean matchesQuery = needle.isEmpty()
                    || language.name.toLowerCase(Locale.ROOT).contains(needle)
                    || language.shortName.toLowerCase(Locale.ROOT).contains(needle);
            if (matchesQuery) {
                matches.add(language);
            }
        }
        if (!matches.isEmpty()) {
            rows.add(getString(R.string.all_languages));
            rows.addAll(matches);
        }
        adapter.setRows(rows);
    }

    private void select(Language picked) {
        Language source = prefs.source();
        Language target = prefs.target();
        if (pickSource) {
            // Picking the current target as the source means "swap".
            if (picked.code.equals(target.code)) {
                target = source;
            }
            source = picked;
        } else {
            if (picked.code.equals(source.code)) {
                source = target;
            }
            target = picked;
        }
        prefs.setLanguages(source, target);
        prefs.addRecent(picked);
        setResult(RESULT_OK);
        finish();
    }

    private final class LanguageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_HEADER = 0;
        private static final int TYPE_LANGUAGE = 1;

        private List<Object> rows = new ArrayList<>();

        void setRows(List<Object> rows) {
            this.rows = rows;
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof Language ? TYPE_LANGUAGE : TYPE_HEADER;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HEADER) {
                return new HeaderHolder(inflater.inflate(R.layout.item_language_header, parent, false));
            }
            return new LanguageHolder(inflater.inflate(R.layout.item_language, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object row = rows.get(position);
            if (holder instanceof HeaderHolder) {
                ((HeaderHolder) holder).text.setText((String) row);
                return;
            }
            Language language = (Language) row;
            LanguageHolder h = (LanguageHolder) holder;
            boolean selected = language.code.equals(current.code);
            h.flag.setText(language.flag());
            // A source language needs an installed OCR model; say so rather than hiding the language.
            boolean readable = !pickSource || readableCodes.contains(language.code);
            h.name.setText(readable ? language.name : language.name + "  ·  " + getString(R.string.ocr_model_missing));
            h.name.setAlpha(readable ? 1f : 0.5f);
            h.radio.setChecked(selected);
            if (selected) {
                h.itemView.setBackgroundResource(R.drawable.bg_row_selected);
            } else {
                h.itemView.setBackground(null);
            }
            h.itemView.setOnClickListener(v -> select(language));
        }
    }

    private static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView text;

        HeaderHolder(View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.header_text);
        }
    }

    private static final class LanguageHolder extends RecyclerView.ViewHolder {
        final TextView flag;
        final TextView name;
        final RadioButton radio;

        LanguageHolder(View itemView) {
            super(itemView);
            flag = itemView.findViewById(R.id.lang_flag);
            name = itemView.findViewById(R.id.lang_name);
            radio = itemView.findViewById(R.id.lang_radio);
        }
    }
}
