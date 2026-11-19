package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.crafting.graph.InputDemand;
import com.huanghuang.rsintegration.crafting.graph.InputPortId;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.planning.FrozenRecipeBranch;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.mods.vanilla.VanillaMachineRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.PerformanceMonitor;
import com.huanghuang.rsintegration.util.PerformanceMonitor.PlanningLatencyPhase;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraftforge.common.crafting.StrictNBTIngredient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** 兼容求解器已选定一条路线后，在原候选的事务内尝试冻结其确定性分支。 */
final class HybridBranchPlanning {
    private final FrozenRecipeBranch.Dispatcher dispatcher;
    private final CraftingResolver.BranchStockValidator checkStock;
    private Capture pending;
    int accepted;
    int submitted;
    private String lastBoundary = "";

    String summary() {
        return "submitted=" + submitted + " accepted=" + accepted + " boundary=" + lastBoundary;
    }
    private final Map<FrozenRecipeBranch, FrozenRecipeBranch.Candidate> singleSteps = new HashMap<>();
    private final Map<StackKey, MaterialRef> identities = new HashMap<>();

    HybridBranchPlanning(FrozenRecipeBranch.Dispatcher dispatcher, CraftingResolver.BranchStockValidator checkStock) {
        this.dispatcher = dispatcher;
        this.checkStock = checkStock;
    }

    boolean waiting() { return pending != null; }

    void cancel() { pending = null; }

    static boolean nativeMachine(Class<?> type) {
        return type == BlastingRecipe.class || type == SmeltingRecipe.class
                || type == SmokingRecipe.class || type == StonecutterRecipe.class;
    }

    boolean start(RecipeIndex.Entry entry, ResolutionContext context, int depth, int batches,
                  List<ResourceLocation> alternatives, List<String> alternativeTypes, ItemStack output,
                  PlanningStack stack, Runnable fallback, Consumer<Boolean> done) {
        Class<?> type = entry.recipe().getClass();
        if (!nativeMachine(type) && type != ShapedRecipe.class && type != ShapelessRecipe.class) return false;
        if (output == null || output.isEmpty() || !context.activeConversionFamilies.isEmpty()) return false;
        var capture = new Capture(context, entry, output, batches, alternatives, alternativeTypes, stack, fallback, done);
        capture.queue.add(new Visit(entry, StackKey.of(output, true), depth));
        stack.resume(capture::advance);
        return true;
    }

    private record Visit(RecipeIndex.Entry entry, StackKey output, int depth) {}
    private record Slot(Ingredient predicate, StackKey key, int count) {}
    private record Operation(RecipeIndex.Entry entry, StackKey output, int yield, List<Slot> slots) {}

    private final class Capture {
        final ResolutionContext ctx;
        final RecipeIndex.Entry root;
        final StackKey target;
        final int batches;
        final List<ResourceLocation> alternatives;
        final List<String> alternativeTypes;
        final PlanningStack stack;
        final Runnable fallback;
        final Consumer<Boolean> done;
        final ArrayDeque<Visit> queue = new ArrayDeque<>();
        final Map<StackKey, Long> initial = new LinkedHashMap<>();
        final Map<ResourceLocation, Operation> operations = new HashMap<>();
        final Map<MaterialRef, List<RecipeNode>> producers = new HashMap<>();
        final Set<StackKey> discovered = new HashSet<>();
        FrozenRecipeBranch.Candidate candidate;
        FrozenRecipeBranch frozen;
        int cursor;
        boolean transaction;
        String checking;

        Capture(ResolutionContext ctx, RecipeIndex.Entry root, ItemStack output, int batches,
                List<ResourceLocation> alternatives, List<String> alternativeTypes, PlanningStack stack,
                Runnable fallback, Consumer<Boolean> done) {
            this.ctx = ctx;
            this.root = root;
            this.target = StackKey.of(output, true);
            this.batches = batches;
            this.alternatives = alternatives;
            this.alternativeTypes = alternativeTypes;
            this.stack = stack;
            this.fallback = fallback;
            this.done = done;
            discovered.add(target);
        }

        // 字典按精确物品和规范 NBT 双向隔离变体，后台只比较值，无需持有注册表或 Ingredient。
        MaterialRef name(StackKey key) {
            return identities.computeIfAbsent(key, ignored ->
                    new MaterialRef(ResourceLocation.fromNamespaceAndPath("rsi_branch", Integer.toString(identities.size())), ""));
        }

        void advance() {
            long start = System.nanoTime();
            try {
                if (!queue.isEmpty()) {
                    Visit visit = queue.removeFirst();
                    if (freeze(visit)) stack.resume(this::advance);
                    else {
                        lastBoundary = visit.entry().recipe().getId() + "/" + checking;
                        stack.resume(fallback);
                    }
                    return;
                }
                Map<MaterialRef, Long> stock = new HashMap<>();
                initial.forEach((key, amount) -> {
                    if (!key.equals(target) && amount > 0) stock.put(name(key), amount);
                });
                long quantity = (long) batches * operations.get(root.recipe().getId()).yield();
                if (operations.size() == 1) {
                    Map<MaterialRef, Long> required = new HashMap<>();
                    for (Slot slot : operations.get(root.recipe().getId()).slots()) {
                        required.merge(name(slot.key()), (long) batches * slot.count(), Math::addExact);
                    }
                    stock.replaceAll((key, amount) -> Math.min(amount, required.getOrDefault(key, 0L)));
                }
                frozen = new FrozenRecipeBranch(producers, stock, new IngredientRef(List.of(name(target)), quantity),
                        operations.size(), RSIntegrationConfig.CRAFTING_PURE_SEARCH_MAX_STATES.get());
                candidate = operations.size() == 1 ? singleSteps.get(frozen) : null;
                stack.resume(this::validate);
                if (candidate != null) return;
                pending = this;
                submitted++;
                dispatcher.submit(frozen, result -> {
                    if (pending == this) {
                        candidate = result;
                        if (result != null && operations.size() == 1 && singleSteps.size() < StepExecutor.maxSteps()) {
                            singleSteps.put(frozen, result);
                        }
                        pending = null;
                    }
                });
            } finally {
                PerformanceMonitor.recordPlanningLatency(PlanningLatencyPhase.HYBRID_CAPTURE, System.nanoTime() - start);
            }
        }

        boolean freeze(Visit visit) {
            checking = "budget";
            if (visit.depth() > StepExecutor.maxDepth() || operations.size() + ctx.steps.size() >= StepExecutor.maxSteps()) return false;
            var entry = visit.entry();
            var recipe = entry.recipe();
            var claim = ModRecipeHandlers.claimFor(recipe);
            var handler = claim.winner();
            Class<?> type = recipe.getClass();
            boolean machine = nativeMachine(type);
            checking = "handler";
            if (claim.ambiguous() || handler != null && !handler.supportsFixedBranchPlanning(recipe)) return false;
            if (machine ? !(handler instanceof VanillaMachineRecipeHandler)
                    : entry.modType() != ModType.GENERIC
                    || type != ShapedRecipe.class && type != ShapelessRecipe.class) return false;
            var output = recipe.getResultItem(ctx.level.registryAccess());
            checking = "output";
            if (output.isEmpty() || !StackKey.of(output, true).equals(visit.output())) return false;
            if (recipe instanceof CraftingRecipe grid && (!CraftPacketUtils.isCraftingRecipeAvailable(grid, ctx.player)
                    || !CraftPacketUtils.getRecipeRemainders(grid).isEmpty()
                    || !ModRecipeHandlers.tryGetSecondaryOutputs(grid, ctx.level.registryAccess()).isEmpty())) return false;
            List<Slot> slots = new ArrayList<>();
            List<IngredientRef> inputs = new ArrayList<>();
            var specs = machine ? handler.getIngredients(recipe)
                    : CraftPacketUtils.extractCraftingIngredientSpecs((CraftingRecipe) recipe);
            if (specs == null) return false;
            for (IngredientSpec spec : ctx.lockedSpecs(recipe.getId(), specs)) {
                if (spec.isEmpty()) continue;
                checking = "role";
                if (!spec.consumption().equals(InputConsumption.consumed())) return false;
                Ingredient predicate = spec.ingredient();
                checking = "predicate";
                if (predicate.getClass() != Ingredient.class && !(predicate instanceof StrictNBTIngredient)) return false;
                ItemStack[] options = predicate.getItems();
                if (options.length != 1 || options[0].isEmpty() || !options[0].getCraftingRemainingItem().isEmpty()
                        || ctx.forcedRecipeFor(predicate) != null || RSIntegrationConfig.getProtectedReserve(predicate) > 0) return false;
                StackKey key = StackKey.of(options[0], true);
                long available = ctx.counts.getOrDefault(key, 0L);
                checking = "stock-or-cycle";
                if (ctx.countMatching(predicate) != available || !IngredientMatcher.test(predicate, key)
                        || ctx.recipeCycles.containsOutput(options[0])) return false;
                initial.put(key, available);
                inputs.add(new IngredientRef(List.of(name(key)), spec.count()));
                slots.add(new Slot(predicate, key, spec.count()));
                if (available == 0 && discovered.add(key)) {
                    // 只有唯一的普通网格生产者可继续展开。其余路线回到原候选筛选与机器探测。
                    var indexed = ctx.index.getOrDefault(key.item(), List.of());
                    checking = "producer";
                    if (indexed.size() != 1 || ctx.isNoRecipe(predicate)) return false;
                    var child = indexed.get(0);
                    if (child.modType() != ModType.GENERIC || !(child.recipe() instanceof CraftingRecipe)
                            || ctx.excludedRecipes.contains(child.recipe().getId())
                            || !CandidateEngine.safeFrozenGrid(child, options[0], ctx)) return false;
                    queue.add(new Visit(child, key, visit.depth() + 1));
                }
            }
            if (slots.isEmpty()) return false;
            initial.putIfAbsent(visit.output(), ctx.counts.getOrDefault(visit.output(), 0L));
            var node = new RecipeNode(recipe.getId(), name(visit.output()), output.getCount(), inputs,
                    entry.modType().id(), entry.recipeTypeId());
            producers.put(node.output(), List.of(node));
            operations.put(node.recipeId(), new Operation(entry, visit.output(), output.getCount(), slots));
            return true;
        }

        void validate() {
            long start = System.nanoTime();
            try {
                if (candidate == null || candidate.steps().isEmpty()
                        || candidate.steps().size() + ctx.steps.size() > StepExecutor.maxSteps()
                        || initial.entrySet().stream().anyMatch(e -> ctx.counts.getOrDefault(e.getKey(), 0L).longValue() != e.getValue())) {
                    stack.resume(fallback);
                    return;
                }
                if (!CandidateEngine.frozenRouteStillAvailable(root, ctx)) {
                    stack.complete(done, false);
                    return;
                }
                ctx.beginUndo();
                transaction = true;
                stack.enter(() -> { if (transaction) ctx.rollback(); });
                stack.resume(this::merge);
            } finally {
                PerformanceMonitor.recordPlanningLatency(PlanningLatencyPhase.HYBRID_VALIDATE, System.nanoTime() - start);
            }
        }

        void merge() {
            long start = System.nanoTime();
            try {
                if (cursor < candidate.steps().size()) {
                    var step = candidate.steps().get(cursor++);
                    var op = operations.get(step.recipeId());
                    if (op != null && step.batches() > 0 && step.batches() <= Integer.MAX_VALUE
                            && apply(op, (int) step.batches())) {
                        stack.resume(this::merge);
                        return;
                    }
                } else if (matchesDelta()) {
                    long validationStart = System.nanoTime();
                    boolean current;
                    try {
                        current = checkStock.check(initial.keySet(), (key, previous, live) -> {
                            if (transaction) {
                                stack.leave();
                                transaction = false;
                            }
                            return ctx.reduceUnusedInitial(key, previous, live);
                        });
                    } finally {
                        PerformanceMonitor.recordPlanningLatency(PlanningLatencyPhase.HYBRID_VALIDATE,
                                System.nanoTime() - validationStart);
                    }
                    if (!current) {
                        if (transaction) stack.leave();
                        stack.resume(fallback);
                        return;
                    }
                    ctx.commitUndo();
                    transaction = false;
                    stack.leave();
                    accepted++;
                    stack.complete(done, true);
                    return;
                }
                stack.leave();
                stack.resume(fallback);
            } finally {
                PerformanceMonitor.recordPlanningLatency(PlanningLatencyPhase.HYBRID_MERGE, System.nanoTime() - start);
            }
        }

        boolean matchesDelta() {
            for (var entry : initial.entrySet()) {
                StackKey key = entry.getKey();
                long expected = candidate.remaining().getOrDefault(name(key), 0L);
                if (key.equals(target)) expected = Math.addExact(expected, Math.addExact(entry.getValue(),
                        (long) batches * operations.get(root.recipe().getId()).yield()));
                else expected = Math.addExact(expected, entry.getValue() - frozen.stock().getOrDefault(name(key), 0L));
                if (ctx.counts.getOrDefault(key, 0L) != expected) return false;
            }
            return true;
        }

        boolean apply(Operation op, int count) {
            var id = ctx.allocateNodeId();
            List<InputDemand> inputs = new ArrayList<>();
            for (Slot slot : op.slots()) {
                var port = new InputPortId(id, inputs.size());
                var supply = ctx.consumeMatchingDetailed(slot.predicate(), (long) count * slot.count());
                if (!supply.complete() || supply.slices().stream().anyMatch(slice ->
                        !slice.material().equals(new MaterialKey(slot.key().item(), slot.key().tag())))) return false;
                supply.slices().forEach(slice -> ctx.addAllocation(port, slice));
                inputs.add(new InputDemand(port, slot.predicate(), supply.supplied(), DemandRole.CONSUMED, slot.key().toStack()));
            }
            var entry = op.entry();
            boolean terminal = entry == root;
            var step = new ResolutionStep(entry.recipe().getId(), entry.modType(), entry.recipeTypeId(),
                    terminal ? alternatives : List.of(), terminal ? alternativeTypes : List.of(), false, count);
            var output = new OutputPortId(id, 0);
            long quantity = (long) count * op.yield();
            var material = new MaterialKey(op.output().item(), op.output().tag());
            ctx.addProduced(material.toStack(1), quantity, new MaterialSource.ProducerOutput(output));
            ctx.steps.add(step);
            ctx.addGraphNode(StepExecutor.toGraphNode(id, step, inputs,
                    List.of(OutputDeclaration.item(output, material, quantity, OutputKind.PRIMARY))));
            return true;
        }
    }
}
