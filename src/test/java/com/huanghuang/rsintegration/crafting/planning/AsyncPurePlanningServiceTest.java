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
            AtomicReference<PureRecipePlanner.Result> result = new AtomicReference<>();
            new AsyncPurePlanningService(coordinator).submit(snapshot, Runnable::run, 20,
                    value -> { result.set(value); done.countDown(); }, failure -> done.countDown());
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertTrue(result.get() != null && result.get().feasible() == false);
        }
    }

    private static ResourceLocation id(String path) { return new ResourceLocation("test", path); }
}
