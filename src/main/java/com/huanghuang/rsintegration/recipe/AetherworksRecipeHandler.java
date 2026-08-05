package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class AetherworksRecipeHandler extends AbstractRecipeHandler {
    private static final List<String> RESULT_METHODS = List.of(
            "getResult", "getOutput", "getOutputCopy", "getAssembledItem", "getResultItem");

    @Override
    public ModType modType() { return ModType.byId(ModIds.ID_AETHERWORKS_ANVIL); }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        for (Class<?> iface : recipe.getClass().getInterfaces()) {
            if ("net.sirplop.aetherworks.recipe.IAetheriumAnvilRecipe".equals(iface.getName())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return AetherworksRecipeAccess.result(recipe, access, RESULT_METHODS);
    }

    @Override
    public boolean hasDeterministicPrimaryOutput(Recipe<?> recipe) {
        if (!AetherworksRecipeAccess.hasNoArgMethod(recipe, "getAllResults")) return true;
        List<?> results = AetherworksRecipeAccess.list(recipe, "getAllResults");
        return results != null && results.size() <= 1;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        List<IngredientSpec> specs = new ArrayList<>();
        Ingredient displayInput = AetherworksRecipeAccess.ingredient(recipe, "getDisplayInput");
        if (displayInput != null) specs.add(new IngredientSpec(displayInput, 1));
        Ingredient addition = AetherworksRecipeAccess.ingredient(recipe, "getAddition");
        if (addition != null) specs.add(new IngredientSpec(addition, 1));
        return specs.isEmpty() ? null : specs;
    }
}
