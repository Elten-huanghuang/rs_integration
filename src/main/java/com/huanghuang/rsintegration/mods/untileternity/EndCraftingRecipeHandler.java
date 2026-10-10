package com.huanghuang.rsintegration.mods.untileternity;

import com.carrot123.until_eternity.recipe.EndCraftingIngredient;
import com.carrot123.until_eternity.recipe.EndCraftingRecipe;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.common.crafting.CompoundIngredient;
import net.minecraftforge.common.crafting.PartialNBTIngredient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 将终末工作台的 5×5 输入与实际产物交给递归规划器。 */
public final class EndCraftingRecipeHandler implements ModRecipeHandler {
    @Override public ModType modType() { return ModType.byId(UntilEternityRSModule.TYPE_ID); }
    @Override public boolean canHandle(Recipe<?> recipe) { return recipe instanceof EndCraftingRecipe; }
    @Override public boolean preferHandlerIngredients() { return true; }

    @Override
    public boolean isCompatibleBinding(Recipe<?> recipe, String blockKey) {
        return blockKey != null && blockKey.startsWith(UntilEternityRSModule.TYPE_ID + "||");
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        // 原模组的 getResultItem() 用工作台图标表示配方类型，真正产物在 result()。
        return ((EndCraftingRecipe) recipe).result().copy();
    }

    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        EndCraftingRecipe endRecipe = (EndCraftingRecipe) recipe;
        List<IngredientSpec> specs = new ArrayList<>(endRecipe.endIngredients().size());
        for (EndCraftingIngredient slot : endRecipe.endIngredients()) {
            if (slot.isEmpty()) {
                specs.add(IngredientSpec.EMPTY);
                continue;
            }
            Ingredient ingredient = ingredientFor(slot);
            if (ingredient == null || ingredient.isEmpty()) return null;
            specs.add(new IngredientSpec(ingredient, 1));
        }
        return List.copyOf(specs);
    }

    @Override
    public boolean supportsBackgroundPlanning(Recipe<?> recipe) {
        EndCraftingRecipe endRecipe = (EndCraftingRecipe) recipe;
        // 多选项 NBT 条件需要保留原模组的子集匹配，不能在图里降为精确匹配。
        for (EndCraftingIngredient slot : endRecipe.endIngredients()) {
            if (slot.requiredNbt() != null && slot.ingredient().getItems().length > 1) {
                return false;
            }
        }
        return ModRecipeHandler.super.supportsBackgroundPlanning(recipe);
    }

    private static Ingredient ingredientFor(EndCraftingIngredient slot) {
        CompoundTag requiredNbt = slot.requiredNbt();
        if (requiredNbt == null) return slot.ingredient();
        Ingredient[] alternatives = Arrays.stream(slot.ingredient().getItems())
                .filter(stack -> !stack.isEmpty())
                .map(stack -> PartialNBTIngredient.of(stack.getItem(), requiredNbt.copy()))
                .toArray(Ingredient[]::new);
        if (alternatives.length == 0) return null;
        return alternatives.length == 1 ? alternatives[0] : CompoundIngredient.of(alternatives);
    }
}
