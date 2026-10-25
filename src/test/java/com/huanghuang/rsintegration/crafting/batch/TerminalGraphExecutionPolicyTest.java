package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.TerminalGraphComposer;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalGraphExecutionPolicyTest extends BootstrapTest {
    @Test
    void repeatedStaticRecipeCanBeComposed() {
        TerminalGraphExecutionPolicy.Decision decision =
                TerminalGraphExecutionPolicy.decide(
                        true, false, new ItemStack(Items.IRON_INGOT));

        assertTrue(decision.composable());
        assertEquals(TerminalGraphExecutionPolicy.Reason.COMPOSABLE, decision.reason());
    }

    @Test
    void preservesEvidenceBasedFallbackReasons() {
        assertTrue(TerminalGraphExecutionPolicy.decide(
                false, true, new ItemStack(Items.IRON_INGOT)).composable());
        assertEquals(TerminalGraphExecutionPolicy.Reason.DYNAMIC_INPUTS,
                TerminalGraphExecutionPolicy.decide(
                        false, false, new ItemStack(Items.IRON_INGOT)).reason());
        TerminalGraphExecutionPolicy.Decision unknownOutput =
                TerminalGraphExecutionPolicy.decide(true, false, ItemStack.EMPTY);
        assertFalse(unknownOutput.composable());
        assertEquals(TerminalGraphExecutionPolicy.Reason.UNKNOWN_OUTPUT, unknownOutput.reason());
        TerminalGraphExecutionPolicy.Decision probabilistic =
                TerminalGraphExecutionPolicy.decide(
                        true, false, new ItemStack(Items.IRON_INGOT));
        assertTrue(probabilistic.composable());
        assertEquals(TerminalGraphExecutionPolicy.Reason.COMPOSABLE, probabilistic.reason());
    }

    @Test
    void selfAmplifyingTerminalCanBeComposedAfterSeedPlanning() {
        TerminalGraphExecutionPolicy.Decision decision =
                TerminalGraphExecutionPolicy.decide(
                        true, false, new ItemStack(Items.IRON_INGOT, 2));

        assertTrue(decision.composable());
        assertEquals(TerminalGraphExecutionPolicy.Reason.COMPOSABLE, decision.reason());
    }

    @Test
    void syntheticTerminalCanBecomeARequestScopedGraphNode() {
        CraftPlanGraph empty = new CraftPlanGraph(CraftPlanGraph.CURRENT_VERSION,
                List.of(), List.of(), List.of(), List.of(), List.of());
        CraftingResolver.ResolutionStep synthetic = new CraftingResolver.ResolutionStep(
                new ResourceLocation("test", "synthetic"), ModType.GENERIC,
                new ResourceLocation("test", "synthetic_type"),
                List.of(), List.of(), true, 1,
                new ItemStack(Items.DIAMOND), new ItemStack(Items.IRON_INGOT));

        CraftPlanGraph graph = TerminalGraphComposer.compose(
                empty, synthetic, new ItemStack(Items.IRON_INGOT));

        assertEquals(1, graph.nodes().size());
        assertTrue(graph.nodes().get(0).inferMode());
        assertTrue(graph.nodes().get(0).inputs().isEmpty());
        assertEquals(Items.DIAMOND, graph.nodes().get(0).syntheticInput().getItem());
    }
}
