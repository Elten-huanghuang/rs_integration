package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.InputDemand;
import com.huanghuang.rsintegration.crafting.graph.InputPortId;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.RootAllocation;
import com.huanghuang.rsintegration.crafting.graph.RootDemand;
import com.huanghuang.rsintegration.mods.apprenticecodex.ApprenticeCodexRSModule;
import com.huanghuang.rsintegration.mods.embers.EreAlchemyRSModule;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.ModIds;
import org.junit.jupiter.api.Test;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphExecutionPolicyTest extends BootstrapTest {

    @Test
    void appendedTerminalStepCannotUseIncompleteGraphExecutor() {
        assertFalse(GraphExecutionPolicy.useGraphExecutor(true));
    }

    @Test
    void selfContainedGraphUsesGraphExecutor() {
        assertTrue(GraphExecutionPolicy.useGraphExecutor(false));
    }

    @Test
    void decisionsExposeCompatibilityReason() {
        GraphExecutionPolicy.Decision appended = GraphExecutionPolicy.decide(true,
                (GraphExecutionPolicy.ModTypeConstraint) null);
        assertFalse(appended.useGraphExecutor());
        assertEquals(GraphExecutionPolicy.Reason.APPENDED_TERMINAL_NOT_IN_GRAPH, appended.reason());

        GraphExecutionPolicy.Decision legacy = GraphExecutionPolicy.decide(false,
                new GraphExecutionPolicy.ModTypeConstraint(true, "requires flat machine transaction"));
        assertFalse(legacy.useGraphExecutor());
        assertEquals(GraphExecutionPolicy.Reason.MOD_REQUIRES_FLAT_EXECUTION, legacy.reason());
        assertTrue(GraphExecutionPolicy.decide(false,
                (GraphExecutionPolicy.ModTypeConstraint) null).useGraphExecutor());
        GraphExecutionPolicy.Decision customGui = GraphExecutionPolicy.decide(false, ModType.CUSTOM_GUI);
        assertFalse(customGui.useGraphExecutor());
        assertEquals(GraphExecutionPolicy.Reason.MOD_REQUIRES_FLAT_EXECUTION, customGui.reason());
    }

    @Test
    void anyLegacyTypeForcesTheWholeGraphOntoFlatExecution() {
        GraphExecutionPolicy.Decision decision = GraphExecutionPolicy.decide(false,
                List.of(ModType.GENERIC, ModType.CUSTOM_GUI));

        assertFalse(decision.useGraphExecutor());
        assertEquals(GraphExecutionPolicy.Reason.MOD_REQUIRES_FLAT_EXECUTION, decision.reason());
        assertTrue(decision.detail().contains("custom_gui"));
    }

    @Test
    void graphCompatibleTypesKeepSelfContainedGraphExecution() {
        GraphExecutionPolicy.Decision decision = GraphExecutionPolicy.decide(false,
                List.of(ModType.GENERIC, ModType.FARMINGFORBLOCKHEADS_MARKET));

        assertTrue(decision.useGraphExecutor());
        assertEquals(GraphExecutionPolicy.Reason.GRAPH_COMPLETE, decision.reason());
    }

    @Test
    void genericCatalystOrDurabilityInputsStayInGraphExecution() {
        CraftPlanGraph catalystGraph = graphWithRole(DemandRole.CATALYST);
        GraphExecutionPolicy.Decision catalyst = GraphExecutionPolicy.decide(
                false, List.of(ModType.GENERIC), catalystGraph);
        assertTrue(catalyst.useGraphExecutor());
        assertEquals(GraphExecutionPolicy.Reason.GRAPH_COMPLETE, catalyst.reason());

        GraphExecutionPolicy.Decision durability = GraphExecutionPolicy.decide(
                false, List.of(ModType.GENERIC), graphWithRole(DemandRole.DURABILITY));
        assertTrue(durability.useGraphExecutor());
        assertEquals(GraphExecutionPolicy.Reason.GRAPH_COMPLETE, durability.reason());
    }

    @Test
    void repeatedArcaneIteratorOperationsUseSequentialFlatExecution() {
        ResourceLocation iteratorType = new ResourceLocation(
                "wizards_reborn", "arcane_iterator");
        ResourceLocation recipeId = new ResourceLocation(
                "wizards_reborn", "arcane_iterator/growing");
        NodeId nodeId = new NodeId(0);
        MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
        InputDemand input = new InputDemand(new InputPortId(nodeId, 0),
                Ingredient.of(Items.DIAMOND), 1, DemandRole.CONSUMED,
                new ItemStack(Items.DIAMOND));
        CraftNode node = new CraftNode(nodeId, recipeId, "wizards_reborn",
                iteratorType, 3, List.of(), List.of(), false,
                null, null, List.of(input), List.of(), List.of());
        RootDemand root = new RootDemand(Ingredient.of(Items.DIAMOND), 3, 0,
                new ItemStack(Items.DIAMOND), List.of(new RootAllocation(
                new MaterialSource.InitialPool(diamond), diamond, 3)),
                DemandRole.CONSUMED);
        CraftPlanGraph graph = new CraftPlanGraph(1, List.of(node), List.of(),
                List.of(root), List.of(), List.of(nodeId));

        GraphExecutionPolicy.Decision decision = GraphExecutionPolicy.decide(
                false, List.of(ModType.GENERIC), graph);

        assertFalse(decision.useGraphExecutor());
        assertEquals(GraphExecutionPolicy.Reason.SEQUENTIAL_MACHINE_BATCH_REQUIRES_FLAT,
                decision.reason());
    }

    @Test
    void embersAlchemyUsesFlatExecutionForTabletLocking() {
        EreAlchemyRSModule.INSTANCE.registerModType();
        ModType alchemy = ModType.byId(ModIds.ID_EMBERS_ALCHEMY);

        GraphExecutionPolicy.Decision decision = GraphExecutionPolicy.decide(false, alchemy);

        assertFalse(decision.useGraphExecutor());
        assertEquals(ModType.GraphExecutionAudit.FLAT_REQUIRED,
                alchemy.graphExecutionAudit());
        assertEquals(GraphExecutionPolicy.Reason.MOD_REQUIRES_FLAT_EXECUTION,
                decision.reason());
        assertTrue(decision.detail().contains("tablet locking"));
    }

    @Test
    void essenceSmokerUsesFlatExecutionForPerBatchCatalysts() {
        ApprenticeCodexRSModule.INSTANCE.registerModType();
        ModType smoker = ModType.byId(ApprenticeCodexRSModule.ESSENCE_SMOKER_TYPE);

        GraphExecutionPolicy.Decision decision = GraphExecutionPolicy.decide(false, smoker);

        assertFalse(decision.useGraphExecutor());
        assertEquals(ModType.GraphExecutionAudit.FLAT_REQUIRED,
                smoker.graphExecutionAudit());
        assertEquals(GraphExecutionPolicy.Reason.MOD_REQUIRES_FLAT_EXECUTION,
                decision.reason());
        assertTrue(decision.detail().contains("one catalyst per eight-material"));
    }

    @Test
    void unauditedTypesFailClosedToFlatExecution() {
        ModType unaudited = ModType.register("test_unaudited_graph_type",
                new String[0], new String[0], new String[0],
                com.huanghuang.rsintegration.crafting.batch.GenericBatchDelegate::new);

        GraphExecutionPolicy.Decision decision = GraphExecutionPolicy.decide(false, unaudited);

        assertFalse(decision.useGraphExecutor());
        assertEquals(GraphExecutionPolicy.Reason.MOD_REQUIRES_FLAT_EXECUTION, decision.reason());
        assertTrue(decision.detail().contains("not audited"));
        unaudited.confirmGraphExecution("test cleanup");
    }

    @Test
    void registryAuditConfirmsOnlyExplicitlyReviewedIds() {
        ModType reviewed = ModType.register("test_reviewed_graph_type",
                new String[0], new String[0], new String[0],
                com.huanghuang.rsintegration.crafting.batch.GenericBatchDelegate::new);
        ModType omitted = ModType.register("test_omitted_graph_type",
                new String[0], new String[0], new String[0],
                com.huanghuang.rsintegration.crafting.batch.GenericBatchDelegate::new);

        ModType.confirmReviewedGraphExecution(List.of(reviewed.id()), "test audit");

        assertTrue(GraphExecutionPolicy.decide(false, reviewed).useGraphExecutor());
        assertFalse(GraphExecutionPolicy.decide(false, omitted).useGraphExecutor());
        omitted.confirmGraphExecution("test cleanup");
    }

    private static CraftPlanGraph graphWithRole(DemandRole role) {
        NodeId nodeId = new NodeId(0);
        MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
        InputDemand input = new InputDemand(new InputPortId(nodeId, 0),
                Ingredient.of(Items.DIAMOND), 1, role, new ItemStack(Items.DIAMOND));
        CraftNode node = new CraftNode(nodeId, new ResourceLocation("test", "role"),
                ModType.GENERIC.id(), null, 1, List.of(), List.of(), false,
                null, null, List.of(input), List.of(), List.of());
        RootDemand root = new RootDemand(Ingredient.of(Items.DIAMOND), 1, 0,
                new ItemStack(Items.DIAMOND), List.of(new RootAllocation(
                new MaterialSource.InitialPool(diamond), diamond, 1)), role);
        return new CraftPlanGraph(1, List.of(node), List.of(), List.of(root),
                List.of(), List.of(nodeId));
    }
}
