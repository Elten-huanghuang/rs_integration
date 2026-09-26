package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.mods.farmersrespite.kettle.FRKettleRecipeSupport;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fluids.FluidStack;
import net.minecraft.world.level.material.Fluids;

import javax.annotation.Nullable;
import java.util.ArrayList;
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
        FluidStack fluidOut = FRKettleRecipeSupport.fluidOut(recipe);
        int bottles = FRKettleRecipeSupport.bottleCount(fluidOut.getAmount());
        return FRKettleRecipeSupport.bottledItem(fluidOut, bottles);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) specs.add(new IngredientSpec(ingredient, 1));
        }

        FluidStack fluidIn = FRKettleRecipeSupport.fluidIn(recipe);
        FluidStack fluidOut = FRKettleRecipeSupport.fluidOut(recipe);
        if (fluidOut.isEmpty()) return specs.isEmpty() ? null : specs;

        int outputBottles = FRKettleRecipeSupport.bottleCount(fluidOut.getAmount());
        boolean water = isWater(fluidIn);
        if (water) {
            // Water is supplied by the kettle itself. The output must still be bottled.
            specs.add(new IngredientSpec(Ingredient.of(Items.GLASS_BOTTLE), outputBottles));
        } else {
            // A non-water KettleRecipe consumes an earlier bottled drink. Those bottles
            // provide the empty containers used to bottle this recipe's output, so they
            // are deliberately not counted a second time as glass bottles.
            ItemStack inputBottles = FRKettleRecipeSupport.bottledItem(
                    fluidIn, FRKettleRecipeSupport.bottleCount(fluidIn.getAmount()));
            if (inputBottles.isEmpty()) return null;
            specs.add(new IngredientSpec(Ingredient.of(inputBottles), inputBottles.getCount()));
        }
        return specs.isEmpty() ? null : specs;
    }

    public static boolean isWater(FluidStack fluid) {
        return fluid != null && !fluid.isEmpty()
                && (fluid.getFluid() == Fluids.WATER
                || fluid.getFluid() == Fluids.FLOWING_WATER);
    }
}
