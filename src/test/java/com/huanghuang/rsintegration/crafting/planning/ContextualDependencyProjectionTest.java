package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContextualDependencyProjectionTest extends BootstrapTest {
    private MockedStatic<ServerLifecycleHooks> lifecycle;

    @BeforeEach
    void serverThread() {
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.isSameThread()).thenReturn(true);
        lifecycle = mockStatic(ServerLifecycleHooks.class);
        lifecycle.when(ServerLifecycleHooks::getCurrentServer).thenReturn(server);
    }

    @AfterEach
    void closeServer() { lifecycle.close(); }

    @Test
    void newDependenciesAreCapturedTransitivelyOnceAndCyclesTerminate() {
        RecipeNode target = recipe("target", "raw");
        RecipeNode first = recipe("first", "raw");
        RecipeNode second = recipe("second", "raw");
        RecipeNode unrelated = recipe("unrelated", "raw");
        ImmutableRecipeGraph graph = graph(target, first, second, unrelated);
        Map<ResourceLocation, Integer> captures = new HashMap<>();
        ImmutableRecipeGraph scoped = ImmutableRecipeGraphProjector.captureContextualDependencies(
                graph, target.recipeId(), recipe -> {
                    captures.merge(recipe.recipeId(), 1, Integer::sum);
                    if (recipe == target) return List.of(catalyst(first.output()));
                    if (recipe == first) return List.of(catalyst(second.output()));
                    if (recipe == second) return List.of(catalyst(first.output()));
                    fail("无关配方不能读取世界状态");
                    return null;
                });
        assertEquals(Map.of(target.recipeId(), 1, first.recipeId(), 1, second.recipeId(), 1), captures);
        assertEquals(3, scoped.recipesById().size());
        assertFalse(scoped.recipesById().containsKey(unrelated.recipeId()));
        assertEquals(first.output(), scoped.recipesById().get(target.recipeId()).inputs().get(0).alternatives().get(0));
        assertEquals(material("raw"), graph.recipesById().get(target.recipeId()).inputs().get(0).alternatives().get(0));
    }

    @Test
    void removingAnInstalledCatalystPrunesItsUnusedRecipeAndLaterRequestsRecapture() {
        RecipeNode tool = recipe("tool", "raw");
        RecipeNode target = new RecipeNode(id("target"), material("target"), 1, List.of(catalyst(tool.output())));
        ImmutableRecipeGraph graph = graph(target, tool);
        AtomicInteger captures = new AtomicInteger();
        ImmutableRecipeGraph installed = ImmutableRecipeGraphProjector.captureContextualDependencies(
                graph, target.recipeId(), recipe -> {
                    captures.incrementAndGet();
                    assertEquals(target.recipeId(), recipe.recipeId());
                    return List.of();
                });
        assertEquals(1, captures.get());
        assertEquals(1, installed.recipesById().size());
        assertTrue(installed.recipesById().get(target.recipeId()).inputs().isEmpty());
        ImmutableRecipeGraph removed = ImmutableRecipeGraphProjector.captureContextualDependencies(
                graph, target.recipeId(), recipe -> null);
        assertEquals(2, removed.recipesById().size());
        assertFalse(removed.recipesById().get(target.recipeId()).inputs().isEmpty());
    }

    @Test
    void changesAreKeptWhenTheWholeGraphIsRelatedAndOutputVariantsSurvive() {
        RecipeNode target = recipe("target", "raw");
        MaterialRef variant = new MaterialRef(target.output().itemId(), "{variant:1}");
        RecipeNode variantNode = new RecipeNode(target.recipeId(), variant, 2, target.inputs());
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(
                Map.of(target.output(), List.of(target), variant, List.of(variantNode)),
                Map.of(target.recipeId(), target));
        AtomicInteger captures = new AtomicInteger();
        ImmutableRecipeGraph scoped = ImmutableRecipeGraphProjector.captureContextualDependencies(
                graph, target.recipeId(), recipe -> {
                    captures.incrementAndGet();
                    return List.of(catalyst(material("tool")));
                });
        assertEquals(1, captures.get());
        assertEquals(2, scoped.recipesByOutput().size());
        assertEquals(2, scoped.recipesByOutput().get(variant).get(0).outputCount());
        assertEquals(DemandRole.CATALYST, scoped.recipesById().get(target.recipeId()).inputs().get(0).role());
        assertEquals(scoped.recipesById().get(target.recipeId()).inputs(),
                scoped.recipesByOutput().get(variant).get(0).inputs());
    }

    @Test
    void backgroundThreadCannotInvokeContextCapture() {
        RecipeNode target = recipe("target", "raw");
        assertThrows(PlanningThreadContext.MainThreadPlanningRequiredException.class, () ->
                PlanningThreadContext.runInBackground(() -> ImmutableRecipeGraphProjector.captureContextualDependencies(
                        graph(target), target.recipeId(), recipe -> {
                            fail("后台不能访问上下文采集回调");
                            return null;
                        })));
    }

    private static IngredientRef catalyst(MaterialRef material) {
        return new IngredientRef(List.of(material), 1, NbtMatchMode.EXACT, DemandRole.CATALYST);
    }

    private static RecipeNode recipe(String output, String input) {
        return new RecipeNode(id(output), material(output), 1,
                List.of(new IngredientRef(List.of(material(input)), 1)));
    }

    private static ImmutableRecipeGraph graph(RecipeNode... recipes) {
        Map<MaterialRef, List<RecipeNode>> outputs = new HashMap<>();
        for (RecipeNode recipe : recipes) outputs.put(recipe.output(), List.of(recipe));
        return new ImmutableRecipeGraph(outputs);
    }

    private static ResourceLocation id(String name) { return new ResourceLocation("test", name); }
    private static MaterialRef material(String name) { return new MaterialRef(id(name), ""); }
}
