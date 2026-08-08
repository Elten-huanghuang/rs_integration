package com.huanghuang.rsintegration.mods.farmersrespite.kettle;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;

/** Small, optional-dependency-safe accessors shared by the Kettle planner and delegate. */
public final class FRKettleRecipeSupport {

    public static final int DEFAULT_MILLIBUCKETS_PER_BOTTLE = 250;

    private FRKettleRecipeSupport() {}

    public static FluidStack fluidIn(Recipe<?> recipe) {
        return invokeFluid(recipe, "getFluidIn");
    }

    public static FluidStack fluidOut(Recipe<?> recipe) {
        return invokeFluid(recipe, "getFluidOut");
    }

    /** Resolve Farmer's Respite's conventional fluid/item pair (coffee -> coffee item). */
    public static ItemStack bottledItem(FluidStack fluid, int count) {
        if (fluid == null || fluid.isEmpty() || count <= 0) return ItemStack.EMPTY;
        ResourceLocation fluidKey = ForgeRegistries.FLUIDS.getKey(fluid.getFluid());
        if (fluidKey == null) return ItemStack.EMPTY;
        var item = ForgeRegistries.ITEMS.getValue(fluidKey);
        if (item == null || item == Items.AIR) return ItemStack.EMPTY;
        return new ItemStack(item, count);
    }

    public static int bottleCount(int millibuckets) {
        if (millibuckets <= 0) return 0;
        return Math.max(1, millibuckets / DEFAULT_MILLIBUCKETS_PER_BOTTLE);
    }

    private static FluidStack invokeFluid(Recipe<?> recipe, String methodName) {
        if (recipe == null) return FluidStack.EMPTY;
        try {
            Method method = recipe.getClass().getMethod(methodName);
            Object value = method.invoke(recipe);
            return value instanceof FluidStack stack ? stack.copy() : FluidStack.EMPTY;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return FluidStack.EMPTY;
        }
    }
}
