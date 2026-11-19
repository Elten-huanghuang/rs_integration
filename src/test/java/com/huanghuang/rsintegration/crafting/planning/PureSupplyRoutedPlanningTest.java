package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PureSupplyRoutedPlanningTest extends BootstrapTest {
    private static final MaterialRef TARGET = material("test:target");
    private static final MaterialRef INTERMEDIATE = material("test:intermediate");
    private static final MaterialRef RAW = material("minecraft:iron_ingot");
    private static final MaterialRef EXTRA = material("minecraft:gold_ingot");
    private static final RecipeNode ROOT = recipe("root", TARGET, demand(INTERMEDIATE));
    private static final RecipeNode BAD = recipe("from_raw", INTERMEDIATE, demand(RAW));
    private static final RecipeNode GOOD = recipe("from_extra", INTERMEDIATE, demand(EXTRA));

    @Test
    void mandatoryZeroSupplySkipsSearchButRestockingTheSameRequestAllowsPureSuccess() {
        var graph = new ImmutableRecipeGraph(Map.of(TARGET, List.of(ROOT), INTERMEDIATE, List.of(BAD)));
        var empty = routed(graph, Map.of(), Map.of());
        var witness = empty.coverage().noSupply();
        assertNotNull(witness);
        assertEquals(List.of(new PureSupplyProof.Port(ROOT.recipeId(), 0, INTERMEDIATE, NbtMatchMode.EXACT),
                new PureSupplyProof.Port(BAD.recipeId(), 0, RAW, NbtMatchMode.EXACT)), witness.path());
        assertNull(empty.plan());
        var stocked = routed(graph, Map.of(new StackKey(Items.IRON_INGOT, null), 1L), Map.of());
        assertNull(stocked.coverage().noSupply());
        assertTrue(stocked.plan().feasible());
    }

    @Test
    void alternateAllowedProducerAndCurrentForcedSelectionDetermineRoute() {
        var graph = new ImmutableRecipeGraph(Map.of(TARGET, List.of(ROOT), INTERMEDIATE, List.of(BAD, GOOD)));
        var stock = Map.of(new StackKey(Items.GOLD_INGOT, null), 1L);
        var both = routed(graph, stock, Map.of());
        assertNull(both.coverage().noSupply());
        assertTrue(both.plan().feasible());
        assertNotNull(routed(graph, stock, Map.of(INTERMEDIATE.itemId(), BAD.recipeId())).coverage().noSupply());
        var selected = routed(graph, stock, Map.of(INTERMEDIATE.itemId(), GOOD.recipeId()));
        assertNull(selected.coverage().noSupply());
        assertTrue(selected.plan().feasible());
    }

    @Test
    void exactStockUsesKindAndNbtAndCountsAllMatchingVariants() {
        var tagged = new MaterialRef(RAW.itemId(), "{v:1}");
        var other = new MaterialRef(RAW.itemId(), "{v:2}");
        var exact = recipe("root", TARGET, demand(tagged));
        var graph = new ImmutableRecipeGraph(Map.of(TARGET, List.of(exact)));
        assertNotNull(check(graph, Map.of(other, 9L, MaterialRef.fluid(RAW.itemId(), tagged.nbt()), 1L)).noSupply());
        assertNull(check(graph, Map.of(tagged, 1L)).noSupply());
        var any = recipe("root", TARGET, new IngredientRef(List.of(tagged), 1, NbtMatchMode.ANY));
        assertNull(check(new ImmutableRecipeGraph(Map.of(TARGET, List.of(any))), Map.of(other, 1L)).noSupply());
    }

    @Test
    void guaranteedSecondaryOutputCanSupplyTheRequiredPort() {
        var multi = new RecipeNode(GOOD.recipeId(), INTERMEDIATE, 1, List.of(demand(EXTRA)),
                "generic", GOOD.recipeTypeId(), List.of(), false, false, Map.of(INTERMEDIATE, 1L, RAW, 1L));
        var root = recipe("root", TARGET, demand(RAW), demand(INTERMEDIATE));
        var graph = new ImmutableRecipeGraph(Map.of(TARGET, List.of(root), INTERMEDIATE, List.of(multi)));
        assertNull(check(graph, Map.of()).noSupply());
        assertTrue(routed(graph, Map.of(new StackKey(Items.GOLD_INGOT, null), 1L), Map.of()).plan().feasible());
    }

    @Test
    void forcedKeyDoesNotRuleOutSurplusFromADifferentlyKeyedPort() {
        var tagged = new MaterialRef(INTERMEDIATE.itemId(), "{v:1}");
        var otherPort = new IngredientRef(List.of(tagged), 1, NbtMatchMode.ANY);
        var root = recipe("root", TARGET, demand(INTERMEDIATE), otherPort);
        var graph = new ImmutableRecipeGraph(Map.of(TARGET, List.of(root), INTERMEDIATE, List.of(BAD, GOOD)));
        var gap = new PurePlanningCoverage.Probe(RAW, PurePlanningCoverage.Status.GAP);
        var result = PureSupplyProof.check(gap, graph, ROOT.recipeId(), Map.of(),
                Map.of(INTERMEDIATE.itemId(), BAD.recipeId()), PlanningPreferences.empty(),
                Set.of(), Set.of(), 4096, Long.MAX_VALUE);
        assertSame(gap, result);
    }

    @Test
    void exclusionsAffectOnlyCurrentSnapshotAndLockedPortsReturnToNormalRouting() {
        var graph = new ImmutableRecipeGraph(Map.of(TARGET, List.of(ROOT), INTERMEDIATE, List.of(BAD, GOOD)));
        var gap = new PurePlanningCoverage.Probe(RAW, PurePlanningCoverage.Status.GAP);
        var excluded = new PlanningPreferences(Map.of(), Map.of(), Set.of(GOOD.recipeId()));
        assertNotNull(PureSupplyProof.check(gap, graph, ROOT.recipeId(), Map.of(), Map.of(), excluded,
                Set.of(), Set.of(), 4096, Long.MAX_VALUE).noSupply());
        var locked = new PlanningPreferences(Map.of(), Map.of("slot", new ItemStack(Items.IRON_INGOT)), Set.of(GOOD.recipeId()));
        assertSame(gap, PureSupplyProof.check(gap, graph, ROOT.recipeId(), Map.of(), Map.of(), locked,
                Set.of(), Set.of(), 4096, Long.MAX_VALUE));
        assertSame(gap, PureSupplyProof.check(gap, graph, ROOT.recipeId(), Map.of(), Map.of(), excluded,
                Set.of(), Set.of(BAD.recipeId()), 4096, Long.MAX_VALUE));
    }

    @Test
    void dynamicIdentityCatalystAndAlternativePortsDoNotProveMandatoryConsumption() {
        var dynamic = new RecipeNode(BAD.recipeId(), INTERMEDIATE, 1, BAD.inputs(), "generic",
                BAD.recipeTypeId(), List.of(), false, true);
        assertNull(check(new ImmutableRecipeGraph(Map.of(TARGET, List.of(ROOT), INTERMEDIATE, List.of(dynamic))),
                Map.of()).noSupply());
        for (var input : List.of(new IngredientRef(List.of(RAW, EXTRA), 1),
                new IngredientRef(List.of(RAW), 1, NbtMatchMode.EXACT, DemandRole.CATALYST))) {
            assertNull(check(new ImmutableRecipeGraph(Map.of(TARGET, List.of(recipe("root", TARGET, input)))),
                    Map.of()).noSupply());
        }
    }

    @Test
    void budgetExhaustionLeavesProbeUnchangedAndCancellationPropagates() {
        var graph = new ImmutableRecipeGraph(Map.of(TARGET, List.of(ROOT), INTERMEDIATE, List.of(BAD)));
        var gap = new PurePlanningCoverage.Probe(RAW, PurePlanningCoverage.Status.GAP);
        assertSame(gap, PureSupplyProof.check(gap, graph, ROOT.recipeId(), Map.of(), Map.of(),
                PlanningPreferences.empty(), Set.of(), Set.of(), 1, Long.MAX_VALUE));
        assertSame(gap, PureSupplyProof.check(gap, graph, ROOT.recipeId(), Map.of(), Map.of(),
                PlanningPreferences.empty(), Set.of(), Set.of(), 4096, System.nanoTime() - 1));
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> check(graph, Map.of()));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void cancellationAfterProofBeforeHandoffDiscardsTheRoute() throws InterruptedException {
        var graph = new ImmutableRecipeGraph(Map.of(TARGET, List.of(ROOT), INTERMEDIATE, List.of(BAD)));
        var snapshot = new PlanningSnapshot(UUID.randomUUID(), 1, 1, ROOT.recipeId(),
                Map.of(), Map.of(), graph, "", "", false);
        var routing = AsyncPurePlanningService.RouteInputs.fromItems(snapshot.availableItems(), 1,
                Set.of(), Set.of(), Set.of(RAW.itemId()));
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var delivered = new AtomicReference<AsyncPurePlanningService.RoutedPlan>();
        var failure = new AtomicReference<Throwable>();
        try (var coordinator = new AsyncPlanningCoordinator(1)) {
            new AsyncPurePlanningService(coordinator).submitRouted(snapshot, routing, 1, callbacks::add,
                    64, 4096, 1024, 1500, delivered::set, failure::set);
            var late = callbacks.poll(5, TimeUnit.SECONDS);
            assertNotNull(late);
            coordinator.cancel(snapshot.playerId());
            late.run();
            var cancelled = callbacks.poll(5, TimeUnit.SECONDS);
            assertNotNull(cancelled);
            cancelled.run();
            assertNull(delivered.get());
            assertInstanceOf(CancellationException.class, failure.get());
        }
    }

    private static AsyncPurePlanningService.RoutedPlan routed(ImmutableRecipeGraph graph, Map<StackKey, Long> stock,
            Map<ResourceLocation, ResourceLocation> forced) {
        var snapshot = new PlanningSnapshot(new UUID(1, 1), 1, 1, ROOT.recipeId(), stock, forced, graph, "", "", false);
        var routing = AsyncPurePlanningService.RouteInputs.fromItems(snapshot.availableItems(), 1,
                Set.of(), Set.of(), Set.of(RAW.itemId()));
        return AsyncPurePlanningService.computeRouted(snapshot, routing, 1, 64, 4096, 1024, 1500);
    }

    private static PurePlanningCoverage.Probe check(ImmutableRecipeGraph graph, Map<MaterialRef, Long> stock) {
        return PureSupplyProof.check(new PurePlanningCoverage.Probe(RAW, PurePlanningCoverage.Status.GAP),
                graph, ROOT.recipeId(), stock, Map.of(), PlanningPreferences.empty(), Set.of(), Set.of(),
                4096, Long.MAX_VALUE);
    }

    private static MaterialRef material(String id) { return new MaterialRef(ResourceLocation.parse(id), ""); }
    private static IngredientRef demand(MaterialRef material) { return new IngredientRef(List.of(material), 1); }
    private static RecipeNode recipe(String id, MaterialRef output, IngredientRef... inputs) {
        return new RecipeNode(ResourceLocation.fromNamespaceAndPath("test", id), output, 1, List.of(inputs));
    }
}
