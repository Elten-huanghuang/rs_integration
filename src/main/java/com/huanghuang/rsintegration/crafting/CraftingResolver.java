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
    private static final long MAX_RESOLVE_NANOS = 500_000_000L;

    private CraftingResolver() {}

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

    private static boolean planRecipeIngredients(CraftingRecipe recipe, Context ctx, int depth) {
        if (depth > MAX_DEPTH) return false;
        if (ctx.steps.size() >= MAX_STEPS) return false;

        ctx.beginUndo();

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
                ctx.rollback();
                return false;
            }
        }
        ctx.commitUndo();
        return true;
    }

    private static boolean planRecipeIngredients(List<IngredientSpec> specs, Context ctx, int depth) {
        if (depth > MAX_DEPTH) return false;
        if (ctx.steps.size() >= MAX_STEPS) return false;

        ctx.beginUndo();

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
                ctx.rollback();
                return false;
            }
        }
        ctx.commitUndo();
        return true;
    }

    private static boolean ensureIngredient(Ingredient ingredient, int count, Context ctx, int depth) {
        if (count <= 0) return true;
        if (ctx.timedOut()) return false;
        if (++ctx.ensureCalls > MAX_ENSURE_CALLS) return false;

        ctx.beginUndo();
        if (ctx.consumeMatching(ingredient, count)) {
            ctx.commitUndo();
            return true;
        }
        ctx.rollback();

        int alreadyHave = ctx.countMatching(ingredient);
        int remaining = count - alreadyHave;

        if (remaining <= 0) {
            return ctx.consumeMatching(ingredient, count);
        }

        List<ModRecipeIndex.RecipeEntry> candidates = candidateRecipes(ingredient, ctx);
        if (candidates.isEmpty()) {
            if (ctx.bestEffort && ctx.missingOut != null) {
                ctx.missingOut.add(describeFirstItem(ingredient));
                return true;
            }
            return false;
        }

        for (ModRecipeIndex.RecipeEntry candidate : candidates) {
            ItemStack output = ModRecipeIndex.tryGetResultItem(candidate.recipe(), ctx.level.registryAccess());
            if (output.isEmpty() || output.getCount() <= 0) {
                continue;
            }

            String bk = branchKey(candidate.recipe().getId(), output);
            if (ctx.resolving.contains(bk)) {
                continue;
            }

            if (requiresMissingSelfInput(candidate, output, ctx)) {
                continue;
            }

            int netGain = netGainPerBatch(candidate, output);
            if (netGain <= 0) continue; // can never produce a net positive
            int batches = Math.max(1, (int) Math.ceil((double) remaining / netGain));
            ctx.beginUndo();

            ctx.resolving.add(bk);

            boolean allOk = true;
            for (int b = 0; b < batches && allOk; b++) {
                allOk = craftOnce(candidate, ctx, depth);
            }

            ctx.resolving.remove(bk);

            if (!allOk) {
                ctx.rollback();
                continue;
            }

            if (ctx.consumeMatching(ingredient, count)) {
                ctx.commitUndo();
                return true;
            }

            ctx.rollback();
        }

        return false;
    }

    private static boolean craftOnce(CraftingRecipe recipe, Context ctx, int depth) {
        if (ctx.timedOut()) return false;
        if (depth > MAX_DEPTH) return false;
        if (ctx.steps.size() >= MAX_STEPS) return false;

        ctx.beginUndo();

        if (!planRecipeIngredients(recipe, ctx, depth + 1)) {
            ctx.rollback();
            return false;
        }

        for (Ingredient ing : recipe.getIngredients()) {
            if (ing.isEmpty()) continue;
            addCraftingRemainder(ing, ctx);
        }

        ItemStack result = recipe.getResultItem(ctx.level.registryAccess());
        ctx.add(result);

        for (ItemStack secondary : ModRecipeIndex.tryGetSecondaryOutputs(recipe, ctx.level.registryAccess())) {
            ctx.add(secondary);
        }

        ctx.steps.add(new ResolutionStep(recipe.getId(), ModType.GENERIC,
                new ResourceLocation("minecraft:crafting")));

        ctx.commitUndo();
        return true;
    }

    private static boolean craftOnce(ModRecipeIndex.RecipeEntry entry, Context ctx, int depth) {
        if (ctx.timedOut()) return false;
        if (depth > MAX_DEPTH) return false;
        if (ctx.steps.size() >= MAX_STEPS) return false;

        if (entry.modType() == ModType.GENERIC && entry.recipe() instanceof CraftingRecipe cr) {
            return craftOnce(cr, ctx, depth);
        }

        ctx.beginUndo();
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(entry.recipe());
        if (specs == null || specs.isEmpty()) {
            ctx.rollback();
            return false;
        }

        if (!planRecipeIngredients(specs, ctx, depth + 1)) {
            ctx.rollback();
            return false;
        }

        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            addCraftingRemainder(spec.ingredient(), ctx);
        }

        ItemStack result = ModRecipeIndex.tryGetResultItem(entry.recipe(), ctx.level.registryAccess());
        ctx.add(result);

        for (ItemStack secondary : ModRecipeIndex.tryGetSecondaryOutputs(entry.recipe(), ctx.level.registryAccess())) {
            ctx.add(secondary);
        }

        ctx.steps.add(new ResolutionStep(entry.recipe().getId(), entry.modType(), entry.recipeTypeId()));

        ctx.commitUndo();
        return true;
    }

    private static List<ModRecipeIndex.RecipeEntry> candidateRecipes(Ingredient ingredient, Context ctx) {
        Map<ResourceLocation, ModRecipeIndex.RecipeEntry> dedup = new LinkedHashMap<>();

        ItemStack[] items = ingredient.getItems();
        int limit = Math.min(items.length, 64);
        for (int idx = 0; idx < limit; idx++) {
            ItemStack stack = items[idx];
            if (stack.isEmpty()) continue;
            Item item = stack.getItem();

            List<CraftingRecipe> vanillaRecipes = ctx.index.get(item);
            if (vanillaRecipes != null) {
                for (CraftingRecipe recipe : vanillaRecipes) {
                    ItemStack output = recipe.getResultItem(ctx.level.registryAccess());
                    if (output.isEmpty() || !ingredient.test(output)) continue;
                    dedup.put(recipe.getId(), new ModRecipeIndex.RecipeEntry(
                            recipe, ModType.GENERIC, new ResourceLocation("minecraft:crafting")));
                }
            }

            if (ctx.modIndex != null) {
                List<ModRecipeIndex.RecipeEntry> modRecipes = ctx.modIndex.get(item);
                if (modRecipes != null) {
                    for (ModRecipeIndex.RecipeEntry entry : modRecipes) {
                        if (!isMachineAvailable(entry.modType(), ctx)) {
                            continue;
                        }
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

        List<ModRecipeIndex.RecipeEntry> result = new ArrayList<>(dedup.values());
        result.sort((a, b) -> Integer.compare(scoreEntry(b, ctx), scoreEntry(a, ctx)));

        return result;
    }

    // 判断配方是否被玩家在计划板中设置为强制首选（OR override）
    private static boolean isPreferred(ModRecipeIndex.RecipeEntry entry, Context ctx) {
        if (ctx.preferredRecipes == null) return false;
        ItemStack output = ModRecipeIndex.tryGetResultItem(entry.recipe(), ctx.level.registryAccess());
        if (output.isEmpty()) return false;
        ResourceLocation itemKey = ForgeRegistries.ITEMS.getKey(output.getItem());
        if (itemKey == null) return false;
        ResourceLocation pref = ctx.preferredRecipes.get(itemKey);
        return pref != null && pref.equals(entry.recipe().getId());
    }

    private static int scoreEntry(ModRecipeIndex.RecipeEntry entry, Context ctx) {
        if (entry.recipe() instanceof CraftingRecipe cr) {
            return scoreRecipe(cr, ctx) + (entry.modType() == ModType.GENERIC ? 10 : 0);
        }
        int score = 0;
        if (entry.modType() == ModType.GENERIC) score += 10;
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(entry.recipe());
        if (specs != null) {
            int nonEmpty = 0;
            for (IngredientSpec spec : specs) {
                if (spec.isEmpty()) continue;
                nonEmpty++;
                if (ctx.countMatching(spec.ingredient()) > 0) {
                    score += 10;
                }
            }
            score -= nonEmpty;
        }
        ItemStack output = ModRecipeIndex.tryGetResultItem(entry.recipe(), ctx.level.registryAccess());
        if (!output.isEmpty()) {
            if (isPreferred(entry, ctx)) {
                score += 10000; // 绝对权重
            }
            if (output.getCount() > 1) {
                score += (output.getCount() - 1) * 5;
            }
        }
        return score;
    }

    private static int scoreRecipe(CraftingRecipe recipe, Context ctx) {
        int score = 0;
        ResourceLocation outputKey = ForgeRegistries.ITEMS.getKey(
                recipe.getResultItem(ctx.level.registryAccess()).getItem());
        if (outputKey != null && ctx.preferredRecipes != null) {
            ResourceLocation preferred = ctx.preferredRecipes.get(outputKey);
            if (preferred != null && preferred.equals(recipe.getId())) {
                score += 10000;
            }
        }

        for (Ingredient ing : recipe.getIngredients()) {
            if (ing.isEmpty()) continue;
            if (ctx.countMatching(ing) > 0) {
                score += 10;
            }
        }

        score -= recipe.getIngredients().size();

        ItemStack output = recipe.getResultItem(ctx.level.registryAccess());
        if (output.getCount() > 1) {
            score += (output.getCount() - 1) * 5;
        }

        return score;
    }

    private static boolean isMachineAvailable(ModType type, Context ctx) {
        if (type == ModType.GENERIC) return true;
        if (ctx.player == null) return false;
        return AltarBindingRegistry.hasAnyBindingForType(ctx.player, type);
    }

    private static boolean requiresMissingSelfInput(ModRecipeIndex.RecipeEntry entry,
                                                    ItemStack output, Context ctx) {
        if (entry.recipe() instanceof CraftingRecipe cr) {
            return requiresMissingSelfInput(cr, output, ctx);
        }
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(entry.recipe());
        if (specs == null || specs.isEmpty()) return false;

        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            // Block self-referencing recipes only when the player has none
            // of the self-referenced item — if they already have some, the
            // recipe can still be useful (e.g. 1 cherry + 1 acacia = 1 acacia).
            if (spec.ingredient().test(output) && ctx.countMatching(spec.ingredient()) <= 0)
                return true;
        }
        return false;
    }

    private static boolean requiresMissingSelfInput(CraftingRecipe recipe, ItemStack output, Context ctx) {
        for (Ingredient ing : recipe.getIngredients()) {
            if (ing.isEmpty()) continue;
            if (ing.test(output) && ctx.countMatching(ing) <= 0) return true;
        }
        return false;
    }

    /**
     * Net items gained per batch — output count minus how many ingredients
     * match the output itself. For self-referencing recipes like
     * "1 calx + 1 dust = 2 calx", net gain is 1, not 2.
     */
    private static int netGainPerBatch(ModRecipeIndex.RecipeEntry entry, ItemStack output) {
        if (entry.recipe() instanceof CraftingRecipe cr) {
            int selfConsumed = 0;
            for (Ingredient ing : cr.getIngredients()) {
                if (ing.isEmpty()) continue;
                ItemStack[] items = ing.getItems();
                // Only single-item ingredients can be reliably flagged as
                // self-consuming.  Using ing.test(output) on a tag ingredient
                // like #logs produces false positives when the output happens
                // to be a log (e.g. "any log -> oak_wood").  The resolver
                // would then underestimate netGain and skip the recipe.
                if (items.length == 1 && !items[0].isEmpty()
                        && ItemStack.isSameItemSameTags(items[0], output)) {
                    selfConsumed++;
                }
            }
            return output.getCount() - selfConsumed;
        }
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(entry.recipe());
        if (specs == null || specs.isEmpty()) return output.getCount();
        int selfConsumed = 0;
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            if (spec.ingredient().test(output))
                selfConsumed += spec.count();
        }
        return output.getCount() - selfConsumed;
    }

    private static String branchKey(ResourceLocation recipeId, ItemStack output) {
        ResourceLocation rl = ForgeRegistries.ITEMS.getKey(output.getItem());
        String itemKey = rl != null ? rl.toString() : "unreg:" + output.getItem().hashCode();
        String nbt = output.getTag() != null && !output.getTag().isEmpty() ? output.getTag().toString() : "";
        return recipeId + "|" + itemKey + "|" + nbt;
    }

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

    @Nullable
    private static Map<ResourceLocation, ResourceLocation> mergeForcedOverrides(
            Level level, @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides) {
        Map<ResourceLocation, ResourceLocation> prefs = buildPreferredRecipes(level);
        if (forcedOverrides == null || forcedOverrides.isEmpty()) return prefs;
        if (prefs == null) return new HashMap<>(forcedOverrides);
        prefs.putAll(forcedOverrides);
        return prefs;
    }

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
        final Set<String> resolving;
        final long deadlineNanos;
        int ensureCalls;
        final java.util.Deque<UndoEntry> undoStack = new java.util.ArrayDeque<>();
        final java.util.Deque<Integer> undoCheckpoints = new java.util.ArrayDeque<>();
        final java.util.Deque<Integer> stepCheckpoints = new java.util.ArrayDeque<>();
        @Nullable final Map<ResourceLocation, ResourceLocation> preferredRecipes;
        @Nullable final ServerPlayer player;
        @Nullable final INetwork network;
        final boolean bestEffort;
        @Nullable final List<String> missingOut;

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

        void beginUndo() {
            undoCheckpoints.push(undoStack.size());
            stepCheckpoints.push(steps.size());
        }

        void commitUndo() {
            undoCheckpoints.pop();
            stepCheckpoints.pop();
            if (undoCheckpoints.isEmpty()) {
                undoStack.clear();
            }
        }

        void rollback() {
            int ucp = undoCheckpoints.pop();
            int scp = stepCheckpoints.pop();
            while (undoStack.size() > ucp) {
                UndoEntry e = undoStack.pop();
                if (e.oldValue == null) counts.remove(e.key);
                else counts.put(e.key, e.oldValue);
            }
            while (steps.size() > scp) {
                steps.remove(steps.size() - 1);
            }
            if (undoCheckpoints.isEmpty()) {
                undoStack.clear();
            }
        }

        void add(ItemStack stack) {
            if (stack.isEmpty() || stack.getCount() <= 0) return;
            StackKey key = StackKey.of(stack, true);
            if (!undoCheckpoints.isEmpty()) {
                undoStack.push(new UndoEntry(key, counts.get(key)));
            }
            counts.merge(key, stack.getCount(), Integer::sum);
        }

        void add(Item item, int count) {
            if (count <= 0) return;
            StackKey key = new StackKey(item, null);
            if (!undoCheckpoints.isEmpty()) {
                undoStack.push(new UndoEntry(key, counts.get(key)));
            }
            counts.merge(key, count, Integer::sum);
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
            // Stable sort: no-NBT first, then by registry name for determinism.
            sortedKeys.sort(Comparator.comparing((StackKey k) -> k.tag() != null)
                    .thenComparing(k -> ForgeRegistries.ITEMS.getKey(k.item()).toString()));

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
            if (!undoCheckpoints.isEmpty()) {
                undoStack.push(new UndoEntry(key, counts.get(key)));
            }
            int current = counts.getOrDefault(key, 0);
            int newCount = current - amount;
            if (newCount <= 0) {
                counts.remove(key);
            } else {
                counts.put(key, newCount);
            }
        }
    }

    static final class UndoEntry {
        final StackKey key;
        @Nullable final Integer oldValue;

        UndoEntry(StackKey key, @Nullable Integer oldValue) {
            this.key = key;
            this.oldValue = oldValue;
        }
    }

    public record StackKey(Item item, @Nullable String tag) {
        static StackKey of(ItemStack stack, boolean includeNbt) {
            Item item = stack.getItem();
            String tag = null;
            if (includeNbt && stack.getTag() != null && !stack.getTag().isEmpty()) {
                tag = stack.getTag().toString();
            }
            return new StackKey(item, tag);
        }
    }
}