package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.List;

public final class IronSpellBooksRecipeHandler implements ModRecipeHandler {
    @Override public ModType modType() { return ModType.byId(IronSpellBooksRSModule.SCROLL_FORGE_TYPE); }
    @Override public ModType modType(Recipe<?> recipe) {
        // 三种工作站共用配方类，执行和绑定必须使用配方声明的具体机器。
        IronSpellBooksRecipe ironRecipe = (IronSpellBooksRecipe) recipe;
        return ModType.byId(switch (ironRecipe.machine()) {
            case SCROLL_FORGE -> IronSpellBooksRSModule.SCROLL_FORGE_TYPE;
            case ARCANE_ANVIL -> IronSpellBooksRSModule.ARCANE_ANVIL_TYPE;
            case ALCHEMIST_CAULDRON -> IronSpellBooksRSModule.ALCHEMIST_CAULDRON_TYPE;
        });
    }
    @Override public boolean canHandle(Recipe<?> recipe) { return recipe instanceof IronSpellBooksRecipe; }
    @Override public boolean cacheByRecipeClass() { return false; }
    @Override public boolean hasDeterministicPrimaryOutput(Recipe<?> recipe) {
        return !(recipe instanceof IronSpellBooksRecipe ironRecipe) || !ironRecipe.isScrollRecycling();
    }
    @Override public boolean supportsTargetedProduction(Recipe<?> recipe) {
        return recipe instanceof IronSpellBooksRecipe ironRecipe && ironRecipe.isScrollRecycling();
    }
    @Override public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return recipe.getResultItem(access).copy();
    }
    @Nullable
    @Override public IntegrationResourceKey getPrimaryOutputResource(
            Recipe<?> recipe,
            RegistryAccess access
    ) {
        ItemStack result = recipe.getResultItem(access);
        if (recipe instanceof IronSpellBooksRecipe ironRecipe
                && (ironRecipe.isScrollRecycling() || ironRecipe.isBrewing())
                && InkFluidSupport.isToken(result)) {
            return InkFluidSupport.resource(result);
        }
        return ModRecipeHandler.super.getPrimaryOutputResource(
                recipe, access);
    }
    @Override public long getPrimaryOutputUnits(
            Recipe<?> recipe,
            RegistryAccess access
    ) {
        ItemStack result = recipe.getResultItem(access);
        return result.isEmpty()
                ? 0L
                : InkFluidSupport.isToken(result)
                ? result.getCount()
                : ModRecipeHandler.super.getPrimaryOutputUnits(
                        recipe, access);
    }
    @Override public boolean requiresTargetedProduction(
            Recipe<?> recipe,
            IntegrationResourceKey output
    ) {
        if (recipe instanceof IronSpellBooksRecipe ironRecipe
                && ironRecipe.isScrollRecycling()
                && output.kind()
                == IntegrationResourceKey.ResourceKind.FLUID) {
            IntegrationResourceKey declared =
                    getPrimaryOutputResource(
                            recipe, RegistryAccess.EMPTY);
            return declared != null
                    && declared.kind() == output.kind()
                    && declared.id().equals(output.id())
                    && (output.identity().isEmpty()
                    || declared.identity().equals(output.identity()));
        }
        return ModRecipeHandler.super.requiresTargetedProduction(
                recipe, output);
    }
    @Override public boolean hasFluidOutputs(Recipe<?> recipe) {
        return recipe instanceof IronSpellBooksRecipe ironRecipe
                && (ironRecipe.isScrollRecycling()
                || ironRecipe.isBrewing());
    }
    @Override public List<ItemStack> getSecondaryOutputs(Recipe<?> recipe, RegistryAccess access) {
        return ((IronSpellBooksRecipe) recipe).secondaryOutputs();
    }
    @Override public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        IronSpellBooksRecipe ironRecipe = (IronSpellBooksRecipe) recipe;
        List<ItemStack> displays = ironRecipe.inputs();
        List<Ingredient> ingredients =
                ironRecipe.inputIngredients();
        ArrayList<IngredientSpec> result = new ArrayList<>(ingredients.size());
        for (int i = 0; i < ingredients.size(); i++) {
            result.add(new IngredientSpec(ingredients.get(i), displays.get(i).getCount()));
        }
        return List.copyOf(result);
    }
}
