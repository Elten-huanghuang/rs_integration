package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.batch.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.integration.AltarBindingRegistry;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

public final class CraftingResolver {

    private static final int MAX_DEPTH = 8;
    private static final int MAX_STEPS = 384;
    private static final int MAX_ENSURE_CALLS = 2000;
    private static final long MAX_RESOLVE_NANOS = 150_000_000L;

    private CraftingResolver() {}

    // ── public API (vanilla-only, backward compatible) ──────────

    public static List<ResourceLocation> resolveStepsFor(
            List<ItemStack> needed,
            Map<Item, Integer> available,
            Level level) {
        return resolveStepsFor(needed, available, level, null);
    }

    public static List<ResourceLocation> resolveStepsFor(
            List<ItemStack> needed,
            Map<Item, Integer> available,
            Level level,
            @Nullable List<String> missingOut) {
        return resolveStepsForStacks(needed, stacksFromCounts(available), level, missingOut);
    }

    public static List<ResourceLocation> resolveStepsForKeyed(
            List<ItemStack> needed,
            Map<StackKey, Integer> available,
            Level level,
            @Nullable List<String> missingOut) {
        return resolveStepsForStacks(needed, stacksFromKeyedCounts(available), level, missingOut);
    }

    public static List<ResourceLocation> resolveStepsForStacks(
            List<ItemStack> needed,
            List<ItemStack> available,
            Level level,
            @Nullable List<String> missingOut) {
        Context ctx = new Context(level,
                CraftingPlanManager.getRecipeIndexForLevel(level),
                available,
                buildPreferredRecipes(level));

        for (ItemStack stack : needed) {
            if (stack.isEmpty()) continue;
            Ingredient ing = Ingredient.of(stack);
            if (!ensureIngredient(ing, stack.getCount(), ctx, 0)) {
                if (missingOut != null) {
                    missingOut.add(describeItem(stack));
                }
            }
        }
        return ctx.steps.stream().map(ResolutionStep::recipeId).collect(Collectors.toList());
    }

    public static List<ResourceLocation> resolveStepsForIngredients(
            List<Ingredient> needed,
            List<ItemStack> available,
            Level level,
            @Nullable List<String> missingOut) {
        Context ctx = new Context(level,
                CraftingPlanManager.getRecipeIndexForLevel(level),
                available,
                buildPreferredRecipes(level));

        for (Ingredient ing : needed) {
            if (ing.isEmpty()) continue;
            if (!ensureIngredient(ing, 1, ctx, 0)) {
                if (missingOut != null) {
                    missingOut.add(describeFirstItem(ing));
                }
            }
        }
        return ctx.steps.stream().map(ResolutionStep::recipeId).collect(Collectors.toList());
    }

    /** Variant that merges forced recipe overrides with config preferred recipes. */
    public static List<ResourceLocation> resolveStepsForIngredients(
            List<Ingredient> needed,
            List<ItemStack> available,
            Level level,
            @Nullable List<String> missingOut,
            Map<ResourceLocation, ResourceLocation> forcedOverrides) {
        Map<ResourceLocation, ResourceLocation> prefs = mergeForcedOverrides(level, forcedOverrides);
        Context ctx = new Context(level,
                CraftingPlanManager.getRecipeIndexForLevel(level),
                available,
                prefs);

        for (Ingredient ing : needed) {
            if (ing.isEmpty()) continue;
            if (!ensureIngredient(ing, 1, ctx, 0)) {
                if (missingOut != null) {
                    missingOut.add(describeFirstItem(ing));
                }
            }
        }
        return ctx.steps.stream().map(ResolutionStep::recipeId).collect(Collectors.toList());
    }

    // ── public API (typed, includes multi-block) ────────────────

    /**
     * Like {@link #resolveStepsForIngredients} but returns typed
     * {@link ResolutionStep} entries so callers can distinguish vanilla
     * from multi-block steps.
     */
    public static List<ResolutionStep> resolveStepsForIngredientsWithTypes(
            List<Ingredient> needed,
            List<ItemStack> available,
            Level level,
            @Nullable ServerPlayer player,
            @Nullable INetwork network,
            @Nullable List<String> missingOut) {
        Context ctx = new Context(level,
                CraftingPlanManager.getRecipeIndexForLevel(level),
                ModRecipeIndex.getRecipeIndexForLevel(level),
                available,
                buildPreferredRecipes(level),
                player,
                network);

        for (Ingredient ing : needed) {
            if (ing.isEmpty()) continue;
            if (!ensureIngredient(ing, 1, ctx, 0)) {
                if (missingOut != null) {
                    missingOut.add(describeFirstItem(ing));
                }
            }
        }
        return new ArrayList<>(ctx.steps);
    }

    /**
     * Like {@link #resolveStepsForIngredientsWithTypes} but accepts pre-keyed
     * available counts to avoid NBT String↔CompoundTag round-trip.
     */
    public static List<ResolutionStep> resolveStepsForIngredientsWithTypes(
            List<Ingredient> needed,
            Map<StackKey, Integer> availableKeyed,
            Level level,
            @Nullable ServerPlayer player,
            @Nullable INetwork network,
            @Nullable List<String> missingOut) {
        return resolveStepsForIngredientsWithTypes(needed, availableKeyed, level,
                player, network, missingOut, null);
    }

    /**
     * Variant with forced recipe overrides (merged on top of config
     * preferred recipes).  Used by the plan screen when the player
     * clicks an OR badge to choose a specific recipe path.
     */
    public static List<ResolutionStep> resolveStepsForIngredientsWithTypes(
            List<Ingredient> needed,
            Map<StackKey, Integer> availableKeyed,
            Level level,
            @Nullable ServerPlayer player,
            @Nullable INetwork network,
            @Nullable List<String> missingOut,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides) {
        Map<ResourceLocation, ResourceLocation> prefs = mergeForcedOverrides(level, forcedOverrides);
        Context ctx = new Context(level,
                CraftingPlanManager.getRecipeIndexForLevel(level),
                ModRecipeIndex.getRecipeIndexForLevel(level),
                availableKeyed,
                prefs,
                player,
                network,
                false);

        for (Ingredient ing : needed) {
            if (ing.isEmpty()) continue;
            if (!ensureIngredient(ing, 1, ctx, 0)) {
                if (missingOut != null) {
                    missingOut.add(describeFirstItem(ing));
                }
            }
        }
        return new ArrayList<>(ctx.steps);
    }

    /**
     * Best-effort variant for plan preview.  Even when leaf ingredients
     * (raw materials with no producing recipe) are unavailable, the
     * resolver includes intermediate steps so the player sees the full
     * recipe chain rather than an empty screen.
     */
    public static List<ResolutionStep> resolveStepsForIngredientsWithTypes(
            List<Ingredient> needed,
            Map<StackKey, Integer> availableKeyed,
            Level level,
            @Nullable ServerPlayer player,
            @Nullable INetwork network,
            @Nullable List<String> missingOut,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides,
            boolean bestEffort) {
        Map<ResourceLocation, ResourceLocation> prefs = mergeForcedOverrides(level, forcedOverrides);
        Context ctx = new Context(level,
                CraftingPlanManager.getRecipeIndexForLevel(level),
                ModRecipeIndex.getRecipeIndexForLevel(level),
                availableKeyed,
                prefs,
                player,
                network,
                bestEffort,
                missingOut);

        for (Ingredient ing : needed) {
            if (ing.isEmpty()) continue;
            if (!ensureIngredient(ing, 1, ctx, 0)) {
                if (missingOut != null) {
                    missingOut.add(describeFirstItem(ing));
                }
            }
        }
        return new ArrayList<>(ctx.steps);
    }

    /**
     * NBT-aware typed variant.
     */
    public static List<ResolutionStep> resolveStepsForKeyedWithTypes(
            List<ItemStack> needed,
            Map<StackKey, Integer> available,
            Level level,
            @Nullable ServerPlayer player,
            @Nullable INetwork network,
            @Nullable List<String> missingOut) {
        return resolveStepsForIngredientsWithTypes(
                needed.stream().map(Ingredient::of).collect(Collectors.toList()),
                available,
                level, player, network, missingOut);
    }

    // ── recursive resolution core ───────────────────────────────

    private static boolean planRecipeIngredients(CraftingRecipe recipe, Context ctx, int depth) {
        if (depth > MAX_DEPTH) return false;
        if (ctx.steps.size() >= MAX_STEPS) return false;

        Snapshot snap = ctx.snapshot();

        Map<Item, Integer> grouped = new LinkedHashMap<>();
        Map<Item, Ingredient> representatives = new HashMap<>();

        for (Ingredient ing : recipe.getIngredients()) {
            if (ing.isEmpty()) continue;
            ItemStack[] items = ing.getItems();
            if (items.length == 0) continue;
            Item item = items[0].getItem();
            grouped.merge(item, 1, Integer::sum);
            representatives.putIfAbsent(item, ing);
        }

        for (Map.Entry<Item, Integer> entry : grouped.entrySet()) {
            Ingredient ing = representatives.get(entry.getKey());
            if (!ensureIngredient(ing, entry.getValue(), ctx, depth + 1)) {
                ctx.restore(snap);
                return false;
            }
        }
        return true;
    }

    /** Generic ingredient planning for any recipe type (vanilla or mod). */
    private static boolean planRecipeIngredients(List<IngredientSpec> specs, Context ctx, int depth) {
        if (depth > MAX_DEPTH) return false;
        if (ctx.steps.size() >= MAX_STEPS) return false;

        Snapshot snap = ctx.snapshot();

        Map<Item, Integer> grouped = new LinkedHashMap<>();
        Map<Item, Ingredient> representatives = new HashMap<>();

        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            ItemStack[] items = spec.ingredient().getItems();
            if (items.length == 0) continue;
            Item item = items[0].getItem();
            grouped.merge(item, spec.count(), Integer::sum);
            representatives.putIfAbsent(item, spec.ingredient());
        }

        for (Map.Entry<Item, Integer> entry : grouped.entrySet()) {
            Ingredient ing = representatives.get(entry.getKey());
            if (!ensureIngredient(ing, entry.getValue(), ctx, depth + 1)) {
                ctx.restore(snap);
                return false;
            }
        }
        return true;
    }

    private static boolean ensureIngredient(Ingredient ingredient, int count, Context ctx, int depth) {
        if (count <= 0) return true;
        if (ctx.timedOut()) return false;
        if (++ctx.ensureCalls > MAX_ENSURE_CALLS) return false;

        Snapshot snap = ctx.snapshot();

        if (ctx.consumeMatching(ingredient, count)) {
            return true;
        }
        ctx.restore(snap);

        int alreadyHave = ctx.countMatching(ingredient);
        int remaining = count - alreadyHave;

        if (remaining <= 0) {
            return ctx.consumeMatching(ingredient, count);
        }

        List<ModRecipeIndex.RecipeEntry> candidates = candidateRecipes(ingredient, ctx);
        if (candidates.isEmpty()) {
            if (com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.isDebugEnabled()) {
                com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.debug(
                        "[RSI-Resolver] No candidate recipes for {} (needed {}), depth={}",
                        describeFirstItem(ingredient), count, depth);
            }
            if (ctx.bestEffort && ctx.missingOut != null) {
                ctx.missingOut.add(describeFirstItem(ingredient));
                return true;
            }
            return false;
        }

        for (ModRecipeIndex.RecipeEntry candidate : candidates) {
            ItemStack output = ModRecipeIndex.tryGetResultItem(candidate.recipe(), ctx.level.registryAccess());
            if (output.isEmpty() || output.getCount() <= 0) {
                if (com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.isDebugEnabled()) {
                    com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.debug(
                            "[RSI-Resolver]   reject {} — output empty or zero count", candidate.recipe().getId());
                }
                continue;
            }

            if (ctx.resolving.contains(output.getItem())) {
                if (com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.isDebugEnabled()) {
                    com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.debug(
                            "[RSI-Resolver]   reject {} — cycle (already resolving {})",
                            candidate.recipe().getId(), describeItem(output));
                }
                continue;
            }

            if (requiresMissingSelfInput(candidate, output, ctx)) {
                if (com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.isDebugEnabled()) {
                    com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.debug(
                            "[RSI-Resolver]   reject {} — requires self-input and none available",
                            candidate.recipe().getId());
                }
                continue;
            }

            int batches = Math.max(1, (int) Math.ceil((double) remaining / output.getCount()));
            Snapshot beforeCandidate = ctx.snapshot();

            ctx.resolving.add(output.getItem());

            boolean allOk = true;
            for (int b = 0; b < batches && allOk; b++) {
                allOk = craftOnce(candidate, ctx, depth);
            }

            ctx.resolving.remove(output.getItem());

            if (!allOk) {
                if (com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.isDebugEnabled()) {
                    com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.debug(
                            "[RSI-Resolver]   reject {} — craftOnce returned false (depth={})",
                            candidate.recipe().getId(), depth);
                }
                ctx.restore(beforeCandidate);
                continue;
            }

            if (ctx.consumeMatching(ingredient, count)) {
                if (com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.isDebugEnabled()) {
                    com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.debug(
                            "[RSI-Resolver]   ACCEPT {} — resolved {}",
                            candidate.recipe().getId(), describeFirstItem(ingredient));
                }
                return true;
            }

            if (com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.isDebugEnabled()) {
                com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.debug(
                        "[RSI-Resolver]   reject {} — final consumeMatching failed (needed {} of {})",
                        candidate.recipe().getId(), count, describeFirstItem(ingredient));
            }
            ctx.restore(beforeCandidate);
        }

        if (com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.isDebugEnabled()) {
            com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.debug(
                    "[RSI-Resolver] FAILED to ensure {} x{} after trying {} candidates (depth={})",
                    describeFirstItem(ingredient), count, candidates.size(), depth);
        }
        ctx.restore(snap);
        return false;
    }

    // ── craftOnce (vanilla + multi-block) ───────────────────────

    private static boolean craftOnce(CraftingRecipe recipe, Context ctx, int depth) {
        if (ctx.timedOut()) return false;
        if (depth > MAX_DEPTH) return false;
        if (ctx.steps.size() >= MAX_STEPS) return false;

        Snapshot snap = ctx.snapshot();

        if (!planRecipeIngredients(recipe, ctx, depth + 1)) {
            ctx.restore(snap);
            return false;
        }

        for (Ingredient ing : recipe.getIngredients()) {
            if (ing.isEmpty()) continue;
            addCraftingRemainder(ing, ctx);
        }

        ItemStack result = recipe.getResultItem(ctx.level.registryAccess());
        ctx.add(result);

        // Add secondary outputs (byproducts) to virtual inventory
        for (ItemStack secondary : ModRecipeIndex.tryGetSecondaryOutputs(recipe, ctx.level.registryAccess())) {
            ctx.add(secondary);
        }

        ctx.steps.add(new ResolutionStep(recipe.getId(), ModType.GENERIC,
                new ResourceLocation("minecraft:crafting")));

        return true;
    }

    private static boolean craftOnce(ModRecipeIndex.RecipeEntry entry, Context ctx, int depth) {
        if (ctx.timedOut()) return false;
        if (depth > MAX_DEPTH) return false;
        if (ctx.steps.size() >= MAX_STEPS) return false;

        // Delegate to vanilla path for crafting recipes
        if (entry.modType() == ModType.GENERIC && entry.recipe() instanceof CraftingRecipe cr) {
            return craftOnce(cr, ctx, depth);
        }

        // Multi-block recipe
        Snapshot snap = ctx.snapshot();
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(entry.recipe());
        if (specs == null || specs.isEmpty()) {
            ctx.restore(snap);
            return false;
        }

        if (!planRecipeIngredients(specs, ctx, depth + 1)) {
            ctx.restore(snap);
            return false;
        }

        // Consume ingredients and add remainders
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            addCraftingRemainder(spec.ingredient(), ctx);
        }

        ItemStack result = ModRecipeIndex.tryGetResultItem(entry.recipe(), ctx.level.registryAccess());
        ctx.add(result);

        // Add secondary outputs (byproducts) to virtual inventory
        for (ItemStack secondary : ModRecipeIndex.tryGetSecondaryOutputs(entry.recipe(), ctx.level.registryAccess())) {
            ctx.add(secondary);
        }

        ctx.steps.add(new ResolutionStep(entry.recipe().getId(), entry.modType(), entry.recipeTypeId()));

        return true;
    }

    // ── candidate recipes ───────────────────────────────────────

    private static List<ModRecipeIndex.RecipeEntry> candidateRecipes(Ingredient ingredient, Context ctx) {
        Map<ResourceLocation, ModRecipeIndex.RecipeEntry> dedup = new LinkedHashMap<>();

        for (ItemStack stack : ingredient.getItems()) {
            if (stack.isEmpty()) continue;
            Item item = stack.getItem();

            // 1. Vanilla crafting index
            List<CraftingRecipe> vanillaRecipes = ctx.index.get(item);
            if (vanillaRecipes != null) {
                for (CraftingRecipe recipe : vanillaRecipes) {
                    ItemStack output = recipe.getResultItem(ctx.level.registryAccess());
                    if (output.isEmpty() || !ingredient.test(output)) continue;
                    dedup.put(recipe.getId(), new ModRecipeIndex.RecipeEntry(
                            recipe, ModType.GENERIC, new ResourceLocation("minecraft:crafting")));
                }
            }

            // 2. Multi-block mod index (if available)
            if (ctx.modIndex != null) {
                List<ModRecipeIndex.RecipeEntry> modRecipes = ctx.modIndex.get(item);
                if (modRecipes != null) {
                    for (ModRecipeIndex.RecipeEntry entry : modRecipes) {
                        if (!isMachineAvailable(entry.modType(), ctx)) continue;
                        ItemStack output = ModRecipeIndex.tryGetResultItem(
                                entry.recipe(), ctx.level.registryAccess());
                        if (output.isEmpty() || !ingredient.test(output)) continue;
                        ResourceLocation rid = entry.recipe().getId();
                        var allowlist = RSIntegrationConfig.MULTIBLOCK_RECIPE_ALLOWLIST.get();
                        if (!allowlist.isEmpty() && !allowlist.contains(rid.toString()))
                            continue;
                        if (RSIntegrationConfig.MULTIBLOCK_RECIPE_BLACKLIST.get().contains(rid.toString()))
                            continue;
                        dedup.put(entry.recipe().getId(), entry);
                    }
                }
            }
        }

        // Capture first item for debug logging
        Item firstItem = null;
        for (ItemStack stack : ingredient.getItems()) {
            if (!stack.isEmpty()) { firstItem = stack.getItem(); break; }
        }

        List<ModRecipeIndex.RecipeEntry> result = new ArrayList<>(dedup.values());
        result.sort((a, b) -> Integer.compare(scoreEntry(b, ctx), scoreEntry(a, ctx)));

        if (com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.isDebugEnabled()) {
            StringBuilder sb = new StringBuilder("[RSI-Resolver] candidateRecipes for ");
            sb.append(describeFirstItem(ingredient)).append(": found ").append(result.size()).append(" candidates");
            for (ModRecipeIndex.RecipeEntry e : result) {
                sb.append("\n  - ").append(e.recipe().getId()).append(" type=").append(e.modType());
            }
            if (result.isEmpty() && firstItem != null) {
                sb.append("\n  index keys for item ").append(
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(firstItem));
                sb.append(": vanilla=").append(ctx.index.containsKey(firstItem));
                sb.append(" mod=").append(ctx.modIndex != null && ctx.modIndex.containsKey(firstItem));
            }
            com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.debug(sb.toString());
        }

        return result;
    }

    // ── scoring ─────────────────────────────────────────────────

    private static int scoreEntry(ModRecipeIndex.RecipeEntry entry, Context ctx) {
        if (entry.recipe() instanceof CraftingRecipe cr) {
            return scoreRecipe(cr, ctx) + (entry.modType() == ModType.GENERIC ? 10 : 0);
        }
        // Non-crafting recipe: base score from ingredient availability
        int score = 0;
        if (entry.modType() == ModType.GENERIC) score += 10; // prefer vanilla
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(entry.recipe());
        if (specs != null) {
            for (IngredientSpec spec : specs) {
                if (!spec.isEmpty() && ctx.countMatching(spec.ingredient()) > 0) {
                    score += 10;
                }
            }
            score -= specs.size();
        }
        // Preferred recipe bonus
        ItemStack output = ModRecipeIndex.tryGetResultItem(entry.recipe(), ctx.level.registryAccess());
        if (!output.isEmpty()) {
            if (ctx.preferredRecipes != null) {
                ResourceLocation itemKey = ForgeRegistries.ITEMS.getKey(output.getItem());
                if (itemKey != null) {
                    ResourceLocation preferred = ctx.preferredRecipes.get(itemKey);
                    if (preferred != null && preferred.equals(entry.recipe().getId())) {
                        score += 1000;
                    }
                }
            }
            // Output divisor bonus: recipes that produce more items per craft are preferred
            if (output.getCount() > 1) {
                score += (output.getCount() - 1) * 5;
            }
        }
        return score;
    }

    private static int scoreRecipe(CraftingRecipe recipe, Context ctx) {
        int score = 0;

        if (ctx.preferredRecipes != null) {
            ResourceLocation outputKey = ForgeRegistries.ITEMS.getKey(
                    recipe.getResultItem(ctx.level.registryAccess()).getItem());
            if (outputKey != null) {
                ResourceLocation preferred = ctx.preferredRecipes.get(outputKey);
                if (preferred != null && preferred.equals(recipe.getId())) {
                    score += 1000;
                }
            }
        }

        for (Ingredient ing : recipe.getIngredients()) {
            if (ing.isEmpty()) continue;
            if (ctx.countMatching(ing) > 0) {
                score += 10;
            }
        }

        score -= recipe.getIngredients().size();

        // Output divisor bonus: recipes producing more items per craft are preferred
        ItemStack output = recipe.getResultItem(ctx.level.registryAccess());
        if (output.getCount() > 1) {
            score += (output.getCount() - 1) * 5;
        }

        return score;
    }

    // ── machine availability ────────────────────────────────────

    private static boolean isMachineAvailable(ModType type, Context ctx) {
        if (type == ModType.GENERIC) return true;
        if (ctx.player == null) return false;
        return AltarBindingRegistry.hasAnyBindingForType(ctx.player, type);
    }

    // ── self-input detection ────────────────────────────────────

    private static boolean requiresMissingSelfInput(ModRecipeIndex.RecipeEntry entry,
                                                     ItemStack output, Context ctx) {
        if (entry.recipe() instanceof CraftingRecipe cr) {
            return requiresMissingSelfInput(cr, output, ctx);
        }
        // Multi-block: check if the output item is also a required input and we have none
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(entry.recipe());
        if (specs == null || specs.isEmpty()) return false;

        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            if (!spec.ingredient().test(output)) continue;
            if (ctx.countMatching(spec.ingredient()) <= 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean requiresMissingSelfInput(CraftingRecipe recipe, ItemStack output, Context ctx) {
        for (Ingredient ing : recipe.getIngredients()) {
            if (ing.isEmpty()) continue;
            if (!ing.test(output)) continue;
            ItemStack[] items = ing.getItems();
            if (items.length == 1 && ctx.countMatching(ing) <= 0) {
                return true;
            }
        }
        return false;
    }

    // ── helpers ─────────────────────────────────────────────────

    private static void addCraftingRemainder(Ingredient ingredient, Context ctx) {
        for (ItemStack stack : ingredient.getItems()) {
            if (stack.isEmpty()) continue;
            try {
                ItemStack remainder = stack.getCraftingRemainingItem();
                if (!remainder.isEmpty()) {
                    ctx.add(remainder.copyWithCount(1));
                    return;
                }
            } catch (Throwable ignored) {}
        }
    }

    private static ItemStack stackFromKey(StackKey key) {
        ItemStack stack = new ItemStack(key.item());
        if (key.tag() != null) {
            try {
                CompoundTag tag = TagParser.parseTag(key.tag());
                stack.setTag(tag);
            } catch (Exception ignored) {}
        }
        return stack;
    }

    private static List<ItemStack> stacksFromCounts(Map<Item, Integer> counts) {
        List<ItemStack> list = new ArrayList<>();
        for (var entry : counts.entrySet()) {
            int count = entry.getValue();
            if (count > 0) {
                list.add(new ItemStack(entry.getKey(), count));
            }
        }
        return list;
    }

    private static List<ItemStack> stacksFromKeyedCounts(Map<StackKey, Integer> counts) {
        List<ItemStack> list = new ArrayList<>();
        for (var entry : counts.entrySet()) {
            int count = entry.getValue();
            if (count > 0) {
                StackKey key = entry.getKey();
                ItemStack stack = new ItemStack(key.item(), count);
                if (key.tag() != null) {
                    try {
                        stack.setTag(net.minecraft.nbt.TagParser.parseTag(key.tag()));
                    } catch (Exception ignored) {}
                }
                list.add(stack);
            }
        }
        return list;
    }

    // ── preferred recipes ────────────────────────────────────────

    @Nullable
    private static Map<ResourceLocation, ResourceLocation> buildPreferredRecipes(Level level) {
        List<? extends String> ids = RSIntegrationConfig.PREFERRED_RECIPES.get();
        if (ids.isEmpty()) return null;

        Map<ResourceLocation, ResourceLocation> map = new HashMap<>();
        RecipeManager rm = level.getRecipeManager();
        for (String idStr : ids) {
            ResourceLocation id = ResourceLocation.tryParse(idStr);
            if (id == null) continue;
            Recipe<?> recipe = rm.byKey(id).orElse(null);
            ItemStack result;
            if (recipe instanceof CraftingRecipe cr) {
                result = cr.getResultItem(level.registryAccess());
            } else if (recipe != null) {
                result = ModRecipeIndex.tryGetResultItem(recipe, level.registryAccess());
            } else {
                continue;
            }
            if (!result.isEmpty()) {
                ResourceLocation itemKey = ForgeRegistries.ITEMS.getKey(result.getItem());
                if (itemKey != null) {
                    map.putIfAbsent(itemKey, id);
                }
            }
        }
        return map.isEmpty() ? null : map;
    }

    /**
     * Merges player-forced recipe overrides (from OR-badge clicks) on top
     * of config-based preferred recipes.  Forced overrides take precedence.
     */
    @Nullable
    private static Map<ResourceLocation, ResourceLocation> mergeForcedOverrides(
            Level level, @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides) {
        Map<ResourceLocation, ResourceLocation> prefs = buildPreferredRecipes(level);
        if (forcedOverrides == null || forcedOverrides.isEmpty()) return prefs;
        if (prefs == null) return new HashMap<>(forcedOverrides);
        prefs.putAll(forcedOverrides);
        return prefs;
    }

    // ── description helpers ──────────────────────────────────────

    private static String describeItem(ItemStack stack) {
        if (stack.isEmpty()) return "???";
        return stack.getHoverName().getString();
    }

    private static String describeFirstItem(Ingredient ingredient) {
        for (ItemStack stack : ingredient.getItems()) {
            if (!stack.isEmpty()) return stack.getHoverName().getString();
        }
        return "???";
    }

    // ── inner types ─────────────────────────────────────────────

    public record ResolutionStep(
            ResourceLocation recipeId,
            ModType modType,
            @Nullable ResourceLocation recipeTypeId
    ) {}

    static final class Context {
        final Level level;
        final Map<Item, List<CraftingRecipe>> index;
        @Nullable final Map<Item, List<ModRecipeIndex.RecipeEntry>> modIndex;
        final Map<StackKey, Integer> counts;
        final List<ResolutionStep> steps;
        final Set<Item> resolving;
        final long deadlineNanos;
        int ensureCalls;
        @Nullable final Map<ResourceLocation, ResourceLocation> preferredRecipes;
        @Nullable final ServerPlayer player;
        @Nullable final INetwork network;
        final boolean bestEffort;
        @Nullable final List<String> missingOut;

        /** Vanilla-only constructor (backward compatible). */
        Context(Level level,
                Map<Item, List<CraftingRecipe>> index,
                List<ItemStack> available,
                @Nullable Map<ResourceLocation, ResourceLocation> preferredRecipes) {
            this.level = level;
            this.index = index;
            this.modIndex = null;
            this.counts = new LinkedHashMap<>();
            this.steps = new ArrayList<>();
            this.resolving = new HashSet<>();
            this.preferredRecipes = preferredRecipes;
            this.player = null;
            this.network = null;
            this.deadlineNanos = System.nanoTime() + MAX_RESOLVE_NANOS;
            this.bestEffort = false;
            this.missingOut = null;

            for (ItemStack stack : available) {
                add(stack);
            }
        }

        /** Full constructor with multi-block support. */
        Context(Level level,
                Map<Item, List<CraftingRecipe>> index,
                Map<Item, List<ModRecipeIndex.RecipeEntry>> modIndex,
                List<ItemStack> available,
                @Nullable Map<ResourceLocation, ResourceLocation> preferredRecipes,
                @Nullable ServerPlayer player,
                @Nullable INetwork network) {
            this.level = level;
            this.index = index;
            this.modIndex = modIndex;
            this.counts = new LinkedHashMap<>();
            this.steps = new ArrayList<>();
            this.resolving = new HashSet<>();
            this.preferredRecipes = preferredRecipes;
            this.player = player;
            this.network = network;
            this.deadlineNanos = System.nanoTime() + MAX_RESOLVE_NANOS;
            this.bestEffort = false;
            this.missingOut = null;

            for (ItemStack stack : available) {
                add(stack);
            }
        }

        /** Full constructor that accepts pre-keyed counts (avoids NBT round-trip). */
        Context(Level level,
                Map<Item, List<CraftingRecipe>> index,
                Map<Item, List<ModRecipeIndex.RecipeEntry>> modIndex,
                Map<StackKey, Integer> keyedCounts,
                @Nullable Map<ResourceLocation, ResourceLocation> preferredRecipes,
                @Nullable ServerPlayer player,
                @Nullable INetwork network,
                boolean unused) {
            this.level = level;
            this.index = index;
            this.modIndex = modIndex;
            this.counts = new LinkedHashMap<>(keyedCounts);
            this.steps = new ArrayList<>();
            this.resolving = new HashSet<>();
            this.preferredRecipes = preferredRecipes;
            this.player = player;
            this.network = network;
            this.deadlineNanos = System.nanoTime() + MAX_RESOLVE_NANOS;
            this.bestEffort = false;
            this.missingOut = null;
        }

        /** Plan-preview constructor with best-effort mode enabled.
         *  When {@code bestEffort} is true, leaf ingredients that have no
         *  producing recipe are added to {@code missingOut} instead of
         *  aborting the chain, so intermediate steps are visible in the
         *  plan even when raw materials are unavailable. */
        Context(Level level,
                Map<Item, List<CraftingRecipe>> index,
                Map<Item, List<ModRecipeIndex.RecipeEntry>> modIndex,
                Map<StackKey, Integer> keyedCounts,
                @Nullable Map<ResourceLocation, ResourceLocation> preferredRecipes,
                @Nullable ServerPlayer player,
                @Nullable INetwork network,
                boolean bestEffort,
                @Nullable List<String> missingOut) {
            this.level = level;
            this.index = index;
            this.modIndex = modIndex;
            this.counts = new LinkedHashMap<>(keyedCounts);
            this.steps = new ArrayList<>();
            this.resolving = new HashSet<>();
            this.preferredRecipes = preferredRecipes;
            this.player = player;
            this.network = network;
            this.deadlineNanos = System.nanoTime() + MAX_RESOLVE_NANOS;
            this.bestEffort = bestEffort;
            this.missingOut = missingOut;
        }

        boolean timedOut() {
            return System.nanoTime() > deadlineNanos;
        }

        Snapshot snapshot() {
            return new Snapshot(new LinkedHashMap<>(counts), new ArrayList<>(steps));
        }

        void restore(Snapshot snap) {
            counts.clear();
            counts.putAll(snap.counts);
            steps.clear();
            steps.addAll(snap.steps);
        }

        void add(ItemStack stack) {
            if (stack.isEmpty() || stack.getCount() <= 0) return;
            counts.merge(StackKey.of(stack, true), stack.getCount(), Integer::sum);
        }

        void add(Item item, int count) {
            if (count <= 0) return;
            counts.merge(new StackKey(item, null), count, Integer::sum);
        }

        int countMatching(Ingredient ingredient) {
            int total = 0;
            for (var entry : counts.entrySet()) {
                if (entry.getValue() > 0) {
                    ItemStack stack = CraftingResolver.stackFromKey(entry.getKey());
                    if (ingredient.test(stack)) {
                        total += entry.getValue();
                    }
                }
            }
            return total;
        }

        boolean consumeMatching(Ingredient ingredient, int needed) {
            int remaining = needed;
            List<StackKey> sortedKeys = new ArrayList<>(counts.keySet());
            sortedKeys.sort(Comparator.comparing(k -> k.tag() != null));

            for (StackKey key : sortedKeys) {
                if (remaining <= 0) return true;
                int available = counts.getOrDefault(key, 0);
                if (available <= 0) continue;
                ItemStack stack = CraftingResolver.stackFromKey(key);
                if (!ingredient.test(stack)) continue;
                int take = Math.min(available, remaining);
                decrement(key, take);
                remaining -= take;
            }
            return remaining <= 0;
        }

        private void decrement(StackKey key, int amount) {
            int current = counts.getOrDefault(key, 0);
            int newCount = current - amount;
            if (newCount <= 0) {
                counts.remove(key);
            } else {
                counts.put(key, newCount);
            }
        }
    }

    static final class Snapshot {
        final Map<StackKey, Integer> counts;
        final List<ResolutionStep> steps;

        Snapshot(Map<StackKey, Integer> counts, List<ResolutionStep> steps) {
            this.counts = counts;
            this.steps = steps;
        }
    }

    public record StackKey(Item item, @Nullable String tag) {
        static StackKey of(ItemStack stack, boolean includeNbt) {
            Item item = stack.getItem();
            String tag = null;
            if (includeNbt && stack.hasTag()) {
                tag = stack.getTag().toString();
            }
            return new StackKey(item, tag);
        }
    }
}
