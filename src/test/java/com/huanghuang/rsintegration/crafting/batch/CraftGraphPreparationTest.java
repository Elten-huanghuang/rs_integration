package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftGraphPreparationTest extends BootstrapTest {
    @Test
    void unchangedBindingFilterReusesGraphButStillChecksEveryCandidate() {
        MaterialRef output = material("output");
        RecipeNode first = recipe("first", output);
        RecipeNode second = recipe("second", output);
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(output, List.of(first, second)));
        List<RecipeNode> runtimeChecked = new ArrayList<>();
        List<RecipeNode> machineChecked = new ArrayList<>();

        var filtered = GenericCraftPacket.filterRecipeGraph(graph,
                candidate -> { runtimeChecked.add(candidate); return true; },
                candidate -> { machineChecked.add(candidate); return true; });

        assertSame(graph, filtered.graph());
        assertEquals(List.of(first, second), runtimeChecked);
        assertEquals(runtimeChecked, machineChecked);
        assertTrue(filtered.blockedOutputIds().isEmpty());
    }

    @Test
    void changedBindingFilterPreservesOrderSharingAndBlockedReasons() {
        MaterialRef mixed = material("mixed");
        MaterialRef kept = material("kept");
        MaterialRef runtimeBlocked = material("runtime_blocked");
        MaterialRef machineBlocked = material("machine_blocked");
        RecipeNode first = recipe("first", mixed);
        RecipeNode rejected = recipe("rejected", mixed);
        RecipeNode last = recipe("last", mixed);
        RecipeNode unchanged = recipe("unchanged", kept);
        RecipeNode unavailable = recipe("unavailable", runtimeBlocked);
        RecipeNode unbound = recipe("unbound", machineBlocked);
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                mixed, List.of(first, rejected, last), kept, List.of(unchanged),
                runtimeBlocked, List.of(unavailable), machineBlocked, List.of(unbound)));

        var filtered = GenericCraftPacket.filterRecipeGraph(graph,
                candidate -> candidate != unavailable,
                candidate -> candidate != rejected && candidate != unbound);

        assertEquals(List.of(first, last), filtered.graph().recipesByOutput().get(mixed));
        assertSame(graph.recipesByOutput().get(kept), filtered.graph().recipesByOutput().get(kept));
        assertEquals(Set.of(machineBlocked.itemId()), filtered.blockedOutputIds());
        assertEquals(Set.of(mixed, kept), filtered.graph().recipesByOutput().keySet());
        assertEquals(List.of(first, rejected, last), graph.recipesByOutput().get(mixed));
        assertThrows(UnsupportedOperationException.class,
                () -> filtered.graph().recipesByOutput().get(mixed).add(rejected));
    }

    @Test
    void addingPlanningRootCopiesOnlyItsBucketAndPreservesTheOriginalGraph() {
        MaterialRef output = material("root_output");
        MaterialRef dependency = material("dependency");
        RecipeNode existing = recipe("existing", output);
        RecipeNode untouched = recipe("untouched", dependency);
        RecipeNode root = recipe("new_root", output);
        ImmutableRecipeGraph original = new ImmutableRecipeGraph(Map.of(
                output, List.of(existing), dependency, List.of(untouched)));

        ImmutableRecipeGraph result = CraftPureExecutionPlanner.withPlanningRoot(original, root);

        assertEquals(List.of(existing, root), result.recipesByOutput().get(output));
        assertEquals(List.of(existing), original.recipesByOutput().get(output));
        assertSame(original.recipesByOutput().get(dependency), result.recipesByOutput().get(dependency));
        assertSame(root, result.recipesById().get(root.recipeId()));
        assertThrows(UnsupportedOperationException.class,
                () -> result.recipesByOutput().get(output).clear());
    }

    @Test
    void physicalRootFallsBackToFrozenRecipeInputsWhenRuntimeSpecsCannotProject() {
        MaterialRef output = material("tacz_output");
        MaterialRef brick = material("archaic_brick");
        RecipeNode target = new RecipeNode(
                new ResourceLocation("emxarms", "attachments/stock_emx_reentrantlock"),
                output, 1, List.of(new IngredientRef(List.of(brick), 1)));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(output, List.of(target)));
        List<IngredientSpec> runtimeOnly = List.of(new IngredientSpec(
                Ingredient.of(Items.IRON_INGOT), 1, DemandRole.TRANSFORMED));

        List<IngredientRef> inputs = CraftPureExecutionPlanner.physicalRootInputs(
                graph, target.recipeId(), runtimeOnly, 2);

        assertEquals(List.of(new IngredientRef(List.of(brick), 2)), inputs);
    }

    private static RecipeNode recipe(String name, MaterialRef output) {
        return new RecipeNode(new ResourceLocation("test", name), output, 1, List.of());
    }

    private static MaterialRef material(String name) {
        return new MaterialRef(new ResourceLocation("test", name), "");
    }
}
