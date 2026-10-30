package com.huanghuang.rsintegration.api.recipe;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.execution.OutputPort;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputSemanticsTest {
    @ParameterizedTest
    @CsvSource({"1, 1, true, false", "2, 4, true, true", "0, 4, false, true"})
    void guaranteedRangeIsPlannedOnlyAtItsPositiveLowerBound(
            long minimum, long maximum, boolean projectable, boolean randomized) {
        OutputDeclaration output = output("main", item("main"), new OutputAmount(minimum, maximum),
                OutputKind.PRIMARY, OutputProbability.guaranteed(), false);

        assertEquals(minimum, output.guaranteedMinimum());
        assertEquals(projectable, output.supportsDeterministicProjection());
        assertEquals(randomized, output.hasRandomizedAmount());
        assertEquals(!projectable, output.requiresTargetedProduction());
    }

    @Test
    void mixedRecipeKeepsGuaranteedPrimaryAndRetriesOnlyTheRandomTarget() {
        OutputDeclaration primary = output("main", item("main"), OutputAmount.exactly(4),
                OutputKind.PRIMARY, OutputProbability.guaranteed(), false);
        OutputDeclaration bonus = output("bonus", item("bonus"), OutputAmount.exactly(2),
                OutputKind.SECONDARY, OutputProbability.probabilistic(0.35, 16, "bonus"), false);
        RecipeView view = view(List.of(primary, bonus));

        assertTrue(view.hasRandomizedOutputs());
        assertTrue(OutputSemantics.hasDeterministicPrimary(view.outputs()));
        assertEquals(List.of(primary), view.deterministicOutputs());
        assertEquals(0, bonus.guaranteedMinimum());
        assertFalse(view.requiresTargetedProduction(primary.resource()));
        assertTrue(view.requiresTargetedProduction(bonus.resource()));
        assertFalse(view.requiresTargetedProduction(item("absent")));
    }

    @Test
    void probabilityOneNormalizesToGuaranteedWhileDynamicOneRemainsUnknown() {
        OutputProbability known = OutputProbability.probabilistic(1.0, 16, "known");
        OutputProbability dynamic = OutputProbability.dynamic(1.0, 16, "dynamic");

        assertTrue(known.isGuaranteed());
        assertEquals(1, known.maxAttempts());
        assertFalse(dynamic.isGuaranteed());
        OutputDeclaration output = output("dynamic", item("main"), OutputAmount.exactly(4),
                OutputKind.PRIMARY, dynamic, false);
        assertEquals(0, output.guaranteedMinimum());
        assertFalse(output.supportsDeterministicProjection());
    }

    @Test
    void nonFiniteProbabilityIsRejectedBeforeItReachesInternalProjection() {
        assertThrows(IllegalArgumentException.class,
                () -> OutputProbability.probabilistic(Double.NaN, 16, "invalid"));
        assertThrows(IllegalArgumentException.class,
                () -> OutputProbability.dynamic(Double.POSITIVE_INFINITY, 16, "invalid"));
    }

    @Test
    void guaranteedExtraDoesNotReplaceTheNominalQuantityOfARandomPrimary() {
        IntegrationResourceKey resource = item("main");
        OutputDeclaration primary = output("main", resource, OutputAmount.exactly(4),
                OutputKind.PRIMARY, OutputProbability.probabilistic(0.35, 16, "main"), false);
        OutputDeclaration secondary = output("extra", resource, OutputAmount.exactly(1),
                OutputKind.SECONDARY, OutputProbability.guaranteed(), false);

        assertTrue(OutputSemantics.requiresTargetedProduction(List.of(primary, secondary), resource));
        assertFalse(OutputSemantics.requiresTargetedProduction(List.of(secondary, primary),
                item("absent")));
    }

    @Test
    void guaranteedSecondarySurvivesRandomExtrasOfTheSameResource() {
        IntegrationResourceKey resource = item("bonus");
        OutputDeclaration fixed = output("fixed", resource, OutputAmount.exactly(2),
                OutputKind.SECONDARY, OutputProbability.guaranteed(), false);
        OutputDeclaration random = output("random", resource, OutputAmount.exactly(3),
                OutputKind.SECONDARY, OutputProbability.probabilistic(0.35, 16, "bonus"), false);

        assertFalse(OutputSemantics.requiresTargetedProduction(List.of(random, fixed), resource));
        assertFalse(OutputSemantics.requiresTargetedProduction(List.of(fixed, random), resource));
    }

    @Test
    void targetMatchingKeepsResourceKindAndExactIdentity() {
        CompoundTag rare = new CompoundTag();
        rare.putString("quality", "rare");
        IntegrationResourceKey rareItem = new IntegrationResourceKey(
                IntegrationResourceKey.ResourceKind.ITEM, id("bonus"), rare);
        OutputDeclaration output = output("bonus", rareItem, OutputAmount.exactly(1),
                OutputKind.SECONDARY, OutputProbability.probabilistic(0.35, 16, "rare"), false);
        CompoundTag normal = new CompoundTag();
        normal.putString("quality", "normal");

        assertTrue(output.matchesTarget(item("bonus")));
        assertTrue(output.matchesTarget(rareItem));
        assertFalse(output.matchesTarget(new IntegrationResourceKey(
                IntegrationResourceKey.ResourceKind.ITEM, id("bonus"), normal)));
        assertFalse(output.matchesTarget(new IntegrationResourceKey(
                IntegrationResourceKey.ResourceKind.FLUID, id("bonus"), rare)));
    }

    @Test
    void executionPortsAndDeclarationsExposeTheSameSemantics() {
        OutputDeclaration declaration = output("main", item("main"), new OutputAmount(2, 4),
                OutputKind.PRIMARY, OutputProbability.guaranteed(), true);
        OutputPort port = new OutputPort(declaration.portId(), declaration.outputGroup(),
                declaration.resource(), declaration.amount(), declaration.outputKind(),
                declaration.probability(), declaration.dynamicIdentity(),
                declaration.targetProducing(), declaration.sourceInputEntryId());

        assertEquals(declaration.guaranteedMinimum(), port.guaranteedMinimum());
        assertEquals(declaration.maximumAmount(), port.maximumAmount());
        assertEquals(declaration.hasRandomizedAmount(), port.hasRandomizedAmount());
        assertTrue(port.supportsDeterministicProjection());
        assertTrue(port.requiresTargetedProduction());
    }

    @Test
    void variableAmountCannotClaimAnEntirelyDeterministicRecipe() {
        OutputDeclaration output = output("main", item("main"), new OutputAmount(2, 4),
                OutputKind.PRIMARY, OutputProbability.guaranteed(), false);

        assertThrows(IllegalArgumentException.class, () -> new RecipeView(
                id("recipe"), id("process"), List.of(), List.of(), List.of(output),
                new RecipeFlags(true, false, true, false, false, false, false)));
    }

    private static RecipeView view(List<OutputDeclaration> outputs) {
        return new RecipeView(id("recipe"), id("process"), List.of(), List.of(), outputs,
                new RecipeFlags(true, outputs.stream().anyMatch(OutputDeclaration::targetProducing),
                        !OutputSemantics.hasRandomizedOutputs(outputs), false, false, false, false));
    }

    private static OutputDeclaration output(
            String port, IntegrationResourceKey resource, OutputAmount amount,
            OutputKind kind, OutputProbability probability, boolean targeted) {
        return new OutputDeclaration(id(port), "output", resource, amount, kind,
                probability, false, targeted, null);
    }

    private static IntegrationResourceKey item(String name) {
        return new IntegrationResourceKey(
                IntegrationResourceKey.ResourceKind.ITEM, id(name), new CompoundTag());
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("test", path);
    }
}
