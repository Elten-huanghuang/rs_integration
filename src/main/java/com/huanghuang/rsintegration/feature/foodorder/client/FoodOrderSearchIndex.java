package com.huanghuang.rsintegration.feature.foodorder.client;

import com.huanghuang.rsintegration.util.PinyinUtil;
import net.minecraft.ChatFormatting;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** 列表更新时缓存名称、全拼、首字母和物品 ID，输入时只匹配缓存文本。 */
final class FoodOrderSearchIndex {
    private final List<String> forms;

    FoodOrderSearchIndex(String displayName, String registryName) {
        String name = normalize(displayName);
        var indexed = new LinkedHashSet<String>();
        indexed.add(name);
        indexed.add(PinyinUtil.toPinyin(name));
        indexed.add(PinyinUtil.toPinyinInitials(name));
        indexed.add(normalize(registryName));
        indexed.remove("");
        forms = List.copyOf(indexed);
    }

    static List<String> queryTerms(String query) {
        String normalized = normalize(query);
        return normalized.isEmpty() ? List.of() : Arrays.asList(normalized.split("\\s+"));
    }

    boolean matches(List<String> terms) {
        for (String term : terms) {
            if (forms.stream().noneMatch(form -> containsInOrder(form, term))) {
                return false;
            }
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
            if (found < 0) {
                return false;
            }
            offset = found + Character.charCount(point);
            index += Character.charCount(point);
        }
        return true;
    }
}
