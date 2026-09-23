package com.huanghuang.rsintegration.mods.tetra.client;

import java.util.Set;

/** Pure matching rules shared by the runtime bridge and regression tests. */
public final class TetraMaterialSelector {
    private TetraMaterialSelector() {}

    public static boolean matches(String key, String category, Set<String> selectors) {
        if (key == null || category == null) return false;
        for (String selector : selectors) {
            if (selector == null || selector.isBlank()) continue;
            String value = selector.trim();
            if (value.startsWith("#")) {
                String categorySelector = value.substring(1);
                if (category.equals(categorySelector)
                        || category.startsWith(categorySelector + "/")
                        || category.endsWith(":" + categorySelector)
                        || category.endsWith("/" + categorySelector)) return true;
                continue;
            }
            if (value.startsWith("!")) {
                String keySelector = value.substring(1);
                if (key.equals(keySelector) || key.endsWith("/" + keySelector)
                        || key.endsWith(":" + keySelector)) return true;
                continue;
            }
            if (key.startsWith(value) || category.equals(value)
                    || category.startsWith(value + "/")
                    || key.endsWith("/" + value)) return true;
        }
        return false;
    }
}
