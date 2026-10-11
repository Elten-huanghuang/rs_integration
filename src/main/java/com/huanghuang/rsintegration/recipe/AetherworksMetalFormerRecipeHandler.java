package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.mods.embers.EmbersFluidIngredients;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.common.crafting.CompoundIngredient;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import net.sirplop.aetherworks.recipe.IMetalFormerRecipe;
import net.sirplop.aetherworks.recipe.MetalFormerRecipe;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

/** 金属成型的物品、精确 NBT 和毫桶流体统一进入递归材料账本。 */
public final class AetherworksMetalFormerRecipeHandler implements ModRecipeHandler {
    @Override public ModType modType() { return ModType.byId(ModIds.ID_AETHERWORKS_METAL_FORMER); }
    @Override public boolean canHandle(Recipe<?> recipe) { return recipe instanceof IMetalFormerRecipe; }
    @Override public boolean preferHandlerIngredients() { return true; }

    @Override public boolean isCompatibleBinding(Recipe<?> recipe, @Nullable String blockKey) {
        return blockKey != null && (blockKey.startsWith(ModIds.ID_AETHERWORKS_METAL_FORMER + "||")
                || blockKey.endsWith(".forge_metal_former"));
    }

    @Override public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        ItemStack result = ((IMetalFormerRecipe) recipe).getResultItem();
        return result == null ? ItemStack.EMPTY : result.copy();
    }

    @Nullable
    @Override public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        IMetalFormerRecipe forming = (IMetalFormerRecipe) recipe;
        Ingredient input = forming.getDisplayInput();
        // 1.1.2 的原生机器只有在物品槽和流体罐均非空时才加工。
        if (input == null || input.isEmpty() || forming.getDisplayInputFluid() == null) return null;
        if (recipe instanceof MetalFormerRecipe nativeRecipe && nativeRecipe.matchExactly) {
            Ingredient[] choices = Arrays.stream(input.getItems())
                    .map(stack -> StrictNBTIngredient.of(stack.copyWithCount(1)))
                    .toArray(Ingredient[]::new);
            if (choices.length == 0) return null;
            input = choices.length == 1 ? choices[0] : CompoundIngredient.of(choices);
        }
        IngredientSpec fluid = EmbersFluidIngredients.spec(forming.getDisplayInputFluid());
        return fluid == null ? null : List.of(new IngredientSpec(input, 1), fluid);
    }

    @Override public boolean supportsBackgroundPlanning(Recipe<?> recipe) {
        return recipe.getClass() == MetalFormerRecipe.class
                && ModRecipeHandler.super.supportsBackgroundPlanning(recipe);
    }

    @Override public boolean supportsIntermediateProjection(Recipe<?> recipe) {
        return supportsBackgroundPlanning(recipe);
    }
}
