package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.api.IntegrationResourceKey.ResourceKind;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.ScaledIngredientSpec;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
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
