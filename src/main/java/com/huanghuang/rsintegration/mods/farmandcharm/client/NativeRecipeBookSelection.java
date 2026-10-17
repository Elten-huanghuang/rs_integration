package com.huanghuang.rsintegration.mods.farmandcharm.client;

import javax.annotation.Nullable;
import net.minecraft.world.item.crafting.Recipe;

/** Exposes the recipe currently selected in a Let's Do recipe-book widget. */
public interface NativeRecipeBookSelection {

    @Nullable
    Recipe<?> rsi$getSelectedRecipe();
}
