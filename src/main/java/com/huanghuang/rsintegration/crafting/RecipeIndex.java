package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.mods.farmingforblockheads.MarketRecipeWrapper;
import com.huanghuang.rsintegration.mods.forbidden.FaRitualWrapper;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.Diagnostics;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified recipe index replacing the split {@code CraftingPlanManager} +
 * {@code ModRecipeIndex} dual-index pattern.
 *
 * <p>Single build pass, delegates extraction to {@link ModRecipeHandler}
 * when available, producing {@code Map<Item, List<Entry>>} for both vanilla
 * and mod recipe lookups.</p>
 */
public final class RecipeIndex {

    public record Entry(Recipe<?> recipe, ModType modType, ResourceLocation recipeTypeId, boolean nbtSensitive) {
        public Entry(Recipe<?> recipe, ModType modType, ResourceLocation recipeTypeId) {
            this(recipe, modType, recipeTypeId, modType != ModType.GENERIC);
        }
    }

    private static volatile Map<Item, List<Entry>> index;
    private static volatile RecipeManager source;

    private RecipeIndex() {}

    public static Map<Item, List<Entry>> get(Level level) {
        RecipeManager rm = level.getRecipeManager();
        Map<Item, List<Entry>> idx = index;
        if (idx != null && source == rm) return idx;
        CraftPacketUtils.clearIngredientCache();

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
                    type = ModType.classifyRecipe(recipe);
                    if (type == null) type = handler.modType();
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

            // ── Market entries (MarketRegistry, not RecipeManager) ────
            int marketIndexed = indexMarketEntries(idx, seen);

            index = idx;
            source = rm;

            long elapsed = System.currentTimeMillis() - start;
            Diagnostics.stopTimer("RecipeIndex.build", diagTimer);
            Diagnostics.record(Diagnostics.Category.INDEX_BUILD,
                    idx.size() + " items, " + seen.size() + " entries, " + elapsed + "ms"
                    + " (skipped: " + skippedUnknown + " unknown, " + skippedEmptyResult
                    + " empty-result, " + skippedIdentity + " identity"
                    + ", " + faIndexed + " FA rituals"
                    + ", " + marketIndexed + " market)");
            RSIntegrationMod.LOGGER.info("[RecipeIndex] built: {} items, {} entries in {}ms"
                            + " (skipped: {} unknown, {} empty-result, {} identity"
                            + ", {} FA rituals, {} market)",
                    idx.size(), seen.size(), elapsed, skippedUnknown, skippedEmptyResult,
                    skippedIdentity, faIndexed, marketIndexed);
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
                        new ResourceLocation(ModIds.FORBIDDEN_ARCANUS, "hephaestus_forge"));
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
            RSIntegrationMod.LOGGER.warn("[RecipeIndex] FA upgrade output failed", e);
            return ItemStack.EMPTY;
        }
    }

    /** Fallback output for FA rituals whose {@code result()} is null or
     *  has an unrecognized type (e.g. {@code apply_eternal_modifier}). */
    @Nullable
    private static ItemStack rsi$faFallbackOutput(Object ritual, ResourceLocation id) {
        try {
            var m = Reflect.findMethod(ritual.getClass(), "mainIngredient", new Class<?>[0]);
            if (m == null) return ItemStack.EMPTY;
            Object main = m.invoke(ritual);
            if (main instanceof net.minecraft.world.item.crafting.Ingredient ing && !ing.isEmpty()) {
                ItemStack[] items = ing.getItems();
                if (items.length > 0 && !items[0].isEmpty()) {
                    RSIntegrationMod.LOGGER.debug("[RecipeIndex] FA fallback output for {}: {}",
                            id, items[0].getHoverName().getString());
                    return items[0].copy();
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RecipeIndex] FA fallback output failed for {}: {}",
                    id, e.toString());
        }
        return ItemStack.EMPTY;
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
                    Object result = getResult != null ? getResult.invoke(ritual) : null;

                    ItemStack output = ItemStack.EMPTY;
                    if (result != null && faCreateItemResultClass.isInstance(result)) {
                        Method getStack = Reflect.findMethod(result.getClass(),
                                "getResult", new Class<?>[0]);
                        if (getStack != null) {
                            Object s = getStack.invoke(result);
                            if (s instanceof ItemStack st && !st.isEmpty())
                                output = st;
                        }
                    } else if (result != null && faUpgradeTierResultClass != null
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
                        Entry entryObj = new Entry(wrapper, ModType.byId(ModIds.FORBIDDEN_ARCANUS),
                                new ResourceLocation(ModIds.FORBIDDEN_ARCANUS, "hephaestus_forge"));
                        idx.computeIfAbsent(output.getItem(),
                                k -> new ArrayList<>()).add(entryObj);
                        count++;
                        continue;
                    }

                    // Fallback: rituals that modify items in-place (e.g.
                    // apply_eternal_modifier) may have null result.  Use
                    // mainIngredient's first matching item as the output.
                    if (output.isEmpty()) {
                        output = rsi$faFallbackOutput(ritual, id);
                    }
                    if (output.isEmpty()) continue;

                    FaRitualWrapper wrapper = new FaRitualWrapper(id, ritual, output);
                    Entry entryObj = new Entry(wrapper, ModType.byId(ModIds.FORBIDDEN_ARCANUS),
                            new ResourceLocation(ModIds.FORBIDDEN_ARCANUS, "hephaestus_forge"));
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

    // ── Market entry indexing ──────────────────────────────────────

    private static volatile boolean marketClassesProbed;
    private static volatile boolean marketAvailable;
    private static volatile Object marketRegistryInst;

    private static void probeMarket() {
        if (marketClassesProbed) return;
        marketClassesProbed = true;
        try {
            Class<?> registryClass = Class.forName(
                    "net.blay09.mods.farmingforblockheads.registry.MarketRegistry");
            java.lang.reflect.Field instField = registryClass.getField("INSTANCE");
            marketRegistryInst = instField.get(null);
            marketAvailable = marketRegistryInst != null;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RecipeIndex] MarketRegistry not available: {}", e.toString());
            marketAvailable = false;
        }
    }

    private static int indexMarketEntries(Map<Item, List<Entry>> idx,
                                           Set<ResourceLocation> seen) {
        probeMarket();
        if (!marketAvailable || marketRegistryInst == null) return 0;
        int count = 0;
        try {
            Class<?> registryClass = marketRegistryInst.getClass();
            java.lang.reflect.Method getEntries = Reflect.findMethod(registryClass,
                    "getEntries", new Class<?>[0]);
            if (getEntries == null) return 0;
            @SuppressWarnings("unchecked")
            Collection<Object> entries = (Collection<Object>) getEntries.invoke(marketRegistryInst);
            if (entries == null) return 0;

            Class<?> entryClass = null;
            java.lang.reflect.Method getOutput = null;
            java.lang.reflect.Method getCost = null;
            java.lang.reflect.Method getEntryId = null;

            for (Object entry : entries) {
                if (entry == null) continue;
                try {
                    if (entryClass == null) {
                        entryClass = entry.getClass();
                        getOutput = Reflect.findMethod(entryClass, "getOutputItem", new Class<?>[0]);
                        getCost = Reflect.findMethod(entryClass, "getCostItem", new Class<?>[0]);
                        getEntryId = Reflect.findMethod(entryClass, "getEntryId", new Class<?>[0]);
                        if (getOutput == null || getCost == null || getEntryId == null) {
                            RSIntegrationMod.LOGGER.warn("[RecipeIndex] Market entry methods not found");
                            return 0;
                        }
                    }

                    ItemStack output = (ItemStack) getOutput.invoke(entry);
                    ItemStack cost = (ItemStack) getCost.invoke(entry);
                    UUID uuid = (UUID) getEntryId.invoke(entry);

                    if (output.isEmpty() || cost.isEmpty()) continue;

                    // Skip identity entries (cost == output) — would create circular deps
                    if (ItemStack.isSameItem(output, cost)) continue;

                    ResourceLocation rid = new ResourceLocation(ModIds.FARMINGFORBLOCKHEADS, "market/" + uuid);
                    if (!seen.add(rid)) continue;

                    MarketRecipeWrapper wrapper =
                            new MarketRecipeWrapper(uuid, output, cost);
                    ModType type = ModType.FARMINGFORBLOCKHEADS_MARKET;
                    Entry indexEntry = new Entry(wrapper, type,
                            new ResourceLocation(ModIds.FARMINGFORBLOCKHEADS, "market"));
                    idx.computeIfAbsent(output.getItem(), k -> new ArrayList<>()).add(indexEntry);
                    count++;
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.debug("[RecipeIndex] Failed to index market entry: {}", e.toString());
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RecipeIndex] Market entry scan failed: {}", e.toString());
        }
        return count;
    }

    /**
     * Returns true if the recipe's output item matches EVERY non-empty input
     * item type.  A true identity recipe (e.g. CraftTweater {@code .copy()})
     * creates circular auto-crafting dependencies and must be skipped.
     *
     * <p>Recipes where the output matches only SOME inputs (e.g. smithing
     * transform: template + weapon + addition → modified weapon) are
     * <em>not</em> identity — they consume a second distinct item and are
     * therefore transformative, even if the output item type happens to
     * match one of the input slots.</p>
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
        boolean anyNonEmpty = false;
        for (var ing : ingredients) {
            if (ing.isEmpty()) continue;
            anyNonEmpty = true;
            boolean found = false;
            for (ItemStack opt : ing.getItems()) {
                if (opt.getItem() == resultItem) {
                    found = true;
                    break;
                }
            }
            if (!found) return false; // distinct ingredient → transformative, not identity
        }
        return anyNonEmpty;
    }

    /** Invalidate the cached index (e.g. on recipe reload). */
    public static void invalidate() {
        index = null;
        source = null;
    }

    // ── result-item extraction (formerly in ModRecipeIndex) ─────

    private static final Map<Class<?>, Method> resultMethodCache = new ConcurrentHashMap<>();

    public static ItemStack tryGetResultItem(Recipe<?> recipe, RegistryAccess access) {
        if (recipe == null) return ItemStack.EMPTY;
        if (recipe instanceof CraftingRecipe cr) {
            return cr.getResultItem(access);
        }
        var handler = ModRecipeHandlers.handlerFor(recipe);
        if (handler != null) {
            ItemStack result = handler.getResultItem(recipe, access);
            if (!result.isEmpty()) return result;
            // Handler exists and returned EMPTY — don't fall through
            // to the reflection probe (same reason as ModRecipeHandlers).
            return ItemStack.EMPTY;
        }
        Class<?> clazz = recipe.getClass();
        Method m = resultMethodCache.get(clazz);
        if (m != null) {
            try {
                Object result;
                if (m.getParameterCount() == 1) {
                    result = m.invoke(recipe, access);
                } else {
                    result = m.invoke(recipe);
                }
                if (result instanceof ItemStack s && !s.isEmpty()) return s;
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        }
        for (String methodName : new String[]{"getResultItem", "getResult", "getOutput", "getOutputCopy", "getAssembledItem"}) {
            boolean isResultItem = "getResultItem".equals(methodName);
            for (Method method : clazz.getMethods()) {
                if (method.getName().equals(methodName)
                        && ItemStack.class.isAssignableFrom(method.getReturnType())
                        && method.getParameterCount() == 1) {
                    try {
                        Object result = method.invoke(recipe, access);
                        if (result instanceof ItemStack s && !s.isEmpty()) {
                            resultMethodCache.put(clazz, method);
                            return s;
                        }
                    } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
                }
            }
            // Skip no-arg getResultItem() — the deprecated overload that mods
            // abuse to return machine block icons.
            if (isResultItem) continue;
            for (Method method : clazz.getMethods()) {
                if (method.getName().equals(methodName)
                        && ItemStack.class.isAssignableFrom(method.getReturnType())
                        && method.getParameterCount() == 0) {
                    try {
                        Object result = method.invoke(recipe);
                        if (result instanceof ItemStack s && !s.isEmpty()) {
                            resultMethodCache.put(clazz, method);
                            return s;
                        }
                    } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
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
                String name = field.getName().toLowerCase(Locale.ROOT);
                if (name.contains("output") || name.contains("result") || name.contains("assembled")) {
                    field.setAccessible(true);
                    try {
                        Object val = field.get(recipe);
                        if (val instanceof ItemStack s && !s.isEmpty()) return s;
                    } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
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
            Method m = Reflect.findMethod(recipe.getClass(), "getRemainingItems", new Class<?>[0]);
            if (m != null) {
                Object obj = m.invoke(recipe);
                if (obj instanceof List<?> list) {
                    for (Object e : list) {
                        if (e instanceof ItemStack s && !s.isEmpty()) results.add(s.copy());
                    }
                } else if (obj instanceof ItemStack[] arr) {
                    for (ItemStack s : arr) {
                        if (!s.isEmpty()) results.add(s.copy());
                    }
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        try {
            Method m = Reflect.findMethod(recipe.getClass(), "getByproducts", new Class<?>[0]);
            if (m != null) {
                Object obj = m.invoke(recipe);
                if (obj instanceof List<?> list) {
                    for (Object e : list) {
                        if (e instanceof ItemStack s && !s.isEmpty()) results.add(s.copy());
                    }
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        try {
            Method m = Reflect.findMethod(recipe.getClass(), "getRollResults", new Class<?>[0]);
            if (m != null) {
                Object obj = m.invoke(recipe);
                if (obj instanceof List<?> list) {
                    for (Object e : list) {
                        if (e instanceof ItemStack s && !s.isEmpty()) results.add(s.copy());
                    }
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
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
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        trySecondaryOutputFields(recipe, results);
        return results;
    }

    @SuppressWarnings("unchecked")
    private static void trySecondaryOutputFields(Recipe<?> recipe, List<ItemStack> results) {
        Class<?> scan = recipe.getClass();
        while (scan != null && scan != Object.class) {
            for (java.lang.reflect.Field field : scan.getDeclaredFields()) {
                String name = field.getName().toLowerCase(Locale.ROOT);
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
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
            }
            scan = scan.getSuperclass();
        }
    }
}
