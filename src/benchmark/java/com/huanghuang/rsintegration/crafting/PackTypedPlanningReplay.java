package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.GenericBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.PackReplayConfirmation;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnostic;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.crafting.plan.PackReplayPages;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.plan.PlanMaterialBill;
import com.huanghuang.rsintegration.crafting.plan.PlanOutcome;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeLayout;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeModel;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.crafting.CompoundIngredient;
import net.minecraftforge.common.crafting.StrictNBTIngredient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** 固定配方由替身 handler 提供；库存分配、候选选择和分片使用生产代码。 */
public final class PackTypedPlanningReplay extends BootstrapTest {
    private static final long SLICE_NANOS = 5_000_000L;
    private static final int ACTIVE_BUDGET_MS = Integer.parseInt(System.getProperty("rsi.replay.activeMs", "4000"));
    private static final int STEP_BUDGET = Integer.parseInt(System.getProperty("rsi.replay.maxSteps", "4096"));
    private static final boolean RESTOCK = Boolean.getBoolean("rsi.replay.restock");
    private static boolean initialized;
    private final Map<MaterialRef, ItemStack> aliases = new HashMap<>();
    private final Map<CraftingResolver.StackKey, MaterialRef> materialsByAlias = new HashMap<>();
    private final Map<ResourceLocation, List<MaterialRef>> aliasesById = new HashMap<>();
    private final Map<Item, List<RecipeIndex.Entry>> index = new HashMap<>();
    private final Map<ResourceLocation, ModType> routes = new HashMap<>();
    private final Map<ResourceLocation, Counts> counts = new HashMap<>();
    private int latestCallbacks;

    public static void run(String label, ImmutableRecipeGraph graph, RecipeNode target,
                           Map<MaterialRef, Long> stock, Map<ResourceLocation, ResourceLocation> forced) {
        run(label, graph, graph, target, stock, forced);
    }

    public static void run(String label, ImmutableRecipeGraph graph, ImmutableRecipeGraph aliasGraph,
                           RecipeNode target, Map<MaterialRef, Long> stock,
                           Map<ResourceLocation, ResourceLocation> forced) {
        if (!initialized) {
            if (ACTIVE_BUDGET_MS <= 0 || STEP_BUDGET < RSIntegrationConfig.MIN_CRAFTING_MAX_GRAPH_NODES
                    || STEP_BUDGET > RSIntegrationConfig.MAX_CRAFTING_MAX_GRAPH_NODES) {
                throw new IllegalArgumentException("回放时间或步数预算超出范围");
            }
            bootstrapMinecraft();
            DeepPlanningFixture.initializeMod();
            var config = CommentedConfig.inMemory();
            RSIntegrationConfig.SERVER_SPEC.correct(config);
            RSIntegrationConfig.SERVER_SPEC.setConfig(config);
            RSIntegrationConfig.CRAFTING_MAX_STEPS.set(STEP_BUDGET);
            initialized = true;
        }
        new PackTypedPlanningReplay().solve(label, graph, aliasGraph, target, stock, forced);
    }

    private void solve(String label, ImmutableRecipeGraph graph, ImmutableRecipeGraph aliasGraph, RecipeNode target,
                       Map<MaterialRef, Long> stock, Map<ResourceLocation, ResourceLocation> forced) {
        // 独立情景会创建新的配方实例，同 ID 的旧替身产物不能沿用。
        ModRecipeHandlers.clearResultCaches();
        Set<MaterialRef> known = new HashSet<>();
        for (RecipeNode recipe : aliasGraph.recipesById().values()) {
            known.addAll(recipe.guaranteedOutputs().keySet());
            recipe.inputs().forEach(input -> known.addAll(input.alternatives()));
        }
        Set<ResourceLocation> itemIds = new HashSet<>();
        known.forEach(material -> itemIds.add(material.itemId()));
        stock.keySet().stream().filter(material -> itemIds.contains(material.itemId())).forEach(known::add);
        List<Item> pool = BuiltInRegistries.ITEM.stream().filter(item -> item != Items.AIR).toList();
        List<ResourceLocation> ids = itemIds.stream().sorted(Comparator
                .comparing((ResourceLocation id) -> !forced.containsKey(id))
                .thenComparing(Comparator.naturalOrder())).toList();
        int reservedAliases = (int) ids.stream().filter(forced::containsKey).count();
        int reusableAliases = pool.size() - reservedAliases;
        for (int i = 0; i < ids.size(); i++) {
            ResourceLocation id = ids.get(i);
            // 强制路线按物品注册名匹配，因此它们的替身不能与其他材料共用。
            int aliasIndex = i < reservedAliases ? i : reservedAliases + (i - reservedAliases) % reusableAliases;
            Item item = pool.get(aliasIndex);
            int group = i < reservedAliases ? 0 : (i - reservedAliases) / reusableAliases;
            var variants = known.stream().filter(material -> material.itemId().equals(id))
                    .sorted(Comparator.comparing(MaterialRef::nbt)).toList();
            for (int variant = 0; variant < variants.size(); variant++) {
                MaterialRef material = variants.get(variant);
                ItemStack stack = new ItemStack(item);
                // 标签复用原版物品注册表，但不会让不同模组材料共享库存。
                stack.getOrCreateTag().putString("rsi_replay", group + ":" + variant);
                aliases.put(material, stack);
                materialsByAlias.put(CraftingResolver.StackKey.of(stack, true), material);
            }
            aliasesById.put(id, variants);
        }

        Map<Recipe<?>, ModRecipeHandlers.Claim> claims = new HashMap<>();
        Map<ResourceLocation, Recipe<?>> byId = new HashMap<>();
        graph.recipesById().values().stream().sorted(Comparator.comparing(RecipeNode::recipeId)).forEach(node -> {
            if (node == target) return;
            ModType route = routes.computeIfAbsent(node.recipeTypeId(), type -> {
                String id = "pack_replay_" + type.getNamespace() + '_' + type.getPath().replace('/', '_');
                ModType existing = ModType.findById(id);
                return existing != null ? existing : ModType.register(
                        id, new String[0], new String[0], new String[0], GenericBatchDelegate::new);
            });
            ItemStack output = aliases.get(node.output()).copyWithCount(Math.toIntExact(node.outputCount()));
            List<IngredientSpec> specs = node.inputs().stream().map(this::spec).toList();
            Recipe<?> recipe = new ReplayRecipe(node.recipeId(), output);
            byId.put(node.recipeId(), recipe);
            ModRecipeHandler handler = new ModRecipeHandler() {
                @Override public ModType modType() { return route; }
                @Override public boolean canHandle(Recipe<?> candidate) { return candidate == recipe; }
                @Override public boolean preferHandlerIngredients() { return true; }
                @Override public ItemStack getResultItem(Recipe<?> candidate, RegistryAccess access) {
                    record(node.recipeId()).outputs++;
                    return output;
                }
                @Override public List<IngredientSpec> getIngredients(Recipe<?> candidate) {
                    record(node.recipeId()).inputs++;
                    return specs;
                }
            };
            claims.put(recipe, new ModRecipeHandlers.Claim(handler, List.of(handler)));
            index.computeIfAbsent(output.getItem(), unused -> new ArrayList<>())
                    .add(new RecipeIndex.Entry(recipe, route, node.recipeTypeId(), true));
        });
        Level level = mock(Level.class, withSettings().stubOnly());
        ServerPlayer player = mock(ServerPlayer.class, withSettings().stubOnly());
        RecipeManager manager = mock(RecipeManager.class, withSettings().stubOnly());
        when(manager.byKey(any())).thenAnswer(call -> Optional.ofNullable(byId.get(call.getArgument(0))));
        when(level.getRecipeManager()).thenReturn(manager);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        Map<CraftingResolver.StackKey, Long> available = new LinkedHashMap<>();
        stock.forEach((material, amount) -> {
            ItemStack item = aliases.get(material);
            if (item != null) available.put(CraftingResolver.StackKey.of(item, true), amount);
        });
        Map<ResourceLocation, ResourceLocation> overrides = new HashMap<>();
        forced.forEach((material, recipe) -> {
            var variants = aliasesById.get(material);
            if (variants == null || !byId.containsKey(recipe)) {
                throw new IllegalStateException("回放缺少强制路线材料或配方：" + material + " -> " + recipe);
            }
            var alias = BuiltInRegistries.ITEM.getKey(aliases.get(variants.get(0)).getItem());
            overrides.put(alias, recipe);
        });
        System.out.printf("typed_%s materials=%d aliases=%d forced=%s%n", label, ids.size(), pool.size(), forced);
        var needed = target.inputs().stream().map(input -> ScaledIngredientSpec.of(spec(input), input.count())).toList();
        try (var recipes = mockStatic(RecipeIndex.class, withSettings().stubOnly());
             var handlers = mockStatic(ModRecipeHandlers.class, withSettings().stubOnly().defaultAnswer(call -> {
                 if (call.getMethod().getName().equals("claimFor")) {
                     var claim = claims.get(call.getArgument(0));
                     if (claim != null) return claim;
                 }
                 return call.callRealMethod();
             }));
             var bindings = mockStatic(AltarBindingRegistry.class, withSettings().stubOnly())) {
            recipes.when(() -> RecipeIndex.get(level)).thenReturn(index);
            recipes.when(() -> RecipeIndex.reusableCatalystRoutes(level)).thenReturn(Map.of());
            bindings.when(() -> AltarBindingRegistry.hasBindingForRecipe(eq(player), any(), any()))
                    .thenAnswer(call -> {
                        record(call.<Recipe<?>>getArgument(1).getId()).bindings++;
                        return true;
                    });
            for (int run = 0; run < 3; run++) measure(label, run, level, player, available, overrides, needed, target);
            if ("saved_disks".equals(label)) measureTimeout(level, player, available, overrides, needed, target);
        }
    }

    private void measureTimeout(Level level, ServerPlayer player,
                                Map<CraftingResolver.StackKey, Long> stock, Map<ResourceLocation, ResourceLocation> forced,
                                List<ScaledIngredientSpec> needed, RecipeNode target) {
        ItemStack output = aliases.get(target.output());
        var job = CraftingResolver.startTypedPreview(needed, stock, level, player, null, new ArrayList<>(),
                forced, true, 80, new CraftingResolver.ActiveRootRecipe(target.recipeId(), output),
                Map.of(), PlanningPreferences.empty());
        long longest = 0;
        try {
            boolean done;
            do {
                long start = System.nanoTime();
                try {
                    done = job.advance(SLICE_NANOS);
                } finally {
                    longest = Math.max(longest, System.nanoTime() - start);
                }
            } while (!done);
            System.out.println("display_timeout_saved_disks completed_within_budget=true");
        } catch (CraftingPlanningTimeoutException timeout) {
            verifyDraft("timeout_saved_disks", job.completedRootsGraph(), target, stock,
                    IntegrationDiagnosticCode.PLANNING_TIMEOUT, longest);
        }
    }

    private void verifyDraft(String label, CraftPlanGraph draft, RecipeNode target,
                             Map<CraftingResolver.StackKey, Long> stock, IntegrationDiagnosticCode diagnostic, long longest) {
        var graph = PlanGraphView.from(draft);
        var response = PlanResponse.incomplete(target.recipeId(), aliases.get(target.output()), 1, null,
                null, null, null, null, graph, stock, IntegrationDiagnostic.error(diagnostic));
        var transfer = PackReplayPages.roundTrip(response);
        var decoded = transfer.plan();
        if (decoded.targetResult().isEmpty() || decoded.success() || !decoded.executionBlocked()
                || decoded.outcome() != PlanOutcome.BUDGET_EXHAUSTED
                || !decoded.missing().isEmpty() || decoded.graph().roots().size() != graph.roots().size()
                || decoded.graph().nodes().size() != graph.nodes().size()
                || !decoded.materials().equals(response.materials())
                || decoded.materials().values().stream().anyMatch(row -> row.missingCount() != 0)) {
            throw new IllegalStateException("预算耗尽草稿未保留目标、已结算根、材料或执行限制");
        }
        var layout = new PlanTreeLayout();
        layout.rebuild(PlanTreeModel.from(decoded).root);
        System.out.printf("display_%s outcome=%s diagnostic=%s roots=%d nodes=%d boxes=%d materials=%d bytes=%d pages=%d maxSliceMs=%.1f%n",
                label, decoded.outcome(), diagnostic, graph.roots().size(), graph.nodes().size(), layout.boxes().size(),
                decoded.materials().size(), transfer.bytes(), transfer.pages(), longest / 1e6);
    }

    private Counts record(ResourceLocation id) {
        latestCallbacks++;
        return counts.computeIfAbsent(id, ignored -> new Counts());
    }

    private void measure(String label, int run, Level level, ServerPlayer player,
                         Map<CraftingResolver.StackKey, Long> stock, Map<ResourceLocation, ResourceLocation> forced,
                         List<ScaledIngredientSpec> needed, RecipeNode target) {
        measure(label, run, level, player, stock, forced, needed, target, ACTIVE_BUDGET_MS);
    }

    private void measure(String label, int run, Level level, ServerPlayer player,
                         Map<CraftingResolver.StackKey, Long> stock, Map<ResourceLocation, ResourceLocation> forced,
                         List<ScaledIngredientSpec> needed, RecipeNode target, int activeBudgetMs) {
        counts.clear();
        latestCallbacks = 0;
        int stepBudget = RSIntegrationConfig.CRAFTING_MAX_STEPS.get();
        List<String> missing = new ArrayList<>();
        var job = CraftingResolver.startTypedPreview(needed, stock, level, player, null, missing, forced,
                true, activeBudgetMs, new CraftingResolver.ActiveRootRecipe(target.recipeId(), aliases.get(target.output())),
                Map.of(), PlanningPreferences.empty());
        int slices = 0;
        int worstCallbacks = 0;
        long longest = 0L;
        long active = 0L;
        long graphTime = 0L;
        String status;
        int nodes = 0;
        int allocatedRoots = 0;
        int draftRoots = 0;
        int draftNodes = 0;
        List<String> unresolvedSlots = new ArrayList<>();
        Map<ResourceLocation, Long> unresolvedMaterials = new HashMap<>();
        Map<CraftingResolver.StackKey, Long> shortageSupply = new HashMap<>();
        String firstMissing = "-";
        int unresolvedEdges = 0;
        try {
            boolean done;
            do {
                int before = latestCallbacks;
                long start = System.nanoTime();
                try {
                    done = job.advance(SLICE_NANOS);
                } finally {
                    long elapsed = System.nanoTime() - start;
                    longest = Math.max(longest, elapsed);
                    active += elapsed;
                    worstCallbacks = Math.max(worstCallbacks, latestCallbacks - before);
                    slices++;
                }
            } while (!done);
            long start = System.nanoTime();
            var result = job.graph();
            graphTime = System.nanoTime() - start;
            nodes = result.nodes().size();
            for (int slot = 0, root = 0; slot < needed.size(); slot++) {
                if (needed.get(slot).isEmpty()) continue;
                long missingQuantity = result.rootDemands().get(root++).unresolvedQuantity();
                if (missingQuantity > 0L) {
                    unresolvedSlots.add(slot + ":" + target.inputs().get(slot).alternatives().get(0).itemId()
                            + "*" + missingQuantity);
                } else {
                    allocatedRoots++;
                }
            }
            unresolvedEdges = result.unresolvedDemands().size();
            for (var demand : result.unresolvedDemands()) {
                var key = CraftingResolver.StackKey.of(demand.displayHint(), true);
                MaterialRef material = materialsByAlias.get(key);
                if (material != null) {
                    ResourceLocation id = material.itemId();
                    if (firstMissing.equals("-")) firstMissing = id + "*" + demand.quantity();
                    unresolvedMaterials.merge(id, demand.quantity(), SaturatingLongMath::add);
                    if (IngredientMatcher.test(demand.ingredient(), key)) {
                        shortageSupply.merge(key, demand.quantity(), SaturatingLongMath::add);
                    }
                }
            }
            if (!unresolvedSlots.isEmpty()) firstMissing = unresolvedSlots.get(0);
            status = unresolvedSlots.isEmpty() && unresolvedEdges == 0 ? "SUCCESS" : "PARTIAL";
            if (run == 2) {
                var view = PlanGraphView.from(result);
                ItemStack output = aliases.get(target.output());
                var terminal = new PlanStep(target.recipeId(), output, 1,
                        target.inputs().stream().map(input -> aliases.get(input.alternatives().get(0))).toList());
                boolean complete = status.equals("SUCCESS");
                var bill = PlanMaterialBill.summarize(Map.of(), Map.of(), Map.of(), stock, output,
                        List.of(terminal), 1, view, !complete);
                if (complete && !bill.feasible()) {
                    throw new IllegalStateException("没有未分配需求的回放仍超过初始库存");
                }
                var response = new PlanResponse(complete, "", output, List.of(terminal),
                        bill.materials(), missing, target.recipeId().toString(), null, null, 0, 0, 0,
                        List.of(), 1, null, null, null, 0, false, false, false,
                        null, Set.of(), Map.of(), null, view);
                var transfer = PackReplayPages.roundTrip(response);
                var decoded = transfer.plan();
                if (decoded.graph().nodes().size() != nodes
                        || decoded.graph().unresolved().size() != unresolvedEdges
                        || decoded.graph().roots().stream().filter(root -> root.unresolvedQuantity() > 0).count()
                                != unresolvedSlots.size()
                        || !decoded.materials().equals(response.materials())
                        || decoded.outcome().ready() != complete) {
                    throw new IllegalStateException("回包后计划节点或缺项不一致");
                }
                var model = PlanTreeModel.from(decoded);
                var layout = new PlanTreeLayout();
                layout.rebuild(model.root);
                long missingBoxes = layout.boxes().stream().filter(box -> box.node().unresolved > 0).count();
                if (layout.boxes().size() < 2 || !complete && missingBoxes == 0) {
                    throw new IllegalStateException("回放计划未生成可展示的节点");
                }
                System.out.printf("display_%s outcome=%s bytes=%d pages=%d boxes=%d missingBoxes=%d truncated=%s%n",
                        label, decoded.outcome(), transfer.bytes(), transfer.pages(), layout.boxes().size(),
                        missingBoxes, model.truncated());
                if (complete) {
                    PackReplayConfirmation.verify(level.getRecipeManager(), response, decoded,
                            transfer.requestId(), result, stock, forced);
                    System.out.printf("confirm_%s request=%d sameGraph=true rejectsMissingStock=true rejectsDuplicate=true%n",
                            label, transfer.requestId());
                }
            }
        } catch (CraftingPlanningTimeoutException | CraftingPlanningLimitException exhausted) {
            boolean timedOut = exhausted instanceof CraftingPlanningTimeoutException;
            status = timedOut
                    ? "TIMEOUT " + job.timeoutSummary() : "LIMIT " + exhausted.getMessage() + " " + job.timeoutSummary();
            var draft = job.completedRootsGraph();
            draftRoots = draft.rootDemands().size();
            draftNodes = draft.nodes().size();
            if (run == 2) verifyDraft(label, draft, target, stock, timedOut
                    ? IntegrationDiagnosticCode.PLANNING_TIMEOUT : IntegrationDiagnosticCode.COMPLEXITY_LIMIT, longest);
        }
        System.out.printf("typed_%s run=%d stepBudget=%d activeBudgetMs=%d status=%s nodes=%d allocatedRoots=%d "
                        + "draftRoots=%d draftNodes=%d unresolvedRoots=%d unresolvedEdges=%d slices=%d activeMs=%.1f maxSliceMs=%.1f graphMs=%.1f "
                        + "inputs=%d outputs=%d bindings=%d maxCallbacks=%d hottest=%s firstMissing=%s unresolvedMaterials=%s unresolvedSlots=%s%n",
                label, run, stepBudget, activeBudgetMs, status, nodes, allocatedRoots, draftRoots, draftNodes,
                unresolvedSlots.size(), unresolvedEdges, slices,
                active / 1e6, longest / 1e6, graphTime / 1e6,
                counts.values().stream().mapToInt(c -> c.inputs).sum(),
                counts.values().stream().mapToInt(c -> c.outputs).sum(),
                counts.values().stream().mapToInt(c -> c.bindings).sum(), worstCallbacks,
                counts.entrySet().stream().sorted(Map.Entry.<ResourceLocation, Counts>comparingByValue(
                                Comparator.comparingInt(Counts::total)).reversed())
                        .limit(6).map(entry -> entry.getKey() + ":" + entry.getValue().total()).toList(),
                firstMissing,
                unresolvedMaterials.entrySet().stream()
                        .sorted(Map.Entry.<ResourceLocation, Long>comparingByValue().reversed()
                                .thenComparing(Map.Entry.comparingByKey()))
                        .limit(8).map(entry -> entry.getKey() + "*" + entry.getValue()).toList(),
                unresolvedSlots);
        if (RESTOCK && run == 2 && "synthetic_farm_and_spirits".equals(label)
                && "PARTIAL".equals(status) && unresolvedSlots.isEmpty() && !shortageSupply.isEmpty()) {
            // 只补这一轮已观测到的材料数量，保留精确变体，再从头求解一次。
            var restocked = new LinkedHashMap<>(stock);
            shortageSupply.forEach((key, quantity) -> restocked.merge(key, quantity, SaturatingLongMath::add));
            long supplied = shortageSupply.values().stream().reduce(0L, SaturatingLongMath::add);
            System.out.printf("restock_%s variants=%d quantity=%d%n", label, shortageSupply.size(), supplied);
            shortageSupply.entrySet().stream().map(entry -> {
                MaterialRef material = materialsByAlias.get(entry.getKey());
                return material.itemId() + (material.nbt().isEmpty() ? "" : material.nbt()) + " * " + entry.getValue();
            }).sorted().forEach(line -> System.out.println("restock_material " + line));
            measure(label + "_restock", 2, level, player, restocked, forced, needed, target);
            if (stepBudget != 4_096 || activeBudgetMs != 4_000) {
                RSIntegrationConfig.CRAFTING_MAX_STEPS.set(4_096);
                try {
                    measure(label + "_restock_default", 2, level, player, restocked, forced, needed, target, 4_000);
                } finally {
                    RSIntegrationConfig.CRAFTING_MAX_STEPS.set(stepBudget);
                }
            }
        }
    }

    private IngredientSpec spec(IngredientRef input) {
        Ingredient[] variants = input.alternatives().stream()
                .flatMap(material -> input.nbtMatchMode() == NbtMatchMode.ANY
                        ? aliasesById.get(material.itemId()).stream() : Stream.of(material))
                .distinct().map(material -> StrictNBTIngredient.of(aliases.get(material)))
                .toArray(Ingredient[]::new);
        return new IngredientSpec(CompoundIngredient.of(variants), Math.toIntExact(input.count()),
                input.role() == DemandRole.CATALYST ? InputConsumption.catalyst() : InputConsumption.consumed());
    }

    private record ReplayRecipe(ResourceLocation id, ItemStack output) implements Recipe<Container> {
        @Override public ResourceLocation getId() { return id; }
        @Override public ItemStack getResultItem(RegistryAccess access) { return output; }
        @Override public boolean matches(Container inventory, Level level) { return false; }
        @Override public ItemStack assemble(Container inventory, RegistryAccess access) { return output.copy(); }
        @Override public boolean canCraftInDimensions(int width, int height) { return false; }
        @Override public RecipeSerializer<?> getSerializer() { return RecipeSerializer.SHAPELESS_RECIPE; }
        @Override public RecipeType<?> getType() { return RecipeType.CRAFTING; }
    }

    private static final class Counts {
        int inputs;
        int outputs;
        int bindings;
        int total() { return inputs + outputs + bindings; }
    }
}
