package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.mods.embers.EreAlchemyRSModule;
import com.huanghuang.rsintegration.util.ModIds;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphExecutionPolicyTest {

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
}
