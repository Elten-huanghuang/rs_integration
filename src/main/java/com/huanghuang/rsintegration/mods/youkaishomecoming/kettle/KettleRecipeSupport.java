package com.huanghuang.rsintegration.mods.youkaishomecoming.kettle;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.reflection.probes.YHKReflection;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class KettleRecipeSupport {
    private KettleRecipeSupport() {}

    public static boolean isKettleRecipe(Recipe<?> recipe) {
        Class<?> type = YHKReflection.kettleRecipeClass;
        return type != null && type.isInstance(recipe);
    }

    @Nullable
    public static Brewing brewing(Recipe<?> recipe) {
        if (!isKettleRecipe(recipe)) return null;
        try {
            Class<?> type = YHKReflection.kettleRecipeClass;
            if (!(type.getField("input").get(recipe) instanceof List<?> inputs)
                    || !(type.getField("result").get(recipe) instanceof FluidStack fluid)
                    || fluid.isEmpty()) return null;

            List<IngredientSpec> solids = new ArrayList<>();
            for (Object entry : inputs) {
                if (!(entry instanceof Ingredient ingredient) || ingredient.isEmpty()) return null;
                solids.add(new IngredientSpec(ingredient, 1));
            }
            if (solids.isEmpty() || solids.size() > 4) return null;
            return packageOutput(fluid, List.copyOf(solids));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    private static Brewing packageOutput(FluidStack fluid, List<IngredientSpec> solids)
            throws ReflectiveOperationException {
        Class<?> fluidType = YHKReflection.yhFluidClass;
        if (fluidType != null && fluidType.isInstance(fluid.getFluid())) {
            Class<?> holderType = YHKReflection.yhFluidHolderClass;
            Object holder = fluidType.getField("type").get(fluid.getFluid());
            if (holderType == null || !holderType.isInstance(holder)) return null;
            int perContainer = (int) holderType.getMethod("amount").invoke(holder);
            if (perContainer <= 0 || fluid.getAmount() % perContainer != 0) return null;
            int count = fluid.getAmount() / perContainer;
            if (!(holderType.getMethod("asStack", int.class).invoke(holder, count) instanceof ItemStack result)
                    || result.isEmpty() || result.getCount() != count
                    || !(holderType.getMethod("getContainer").invoke(holder) instanceof Item container)
                    || container == Items.AIR) return null;
            return new Brewing(fluid.copy(), solids, new ItemStack(container, count), result.copy());
        }

        Item bucket = fluid.getFluid().getBucket();
        if (bucket == Items.AIR || fluid.getAmount() != FluidType.BUCKET_VOLUME || fluid.hasTag()) return null;
        return new Brewing(fluid.copy(), solids, new ItemStack(Items.BUCKET), new ItemStack(bucket));
    }

    public record Brewing(FluidStack fluid, List<IngredientSpec> solids, ItemStack containers, ItemStack result) {
        public List<IngredientSpec> ingredients() {
            List<IngredientSpec> specs = new ArrayList<>(solids);
            specs.add(new IngredientSpec(Ingredient.of(containers), containers.getCount()));
            return List.copyOf(specs);
        }
    }
}
