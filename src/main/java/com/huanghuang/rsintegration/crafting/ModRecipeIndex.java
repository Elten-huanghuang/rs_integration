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

/**
 * Unified recipe index that covers both vanilla crafting-table recipes and
 * multi-block mod recipes (Goety, Malum, FA, Eidolon, WR).
 *
 * <p>Used by {@link CraftingResolver} to find candidate recipes for producing
 * a given item, regardless of recipe type.</p>
 */
public final class ModRecipeIndex {

    private static volatile Map<Item, List<RecipeEntry>> recipeIndex;
    private static volatile RecipeManager indexedRecipeManager;

    private ModRecipeIndex() {}

    // ── recipe entry ────────────────────────────────────────────

    public record RecipeEntry(
            Recipe<?> recipe,
            ModType modType,
            ResourceLocation recipeTypeId
    ) {}

    // ── index ────────────────────────────────────────────────────

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

            // 1. Vanilla crafting recipes
            for (CraftingRecipe recipe : rm.getAllRecipesFor(RecipeType.CRAFTING)) {
                ItemStack result = recipe.getResultItem(level.registryAccess());
                if (result.isEmpty()) {
                    result = tryGetResultItem(recipe, level.registryAccess());
                }
                if (result.isEmpty()) continue;
                if (!seen.add(recipe.getId())) continue;
                idx.computeIfAbsent(result.getItem(), k -> new ArrayList<>())
                        .add(new RecipeEntry(recipe, ModType.GENERIC,
                                new ResourceLocation("minecraft:crafting")));
            }

            // 2. All mod recipes via class-name classification
            for (Recipe<?> recipe : rm.getRecipes()) {
                if (!seen.add(recipe.getId())) continue;
                ModType type = ModType.classifyRecipe(recipe);
                if (type == ModType.GENERIC) continue; // already in vanilla index

                ItemStack result = tryGetResultItem(recipe, level.registryAccess());
                if (result.isEmpty()) continue;

                // Unknown recipe type (not from 5 known magic mods) — index as
                // GENERIC so the resolver can propose it as an intermediate step
                if (type == null) {
                    type = ModType.GENERIC;
                }

                ResourceLocation typeId = recipe.getType() != null
                        ? ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType())
                        : null;
                if (typeId == null) typeId = new ResourceLocation("unknown");

                idx.computeIfAbsent(result.getItem(), k -> new ArrayList<>())
                        .add(new RecipeEntry(recipe, type, typeId));

                // Index by secondary outputs too so the resolver can discover
                // recipes that produce a needed item as a byproduct
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

    // ── result extraction ────────────────────────────────────────

    /**
     * Extract the output ItemStack from any recipe type via reflection.
     * Tries common method signatures in order.
     */
    public static ItemStack tryGetResultItem(Recipe<?> recipe, RegistryAccess access) {
        if (recipe == null) return ItemStack.EMPTY;
        if (recipe instanceof CraftingRecipe cr) {
            return cr.getResultItem(access);
        }
        // Try getResultItem(RegistryAccess) — standard Forge
        try {
            Object result = recipe.getClass().getMethod("getResultItem", RegistryAccess.class)
                    .invoke(recipe, access);
            if (result instanceof ItemStack s && !s.isEmpty()) return s;
        } catch (Exception ignored) {}
        // Try getResultItem() — no-arg variant
        try {
            Object result = recipe.getClass().getMethod("getResultItem").invoke(recipe);
            if (result instanceof ItemStack s && !s.isEmpty()) return s;
        } catch (Exception ignored) {}
        // Try getResult() — common in older/compat recipes
        try {
            Object result = recipe.getClass().getMethod("getResult").invoke(recipe);
            if (result instanceof ItemStack s && !s.isEmpty()) return s;
        } catch (Exception ignored) {}
        // Try getOutput() — some mods use this
        try {
            Object result = recipe.getClass().getMethod("getOutput").invoke(recipe);
            if (result instanceof ItemStack s && !s.isEmpty()) return s;
        } catch (Exception ignored) {}
        // Try getOutputCopy() — some mods return a copy
        try {
            Object result = recipe.getClass().getMethod("getOutputCopy").invoke(recipe);
            if (result instanceof ItemStack s && !s.isEmpty()) return s;
        } catch (Exception ignored) {}
        // Try getAssembledItem() — Goety RitualRecipe artifact
        try {
            Object result = recipe.getClass().getMethod("getAssembledItem").invoke(recipe);
            if (result instanceof ItemStack s && !s.isEmpty()) return s;
        } catch (Exception ignored) {}
        // Try accessing public 'output' or 'result' field of type ItemStack
        ItemStack fieldResult = tryGetOutputField(recipe);
        if (!fieldResult.isEmpty()) return fieldResult;

        RSIntegrationMod.LOGGER.debug("[ModRecipeIndex] Cannot extract result from {} (class: {})",
                recipe.getId(), recipe.getClass().getName());
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

    // ── secondary output extraction ────────────────────────────────

    /**
     * Extract secondary/byproduct outputs from any recipe type via reflection.
     * Tries common method signatures that mod recipes use for byproducts.
     */
    public static List<ItemStack> tryGetSecondaryOutputs(Recipe<?> recipe, RegistryAccess access) {
        List<ItemStack> results = new ArrayList<>();
        if (recipe == null) return results;

        // getRemainingItems() — returns List<ItemStack> or ItemStack[]
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

        // getByproducts() — common in modded recipes
        try {
            Object obj = recipe.getClass().getMethod("getByproducts").invoke(recipe);
            if (obj instanceof List<?> list) {
                for (Object e : list) {
                    if (e instanceof ItemStack s && !s.isEmpty()) results.add(s.copy());
                }
            }
        } catch (Exception ignored) {}

        // getRollResults() — Malum/WR weighted result lists
        try {
            Object obj = recipe.getClass().getMethod("getRollResults").invoke(recipe);
            if (obj instanceof List<?> list) {
                for (Object e : list) {
                    if (e instanceof ItemStack s && !s.isEmpty()) results.add(s.copy());
                }
            }
        } catch (Exception ignored) {}

        // getOutputs() — returns ItemStack[] or List<ItemStack>
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

        // Field scan for List<ItemStack> fields with byproduct-related names
        trySecondaryOutputFields(recipe, results);

        // Deduplicate by item type
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
