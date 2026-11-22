package com.huanghuang.rsintegration.mods.farmersrespite;

import com.huanghuang.rsintegration.recipe.AbstractRecipeHandler;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.mods.farmersrespite.kettle.FRKettleRecipeSupport;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import java.util.List;

/** Recipe semantics for Farmer's Respite Kettle's fluid-output recipes. */
public final class FarmersRespiteRecipeHandler extends AbstractRecipeHandler {

    private static final String KETTLE_RECIPE =
            "umpaz.farmersrespite.common.crafting.KettleRecipe";

    static {
        registerRecipePrefixes(FarmersRespiteRecipeHandler.class, KETTLE_RECIPE);
    }

    @Override
    public ModType modType() {
        return ModType.byId("farmersrespite_kettle");
    }

    @Override
    public boolean preferHandlerIngredients() {
        return true;
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        var brewing = FRKettleRecipeSupport.brewing(recipe, FRKettleRecipeSupport.currentRecipeManager());
        return brewing == null ? ItemStack.EMPTY : brewing.result();
    }

    @Override
    public List<ItemStack> getSecondaryOutputs(Recipe<?> recipe, RegistryAccess access) {
        var brewing = FRKettleRecipeSupport.brewing(recipe, FRKettleRecipeSupport.currentRecipeManager());
        ItemStack returned = brewing == null ? ItemStack.EMPTY : brewing.returnedContainer();
        return returned.isEmpty() ? List.of() : List.of(returned);
    }

    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        var brewing = FRKettleRecipeSupport.brewing(recipe, FRKettleRecipeSupport.currentRecipeManager());
        // 空列表阻止通用探测把缺少装瓶配方的输入退化为仅固体材料。
        return brewing == null ? List.of() : brewing.ingredients(recipe);
    }
}
