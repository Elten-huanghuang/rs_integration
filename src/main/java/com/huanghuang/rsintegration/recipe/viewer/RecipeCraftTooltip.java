package com.huanghuang.rsintegration.recipe.viewer;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.mods.pmmo.PmmoRSModule;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.network.binding.BindingStorage.BindingEntry;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.util.SharedText;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;

public final class RecipeCraftTooltip {
    private static final String KEY_PREFIX = "gui.rs_integration.jei.";

    private RecipeCraftTooltip() {}

    public static Component resolve(String filter, @Nullable ModType recipeType,
                                    @Nullable BindingEntry binding) {
        if ("block.minecraft.anvil".equals(filter)) return SharedText.OPEN_BOUND_MACHINE.text();
        if ("generic".equals(filter)) return Component.translatable(KEY_PREFIX + "rs_auto_craft");
        if (PmmoRSModule.TYPE_ID.equals(filter)) return Component.translatable(KEY_PREFIX + "pmmo_salvage_craft");
        if (ModIds.ID_SUMMONING_RITUALS.equals(filter)) {
            return Component.translatable(KEY_PREFIX + "summoning_rituals_prepare");
        }

        if (binding != null && binding.blockKey() != null && !binding.blockKey().isBlank()) {
            Component name = BindingEventHandler.resolveBlockName(
                    binding.blockKey(), binding.blockRegKey(), binding.displayStack());
            return Component.translatable(KEY_PREFIX + "remote_craft_via", name);
        }
        if (recipeType != null && recipeType.isVirtual() && recipeType.virtualJeiTooltipKey() != null) {
            return Component.translatable(recipeType.virtualJeiTooltipKey());
        }
        return Component.translatable(KEY_PREFIX + "remote_craft");
    }
}
