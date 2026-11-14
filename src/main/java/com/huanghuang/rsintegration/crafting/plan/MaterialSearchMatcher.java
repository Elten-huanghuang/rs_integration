package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.util.PinyinUtil;

import java.util.Locale;

final class MaterialSearchMatcher {

    private MaterialSearchMatcher() {}

    static boolean matches(String displayName, String registryName, String rawQuery) {
        return prepare(displayName, registryName).matches(normalizeQuery(rawQuery));
    }

    static String normalizeQuery(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    }

    static SearchText prepare(String displayName, String registryName) {
        return new SearchText(displayName == null ? "" : displayName.toLowerCase(Locale.ROOT),
                registryName == null ? "" : registryName.toLowerCase(Locale.ROOT));
    }

    /** 搜索结果归页面持有，按需转换一次，避免大目录反复冲掉全局小缓存。 */
    static final class SearchText {
        private final String name;
        private final String registry;
        private String pinyin;
        private String initials;

        private SearchText(String name, String registry) {
            this.name = name;
            this.registry = registry;
        }

        boolean matches(String normalizedQuery) {
            if (normalizedQuery.isEmpty() || name.contains(normalizedQuery) || registry.contains(normalizedQuery)) {
                return true;
            }
            if (pinyin == null) {
                pinyin = PinyinUtil.toPinyin(name);
                initials = PinyinUtil.toPinyinInitials(name);
            }
            return pinyin.contains(normalizedQuery) || initials.contains(normalizedQuery);
        }
    }
}
