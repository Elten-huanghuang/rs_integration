package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.command.PerformanceMonitor;
import com.huanghuang.rsintegration.network.AltarBindingRegistry;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.recipe.SlashBladeRecipeHandler;
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
import net.minecraft.world.level.Level;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

public final class CraftingResolver {

    private static final int MAX_ENSURE_CALLS = 2000;

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

        EdgeTracker edges = new EdgeTracker();
        for (ItemStack stack : needed) {
            if (stack.isEmpty()) continue;
            Ingredient ing = ingredientOf(stack, stack.hasTag());
            if (!ensureIngredient(ing, stack.getCount(), ctx, 0, edges)) {
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

        EdgeTracker edges = new EdgeTracker();
        for (Ingredient ing : needed) {
            if (ing.isEmpty()) continue;
            if (!ensureIngredient(ing, 1, ctx, 0, edges)) {
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

        EdgeTracker edges = new EdgeTracker();
        for (Ingredient ing : needed) {
            if (ing.isEmpty()) continue;
            if (!ensureIngredient(ing, 1, ctx, 0, edges)) {
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

        EdgeTracker edges = new EdgeTracker();
        for (Ingredient ing : needed) {
            if (ing.isEmpty()) continue;
            if (!ensureIngredient(ing, 1, ctx, 0, edges)) {
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
        return resolveStepsForIngredientsWithTypes(needed, availableKeyed, level,
                player, network, missingOut, forcedOverrides, false);
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

        EdgeTracker edges = new EdgeTracker();
        for (Ingredient ing : needed) {
            if (ing.isEmpty()) continue;
            if (!ensureIngredient(ing, 1, ctx, 0, edges)) {
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
                needed.stream().map(s -> ingredientOf(s, s.hasTag())).collect(Collectors.toList()),
                available,
                level, player, network, missingOut);
    }

    static boolean ensureIngredient(Ingredient ingredient, int count, ResolutionContext ctx, int depth,
                                     EdgeTracker edges) {
        if (count <= 0) return true;
        if (ctx.timedOut()) {
            PerformanceMonitor.recordResolveTimeout();
            return false;
        }
        if (++ctx.ensureCalls > MAX_ENSURE_CALLS) return false;

        int minReserve = RSIntegrationConfig.getProtectedReserve(ingredient, ctx.player);

        ctx.beginUndo();
        edges.beginUndo();

        boolean mayConsumeDirect = minReserve <= 0
                || ctx.countMatching(ingredient) >= count + minReserve;
        if (mayConsumeDirect && ctx.consumeMatching(ingredient, count)) {
            ctx.commitUndo();
            edges.commitUndo();
            return true;
        }
        ctx.rollback();
        edges.rollback();

        int alreadyHave = ctx.countMatching(ingredient);
        int remaining = count - alreadyHave;
        if (minReserve > 0) {
            remaining += minReserve;
        }

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

        record AliveCandidate(RecipeIndex.Entry entry, ItemStack output, int netGain) {}
        List<AliveCandidate> alive = new ArrayList<>();
        for (RecipeIndex.Entry candidate : candidates) {
            ItemStack out = ModRecipeHandlers.tryGetResultItem(
                    candidate.recipe(), ctx.level.registryAccess());
            // If the result is bare (no NBT), scan fields for the real
            // NBT-carrying output — TACZ/Applied Armorer hide it there.
            if (!out.isEmpty() && !out.hasTag()) {
                ItemStack hidden = extractHiddenOutput(candidate.recipe());
                if (!hidden.isEmpty()) {
                    out = hidden;
                }
            }
            if (out.isEmpty() || out.getCount() <= 0) {
                continue;
            }
            int ng = netGainPerBatch(candidate, out, ctx.level.registryAccess());
            if (ng <= 0) {
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
            List<ResourceLocation> altIds = new ArrayList<>();
            List<String> altModTypes = new ArrayList<>();
            for (AliveCandidate other : alive) {
                if (other == a) continue;
                altIds.add(other.entry.recipe().getId());
                altModTypes.add(other.entry.modType().id());
            }

            String bk = branchKey(a.entry.recipe().getId(), a.output);
            if (ctx.resolving.contains(bk)) {
                ctx.diag("ensureIngredient SKIP " + a.entry.recipe().getId() + ": cycle detected");
                continue;
            }

            if (requiresMissingSelfInput(a.entry, a.output, ctx)) {
                ctx.diag("ensureIngredient SKIP " + a.entry.recipe().getId() + ": self-input missing");
                continue;
            }

            // Ping-pong guard using StackKey (Item + NBT) to correctly distinguish
            // NBT-differentiated items like TACZ attachments (all tacz:attachment).
            Set<StackKey> inKeys = getRecipeInputKeys(a.entry.recipe(), ctx.level.registryAccess());
            StackKey outKey = StackKey.of(a.output, a.output.hasTag());
            boolean isPingPong = false;
            for (StackKey inKey : inKeys) {
                if (edges.containsReverse(inKey, outKey)) {
                    isPingPong = true;
                    break;
                }
            }
            if (isPingPong) {
                ctx.diag("ensureIngredient SKIP " + a.entry.recipe().getId()
                        + ": ping-pong conversion cycle detected");
                continue;
            }

            int batches = Math.max(1, (int) Math.ceil((double) remaining / a.netGain));

            ctx.beginUndo();
            edges.beginUndo();
            ctx.resolving.add(bk);

            for (StackKey inKey : inKeys) {
                edges.addEdge(inKey, outKey);
            }

            boolean allOk = StepExecutor.craftBatched(a.entry, ctx, depth, altIds, altModTypes, edges, batches);

            ctx.resolving.remove(bk);

            if (!allOk) {
                ctx.diag("ensureIngredient SKIP " + a.entry.recipe().getId() + ": craftOnce failed");
                ctx.rollback();
                edges.rollback();
                continue;
            }

            if (ctx.consumeMatching(ingredient, count)) {
                ctx.diag("ensureIngredient OK " + a.entry.recipe().getId());
                ctx.commitUndo();
                edges.commitUndo();
                return true;
            }

            ctx.diag("ensureIngredient SKIP " + a.entry.recipe().getId() + ": consumeMatching failed after craft");
            ctx.rollback();
            edges.rollback();
        }

        ctx.diag("ensureIngredient FAILED: exhausted " + alive.size() + " viable candidates for "
                + describeFirstItem(ingredient));
        if (ctx.bestEffort && ctx.missingOut != null) {
            ctx.missingOut.add(describeFirstItem(ingredient));
            return true;
        }
        return false;
    }

    private static Set<StackKey> getRecipeInputKeys(Recipe<?> recipe) {
        return getRecipeInputKeys(recipe, null);
    }

    private static Set<StackKey> getRecipeInputKeys(Recipe<?> recipe,
                                                     @Nullable net.minecraft.core.RegistryAccess access) {
        Set<StackKey> inputs = new HashSet<>();
        if (recipe instanceof CraftingRecipe cr) {
            for (Ingredient ing : cr.getIngredients()) {
                if (ing.isEmpty()) continue;
                for (ItemStack stack : ing.getItems()) {
                    if (!stack.isEmpty()) inputs.add(StackKey.of(stack, stack.hasTag()));
                }
            }
            return inputs;
        }
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(recipe);
        if (specs != null && !isIngredientDataBroken(specs)) {
            for (IngredientSpec spec : specs) {
                if (spec.isEmpty()) continue;
                for (ItemStack stack : spec.ingredient().getItems()) {
                    if (!stack.isEmpty()) inputs.add(StackKey.of(stack, stack.hasTag()));
                }
            }
            return inputs;
        }
        // Standard path returned broken data — extract real inputs via reflection.
        if (access != null) {
            List<ItemStack> repaired = getRepairedInputStacks(recipe, access);
            for (ItemStack stack : repaired) {
                inputs.add(StackKey.of(stack, stack.hasTag()));
            }
        }
        return inputs;
    }

    private static boolean requiresMissingSelfInput(RecipeIndex.Entry entry,
                                                    ItemStack output, ResolutionContext ctx) {
        if (entry.recipe() instanceof CraftingRecipe cr) {
            return requiresMissingSelfInput(cr, output, ctx);
        }
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(entry.recipe());
        if (isIngredientDataBroken(specs)) {
            List<ItemStack> repaired = getRepairedInputStacks(entry.recipe(), ctx.level.registryAccess());
            for (ItemStack stack : repaired) {
                Ingredient ing = ingredientOf(stack, stack.hasTag());
                if (ing.test(output) && ctx.countMatching(ing) <= 0) return true;
            }
            return false;
        }
        if (specs == null || specs.isEmpty()) return false;

        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
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

    private static int netGainPerBatch(RecipeIndex.Entry entry, ItemStack output,
                                        net.minecraft.core.RegistryAccess access) {
        if (entry.recipe() instanceof CraftingRecipe cr) {
            int selfConsumed = 0;
            for (Ingredient ing : cr.getIngredients()) {
                if (ing.isEmpty()) continue;
                ItemStack[] items = ing.getItems();
                if (items.length == 1 && !items[0].isEmpty()
                        && ItemStack.isSameItemSameTags(items[0], output)) {
                    selfConsumed++;
                }
            }
            return output.getCount() - selfConsumed;
        }
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(entry.recipe());
        if (isIngredientDataBroken(specs)) {
            int selfConsumed = 0;
            List<ItemStack> repaired = getRepairedInputStacks(entry.recipe(), access);
            for (ItemStack stack : repaired) {
                Ingredient ing = ingredientOf(stack, stack.hasTag());
                if (ing.test(output)) selfConsumed += stack.getCount();
            }
            return output.getCount() - selfConsumed;
        }
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
        String nbt = output.getTag() != null ? output.getTag().toString() : "";
        return recipeId + "|" + itemKey + "|" + nbt;
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
                        stack.setTag(TagParser.parseTag(key.tag()));
                    } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] NBT parse failed: {}", e.toString()); }
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
                result = ModRecipeHandlers.tryGetResultItem(recipe, level.registryAccess());
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
        String base = null;
        for (ItemStack stack : ingredient.getItems()) {
            if (!stack.isEmpty()) {
                base = stack.getHoverName().getString();
                break;
            }
        }
        if (base == null) return "???";
        String nbtHint = SlashBladeRecipeHandler
                .describeNbtRequirements(ingredient);
        return nbtHint != null ? base + nbtHint : base;
    }

    public record ResolutionStep(
            ResourceLocation recipeId,
            ModType modType,
            @Nullable ResourceLocation recipeTypeId,
            List<ResourceLocation> alternativeIds,
            List<String> alternativeModTypes,
            boolean inferMode
    ) {
        public ResolutionStep {
            alternativeIds = List.copyOf(alternativeIds);
            alternativeModTypes = List.copyOf(alternativeModTypes);
        }
        public ResolutionStep(ResourceLocation recipeId, ModType modType,
                              @Nullable ResourceLocation recipeTypeId) {
            this(recipeId, modType, recipeTypeId, List.of(), List.of(), false);
        }
        public ResolutionStep(ResourceLocation recipeId, ModType modType,
                              @Nullable ResourceLocation recipeTypeId,
                              boolean inferMode) {
            this(recipeId, modType, recipeTypeId, List.of(), List.of(), inferMode);
        }
        public ResolutionStep(ResourceLocation recipeId, ModType modType,
                              @Nullable ResourceLocation recipeTypeId,
                              List<ResourceLocation> alternativeIds,
                              List<String> alternativeModTypes) {
            this(recipeId, modType, recipeTypeId, alternativeIds, alternativeModTypes, false);
        }
    }

    public record TraceEntry(ResourceLocation recipeId, ModType modType, int score,
                              boolean skipped, String reason) {}

    // ── Hidden NBT output extraction ──────────────────────────────────
    //
    // Some mods (TACZ, Applied Armorer) have getResultItem() return a bare
    // item without NBT while the real crafted output (with AttachmentId etc.)
    // lives in a private ItemStack field.  Scan every ItemStack field on
    // the recipe class and return the first one that carries NBT — that is
    // almost certainly the real output the mod author intended.

    public static ItemStack extractHiddenOutput(Recipe<?> recipe) {
        Class<?> clazz = recipe.getClass();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                if (f.getType() != ItemStack.class) continue;
                f.setAccessible(true);
                try {
                    ItemStack stack = (ItemStack) f.get(recipe);
                    if (stack != null && !stack.isEmpty() && stack.hasTag()) {
                        return stack.copy();
                    }
                } catch (Exception ignored) {}
            }
            clazz = clazz.getSuperclass();
        }
        return ItemStack.EMPTY;
    }

    // ── Broken ingredient repair ───────────────────────────────────
    //
    // Some mods (TACZ, Applied Armorer) implement getIngredients() by
    // returning bare items without NBT or even AIR placeholders, while
    // the real ingredient data lives in private fields.  When the
    // standard extraction path produces garbage, these helpers detect
    // it and fall back to reflection-based field scanning.

    private static boolean isIngredientDataBroken(List<IngredientSpec> specs) {
        if (specs == null || specs.isEmpty()) return true;
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            for (ItemStack stack : spec.ingredient().getItems()) {
                if (stack.isEmpty()) continue;
                if (stack.getItem() == net.minecraft.world.item.Items.AIR) return true;
                ResourceLocation rl = ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (rl != null && !stack.hasTag()) {
                    String ns = rl.getNamespace();
                    if ("tacz".equals(ns) || "applied_armorer".equals(ns)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Extract real ingredient stacks from a recipe whose standard
     * {@code getIngredients()} / {@code extractIngredientSpecs()} path
     * returned broken data.  Scans private {@code ItemStack} and
     * {@code List<ItemStack>} fields, skipping fields that match the
     * recipe output.
     */
    public static List<ItemStack> getRepairedInputStacks(Recipe<?> recipe, net.minecraft.core.RegistryAccess access) {
        ItemStack output = ModRecipeHandlers.tryGetResultItem(recipe, access);
        if (output.isEmpty() || !output.hasTag()) {
            output = extractHiddenOutput(recipe);
        }

        List<ItemStack> repaired = new ArrayList<>();
        Class<?> clazz = recipe.getClass();

        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                String name = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.contains("out") || name.contains("result")) continue;

                f.setAccessible(true);
                try {
                    Object val = f.get(recipe);
                    if (val instanceof ItemStack stack) {
                        if (!stack.isEmpty() && stack.getItem() != net.minecraft.world.item.Items.AIR) {
                            if (!ItemStack.isSameItemSameTags(stack, output)) {
                                repaired.add(stack.copy());
                            }
                        }
                    } else if (val instanceof List<?> list) {
                        for (Object elem : list) {
                            if (elem instanceof ItemStack stack) {
                                if (!stack.isEmpty() && stack.getItem() != net.minecraft.world.item.Items.AIR) {
                                    if (!ItemStack.isSameItemSameTags(stack, output)) {
                                        repaired.add(stack.copy());
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
            clazz = clazz.getSuperclass();
        }
        return repaired;
    }

    // ── Trace ──────────────────────────────────────────────────────

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

    static Ingredient ingredientOf(ItemStack stack) {
        return ingredientOf(stack, false);
    }

    static Ingredient ingredientOf(ItemStack stack, boolean strictNbt) {
        if (strictNbt && stack.hasTag()) {
            return StrictNBTIngredient.of(stack.copy());
        }
        if (stack.hasTag()) {
            return Ingredient.of(new ItemStack(stack.getItem()));
        }
        return Ingredient.of(stack);
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
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] NBT parse failed: {}", e.toString()); }
            }
            return stack;
        }
    }

    /** Tracks directed conversion edges (StackKey → StackKey) with transactional undo/redo.
     *  Using StackKey (Item + NBT) instead of plain Item prevents false ping-pong
     *  detection for NBT-differentiated items like TACZ attachments. */
    public static class EdgeTracker {
        private final Set<String> activeEdges = new HashSet<>();
        private final List<List<String>> history = new ArrayList<>();

        public void beginUndo() {
            history.add(new ArrayList<>());
        }

        public void commitUndo() {
            if (history.size() > 1) {
                List<String> current = history.remove(history.size() - 1);
                history.get(history.size() - 1).addAll(current);
            } else if (history.size() == 1) {
                history.remove(0);
            }
        }

        public void rollback() {
            if (!history.isEmpty()) {
                List<String> current = history.remove(history.size() - 1);
                activeEdges.removeAll(current);
            }
        }

        public void addEdge(StackKey in, StackKey out) {
            ResourceLocation inRl = ForgeRegistries.ITEMS.getKey(in.item());
            ResourceLocation outRl = ForgeRegistries.ITEMS.getKey(out.item());
            if (inRl == null || outRl == null) return;

            String inStr = inRl + (in.tag() != null ? "|" + in.tag() : "");
            String outStr = outRl + (out.tag() != null ? "|" + out.tag() : "");

            String edge = inStr + "->" + outStr;
            if (activeEdges.add(edge) && !history.isEmpty()) {
                history.get(history.size() - 1).add(edge);
            }
        }

        public boolean containsReverse(StackKey in, StackKey out) {
            ResourceLocation inRl = ForgeRegistries.ITEMS.getKey(in.item());
            ResourceLocation outRl = ForgeRegistries.ITEMS.getKey(out.item());
            if (inRl == null || outRl == null) return false;

            String inStr = inRl + (in.tag() != null ? "|" + in.tag() : "");
            String outStr = outRl + (out.tag() != null ? "|" + out.tag() : "");

            return activeEdges.contains(outStr + "->" + inStr);
        }
    }
}
