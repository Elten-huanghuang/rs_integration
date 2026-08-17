package com.huanghuang.rsintegration.mods.youkaishomecoming.ferment;

import com.huanghuang.rsintegration.reflection.probes.YHKReflection;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Shared production semantics for YHK fermentation recipes. */
public final class FermentationRecipeOutputs {

    private FermentationRecipeOutputs() {}

    public record Production(ItemStack primary, List<ItemStack> secondary) {
        public Production {
            primary = primary == null ? ItemStack.EMPTY : primary.copy();
            List<ItemStack> copies = new ArrayList<>();
            if (secondary != null) {
                for (ItemStack stack : secondary) {
                    if (stack != null && !stack.isEmpty()) copies.add(stack.copy());
                }
            }
            secondary = List.copyOf(copies);
        }
    }

    /** Item representation of a recipe's non-water input fluid. */
    public record FluidMaterial(FluidStack fluid, ItemStack filledContainers,
                                ItemStack emptyContainers, boolean supported) {
        public FluidMaterial {
            fluid = fluid == null ? FluidStack.EMPTY : fluid.copy();
            filledContainers = filledContainers == null
                    ? ItemStack.EMPTY : filledContainers.copy();
            emptyContainers = emptyContainers == null
                    ? ItemStack.EMPTY : emptyContainers.copy();
        }

        public boolean requiresPlannedContainers() {
            return !filledContainers.isEmpty();
        }
    }

    public static Production fromRecipe(Recipe<?> recipe, int effectiveInputCount) {
        if (recipe == null) return new Production(ItemStack.EMPTY, List.of());
        Production itemProduction = calculate(isSimple(recipe), effectiveInputCount, readResults(recipe));
        if (!itemProduction.primary().isEmpty()) return itemProduction;
        return new Production(readFluidResult(recipe), List.of());
    }

    /**
     * Converts the recipe's input fluid into the filled item containers that
     * recursive crafting can reserve. Water keeps the legacy tank-fill path;
     * YHK fluids use their native holder API, and ordinary Forge fluids fall
     * back to buckets when the amount is an exact bucket multiple.
     */
    public static FluidMaterial inputFluidMaterial(Recipe<?> recipe) {
        return fluidMaterial(readFluidField(recipe, "inputFluid"));
    }

    static FluidMaterial fluidMaterial(FluidStack fluid) {
        if (fluid == null) fluid = FluidStack.EMPTY;
        if (fluid.isEmpty() || fluid.getFluid() == Fluids.WATER) {
            return new FluidMaterial(fluid, ItemStack.EMPTY, ItemStack.EMPTY, true);
        }

        // Keep ordinary Forge fluids independent from YHK's reflection probe.
        // Besides being cheaper, this lets standard bucket fluids work in
        // environments where Youkai's Homecoming is not loaded.
        int buckets = exactContainerCount(fluid.getAmount(), 1000);
        Item bucket = fluid.getFluid().getBucket();
        if (buckets > 0 && bucket != Items.AIR) {
            return new FluidMaterial(fluid, new ItemStack(bucket, buckets),
                    new ItemStack(Items.BUCKET, buckets), true);
        }

        if (YHKReflection.yhFluidClass != null
                && YHKReflection.yhFluidClass.isInstance(fluid.getFluid())) {
            try {
                Field typeField = YHKReflection.yhFluidClass.getField("type");
                Object holder = typeField.get(fluid.getFluid());
                if (holder == null || YHKReflection.yhFluidHolderClass == null
                        || !YHKReflection.yhFluidHolderClass.isInstance(holder)) {
                    return unsupportedFluid(fluid);
                }
                Method amountMethod = YHKReflection.yhFluidHolderClass.getMethod("amount");
                Method asStackMethod = YHKReflection.yhFluidHolderClass.getMethod(
                        "asStack", int.class);
                Method containerMethod = YHKReflection.yhFluidHolderClass.getMethod(
                        "getContainer");
                int perContainer = (int) amountMethod.invoke(holder);
                int count = exactContainerCount(fluid.getAmount(), perContainer);
                if (count <= 0) return unsupportedFluid(fluid);

                Object filledValue = asStackMethod.invoke(holder, count);
                if (!(filledValue instanceof ItemStack filled) || filled.isEmpty()) {
                    return unsupportedFluid(fluid);
                }
                ItemStack empty = ItemStack.EMPTY;
                Object containerValue = containerMethod.invoke(holder);
                if (containerValue instanceof Item container && container != Items.AIR) {
                    empty = new ItemStack(container, count);
                }
                return new FluidMaterial(fluid, filled, empty, true);
            } catch (ReflectiveOperationException | ClassCastException e) {
                return unsupportedFluid(fluid);
            }
        }

        return unsupportedFluid(fluid);
    }

    static int exactContainerCount(int fluidAmount, int perContainer) {
        if (fluidAmount <= 0 || perContainer <= 0 || fluidAmount % perContainer != 0) {
            return 0;
        }
        return fluidAmount / perContainer;
    }

    public static int effectiveIngredientCount(Recipe<?> recipe) {
        Field field = findFieldUp(recipe.getClass(), "ingredients");
        if (field == null) return 0;
        try {
            field.setAccessible(true);
            Object value = field.get(recipe);
            if (!(value instanceof List<?> list)) return 0;
            int count = 0;
            for (Object entry : list) {
                if (entry instanceof Ingredient ingredient && !ingredient.isEmpty()) count++;
            }
            return count;
        } catch (Exception ignored) {
            return 0;
        }
    }

    public static Production calculate(boolean simple, int effectiveInputCount,
                                       List<ItemStack> results) {
        if (results == null || results.isEmpty()) {
            return new Production(ItemStack.EMPTY, List.of());
        }
        ItemStack first = firstNonEmpty(results);
        if (first.isEmpty()) return new Production(ItemStack.EMPTY, List.of());

        if (simple) {
            if (effectiveInputCount <= 0) return new Production(ItemStack.EMPTY, List.of());
            long total = (long) first.getCount() * effectiveInputCount;
            if (total <= 0 || total > Integer.MAX_VALUE) {
                return new Production(ItemStack.EMPTY, List.of());
            }
            return new Production(first.copyWithCount((int) total), List.of());
        }

        List<ItemStack> groups = new ArrayList<>();
        for (ItemStack result : results) {
            if (result == null || result.isEmpty()) continue;
            merge(groups, result);
        }
        if (groups.isEmpty()) return new Production(ItemStack.EMPTY, List.of());
        ItemStack primary = groups.remove(0);
        return new Production(primary, groups);
    }

    public static List<ItemStack> readResults(Recipe<?> recipe) {
        Field field = findFieldUp(recipe.getClass(), "results");
        if (field == null) return List.of();
        try {
            field.setAccessible(true);
            Object value = field.get(recipe);
            if (!(value instanceof List<?> list)) return List.of();
            List<ItemStack> results = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof ItemStack stack && !stack.isEmpty()) results.add(stack.copy());
            }
            return List.copyOf(results);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public static boolean isSimple(Recipe<?> recipe) {
        try {
            Method method = recipe.getClass().getMethod("isSimple");
            Object value = method.invoke(recipe);
            return value instanceof Boolean simple && simple;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static ItemStack readFluidResult(Recipe<?> recipe) {
        FluidStack fluid = readFluidField(recipe, "outputFluid");
        if (fluid.isEmpty() || YHKReflection.yhFluidClass == null
                || YHKReflection.yhFluidHolderClass == null) return ItemStack.EMPTY;
        try {
            if (!YHKReflection.yhFluidClass.isInstance(fluid.getFluid())) {
                return ItemStack.EMPTY;
            }

            Field typeField = YHKReflection.yhFluidClass.getField("type");
            Object holder = typeField.get(fluid.getFluid());
            if (holder == null || !YHKReflection.yhFluidHolderClass.isInstance(holder)) {
                return ItemStack.EMPTY;
            }
            Method amountMethod = YHKReflection.yhFluidHolderClass.getMethod("amount");
            Method asStackMethod = YHKReflection.yhFluidHolderClass.getMethod("asStack", int.class);
            Object amountValue = amountMethod.invoke(holder);
            if (!(amountValue instanceof Integer amount) || amount <= 0
                    || fluid.getAmount() <= 0 || fluid.getAmount() % amount != 0) {
                return ItemStack.EMPTY;
            }
            Object result = asStackMethod.invoke(holder, fluid.getAmount() / amount);
            return result instanceof ItemStack stack && !stack.isEmpty()
                    ? stack.copy() : ItemStack.EMPTY;
        } catch (Exception ignored) {
            return ItemStack.EMPTY;
        }
    }

    private static FluidStack readFluidField(Recipe<?> recipe, String fieldName) {
        if (recipe == null) return FluidStack.EMPTY;
        Field field = findFieldUp(recipe.getClass(), fieldName);
        if (field == null) return FluidStack.EMPTY;
        try {
            field.setAccessible(true);
            Object value = field.get(recipe);
            return value instanceof FluidStack fluid ? fluid.copy() : FluidStack.EMPTY;
        } catch (Exception ignored) {
            return FluidStack.EMPTY;
        }
    }

    private static FluidMaterial unsupportedFluid(FluidStack fluid) {
        return new FluidMaterial(fluid, ItemStack.EMPTY, ItemStack.EMPTY, false);
    }

    private static void merge(List<ItemStack> groups, ItemStack incoming) {
        for (ItemStack group : groups) {
            if (ItemStack.isSameItemSameTags(group, incoming)) {
                long total = (long) group.getCount() + incoming.getCount();
                group.setCount((int) Math.min(Integer.MAX_VALUE, total));
                return;
            }
        }
        groups.add(incoming.copy());
    }

    private static ItemStack firstNonEmpty(List<ItemStack> results) {
        for (ItemStack result : results) {
            if (result != null && !result.isEmpty()) return result.copy();
        }
        return ItemStack.EMPTY;
    }

    @Nullable
    private static Field findFieldUp(Class<?> clazz, String name) {
        Class<?> scan = clazz;
        while (scan != null && scan != Object.class) {
            try {
                return scan.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                scan = scan.getSuperclass();
            }
        }
        return null;
    }
}
