package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
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
        ResolutionContext ctx = new ResolutionContext(level,
                RecipeIndex.get(level),
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
        ResolutionContext ctx = new ResolutionContext(level,
                RecipeIndex.get(level),
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
        ResolutionContext ctx = new ResolutionContext(level,
                RecipeIndex.get(level),
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
        ResolutionContext ctx = new ResolutionContext(level,
                RecipeIndex.get(level),
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
        ResolutionContext ctx = new ResolutionContext(level,
                RecipeIndex.get(level),
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
        ResolutionContext ctx = new ResolutionContext(level,
                RecipeIndex.get(level),
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

    static boolean ensureIngredient(Ingredient ingredient, int count, ResolutionContext ctx, int depth) {
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

        List<RecipeIndex.Entry> candidates = CandidateEngine.findCandidates(ingredient, ctx);
        ctx.diag("ensureIngredient candidates=" + candidates.size() + " remaining=" + remaining + " depth=" + depth);
        if (candidates.isEmpty()) {
            ctx.diag("ensureIngredient FAILED: no candidates for " + describeFirstItem(ingredient));
            if (ctx.bestEffort && ctx.missingOut != null) {
                ctx.missingOut.add(describeFirstItem(ingredient));
                return true;
            }
            return false;
        }

        // Pre-filter: exclude candidates that can never work (empty output, non-positive net gain).
        // Contextual checks (cycle, self-input) stay in the per-candidate loop.
        record AliveCandidate(RecipeIndex.Entry entry, ItemStack output, int netGain) {}
        List<AliveCandidate> alive = new ArrayList<>();
        for (RecipeIndex.Entry candidate : candidates) {
            ItemStack out = com.huanghuang.rsintegration.recipe.ModRecipeHandlers.tryGetResultItem(
                    candidate.recipe(), ctx.level.registryAccess());
            if (out.isEmpty() || out.getCount() <= 0) {
                ctx.diag("ensureIngredient SKIP " + candidate.recipe().getId() + ": output empty");
                continue;
            }
            int ng = netGainPerBatch(candidate, out);
            if (ng <= 0) {
                ctx.diag("ensureIngredient SKIP " + candidate.recipe().getId() + ": netGain=" + ng);
                continue;
            }
            alive.add(new AliveCandidate(candidate, out, ng));
        }

        if (alive.isEmpty()) {
            ctx.diag("ensureIngredient FAILED: no viable candidates for " + describeFirstItem(ingredient));
            if (ctx.bestEffort && ctx.missingOut != null) {
                ctx.missingOut.add(describeFirstItem(ingredient));
                return true;
            }
            return false;
        }

        for (AliveCandidate a : alive) {
            // Build alternatives: all other alive candidates
            List<ResourceLocation> altIds = new ArrayList<>();
            List<String> altModTypes = new ArrayList<>();
            for (AliveCandidate other : alive) {
                if (other == a) continue;
                altIds.add(other.entry.recipe().getId());
                altModTypes.add(other.entry.modType().id());
            }

            // Contextual checks
            String bk = branchKey(a.entry.recipe().getId(), a.output);
            if (ctx.resolving.contains(bk)) {
                ctx.diag("ensureIngredient SKIP " + a.entry.recipe().getId() + ": cycle detected");
                continue;
            }

            if (requiresMissingSelfInput(a.entry, a.output, ctx)) {
                ctx.diag("ensureIngredient SKIP " + a.entry.recipe().getId() + ": self-input missing");
                continue;
            }

            int batches = Math.max(1, (int) Math.ceil((double) remaining / a.netGain));
            ctx.beginUndo();
            ctx.resolving.add(bk);

            boolean allOk = true;
            for (int b = 0; b < batches && allOk; b++) {
                allOk = StepExecutor.craftOnce(a.entry, ctx, depth, altIds, altModTypes);
            }

            ctx.resolving.remove(bk);

            if (!allOk) {
                ctx.diag("ensureIngredient SKIP " + a.entry.recipe().getId() + ": craftOnce failed");
                ctx.rollback();
                continue;
            }

            if (ctx.consumeMatching(ingredient, count)) {
                ctx.diag("ensureIngredient OK " + a.entry.recipe().getId());
                ctx.commitUndo();
                return true;
            }

            ctx.diag("ensureIngredient SKIP " + a.entry.recipe().getId() + ": consumeMatching failed after craft");
            ctx.rollback();
        }

        ctx.diag("ensureIngredient FAILED: exhausted " + alive.size() + " viable candidates for "
                + describeFirstItem(ingredient));
        return false;
    }

    private static boolean requiresMissingSelfInput(RecipeIndex.Entry entry,
                                                    ItemStack output, ResolutionContext ctx) {
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

    private static boolean requiresMissingSelfInput(CraftingRecipe recipe, ItemStack output, ResolutionContext ctx) {
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
    private static int netGainPerBatch(RecipeIndex.Entry entry, ItemStack output) {
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


    private static ItemStack stackFromKey(StackKey key) {
        ItemStack stack = new ItemStack(key.item());
        if (key.tag() != null) {
            try {
                CompoundTag tag = TagParser.parseTag(key.tag());
                stack.setTag(tag);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
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
                    } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
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
                result = com.huanghuang.rsintegration.recipe.ModRecipeHandlers.tryGetResultItem(recipe, level.registryAccess());
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
            @Nullable ResourceLocation recipeTypeId,
            List<ResourceLocation> alternativeIds,
            List<String> alternativeModTypes
    ) {
        public ResolutionStep {
            alternativeIds = List.copyOf(alternativeIds);
            alternativeModTypes = List.copyOf(alternativeModTypes);
        }

        /** Backward-compatible constructor — no alternatives. */
        public ResolutionStep(ResourceLocation recipeId, ModType modType,
                              @Nullable ResourceLocation recipeTypeId) {
            this(recipeId, modType, recipeTypeId, List.of(), List.of());
        }
    }

    public record TraceEntry(ResourceLocation recipeId, ModType modType, int score,
                              boolean skipped, String reason) {}

    /**
     * Produce a diagnostic trace of all candidate recipes considered for an
     * ingredient, including skipped candidates and their skip reasons.
     */
    public static List<TraceEntry> traceCandidates(Ingredient ingredient,
                                                    Level level,
                                                    @Nullable ServerPlayer player) {
        return traceCandidates(ingredient, level, player, Map.of());
    }

    public static List<TraceEntry> traceCandidates(Ingredient ingredient,
                                                    Level level,
                                                    @Nullable ServerPlayer player,
                                                    Map<StackKey, Integer> available) {
        ResolutionContext ctx = new ResolutionContext(level,
                RecipeIndex.get(level), available, null, player, null, false, null);
        List<CandidateEngine.CandidateDiagnostic> raw = new ArrayList<>();
        CandidateEngine.findCandidates(ingredient, ctx, raw);
        List<TraceEntry> out = new ArrayList<>();
        for (var d : raw) {
            out.add(new TraceEntry(d.recipeId(), d.modType(), d.score(), d.skipped(), d.skipReason()));
        }
        return out;
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

        public ItemStack toStack() {
            ItemStack stack = new ItemStack(item);
            if (tag != null) {
                try {
                    stack.setTag(TagParser.parseTag(tag));
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
            }
            return stack;
        }
    }
}