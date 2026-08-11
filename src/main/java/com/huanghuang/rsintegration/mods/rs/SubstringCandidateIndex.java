package com.huanghuang.rsintegration.mods.rs;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Incremental substring candidate index. Exact matching remains the caller's responsibility. */
final class SubstringCandidateIndex {
    private static final int BIGRAM = 2;
    private static final int TRIGRAM = 3;

    private static final class ModeIndex {
        private final Map<String, BitSet> bigrams = new HashMap<>();
        private final Map<String, BitSet> trigrams = new HashMap<>();
        private final BitSet indexed = new BitSet();
    }

    private final Map<Integer, ModeIndex> modes = new HashMap<>();
    private final Map<String, Integer> ordinals = new HashMap<>();
    private final List<String> ids = new ArrayList<>();
    private final BitSet active = new BitSet();

    static SubstringCandidateIndex build(
            Map<Integer, ? extends Map<String, String>> textsByMode) {
        SubstringCandidateIndex built = new SubstringCandidateIndex();
        for (Map.Entry<Integer, ? extends Map<String, String>> mode : textsByMode.entrySet()) {
            for (Map.Entry<String, String> entry : mode.getValue().entrySet()) {
                built.index(mode.getKey(), entry.getKey(), entry.getValue());
            }
        }
        return built;
    }

    static SubstringCandidateIndex build(int mode, Map<String, String> texts) {
        SubstringCandidateIndex built = new SubstringCandidateIndex();
        for (Map.Entry<String, String> entry : texts.entrySet()) {
            built.index(mode, entry.getKey(), entry.getValue());
        }
        return built;
    }

    void index(int mode, String id, String text) {
        if (text == null) return;
        int ordinal = ordinals.computeIfAbsent(id, key -> {
            int created = ids.size();
            ids.add(key);
            return created;
        });
        active.set(ordinal);
        ModeIndex index = modes.computeIfAbsent(mode, ignored -> new ModeIndex());
        index.indexed.set(ordinal);
        addGrams(index.bigrams, text, BIGRAM, ordinal);
        addGrams(index.trigrams, text, TRIGRAM, ordinal);
    }

    List<String> candidates(int mode, String query) {
        ModeIndex index = modes.get(mode);
        if (index == null || query == null || query.isEmpty()) return List.of();
        BitSet candidates;
        if (query.length() == 1) {
            candidates = (BitSet) index.indexed.clone();
        } else {
            int width = query.length() == 2 ? BIGRAM : TRIGRAM;
            Map<String, BitSet> postings = width == BIGRAM ? index.bigrams : index.trigrams;
            candidates = intersect(postings, query, width);
        }
        candidates.and(active);
        List<String> result = new ArrayList<>(candidates.cardinality());
        for (int bit = candidates.nextSetBit(0); bit >= 0;
             bit = candidates.nextSetBit(bit + 1)) {
            result.add(ids.get(bit));
        }
        return List.copyOf(result);
    }

    static boolean shouldUseIndex(boolean indexReady, int indexedCandidates,
                                  int searchableEntries, int maximumPercent) {
        if (!indexReady || searchableEntries <= 0) return false;
        return indexedCandidates * 100L <= searchableEntries * (long) maximumPercent;
    }

    void remove(String id) {
        Integer ordinal = ordinals.get(id);
        if (ordinal != null) active.clear(ordinal);
    }

    void clearMode(int mode) {
        modes.remove(mode);
    }

    void clear() {
        modes.clear();
        ordinals.clear();
        ids.clear();
        active.clear();
    }

    private static void addGrams(Map<String, BitSet> postings, String text,
                                 int width, int ordinal) {
        if (text.length() < width) return;
        String previous = null;
        for (int offset = 0; offset <= text.length() - width; offset++) {
            String gram = text.substring(offset, offset + width);
            if (gram.equals(previous)) continue;
            postings.computeIfAbsent(gram, ignored -> new BitSet()).set(ordinal);
            previous = gram;
        }
    }

    private static BitSet intersect(Map<String, BitSet> postings, String query, int width) {
        BitSet result = null;
        for (int offset = 0; offset <= query.length() - width; offset++) {
            BitSet posting = postings.get(query.substring(offset, offset + width));
            if (posting == null) return new BitSet();
            if (result == null) result = (BitSet) posting.clone();
            else result.and(posting);
            if (result.isEmpty()) return result;
        }
        return result == null ? new BitSet() : result;
    }
}
