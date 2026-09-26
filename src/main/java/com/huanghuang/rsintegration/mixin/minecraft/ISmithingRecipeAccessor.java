package com.huanghuang.rsintegration.mixin.minecraft;

import com.huanghuang.rsintegration.api.ISmithingRecipeAccessor;
import net.minecraft.world.item.crafting.Ingredient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;

@Mixin(SmithingTransformRecipe.class)
interface SmithingTransformRecipeAccessor extends ISmithingRecipeAccessor {
    @Accessor("template") @Override Ingredient rsi$getTemplate();
    @Accessor("base")     @Override Ingredient rsi$getBase();
    @Accessor("addition") @Override Ingredient rsi$getAddition();
}

@Mixin(SmithingTrimRecipe.class)
interface SmithingTrimRecipeAccessor extends ISmithingRecipeAccessor {
    @Accessor("template") @Override Ingredient rsi$getTemplate();
    @Accessor("base")     @Override Ingredient rsi$getBase();
    @Accessor("addition") @Override Ingredient rsi$getAddition();
}
