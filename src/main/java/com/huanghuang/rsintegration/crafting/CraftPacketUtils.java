package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.binding.BindingStorage;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.CraftLogContext;
import com.huanghuang.rsintegration.util.PlayerUtils;
import com.huanghuang.rsintegration.util.Reflect;
import com.huanghuang.rsintegration.util.TextBuilder;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class CraftPacketUtils {

    private CraftPacketUtils() {}

    private static final Map<ResourceLocation, List<Ingredient>> ingredientCache = new ConcurrentHashMap<>();
    private static final Set<ResourceLocation> emptyIngredientMarkers = ConcurrentHashMap.newKeySet();
    /** Caches the ingredient-list Field per recipe class for scanAllFieldsForIngredients. */
    private static final Map<Class<?>, java.lang.reflect.Field> ingredientFieldCache = new ConcurrentHashMap<>();
    private static final java.lang.reflect.Field NO_INGREDIENT_FIELD;
    static {
        try { NO_INGREDIENT_FIELD = CraftPacketUtils.class.getDeclaredField("ingredientCache"); }
        catch (NoSuchFieldException e) { throw new RuntimeException(e); }
    }

    /** Clear ingredient extraction cache — called when recipes are reloaded. */
    public static void clearIngredientCache() {
        ingredientCache.clear();
        emptyIngredientMarkers.clear();
        ingredientFieldCache.clear();
    }

    // ── shared utilities used by all craft packets ──────────────

    @Nullable
    public static ServerLevel resolveLevel(@Nonnull MinecraftServer server, @Nullable ResourceLocation dim,
                                           @Nonnull ServerPlayer player) {
        if (dim != null) {
            ResourceKey<Level> key = ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION, dim);
            ServerLevel level = server.getLevel(key);
            if (level != null) return level;
        }
        return (ServerLevel) player.level();
    }

    public static Component describeIngredient(Ingredient ingredient) {
        for (ItemStack stack : ingredient.getItems()) {
            if (!stack.isEmpty()) return stack.getHoverName();
        }
        return Component.literal("Unknown Item");
    }

    /** Merge duplicate item names with counts: "大地精魂, 大地精魂, 大地精魂" → "大地精魂 x3".
     *  Caps output at ~120 chars to avoid flooding chat with an unreadable wall. */
    @Nonnull
    public static String formatMissingSummary(@Nonnull List<String> missing) {
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String name : missing) {
            counts.merge(name, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        int shown = 0;
        int total = counts.size();
        for (java.util.Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(entry.getKey());
            if (entry.getValue() > 1) {
                sb.append(" x").append(entry.getValue());
            }
            shown++;
            if (sb.length() > 120 && shown < total) {
                sb.append(" ").append(Component.translatable("rsi.plan.missing_more", total - shown).getString());
                break;
            }
        }
        return sb.toString();
    }

    /**
     * Execute crafting steps using virtual inventory forward-feeding.
     *
     * Intermediate step outputs are held in a virtual list so later steps
     * consume them first, avoiding real RS network extraction for items
     * that were just produced.  Reduces RS I/O by 50-90% for typical chains.
     *
     * Ledger entries are committed atomically at the end.
     * On any failure, nothing is physically moved.
     */
    public static boolean executeCraftingSteps(@Nonnull ServerPlayer player,
                                               @Nonnull List<CraftingResolver.ResolutionStep> steps,
                                               @Nonnull INetwork network) {
        RecipeManager rm = player.serverLevel().getRecipeManager();
        ResourceLocation primaryRecipe = steps.isEmpty() ? new ResourceLocation("rsintegration", "empty_chain")
                : steps.get(0).recipeId();
        CraftLogContext ctx = CraftLogContext.create(player.getUUID(), primaryRecipe);
        try (ExtractionLedger ledger = new ExtractionLedger()) {
            ledger.setLogContext(ctx);
            List<ItemStack> virtualInventory = new ArrayList<>();

            RSIntegrationMod.LOGGER.debug(ctx.format("Starting {} steps"), steps.size());

            // Phase 1: process each step, feeding outputs forward into virtual inventory
            for (int stepIdx = 0; stepIdx < steps.size(); stepIdx++) {
                CraftingResolver.ResolutionStep rstep = steps.get(stepIdx);
                ResourceLocation stepId = rstep.recipeId();
                int executions = rstep.executions();
                Recipe<?> recipe = rm.byKey(stepId).orElse(null);
                if (recipe == null) {
                    RSIntegrationMod.LOGGER.debug(ctx.format("Step {}/{} {}: recipe not found"), stepIdx + 1, steps.size(), stepId);
                    continue;
                }

                RSIntegrationMod.LOGGER.debug(ctx.format("Step {}/{} {} x{} type={} virtualInvBefore={}"),
                        stepIdx + 1, steps.size(), stepId, executions, recipe.getClass().getSimpleName(),
                        virtualInventory.stream().map(s -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()) + "x" + s.getCount()).toList());

                if (recipe instanceof CraftingRecipe craftingRecipe) {
                    List<Ingredient> ingredients = craftingRecipe.getIngredients();
                    ItemStack[] consumed = new ItemStack[ingredients.size()];
                    for (int idx = 0; idx < ingredients.size(); idx++) {
                        Ingredient ing = ingredients.get(idx);
                        if (ing.isEmpty()) continue;

                        int stillNeeded = executions;
                        var iter = virtualInventory.iterator();
                        while (iter.hasNext() && stillNeeded > 0) {
                            ItemStack vItem = iter.next();
                            if (ing.test(vItem) && matchesIngredientTags(vItem, ing)) {
                                int take = Math.min(stillNeeded, vItem.getCount());
                                consumed[idx] = vItem.copyWithCount(1);
                                vItem.shrink(take);
                                stillNeeded -= take;
                                if (vItem.isEmpty()) iter.remove();
                            }
                        }

                        if (stillNeeded > 0) {
                            ItemStack reserved = ledger.reserveFromNetwork(ing, stillNeeded, network);
                            if (reserved.isEmpty()) {
                                reserved = ledger.reserveFromInventory(ing, stillNeeded, player);
                            }
                            if (reserved.isEmpty()) {
                                RSIntegrationMod.LOGGER.error(ctx.format("Step {}/{} {}: missing ingredient, aborting"),
                                        stepIdx + 1, steps.size(), stepId);
                                return false;
                            }
                            consumed[idx] = reserved.copyWithCount(1);
                            stillNeeded -= reserved.getCount();
                            RSIntegrationMod.LOGGER.debug(ctx.format("Step {}/{} {}: reserved {} from ledger"),
                                    stepIdx + 1, steps.size(), stepId,
                                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(reserved.getItem()) + "x" + reserved.getCount());
                        }
                    }

                    ItemStack result;
                    if (isSlashBladeRecipe(craftingRecipe)) {
                        result = assembleBladeOutput(craftingRecipe, consumed, player);
                    } else {
                        result = craftingRecipe.getResultItem(player.serverLevel().registryAccess());
                    }
                    if (!result.isEmpty()) {
                        addToVirtual(virtualInventory, result.copyWithCount(result.getCount() * executions));
                        RSIntegrationMod.LOGGER.debug(ctx.format("Step {}/{} {}: produced {} to virtual"),
                                stepIdx + 1, steps.size(), stepId,
                                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(result.getItem()) + "x" + (result.getCount() * executions));
                    }
                    // Add secondary outputs (byproducts) to virtual inventory.
                    // For CraftingRecipe, getRecipeRemainders (Forge API) already
                    // handles all remainders correctly — reflection-based scanning
                    // would duplicate them, causing a dupe exploit with buckets etc.
                    for (ItemStack remainder : getRecipeRemainders(craftingRecipe, consumed)) {
                        addToVirtual(virtualInventory, remainder.copyWithCount(remainder.getCount() * executions));
                    }
                } else {
                    // Non-crafting recipe (sawmill, custom mod type, etc.)
                    List<IngredientSpec> specs = extractIngredientSpecs(recipe);
                    if (specs == null || specs.isEmpty()) {
                        RSIntegrationMod.LOGGER.debug(ctx.format("Step {}/{} {}: no ingredient specs, skipping"),
                                stepIdx + 1, steps.size(), stepId);
                        continue;
                    }

                    for (IngredientSpec spec : specs) {
                        if (spec.isEmpty()) continue;
                        int stillNeeded = spec.count() * executions;
                        var iter = virtualInventory.iterator();
                        while (iter.hasNext() && stillNeeded > 0) {
                            ItemStack vItem = iter.next();
                            if (spec.ingredient().test(vItem) && matchesIngredientTags(vItem, spec.ingredient())) {
                                int take = Math.min(stillNeeded, vItem.getCount());
                                vItem.shrink(take);
                                stillNeeded -= take;
                                if (vItem.isEmpty()) iter.remove();
                            }
                        }
                        if (stillNeeded > 0) {
                            ItemStack[] opts = spec.ingredient().getItems();
                            RSIntegrationMod.LOGGER.debug(ctx.format("Step {}/{} {}: need {} more of {}, reserving from ledger..."),
                                    stepIdx + 1, steps.size(), stepId, stillNeeded,
                                    opts.length > 0 ? net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(opts[0].getItem()) : "?");
                            ItemStack reserved = ledger.reserveFromNetwork(spec.ingredient(), stillNeeded, network);
                            if (reserved.isEmpty()) {
                                reserved = ledger.reserveFromInventory(spec.ingredient(), stillNeeded, player);
                            }
                            if (reserved.isEmpty()) {
                                RSIntegrationMod.LOGGER.error(ctx.format("Step {}/{} {}: missing ingredient, aborting"),
                                        stepIdx + 1, steps.size(), stepId);
                                return false;
                            }
                            stillNeeded -= reserved.getCount();
                            RSIntegrationMod.LOGGER.debug(ctx.format("Step {}/{} {}: reserved {} from ledger (stillNeeded={})"),
                                    stepIdx + 1, steps.size(), stepId,
                                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(reserved.getItem()) + "x" + reserved.getCount(),
                                    stillNeeded);
                        }
                    }

                    ItemStack result = RecipeIndex.tryGetResultItem(recipe, player.serverLevel().registryAccess());
                    if (!result.isEmpty()) {
                        addToVirtual(virtualInventory, result.copyWithCount(result.getCount() * executions));
                        RSIntegrationMod.LOGGER.debug(ctx.format("Step {}/{} {}: produced {} to virtual"),
                                stepIdx + 1, steps.size(), stepId,
                                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(result.getItem()) + "x" + (result.getCount() * executions));
                    }
                    // Add secondary outputs (byproducts) to virtual inventory
                    for (ItemStack secondary : RecipeIndex.tryGetSecondaryOutputs(recipe, player.serverLevel().registryAccess())) {
                        addToVirtual(virtualInventory, secondary.copyWithCount(secondary.getCount() * executions));
                    }
                    if (result.isEmpty()) {
                        RSIntegrationMod.LOGGER.debug(ctx.format("Step {}/{} {}: result empty, nothing added to virtual"),
                                stepIdx + 1, steps.size(), stepId);
                    }
                }
            }

            // Phase 2: commit all real extractions atomically
            if (!ledger.commit(network, player)) {
                RSIntegrationMod.LOGGER.error(ctx.format("executeCraftingSteps: commit failed for {} steps"), steps.size());
                return false;
            }

            // Phase 3: flush remaining virtual inventory into RS network;
            // any remainder that doesn't fit goes to the player (split into
            // max-stack-size chunks to prevent item-entity explosions).
            for (ItemStack vi : virtualInventory) {
                if (!vi.isEmpty()) {
                    var tracker = network.getItemStorageTracker();
                    if (tracker != null) tracker.changed(player, vi.copy());
                    ItemStack remainder = network.insertItem(vi.copy(), vi.getCount(),
                            com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    if (!remainder.isEmpty()) {
                        PlayerUtils.safeGiveToPlayer(player, remainder, network);
                    }
                }
            }

            return true;
        }
    }

    /**
     * When a virtual inventory item carries NBT, verify that at least one
     * variant of the ingredient also specifies matching tags.  Some mod
     * ingredients match on item type alone (ignoring NBT), and feeding a
     * tag-mismatched intermediate item to a real machine would cause the
     * recipe to fail — detection here avoids "the plan said OK but the
     * machine rejected it" bugs.
     */
    private static boolean matchesIngredientTags(ItemStack candidate, Ingredient ingredient) {
        if (!candidate.hasTag()) return true;
        for (ItemStack opt : ingredient.getItems()) {
            if (ItemStack.isSameItemSameTags(candidate, opt)) return true;
        }
        // Custom ingredients (e.g. SlashBladeIngredient) use ingredient.test()
        // for real NBT validation but return bare template items from getItems().
        // ingredient.test() already passed — trust it if the item type matches.
        Item candidateItem = candidate.getItem();
        for (ItemStack opt : ingredient.getItems()) {
            if (opt.getItem() == candidateItem) return true;
        }
        return false;
    }

    private static void addToVirtual(List<ItemStack> virtualInventory, ItemStack result) {
        for (ItemStack vi : virtualInventory) {
            if (ItemStack.isSameItemSameTags(vi, result)) {
                vi.grow(result.getCount());
                return;
            }
        }
        virtualInventory.add(result.copy());
    }

    /**
     * Get crafting remainders using the <b>actually consumed</b> items.
     * This is critical for durability-based remainders (tools, weapons) —
     * calling {@code getCraftingRemainingItem()} on a fresh template item
     * would ignore damage and produce incorrect remainders.
     *
     * @param consumed the items that were actually placed in the crafting grid
     */
    public static List<ItemStack> getRecipeRemainders(CraftingRecipe recipe, ItemStack[] consumed) {
        List<Ingredient> ingredients = recipe.getIngredients();
        AbstractContainerMenu dummyMenu = new AbstractContainerMenu(null, -1) {
            @Override public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player p, int i) { return ItemStack.EMPTY; }
            @Override public boolean stillValid(net.minecraft.world.entity.player.Player p) { return false; }
        };
        var container = new net.minecraft.world.inventory.TransientCraftingContainer(dummyMenu, 3, 3);
        int slots = Math.min(ingredients.size(), 9);
        for (int i = 0; i < slots; i++) {
            if (consumed[i] != null && !consumed[i].isEmpty()) {
                container.setItem(i, consumed[i].copy());
            } else if (!ingredients.get(i).isEmpty()) {
                ItemStack[] items = ingredients.get(i).getItems();
                if (items.length > 0 && !items[0].isEmpty()) {
                    container.setItem(i, items[0].copy());
                }
            }
        }
        NonNullList<ItemStack> allRemainders = recipe.getRemainingItems(container);
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack r : allRemainders) {
            if (!r.isEmpty()) result.add(r.copy());
        }
        return result;
    }

    /**
     * Get crafting remainders using template items from the ingredient list.
     * Prefer {@link #getRecipeRemainders(CraftingRecipe, ItemStack[])} when
     * actual consumed items are available, to correctly handle durability.
     */
    public static List<ItemStack> getRecipeRemainders(CraftingRecipe recipe) {
        List<Ingredient> ingredients = recipe.getIngredients();
        AbstractContainerMenu dummyMenu = new AbstractContainerMenu(null, -1) {
            @Override public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player p, int i) { return ItemStack.EMPTY; }
            @Override public boolean stillValid(net.minecraft.world.entity.player.Player p) { return false; }
        };
        var container = new net.minecraft.world.inventory.TransientCraftingContainer(dummyMenu, 3, 3);
        for (int i = 0; i < Math.min(ingredients.size(), 9); i++) {
            Ingredient ing = ingredients.get(i);
            if (!ing.isEmpty()) {
                ItemStack[] items = ing.getItems();
                if (items.length > 0 && !items[0].isEmpty()) {
                    container.setItem(i, items[0].copy());
                }
            }
        }
        NonNullList<ItemStack> allRemainders = recipe.getRemainingItems(container);
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack r : allRemainders) {
            if (!r.isEmpty()) result.add(r.copy());
        }
        return result;
    }

    public static boolean isSlashBladeRecipe(CraftingRecipe recipe) {
        String name = recipe.getClass().getName();
        return name.startsWith("mods.flammpfeil.slashblade.recipe.SlashBlade");
    }

    /**
     * Assemble a SlashBlade recipe output by feeding the real consumed items
     * into a 3×3 crafting container and calling {@code recipe.assemble()}.
     * This transfers input blade stats (ProudSoul, KillCount, Refine,
     * enchantments) to the output, which {@code getResultItem()} skips.
     */
    private static ItemStack assembleBladeOutput(CraftingRecipe recipe, ItemStack[] consumed,
                                                  ServerPlayer player) {
        AbstractContainerMenu dummyMenu = new AbstractContainerMenu(null, -1) {
            @Override public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player p, int i) { return ItemStack.EMPTY; }
            @Override public boolean stillValid(net.minecraft.world.entity.player.Player p) { return false; }
        };
        var container = new net.minecraft.world.inventory.TransientCraftingContainer(dummyMenu, 3, 3);
        for (int i = 0; i < consumed.length && i < 9; i++) {
            if (consumed[i] != null && !consumed[i].isEmpty()) {
                container.setItem(i, consumed[i].copy());
            }
        }
        return recipe.assemble(container, player.serverLevel().registryAccess());
    }

    /**
     * Extract items matching {@code ingredient} from the player's main inventory,
     * aggregating across multiple stacks when no single stack has enough.
     * Uses a two-pass approach: first verify total availability, then extract,
     * so partial extraction never needs to be rolled back.
     */
    private static ItemStack extractFromPlayerInventoryAggregated(ServerPlayer player, Ingredient ingredient, int count) {
        // First pass: verify enough total items exist
        int total = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (ingredient.test(stack) && stack.getCount() > 0) {
                total += stack.getCount();
                if (total >= count) break;
            }
        }
        if (total < count) return ItemStack.EMPTY;

        // Second pass: extract with confidence
        ItemStack aggregated = ItemStack.EMPTY;
        int stillNeeded = count;
        for (ItemStack stack : player.getInventory().items) {
            if (ingredient.test(stack) && stack.getCount() > 0) {
                int take = Math.min(stillNeeded, stack.getCount());
                if (aggregated.isEmpty()) {
                    aggregated = stack.split(take);
                } else {
                    stack.shrink(take);
                    aggregated.grow(take);
                }
                stillNeeded -= take;
                if (stillNeeded <= 0) {
                    player.getInventory().setChanged();
                    player.inventoryMenu.broadcastChanges();
                    return aggregated;
                }
            }
        }
        return ItemStack.EMPTY; // Unreachable — first pass verified sufficiency
    }

    // ── ingredient extraction helpers ───────────────────────────

    @Nullable
    @SuppressWarnings("unchecked")
    public static List<Ingredient> extractIngredients(Object recipe) {
        Class<?> clazz = recipe.getClass();
        ResourceLocation recipeId = recipe instanceof Recipe<?> r ? r.getId() : null;

        if (recipeId != null) {
            List<Ingredient> cached = ingredientCache.get(recipeId);
            if (cached != null) return cached;
            if (emptyIngredientMarkers.contains(recipeId)) return null;
        }

        List<Ingredient> result = tryGetIngredients(recipe, "getIngredients");
        if (result != null) return cacheAndReturn(recipeId, filterWRCrystal(recipe, result));

        Class<?> scan = clazz;
        while (scan != null && scan != Object.class) {
            for (java.lang.reflect.Field field : scan.getDeclaredFields()) {
                if (field.getName().equals("ingredients")
                        && List.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    try {
                        List<?> list = (List<?>) field.get(recipe);
                        if (!list.isEmpty() && list.get(0) instanceof Ingredient) {
                            return cacheAndReturn(recipeId, filterWRCrystal(recipe, (List<Ingredient>) list));
                        }
                    } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
                }
            }
            scan = scan.getSuperclass();
        }

        for (String name : new String[]{"getInputs", "getInputItems"}) {
            result = tryGetIngredients(recipe, name);
            if (result != null) return cacheAndReturn(recipeId, filterWRCrystal(recipe, result));
        }

        result = tryExtractStepBasedIngredients(recipe);
        if (result != null) return cacheAndReturn(recipeId, result);

        result = tryExtractRitualBasedIngredients(recipe);
        if (result != null) return cacheAndReturn(recipeId, result);

        result = scanAllFieldsForIngredients(recipe);
        if (result != null) return cacheAndReturn(recipeId, filterWRCrystal(recipe, result));

        result = extractLodestoneIngredients(recipe);
        if (result != null) result = filterWRCrystal(recipe, result);
        if (result != null) return cacheAndReturn(recipeId, result);

        if (recipeId != null) emptyIngredientMarkers.add(recipeId);
        return null;
    }

    private static List<Ingredient> cacheAndReturn(ResourceLocation recipeId, List<Ingredient> result) {
        if (result == null || recipeId == null) return result;
        List<Ingredient> immutable = Collections.unmodifiableList(result);
        ingredientCache.put(recipeId, immutable);
        return immutable;
    }

    /**
     * WR crystal infusion/ritual recipes: the crystal stays in the ritual block
     * as a catalyst and must NOT be extracted from RS as a material.
     * <p>
     * Primary strategy: use the ritual's {@code getCrystalType(ItemStack)} which
     * does {@code item instanceof CrystalItem} internally — the same check WR
     * uses at runtime.
     * <p>
     * Fallback (no ritual available): detect crystal items by their class
     * (CrystalItem / FracturedCrystalItem / PrecisionCrystalItem).
     */
    private static List<Ingredient> filterWRCrystal(Object recipe, List<Ingredient> ingredients) {
        String className = recipe.getClass().getName();
        boolean isWR = className.startsWith("mod.maxbogomol.wizards_reborn.");

        // Only filter WR recipes; for everything else return unfiltered
        if (!isWR) return ingredients;

        // WissenCrystallizerRecipe: fractured crystals are the actual
        // consumed input materials, not catalysts. Do NOT strip them.
        if (className.endsWith("WissenCrystallizerRecipe")) return ingredients;

        // ArcaneIteratorRecipe: crystal goes on a pedestal as a consumable
        // input (the Arcane Iterator has no separate crystal block).
        // Do NOT strip crystals — the delegate must extract and place them.
        if (className.endsWith("ArcaneIteratorRecipe")) {
            RSIntegrationMod.LOGGER.debug("[RSI] filterWRCrystal: ArcaneIteratorRecipe — keeping all {} ingredients (crystal goes on pedestal)",
                    ingredients.size());
            return ingredients;
        }

        java.util.Set<Item> crystalItems = new java.util.HashSet<>();

        // CrystalRitualRecipe has getRitual(); CrystalInfusionRecipe has neither.
        // For both, the crystal sits in a separate Crystal block as a catalyst
        // and must NOT be extracted from RS as a material.
        Object ritual = null;
        java.lang.reflect.Method getCrystalType = null;
        try {
            java.lang.reflect.Method getRitual = Reflect.findMethod(
                    recipe.getClass(), "getRitual", new Class<?>[0]);
            if (getRitual != null) {
                ritual = getRitual.invoke(recipe);
                if (ritual != null) {
                    getCrystalType = Reflect.findMethod(ritual.getClass(),
                            "getCrystalType", new Class<?>[]{ItemStack.class});
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI] WR ritual access failed", e);
        }

        if (getCrystalType != null && ritual != null) {
            // Use the official WR API to identify crystal items
            for (Ingredient ing : ingredients) {
                for (ItemStack is : ing.getItems()) {
                    try {
                        Object ct = getCrystalType.invoke(ritual, is);
                        if (ct != null) {
                            crystalItems.add(is.getItem());
                        }
                    } catch (Exception e) {
                        RSIntegrationMod.LOGGER.warn("[RSI] Crystal type detection failed", e);
                    }
                }
            }
        }

        // Fallback: use CrystalHandler.getItems() — WR's official crystal registry
        if (crystalItems.isEmpty()) {
            try {
                Class<?> handlerClass = Class.forName(
                        "mod.maxbogomol.wizards_reborn.api.crystal.CrystalHandler");
                java.lang.reflect.Method getItems = handlerClass.getMethod("getItems");
                @SuppressWarnings("unchecked")
                java.util.ArrayList<Item> registered = (java.util.ArrayList<Item>) getItems.invoke(null);
                if (registered != null) {
                    crystalItems.addAll(registered);
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI] CrystalHandler.getItems failed", e);
            }
        }

        // Class-based detection — catches any WR crystal item regardless
        // of recipe type (CrystalRitualRecipe, CrystalInfusionRecipe, etc.).
        if (crystalItems.isEmpty()) {
            try {
                Class<?> crystalItemClass = Class.forName(
                        "mod.maxbogomol.wizards_reborn.common.item.CrystalItem");
                Class<?> fracturedClass = Class.forName(
                        "mod.maxbogomol.wizards_reborn.common.item.FracturedCrystalItem");
                Class<?> precisionClass = Class.forName(
                        "mod.maxbogomol.wizards_reborn.common.item.PrecisionCrystalItem");
                for (Ingredient ing : ingredients) {
                    for (ItemStack is : ing.getItems()) {
                        Item item = is.getItem();
                        if (crystalItemClass.isInstance(item)
                                || fracturedClass.isInstance(item)
                                || precisionClass.isInstance(item)) {
                            crystalItems.add(item);
                        }
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI] WR crystal class detection failed", e);
            }
        }

        if (crystalItems.isEmpty()) {
            RSIntegrationMod.LOGGER.debug("[RSI] filterWRCrystal: no crystal items identified for recipe class {}, returning {} unfiltered ingredients",
                    className, ingredients.size());
            return ingredients;
        }

        List<Ingredient> filtered = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            boolean isCrystal = false;
            for (ItemStack is : ing.getItems()) {
                if (crystalItems.contains(is.getItem())) { isCrystal = true; break; }
            }
            if (!isCrystal) filtered.add(ing);
        }
        RSIntegrationMod.LOGGER.debug("[RSI] filterWRCrystal: recipe={}, removed {} crystal ingredient(s), kept {} material ingredient(s)",
                className, ingredients.size() - filtered.size(), filtered.size());
        return filtered;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static List<Ingredient> tryGetIngredients(Object recipe, String methodName) {
        try {
            Object result = recipe.getClass().getMethod(methodName).invoke(recipe);
            if (result instanceof List<?> list) {
                if (!list.isEmpty()) {
                    if (list.get(0) instanceof Ingredient) return (List<Ingredient>) list;
                    if (list.get(0) instanceof ItemStack) {
                        List<Ingredient> wrapped = new ArrayList<>();
                        for (Object obj : list) {
                            if (obj instanceof ItemStack stack) wrapped.add(CraftingResolver.ingredientOf(stack, stack.hasTag()));
                        }
                        if (!wrapped.isEmpty()) return wrapped;
                    }
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        return null;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static List<Ingredient> scanAllFieldsForIngredients(Object recipe) {
        Class<?> clazz = recipe.getClass();
        java.lang.reflect.Field cached = ingredientFieldCache.get(clazz);
        if (cached != null) {
            if (cached == NO_INGREDIENT_FIELD) return null;
            try {
                List<?> list = (List<?>) cached.get(recipe);
                if (list != null && !list.isEmpty() && list.get(0) instanceof Ingredient) {
                    return (List<Ingredient>) list;
                }
            } catch (Exception e) { /* fall through to rescan */ }
        }
        Class<?> scan = clazz;
        while (scan != null && scan != Object.class) {
            for (java.lang.reflect.Field field : scan.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                try {
                    List<?> list = (List<?>) field.get(recipe);
                    if (list != null && !list.isEmpty() && list.get(0) instanceof Ingredient) {
                        ingredientFieldCache.put(clazz, field);
                        return (List<Ingredient>) list;
                    }
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
            }
            scan = scan.getSuperclass();
        }
        ingredientFieldCache.put(clazz, NO_INGREDIENT_FIELD);
        return null;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static List<Ingredient> tryExtractStepBasedIngredients(Object recipe) {
        try {
            java.lang.reflect.Method stepsMethod = recipe.getClass().getMethod("getSteps");
            List<?> steps = (List<?>) stepsMethod.invoke(recipe);
            if (steps == null || steps.isEmpty()) return null;
            List<Ingredient> all = new ArrayList<>();
            for (Object step : steps) {
                try {
                    java.lang.reflect.Field matchesField = step.getClass().getField("matches");
                    List<Ingredient> matches = (List<Ingredient>) matchesField.get(step);
                    if (matches != null) {
                        for (Ingredient ing : matches) {
                            if (!ing.isEmpty()) all.add(ing);
                        }
                    }
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
            }
            if (!all.isEmpty()) return all;
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        return null;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static List<Ingredient> tryExtractRitualBasedIngredients(Object recipe) {
        List<Ingredient> all = new ArrayList<>();

        // Probe mainIngredient field (FA Ritual, generic ritual recipes)
        try {
            java.lang.reflect.Field mainField = findAnyField(recipe.getClass(), "mainIngredient");
            if (mainField != null && Ingredient.class.isAssignableFrom(mainField.getType())) {
                mainField.setAccessible(true);
                Ingredient main = (Ingredient) mainField.get(recipe);
                if (main != null && !main.isEmpty()) all.add(main);
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }

        // Probe inputs field — list of objects each with an `ingredient` sub-field
        try {
            java.lang.reflect.Field inputsField = findAnyField(recipe.getClass(), "inputs");
            if (inputsField != null && List.class.isAssignableFrom(inputsField.getType())) {
                inputsField.setAccessible(true);
                List<?> inputs = (List<?>) inputsField.get(recipe);
                if (inputs != null) {
                    for (Object ri : inputs) {
                        try {
                            java.lang.reflect.Field ingField = findAnyField(ri.getClass(), "ingredient");
                            if (ingField != null && Ingredient.class.isAssignableFrom(ingField.getType())) {
                                ingField.setAccessible(true);
                                Ingredient ing = (Ingredient) ingField.get(ri);
                                if (ing != null && !ing.isEmpty()) all.add(ing);
                            }
                        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
                    }
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }

        return all.isEmpty() ? null : all;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public static List<Ingredient> extractLodestoneIngredients(Object recipe) {
        List<IngredientSpec> specs = tryExtractIngredientSpecsWithCount(recipe);
        if (specs == null) return null;
        return specs.stream().map(IngredientSpec::ingredient).collect(Collectors.toList());
    }

    /**
     * Like {@link #extractIngredients(Object)} but preserves the {@code count} field
     * from {@code IngredientWithCount} objects (Malum, Lodestone, etc.).
     * Falls back to {@code extractIngredients()} with count=1 for standard recipes.
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public static List<IngredientSpec> extractIngredientSpecs(Object recipe) {
        // Try registered handler first (explicit per-mod logic)
        if (recipe instanceof net.minecraft.world.item.crafting.Recipe<?> r) {
            var handler = ModRecipeHandlers.handlerFor(r);
            if (handler != null) {
                List<IngredientSpec> result = handler.getIngredients(r);
                if (result != null) return result;
            }
        }

        // Fallback probes for unrecognized recipe types
        List<IngredientSpec> result = tryExtractIngredientSpecsWithCount(recipe);
        if (result != null) return result;

        result = tryExtractRitualBasedSpecs(recipe);
        if (result != null) return result;

        List<Ingredient> ingredients = extractIngredients(recipe);
        if (ingredients == null) return null;

        return ingredients.stream()
                .map(ing -> new IngredientSpec(ing, 1))
                .collect(Collectors.toList());
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static List<IngredientSpec> tryExtractIngredientSpecsWithCount(Object recipe) {
        try {
            Class<?> iwcClass = Class.forName(
                    "team.lodestar.lodestone.systems.recipe.IngredientWithCount");
            java.lang.reflect.Field ingField = iwcClass.getField("ingredient");
            java.lang.reflect.Field countField = iwcClass.getField("count");
            List<IngredientSpec> result = new ArrayList<>();

            java.lang.reflect.Field inputField = findAnyField(recipe.getClass(), "input");
            if (inputField != null && iwcClass.isAssignableFrom(inputField.getType())) {
                inputField.setAccessible(true);
                Object iwc = inputField.get(recipe);
                if (iwc != null) {
                    Ingredient ing = (Ingredient) ingField.get(iwc);
                    int count = countField.getInt(iwc);
                    if (ing != null && count > 0) result.add(new IngredientSpec(ing, count));
                }
            }

            java.lang.reflect.Field extraField = findAnyField(recipe.getClass(), "extraItems");
            if (extraField != null && List.class.isAssignableFrom(extraField.getType())) {
                extraField.setAccessible(true);
                List<?> list = (List<?>) extraField.get(recipe);
                if (list != null) {
                    for (Object iwc : list) {
                        if (iwcClass.isInstance(iwc)) {
                            Ingredient ing = (Ingredient) ingField.get(iwc);
                            int count = countField.getInt(iwc);
                            if (ing != null && count > 0) result.add(new IngredientSpec(ing, count));
                        }
                    }
                }
            }

            java.lang.reflect.Field spiritsField = findAnyField(recipe.getClass(), "spirits");
            if (spiritsField != null && List.class.isAssignableFrom(spiritsField.getType())) {
                spiritsField.setAccessible(true);
                List<?> spiritList = (List<?>) spiritsField.get(recipe);
                if (spiritList != null) {
                    for (Object swc : spiritList) {
                        try {
                            Optional<Object> itemOpt = Reflect.invoke(swc, "getItem");
                            if (itemOpt.isPresent() && itemOpt.get() instanceof net.minecraft.world.item.Item it) {
                                int count = Reflect.getIntField(swc, "count").orElse(1);
                                result.add(new IngredientSpec(Ingredient.of(it), count));
                            }
                        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
                    }
                }
            }

            return result.isEmpty() ? null : result;
        } catch (ClassNotFoundException e) {
            RSIntegrationMod.LOGGER.warn("[RSI] Lodestone IngredientWithCount parse failed", e);
            return null;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI] Lodestone IngredientWithCount parse failed", e);
            return null;
        }
    }

    /**
     * Read the {@code count} field from an {@code IngredientWithCount} object.
     * Returns {@code fallback} if the object is null or has no count field.
     */
    public static int readIngredientCount(@Nullable Object iwcObj, int fallback) {
        if (iwcObj == null) return fallback;
        return Reflect.getIntField(iwcObj, "count").orElse(fallback);
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static List<IngredientSpec> tryExtractRitualBasedSpecs(Object recipe) {
        List<IngredientSpec> specs = new ArrayList<>();

        try {
            java.lang.reflect.Field mainField = findAnyField(recipe.getClass(), "mainIngredient");
            if (mainField != null && Ingredient.class.isAssignableFrom(mainField.getType())) {
                mainField.setAccessible(true);
                Ingredient main = (Ingredient) mainField.get(recipe);
                if (main != null && !main.isEmpty()) specs.add(new IngredientSpec(main, 1));
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }

        try {
            java.lang.reflect.Field inputsField = findAnyField(recipe.getClass(), "inputs");
            if (inputsField != null && List.class.isAssignableFrom(inputsField.getType())) {
                inputsField.setAccessible(true);
                List<?> inputs = (List<?>) inputsField.get(recipe);
                if (inputs != null) {
                    for (Object ri : inputs) {
                        try {
                            java.lang.reflect.Field ingField = findAnyField(ri.getClass(), "ingredient");
                            java.lang.reflect.Field amtField = findAnyField(ri.getClass(), "amount");
                            if (ingField != null && Ingredient.class.isAssignableFrom(ingField.getType())) {
                                ingField.setAccessible(true);
                                Ingredient ing = (Ingredient) ingField.get(ri);
                                if (ing != null && !ing.isEmpty()) {
                                    int amt = 1;
                                    if (amtField != null) {
                                        amtField.setAccessible(true);
                                        amt = Math.max(1, amtField.getInt(ri));
                                    }
                                    specs.add(new IngredientSpec(ing, amt));
                                }
                            }
                        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
                    }
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }

        return specs.isEmpty() ? null : specs;
    }

    private static java.lang.reflect.Field findAnyField(Class<?> clazz, String name) {
        return Reflect.findField(clazz, name).orElse(null);
    }

    // ── multi-block chain helper ──────────────────────────────────

    /**
     * Resolve auto-craft steps using the multi-block-aware resolver, then
     * either execute vanilla steps inline or submit an {@link AsyncCraftChain}
     * for multi-block steps.
     *
     * @return true if the chain was executed/submitted and items should
     *         now (or eventually) be available
     */
    /**
     * Resolve and execute/submit a crafting chain for the given ingredient+count.
     * Preserves the original {@link Ingredient} (which may represent a Tag with
     * many valid item options) instead of degrading it to a single ItemStack.
     *
     * @return true if the chain was executed/submitted and items should
     *         now (or eventually) be available
     */
    private static boolean tryResolveAndRunChain(ServerPlayer player, INetwork network,
                                                  Ingredient ingredient, int count) {
        Map<StackKey, Integer> available = MaterialSources.listAllAvailable(player, network);
        List<IngredientSpec> specs = List.of(new IngredientSpec(ingredient, count));

        // 1. Try typed resolver (includes multi-block candidates)
        if (RSIntegrationConfig.ENABLE_MULTIBLOCK_AUTO_CRAFTING.get()) {
            List<String> missing = new ArrayList<>();
            List<ResolutionStep> steps = CraftingResolver.resolveStepsForSpecsWithTypes(
                    specs, available, player.serverLevel(), player, network, missing, null, false);
            if (!missing.isEmpty()) {
                RSIntegrationMod.LOGGER.debug("[RSI] tryResolveAndRunChain: typed resolver could not resolve: {}",
                        missing);
            }
            if (!steps.isEmpty() && missing.isEmpty()) {
                boolean hasMultiblock = steps.stream()
                        .anyMatch(s -> s.modType() != ModType.GENERIC);
                if (hasMultiblock) {
                    AsyncCraftChain chain = new AsyncCraftChain(player.getUUID(), player.getServer(), network, steps);
                    AsyncCraftManager.getInstance().submit(chain);
                    player.sendSystemMessage(
                            TextBuilder.translate("rsi.async.chain_started", steps.size())
                                    .build());
                    return false; // items not yet available — chain is running async
                }
                // All vanilla — execute inline
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.info.auto_crafting", steps.size()));
                return executeCraftingSteps(player, steps, network);
            }
        }

        // 2. Fallback: vanilla-only resolver
        List<String> missing = new ArrayList<>();
        List<ResourceLocation> vanillaSteps = CraftingResolver.resolveStepsForSpecs(
                specs, available, player.serverLevel(), missing);
        if (!missing.isEmpty()) {
            RSIntegrationMod.LOGGER.debug("[RSI] tryResolveAndRunChain: vanilla resolver could not resolve: {}",
                    missing);
        }
        if (!vanillaSteps.isEmpty() && missing.isEmpty()) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.generic.info.auto_crafting", vanillaSteps.size()));
            List<ResolutionStep> wrapped = new ArrayList<>();
            for (ResourceLocation id : vanillaSteps) {
                wrapped.add(new ResolutionStep(id, ModType.GENERIC,
                        new ResourceLocation("minecraft:crafting")));
            }
            return executeCraftingSteps(player, wrapped, network);
        }

        RSIntegrationMod.LOGGER.warn("[RSI] tryResolveAndRunChain: both typed and vanilla resolvers failed for {} x{}",
                describeIngredient(ingredient).getString(), count);
        return false;
    }

    // ── shared material extraction with auto-crafting ────────────

    /**
     * Try extracting from: altar binding → RS network → player inventory.
     * If all fail and auto-crafting is enabled, resolve intermediates via CraftingResolver.
     * Performs immediate extraction (backward-compatible).
     */
    public static ItemStack ensureMaterialAvailable(ServerPlayer player, ResourceKey<Level> altarDim,
                                                     BlockPos altarPos, Ingredient ingredient, int count) {
        return ensureMaterialAvailable(player, altarDim, altarPos, ingredient, count, null);
    }

    /**
     * Ledger-aware variant: when {@code ledger} is non-null, reserves from the ledger
     * (no physical extraction yet) and returns a copy suitable for slot placement.
     * When ledger is null, performs immediate extraction as before.
     *
     * Auto-crafting fallback is executed inline (physical) in both modes because the
     * intermediate items must exist in the network before extraction can proceed.
     */
    public static ItemStack ensureMaterialAvailable(ServerPlayer player, ResourceKey<Level> altarDim,
                                                     BlockPos altarPos, Ingredient ingredient, int count,
                                                     @Nullable ExtractionLedger ledger) {
        if (ledger != null) {
            INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
            if (network == null && altarDim != null && altarPos != null) {
                network = AltarBindingRegistry.resolveNetworkForAltar(player, altarDim, altarPos);
            }
            ItemStack reserved = ledger.reserve(ingredient, count, network, player, altarDim, altarPos);
            if (!reserved.isEmpty()) return reserved;

            // Auto-craft fallback (inline vanilla or async multi-block)
            if (RSIntegrationConfig.ENABLE_AUTO_CRAFTING.get() && !ingredient.isEmpty()
                    && network != null) {
                if (tryResolveAndRunChain(player, network, ingredient, count)) {
                    reserved = ledger.reserve(ingredient, count, network,
                            player, altarDim, altarPos);
                    if (!reserved.isEmpty()) return reserved;
                }
            }
            return ItemStack.EMPTY;
        }

        // Original immediate-extraction path (ledger == null)
        // Phase 1: direct extraction
        ItemStack result = AltarBindingRegistry.tryExtractFromBindings(player, altarDim, altarPos, ingredient, count);
        if (!result.isEmpty()) return result;
        result = RSIntegrationNetwork.tryExtractFromPlayerRS(player, ingredient, count);
        if (!result.isEmpty()) return result;
        result = extractFromPlayerInventoryAggregated(player, ingredient, count);
        if (!result.isEmpty()) return result;

        // Phase 2: auto-craft intermediates (inline vanilla or async multi-block)
        if (!RSIntegrationConfig.ENABLE_AUTO_CRAFTING.get()) return ItemStack.EMPTY;
        if (ingredient.isEmpty()) return ItemStack.EMPTY;

        INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        if (network == null) {
            network = AltarBindingRegistry.resolveNetworkForAltar(player, altarDim, altarPos);
        }
        if (network == null) return ItemStack.EMPTY;

        if (!tryResolveAndRunChain(player, network, ingredient, count)) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.auto_craft_failed"));
            return ItemStack.EMPTY;
        }

        // Retry ALL extraction paths after auto-crafting
        result = AltarBindingRegistry.tryExtractFromBindings(player, altarDim, altarPos, ingredient, count);
        if (!result.isEmpty()) return result;
        result = RSIntegrationNetwork.tryExtractFromPlayerRS(player, ingredient, count);
        if (!result.isEmpty()) return result;
        result = extractFromPlayerInventoryAggregated(player, ingredient, count);
        if (!result.isEmpty()) return result;
        return ItemStack.EMPTY;
    }

    /**
     * Resolve RS network with binding fallback. When the player has no RS terminal
     * open and no NetworkItem in inventory, try to find the network through the
     * altar's registered bindings. Callers that have {@code altarDim}/{@code altarPos}
     * available should use this instead of {@link RSIntegration#resolveNetworkFromPlayer}.
     */
    @Nullable
    public static INetwork resolveNetworkForCraft(ServerPlayer player,
                                                   @Nullable ResourceKey<Level> altarDim,
                                                   @Nullable BlockPos altarPos) {
        // Try altar-specific binding first — if this altar is bound to a
        // specific RS network, that network takes priority over any random
        // NetworkItem the player happens to carry.
        if (altarDim != null && altarPos != null) {
            INetwork net = AltarBindingRegistry.resolveNetworkForAltar(player, altarDim, altarPos);
            if (net != null) return net;
        }
        // Fall back to generic player inventory / nearby node scan
        return RSIntegrationNetwork.resolveNetworkFromPlayer(player);
    }

    // ── keyed-counts helpers ──────────────────────────────────────

    private static List<ItemStack> stacksFromKeyed(Map<StackKey, Integer> counts) {
        List<ItemStack> list = new ArrayList<>();
        for (var entry : counts.entrySet()) {
            int count = entry.getValue();
            if (count <= 0) continue;
            StackKey key = entry.getKey();
            ItemStack stack = new ItemStack(key.item(), count);
            if (key.tag() != null) {
                try {
                    stack.setTag(net.minecraft.nbt.TagParser.parseTag(key.tag()));
                } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] NBT parse failed for key {}", key, ex); }
            }
            list.add(stack);
        }
        return list;
    }

    // ── shared recipe chain resolution ────────────────────────────

    /**
     * Resolve intermediate crafting steps for a CraftingRecipe, trying the
     * typed resolver first (includes multi-block candidates) and falling back
     * to vanilla-only resolution.
     *
     * <p>The returned list does NOT include the final recipe step — callers
     * should append it themselves. Returns null if resolution fails
     * completely.</p>
     */
    @Nullable
    public static List<ResolutionStep> resolveIntermediateSteps(
            ServerPlayer player, INetwork network, CraftingRecipe recipe) {
        Map<StackKey, Integer> available = MaterialSources.listAllAvailable(player, network);

        List<IngredientSpec> specs = extractIngredientSpecs(recipe);
        if (specs == null) return null;

        List<String> missingCheck = new ArrayList<>();
        List<ResolutionStep> allSteps = CraftingResolver.resolveStepsForSpecsWithTypes(
                specs, available, player.serverLevel(), player, network, missingCheck, null, false);

        if (!missingCheck.isEmpty()) {
            RSIntegrationMod.LOGGER.debug("[RSI] resolveIntermediateSteps: typed resolver could not resolve: {}",
                    missingCheck);
            allSteps = null;
        }

        if (allSteps == null) {
            missingCheck.clear();
            List<ResourceLocation> vanillaIds = CraftingResolver.resolveStepsForSpecs(
                    specs, available, player.serverLevel(), missingCheck);
            if (!missingCheck.isEmpty()) {
                RSIntegrationMod.LOGGER.debug("[RSI] resolveIntermediateSteps: vanilla resolver also could not resolve: {}",
                        missingCheck);
            }
            if (!vanillaIds.isEmpty()) {
                allSteps = new ArrayList<>();
                for (ResourceLocation id : vanillaIds) {
                    allSteps.add(new ResolutionStep(id, ModType.GENERIC,
                            new ResourceLocation("minecraft:crafting")));
                }
            }
        }

        return (allSteps == null || allSteps.isEmpty()) ? null : allSteps;
    }

    // ── availability pre-resolve ──────────────────────────────────

    /**
     * Check whether intermediate items needed by a recipe can be auto-crafted.
     * Does NOT execute any crafting — read-only preview for GUI availability checks.
     */
    public static boolean canResolveMissingIngredients(ServerPlayer player, Recipe<?> recipe,
                                                  @Nullable ResourceKey<Level> altarDim,
                                                  @Nullable BlockPos altarPos) {
        if (!RSIntegrationConfig.ENABLE_AUTO_CRAFTING.get()) return false;

        List<IngredientSpec> specs = extractIngredientSpecs(recipe);
        if (specs == null || specs.isEmpty()) return false;

        List<IngredientSpec> needed = new ArrayList<>();
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            needed.add(spec);
        }
        if (needed.isEmpty()) return false;

        INetwork network = resolveNetworkForCraft(player, altarDim, altarPos);
        if (network == null) return false;
        Map<StackKey, Integer> available = MaterialSources.listAllAvailable(player, network);
        List<String> missing = new ArrayList<>();
        List<ResolutionStep> allSteps = CraftingResolver.resolveStepsForSpecsWithTypes(
                needed, available, player.serverLevel(), player, network, missing, null, false);

        // Only report as craftable when every intermediate is vanilla;
        // multi-block steps require async chain execution, not a preview.
        boolean craftable = !allSteps.isEmpty() && missing.isEmpty()
                && allSteps.stream().allMatch(s -> s.modType() == ModType.GENERIC);
        if (craftable) {
            RSIntegrationMod.LOGGER.debug("[RSI] Intermediate check for recipe {}: all {} steps can be auto-crafted",
                    recipe.getId(), allSteps.size());
        }
        return craftable;
    }

    /**
     * Adds estimated fuel to the material requirements for machines that
     * consume burnable fuel items (CrockPot, vanilla furnaces, etc.).
     * Fuel burn time varies by item (coal=1600t, stick=100t, etc.) so the
     * exact count is an estimate.  One fuel item typically lasts for several
     * crafts; we estimate conservatively.
     */
    public static void addFuelToMaterials(Map<Item, Integer> itemAvailable,
                                           Map<Item, Ingredient> itemSource,
                                           Map<Item, Integer> neededCounts,
                                           int repeatCount) {
        Item bestFuel = null;
        int bestScore = 0;
        for (var entry : itemAvailable.entrySet()) {
            try {
                if (new ItemStack(entry.getKey()).getBurnTime(null) > 0) {
                    int score = entry.getValue();
                    ResourceLocation rl = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(entry.getKey());
                    if (rl != null && "minecraft".equals(rl.getNamespace())) score += 64;
                    if (score > bestScore) {
                        bestScore = score;
                        bestFuel = entry.getKey();
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI] Fuel detection failed", e);
            }
        }
        if (bestFuel == null) {
            bestFuel = net.minecraft.world.item.Items.COAL;
        }
        int fuelNeeded = Math.max(1, repeatCount / 4);
        neededCounts.merge(bestFuel, fuelNeeded, Integer::sum);
        itemSource.putIfAbsent(bestFuel, Ingredient.of(bestFuel));
    }
}
