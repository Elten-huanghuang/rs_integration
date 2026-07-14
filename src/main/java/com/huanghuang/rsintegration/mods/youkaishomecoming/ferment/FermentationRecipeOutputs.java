package com.huanghuang.rsintegration.mods.youkaishomecoming.ferment;

import com.huanghuang.rsintegration.reflection.probes.YHKReflection;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
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

    public static Production fromRecipe(Recipe<?> recipe, int effectiveInputCount) {
        if (recipe == null) return new Production(ItemStack.EMPTY, List.of());
        Production itemProduction = calculate(isSimple(recipe), effectiveInputCount, readResults(recipe));
        if (!itemProduction.primary().isEmpty()) return itemProduction;
        return new Production(readFluidResult(recipe), List.of());
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
        Field field = findFieldUp(recipe.getClass(), "outputFluid");
        if (field == null || YHKReflection.yhFluidClass == null
                || YHKReflection.yhFluidHolderClass == null) return ItemStack.EMPTY;
        try {
            field.setAccessible(true);
            Object value = field.get(recipe);
            if (!(value instanceof FluidStack fluid) || fluid.isEmpty()
                    || !YHKReflection.yhFluidClass.isInstance(fluid.getFluid())) {
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
