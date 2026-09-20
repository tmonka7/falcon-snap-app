package com.falcon.snap.engine;

import android.util.JsonReader;
import android.util.JsonToken;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The NLLB-200 tokenizer, read from the HuggingFace {@code tokenizer.json}: a SentencePiece-style
 * BPE ("▁" marks a word start) plus one special token per language, e.g. {@code eng_Latn}.
 *
 * The file is 17 MB with 256k vocabulary entries, so it is parsed as a stream, and merges are kept
 * in a primitive hash map instead of 250k boxed entries.
 */
final class NllbTokenizer {
    static final int EOS = 2;
    private static final int UNK = 3;
    private static final char WORD_START = '▁';

    private final Map<String, Integer> vocab = new HashMap<>(400_000);
    private final Map<String, Integer> specialTokens = new HashMap<>();
    private String[] pieces;
    /** Ids of the added tokens (language codes etc.), which may or may not also be listed in the vocab. */
    private boolean[] special;
    /** (left id, right id) -> merge rank; the id produced by that merge is mergedIds[rank]. */
    private MergeTable merges;
    private int[] mergedIds;
    private final Map<String, int[]> wordCache = new HashMap<>();

    static NllbTokenizer load(File tokenizerJson) throws IOException {
        NllbTokenizer tokenizer = new NllbTokenizer();
        List<String> mergeLeft = new ArrayList<>();
        List<String> mergeRight = new ArrayList<>();
        try (JsonReader reader = new JsonReader(new BufferedReader(
                new InputStreamReader(new FileInputStream(tokenizerJson), StandardCharsets.UTF_8), 1 << 16))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (name.equals("added_tokens")) {
                    tokenizer.readAddedTokens(reader);
                } else if (name.equals("model")) {
                    tokenizer.readModel(reader, mergeLeft, mergeRight);
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
        }
        tokenizer.index(mergeLeft, mergeRight);
        return tokenizer;
    }

    private NllbTokenizer() {
    }

    // ---------------------------------------------------------------- loading

    private void readAddedTokens(JsonReader reader) throws IOException {
        reader.beginArray();
        while (reader.hasNext()) {
            int id = -1;
            String content = null;
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (name.equals("id")) {
                    id = reader.nextInt();
                } else if (name.equals("content")) {
                    content = reader.nextString();
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
            if (id >= 0 && content != null) {
                specialTokens.put(content, id);
            }
        }
        reader.endArray();
    }

    private void readModel(JsonReader reader, List<String> mergeLeft, List<String> mergeRight) throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (name.equals("type")) {
                String type = reader.nextString();
                if (!"BPE".equals(type)) {
                    throw new IOException("Unsupported tokenizer model '" + type + "': NLLB uses BPE");
                }
            } else if (name.equals("vocab")) {
                reader.beginObject();
                while (reader.hasNext()) {
                    vocab.put(reader.nextName(), reader.nextInt());
                }
                reader.endObject();
            } else if (name.equals("merges")) {
                reader.beginArray();
                while (reader.hasNext()) {
                    // Older files store "left right", newer ones ["left", "right"].
                    if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray();
                        mergeLeft.add(reader.nextString());
                        mergeRight.add(reader.nextString());
                        reader.endArray();
                    } else {
                        String merge = reader.nextString();
                        int space = merge.indexOf(' ');
                        mergeLeft.add(merge.substring(0, space));
                        mergeRight.add(merge.substring(space + 1));
                    }
                }
                reader.endArray();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
    }

    private void index(List<String> mergeLeft, List<String> mergeRight) throws IOException {
        if (vocab.isEmpty() || mergeLeft.isEmpty()) {
            throw new IOException("tokenizer.json has no BPE vocabulary");
        }
        int maxId = 0;
        for (int id : vocab.values()) {
            maxId = Math.max(maxId, id);
        }
        for (int id : specialTokens.values()) {
            maxId = Math.max(maxId, id);
        }
        pieces = new String[maxId + 1];
        special = new boolean[maxId + 1];
        for (int id : specialTokens.values()) {
            special[id] = true;
        }
        for (Map.Entry<String, Integer> entry : vocab.entrySet()) {
            pieces[entry.getValue()] = entry.getKey();
        }

        merges = new MergeTable(mergeLeft.size());
        mergedIds = new int[mergeLeft.size()];
        for (int rank = 0; rank < mergeLeft.size(); rank++) {
            Integer left = vocab.get(mergeLeft.get(rank));
            Integer right = vocab.get(mergeRight.get(rank));
            Integer merged = vocab.get(mergeLeft.get(rank) + mergeRight.get(rank));
            if (left != null && right != null && merged != null) {
                merges.put(key(left, right), rank);
                mergedIds[rank] = merged;
            }
        }
    }

    // ---------------------------------------------------------------- encoding

    /** Id of a language token such as "eng_Latn", or -1 if this tokenizer does not know it. */
    int languageId(String nllbCode) {
        Integer id = specialTokens.get(nllbCode);
        return id == null ? -1 : id;
    }

    /** True for &lt;s&gt;, &lt;pad&gt;, &lt;/s&gt;, &lt;unk&gt;, language codes and &lt;mask&gt;: never part of the text. */
    boolean isSpecial(int id) {
        return id < 4 || id >= pieces.length || special[id] || pieces[id] == null;
    }

    /** Token ids of the text alone: no language code and no end-of-sentence token. */
    int[] encode(String text) {
        // SentencePiece's nmt_nfkc normalization, approximated: NFKC, then whitespace collapsed.
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("[\\s\\p{Cntrl}]+", " ").trim();
        int[] out = new int[normalized.length() + 8];
        int size = 0;
        for (String word : normalized.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            int[] ids = encodeWord(WORD_START + word);
            if (size + ids.length > out.length) {
                out = Arrays.copyOf(out, Math.max(out.length * 2, size + ids.length));
            }
            System.arraycopy(ids, 0, out, size, ids.length);
            size += ids.length;
        }
        return Arrays.copyOf(out, size);
    }

    private int[] encodeWord(String word) {
        int[] cached = wordCache.get(word);
        if (cached != null) {
            return cached;
        }
        // Start from single characters, then repeatedly apply the best-ranked merge.
        int[] symbols = new int[word.codePointCount(0, word.length())];
        int count = 0;
        for (int i = 0; i < word.length(); ) {
            int codePoint = word.codePointAt(i);
            Integer id = vocab.get(new String(Character.toChars(codePoint)));
            symbols[count++] = id == null ? UNK : id;
            i += Character.charCount(codePoint);
        }
        while (count > 1) {
            int bestRank = Integer.MAX_VALUE;
            int bestAt = -1;
            for (int i = 0; i < count - 1; i++) {
                int rank = merges.get(key(symbols[i], symbols[i + 1]));
                if (rank >= 0 && rank < bestRank) {
                    bestRank = rank;
                    bestAt = i;
                }
            }
            if (bestAt < 0) {
                break;
            }
            symbols[bestAt] = mergedIds[bestRank];
            System.arraycopy(symbols, bestAt + 2, symbols, bestAt + 1, count - bestAt - 2);
            count--;
        }
        // fuse_unk: a run of unknown characters becomes a single <unk>.
        int[] ids = new int[count];
        int size = 0;
        for (int i = 0; i < count; i++) {
            if (symbols[i] != UNK || size == 0 || ids[size - 1] != UNK) {
                ids[size++] = symbols[i];
            }
        }
        ids = Arrays.copyOf(ids, size);
        if (wordCache.size() < 5000) {
            wordCache.put(word, ids);
        }
        return ids;
    }

    String decode(List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            if (!isSpecial(id)) {
                sb.append(pieces[id]);
            }
        }
        return sb.toString().replace(WORD_START, ' ').trim();
    }

    private static long key(int left, int right) {
        return ((long) left << 32) | (right & 0xFFFFFFFFL);
    }

    /** Open-addressing long -> int map. Returns -1 for a missing key. */
    private static final class MergeTable {
        private final long[] keys;
        private final int[] values;
        private final int mask;

        MergeTable(int expected) {
            int capacity = 1;
            while (capacity < expected * 2) {
                capacity <<= 1;
            }
            keys = new long[capacity];
            values = new int[capacity];
            Arrays.fill(values, -1);
            mask = capacity - 1;
        }

        void put(long key, int value) {
            int slot = slotOf(key);
            // Keep the first (best-ranked) merge if a pair is listed twice.
            if (values[slot] < 0) {
                keys[slot] = key;
                values[slot] = value;
            }
        }

        int get(long key) {
            return values[slotOf(key)];
        }

        /** The slot holding this key, or the empty slot where it would go. */
        private int slotOf(long key) {
            long mixed = key * 0x9E3779B97F4A7C15L;
            int slot = (int) (mixed >>> 40) & mask;
            while (values[slot] >= 0 && keys[slot] != key) {
                slot = (slot + 1) & mask;
            }
            return slot;
        }
    }
}
