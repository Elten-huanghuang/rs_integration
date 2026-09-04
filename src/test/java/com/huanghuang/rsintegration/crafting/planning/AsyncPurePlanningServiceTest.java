package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AsyncPurePlanningServiceTest {
    @Test
    void computesFromSnapshotAndReturnsOnServerExecutor() throws Exception {
        MaterialRef log = new MaterialRef(id("log"), "");
        MaterialRef plank = new MaterialRef(id("plank"), "");
        RecipeNode target = new RecipeNode(id("planks"), plank, 4,
                List.of(new IngredientRef(List.of(log), 1)));
        PlanningSnapshot snapshot = new PlanningSnapshot(UUID.randomUUID(), 1, 1, id("planks"),
                Map.of(), Map.of(), new ImmutableRecipeGraph(Map.of(plank, List.of(target))),
                "network", "binding", false);
        try (AsyncPlanningCoordinator coordinator = new AsyncPlanningCoordinator(1)) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<AsyncPurePlanningService.CompletedPlan> result = new AtomicReference<>();
            new AsyncPurePlanningService(coordinator).submit(snapshot, Runnable::run, 20,
                    value -> { result.set(value); done.countDown(); }, failure -> done.countDown());
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertTrue(result.get() != null && result.get().snapshot() == snapshot);
            assertTrue(result.get() != null && result.get().result().feasible() == false);
        }
    }

    @Test
    void terminalAmplificationUsesOneSeedAcrossRepeatedExecutions() throws Exception {
        MaterialRef template = new MaterialRef(id("template"), "");
        MaterialRef diamond = new MaterialRef(id("diamond"), "");
        MaterialRef stone = new MaterialRef(id("stone"), "");
        RecipeNode duplicate = new RecipeNode(id("duplicate"), template, 2, List.of(
                new IngredientRef(List.of(template), 1),
                new IngredientRef(List.of(diamond), 7),
                new IngredientRef(List.of(stone), 1)));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(
                Map.of(template, List.of(duplicate)));
        Map<MaterialRef, Integer> projectedStock = Map.of(template, 1, diamond, 42, stone, 6);

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(graph,
                projectedStock,
                com.huanghuang.rsintegration.crafting.SelfAmplifyingRecipePolicy
                        .scaleTargetInputs(duplicate, 6), 20);

        assertTrue(result.feasible());
        assertEquals(List.of(), result.steps());
        assertTrue(result.remaining().isEmpty());
    }

    @Test
    void repeatedPhysicalTerminalPlansEveryIntermediateInput() {
        MaterialRef commonInk = new MaterialRef(id("common_ink"), "");
        MaterialRef uncommonInk = new MaterialRef(id("uncommon_ink"), "");
        MaterialRef rareInk = new MaterialRef(id("rare_ink"), "");
        RecipeNode makeUncommon = new RecipeNode(id("make_uncommon"), uncommonInk, 1,
                List.of(new IngredientRef(List.of(commonInk), 1)));
        RecipeNode makeRare = new RecipeNode(id("make_rare"), rareInk, 1,
                List.of(new IngredientRef(List.of(uncommonInk), 1)));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                uncommonInk, List.of(makeUncommon), rareInk, List.of(makeRare)));

        List<IngredientRef> roots = com.huanghuang.rsintegration.crafting
                .SelfAmplifyingRecipePolicy.scaleTargetInputs(makeRare, 3);
        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                graph, Map.of(commonInk, 3), roots, 20);

        assertTrue(result.feasible());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(id("make_uncommon"), 3)),
                result.steps());
    }

    @Test
    void neutralTerminalConversionCannotConsumeItsOwnOutputFromBroadInput() {
        MaterialRef chest = new MaterialRef(id("chest"), "");
        MaterialRef trappedChest = new MaterialRef(id("trapped_chest"), "");
        MaterialRef hook = new MaterialRef(id("tripwire_hook"), "");
        RecipeNode target = new RecipeNode(id("make_trapped_chest"), trappedChest, 1,
                List.of(new IngredientRef(List.of(chest, trappedChest), 1),
                        new IngredientRef(List.of(hook), 1)));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(trappedChest, List.of(target)));
        List<IngredientRef> roots = com.huanghuang.rsintegration.crafting
                .SelfAmplifyingRecipePolicy.scaleTargetInputs(target, 21);

        assertEquals(List.of(chest), roots.get(0).alternatives());
        assertEquals(21, roots.get(0).count());
        PureRecipePlanner.Result result = PureRecipePlanner.resolve(graph,
                Map.of(chest, 15, trappedChest, 6, hook, 21), roots, 20);
        assertTrue(!result.feasible());
    }

    private static ResourceLocation id(String path) { return new ResourceLocation("test", path); }
}
