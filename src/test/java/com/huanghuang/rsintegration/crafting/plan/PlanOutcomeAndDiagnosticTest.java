package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanOutcomeAndDiagnosticTest extends BootstrapTest {

    @Test
    void legacyPlanInfersMaterialShortageOutcome() {
        IngredientKey iron = IngredientKey.of(new ItemStack(Items.IRON_INGOT));
        PlanResponse plan = new PlanResponse(false, "target", new ItemStack(Items.DIAMOND),
                List.of(), Map.of(iron, new PlanResponse.Availability(4, 1)),
                List.of(), "test:recipe");

        assertEquals(PlanOutcome.MISSING_INPUTS, plan.outcome());
        assertTrue(plan.diagnostics().isEmpty());
    }

    @Test
    void packetRoundTripPreservesOutcomeAndStructuredDiagnostic() {
        IntegrationDiagnostic diagnostic = IntegrationDiagnostic.error(
                IntegrationDiagnosticCode.COMPLEXITY_LIMIT);
        PlanResponse plan = new PlanResponse(false, "", ItemStack.EMPTY,
                List.of(), Map.of(), List.of(), "", List.of(), 1,
                PlanOutcome.BUDGET_EXHAUSTED, List.of(diagnostic));

        PlanResponse decoded = PlanResponsePacket.decodePayload(
                PlanResponsePacket.encodePayload(plan, 41L)).plan();

        assertEquals(PlanOutcome.BUDGET_EXHAUSTED, decoded.outcome());
        assertEquals(List.of(diagnostic), decoded.diagnostics());
    }

    @Test
    void diagnosticResolvesTranslationOnTheClient() {
        IntegrationDiagnostic diagnostic = IntegrationDiagnostic.error(
                IntegrationDiagnosticCode.GRAPH_TOO_LARGE, "12", "8");

        assertTrue(diagnostic.clientMessage().getContents() instanceof TranslatableContents);
        TranslatableContents contents =
                (TranslatableContents) diagnostic.clientMessage().getContents();
        assertEquals("rsi.plan.failure.graph_too_large", contents.getKey());
        assertEquals(List.of("12", "8"), List.of(contents.getArgs()));
    }
}
