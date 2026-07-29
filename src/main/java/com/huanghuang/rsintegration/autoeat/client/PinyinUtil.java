package com.huanghuang.rsintegration.autoeat.client;

import com.ibm.icu.text.Transliterator;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class PinyinUtil {

    static final int CACHE_LIMIT = 512;

    private static final ThreadLocal<Transliterator> HAN_TO_LATIN =
            ThreadLocal.withInitial(() -> Transliterator.getInstance("Han-Latin"));
    private static final Map<String, SearchForms> CACHE =
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SearchForms> eldest) {
                    return size() > CACHE_LIMIT;
                }
            };

    private PinyinUtil() {}

    /** Convert Chinese text to pinyin (no tones, lowercase). Non-Chinese chars pass through unchanged. */
    public static String toPinyin(String text) {
        return formsFor(text).full();
    }

    /** Extract pinyin initials (e.g. "\u82f9\u679c" becomes "pg"). Non-Chinese chars pass through. */
    public static String toPinyinInitials(String text) {
        return formsFor(text).initials();
    }

    private static SearchForms formsFor(String text) {
        if (text == null || text.isEmpty()) return SearchForms.EMPTY;
        synchronized (CACHE) {
            SearchForms cached = CACHE.get(text);
            if (cached != null) return cached;
        }

        SearchForms forms;
        try {
            forms = transliterate(text);
        } catch (Throwable t) {
            String fallback = text.toLowerCase(Locale.ROOT);
            forms = new SearchForms(fallback, fallback);
        }

        synchronized (CACHE) {
            SearchForms raced = CACHE.get(text);
            if (raced != null) return raced;
            CACHE.put(text, forms);
            return forms;
        }
    }

    private static SearchForms transliterate(String text) {
        StringBuilder full = new StringBuilder(text.length() * 2);
        StringBuilder initials = new StringBuilder(text.length());

        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN) {
                String literal = new String(Character.toChars(codePoint)).toLowerCase(Locale.ROOT);
                full.append(literal);
                initials.append(literal);
                offset += Character.charCount(codePoint);
                continue;
            }

            int runEnd = offset + Character.charCount(codePoint);
            while (runEnd < text.length()) {
                int next = text.codePointAt(runEnd);
                if (Character.UnicodeScript.of(next) != Character.UnicodeScript.HAN) break;
                runEnd += Character.charCount(next);
            }
            appendHanRun(HAN_TO_LATIN.get().transliterate(text.substring(offset, runEnd)),
                    full, initials);
            offset = runEnd;
        }
        return new SearchForms(full.toString(), initials.toString());
    }

    private static void appendHanRun(String transliterated, StringBuilder full,
                                     StringBuilder initials) {
        int tokenStart = 0;
        for (int offset = 0; offset <= transliterated.length();) {
            int codePoint = offset < transliterated.length()
                    ? transliterated.codePointAt(offset) : -1;
            if (codePoint == -1 || isSyllableSeparator(codePoint)) {
                if (tokenStart < offset) {
                    appendSyllable(transliterated.substring(tokenStart, offset), full, initials);
                }
                if (codePoint == -1) break;
                offset += Character.charCount(codePoint);
                tokenStart = offset;
            } else {
                offset += Character.charCount(codePoint);
            }
        }
    }

    private static boolean isSyllableSeparator(int codePoint) {
        return Character.isWhitespace(codePoint)
                || codePoint == '\'' || codePoint == '\u2019' || codePoint == '\u02bc';
    }

    private static void appendSyllable(String syllable, StringBuilder full,
                                       StringBuilder initials) {
        String normalized = normalizeSyllable(syllable);
        if (normalized.isEmpty()) return;

        full.append(normalized);
        int first = normalized.codePointAt(0);
        if (Character.UnicodeScript.of(first) == Character.UnicodeScript.HAN) {
            // ICU leaves unknown Han characters untouched; retain them instead of losing searchability.
            initials.append(normalized);
        } else {
            initials.appendCodePoint(first);
        }
    }

    private static String normalizeSyllable(String syllable) {
        String decomposed = Normalizer.normalize(syllable, Normalizer.Form.NFD);
        StringBuilder normalized = new StringBuilder(decomposed.length());
        for (int offset = 0; offset < decomposed.length();) {
            int codePoint = decomposed.codePointAt(offset);
            int nextOffset = offset + Character.charCount(codePoint);
            if (isCombiningMark(codePoint)) {
                offset = nextOffset;
                continue;
            }

            boolean hasDiaeresis = false;
            int marksEnd = nextOffset;
            while (marksEnd < decomposed.length()) {
                int mark = decomposed.codePointAt(marksEnd);
                if (!isCombiningMark(mark)) break;
                if (mark == 0x0308) hasDiaeresis = true;
                marksEnd += Character.charCount(mark);
            }
            if ((codePoint == 'u' || codePoint == 'U') && hasDiaeresis) {
                normalized.append('v');
            } else {
                normalized.appendCodePoint(codePoint);
            }
            offset = marksEnd;
        }
        return normalized.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean isCombiningMark(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    static int cacheSizeForTesting() {
        synchronized (CACHE) {
            return CACHE.size();
        }
    }

    static void clearCacheForTesting() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    private record SearchForms(String full, String initials) {
        private static final SearchForms EMPTY = new SearchForms("", "");
    }
}
