package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.autoeat.client.PinyinUtil;

import java.util.Locale;

final class MaterialSearchMatcher {

    private MaterialSearchMatcher() {}

    static boolean matches(String displayName, String registryName, String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) return true;

        String name = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
        String registry = registryName == null ? "" : registryName.toLowerCase(Locale.ROOT);
        return name.contains(query)
                || registry.contains(query)
                || PinyinUtil.toPinyin(name).contains(query)
                || PinyinUtil.toPinyinInitials(name).contains(query);
    }
}
