package com.huanghuang.rsintegration.mods.rs;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Parses the special prefixes understood by Refined Storage's grid filter. */
public record GridSearchQuery(int requiredModes, List<Term> terms) {
    public static final int TOOLTIP = 1;
    public static final int TAG = 2;
    public static final int MOD = 4;

    private static final GridSearchQuery EMPTY = new GridSearchQuery(0, List.of());

    public GridSearchQuery {
        terms = List.copyOf(terms);
    }

    public static GridSearchQuery parse(String query) {
        if (query == null || query.isBlank()) return EMPTY;

        int modes = 0;
        Set<Term> uniqueTerms = new LinkedHashSet<>();
        for (String branch : query.toLowerCase(Locale.ROOT).split("\\|", -1)) {
            String trimmed = branch.trim();
            if (trimmed.isEmpty()) continue;
            for (String token : trimmed.split("\\s+")) {
                if (token.length() < 2) continue;
                int mode = modeForPrefix(token.charAt(0));
                if (mode == 0) continue;
                modes |= mode;
                uniqueTerms.add(new Term(mode, token.substring(1)));
            }
        }
        if (modes == 0) return EMPTY;
        return new GridSearchQuery(modes, new ArrayList<>(uniqueTerms));
    }

    private static int modeForPrefix(char prefix) {
        return switch (prefix) {
            case '#' -> TOOLTIP;
            case '$' -> TAG;
            case '@' -> MOD;
            default -> 0;
        };
    }

    public record Term(int mode, String text) {}
}
