package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.batch.ModType;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ModRecipeIndex {

    private static volatile Map<Item, List<RecipeEntry>> recipeIndex;
    private static volatile RecipeManager indexedRecipeManager;

    /** Cache resolved Method objects per recipe class to avoid repeated reflection. */
    private static final Map<Class<?>, java.lang.reflect.Method> resultMethodCache = new ConcurrentHashMap<>();

    private ModRecipeIndex() {}

    public record RecipeEntry(
            Recipe<?> recipe,
            ModType modType,
            ResourceLocation recipeTypeId
    ) {}

    public static Map<Item, List<RecipeEntry>> getRecipeIndexForLevel(Level level) {
        RecipeManager rm = level.getRecipeManager();
        Map<Item, List<RecipeEntry>> idx = recipeIndex;
        if (idx != null && indexedRecipeManager == rm) {
            return idx;
        }

        synchronized (ModRecipeIndex.class) {
            idx = recipeIndex;
            if (idx != null && indexedRecipeManager == rm) {
                return idx;
            }

            long start = System.currentTimeMillis();
            idx = new HashMap<>();
            Set<ResourceLocation> seen = new HashSet<>();

            for (CraftingRecipe recipe : rm.getAllRecipesFor(RecipeType.CRAFTING)) {
                ModType type = ModType.classifyRecipe(recipe);
                // Only index CraftingRecipes that belong to a known mod type.
                // Vanilla recipes (type == null) live in CraftingPlanManager;
                // unregistered mod recipes (also type == null) must not be
                // treated as GENERIC — the vanilla execution path has no
                // idea how to satisfy their ingredient requirements and may
                // produce garbage step chains.
                if (type == null) continue;
                ItemStack result = recipe.getResultItem(level.registryAccess());
                if (result.isEmpty()) {
                    result = tryGetResultItem(recipe, level.registryAccess());
                }
                if (result.isEmpty()) continue;
                if (!seen.add(recipe.getId())) continue;
                idx.computeIfAbsent(result.getItem(), k -> new ArrayList<>())
                        .add(new RecipeEntry(recipe, type,
                                new ResourceLocation("minecraft:crafting")));
            }

            for (Recipe<?> recipe : rm.getRecipes()) {
                if (!seen.add(recipe.getId())) continue;
                ModType type = ModType.classifyRecipe(recipe);
                if (type == ModType.GENERIC) continue;

                ItemStack result = tryGetResultItem(recipe, level.registryAccess());
                if (result.isEmpty()) continue;

                // 💡 修复：绝不能把未知的机器配方强制降级为 GENERIC，否则会被原版合成流瞬间白嫖！
                if (type == null) {
                    continue; // 忽略不支持的多方块配方
                }

                ResourceLocation typeId = recipe.getType() != null
                        ? ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType())
                        : null;
                if (typeId == null) typeId = new ResourceLocation("unknown");

                idx.computeIfAbsent(result.getItem(), k -> new ArrayList<>())
                        .add(new RecipeEntry(recipe, type, typeId));

                List<ItemStack> secondary = tryGetSecondaryOutputs(recipe, level.registryAccess());
                for (ItemStack sec : secondary) {
                    idx.computeIfAbsent(sec.getItem(), k -> new ArrayList<>())
                            .add(new RecipeEntry(recipe, type, typeId));
                }
            }

            recipeIndex = idx;
            indexedRecipeManager = rm;

            long elapsed = System.currentTimeMillis() - start;
            RSIntegrationMod.LOGGER.debug("[ModRecipeIndex] built: {} items, {} entries in {}ms",
                    idx.size(), seen.size(), elapsed);
            return idx;
        }
    }

    public static ItemStack tryGetResultItem(Recipe<?> recipe, RegistryAccess access) {
        if (recipe == null) return ItemStack.EMPTY;
        if (recipe instanceof CraftingRecipe cr) {
            return cr.getResultItem(access);
        }
        // Try cached reflective access for this recipe class
        Class<?> clazz = recipe.getClass();
        java.lang.reflect.Method m = resultMethodCache.get(clazz);
        if (m != null) {
            try {
                Object result;
                if (m.getParameterCount() == 1) {
                    result = m.invoke(recipe, access);
                } else {
                    result = m.invoke(recipe);
                }
                if (result instanceof ItemStack s && !s.isEmpty()) return s;
            } catch (Exception ignored) {}
        }
        // Probe for the result accessor and cache it
        for (String methodName : new String[]{"getResultItem", "getResultItem", "getResult", "getOutput", "getOutputCopy", "getAssembledItem"}) {
            for (java.lang.reflect.Method method : clazz.getMethods()) {
                if (method.getName().equals(methodName)
                        && ItemStack.class.isAssignableFrom(method.getReturnType())
                        && (method.getParameterCount() == 1 || method.getParameterCount() == 0)) {
                    try {
                        Object result;
                        if (method.getParameterCount() == 1) {
                            result = method.invoke(recipe, access);
                        } else {
                            result = method.invoke(recipe);
                        }
                        if (result instanceof ItemStack s && !s.isEmpty()) {
                            resultMethodCache.put(clazz, method);
                            return s;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        ItemStack fieldResult = tryGetOutputField(recipe);
        if (!fieldResult.isEmpty()) return fieldResult;

        return ItemStack.EMPTY;
    }

    @Nullable
    private static ItemStack tryGetOutputField(Recipe<?> recipe) {
        Class<?> scan = recipe.getClass();
        while (scan != null && scan != Object.class) {
            for (java.lang.reflect.Field field : scan.getDeclaredFields()) {
                if (!ItemStack.class.isAssignableFrom(field.getType())) continue;
                String name = field.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.contains("output") || name.contains("result") || name.contains("assembled")) {
                    field.setAccessible(true);
                    try {
                        Object val = field.get(recipe);
                        if (val instanceof ItemStack s && !s.isEmpty()) return s;
                    } catch (Exception ignored) {}
                }
            }
            scan = scan.getSuperclass();
        }
        return ItemStack.EMPTY;
    }

    public static List<ItemStack> tryGetSecondaryOutputs(Recipe<?> recipe, RegistryAccess access) {
        List<ItemStack> results = new ArrayList<>();
        if (recipe == null) return results;

        try {
            Object obj = recipe.getClass().getMethod("getRemainingItems").invoke(recipe);
            if (obj instanceof List<?> list) {
                for (Object e : list) {
                    if (e instanceof ItemStack s && !s.isEmpty()) results.add(s.copy());
                }
            } else if (obj instanceof ItemStack[] arr) {
                for (ItemStack s : arr) {
                    if (!s.isEmpty()) results.add(s.copy());
                }
            }
        } catch (Exception ignored) {}
        try {
            Object obj = recipe.getClass().getMethod("getByproducts").invoke(recipe);
            if (obj instanceof List<?> list) {
                for (Object e : list) {
                    if (e instanceof ItemStack s && !s.isEmpty()) results.add(s.copy());
                }
            }
        } catch (Exception ignored) {}
        try {
            Object obj = recipe.getClass().getMethod("getRollResults").invoke(recipe);
            if (obj instanceof List<?> list) {
                for (Object e : list) {
                    if (e instanceof ItemStack s && !s.isEmpty()) results.add(s.copy());
                }
            }
        } catch (Exception ignored) {}
        try {
            Object obj = recipe.getClass().getMethod("getOutputs").invoke(recipe);
            if (obj instanceof List<?> list) {
                for (Object e : list) {
                    if (e instanceof ItemStack s && !s.isEmpty()) results.add(s.copy());
                }
            } else if (obj instanceof ItemStack[] arr) {
                for (ItemStack s : arr) {
                    if (!s.isEmpty()) results.add(s.copy());
                }
            }
        } catch (Exception ignored) {}

        trySecondaryOutputFields(recipe, results);
        return results;
    }

    @SuppressWarnings("unchecked")
    private static void trySecondaryOutputFields(Recipe<?> recipe, List<ItemStack> results) {
        Class<?> scan = recipe.getClass();
        while (scan != null && scan != Object.class) {
            for (java.lang.reflect.Field field : scan.getDeclaredFields()) {
                String name = field.getName().toLowerCase(java.util.Locale.ROOT);
                if (!name.contains("byproduct") && !name.contains("secondary")
                        && !name.contains("extra") && !name.contains("bonus")
                        && !name.contains("roll"))
                    continue;
                field.setAccessible(true);
                try {
                    Object val = field.get(recipe);
                    if (val instanceof List<?> list) {
                        for (Object e : list) {
                            if (e instanceof ItemStack s && !s.isEmpty()) results.add(s.copy());
                        }
                    } else if (val instanceof ItemStack s && !s.isEmpty()) {
                        results.add(s.copy());
                    }
                } catch (Exception ignored) {}
            }
            scan = scan.getSuperclass();
        }
    }
}