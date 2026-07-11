package com.huanghuang.rsintegration.autoeat.client;

import com.github.stuxuhai.jpinyin.PinyinFormat;
import com.github.stuxuhai.jpinyin.PinyinHelper;

final class PinyinUtil {

    private PinyinUtil() {}

    /** Convert Chinese text to pinyin (no tones, lowercase). Non-Chinese chars pass through unchanged. */
    static String toPinyin(String text) {
        if (text == null || text.isEmpty()) return "";
        try {
            return PinyinHelper.convertToPinyinString(text, "", PinyinFormat.WITHOUT_TONE).toLowerCase();
        } catch (Throwable t) {
            return text.toLowerCase();
        }
    }

    /** Extract pinyin initials (e.g. "苹果" → "pg"). Non-Chinese chars pass through. */
    static String toPinyinInitials(String text) {
        if (text == null || text.isEmpty()) return "";
        try {
            return PinyinHelper.getShortPinyin(text).toLowerCase();
        } catch (Throwable t) {
            return text.toLowerCase();
        }
    }
}
