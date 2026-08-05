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

public final class AetherworksToolStationRecipeHandler extends AbstractRecipeHandler {
    private static final List<String> RESULT_METHODS = List.of(
            "getResultItem", "getResult", "getOutput", "getOutputCopy", "getAssembledItem");

    @Override
    public ModType modType() { return ModType.byId(ModIds.ID_AETHERWORKS_TOOL_STATION); }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        for (Class<?> iface : recipe.getClass().getInterfaces()) {
            if ("net.sirplop.aetherworks.recipe.IToolStationRecipe".equals(iface.getName())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return AetherworksRecipeAccess.result(recipe, access, RESULT_METHODS);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        List<Ingredient> inputs = AetherworksRecipeAccess.ingredients(recipe, "getDisplayInputs");
        if (inputs == null) return null;
        List<IngredientSpec> specs = new ArrayList<>(inputs.size());
        for (Ingredient ingredient : inputs) specs.add(new IngredientSpec(ingredient, 1));
        return specs;
    }
}
