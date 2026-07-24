package com.huanghuang.rsintegration.autoeat.client;

import com.github.promeg.pinyinhelper.Pinyin;

import java.util.Locale;

public final class PinyinUtil {

    private PinyinUtil() {}

    /** Convert Chinese text to pinyin (no tones, lowercase). Non-Chinese chars pass through unchanged. */
    public static String toPinyin(String text) {
        if (text == null || text.isEmpty()) return "";
        try {
            return Pinyin.toPinyin(text, "").toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return text.toLowerCase(Locale.ROOT);
        }
    }

    /** Extract pinyin initials (e.g. "苹果" → "pg"). Non-Chinese chars pass through. */
    public static String toPinyinInitials(String text) {
        if (text == null || text.isEmpty()) return "";
        try {
            StringBuilder initials = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                char value = text.charAt(i);
                if (Pinyin.isChinese(value)) {
                    String syllable = Pinyin.toPinyin(value);
                    if (syllable != null && !syllable.isEmpty()) {
                        initials.append(Character.toLowerCase(syllable.charAt(0)));
                    }
                } else {
                    initials.append(Character.toLowerCase(value));
                }
            }
            return initials.toString();
        } catch (Throwable t) {
            return text.toLowerCase(Locale.ROOT);
        }
    }
}
