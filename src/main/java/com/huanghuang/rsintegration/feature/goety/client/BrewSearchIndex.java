package com.huanghuang.rsintegration.feature.goety.client;

import com.huanghuang.rsintegration.util.PinyinUtil;
import net.minecraft.ChatFormatting;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** 目录更新时生成拼音索引，输入搜索词时只做文本匹配。 */
public final class BrewSearchIndex {
    private final List<String> forms;

    public BrewSearchIndex(Collection<String> labels) {
        var indexed = new LinkedHashSet<String>();
        for (String label : labels) {
            String text = normalize(label);
            if (text.isEmpty()) continue;
            indexed.add(text);
            indexed.add(PinyinUtil.toPinyin(text));
            indexed.add(PinyinUtil.toPinyinInitials(text));
        }
        forms = List.copyOf(indexed);
    }

    public boolean matches(String query) {
        String normalized = normalize(query);
        if (normalized.isEmpty()) return true;
        for (String term : normalized.split("\\s+")) {
            if (forms.stream().noneMatch(form -> containsInOrder(form, term))) return false;
        }
        return true;
    }

    private static String normalize(String text) {
        return text == null ? "" : ChatFormatting.stripFormatting(text).trim().toLowerCase(Locale.ROOT);
    }

    private static boolean containsInOrder(String text, String term) {
        int offset = 0;
        for (int index = 0; index < term.length();) {
            int point = term.codePointAt(index);
            int found = text.indexOf(point, offset);
            if (found < 0) return false;
            offset = found + Character.charCount(point);
            index += Character.charCount(point);
        }
        return true;
    }
}
