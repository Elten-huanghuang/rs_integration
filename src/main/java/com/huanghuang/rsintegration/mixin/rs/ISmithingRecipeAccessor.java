package com.huanghuang.rsintegration.mixin.rs;

import com.huanghuang.rsintegration.api.ISmithingRecipeAccessor;
import net.minecraft.world.item.crafting.Ingredient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Mixin-side implementations of {@link ISmithingRecipeAccessor}.
 * The parent interface lives in the {@code api} package so external code
 * can reference it without violating Mixin's package-ownership rules.
 */

@Mixin(net.minecraft.world.item.crafting.SmithingTransformRecipe.class)
interface SmithingTransformRecipeAccessor extends ISmithingRecipeAccessor {
    @Accessor("template") @Override Ingredient rsi$getTemplate();
    @Accessor("base")     @Override Ingredient rsi$getBase();
    @Accessor("addition") @Override Ingredient rsi$getAddition();
}

@Mixin(net.minecraft.world.item.crafting.SmithingTrimRecipe.class)
interface SmithingTrimRecipeAccessor extends ISmithingRecipeAccessor {
    @Accessor("template") @Override Ingredient rsi$getTemplate();
    @Accessor("base")     @Override Ingredient rsi$getBase();
    @Accessor("addition") @Override Ingredient rsi$getAddition();
}
