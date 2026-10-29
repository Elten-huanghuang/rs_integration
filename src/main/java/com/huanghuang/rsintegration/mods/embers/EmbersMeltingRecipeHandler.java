package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.util.ModIds;
import com.rekindled.embers.recipe.IMeltingRecipe;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.List;

public final class EmbersMeltingRecipeHandler implements ModRecipeHandler {
    @Override public ModType modType() { return ModType.byId(ModIds.ID_EMBERS_MELTER); }
    @Override public boolean canHandle(Recipe<?> recipe) { return recipe instanceof IMeltingRecipe; }
    @Override public boolean preferHandlerIngredients() { return true; }
    @Override public boolean isCompatibleBinding(Recipe<?> recipe, String blockKey) {
        return blockKey != null && blockKey.contains(ModIds.ID_EMBERS_MELTER);
    }
    @Override public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return InkFluidSupport.token(((IMeltingRecipe) recipe).getDisplayOutput());
    }
    @Override public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        Ingredient input = ((IMeltingRecipe) recipe).getDisplayInput();
        return input == null || input.isEmpty() ? null : List.of(new IngredientSpec(input, 1));
    }
    /**
     * 任何 {@link IMeltingRecipe} 都能投影：展示用的输入/产出是接口方法，
     * 不依赖具体实现类。以前只放行 {@code MeltingRecipe} 本身，附加模组（或其他
     * 数据包）注册的熔炼配方会因此进不了预览图，预览只能把熔融金属当成「库存里已有」，
     * 执行侧却又能真的去熔化，于是预览与实际派发对不上。
     */
    @Override public boolean supportsBackgroundPlanning(Recipe<?> recipe) {
        return recipe instanceof IMeltingRecipe
                && ModRecipeHandler.super.supportsBackgroundPlanning(recipe);
    }
    @Override public boolean supportsIntermediateProjection(Recipe<?> recipe) {
        return supportsBackgroundPlanning(recipe);
    }
}
