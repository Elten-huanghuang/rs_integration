package com.huanghuang.rsintegration.sidepanel.client;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.network.RSJeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.world.item.ItemStack;

/**
 * JEI integration bridge for the side panel.
 * Handles push/pull of search text to/from JEI, and showing recipes/uses.
 */
public final class SidePanelJeiBridge {

    private SidePanelJeiBridge() {}

    /** Check whether JEI runtime is available. */
    public static boolean isJeiAvailable() {
        return RSJeiPlugin.getRuntime() != null;
    }

    /** Get the JEI runtime (may be null). */
    public static IJeiRuntime getRuntime() {
        return RSJeiPlugin.getRuntime();
    }

    /**
     * Push the current search text to JEI's ingredient filter.
     * Only does anything when searchMode >= 2 (JEI-linked modes).
     */
    public static void pushFilter(int searchMode, String searchText) {
        if (searchMode < 2) return;
        try {
            IJeiRuntime rt = RSJeiPlugin.getRuntime();
            if (rt != null) rt.getIngredientFilter().setFilterText(searchText);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-SidePanel] JEI push failed", e);
        }
    }

    /**
     * Pull the current filter text from JEI.
     * Only does anything when searchMode >= 2.
     *
     * @param searchMode        current search mode
     * @param currentSearchText current panel search text
     * @param lastJeiFilterText last known JEI filter text (to avoid redundant updates)
     * @return a {@link PullResult} indicating whether the search text changed
     */
    public static PullResult pullFilter(int searchMode, String currentSearchText,
                                         String lastJeiFilterText) {
        if (searchMode < 2) return PullResult.NO_CHANGE;
        try {
            IJeiRuntime rt = RSJeiPlugin.getRuntime();
            if (rt == null) return PullResult.NO_CHANGE;
            String t = rt.getIngredientFilter().getFilterText();
            if (t == null) return PullResult.NO_CHANGE;
            if (t.equals(lastJeiFilterText)) return PullResult.NO_CHANGE;
            if (!t.equals(currentSearchText)) {
                return new PullResult(t, t, true);
            }
            // t differs from lastJeiFilterText but equals currentSearchText —
            // just update the tracking field without triggering a rebuild.
            return new PullResult(currentSearchText, t, false);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-SidePanel] JEI pull failed", e);
            return PullResult.NO_CHANGE;
        }
    }

    /** Clear the JEI ingredient filter text. */
    public static void clearFilter() {
        try {
            IJeiRuntime rt = RSJeiPlugin.getRuntime();
            if (rt != null) rt.getIngredientFilter().setFilterText("");
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-SidePanel] JEI clear failed", e);
        }
    }

    /**
     * Show JEI recipes or uses for the given item stack.
     *
     * @param usage true = show uses (U key), false = show recipes (R key)
     * @param stack the item stack to look up
     */
    public static void showJeiForItem(boolean usage, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        try {
            IJeiRuntime runtime = RSJeiPlugin.getRuntime();
            if (runtime == null) return;
            IFocusFactory ff = runtime.getJeiHelpers().getFocusFactory();
            var focus = ff.createFocus(
                    usage ? RecipeIngredientRole.INPUT : RecipeIngredientRole.OUTPUT,
                    VanillaTypes.ITEM_STACK, stack);
            runtime.getRecipesGui().show(focus);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-SidePanel] JEI {} failed", usage ? "showUses" : "showRecipes", e);
        }
    }

    // ── Result types ──────────────────────────────────────────────

    /** Result of {@link #pullFilter}. */
    public static final class PullResult {
        /** Sentinel indicating no change was detected. */
        public static final PullResult NO_CHANGE = new PullResult(null, null, false);

        /** New search text to use (null if no change). */
        public final String newSearchText;
        /** Updated last-JEI-filter-text tracking value. */
        public final String newLastJeiFilterText;
        /** Whether the display list needs rebuilding due to a search change. */
        public final boolean searchChanged;

        PullResult(String newSearchText, String newLastJeiFilterText, boolean searchChanged) {
            this.newSearchText = newSearchText;
            this.newLastJeiFilterText = newLastJeiFilterText;
            this.searchChanged = searchChanged;
        }

        public boolean changed() { return newSearchText != null; }
    }
}
