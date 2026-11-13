package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.api.IntegrationResourceKey.ResourceKind;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.ScaledIngredientSpec;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanValidator;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.NodeOutputAccumulator;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.plan.RecipeChoice;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner.FlowEvent;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner.MaterialFlow;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner.MaterialUse;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner.PlannedStep;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner.Production;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PurePlanGraphAdapterTest extends BootstrapTest {
    @Test
    void craftedAuxiliaryAndRouteSpecificInputsShareOneExecutionProducer() {
        RecipeNode fuel = recipe("fuel", new ItemStack(Items.COAL), 16, demand(Items.OAK_LOG, 1));
        RecipeNode upstream = recipe("upstream", new ItemStack(Items.OAK_PLANKS), 2, demand(Items.IRON_INGOT, 1));
        RecipeNode first = recipe("first", new ItemStack(Items.DIAMOND), 1,
                demand(Items.COAL, 1), demand(Items.OAK_PLANKS, 1));
        RecipeNode second = recipe("second", new ItemStack(Items.DIAMOND), 1,
                demand(Items.COAL, 1), demand(Items.EMERALD, 1));
        RecipeNode left = recipe("left", new ItemStack(Items.STICK), 1, demand(Items.DIAMOND, 1));
        RecipeNode right = recipe("right", new ItemStack(Items.PAPER), 1, demand(Items.DIAMOND, 1));
        var stock = Map.of(key(Items.OAK_LOG), 1L, key(Items.IRON_INGOT), 1L);
        var result = plan(graph(fuel, upstream, first, second, left, right), stock,
                List.of(demand(Items.STICK, 1), demand(Items.PAPER, 1)));
        assertEquals(5, result.steps().size());
        assertEquals(Map.of(material(Items.COAL), 14L), result.remaining());
        var replayed = PurePlanGraphAdapter.toInputGraph(result, stock,
                List.of(spec(Items.STICK, 1), spec(Items.PAPER, 1)));
        CraftPlanValidator.validate(replayed);
        assertEquals(5, replayed.nodes().size());
        var producer = replayed.nodes().stream().filter(node -> node.recipeId().equals(fuel.recipeId())).findFirst().orElseThrow();
        assertEquals(16L, producer.outputs().get(0).quantity());
        assertEquals(2L, replayed.allocations().stream().filter(allocation -> allocation.source().equals(
                        new MaterialSource.ProducerOutput(producer.outputs().get(0).id())))
                .mapToLong(allocation -> allocation.quantity()).sum());
    }

    @Test
    void differentYieldCommonInputLowerBoundReplaysAdditionalActualProduction() {
        RecipeNode upstream = recipe("upstream", new ItemStack(Items.OAK_PLANKS), 2, demand(Items.OAK_LOG, 1));
        RecipeNode first = recipe("a_route", new ItemStack(Items.DIAMOND), 2,
                demand(Items.OAK_PLANKS, 1), demand(Items.IRON_INGOT, 1));
        RecipeNode second = recipe("b_route", new ItemStack(Items.DIAMOND), 3,
                demand(Items.OAK_PLANKS, 1), demand(Items.GOLD_INGOT, 1));
        RecipeNode left = recipe("left", new ItemStack(Items.STICK), 1, demand(Items.DIAMOND, 3));
        RecipeNode right = recipe("right", new ItemStack(Items.PAPER), 1, demand(Items.DIAMOND, 2));
        for (var stock : List.of(Map.of(key(Items.OAK_LOG), 2L, key(Items.IRON_INGOT), 3L),
                Map.of(key(Items.OAK_LOG), 1L, key(Items.IRON_INGOT), 1L, key(Items.GOLD_INGOT), 1L))) {
            var result = plan(graph(upstream, first, second, left, right), stock,
                    List.of(demand(Items.STICK, 1), demand(Items.PAPER, 1)));
            var replayed = PurePlanGraphAdapter.toInputGraph(result, stock,
                    List.of(spec(Items.STICK, 1), spec(Items.PAPER, 1)));
            CraftPlanValidator.validate(replayed);
            assertEquals(result.steps().size(), replayed.nodes().size());
            long expectedInputs = stock.get(key(Items.IRON_INGOT)) == 3L ? 3L : 2L;
            assertEquals(expectedInputs, replayed.allocations().stream()
                    .filter(allocation -> allocation.material().item() == Items.OAK_PLANKS)
                    .mapToLong(allocation -> allocation.quantity()).sum());
            assertEquals(5L, replayed.allocations().stream()
                    .filter(allocation -> allocation.material().item() == Items.DIAMOND)
                    .mapToLong(allocation -> allocation.quantity()).sum());
            assertEquals(expectedInputs == 3L ? Map.of(material(Items.OAK_PLANKS), 1L, material(Items.DIAMOND), 1L)
                    : Map.of(), result.remaining());
        }
    }

    @Test
    void splitRoutesUseOneSharedUpstreamAndKeepActualExecutionPorts() {
        RecipeNode upstream = recipe("upstream", new ItemStack(Items.OAK_PLANKS), 2,
                demand(Items.OAK_LOG, 1));
        RecipeNode first = recipe("a_route", new ItemStack(Items.DIAMOND), 1,
                demand(Items.OAK_PLANKS, 1), demand(Items.IRON_INGOT, 1));
        RecipeNode second = recipe("b_route", new ItemStack(Items.DIAMOND), 1,
                demand(Items.OAK_PLANKS, 1), demand(Items.GOLD_INGOT, 1));
        RecipeNode left = recipe("left", new ItemStack(Items.STICK), 1, demand(Items.DIAMOND, 1));
        RecipeNode right = recipe("right", new ItemStack(Items.PAPER), 1, demand(Items.DIAMOND, 1));
        var stock = Map.of(key(Items.OAK_LOG), 5L, key(Items.IRON_INGOT), 6L, key(Items.GOLD_INGOT), 4L);
        var result = plan(graph(upstream, first, second, left, right), stock,
                List.of(demand(Items.STICK, 5), demand(Items.PAPER, 5)));
        assertEquals(5, result.steps().size());
        assertEquals(5L, result.steps().get(0).batches());
        assertTrue(result.remaining().isEmpty());
        var replayed = PurePlanGraphAdapter.toInputGraph(result, stock,
                List.of(spec(Items.STICK, 5), spec(Items.PAPER, 5)));
        CraftPlanValidator.validate(replayed);
        assertEquals(5, replayed.nodes().size());
        assertEquals(10L, replayed.nodes().get(0).outputs().get(0).quantity());
        long sharedAllocated = replayed.allocations().stream()
                .filter(allocation -> allocation.source().equals(new MaterialSource.ProducerOutput(
                        replayed.nodes().get(0).outputs().get(0).id())))
                .mapToLong(allocation -> allocation.quantity()).sum();
        assertEquals(10L, sharedAllocated);
        assertEquals(6L, result.steps().stream().filter(step -> step.recipeId().equals(first.recipeId()))
                .mapToLong(PlannedStep::batches).sum());
        assertEquals(4L, result.steps().stream().filter(step -> step.recipeId().equals(second.recipeId()))
                .mapToLong(PlannedStep::batches).sum());
    }

    @Test
    void manyProducerLotsKeepFifoSourcesAcrossPartialAndLaterConsumption() {
        int producers = 2_000;
        RecipeNode producer = joint("many_lots", Items.DIAMOND,
                Map.of(material(Items.DIAMOND), 3L, material(Items.EMERALD), 1L));
        List<PlannedStep> steps = new ArrayList<>();
        List<FlowEvent> events = new ArrayList<>();
        List<IngredientRef> roots = new ArrayList<>();
        // 先创建足够多的存活批次，再重复跨批次取料；每个根都必须保留准确的 FIFO 来源。
        for (int index = 0; index < producers; index++) {
            steps.add(new PlannedStep(producer.recipeId(), 1L));
            events.add(new Production(index, producer, 1L, producer.output(), null));
        }
        for (int port = 0; port < producers * 3 / 2; port++) {
            IngredientRef root = demand(Items.DIAMOND, 2);
            roots.add(root);
            events.add(new MaterialUse(-1, port, root, Map.of(material(Items.DIAMOND), 2L)));
        }
        // 消耗旧批次后再次发布，确认取料游标不会漏掉后来产生的副产物。
        steps.add(new PlannedStep(producer.recipeId(), 1L));
        events.add(new Production(producers, producer, 1L, producer.output(), null));
        IngredientRef emerald = demand(Items.EMERALD, producers + 1L);
        events.add(new MaterialUse(-1, roots.size(), emerald, Map.of(material(Items.EMERALD), producers + 1L)));
        roots.add(emerald);
        var result = new PureRecipePlanner.Result(PureRecipePlanner.Feasibility.FEASIBLE, steps, List.of(),
                Map.of(material(Items.DIAMOND), 4L), List.of(), PureRecipePlanner.Status.SUCCESS,
                0, 0, 0, PureRecipePlanner.FailureReason.NONE, new MaterialFlow(roots, events));
        var stock = Map.of(key(Items.DIAMOND), 1L);
        var replayed = PurePlanGraphAdapter.toOutputGraph(result, stock);
        CraftPlanValidator.validate(replayed);
        assertEquals(producers + 1, replayed.nodes().size());
        long offset = 0L;
        for (int port = 0; port < producers * 3 / 2; port++) {
            long total = 0L;
            for (var allocation : replayed.rootDemands().get(port).allocations()) {
                if (offset == 0L) {
                    assertInstanceOf(MaterialSource.InitialPool.class, allocation.source());
                    assertEquals(1L, allocation.quantity());
                } else {
                    int node = (int) ((offset - 1L) / 3L);
                    assertEquals(new MaterialSource.ProducerOutput(replayed.nodes().get(node).outputs().get(0).id()),
                            allocation.source());
                    assertTrue(allocation.quantity() <= 3L - (offset - 1L) % 3L);
                }
                offset += allocation.quantity();
                total += allocation.quantity();
            }
            assertEquals(2L, total);
        }
        assertEquals(producers + 1L, replayed.rootDemands().get(roots.size() - 1).allocations().stream()
                .mapToLong(allocation -> allocation.quantity()).sum());
    }

    @Test
    void realIngredientProjectionAggregatesAnyAndPreservesNbtAllocationsInExecutionGraph() {
        long amount = 50_000_000L;
        IngredientRef log = ImmutableRecipeGraphProjector.projectIngredient(spec(Items.OAK_LOG, 1));
        IngredientRef planks = ImmutableRecipeGraphProjector.projectIngredient(spec(Items.OAK_PLANKS, 1));
        assertEquals(NbtMatchMode.ANY, log.nbtMatchMode());
        RecipeNode shared = recipe("any_planks", new ItemStack(Items.OAK_PLANKS), 1, log);
        RecipeNode left = recipe("any_stick", new ItemStack(Items.STICK), 1, planks);
        RecipeNode right = recipe("any_paper", new ItemStack(Items.PAPER), 1, planks);
        ItemStack tagged = new ItemStack(Items.OAK_LOG);
        tagged.getOrCreateTag().putString("origin", "pipeline");
        MaterialKey key = MaterialKey.of(tagged);
        var stock = Map.of(new StackKey(key.item(), key.tag()), amount, key(Items.OAK_LOG), amount);
        var roots = List.of(ImmutableRecipeGraphProjector.projectIngredient(spec(Items.STICK, amount)),
                ImmutableRecipeGraphProjector.projectIngredient(spec(Items.PAPER, amount)));
        var result = plan(graph(shared, left, right), stock, roots);
        assertEquals(3, result.steps().size());
        assertEquals(amount * 2, result.steps().get(0).batches());
        var replayed = PurePlanGraphAdapter.toInputGraph(result, stock,
                List.of(spec(Items.STICK, amount), spec(Items.PAPER, amount)));
        assertDoesNotThrow(() -> CraftPlanValidator.validate(replayed));
        assertEquals(3, replayed.nodes().size());
        assertEquals(amount * 2, replayed.nodes().get(0).inputs().get(0).quantity());
    }

    @Test
    void aggregatedSharedProducerReplaysLongInputQuantitiesIntoExecutionGraph() {
        long amount = 50_000_000L;
        RecipeNode shared = recipe("bulk_planks", new ItemStack(Items.OAK_PLANKS), 1,
                demand(Items.OAK_LOG, 64));
        RecipeNode left = recipe("bulk_sticks", new ItemStack(Items.STICK), 1,
                demand(Items.OAK_PLANKS, 1));
        RecipeNode right = recipe("bulk_paper", new ItemStack(Items.PAPER), 1,
                demand(Items.OAK_PLANKS, 1));
        var stock = Map.of(key(Items.OAK_LOG), amount * 128L);
        var result = plan(graph(shared, left, right), stock,
                List.of(demand(Items.STICK, amount), demand(Items.PAPER, amount)));
        assertEquals(3, result.steps().size());
        assertEquals(amount * 2L, result.steps().get(0).batches());

        var replayed = PurePlanGraphAdapter.toInputGraph(result, stock,
                List.of(spec(Items.STICK, amount), spec(Items.PAPER, amount)));
        assertDoesNotThrow(() -> CraftPlanValidator.validate(replayed));
        assertEquals(3, replayed.nodes().size());
        assertEquals(amount * 128L, replayed.nodes().get(0).inputs().get(0).quantity());
        assertEquals(amount * 2L, replayed.nodes().get(0).outputs().get(0).quantity());
    }

    @Test
    void replayKeepsThePreviewedFluidRecipeEvenWhenBucketsBecomeAvailable() {
        ItemStack fluid = InkFluidSupport.token(InkFluidTestFixtures.tokenItem(),
                new FluidStack(Fluids.WATER, 1));
        MaterialRef water = material(fluid);
        RecipeNode melt = new RecipeNode(id("melting"), water, 90,
                List.of(demand(Items.ICE, 1)), "embers_melter", id("melting"));
        RecipeNode drain = recipe("drain_bucket", fluid, 1000, demand(Items.WATER_BUCKET, 1));
        Map<StackKey, Long> previewStock = Map.of(key(Items.ICE), 21L);
        var result = plan(graph(melt, drain), previewStock,
                List.of(new IngredientRef(List.of(water), 1890)));
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(melt.recipeId(), 21)), result.steps());
        Map<StackKey, Long> currentStock = new LinkedHashMap<>(previewStock);
        currentStock.put(key(Items.WATER_BUCKET), 2L);

        CraftPlanGraph replayed = PurePlanGraphAdapter.toInputGraph(result, currentStock,
                List.of(new ScaledIngredientSpec(StrictNBTIngredient.of(fluid), 1890,
                        InputConsumption.consumed())));

        assertEquals(1, replayed.nodes().size());
        var node = replayed.nodes().get(0);
        assertEquals(melt.recipeId(), node.recipeId());
        assertEquals("embers_melter", node.modTypeId());
        assertEquals(21, node.executions());
        assertEquals(1890, node.outputs().get(0).quantity());
        assertEquals(ResourceKind.FLUID, node.outputs().get(0).resource().kind());
        assertEquals(21, replayed.allocations().get(0).quantity());
        var accumulator = new NodeOutputAccumulator(node.outputs());
        assertEquals(1890, accumulator.add(List.of(fluid.copyWithCount(1890)))
                .get(0).stack().getCount());
        assertTrue(accumulator.isComplete());
    }

    @Test
    void failedBranchDoesNotLeaveProductionOrMaterialEventsInTheGraph() {
        RecipeNode rejected = recipe("uses_reserved_iron", new ItemStack(Items.DIAMOND), 1,
                demand(Items.IRON_INGOT, 1));
        RecipeNode selected = recipe("uses_gold", new ItemStack(Items.DIAMOND), 1,
                demand(Items.GOLD_INGOT, 1));
        var stock = Map.of(key(Items.IRON_INGOT), 1L, key(Items.GOLD_INGOT), 1L);
        PlanningPreferences preferred = new PlanningPreferences(
                Map.of("minecraft:diamond", new RecipeChoice.Normal(rejected.recipeId())),
                Map.of(), Set.of());
        var result = PureRecipePlanner.resolveConfigured(graph(rejected, selected),
                ImmutableRecipeGraphProjector.projectAvailability(stock),
                List.of(demand(Items.DIAMOND, 1), demand(Items.IRON_INGOT, 1)),
                20, 20000, 8192, Long.MAX_VALUE, Map.of(), preferred);

        assertTrue(result.feasible(), result.toString());
        assertTrue(result.backtracks() > 0);
        var replayed = PurePlanGraphAdapter.toInputGraph(result, stock,
                List.of(spec(Items.DIAMOND, 1), spec(Items.IRON_INGOT, 1)));

        assertEquals(List.of(selected.recipeId()),
                replayed.nodes().stream().map(node -> node.recipeId()).toList());
        assertEquals(Items.GOLD_INGOT, replayed.allocations().get(0).material().item());
        assertEquals(Items.IRON_INGOT, replayed.rootDemands().get(1).allocations().get(0).material().item());
    }

    @Test
    void mergedRootsRecoverDuplicateTerminalSlotsInTheirOriginalOrder() {
        var stock = Map.of(key(Items.DIAMOND), 5L, key(Items.IRON_INGOT), 1L);
        var result = plan(graph(), stock, List.of(demand(Items.DIAMOND, 2),
                demand(Items.IRON_INGOT, 1), demand(Items.DIAMOND, 3)));
        var replayed = PurePlanGraphAdapter.toInputGraph(result, stock,
                List.of(spec(Items.DIAMOND, 2), spec(Items.IRON_INGOT, 1), spec(Items.DIAMOND, 3)));

        assertTrue(replayed.nodes().isEmpty());
        assertEquals(List.of(2L, 1L, 3L),
                replayed.rootDemands().stream().map(root -> root.quantity()).toList());
        assertEquals(List.of(Items.DIAMOND, Items.IRON_INGOT, Items.DIAMOND),
                replayed.rootDemands().stream().map(root -> root.displayHint().getItem()).toList());
    }

    @Test
    void restoringBroadTerminalSlotsDoesNotStealTheLaterExactMaterial() {
        IngredientRef broad = new IngredientRef(List.of(material(Items.IRON_INGOT),
                material(Items.GOLD_INGOT)), 1);
        var stock = Map.of(key(Items.IRON_INGOT), 1L, key(Items.GOLD_INGOT), 1L);
        var result = plan(graph(), stock, List.of(broad, demand(Items.IRON_INGOT, 1)));
        var replayed = PurePlanGraphAdapter.toInputGraph(result, stock,
                List.of(new ScaledIngredientSpec(Ingredient.of(Items.IRON_INGOT, Items.GOLD_INGOT), 1,
                        InputConsumption.consumed()),
                        spec(Items.IRON_INGOT, 1)));

        assertEquals(Items.GOLD_INGOT, replayed.rootDemands().get(0).displayHint().getItem());
        assertEquals(Items.IRON_INGOT, replayed.rootDemands().get(1).displayHint().getItem());
    }

    @Test
    void nestedRecipesBorrowTheSameCatalystAfterTheirDependenciesComplete() {
        IngredientRef tool = catalyst(Items.SHEARS);
        RecipeNode inner = recipe("inner", new ItemStack(Items.DIAMOND), 1,
                tool, demand(Items.IRON_INGOT, 1));
        RecipeNode outer = recipe("outer", new ItemStack(Items.EMERALD), 1,
                tool, demand(Items.DIAMOND, 1));
        var stock = Map.of(key(Items.SHEARS), 1L, key(Items.IRON_INGOT), 1L);
        var result = plan(graph(inner, outer), stock, List.of(demand(Items.EMERALD, 1)));
        var replayed = PurePlanGraphAdapter.toInputGraph(result, stock,
                List.of(spec(Items.EMERALD, 1)));

        assertEquals(2, replayed.nodes().size());
        var innerReturn = replayed.nodes().get(0).outputs().stream()
                .filter(output -> output.kind() == OutputKind.CATALYST_RETURN).findFirst().orElseThrow();
        assertTrue(replayed.allocations().stream().anyMatch(allocation ->
                allocation.source().equals(new MaterialSource.ProducerOutput(innerReturn.id()))));
        assertEquals(1, innerReturn.quantity());
        assertNotNull(innerReturn.sourceInput());
    }

    @Test
    void multipleCatalystPortsKeepTheirReturnOrigins() {
        RecipeNode producer = recipe("tools", new ItemStack(Items.DIAMOND), 1,
                catalyst(Items.SHEARS), catalyst(Items.FISHING_ROD), demand(Items.IRON_INGOT, 1));
        var stock = Map.of(key(Items.SHEARS), 1L, key(Items.FISHING_ROD), 1L,
                key(Items.IRON_INGOT), 4L);
        var result = plan(graph(producer), stock, List.of(demand(Items.DIAMOND, 4)));
        var replayed = PurePlanGraphAdapter.toInputGraph(result, stock,
                List.of(spec(Items.DIAMOND, 4)));
        var node = replayed.nodes().get(0);
        var returns = node.outputs().stream()
                .filter(output -> output.kind() == OutputKind.CATALYST_RETURN).toList();

        assertEquals(4, node.executions());
        assertEquals(2, returns.size());
        assertEquals(List.of(1L, 1L), returns.stream().map(output -> output.quantity()).toList());
        assertNotEquals(returns.get(0).sourceInput(), returns.get(1).sourceInput());
    }

    @Test
    void hugeExecutionCountsSplitWithoutLosingInputsOrProducedRoots() {
        long count = (long) Integer.MAX_VALUE + 10L;
        RecipeNode producer = recipe("large_batch", new ItemStack(Items.DIAMOND), 1,
                demand(Items.IRON_INGOT, 1));
        var stock = Map.of(key(Items.IRON_INGOT), count);
        var result = plan(graph(producer), stock, List.of(demand(Items.DIAMOND, count)));
        var replayed = PurePlanGraphAdapter.toInputGraph(result, stock,
                List.of(spec(Items.DIAMOND, count)));

        assertEquals(List.of(Integer.MAX_VALUE, 10),
                replayed.nodes().stream().map(node -> node.executions()).toList());
        assertEquals(count, replayed.allocations().stream().mapToLong(a -> a.quantity()).sum());
        assertEquals(count, replayed.rootDemands().get(0).allocations().stream()
                .mapToLong(a -> a.quantity()).sum());
    }

    @Test
    void replayKeepsTheFullNbtOfTheSelectedInventoryTool() {
        ItemStack ordinary = new ItemStack(Items.SHEARS);
        ItemStack selected = ordinary.copy();
        selected.getOrCreateTag().putString("owner", "chosen");
        var stock = Map.of(new StackKey(selected.getItem(), selected.getTag().toString()), 1L,
                key(Items.SHEARS), 1L);
        MaterialRef precise = material(selected);
        var result = plan(graph(), stock,
                List.of(new IngredientRef(List.of(precise), 1, NbtMatchMode.EXACT)));
        var replayed = PurePlanGraphAdapter.toInputGraph(result, stock,
                List.of(new ScaledIngredientSpec(StrictNBTIngredient.of(selected), 1,
                        InputConsumption.consumed())));

        assertEquals("chosen", replayed.rootDemands().get(0).displayHint().getTag().getString("owner"));
        assertFalse(replayed.rootDemands().get(0).ingredient().test(ordinary));
    }

    @Test
    void selfAmplificationAlreadyContainsItsTerminalProduction() {
        RecipeNode target = recipe("grow_slime", new ItemStack(Items.SLIME_BALL), 2,
                demand(Items.SLIME_BALL, 1), demand(Items.DIRT, 1));
        var stock = Map.of(key(Items.SLIME_BALL), 1L, key(Items.DIRT), 10L);
        var result = AsyncPurePlanningService.resolveInScope(graph(target),
                ImmutableRecipeGraphProjector.projectAvailability(stock), target, 5,
                30, 20000, 8192, Long.MAX_VALUE, Map.of(), PlanningPreferences.empty());
        assertTrue(result.feasible(), result.toString());

        assertTrue(PurePlanGraphAdapter.targetsOutput(result, target));
        var replayed = PurePlanGraphAdapter.toOutputGraph(result, stock);
        assertEquals(result.steps().stream().mapToLong(PureRecipePlanner.PlannedStep::batches).sum(),
                replayed.nodes().stream().mapToLong(node -> node.executions()).sum());
        assertEquals(6, replayed.rootDemands().get(0).quantity());

        PlanCache cache = new PlanCache(1000);
        UUID player = UUID.randomUUID();
        var key = new PlanCache.Key(player, target.recipeId(), Map.of(), 5, "");
        var snapshot = new PlanningSnapshot(player, 1, key.recipeRevision(), target.recipeId(),
                stock, Map.of(), graph(target), "", "", false);
        var response = new PlanResponse(true, "", new ItemStack(Items.SLIME_BALL),
                List.of(), Map.of(), List.of(), "", null, null, 0, 0, 0, List.of(), 5);
        cache.put(key, response, snapshot, result, replayed, true, 1);
        assertTrue(cache.peekForExecution(key).terminalEmbedded());
    }

    @Test
    void sameItemInputAndOutputDoNotMakeAnOrdinaryTerminalEmbedded() {
        RecipeNode target = recipe("repair", new ItemStack(Items.SHEARS), 1,
                demand(Items.SHEARS, 1));
        var stock = Map.of(key(Items.SHEARS), 1L);
        var result = AsyncPurePlanningService.resolveInScope(graph(target),
                ImmutableRecipeGraphProjector.projectAvailability(stock), target, 1,
                30, 20000, 8192, Long.MAX_VALUE, Map.of(), PlanningPreferences.empty());

        assertTrue(result.feasible());
        assertFalse(PurePlanGraphAdapter.targetsOutput(result, target));
    }

    @Test
    void untargetedCoProductDeferralKeepsRootPortsAndBatchSources() {
        RecipeNode producer = joint("untargeted", Items.DIAMOND,
                Map.of(material(Items.DIAMOND), 1L, material(Items.EMERALD), 2L), demand(Items.COAL, 1));
        for (long count : List.of(1L, 7L, (long) Integer.MAX_VALUE + 10L)) {
            var stock = Map.of(key(Items.COAL), count);
            var result = plan(graph(producer), stock,
                    List.of(demand(Items.EMERALD, count), demand(Items.DIAMOND, count)));
            var replayed = PurePlanGraphAdapter.toInputGraph(result, stock,
                    List.of(spec(Items.EMERALD, count), spec(Items.DIAMOND, count)));
            assertEquals(count, replayed.nodes().stream().mapToLong(node -> node.executions()).sum());
            assertEquals(Map.of(material(Items.EMERALD), count), result.remaining());
            for (int port = 0; port < 2; port++) {
                var root = replayed.rootDemands().get(port);
                var expected = material(port == 0 ? Items.EMERALD : Items.DIAMOND);
                assertEquals(count, root.allocations().stream().mapToLong(a -> a.quantity()).sum());
                for (var allocation : root.allocations()) {
                    assertEquals(expected, material(allocation.material().toStack(1)));
                    assertTrue(replayed.nodes().stream().flatMap(node -> node.outputs().stream()).anyMatch(output ->
                            output.material().equals(allocation.material())
                                    && new MaterialSource.ProducerOutput(output.id()).equals(allocation.source())));
                }
            }
        }
    }

    @Test
    void compactSharedProducerPublishesAllOutputsBeforeLaterConsumer() {
        var outputs = Map.of(material(Items.DIAMOND), 2L, material(Items.EMERALD), 3L);
        RecipeNode first = joint("compact_a", Items.DIAMOND, outputs, demand(Items.COAL, 1));
        RecipeNode equivalent = joint("compact_b", Items.DIAMOND, outputs, demand(Items.COAL, 1));
        RecipeNode left = recipe("compact_left", new ItemStack(Items.IRON_INGOT), 1, demand(Items.DIAMOND, 3));
        RecipeNode right = recipe("compact_right", new ItemStack(Items.GOLD_INGOT), 1, demand(Items.DIAMOND, 3));
        var stock = Map.of(key(Items.COAL), 3L);
        // 最后一项不进入确定性片段，验证已聚合的副产物能供给后续真实需求。
        var roots = List.of(demand(Items.IRON_INGOT, 1), demand(Items.GOLD_INGOT, 1),
                new IngredientRef(List.of(material(Items.EMERALD)), 7L, NbtMatchMode.PARTIAL));
        var result = plan(graph(first, equivalent, left, right), stock, roots);
        assertEquals(3, result.steps().size());
        assertEquals(3L, result.steps().get(0).batches());
        assertEquals(Map.of(material(Items.EMERALD), 2L), result.remaining());
        var replayed = PurePlanGraphAdapter.toInputGraph(result, stock,
                List.of(spec(Items.IRON_INGOT, 1), spec(Items.GOLD_INGOT, 1), spec(Items.EMERALD, 7)));
        CraftPlanValidator.validate(replayed);
        assertEquals(3, replayed.nodes().size());
        var producer = replayed.nodes().stream().filter(node -> node.recipeId().equals(first.recipeId())).findFirst().orElseThrow();
        assertEquals(3, producer.executions());
        assertEquals(2, producer.outputs().size());
        var emerald = producer.outputs().stream().filter(output ->
                output.material().equals(MaterialKey.of(new ItemStack(Items.EMERALD)))).findFirst().orElseThrow();
        var diamond = producer.outputs().stream().filter(output ->
                output.material().equals(MaterialKey.of(new ItemStack(Items.DIAMOND)))).findFirst().orElseThrow();
        assertEquals(7L, replayed.rootDemands().get(2).allocations().stream().filter(allocation ->
                allocation.source().equals(new MaterialSource.ProducerOutput(emerald.id())))
                .mapToLong(allocation -> allocation.quantity()).sum());
        assertEquals(6L, replayed.allocations().stream().filter(allocation ->
                allocation.source().equals(new MaterialSource.ProducerOutput(diamond.id())))
                .mapToLong(allocation -> allocation.quantity()).sum());
    }

    @Test
    void nestedDeferredInputsWaitForLaterProductionBeforeCompletingTheirConsumer() {
        RecipeNode producer = joint("producer", Items.DIAMOND,
                Map.of(material(Items.DIAMOND), 1L, material(Items.EMERALD), 2L), demand(Items.COAL, 1));
        RecipeNode first = recipe("first_consumer", new ItemStack(Items.GOLD_INGOT), 1,
                demand(Items.EMERALD, 1));
        RecipeNode second = recipe("second_consumer", new ItemStack(Items.IRON_INGOT), 1,
                demand(Items.DIAMOND, 1));
        // 催化剂使此分支保留递归完成任务，验证跨消费者延后也不会提前完成。
        RecipeNode terminal = recipe("terminal", new ItemStack(Items.REDSTONE), 1,
                demand(Items.GOLD_INGOT, 1), demand(Items.IRON_INGOT, 1), catalyst(Items.SHEARS));
        var stock = Map.of(key(Items.COAL), 1L, key(Items.SHEARS), 1L);
        var result = plan(graph(producer, first, second, terminal), stock,
                List.of(demand(Items.EMERALD, 1), demand(Items.REDSTONE, 1)));
        var replayed = PurePlanGraphAdapter.toInputGraph(result, stock,
                List.of(spec(Items.EMERALD, 1), spec(Items.REDSTONE, 1)));
        assertEquals(4, replayed.nodes().size());
        var producerNode = replayed.nodes().stream().filter(node -> node.recipeId().equals(producer.recipeId())).findFirst().orElseThrow();
        var firstNode = replayed.nodes().stream().filter(node -> node.recipeId().equals(first.recipeId())).findFirst().orElseThrow();
        var emeraldPort = producerNode.outputs().stream().filter(output ->
                output.material().equals(MaterialKey.of(new ItemStack(Items.EMERALD)))).findFirst().orElseThrow();
        assertEquals(1L, replayed.allocations().stream().filter(allocation ->
                allocation.consumer().nodeId().equals(firstNode.id())
                        && allocation.source().equals(new MaterialSource.ProducerOutput(emeraldPort.id())))
                .mapToLong(allocation -> allocation.quantity()).sum());
        assertEquals(Map.of(material(Items.SHEARS), 1L), result.remaining());
        assertTrue(replayed.nodes().indexOf(producerNode) < replayed.nodes().indexOf(firstNode));
    }

    @Test
    void coProductsHaveDistinctPortsOnOneProducerAndCorrectRootAllocations() {
        var outputs = Map.of(material(Items.DIAMOND), 1L, material(Items.EMERALD), 1L);
        RecipeNode diamond = joint("joint", Items.DIAMOND, outputs, demand(Items.COAL, 1));
        RecipeNode emerald = joint("joint", Items.EMERALD, outputs, demand(Items.COAL, 1));
        for (var order : List.of(List.of(Items.DIAMOND, Items.EMERALD), List.of(Items.EMERALD, Items.DIAMOND))) {
            for (long count : List.of(1L, 7L, (long) Integer.MAX_VALUE + 10L)) {
                var stock = Map.of(key(Items.COAL), count);
                var result = plan(graph(diamond, emerald), stock,
                        order.stream().map(item -> demand(item, count)).toList());
                var replayed = PurePlanGraphAdapter.toInputGraph(result, stock,
                        order.stream().map(item -> spec(item, count)).toList());
                assertEquals(count, replayed.nodes().stream().mapToLong(node -> node.executions()).sum());
                assertEquals(count > Integer.MAX_VALUE ? 2 : 1, replayed.nodes().size());
                assertTrue(replayed.nodes().stream().allMatch(node -> node.outputs().size() == 2));
                for (var root : replayed.rootDemands()) {
                    assertEquals(count, root.allocations().stream().mapToLong(a -> a.quantity()).sum());
                    for (var allocation : root.allocations()) {
                        assertTrue(replayed.nodes().stream().flatMap(node -> node.outputs().stream()).anyMatch(
                                output -> output.material().equals(allocation.material())
                                        && new MaterialSource.ProducerOutput(output.id()).equals(allocation.source())));
                    }
                }
            }
        }
    }

    @Test
    void splitRoutesAndSharedCoProductsReplayAcrossMultipleConsumers() {
        var outputs = Map.of(material(Items.DIAMOND), 2L, material(Items.EMERALD), 1L);
        RecipeNode first = joint("first", Items.DIAMOND, outputs, demand(Items.COAL, 2));
        RecipeNode second = joint("second", Items.DIAMOND, outputs, demand(Items.IRON_INGOT, 1));
        RecipeNode consumer = recipe("consumer", new ItemStack(Items.GOLD_INGOT), 1,
                demand(Items.DIAMOND, 3), demand(Items.EMERALD, 1));
        var stock = Map.of(key(Items.COAL), 6L, key(Items.IRON_INGOT), 2L);
        var result = plan(graph(first, second, consumer), stock,
                List.of(demand(Items.DIAMOND, 7), demand(Items.GOLD_INGOT, 1), demand(Items.EMERALD, 3)));
        var replayed = PurePlanGraphAdapter.toInputGraph(result, stock,
                List.of(spec(Items.DIAMOND, 7), spec(Items.GOLD_INGOT, 1), spec(Items.EMERALD, 3)));
        assertEquals(Map.of(material(Items.EMERALD), 1L), result.remaining());
        assertEquals(3L, replayed.nodes().stream().filter(node -> node.recipeId().equals(first.recipeId()))
                .mapToLong(node -> node.executions()).sum());
        assertEquals(2L, replayed.nodes().stream().filter(node -> node.recipeId().equals(second.recipeId()))
                .mapToLong(node -> node.executions()).sum());
        assertEquals(3, replayed.rootDemands().size());
        assertTrue(replayed.allocations().stream().anyMatch(allocation ->
                allocation.source() instanceof MaterialSource.ProducerOutput));
    }

    private static RecipeNode joint(String name, Item selected, Map<MaterialRef, Long> outputs,
                                    IngredientRef... inputs) {
        return new RecipeNode(id(name), material(selected), outputs.get(material(selected)), List.of(inputs),
                "generic", new ResourceLocation("minecraft", "crafting"), List.of(), false, false, outputs);
    }

    private static PureRecipePlanner.Result plan(ImmutableRecipeGraph graph,
                                                 Map<StackKey, Long> stock, List<IngredientRef> roots) {
        var result = PureRecipePlanner.resolve(graph,
                ImmutableRecipeGraphProjector.projectAvailability(stock), roots, 30);
        assertTrue(result.feasible(), result.toString());
        return result;
    }

    private static ImmutableRecipeGraph graph(RecipeNode... nodes) {
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        for (RecipeNode node : nodes) {
            recipes.computeIfAbsent(node.output(), ignored -> new ArrayList<>()).add(node);
        }
        return new ImmutableRecipeGraph(recipes);
    }

    private static RecipeNode recipe(String name, ItemStack output, long count, IngredientRef... inputs) {
        return new RecipeNode(id(name), material(output), count, List.of(inputs));
    }

    private static ScaledIngredientSpec spec(Item item, long count) {
        return new ScaledIngredientSpec(Ingredient.of(item), count, InputConsumption.consumed());
    }

    private static IngredientRef demand(Item item, long count) {
        return new IngredientRef(List.of(material(item)), count);
    }

    private static IngredientRef catalyst(Item item) {
        return new IngredientRef(List.of(material(item)), 1, NbtMatchMode.EXACT, DemandRole.CATALYST);
    }

    private static StackKey key(Item item) {
        MaterialKey identity = MaterialKey.of(new ItemStack(item));
        return new StackKey(identity.item(), identity.tag());
    }
    private static MaterialRef material(Item item) { return material(new ItemStack(item)); }
    private static MaterialRef material(ItemStack stack) {
        return ImmutableRecipeGraphProjector.material(stack, true);
    }
    private static ResourceLocation id(String path) { return new ResourceLocation("test", path); }
}
