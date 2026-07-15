package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanValidator;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.InputDemand;
import com.huanghuang.rsintegration.crafting.graph.InputPortId;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.graph.RootAllocation;
import com.huanghuang.rsintegration.crafting.graph.RootDemand;
import com.huanghuang.rsintegration.command.PerformanceMonitor;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
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

    /** Synthetic async step: let Enigmatic Legacy taint an Earth Heart in the
     * player's inventory, then return that exact instance to the chain. */
    public static final ResourceLocation TAINT_EARTH_HEART_STEP =
            new ResourceLocation(RSIntegrationMod.MOD_ID, "internal/taint_earth_heart");
    private static final ResourceLocation EARTH_HEART_ID =
            new ResourceLocation("enigmaticlegacy", "earth_heart");

    private static final int MAX_ENSURE_CALLS = 2000; // fallback if config unavailable

    /** Per-plan recursion cap from config, falling back to the default. */
    private static int maxEnsureCalls() {
        try {
            return RSIntegrationConfig.CRAFTING_MAX_ENSURE_CALLS.get();
        } catch (Exception e) {
            return MAX_ENSURE_CALLS;
        }
    }

    /** Cache for {@link #extractHiddenOutput(Recipe)} — avoids repeated
     *  reflection field-scanning in DFS candidate loops. */
    private static final java.util.concurrent.ConcurrentHashMap<ResourceLocation, ItemStack>
            HIDDEN_OUTPUT_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

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

    // ── IngredientSpec-based resolution (preserves Tag + real count) ──

    /**
     * Resolve crafting steps for ingredients with per-slot counts preserved.
     * Unlike the older {@code resolveStepsForIngredientsWithTypes} which
     * hardcodes count=1, this method uses the real {@code spec.count()} so
     * recipes requiring multiple items per slot (e.g. 5 glass panes) are
     * planned correctly.
     */
    public static List<ResolutionStep> resolveStepsForSpecsWithTypes(
            List<IngredientSpec> needed,
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
        for (IngredientSpec spec : needed) {
            if (spec.isEmpty()) continue;
            if (!ensureIngredient(spec.ingredient(), spec.count(), ctx, 0, edges)) {
                if (missingOut != null) {
                    missingOut.add(describeFirstItem(spec.ingredient()));
                }
            }
        }
        return new ArrayList<>(ctx.steps);
    }

    public static CraftPlanGraph resolveGraphForSpecsWithTypes(
            List<IngredientSpec> needed,
            Map<StackKey, Integer> availableKeyed,
            Level level,
            @Nullable ServerPlayer player,
            @Nullable INetwork network,
            @Nullable List<String> missingOut,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides,
            boolean bestEffort) {
        Map<ResourceLocation, ResourceLocation> prefs = mergeForcedOverrides(level, forcedOverrides);
        ResolutionContext ctx = new ResolutionContext(level, RecipeIndex.get(level), availableKeyed,
                prefs, player, network, bestEffort, missingOut);
        EdgeTracker edges = new EdgeTracker();
        List<RootDemand> roots = new ArrayList<>();

        for (int rootIndex = 0; rootIndex < needed.size(); rootIndex++) {
            IngredientSpec spec = needed.get(rootIndex);
            if (spec.isEmpty()) continue;
            List<ResolutionContext.SupplySlice> consumed = new ArrayList<>();
            boolean resolved = ensureIngredient(spec.ingredient(), spec.count(), ctx, 0, edges, null, consumed);
            List<RootAllocation> allocations = new ArrayList<>(consumed.size());
            for (ResolutionContext.SupplySlice slice : consumed) {
                allocations.add(new RootAllocation(slice.source(), slice.material(), slice.quantity()));
            }
            int supplied = consumed.stream().mapToInt(ResolutionContext.SupplySlice::quantity).sum();
            int missing = Math.max(0, spec.count() - supplied);
            if (!resolved && missingOut != null) missingOut.add(describeFirstItem(spec.ingredient()));
            roots.add(new RootDemand(spec.ingredient(), spec.count(), missing,
                    firstDisplayStack(spec.ingredient()), allocations));
            if (!resolved && !bestEffort) break;
        }

        CraftPlanGraph graph = new CraftPlanGraph(CraftPlanGraph.CURRENT_VERSION,
                ctx.graphNodes, ctx.graphAllocations, roots, ctx.graphUnresolved,
                ctx.graphNodes.stream().map(com.huanghuang.rsintegration.crafting.graph.CraftNode::id).toList());
        CraftPlanValidator.validate(graph);
        return graph;
    }

    private static ItemStack firstDisplayStack(Ingredient ingredient) {
        ItemStack[] display = ingredient.getItems();
        return display.length == 0 ? ItemStack.EMPTY : display[0].copyWithCount(1);
    }

    /** Vanilla-only variant that returns flat recipe-id list. */
    public static List<ResourceLocation> resolveStepsForSpecs(
            List<IngredientSpec> needed,
            Map<StackKey, Integer> availableKeyed,
            Level level,
            @Nullable List<String> missingOut) {
        List<ItemStack> availStacks = stacksFromKeyedCounts(availableKeyed);
        ResolutionContext ctx = new ResolutionContext(level,
                RecipeIndex.get(level),
                availStacks,
                buildPreferredRecipes(level));

        EdgeTracker edges = new EdgeTracker();
        for (IngredientSpec spec : needed) {
            if (spec.isEmpty()) continue;
            if (!ensureIngredient(spec.ingredient(), spec.count(), ctx, 0, edges)) {
                if (missingOut != null) {
                    missingOut.add(describeFirstItem(spec.ingredient()));
                }
            }
        }
        return ctx.steps.stream().map(ResolutionStep::recipeId).collect(Collectors.toList());
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
        return ensureIngredient(ingredient, count, ctx, depth, edges, null, null);
    }

    static boolean ensureIngredient(Ingredient ingredient, int count, ResolutionContext ctx, int depth,
                                     EdgeTracker edges, @Nullable InputPortId consumer,
                                     @Nullable List<ResolutionContext.SupplySlice> consumedOut) {
        if (count <= 0) return true;

        int minReserve = RSIntegrationConfig.getProtectedReserve(ingredient);

        ctx.beginUndo();
        edges.beginUndo();

        long consumeStart = System.nanoTime();
        boolean mayConsumeDirect = minReserve <= 0
                || ctx.countMatching(ingredient) >= count + minReserve;
        if (mayConsumeDirect) {
            ResolutionContext.SupplyConsumption direct = ctx.consumeMatchingDetailed(ingredient, count);
            if (direct.complete()) {
                recordSupplyConsumption(ctx, consumer, consumedOut, direct);
                ctx.commitUndo();
                edges.commitUndo();
                return true;
            }
        }
        ctx.rollback();
        edges.rollback();

        // Budget guards gate only the expensive recursive candidate search below.
        // An ingredient already present in inventory is consumed above, BEFORE these
        // checks run — otherwise a large plan that exhausts the 500ms deadline (or the
        // ensureCalls cap) would falsely mark a directly-available ingredient as
        // missing purely because of its position in resolution order (e.g. Malum
        // spirits resolve last, after costly base-material sub-crafts).
        if (ctx.timedOut()) {
            PerformanceMonitor.recordResolveTimeout();
            return false;
        }
        if (++ctx.ensureCalls > maxEnsureCalls()) return false;

        int alreadyHave = ctx.countMatching(ingredient);
        long consumeMs = (System.nanoTime() - consumeStart) / 1_000_000;
        int remaining = count - alreadyHave;
        if (remaining > 0 && isTaintedEarthHeartRequirement(ingredient)) {
            Ingredient plainHeart = Ingredient.of(ForgeRegistries.ITEMS.getValue(EARTH_HEART_ID));
            NodeId nodeId = ctx.allocateNodeId();
            InputPortId inputPort = new InputPortId(nodeId, 0);
            if (plainHeart.getItems().length > 0
                    && ensureIngredient(plainHeart, remaining, ctx, depth + 1, edges, inputPort, null)) {
                ItemStack plainTemplate = plainHeart.getItems()[0].copyWithCount(1);
                ItemStack taintedTemplate = ingredient.getItems().length > 0
                        ? ingredient.getItems()[0].copyWithCount(1) : ItemStack.EMPTY;
                if (taintedTemplate.isEmpty()) return false;
                ResolutionStep step = new ResolutionStep(TAINT_EARTH_HEART_STEP, ModType.GENERIC,
                        TAINT_EARTH_HEART_STEP, List.of(), List.of(), false, remaining,
                        plainTemplate, taintedTemplate);
                ctx.steps.add(step);

                ItemStack tainted = taintedTemplate.copyWithCount(remaining);
                OutputPortId outputPort = new OutputPortId(nodeId, 0);
                ctx.addProduced(tainted, new MaterialSource.ProducerOutput(outputPort));
                ctx.addGraphNode(new CraftNode(nodeId, step.recipeId(), step.modType().id(),
                        step.recipeTypeId(), step.executions(), step.alternativeIds(),
                        step.alternativeModTypes(), step.inferMode(), step.syntheticInput(),
                        step.syntheticOutput(), List.of(new InputDemand(inputPort, plainHeart,
                        remaining, DemandRole.TRANSFORMED, plainTemplate)),
                        List.of(new OutputDeclaration(outputPort, MaterialKey.of(tainted),
                                remaining, OutputKind.SYNTHETIC))));
                alreadyHave = ctx.countMatching(ingredient);
                remaining = count - alreadyHave;
            }
        }
        if (minReserve > 0) {
            remaining += minReserve;
        }

        if (remaining <= 0) {
            ResolutionContext.SupplyConsumption existing = ctx.consumeMatchingDetailed(ingredient, count);
            if (existing.complete()) recordSupplyConsumption(ctx, consumer, consumedOut, existing);
            return existing.complete();
        }

        long candStart = System.nanoTime();
        List<RecipeIndex.Entry> candidates = CandidateEngine.findCandidates(ingredient, ctx);
        long candMs = (System.nanoTime() - candStart) / 1_000_000;
        ctx.diag("ensureIngredient candidates=" + candidates.size() + " remaining=" + remaining + " depth=" + depth);
        RSIntegrationMod.LOGGER.debug("[RSI-ensure] countMatch/consume={}ms, findCandidates={}ms, {} candidates for {}",
                consumeMs, candMs, candidates.size(), describeFirstItem(ingredient));
        if (candidates.isEmpty()) {
            ctx.diag("ensureIngredient FAILED: no candidates for " + describeFirstItem(ingredient));
            if (ctx.bestEffort && ctx.missingOut != null) {
                recordUnresolved(ctx, consumer, ingredient, count);
                return true;
            }
            return false;
        }

        record AliveCandidate(RecipeIndex.Entry entry, ItemStack output, int netGain) {}
        List<AliveCandidate> alive = new ArrayList<>();
        long aliveStart = System.nanoTime();
        for (RecipeIndex.Entry candidate : candidates) {
            if (ctx.timedOut()) {
                long aliveMs = (System.nanoTime() - aliveStart) / 1_000_000;
                RSIntegrationMod.LOGGER.debug("[RSI-ensure] alive-building timed out after {}ms, keeping {}/{} candidates",
                        aliveMs, alive.size(), candidates.size());
                PerformanceMonitor.recordResolveTimeout();
                break;
            }
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

        long aliveMs = (System.nanoTime() - aliveStart) / 1_000_000;
        if (alive.isEmpty()) {
            ctx.diag("ensureIngredient FAILED: no viable candidates (total=" + candidates.size()
                    + ", all rejected) for " + describeFirstItem(ingredient));
            RSIntegrationMod.LOGGER.debug("[RSI-ensure] no viable candidates for {} ({} total candidates rejected, alive-building took {}ms)",
                    describeFirstItem(ingredient), candidates.size(), aliveMs);
            if (ctx.bestEffort && ctx.missingOut != null) {
                recordUnresolved(ctx, consumer, ingredient, count);
                return true;
            }
            return false;
        }

        if (RSIntegrationMod.LOGGER.isDebugEnabled() && alive.size() > 1) {
            StringBuilder sb = new StringBuilder("[RSI-ensure] ").append(alive.size())
                    .append(" alive candidates for ").append(describeFirstItem(ingredient)).append(":");
            for (AliveCandidate a : alive) {
                sb.append(" [").append(a.entry.recipe().getId()).append(" netGain=").append(a.netGain).append("]");
            }
            RSIntegrationMod.LOGGER.debug(sb.toString());
        }

        for (AliveCandidate a : alive) {
            // Don't check timedOut() before the first candidate — the alive-building
            // loop may have exhausted the deadline with expensive result-item probes
            // for mod recipes. The first candidate is the highest-scored and is near-
            // certain to be a vanilla recipe with fast craftBatched.
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
                if (ctx.timedOut()) {
                    PerformanceMonitor.recordResolveTimeout();
                    if (ctx.bestEffort && ctx.missingOut != null) {
                        ctx.missingOut.add(describeFirstItem(ingredient));
                        return true;
                    }
                    return false;
                }
                continue;
            }

            ResolutionContext.SupplyConsumption crafted = ctx.consumeMatchingDetailed(ingredient, count);
            if (crafted.complete()) {
                recordSupplyConsumption(ctx, consumer, consumedOut, crafted);
                ctx.diag("ensureIngredient OK " + a.entry.recipe().getId());
                ctx.commitUndo();
                edges.commitUndo();
                return true;
            }

            ctx.diag("ensureIngredient SKIP " + a.entry.recipe().getId() + ": consumeMatching failed after craft");
            ctx.rollback();
            edges.rollback();
            if (ctx.timedOut()) {
                PerformanceMonitor.recordResolveTimeout();
                if (ctx.bestEffort && ctx.missingOut != null) {
                    ctx.missingOut.add(describeFirstItem(ingredient));
                    return true;
                }
                return false;
            }
        }

        ctx.diag("ensureIngredient FAILED: exhausted " + alive.size() + " viable candidates for "
                + describeFirstItem(ingredient));
        if (ctx.bestEffort && ctx.missingOut != null) {
            ctx.missingOut.add(describeFirstItem(ingredient));
            return true;
        }
        return false;
    }

    private static void recordUnresolved(ResolutionContext ctx, @Nullable InputPortId consumer,
                                         Ingredient ingredient, int quantity) {
        ctx.missingOut.add(describeFirstItem(ingredient));
        if (consumer != null) ctx.addUnresolved(consumer, ingredient, quantity);
    }

    private static void recordSupplyConsumption(ResolutionContext ctx,
                                                @Nullable InputPortId consumer,
                                                @Nullable List<ResolutionContext.SupplySlice> consumedOut,
                                                ResolutionContext.SupplyConsumption consumption) {
        if (consumer != null) {
            for (ResolutionContext.SupplySlice slice : consumption.slices()) {
                ctx.addAllocation(consumer, slice);
            }
        }
        if (consumedOut != null) consumedOut.addAll(consumption.slices());
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

        // Allow amplification recipes (output > self-consumed) even when
        // a self-matching ingredient is temporarily unavailable — recursive
        // ensureIngredient will either locate the base material or hit
        // cycle detection via the resolving set.
        int selfConsumed = 0;
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            if (spec.ingredient().test(output)) {
                selfConsumed += spec.count();
            }
        }
        if (output.getCount() > selfConsumed) return false;

        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            if (spec.ingredient().test(output) && ctx.countMatching(spec.ingredient()) <= 0)
                return true;
        }
        return false;
    }

    private static boolean requiresMissingSelfInput(CraftingRecipe recipe, ItemStack output, ResolutionContext ctx) {
        int selfConsumed = 0;
        boolean anyMissing = false;
        for (Ingredient ing : recipe.getIngredients()) {
            if (ing.isEmpty()) continue;
            if (ing.test(output)) {
                selfConsumed++;
                if (ctx.countMatching(ing) <= 0) anyMissing = true;
            }
        }
        // Amplification recipes (A + catalyst → n*A, n > 1): allow even when
        // the self-matching input is temporarily unavailable.
        if (output.getCount() > selfConsumed) return false;
        return anyMissing;
    }

    private static int netGainPerBatch(RecipeIndex.Entry entry, ItemStack output,
                                        net.minecraft.core.RegistryAccess access) {
        if (entry.recipe() instanceof CraftingRecipe cr) {
            int selfConsumed = 0;
            for (Ingredient ing : cr.getIngredients()) {
                if (ing.isEmpty()) continue;
                if (ing.test(output)) {
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
                    } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] NBT parse failed", e); }
                }
                list.add(stack);
            }
        }
        return list;
    }

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

    private static boolean isTaintedEarthHeartRequirement(Ingredient ingredient) {
        for (ItemStack stack : ingredient.getItems()) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (EARTH_HEART_ID.equals(id) && stack.getTag() != null
                    && stack.getTag().getBoolean("isTainted")) return true;
        }
        return false;
    }

    public record ResolutionStep(
            ResourceLocation recipeId,
            ModType modType,
            @Nullable ResourceLocation recipeTypeId,
            List<ResourceLocation> alternativeIds,
            List<String> alternativeModTypes,
            boolean inferMode,
            int executions,
            @Nullable ItemStack syntheticInput,
            @Nullable ItemStack syntheticOutput
    ) {
        public ResolutionStep {
            alternativeIds = List.copyOf(alternativeIds);
            alternativeModTypes = List.copyOf(alternativeModTypes);
            if (executions < 1) executions = 1;
            syntheticInput = syntheticInput == null ? null : syntheticInput.copyWithCount(1);
            syntheticOutput = syntheticOutput == null ? null : syntheticOutput.copyWithCount(1);
        }
        /** Backward-compatible: explicit executions. */
        public ResolutionStep(ResourceLocation recipeId, ModType modType,
                              @Nullable ResourceLocation recipeTypeId,
                              List<ResourceLocation> alternativeIds,
                              List<String> alternativeModTypes,
                              boolean inferMode, int executions) {
            this(recipeId, modType, recipeTypeId, alternativeIds, alternativeModTypes,
                    inferMode, executions, null, null);
        }
        /** Backward-compatible: single execution. */
        public ResolutionStep(ResourceLocation recipeId, ModType modType,
                              @Nullable ResourceLocation recipeTypeId,
                              List<ResourceLocation> alternativeIds,
                              List<String> alternativeModTypes,
                              boolean inferMode) {
            this(recipeId, modType, recipeTypeId, alternativeIds, alternativeModTypes, inferMode, 1, null, null);
        }
        public ResolutionStep(ResourceLocation recipeId, ModType modType,
                              @Nullable ResourceLocation recipeTypeId) {
            this(recipeId, modType, recipeTypeId, List.of(), List.of(), false, 1, null, null);
        }
        public ResolutionStep(ResourceLocation recipeId, ModType modType,
                              @Nullable ResourceLocation recipeTypeId,
                              boolean inferMode) {
            this(recipeId, modType, recipeTypeId, List.of(), List.of(), inferMode, 1, null, null);
        }
        public ResolutionStep(ResourceLocation recipeId, ModType modType,
                              @Nullable ResourceLocation recipeTypeId,
                              List<ResourceLocation> alternativeIds,
                              List<String> alternativeModTypes) {
            this(recipeId, modType, recipeTypeId, alternativeIds, alternativeModTypes, false, 1, null, null);
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
        ResourceLocation id = recipe.getId();
        ItemStack cached = HIDDEN_OUTPUT_CACHE.get(id);
        if (cached != null) return cached.copy();

        Class<?> clazz = recipe.getClass();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                if (f.getType() != ItemStack.class) continue;
                f.setAccessible(true);
                try {
                    ItemStack stack = (ItemStack) f.get(recipe);
                    if (stack != null && !stack.isEmpty() && stack.hasTag()) {
                        ItemStack result = stack.copy();
                        HIDDEN_OUTPUT_CACHE.put(id, result.copy());
                        return result;
                    }
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Resolver] reflection read failed", e); }
            }
            clazz = clazz.getSuperclass();
        }
        // Cache negative result too so we don't re-scan recipes with no hidden output
        HIDDEN_OUTPUT_CACHE.put(id, ItemStack.EMPTY);
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
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Resolver] reflection read failed", e); }
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
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] NBT parse failed", e); }
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
            }
            // When history.size() == 1 (outermost level), edges stay in activeEdges
            // as committed — they prevent reverse edges across the entire plan.
            // The level is kept as a permanent baseline so future rollback() calls
            // only undo edges added by subsequent beginUndo() pushes.
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
