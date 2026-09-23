package com.huanghuang.rsintegration.mods.jei;

/** Gives JEI's ingredient filter a way to invalidate its cached grid. */
public final class TetraWorkbenchJeiFilterRefreshRegistry {
    private static JeiIngredientFilterRefresh ingredientFilter;

    private TetraWorkbenchJeiFilterRefreshRegistry() {}

    public static void register(JeiIngredientFilterRefresh value) {
        ingredientFilter = value;
    }

    public static void refresh() {
        if (ingredientFilter != null) ingredientFilter.rsi$refreshSolCarrotResults();
    }

    public static void clear() {
        ingredientFilter = null;
    }
}
