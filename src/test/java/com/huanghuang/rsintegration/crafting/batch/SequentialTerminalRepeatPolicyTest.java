package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.graph.RootAllocation;
import com.huanghuang.rsintegration.crafting.graph.RootDemand;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SequentialTerminalRepeatPolicyTest extends BootstrapTest {

    @Test
    void arcaneIteratorBatchIsReducedToOnePhysicalStart() {
        ResolutionStep lens = step("arcane_iterator/arcanum_lens", 2);

        assertTrue(CraftExecutionPolicy.requiresExternalTerminalRepeat(lens));

        ResolutionStep single = CraftExecutionPolicy.singleExecution(lens);
        assertEquals(1, single.executions());
        assertEquals(lens.recipeId(), single.recipeId());
        assertSame(lens.modType(), single.modType());
    }

    @Test
    void enchantmentRecipeUsesTheSameSequentialPolicy() {
        ResolutionStep enchantment = step("arcane_iterator/enchantments/aqua_affinity", 3);

        assertTrue(CraftExecutionPolicy.requiresExternalTerminalRepeat(enchantment));
        assertEquals(1,
                CraftExecutionPolicy.singleExecution(enchantment).executions());
    }

    @Test
    void singleRunAndOtherRecipesKeepTheExistingExecutionPath() {
        assertFalse(CraftExecutionPolicy.requiresExternalTerminalRepeat(
                step("arcane_iterator/arcanum_lens", 1)));
        assertFalse(CraftExecutionPolicy.requiresExternalTerminalRepeat(
                step("wissen_crystallizer/earth_crystal_seed", 2)));
        assertFalse(CraftExecutionPolicy.requiresExternalTerminalRepeat(null));
    }

    @Test
    void singleTerminalGraphKeepsOnlyOnePhysicalStartAndRepeatsOutsideTheGraph() {
        ResolutionStep lens = step("arcane_iterator/arcanum_lens", 4);
        CraftPlanGraph empty = new CraftPlanGraph(CraftPlanGraph.CURRENT_VERSION,
                List.of(), List.of(), List.of(), List.of(), List.of());
        NodeId nodeId = new NodeId(0);
        CraftNode node = new CraftNode(nodeId, lens.recipeId(), lens.modType().id(),
                lens.recipeTypeId(), 1, List.of(), List.of(), lens.inferMode(),
                lens.syntheticInput(), lens.syntheticOutput(), List.of(), List.of());
        MaterialKey output = MaterialKey.of(new ItemStack(Items.ENCHANTED_BOOK));
        OutputPortId port = new OutputPortId(nodeId, 0);
        RootDemand root = new RootDemand(Ingredient.of(Items.ENCHANTED_BOOK), 1, 0,
                new ItemStack(Items.ENCHANTED_BOOK), List.of(new RootAllocation(
                new MaterialSource.ProducerOutput(port), output, 1)),
                DemandRole.CONSUMED);
        CraftPlanGraph graph = new CraftPlanGraph(CraftPlanGraph.CURRENT_VERSION,
                List.of(node), List.of(), List.of(root), List.of(), List.of(nodeId));

        assertEquals(1, CraftExecutionPolicy.graphTerminalStep(empty, lens).executions());
        assertEquals(1, graph.nodes().get(0).executions());
        assertEquals(4, CraftExecutionPolicy.graphRemainingRepeats(graph, lens));
    }

    private static ResolutionStep step(String path, int executions) {
        return new ResolutionStep(
                new ResourceLocation("wizards_reborn", path),
                ModType.GENERIC,
                new ResourceLocation("wizards_reborn", path),
                List.of(), List.of(), false, executions,
                new ItemStack(Items.BOOK), new ItemStack(Items.ENCHANTED_BOOK));
    }
}
