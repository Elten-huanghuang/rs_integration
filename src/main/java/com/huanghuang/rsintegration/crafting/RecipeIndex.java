package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.mods.forbidden.FaRitualWrapper;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.Diagnostics;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Unified recipe index replacing the split {@code CraftingPlanManager} +
 * {@code ModRecipeIndex} dual-index pattern.
 *
 * <p>Single build pass, delegates extraction to {@link ModRecipeHandler}
 * when available, producing {@code Map<Item, List<Entry>>} for both vanilla
 * and mod recipe lookups.</p>
 */
public final class RecipeIndex {

    public record Entry(Recipe<?> recipe, ModType modType, ResourceLocation recipeTypeId) {}

    private static volatile Map<Item, List<Entry>> index;
    private static volatile RecipeManager source;

    private RecipeIndex() {}

    public static Map<Item, List<Entry>> get(Level level) {
        RecipeManager rm = level.getRecipeManager();
        Map<Item, List<Entry>> idx = index;
        if (idx != null && source == rm) return idx;

        synchronized (RecipeIndex.class) {
            idx = index;
            if (idx != null && source == rm) return idx;

            long diagTimer = Diagnostics.startTimer();
            long start = System.currentTimeMillis();
            idx = new HashMap<>();
            Set<ResourceLocation> seen = new HashSet<>();
            int skippedUnknown = 0, skippedEmptyResult = 0, skippedNoHandler = 0, skippedIdentity = 0;

            for (Recipe<?> recipe : rm.getRecipes()) {
                if (!seen.add(recipe.getId())) continue;

                ModRecipeHandler handler = ModRecipeHandlers.handlerFor(recipe);
                ModType type;
                ItemStack result;

                if (handler != null) {
                    type = handler.modType();
                    result = ModRecipeHandlers.tryGetResultItem(recipe, level.registryAccess());
                } else if (recipe instanceof CraftingRecipe cr
                        && ModType.classifyRecipe(recipe) == null) {
                    // Vanilla / CraftTweaker / datapack crafting recipe — no handler needed
                    type = ModType.GENERIC;
                    result = cr.getResultItem(level.registryAccess());
                } else {
                    skippedUnknown++;
                    continue;
                }

                if (result.isEmpty()) {
                    skippedEmptyResult++;
                    continue;
                }

                // Filter identity recipes (output matches one of the inputs).
                // A .copy() recipe (e.g. CraftTweaker arcanum_lens → arcanum_lens)
                // creates circular dependencies and has no crafting value.
                if (isIdentityRecipe(recipe, result, handler)) {
                    skippedIdentity++;
                    continue;
                }

                ResourceLocation typeId = recipe.getType() != null
                        ? ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType())
                        : new ResourceLocation("minecraft:crafting");
                if (typeId == null) typeId = new ResourceLocation("minecraft:crafting");

                Entry entry = new Entry(recipe, type, typeId);
                idx.computeIfAbsent(result.getItem(), k -> new ArrayList<>()).add(entry);

                // Secondary outputs
                if (handler != null) {
                    for (ItemStack sec : handler.getSecondaryOutputs(recipe, level.registryAccess())) {
                        if (!sec.isEmpty()) {
                            idx.computeIfAbsent(sec.getItem(), k -> new ArrayList<>()).add(entry);
                        }
                    }
                }
            }

            // ── FA rituals (FARegistries.RITUAL, not RecipeManager) ──────
            int faIndexed = indexFARituals(level, idx, seen);

            index = idx;
            source = rm;

            long elapsed = System.currentTimeMillis() - start;
            Diagnostics.stopTimer("RecipeIndex.build", diagTimer);
            Diagnostics.record(Diagnostics.Category.INDEX_BUILD,
                    idx.size() + " items, " + seen.size() + " entries, " + elapsed + "ms"
                    + " (skipped: " + skippedUnknown + " unknown, " + skippedEmptyResult
                    + " empty-result, " + skippedIdentity + " identity"
                    + ", " + faIndexed + " FA rituals)");
            RSIntegrationMod.LOGGER.info("[RecipeIndex] built: {} items, {} entries in {}ms"
                            + " (skipped: {} unknown, {} empty-result, {} identity"
                            + ", {} FA rituals)",
                    idx.size(), seen.size(), elapsed, skippedUnknown, skippedEmptyResult,
                    skippedIdentity, faIndexed);
            return idx;
        }
    }

    // ── FA ritual indexing ──────────────────────────────────────

    private static volatile boolean faClassesProbed;
    private static volatile boolean faAvailable;
    private static volatile ResourceKey<?> faRitualKey;
    private static volatile Class<?> faCreateItemResultClass;
    private static volatile Class<?> faUpgradeTierResultClass;
    private static volatile Method faSetTierOnStack;
    private static volatile Item faForgeBlockItem;

    private static void probeFaClasses() {
        if (faClassesProbed) return;
        faClassesProbed = true;
        try {
            Class<?> faRegistries = Class.forName(
                    "com.stal111.forbidden_arcanus.core.registry.FARegistries");
            java.lang.reflect.Field f = faRegistries.getField("RITUAL");
            f.setAccessible(true);
            faRitualKey = (ResourceKey<?>) f.get(null);
            faCreateItemResultClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.ritual.result.CreateItemResult");
            faUpgradeTierResultClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.ritual.result.UpgradeTierResult");
            faAvailable = true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RecipeIndex] FA classes not available: {}", e.toString());
            faAvailable = false;
        }
    }

    /** Create a HephaestusForgeBlock ItemStack with {@code upgradedTier}
     *  applied via {@code setTierOnStack}, matching FA's own JEI display. */
    private static ItemStack rsi$makeFaUpgradeOutput(int upgradedTier) {
        try {
            if (faForgeBlockItem == null) {
                net.minecraft.world.level.block.Block block = ForgeRegistries.BLOCKS.getValue(
                        new ResourceLocation("forbidden_arcanus", "hephaestus_forge"));
                if (block == null) return ItemStack.EMPTY;
                faForgeBlockItem = block.asItem();
            }
            if (faSetTierOnStack == null) {
                Class<?> hfbClass = Class.forName(
                        "com.stal111.forbidden_arcanus.common.block.HephaestusForgeBlock");
                faSetTierOnStack = Reflect.findMethod(hfbClass, "setTierOnStack",
                        new Class<?>[]{ItemStack.class, int.class});
            }
            if (faSetTierOnStack == null) return ItemStack.EMPTY;
            ItemStack stack = new ItemStack(faForgeBlockItem);
            return (ItemStack) faSetTierOnStack.invoke(null, stack, upgradedTier);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RecipeIndex] FA upgrade output failed", e);
            return ItemStack.EMPTY;
        }
    }

    /**
     * Scan {@code FARegistries.RITUAL} and add item-producing rituals
     * to the index.  Returns the number of rituals indexed.
     */
    @SuppressWarnings("unchecked")
    private static int indexFARituals(Level level, Map<Item, List<Entry>> idx,
                                      Set<ResourceLocation> seen) {
        probeFaClasses();
        if (!faAvailable || faRitualKey == null) return 0;
        int count = 0;
        try {
            net.minecraft.core.Registry<Object> faRegistry =
                    (net.minecraft.core.Registry<Object>)
                    level.registryAccess().registryOrThrow(
                            (ResourceKey<? extends net.minecraft.core.Registry<Object>>)
                            (Object) faRitualKey);

            for (var entry : faRegistry.entrySet()) {
                ResourceLocation id = entry.getKey().location();
                Object ritual = entry.getValue();
                if (ritual == null || !seen.add(id)) continue;
                try {
                    Method getResult = Reflect.findMethod(ritual.getClass(),
                            "result", new Class<?>[0]);
                    if (getResult == null) continue;
                    Object result = getResult.invoke(ritual);
                    if (result == null) continue;

                    ItemStack output = ItemStack.EMPTY;
                    if (faCreateItemResultClass.isInstance(result)) {
                        Method getStack = Reflect.findMethod(result.getClass(),
                                "getResult", new Class<?>[0]);
                        if (getStack != null) {
                            Object s = getStack.invoke(result);
                            if (s instanceof ItemStack st && !st.isEmpty())
                                output = st;
                        }
                    } else if (faUpgradeTierResultClass != null
                            && faUpgradeTierResultClass.isInstance(result)) {
                        int from = 0, to = 0;
                        try {
                            Method getFrom = Reflect.findMethod(result.getClass(), "getRequiredTier", new Class<?>[0]);
                            Method getTo = Reflect.findMethod(result.getClass(), "getUpgradedTier", new Class<?>[0]);
                            if (getFrom != null) from = (int) getFrom.invoke(result);
                            if (getTo != null) to = (int) getTo.invoke(result);
                        } catch (Exception ignored) {}
                        output = rsi$makeFaUpgradeOutput(to);
                        if (output.isEmpty()) continue;
                        FaRitualWrapper wrapper = new FaRitualWrapper(id, ritual, output, from, to);
                        Entry entryObj = new Entry(wrapper, ModType.FORBIDDEN_ARCANUS,
                                new ResourceLocation("forbidden_arcanus", "hephaestus_forge"));
                        idx.computeIfAbsent(output.getItem(),
                                k -> new ArrayList<>()).add(entryObj);
                        count++;
                        continue;
                    }
                    if (output.isEmpty()) continue;

                    FaRitualWrapper wrapper = new FaRitualWrapper(id, ritual, output);
                    Entry entryObj = new Entry(wrapper, ModType.FORBIDDEN_ARCANUS,
                            new ResourceLocation("forbidden_arcanus", "hephaestus_forge"));
                    idx.computeIfAbsent(output.getItem(),
                            k -> new ArrayList<>()).add(entryObj);
                    count++;
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.debug("[RecipeIndex] Failed to index FA ritual {}: {}",
                            id, e.toString());
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RecipeIndex] FA ritual scan failed: {}", e.toString());
        }
        return count;
    }

    /**
     * Returns true if the recipe's output item matches any of its input
     * items.  Identity recipes (e.g. CraftTweater {@code .copy()} recipes)
     * create circular auto-crafting dependencies and are skipped.
     */
    private static boolean isIdentityRecipe(Recipe<?> recipe, ItemStack result,
                                            ModRecipeHandler handler) {
        Item resultItem = result.getItem();
        List<net.minecraft.world.item.crafting.Ingredient> ingredients;
        if (handler != null) {
            var specs = handler.getIngredients(recipe);
            if (specs == null) return false;
            ingredients = new ArrayList<>();
            for (var spec : specs) {
                if (!spec.isEmpty()) ingredients.add(spec.ingredient());
            }
        } else {
            ingredients = recipe.getIngredients();
        }
        for (var ing : ingredients) {
            for (ItemStack opt : ing.getItems()) {
                if (opt.getItem() == resultItem) return true;
            }
        }
        return false;
    }

    /** Invalidate the cached index (e.g. on recipe reload). */
    public static void invalidate() {
        index = null;
        source = null;
    }
}
